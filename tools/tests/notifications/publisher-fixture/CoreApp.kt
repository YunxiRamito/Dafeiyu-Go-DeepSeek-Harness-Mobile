@file:Suppress("UNUSED_PARAMETER")

package androidx.core.app

import android.app.Notification
import android.app.PendingIntent
import android.content.Context

class NotificationCompat {
    class Builder(context: Context, private val channel: String) {
        private var title = ""
        private var text = ""
        private var ongoing = false
        fun setContentTitle(value: String) = apply { title = value }
        fun setContentText(value: String) = apply { text = value }
        fun setOngoing(value: Boolean) = apply { ongoing = value }
        fun setSmallIcon(value: Int) = this
        fun setContentIntent(value: PendingIntent) = this
        fun setPriority(value: Int) = this
        fun setOnlyAlertOnce(value: Boolean) = this
        fun setCategory(value: String) = this
        fun setProgress(max: Int, progress: Int, indeterminate: Boolean) = this
        fun setAutoCancel(value: Boolean) = this
        fun build() = Notification(channel, title, text, ongoing)
    }
    companion object {
        const val CATEGORY_PROGRESS = "progress"
        const val PRIORITY_DEFAULT = 0
    }
}

/** Injects revocation at the notify boundary, after the production preflight. */
class NotificationManagerCompat(private val context: Context) {
    data class Call(val tag: String?, val id: Int, val notification: Notification)
    val attempts = mutableListOf<Call>()
    val accepted = mutableListOf<Call>()
    val cancelled = mutableListOf<Int>()
    var beforeNotify: (() -> Unit)? = null
    var failure: RuntimeException? = null
    fun areNotificationsEnabled() = context.notificationsEnabled
    fun notify(id: Int, notification: Notification) = record(null, id, notification)
    fun notify(tag: String, id: Int, notification: Notification) = record(tag, id, notification)
    fun cancel(id: Int) { cancelled += id }
    private fun record(tag: String?, id: Int, notification: Notification) {
        val call = Call(tag, id, notification)
        attempts += call
        beforeNotify?.also { beforeNotify = null }?.invoke()
        if (android.os.Build.VERSION.SDK_INT >= 33 && !context.permissionGranted) {
            throw SecurityException("fixture: permission revoked after preflight")
        }
        failure?.let { throw it }
        accepted += call
    }
    companion object {
        fun from(context: Context) = context.compatManager
    }
}
