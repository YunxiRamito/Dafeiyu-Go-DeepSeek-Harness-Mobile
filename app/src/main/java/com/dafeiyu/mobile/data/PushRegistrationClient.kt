package com.dafeiyu.mobile.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.net.URL
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.json.JSONArray
import org.json.JSONObject

class PushClientFailure(val code: String, val httpStatus: Int? = null) : IOException("推送注册请求失败（$code）")

/** Explicit requests only. No manufactured SDK token, automatic retry, poller or background keepalive. */
class PushRegistrationClient private constructor(private val signer: PushProofSigner, private val packageName: String) {
    constructor(context: Context) : this(PushIdentity(context), context.applicationContext.packageName)
    internal constructor(signer: PushProofSigner, exchange: suspend (String, String, ByteArray?) -> PushHttpResult) :
        this(signer, "com.dafeiyu.mobile") { fixtureExchange = exchange }
    private var fixtureExchange: (suspend (String, String, ByteArray?) -> PushHttpResult)? = null

    suspend fun capabilities(): PushCapabilities {
        val response = request("GET", "/api/android/push/capabilities", null)
        val json = success(response)
        require(json.int("schema") == 1 && json.string("protocol") == PushIdentity.PROTOCOL) { "推送协议不兼容" }
        val entries = json.get("channels") as? JSONArray ?: throw PushClientFailure("invalid-response")
        require(entries.length() <= PushChannelPolicy.channels.size)
        val channels = (0 until entries.length()).map { index -> (entries.get(index) as? String)
            ?.takeIf { it in PushChannelPolicy.channels } ?: throw PushClientFailure("invalid-response") }
        require(channels.distinct().size == channels.size)
        val categories = json.get("categories") as? JSONArray ?: throw PushClientFailure("invalid-response")
        val expected = setOf("task", "device", "announcement", "message", "backend")
        require(categories.length() == expected.size && (0 until categories.length()).map { categories.get(it) }.toSet() == expected)
        val configured = json.boolean("configured")
        require(configured == channels.isNotEmpty())
        return PushCapabilities(PushIdentity.PROTOCOL, channels.toSet(), configured)
    }

    suspend fun state(): PushRegistrationState = signed("state", JSONObject())
    suspend fun register(token: PushSdkToken, preferences: PushPreferences, expectedRevision: Long): PushRegistrationState {
        require(expectedRevision in 0 until Long.MAX_VALUE)
        if (packageName != "com.dafeiyu.mobile") throw PushClientFailure("package-not-registered")
        if (fixtureExchange == null && token.channel !in PushChannelPolicy.installedSdkChannels) throw PushClientFailure("sdk-unconfigured")
        val capabilities = capabilities()
        if (token.channel !in capabilities.channels) throw PushClientFailure("channel-unconfigured")
        return signed("register", JSONObject().put("expectedRevision", expectedRevision).put("channel", token.channel)
            .put("token", token.token).put("packageName", packageName).put("preferences", preferences.json())).also {
            require(it.status == "registered" && it.channel == token.channel && it.revision == expectedRevision + 1 && it.preferences == preferences) {
                "推送注册回执与提交不一致"
            }
        }
    }
    suspend fun updatePreferences(preferences: PushPreferences, expectedRevision: Long): PushRegistrationState {
        require(expectedRevision in 0 until Long.MAX_VALUE)
        return signed("preferences", JSONObject().put("expectedRevision", expectedRevision).put("preferences", preferences.json())).also {
            require(it.revision == expectedRevision + 1 && it.preferences == preferences) { "推送分类回执与提交不一致" }
        }
    }
    suspend fun unregister(expectedRevision: Long): PushRegistrationState {
        require(expectedRevision in 0 until Long.MAX_VALUE)
        return signed("unregister", JSONObject().put("expectedRevision", expectedRevision)).also {
            require(it.status == "unregistered" && it.channel == null && it.revision == expectedRevision + 1) { "推送注销回执与提交不一致" }
        }
    }
    private suspend fun signed(operation: String, payload: JSONObject): PushRegistrationState {
        val path = "/api/android/push/$operation"
        val clear = payload.toString().toByteArray(Charsets.UTF_8)
        val envelope = try { signer.sign(path, clear).toString().toByteArray(Charsets.UTF_8) } finally { clear.fill(0) }
        val response = try { request("POST", path, envelope) } finally { envelope.fill(0) }
        val value = success(response)
        val key = value.string("installationKey")
        require(key == signer.installationKey() && key.matches(Regex("[a-f0-9]{64}")))
        val status = value.string("status")
        require(status in setOf("registered", "unregistered", "token-rejected"))
        val channel = value.optionalString("channel")
        require(channel == null || channel in PushChannelPolicy.channels)
        val revision = value.long("revision")
        require(revision >= 0 && (status != "registered" || channel != null))
        val settings = value.get("preferences")
        val preferences = if (settings == JSONObject.NULL) null else (settings as? JSONObject)?.let {
            PushPreferences(it.boolean("enabled"), it.boolean("live"), it.boolean("devices"), it.boolean("announcements"),
                it.boolean("messages"), it.boolean("backend"))
        } ?: if (settings != JSONObject.NULL) throw PushClientFailure("invalid-response") else null
        return PushRegistrationState(key, status, channel, revision, preferences)
    }

