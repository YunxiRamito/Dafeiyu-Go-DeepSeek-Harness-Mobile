package com.dafeiyu.mobile

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dafeiyu.mobile.data.AndroidOfficialCredentialJournal
import com.dafeiyu.mobile.data.JournalModelRuntimeRecoveryAccess
import com.dafeiyu.mobile.data.LinuxArchiveExtractor
import com.dafeiyu.mobile.data.LinuxComponentStatus
import com.dafeiyu.mobile.data.LinuxRuntimeManager
import com.dafeiyu.mobile.data.ModelProviderConfig
import com.dafeiyu.mobile.data.ModelProviderStore
import com.dafeiyu.mobile.data.PiAiNodeWorker
import com.dafeiyu.mobile.data.PiAiRuntimeInstaller
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Installs the frozen package graph from verified local cache and runs the
 * real Android launcher/controller. Streams reach only a loopback fixture. */
@RunWith(AndroidJUnit4::class)
class PiAiWorkerSmokeTest {
    @Test
    fun actualAndroidWorkerCatalogStreamAndCancel(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        check(context.packageName == "com.dafeiyu.mobile.dev")
        check(android.os.Build.SUPPORTED_ABIS.firstOrNull() == "x86_64")
        val runtime = LinuxRuntimeManager(context)
        runtime.refresh()
        check(runtime.snapshot.value.first { it.id == "node" }.status == LinuxComponentStatus.READY) {
            "Run the verified native x86_64 runtime smoke first"
        }
        val reportFile = File(context.filesDir, "model-worker-smoke-report.json")
        val results = JSONArray()
        val report = JSONObject().put("schema", 1).put("package", context.packageName).put("abi", "x86_64")
            .put("started_at", System.currentTimeMillis()).put("paidModelRequests", 0).put("checks", results).put("passed", false)
        val id = UUID.randomUUID().toString()
        val data = File(context.filesDir, "model-worker-smoke/$id").apply { check(mkdirs()) }
        val vault = File(context.noBackupFilesDir, "model-worker-smoke/$id").apply { check(mkdirs()) }
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = data
            override fun getNoBackupFilesDir(): File = vault
        }
        val store = ModelProviderStore(isolated)
        val installer = PiAiRuntimeInstaller(isolated, runtime)
        val publicWorker = PiAiNodeWorker(isolated, store, runtime)
        var fixtureWorker: PiAiNodeWorker? = null
        var server: FixtureServer? = null
        fun persist() { reportFile.writeText(report.toString(2), Charsets.UTF_8) }
        suspend fun checkCase(name: String, block: suspend () -> JSONObject) {
            val entry = JSONObject().put("name", name).put("passed", false)
            results.put(entry); persist()
            try { entry.put("evidence", withTimeout(180_000) { block() }).put("passed", true) }
            catch (failure: Throwable) { entry.put("error", failure.javaClass.simpleName); throw failure }
            finally { persist() }
        }
        try {
            checkCase("stage complete fixed dependency graph with lock and archive checksums") {
                stageCache(context, data)
            }
            var networkTransfers = 0
            var completed = 0
            var packageCount = 0
            lateinit var component: File
            checkCase("actual PiAiRuntimeInstaller extracts all pinned package identities from cache") {
                component = installer.install("official") { progress ->
                    if (progress.threads > 0) networkTransfers++
                    completed = progress.completedPackages
                    packageCount = progress.totalPackages
                }
                check(networkTransfers == 0 && completed == 89 && packageCount == 89 && installer.current() == component)
                val manifest = JSONObject(File(component, "node_modules/@earendil-works/pi-ai/package.json").readText())
                check(manifest.getString("name") == "@earendil-works/pi-ai" && manifest.getString("version") == "0.87.1")
                JSONObject().put("packages", completed).put("version", manifest.getString("version"))
                    .put("networkTransfers", networkTransfers).put("cacheOnly", true)
            }
            checkCase("public Android worker launches without DSH and returns real built-in catalogs and auth modes") {
                for (provider in listOf("anthropic", "amazon-bedrock", "google-vertex")) {
                    store.upsert(ModelProviderConfig(provider, provider, builtinProvider = provider))
                }
                publicWorker.start(component)
                val catalog = publicWorker.catalog()
                val providers = catalog.getJSONArray("providers")
                check(catalog.getString("version") == "0.87.1" && catalog.getJSONArray("apis").length() == 10 && providers.length() == 4)
                for (index in 0 until providers.length()) {
                    val provider = providers.getJSONObject(index)
                    check(provider.getJSONArray("models").length() > 0 && !provider.getBoolean("configured"))
                }
                val anthropic = (0 until providers.length()).map { providers.getJSONObject(it) }.single { it.getString("id") == "anthropic" }
                check((0 until anthropic.getJSONArray("authMethods").length()).any { anthropic.getJSONArray("authMethods").getString(it) == "oauth" })
                publicWorker.stop()
                waitWorkerReleased(runtime)
                JSONObject().put("version", catalog.getString("version")).put("apis", catalog.getJSONArray("apis"))
                    .put("providerCount", providers.length()).put("realAndroidProcess", true).put("dshServiceStarted", false)
            }
            val fixtureServer = FixtureServer().also { server = it }
            val configuration = JSONObject().put("revision", 1).put("providers", JSONArray().put(JSONObject()
                .put("id", "fixture").put("name", "Offline loopback fixture").put("api", "openai-completions")
                .put("baseUrl", "http://127.0.0.1:${fixtureServer.port}/v1")
                .put("models", JSONArray().put(JSONObject().put("id", "fixture-model").put("reasoning", false)))))
                .put("credentials", JSONObject().put("fixture", JSONObject().put("type", "api_key").put("key", "SYNTHETIC_LOOPBACK_ONLY")))
            val worker = PiAiNodeWorker(
                launchProcess = { directory -> PiAiNodeWorker.launchAndroid(isolated, runtime, directory) },
                providerConfiguration = { JSONObject(configuration.toString()) },
                saveCredential = { _, _, _ -> error("This offline test has no login or credential refresh") },
                recovery = JournalModelRuntimeRecoveryAccess(AndroidOfficialCredentialJournal(isolated)),
            ).also { fixtureWorker = it }
            worker.start(component)
            checkCase("actual Android stream preserves text and tool data without executing tools") {
                val events = CopyOnWriteArrayList<JSONObject>()
                val result = worker.stream("fixture", "fixture-model", input("stream"), requestId = "android-stream") { events += it }
                check(result.getBoolean("finished") && events.any { it.optString("type") == "text_delta" && it.optString("delta") == "offline fixture" })
                check(events.any { it.optString("type") == "toolcall_end" && it.getJSONObject("toolCall").getString("name") == "host_only" })
                check(events.none { it.toString().contains("diagnostics") || it.toString().contains("SYNTHETIC_LOOPBACK_ONLY") })
                fixtureServer.requireHealthy()
                check(fixtureServer.requests.get() == 1 && fixtureServer.toolRequests.get() == 1)
                JSONObject().put("eventTypes", JSONArray(events.map { it.getString("type") })).put("loopbackRequests", 1)
                    .put("toolDataReturned", true).put("toolExecutions", 0)
            }
            checkCase("actual Android cancellation aborts blocked HTTP stream and permits later request") {
                val pending = async {
                    worker.stream("fixture", "fixture-model", input("block"), requestId = "android-cancel") {}
                }
                withTimeout(10_000) { fixtureServer.blocked.await() }
                pending.cancelAndJoin()
                check(pending.isCancelled)
                withTimeout(10_000) { fixtureServer.disconnected.await() }
                val catalog = worker.catalog()
                check(catalog.getJSONArray("providers").getJSONObject(0).getString("id") == "fixture")
                val events = CopyOnWriteArrayList<JSONObject>()
                worker.stream("fixture", "fixture-model", input("after-cancel"), requestId = "android-after-cancel") { events += it }
                check(events.any { it.optString("type") == "text_delta" && it.optString("delta") == "offline fixture" })
                fixtureServer.requireHealthy()
                check(fixtureServer.requests.get() == 3)
                JSONObject().put("cancelled", true).put("httpDisconnected", true).put("nextCatalog", true)
                    .put("nextStream", true).put("totalLoopbackRequests", fixtureServer.requests.get())
                    .put("unstartedConnectionsClosed", fixtureServer.unstartedConnectionsClosed.get())
            }
            checkCase("unknown model and effort refuse before synthetic HTTP and worker stop releases real lease") {
                val before = fixtureServer.requests.get()
                for ((model, effort) in listOf("unknown-model" to null, "fixture-model" to "invented")) {
                    var refused = false
                    try { worker.stream("fixture", model, input("reject"), reasoning = effort) {} }
                    catch (failure: java.io.IOException) {
                        check(failure.message?.startsWith(if (effort == null) "UNKNOWN_MODEL:" else "UNSUPPORTED_REASONING:") == true)
                        refused = true
                    }
                    check(refused)
                }
                check(fixtureServer.requests.get() == before)
                worker.stop(); waitWorkerReleased(runtime)
                JSONObject().put("preNetworkRefusal", true).put("realProcessLeaseReleased", true)
            }
            report.put("passed", true)
        } catch (failure: Throwable) {
            report.put("failure", failure.javaClass.simpleName)
            throw failure
        } finally {
            withContext(NonCancellable) {
                fixtureWorker?.stop(); publicWorker.stop(); server?.close()
                waitWorkerReleased(runtime)
                check(!runtime.hasProcessLease(data) && !runtime.hasProcessLease(vault))
                check(data.deleteRecursively() && vault.deleteRecursively())
            }
            report.put("finished_at", System.currentTimeMillis()); persist()
        }
    }

    private fun input(value: String): JSONObject = JSONObject().put("messages", JSONArray().put(JSONObject()
        .put("role", "user").put("content", value).put("timestamp", 1_700_000_000_000L)))
        .put("tools", JSONArray().put(JSONObject().put("name", "host_only").put("description", "Host-only fixture tool")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("value", JSONObject().put("type", "string"))))))

    private suspend fun waitWorkerReleased(runtime: LinuxRuntimeManager) {
        withTimeout(10_000) { while (runtime.managedProcesses.value.any { it.id == "model-worker" }) delay(20) }
    }

    private fun stageCache(context: Context, data: File): JSONObject {
        val input = File(context.cacheDir, "model-worker-smoke-input")
        val archive = File(input, "model-worker-cache.zip")
        check(archive.isFile && archive.length() == 10_360_321L && digest(archive.readBytes()) ==
            "994f101877e00c434659f564bc0cc1901f72b09b9137c62c384d86cb924d3797")
        val lockBytes = context.assets.open("model-worker/runtime-lock.json").use { it.readBytes() }
        check(digest(lockBytes) == "6965494bd8dc143876b7f30eb6073d11d620f8ba652cd0d8ae5d0ee7477349b6")
        val artifacts = JSONObject(lockBytes.toString(Charsets.UTF_8)).getJSONArray("artifacts")
        val names = (0 until artifacts.length()).map { index ->
            "downloads/" + digest(artifacts.getJSONObject(index).getString("integrity").toByteArray()) + ".tgz"
        }.toSet()
        check(names.size == 85)
        val seen = mutableSetOf<String>()
        val cacheRoot = File(data, "model-runtime").apply { check(mkdir()) }
        check(File(cacheRoot, "downloads").mkdir())
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                check(!entry.isDirectory && seen.add(entry.name) && entry.size in 1..(20L * 1024 * 1024))
                if (entry.name == "runtime-lock.json") {
                    check(zip.getInputStream(entry).use { it.readBytes() }.contentEquals(lockBytes))
                } else {
                    check(entry.name in names)
                    val target = File(cacheRoot, entry.name)
                    check(target.canonicalPath.startsWith(cacheRoot.canonicalPath + File.separator) && !LinuxArchiveExtractor.isSymlink(target))
                    zip.getInputStream(entry).use { source -> target.outputStream().use { source.copyTo(it) } }
                    check(target.length() == entry.size)
                }
            }
        }
        check(seen == names + "runtime-lock.json")
        return JSONObject().put("packages", artifacts.length()).put("cachedArchives", names.size)
            .put("archiveSize", archive.length()).put("lockSha256", digest(lockBytes))
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A bounded HTTP/1.1 endpoint binds loopback only and stores no key/logs. */
    private class FixtureServer : AutoCloseable {
        private val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 200 }
        val port: Int get() = listener.localPort
        val requests = AtomicInteger()
        val toolRequests = AtomicInteger()
        val unstartedConnectionsClosed = AtomicInteger()
        val blocked = CompletableDeferred<Unit>()
        val disconnected = CompletableDeferred<Unit>()
        private val sockets = ConcurrentLinkedQueue<Socket>()
        private val errors = ConcurrentLinkedQueue<Throwable>()
        private val handlers = Executors.newFixedThreadPool(2)
        @Volatile private var closed = false
        private val accepting = Thread({
            while (!closed) {
                try { val socket = listener.accept(); sockets += socket; handlers.submit { handle(socket) } }
                catch (_: SocketTimeoutException) { }
                catch (failure: Throwable) { if (!closed) errors += failure }
            }
        }, "dafeiyu-offline-model-http").apply { isDaemon = true; start() }

        fun requireHealthy() {
            val failure = errors.peek() ?: return
            throw IllegalStateException("Loopback synthetic HTTP fixture failed", failure)
        }

        private fun handle(socket: Socket) {
            try {
                socket.soTimeout = 10_000
                val input = socket.getInputStream().buffered()
                fun line(allowUnstartedEof: Boolean = false): String? {
                    val bytes = ByteArrayOutputStream()
                    while (true) { val next = input.read()
                        // A cancelled Undici request can close a replacement pool
                        // connection before it sends a request. This is no HTTP
                        // request; EOF anywhere after the first byte still fails.
                        if (next < 0 && allowUnstartedEof && bytes.size() == 0) {
                            unstartedConnectionsClosed.incrementAndGet()
                            return null
                        }
                        check(next >= 0) { "Loopback HTTP fixture line ended before LF (${bytes.size()} bytes)" }
                        check(bytes.size() < 8192) { "Loopback HTTP fixture line exceeded 8192 bytes" }
                        if (next == 10) return bytes.toString("UTF-8").trimEnd('\r'); bytes.write(next) }
                }
                val requestLine = line(allowUnstartedEof = true) ?: return
                check(requestLine == "POST /v1/chat/completions HTTP/1.1")
                val headers = mutableMapOf<String, String>()
                while (true) { val header = checkNotNull(line()); if (header.isEmpty()) break
                    check(headers.size < 64 && ':' in header)
                    headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim() }
                check(headers["host"] == "127.0.0.1:$port" && headers["authorization"] == "Bearer SYNTHETIC_LOOPBACK_ONLY")
                val length = checkNotNull(headers["content-length"]).toInt(); check(length in 1..128 * 1024)
                val bytes = ByteArray(length); var offset = 0
                while (offset < length) { val count = input.read(bytes, offset, length - offset)
                    check(count > 0) { "Loopback HTTP fixture body ended before declared length" }; offset += count }
                val request = JSONObject(bytes.toString(Charsets.UTF_8)); bytes.fill(0)
                requests.incrementAndGet()
                if (request.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name") == "host_only") toolRequests.incrementAndGet()
                val messages = request.getJSONArray("messages")
                val shouldBlock = (0 until messages.length()).any { messages.getJSONObject(it).optString("content") == "block" }
                val output = socket.getOutputStream()
                output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                if (shouldBlock) {
                    output.write(":keepalive\n\n".toByteArray()); output.flush(); blocked.complete(Unit)
                    try { check(input.read() == -1); disconnected.complete(Unit) }
                    catch (failure: java.net.SocketException) { disconnected.complete(Unit) }
                } else {
                    fun chunk(delta: JSONObject): String = "data: " + JSONObject().put("id", "fixture")
                        .put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", delta).put("finish_reason", JSONObject.NULL))) + "\n\n"
                    val stream = chunk(JSONObject().put("role", "assistant").put("content", "offline fixture")) +
                        chunk(JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", "fixture-call")
                            .put("type", "function").put("function", JSONObject().put("name", "host_only").put("arguments", "{\"value\":\"x\"}"))))) +
                        "data: " + JSONObject().put("id", "fixture").put("choices", JSONArray().put(JSONObject().put("index", 0)
                            .put("delta", JSONObject()).put("finish_reason", "tool_calls")))
                            .put("usage", JSONObject().put("prompt_tokens", 2).put("completion_tokens", 3).put("total_tokens", 5)) + "\n\ndata: [DONE]\n\n"
                    output.write(stream.toByteArray()); output.flush()
                }
            } catch (failure: Throwable) { if (!closed) errors += failure }
            finally { sockets.remove(socket); socket.close() }
        }

        override fun close() {
            closed = true; listener.close(); sockets.forEach { it.close() }; handlers.shutdownNow(); accepting.join(1000)
        }
    }
}
