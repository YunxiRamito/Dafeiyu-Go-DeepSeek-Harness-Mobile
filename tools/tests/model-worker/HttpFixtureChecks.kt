package com.dafeiyu.mobile.data

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

private var passed = 0

private suspend fun checkCase(name: String, block: suspend () -> Unit) {
    withTimeout(10_000) { block() }
    passed++
    println("PASS $name")
}

/** Access the exact Android instrumentation fixture, rather than a copied HTTP implementation. */
private class FixtureAccess : AutoCloseable {
    private val type = Class.forName("com.dafeiyu.mobile.PiAiWorkerSmokeTest\$FixtureServer")
    private val instance = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

    private fun invoke(name: String): Any? = try {
        type.getDeclaredMethod(name).apply { isAccessible = true }.invoke(instance)
    } catch (failure: InvocationTargetException) { throw failure.targetException }

    val port: Int get() = invoke("getPort") as Int
    val requests: AtomicInteger get() = invoke("getRequests") as AtomicInteger
    val unstartedConnectionsClosed: AtomicInteger get() = invoke("getUnstartedConnectionsClosed") as AtomicInteger
    @Suppress("UNCHECKED_CAST")
    val blocked: CompletableDeferred<Unit> get() = invoke("getBlocked") as CompletableDeferred<Unit>
    @Suppress("UNCHECKED_CAST")
    val disconnected: CompletableDeferred<Unit> get() = invoke("getDisconnected") as CompletableDeferred<Unit>
    @Suppress("UNCHECKED_CAST")
    val errors: ConcurrentLinkedQueue<Throwable> get() = type.getDeclaredField("errors")
        .apply { isAccessible = true }.get(instance) as ConcurrentLinkedQueue<Throwable>

    fun requireHealthy() { invoke("requireHealthy") }

    fun sendAndCloseOutput(prefix: String) {
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write(prefix.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            socket.shutdownOutput()
            socket.soTimeout = 2000
            check(socket.getInputStream().read() == -1)
        }
    }

    override fun close() { (instance as AutoCloseable).close() }
}

private suspend fun rejected(prefix: String, expectedMessage: String? = null) = rejected({ prefix }, expectedMessage)

private suspend fun rejected(prefix: (Int) -> String, expectedMessage: String? = null) {
    FixtureAccess().use { fixture ->
        fixture.sendAndCloseOutput(prefix(fixture.port))
        while (fixture.errors.isEmpty()) delay(10)
        val failure = runCatching { fixture.requireHealthy() }.exceptionOrNull()
        check(failure is IllegalStateException && failure.cause === fixture.errors.peek())
        if (expectedMessage != null) check(failure.cause?.message?.contains(expectedMessage) == true)
        check(fixture.requests.get() == 0 && fixture.unstartedConnectionsClosed.get() == 0)
    }
}

private fun input(value: String) = JSONObject().put("messages", JSONArray().put(JSONObject()
    .put("role", "user").put("content", value).put("timestamp", 1_700_000_000_000L)))
    .put("tools", JSONArray().put(JSONObject().put("name", "host_only").put("description", "Host-only fixture tool")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("value", JSONObject().put("type", "string"))))))

