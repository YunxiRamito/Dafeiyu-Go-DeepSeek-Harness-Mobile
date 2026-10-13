package com.dafeiyu.mobile.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * HTTP/1.1 + RFC6455 carrier restricted to one ephemeral loopback authority.
 * Lengths are bounded before body/frame allocation; no default temp directory,
 * compression negotiation, arbitrary CONNECT, or unauthenticated local API.
 */
class RemoteDshLoopbackProxy(private val gateway: RemoteDshWebGateway, parentScope: CoroutineScope) : AutoCloseable {
    data class Access(val url: String, val origin: String)
    data class Fault(val stage: String, val code: String)
    /** Fixed categories only; no request body, launch token, cookie, path or raw exception text. */
    @Volatile var lastFailure: Fault? = null
        private set
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO)
    private val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
    private val permits = Semaphore(8)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var server: ServerSocket? = null

    suspend fun start(): Access = withContext(Dispatchers.IO) {
        check(server == null)
        gateway.ensureAvailable()
        val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        server = listener
        val origin = "http://127.0.0.1:${listener.localPort}"
        scope.launch {
            try {
                while (!listener.isClosed) {
                    val socket = listener.accept()
                    if (!socket.inetAddress.isLoopbackAddress || !permits.tryAcquire()) { socket.close(); continue }
                    clients.add(socket)
                    scope.launch {
                        try { serve(socket, origin) } catch (_: Exception) { /* Public HTTP errors contain no transport/provider details. */ }
                        finally { clients.remove(socket); runCatching { socket.close() }; permits.release() }
                    }
                }
            } catch (_: Exception) { /* Listener closed by lifecycle/revocation. */ }
        }
        Access("$origin/?dafeiyu_session=$secret", origin)
    }

    private suspend fun serve(socket: Socket, origin: String) {
        socket.soTimeout = 20_000
        socket.tcpNoDelay = true
        val input = socket.getInputStream().buffered()
        val output = socket.getOutputStream().buffered()
        val request = try { readRequest(input) } catch (_: Exception) { respond(output, 400, emptyMap(), ByteArray(0)); return }
        val host = origin.removePrefix("http://")
        if (request.headers["host"] != host) { respond(output, 403, emptyMap(), ByteArray(0)); return }
        val requestOrigin = request.headers["origin"]
        if (requestOrigin != null && requestOrigin != origin) { respond(output, 403, emptyMap(), ByteArray(0)); return }
        if (request.method == "GET" && request.path == "/?dafeiyu_session=$secret") {
            // Phone-only browser session. It is unrelated to the PC Cookie jar.
            respond(output, 303, mapOf("location" to "/", "set-cookie" to "dafeiyu_local=$secret; Path=/; HttpOnly; SameSite=Strict", "cache-control" to "no-store"), ByteArray(0))
            return
        }
        val cookies = request.headers["cookie"].orEmpty().split(';').map { it.trim() }.filter { it.startsWith("dafeiyu_local=") }
        val credential = cookies.singleOrNull()?.removePrefix("dafeiyu_local=")
        if (credential == null || !MessageDigest.isEqual(credential.toByteArray(), secret.toByteArray())) { respond(output, 403, emptyMap(), ByteArray(0)); return }
        if (request.method == "POST" && requestOrigin != origin) { respond(output, 403, emptyMap(), ByteArray(0)); return }
        if (request.headers["upgrade"]?.equals("websocket", ignoreCase = true) == true) {
            if (request.method != "GET" || request.path != "/api/remote.mux" || requestOrigin != origin || request.body.isNotEmpty()) { respond(output, 403, emptyMap(), ByteArray(0)); return }
            upgrade(socket, input, output, request)
            return
        }
        try {
            RemoteDshWebClient.validateRemoteWebPath(request.path)
            gateway.ensureAvailable()
            val result = gateway.http(request.method, request.path, request.headers.filterKeys { it in UPSTREAM_HEADERS }, request.body)
            require(result.body.size <= RemoteDshWebClient.RESPONSE_BYTES && result.status in 200..599)
            val headers = result.headers.filterKeys { it in RESPONSE_HEADERS }.toMutableMap()
            headers["content-security-policy"] = "default-src 'self' data: blob:; connect-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; worker-src 'self' blob:; frame-ancestors 'none'"
            headers["referrer-policy"] = "no-referrer"
            headers["x-content-type-options"] = "nosniff"
            respond(output, result.status, headers, result.body)
        } catch (failure: Exception) {
            recordFailure("http", failure)
            respond(output, 502, mapOf("content-type" to "text/plain; charset=utf-8", "cache-control" to "no-store"), "电脑 DSH 网页连接暂不可用，请返回连接设置。".toByteArray())
        }
    }

    private suspend fun upgrade(socket: Socket, input: InputStream, output: OutputStream, request: Request) {
        val key = request.headers["sec-websocket-key"].orEmpty()
        val connection = request.headers["connection"].orEmpty().split(',').map { it.trim().lowercase() }
        if (request.headers["sec-websocket-version"] != "13" || "upgrade" !in connection || !key.matches(Regex("[A-Za-z0-9+/]{22}==")) || Base64.getDecoder().decode(key).size != 16 || request.headers.containsKey("sec-websocket-protocol")) {
            respond(output, 400, emptyMap(), ByteArray(0)); return
        }
        val remote = try { gateway.ensureAvailable(); gateway.openSocket() } catch (failure: Exception) {
            recordFailure("socket_open", failure); respond(output, 502, emptyMap(), ByteArray(0)); return
        }
        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)))
        output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(Charsets.US_ASCII)); output.flush()
        val outputLock = Any()
        socket.soTimeout = 60_000
        val downstream = scope.launch {
            try {
                while (true) {
                    val frame = remote.receive() ?: break
                    val text = frame.toString().toByteArray(Charsets.UTF_8)
                    require(text.size <= WS_OUTPUT_LIMIT)
                    synchronized(outputLock) { writeFrame(output, 1, text) }
                }
            } catch (failure: Exception) { recordFailure("socket_receive", failure) }
            finally { runCatching { synchronized(outputLock) { writeFrame(output, 8, byteArrayOf(3, -13)) } }; runCatching { socket.close() } }
        }
        val fragments = ByteArrayOutputStream()
        var fragmented = false
        try {
            while (true) {
                val frame = readFrame(input)
                when (frame.opcode) {
                    8 -> {
                        if (frame.payload.size >= 2) {
                            val code = (frame.payload[0].toInt() and 255) shl 8 or (frame.payload[1].toInt() and 255)
                            require(code in 1000..4999 && code !in setOf(1004, 1005, 1006, 1015) && (code <= 1014 || code >= 3000))
                            decodeUtf8(frame.payload.copyOfRange(2, frame.payload.size))
                        }
                        synchronized(outputLock) { writeFrame(output, 8, frame.payload) }; return
                    }
                    9 -> synchronized(outputLock) { writeFrame(output, 10, frame.payload) }
                    10 -> Unit
                    0, 1 -> {
                        require(frame.opcode != 0 || fragmented)
                        require(frame.opcode != 1 || !fragmented)
                        require(fragments.size() + frame.payload.size <= WS_INPUT_LIMIT)
                        fragments.write(frame.payload)
                        fragmented = !frame.final
                        if (frame.final) {
                            val text = decodeUtf8(fragments.toByteArray())
                            fragments.reset()
                            gateway.ensureAvailable()
                            remote.send(JSONObject(text))
                        }
                    }
                    else -> throw IllegalArgumentException("Unsupported WebSocket opcode")
                }
            }
        } finally { downstream.cancel(); runCatching { remote.close() } }
    }

    private fun recordFailure(stage: String, failure: Exception) {
        val code = when (failure) {
            is TimeoutCancellationException -> "request_timeout"
            is CancellationException -> "request_cancelled"
            is AriesProviderException -> failure.code.takeIf { it in setOf("conflict", "revoked", "remote_offline",
                "connection_unavailable", "unsupported_method", "protocol_error", "permission_denied", "request_uncertain",
                "request_timeout", "request_cancelled", "validation_failed", "remote_execution_failed") } ?: "provider_error"
            is java.io.IOException -> "connection_unavailable"
            is IllegalArgumentException, is org.json.JSONException -> "protocol_error"
            else -> "remote_web_failed"
        }
        lastFailure = Fault(stage, code)
    }

    override fun close() {
        runCatching { server?.close() }; server = null
        clients.forEach { runCatching { it.close() } }
        clients.clear(); scope.cancel()
    }

    internal data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)
    internal data class Frame(val final: Boolean, val opcode: Int, val payload: ByteArray)

    companion object {
        private const val HEAD_LIMIT = 16 * 1024
        private const val WS_INPUT_LIMIT = 64 * 1024
        private const val WS_OUTPUT_LIMIT = 96 * 1024
        private val UPSTREAM_HEADERS = setOf("accept", "content-type", "if-none-match", "if-modified-since")
        private val RESPONSE_HEADERS = setOf("content-type", "cache-control", "etag", "last-modified", "content-encoding")

        internal fun readRequest(input: InputStream): Request {
            val head = ByteArrayOutputStream()
            var last = 0
            while (true) {
                val byte = input.read()
                if (byte < 0) throw EOFException()
                require(head.size() < HEAD_LIMIT)
                require(byte == 9 || byte == 10 || byte == 13 || byte in 32..126)
                head.write(byte)
                last = (last shl 8) or byte
                if (last == 0x0d0a0d0a) break
            }
            val lines = head.toString(Charsets.US_ASCII.name()).removeSuffix("\r\n\r\n").split("\r\n")
            val request = lines.first().split(' ')
            require(request.size == 3 && request[0] in setOf("GET", "POST") && request[2] == "HTTP/1.1" && request[1].length <= 4096)
            val headers = LinkedHashMap<String, String>()
            require(lines.size <= 65)
            for (line in lines.drop(1)) {
                val colon = line.indexOf(':')
                require(colon > 0 && !line.startsWith(' ') && !line.startsWith('\t'))
                val name = line.substring(0, colon).lowercase()
                val value = line.substring(colon + 1).trim()
                require(name.matches(Regex("[a-z0-9!#$%&'*+.^_`|~-]+")) && !headers.containsKey(name) && !value.any { it.code < 32 && it != '\t' })
                headers[name] = value
            }
            require("host" in headers && "transfer-encoding" !in headers && "expect" !in headers)
            val lengthText = headers["content-length"] ?: "0"
            require(lengthText.matches(Regex("[0-9]{1,8}")))
            val length = lengthText.toInt()
            require(length <= RemoteDshWebClient.REQUEST_BYTES && (request[0] != "GET" || length == 0))
            return Request(request[0], request[1], headers, exact(input, length))
        }

        /** Mask, RSV and declared length are checked before allocating payload bytes. */
        internal fun readFrame(input: InputStream): Frame {
            val first = input.read(); val second = input.read()
            if (first < 0 || second < 0) throw EOFException()
            val final = first and 128 != 0
            val opcode = first and 15
            require(first and 112 == 0 && second and 128 != 0 && opcode in setOf(0, 1, 8, 9, 10))
            var length = (second and 127).toLong()
            if (length == 126L) {
                val bytes = exact(input, 2)
                length = ((bytes[0].toInt() and 255) shl 8 or (bytes[1].toInt() and 255)).toLong()
                require(length >= 126)
            } else if (length == 127L) {
                val bytes = exact(input, 8)
                require(bytes[0].toInt() and 128 == 0)
                length = 0
                for (byte in bytes) length = (length shl 8) or (byte.toLong() and 255L)
                require(length >= 65536)
            }
            require(length <= WS_INPUT_LIMIT && (opcode < 8 || final && length <= 125))
            require(opcode != 8 || length != 1L)
            val mask = exact(input, 4)
            val payload = exact(input, length.toInt())
            for (index in payload.indices) payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
            return Frame(final, opcode, payload)
        }
        internal fun writeFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
            require(payload.size <= WS_OUTPUT_LIMIT && (opcode < 8 || payload.size <= 125))
            output.write(128 or opcode)
            when {
                payload.size < 126 -> output.write(payload.size)
                payload.size <= 65535 -> { output.write(126); output.write(payload.size ushr 8); output.write(payload.size and 255) }
                else -> { output.write(127); for (shift in 56 downTo 0 step 8) output.write((payload.size.toLong() ushr shift).toInt() and 255) }
            }
            output.write(payload); output.flush()
        }
        private fun exact(input: InputStream, count: Int): ByteArray {
            val bytes = ByteArray(count)
            var index = 0
            while (index < count) { val read = input.read(bytes, index, count - index); if (read < 0) throw EOFException(); index += read }
            return bytes
        }
        private fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        private fun respond(output: OutputStream, status: Int, headers: Map<String, String>, body: ByteArray) {
            require(body.size <= RemoteDshWebClient.RESPONSE_BYTES)
            val head = StringBuilder("HTTP/1.1 $status ${if (status == 200) "OK" else "Response"}\r\nConnection: close\r\nContent-Length: ${body.size}\r\n")
            for ((name, value) in headers) {
                require(name.matches(Regex("[a-z-]+")) && value.length <= 4096 && !value.any { it == '\r' || it == '\n' || it.code == 0 })
                head.append(name).append(": ").append(value).append("\r\n")
            }
            head.append("\r\n"); output.write(head.toString().toByteArray(Charsets.US_ASCII)); output.write(body); output.flush()
        }
    }
}
