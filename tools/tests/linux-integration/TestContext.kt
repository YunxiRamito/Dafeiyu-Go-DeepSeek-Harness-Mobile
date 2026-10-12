package android.content

import java.io.File

/** JVM transport fixture only; it does not simulate Android permissions, TLS or process execution. */
class Context(val filesDir: File) {
    val applicationContext: Context get() = this
    val noBackupFilesDir: File get() = File(filesDir, "no-backup")
    val packageName: String get() = "com.dafeiyu.mobile.preview"
    val resources: android.content.res.Resources get() = error("Backend TLS is outside this offline fixture")
}
