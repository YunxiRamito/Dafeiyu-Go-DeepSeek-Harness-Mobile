package com.dafeiyu.mobile.data

import android.content.Context
import android.os.Build
import com.dafeiyu.mobile.R
import android.util.Base64
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Aries public API v1 client.
 *
 * The client follows the PC/server protocol: HTTPS only, private CA trust, local
 * Ed25519 enrollment, signed requests, one-use request nonces, and idempotency
 * keys for durable mutations. It does not use the Aries admin API.
 */
private const val ARIES_DEFAULT_BASE_URL = "https://202.189.21.218:10240"

private fun org.json.JSONObject.optionalString(key: String): String? =
    optString(key).trim().takeIf { it.isNotEmpty() }

private fun org.json.JSONObject.optionalNonNegativeInt(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key, -1).takeIf { it >= 0 }

private fun org.json.JSONObject.requiredString(key: String): String =
    optionalString(key) ?: throw IllegalStateException("Aries response field '$key' is missing")

private fun org.json.JSONObject.requiredProtocolString(key: String): String {
    val value = opt(key)
    require(value is String && value.isNotBlank() && value.none(Char::isISOControl)) {
        "Aries response field '$key' is missing or invalid"
    }
    return value
}

class AriesClient(private val context: Context) {
    data class Config(
        val baseUrl: String = ARIES_DEFAULT_BASE_URL,
        val connectTimeoutMs: Int = 5_000,
        val readTimeoutMs: Int = 10_000,
    )

    data class Health(
        val apiVersion: String?,
        val buildVersion: String?,
        val status: String?,
        val startedAt: String?,
    )

    data class Node(
        val apiVersion: String?,
        val nodeId: String?,
        val serverCode: String?,
        val capabilities: Set<String> = emptySet(),
    )

    data class PairingPayload(
        val endpoint: String,
        val code: String?,
        val group: String?,
        val nodeId: String? = null,
    )

    data class Challenge(
        val challengeId: String,
        val nonce: String,
        val expiresAt: String?,
        val publicKey: String,
    )

    data class Enrollment(
        val deviceId: String,
        val status: String,
    )

    data class Heartbeat(
        val deviceId: String,
        val online: Boolean,
        val lastSeenAt: String?,
        val expiresAt: String?,
        val clientVersion: String?,
        val deviceName: String? = null,
    )

    data class InviteInfo(
        val groupId: String,
        val groupName: String?,
        val authorityNode: String?,
        val memberCount: Int?,
        val approvalPolicy: String?,
        val expiresAt: String?,
    )

    data class JoinApplication(
        val applicationId: String,
        val groupId: String,
        val status: String,
        val requiredApprovals: Int?,
    )

    enum class ConnectionState { PREVIEW, OFFLINE, HEALTHY, UNREGISTERED, REGISTERED }

    data class ConnectionSnapshot(
        val endpoint: String,
        val state: ConnectionState,
        val health: Health? = null,
        val node: Node? = null,
        val deviceId: String? = null,
        val checkedAtEpochMillis: Long = System.currentTimeMillis(),
        val errorMessage: String? = null,
    )

    class AriesException(
        val httpStatus: Int? = null,
        val errorCode: String? = null,
        override val message: String,
        cause: Throwable? = null,
        val requestId: String? = null,
    ) : IOException(message, cause)

    private val identityStore = AriesIdentityStore(context)

    suspend fun healthz(config: Config = Config()): Result<Health> = withContext(Dispatchers.IO) {
        request(config, "GET", "/healthz") { json ->
            Health(
                apiVersion = json.optionalString("api_version"),
                buildVersion = json.optionalString("build_version"),
                status = json.optionalString("status"),
                startedAt = json.optionalString("started_at"),
            )
        }
    }

    suspend fun node(config: Config = Config()): Result<Node> = withContext(Dispatchers.IO) {
        request(config, "GET", "/api/v1/public/node", parse = ::parseNode)
    }

