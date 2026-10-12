package android.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** Exercises receipt replacement/rollback; Android fsync behavior still needs device validation. */
class AtomicFile(val baseFile: File) {
    fun openRead(): FileInputStream {
        val backup = File(baseFile.path + ".bak")
        if (backup.exists()) {
            backup.copyTo(baseFile, overwrite = true)
            backup.delete()
        }
        return baseFile.inputStream()
    }
    fun startWrite(): FileOutputStream {
        if (failStart) throw IOException("simulated receipt start failure")
        return File(baseFile.path + ".new").outputStream()
    }
    fun finishWrite(stream: FileOutputStream) {
        stream.close()
        if (failFinish) throw IOException("simulated receipt commit failure")
        File(baseFile.path + ".new").copyTo(baseFile, overwrite = true)
        File(baseFile.path + ".new").delete()
    }
    fun failWrite(stream: FileOutputStream) {
        stream.close()
        File(baseFile.path + ".new").delete()
    }
    companion object {
        var failStart = false
        var failFinish = false
    }
}
