package com.dafeiyu.mobile.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal object LocalPluginIpcPolicy {
    const val PROTOCOL = "dafeiyu-go.connection.v1"
    const val MAX_REQUEST_BYTES = 1024 * 1024
    const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    // Eight data requests retain bounded capacity for the shared ingress and ACKs.
    const val MAX_CLIENTS = 16
    const val DEADLINE_MILLIS = 60_000L
    const val MAX_NONCES = 65_536
    private const val MAX_REQUEST_ID_LENGTH = 128
    private val noncePattern = Regex("[A-Za-z0-9_-]{24,128}")

    class Failure(val code: String, val requestId: String = "") : Exception(code)

    data class Request(val id: String, val method: String, val params: JSONObject, val remoteScope: JSONObject? = null)
    data class RuntimeIdentity(val pid: Int, val parentPid: Int, val uid: Int)

    /** In-memory app admissions never cross stdin or IPC. A claimed JSON scope
     * alone grants no authority to bypass the ordinary Provider serialization. */
    class NativeAdmissions(private val clock: () -> Long = System::nanoTime) : AutoCloseable {
        private data class Admission(val fingerprint: String, val scope: JSONObject, val expiresAt: Long)
        private val entries = LinkedHashMap<String, Admission>()
        private var closed = false

        @Synchronized
        fun register(method: String, params: JSONObject, scope: JSONObject): String? {
            check(!closed) { "bridge_closed" }
            if (method != "send_encrypted_message" || params.optString("kind") != "dsh_remote_request" ||
                params.optJSONObject("body")?.optString("operation") !in NATIVE_BULK_OPERATIONS) return null
            discardExpired()
            check(entries.size < MAX_CLIENTS) { "native_admission_full" }
            val detachedScope = JSONObject(scope.toString())
            val token = UUID.randomUUID().toString()
            entries[token] = Admission(fingerprint(method, params, detachedScope), detachedScope,
                clock() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MILLIS))
            return token
        }

        /** Consume only after the Bridge has rechecked the actual UID/PID/lease. */
        @Synchronized
        fun consume(method: String, params: JSONObject, scope: JSONObject?): JSONObject? {
            if (closed || scope == null) return null
            discardExpired()
            if (entries.isEmpty()) return null
            val hash = fingerprint(method, params, scope)
            val entry = entries.entries.firstOrNull { it.value.fingerprint == hash } ?: return null
            entries.remove(entry.key)
            return JSONObject(entry.value.scope.toString())
        }

        @Synchronized
        fun revoke(token: String?) { token?.let(entries::remove) }

        private fun discardExpired() {
            val now = clock()
            entries.entries.removeIf { it.value.expiresAt <= now }
        }

        @Synchronized
        override fun close() { closed = true; entries.clear() }

        private fun fingerprint(method: String, params: JSONObject, scope: JSONObject): String =
            nativeFingerprint(method, params, scope)
    }

    data class ScopedAdmission(val scope: JSONObject, val capability: String, val acknowledgeMessageId: String?)

    /** Application-side metadata never crosses the shared Node wire. It only
     * folds repeated authorization reads into one serialized native operation. */
    class NativeScopedAdmissions(private val clock: () -> Long = System::nanoTime) : AutoCloseable {
        private data class Entry(val hash: String, val admission: ScopedAdmission, val expiresAt: Long)
        private val entries = LinkedHashMap<String, Entry>()
        private var closed = false

        @Synchronized
        fun register(method: String, params: JSONObject, scope: JSONObject, capability: String, acknowledgeMessageId: String?): String {
            check(!closed) { "bridge_closed" }
            validate(method, params, scope, capability, acknowledgeMessageId)
            discardExpired()
            check(entries.size < MAX_CLIENTS) { "native_admission_full" }
            val token = UUID.randomUUID().toString()
            val detached = JSONObject(scope.toString())
            val hash = nativeFingerprint(method, params, detached)
            require(entries.values.none { it.hash == hash && (it.admission.capability != capability ||
                it.admission.acknowledgeMessageId != acknowledgeMessageId) }) { "native_admission_conflict" }
            entries[token] = Entry(hash, ScopedAdmission(detached, capability, acknowledgeMessageId),
                clock() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MILLIS))
            return token
        }

        @Synchronized
        fun consume(method: String, params: JSONObject, scope: JSONObject?): ScopedAdmission? {
            if (closed || scope == null) return null
            discardExpired()
            val hash = nativeFingerprint(method, params, scope)
            val entry = entries.entries.firstOrNull { it.value.hash == hash } ?: return null
            entries.remove(entry.key)
            return entry.value.admission.let { it.copy(scope = JSONObject(it.scope.toString())) }
        }

        @Synchronized fun revoke(token: String?) { token?.let(entries::remove) }
        @Synchronized override fun close() { closed = true; entries.clear() }
        private fun discardExpired() { val now = clock(); entries.entries.removeIf { it.value.expiresAt <= now } }

        companion object {
            fun validate(method: String, params: JSONObject, scope: JSONObject, capability: String, acknowledgeMessageId: String?) {
                require(scope.length() == 4 && listOf("node_id", "group_id", "local_device_id", "remote_device_id").all {
                    (scope.opt(it) as? String)?.matches(Regex("[A-Za-z0-9_-]{1,128}")) == true
                } && scope.getString("local_device_id") != scope.getString("remote_device_id")) { "permission_denied" }
                require(params.optString("group_id") == scope.getString("group_id")) { "permission_denied" }
                val expected = when (method) {
                    "list_incoming_messages" -> {
                        require(params.keys().asSequence().all { it in setOf("group_id", "cursor", "limit") } && acknowledgeMessageId == null) { "permission_denied" }
                        "remote_task"
                    }
                    "acknowledge_message" -> {
                        require(params.length() == 2 && params.optString("message_id").isNotEmpty() && acknowledgeMessageId == null) { "permission_denied" }
                        "remote_task"
                    }
                    "send_encrypted_message" -> {
                        require(params.length() == 4 && params.has("idempotency_key")) { "permission_denied" }
                        val body = params.getJSONObject("body")
                        val id = body.getString("request_id")
                        require(id.matches(Regex("[A-Za-z0-9_-]{8,128}")) && body.optString("protocol") == "dafeiyu-go.remote-dsh.v1" &&
                            body.optString("target_device_id") == scope.getString("remote_device_id")) { "permission_denied" }
                        when (params.getString("kind")) {
                            "dsh_remote_request" -> {
                                require(body.length() == 5 && body.optJSONObject("payload") != null && acknowledgeMessageId == null &&
                                    params.getString("idempotency_key") == "dsh-remote-$id") { "permission_denied" }
                                capabilityForOperation(body.getString("operation"))
                            }
                            "dsh_remote_cancel" -> {
                                require(body.length() == 4 && body.optString("operation") == "cancel" && acknowledgeMessageId == null &&
                                    params.getString("idempotency_key") == "dsh-remote-cancel-$id") { "permission_denied" }
                                "remote_task"
                            }
                            "dsh_remote_ack" -> {
                                val raw = body.opt("sequence")
                                require(body.length() == 4 && raw is Number && raw.toDouble() == raw.toLong().toDouble() &&
                                    raw.toLong() in 0..1025 && !acknowledgeMessageId.isNullOrEmpty() && acknowledgeMessageId.length <= 256 &&
                                    params.getString("idempotency_key") == "web-ack-$id-${raw.toLong()}") { "permission_denied" }
                                "remote_web_tunnel"
                            }
                            else -> error("permission_denied")
                        }
                    }
                    else -> error("unsupported_method")
                }
                require(expected == capability) { "permission_denied" }
                nativeFingerprint(method, params, scope)
            }

            private fun capabilityForOperation(operation: String): String = when (operation) {
                "session.archived.delete.prepare", "session.archived.delete" -> "remote_archive_delete"
                "artifact.list", "artifact.prepare", "artifact.read", "artifact.close" -> "remote_artifacts"
                "attachment.prepare", "attachment.write", "attachment.status", "attachment.commit", "attachment.cancel" -> "remote_attachments"
                "workspace.remove.prepare", "workspace.remove", "workspace.files.delete", "workspace.removed.list", "workspace.removed.restore",
                "session.archive", "session.restore", "session.archived.list" -> "remote_workspace_lifecycle"
                "web.http", "web.socket", "web.socket.send", "web.socket.close" -> "remote_web_tunnel"
                "directory.list", "workspace.list", "workspace.resolve" -> "remote_directory"
                "session.selectModel", "settings.describe", "settings.update", "permissions.catalog" -> "remote_settings"
                "session.create", "session.prompt", "session.cancel", "session.control", "session.follow", "session.list", "models.catalog" -> "remote_task"
                else -> error("unsupported_method")
            }
        }
    }

    private fun nativeFingerprint(method: String, params: JSONObject, scope: JSONObject): String {
            var items = 0
            fun canonical(value: Any?, depth: Int): String {
                require(depth <= 32 && ++items <= 100_000) { "protocol_error" }
                return when (value) {
                    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
                        require(key.length <= 512 && key !in setOf("__proto__", "constructor", "prototype")) { "protocol_error" }
                        JSONObject.quote(key) + ":" + canonical(value.get(key), depth + 1)
                    }
                    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it), depth + 1) }
                    else -> {
                        require(value == null || value == JSONObject.NULL || value is String || value is Number || value is Boolean) { "protocol_error" }
                        if (value is Number) require(value.toDouble().isFinite()) { "protocol_error" }
                        if (value is String) require(value.length <= MAX_REQUEST_BYTES) { "protocol_error" }
                        JSONArray().put(value ?: JSONObject.NULL).toString().let { it.substring(1, it.length - 1) }
                    }
                }
            }
            val bytes = canonical(JSONObject().put("method", method).put("params", params).put("scope", scope), 0)
                .toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_REQUEST_BYTES) { "protocol_error" }
            return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private val NATIVE_BULK_OPERATIONS = setOf("artifact.list", "artifact.read", "artifact.close", "attachment.write", "attachment.status")

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

    fun runtimeTracerPid(status: String): Int {
        val fields = status.lineSequence().filter { it.startsWith("TracerPid:") }.toList()
        require(fields.size == 1) { "invalid_runtime_identity" }
        return fields.single().substringAfter(':').trim().toIntOrNull()?.takeIf { it >= 0 }
            ?: throw IllegalArgumentException("invalid_runtime_identity")
    }

    /** PRoot executes guest ELF through its APK loader; bind its exact executable file mappings. */
    fun mappedExecutableEvidence(maps: String, expectedPath: String, device: Long, inode: Long): List<String> {
        require(expectedPath.startsWith('/') && expectedPath.none { it.code < 32 } && inode > 0)
        val major = ((device ushr 8) and 0xfff) or ((device ushr 32) and 0xfffff000)
        val minor = (device and 0xff) or ((device ushr 12) and 0xffffff00)
        val evidence = maps.lineSequence().map { it.trim().split(Regex("\\s+"), limit = 6) }
            .filter { it.size == 6 && it[5] == expectedPath }.map { fields ->
                require(fields[0].matches(Regex("[0-9a-f]+-[0-9a-f]+")) &&
                    fields[1].matches(Regex("[r-][w-][x-][ps]")) && fields[2].matches(Regex("[0-9a-f]+"))) {
                    "invalid_runtime_executable"
                }
                val mappedDevice = fields[3].split(':')
                require(mappedDevice.size == 2 && mappedDevice[0].toLongOrNull(16) == major &&
                    mappedDevice[1].toLongOrNull(16) == minor && fields[4].toLongOrNull() == inode) {
                    "invalid_runtime_executable"
                }
                fields.take(5).joinToString(" ")
            }.toList()
        require(evidence.isNotEmpty() && evidence.any { it.split(' ')[1] == "r-xp" }) { "invalid_runtime_executable" }
        return evidence
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
        fun bindRuntime(identity: RuntimeIdentity, launcher: RuntimeIdentity? = null) {
            require(!closed && runtimePid == null) { "runtime_already_bound_or_closed" }
            require(identity.pid > 0 && identity.pid != hostPid && identity.uid == appUid) { "invalid_runtime_identity" }
            if (launcher == null) require(identity.parentPid == hostPid) { "invalid_runtime_identity" }
            else require(launcher.pid > 0 && launcher.pid != hostPid && launcher.pid != identity.pid &&
                launcher.uid == appUid && launcher.parentPid == hostPid && identity.parentPid == launcher.pid) {
                "invalid_runtime_identity"
            }
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
            val remoteScope = if (request.has("remote_scope")) {
                val scope = request.opt("remote_scope") as? JSONObject ?: throw Failure("protocol_error", id)
                if (scope.length() != 4 || listOf("node_id", "group_id", "local_device_id", "remote_device_id").any {
                        val value = scope.opt(it) as? String
                        value == null || !value.matches(Regex("[A-Za-z0-9_-]{1,128}"))
                    } || scope.getString("local_device_id") == scope.getString("remote_device_id"))
                    throw Failure("protocol_error", id)
                JSONObject(scope.toString())
            } else null
            // Never evict an accepted nonce while this capability/generation remains valid.
            if (nonces.size >= MAX_NONCES || !nonces.add(nonce)) throw Failure("permission_denied", id)
            return Request(id, method, params, remoteScope)
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

    fun readRequest(input: InputStream, byteLimit: Int = MAX_REQUEST_BYTES): JSONObject? {
        require(byteLimit in 1..MAX_RESPONSE_BYTES)
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) {
                if (bytes.size() == 0) return null
                throw Failure("protocol_error")
            }
            // The shared Node transport includes the final LF in its byte limit.
            if (bytes.size() + 1 > byteLimit) throw Failure("protocol_error")
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
