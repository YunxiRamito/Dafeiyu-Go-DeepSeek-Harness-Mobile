package com.dafeiyu.mobile.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns only the app-created temporary cache directories. Workspace data lives in
 * filesDir and SAF content lives outside this store by design.
 */
object CacheStore {
    private const val AttachmentDirectory = "attachment-previews"
    private const val FileProviderDirectory = "attachment_previews"
    private const val DefaultMaxAgeMillis = 24L * 60L * 60L * 1000L
    private const val DefaultMaxFilesPerDirectory = 12

    // Keep this list explicit so an unrelated library cache is never deleted by this page.
    private val managedDirectoryNames = listOf(
        AttachmentDirectory,
        "web-previews",
        "qr-previews",
        "network-downloads",
        "runtime-temp",
    )

    data class DirectoryStats(
        val bytes: Long,
        val files: Int,
    )

    data class Stats(
        val totalBytes: Long,
        val totalFiles: Int,
        val byDirectory: Map<String, DirectoryStats>,
        /** Files that could not be inspected while calculating this snapshot. */
        val failures: List<Failure> = emptyList(),
    )

    data class Failure(
        val path: String,
        val reason: String,
    )

    data class CleanupResult(
        val deletedBytes: Long,
        val deletedFiles: Int,
        val failures: List<Failure>,
        val remaining: Stats,
    )

    data class CleanupPolicy(
        val maxAgeMillis: Long = DefaultMaxAgeMillis,
        val maxFilesPerDirectory: Int = DefaultMaxFilesPerDirectory,
    )

    suspend fun stats(
        context: Context,
        protectedPaths: Collection<String> = emptyList(),
    ): Stats = withContext(Dispatchers.IO) {
        collectStats(context, protectedPaths)
    }

    /** Removes expired or excess unreferenced files and keeps recent files bounded. */
    suspend fun cleanupExpired(
        context: Context,
        protectedPaths: Collection<String> = emptyList(),
        policy: CleanupPolicy = CleanupPolicy(),
    ): CleanupResult = withContext(Dispatchers.IO) {
        require(policy.maxAgeMillis >= 0L) { "缓存保留时间不能为负数" }
        require(policy.maxFilesPerDirectory >= 0) { "缓存文件数量上限不能为负数" }
        val root = cacheRoot(context)
        val protected = resolveProtectedPaths(context, root, protectedPaths)
        val failures = mutableListOf<Failure>()
        val candidates = managedFiles(root, failures)
        val now = System.currentTimeMillis()
        val deleted = deleteCandidates(
            candidates = candidates,
            protected = protected,
            shouldDelete = { file, unprotectedIndexByDirectory ->
                val expired = now - file.lastModified() >= policy.maxAgeMillis
                val excessive = (unprotectedIndexByDirectory[file] ?: Int.MAX_VALUE) >= policy.maxFilesPerDirectory
                expired || excessive
            },
            failures = failures,
        )
        val remaining = collectStats(context, protectedPaths)
        CleanupResult(
            deletedBytes = deleted.first,
            deletedFiles = deleted.second,
            failures = mergeFailures(failures, remaining.failures),
            remaining = remaining,
        )
    }

    /** Removes every unreferenced file in the managed directories only. */
    suspend fun clear(
        context: Context,
        protectedPaths: Collection<String> = emptyList(),
    ): CleanupResult = withContext(Dispatchers.IO) {
        val root = cacheRoot(context)
        val protected = resolveProtectedPaths(context, root, protectedPaths)
        val failures = mutableListOf<Failure>()
        val deleted = deleteCandidates(
            candidates = managedFiles(root, failures),
            protected = protected,
            shouldDelete = { _, _ -> true },
            failures = failures,
        )
        val remaining = collectStats(context, protectedPaths)
        CleanupResult(
            deletedBytes = deleted.first,
            deletedFiles = deleted.second,
            failures = mergeFailures(failures, remaining.failures),
            remaining = remaining,
        )
    }

    private fun collectStats(context: Context, protectedPaths: Collection<String>): Stats {
        val root = cacheRoot(context)
        val failures = mutableListOf<Failure>()
        val files = managedFiles(root, failures)
        val byDirectory = files.groupBy { directoryName(root, it) }
            .mapValues { (_, entries) ->
                DirectoryStats(
                    bytes = entries.sumOf { it.length() },
                    files = entries.size,
                )
            }
        return Stats(
            totalBytes = byDirectory.values.sumOf { it.bytes },
            totalFiles = byDirectory.values.sumOf { it.files },
            byDirectory = managedDirectoryNames.associateWith { byDirectory[it] ?: DirectoryStats(0L, 0) },
            failures = failures.toList(),
        )
    }

