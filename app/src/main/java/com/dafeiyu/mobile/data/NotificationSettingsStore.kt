package com.dafeiyu.mobile.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Stable notification channel identifiers owned by the mobile app.
 *
 * Creating a channel does not post a notification and does not start any
 * background work. Channel ids must remain stable after release because
 * Android stores the user's channel choices by id.
 */
object NotificationChannelIds {
    const val GENERAL = "dafeiyu.general"
    const val TASKS = "dafeiyu.tasks"
    const val LIVE_STATUS = "dafeiyu.live_status"
    const val ANNOUNCEMENTS = "dafeiyu.announcements"
    const val DEVICES = "dafeiyu.devices"

    val all: List<String> = listOf(GENERAL, TASKS, LIVE_STATUS, ANNOUNCEMENTS, DEVICES)
}

data class NotificationChannelState(
    val id: String,
    val name: String,
    val description: String?,
    val exists: Boolean,
    val importance: Int?,
    val enabled: Boolean,
    val blockedByUser: Boolean,
    val showBadge: Boolean?,
    val userLockedFields: Int?,
)

data class NotificationPreferences(
    val taskNotificationsEnabled: Boolean,
    val liveIslandEnabled: Boolean,
    val announcementNotificationsEnabled: Boolean,
    val deviceNotificationsEnabled: Boolean,
)

data class NotificationFeatureState(
    val taskNotificationsPreferenceEnabled: Boolean,
    val taskNotificationsAllowed: Boolean,
    val liveIslandPreferenceEnabled: Boolean,
    val liveStatusChannelAvailable: Boolean,
)

data class NotificationSystemState(
    val permissionDeclared: Boolean,
    val runtimePermissionGranted: Boolean,
    val appNotificationsEnabled: Boolean,
    val channelsSupported: Boolean,
    val channels: List<NotificationChannelState>,
    val preferences: NotificationPreferences,
    val features: NotificationFeatureState,
) {
    val canRequestRuntimePermission: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            permissionDeclared && !runtimePermissionGranted
}

/**
 * Data-only access to Android notification configuration and app preferences.
 *
 * This class deliberately has no notification posting API. It only manages
 * channels, reads system state, and stores the notification preference values
 * already used by the settings repository.
 */
class NotificationSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val notificationManager: NotificationManager? =
        appContext.getSystemService(NotificationManager::class.java)
    private val preferences =
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** Creates missing channels and reuses existing channels without resetting user choices. */
    fun ensureChannels(): List<NotificationChannelState> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()
        val manager = notificationManager ?: return emptyList()
        manager.createNotificationChannels(channelDefinitions().map(::toNotificationChannel))
        return readChannels(manager)
    }

    /** Reads current app permission, app-wide notification status, channels, and feature preferences. */
    fun readState(ensureChannels: Boolean = false): NotificationSystemState {
        val channels = if (ensureChannels) {
            ensureChannels()
        } else {
            readChannels(notificationManager)
        }
        val appNotificationsEnabled = notificationManager?.areNotificationsEnabled() ?: false
        val currentPreferences = readPreferences()
        val taskChannel = channels.firstOrNull { it.id == NotificationChannelIds.TASKS }
        val liveStatusChannel = channels.firstOrNull { it.id == NotificationChannelIds.LIVE_STATUS }
        val taskAllowed = currentPreferences.taskNotificationsEnabled &&
            appNotificationsEnabled &&
            (taskChannel?.enabled ?: false)
        val liveStatusAvailable = currentPreferences.liveIslandEnabled &&
            appNotificationsEnabled &&
            (liveStatusChannel?.enabled ?: false)

        return NotificationSystemState(
            permissionDeclared = isPostNotificationsDeclared(),
            runtimePermissionGranted = isPostNotificationsGranted(),
            appNotificationsEnabled = appNotificationsEnabled,
            channelsSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null,
            channels = channels,
            preferences = currentPreferences,
            features = NotificationFeatureState(
                taskNotificationsPreferenceEnabled = currentPreferences.taskNotificationsEnabled,
                taskNotificationsAllowed = taskAllowed,
                liveIslandPreferenceEnabled = currentPreferences.liveIslandEnabled,
                liveStatusChannelAvailable = liveStatusAvailable,
            ),
        )
    }

    fun readPreferences(): NotificationPreferences = NotificationPreferences(
        taskNotificationsEnabled = preferences.getBoolean(KEY_TASK_NOTIFICATIONS, DEFAULT_ENABLED),
        liveIslandEnabled = preferences.getBoolean(KEY_LIVE_ISLAND, DEFAULT_ENABLED),
        announcementNotificationsEnabled = preferences.getBoolean(KEY_ANNOUNCEMENTS, DEFAULT_ENABLED),
        deviceNotificationsEnabled = preferences.getBoolean(KEY_DEVICES, DEFAULT_ENABLED),
    )

    /** Updates the existing app-level task/reply/error notification preference. */
    fun setTaskNotificationsEnabled(enabled: Boolean): NotificationPreferences =
        updateBoolean(KEY_TASK_NOTIFICATIONS, enabled).let { readPreferences() }

    /** Updates the existing app-level preference for future ongoing/live status presentation. */
    fun setLiveIslandEnabled(enabled: Boolean): NotificationPreferences =
        updateBoolean(KEY_LIVE_ISLAND, enabled).let { readPreferences() }

    fun setAnnouncementNotificationsEnabled(enabled: Boolean): NotificationPreferences =
        updateBoolean(KEY_ANNOUNCEMENTS, enabled).let { readPreferences() }

    fun setDeviceNotificationsEnabled(enabled: Boolean): NotificationPreferences =
        updateBoolean(KEY_DEVICES, enabled).let { readPreferences() }

    fun channelState(channelId: String): NotificationChannelState? =
        readChannels(notificationManager).firstOrNull { it.id == channelId }

    private fun updateBoolean(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
    }

    private fun isPostNotificationsDeclared(): Boolean {
        val requestedPermissions = runCatching {
            @Suppress("DEPRECATION")
            appContext.packageManager
                .getPackageInfo(appContext.packageName, 0)
                .requestedPermissions
                .orEmpty()
                .toSet()
        }.getOrDefault(emptySet())
        return Manifest.permission.POST_NOTIFICATIONS in requestedPermissions
    }

    private fun isPostNotificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private fun readChannels(manager: NotificationManager?): List<NotificationChannelState> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || manager == null) return emptyList()
        return channelDefinitions().map { definition ->
            val channel = manager.getNotificationChannel(definition.id)
            channel?.let(::toChannelState) ?: NotificationChannelState(
                id = definition.id,
                name = definition.name,
                description = definition.description,
                exists = false,
                importance = null,
                enabled = false,
                blockedByUser = false,
                showBadge = null,
                userLockedFields = null,
            )
        }
    }

    private fun channelDefinitions(): List<ChannelDefinition> = listOf(
        ChannelDefinition(
            id = NotificationChannelIds.GENERAL,
            name = "大肥鱼通知",
            description = "大肥鱼的一般提醒",
            importance = NotificationManager.IMPORTANCE_DEFAULT,
        ),
        ChannelDefinition(
            id = NotificationChannelIds.TASKS,
            name = "任务通知",
            description = "回复、任务完成和错误提醒",
            importance = NotificationManager.IMPORTANCE_DEFAULT,
        ),
        ChannelDefinition(
            id = NotificationChannelIds.LIVE_STATUS,
            name = "进行中的状态",
            description = "为未来的进行中状态展示预留的通知渠道",
            importance = NotificationManager.IMPORTANCE_LOW,
        ),
        ChannelDefinition(
            id = NotificationChannelIds.ANNOUNCEMENTS,
            name = "公告推送",
            description = "产品公告和服务变更",
            importance = NotificationManager.IMPORTANCE_LOW,
        ),
        ChannelDefinition(
            id = NotificationChannelIds.DEVICES,
            name = "连接的设备",
            description = "电脑或 Aries 设备的连接状态",
            importance = NotificationManager.IMPORTANCE_LOW,
        ),
    )

    private fun toNotificationChannel(definition: ChannelDefinition): NotificationChannel =
        NotificationChannel(definition.id, definition.name, definition.importance).apply {
            description = definition.description
            setShowBadge(definition.id != NotificationChannelIds.LIVE_STATUS)
        }

    private fun toChannelState(channel: NotificationChannel): NotificationChannelState =
        NotificationChannelState(
            id = channel.id,
            name = channel.name.toString(),
            description = channel.description,
            exists = true,
            importance = channel.importance,
            enabled = channel.importance != NotificationManager.IMPORTANCE_NONE,
            blockedByUser = channel.importance == NotificationManager.IMPORTANCE_NONE,
            showBadge = channel.canShowBadge(),
            userLockedFields = null,
        )

    private data class ChannelDefinition(
        val id: String,
        val name: String,
        val description: String,
        val importance: Int,
    )

    companion object {
        private const val PREFERENCES_NAME = "dafeiyu_settings"
        private const val KEY_TASK_NOTIFICATIONS = "notifications_enabled"
        private const val KEY_LIVE_ISLAND = "live_island_notifications"
        private const val KEY_ANNOUNCEMENTS = "announcement_notifications"
        private const val KEY_DEVICES = "device_notifications"
        private const val DEFAULT_ENABLED = true
    }
}
