package com.dafeiyu.mobile.data

import java.io.File
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

private val binding = RemoteDshScope("fixture-node", "fixture-group", "fixture-phone", "fixture-computer")
private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
private fun ticket(content: ByteArray) = RemoteArtifactTicket("ticket-fixture", "workspace-a", "out.bin", "out.bin", "a".repeat(64),
    content.size.toLong(), digest(content), RemoteArtifactProtocol.CHUNK_BYTES, System.currentTimeMillis() + 60_000)

fun main(args: Array<String>) = runBlocking {
    val root = File(args.single()).apply { check(mkdirs()) }
    val client = OkHttpClient.Builder().followRedirects(false).readTimeout(8, TimeUnit.SECONDS).build()
    var passed = 0
    suspend fun checkCase(name: String, block: suspend () -> Unit) { block(); passed++; println("PASS $passed $name") }
    class Source(val content: ByteArray) : RemoteArtifactCall {
        val metadata = ticket(content)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val revoked = AtomicBoolean()
        val hold = AtomicBoolean()
        override suspend fun request(operation: String, payload: JSONObject, binding: RemoteDshScope): JSONObject {
            check(operation == "artifact.read" && binding == com.dafeiyu.mobile.data.binding && payload.getString("ticket") == metadata.ticket)
            if (revoked.get()) throw AriesProviderException("revoked")
            val count = active.incrementAndGet(); maximum.accumulateAndGet(count, ::maxOf)
            try {
                while (hold.get()) delay(10)
                delay(1)
                if (revoked.get()) throw AriesProviderException("revoked")
                val offset = payload.getLong("offset")
                val length = payload.getInt("length")
                val end = minOf(content.size.toLong(), offset + length).toInt()
                return JSONObject().put("ticket", metadata.ticket).put("offset", offset).put("size", content.size)
                    .put("version", metadata.version).put("sha256", metadata.sha256)
                    .put("bytesBase64", Base64.getEncoder().encodeToString(content.copyOfRange(offset.toInt(), end)))
            } finally { active.decrementAndGet() }
        }
        fun proxy(): RemoteArtifactLoopback = RemoteArtifactLoopback(binding, metadata, this) {
            check(it == binding)
            if (revoked.get()) throw AriesProviderException("revoked")
        }
    }
    fun request(source: Source, proxy: RemoteArtifactLoopback, name: String, resume: String? = null) = ArtifactDownloadRequest(
        listOf(proxy.url), File(root, name), ArtifactChecksum("SHA-256", source.metadata.sha256), source.content.size.toLong(),
        allowedLoopbackPort = proxy.port, loopbackResumeIdentity = resume,
    )
    try {
        for ((size, threads) in listOf(2 * 1024 * 1024 + 11 to 4, 7 * 1024 * 1024 + 3 to 8)) {
            checkCase("$threads real loopback ranges use simultaneous bounded remote reads and digest-before-commit") {
                val source = Source(ByteArray(size) { (it * 31 % 251).toByte() })
                source.proxy().use { proxy ->
                    val result = ArtifactDownloader(root, { client }).download(request(source, proxy, "$threads.bin"))
                    check(result.threads == threads && source.maximum.get() == threads)
                    check(result.file.readBytes().contentEquals(source.content))
                }
            }
        }
        checkCase("an empty selected artifact retains its real digest and completes without fabricated bytes") {
            val source = Source(byteArrayOf())
            source.proxy().use { proxy ->
                val result = ArtifactDownloader(root, { client }).download(request(source, proxy, "empty.bin"))
                check(result.file.isFile && result.file.length() == 0L && result.threads == 1)
            }
        }
        checkCase("cancellation preserves version-bound parts and a fresh random loopback resumes them") {
            val source = Source(ByteArray(7 * 1024 * 1024 + 17) { (it % 239).toByte() })
            val identity = "b".repeat(64)
            source.proxy().use { proxy ->
                try {
                    ArtifactDownloader(root, { client }).download(request(source, proxy, "resume.bin", identity)) {
                        if (it.receivedBytes > 800_000) throw CancellationException("fixture pause")
                    }
                    error("cancellation expected")
                } catch (_: CancellationException) { }
            }
            check(!File(root, "resume.bin").exists())
            source.proxy().use { proxy ->
                val result = ArtifactDownloader(root, { client }).download(request(source, proxy, "resume.bin", identity))
                check(result.resumedBytes > 0 && result.file.readBytes().contentEquals(source.content))
            }
        }
        checkCase("revocation during concurrent remote reads cannot publish a local completed file") {
            val source = Source(ByteArray(2 * 1024 * 1024) { (it % 127).toByte() })
            source.hold.set(true)
            source.proxy().use { proxy ->
                val pending = async { runCatching { ArtifactDownloader(root, { client }).download(request(source, proxy, "revoked.bin")) } }
                while (source.active.get() < 4) delay(5)
                source.revoked.set(true); source.hold.set(false)
                check(pending.await().isFailure && !File(root, "revoked.bin").exists())
            }
        }
        checkCase("the production loopback allowance refuses wrong ports, localhost aliases and external HTTP") {
            val source = Source(byteArrayOf(1))
            source.proxy().use { proxy ->
                for (url in listOf(proxy.url.replace("127.0.0.1", "localhost"), "http://example.com/file")) {
                    check(runCatching { ArtifactDownloader(root, { client }).download(request(source, proxy, "blocked.bin").copy(urls = listOf(url))) }.isFailure)
                }
                val wrong = if (proxy.port == 65535) proxy.port - 1 else proxy.port + 1
                check(runCatching { ArtifactDownloader(root, { client }).download(request(source, proxy, "blocked.bin").copy(allowedLoopbackPort = wrong)) }.isFailure)
            }
            check(!File(root, "blocked.bin").exists())
        }
        checkCase("random endpoint, Host, Origin and version validators constrain local access") {
            val source = Source(byteArrayOf(1, 2, 3))
            source.proxy().use { proxy ->
                for ((url, header, value, status) in listOf(
                    listOf(proxy.url.substringBeforeLast('/') + "/unknown", "Host", "127.0.0.1:${proxy.port}", 404),
                    listOf(proxy.url, "Host", "other-origin.example", 403),
                    listOf(proxy.url, "Origin", "https://other-origin.example", 403),
                    listOf(proxy.url, "If-Match", "\"another-version\"", 412))) {
                    client.newCall(Request.Builder().url(url as String).header(header as String, value as String).build()).execute().use { check(it.code == status) }
                }
                Socket("127.0.0.1", proxy.port).use { socket ->
                    socket.soTimeout = 2000
                    socket.getOutputStream().write("GET /${proxy.url.substringAfterLast('/')} HTTP/1.1\r\nHost: 127.0.0.1:${proxy.port}\r\nHost: other\r\n\r\n".toByteArray())
                    check(socket.getInputStream().bufferedReader().readLine().contains("400"))
                }
            }
            check(source.maximum.get() == 0)
        }
        checkCase("protocol parsing refuses mismatched files, fractional sizes and noncanonical byte payloads") {
            val selected = RemoteArtifactEntry("out.bin", "out.bin", false, 3, "a".repeat(64))
            val value = JSONObject().put("workspaceId", "workspace-a").put("directory", "").put("nextCursor", JSONObject.NULL)
                .put("entries", JSONArray().put(JSONObject().put("name", "out.bin").put("relativePath", "out.bin").put("kind", "file")
                    .put("size", 3).put("version", selected.version)))
            check(RemoteArtifactProtocol.listing(value, "workspace-a", "").entries.single() == selected)
            check(runCatching { RemoteArtifactProtocol.listing(value, "other", "") }.isFailure)
            value.getJSONArray("entries").getJSONObject(0).put("size", 3.0)
            check(runCatching { RemoteArtifactProtocol.listing(value, "workspace-a", "") }.isFailure)
            val source = Source(byteArrayOf(1, 2, 3))
            val bytes = source.request("artifact.read", JSONObject().put("ticket", source.metadata.ticket).put("offset", 0).put("length", 3), binding)
            bytes.put("bytesBase64", "AQID=")
            check(runCatching { RemoteArtifactProtocol.bytes(bytes, source.metadata, 0, 3) }.isFailure)
            for (bad in listOf("../a", "/a", "a\\b", "a/../b", "a\u0000b")) check(runCatching { RemoteArtifactProtocol.path(bad) }.isFailure)
        }
    } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    println("$passed PASS; real loopback HTTP and ArtifactDownloader; remote requests are synthetic, no Aries/Android/OEM device claims.")
}
