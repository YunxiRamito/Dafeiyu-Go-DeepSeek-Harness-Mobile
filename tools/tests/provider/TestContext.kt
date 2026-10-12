package android.content

import java.io.File

/** JVM fixture with atomic preference snapshots and synchronous change notification. */
class Context(val noBackupFilesDir: File) {
    val applicationContext get() = this
    val preferences = SharedPreferences()
    fun getSharedPreferences(name: String, mode: Int) = preferences
    companion object { const val MODE_PRIVATE = 0 }
}

class SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    private val listeners = mutableSetOf<OnSharedPreferenceChangeListener>()
    private val notifications = mutableListOf<() -> Unit>()
    var deferNotifications = false
    val listenerCount get() = synchronized(this) { listeners.size }
    var afterRead: (() -> Unit)? = null
    val all: Map<String, *> get() {
        val snapshot = synchronized(this) { values.toMap() }
        afterRead?.also { afterRead = null }?.invoke()
        return snapshot
    }
    fun put(key: String, value: String) {
        val observers = synchronized(this) {
            if (values[key] == value) return
            values[key] = value
            listeners.toList()
        }
        val notify = { observers.forEach { it.onSharedPreferenceChanged(this, key) } }
        if (deferNotifications) synchronized(this) { notifications.add(notify) } else notify()
    }
    fun notifyPending() {
        val queued = synchronized(this) { notifications.toList().also { notifications.clear() } }
        queued.forEach { it() }
    }
    fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) {
        synchronized(this) { listeners.add(listener) }
    }
    fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) {
        synchronized(this) { listeners.remove(listener) }
    }
    fun interface OnSharedPreferenceChangeListener {
        fun onSharedPreferenceChanged(preferences: SharedPreferences, key: String?)
    }
}
