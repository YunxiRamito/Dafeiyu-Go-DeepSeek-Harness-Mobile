package com.dafeiyu.mobile.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

/** Local browser carrier; it never knows the PC launch token or upstream Cookie. */
interface RemoteDshWebGateway {
    data class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray)
    interface Socket {
        suspend fun send(frame: JSONObject)
        suspend fun receive(): JSONObject?
        suspend fun close()
    }
    suspend fun ensureAvailable()
    suspend fun http(method: String, path: String, headers: Map<String, String>, body: ByteArray): HttpResponse
    suspend fun openSocket(): Socket
}

/** Ordered Connect envelopes. A lost POST reply is observed with the same deterministic identity. */
class RemoteDshWebClient(private val transport: RemoteDshTransport, private val binding: RemoteDshScope,
    private val trace: (Trace) -> Unit = {},
) : RemoteDshWebGateway {
    /** Optional QA metadata observer. Bodies, URLs, headers and credentials are never included. */
    data class Trace(val stage: String, val requestId: String? = null, val cursor: String? = null,
        val nextCursor: String? = null, val sequence: Long? = null, val type: String? = null,
        val eventCount: Int? = null, val readerPresent: Boolean? = null)
    private fun trace(value: Trace) { runCatching { trace.invoke(value) } }
    private data class HttpIntent(val fingerprint: String,
        val response: CompletableDeferred<RemoteDshWebGateway.HttpResponse> = CompletableDeferred())
    private val httpMutex = Mutex()
    private val httpIntents = linkedMapOf<String, HttpIntent>()
    private var httpCacheBytes = 0
    private val availabilityMutex = Mutex()
    private var availabilityInFlight: CompletableDeferred<JSONObject>? = null
    private val ingressMutex = Mutex()
    private val readers = linkedMapOf<String, Reader>()
    private var ingressCursor = "0"
    @Volatile private var ingressRevision = 0L
    override suspend fun ensureAvailable() {
        // Concurrent browser requests may share the same in-flight observation,
        // never a cached admission. Every submit/poll/ACK still passes the Native
        // transaction's current selector, membership, epoch and capability checks.
        val (observation, ownsObservation) = availabilityMutex.withLock {
            availabilityInFlight?.let { it to false } ?: CompletableDeferred<JSONObject>().also {
                availabilityInFlight = it
            }.let { it to true }
        }
        if (ownsObservation) {
            try { observation.complete(transport.connectionState()) }
            catch (failure: Throwable) { observation.completeExceptionally(failure); throw failure }
            finally { withContext(NonCancellable) { availabilityMutex.withLock {
                if (availabilityInFlight === observation) availabilityInFlight = null
            } } }
        }
        val state = observation.await()
        binding.requireCurrent(state, requireOnline = true)
        if (!state.getJSONObject("capabilities").optBoolean("remote_web_tunnel")) throw AriesProviderException("unsupported_method")
    }

    override suspend fun http(method: String, path: String, headers: Map<String, String>, body: ByteArray): RemoteDshWebGateway.HttpResponse = withTimeout(60_000L) {
        require(method in setOf("GET", "POST") && body.size <= REQUEST_BYTES)
        validateRemoteWebPath(path)
        val payload = JSONObject().put("method", method).put("path", path).put("headers", JSONObject(headers))
            .put("body_base64", Base64.getEncoder().encodeToString(body))
        val requestId = if (method == "POST") {
            val rpc = JSONObject(body.toString(Charsets.UTF_8))
            require(rpc.getString("rpcId").matches(ID))
            "web-http-" + hash((binding.storageKey + "\n" + rpc.getString("rpcId")).toByteArray()).take(48)
        } else RemoteDshClient.newRequestId()
        ensureAvailable()
        val (intent, ownsIntent) = if (method == "POST") httpMutex.withLock {
            val fingerprint = remoteJsonHash(payload)
            val previous = httpIntents[requestId]
            if (previous != null) {
                if (previous.fingerprint != fingerprint) throw AriesProviderException("conflict")
                previous to false
            } else {
                if (httpIntents.size >= HTTP_INTENTS) throw AriesProviderException("disk_space_low")
                HttpIntent(fingerprint).also { httpIntents[requestId] = it } to true
            }
        } else null to true
        if (!ownsIntent) {
            val response = checkNotNull(intent).response.await()
            ensureAvailable()
            return@withTimeout response.copy(headers = response.headers.toMap(), body = response.body.copyOf())
        }
        var reader: Reader? = null
        var status: Int? = null
        var responseHeaders = emptyMap<String, String>()
        var chunkIndex = 0
        val bytes = ByteArrayOutputStream()
        var ended = false
        try {
            // Registration precedes submission: another HTTP/WS reader may
            // advance the shared cursor while this request is being admitted.
            val currentReader = registerReader(requestId).also { reader = it }
            trace(Trace("http.submit.start", requestId))
            transport.submit(RemoteDshClient.Request(binding.groupId, "web.http", payload, requestId, binding))
            trace(Trace("http.submit.done", requestId))
            while (true) {
                val event = currentReader.next() ?: throw AriesProviderException("protocol_error")
                when (event.type) {
                    "result" -> require((event.payload as? JSONObject)?.optString("stream") == "http")
                    "event" -> {
                        val frame = event.payload as? JSONObject ?: throw AriesProviderException("protocol_error")
                        require(!ended)
                        when (frame.getString("kind")) {
                            "http.head" -> {
                                require(status == null && chunkIndex == 0)
                                status = frame.getInt("status").also { require(it in 200..599) }
                                val values = frame.getJSONObject("headers")
                                require(values.length() <= 8)
                                responseHeaders = values.keys().asSequence().associateWith { key ->
                                    require(key in RESPONSE_HEADERS)
                                    values.getString(key).also { require(it.length <= 1024 && !it.any { c -> c == '\r' || c == '\n' || c.code == 0 }) }
                                }
                            }
                            "http.chunk" -> {
                                require(status != null && frame.getInt("index") == chunkIndex++)
                                val encoded = frame.getString("bytes")
                                require(encoded.length <= 64 * 1024 && encoded.matches(BASE64))
                                val block = Base64.getDecoder().decode(encoded)
                                require(block.isNotEmpty() && block.size <= CHUNK_BYTES && bytes.size() + block.size <= RESPONSE_BYTES)
                                bytes.write(block)
                            }
                            "http.end" -> {
                                require(status != null && frame.getInt("size") == bytes.size() && frame.getString("sha256") == hash(bytes.toByteArray()))
                                ended = true
                            }
                            else -> throw AriesProviderException("protocol_error")
                        }
                    }
                    "closed" -> {
                        require(ended && status != null)
                        val response = RemoteDshWebGateway.HttpResponse(status, responseHeaders, bytes.toByteArray())
                        if (intent != null) httpMutex.withLock {
                            if (httpCacheBytes + response.body.size > RESPONSE_BYTES) throw AriesProviderException("disk_space_low")
                            httpCacheBytes += response.body.size
                            intent.response.complete(response.copy(headers = response.headers.toMap(), body = response.body.copyOf()))
                        }
                        return@withTimeout response
                    }
                    "error" -> throw AriesProviderException((event.payload as? JSONObject)?.optString("code", "remote_execution_failed") ?: "remote_execution_failed")
                    else -> throw AriesProviderException("protocol_error")
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw AriesProviderException("protocol_error")
        } catch (error: Throwable) {
            intent?.response?.completeExceptionally(error)
            // Cancellation only stops the observation, never repeats a PC POST.
            if (error !is CancellationException) runCatching { transport.cancelObservation(binding, requestId) }
            throw error
        } finally { reader?.let { unregisterReader(it) } }
    }

    override suspend fun openSocket(): RemoteDshWebGateway.Socket {
        ensureAvailable()
        val requestId = RemoteDshClient.newRequestId()
        val reader = registerReader(requestId)
        val socketId = try {
            transport.submit(RemoteDshClient.Request(binding.groupId, "web.socket", JSONObject().put("path", "/api/remote.mux"), requestId, binding))
            val first = withTimeout(30_000L) { reader.next() } ?: throw AriesProviderException("protocol_error")
            if (first.type == "error") throw AriesProviderException((first.payload as? JSONObject)?.optString("code", "remote_execution_failed") ?: "remote_execution_failed")
            require(first.type == "result")
            (first.payload as JSONObject).getString("socket_id").also { require(it.matches(ID)) }
        } catch (failure: Throwable) { unregisterReader(reader); throw failure }
        return object : RemoteDshWebGateway.Socket {
            private var sequence = 0L
            private var closed = false
            override suspend fun send(frame: JSONObject) {
                check(!closed)
                require(frame.toString().toByteArray().size <= REQUEST_BYTES)
                ensureAvailable()
                val sendId = "web-send-" + hash((binding.storageKey + "\n" + socketId + "\n" + sequence).toByteArray()).take(48)
                val sendReader = registerReader(sendId)
                val receipt = try {
                    transport.submit(RemoteDshClient.Request(binding.groupId, "web.socket.send", JSONObject().put("socket_id", socketId)
                        .put("sequence", sequence).put("frame", frame), sendId, binding))
                    // Do not send another frame until the PC accepted this exact sequence.
                    withTimeout(30_000L) { sendReader.next() } ?: throw AriesProviderException("protocol_error")
                } finally { unregisterReader(sendReader) }
                if (receipt.type == "error") throw AriesProviderException((receipt.payload as? JSONObject)?.optString("code", "remote_execution_failed") ?: "remote_execution_failed")
                require(receipt.type == "result" && (receipt.payload as? JSONObject)?.optLong("next_sequence") == sequence + 1L)
                sequence++
            }
            override suspend fun receive(): JSONObject? {
                if (closed) return null
                val event = reader.next() ?: return null
                if (event.type == "closed") { closed = true; unregisterReader(reader); return null }
                if (event.type == "error") throw AriesProviderException((event.payload as? JSONObject)?.optString("code", "remote_execution_failed") ?: "remote_execution_failed")
                require(event.type == "event")
                val frame = event.payload as? JSONObject ?: throw AriesProviderException("protocol_error")
                require(frame.getString("kind") == "ws.frame")
                return frame.getJSONObject("frame")
            }
            override suspend fun close() {
                if (closed) return
                closed = true
                unregisterReader(reader)
                // A changed/revoked binding cannot dispatch a late close into another group.
                runCatching {
                    ensureAvailable()
                    transport.cancelObservation(binding, requestId)
                }
            }
        }
    }

    private suspend fun registerReader(requestId: String): Reader = ingressMutex.withLock {
        if (readers.containsKey(requestId)) throw AriesProviderException("conflict")
        if (readers.size >= READERS) throw AriesProviderException("connection_unavailable")
        Reader(requestId).also { readers[requestId] = it; trace(Trace("reader.register", requestId, ingressCursor)) }
    }

    private suspend fun unregisterReader(reader: Reader) = withContext(NonCancellable) {
        ingressMutex.withLock {
            if (readers[reader.requestId] === reader) {
                readers.remove(reader.requestId)
                trace(Trace("reader.unregister", reader.requestId, ingressCursor))
            }
        }
    }

    /** One exclusive cursor serves all HTTP/WS requests; unmatched task frames remain unacknowledged. */
    private suspend fun pollReaders(observedRevision: Long) = ingressMutex.withLock {
        // A queued reader first consumes the poll which completed while it was
        // waiting. Empty pages also count: six concurrent consumers must not
        // perform six identical serialized HTTP polls for one observation.
        if (ingressRevision != observedRevision) return@withLock
        trace(Trace("ingress.poll.start", cursor = ingressCursor))
        val page = transport.poll(binding, ingressCursor, 4)
        trace(Trace("ingress.poll.done", cursor = ingressCursor, nextCursor = page.nextCursor, eventCount = page.events.size))
        for (event in page.events) {
            trace(Trace("ingress.route", event.requestId, ingressCursor, page.nextCursor, event.sequence, event.type,
                readerPresent = readers.containsKey(event.requestId)))
            readers[event.requestId]?.accept(event)
        }
        page.nextCursor?.let { next ->
            require(next.matches(Regex("[0-9]{1,64}")) && java.math.BigInteger(next) >= java.math.BigInteger(ingressCursor))
            ingressCursor = next
        }
        ingressRevision++
    }

    private inner class Reader(val requestId: String) {
        private val mutex = Mutex()
        private var sequence = 0L
        private val pending = sortedMapOf<Long, RemoteDshClient.Event>()
        private val fingerprints = HashMap<Long, String>()
        private var pendingBytes = 0
        private var terminal = false
        suspend fun accept(event: RemoteDshClient.Event) = mutex.withLock {
            require(event.requestId == requestId && event.senderDeviceId == binding.remoteDeviceId &&
                event.sequence in 0..1025 && event.messageId.isNotEmpty())
            val envelope = JSONObject().put("type", event.type).put("payload", event.payload)
            remoteValidateJson(envelope)
            val fingerprint = remoteJsonHash(envelope)
            val previous = fingerprints[event.sequence]
            require(previous == null || previous == fingerprint)
            if (previous != null) return@withLock
            require(fingerprints.size < 1026 && event.sequence >= sequence && pending.size < 128)
            val size = envelope.toString().toByteArray(Charsets.UTF_8).size
            require(pendingBytes + size <= PENDING_BYTES)
            fingerprints[event.sequence] = fingerprint
            pending[event.sequence] = event
            pendingBytes += size
        }
        suspend fun next(): RemoteDshClient.Event? = withTimeout(60_000L) {
            while (true) {
                currentCoroutineContext().ensureActive()
                val observedRevision = ingressRevision
                val received = mutex.withLock {
                    pending.remove(sequence)?.also { event ->
                        pendingBytes -= JSONObject().put("type", event.type).put("payload", event.payload)
                            .toString().toByteArray(Charsets.UTF_8).size
                        sequence++
                    }
                }
                received?.let { event ->
                    // Native ACK admission revalidates the exact relationship,
                    // current epoch and capability before this frame is exposed.
                    trace(Trace("reader.ack.start", requestId, sequence = event.sequence, type = event.type))
                    val receipt = transport.acknowledgeWeb(binding, event)
                    require(receipt.optBoolean("acknowledged"))
                    trace(Trace("reader.ack.done", requestId, sequence = event.sequence, type = event.type))
                    if (event.type in setOf("closed", "error")) terminal = true
                    return@withTimeout event
                }
                if (terminal) return@withTimeout null
                pollReaders(observedRevision)
                if (mutex.withLock { !pending.containsKey(sequence) }) delay(250L)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }

    companion object {
        const val REQUEST_BYTES = 64 * 1024
        const val CHUNK_BYTES = 48 * 1024
        const val RESPONSE_BYTES = 8 * 1024 * 1024
        private const val PENDING_BYTES = 512 * 1024
        private const val READERS = 16
        private const val HTTP_INTENTS = 128
        private val ID = Regex("[A-Za-z0-9_-]{8,128}")
        private val BASE64 = Regex("(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?")
        private val RESPONSE_HEADERS = setOf("content-type", "cache-control", "etag", "last-modified", "content-encoding")
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun validateRemoteWebPath(value: String) {
            require(value.length <= 4096 && value.startsWith('/') && !value.startsWith("//") && !value.any { it.code <= 32 || it.code == 127 || it == '\\' || it == '#' })
            if (value.startsWith("/plugins/??")) {
                val match = Regex("/plugins/\\?\\?((?:(?:@[a-z0-9._-]+/)?[a-z0-9._-]+/client\\.js)(?:,(?:@[a-z0-9._-]+/)?[a-z0-9._-]+/client\\.js)*)&rev=([a-f0-9]{12})").matchEntire(value)
                require(match != null && match.groupValues[1].split(',').size <= 128 &&
                    match.groupValues[1].split('/').none { it == "." || it == ".." })
                return
            }
            val parts = value.split('?', limit = 3)
            require(parts.size <= 2)
            val decoded = java.net.URLDecoder.decode(parts[0].replace("+", "%2B"), Charsets.UTF_8.name())
            require(!decoded.any { it.code <= 32 || it.code == 127 || it == '%' || it == '\\' } && decoded.split('/').none { it == "." || it == ".." })
            if (parts.size == 2) parts[1].split('&').forEach { query ->
                val field = query.split('=', limit = 2)
                require(field.size == 2 && field[0] in setOf("v", "t", "hash", "rev") && field[1].matches(Regex("[A-Za-z0-9._-]{0,128}")))
            }
        }
    }
}
