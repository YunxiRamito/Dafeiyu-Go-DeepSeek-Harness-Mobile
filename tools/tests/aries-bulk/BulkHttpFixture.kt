package com.dafeiyu.mobile.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject

/** Real loopback HTTP sockets; no public Aries/TLS/signing or Android HTTP implementation is claimed. */
class BulkHttpFixture(private val expected: Int) : AutoCloseable {
    val reached = CompletableDeferred<Unit>()
    val inFlight = AtomicInteger()
    val maximum = AtomicInteger()
    val received = AtomicInteger()
    private val release = CountDownLatch(1)
    private val closed = AtomicBoolean()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val executor = Executors.newFixedThreadPool(16)
    // Windows JDK21's selector wakeup pipe attempts AF_UNIX on this G: volume.
    // Blocking sockets keep this fixture on the authorized volume without changing production IO.
    private val server = ServerSocket().apply {
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16)
    }
    private val acceptThread = Thread({
        while (!closed.get()) {
            val socket = try { server.accept() } catch (error: Throwable) {
                if (!closed.get()) reached.completeExceptionally(error)
                break
            }
            sockets.add(socket)
            executor.execute { handle(socket) }
        }
    }, "aries-bulk-http-fixture").apply { isDaemon = true; start() }

    private fun handle(socket: Socket) {
        var admitted = false
        try {
            socket.soTimeout = 25000
            val input = socket.getInputStream().buffered()
            check(line(input) == "POST /synthetic/messages HTTP/1.1")
            val headers = mutableMapOf<String, String>()
            var headerBytes = 0
            while (true) {
                val header = line(input)
                headerBytes += header.length + 2
                check(headerBytes <= 32768)
                if (header.isEmpty()) break
                val split = header.indexOf(':')
                check(split > 0)
                val name = header.substring(0, split).lowercase(Locale.ROOT)
                check(headers.put(name, header.substring(split + 1).trim()) == null)
            }
            check("transfer-encoding" !in headers)
            check(headers["idempotency-key"].orEmpty().isNotBlank())
            val size = headers.getValue("content-length").toInt()
            check(size in 1..1024 * 1024)
            val bytes = input.readNBytes(size)
            check(bytes.size == size)
            val body = JSONObject(bytes.toString(Charsets.UTF_8))
            val envelope = AriesCrypto.readContent(body.getString("ciphertext"))
            val plain = AriesCrypto.openContent(BulkFixture.contentKey, envelope, BulkFixture.localPublic)
            try { check(JSONObject(plain.toString(Charsets.UTF_8)).optString("kind") == RemoteDshClient.REQUEST_KIND) }
            finally { plain.fill(0) }
            val active = inFlight.incrementAndGet()
            admitted = true
            maximum.updateAndGet { maxOf(it, active) }
            val count = received.incrementAndGet()
            if (count == expected) reached.complete(Unit)
            check(release.await(20, TimeUnit.SECONDS)) { "fixture_http_response_hold_timeout" }
            val response = JSONObject().put("message_id", "http_$count").put("generation", 1).toString().toByteArray()
            val responseHeaders = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(responseHeaders.toByteArray(Charsets.US_ASCII)); write(response); flush()
            }
        } catch (error: Throwable) { reached.completeExceptionally(error) }
        finally {
            if (admitted) inFlight.decrementAndGet()
            sockets.remove(socket)
            socket.close()
        }
    }

    private fun line(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() <= 8192) {
            val value = input.read()
            check(value >= 0) { "fixture_http_unexpected_eof" }
            if (value == 10) {
                val raw = bytes.toByteArray()
                check(raw.isNotEmpty() && raw.last() == 13.toByte())
                return raw.copyOf(raw.size - 1).toString(Charsets.US_ASCII)
            }
            bytes.write(value)
        }
        error("fixture_http_header_too_long")
    }

    fun post(body: JSONObject, idempotencyKey: String): JSONObject {
        val uri = URI("http://127.0.0.1:${server.localPort}/synthetic/messages")
        val connection = uri.toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000; connection.readTimeout = 25000; connection.doOutput = true
            val bytes = body.toString().toByteArray()
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Idempotency-Key", idempotencyKey)
            connection.outputStream.use { it.write(bytes) }
            check(connection.responseCode == 200)
            return connection.inputStream.use { JSONObject(it.readAllBytes().toString(Charsets.UTF_8)) }
        } finally { connection.disconnect() }
    }

    fun releaseResponses() = release.countDown()
    override fun close() {
        closed.set(true); releaseResponses(); server.close()
        sockets.forEach { runCatching { it.close() } }
        executor.shutdownNow(); acceptThread.join(1000)
    }
}
