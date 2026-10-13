package android.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class AtomicFile(private val file: File) {
    fun openRead() = FileInputStream(file)
    fun startWrite() = FileOutputStream(file)
    fun finishWrite(output: FileOutputStream) = output.close()
    fun failWrite(output: FileOutputStream) = output.close()
}
