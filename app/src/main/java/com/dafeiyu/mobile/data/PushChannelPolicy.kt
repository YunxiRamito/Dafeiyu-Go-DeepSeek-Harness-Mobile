package com.dafeiyu.mobile.data

import org.json.JSONObject

data class PushCapabilities(val protocol: String, val channels: Set<String>, val configured: Boolean)
data class PushPreferences(val enabled: Boolean, val live: Boolean, val devices: Boolean,
    val announcements: Boolean, val messages: Boolean, val backend: Boolean) {
    internal fun json(): JSONObject = JSONObject().put("enabled", enabled).put("live", live).put("devices", devices)
        .put("announcements", announcements).put("messages", messages).put("backend", backend)
}
data class PushRegistrationState(val installationKey: String, val status: String, val channel: String?,
    val revision: Long, val preferences: PushPreferences?)

/** Construct only from a real registered SDK callback, never an installation UUID or guessed token. */
class PushSdkToken(val channel: String, val token: String) {
    init { require(channel in PushChannelPolicy.channels && token.length in 1..4096 && token.all { it in '!'..'~' }) }
    override fun toString(): String = "PushSdkToken(channel=$channel, token=[redacted])"
}
data class PushChannelSelection(val channel: String?, val reason: String)

object PushChannelPolicy {
    val channels = setOf("fcm", "mipush", "oppo", "vivo", "huawei")
    /** This build has no registered OEM/FCM application accounts or SDK callbacks yet. */
    val installedSdkChannels: Set<String> = emptySet()

    fun select(serverChannels: Set<String>, sdkChannels: Set<String>, manufacturer: String,
        hasGms: Boolean, explicitChannel: String? = null): PushChannelSelection {
        require(serverChannels.all { it in channels } && sdkChannels.all { it in channels })
        if (explicitChannel != null) {
            require(explicitChannel in channels)
            return when {
                explicitChannel !in serverChannels -> PushChannelSelection(null, "channel-unconfigured")
                explicitChannel !in sdkChannels -> PushChannelSelection(null, "sdk-unconfigured")
                else -> PushChannelSelection(explicitChannel, "available")
            }
        }
        if (serverChannels.isEmpty()) return PushChannelSelection(null, "server-unconfigured")
        val preferred = when (manufacturer.lowercase(java.util.Locale.ROOT)) {
            "xiaomi", "redmi", "poco" -> "mipush"
            "oppo", "oneplus", "realme" -> "oppo"
            "vivo", "iqoo" -> "vivo"
            "huawei", "honor" -> "huawei"
            else -> null
        }
        val order = buildList { if (hasGms) add("fcm"); preferred?.let(::add) }.distinct()
        val selected = order.firstOrNull { it in serverChannels && it in sdkChannels }
        return PushChannelSelection(selected, if (selected != null) "available" else "sdk-unconfigured")
    }
}
