package com.dafeiyu.mobile.data

import java.io.BufferedInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Isolated candidate: private authenticated stdio, not a server or a journal
 * state machine. The host keeps its actual transactionMutex from ticket issue
 * through helper challenges and the result journal CAS. Imported projections
 * and caller-created tickets cannot be passed into this client.
 *
 * Requires the companion runtime candidate's retainLeaseUntilPipesComplete
 * path. The mandatory, application-owned factory launches a fixed, verified
 * helper with exclusiveRuntimeUse=true AND retainLeaseUntilPipesComplete=true.
 * It owns any partial start failure before returning StartedHelper. Slot,
 * pins, cleared launch environment, mounts and tracee verification stay with
 * the host; no nullable guard or positive Boolean bypass is supplied here.
 */
internal class AndroidOfficialDshControlledPort private constructor(
    private val authorizer: AndroidOfficialDshStorageAuthorizer,
    private val binding: TrustedLaunchBinding,
) {
    internal data class StartedHelper(
        val runtime: LinuxRuntimeManager,
        val process: LinuxManagedProcess,
        val actualMounts: List<File>,
        val initialization: JSONObject,
    )

    /** Every callback is mandatory. PRoot's tracer PID is different from the
     * Node tracee PID; requireNodeTracee verifies that actual launch binding,
     * rather than comparing readiness.pid with LinuxManagedProcess.pid. */
    internal class TrustedLaunchBinding(
        val ownership: Ownership,
        val start: suspend () -> StartedHelper,
        val requireCurrentSlot: suspend (StartedHelper) -> Unit,
        val requireNodeTracee: suspend (StartedHelper, Int) -> Unit,
        val startupDeadlineMillis: Long = 20_000,
        val requestDeadlineMillis: Long = 15_000,
        val cleanupDeadlineMillis: Long = 10_000,
    ) {
        init {
            require(startupDeadlineMillis in 1..120_000 && requestDeadlineMillis in 1..120_000 &&
                cleanupDeadlineMillis in 1..120_000)
        }
    }

    /** The host retains this concrete registry. A failed open or cancellation
     * leaves an actual session, startup/IO Jobs and managed process reachable
     * for retryCleanup. It is removed only after verified native/pipe cleanup.
     * Runtime's retained registration separately blocks slot replacement. */
    internal class Ownership {
        private val sessions = mutableSetOf<AndroidOfficialDshControlledPort>()
        internal fun retain(port: AndroidOfficialDshControlledPort) = synchronized(sessions) { sessions.add(port); Unit }
        internal fun release(port: AndroidOfficialDshControlledPort) = synchronized(sessions) { sessions.remove(port); Unit }
        fun retainedSessions(): List<AndroidOfficialDshControlledPort> = synchronized(sessions) { sessions.toList() }
    }

    internal class Failure(val code: String) : Exception(code) {
        init { require(code in SAFE_CODES) }
    }

    private val commandLock = Mutex()
    private val cleanupLock = Mutex()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val frames = Channel<ByteArray>(1)
    private val writerLock = Any()
    private val writers = mutableSetOf<Deferred<Unit>>()
    private val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
    @Volatile private var started: StartedHelper? = null
    @Volatile private var ready = false
    @Volatile private var accepting = true
    @Volatile private var cleaned = false
    private var startup: Deferred<Unit>? = null
    private var stopTask: Deferred<Unit>? = null
    private var outputReader: Job? = null
    private var errorReader: Job? = null
    private var scopeId = ""
    private var nonce = ""
    private var sent = 0L
    private var received = 0L

    suspend fun read(candidate: String): JSONObject = request("read") {
        authorizer.issueRead(candidate) to JSONObject().put("candidate", candidate)
    }

    suspend fun stage(expected: JSONObject): JSONObject = request("stage") {
        val snapshot = JSONObject(expected.toString())
        authorizer.issue("stage", snapshot) to JSONObject().put("expected", snapshot)
    }

    suspend fun select(expected: JSONObject): JSONObject = request("select") {
        val snapshot = JSONObject(expected.toString())
        authorizer.issue("select", snapshot) to JSONObject().put("expected", snapshot)
    }

    private suspend fun request(
        operation: String,
        issue: () -> Pair<AndroidOfficialDshStorageAuthorizer.Ticket, JSONObject>,
    ): JSONObject = commandLock.withLock {
        currentCoroutineContext().ensureActive()
        if (!accepting || !ready) throw Failure("HostUnavailable")
        try {
            withTimeout(binding.requestDeadlineMillis) {
                val (ticket, fields) = withContext(Dispatchers.IO) { issue() }
                checkPipe(ticket.operation == operation && ticket.candidate.matches(CANDIDATE))
                val projection = ticket.projection()
                checkPipe(projection.opt("scopeId") == scopeId)
                if (!approve(ticket)) throw Failure("HostUnavailable")
                val requestId = randomHex(16)
                send(fields.put("requestId", requestId).put("operation", operation).put("authorization", projection))
                val phases = when (operation) {
                    "read" -> listOf("read-locked")
                    "stage" -> {
                        val pending = projection.opt("pending") as? JSONObject ?: throw Failure("HostUnavailable")
                        checkPipe(pending.has("desired"))
                        if (pending.isNull("desired")) listOf("stage-before", "stage-after-write")
                        else listOf("stage-before", "stage-before-write", "stage-after-write")
                    }
                    "select" -> listOf("select-before-write", "select-after-write")
                    else -> throw Failure("HostUnavailable")
                }
                var phaseIndex = 0
                var denied = false
                val challenges = mutableSetOf<String>()
                while (true) {
                    val body = receive()
                    checkPipe(body.opt("requestId") == requestId)
                    when (body.opt("kind")) {
                        "lease-check" -> {
                            checkPipe(body.keysSet() == setOf("kind", "requestId", "phase", "candidate", "challenge"))
                            checkPipe(phaseIndex < phases.size && body.opt("phase") == phases[phaseIndex] &&
                                body.opt("candidate") == ticket.candidate)
                            val challenge = body.opt("challenge") as? String ?: throw Failure("HostUnavailable")
                            checkPipe(challenge.matches(HEX32) && challenges.add(challenge))
                            val allowed = !denied && approve(ticket)
                            denied = denied || !allowed
                            send(JSONObject().put("kind", "lease-approval").put("requestId", requestId)
                                .put("challenge", challenge).put("allowed", allowed))
                            phaseIndex++
                        }
                        "result" -> {
                            if (body.opt("ok") == true) {
                                checkPipe(body.keysSet() == setOf("kind", "requestId", "ok", "value") &&
                                    !denied && phaseIndex == phases.size)
                                val result = body.opt("value") as? JSONObject ?: throw Failure("HostUnavailable")
                                return@withTimeout JSONObject(result.toString())
                            }
                            checkPipe(body.opt("ok") == false &&
                                body.keysSet() == setOf("kind", "requestId", "ok", "code"))
                            val code = body.opt("code") as? String ?: throw Failure("HostUnavailable")
                            throw Failure(if (code in SAFE_CODES) code else "HostUnavailable")
                        }
                        else -> throw Failure("HostUnavailable")
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                throw Failure("HostUnavailable")
            }
        } catch (failure: Throwable) {
            failedOperation(failure)
        }
    }

    /** All three proofs are evaluated for each exact lock-held challenge. An
     * exception or changed journal produces a signed denial, never approval. */
    private suspend fun approve(ticket: AndroidOfficialDshStorageAuthorizer.Ticket): Boolean {
        val helper = started ?: return false
        return try {
            binding.requireCurrentSlot(helper)
            helper.runtime.requireExclusiveManagedLease(helper.process, helper.actualMounts)
            withContext(Dispatchers.IO) { authorizer.approveCurrent(ticket) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    /** Graceful close is authenticated. Regardless of acknowledgement, only
     * actual process exit and completed IO Jobs can release the retained lease. */
    suspend fun close() = commandLock.withLock {
        try {
            if (accepting && ready) withTimeout(binding.requestDeadlineMillis) {
                val requestId = randomHex(16)
                send(JSONObject().put("requestId", requestId).put("operation", "close"))
                val result = receive()
                checkPipe(result.keysSet() == setOf("kind", "requestId", "ok", "value") &&
                    result.opt("kind") == "result" && result.opt("requestId") == requestId &&
                    result.opt("ok") == true && result.opt("value") === JSONObject.NULL)
            }
        } catch (failure: Throwable) { failedOperation(failure) }
        if (!retryCleanup()) throw Failure("StoragePending")
    }

    /** Bounded waiting is separate from the actual stop/read/write jobs. A
     * deadline, cancel(), or closed Channel is never mistaken for completion. */
    suspend fun retryCleanup(): Boolean = withContext(NonCancellable) {
        cleanupLock.withLock {
            if (cleaned) return@withLock true
            synchronized(writerLock) { accepting = false }
            ready = false
            secret.fill(0)
            val complete = withTimeoutOrNull(binding.cleanupDeadlineMillis) {
                // Startup is independently owned; even a late successful
                // factory result is adopted and cleaned before owner release.
                // The application-owned factory must retain/clean a partial
                // launch if cancellation precedes its returned handle.
                startup?.cancel()
                startup?.join()
                val helper = started
                if (helper != null) {
                    var stop = stopTask
                    if (stop == null || stop.isCompleted && stop.isCancelled) {
                        stop = ioScope.async { helper.process.stop() }
                        stopTask = stop
                    }
                    stop.await()
                    helper.process.awaitExit()
                    val activeWriters = synchronized(writerLock) { writers.toList() }
                    activeWriters.forEach { it.cancel() }
                    outputReader?.cancel()
                    errorReader?.cancel()
                    activeWriters.forEach { it.join() }
                    outputReader?.join()
                    errorReader?.join()
                    checkPipe(!helper.process.isAlive && outputReader?.isCompleted == true &&
                        errorReader?.isCompleted == true && synchronized(writerLock) { writers.none { !it.isCompleted } })
                    helper.process.completePipeCleanup()
                }
                frames.close()
                while (true) { frames.tryReceive().getOrNull()?.fill(0) ?: break }
                ioScope.cancel()
                cleaned = true
                binding.ownership.release(this@AndroidOfficialDshControlledPort)
                true
            }
            complete == true
        }
    }

    private suspend fun failedOperation(failure: Throwable): Nothing {
        // Cleanup failure must not forward any raw parser/OS error or cause.
        try { retryCleanup() } catch (_: Exception) { /* actual owner remains */ }
        when (failure) {
            is TimeoutCancellationException -> throw Failure("HostUnavailable")
            is CancellationException -> throw failure
            is Failure -> throw failure
            else -> throw Failure("HostUnavailable")
        }
    }

    private suspend fun start() {
        // Do not lose a successful actual factory return at a cancellable
        // withContext boundary. The exact startup Job remains host-reachable
        // while this bounded-cleanup wait is pending; adoption is atomic with
        // respect to caller cancellation, not an invented finished flag.
        val helper = withContext(NonCancellable) {
            binding.start().also { started = it }
        }
        val process = helper.process
        val outputJob = ioScope.launch(start = CoroutineStart.LAZY) { pumpOutput(process) }
        val errorJob = ioScope.launch(start = CoroutineStart.LAZY) { pumpErrors(process) }
        outputReader = outputJob
        errorReader = errorJob
        withContext(NonCancellable) {
            process.registerPipeReaders(outputJob, errorJob)
            outputJob.start()
            errorJob.start()
        }
        val init = JSONObject(helper.initialization.toString())
        checkPipe(init.keysSet() == setOf("scopeId", "binding", "runtimePins") &&
            init.opt("binding") is JSONObject && init.opt("runtimePins") is JSONArray)
        scopeId = init.opt("scopeId") as? String ?: throw Failure("HostUnavailable")
        checkPipe(scopeId.matches(HEX64) && helper.actualMounts.isNotEmpty())
        binding.requireCurrentSlot(helper)
        helper.runtime.requireExclusiveManagedLease(process, helper.actualMounts)
        try {
            write(PipeJson.encode(init.put("kind", "controlled-init").put("protocol", 1)
                .put("secret", Base64.getEncoder().encodeToString(secret))))
            val response = nextObject()
            checkPipe(response.keysSet() == setOf("kind", "protocol", "scopeId", "nonce", "pid") &&
                response.opt("kind") == "controlled-ready" && response.opt("protocol") == 1L && response.opt("scopeId") == scopeId)
            nonce = response.opt("nonce") as? String ?: throw Failure("HostUnavailable")
            val pid = response.opt("pid") as? Long ?: throw Failure("HostUnavailable")
            checkPipe(nonce.matches(HEX48) && pid in 1..Int.MAX_VALUE)
            binding.requireNodeTracee(helper, pid.toInt())
            binding.requireCurrentSlot(helper)
            helper.runtime.requireExclusiveManagedLease(process, helper.actualMounts)
            checkPipe(accepting)
            ready = true
        } finally { init.remove("secret") }
    }

    private suspend fun pumpOutput(process: LinuxManagedProcess) {
        val buffer = ByteArray(MAX_FRAME)
        var length = 0
        try {
            val input = BufferedInputStream(process.output, 8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val frame = runInterruptible {
                    while (true) {
                        val byte = input.read()
                        if (byte < 0) { checkPipe(length == 0); break }
                        if (byte == 10) break
                        checkPipe(length < MAX_FRAME)
                        buffer[length++] = byte.toByte()
                    }
                    if (length == 0) null else buffer.copyOf(length)
                }
                buffer.fill(0, 0, length)
                length = 0
                if (frame == null) break
                try { frames.send(frame) }
                catch (failure: Throwable) { frame.fill(0); throw failure }
            }
            frames.close()
        } catch (_: Exception) { frames.close(Failure("HostUnavailable")) }
        finally {
            buffer.fill(0)
            runCatching { process.output.close() }
        }
    }

    private suspend fun pumpErrors(process: LinuxManagedProcess) {
        val buffer = ByteArray(8192)
        try {
            while (runInterruptible { process.errors.read(buffer) } >= 0) currentCoroutineContext().ensureActive()
        } catch (_: Exception) { frames.close(Failure("HostUnavailable")) }
        finally {
            buffer.fill(0)
            runCatching { process.errors.close() }
        }
    }

    private suspend fun write(frame: ByteArray) {
        checkPipe(frame.size <= MAX_FRAME)
        val process = started?.process ?: throw Failure("HostUnavailable")
        val writer = ioScope.async(start = CoroutineStart.LAZY) {
            try {
                runInterruptible {
                    process.input.write(frame)
                    process.input.write(10)
                    process.input.flush()
                }
            } finally { frame.fill(0) }
        }
        synchronized(writerLock) {
            // Cleanup's accepting=false and its writer snapshot share this
            // lock. No late writer may register after cleanup has begun.
            if (!accepting) {
                frame.fill(0)
                writer.cancel()
                throw Failure("HostUnavailable")
            }
            writers.add(writer)
        }
        writer.invokeOnCompletion { synchronized(writerLock) { writers.remove(writer) } }
        writer.start()
        writer.await()
    }

    private suspend fun send(value: JSONObject) {
        checkPipe(sent < MAX_SEQUENCE && nonce.matches(HEX48))
        val bytes = PipeJson.encode(value)
        val body = try { Base64.getEncoder().encodeToString(bytes) } finally { bytes.fill(0) }
        checkPipe(body.length <= MAX_FRAME)
        val sequence = ++sent
        write(PipeJson.encode(JSONObject().put("sequence", sequence).put("body", body).put("mac", signature(sequence, body))))
    }

    private suspend fun receive(): JSONObject {
        val envelope = nextObject()
        checkPipe(envelope.keysSet() == setOf("sequence", "body", "mac") && received < MAX_SEQUENCE)
        val sequence = envelope.opt("sequence") as? Long ?: throw Failure("HostUnavailable")
        val body = envelope.opt("body") as? String ?: throw Failure("HostUnavailable")
        val mac = envelope.opt("mac") as? String ?: throw Failure("HostUnavailable")
        checkPipe(sequence == received + 1 && body.length <= MAX_FRAME && mac.matches(HEX64))
        val actual = hexBytes(mac)
        val wanted = hexBytes(signature(sequence, body))
        try { checkPipe(MessageDigest.isEqual(actual, wanted)) }
        finally { actual.fill(0); wanted.fill(0) }
        val bytes = try { Base64.getDecoder().decode(body) } catch (_: Exception) { throw Failure("HostUnavailable") }
        return try {
            checkPipe(Base64.getEncoder().encodeToString(bytes) == body)
            val value = PipeJson.decode(bytes)
            received = sequence
            value
        } finally { bytes.fill(0) }
    }

    private suspend fun nextObject(): JSONObject {
        val bytes = frames.receive()
        return try { PipeJson.decode(bytes) } finally { bytes.fill(0) }
    }

    private fun signature(sequence: Long, body: String): String {
        val input = "$nonce\n$sequence\n$body".toByteArray(Charsets.UTF_8)
        val output = try { Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(secret, "HmacSHA256")); doFinal(input) } }
        finally { input.fill(0) }
        return try { output.hex() } finally { output.fill(0) }
    }

    companion object {
        internal const val MAX_FRAME = 4 * 1024 * 1024
        private const val MAX_SEQUENCE = 9_007_199_254_740_991L
        private val HEX32 = Regex("[a-f0-9]{32}")
        private val HEX48 = Regex("[a-f0-9]{48}")
        private val HEX64 = Regex("[a-f0-9]{64}")
        private val CANDIDATE = Regex("DAFEIYU_PROVIDER_DEEPSEEK_[a-f0-9]{32}")
        private val SAFE_CODES = setOf("InvalidIntent", "InvalidJournal", "StoragePending", "JournalConflict", "NativeConflict",
            "DshConflict", "CandidateConflict", "HostUnavailable", "ReferenceBound", "PendingExists", "NotConfirmed", "Cancelled",
            "UnsupportedProfile", "UnsupportedLaunch")

        suspend fun open(authorizer: AndroidOfficialDshStorageAuthorizer, binding: TrustedLaunchBinding?): AndroidOfficialDshControlledPort {
            if (binding == null) throw Failure("HostUnavailable")
            val port = AndroidOfficialDshControlledPort(authorizer, binding)
            // This independent real Job is retained even if the caller times
            // out before the factory returns an adopted managed process.
            val start = port.ioScope.async(start = CoroutineStart.LAZY) { port.start() }
            port.startup = start
            // Publish only after the actual startup Job has been registered.
            // Concurrent host cleanup can now cancel/join that exact Job.
            binding.ownership.retain(port)
            start.start()
            try { withTimeout(binding.startupDeadlineMillis) { start.await() }; return port }
            catch (failure: Throwable) { port.failedOperation(failure) }
        }

        private fun checkPipe(value: Boolean) { if (!value) throw Failure("HostUnavailable") }
        private fun JSONObject.keysSet(): Set<String> = keys().asSequence().toSet()
        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
        private fun hexBytes(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        private fun randomHex(size: Int): String = ByteArray(size).also { SecureRandom().nextBytes(it) }.let { value ->
            try { value.hex() } finally { value.fill(0) }
        }
    }
}

/** org.json is deliberately lenient on Android. Validate strict JSON before
 * admitting a frame: duplicate names, extensions, trailing input, excessive
 * nesting, non-finite numbers and unpaired Unicode surrogates fail closed. */
internal object PipeJson {
    fun encode(value: JSONObject): ByteArray {
        val text = value.toString()
        Parser(text).read()
        return text.toByteArray(Charsets.UTF_8).also { checkFrame(it.size <= AndroidOfficialDshControlledPort.MAX_FRAME) }
    }

    fun decode(bytes: ByteArray): JSONObject {
        checkFrame(bytes.size in 1..AndroidOfficialDshControlledPort.MAX_FRAME)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return Parser(text).read()
    }

    private fun checkFrame(value: Boolean) { if (!value) throw AndroidOfficialDshControlledPort.Failure("HostUnavailable") }

    private class Parser(private val text: String) {
        private var at = 0
        private var nodes = 0
        fun read(): JSONObject {
            val value = value(0)
            whitespace()
            checkFrame(at == text.length && value is JSONObject)
            return value as JSONObject
        }
        private fun whitespace() { while (at < text.length && text[at] in " \t\r\n") at++ }
        private fun take(char: Char): Boolean = if (at < text.length && text[at] == char) { at++; true } else false
        private fun value(depth: Int): Any {
            checkFrame(depth <= 48 && ++nodes <= 65_536)
            whitespace()
            checkFrame(at < text.length)
            return when (text[at]) {
                '{' -> {
                    at++
                    val result = JSONObject()
                    val names = mutableSetOf<String>()
                    whitespace()
                    if (!take('}')) while (true) {
                        whitespace()
                        val name = string()
                        checkFrame(names.add(name))
                        whitespace()
                        checkFrame(take(':'))
                        result.put(name, value(depth + 1))
                        whitespace()
                        if (take('}')) break
                        checkFrame(take(','))
                    }
                    result
                }
                '[' -> {
                    at++
                    val result = JSONArray()
                    whitespace()
                    if (!take(']')) while (true) {
                        result.put(value(depth + 1))
                        whitespace()
                        if (take(']')) break
                        checkFrame(take(','))
                    }
                    result
                }
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", JSONObject.NULL)
                else -> number()
            }
        }
        private fun literal(word: String, value: Any): Any {
            checkFrame(text.startsWith(word, at))
            at += word.length
            return value
        }
        private fun string(): String {
            checkFrame(take('"'))
            val value = StringBuilder()
            while (at < text.length) {
                var char = text[at++]
                if (char == '"') {
                    for (index in value.indices) {
                        if (value[index].isHighSurrogate()) checkFrame(index + 1 < value.length && value[index + 1].isLowSurrogate())
                        if (value[index].isLowSurrogate()) checkFrame(index > 0 && value[index - 1].isHighSurrogate())
                    }
                    return value.toString()
                }
                checkFrame(char.code >= 32)
                if (char == '\\') {
                    checkFrame(at < text.length)
                    char = when (val escaped = text[at++]) {
                        '"', '\\', '/' -> escaped
                        'b' -> '\b'
                        'f' -> '\u000c'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'u' -> {
                            checkFrame(at + 4 <= text.length)
                            val hex = text.substring(at, at + 4)
                            checkFrame(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
                            at += 4
                            hex.toInt(16).toChar()
                        }
                        else -> throw AndroidOfficialDshControlledPort.Failure("HostUnavailable")
                    }
                }
                value.append(char)
            }
            throw AndroidOfficialDshControlledPort.Failure("HostUnavailable")
        }
        private fun number(): Number {
            val start = at
            take('-')
            if (!take('0')) {
                checkFrame(at < text.length && text[at] in '1'..'9')
                while (at < text.length && text[at] in '0'..'9') at++
            }
            var integral = true
            if (take('.')) {
                integral = false
                val digits = at
                while (at < text.length && text[at] in '0'..'9') at++
                checkFrame(at > digits)
            }
            if (take('e') || take('E')) {
                integral = false
                if (!take('+')) take('-')
                val digits = at
                while (at < text.length && text[at] in '0'..'9') at++
                checkFrame(at > digits)
            }
            val number = text.substring(start, at)
            return if (integral) number.toLongOrNull() ?: throw AndroidOfficialDshControlledPort.Failure("HostUnavailable")
            else (number.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw AndroidOfficialDshControlledPort.Failure("HostUnavailable"))
        }
    }
}
