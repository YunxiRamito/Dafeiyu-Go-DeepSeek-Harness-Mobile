package com.dafeiyu.mobile.data

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarConstants

/** Extracts only locked runtime archives into a private, unpublished candidate. */
internal object LinuxArchiveExtractor {
    private data class Link(val path: String, val target: String, val hard: Boolean)
    private data class RegularWitness(val type: Byte, val mode: Int, val size: Long, val digest: ByteArray)

    fun extract(input: InputStream, root: File, maximumBytes: Long, checkCancelled: () -> Unit,
        selected: (String) -> Boolean = { true }, progress: (Long) -> Unit = {}) =
        extract(input, root, maximumBytes, checkCancelled, selected, emptySet(), progress)

    fun extract(input: InputStream, root: File, maximumBytes: Long, checkCancelled: () -> Unit,
        selected: (String) -> Boolean = { true }, identicalRegularDuplicates: Set<String>, progress: (Long) -> Unit = {}) {
        require(root.isDirectory && !isSymlink(root)) { "解包目录无效" }
        val boundary = root.canonicalFile
        val duplicatePaths = identicalRegularDuplicates.toSet()
        require(duplicatePaths.size <= 8 && duplicatePaths.all { it.isNotEmpty() && entryPath(it) == it }) {
            "重复归档成员策略无效"
        }
        val names = HashSet<String>()
        val witnesses = HashMap<String, RegularWitness>()
        val links = ArrayList<Link>()
        var expanded = 0L
        var count = 0
        var duplicates = 0
        GZIPInputStream(input.buffered(64 * 1024)).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                while (true) {
                    checkCancelled()
                    val entry = tar.nextEntry ?: break
                    check(++count <= 60_000) { "归档成员过多" }
                    val path = entryPath(entry.name)
                    if (path.isEmpty()) {
                        require(entry.linkFlag == TarConstants.LF_DIR) { "归档根条目无效" }
                        continue
                    }
                    if (!names.add(path)) {
                        // Opt-in applies only to exact entries of two digest-verified
                        // npm archives. General runtime extraction still refuses duplicates.
                        require(path in duplicatePaths) { "归档路径重复：$path" }
                        require(++duplicates <= 16) { "重复归档成员过多" }
                        val witness = witnesses[path] ?: throw IOException("重复归档成员不是已验证普通文件")
                        require(entry.linkFlag == witness.type && entry.mode == witness.mode && entry.size == witness.size &&
                            !entry.isSparse && entry.isCheckSumOK && tar.canReadEntryData(entry)) { "重复归档成员类型或属性冲突" }
                        expanded = Math.addExact(expanded, entry.size)
                        require(expanded <= maximumBytes) { "归档展开大小超过锁定上限" }
                        val file = File(boundary, path)
                        parent(boundary, file)
                        require(!isSymlink(file) && file.isFile && file.length() == witness.size && within(boundary, file.canonicalFile)) {
                            "重复归档成员目标已变化"
                        }
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(64 * 1024)
                        var remaining = entry.size
                        while (remaining > 0) {
                            checkCancelled()
                            val read = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (read <= 0) throw IOException("重复归档成员不完整")
                            digest.update(buffer, 0, read)
                            remaining -= read
                        }
                        require(MessageDigest.isEqual(witness.digest, digest.digest())) { "重复归档成员内容冲突" }
                        val actual = MessageDigest.getInstance("SHA-256")
                        file.inputStream().use { source ->
                            while (true) {
                                checkCancelled()
                                val read = source.read(buffer)
                                if (read < 0) break
                                actual.update(buffer, 0, read)
                            }
                        }
                        require(MessageDigest.isEqual(witness.digest, actual.digest())) { "重复归档成员目标内容已变化" }
                        progress(expanded)
                        continue
                    }
                    if (!selected(path)) continue
                    val file = File(boundary, path)
                    when {
                        entry.linkFlag == TarConstants.LF_DIR -> {
                            val parent = parent(boundary, file)
                            require(!isSymlink(file)) { "目录覆盖符号链接：$path" }
                            require(file.isDirectory || file.mkdir()) { "无法创建归档目录：$path" }
                            require(parent.isDirectory)
                            // Keep directories traversable until the final install probes finish.
                            Os.chmod(file.path, (entry.mode and 0x1ff) or 0x1c0)
                        }
                        entry.linkFlag == TarConstants.LF_NORMAL || entry.linkFlag == TarConstants.LF_OLDNORM -> {
                            require(entry.size >= 0 && entry.size <= 160L * 1024 * 1024) { "归档文件过大" }
                            expanded = Math.addExact(expanded, entry.size)
                            require(expanded <= maximumBytes) { "归档展开大小超过锁定上限" }
                            parent(boundary, file)
                            unlinkFile(file)
                            val digest = if (path in duplicatePaths) MessageDigest.getInstance("SHA-256") else null
                            if (digest != null) require(!entry.isSparse && entry.isCheckSumOK && tar.canReadEntryData(entry)) {
                                "重复归档成员策略仅支持普通文件"
                            }
                            file.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var remaining = entry.size
                                while (remaining > 0) {
                                    checkCancelled()
                                    val read = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                    if (read <= 0) throw IOException("归档文件不完整：$path")
                                    output.write(buffer, 0, read)
                                    digest?.update(buffer, 0, read)
                                    remaining -= read
                                }
                            }
                            Os.chmod(file.path, (entry.mode and 0x1ff) or 0x180)
                            digest?.let { witnesses[path] = RegularWitness(entry.linkFlag, entry.mode, entry.size, it.digest()) }
                            progress(expanded)
                        }
                        entry.linkFlag == TarConstants.LF_SYMLINK -> links += Link(path, guestLink(path, entry.linkName), false)
                        entry.linkFlag == TarConstants.LF_LINK -> links += Link(path, entryPath(entry.linkName), true)
                        else -> throw IOException("归档包含不支持的特殊文件：$path")
                    }
                }
            }
        }
        // Publish links after file writes, so an earlier archive link cannot redirect a later write.
        for (link in links.filter { it.hard }) {
            checkCancelled()
            val destination = File(boundary, link.path)
            val target = File(boundary, link.target)
            parent(boundary, destination)
            require(!isSymlink(target) && target.isFile && within(boundary, target.canonicalFile)) { "硬链接目标无效" }
            unlinkFile(destination)
            try { Os.link(target.path, destination.path) }
            catch (failure: android.system.ErrnoException) {
                // Android's untrusted-app SELinux domain may refuse link(2).
                // Materialize only the already admitted regular target; never
                // follow an archive link or relax the candidate boundary.
                if (failure.errno !in setOf(OsConstants.EACCES, OsConstants.EPERM, OsConstants.EXDEV, OsConstants.EOPNOTSUPP)) throw failure
                val size = target.length()
                expanded = Math.addExact(expanded, size)
                require(expanded <= maximumBytes) { "硬链接副本超过锁定展开上限" }
                require(!isSymlink(target) && target.isFile && within(boundary, target.canonicalFile)) { "硬链接目标已变化" }
                target.inputStream().use { source -> destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = size
                    while (remaining > 0) {
                        checkCancelled()
                        val read = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        require(read > 0) { "硬链接目标文件不完整" }
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                    require(source.read() < 0) { "硬链接目标大小已变化" }
                } }
                Os.chmod(destination.path, (Os.stat(target.path).st_mode and 0x1ff) or 0x180)
                progress(expanded)
            }
        }
        for (link in links.filter { !it.hard }) {
            checkCancelled()
            val destination = File(boundary, link.path)
            parent(boundary, destination)
            unlinkFile(destination)
            Os.symlink(link.target, destination.path)
            require(within(boundary, destination.canonicalFile)) { "符号链接越出运行环境" }
        }
    }

    /** Clones an active slot without following symbolic links, including during cancellation cleanup. */
    fun copyTree(source: File, destination: File, checkCancelled: () -> Unit) {
        checkCancelled()
        require(destination.isDirectory || destination.mkdirs())
        val root = source.canonicalFile
        fun copy(directory: File, output: File) {
            for (file in directory.listFiles() ?: throw IOException("无法读取运行环境")) {
                checkCancelled()
                val target = File(output, file.name)
                if (isSymlink(file)) {
                    require(within(root, file.canonicalFile)) { "已有运行环境含外部链接" }
                    Os.symlink(Os.readlink(file.path), target.path)
                } else if (file.isDirectory) {
                    require(target.mkdir())
                    copy(file, target)
                    Os.chmod(target.path, (Os.stat(file.path).st_mode and 0x1ff) or 0x1c0)
                } else if (file.isFile) {
                    file.inputStream().use { input -> target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkCancelled()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                    } }
                    Os.chmod(target.path, (Os.stat(file.path).st_mode and 0x1ff) or 0x180)
                } else throw IOException("已有运行环境含特殊文件")
            }
        }
        copy(source, destination)
    }

    fun removeTree(file: File) {
        if (isSymlink(file)) {
            check(file.delete()) { "无法删除候选链接" }
            return
        }
        if (!file.exists()) return
        if (file.isDirectory) for (child in file.listFiles() ?: throw IOException("无法清理候选目录")) removeTree(child)
        check(file.delete()) { "无法清理候选文件" }
    }

    fun treeBytes(directory: File, checkCancelled: () -> Unit): Long {
        var bytes = 0L
        var count = 0
        fun inspect(parent: File) {
            for (file in parent.listFiles() ?: throw IOException("无法读取运行环境")) {
                checkCancelled()
                require(++count <= 120_000) { "运行环境文件数量超过安装上限" }
                if (isSymlink(file)) continue
                if (file.isDirectory) inspect(file)
                else {
                    require(file.isFile()) { "运行环境含特殊文件" }
                    bytes = Math.addExact(bytes, file.length())
                }
            }
        }
        inspect(directory)
        return bytes
    }

    private fun entryPath(value: String): String {
        require(value.length <= 4096 && '\u0000' !in value && '\\' !in value && !value.startsWith('/')) { "归档路径无效" }
        val parts = value.split('/').filter { it.isNotEmpty() && it != "." }
        require(parts.none { it == ".." }) { "归档路径越界" }
        return parts.joinToString("/")
    }

    private fun guestLink(path: String, value: String): String {
        require(value.isNotEmpty() && value.length <= 4096 && '\u0000' !in value && '\\' !in value) { "符号链接无效" }
        val base = path.split('/').dropLast(1)
        val resolved = if (value.startsWith('/')) ArrayList<String>() else ArrayList(base)
        for (part in value.split('/')) when (part) {
            "", "." -> Unit
            ".." -> { require(resolved.isNotEmpty()) { "符号链接越界" }; resolved.removeAt(resolved.lastIndex) }
            else -> resolved.add(part)
        }
        // Absolute guest links become relative host links, keeping every host filesystem operation private.
        val common = base.zip(resolved).takeWhile { it.first == it.second }.size
        return (List(base.size - common) { ".." } + resolved.drop(common)).joinToString("/").ifEmpty { "." }
    }

    private fun parent(root: File, file: File): File {
        val parent = requireNotNull(file.parentFile)
        var ancestor: File? = parent
        while (ancestor != null && ancestor != root) {
            require(!isSymlink(ancestor)) { "归档父目录不能为符号链接" }
            ancestor = ancestor.parentFile
        }
        require(ancestor == root) { "归档父目录越界" }
        require(within(root, parent.canonicalFile)) { "归档父目录越界" }
        require(parent.isDirectory || parent.mkdirs()) { "无法创建归档父目录" }
        require(within(root, parent.canonicalFile)) { "归档父目录越界" }
        return parent
    }

    private fun unlinkFile(file: File) {
        if (isSymlink(file) || file.exists()) {
            require(!file.isDirectory || isSymlink(file)) { "归档文件覆盖目录" }
            require(file.delete()) { "无法替换归档文件" }
        }
    }

    fun isSymlink(file: File): Boolean = try {
        OsConstants.S_ISLNK(Os.lstat(file.path).st_mode)
    } catch (missing: android.system.ErrnoException) {
        if (missing.errno != OsConstants.ENOENT) throw missing
        false
    }

    private fun within(root: File, path: File) = path == root || path.path.startsWith(root.path + File.separator)
}
