package com.dafeiyu.mobile.data

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

data class WorkspaceFileRemovalResult(
    val deletedPaths: List<String>,
    val preservedExternalUri: String?,
    val error: String? = null,
) {
    val completed: Boolean get() = error == null
}

/** SAF trees are references. Only these exact application-private working copies are deletable. */
internal fun workspaceOwnedDirectories(root: File, workspace: Workspace): List<File> {
    require(workspace.id.matches(Regex("[a-zA-Z0-9-]+"))) { "工作区身份无效，已保留文件" }
    val linuxCopy = File(File(requireNotNull(root.parentFile), "linux-workspaces"), workspace.id)
    return if (workspace.uri != null) listOf(linuxCopy)
    else listOf(File(root, if (workspace.id == "default") "默认工作区" else workspace.id), linuxCopy)
}

/** Call only after stopping the actual task processes and committing removal of the live record. */
internal fun deleteOwnedWorkspaceDirectories(root: File, workspace: Workspace): WorkspaceFileRemovalResult {
    val deleted = mutableListOf<String>()
    return try {
        val targets = workspaceOwnedDirectories(root, workspace)
        // Validate both roots before beginning a potentially partial removal.
        targets.forEach { validateOwnedTarget(it) }
        for (target in targets) {
            val paths = collectRemovalPaths(target)
            for (path in paths.asReversed()) {
                validateOwnedTarget(target)
                validateParentChain(target.toPath(), path)
                Files.deleteIfExists(path)
            }
            deleted += target.absolutePath
        }
        WorkspaceFileRemovalResult(deleted.toList(), workspace.uri)
    } catch (error: Exception) {
        WorkspaceFileRemovalResult(deleted.toList(), workspace.uri,
            error.message ?: "应用内目录未能完全删除，部分文件已保留")
    }
}

private fun validateOwnedTarget(target: File) {
    val base = requireNotNull(target.parentFile)
    check(base.canonicalFile == base.absoluteFile &&
        !Files.isSymbolicLink(base.toPath())) { "工作区根目录发生变化，已保留文件" }
    check(target.canonicalFile.parentFile == base.canonicalFile &&
        !Files.isSymbolicLink(target.toPath())) { "工作区路径不安全，已保留文件" }
    check(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS) ||
        Files.isDirectory(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        "工作区路径不是目录，已保留文件"
    }
}

private fun collectRemovalPaths(target: File): List<Path> {
    val root = target.toPath()
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return emptyList()
    val paths = mutableListOf<Path>()
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            check(root.relativize(dir).nameCount <= 128) { "目录层级过深，已保留文件" }
            check(paths.size < 100_000) { "文件数量过多，已保留文件" }
            validateParentChain(root, dir)
            paths.add(dir)
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            check(paths.size < 100_000) { "文件数量过多，已保留文件" }
            check(attrs.isRegularFile || attrs.isSymbolicLink) { "目录包含特殊文件，已保留文件" }
            validateParentChain(root, file)
            // Links are unlinked as entries; their targets are never visited or deleted.
            paths.add(file)
            return FileVisitResult.CONTINUE
        }
    })
    return paths
}

private fun validateParentChain(root: Path, entry: Path) {
    check(entry.startsWith(root)) { "工作区路径越界，已保留文件" }
    var current: Path? = entry.parent
    while (current != null && current.startsWith(root)) {
        check(!Files.isSymbolicLink(current) &&
            Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) { "工作区目录发生变化，已保留文件" }
        if (current == root) break
        current = current.parent
    }
}
