package com.dafeiyu.mobile.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dafeiyu.mobile.MainActivity

/**
 * Small local publisher used by the foreground task lifecycle. It does not
 * pretend to be FCM or an OEM push channel; server events can use the same
 * channels once a registered transport delivers them.
 */
class NotificationPublisher(context: Context) {
    private val app = context.applicationContext
    private val manager = NotificationManagerCompat.from(app)

    fun ensureChannels() {
        NotificationSettingsStore(app).ensureChannels()
    }

    fun postRunning(enabled: Boolean, detail: String = "任务正在运行") {
        if (!enabled || !canPost()) { clearRunning(); return }
        ensureChannels()
        val notification = base(NotificationChannelIds.LIVE_STATUS, "Dafeiyu-GO", detail)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(0, 0, true)
            .build()
        notifyGuarded(
            RUNNING_ID,
            requestLiveUpdate(notification),
        )
    }

    fun postCompleted(enabled: Boolean, detail: String = "任务已完成") {
        manager.cancel(RUNNING_ID)
        if (!enabled || !canPost()) return
        ensureChannels()
        notifyGuarded(
            COMPLETED_ID,
            base(NotificationChannelIds.TASKS, "Dafeiyu-GO", detail)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun clearRunning() {
        manager.cancel(RUNNING_ID)
    }

    /** A reminder never executes a notice action or downloads a file. */
    fun postEvent(event: NotificationEventPolicy.Event) {
        val preferences = app.getSharedPreferences("dafeiyu_settings", Context.MODE_PRIVATE)
        if (!preferences.getBoolean("notifications_enabled", true) ||
            !preferences.getBoolean(event.category.preference, true) || !canPost()) return
        ensureChannels()
        notifyGuarded(EVENT_ID,
            base(event.category.channel, event.title.take(160), event.detail.take(320)).setAutoCancel(true).build(),
            "event-${event.id}")
    }

    private fun requestLiveUpdate(notification: Notification): Notification {
        if (android.os.Build.VERSION.SDK_INT < 36) return notification
        // Public API 36 method accessed reflectively while also building with
        // SDK 35. Promotion remains subject to platform and user settings.
        return runCatching {
            val builder = Notification.Builder.recoverBuilder(app, notification)
            Notification.Builder::class.java.getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                .invoke(builder, true)
            builder.build()
        }.getOrDefault(notification)
    }

    fun postUpdate(version: String, downloaded: Boolean) {
        val preferences = app.getSharedPreferences("dafeiyu_settings", Context.MODE_PRIVATE)
        if (!preferences.getBoolean("notifications_enabled", true) ||
            !preferences.getBoolean("message_notifications", true) || !canPost()) return
        val deliveryKey = "$version:${if (downloaded) "ready" else "available"}"
        val delivered = app.getSharedPreferences("android_update_notifications", Context.MODE_PRIVATE)
        if (delivered.getString("last_delivered", null) == deliveryKey) return
        ensureChannels()
        val posted = notifyGuarded(UPDATE_ID, base(NotificationChannelIds.TASKS, "Dafeiyu-GO 有新版本 $version",
            if (downloaded) "已下载并通过校验，打开更新页确认安装。" else "打开更新页查看更新内容和下载。").setAutoCancel(true).build())
        if (posted) delivered.edit().putString("last_delivered", deliveryKey).apply()
    }

    @SuppressLint("MissingPermission")
    private fun notifyGuarded(id: Int, notification: Notification, tag: String? = null): Boolean = try {
        // Permission may be revoked after canPost(). A rejected post must not
        // consume the update reminder, so it can be retried after permission returns.
        if (tag == null) manager.notify(id, notification) else manager.notify(tag, id, notification)
        true
    } catch (_: SecurityException) {
        false
    }

    private fun canPost(): Boolean = NotificationManagerCompat.from(app).areNotificationsEnabled() &&
        (android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED)

    private fun base(channel: String, title: String, text: String): NotificationCompat.Builder {
        val intent = Intent(app, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            app,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(app, channel)
            .setSmallIcon(com.dafeiyu.mobile.R.drawable.sf_bell)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
    }

    companion object {
        private const val RUNNING_ID = 10_241
        private const val COMPLETED_ID = 10_242
        private const val UPDATE_ID = 10_243
        private const val EVENT_ID = 10_244
    }
}

