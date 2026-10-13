package android.system

import java.io.FileDescriptor
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap

/** JVM fixture only. Link identity is real; directory fsync is an injected
 * acknowledgment, not evidence about Android filesystem durability. */
class ErrnoException(operation: String, @JvmField val errno: Int) : Exception(operation)
class StructStat(@JvmField val st_mode: Int)
object OsConstants {
    const val ENOENT = 2
    const val O_RDONLY = 0
    const val O_CLOEXEC = 0x80000
    @JvmStatic fun S_ISLNK(mode: Int): Boolean = mode and 0xf000 == 0xa000
    @JvmStatic fun S_ISDIR(mode: Int): Boolean = mode and 0xf000 == 0x4000
}
object Os {
    @JvmField var failFsync = false
    @JvmField var directorySyncs = 0
    private val descriptors = IdentityHashMap<FileDescriptor, Path>()
    @JvmStatic fun lstat(value: String): StructStat {
        val path = Path.of(value)
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw ErrnoException("lstat", OsConstants.ENOENT)
        return StructStat(if (Files.isSymbolicLink(path)) 0xa000 else if (Files.isDirectory(path)) 0x4000 else 0x8000)
    }
    @JvmStatic fun stat(value: String): StructStat = lstat(value)
    @JvmStatic fun open(value: String, flags: Int, mode: Int): FileDescriptor {
        val path = Path.of(value)
        check(Files.isDirectory(path) && flags == OsConstants.O_CLOEXEC && mode == 0)
        return FileDescriptor().also { descriptors[it] = path }
    }
    @JvmStatic fun fstat(value: FileDescriptor): StructStat = lstat(checkNotNull(descriptors[value]).toString())
    @JvmStatic fun fsync(value: FileDescriptor) {
        check(descriptors.containsKey(value))
        if (failFsync) throw ErrnoException("fsync", 5)
        directorySyncs++
    }
    @JvmStatic fun close(value: FileDescriptor) { check(descriptors.remove(value) != null) }
}
