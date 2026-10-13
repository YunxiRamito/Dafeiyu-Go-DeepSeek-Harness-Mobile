package com.dafeiyu.mobile.data

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private var passed = 0
private suspend fun checkCase(name: String, block: suspend () -> Unit) {
    withTimeout(10_000) { block() }
    println("PASS $name")
    passed++
}

private class ProbeInput : InputStream() {
    private val bytes = LinkedBlockingQueue<Int>()
    fun send(value: String) { value.toByteArray(Charsets.UTF_8).forEach { bytes.put(it.toInt() and 255) } }
    override fun read(): Int = bytes.take()
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val first = read()
        if (first < 0) return -1
        buffer[offset] = first.toByte()
        var count = 1
        while (count < length) {
            val value = bytes.poll() ?: break
            if (value < 0) { bytes.offer(value); break }
            buffer[offset + count++] = value.toByte()
        }
        return count
    }
    override fun close() { bytes.offer(-1) }
}

private class DshFixture(val token: String, private val extra: String = "", private val stopFailure: Boolean = false) : Process() {
    val standard = ProbeInput()
    val managed = LinuxManagedProcess("fixture-dsh", ids.incrementAndGet(), "fixture-slot", this) {
        if (stopFailure) throw IOException("fixture refused termination")
        shutdown()
    }
    @Volatile var live = true
    val stopCount = AtomicInteger()
    fun started(port: Int) { standard.send(extra + "dsh web: http://127.0.0.1:$port/?token=$token\n") }
    fun silentDeath() { live = false }
    fun releaseExit() { managed.onExited() }
    fun shutdown() { stopCount.incrementAndGet(); live = false; standard.close(); managed.onExited() }
    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
    override fun getInputStream(): InputStream = standard
    override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
    override fun waitFor(): Int { while (live) Thread.yield(); return 0 }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !live
    override fun exitValue(): Int { check(!live); return 0 }
    override fun isAlive(): Boolean = live
    override fun destroy() { shutdown() }
    companion object { val ids = AtomicInteger(4000) }
}

private fun fixtureSlot(): DshRuntimeSlot = DshRuntimeSlot(File("unused"), DshRuntimeManifest(
    1, "fixture-dsh", "0.2.1-alpha.1", "24.19.0", "https://example.invalid/bundle.tar.gz",
    "a".repeat(64), 1, 1, "node_modules/@deepseek-ai/dsh/lib/bin.js"), 0)

private fun fixtureManager(queue: Channel<DshFixture>, health: suspend (String) -> DshHealthResult?, timeout: Long = 3000) = DshRuntimeManager(
    currentSlot = ::fixtureSlot,
    launchProcess = { _, port, _ -> queue.receive().also { it.started(port) }.managed },
    queryHealth = health,
    startupTimeoutMs = timeout,
)

private fun server(mode: String): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
    createContext("/") { request ->
        val port = address.port
        val name = "dsh-auth-" + Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest("127.0.0.1:$port".toByteArray()))
        val cookie = "$name=v1.fixture.${"a".repeat(43)}"
        if (mode == "contract" && request.requestURI.rawQuery != null) {
            request.responseHeaders.set("Location", "./")
            request.responseHeaders.set("Cache-Control", "no-store")
            request.responseHeaders.set("Set-Cookie", "$cookie; Path=/; HttpOnly; SameSite=Strict")
            request.sendResponseHeaders(303, -1)
        } else {
            val body = (if (mode == "contract" && request.requestHeaders.getFirst("Cookie") == cookie)
                "<html><head><script>window.__DSH_BOOT__={}</script></head></html>" else "unrelated server").toByteArray()
            request.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
            request.sendResponseHeaders(if (mode == "unauthorized") 401 else if (mode == "missing") 404 else 200, body.size.toLong())
            request.responseBody.use { it.write(body) }
        }
        request.close()
    }
    start()
}

