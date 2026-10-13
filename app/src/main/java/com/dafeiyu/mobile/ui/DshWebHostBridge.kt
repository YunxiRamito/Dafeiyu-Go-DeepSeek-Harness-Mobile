package com.dafeiyu.mobile.ui

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import org.json.JSONArray
import org.json.JSONObject

/** Display preferences are application-owned; this signal grants no task authority. */
internal data class DshWebClientContext(val mode: String, val theme: String,
    val material: Boolean, val motion: Boolean) {
    init {
        require(mode in setOf("local", "online") && theme in setOf("system", "light", "dark"))
    }
    fun toJson(): JSONObject = JSONObject().put("version", 1).put("platform", "android")
        .put("surface", "mobile").put("mode", mode).put("theme", theme)
        .put("material", material).put("motion", motion)
}

/** Fixed application routes only. A missing callback never advertises a capability. */
internal data class DshWebHostActions(
    val openAppSettings: (() -> Boolean)? = null,
    val openConnectionSettings: (() -> Boolean)? = null,
    val openScanner: (() -> Boolean)? = null,
    val openPairing: (() -> Boolean)? = null,
    val connectionState: (suspend () -> JSONObject)? = null,
    val onFailure: ((String) -> Unit)? = null,
) {
    fun capabilities(): List<String> = buildList {
        if (openAppSettings != null) add("app.settings.open")
        if (openConnectionSettings != null) add("connection.settings.open")
        if (openScanner != null) add("app.scan.open")
        if (openPairing != null) add("pairing.open")
        if (connectionState != null) add("connection.state")
    }
}

/** AndroidX WebKit 1.14.0 WebMessageListener/document-start API adapter.
 * Reviewed AndroidX source reference: 078de553b861299af03d7a548377cc1307db776d,
 * Apache-2.0; 1.14.0 sources.jar SHA-256 714bc9cebdba29d5e336e026367c50ff235bd0090e1949efcbd81537a213176e.
 * No implementation is copied. There is no JavaScriptInterface, URL dispatch,
 * command execution, permission grant, identity mutation or pairing cancellation.
 */