    /** Runs the non-mutating service probe, then verifies a registered device if present. */
    suspend fun inspect(config: Config = Config(), clientVersion: String? = null): ConnectionSnapshot =
        withContext(Dispatchers.IO) {
            val endpoint = SettingsContract.normalizeAriesEndpoint(config.baseUrl)
                ?: return@withContext ConnectionSnapshot(config.baseUrl, ConnectionState.PREVIEW,
                    errorMessage = "Aries 地址必须是 HTTPS 主机地址")
            val health = runCatching { request(config, "GET", "/healthz") { json ->
                Health(json.optionalString("api_version"), json.optionalString("build_version"),
                    json.optionalString("status"), json.optionalString("started_at"))
            }.getOrThrow() }.getOrElse { error ->
                if (error is CancellationException) throw error
                currentCoroutineContext().ensureActive()
                return@withContext ConnectionSnapshot(endpoint, ConnectionState.OFFLINE,
                    errorMessage = error.message)
            }
            val node = runCatching {
                request(config, "GET", "/api/v1/public/node", parse = ::parseNode).getOrThrow()
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                currentCoroutineContext().ensureActive()
                return@withContext ConnectionSnapshot(endpoint, ConnectionState.OFFLINE, health = health,
                    errorMessage = error.message)
            }
            val deviceId = identityStore.identity().deviceId
            if (deviceId == null) {
                return@withContext ConnectionSnapshot(endpoint, ConnectionState.UNREGISTERED,
                    health = health, node = node)
            }
            val heartbeat = runCatching { heartbeatBlocking(config, clientVersion, node) }.getOrElse { error ->
                if (error is CancellationException) throw error
                currentCoroutineContext().ensureActive()
                return@withContext ConnectionSnapshot(endpoint, ConnectionState.OFFLINE,
                    health = health, node = node, deviceId = deviceId, errorMessage = error.message)
            }
            ConnectionSnapshot(endpoint, ConnectionState.REGISTERED,
                health = health, node = node, deviceId = heartbeat.deviceId)
        }

    suspend fun requestChallenge(
        config: Config = Config(),
        deviceId: String? = identityStore.identity().deviceId,
    ): Result<Challenge> = withContext(Dispatchers.IO) {
        val identity = identityStore.identity()
        val body = JSONObject().apply {
            if (deviceId.isNullOrBlank()) put("public_key", identity.publicKeyBase64Url)
            else put("device_id", deviceId)
        }.toString().toByteArray(StandardCharsets.UTF_8)
        request(config, "POST", "/api/v1/public/devices/challenge", body) { json ->
            val challenge = Challenge(
                challengeId = json.requiredProtocolString("challenge_id"),
                nonce = json.requiredProtocolString("nonce"),
                expiresAt = json.requiredProtocolString("expires_at"),
                publicKey = identity.publicKeyBase64Url,
            )
            validateChallenge(challenge)
            challenge
        }
    }

    /** Enrolls the local public key and stores only the returned device id locally. */
    suspend fun enroll(
        challenge: Challenge,
        config: Config = Config(),
        idempotencyKey: String = newIdempotencyKey(),
    ): Result<Enrollment> = withContext(Dispatchers.IO) {
        validateChallenge(challenge)
        val identity = identityStore.identity()
        require(challenge.publicKey == identity.publicKeyBase64Url) {
            "Aries challenge belongs to a different local identity"
        }
        val canonical = "enroll\n${challenge.challengeId}\n${challenge.nonce}\n${challenge.publicKey}"
        val signature = encodeBase64Url(identityStore.sign(canonical.toByteArray(StandardCharsets.UTF_8)))
        val body = JSONObject().put("challenge_id", challenge.challengeId)
            .put("signature", signature).toString().toByteArray(StandardCharsets.UTF_8)
        request(config, "POST", "/api/v1/public/devices/enroll", body,
            idempotencyKey = requireIdempotencyKey(idempotencyKey)) { json ->
            val enrollment = Enrollment(
                deviceId = requireDeviceId(json.requiredProtocolString("device_id")),
                status = requireEnrollmentStatus(json.requiredProtocolString("status")),
            )
            require(enrollment.deviceId == deriveDeviceId(identity.publicKeyBase64Url)) {
                "Aries enrollment returned a device id for a different identity"
            }
            identityStore.setDeviceId(enrollment.deviceId)
            enrollment
        }
    }

