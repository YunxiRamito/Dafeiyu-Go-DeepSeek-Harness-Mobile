package com.dafeiyu.mobile.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
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

    fun create(context: Context, access: String, selectedSession: String?,
        onPageStarted: (WebView) -> Unit = {}, onPageFinished: (WebView) -> Unit = {},
        onMainFrameError: () -> Unit = {},
        onMainFrameFailureCode: (Int, Boolean) -> Unit = { _, _ -> }): WebView {
        val expected = Uri.parse(access)
        require(expected.scheme == "http" && expected.host == "127.0.0.1" &&
            expected.port in 1024..65535 && expected.userInfo == null) { "DSH 网页入口必须是本机已认证地址" }
        return WebView(context).apply {
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
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { onPageStarted(view) }
                override fun onPageFinished(view: WebView, url: String) { onPageFinished(view) }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        onMainFrameFailureCode(error.errorCode, false)
                        onMainFrameError()
                    }
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                    if (request.isForMainFrame) {
                        onMainFrameFailureCode(errorResponse.statusCode, true)
                        onMainFrameError()
                    }
                }
            }
            tag = access
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
