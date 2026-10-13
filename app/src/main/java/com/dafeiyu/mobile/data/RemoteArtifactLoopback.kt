package com.dafeiyu.mobile.data

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONObject

fun interface RemoteArtifactCall {
    suspend fun request(operation: String, payload: JSONObject, binding: RemoteDshScope): JSONObject
}

/** An ephemeral, exact-file Range endpoint; remote bytes continue through authenticated Aries requests. */
class RemoteArtifactLoopback(
    private val binding: RemoteDshScope,
    private val ticket: RemoteArtifactTicket,
    private val call: RemoteArtifactCall,
    private val ensureCurrent: suspend (RemoteDshScope) -> Unit,
) : AutoCloseable {
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val permits = Semaphore(8)
    private val token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
    @Volatile private var closed = false
    val port: Int get() = socket.localPort
    val url: String = "http://127.0.0.1:${socket.localPort}/$token"
    private val host = "127.0.0.1:${socket.localPort}"
    private val etag = "\"${ticket.sha256}\""

    init {
        scope.launch {
            while (!closed) {
                val client = try { socket.accept() } catch (_: IOException) { break }
                if (!client.inetAddress.isLoopbackAddress || !permits.tryAcquire()) { client.close(); continue }
                clients += client
                scope.launch {
                    try { client.use { serve(it) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* A short/error stream cannot pass the shared downloader's size/digest checks. */ }
                    finally { clients -= client; permits.release() }
                }
            }
        }
    }
    private suspend fun serve(client: Socket) {
        client.soTimeout = 8_000
        val input = BufferedInputStream(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream())
        var budget = 8 * 1024
        fun line(): String {
            val bytes = java.io.ByteArrayOutputStream()
            while (true) {
                if (--budget < 0) throw IOException("header limit")
                val value = input.read()
                if (value == -1) throw IOException("incomplete header")
                if (value == 13) {
                    if (--budget < 0 || input.read() != 10) throw IOException("invalid line")
                    return bytes.toString(Charsets.US_ASCII.name())
                }
                if (value < 32 || value > 126) throw IOException("invalid header")
                bytes.write(value)
            }
        }
        fun error(status: Int) {
            output.write("HTTP/1.1 $status Unavailable\r\nContent-Length: 0\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n"
                .toByteArray(Charsets.US_ASCII)); output.flush()
        }
        val request = line().split(' ')
        if (request.size != 3 || request[2] != "HTTP/1.1" || request[0] !in setOf("GET", "HEAD") || request[1] != "/$token") { error(404); return }
        val headers = mutableMapOf<String, String>()
        while (true) {
            val entry = line()
            if (entry.isEmpty()) break
            val split = entry.indexOf(':')
            if (split <= 0) { error(400); return }
            val name = entry.substring(0, split).lowercase(java.util.Locale.ROOT)
            if (!name.matches(Regex("[a-z0-9-]+")) || headers.put(name, entry.substring(split + 1).trim()) != null) { error(400); return }
        }
        if (headers["host"] != host || headers["origin"]?.let { it != "http://$host" } == true ||
            headers["transfer-encoding"] != null || headers["content-length"]?.let { it != "0" } == true) { error(403); return }
        if (headers["if-match"]?.let { it != etag } == true) { error(412); return }
        var offset = 0L
        var end = ticket.size - 1
        var ranged = false
        if (headers["range"] != null && ticket.size > 0) {
            val parsed = Regex("bytes=([0-9]{1,16})-([0-9]{1,16})").matchEntire(headers.getValue("range"))
            if (parsed == null) { error(416); return }
            offset = parsed.groupValues[1].toLong(); end = minOf(parsed.groupValues[2].toLong(), ticket.size - 1)
            if (offset > end || offset >= ticket.size) { error(416); return }
            if (headers["if-range"] == null || headers["if-range"] == etag) ranged = true
            else { offset = 0; end = ticket.size - 1 }
        }
        try { ensureCurrent(binding) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error(409); return }
        currentCoroutineContext().ensureActive()
        val size = if (ticket.size == 0L) 0 else end - offset + 1
        val response = buildString {
            append(if (ranged) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Length: $size\r\nContent-Type: application/octet-stream\r\nAccept-Ranges: bytes\r\nETag: $etag\r\n")
            if (ranged) append("Content-Range: bytes $offset-$end/${ticket.size}\r\n")
            append("Cache-Control: no-store\r\nConnection: close\r\n\r\n")
        }
        output.write(response.toByteArray(Charsets.US_ASCII)); output.flush()
        if (request[0] == "HEAD") return
        while (offset <= end) {
            currentCoroutineContext().ensureActive()
            if (closed) throw IOException("closed")
            val length = minOf(ticket.chunkBytes.toLong(), end - offset + 1).toInt()
            val value = call.request("artifact.read", JSONObject().put("ticket", ticket.ticket).put("offset", offset).put("length", length), binding)
            val bytes = RemoteArtifactProtocol.bytes(value, ticket, offset, length)
            output.write(bytes); output.flush(); offset += bytes.size
        }
    }
    override fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
        clients.forEach { runCatching { it.close() } }
        scope.cancel()
    }
}
