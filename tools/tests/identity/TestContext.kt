package android.content

import java.io.File

/** In-memory prefs preserve partial records for production identity failure checks. */
class Context(val noBackupFilesDir: File = File(".")) {
    val applicationContext: Context get() = this
    private val stores = mutableMapOf<String, SharedPreferences>()
    @Synchronized fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        stores.getOrPut(name) { SharedPreferences() }
    companion object { const val MODE_PRIVATE = 0 }
}

class SharedPreferences {
    private val values = mutableMapOf<String, String>()
    var failCommit = false
    @Synchronized fun getString(name: String, fallback: String?): String? = values[name] ?: fallback
    @Synchronized fun contains(name: String) = values.containsKey(name)
    @Synchronized fun snapshot(): Map<String, String> = values.toMap()
    fun edit() = Editor()
    inner class Editor {
        private val changes = mutableMapOf<String, String?>()
        fun putString(name: String, value: String): Editor { changes[name] = value; return this }
        fun remove(name: String): Editor { changes[name] = null; return this }
        fun commit(): Boolean = synchronized(this@SharedPreferences) {
            if (failCommit) return@synchronized false
            changes.forEach { (name, value) -> if (value == null) values.remove(name) else values[name] = value }
            true
        }
    }
}
