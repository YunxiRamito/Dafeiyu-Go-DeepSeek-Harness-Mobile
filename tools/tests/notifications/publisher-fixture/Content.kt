@file:Suppress("UNUSED_PARAMETER")

package android.content

import android.app.NotificationManager
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat

/** Memory-only platform substitute. It never contacts Android or changes permission. */
class Context {
    val applicationContext get() = this
    val packageName = "notification-publisher-fixture"
    val packageManager = PackageManager()
    var permissionGranted = true
    var notificationsEnabled = true
    val platformManager = NotificationManager(this)
    val compatManager = NotificationManagerCompat(this)
    private val preferences = mutableMapOf<String, SharedPreferences>()
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        preferences.getOrPut(name) { SharedPreferences() }
    fun <T> getSystemService(type: Class<T>): T = type.cast(platformManager)
    companion object { const val MODE_PRIVATE = 0 }
}

class SharedPreferences {
    private val values = mutableMapOf<String, Any>()
    var writes = 0
        private set
    fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
    fun getString(key: String, default: String?) = values[key] as? String ?: default
    fun edit() = Editor()
    inner class Editor {
        private val pending = mutableMapOf<String, Any>()
        fun putString(key: String, value: String) = apply { pending[key] = value }
        fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        fun apply() { values.putAll(pending); writes++ }
    }
}

class Intent(context: Context, target: Class<*>)
