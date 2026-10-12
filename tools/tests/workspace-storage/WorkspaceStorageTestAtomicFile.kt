package android.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Minimal JVM substitute. These tests cover WorkspaceStore's JSON and size behavior;
 * this stub does not prove Android AtomicFile crash durability.
 */
class AtomicFile(val baseFile: File) {
    companion object {
        var failStartFor: String? = null
        var failFinishFor: String? = null
        var failDeleteFor: String? = null
        @Volatile var beforeStartWrite: ((File) -> Unit)? = null
    }
    private val backup = File(baseFile.path + ".bak")
    fun openRead(): FileInputStream {
        if (backup.exists()) {
            baseFile.delete()
            check(backup.renameTo(baseFile))
        }
        return FileInputStream(baseFile)
    }
    fun startWrite(): FileOutputStream {
        beforeStartWrite?.invoke(baseFile)
        check(baseFile.name != failStartFor) { "injected startWrite failure" }
        if (baseFile.exists() && !backup.exists()) check(baseFile.renameTo(backup))
        return FileOutputStream(baseFile)
    }
    fun finishWrite(output: FileOutputStream) {
        output.close()
        check(baseFile.name != failFinishFor) { "injected finishWrite failure" }
        backup.delete()
    }
    fun failWrite(output: FileOutputStream) {
        output.close()
        baseFile.delete()
        if (backup.exists()) check(backup.renameTo(baseFile))
    }
    fun delete() {
        if (baseFile.name == failDeleteFor) return
        baseFile.delete()
        backup.delete()
    }
}

