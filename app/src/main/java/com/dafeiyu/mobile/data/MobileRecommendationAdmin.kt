package com.dafeiyu.mobile.data

import java.security.MessageDigest
import java.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal data class MobileRecommendationDocument(val skills: Boolean, val sha: String, val text: String)

/** Two fixed mobile files. The caller must retain the fetched blob SHA until its explicit save. */
internal class MobileRecommendationAdmin(client: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).build()) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    suspend fun read(skills: Boolean, token: String): MobileRecommendationDocument {
        val response = DeveloperAdminClient.exchange(client, request(skills, token).get().build().let {
            it.newBuilder().url(it.url.newBuilder().addQueryParameter("ref", "main").build()).build()
        }, 512 * 1024)
        val item = JSONObject(DeveloperAdminClient.decode(response))
        val sha = item.getString("sha").also { require(it.matches(Regex("[a-f0-9]{40}"))) }
        require(item.getString("type") == "file" && item.getString("encoding") == "base64")
        val size = item.get("size") as? Int ?: error("推荐清单大小无效")
        require(size in 1..256 * 1024)
        val bytes = Base64.getDecoder().decode(item.getString("content").replace("\n", "").replace("\r", ""))
        require(bytes.size == size && blobSha(bytes) == sha) { "推荐清单的内容摘要不匹配" }
        val text = DeveloperAdminClient.decode(bytes)
        RecommendationFeed.parse(JSONObject(text), skills)
        return MobileRecommendationDocument(skills, sha, text)
    }

    suspend fun save(before: MobileRecommendationDocument, text: String, token: String): String {
        val bytes = text.toByteArray()
        require(bytes.size in 1..256 * 1024 && before.sha.matches(Regex("[a-f0-9]{40}")))
        require(DeveloperAdminClient.decode(bytes) == text) { "推荐清单包含无效 Unicode 字符" }
        RecommendationFeed.parse(JSONObject(text), before.skills)
        val payload = JSONObject().put("message", "Update Android ${if (before.skills) "skill" else "DSH plugin"} recommendations")
            .put("sha", before.sha).put("branch", "main").put("content", Base64.getEncoder().encodeToString(bytes))
        val response = DeveloperAdminClient.exchange(client, request(before.skills, token)
            .put(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build(), 512 * 1024)
        val result = JSONObject(DeveloperAdminClient.decode(response))
        val sha = result.getJSONObject("content").getString("sha")
        require(sha == blobSha(bytes)) { "GitHub 未确认推荐清单的完整内容，请刷新后检查" }
        return sha
    }

    private fun request(skills: Boolean, token: String): Request.Builder {
        require(token.length in 1..2048 && token.none { it.isWhitespace() || it.isISOControl() }) { "GitHub 令牌无效" }
        val name = if (skills) "mobile-skills.json" else "mobile-dsh-plugins.json"
        return Request.Builder().url("https://api.github.com/repos/YunxiRamito/Dafeiyu-Go-DeepSeek-Harness-Mobile/contents/recommendations/$name")
            .header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
    }
    private fun blobSha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1")
        .digest("blob ${bytes.size}\u0000".toByteArray() + bytes).joinToString("") { "%02x".format(it) }
}