    suspend fun heartbeat(
        config: Config = Config(),
        clientVersion: String? = null,
        expectedNodeId: String? = null,
    ): Result<Heartbeat> = withContext(Dispatchers.IO) {
        runCatching {
            if (identityStore.identity().deviceId == null) throw AriesException(message = "Aries 设备尚未注册")
            val currentNode = node(config).getOrThrow()
            require(expectedNodeId == null || expectedNodeId == currentNode.nodeId) { "Aries node_id changed" }
            heartbeatBlocking(config, clientVersion, currentNode)
        }.onFailure {
            if (it is CancellationException) throw it
            currentCoroutineContext().ensureActive()
        }
    }

    suspend fun lookupInvite(code: String, config: Config = Config()): Result<InviteInfo> =
        withContext(Dispatchers.IO) {
            val inviteCode = requireInviteCode(code)
            request(config, "GET", "/api/v1/public/invites/${encodePathSegment(inviteCode)}") { json ->
                InviteInfo(
                    groupId = json.requiredString("group_id"),
                    groupName = json.optionalString("group_name"),
                    authorityNode = json.optionalString("authority_node"),
                    memberCount = json.optionalNonNegativeInt("member_count"),
                    approvalPolicy = json.optionalString("approval_policy"),
                    expiresAt = json.optionalString("expires_at"),
                )
            }
        }

    /** Starts a real group application; approval and key delivery remain server/member actions. */
    suspend fun applyInvite(
        code: String,
        config: Config = Config(),
        idempotencyKey: String = newIdempotencyKey(),
    ): Result<JoinApplication> = withContext(Dispatchers.IO) {
        val inviteCode = requireInviteCode(code)
        val deviceId = identityStore.identity().deviceId
            ?: return@withContext Result.failure(AriesException(message = "Aries 设备尚未注册"))
        val body = "{}".toByteArray(StandardCharsets.UTF_8)
        request(config, "POST", "/api/v1/public/invites/${encodePathSegment(inviteCode)}/applications", body,
            signed = true, idempotencyKey = requireIdempotencyKey(idempotencyKey)) { json ->
            JoinApplication(
                applicationId = json.requiredString("application_id"),
                groupId = json.requiredString("group_id"),
                status = json.requiredString("status"),
                requiredApprovals = json.optionalNonNegativeInt("required_approvals"),
            ).also {
                check(identityStore.identity().deviceId == deviceId) { "Aries identity changed during request" }
            }
        }
    }

    fun localIdentity(): AriesIdentityStore.Identity = identityStore.identity()

    fun parsePairingPayload(raw: String): PairingPayload {
        val parsed = AriesProtocolPolicy.parsePairingInput(raw)
        return PairingPayload(parsed.endpoint, parsed.code, parsed.group, parsed.nodeId)
    }

    private fun parseNode(json: JSONObject): Node {
        val array = json.optJSONArray("capabilities")
        val capabilities = buildSet<String> {
            if (array != null) for (index in 0 until array.length()) {
                (array.opt(index) as? String)?.takeIf(String::isNotEmpty)?.let(::add)
            }
        }
        return Node(json.optionalString("api_version"), json.optionalString("node_id"),
            json.optionalString("server_code"), capabilities)
    }

