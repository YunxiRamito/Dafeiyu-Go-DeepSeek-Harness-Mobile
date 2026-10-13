package com.dafeiyu.mobile.data

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

internal data class DeveloperAdminPage(val items: List<JSONObject>, val total: Int, val nextOffset: Int?)

/** Opening the page grants no authority. Every operation uses a transient server-issued token. */
internal class DeveloperAdminClient private constructor(client: OkHttpClient) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    constructor(context: Context) : this(ComponentDownloadClient(context).pinnedBackendClient())
    internal constructor(client: OkHttpClient, fixture: Boolean) : this(client) { require(fixture) }

    suspend fun messages(token: String, offset: Int): DeveloperAdminPage = page(
        json(token, "GET", "$MESSAGES?offset=${offset(offset)}&limit=10"), "messages", "total", offset)

    suspend fun saveMessage(token: String, id: String?, draft: JSONObject): JSONObject {
        validateMessage(draft)
        return json(token, if (id == null) "POST" else "PUT", MESSAGES + (id?.let { "/${uuid(it)}" } ?: ""), draft)
            .also { require(it.getString("state").equals("draft", true)); uuid(it.getString("id")); require(id == null || it.getString("id") == id) }
    }
    suspend fun publishMessage(token: String, id: String) = json(token, "POST", "$MESSAGES/${uuid(id)}/publish")
        .also { require(it.getString("id") == id && it.getString("state").equals("published", true)) }
    suspend fun withdrawMessage(token: String, id: String) = json(token, "POST", "$MESSAGES/${uuid(id)}/withdraw")
        .also { require(it.getString("id") == id && it.getString("state").equals("withdrawn", true)) }
    suspend fun removeDraft(token: String, id: String) { request(token, "DELETE", "$MESSAGES/${uuid(id)}") }
    suspend fun messageMetrics(token: String, id: String): JSONObject = json(token, "GET", "$MESSAGES/${uuid(id)}/metrics")

    suspend fun feedback(token: String, offset: Int): DeveloperAdminPage = page(
        json(token, "GET", "$FEEDBACK?offset=${offset(offset)}&limit=10"), "feedback", "totalCount", offset)
    suspend fun feedbackSupplements(token: String, id: String, offset: Int): JSONObject {
        validateToken(token)
        return publicJson("/api/android/feedback/${uuid(id)}/supplements?offset=${offset(offset)}")
            .also { value -> require(value.getJSONArray("supplements").length() <= 50); validateNext(value, offset, value.getJSONArray("supplements").length()) }
    }
    suspend fun updateFeedback(token: String, id: String, status: String, category: String): JSONObject {
        require(status in STATUSES && category in CATEGORIES)
        return json(token, "PATCH", "$FEEDBACK/${uuid(id)}", JSONObject().put("status", status).put("category", category))
            .also { require(it.getString("id") == id && it.getString("status") == status && it.getString("category") == category) }
    }
    suspend fun reply(token: String, id: String, body: String) = json(token, "POST", "$FEEDBACK/${uuid(id)}/replies",
        JSONObject().put("body", text(body, 12000))).also { require(it.getString("id") == id) }
    suspend fun removeFeedback(token: String, id: String) { request(token, "DELETE", "$FEEDBACK/${uuid(id)}") }
    suspend fun ban(token: String, id: String, reason: String, hours: Int?, remove: Boolean): JSONObject {
        require(hours == null || hours in 1..876000)
        require(reason.none(Char::isISOControl))
        return json(token, "POST", "$FEEDBACK/${uuid(id)}/ban", JSONObject().put("reason", text(reason, 500))
            .put("durationHours", hours ?: JSONObject.NULL).put("deleteFeedback", remove))
    }
    suspend fun bans(token: String, offset: Int): DeveloperAdminPage = page(
        json(token, "GET", "$FEEDBACK/bans?offset=${offset(offset)}&limit=10"), "bans", "totalCount", offset)
    suspend fun unban(token: String, id: String) { request(token, "DELETE", "$FEEDBACK/bans/${uuid(id)}") }
    suspend fun feedbackLogs(token: String, id: String, offset: Int): JSONObject =
        json(token, "GET", "$FEEDBACK/${uuid(id)}/logs?offset=${offset(offset)}").also { value ->
            require(value.getJSONArray("logs").length() <= 20); validateNext(value, offset, value.getJSONArray("logs").length())
        }
    suspend fun readLog(token: String, feedbackId: String, id: String): ByteArray =
        request(token, "GET", "$FEEDBACK/${uuid(feedbackId)}/logs/${uuid(id)}", maximum = 1024 * 1024)

    suspend fun acknowledgements(token: String): List<JSONObject> = json(token, "GET", ACKNOWLEDGEMENTS)
        .getJSONArray("acknowledgements").let { rows -> require(rows.length() <= 10000); List(rows.length()) { rows.getJSONObject(it) } }
    suspend fun saveAcknowledgement(token: String, id: String?, value: JSONObject): JSONObject {
        text(value.getString("name"), 120).also { require(it.none(Char::isISOControl)) }
        require(value.getString("category") in setOf("Person", "Organization"))
        val link = value.optString("link")
        if (link.isNotBlank()) validateUrl(link)
        listOf("role" to 120, "contribution" to 1000).forEach { (field, maximum) ->
            if (!value.isNull(field)) require(value.getString(field).let { it.length <= maximum && it.none(Char::isISOControl) })
        }
        if (!value.isNull("sortOrder")) require((value.get("sortOrder") as? Int)?.let { it in 0..1_000_000 } == true)
        return json(token, if (id == null) "POST" else "PUT", ACKNOWLEDGEMENTS + (id?.let { "/${uuid(it)}" } ?: ""), value)
            .also { uuid(it.getString("id")); require(id == null || it.getString("id") == id) }
    }
    suspend fun removeAcknowledgement(token: String, id: String) { request(token, "DELETE", "$ACKNOWLEDGEMENTS/${uuid(id)}") }
    suspend fun reorderAcknowledgements(token: String, ids: List<String>): JSONObject {
        require(ids.size <= 10000 && ids.distinct().size == ids.size); ids.forEach(::uuid)
        return json(token, "PUT", "$ACKNOWLEDGEMENTS/order", JSONObject().put("ids", JSONArray(ids)))
    }
    suspend fun removeAvatar(token: String, id: String) { request(token, "DELETE", "$ACKNOWLEDGEMENTS/${uuid(id)}/avatar") }
    suspend fun uploadAvatar(token: String, id: String, image: DeveloperCenterClient.FeedbackUploadImage) {
        val multipart = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("file", image.fileName, image.requestBody()).build()
        request(token, "PUT", "$ACKNOWLEDGEMENTS/${uuid(id)}/avatar", multipart)
    }

    private suspend fun json(token: String, method: String, path: String, value: JSONObject? = null): JSONObject {
        val body = value?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
        val bytes = request(token, method, path, body, maximum = if (path.startsWith(FEEDBACK)) 16 * 1024 * 1024 else 1024 * 1024)
        return try { JSONObject(decode(bytes)) } catch (_: Exception) { throw IOException("管理接口返回的内容无效") }
    }
    private suspend fun publicJson(path: String): JSONObject {
        val request = Request.Builder().url(ComponentDownloadSources.BACKEND + path).header("Accept", "application/json").build()
        val bytes = exchange(client, request, 16 * 1024 * 1024)
        return try { JSONObject(decode(bytes)) } catch (_: Exception) { throw IOException("管理接口返回的内容无效") }
    }
    private suspend fun request(token: String, method: String, path: String, body: RequestBody? = null,
        maximum: Int = 1024 * 1024): ByteArray {
        validateToken(token)
        require(path.startsWith("$MESSAGES") || path.startsWith("$FEEDBACK") || path.startsWith(ACKNOWLEDGEMENTS))
        require(!path.contains("..") && !path.contains('#') && !path.contains('\\'))
        val request = Request.Builder().url(ComponentDownloadSources.BACKEND + path)
            .header("Authorization", "Bearer $token").header("Accept", "application/json")
            .method(method, body ?: if (method in setOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody() else null).build()
        return exchange(client, request, maximum)
    }

    companion object {
        private const val MESSAGES = "/api/admin/android/messages"
        private const val FEEDBACK = "/api/admin/android/feedback"
        private const val ACKNOWLEDGEMENTS = "/api/admin/acknowledgements"
        val STATUSES = listOf("Submitted", "Processing", "Completed", "Deferred")
        val CATEGORIES = listOf("Suggestion", "Bug")
        private fun uuid(value: String): String = UUID.fromString(value).toString().also { require(it == value && UUID.fromString(it) != UUID(0, 0)) }
        private fun offset(value: Int): Int = value.also { require(it in 0..1_000_000) }
        private fun validateToken(value: String) { require(value.length in 1..2048 && value.none { it.isWhitespace() || it.isISOControl() }) { "管理令牌无效" } }
        private fun text(value: String, maximum: Int): String = value.trim().also { require(it.isNotEmpty() && it.length <= maximum && !it.contains('\u0000')) }
        private fun page(json: JSONObject, field: String, totalField: String, offset: Int): DeveloperAdminPage {
            val rows = json.getJSONArray(field)
            val total = json.get(totalField) as? Int ?: throw IOException("管理分页总数无效")
            require(rows.length() <= 10 && total >= rows.length() && total >= 0)
            val next = if (json.isNull("nextOffset")) null else (json.get("nextOffset") as? Int ?: throw IOException("管理分页无效"))
            require(next == null || next == offset + rows.length() && next > offset && next < total)
            return DeveloperAdminPage(List(rows.length()) { rows.getJSONObject(it).also { row -> uuid(row.getString("id")) } }, total, next)
        }
        private fun validateNext(value: JSONObject, offset: Int, size: Int) {
            if (!value.isNull("nextOffset")) require((value.get("nextOffset") as? Int)?.let { it == offset + size && it > offset } == true)
        }
        private fun validateUrl(value: String) {
            require(value.length in 1..2048 && value.none(Char::isISOControl) && java.net.URI(value).let {
                it.scheme == "https" && it.userInfo == null && !it.host.isNullOrBlank()
            }) { "请输入不含凭据的 HTTPS 链接" }
        }
        fun validateMessage(value: JSONObject) {
            require(value.getString("kind") in setOf("Announcement", "Notification"))
            require(text(value.getString("title"), 200).none(Char::isISOControl))
            text(value.getString("markdown"), 32000)
            if (!value.isNull("date")) {
                val date = value.getString("date")
                require(date.length == 10 && LocalDate.parse(date).toString() == date)
            }
            if (!value.isNull("expiresAt")) require(OffsetDateTime.parse(value.getString("expiresAt")).toInstant().isAfter(Instant.now()))
            require(value.isNull("buttons") || value.get("buttons") is JSONArray)
            value.optJSONArray("buttons")?.let { buttons ->
                require(buttons.length() <= 2 && (value.getString("kind") != "Announcement" || buttons.length() == 0))
                for (index in 0 until buttons.length()) {
                    val button = buttons.getJSONObject(index)
                    require(text(button.getString("label"), 80).none(Char::isISOControl))
                    require(button.getString("action") in setOf("Url", "Settings", "Dismiss") && button.isNull("command"))
                    when (button.getString("action")) {
                        "Url" -> { validateUrl(button.getString("url")); require(button.isNull("page")) }
                        "Settings" -> { require(button.getString("page") in setOf("Home", "Engine", "Online", "General", "Models", "Environment", "Data", "Support", "About", "Updates")); require(button.isNull("url") || button.getString("url").isEmpty()) }
                        "Dismiss" -> require((button.isNull("url") || button.getString("url").isEmpty()) && button.isNull("page"))
                    }
                }
            }
        }
        internal fun decode(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        internal suspend fun exchange(client: OkHttpClient, request: Request, maximum: Int): ByteArray = suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("管理服务连接失败，请重试"))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val bytes = response.use {
                            if (!it.isSuccessful) throw IOException(when (it.code) {
                                401, 403 -> "管理身份验证失败，请检查令牌权限"
                                409 -> "内容已变化，请刷新后重试"
                                else -> "管理接口返回 HTTP ${it.code}，请刷新确认结果"
                            })
                            val body = it.body ?: return@use ByteArray(0)
                            if (body.contentLength() > maximum) throw IOException("管理接口返回的内容超出大小限制")
                            body.byteStream().use { stream ->
                                val out = ByteArrayOutputStream(); val chunk = ByteArray(8192)
                                while (true) {
                                    val count = stream.read(chunk)
                                    if (count < 0) break
                                    if (out.size() + count > maximum) throw IOException("管理接口返回的内容超出大小限制")
                                    out.write(chunk, 0, count)
                                }
                                out.toByteArray()
                            }
                        }
                        if (continuation.isActive) continuation.resume(bytes)
                    } catch (failure: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                }
            })
        }
    }
}
