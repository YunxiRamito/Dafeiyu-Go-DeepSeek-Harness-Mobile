package com.dafeiyu.mobile.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class GithubExtensionReference(val owner: String, val repository: String, val path: String = "", val ref: String = "HEAD") {
    init {
        require(owner.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}"))) { "GitHub 作者名无效" }
        require(repository.matches(Regex("[A-Za-z0-9._-]{1,100}")) && repository !in setOf(".", "..")) { "GitHub 仓库名无效" }
        require(path.isEmpty() || ExtensionFiles.relative(path) == path) { "GitHub 子目录无效" }
        require(ref.isNotBlank() && ref.length <= 200 && ref.none { it.code < 32 || it.code == 127 }) { "GitHub 版本无效" }
    }
    val url: String get() = "https://github.com/$owner/$repository" + if (ref == "HEAD" && path.isEmpty()) "" else {
        val encodedRef = URLEncoder.encode(ref, "UTF-8").replace("+", "%20")
        val encodedPath = URLEncoder.encode(path, "UTF-8").replace("+", "%20")
        "?ref=$encodedRef" + if (path.isEmpty()) "" else "&path=$encodedPath"
    }

    companion object {
        /** An explicit ?ref= accepts branch names containing '/'; otherwise tree URLs use their first ref segment. */
        fun parse(value: String): GithubExtensionReference {
            val input = value.trim()
            val uri = if (input.startsWith("https://")) URI(input) else URI("https://github.com/$input")
            require(uri.scheme == "https" && uri.host.equals("github.com", true) && uri.userInfo == null &&
                uri.fragment == null && uri.port == -1) { "请输入 github.com 仓库或子目录链接" }
            val pieces = uri.path.trim('/').split('/')
            require(pieces.size >= 2 && pieces.none { it.isBlank() })
            val query = uri.rawQuery?.split('&').orEmpty().associate { field ->
                field.substringBefore('=') to java.net.URLDecoder.decode(field.substringAfter('=', ""), "UTF-8")
            }
            require(query.keys.all { it == "ref" || it == "path" }) { "GitHub 链接参数不受支持" }
            val explicitRef = query["ref"]
            return when {
                pieces.size == 2 -> GithubExtensionReference(pieces[0], pieces[1].removeSuffix(".git"), query["path"].orEmpty(), explicitRef ?: "HEAD")
                pieces.size >= 4 && pieces[2] in setOf("tree", "blob") -> GithubExtensionReference(pieces[0], pieces[1],
                    query["path"] ?: pieces.drop(4).joinToString("/"), explicitRef ?: pieces[3])
                else -> throw IllegalArgumentException("请输入仓库地址或 tree / blob 子目录链接")
            }
        }
    }
}

data class GithubTreeFile(val path: String, val sha: String, val bytes: Long, val mode: String)
data class FixedGithubExtension(val reference: GithubExtensionReference, val commit: String, val files: List<GithubTreeFile>) {
    val bytes: Long get() = files.sumOf { it.bytes }
    val fixedUrl: String get() = "https://github.com/${reference.owner}/${reference.repository}/tree/$commit" +
        if (reference.path.isEmpty()) "" else "/${reference.path}"
}

