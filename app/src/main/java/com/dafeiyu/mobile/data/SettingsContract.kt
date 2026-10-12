package com.dafeiyu.mobile.data

import java.net.URI

/**
 * Stable values shared by the settings repository and future settings screens.
 * Keep these ids protocol-facing; display labels belong to the UI layer.
 */
object SettingsContract {
    const val SETTINGS_VERSION = 2

    val THEME_MODES = setOf("system", "light", "dark")
    val DOWNLOAD_SOURCES = setOf("official", "cdn", "backend")
    val UPDATE_POLICIES = setOf("auto", "remind", "off")
    val DSH_VERSION_CHANNELS = setOf("stable", "preview")
    val ENGINE_MODES = setOf("official", "dsh")
    val CONNECTION_MODES = setOf("local", "online", "hybrid")
    val ONLINE_CONNECTION_STATES = setOf(
        "unconfigured",
        "checking",
        "offline",
        "healthy",
        "unregistered",
        "registered",
    )

    fun themeMode(value: String): String = value.takeIf { it in THEME_MODES } ?: "system"
    fun downloadSource(value: String): String = value.takeIf { it in DOWNLOAD_SOURCES } ?: "cdn"
    fun updatePolicy(value: String): String = value.takeIf { it in UPDATE_POLICIES } ?: "remind"
    fun dshVersionChannel(value: String): String = value.takeIf { it in DSH_VERSION_CHANNELS } ?: "stable"
    fun engineMode(value: String): String = value.takeIf { it in ENGINE_MODES } ?: "official"
    fun connectionMode(value: String): String = value.takeIf { it in CONNECTION_MODES } ?: "local"
    fun onlineConnectionState(value: String): String =
        value.takeIf { it in ONLINE_CONNECTION_STATES } ?: "unconfigured"

    /**
     * Aries endpoints are transport configuration, never arbitrary user supplied URLs.
     * HTTPS is required for remote connections; loopback HTTP is intentionally not
     * accepted by the mobile client because the PC service contract requires TLS off
     * loopback.
     */
    fun normalizeAriesEndpoint(raw: String): String? = runCatching {
        val value = raw.trim().trimEnd('/')
        val uri = URI(value)
        if (!uri.scheme.equals("https", ignoreCase = true)) return@runCatching null
        if (uri.userInfo != null || uri.query != null || uri.fragment != null || uri.host.isNullOrBlank()) {
            return@runCatching null
        }
        if (uri.path.isNullOrBlank() || uri.path == "/") value else null
    }.getOrNull()?.takeIf { it.length <= 512 }
}

enum class OnlineConnectionState(val id: String) {
    UNCONFIGURED("unconfigured"),
    CHECKING("checking"),
    OFFLINE("offline"),
    HEALTHY("healthy"),
    UNREGISTERED("unregistered"),
    REGISTERED("registered"),
    ;

    companion object {
        fun fromId(id: String): OnlineConnectionState =
            entries.firstOrNull { it.id == id } ?: UNCONFIGURED
    }
}

data class AriesConnectionSettings(
    val endpoint: String = "",
    val nodeId: String? = null,
    val serverCode: String? = null,
    val deviceId: String? = null,
    val state: OnlineConnectionState = OnlineConnectionState.UNCONFIGURED,
    val lastCheckedAtEpochMillis: Long? = null,
)

data class SettingsSnapshot(
    val themeMode: String = "system",
    val reduceTransparency: Boolean = false,
    val reduceMotion: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val keepRunning: Boolean = true,
    val downloadSource: String = "cdn",
    val updatePolicy: String = "remind",
    val updateWifiOnly: Boolean = true,
    val dshVersionChannel: String = "stable",
    val dshUpdatePolicy: String = "remind",
    val aries: AriesConnectionSettings = AriesConnectionSettings(),
)

data class AriesNodeStatus(
    val apiVersion: String? = null,
    val nodeId: String? = null,
    val serverCode: String? = null,
    val buildVersion: String? = null,
    val serviceStatus: String? = null,
    val startedAt: String? = null,
)

