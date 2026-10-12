package android.content

import java.io.File

/** Isolated JVM fixture, not an Android runtime implementation. */
class Context(val noBackupFilesDir: File) {
    val applicationContext: Context get() = this
}