/** Resolve mutable user input once, then verify every raw file against the fixed Git tree's blob hash and size. */
class GithubExtensionSource(
    context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).followSslRedirects(false).followRedirects(false).build(),
    private val downloads: ComponentDownloadClient = ComponentDownloadClient(context),
) {
    suspend fun resolve(reference: GithubExtensionReference): FixedGithubExtension = withContext(Dispatchers.IO) {
        val prefix = "https://api.github.com/repos/${reference.owner}/${reference.repository}"
        val commit = json("$prefix/commits/${encode(reference.ref)}", 256 * 1024L).getString("sha")
        require(commit.matches(Regex("[a-f0-9]{40}"))) { "GitHub 未返回完整 commit" }
        val tree = json("$prefix/git/trees/$commit?recursive=1", 8 * 1024 * 1024L)
        check(!tree.optBoolean("truncated")) { "仓库目录过大，GitHub 未返回完整 tree；请选择较小的仓库" }
        val entries = tree.getJSONArray("tree")
        val chosen = ArrayList<GithubTreeFile>()
        val names = HashSet<String>()
        val licenses = setOf("license", "license.md", "license.txt", "copying", "copying.md", "copying.txt", "notice", "notice.md", "notice.txt")
        for (index in 0 until entries.length()) {
            val value = entries.getJSONObject(index)
            val path = value.getString("path")
            val selected = reference.path.isEmpty() || path == reference.path || path.startsWith(reference.path + "/") ||
                !path.contains('/') && path.lowercase(Locale.ROOT) in licenses
            if (!selected || value.optString("type") == "tree") continue
            ExtensionFiles.relative(path)
            require(value.getString("type") == "blob" && value.getString("mode") in setOf("100644", "100755")) {
                "扩展源包含符号链接、子模块或特殊条目：$path"
            }
            require(names.add(path.lowercase(Locale.ROOT))) { "扩展源有大小写冲突路径" }
            val sha = value.getString("sha")
            val size = value.getLong("size")
            require(sha.matches(Regex("[a-f0-9]{40}")) && size in 0..ExtensionFiles.MAX_PACKAGE_BYTES)
            chosen += GithubTreeFile(path, sha, size, value.getString("mode"))
            require(chosen.size <= ExtensionFiles.MAX_FILES && chosen.sumOf { it.bytes } <= ExtensionFiles.MAX_PACKAGE_BYTES) {
                "扩展源的文件数或展开大小超过上限"
            }
        }
        require(chosen.any { reference.path.isEmpty() || it.path == reference.path || it.path.startsWith(reference.path + "/") }) {
            "指定的 GitHub 路径不存在"
        }
        FixedGithubExtension(reference, commit, chosen.sortedBy { it.path })
    }

    suspend fun download(fixed: FixedGithubExtension, target: File, source: String,
        progress: (ArtifactDownloadProgress) -> Unit = {}): File = withContext(Dispatchers.IO) {
        ExtensionFiles.directory(target)
        var completed = 0L
        for (item in fixed.files) {
            currentCoroutineContext().ensureActive()
            val url = "https://raw.githubusercontent.com/${fixed.reference.owner}/${fixed.reference.repository}/${fixed.commit}/" +
                item.path.split('/').joinToString("/") { encode(it) }
            val destination = ExtensionFiles.resolve(target, item.path)
            downloads.downloadArtifact(ArtifactDownloadRequest(listOf(url), destination, ArtifactChecksum.gitBlob(item.sha),
                expectedSize = item.bytes, maxBytes = ExtensionFiles.MAX_PACKAGE_BYTES), source) {
                progress(it.copy(receivedBytes = completed + it.receivedBytes, totalBytes = fixed.bytes))
            }
            completed += item.bytes
        }
        if (fixed.reference.path.isEmpty()) target else ExtensionFiles.resolve(target, fixed.reference.path)
    }

    suspend fun search(query: String, skills: Boolean): List<GithubSearchEntry> = withContext(Dispatchers.IO) {
        require(query.length in 1..200)
        val suffix = if (skills) " topic:agent-skills" else " deepseek-harness"
        val values = json("https://api.github.com/search/repositories?q=${encode(query + suffix)}&per_page=20", 1024 * 1024L)
            .getJSONArray("items")
        List(values.length()) { index ->
            val value = values.getJSONObject(index)
            GithubSearchEntry(GithubExtensionReference(value.getJSONObject("owner").getString("login"), value.getString("name"),
                ref = value.getString("default_branch")), value.optString("description").takeIf { it != "null" }.orEmpty(),
                value.optJSONObject("license")?.optString("spdx_id").orEmpty())
        }
    }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    private suspend fun json(url: String, limit: Long): JSONObject {
        require(URI(url).host == "api.github.com")
        val call = client.newCall(Request.Builder().url(url).header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "Dafeiyu-Go-Mobile/0.1.25").build())
        val cancellation = currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion(onCancelling = true,
            invokeImmediately = true) { if (it != null) call.cancel() }
        try {
            return call.execute().use { response ->
                if (response.code == 403 || response.code == 429) throw IOException("GitHub 请求频率受限，请稍后重试")
                if (!response.isSuccessful) throw IOException("GitHub HTTP ${response.code}；请检查仓库、分支和网络")
                JSONObject(ExtensionFiles.utf8(requireNotNull(response.body).byteStream().use { ExtensionFiles.bytes(it, limit) }))
            }
        } finally { cancellation?.dispose() }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

data class GithubSearchEntry(val reference: GithubExtensionReference, val description: String, val license: String)
