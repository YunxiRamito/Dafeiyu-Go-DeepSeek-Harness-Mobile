package com.dafeiyu.mobile.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Anonymous presence client for DeveloperCenter.Server.
 *
 * The server counts installations that sent a heartbeat during the preceding
 * five-minute window. Presence reads remain read-only; heartbeat() is the
 * only method that registers this installation.
 */
class DeveloperCenterPresenceClient(
    private val config: Config = Config(),
) {
    data class Config(
        val baseUrl: String? = DeveloperCenterPresenceClient.DEFAULT_BASE_URL,
        val connectTimeoutMs: Int = 1_500,
        val readTimeoutMs: Int = 1_500,
    )

    enum class ServiceState {
        PREVIEW,
        ONLINE,
        OFFLINE,
    }

    enum class HeartbeatState {
        /** No server URL is configured for this client instance. */
        PREVIEW,

        /** The server accepted the anonymous installation heartbeat. */
        SENT,

        /** A successful heartbeat was sent less than 60 seconds ago. */
        THROTTLED,

        /** The request failed or the server rejected it. */
        FAILED,

        /** The supplied launcher version is not valid for the server contract. */
        INVALID_INPUT,
    }

    data class PlatformVersion(
        val version: String,
        val versionCode: Int?,
        val count: Int,
    )

    data class PlatformSnapshot(
        val platform: String,
        val onlineCount: Int,
        val versions: List<PlatformVersion>,
        val otherVersionCount: Int,
    )

    data class Snapshot(
        val serviceState: ServiceState,
        /** Combined PC + Android count; null when only a legacy PC snapshot is available. */
        val onlineDeviceCount: Int? = null,
        val onlineWindowSeconds: Int? = null,
        val pcOnlineCount: Int? = null,
        val androidOnlineCount: Int? = null,
        val platforms: List<PlatformSnapshot> = emptyList(),
    ) {
        fun platform(name: String): PlatformSnapshot? = platforms.firstOrNull { it.platform == name }
    }

    data class HeartbeatResult(
        val state: HeartbeatState,
        val serverTime: String? = null,
        val onlineWindowSeconds: Int? = null,
    ) {
        val accepted: Boolean
            get() = state == HeartbeatState.SENT
    }

    /**
     * Reads the public online count. This never registers the mobile client.
     */
    suspend fun probe(): Snapshot = withContext(Dispatchers.IO) {
        val normalizedBaseUrl = normalizeBaseUrl(config.baseUrl)
            ?: return@withContext Snapshot(ServiceState.PREVIEW)
        runCatching {
            val response = request(
                method = "GET",
                url = "$normalizedBaseUrl/api/presence/platforms",
            )
            if (response.code in 200..299) {
                val body = response.body ?: return@runCatching Snapshot(ServiceState.OFFLINE)
                return@runCatching parsePlatforms(JSONObject(body))
            }
            // Older server deployments expose only the PC installation table at /api/presence.
            // Keep that useful as an explicitly PC-only snapshot; never label it as a combined total.
            if (response.code !in setOf(404, 405)) return@runCatching Snapshot(ServiceState.OFFLINE)
            val legacy = request(method = "GET", url = "$normalizedBaseUrl/api/presence")
            if (legacy.code !in 200..299) return@runCatching Snapshot(ServiceState.OFFLINE)
            val json = legacy.body?.let(::JSONObject)
                ?: return@runCatching Snapshot(ServiceState.OFFLINE)
            val count = nonNegativeInt(json, "onlineCount")
                ?: return@runCatching Snapshot(ServiceState.OFFLINE)
            Snapshot(
                serviceState = ServiceState.ONLINE,
                onlineWindowSeconds = positiveInt(json, "onlineWindowSeconds") ?: 300,
                pcOnlineCount = count,
            )
        }.getOrElse {
            coroutineContext.ensureActive()
            Snapshot(ServiceState.OFFLINE)
        }
    }

    internal fun parsePlatforms(json: JSONObject): Snapshot {
        val rows = json.optJSONArray("platforms") ?: error("platforms snapshot missing")
        if (rows.length() != 2) error("invalid platform coverage")
        val platforms = buildList {
            for (index in 0 until rows.length()) {
                val item = rows.optJSONObject(index) ?: error("invalid platform snapshot")
                val platform = item.optString("platform")
                if (platform != "pc" && platform != "android") error("unsupported presence platform")
                val onlineCount = nonNegativeInt(item, "onlineCount") ?: error("invalid platform count")
                val otherVersionCount = nonNegativeInt(item, "otherVersionCount") ?: error("invalid other version count")
                val encodedVersions = item.optJSONArray("versions") ?: error("platform versions missing")
                if (encodedVersions.length() > 64) error("too many version groups")
                val versions = buildList {
                    for (versionIndex in 0 until encodedVersions.length()) {
                        val version = encodedVersions.optJSONObject(versionIndex) ?: error("invalid version item")
                        val label = version.optString("version")
                        val count = nonNegativeInt(version, "count") ?: error("invalid version count")
                        if (normalizeLauncherVersion(label) != label) error("invalid version label")
                        if (count == 0) error("empty version group")
                        val versionCode = if (version.has("versionCode") && !version.isNull("versionCode")) {
                            nonNegativeInt(version, "versionCode")?.takeIf { it in 1..2_100_000_000 }
                                ?: error("invalid version code")
                        } else null
                        if (platform == "pc" && versionCode != null) error("unexpected PC version code")
                        add(PlatformVersion(label, versionCode, count))
                    }
                }
                if (versions.map { it.version to it.versionCode }.toSet().size != versions.size) {
                    error("duplicate version group")
                }
                if (versions.sumOf { it.count.toLong() } + otherVersionCount != onlineCount.toLong()) {
                    error("version counts mismatch")
                }
                add(PlatformSnapshot(platform, onlineCount, versions, otherVersionCount))
            }
        }
        if (platforms.map { it.platform }.toSet() != setOf("pc", "android")) error("platform coverage incomplete")
        val pc = platforms.first { it.platform == "pc" }
        val android = platforms.first { it.platform == "android" }
        val combined = (pc.onlineCount.toLong() + android.onlineCount.toLong())
            .takeIf { it <= Int.MAX_VALUE }?.toInt() ?: error("combined count overflow")
        if (nonNegativeInt(json, "onlineCount") != combined) error("combined count mismatch")
        if (nonNegativeInt(json, "onlineInstallations") != combined) error("installation count mismatch")
        val onlineWindowSeconds = positiveInt(json, "onlineWindowSeconds")
            ?.takeIf { it <= 86_400 } ?: error("invalid online window")
        return Snapshot(
            serviceState = ServiceState.ONLINE,
            onlineDeviceCount = combined,
            onlineWindowSeconds = onlineWindowSeconds,
            pcOnlineCount = pc.onlineCount,
            androidOnlineCount = android.onlineCount,
            platforms = platforms,
        )
    }

    private fun nonNegativeInt(json: JSONObject, name: String): Int? {
        val value = json.opt(name) as? Number ?: return null
        val number = value.toLong()
        return number.takeIf { it >= 0 && it <= Int.MAX_VALUE && value.toDouble() == it.toDouble() }?.toInt()
    }

    private fun positiveInt(json: JSONObject, name: String): Int? =
        nonNegativeInt(json, name)?.takeIf { it > 0 }

    /**
     * Registers this app installation with DeveloperCenter.Server.
     *
     * Call this from a foreground lifecycle while the app is active, at most
     * once every 60 seconds. The method also persists its last attempt
     * time, so repeated foreground callbacks and process recreation cannot
     * create a faster heartbeat stream. Only a random UUID is persisted and
     * sent; no device identifiers or hardware information leave the app.
     *
     * Failures are represented by HeartbeatResult and never escape to the UI.
     */
    suspend fun heartbeat(
        context: Context,
        launcherVersion: String,
    ): HeartbeatResult = withContext(Dispatchers.IO) {
        val normalizedBaseUrl = normalizeBaseUrl(config.baseUrl)
            ?: return@withContext HeartbeatResult(HeartbeatState.PREVIEW)
        val version = normalizeLauncherVersion(launcherVersion)
            ?: return@withContext HeartbeatResult(HeartbeatState.INVALID_INPUT)

        val preferences = runCatching {
            context.applicationContext.getSharedPreferences(
                INSTALLATION_PREFERENCES,
                Context.MODE_PRIVATE,
            )
        }.getOrNull()
            ?: return@withContext HeartbeatResult(HeartbeatState.FAILED)
        val now = System.currentTimeMillis()

        synchronized(HEARTBEAT_LOCK) {
            val lastSentAt = preferences.getLong(KEY_LAST_HEARTBEAT_AT, 0L)
            if (lastSentAt > 0L && now >= lastSentAt && now - lastSentAt < HEARTBEAT_INTERVAL_MS) {
                return@synchronized HeartbeatResult(HeartbeatState.THROTTLED)
            }

            val installationId = runCatching { getOrCreateInstallationId(preferences) }
                .getOrNull()
                ?: return@synchronized HeartbeatResult(HeartbeatState.FAILED)
            if (!preferences.edit().putLong(KEY_LAST_HEARTBEAT_AT, now).commit()) {
                return@synchronized HeartbeatResult(HeartbeatState.FAILED)
            }
            runCatching {
                val versionCode = installedVersionCode(context)
                val body = JSONObject()
                    .put("installationId", installationId.toString())
                    .put("appVersion", version)
                    .put("versionCode", versionCode ?: JSONObject.NULL)
                    // The Android-specific route already separates its table; retain the
                    // requested marker as an additive compatibility hint for newer servers.
                    .put("platform", ANDROID_PLATFORM_MARKER)
                    .toString()
                val response = request(
                    method = "POST",
                    url = "$normalizedBaseUrl/api/android/heartbeat",
                    body = body,
                )
                if (response.code !in 200..299) {
                    return@runCatching HeartbeatResult(HeartbeatState.FAILED)
                }

                val responseJson = response.body?.let(::JSONObject)

                HeartbeatResult(
                    state = HeartbeatState.SENT,
                    serverTime = responseJson?.optString("lastSeenAt")
                        ?.takeIf { it.isNotBlank() }
                        ?: responseJson?.optString("serverTime")?.takeIf { it.isNotBlank() },
                    onlineWindowSeconds = responseJson
                        ?.optInt("onlineWindowSeconds", 300)
                        ?.takeIf { it > 0 },
                )
            }.getOrElse { HeartbeatResult(HeartbeatState.FAILED) }
        }
    }

    private fun request(
        method: String,
        url: String,
        body: String? = null,
    ): HttpResult {
        val uri = URI(url)
        val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = config.connectTimeoutMs
            readTimeout = config.readTimeoutMs
            useCaches = false
            // Redirects would weaken the endpoint/pin decision and are not part
            // of either DeveloperCenter public contract.
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setFixedLengthStreamingMode(body.toByteArray(Charsets.UTF_8).size)
            }
        }
        if (connection is HttpsURLConnection && isPinnedEndpoint(uri)) {
            connection.sslSocketFactory = pinnedSslSocketFactory()
            // Keep the platform verifier. It strictly checks the IP/DNS SAN;
            // hostname verification is never replaced with an allow-all lambda.
            connection.hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        }
        return try {
            if (body != null) {
                connection.outputStream.use { output ->
                    output.write(body.toByteArray(Charsets.UTF_8))
                }
            }
            val code = connection.responseCode
            HttpResult(code, readBody(connection, code))
        } finally {
            connection.disconnect()
        }
    }

    private fun readBody(connection: HttpURLConnection, code: Int): String? {
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            ?: return null
        return stream.bufferedReader(Charsets.UTF_8).use {
            val buffer = CharArray(MAX_RESPONSE_CHARS + 1)
            var size = 0
            while (size < buffer.size) {
                val read = it.read(buffer, size, buffer.size - size)
                if (read < 0) break
                size += read
            }
            check(size <= MAX_RESPONSE_CHARS) { "presence response too large" }
            String(buffer, 0, size)
        }
    }

    private fun normalizeBaseUrl(raw: String?): String? {
        val value = raw?.trim()?.trimEnd('/') ?: return null
        if (value.isBlank()) return null
        return runCatching {
            val uri = URI(value)
            if (
                uri.scheme !in setOf("http", "https") ||
                uri.host.isNullOrBlank() ||
                uri.rawUserInfo != null ||
                uri.query != null ||
                uri.fragment != null
            ) {
                null
            } else {
                value
            }
        }.getOrNull()
    }

    private fun normalizeLauncherVersion(raw: String): String? {
        val value = raw.trim()
        return value.takeIf {
            it.isNotEmpty() && it.length <= MAX_LAUNCHER_VERSION_LENGTH &&
                it.none(Char::isISOControl)
        }
    }

    private fun installedVersionCode(context: Context): Int? = runCatching {
        val packageInfo = context.applicationContext.packageManager.getPackageInfo(
            context.applicationContext.packageName,
            0,
        )
        @Suppress("DEPRECATION")
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
        code.takeIf { it in 1L..2_100_000_000L }?.toInt()
    }.getOrNull()

    private fun isPinnedEndpoint(uri: URI): Boolean =
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(PINNED_HOST, ignoreCase = true) &&
            uri.port == PINNED_PORT

    private fun getOrCreateInstallationId(preferences: android.content.SharedPreferences): UUID {
        val existing = preferences.getString(KEY_INSTALLATION_ID, null)
            ?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() }
        if (existing != null) return existing

        val generated = UUID.randomUUID()
        // commit() is deliberate: the UUID must survive a process death before
        // the first heartbeat returns, otherwise one install could be counted as
        // several installations.
        check(preferences.edit().putString(KEY_INSTALLATION_ID, generated.toString()).commit()) {
            "unable to persist installation id"
        }
        return generated
    }

    private data class HttpResult(val code: Int, val body: String?)

    private fun pinnedSslSocketFactory(): SSLSocketFactory {
        val defaults = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm(),
        ).apply { init(null as java.security.KeyStore?) }
        val defaultTrust = defaults.trustManagers.filterIsInstance<X509TrustManager>().single()
        val pinned = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = defaultTrust.acceptedIssuers

            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                defaultTrust.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                require(chain.isNotEmpty()) { "empty server certificate chain" }
                val leaf = chain.first()
                // The pin is required even when the system trust store happens
                // to trust the certificate. If it is a private-CA certificate,
                // the exact pin still permits it after the leaf validity check.
                leaf.checkValidity()
                val actual = MessageDigest.getInstance("SHA-256")
                    .digest(leaf.publicKey.encoded)
                    .joinToString("") { byte -> "%02x".format(byte) }
                check(actual.equals(PINNED_SPKI_SHA256, ignoreCase = true)) {
                    "untrusted pinned server certificate"
                }
            }
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(pinned), null)
        }.socketFactory
    }

    companion object {
        // The public DeveloperCenter endpoint used by the PC launcher.
        // Passing Config(baseUrl = null) keeps a local/mobile preview offline.
        const val DEFAULT_BASE_URL = "https://202.189.21.218:8787"

        private const val PINNED_HOST = "202.189.21.218"
        private const val PINNED_PORT = 8787
        private const val PINNED_SPKI_SHA256 =
            "2c72728755267c607124b2ac86e431743c34af5c6069aa22bbcc401e0f626f0b"

        private const val INSTALLATION_PREFERENCES =
            "developer_center_presence"
        private const val KEY_INSTALLATION_ID = "installation_id"
        private const val KEY_LAST_HEARTBEAT_AT = "last_heartbeat_attempt_at_millis"
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
        private const val MAX_LAUNCHER_VERSION_LENGTH = 64
        private const val MAX_RESPONSE_CHARS = 32_768
        private const val ANDROID_PLATFORM_MARKER = "我是Android系统的"
        private val HEARTBEAT_LOCK = Any()
    }
}






