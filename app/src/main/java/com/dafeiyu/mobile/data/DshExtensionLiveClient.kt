package com.dafeiyu.mobile.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Proxy
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/** Only two fixed first-party plugin methods, authenticated to this app's running loopback DSH. */
internal class DshExtensionLiveClient {
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()

    suspend fun call(accessUrl: String, endpoint: String, args: JSONObject): Any {
        require(endpoint in setOf("pluginManager/listPlugins", "pluginManager/setPluginEnabled"))
        val uri = URI(accessUrl)
        require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port in 1024..65535 && uri.rawPath == "/" &&
            uri.userInfo == null && uri.fragment == null && uri.rawQuery?.matches(Regex("token=[A-Za-z0-9_-]{43}")) == true) {
            "本机 DSH 未提供有效的授权地址"
        }
        val cookie = request(Request.Builder().url(accessUrl).build()) { response ->
            check(response.code == 303 && response.header("Location") == "./" && response.header("Cache-Control") == "no-store") {
                "本机 DSH 授权未建立"
            }
            val name = "dsh-auth-" + Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest("127.0.0.1:${uri.port}".toByteArray()))
            val value = response.header("Set-Cookie")?.substringBefore(';') ?: error("本机 DSH 缺少会话凭据")
            check(value.matches(Regex("${Regex.escape(name)}=v1\\.[A-Za-z0-9_-]{1,2048}\\.[A-Za-z0-9_-]{43}"))) {
                "本机 DSH 会话凭据格式不匹配"
            }
            value
        }
        val id = UUID.randomUUID().toString()
        val body = JSONObject().put("type", "client-request").put("rpcId", id).put("method", endpoint)
            .put("payload", JSONObject().put("args", args)).toString()
        require(body.toByteArray().size <= 16 * 1024)
        return request(Request.Builder().url("http://127.0.0.1:${uri.port}/api/$endpoint").header("Cookie", cookie)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()) { response ->
            check(response.code == 200 && response.header("Content-Type")?.startsWith("application/json") == true) { "DSH 插件管理请求未完成" }
            val bytes = ByteArrayOutputStream()
            requireNotNull(response.body).byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    require(bytes.size() + count <= 2 * 1024 * 1024) { "DSH 插件管理响应超过上限" }
                    bytes.write(buffer, 0, count)
                }
            }
            val envelope = JSONObject(ExtensionFiles.utf8(bytes.toByteArray()))
            check(envelope.getString("type") == "server-response" && envelope.getString("rpcId") == id) { "插件管理响应与请求不匹配" }
            val result = envelope.getJSONObject("result")
            check(result.getBoolean("ok")) { "DSH 未接受此插件操作，请刷新运行状态" }
            result.get("value")
        }
    }

    private suspend fun <T> request(request: Request, decode: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("本机 DSH 插件连接失败或已取消"))
            }
            override fun onResponse(call: Call, response: Response) {
                try { response.use { val value = decode(it); if (continuation.isActive) continuation.resume(value) } }
                catch (failure: Throwable) { if (continuation.isActive) continuation.resumeWithException(failure) }
            }
        })
    }
}
