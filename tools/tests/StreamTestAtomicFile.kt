package android.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** JVM file fixture; these tests verify WorkspaceStore JSON, not Android atomic-write guarantees. */
class AtomicFile(val baseFile: File) {
    fun openRead() = FileInputStream(baseFile)
    fun startWrite() = FileOutputStream(baseFile)
    fun finishWrite(output: FileOutputStream) { output.close() }
    fun failWrite(output: FileOutputStream) { output.close() }
    fun delete() { baseFile.delete() }
}
