package com.dafeiyu.mobile.data

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.OkHttpClient

private data class Hit(val path: String, val start: Int, val end: Int, val ifMatch: String?)

fun main(args: Array<String>) = runBlocking {
    val root = File(args.single()).apply { mkdirs() }
    val data = ByteArray(21 * 1024 * 1024 + 17) { ((it * 31 + it / 7) and 255).toByte() }
    val hits = ConcurrentLinkedQueue<Hit>()
    val active = AtomicInteger()
    val maximum = AtomicInteger()
    var version = "v1"
    val pool = Executors.newCachedThreadPool()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = pool
        createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val content = if (path == "/git-hello") "hello".toByteArray() else data
            val total = when (path) {
                "/git-hello" -> content.size
                "/four", "/no-range" -> 2 * 1024 * 1024 + 3
                "/small" -> 1024 * 1024
                "/large" -> data.size
                else -> 7 * 1024 * 1024 + 17
            }
            val requested = exchange.requestHeaders.getFirst("Range")
            val range = requested?.let { Regex("bytes=(\\d+)-(\\d+)").matchEntire(it) }
            val acceptsRange = path != "/no-range"
            val start = if (acceptsRange && range != null) range.groupValues[1].toInt() else 0
            val end = if (acceptsRange && range != null) range.groupValues[2].toInt() else total - 1
            val etag = "\"$version\""
            hits += Hit(path, start, end, exchange.requestHeaders.getFirst("If-Match"))
            try {
                exchange.responseHeaders.add("ETag", if (path == "/etag-mismatch" && end > 0) "\"changed\"" else etag)
                if (acceptsRange && range != null) exchange.responseHeaders.add("Content-Range",
                    "bytes ${if (path == "/bad-range" && end > 0) start + 1 else start}-$end/$total")
                exchange.sendResponseHeaders(if (acceptsRange && range != null) 206 else 200, (end - start + 1).toLong())
                if (end > 0 && acceptsRange) {
                    val count = active.incrementAndGet()
                    maximum.accumulateAndGet(count, ::maxOf)
                    Thread.sleep(45)
                }
                try {
                    var position = start
                    while (position <= end) {
                        val count = minOf(16 * 1024, end - position + 1)
                        exchange.responseBody.write(content, position, count)
                        position += count
                        if (path == "/resume") Thread.sleep(3)
                    }
                } finally { if (end > 0 && acceptsRange) active.decrementAndGet() }
            } catch (_: Exception) { /* Client cancellation is an expected server outcome. */ }
            finally { exchange.close() }
        }
        start()
    }
    val endpoint = "http://127.0.0.1:${server.address.port}"
    val client = OkHttpClient.Builder().build()
    val downloader = ArtifactDownloader(root, { client })
    var passed = 0
    suspend fun test(name: String, block: suspend () -> Unit) {
        hits.clear(); maximum.set(0)
        block(); passed++; println("PASS $name")
    }
    fun request(path: String, name: String, size: Int = 7 * 1024 * 1024 + 17, algorithm: String = "SHA-256") =
        ArtifactDownloadRequest(listOf(endpoint + path), File(root, name),
            ArtifactChecksum(algorithm, MessageDigest.getInstance(algorithm).digest(data.copyOf(size))
                .joinToString("") { "%02x".format(it) }), size.toLong(), allowHttpForTests = true)
    try {
        test("one to six MiB downloads use four concurrent validated ranges") {
            val result = downloader.download(request("/four", "four.bin", 2 * 1024 * 1024 + 3))
            check(result.threads == 4 && maximum.get() == 4)
            check(hits.filter { it.path == "/four" && it.end > 0 }.size == 4)
            check(hits.filter { it.end > 0 }.all { it.ifMatch == "\"v1\"" })
        }
        test("over six MiB downloads use eight concurrent validated ranges") {
            val result = downloader.download(request("/eight", "eight.bin"))
            check(result.threads == 8 && maximum.get() == 8)
            check(result.file.readBytes().contentEquals(data.copyOf(7 * 1024 * 1024 + 17)))
        }
        test("over twenty MiB keeps eight concurrent data ranges") {
            val result = downloader.download(request("/large", "large.bin", data.size))
            check(result.threads == 8 && maximum.get() == 8)
            check(result.file.readBytes().contentEquals(data))
        }
        test("one MiB boundary uses a single data stream") {
            val result = downloader.download(request("/small", "small.bin", 1024 * 1024))
            check(result.threads == 1)
        }
        test("large non-range route is skipped for the next range-capable source") {
            val value = request("/four", "fallback.bin", 2 * 1024 * 1024 + 3)
                .copy(urls = listOf(endpoint + "/no-range", endpoint + "/four"))
            val result = downloader.download(value)
            check(result.threads == 4 && result.source.endsWith("/four"))
            check(hits.count { it.path == "/no-range" } == 1)
        }
        test("all non-range routes fall back with a real single-stream reason") {
            val progress = mutableListOf<ArtifactDownloadProgress>()
            val result = downloader.download(request("/no-range", "single.bin", 2 * 1024 * 1024 + 3)) { progress += it }
            check(result.threads == 1 && result.singleStreamReason?.contains("Range") == true)
            check(progress.last().receivedBytes == result.file.length())
        }
        test("SHA512 integrity metadata is supported") {
            val value = request("/four", "sha512.bin", 2 * 1024 * 1024 + 3, "SHA-512")
            val sri = "sha512-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512")
                .digest(data.copyOf(2 * 1024 * 1024 + 3)))
            check(ArtifactChecksum.parse(sri) == value.checksum)
            check(downloader.download(value).file.isFile)
        }
        test("verified cache does not access network again") {
            val value = request("/four", "four.bin", 2 * 1024 * 1024 + 3)
            check(downloader.download(value).source == "cache" && hits.isEmpty())
        }
        test("fixed Git blob validates object identity instead of raw SHA1 or ETag") {
            val value = ArtifactDownloadRequest(listOf(endpoint + "/git-hello"), File(root, "git-hello.bin"),
                ArtifactChecksum.gitBlob("b6fc4c620b67d95f953a5c1c1230aaab5db5a1b0"), expectedSize = 5,
                allowHttpForTests = true)
            check(downloader.download(value).file.readText() == "hello")
            hits.clear()
            check(downloader.download(value).source == "cache" && hits.isEmpty())
            check(runCatching { downloader.download(value.copy(destination = File(root, "git-raw.bin"),
                checksum = ArtifactChecksum.gitBlob("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d"))) }.isFailure)
            check(!File(root, "git-raw.bin").exists())
        }
        test("Git integrity requires explicit fixed size and does not accept bare SHA1") {
            check(runCatching { ArtifactChecksum.parse("b6fc4c620b67d95f953a5c1c1230aaab5db5a1b0") }.isFailure)
            val value = ArtifactDownloadRequest(listOf(endpoint + "/git-hello"), File(root, "git-no-size.bin"),
                ArtifactChecksum.gitBlob("b6fc4c620b67d95f953a5c1c1230aaab5db5a1b0"), allowHttpForTests = true)
            check(runCatching { downloader.download(value) }.isFailure && hits.isEmpty())
            val empty = File(root, "git-empty.bin").apply { writeBytes(byteArrayOf()) }
            check(downloader.download(value.copy(destination = empty, expectedSize = 0,
                checksum = ArtifactChecksum.gitBlob("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391"))).source == "cache")
        }
        test("bad checksum preserves an existing target and removes invalid partials") {
            val value = request("/four", "invalid.bin", 2 * 1024 * 1024 + 3).copy(checksum = ArtifactChecksum("SHA-256", "0".repeat(64)))
            value.destination.writeText("previous")
            check(runCatching { downloader.download(value) }.isFailure)
            check(value.destination.readText() == "previous")
            check(!File(root, ".invalid.bin.download").exists())
        }
        test("mismatched Content-Range is rejected without publishing output") {
            val value = request("/bad-range", "bad-range.bin")
            check(runCatching { downloader.download(value) }.isFailure && !value.destination.exists())
        }
        test("resource validator changes are rejected without publishing output") {
            val value = request("/etag-mismatch", "bad-etag.bin")
            check(runCatching { downloader.download(value) }.isFailure && !value.destination.exists())
        }
        test("cancelled ranges resume only against unchanged resource validator") {
            val value = request("/resume", "resume.bin")
            val ready = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.IO) { downloader.download(value) {
                if (it.receivedBytes > 300_000) ready.complete(Unit)
            } }
            withTimeout(10_000) { ready.await() }
            job.cancelAndJoin()
            check(!value.destination.exists())
            val result = downloader.download(value)
            check(result.resumedBytes > 0 && result.file.readBytes().contentEquals(data.copyOf(7 * 1024 * 1024 + 17)))
        }
        test("changed ETag discards cancelled partials before retry") {
            val value = request("/resume", "changed.bin")
            val ready = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.IO) { downloader.download(value) {
                if (it.receivedBytes > 300_000) ready.complete(Unit)
            } }
            withTimeout(10_000) { ready.await() }; job.cancelAndJoin()
            version = "v2"
            val result = downloader.download(value)
            check(result.resumedBytes == 0L && result.file.readBytes().contentEquals(data.copyOf(7 * 1024 * 1024 + 17)))
        }
        test("HTTP is forbidden outside explicit loopback test transport") {
            check(runCatching { downloader.download(request("/four", "http.bin", 2 * 1024 * 1024 + 3)
                .copy(allowHttpForTests = false)) }.isFailure)
        }
        test("destination escape and protocol header overrides fail closed") {
            val value = request("/four", "bad-path.bin", 2 * 1024 * 1024 + 3)
            check(runCatching { downloader.download(value.copy(destination = File(root, "../escape.bin"))) }.isFailure)
            check(runCatching { downloader.download(value.copy(headers = mapOf("Range" to "bytes=0-1"))) }.isFailure)
        }
        println("RESULT $passed/$passed production downloader checks with real loopback HTTP and concurrent IO; TLS/Android not exercised")
    } finally {
        server.stop(0); pool.shutdownNow()
        client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
    }
}
