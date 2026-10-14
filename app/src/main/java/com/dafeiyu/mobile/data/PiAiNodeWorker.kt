package com.dafeiyu.mobile.data

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Every production instance binds to the same canonical journal root. */
internal interface ModelRuntimeRecoveryAccess {
    suspend fun <T> withNativeReady(operation: suspend () -> T): T
    /** Callback admission fails fast so recovery cannot hold up process stop/cancel. */
    suspend fun <T> tryWithNativeReady(operation: suspend () -> T): T
}

internal class JournalModelRuntimeRecoveryAccess(private val journal: OfficialCredentialJournalPort) : ModelRuntimeRecoveryAccess {
    private val recovery = OfficialCredentialRecoveryGate(journal)
    override suspend fun <T> withNativeReady(operation: suspend () -> T): T {
        var admitted = false
        try {
            return recovery.withNativeReady { admitted = true; operation() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (admitted) throw failure
            throw ModelRuntimeException("CREDENTIAL_RECOVERY_REQUIRED", "凭据恢复尚未完成或记录损坏，请先在设置中恢复后重试")
        }
    }
    override suspend fun <T> tryWithNativeReady(operation: suspend () -> T): T {
        check(journal.transactionMutex.tryLock()) { "凭据恢复正在进行，请完成恢复后重新认证" }
        var admitted = false
        try {
            check(!recovery.hasPending()) { "PENDING_EXISTS" }
            admitted = true
            return operation()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (admitted) throw failure
            throw ModelRuntimeException("CREDENTIAL_RECOVERY_REQUIRED", "凭据恢复尚未完成或记录损坏，请先在设置中恢复后重试")
        } finally { journal.transactionMutex.unlock() }
    }
}

/** A single controlled Node process multiplexes up to four requests. Tool calls are returned as data. */
class PiAiNodeWorker internal constructor(
    private val launchProcess: suspend (File) -> LinuxManagedProcess,
    private val providerConfiguration: suspend () -> JSONObject,
    private val saveCredential: suspend (String, JSONObject?, Long) -> Long,
    private val recovery: ModelRuntimeRecoveryAccess,
) {
    constructor(
        context: Context,
        providers: ModelProviderStore,
        runtime: LinuxRuntimeManager = LinuxRuntimeManager(context),
    ) : this(
        launchProcess = { directory -> launchAndroid(context.applicationContext, runtime, directory) },
        providerConfiguration = { withContext(Dispatchers.IO) { providers.workerConfiguration() } },
        saveCredential = { provider, credential, revision ->
            withContext(Dispatchers.IO) { providers.persistWorkerCredential(provider, credential, revision) }
        },
        recovery = JournalModelRuntimeRecoveryAccess(AndroidOfficialCredentialJournal(context.applicationContext)),
    )

    /** All mutable protocol state belongs to one exact process lifetime, including request IDs. */
    private class PendingRequest(val frames: Channel<JSONObject>, val type: String, val providerId: String?, val owner: Job)

    private class Generation(val process: LinuxManagedProcess) {
        val ready = CompletableDeferred<Unit>()
        val pending = ConcurrentHashMap<String, PendingRequest>()
        val receipts = mutableSetOf<String>()
        val writes = Mutex()
        val credentials = Mutex()
        val configuration = Mutex()
        @Volatile var revision = -1L
        @Volatile var configuredRevision = -1L
        @Volatile var configuring = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycle = Mutex()
    private val current = AtomicReference<Generation?>()

    suspend fun start(componentDirectory: File) = lifecycle.withLock {
        val previous = current.get()
        if (previous?.process?.isAlive == true) return@withLock
        if (previous != null) retire(previous, IOException("模型运行器已退出"))
        val launched = launchProcess(componentDirectory)
        val generation = Generation(launched)
        current.set(generation)
        scope.launch { read(generation) }
        // Never persist provider stderr: SDK errors may echo authentication material.
        scope.launch { runCatching { launched.errors.use { errors -> val buffer = ByteArray(4096); while (errors.read(buffer) >= 0) buffer.fill(0) } } }
        scope.launch {
            val exit = launched.awaitExit()
            retire(generation, IOException("模型运行进程退出（$exit）；请重试或修复组件"))
        }
        try { withTimeout(15_000) { generation.ready.await() }; ensureCurrent(generation) }
        catch (failure: Throwable) {
            withContext(NonCancellable) { runCatching { launched.stop() } }
            retire(generation, failure)
            throw failure
        }
    }

    suspend fun configure() { configuredGeneration() }

    private suspend fun configuredGeneration(): Generation {
        val generation = current.get() ?: throw IOException("模型运行组件未启动")
        generation.configuration.withLock {
            repeat(4) {
                // configuration -> credentials -> journal -> vault. The
                // journal/credentials locks never enclose a pipe/RPC wait.
                val configuration = generation.credentials.withLock {
                    recovery.withNativeReady {
                        ensureCurrent(generation)
                        JSONObject(providerConfiguration().toString()).also { snapshot ->
                            val revision = snapshot.getLong("revision")
                            require(revision >= 0)
                            // DeepSeek's dedicated native Key never enters the
                            // Node authentication store or callback protocol.
                            snapshot.getJSONObject("credentials").remove("deepseek")
                            if (generation.configuredRevision != revision || generation.revision != revision) {
                                check(generation.pending.isEmpty()) { "模型任务正在运行，无法切换配置" }
                                generation.revision = revision
                                generation.configuredRevision = -1L
                                generation.configuring = true
                            }
                        }
                    }
                }
                val revision = configuration.getLong("revision")
                if (!generation.configuring) return generation
                try { call(generation, "configure", configuration) {} }
                finally { generation.credentials.withLock { generation.configuring = false } }
                val accepted = generation.credentials.withLock {
                    recovery.withNativeReady {
                        ensureCurrent(generation)
                        val actualRevision = providerConfiguration().getLong("revision")
                        val matches = actualRevision == revision && generation.revision == revision
                        generation.configuredRevision = if (matches) revision else -1L
                        matches
                    }
                }
                if (accepted) return generation
            }
            throw IOException("提供商配置连续变化，请稍后重新读取目录")
        }
    }

    suspend fun catalog(allBuiltins: Boolean = false): JSONObject {
        val generation = configuredGeneration()
        return call(generation, "catalog", JSONObject().put("allBuiltins", allBuiltins)) {}
    }

    suspend fun stream(
        providerId: String,
        modelId: String,
        context: JSONObject,
        reasoning: String? = null,
        requestId: String = UUID.randomUUID().toString(),
        onEvent: suspend (JSONObject) -> Unit,
    ): JSONObject {
        require(providerId != "deepseek") { "DeepSeek 官方使用原生直接接入" }
        val generation = configuredGeneration()
        return call(generation, "stream", JSONObject().put("provider", providerId).put("model", modelId).put("context", context)
            .also { if (reasoning != null) it.put("reasoning", reasoning) }, requestId, onEvent)
    }

    suspend fun login(
        providerId: String,
        authType: String,
        onPrompt: suspend (JSONObject) -> String,
        onEvent: suspend (JSONObject) -> Unit = {},
    ): JSONObject = coroutineScope {
        require(providerId != "deepseek") { "DeepSeek 官方的 API Key 使用专用入口管理" }
        val generation = configuredGeneration()
        val prompts = ConcurrentHashMap<String, Job>()
        try {
            call(generation, "login", JSONObject().put("provider", providerId).put("authType", authType)) { frame ->
                when (frame.optString("type")) {
                    "auth_prompt" -> {
                        val promptId = frame.getString("promptId")
                        require(promptId.length <= 120 && prompts.size < 8)
                        val job = launch(start = CoroutineStart.LAZY) {
                            val response = onPrompt(frame.getJSONObject("prompt"))
                            write(generation, JSONObject().put("type", "auth_reply").put("id", UUID.randomUUID().toString())
                                .put("promptId", promptId).put("value", response))
                        }
                        check(prompts.putIfAbsent(promptId, job) == null) { "认证提示 ID 重复" }
                        job.invokeOnCompletion { prompts.remove(promptId, job) }
                        job.start()
                    }
                    "auth_prompt_cancel" -> prompts.remove(frame.getString("promptId"))?.cancelAndJoin()
                    "auth_event" -> onEvent(frame.getJSONObject("event"))
                }
            }
        } finally {
            withContext(NonCancellable) { prompts.values.toList().forEach { it.cancelAndJoin() } }
        }
    }

    suspend fun logout(providerId: String): JSONObject {
        require(providerId != "deepseek") { "DeepSeek 官方的 API Key 使用专用入口管理" }
        val generation = configuredGeneration()
        return call(generation, "logout", JSONObject().put("provider", providerId)) {}
    }

    suspend fun cancel(requestId: String) {
        val generation = current.get() ?: return
        if (generation.pending.containsKey(requestId)) cancel(generation, requestId)
    }

    private suspend fun cancel(generation: Generation, requestId: String) = write(generation,
        JSONObject().put("type", "cancel").put("id", UUID.randomUUID().toString()).put("target", requestId))

    suspend fun stop() = lifecycle.withLock {
        val generation = current.get() ?: return@withLock
        withContext(NonCancellable + Dispatchers.IO) { generation.process.stop() }
        retire(generation, CancellationException("模型运行器已停止"))
    }

    private suspend fun call(
        generation: Generation,
        type: String,
        arguments: JSONObject,
        onEvent: suspend (JSONObject) -> Unit,
    ): JSONObject = call(generation, type, arguments, UUID.randomUUID().toString(), onEvent)

    private suspend fun call(generation: Generation, type: String, arguments: JSONObject, id: String, onEvent: suspend (JSONObject) -> Unit): JSONObject {
        require(id.isNotBlank() && id.length <= 120)
        val frames = Channel<JSONObject>(64)
        val owner = currentCoroutineContext()[Job] ?: error("模型请求需要可取消的协程")
        val request = PendingRequest(frames, type, arguments.optString("provider").takeIf { it.isNotBlank() }, owner)
        generation.credentials.withLock {
            recovery.withNativeReady {
                currentCoroutineContext().ensureActive()
                ensureCurrent(generation)
                val actualRevision = providerConfiguration().getLong("revision")
                val expectedRevision = if (type == "configure") arguments.getLong("revision") else generation.configuredRevision
                check(actualRevision == expectedRevision && generation.revision == expectedRevision) { "提供商配置已变化，请重新读取目录" }
                check(type == "configure" || !generation.configuring) { "模型配置尚未载入" }
                check(generation.pending.size < 4)
                check(generation.pending.putIfAbsent(id, request) == null) { "模型请求 ID 重复" }
            }
        }
        var terminal = false
        try {
            write(generation, JSONObject(arguments.toString()).put("type", type).put("id", id))
            return withTimeout(if (type == "login") 600_000L else 720_000L) {
                var expected = 0L
                for (frame in frames) {
                    when (frame.getString("type")) {
                        "result" -> { terminal = true; return@withTimeout frame.optJSONObject("value") ?: JSONObject() }
                        "error" -> {
                            terminal = true
                            throw IOException("${frame.optJSONObject("error")?.optString("code", "PROVIDER_FAILED")}: 模型请求失败，请检查提供商配置或认证")
                        }
                        "event" -> {
                            require(frame.getLong("sequence") == expected++) { "模型流事件顺序无效" }
                            onEvent(frame.getJSONObject("event"))
                        }
                        "auth_prompt", "auth_prompt_cancel", "auth_event" -> onEvent(frame)
                    }
                }
                throw IOException("模型运行器中断，响应未完成")
            }
        } finally {
            generation.pending.remove(id, request)
            frames.close()
            if (!terminal && current.get() === generation && generation.process.isAlive) withContext(NonCancellable) {
                runCatching { withTimeout(1000) { cancel(generation, id) } }
            }
        }
    }

    private suspend fun write(generation: Generation, frame: JSONObject) = generation.writes.withLock {
        ensureCurrent(generation)
        val active = generation.process
        frame.put("protocol", PROTOCOL)
        val bytes = (frame.toString() + "\n").toByteArray()
        require(bytes.size <= MAX_FRAME)
        val writeTask = scope.async(Dispatchers.IO) {
            try { active.input.write(bytes); active.input.flush() }
            finally { bytes.fill(0) }
        }
        try { withTimeout(10_000) { writeTask.await() } }
        catch (failure: Throwable) {
            withContext(NonCancellable) { runCatching { active.stop() } }
            writeTask.cancel()
            retire(generation, failure)
            throw failure
        }
    }

    private suspend fun read(generation: Generation) {
        val active = generation.process
        try {
            val input = active.output.buffered()
            while (active.isAlive || input.available() > 0) {
                val bytes = ByteArrayOutputStream()
                while (true) {
                    val value = input.read()
                    if (value < 0) { if (bytes.size() == 0) return else throw IOException("模型消息未完成") }
                    if (value == '\n'.code) break
                    require(bytes.size() < MAX_FRAME) { "模型消息超过上限" }
                    bytes.write(value)
                }
                val frame = JSONObject(bytes.toString("UTF-8"))
                require(frame.getString("protocol") == PROTOCOL)
                when (frame.getString("type")) {
                    "ready" -> {
                        require(frame.getString("version") == "0.87.1")
                        generation.ready.complete(Unit)
                    }
                    "fatal" -> throw IOException("模型运行组件无法载入或协议无效")
                    "credential_write" -> persistCredential(generation, frame)
                    else -> frame.optString("id").takeIf { it.isNotBlank() }?.let { id ->
                        val target = generation.pending[id]
                        if (target != null && target.frames.trySend(frame).isFailure) throw IOException("模型事件过多，已停止运行器以保护内存")
                    }
                }
            }
        } catch (failure: Throwable) {
            retire(generation, failure)
            withContext(NonCancellable) { runCatching { active.stop() } }
        }
    }

    private suspend fun persistCredential(generation: Generation, frame: JSONObject) = lifecycle.withLock {
        // Serialize a durable write with process replacement. A stale receipt never
        // writes current credentials or acknowledges to a different process.
        if (current.get() !== generation) return@withLock
        val receiptId = frame.getString("receiptId")
        require(receiptId.length in 1..120 && receiptId.none(Char::isISOControl))
        val success = if (!generation.credentials.tryLock()) false else {
            try {
                check(generation.receipts.size < 4096 && generation.receipts.add(receiptId)) { "认证回执重复或过多" }
                val providerId = frame.getString("provider")
                require(providerId != "deepseek") { "DeepSeek 官方的 API Key 使用专用入口管理" }
                check(credentialCallerActive(generation, providerId)) { "认证请求已取消或不属于当前提供商" }
                recovery.tryWithNativeReady {
                    ensureCurrent(generation)
                    // Revalidate the live owner after journal admission. The
                    // callback uses tryLock and never queues behind recovery.
                    check(credentialCallerActive(generation, providerId)) { "认证请求已取消" }
                    val snapshot = providerConfiguration()
                    check(snapshot.getLong("revision") == generation.revision && generation.configuredRevision == generation.revision) {
                        "提供商配置已变化，拒绝旧认证回调"
                    }
                    val providers = snapshot.getJSONArray("providers")
                    check((0 until providers.length()).any { index -> providers.getJSONObject(index).let {
                        it.getString("id") == providerId && it.optBoolean("enabled", true)
                    } }) { "所选提供商已删除或停用" }
                    val credential = if (frame.isNull("credential")) null else frame.getJSONObject("credential")
                    val revision = saveCredential(providerId, credential, generation.revision)
                    check(revision > generation.revision) { "凭据持久化没有产生新版本" }
                    generation.revision = revision
                    generation.configuredRevision = revision
                }
                true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            finally { generation.credentials.unlock() }
        }
        // lifecycle remains held so the exact process gets its own receipt;
        // credentials/journal are released before any backpressured IO.
        write(generation, JSONObject().put("type", "credential_ack").put("id", UUID.randomUUID().toString())
            .put("receiptId", receiptId).put("ok", success))
    }

    private fun credentialCallerActive(generation: Generation, providerId: String): Boolean =
        !generation.configuring && generation.pending.values.any { request ->
            request.owner.isActive && (request.type == "catalog" ||
                request.type in setOf("login", "logout", "stream") && request.providerId == providerId)
        }

    private fun ensureCurrent(generation: Generation) {
        if (current.get() !== generation || !generation.process.isAlive) throw IOException("模型运行器已退出或切换到新进程")
    }

    private fun retire(generation: Generation, failure: Throwable) {
        current.compareAndSet(generation, null)
        generation.ready.completeExceptionally(failure)
        generation.pending.values.forEach { it.frames.close(failure) }
    }

    companion object {
        const val PROTOCOL = "dafeiyu-go.model-worker.v1"
        private const val MAX_FRAME = 4 * 1024 * 1024

        internal suspend fun launchAndroid(app: Context, runtime: LinuxRuntimeManager, componentDirectory: File): LinuxManagedProcess {
            require(componentDirectory.isDirectory && !LinuxArchiveExtractor.isSymlink(componentDirectory)) { "模型运行组件尚未安装" }
            val privateRoot = File(app.filesDir, "model-runtime")
            require(componentDirectory.canonicalPath.startsWith(privateRoot.canonicalPath + File.separator)) { "模型运行组件路径无效" }
            val workerRoot = File(app.noBackupFilesDir, "model-worker").apply { require(isDirectory || mkdirs()) }
            require(!LinuxArchiveExtractor.isSymlink(workerRoot))
            val script = File(workerRoot, "worker.mjs")
            require(!LinuxArchiveExtractor.isSymlink(script))
            app.assets.open("model-worker/worker.mjs").use { source -> script.outputStream().use { output -> source.copyTo(output) } }
            return runtime.startManagedProcess("model-worker", listOf("/usr/local/bin/node", "/dafeiyu-model-host/worker.mjs", "/dafeiyu-model-component"),
                mapOf("/dafeiyu-model-host" to workerRoot, "/dafeiyu-model-component" to componentDirectory), workerRoot)
        }
    }
}
