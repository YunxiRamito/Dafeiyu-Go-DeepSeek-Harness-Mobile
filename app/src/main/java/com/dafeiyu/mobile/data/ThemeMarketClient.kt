package com.dafeiyu.mobile.data

import android.content.Context
import com.dafeiyu.mobile.glasslab.LabDocument
import java.io.File
import java.io.IOException
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

internal data class ThemeMarketEntry(val id: String, val title: String, val description: String,
    val revision: Int, val sha256: String, val size: Long, val downloadPath: String, val preview: LabDocument)

internal data class ThemeMarketPage(val items: List<ThemeMarketEntry>, val page: Int, val total: Int,
    val pageSize: Int = 10)

internal object ThemeMarketCodec {
    const val MAX_PAGE_BYTES = 2 * 1024 * 1024
    const val PATH = "/api/android/themes"

    fun decode(bytes: ByteArray): ThemeMarketPage {
        require(bytes.size <= MAX_PAGE_BYTES) { "主题目录超过大小限制" }
        val root = JSONObject(ThemeDocuments.utf8(bytes))
        val page = integer(root, "page", 1, 100_000)
        val pageSize = integer(root, "pageSize", 10, 10)
        val total = integer(root, "total", 0, 1_000_000)
        val raw = root.getJSONArray("items")
        require(raw.length() <= pageSize && raw.length() <= total)
        val items = (0 until raw.length()).map { index ->
            val item = raw.getJSONObject(index)
            val id = ThemeDocuments.safeId(string(item, "id"))
            val digest = string(item, "sha256").lowercase().also { require(it.matches(Regex("[a-f0-9]{64}"))) }
            val size = integer(item, "size", 1, LabDocument.MAX_IMPORT_BYTES).toLong()
            val description = string(item, "description").also {
                require(it.codePointCount(0, it.length) <= 500 && it.none { c -> Character.isISOControl(c) && c != '\n' })
            }
            val path = string(item, "downloadPath").also { require(it == "$PATH/$id/file") }
            val preview = item.getJSONObject("preview")
            listOf("light", "dark").forEach { theme ->
                val styles = preview.getJSONObject(theme)
                styles.getJSONObject("circle"); styles.getJSONObject("accent")
            }
            val document = JSONObject(LabDocument.defaults().encode())
                .put("light", preview.getJSONObject("light")).put("dark", preview.getJSONObject("dark"))
            ThemeMarketEntry(id, ThemeDocuments.safeTitle(string(item, "title")), description,
                integer(item, "revision", 1, Int.MAX_VALUE), digest, size, path,
                LabDocument.decode(document.toString()))
        }
        require(items.map { it.id }.distinct().size == items.size)
        return ThemeMarketPage(items, page, total, pageSize)
    }

    fun encode(page: ThemeMarketPage): ByteArray = JSONObject().put("page", page.page).put("pageSize", page.pageSize)
        .put("total", page.total).put("items", JSONArray().apply { page.items.forEach { item ->
            put(JSONObject().put("id", item.id).put("title", item.title).put("description", item.description)
                .put("revision", item.revision).put("sha256", item.sha256).put("size", item.size)
                .put("downloadPath", item.downloadPath).put("preview", ThemeDocuments.materialContext(item.preview)
                    .apply { remove("schema"); remove("renderer") }))
        } }).toString(2).toByteArray(Charsets.UTF_8).also { decode(it) }

    private fun string(root: JSONObject, key: String): String = root.opt(key) as? String
        ?: error("主题目录 $key 必须是文本")
    private fun integer(root: JSONObject, key: String, minimum: Int, maximum: Int): Int {
        val number = root.opt(key) as? Number ?: error("主题目录 $key 必须是整数")
        require(number.toDouble().isFinite() && number.toDouble() >= minimum && number.toDouble() <= maximum &&
            number.toDouble() == number.toInt().toDouble()) { "主题目录 $key 超出范围" }
        return number.toInt()
    }
}

/** Pinned catalog transport. Installed themes and file import remain available when the service is offline. */
internal class ThemeMarketClient private constructor(
    private val root: File,
    private val client: () -> OkHttpClient,
    private val baseUrl: String?,
    private val fixture: Boolean,
) {
    private val http by lazy { client().newBuilder().followRedirects(false).followSslRedirects(false).build() }
    constructor(context: Context) : this(context.applicationContext.filesDir,
        { ComponentDownloadClient(context).pinnedBackendClient() },
        if (BACKEND_AVAILABLE) ComponentDownloadSources.BACKEND else null, false)

    internal constructor(root: File, client: OkHttpClient, baseUrl: String?, fixture: Boolean) :
        this(root, { client }, baseUrl, fixture) { require(fixture) }

    val available: Boolean get() = baseUrl != null

    suspend fun page(page: Int): ThemeMarketPage {
        require(page in 1..100_000)
        val origin = endpoint()
        val request = Request.Builder().url("$origin${ThemeMarketCodec.PATH}?page=$page&pageSize=10")
            .header("Accept", "application/json").build()
        val bytes = exchange(request)
        return ThemeMarketCodec.decode(bytes).also { require(it.page == page) { "主题目录页码不匹配" } }
    }

    suspend fun download(entry: ThemeMarketEntry, progress: (ArtifactDownloadProgress) -> Unit): InstalledTheme =
        withContext(Dispatchers.IO) {
            val origin = endpoint()
            val destination = File(root, "themes/downloads/${entry.sha256}.json")
            val result = ArtifactDownloader(root, { http }).download(ArtifactDownloadRequest(
                urls = listOf(origin + entry.downloadPath), destination = destination,
                checksum = ArtifactChecksum("SHA-256", entry.sha256), expectedSize = entry.size,
                maxBytes = LabDocument.MAX_IMPORT_BYTES.toLong(), allowHttpForTests = fixture,
            ), progress)
            currentCoroutineContext().ensureActive()
            val bytes = result.file.inputStream().use { ThemeDocuments.read(it) }
            require(bytes.size.toLong() == entry.size && ThemeDocuments.sha256(bytes) == entry.sha256) { "主题摘要校验失败" }
            InstalledTheme(entry.id, entry.title, entry.revision, entry.sha256, ThemeDocuments.decode(bytes))
        }

    private fun endpoint(): String {
        val source = baseUrl ?: throw IOException("主题市场暂时离线；已安装主题和自定义主题仍可使用。")
        val uri = URI(source)
        require(uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.isNullOrEmpty())
        require(if (fixture) uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port in 1024..65535
            else source == ComponentDownloadSources.BACKEND) { "主题服务器地址未通过校验" }
        return source
    }

    private suspend fun exchange(request: Request): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("主题市场连接失败；已下载主题仍可使用。", error))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        if (!it.isSuccessful) throw IOException("主题市场返回 HTTP ${it.code}")
                        val body = it.body ?: throw IOException("主题目录为空")
                        require(body.contentLength() <= ThemeMarketCodec.MAX_PAGE_BYTES) { "主题目录超过大小限制" }
                        body.byteStream().use { stream -> ThemeDocuments.read(stream, ThemeMarketCodec.MAX_PAGE_BYTES) }
                    }
                    if (continuation.isActive) continuation.resume(bytes)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        })
    }

    companion object {
        // Deployment is owned by the main agent; request failures preserve installed/custom themes.
        const val BACKEND_AVAILABLE = true
    }
}