    private fun heartbeatBody(clientVersion: String?, capabilities: Set<String>): ByteArray {
        val version = clientVersion?.trim()?.takeIf { it.isNotEmpty() }
        require(version == null || version.length <= 64 && version.none(Char::isISOControl)) {
            "client_version is invalid"
        }
        val deviceName = if (AriesDeviceName.Capability in capabilities)
            AriesDeviceName.fromBuild(Build.MANUFACTURER, Build.MODEL) else null
        return JSONObject().apply {
            version?.let { put("client_version", it) }
            deviceName?.let { put("device_name", it) }
        }.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun parseHeartbeat(json: JSONObject): Heartbeat {
        val deviceName = if (!json.has("device_name") || json.isNull("device_name")) null else {
            val value = json.opt("device_name")
            require(value is String) { "Aries response device_name must be a string" }
            AriesDeviceName.normalize(value)
        }
        return Heartbeat(
            deviceId = json.requiredString("device_id"),
            online = json.optBoolean("online", false),
            lastSeenAt = json.optionalString("last_seen_at"),
            expiresAt = json.optionalString("expires_at"),
            clientVersion = json.optionalString("client_version"),
            deviceName = deviceName,
        )
    }

    private suspend fun heartbeatBlocking(config: Config, clientVersion: String?, currentNode: Node): Heartbeat {
        val identity = identityStore.identity()
        val deviceId = identity.deviceId ?: throw AriesException(message = "Aries 设备尚未注册")
        val body = heartbeatBody(clientVersion, currentNode.capabilities)
        return request(config, "POST", "/api/v1/public/devices/heartbeat", body, signed = true) { json ->
            val heartbeat = parseHeartbeat(json)
            require(heartbeat.deviceId == deviceId) {
                "Aries heartbeat returned a different device id"
            }
            require(heartbeat.online) {
                "Aries heartbeat did not confirm online=true"
            }
            heartbeat
        }.getOrThrow()
    }

    /**
     * Executes an authenticated, read-only JSON GET for protocol facades in this
     * package. The request goes through the same identity, signature canonicalization,
     * TLS trust, request-id handling, and error parsing as the existing client API.
     */
    internal suspend fun <T> signedGet(
        config: Config = Config(),
        path: String,
        parse: (JSONObject) -> T,
    ): Result<T> = request(config, "GET", path, signed = true, parse = parse)

    internal suspend fun signedMutation(config: Config, method: String, path: String,
        body: JSONObject, key: String): JSONObject = withContext(Dispatchers.IO) {
        request(config, method, path, body.toString().toByteArray(Charsets.UTF_8),
            signed = true, idempotencyKey = key) { it }.getOrThrow()
    }

    internal suspend fun signedRaw(config: Config, method: String, path: String,
        body: ByteArray = ByteArray(0), key: String? = null): ByteArray = withContext(Dispatchers.IO) {
        require(method in listOf("GET", "PUT") && path.startsWith("/api/v1/public/groups/") &&
            !path.contains("..") && body.size <= 4194304)
        val base = requireNotNull(SettingsContract.normalizeAriesEndpoint(config.baseUrl))
        val uri = URI(base + path)
        val identity = identityStore.identity()
        requireNotNull(identity.deviceId)
        val idempotency = AriesProtocolPolicy.idempotencyHeader(method, path, true, key)
        require(method == "GET" || idempotency != null)
        val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val nonce = encodeBase64Url(ByteArray(18).also(java.security.SecureRandom()::nextBytes))
        val canonical = listOf(method, uri.rawPath, timestamp, nonce, idempotency.orEmpty(),
            encodeBase64Url(MessageDigest.getInstance("SHA-256").digest(body))).joinToString("\n")
        val connection = (uri.toURL().openConnection() as HttpsURLConnection).apply {
            requestMethod = method; instanceFollowRedirects = false
            connectTimeout = config.connectTimeoutMs; readTimeout = config.readTimeoutMs
            sslSocketFactory = ariesSslSocketFactory(); hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
            setRequestProperty("X-Device-ID", identity.deviceId)
            setRequestProperty("X-Device-Timestamp", timestamp); setRequestProperty("X-Device-Nonce", nonce)
            setRequestProperty("X-Device-Signature", encodeBase64Url(identityStore.sign(canonical.toByteArray())))
            idempotency?.let { setRequestProperty("Idempotency-Key", it) }
            if (method == "PUT") { doOutput = true; setFixedLengthStreamingMode(body.size); setRequestProperty("Content-Type", "application/octet-stream") }
        }
        try {
            currentCoroutineContext().ensureActive()
            if (method == "PUT") connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val text = connection.errorStream?.use { AriesProtocolPolicy.readResponse(it, MAX_RESPONSE_BYTES) }.orEmpty()
                throw parseHttpError(status, text, connection.getHeaderField("X-Request-ID"))
            }
            val output = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    require(output.size() + n <= 4194304) { "chunk too large" }; output.write(buffer, 0, n)
                }
            }
            output.toByteArray().also { bytes ->
                if (method == "GET") require(connection.getHeaderField("X-Chunk-SHA256") == AriesCrypto.hash(bytes)) { "chunk hash mismatch" }
            }
        } finally { connection.disconnect() }
    }
    private suspend fun <T> request(
        config: Config,
        method: String,
        path: String,
        body: ByteArray = ByteArray(0),
        signed: Boolean = false,
        idempotencyKey: String? = null,
        parse: (JSONObject) -> T,
    ): Result<T> = runCatching {
        val coroutineContext = currentCoroutineContext()
        coroutineContext.ensureActive()
        val base = SettingsContract.normalizeAriesEndpoint(config.baseUrl)
            ?: throw AriesException(message = "Aries 地址必须是 HTTPS 主机地址")
        require(path.startsWith('/') && !path.contains("..")) { "invalid Aries path" }
        val idempotencyHeader = AriesProtocolPolicy.idempotencyHeader(method, path, signed, idempotencyKey)
        if (signed && identityStore.identity().deviceId == null) {
            throw AriesException(message = "Aries 设备尚未注册")
        }
        val requestUri = URI(base + path)
        val connection = (requestUri.toURL().openConnection() as HttpsURLConnection).apply {
            connectTimeout = config.connectTimeoutMs.coerceIn(500, 30_000)
            readTimeout = config.readTimeoutMs.coerceIn(500, 60_000)
            requestMethod = method
            instanceFollowRedirects = false
            sslSocketFactory = ariesSslSocketFactory()
            hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
            setRequestProperty("Accept", "application/json")
            if (body.isNotEmpty() || method != "GET") {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setFixedLengthStreamingMode(body.size)
            }
        }
        try {
            if (signed) {
                val identity = identityStore.identity()
                val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                val nonce = encodeBase64Url(ByteArray(18).also { java.security.SecureRandom().nextBytes(it) })
                val idempotency = idempotencyHeader.orEmpty()
                val bodyHash = encodeBase64Url(MessageDigest.getInstance("SHA-256").digest(body))
                val canonical = listOf(method, requestUri.rawPath + (requestUri.rawQuery?.let { "?$it" } ?: ""),
                    timestamp, nonce, idempotency, bodyHash).joinToString("\n")
                val signature = encodeBase64Url(identityStore.sign(canonical.toByteArray(StandardCharsets.UTF_8)))
                connection.setRequestProperty("X-Device-ID", identity.deviceId)
                connection.setRequestProperty("X-Device-Timestamp", timestamp)
                connection.setRequestProperty("X-Device-Nonce", nonce)
                connection.setRequestProperty("X-Device-Signature", signature)
            }
            idempotencyHeader?.let { connection.setRequestProperty("Idempotency-Key", it) }
            coroutineContext.ensureActive()
            if (body.isNotEmpty() || method != "GET") connection.outputStream.use { it.write(body) }
            coroutineContext.ensureActive()
            val status = connection.responseCode
            val responseRequestId = connection.getHeaderField("X-Request-ID")
                ?.trim()?.takeIf { it.isNotEmpty() }
            val responseBody = try {
                (if (status in 200..299) connection.inputStream else connection.errorStream)?.use {
                    AriesProtocolPolicy.readResponse(it, MAX_RESPONSE_BYTES) { coroutineContext.ensureActive() }
                } ?: throw AriesException(
                    httpStatus = status, message = "Aries response is empty", requestId = responseRequestId,
                )
            } catch (error: AriesProtocolPolicy.ResponseTooLarge) {
                throw AriesException(
                    httpStatus = status, errorCode = "response_too_large", message = error.message.orEmpty(),
                    cause = error, requestId = responseRequestId,
                )
            }
            coroutineContext.ensureActive()
            if (status !in 200..299) throw parseHttpError(status, responseBody, responseRequestId)
            try {
                parse(JSONObject(responseBody))
            } catch (error: AriesException) {
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw AriesException(
                    httpStatus = status,
                    message = "Aries response is invalid",
                    cause = error,
                    requestId = responseRequestId,
                )
            }
        } finally {
            connection.disconnect()
        }
    }.onFailure {
        if (it is CancellationException) throw it
        currentCoroutineContext().ensureActive()
    }

    // Reuse the immutable bundled-CA TLS context across this client's requests.
    // Creating a new factory for every poll prevents TLS session reuse and
    // separates HTTPS connection pools. Authorization and node checks still
    // run for each request in the provider; no remote state is cached here.
    private val ariesSocketFactory: javax.net.ssl.SSLSocketFactory by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val factory = java.security.cert.CertificateFactory.getInstance("X.509")
        val root = context.resources.openRawResource(R.raw.dafeiyu_download_ca).use {
            factory.generateCertificate(it)
        }
        val store = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("aries-root", root)
        }
        val trustManager = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm(),
        ).apply { init(store) }.trustManagers.filterIsInstance<X509TrustManager>().single()
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), null)
        }.socketFactory
    }

    private fun ariesSslSocketFactory(): javax.net.ssl.SSLSocketFactory = ariesSocketFactory

    private fun parseHttpError(status: Int, body: String, responseRequestId: String?): AriesException {
        val error = runCatching {
            JSONObject(body).optJSONObject("error")
        }.getOrNull()
        val errorRequestId = error?.optionalString("request_id")
        return AriesException(
            httpStatus = status,
            errorCode = error?.optionalString("code"),
            message = error?.optionalString("message") ?: "Aries HTTP $status",
            requestId = responseRequestId ?: errorRequestId,
        )
    }

    private fun validateChallenge(challenge: Challenge) {
        require(challenge.challengeId.isNotBlank() && challenge.challengeId.none(Char::isISOControl)) {
            "Aries challenge_id is missing or invalid"
        }
        require(challenge.expiresAt?.isNotBlank() == true && challenge.expiresAt.none(Char::isISOControl)) {
            "Aries challenge expires_at is missing or invalid"
        }
        decodeUnpaddedBase64Url(challenge.nonce, expectedSize = CHALLENGE_NONCE_BYTES)
        require(challenge.publicKey.isNotBlank() && challenge.publicKey.none(Char::isISOControl)) {
            "Aries challenge public key is missing or invalid"
        }
    }

    private fun decodeUnpaddedBase64Url(value: String, expectedSize: Int): ByteArray {
        require(value.isNotEmpty() && value.length % 4 != 1 && value.all(::isBase64UrlChar)) {
            "Aries challenge nonce is not unpadded base64url"
        }
        val decoded = runCatching {
            Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }.getOrElse {
            throw IllegalArgumentException("Aries challenge nonce is not valid base64url", it)
        }
        require(Base64.encodeToString(decoded, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) == value) {
            "Aries challenge nonce is not canonical unpadded base64url"
        }
        require(decoded.size == expectedSize) {
            "Aries challenge nonce must decode to $expectedSize bytes"
        }
        return decoded
    }

    private fun requireDeviceId(value: String): String {
        require(DEVICE_ID_PATTERN.matches(value)) { "Aries device_id is invalid" }
        return value
    }

    private fun requireEnrollmentStatus(value: String): String {
        require(value == ENROLLMENT_STATUS_REGISTERED) { "Aries enrollment status is invalid" }
        return value
    }

    private fun deriveDeviceId(publicKeyBase64Url: String): String {
        val publicKey = runCatching {
            Base64.decode(publicKeyBase64Url, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }.getOrElse {
            throw IllegalArgumentException("Aries public key is invalid", it)
        }
        require(publicKey.size == ED25519_PUBLIC_KEY_BYTES) { "Aries public key is invalid" }
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey)
        return "dev_" + digest.take(DEVICE_ID_HASH_BYTES).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun requireInviteCode(value: String): String {
        val code = value.trim()
        require(code.length in 1..128 && code.none(Char::isISOControl)) { "Aries 邀请码无效" }
        return code
    }

    private fun requireIdempotencyKey(value: String): String = AriesProtocolPolicy.requireIdempotencyKey(value)

    private fun encodePathSegment(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    companion object {
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val CHALLENGE_NONCE_BYTES = 32
        private const val ED25519_PUBLIC_KEY_BYTES = 32
        private const val DEVICE_ID_HASH_BYTES = 16
        private const val ENROLLMENT_STATUS_REGISTERED = "registered"
        private val DEVICE_ID_PATTERN = Regex("dev_[0-9a-f]{32}")

        private fun isBase64UrlChar(value: Char): Boolean =
            value in 'A'..'Z' || value in 'a'..'z' || value in '0'..'9' || value == '-' || value == '_'

        private fun newIdempotencyKey(): String = "android-" + UUID.randomUUID().toString()
        private fun encodeBase64Url(value: ByteArray): String =
            Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    }
}

