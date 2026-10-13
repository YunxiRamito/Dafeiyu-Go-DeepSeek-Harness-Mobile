package android.system

import java.nio.file.Files
import java.nio.file.Path

/** JVM fixture only. File identity/link checks are real; Android chmod/SELinux are not exercised. */
class ErrnoException(operation: String, @JvmField val errno: Int) : Exception(operation)
class StructStat(@JvmField val st_mode: Int)
object OsConstants {
    const val ENOENT = 2
    @JvmStatic fun S_ISLNK(mode: Int): Boolean = mode and 0xf000 == 0xa000
}
object Os {
    @JvmStatic fun lstat(value: String): StructStat {
        val path = Path.of(value)
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw ErrnoException("lstat", OsConstants.ENOENT)
        return StructStat(if (Files.isSymbolicLink(path)) 0xa000 else if (Files.isDirectory(path)) 0x4000 else 0x8000)
    }
    @JvmStatic fun stat(value: String): StructStat = lstat(value)
    @JvmStatic fun chmod(value: String, mode: Int) { check(Files.exists(Path.of(value)) && mode in 0..0x1ff) }
    @JvmStatic fun link(source: String, destination: String) { Files.createLink(Path.of(destination), Path.of(source)) }
    @JvmStatic fun symlink(source: String, destination: String) { Files.createSymbolicLink(Path.of(destination), Path.of(source)) }
    @JvmStatic fun readlink(value: String): String = Files.readSymbolicLink(Path.of(value)).toString()
}
