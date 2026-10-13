package com.dafeiyu.mobile.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.util.UUID
import org.json.JSONObject

/** The same owned, authenticated loopback surface is used in both DSH modes. */
internal object DshWebViewFactory {
    const val BOOTSTRAP_UNAVAILABLE = -10_001
    const val DISPLAY_CONTEXT_UNAVAILABLE = -10_002
    const val RENDERER_UNAVAILABLE = -10_003

    private class OwnedWebView(context: Context) : WebView(context) {
        var displayHost: DshWebHostBridge? = null
        var rendererGone = false
        var destroyed = false
        override fun destroy() {
            if (destroyed) return
            destroyed = true
            displayHost?.dispose()
            displayHost = null
            super.destroy()
        }
    }

    /** Renderer exit cannot be recovered by reloading the retired instance. */
    fun release(view: WebView) {
        val owned = view as? OwnedWebView
        if (owned?.destroyed == true) return
        if (owned?.rendererGone != true) view.stopLoading()
        view.destroy()
    }

    fun updateDisplayHost(view: WebView, client: DshWebClientContext?, actions: DshWebHostActions) {
        val owned = view as? OwnedWebView ?: return
        if (owned.destroyed || owned.rendererGone) return
        if (client == null) {
            owned.displayHost?.dispose()
            owned.displayHost = null
        } else owned.displayHost?.update(client, actions)
    }

    fun create(context: Context, access: String, selectedSession: String?,
        onPageStarted: (WebView) -> Unit = {}, onPageFinished: (WebView) -> Unit = {},
        onMainFrameError: () -> Unit = {},
        onMainFrameFailureCode: (Int, Boolean) -> Unit = { _, _ -> },
        onViewCreated: (WebView) -> Unit = {},
        mobileClient: DshWebClientContext? = null,
        hostActions: DshWebHostActions = DshWebHostActions(),
        onDisplayHostUnavailable: (String) -> Unit = {},
        onDisplayReadyChanged: (WebView, Boolean) -> Unit = { _, _ -> },
        onRendererUnavailable: (WebView, Boolean) -> Unit = { _, _ -> }): WebView {
        val expected = Uri.parse(access)
        require(expected.scheme == "http" && expected.host == "127.0.0.1" &&
            expected.port in 1024..65535 && expected.userInfo == null) { "DSH 网页入口必须是本机已认证地址" }
        return OwnedWebView(context).apply {
            // Draw once through the hardware-accelerated window, like the
            // reviewed DSHA rc2 FrameLayout host. Compose must not record or
            // replay this view for page transitions or glass backdrop sampling.
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            @Suppress("SetJavaScriptEnabled")
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.setGeolocationEnabled(false)
            webViewClient = object : WebViewClient() {
                private fun sameOrigin(uri: Uri): Boolean = uri.scheme == expected.scheme &&
                    uri.host == expected.host && uri.port == expected.port && uri.userInfo == null
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !sameOrigin(request.url)
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    if (sameOrigin(request.url)) null else WebResourceResponse("text/plain", "UTF-8", 403,
                        "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    val owned = view as? OwnedWebView ?: return
                    if (owned.destroyed || owned.rendererGone) return
                    owned.displayHost?.pageStarted()
                    onDisplayReadyChanged(view, false)
                    onPageStarted(view)
                }
                override fun onPageCommitVisible(view: WebView, url: String) {
                    val owned = view as? OwnedWebView ?: return
                    if (!owned.destroyed && !owned.rendererGone && sameOrigin(Uri.parse(url)))
                        owned.displayHost?.pageCommitted()
                }
                override fun onPageFinished(view: WebView, url: String) {
                    val owned = view as? OwnedWebView ?: return
                    if (!owned.destroyed && !owned.rendererGone) onPageFinished(view)
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    val owned = view as? OwnedWebView ?: return
                    if (owned.destroyed || owned.rendererGone) return
                    if (request.isForMainFrame) {
                        owned.displayHost?.pageFailed()
                        onDisplayReadyChanged(view, false)
                        onMainFrameFailureCode(error.errorCode, false)
                        onMainFrameError()
                    }
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                    val owned = view as? OwnedWebView ?: return
                    if (owned.destroyed || owned.rendererGone) return
                    if (request.isForMainFrame) {
                        owned.displayHost?.pageFailed()
                        onDisplayReadyChanged(view, false)
                        onMainFrameFailureCode(errorResponse.statusCode, true)
                        onMainFrameError()
                    }
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    val owned = view as? OwnedWebView ?: return false
                    if (owned.destroyed || owned.rendererGone) return true
                    owned.rendererGone = true
                    owned.displayHost?.dispose()
                    owned.displayHost = null
                    onDisplayReadyChanged(view, false)
                    // Do not log the authenticated URL, queries or credentials.
                    Log.w("DafeiyuDshWeb", "renderer_gone crash=${detail.didCrash()} priority=${detail.rendererPriorityAtExit()}")
                    onMainFrameFailureCode(RENDERER_UNAVAILABLE, false)
                    onMainFrameError()
                    onRendererUnavailable(view, detail.didCrash())
                    (view.parent as? ViewGroup)?.removeView(view)
                    owned.destroy()
                    return true
                }
            }
            tag = access
            // Establish ownership before bootstrap can synchronously report an
            // unavailable feature, and before native navigation emits callbacks.
            onViewCreated(this)
            if (mobileClient != null) {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    onMainFrameError()
                    onMainFrameFailureCode(DISPLAY_CONTEXT_UNAVAILABLE, false)
                    return@apply
                }
                val origin = "http://127.0.0.1:${expected.port}"
                try {
                    displayHost = DshWebHostBridge.install(this, origin, mobileClient, hostActions,
                        onDisplayHostUnavailable) { ready ->
                            if (!destroyed && !rendererGone) onDisplayReadyChanged(this, ready)
                        }
                } catch (_: Exception) {
                    onMainFrameError()
                    onMainFrameFailureCode(DISPLAY_CONTEXT_UNAVAILABLE, false)
                    return@apply
                }
            }
            if (selectedSession != null) {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    onMainFrameError()
                    onMainFrameFailureCode(BOOTSTRAP_UNAVAILABLE, false)
                    return@apply
                }
                val selection = JSONObject().put("sessionId", selectedSession).toString()
                val origin = "http://127.0.0.1:${expected.port}"
                val marker = JSONObject.quote(UUID.randomUUID().toString())
                // Authenticate with a native navigation. Inject only in the exact
                // owned origin, before the first-party store reads its selection.
                // The per-view marker prevents later reloads from resetting it.
                val bootstrap = "(()=>{if(window!==window.top||location.pathname!=='/')return;" +
                    "const key='dafeiyu.dsh.selection-bootstrap',marker=$marker;" +
                    "if(sessionStorage.getItem(key)===marker)return;" +
                    "localStorage.setItem('dsh.sessions.current'," + JSONObject.quote(selection) + ");" +
                    "sessionStorage.setItem(key,marker)})()"
                WebViewCompat.addDocumentStartJavaScript(this, bootstrap, setOf(origin))
            }
            loadUrl(expected.toString())
        }
    }
}