/** Host JVM + real Node/pi-ai loopback only; no Android process or runtime lease claim. */
fun main(args: Array<String>) = runBlocking {
    check(args.size == 4)
    checkCase("a connection closed before any request byte is not an HTTP request or fixture error") {
        FixtureAccess().use { fixture ->
            fixture.sendAndCloseOutput("")
            while (fixture.unstartedConnectionsClosed.get() != 1) delay(10)
            fixture.requireHealthy()
            check(fixture.requests.get() == 0)
        }
    }
    checkCase("partial request-line EOF still fails with the original failure cause") {
        rejected("POST", "before LF (4 bytes)")
    }
    checkCase("EOF before the first header fails after a request line has started") {
        rejected("POST /v1/chat/completions HTTP/1.1\r\n", "before LF (0 bytes)")
    }
    checkCase("partial header EOF still fails") {
        rejected("POST /v1/chat/completions HTTP/1.1\r\nHost: loopback", "before LF (14 bytes)")
    }
    checkCase("an oversized request line still fails the original 8192-byte bound") {
        rejected("P".repeat(8192) + "\n", "exceeded 8192 bytes")
    }
    checkCase("an unexpected HTTP verb still fails") {
        rejected("GET /v1/chat/completions HTTP/1.1\r\n")
    }
    checkCase("a truncated body still fails rather than being treated as an unused connection") {
        rejected({ port -> "POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
            "Authorization: Bearer SYNTHETIC_LOOPBACK_ONLY\r\nContent-Length: 4\r\n\r\n{}" }, "body ended before declared length")
    }
    FixtureAccess().use { fixture ->
        val workDirectory = File(args[3], "host-controller").apply { check(isDirectory || mkdirs()) }
        val configuration = JSONObject().put("revision", 1).put("providers", JSONArray().put(JSONObject()
            .put("id", "fixture").put("api", "openai-completions").put("baseUrl", "http://127.0.0.1:${fixture.port}/v1")
            .put("models", JSONArray().put(JSONObject().put("id", "fixture-model").put("reasoning", false)))))
            .put("credentials", JSONObject().put("fixture", JSONObject().put("type", "api_key").put("key", "SYNTHETIC_LOOPBACK_ONLY")))
        var process: Process? = null
        lateinit var managed: LinuxManagedProcess
        val worker = PiAiNodeWorker(
            launchProcess = {
                val launched = ProcessBuilder(args[0], args[1], args[2]).directory(workDirectory).apply {
                    environment().keys.removeAll { it !in setOf("PATH", "Path", "SYSTEMROOT", "SystemRoot") }
                    environment()["OTEL_SDK_DISABLED"] = "true"
                }.start()
                process = launched
                // Android35's Process API stub lacks the host JVM pid method.
                val hostPid = (Process::class.java.getMethod("pid").invoke(launched) as Long).toInt()
                managed = LinuxManagedProcess("host-fixture-worker", hostPid, "host-fixture-slot", launched) {
                    launched.destroy()
                    if (!launched.waitFor(3, TimeUnit.SECONDS)) launched.destroyForcibly()
                    check(launched.waitFor(5, TimeUnit.SECONDS))
                }
                Thread({ launched.waitFor(); managed.onExited() }, "host-pi-worker-exit").apply { isDaemon = true; start() }
                managed
            },
            providerConfiguration = { JSONObject(configuration.toString()) },
            saveCredential = { _, _, _ -> error("This loopback fixture never performs login") },
            recovery = fixtureModelRecoveryAccess(),
        )
        try {
            worker.start(File(args[2]))
            checkCase("production Kotlin controller streams real pinned SDK text and tool data") {
                val events = mutableListOf<JSONObject>()
                check(worker.stream("fixture", "fixture-model", input("stream"), requestId = "host-stream") { events += it }.getBoolean("finished"))
                check(events.any { it.optString("type") == "text_delta" && it.optString("delta") == "offline fixture" })
                check(events.any { it.optString("type") == "toolcall_end" && it.getJSONObject("toolCall").getString("name") == "host_only" })
                fixture.requireHealthy()
                check(fixture.requests.get() == 1)
            }
            checkCase("production coroutine cancellation aborts HTTP and preserves the live worker for later requests") {
                coroutineScope {
                    val pending = async { worker.stream("fixture", "fixture-model", input("block"), requestId = "host-cancel") {} }
                    fixture.blocked.await()
                    pending.cancelAndJoin()
                    check(pending.isCancelled)
                    fixture.disconnected.await()
                    check(process?.isAlive == true)
                    check(worker.catalog().getJSONArray("providers").getJSONObject(0).getString("id") == "fixture")
                    val events = mutableListOf<JSONObject>()
                    worker.stream("fixture", "fixture-model", input("after-cancel"), requestId = "host-after-cancel") { events += it }
                    check(events.any { it.optString("type") == "text_delta" && it.optString("delta") == "offline fixture" })
                    fixture.requireHealthy()
                    check(fixture.requests.get() == 3)
                }
                println("  observed unstarted connections closed: ${fixture.unstartedConnectionsClosed.get()}")
            }
            checkCase("production controller preserves model and effort refusal before any additional HTTP") {
                for ((model, effort) in listOf("unknown-model" to null, "fixture-model" to "invented")) {
                    val failure = runCatching { worker.stream("fixture", model, input("reject"), reasoning = effort) {} }.exceptionOrNull()
                    check(failure is java.io.IOException)
                    check(failure.message?.startsWith(if (effort == null) "UNKNOWN_MODEL:" else "UNSUPPORTED_REASONING:") == true)
                }
                check(fixture.requests.get() == 3)
                fixture.requireHealthy()
            }
            checkCase("production stop waits for the real host worker process to exit") {
                worker.stop()
                managed.awaitExit()
                check(process?.isAlive == false)
            }
        } finally {
            worker.stop()
            process?.let { if (it.isAlive) { it.destroyForcibly(); it.waitFor(5, TimeUnit.SECONDS) } }
        }
    }
    println("HTTP fixture and real production controller cancellation: $passed PASS; host JVM/Node only, zero paid requests")
}
