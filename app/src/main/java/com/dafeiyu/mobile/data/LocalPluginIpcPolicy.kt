package com.dafeiyu.mobile.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.json.JSONTokener

internal object LocalPluginIpcPolicy {
    const val PROTOCOL = "dafeiyu-go.connection.v1"
    const val MAX_REQUEST_BYTES = 1024 * 1024
    const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    const val MAX_CLIENTS = 4
    const val DEADLINE_MILLIS = 60_000L
    const val MAX_NONCES = 65_536
    private const val MAX_REQUEST_ID_LENGTH = 128
    private val noncePattern = Regex("[A-Za-z0-9_-]{24,128}")

    class Failure(val code: String, val requestId: String = "") : Exception(code)

    data class Request(val id: String, val method: String, val params: JSONObject)
    data class RuntimeIdentity(val pid: Int, val parentPid: Int, val uid: Int)

    fun runtimeIdentity(status: String): RuntimeIdentity {
        fun field(name: String): List<String> {
            val lines = status.lineSequence().filter { it.startsWith("$name:") }.toList()
            require(lines.size == 1) { "invalid_runtime_identity" }
            return lines.single().substringAfter(':').trim().split(Regex("\\s+"))
        }
        fun pid(name: String): Int {
            val values = field(name)
            require(values.size == 1) { "invalid_runtime_identity" }
            return values.single().toIntOrNull()?.takeIf { it > 0 }
                ?: throw IllegalArgumentException("invalid_runtime_identity")
        }
        val uids = field("Uid").map { it.toIntOrNull() }
        require(uids.size == 4 && uids.all { it != null && it >= 0 && it == uids.first() }) {
            "invalid_runtime_identity"
        }
        return RuntimeIdentity(pid("Pid"), pid("PPid"), requireNotNull(uids.first()))
    }

    class Session(
        private val appUid: Int,
        private val hostPid: Int,
        private val generation: String,
        capability: String,
    ) : AutoCloseable {
        private val capabilityBytes = capability.toByteArray(Charsets.UTF_8)
        private val nonces = HashSet<String>()
        private val slots = Semaphore(MAX_CLIENTS)
        private var runtimePid: Int? = null
        private var closed = false

        init {
            require(appUid >= 0 && hostPid > 0)
            require(generation.matches(Regex("[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")))
            val decoded = runCatching { Base64.getUrlDecoder().decode(capability) }.getOrNull()
            require(decoded?.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == capability)
        }

        @Synchronized
        fun bindRuntime(identity: RuntimeIdentity) {
            require(!closed && runtimePid == null) { "runtime_already_bound_or_closed" }
            require(identity.pid > 0 && identity.pid != hostPid &&
                identity.parentPid == hostPid && identity.uid == appUid) { "invalid_runtime_identity" }
            runtimePid = identity.pid
        }

        @Synchronized
        fun acceptsPeer(uid: Int, pid: Int): Boolean =
            !closed && uid == appUid && runtimePid != null && pid == runtimePid

        @Synchronized
        fun acquirePeer(uid: Int, pid: Int): Permit? {
            if (!acceptsPeer(uid, pid) || !slots.tryAcquire()) return null
            return Permit(slots)
        }

        @Synchronized
        fun validate(request: JSONObject): Request {
            val id = request.opt("request_id") as? String
            if (id == null || id.isEmpty() || id.length > MAX_REQUEST_ID_LENGTH || id.any(Char::isISOControl)) {
                throw Failure("protocol_error")
            }
            if (request.opt("protocol") != PROTOCOL) throw Failure("protocol_error", id)
            if (closed || runtimePid == null || request.opt("generation") != generation) {
                throw Failure("permission_denied", id)
            }
            val suppliedCapability = request.opt("capability") as? String
            if (suppliedCapability == null ||
                !MessageDigest.isEqual(suppliedCapability.toByteArray(Charsets.UTF_8), capabilityBytes)) {
                throw Failure("permission_denied", id)
            }
            val nonce = request.opt("nonce") as? String
            if (nonce == null || !noncePattern.matches(nonce)) throw Failure("permission_denied", id)
            val method = request.opt("method") as? String
            val params = request.opt("params") as? JSONObject
            if (method == null || method.isEmpty() || method.any(Char::isISOControl) || params == null) {
                throw Failure("protocol_error", id)
            }
            // Never evict an accepted nonce while this capability/generation remains valid.
            if (nonces.size >= MAX_NONCES || !nonces.add(nonce)) throw Failure("permission_denied", id)
            return Request(id, method, params)
        }

        @Synchronized
        override fun close() {
            closed = true
            runtimePid = null
            nonces.clear()
            capabilityBytes.fill(0)
        }
    }

    class Permit internal constructor(private val slots: Semaphore) : AutoCloseable {
        private val released = AtomicBoolean(false)
        override fun close() {
            if (released.compareAndSet(false, true)) slots.release()
        }
    }

    fun readRequest(input: InputStream): JSONObject? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) {
                if (bytes.size() == 0) return null
                throw Failure("protocol_error")
            }
            // The shared Node transport includes the final LF in its byte limit.
            if (bytes.size() + 1 > MAX_REQUEST_BYTES) throw Failure("protocol_error")
            if (value == 10) break
            bytes.write(value)
        }
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
            val tokens = JSONTokener(text)
            val value = tokens.nextValue()
            if (value !is JSONObject || tokens.nextClean() != '\u0000') throw Failure("protocol_error")
            value
        } catch (_: Exception) {
            throw Failure("protocol_error")
        }
    }

    fun error(id: String, code: String): JSONObject =
        JSONObject().put("success", false).put("request_id", id).put("error_code", code)
            .put("message", code).put("retryable", code == "request_timeout" || code == "connection_unavailable")

    fun responseBytes(response: JSONObject, id: String): ByteArray {
        val bytes = (response.toString() + "\n").toByteArray(Charsets.UTF_8)
        return if (bytes.size <= MAX_RESPONSE_BYTES) bytes
        else (error(id, "protocol_error").toString() + "\n").toByteArray(Charsets.UTF_8)
    }
}
