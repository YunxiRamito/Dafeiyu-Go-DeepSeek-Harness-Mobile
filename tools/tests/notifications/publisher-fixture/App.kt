@file:Suppress("UNUSED_PARAMETER")

package android.app

import android.content.Context
import android.content.Intent

data class Notification(
    val channel: String,
    val title: String,
    val text: String,
    val ongoing: Boolean = false,
) {
    class Builder(private val notification: Notification) {
        fun build() = notification
        companion object {
            fun recoverBuilder(context: Context, notification: Notification) = Builder(notification)
        }
    }
}

class PendingIntent {
    companion object {
        const val FLAG_UPDATE_CURRENT = 1
        const val FLAG_IMMUTABLE = 2
        fun getActivity(context: Context, code: Int, intent: Intent, flags: Int) = PendingIntent()
    }
}

class NotificationChannel(val id: String, val name: CharSequence, val importance: Int) {
    var description: String? = null
    private var badge = true
    fun setShowBadge(value: Boolean) { badge = value }
    fun canShowBadge() = badge
}

class NotificationManager(private val context: Context) {
    val channels = mutableMapOf<String, NotificationChannel>()
    fun createNotificationChannels(definitions: List<NotificationChannel>) {
        definitions.forEach { channels.putIfAbsent(it.id, it) }
    }
    fun getNotificationChannel(id: String) = channels[id]
    fun areNotificationsEnabled() = context.notificationsEnabled
    companion object {
        const val IMPORTANCE_NONE = 0
        const val IMPORTANCE_LOW = 2
        const val IMPORTANCE_DEFAULT = 3
    }
}
