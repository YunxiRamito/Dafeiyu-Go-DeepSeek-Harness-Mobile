package com.dafeiyu.mobile.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Local cache and protocol validator for DeveloperCenter public messages.
 *
 * The store only reads and writes structured JSON in the app-private directory.
 * Notice actions are data; this class never opens URLs, changes settings, or
 * executes PowerShell commands.
 */
class NoticeStore(context: Context) {
    private val cacheFile = File(context.applicationContext.filesDir, CACHE_RELATIVE_PATH)

    enum class Status {
        AVAILABLE,
        NO_DATA,
        READ_FAILED,
        DEMO,
    }

    data class ReadResult(
        val status: Status,
        val items: List<Notice> = emptyList(),
        val sourcePath: String? = null,
        val errorMessage: String? = null,
    ) {
        val hasData: Boolean get() = status == Status.AVAILABLE && items.isNotEmpty()

        companion object {
            fun demo(reason: String = "当前页面使用演示状态，未读取真实公告。") = ReadResult(
                status = Status.DEMO,
                errorMessage = reason,
            )
        }
    }

    data class Notice(
        val id: String,
        val kind: Kind,
        val title: String,
        val markdown: String,
        val date: String?,
        val publishedAtEpochMillis: Long,
        val expiresAtEpochMillis: Long?,
        val buttons: List<Button> = emptyList(),
    )

    enum class Kind(val wireValue: String) {
        ANNOUNCEMENT("announcement"),
        NOTIFICATION("notification"),
        ;

        companion object {
            fun parse(raw: String): Kind? = entries.firstOrNull { it.wireValue == raw.trim().lowercase() }
        }
    }

    data class Button(
        val label: String,
        val action: Action,
        val target: String? = null,
    )

    enum class Action(val wireValue: String) {
        URL("url"),
        SETTINGS("settings"),
        POWERSHELL("powershell"),
        DISMISS("dismiss"),
        ;

        companion object {
            fun parse(raw: String): Action? = entries.firstOrNull { it.wireValue == raw.trim().lowercase() }
        }
    }

    /** Reads the current app-private cache. Missing cache is a normal no-data state. */
    fun read(): ReadResult {
        if (!cacheFile.exists()) return ReadResult(Status.NO_DATA, sourcePath = cacheFile.path)
        if (!cacheFile.isFile) return failed("公告缓存路径不是文件。")
        if (cacheFile.length() > MAX_CACHE_BYTES) return failed("公告缓存超过大小限制。")
        return try {
            val root = cacheFile.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            require(root.optInt("version", -1) == 1) { "公告缓存版本不支持。" }
            val items = parseMessages(root, filterExpired = true)
            if (items.isEmpty()) ReadResult(Status.NO_DATA, sourcePath = cacheFile.path)
            else ReadResult(Status.AVAILABLE, items, cacheFile.path)
        } catch (error: IOException) {
            failed(error.message ?: "无法读取公告缓存。")
        } catch (error: SecurityException) {
            failed(error.message ?: "没有读取公告缓存的权限。")
        } catch (error: IllegalArgumentException) {
            failed(error.message ?: "公告缓存格式无效。")
        } catch (error: org.json.JSONException) {
            failed(error.message ?: "公告缓存格式无效。")
        }
    }

