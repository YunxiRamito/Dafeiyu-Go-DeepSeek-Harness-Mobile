package com.dafeiyu.mobile.data

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ExtensionRecommendation(val id: String, val name: String, val description: String, val category: String,
    val github: GithubExtensionReference?, val npm: String?, val license: String, val note: String)
data class ExtensionRecommendationResult(val items: List<ExtensionRecommendation>, val fromCache: Boolean,
    val sourceCommit: String?, val warning: String?)

/** Mobile recommendations have their own GitHub files, independent from the PC channel. */
class RecommendationFeed(context: Context, private val github: GithubExtensionSource = GithubExtensionSource(context)) {
    private val app = context.applicationContext
    private val cache = File(app.filesDir, "mobile-extension-recommendations")

    suspend fun load(skills: Boolean, source: String, refresh: Boolean = false): ExtensionRecommendationResult = withContext(Dispatchers.IO) {
        ExtensionFiles.directory(cache)
        val name = if (skills) "mobile-skills.json" else "mobile-dsh-plugins.json"
        val saved = File(cache, name)
        fun cached(): ExtensionRecommendationResult? = runCatching {
            if (!saved.isFile) null else {
                val document = JSONObject(ExtensionFiles.text(saved, 256 * 1024L))
                ExtensionRecommendationResult(parse(document.getJSONObject("feed"), skills), true,
                    document.optString("commit").takeIf { it.matches(Regex("[a-f0-9]{40}")) }, null)
            }
        }.getOrNull()
        if (!refresh) cached()?.let { return@withContext it }
        val stage = File(cache, "read-${UUID.randomUUID()}")
        try {
            val reference = GithubExtensionReference("YunxiRamito", "Dafeiyu-Go-DeepSeek-Harness-Mobile", "recommendations/$name", "main")
            val fixed = github.resolve(reference)
            val file = github.download(fixed, stage, source)
            val document = JSONObject(ExtensionFiles.text(file, 256 * 1024L))
            val items = parse(document, skills)
            ExtensionFiles.atomic(saved, JSONObject().put("commit", fixed.commit).put("feed", document).toString())
            ExtensionRecommendationResult(items, false, fixed.commit, null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failed: Exception) {
            cached()?.copy(warning = "推荐源未更新，使用上次缓存：${failed.message}")
                ?: ExtensionRecommendationResult(emptyList(), false, null, "推荐源暂不可读取：${failed.message}")
        } finally { if (stage.exists()) ExtensionFiles.deleteOwned(cache, stage) }
    }

    companion object {
        fun parse(document: JSONObject, skills: Boolean): List<ExtensionRecommendation> {
            require(document.getInt("schemaVersion") == 1 && document.getString("platform") == "android") { "不是手机推荐清单" }
            val array = document.getJSONArray("items")
            require(array.length() <= 200)
            val ids = HashSet<String>()
            return List(array.length()) { index ->
                val item = array.getJSONObject(index)
                val id = item.getString("id")
                require(id.matches(Regex("[a-z0-9][a-z0-9._-]{0,100}")) && ids.add(id)) { "推荐条目 ID 无效或重复" }
                val reference = item.optJSONObject("github")?.let { value -> GithubExtensionReference(value.getString("owner"),
                    value.getString("repository"), value.optString("path"), value.getString("ref")) }
                val npm = item.optString("npm").takeIf { it.isNotBlank() }
                require(reference != null || !skills && npm != null) { "推荐条目缺少安装来源" }
                npm?.let { require(NpmExtensionSpec.isValid(it)) { "推荐 npm 包规格无效" } }
                val name = item.getString("name"); val description = item.getString("description")
                require(name.isNotBlank() && name.length <= 200 && description.length <= 4_096)
                ExtensionRecommendation(id, name, description, if (skills) "skill" else "plugin", reference, npm,
                    item.optString("license"), item.optString("note"))
            }
        }
    }
}

/** Registry specs only. Git, URL, alias and local paths must use the reviewed explicit source importer. */
object NpmExtensionSpec {
    private val pattern = Regex("^(?:@[a-z0-9._-]+/)?[a-z0-9][a-z0-9._-]*(?:@(?:[a-zA-Z0-9][a-zA-Z0-9.+_-]*))?$")
    fun isValid(value: String): Boolean = value.length <= 240 && pattern.matches(value) && value.none { it in setOf(':', '\\') }
    fun packageName(value: String): String {
        require(isValid(value))
        val separator = value.indexOf('@', 1)
        return if (separator >= 0) value.substring(0, separator) else value
    }
}