fun main() = runBlocking {
    val oldToken = "a".repeat(43)
    val nextToken = "b".repeat(43)
    checkCase("only the exact DSH launch line for the owned port yields an access URL") {
        val url = "http://127.0.0.1:4311/?token=$oldToken"
        check(DshWebHealth.accessLine("dsh web: $url", 4311) == url)
        check(DshWebHealth.accessLine("other: $url", 4311) == null)
        check(DshWebHealth.accessLine("dsh web: $url", 4312) == null)
        check(DshWebHealth.accessLine("dsh web: ${url.replace("127.0.0.1", "localhost")}", 4311) == null)
        check(DshWebHealth.accessLine("dsh web: $url&other=1", 4311) == null)
        check(DshWebHealth.accessLine("dsh web: $url#fragment", 4311) == null)
    }
    checkCase("real loopback 303 cookie exchange and boot HTML are required") {
        val http = server("contract")
        try { check(DshWebHealth.check("http://127.0.0.1:${http.address.port}/?token=$oldToken")?.code == 200) }
        finally { http.stop(0) }
    }
    for (mode in listOf("unrelated", "unauthorized", "missing")) checkCase("$mode HTTP response cannot mark DSH healthy") {
        val http = server(mode)
        try { check(DshWebHealth.check("http://127.0.0.1:${http.address.port}/?token=$oldToken") == null) }
        finally { http.stop(0) }
    }
    checkCase("late old health and startup cleanup cannot clobber a replacement session") {
        val queue = Channel<DshFixture>(2)
        val old = DshFixture(oldToken); val next = DshFixture(nextToken)
        queue.send(old); queue.send(next)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val manager = fixtureManager(queue, { url ->
            if (url.contains(oldToken)) { entered.complete(Unit); release.await() }
            DshHealthResult(200)
        })
        try {
            val startOld = async { runCatching { manager.start(requestedPort = 4311) } }
            entered.await(); manager.stop()
            check(manager.start(requestedPort = 4312).status == DshServiceStatus.RUNNING)
            release.complete(Unit)
            check(startOld.await().isFailure)
            check(manager.snapshot.value.status == DshServiceStatus.RUNNING && manager.snapshot.value.port == 4312)
            check(manager.health()?.code == 200)
        } finally { release.complete(Unit); manager.stop(); old.shutdown(); next.shutdown(); manager.close() }
    }
    checkCase("late old process exit leaves the replacement process running") {
        val queue = Channel<DshFixture>(2); val old = DshFixture(oldToken); val next = DshFixture(nextToken)
        queue.send(old); queue.send(next)
        val manager = fixtureManager(queue, { DshHealthResult(200) })
        try {
            manager.start(requestedPort = 4311); old.silentDeath()
            manager.start(requestedPort = 4312); old.releaseExit(); delay(100)
            check(manager.snapshot.value.status == DshServiceStatus.RUNNING && manager.snapshot.value.pid == next.managed.pid)
        } finally { manager.stop(); old.shutdown(); next.shutdown(); manager.close() }
    }
    checkCase("verbose stdout beyond 256 KiB still drains to the authentic launch line") {
        val queue = Channel<DshFixture>(1); val process = DshFixture(oldToken, ("diagnostic line\n".repeat(20000)))
        queue.send(process)
        val manager = fixtureManager(queue, { DshHealthResult(200) })
        try { check(manager.start(requestedPort = 4311).status == DshServiceStatus.RUNNING) }
        finally { manager.stop(); manager.close() }
    }
    checkCase("unexpected process exit error survives refresh until a real new transition") {
        val queue = Channel<DshFixture>(1); val process = DshFixture(oldToken); queue.send(process)
        val manager = fixtureManager(queue, { DshHealthResult(200) })
        try {
            manager.start(requestedPort = 4311); process.shutdown(); delay(50)
            val failure = manager.snapshot.value
            check(failure.status == DshServiceStatus.ERROR)
            check(manager.refresh().status == DshServiceStatus.ERROR)
        } finally { manager.stop(); manager.close() }
    }
    checkCase("startup exit remains an actual failure after the watcher clears the current session") {
        val queue = Channel<DshFixture>(1); val process = DshFixture(oldToken); queue.send(process)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val manager = fixtureManager(queue, { entered.complete(Unit); release.await(); null })
        try {
            val start = async { runCatching { manager.start(requestedPort = 4311) } }
            entered.await(); process.shutdown()
            withTimeout(2000) { while (manager.snapshot.value.status != DshServiceStatus.ERROR) delay(10) }
            release.complete(Unit)
            val failure = start.await().exceptionOrNull()
            check(failure is IOException && failure.message?.contains("DSH 服务进程提前退出") == true)
            check(manager.snapshot.value.status == DshServiceStatus.ERROR && manager.snapshot.value.lastError?.contains("提前退出") == true)
        } finally { release.complete(Unit); manager.stop(); manager.close() }
    }
    checkCase("failed stop retains the exact process and refuses a second launch") {
        val queue = Channel<DshFixture>(1); val process = DshFixture(oldToken, stopFailure = true); queue.send(process)
        val manager = fixtureManager(queue, { DshHealthResult(200) })
        try {
            manager.start(requestedPort = 4311)
            check(runCatching { manager.stop() }.isFailure)
            check(manager.snapshot.value.pid == process.managed.pid && process.live)
            manager.start(requestedPort = 4312)
            check(manager.snapshot.value.pid == process.managed.pid)
        } finally { process.shutdown(); delay(20); manager.stop(); manager.close() }
    }
    checkCase("close terminates its owned process and prohibits new launches") {
        val queue = Channel<DshFixture>(1); val process = DshFixture(oldToken); queue.send(process)
        val manager = fixtureManager(queue, { DshHealthResult(200) })
        manager.start(requestedPort = 4311); manager.close()
        withTimeout(2000) { while (process.live) delay(10) }
        check(runCatching { manager.start() }.isFailure)
    }
    println("DSH_LIFECYCLE_CHECKS=$passed; production manager + real loopback HTTP + controlled Process pipes. No Android process or paid model call.")
}
