package com.dafeiyu.mobile.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.Socket
import java.net.URI
import java.util.Base64
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit isolated QA phases: fresh pairing, real HTTP/WS, then exact durable terminal cleanup. */
@RunWith(AndroidJUnit4::class)
class RemoteDshPublicWebSmoke {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val root by lazy { File(target.filesDir, "public-web-audit") }
    private val prefs by lazy { app.getSharedPreferences("dafeiyu_settings", Context.MODE_PRIVATE) }
    private val config by lazy { JSONObject(File(root, "phone-config.json").readText(Charsets.UTF_8)) }
    private val endpoint by lazy { config.getString("endpoint") }
    private val node by lazy { config.getString("node_id") }
    private val group by lazy { config.getString("group_id") }
    // Preserve the QA package's existing relationship. Only instrumentation
    // redirects persistence; all providers, IPC, crypto and runtime calls are production.
    private val app by lazy {
        check(target.packageName == "com.dafeiyu.mobile.dev" && group.matches(ID))
        val vault = File(target.noBackupFilesDir, "public-web-audit/$group").apply { check(isDirectory || mkdirs()) }
        object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = vault
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                target.getSharedPreferences("public-web-$group-$name", mode)
        }
    }
    private val client by lazy { AriesClient(app) }
    private val provider by lazy { AriesConnectionProvider(app) }
    private var checks = 0
    private fun pass(name: String) { checks++; Log.i(TAG, "PASS $checks $name") }
    private fun isolated() {
        check(app.packageName == "com.dafeiyu.mobile.dev") { "This test only accepts the independent QA debug package" }
        check(endpoint == "https://202.189.21.218:10240" && node.matches(ID) && group.matches(ID))
    }
    private fun write(name: String, value: JSONObject) {
        check(root.isDirectory || root.mkdirs())
        val file = File(root, name)
        val temporary = File(root, "$name.tmp")
        temporary.writeText(value.toString(), Charsets.UTF_8)
        check(temporary.renameTo(file))
    }
    private fun targetSelection(): JSONObject = JSONObject().also { selection ->
        val actual = target.getSharedPreferences("dafeiyu_settings", Context.MODE_PRIVATE)
        SELECTORS.forEach { selection.put(it, actual.getString(it, null) ?: JSONObject.NULL) }
    }

    @Test fun pairIsolatedPublicAries() = runBlocking {
        isolated()
        check(!File(root, "original-selection.json").exists()) { "Previous isolated test must finish cleanup first" }
        check(prefs.getString("aries_group_id", "").isNullOrEmpty()) { "Existing QA pairing is preserved" }
        write("target-selection.json", targetSelection())
        val original = JSONObject()
        SELECTORS.forEach { original.put(it, prefs.getString(it, null) ?: JSONObject.NULL) }
        write("original-selection.json", original)
        val api = AriesClient.Config(endpoint)
        val actual = client.node(api).getOrThrow()
        assertEquals(node, actual.nodeId)
        assertTrue(actual.capabilities.contains("pairing_unpair"))
        pass("pinned public node identity and cleanup capability verified")
        val challenge = client.requestChallenge(api).getOrThrow()
        val enrollment = client.enroll(challenge, api).getOrThrow()
        val identity = AriesIdentityStore(app).identity()
        assertEquals(enrollment.deviceId, identity.deviceId)
        client.heartbeat(api, "android-public-web-qa", node).getOrThrow()
        pass("real Android Keystore identity enrolled and signed heartbeat accepted")
        val invitation = client.lookupInvite(config.getString("invite_code"), api).getOrThrow()
        assertEquals(group, invitation.groupId)
        assertEquals(node, invitation.authorityNode)
        val application = client.applyInvite(config.getString("invite_code"), api,
            "public-web-apply-${UUID.randomUUID()}").getOrThrow()
        assertEquals(group, application.groupId)
        check(prefs.edit().putString("aries_endpoint", endpoint).putString("aries_node_id", node)
            .putString("aries_group_id", group).putString("aries_device_id", enrollment.deviceId).commit())
        provider.saveApplication(endpoint, node, group, application.applicationId)
        write("phone-application.json", JSONObject().put("node_id", node).put("group_id", group)
            .put("device_id", enrollment.deviceId).put("public_key", identity.publicKeyBase64Url)
            .put("application_id", application.applicationId))
        pass("exact public application persisted for strict PC approval")
        assertEquals(JSONObject(File(root, "target-selection.json").readText()).toString(), targetSelection().toString())
        pass("application relationship was preserved in a distinct instrumentation persistence scope")
        write("pair-result.json", JSONObject().put("checks", checks).put("success", true).put("group_id", group))
        Unit
    }

    @Test fun realAndroidConnectHttpAndWebSocket() = runBlocking {
        isolated()
        assertEquals(group, prefs.getString("aries_group_id", ""))
        val host = ConnectPluginHost.get(app)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var proxy: RemoteDshLoopbackProxy? = null
        var success = false
        val traceFrames = Collections.synchronizedList(mutableListOf<JSONObject>())
        val traceStart = System.nanoTime()
        fun trace(stage: String, detail: JSONObject = JSONObject()) {
            if (traceFrames.size < 4096) traceFrames.add(JSONObject().put("elapsedMillis", (System.nanoTime() - traceStart) / 1_000_000)
                .put("stage", stage).put("metadata", detail))
        }
        try {
            val review = withTimeout(90_000) {
                var found: JSONObject? = null
                while (found == null) { found = provider.pendingDelivery(); if (found == null) delay(750) }
                found
            }
            assertEquals(config.getString("pc_device_id"), review.getString("sender_id"))
            assertEquals(config.getString("pc_fingerprint"), review.getString("fingerprint"))
            val application = JSONObject(File(root, "phone-application.json").readText()).getString("application_id")
            assertEquals(application, review.getString("application_id"))
            provider.confirmDelivery(endpoint, node, group, application, review.getString("delivery_id"), review.getString("fingerprint"))
            pass("real cross-platform content key delivery and exact sender confirmation completed")
            val loaded = host.start()
            assertEquals(config.getString("connect_sha256"), loaded.archiveSha256)
            assertEquals(config.getLong("connect_size"), loaded.archiveSizeBytes)
            assertEquals(ConnectPluginHostStatus.RUNNING, loaded.status)
            pass("Android actual Node loaded byte-identical frozen Connect 0.3.0")
            host.setNativeDiagnosticObserver { diagnostic -> trace("native.provider", diagnostic.toJson()) }
            val paired = withTimeout(90_000) {
                var state = host.call("get_connection_state", JSONObject(), null)
                while (!state.getJSONObject("capabilities").optBoolean("remote_task") ||
                    state.optJSONObject("remote_device")?.optString("online") != "online") {
                    delay(750); state = host.call("get_connection_state", JSONObject(), null)
                }
                state
            }
            assertEquals("paired", paired.getString("pairing_status"))
            assertEquals(config.getString("pc_device_id"), paired.getJSONObject("remote_device").getString("id"))
            assertEquals(config.getString("pc_public_key"), paired.getJSONObject("remote_device").getString("public_key"))
            // This wrapper changes only the capability gate for the isolated test.
            // Every send/poll/ack still runs production Node, scope checks, crypto and public Aries.
            val transport = RemoteDshClient(object : RemoteDshProviderCall {
              override suspend fun call(method: String, params: JSONObject, expectedRemoteScope: RemoteDshScope?): JSONObject {
                val metadata = JSONObject().put("method", method)
                if (method == "list_incoming_messages") metadata.put("cursor", params.optString("cursor"))
                if (method == "send_encrypted_message") params.optJSONObject("body")?.let { body ->
                    metadata.put("requestId", body.optString("request_id")).put("sequence", body.opt("sequence") ?: JSONObject.NULL)
                        .put("kind", params.optString("kind"))
                }
                trace("native.call.start", metadata)
                val result = host.call(method, params, expectedRemoteScope)
                trace("native.call.done", metadata)
                if (method == "list_incoming_messages") {
                    val messages = result.getJSONArray("messages")
                    val rows = JSONArray()
                    for (index in 0 until messages.length()) {
                        val message = messages.getJSONObject(index)
                        val body = message.optJSONObject("body")
                        rows.put(JSONObject().put("messageId", message.optString("message_id"))
                            .put("kind", message.optString("kind")).put("requestId", body?.optString("request_id") ?: JSONObject.NULL)
                            .put("sequence", body?.opt("sequence") ?: JSONObject.NULL).put("type", body?.optString("type") ?: JSONObject.NULL))
                    }
                    trace("native.page", JSONObject().put("cursor", params.optString("cursor"))
                        .put("nextCursor", result.opt("next_cursor") ?: JSONObject.NULL).put("messages", rows))
                }
                return if (method == "get_connection_state") JSONObject(result.toString()).also {
                    it.getJSONObject("capabilities").put("remote_web_tunnel", true)
                } else result
              }
              override suspend fun callInScope(method: String, params: JSONObject, scope: RemoteDshScope, capability: String,
                  acknowledgeMessageId: String?): JSONObject {
                val metadata = JSONObject().put("method", method).put("capability", capability)
                    .put("combinedAck", acknowledgeMessageId != null)
                if (method == "list_incoming_messages") metadata.put("cursor", params.optString("cursor"))
                params.optJSONObject("body")?.let { body -> metadata.put("requestId", body.optString("request_id"))
                    .put("sequence", body.opt("sequence") ?: JSONObject.NULL) }
                trace("native.scoped.start", metadata)
                val result = host.callInScope(method, params, scope, capability, acknowledgeMessageId)
                trace("native.scoped.done", metadata)
                if (method == "list_incoming_messages") {
                    val rows = JSONArray()
                    val messages = result.getJSONArray("messages")
                    for (index in 0 until messages.length()) {
                        val message = messages.getJSONObject(index)
                        val body = message.optJSONObject("body")
                        rows.put(JSONObject().put("messageId", message.optString("message_id"))
                            .put("requestId", body?.optString("request_id") ?: JSONObject.NULL)
                            .put("sequence", body?.opt("sequence") ?: JSONObject.NULL).put("type", body?.optString("type") ?: JSONObject.NULL))
                    }
                    trace("native.page", JSONObject().put("cursor", params.optString("cursor"))
                        .put("nextCursor", result.opt("next_cursor") ?: JSONObject.NULL).put("messages", rows))
                }
                return result
              }
            })
            val binding = RemoteDshScope.fromState(paired)
            val web = RemoteDshWebClient(transport, binding) { event ->
                trace(event.stage, JSONObject().put("requestId", event.requestId ?: JSONObject.NULL)
                    .put("cursor", event.cursor ?: JSONObject.NULL).put("nextCursor", event.nextCursor ?: JSONObject.NULL)
                    .put("sequence", event.sequence ?: JSONObject.NULL).put("type", event.type ?: JSONObject.NULL)
                    .put("eventCount", event.eventCount ?: JSONObject.NULL).put("readerPresent", event.readerPresent ?: JSONObject.NULL))
            }
            proxy = RemoteDshLoopbackProxy(web, scope)
            val access = proxy.start()
            pass("real Android loopback HTTP/WS proxy started for exact authenticated PC")
            val entry = request(access.origin, "GET", URI(access.url).rawPath + "?" + URI(access.url).rawQuery)
            assertTrue(entry.first.startsWith("HTTP/1.1 303"))
            val cookie = Regex("(?im)^set-cookie: ([^;]+)").find(entry.first)!!.groupValues[1]
            val index = request(access.origin, "GET", "/", cookie)
            assertTrue(index.first.startsWith("HTTP/1.1 200"))
            val html = decoded(index).toString(Charsets.UTF_8)
            assertTrue(html.contains("__DSH_BOOT__") && html.contains("plugins/??") && !html.contains("token="))
            pass("actual DSH HTML and bootstrap crossed public encrypted relay without PC launch credential")
            val rpc = JSONObject().put("type", "client-request").put("rpcId", "public-rpc-${UUID.randomUUID()}")
                .put("method", "session/modelCatalog").put("payload", JSONObject().put("args", JSONObject()))
            val catalog = request(access.origin, "POST", "/api/session/modelCatalog", cookie, rpc.toString(),
                "Origin: ${access.origin}\r\nContent-Type: application/json\r\n")
            val catalogStatus = catalog.first.substringBefore("\r\n").split(' ').getOrNull(1)?.toIntOrNull() ?: -1
            write("rpc-diagnostic.json", JSONObject().put("httpStatus", catalogStatus)
                .put("stage", proxy.lastFailure?.stage ?: JSONObject.NULL).put("code", proxy.lastFailure?.code ?: JSONObject.NULL))
            Log.i(TAG, "modelCatalog status=$catalogStatus fault=${proxy.lastFailure?.code ?: "none"}")
            assertTrue(catalog.first.startsWith("HTTP/1.1 200"))
            val result = JSONObject(decoded(catalog).toString(Charsets.UTF_8)).getJSONObject("result")
            assertTrue(result.getBoolean("ok") && result.getJSONObject("value").getJSONArray("groups").length() > 0)
            pass("actual DSH model catalog RPC returned through Android same-origin HTTP")
            socketSnapshot(access.origin, cookie, config.getString("session_id"))
            pass("real RFC6455 upgrade and DSH mux session snapshot retain public thought/body/child catalog")
            validateWebView(access.url, access.origin) { proxy?.lastFailure }
            pass("actual Android WebView loaded DSH module graph and mounted the page through encrypted proxy")
            success = true
        } finally {
            withContext(NonCancellable) {
                proxy?.close(); scope.cancel(); host.setNativeDiagnosticObserver(null); host.stop()
                write("phone-frame-trace.json", JSONObject().put("frames", JSONArray(traceFrames.toList()))
                    .put("paidModelCalls", 0).put("privatePayloadIncluded", false))
                write("phone-finished.json", JSONObject().put("group_id", group)
                    .put("device_id", AriesIdentityStore(app).identity().deviceId).put("checks", checks)
                    .put("success", success).put("paidModelCalls", 0).put("productionWebCapability", false))
            }
        }
        Unit
    }

    @Test fun observeExactDurableTerminalAndRestoreSelection() = runBlocking {
        isolated()
        val device = AriesIdentityStore(app).identity().deviceId
        val state = withTimeout(90_000) {
            var value = provider.call("get_connection_state")
            while (value.optJSONObject("terminal_receipt") == null) { delay(1000); value = provider.call("get_connection_state") }
            value
        }
        val terminal = state.getJSONObject("terminal_receipt")
        assertEquals(group, terminal.getString("group_id")); assertEquals(device, terminal.getString("device_id"))
        // General membership-status reports closure for this former member;
        // only the initiating unpair response uses the pair-specific status.
        assertEquals("group_closed", terminal.getString("status")); assertEquals("revoked", state.getString("pairing_status"))
        pass("Android received exact persistent terminal after actual PC unpair")
        val originalFile = File(root, "original-selection.json")
        val original = JSONObject(originalFile.readText())
        val edit = prefs.edit()
        SELECTORS.forEach { if (original.isNull(it)) edit.remove(it) else edit.putString(it, original.getString(it)) }
        check(edit.commit())
        assertEquals(device, AriesIdentityStore(app).identity().deviceId)
        assertEquals(JSONObject(File(root, "target-selection.json").readText()).toString(), targetSelection().toString())
        check(originalFile.delete())
        pass("isolated selector restored after durable terminal; test identity and actual QA relationship preserved")
        write("phone-cleanup.json", JSONObject().put("group_id", group).put("device_id", device)
            .put("terminal", terminal).put("cleanupVerified", true).put("checks", checks))
        Unit
    }

    private suspend fun validateWebView(url: String, origin: String, proxyFailure: () -> RemoteDshLoopbackProxy.Fault?) {
        val paths = Collections.synchronizedSet(mutableSetOf<String>())
        val errors = AtomicInteger()
        val records = Collections.synchronizedList(mutableListOf<JSONObject>())
        val lastProbe = AtomicReference(JSONObject())
        var mounted = false
        var view: WebView? = null
        fun resourceKind(path: String): String = when {
            path.startsWith("/assets/") -> "asset"
            path.startsWith("/plugins/") -> "plugin"
            path == "/api" -> "rpc"
            path == "/api/remote.mux" -> "mux"
            path == "/" -> "page"
            else -> "other"
        }
        fun record(kind: String, request: WebResourceRequest, code: Int) {
            errors.incrementAndGet()
            synchronized(records) {
                if (records.size < 64) records.add(JSONObject().put("kind", kind)
                    .put("resource", resourceKind(request.url.path.orEmpty())).put("mainFrame", request.isForMainFrame)
                    .put("code", code))
            }
        }
        try {
            instrumentation.runOnMainSync {
                view = WebView(app).apply {
                    settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onLoadResource(view: WebView, url: String) { paths.add(Uri.parse(url).path.orEmpty()) }
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            !request.url.toString().startsWith("$origin/")
                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                            record("http", request, errorResponse.statusCode)
                        }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            record("webview", request, error.errorCode)
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                            if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) synchronized(records) {
                                if (records.size < 64) records.add(JSONObject().put("kind", "console").put("category", when {
                                    message.message().contains("Content Security Policy", ignoreCase = true) -> "csp"
                                    message.message().contains("Failed to fetch", ignoreCase = true) -> "fetch"
                                    message.message().contains("Failed to load module", ignoreCase = true) -> "module"
                                    message.message().contains("SyntaxError") -> "syntax"
                                    else -> "other"
                                }))
                            }
                            return true
                        }
                    }
                    loadUrl(url)
                }
            }
            withTimeout(180_000) {
                while (true) {
                    val probe = CompletableDeferred<String>()
                    instrumentation.runOnMainSync {
                        view!!.evaluateJavascript("""JSON.stringify((()=>{const root=document.getElementById('root'); const mode=window.__ModuleLoader__?.mode;
                            return {hasBoot:!!globalThis.__DSH_BOOT__,moduleMode:mode==='queue'||mode==='live'?mode:'other',
                            rootChildren:root?.childElementCount||0,bootPresent:!!root?.querySelector('[data-dsh-boot]'),
                            buttons:root?.querySelectorAll('button,[role="button"]').length||0}})())""".trimIndent()) { probe.complete(it) }
                    }
                    val observed = JSONObject(JSONArray("[${probe.await()}]").getString(0))
                    lastProbe.set(observed)
                    if (observed.getBoolean("hasBoot") && observed.getString("moduleMode") == "live" &&
                        observed.getInt("rootChildren") > 0 && !observed.getBoolean("bootPresent") && observed.getInt("buttons") > 0) {
                        mounted = true; break
                    }
                    check(errors.get() == 0) { "Actual WebView resource failed" }
                    delay(500)
                }
            }
            assertTrue(paths.any { it.startsWith("/assets/") })
            assertTrue(paths.contains("/plugins/"))
            assertEquals(0, errors.get())
        } finally {
            val fault = proxyFailure()
            try { instrumentation.runOnMainSync { view?.stopLoading(); view?.destroy() } }
            finally { write("webview-diagnostic.json", JSONObject().put("mounted", mounted).put("probe", lastProbe.get())
                .put("resourceErrors", errors.get()).put("records", JSONArray(synchronized(records) { records.toList() }))
                .put("proxyStage", fault?.stage ?: JSONObject.NULL).put("proxyCode", fault?.code ?: JSONObject.NULL)
                .put("privatePayloadIncluded", false).put("paidModelCalls", 0)) }
        }
    }

    private fun socketSnapshot(origin: String, cookie: String, sessionId: String) {
        val target = URI(origin)
        Socket(target.host, target.port).use { socket ->
            socket.soTimeout = 90_000
            val key = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
            val upgrade = "GET /api/remote.mux HTTP/1.1\r\nHost: ${target.authority}\r\nOrigin: $origin\r\nCookie: $cookie\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: $key\r\n\r\n"
            socket.getOutputStream().write(upgrade.toByteArray(Charsets.US_ASCII))
            assertTrue(readHead(socket.getInputStream()).startsWith("HTTP/1.1 101"))
            val stream = "public-session-${UUID.randomUUID()}"
            val frame = JSONObject().put("type", "open").put("streamId", stream).put("endpoint", "session/follow")
                .put("payload", JSONObject().put("args", JSONObject().put("request", JSONObject()
                    .put("address", JSONObject().put("kind", "session").put("sessionId", sessionId))
                    .put("maxMessages", 200).put("assistantStream", true))))
            socket.getOutputStream().write(masked(frame.toString().toByteArray(Charsets.UTF_8)))
            val received = readServerFrame(socket.getInputStream())
            assertEquals("item", received.getString("type")); assertEquals(stream, received.getString("streamId"))
            val snapshot = received.getJSONObject("value")
            assertEquals("snapshot", snapshot.getString("type")); assertEquals(sessionId, snapshot.getJSONObject("header").getString("id"))
            assertTrue(snapshot.toString().contains("公网联调公开思考") && snapshot.toString().contains("公网联调离线正文"))
            assertTrue(snapshot.getJSONObject("projections").getJSONObject("values").getJSONArray("subagentCatalog").length() == 1)
            val cancel = JSONObject().put("type", "cancel").put("streamId", stream)
            socket.getOutputStream().write(masked(cancel.toString().toByteArray(Charsets.UTF_8)))
        }
    }

    private fun request(origin: String, method: String, path: String, cookie: String? = null, text: String = "", extra: String = ""): Pair<String, ByteArray> {
        val target = URI(origin); val bytes = text.toByteArray(Charsets.UTF_8)
        Socket(target.host, target.port).use { socket ->
            socket.soTimeout = 90_000
            val head = "$method $path HTTP/1.1\r\nHost: ${target.authority}\r\n" + (cookie?.let { "Cookie: $it\r\n" } ?: "") + "Content-Length: ${bytes.size}\r\n$extra\r\n"
            socket.getOutputStream().write(head.toByteArray(Charsets.US_ASCII)); socket.getOutputStream().write(bytes)
            val response = readHead(socket.getInputStream())
            val length = Regex("(?im)^content-length: ([0-9]+)").find(response)!!.groupValues[1].toInt()
            check(length <= RemoteDshWebClient.RESPONSE_BYTES)
            return response to readBytes(socket.getInputStream(), length)
        }
    }
    private fun decoded(value: Pair<String, ByteArray>): ByteArray = if (Regex("(?im)^content-encoding: gzip").containsMatchIn(value.first))
        GZIPInputStream(value.second.inputStream()).use { input ->
            val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; check(out.size() + n <= 16 * 1024 * 1024); out.write(buffer, 0, n) }; out.toByteArray()
        } else value.second
    private fun readHead(input: InputStream): String {
        val out = ByteArrayOutputStream(); var tail = 0
        while (out.size() <= 16 * 1024) { val b = input.read(); check(b >= 0); out.write(b); tail = tail shl 8 or b; if (tail == 0x0d0a0d0a) return out.toString(Charsets.US_ASCII.name()) }
        error("Response header too large")
    }
    private fun readBytes(input: InputStream, size: Int): ByteArray {
        val bytes = ByteArray(size); var offset = 0
        while (offset < size) { val n = input.read(bytes, offset, size - offset); check(n > 0); offset += n }; return bytes
    }
    private fun masked(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(); out.write(129)
        if (bytes.size < 126) out.write(128 or bytes.size) else { out.write(254); out.write(bytes.size ushr 8); out.write(bytes.size and 255) }
        val mask = byteArrayOf(1, 2, 3, 4); out.write(mask)
        out.write(ByteArray(bytes.size) { (bytes[it].toInt() xor mask[it % 4].toInt()).toByte() }); return out.toByteArray()
    }
    private fun readServerFrame(input: InputStream): JSONObject {
        assertEquals(129, input.read())
        val b = input.read(); check(b >= 0 && b and 128 == 0)
        val size = when (b) {
            126 -> input.read() shl 8 or input.read()
            127 -> java.nio.ByteBuffer.wrap(readBytes(input, 8)).long.also { check(it in 0..RemoteDshWebClient.RESPONSE_BYTES) }.toInt()
            else -> b
        }
        check(size in 1..RemoteDshWebClient.RESPONSE_BYTES)
        return JSONObject(readBytes(input, size).toString(Charsets.UTF_8))
    }
    companion object {
        const val TAG = "DafeiyuPublicWebSmoke"
        private val ID = Regex("[A-Za-z0-9_-]{1,128}")
        private val SELECTORS = listOf("aries_endpoint", "aries_node_id", "aries_group_id", "aries_device_id")
    }
}