    private fun mergeFailures(first: List<Failure>, second: List<Failure>): List<Failure> =
        (first + second).distinctBy { it.path to it.reason }

    private fun managedFiles(root: File, failures: MutableList<Failure>): List<File> {
        val rootPath = runCatching { root.canonicalPath }.getOrElse {
            failures += Failure(root.path, "无法解析应用缓存目录：${it.message ?: "未知错误"}")
            return emptyList()
        }
        val files = mutableListOf<File>()
        managedDirectoryNames.forEach { name ->
            val directory = File(root, name)
            if (!directory.exists()) return@forEach
            if (!directory.isDirectory) {
                failures += Failure(directory.path, "缓存目录路径不是目录")
                return@forEach
            }
            collectFiles(directory, rootPath, files, failures)
        }
        return files
    }

    private fun collectFiles(
        directory: File,
        rootPath: String,
        output: MutableList<File>,
        failures: MutableList<Failure>,
    ) {
        val children = directory.listFiles()
        if (children == null) {
            failures += Failure(directory.path, "无法读取缓存目录")
            return
        }
        children.forEach { child ->
            val canonical = runCatching { child.canonicalFile }.getOrElse {
                failures += Failure(child.path, "无法解析缓存路径：${it.message ?: "未知错误"}")
                return@forEach
            }
            if (!isWithinRoot(rootPath, canonical.path)) {
                failures += Failure(child.path, "已跳过缓存目录之外的路径")
                return@forEach
            }
            when {
                child.isDirectory -> collectFiles(child, rootPath, output, failures)
                child.isFile -> output += child
            }
        }
    }

    private fun deleteCandidates(
        candidates: List<File>,
        protected: Set<String>,
        shouldDelete: (File, Map<File, Int>) -> Boolean,
        failures: MutableList<Failure>,
    ): Pair<Long, Int> {
        val unprotectedIndexByDirectory = candidates
            .filterNot { canonicalPath(it) in protected }
            .groupBy { it.parentFile?.path.orEmpty() }
            .values
            .flatMap { entries ->
                entries.sortedByDescending { it.lastModified() }.mapIndexed { index, file -> file to index }
            }
            .toMap()
        var deletedBytes = 0L
        var deletedFiles = 0
        candidates.forEach { file ->
            val canonical = canonicalPath(file)
            if (canonical in protected || !shouldDelete(file, unprotectedIndexByDirectory)) return@forEach
            val size = file.length()
            val deleted = runCatching { file.delete() }.getOrElse { error ->
                failures += Failure(file.path, error.message ?: "删除失败")
                false
            }
            if (deleted || !file.exists()) {
                deletedBytes += size
                deletedFiles++
            } else {
                failures += Failure(file.path, "系统未删除该文件")
            }
        }
        return deletedBytes to deletedFiles
    }

    private fun resolveProtectedPaths(
        context: Context,
        root: File,
        values: Collection<String>,
    ): Set<String> {
        val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return emptySet()
        return values.mapNotNull { value ->
            val uri = runCatching { Uri.parse(value) }.getOrNull()
            val file = when {
                uri?.scheme == "file" -> uri.path?.let(::File)
                uri?.scheme == "content" && uri.authority == "${context.packageName}.attachments" -> {
                    val segments = uri.pathSegments
                    if (segments.size == 2 && segments[0] == FileProviderDirectory) {
                        File(root, "$AttachmentDirectory/${segments[1]}")
                    } else null
                }
                uri?.scheme == null || uri.scheme.isNullOrEmpty() -> File(value)
                else -> null
            } ?: return@mapNotNull null
            val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return@mapNotNull null
            canonical.takeIf { isWithinRoot(rootPath, it) }
        }.toSet()
    }

    private fun cacheRoot(context: Context): File = context.cacheDir

    private fun directoryName(root: File, file: File): String {
        val rootPath = root.absolutePath.trimEnd(File.separatorChar) + File.separator
        val relative = file.absolutePath.removePrefix(rootPath)
        return relative.substringBefore(File.separatorChar, relative)
    }

    private fun canonicalPath(file: File): String = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    private fun isWithinRoot(rootPath: String, path: String): Boolean {
        val prefix = rootPath.trimEnd(File.separatorChar) + File.separator
        return path == rootPath || path.startsWith(prefix)
    }
}
