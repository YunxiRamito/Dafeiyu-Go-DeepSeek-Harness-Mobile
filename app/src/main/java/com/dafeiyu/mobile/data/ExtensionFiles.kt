package com.dafeiyu.mobile.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream

/** Shared bounded import rules. Archives are data, and importing them never executes their contents. */
internal object ExtensionFiles {
    const val MAX_PACKAGE_BYTES = 128L * 1024 * 1024
    const val MAX_FILES = 5_000

    fun relative(value: String): String {
        require(value.isNotEmpty() && value.length <= 1_024 && '\\' !in value && ':' !in value &&
            !value.startsWith('/') && value.none { it.code < 32 || it.code == 127 }) { "扩展路径无效" }
        val parts = value.removeSuffix("/").split('/')
        require(parts.all { it.isNotEmpty() && it !in setOf(".", "..", ".git") && it.length <= 240 }) { "扩展路径越界" }
        return parts.joinToString("/")
    }

    fun directory(file: File) {
        require(!Files.isSymbolicLink(file.toPath())) { "扩展目录不能是符号链接" }
        require(file.isDirectory || file.mkdirs()) { "无法创建扩展目录" }
    }

    fun resolve(root: File, value: String): File {
        val path = relative(value)
        require(root.isDirectory && !Files.isSymbolicLink(root.toPath()))
        val target = File(root, path)
        var current = root
        path.split('/').forEach { part ->
            current = File(current, part)
            require(!Files.isSymbolicLink(current.toPath())) { "扩展路径包含符号链接" }
        }
        require(target.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "扩展路径越界" }
        return target
    }

    fun bytes(input: InputStream, maximum: Long): ByteArray {
        require(maximum in 1..MAX_PACKAGE_BYTES)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= maximum) { "扩展内容超过上限" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun prefix(input: InputStream, maximum: Int): ByteArray {
        require(maximum >= 0)
        val bytes = ByteArray(maximum)
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) break
            if (count == 0) continue
            offset += count
        }
        return if (offset == bytes.size) bytes else bytes.copyOf(offset)
    }

    fun text(file: File, maximum: Long): String {
        require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() <= maximum) { "扩展说明不可读取或超过上限" }
        return utf8(file.inputStream().use { bytes(it, maximum) })
    }

    fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    fun atomic(file: File, contents: String) {
        directory(requireNotNull(file.parentFile))
        require(!Files.isSymbolicLink(file.toPath()))
        val staged = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.new")
        try {
            staged.outputStream().use { output -> output.write(contents.toByteArray()); output.fd.sync() }
            Files.move(staged.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { staged.delete() }
    }

    suspend fun copy(input: InputStream, file: File, maximum: Long): Long {
        directory(requireNotNull(file.parentFile))
        require(!Files.isSymbolicLink(file.toPath()))
        var count = 0L
        file.outputStream().use { output ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                count += read
                require(count <= maximum) { "扩展包超过上限" }
                output.write(buffer, 0, read)
            }
            output.fd.sync()
        }
        return count
    }

    suspend fun unpack(archive: File, target: File): File {
        directory(target)
        val names = HashSet<String>()
        var total = 0L
        var files = 0
        suspend fun entry(name: String, size: Long, isDirectory: Boolean, input: InputStream) {
            currentCoroutineContext().ensureActive()
            val relative = relative(name)
            require(names.add(relative.lowercase(Locale.ROOT))) { "扩展包有重复路径" }
            require(++files <= MAX_FILES && size in 0..MAX_PACKAGE_BYTES) { "扩展包条目超过上限" }
            val destination = resolve(target, relative)
            if (isDirectory) directory(destination)
            else {
                val copied = copy(input, destination, minOf(MAX_PACKAGE_BYTES - total, MAX_PACKAGE_BYTES))
                require(copied == size) { "扩展包文件长度不匹配" }
                total += copied
            }
        }
        val magic = archive.inputStream().use { prefix(it, 4) }
        when {
            magic.size >= 2 && magic[0] == 0x50.toByte() && magic[1] == 0x4b.toByte() ->
                ZipArchiveInputStream(archive.inputStream()).use { input ->
                    while (true) {
                        val value = input.nextEntry ?: break
                        require(!value.isUnixSymlink && (value.unixMode == 0 || value.unixMode and 0xf000 in setOf(0x8000, 0x4000))) {
                            "扩展包包含链接或特殊文件"
                        }
                        require(input.canReadEntryData(value)) { "扩展包包含不支持的加密或压缩格式" }
                        // ZIP streaming entries can use a trailing descriptor; count the actual bytes instead.
                        val name = relative(value.name)
                        require(names.add(name.lowercase(Locale.ROOT)) && ++files <= MAX_FILES) { "扩展包有重复或过多条目" }
                        val destination = resolve(target, name)
                        if (value.isDirectory) directory(destination)
                        else {
                            val copied = copy(input, destination, MAX_PACKAGE_BYTES - total)
                            require(value.size < 0 || value.size == copied) { "扩展包文件长度不匹配" }
                            total += copied
                        }
                    }
                }
            magic.size >= 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte() ->
                TarArchiveInputStream(GZIPInputStream(archive.inputStream())).use { input ->
                    while (true) {
                        val value = input.nextEntry ?: break
                        require(value.linkFlag in setOf(TarConstants.LF_NORMAL, TarConstants.LF_OLDNORM, TarConstants.LF_DIR)) {
                            "扩展包包含链接或特殊文件"
                        }
                        entry(value.name, value.size, value.isDirectory, input)
                    }
                }
            else -> throw IllegalArgumentException("请选择 ZIP 或 tar.gz / tgz 扩展包")
        }
        require(files > 0) { "扩展包为空" }
        return target
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun deleteOwned(root: File, file: File) {
        require(file != root && file.absoluteFile.normalize().path.startsWith(root.absoluteFile.normalize().path + File.separator))
        if (Files.isSymbolicLink(file.toPath())) { Files.delete(file.toPath()); return }
        if (file.isDirectory) file.listFiles()?.forEach { deleteOwned(root, it) }
        require(!file.exists() || file.delete()) { "无法清理扩展暂存内容" }
    }
}