    private suspend fun request(method: String, path: String, bytes: ByteArray?): PushHttpResult {
        fixtureExchange?.let { return it(method, path, bytes) }
        require(path == "/api/android/push/capabilities" || path in PushIdentity.routes)
        return suspendCancellableCoroutine { continuation ->
            val opened = AtomicReference<HttpsURLConnection?>()
            continuation.invokeOnCancellation { opened.getAndSet(null)?.disconnect() }
            Thread({
                var connection: HttpsURLConnection? = null
                try {
                    val socket = URL("https://202.189.21.218:8787$path").openConnection() as HttpsURLConnection
                    connection = socket; opened.set(socket)
                    if (!continuation.isActive) return@Thread
                    socket.sslSocketFactory = ssl.socketFactory
                    socket.instanceFollowRedirects = false
                    socket.requestMethod = method; socket.connectTimeout = 8_000; socket.readTimeout = 8_000
                    socket.setRequestProperty("Accept", "application/json")
                    socket.setRequestProperty("Cache-Control", "no-store")
                    if (bytes != null) {
                        socket.doOutput = true; socket.setRequestProperty("Content-Type", "application/json")
                        socket.setFixedLengthStreamingMode(bytes.size); socket.outputStream.use { it.write(bytes) }
                    }
                    val status = socket.responseCode
                    if (status in 300..399) throw PushClientFailure("redirect-rejected", status)
                    val contentType = socket.contentType.orEmpty()
                    require(contentType.startsWith("application/json", true) && socket.contentLengthLong <= 32 * 1024)
                    val input = if (status in 200..299) socket.inputStream else socket.errorStream
                    val body = ByteArrayOutputStream()
                    input?.use { stream -> val buffer = ByteArray(4096)
                        while (true) { val count = stream.read(buffer); if (count < 0) break
                            if (body.size() + count > 32 * 1024) throw PushClientFailure("response-too-large")
                            body.write(buffer, 0, count) }
                    }
                    val result = PushHttpResult(status, body.toByteArray())
                    if (continuation.isActive) continuation.resume(result)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(if (failure is PushClientFailure) failure else PushClientFailure("network"))
                } finally { opened.set(null); connection?.disconnect() }
            }, "dafeiyu-push-registration").apply { isDaemon = true }.start()
        }
    }

    private fun success(response: PushHttpResult): JSONObject {
        val body = try { PushStrictJson(response.bytes).read() } catch (_: Exception) { throw PushClientFailure("invalid-response", response.status) }
        if (response.status !in 200..299) {
            val code = body.opt("error") as? String
            throw PushClientFailure(code?.takeIf { it.matches(Regex("[a-z-]{1,80}")) } ?: "http-error", response.status)
        }
        return body
    }
    private val ssl by lazy {
        val pinned = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { throw java.security.cert.CertificateException() }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw java.security.cert.CertificateException()
                leaf.checkValidity()
                val pin = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)
                require(pin.joinToString("") { "%02x".format(it.toInt() and 255) } == PIN) { "推送服务证书不匹配" }
            }
        }
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(pinned), null) }
    }
    private fun JSONObject.string(name: String): String = get(name) as? String ?: throw PushClientFailure("invalid-response")
    private fun JSONObject.optionalString(name: String): String? = if (get(name) == JSONObject.NULL) null else string(name)
    private fun JSONObject.boolean(name: String): Boolean = get(name) as? Boolean ?: throw PushClientFailure("invalid-response")
    private fun JSONObject.long(name: String): Long = (get(name) as? Long) ?: throw PushClientFailure("invalid-response")
    private fun JSONObject.int(name: String): Int = long(name).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
    companion object { private const val PIN = "2c72728755267c607124b2ac86e431743c34af5c6069aa22bbcc401e0f626f0b" }
}

internal data class PushHttpResult(val status: Int, val bytes: ByteArray)

/** Bounded strict parsing rejects duplicate names, coercion, unknown depth and trailing data. */
private class PushStrictJson(bytes: ByteArray) {
    init { require(bytes.size <= 32 * 1024) }
    private val text = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    private val reader = JsonReader(StringReader(text)).apply { isLenient = false }
    private var nodes = 0
    fun read(): JSONObject = reader.use { val result = value(0) as? JSONObject ?: error("object required")
        require(reader.peek() == JsonToken.END_DOCUMENT); result }
    private fun value(depth: Int): Any {
        require(depth <= 12 && ++nodes <= 512)
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JSONObject().also { result -> reader.beginObject(); val names = mutableSetOf<String>()
                while (reader.hasNext()) { val name = reader.nextName(); require(names.add(name) && name.length <= 128); result.put(name, value(depth + 1)) }
                reader.endObject() }
            JsonToken.BEGIN_ARRAY -> JSONArray().also { result -> reader.beginArray()
                while (reader.hasNext()) result.put(value(depth + 1)); reader.endArray() }
            JsonToken.STRING -> reader.nextString().also { require(it.length <= 8192) }
            JsonToken.NUMBER -> reader.nextString().also { require(it.matches(Regex("-?(0|[1-9][0-9]*)"))) }.toLong()
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
            else -> error("invalid token")
        }
    }
}