    /** Saves a validated feed directly to the app-private cache. */
    fun save(items: List<Notice>): Result<Unit> = runCatching {
        require(items.size <= MAX_MESSAGE_COUNT) { "公告数量超过限制。" }
        items.forEach { validate(it) }
        val parent = cacheFile.parentFile ?: error("公告缓存目录无效。")
        if (!parent.isDirectory && !parent.mkdirs()) error("无法创建公告缓存目录。")
        val root = JSONObject().put("version", 1).put("messages", JSONArray().apply {
            items.forEach { put(encode(it)) }
        })
        cacheFile.outputStream().bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(root.toString()) }
    }

    private fun encode(item: Notice): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("kind", item.kind.wireValue)
        put("title", item.title)
        put("markdown", item.markdown)
        put("date", item.date ?: JSONObject.NULL)
        put("publishedAt", Instant.ofEpochMilli(item.publishedAtEpochMillis).toString())
        put("expiresAt", item.expiresAtEpochMillis?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
        put("buttons", JSONArray().apply {
            item.buttons.forEach { button ->
                put(JSONObject().apply {
                    put("label", button.label)
                    put("action", button.action.wireValue)
                    when (button.action) {
                        Action.URL -> put("url", button.target ?: "")
                        Action.SETTINGS -> put("page", button.target ?: "")
                        Action.POWERSHELL -> put("command", button.target ?: "")
                        Action.DISMISS -> Unit
                    }
                })
            }
        })
    }

    private fun validate(item: Notice) {
        require(item.id.isNotBlank() && item.id.length <= 128)
        require(item.title.isNotBlank() && item.title.length <= 256)
        require(item.markdown.length <= 65_536)
        require(item.buttons.size <= 2 && (item.kind != Kind.ANNOUNCEMENT || item.buttons.isEmpty()))
        item.buttons.forEach { button ->
            require(button.label.isNotBlank() && button.label.length <= 80)
            when (button.action) {
                Action.URL -> require(button.target?.let { value ->
                    val uri = runCatching { URI(value) }.getOrNull()
                    uri != null && uri.scheme.equals("https", true) && uri.userInfo.isNullOrEmpty()
                } == true)
                Action.SETTINGS -> require(button.target in ALLOWED_SETTINGS_PAGES)
                Action.POWERSHELL -> require(!button.target.isNullOrBlank() && button.target.length <= 8_000)
                Action.DISMISS -> require(button.target == null)
            }
        }
    }

    private fun failed(message: String) = ReadResult(
        status = Status.READ_FAILED,
        sourcePath = cacheFile.path,
        errorMessage = message,
    )

    companion object {
        const val CACHE_RELATIVE_PATH = "developer-center/notices.json"
        private val DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE
        private val ALLOWED_SETTINGS_PAGES = setOf("Home", "General", "Extensions", "Core", "Updates", "Patches", "About")
        private const val MAX_MESSAGE_COUNT = 256
        private const val MAX_CACHE_BYTES = 2L * 1024L * 1024L

        /** Converts and validates the formal DeveloperCenter `/api/messages` response. */
        internal fun parseRemoteFeed(
            root: JSONObject,
            nowEpochMillis: Long = System.currentTimeMillis(),
        ): List<Notice> = parseMessages(root, filterExpired = true, nowEpochMillis = nowEpochMillis)

        private fun parseMessages(
            root: JSONObject,
            filterExpired: Boolean,
            nowEpochMillis: Long = System.currentTimeMillis(),
        ): List<Notice> {
            val array = root.optJSONArray("messages") ?: throw IllegalArgumentException("公告响应缺少 messages。")
            require(array.length() <= MAX_MESSAGE_COUNT) { "公告数量超过限制。" }
            return buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val notice = parseItem(item) ?: continue
                    if (filterExpired && notice.expiresAtEpochMillis?.let { it <= nowEpochMillis } == true) continue
                    add(notice)
                }
            }.sortedWith(compareByDescending<Notice> { it.kind == Kind.ANNOUNCEMENT }
                .thenByDescending { it.publishedAtEpochMillis }
                .thenBy { it.id })
        }

        private fun parseItem(json: JSONObject): Notice? {
            val id = json.optString("id").trim()
            val title = json.optString("title")
            val markdown = json.optString("markdown", json.optString("body", ""))
            val kind = Kind.parse(json.optString("kind")) ?: return null
            if (id.isBlank() || id.length > 128 || title.isBlank() || title.length > 256 || markdown.length > 65_536) return null
            val published = parseTimestamp(json.optString("publishedAt")) ?: return null
            val expires = if (json.isNull("expiresAt") || !json.has("expiresAt")) null
            else parseTimestamp(json.optString("expiresAt")) ?: return null
            val date = json.optString("date").takeIf { it.isNotBlank() }?.also {
                runCatching { LocalDate.parse(it, DATE_FORMAT) }.getOrElse { return null }
            }
            val buttons = parseButtons(json.optJSONArray("buttons")) ?: return null
            if (buttons.size > 2 || (kind == Kind.ANNOUNCEMENT && buttons.isNotEmpty())) return null
            return Notice(id, kind, title, markdown, date, published, expires, buttons)
        }

        private fun parseButtons(array: JSONArray?): List<Button>? {
            if (array == null) return emptyList()
            if (array.length() > 2) return null
            return buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: return null
                    val label = json.optString("label").trim()
                    val action = Action.parse(json.optString("action")) ?: return null
                    if (label.isBlank() || label.length > 80) return null
                    val target = when (action) {
                        Action.URL -> json.optString("url").takeIf { it.isNotBlank() }?.also { value ->
                            val uri = runCatching { URI(value) }.getOrNull()
                            if (uri == null || !uri.scheme.equals("https", true) || !uri.userInfo.isNullOrEmpty()) return null
                        }
                        Action.SETTINGS -> json.optString("page").takeIf { it in ALLOWED_SETTINGS_PAGES }
                        Action.POWERSHELL -> json.optString("command").takeIf { it.isNotBlank() && it.length <= 8_000 }
                        Action.DISMISS -> null
                    }
                    if (action != Action.DISMISS && target == null) return null
                    add(Button(label, action, target))
                }
            }
        }

        private fun parseTimestamp(raw: String): Long? = runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
    }
}
