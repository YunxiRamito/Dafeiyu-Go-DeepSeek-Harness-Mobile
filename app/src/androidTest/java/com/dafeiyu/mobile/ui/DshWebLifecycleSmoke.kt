package com.dafeiyu.mobile.ui

import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Actual production Compose surface + real Android WebView; loopback HTML only, no DSH/model/Aries calls. */
@RunWith(AndroidJUnit4::class)
class DshWebLifecycleSmoke {
    @Test fun nullAccessThenReplacementKeepsTheOwnedWebView(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        check(target.packageName == "com.dafeiyu.mobile.dev")
        val reportFile = File(target.filesDir, "web-lifecycle-smoke-report.json")
        val checks = JSONArray()
        val report = JSONObject().put("schema", 1).put("passed", false).put("checks", checks)
            .put("actualProductionComposable", true).put("actualAndroidWebView", true)
            .put("network", "loopback_synthetic_html").put("paidModelRequests", 0).put("startedAt", System.currentTimeMillis())
        fun persist() = reportFile.writeText(report.toString(2))
        suspend fun case(name: String, block: suspend () -> Unit) {
            val row = JSONObject().put("name", name).put("passed", false)
            checks.put(row); persist()
            try { withTimeout(20_000) { block() }; row.put("passed", true) }
            catch (failure: Throwable) { row.put("error", failure.javaClass.simpleName); throw failure }
            finally { persist() }
        }
        val access = mutableStateOf<String?>(null)
        val selection = mutableStateOf<String?>("lifecycle-session-a")
        val host = AtomicReference<DshWebLifecycleQaActivity>()
        val first = LoopbackPage("A")
        val second = LoopbackPage("B")
        var scenario: ActivityScenario<DshWebLifecycleQaActivity>? = null
        try {
            scenario = ActivityScenario.launch(Intent(target, DshWebLifecycleQaActivity::class.java))
            scenario.onActivity { activity ->
                host.set(activity)
                activity.setContent { MaterialTheme {
                    DshWebSurface(access.value, selection.value, "正在准备本机 DSH", "离线生命周期专项", {})
                } }
            }
            suspend fun currentWeb(): WebView? = withContext(Dispatchers.Main) {
                webViews(checkNotNull(host.get()).window.decorView).singleOrNull()
            }
            suspend fun surface(label: String, session: String, excluding: WebView? = null): WebView = withTimeout(15_000) {
                while (true) {
                    currentWeb()?.takeIf { it !== excluding }?.let { view ->
                        val raw = evaluate(view, "JSON.stringify({rendered:window.qaRendered===true,label:document.body?.dataset.qa,selected:localStorage.getItem('dsh.sessions.current')})")
                        val state = runCatching { JSONObject(JSONArray("[$raw]").getString(0)) }.getOrNull()
                        if (state?.optBoolean("rendered") == true && state.optString("label") == label &&
                            runCatching { JSONObject(state.getString("selected")).getString("sessionId") }.getOrNull() == session) return@withTimeout view
                    }
                    delay(100)
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
            case("null access renders the waiting surface without allocating a WebView") {
                instrumentation.waitForIdleSync()
                check(currentWeb() == null)
            }
            var a: WebView? = null
            case("null to authenticated URL loads real HTML and selected session before document startup") {
                withContext(Dispatchers.Main) { access.value = first.access }
                a = surface("A", "lifecycle-session-a")
                check(first.tokenNavigations > 0 && first.documents > 0)
            }
            val oldA = checkNotNull(a)
            val oldAClient = withContext(Dispatchers.Main) { oldA.webViewClient }
            var b: WebView? = null
            case("access replacement releases the old view and keeps its replacement alive") {
                withContext(Dispatchers.Main) { access.value = second.access }
                b = surface("B", "lifecycle-session-a", oldA)
                check(b !== oldA)
                withContext(Dispatchers.Main) { check(oldA.parent == null && !oldA.isAttachedToWindow) }
            }
            case("late callbacks from a released access cannot overwrite the current surface") {
                withContext(Dispatchers.Main) {
                    oldAClient.onPageStarted(oldA, first.access, null)
                    oldAClient.onReceivedHttpError(oldA, mainFrame(first.access), WebResourceResponse("text/plain", "UTF-8", 502,
                        "Bad Gateway", emptyMap(), ByteArrayInputStream(ByteArray(0))))
                    oldAClient.onPageFinished(oldA, first.access)
                }
                instrumentation.waitForIdleSync()
                check(surface("B", "lifecycle-session-a") === b)
                val tree = checkNotNull(instrumentation.uiAutomation.rootInActiveWindow)
                check(tree.packageName == target.packageName)
                check(!containsText(tree, "DSH 网页无法访问"))
            }
            val oldB = checkNotNull(b)
            case("same URL with a new selected session creates an independently owned bootstrapped view") {
                withContext(Dispatchers.Main) { selection.value = "lifecycle-session-b" }
                val next = surface("B", "lifecycle-session-b", oldB)
                check(next !== oldB)
                withContext(Dispatchers.Main) { check(oldB.parent == null && !oldB.isAttachedToWindow) }
            }
            case("removing access releases the view and a later URL loads again") {
                val old = checkNotNull(currentWeb())
                withContext(Dispatchers.Main) { access.value = null }
                instrumentation.waitForIdleSync()
                withTimeout(5000) { while (currentWeb() != null) delay(50) }
                withContext(Dispatchers.Main) { check(old.parent == null && !old.isAttachedToWindow) }
                withContext(Dispatchers.Main) { access.value = first.access }
                check(surface("A", "lifecycle-session-b", old) !== old)
            }
            case("composition disposal removes only its own WebView") {
                val old = checkNotNull(currentWeb())
                withContext(Dispatchers.Main) { checkNotNull(host.get()).setContent { MaterialTheme {} } }
                instrumentation.waitForIdleSync()
                withTimeout(5000) { while (currentWeb() != null) delay(50) }
                withContext(Dispatchers.Main) { check(old.parent == null && !old.isAttachedToWindow) }
            }
            first.requireHealthy(); second.requireHealthy()
            report.put("passed", true).put("finishedAt", System.currentTimeMillis())
        } finally {
            withContext(NonCancellable) {
                scenario?.close()
                first.close(); second.close()
                report.put("testServersStopped", true); persist()
            }
        }
    }

    private suspend fun evaluate(view: WebView, script: String): String = withContext(Dispatchers.Main) {
        withTimeout(5000) { suspendCancellableCoroutine { continuation -> view.evaluateJavascript(script) { value ->
            if (continuation.isActive) continuation.resume(value)
        } } }
    }

    private fun webViews(view: View): List<WebView> = when (view) {
        is WebView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { webViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun containsText(root: AccessibilityNodeInfo, text: String): Boolean {
        val pending = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (pending.isNotEmpty()) {
            check(++visited <= 512)
            val next = pending.removeFirst()
            if (next.text?.contains(text) == true) return true
            for (index in 0 until next.childCount) next.getChild(index)?.let(pending::addLast)
        }
        return false
    }

    private fun mainFrame(value: String) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(value)
        override fun isForMainFrame(): Boolean = true
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = false
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    private class LoopbackPage(private val label: String) : Closeable {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        private val failure = AtomicReference<Throwable?>()
        @Volatile var tokenNavigations = 0
            private set
        @Volatile var documents = 0
            private set
        val access = "http://127.0.0.1:${server.localPort}/?token=${"A".repeat(43)}"
        private val worker = thread(name = "dsh-web-lifecycle-loopback", isDaemon = true) {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 2000
                    val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val request = input.readLine().orEmpty()
                    var length = request.length
                    while (true) {
                        val line = input.readLine() ?: break
                        length += line.length; check(length <= 8192)
                        if (line.isEmpty()) break
                    }
                    val response = if (request.startsWith("GET /?token=")) {
                        tokenNavigations++
                        "HTTP/1.1 303 See Other\r\nLocation: ./\r\nCache-Control: no-store\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    } else {
                        documents++
                        val body = "<!doctype html><html><body data-qa='$label'><div id='root'>surface-$label</div><script>window.qaRendered=true</script></body></html>"
                        "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: ${body.toByteArray().size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n$body"
                    }
                    socket.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
                    socket.getOutputStream().flush()
                }
            } catch (error: Throwable) {
                if (!(error is SocketException && server.isClosed)) failure.set(error)
            }
        }
        fun requireHealthy() { failure.get()?.let { throw AssertionError("Loopback fixture failed", it) } }
        override fun close() { server.close(); worker.join(3000); check(!worker.isAlive); requireHealthy() }
    }
}