internal class DshWebHostBridge private constructor(private val view: WebView,
    private val origin: String, private var client: DshWebClientContext,
    private var actions: DshWebHostActions, private val onUnavailable: (String) -> Unit,
    private val onPresentationReadyChanged: (Boolean) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pending = mutableMapOf<String, Job>()
    private val seen = mutableSetOf<String>()
    private var generation = 0L
    private var closed = false
    private var listening = false
    private var bootstrap: ScriptHandler? = null
    private var publishedCapabilities = emptyList<String>()
    private var boundDocument: String? = null
    private var presentationReady = false
    private var presentationGeneration = 0L

    private fun owned(uri: Uri): Boolean = uri.scheme == "http" && uri.host == "127.0.0.1" &&
        uri.port == Uri.parse(origin).port && uri.userInfo == null

    private fun ownedDocument(): Boolean = view.url?.let { url -> runCatching {
        val uri = Uri.parse(url)
        owned(uri) && uri.path == "/" && uri.getQueryParameter("dafeiyu-preview") == null
    }.getOrDefault(false) } == true

    private fun displayCapabilities(): List<String> = if (listening)
        actions.capabilities() + PRESENTATION_CAPABILITY else emptyList()

    private fun setPresentationReady(ready: Boolean) {
        if (presentationReady == ready) return
        presentationReady = ready
        onPresentationReadyChanged(ready)
    }

    private fun install() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            try {
                WebViewCompat.addWebMessageListener(view, CHANNEL, setOf(origin)) {
                    sender, message, sourceOrigin, mainFrame, reply ->
                    receive(sender, message, sourceOrigin, mainFrame, reply)
                }
                listening = true
            } catch (_: Exception) {
                runCatching { WebViewCompat.removeWebMessageListener(view, CHANNEL) }
            }
        }
        publishedCapabilities = displayCapabilities()
        bootstrap = WebViewCompat.addDocumentStartJavaScript(view,
            bootstrapScript(origin, client, publishedCapabilities, listening), setOf(origin))
        if (!listening) onUnavailable("此 Android System WebView 不支持安全手机入口；请更新后使用软件设置、扫码及配对入口。")
    }

    fun update(next: DshWebClientContext, nextActions: DshWebHostActions) {
        if (closed) return
        actions = nextActions
        val capabilities = displayCapabilities()
        if (next == client && capabilities == publishedCapabilities) return
        val modeChanged = next.mode != client.mode
        client = next
        cancelPending()
        if (modeChanged) setPresentationReady(false)
        try {
            val replacement = WebViewCompat.addDocumentStartJavaScript(view,
                bootstrapScript(origin, client, capabilities, listening), setOf(origin))
            val previous = bootstrap
            bootstrap = replacement
            previous?.remove()
            publishedCapabilities = capabilities
            publishUpdate(capabilities)
        } catch (_: Exception) {
            publishedCapabilities = emptyList()
            actions = DshWebHostActions()
            runCatching { publishUpdate(emptyList()) }
            onUnavailable("手机网页入口暂时不可用；请更新 Android System WebView 后重试。")
        }
    }

    private fun publishUpdate(capabilities: List<String>) {
        // Only native-serialized preferences/capabilities enter this expression.
        // The same document-start adapter owns its closure-based update method.
        if (ownedDocument()) view.evaluateJavascript("(()=>{if(window===window.top&&location.origin===" +
            JSONObject.quote(origin) + "&&location.pathname==='/')window.__DAFEIYU_DSH_HOST_UPDATE__?.(" +
            client.toJson().toString() + "," + JSONArray(capabilities).toString() + ")})()", null)
    }

    fun pageStarted() {
        if (closed) return
        cancelPending()
        seen.clear()
        boundDocument = null
        setPresentationReady(false)
    }

    /** A committed document is identified independently of page load/engine readiness. */
    fun pageCommitted() {
        if (closed || !ownedDocument()) return
        val epoch = generation
        val probe = "(()=>{if(window!==window.top||location.origin!==" + JSONObject.quote(origin) +
            "||location.pathname!=='/'||new URL(location.href).searchParams.has('dafeiyu-preview'))return null;" +
            "return window.__DAFEIYU_DSH_DISPLAY_DOCUMENT__?.version===1?" +
            "window.__DAFEIYU_DSH_DISPLAY_DOCUMENT__.id:null})()"
        runCatching {
            view.evaluateJavascript(probe) { value ->
                if (closed || epoch != generation || !ownedDocument()) return@evaluateJavascript
                val document = runCatching { JSONArray("[$value]").opt(0) as? String }.getOrNull()
                    ?.takeIf { DOCUMENT.matches(it) } ?: return@evaluateJavascript
                boundDocument = document
                runCatching {
                    view.evaluateJavascript("(()=>{if(window===window.top&&location.origin===" +
                        JSONObject.quote(origin) + "&&location.pathname==='/'&&" +
                        "!new URL(location.href).searchParams.has('dafeiyu-preview'))" +
                        "window.__DAFEIYU_DSH_HOST_BIND__?.(" + JSONObject.quote(document) + ")})()", null)
                }.onFailure { bindingFailed(epoch) }
            }
        }.onFailure { bindingFailed(epoch) }
    }

    private fun bindingFailed(epoch: Long) {
        if (closed || epoch != generation) return
        cancelPending()
        boundDocument = null
        setPresentationReady(false)
        onUnavailable("DSH 网页显示桥暂时不可用，请重新打开网页。")
    }

    fun pageFailed() {
        if (closed) return
        cancelPending()
        boundDocument = null
        setPresentationReady(false)
    }

    private suspend fun confirmsPresentation(document: String, epoch: Long,
        presentationEpoch: Long): Boolean = suspendCancellableCoroutine { continuation ->
        val probe = "(()=>{try{return window===window.top&&location.origin===" + JSONObject.quote(origin) +
            "&&location.pathname==='/'&&!new URL(location.href).searchParams.has('dafeiyu-preview')&&" +
            "window.__DAFEIYU_DSH_DISPLAY_DOCUMENT__?.id===" + JSONObject.quote(document) +
            "&&window.__DAFEIYU_DSH_PRESENTATION__?.version===1&&" +
            "window.__DAFEIYU_DSH_PRESENTATION__.isReady()===true}catch(_){return false}})()"
        runCatching {
            view.evaluateJavascript(probe) { value ->
                if (continuation.isActive) continuation.resume(value == "true" && !closed &&
                    generation == epoch && presentationGeneration == presentationEpoch &&
                    boundDocument == document && ownedDocument())
            }
        }.onFailure { if (continuation.isActive) continuation.resume(false) }
    }

    private fun cancelPending() {
        ++generation
        val requests = pending.values.toList()
        pending.clear()
        requests.forEach { it.cancel() }
    }

    private fun receive(sender: WebView, message: WebMessageCompat, source: Uri,
        mainFrame: Boolean, reply: JavaScriptReplyProxy) {
        if (closed || sender !== view || !mainFrame || !owned(source) || !ownedDocument() ||
            message.type != WebMessageCompat.TYPE_STRING) return
        val text = message.data ?: return
        if (text.length > REQUEST_CHARS) return
        val request = runCatching { JSONObject(text) }.getOrNull() ?: return
        val id = request.opt("id") as? String ?: return
        if (!ID.matches(id)) return
        val document = request.opt("document") as? String ?: return
        if (!DOCUMENT.matches(document) || document != boundDocument) return
        if (request.opt("version") != 1 || request.length() != 4 ||
            request.keys().asSequence().toSet() != setOf("version", "id", "method", "document")) {
            respond(reply, id, error = "invalid_request")
            return
        }
        val method = request.opt("method") as? String ?: return
        val capability = METHODS[method]
        if (capability == null || capability !in publishedCapabilities || capability !in displayCapabilities()) {
            respond(reply, id, error = "unavailable")
            return
        }
        if (id in seen || pending.size >= MAX_PENDING || seen.size >= MAX_REQUESTS) {
            respond(reply, id, error = "request_limit")
            return
        }
        seen.add(id)
        val epoch = generation
        if (method == "notifyPresentationUnavailable") ++presentationGeneration
        val presentationEpoch = presentationGeneration
        val admitted = actions
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = withTimeout(TIMEOUT_MILLIS) {
                    when (method) {
                        "notifyPresentationReady" -> {
                            check(confirmsPresentation(document, epoch, presentationEpoch))
                            setPresentationReady(true)
                            JSONObject().put("accepted", true)
                        }
                        "notifyPresentationUnavailable" -> {
                            setPresentationReady(false)
                            JSONObject().put("accepted", true)
                        }
                        "getConnectionState" -> publicConnectionState(checkNotNull(admitted.connectionState).invoke())
                        else -> {
                            val callback = when (method) {
                                "openAppSettings" -> admitted.openAppSettings
                                "openConnectionSettings" -> admitted.openConnectionSettings
                                "openScanner" -> admitted.openScanner
                                "openPairing" -> admitted.openPairing
                                else -> null
                            }
                            check(checkNotNull(callback).invoke())
                            JSONObject().put("accepted", true)
                        }
                    }
                }
                if (!closed && epoch == generation && boundDocument == document && ownedDocument()) respond(reply, id, result)
            } catch (_: TimeoutCancellationException) {
                if (!closed && epoch == generation && ownedDocument()) {
                    notifyFailure(method)
                    respond(reply, id, error = "timeout")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (!closed && epoch == generation && ownedDocument()) {
                    notifyFailure(method)
                    respond(reply, id, error = "unavailable")
                }
            } finally {
                if (pending[id] === currentCoroutineContext()[Job]) pending.remove(id)
            }
        }
        pending[id] = task
        task.start()
    }

    private fun notifyFailure(method: String) {
        val message = when (method) {
            "openScanner" -> "扫码页面未能打开，请重新尝试。"
            "openPairing" -> "配对页面未能打开，请重新尝试。"
            "getConnectionState" -> "暂时无法读取 Aries 展示状态，请在在线设置中检查连接。"
            "notifyPresentationReady" -> "DSH 网页仍在准备，请等待真实连接就绪。"
            else -> "设置页面未能打开，请重新尝试。"
        }
        runCatching { actions.onFailure?.invoke(message) }
    }

    private fun respond(reply: JavaScriptReplyProxy, id: String, result: JSONObject? = null,
        error: String? = null) {
        val response = JSONObject().put("version", 1).put("id", id).put("ok", error == null)
        if (error == null) response.put("result", result ?: JSONObject.NULL) else response.put("error", error)
        val text = response.toString()
        if (text.length <= RESPONSE_CHARS) runCatching { reply.postMessage(text) }
    }

    fun dispose() {
        if (closed) return
        setPresentationReady(false)
        closed = true
        cancelPending()
        scope.cancel()
        bootstrap?.let { runCatching { it.remove() } }
        bootstrap = null
        if (listening) runCatching { WebViewCompat.removeWebMessageListener(view, CHANNEL) }
        listening = false
        seen.clear()
        actions = DshWebHostActions()
        boundDocument = null
    }

    companion object {
        private const val CHANNEL = "__dafeiyuNativeDisplay"
        private const val REQUEST_CHARS = 512
        private const val RESPONSE_CHARS = 65_536
        private const val MAX_PENDING = 8
        private const val MAX_REQUESTS = 4096
        private const val TIMEOUT_MILLIS = 15_000L
        private val ID = Regex("[A-Za-z0-9_-]{1,96}")
        private val DOCUMENT = Regex("d[A-Za-z0-9_-]{8,95}")
        private const val PRESENTATION_CAPABILITY = "app.web.presentation"
        private val METHODS = mapOf("openAppSettings" to "app.settings.open",
            "openConnectionSettings" to "connection.settings.open", "openScanner" to "app.scan.open",
            "openPairing" to "pairing.open", "getConnectionState" to "connection.state",
            "notifyPresentationReady" to PRESENTATION_CAPABILITY,
            "notifyPresentationUnavailable" to PRESENTATION_CAPABILITY)

        fun install(view: WebView, origin: String, client: DshWebClientContext,
            actions: DshWebHostActions, onUnavailable: (String) -> Unit,
            onPresentationReadyChanged: (Boolean) -> Unit = {}): DshWebHostBridge {
            check(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
            val bridge = DshWebHostBridge(view, origin, client, actions, onUnavailable, onPresentationReadyChanged)
            try { bridge.install(); return bridge }
            catch (failure: Exception) { bridge.dispose(); throw failure }
        }

        /** Reconstruct a bounded public display projection. Never copy provider JSON. */
        internal fun publicConnectionState(raw: JSONObject): JSONObject {
            require(raw.optString("protocol") == "dafeiyu-go.connection.v1")
            val server = raw.opt("server_connected") as? Boolean ?: error("invalid state")
            val pairing = raw.optString("pairing_status").takeIf {
                it in setOf("paired", "unpaired", "awaiting_approval", "unknown", "revoked")
            } ?: error("invalid state")
            fun displayId(value: Any?): String? = (value as? String)?.takeIf {
                it.length in 1..128 && it.none(Char::isISOControl)
            }
            fun describe(source: JSONObject): JSONObject? {
                val id = displayId(source.opt("id")) ?: return null
                val online = source.optString("online", "unknown").takeIf { it in setOf("online", "offline", "unknown") }
                    ?: return null
                val name = (source.opt("name") as? String)?.trim()?.takeIf {
                    it.isNotEmpty() && it.codePointCount(0, it.length) <= 128 && it.none(Char::isISOControl)
                }
                return JSONObject().put("id", id).put("name", name ?: JSONObject.NULL).put("online", online)
            }
            val group = displayId(raw.opt("group_id"))
            val state = JSONObject().put("protocol", "dafeiyu-go.connection.v1")
                .put("server_connected", server).put("pairing_status", pairing)
                .put("group_id", group ?: JSONObject.NULL)
                .put("capabilities", JSONObject().put("presence",
                    raw.optJSONObject("capabilities")?.opt("presence") == true && server && pairing == "paired" && group != null))
                .put("remote_device", raw.optJSONObject("remote_device")?.let(::describe) ?: JSONObject.NULL)
            val members = raw.optJSONArray("members")
            if (members != null && members.length() <= 200) {
                val roster = JSONArray()
                val ids = mutableSetOf<String>()
                var complete = true
                for (index in 0 until members.length()) {
                    val source = members.optJSONObject(index)
                    val member = source?.let(::describe)
                    val active = source?.opt("active") as? Boolean
                    if (member == null || active == null || !ids.add(member.getString("id"))) { complete = false; break }
                    roster.put(member.put("active", active))
                }
                // Do not turn a partial/malformed/unknown roster into a count.
                if (complete && roster.toString().length <= 48_000) state.put("members", roster)
            }
            return state
        }

        private fun bootstrapScript(origin: String, client: DshWebClientContext,
            capabilities: List<String>, listening: Boolean): String = """
            (()=>{
              'use strict';
              if(window!==window.top||location.origin!==${JSONObject.quote(origin)}||location.pathname!=='/'||
                 new URL(location.href).searchParams.has('dafeiyu-preview'))return;
              const native=window.${CHANNEL};
              const pending=new Map();
              const methods=${JSONObject(METHODS).toString()};
              let caps=${JSONArray(capabilities).toString()};
              let active=true,bound=false,serial=0;
              const prefix='h'+Date.now().toString(36)+'_'+Math.random().toString(36).slice(2,14)+'_';
              const documentId='d'+Date.now().toString(36)+'_'+Math.random().toString(36).slice(2,18);
              Object.defineProperty(window,'__DAFEIYU_DSH_DISPLAY_DOCUMENT__',{configurable:false,writable:false,
                value:Object.freeze({version:1,id:documentId})});
              function release(reason){
                for(const item of pending.values()){clearTimeout(item.timer);item.reject(new Error(reason));}
                pending.clear();
              }
              function request(method){
                if(!active||!bound||!caps.includes(methods[method])||typeof native?.postMessage!=='function')
                  return Promise.reject(new Error('unavailable'));
                if(pending.size>=8||serial>=4096)return Promise.reject(new Error('request_limit'));
                return new Promise((resolve,reject)=>{
                  const id=prefix+(++serial);
                  const timer=setTimeout(()=>{if(pending.delete(id))reject(new Error('timeout'));},15000);
                  pending.set(id,{resolve,reject,timer});
                  try{native.postMessage(JSON.stringify({version:1,id,method,document:documentId}));}
                  catch(_){clearTimeout(timer);pending.delete(id);reject(new Error('unavailable'));}
                });
              }
              if(typeof native?.postMessage==='function')native.onmessage=event=>{
                if(typeof event.data!=='string'||event.data.length>65536)return;
                let data;try{data=JSON.parse(event.data);}catch(_){return;}
                if(!data||data.version!==1||typeof data.id!=='string'||typeof data.ok!=='boolean')return;
                const item=pending.get(data.id);if(!item)return;
                pending.delete(data.id);clearTimeout(item.timer);
                if(data.ok)item.resolve(data.result);else item.reject(new Error(
                  ['invalid_request','unavailable','request_limit','timeout'].includes(data.error)?data.error:'unavailable'));
              };
              function publish(context,nextCaps){
                release('context_changed');
                caps=Array.isArray(nextCaps)?nextCaps.filter(value=>Object.values(methods).includes(value)):[];
                Object.defineProperty(window,'__DAFEIYU_DSH_CLIENT__',{configurable:true,writable:false,
                  value:Object.freeze({...context,version:1,platform:'android',surface:'mobile'})});
                const host={version:1,platform:'android',document_bound:bound,capabilities:Object.freeze(caps.slice())};
                for(const method of Object.keys(methods))if(caps.includes(methods[method]))host[method]=()=>request(method);
                if(!${listening})host.unavailable_reason='web_message_listener_unavailable';
                Object.defineProperty(window,'__DAFEIYU_DSH_HOST__',{configurable:true,writable:false,value:Object.freeze(host)});
                window.dispatchEvent(new Event('dafeiyu-dsh-client-change'));
                window.dispatchEvent(new Event('dafeiyu-dsh-host-change'));
              }
              Object.defineProperty(window,'__DAFEIYU_DSH_HOST_UPDATE__',{configurable:true,writable:false,value:publish});
              Object.defineProperty(window,'__DAFEIYU_DSH_HOST_BIND__',{configurable:false,writable:false,value:id=>{
                if(!active||id!==documentId)return;bound=true;publish(window.__DAFEIYU_DSH_CLIENT__,caps);
              }});
              window.addEventListener('pagehide',()=>{active=false;bound=false;release('page_closed');});
              window.addEventListener('pageshow',()=>{active=true;});
              publish(${client.toJson().toString()},caps);
            })()
            """.trimIndent()
    }
}
