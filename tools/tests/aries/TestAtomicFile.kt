package android.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** Simulates replacement and rollback; Android's actual fsync semantics require a device. */
class AtomicFile(private val baseFile: File) {
    fun openRead(): FileInputStream {
        val backup = File(baseFile.path + ".bak")
        if (backup.exists()) {
            backup.copyTo(baseFile, overwrite = true)
            backup.delete()
        }
        return FileInputStream(baseFile)
    }
    fun startWrite(): FileOutputStream = FileOutputStream(File(baseFile.path + ".new"))
    fun finishWrite(stream: FileOutputStream) {
        stream.close()
        if (failFinish) throw IOException("simulated write failure")
        File(baseFile.path + ".new").copyTo(baseFile, overwrite = true)
        File(baseFile.path + ".new").delete()
    }
    fun failWrite(stream: FileOutputStream) {
        stream.close()
        File(baseFile.path + ".new").delete()
    }
    fun delete() {
        baseFile.delete()
        File(baseFile.path + ".new").delete()
        File(baseFile.path + ".bak").delete()
    }
    companion object {
        var failFinish = false
    }
}
