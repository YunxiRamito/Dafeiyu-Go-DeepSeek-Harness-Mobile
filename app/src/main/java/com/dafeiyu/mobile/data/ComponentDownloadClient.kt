package com.dafeiyu.mobile.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.X509TrustManager
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Port of the PC launcher's source separation: CDN fallback never includes the private backend. */
object ComponentDownloadSources {
    const val BACKEND = "https://202.189.21.218:8787"
    private val mirrors = listOf("https://ghproxy.net/", "https://gh-proxy.com/", "https://ghfast.top/")
    fun candidates(url: String, source: String): List<String> {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.userInfo == null && uri.fragment == null && !uri.host.isNullOrBlank())
        if (url.startsWith(BACKEND + "/")) return listOf(url)
        if (uri.host in setOf("api.github.com", "uploads.github.com")) return listOf(url)
        return when (source) {
            "backend" -> listOf("$BACKEND/api/download?url=" + URLEncoder.encode(url, "UTF-8"))
            "official" -> listOf(url)
            else -> when (uri.host) {
                "github.com", "raw.githubusercontent.com", "codeload.github.com", "objects.githubusercontent.com" ->
                    mirrors.map { it + url } + url
                "registry.npmjs.org" -> listOf(url.replace("https://registry.npmjs.org/", "https://registry.npmmirror.com/"), url)
                "nodejs.org" -> listOf(url.replace("https://nodejs.org/dist/", "https://npmmirror.com/mirrors/node/"), url)
                else -> listOf(url)
            }
        }
    }
    fun npmRegistry(source: String): String = if (source == "cdn") "https://registry.npmmirror.com" else "https://registry.npmjs.org"
}

class ComponentDownloadClient(context: Context, directClient: OkHttpClient? = null) {
    private val app = context.applicationContext
    private val official = directClient ?: OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(15, TimeUnit.MINUTES)
        .followSslRedirects(false).build()
    private val backend by lazy {
        val resource = app.resources.getIdentifier("dafeiyu_download_ca", "raw", app.packageName)
        check(resource != 0) { "下载服务器 CA 资源缺失" }
        val certificates = app.resources.openRawResource(resource).use {
            CertificateFactory.getInstance("X.509").generateCertificates(it)
        }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            certificates.forEachIndexed { i, certificate -> setCertificateEntry("download-$i", certificate) }
        }
        val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
        val ssl = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        official.newBuilder().sslSocketFactory(ssl.socketFactory, manager)
            .certificatePinner(okhttp3.CertificatePinner.Builder().add("202.189.21.218",
                "sha256/" + android.util.Base64.encodeToString(hex("2c72728755267c607124b2ac86e431743c34af5c6069aa22bbcc401e0f626f0b"), android.util.Base64.NO_WRAP)).build())
            .followRedirects(false).build()
    }

    /** First-party management uses the same private CA, host verification and SPKI pin. */
    internal fun pinnedBackendClient(): OkHttpClient = backend

    suspend fun download(url: String, expectedSha256: String, destination: File, source: String,
        progress: (Long, Long?) -> Unit = { _, _ -> }): File = downloadArtifact(
            ArtifactDownloadRequest(listOf(url), destination, ArtifactChecksum("SHA-256", expectedSha256)),
            source,
        ) { value -> progress(value.receivedBytes, value.totalBytes) }.file

    suspend fun downloadArtifact(request: ArtifactDownloadRequest, source: String,
        progress: (ArtifactDownloadProgress) -> Unit = {}): ArtifactDownloadResult {
        val candidates = request.urls.flatMap { ComponentDownloadSources.candidates(it, source) }.distinct()
        val downloader = ArtifactDownloader(app.filesDir, { url ->
            if (url.startsWith(ComponentDownloadSources.BACKEND + "/")) backend else official
        }) { url ->
            if (url.startsWith(ComponentDownloadSources.BACKEND + "/api/download?")) awaitBackend(url, backend) { received, total ->
                progress(ArtifactDownloadProgress(received, total, 0, url, "正在准备服务器下载缓存"))
            }
        }
        return downloader.download(request.copy(urls = candidates, allowHttpForTests = false), progress)
    }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    private suspend fun awaitBackend(url: String, client: OkHttpClient, progress: (Long, Long?) -> Unit) {
        val started = System.nanoTime()
        while (TimeUnit.NANOSECONDS.toMinutes(System.nanoTime() - started) < 15) {
            currentCoroutineContext().ensureActive()
            val call = client.newCall(Request.Builder().url(url.replace("/api/download?", "/api/download/status?")).build())
            val cancellation = currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (it != null) call.cancel() }
            val ready = try { call.execute().use {
                if (!it.isSuccessful) throw IOException("服务器准备响应 HTTP ${it.code}")
                val input = it.body?.byteStream() ?: throw IOException("服务器准备响应为空")
                val bytes = input.use { body ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= 128 * 1024) {
                        val count = body.read(buffer, 0, minOf(buffer.size, 128 * 1024 + 1 - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                check(bytes.size <= 128 * 1024)
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                progress(json.optLong("receivedBytes", 0L), null)
                json.optBoolean("ready")
            } } finally { cancellation?.dispose() }
            if (ready) return
            delay(2_000)
        }
        throw IOException("下载服务器准备超时")
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
