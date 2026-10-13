package com.dafeiyu.mobile.data

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class DymRetainedResources(val directories: Map<String, File>, val pcPreferences: JSONObject?, val sharedCredentials: JSONObject? = null)
data class DymRetainedSnapshot(val id: String, val importedAtMillis: Long, val files: Int, val bytes: Long)

/** Opaque user data stays outside installed runtimes until a real DSH restore adapter is available. */
class DymRetainedResourceStore(private val privateRoot: File) {
    private val root = File(privateRoot, "dym-retained")
    private val lock = locks.computeIfAbsent(privateRoot.canonicalPath) { Mutex() }

    suspend fun read(): DymRetainedResources = withContext(Dispatchers.IO) { lock.withLock { readLocked() } }

    /** Each original import remains independently accessible even after a later
     * import or an installed resource takes precedence in the current export. */
    suspend fun snapshots(): List<DymRetainedSnapshot> = withContext(Dispatchers.IO) { lock.withLock {
        owned(root)
        if (!root.exists()) return@withLock emptyList()
        check(root.isDirectory)
        val snapshots = root.listFiles().orEmpty().filter { snapshotPattern.matches(it.name) }
        check(snapshots.size <= MAX_SNAPSHOTS)
        snapshots.map { folder ->
            val files = regularFiles(folder)
            DymRetainedSnapshot(folder.name, folder.lastModified(), files.size, files.sumOf(File::length))
        }.sortedByDescending { it.importedAtMillis }
    } }

    suspend fun readSnapshot(id: String): DymRetainedResources = withContext(Dispatchers.IO) { lock.withLock {
        require(snapshotPattern.matches(id)) { "原始资源标识无效" }
        readSnapshotLocked(id)
    } }

    /** Publishes an immutable snapshot; earlier snapshots are kept so an active export remains valid. */
    suspend fun retain(result: DymImportResult): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            if (result.groupFiles.isEmpty() && result.pcPreferences == null && result.sharedCredentials == null) return@withLock 0
            require(result.stagingRoot.canonicalFile.parentFile == File(privateRoot, "dym-staging").canonicalFile)
            val resources = result.groupFiles.values.flatten()
            check(resources.size <= MAX_ENTRIES)
            owned(root)
            check(root.isDirectory || root.mkdirs())
            val existing = root.listFiles().orEmpty().filter { it.name.startsWith("snapshot-") }
            check(existing.size < MAX_SNAPSHOTS) { "已保留 $MAX_SNAPSHOTS 份 PC 资源，请先导出并整理后再导入资源" }
            val oldSize = existing.sumOf { regularFiles(it).sumOf(File::length) }
            val inputSize = resources.sumOf { resource ->
                stagedFile(result, resource).length()
            }
            check(oldSize + inputSize <= MAX_TOTAL_BYTES) { "保留的 PC 资源超过 2 GiB 上限" }
            val candidate = File(root, ".candidate-${UUID.randomUUID()}")
            check(candidate.mkdir())
            val published = File(root, "snapshot-${UUID.randomUUID()}")
            var pointerCommitted = false
            try {
                for (resource in resources) {
                    currentCoroutineContext().ensureActive()
                    val source = stagedFile(result, resource)
                    val target = File(candidate, safePath(resource.archivePath))
                    owned(target)
                    check(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
                    source.inputStream().use { input -> target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            check(copied <= MAX_ENTRY_BYTES && copied <= source.length())
                            output.write(buffer, 0, count)
                        }
                        check(copied == source.length()) { "保留资源时文件发生变化" }
                    } }
                }
                result.pcPreferences?.let { preferences ->
                    val bytes = preferences.toString().toByteArray()
                    check(bytes.size <= MAX_PREFERENCE_BYTES)
                    val file = File(candidate, DymArchiveStore.PC_PREFERENCES)
                    check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
                    file.writeBytes(bytes)
                }
                result.sharedCredentials?.let { credentials ->
                    DymSharedCredentials.validate(credentials)
                    val bytes = credentials.toString().toByteArray()
                    check(bytes.size <= MAX_PREFERENCE_BYTES)
                    val file = File(candidate, DymArchiveStore.SHARED_CREDENTIALS)
                    check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
                    file.writeBytes(bytes)
                }
                currentCoroutineContext().ensureActive()
                Files.move(candidate.toPath(), published.toPath(), StandardCopyOption.ATOMIC_MOVE)
                val pointer = File(root, "active.json")
                val partial = File(root, "active.${UUID.randomUUID()}.partial")
                try {
                    partial.outputStream().use { output ->
                        output.write(JSONObject().put("schema", 1).put("snapshot", published.name).toString().toByteArray())
                        output.flush()
                    }
                    currentCoroutineContext().ensureActive()
                    Files.move(partial.toPath(), pointer.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    pointerCommitted = true
                } finally { partial.delete() }
                resources.size
            } finally {
                if (candidate.exists()) removeOwnedTree(candidate)
                if (!pointerCommitted && published.exists()) removeOwnedTree(published)
            }
        }
    }

    private fun readLocked(): DymRetainedResources {
        val pointer = File(root, "active.json")
        if (!pointer.exists()) return DymRetainedResources(emptyMap(), null)
        owned(pointer)
        check(pointer.isFile && pointer.length() <= 4096) { "保留资源索引无效" }
        val json = JSONObject(pointer.readText())
        val name = json.getString("snapshot")
        require(json.getInt("schema") == 1 && snapshotPattern.matches(name))
        return readSnapshotLocked(name)
    }

    private fun readSnapshotLocked(name: String): DymRetainedResources {
        val snapshot = owned(File(root, name))
        check(snapshot.isDirectory) { "保留资源已丢失" }
        val directories = prefixes.mapNotNull { prefix ->
            val folder = owned(File(snapshot, prefix))
            if (folder.isDirectory) prefix to folder else null
        }.toMap()
        val pcFile = owned(File(snapshot, DymArchiveStore.PC_PREFERENCES))
        val pcPreferences = if (pcFile.isFile) {
            check(pcFile.length() <= MAX_PREFERENCE_BYTES)
            JSONObject(pcFile.readText())
        } else null
        val credentialsFile = owned(File(snapshot, DymArchiveStore.SHARED_CREDENTIALS))
        val credentials = if (credentialsFile.isFile) {
            check(credentialsFile.length() <= MAX_PREFERENCE_BYTES)
            JSONObject(credentialsFile.readText()).also(DymSharedCredentials::validate)
        } else null
        return DymRetainedResources(directories, pcPreferences, credentials)
    }

    private fun stagedFile(result: DymImportResult, resource: DymStagedFile): File {
        val path = safePath(resource.archivePath)
        require(prefixes.any { path.startsWith("$it/") }) { "保留资源分组无效" }
        val expected = File(result.stagingRoot, path)
        require(resource.file.absoluteFile == expected.absoluteFile)
        val actual = owned(resource.file)
        require(actual.isFile && actual.length() <= MAX_ENTRY_BYTES &&
            actual.path.startsWith(result.stagingRoot.canonicalPath + File.separator)) { "暂存资源无效" }
        return actual
    }

    private fun owned(file: File): File {
        val base = privateRoot.absoluteFile.toPath().normalize()
        val path = file.absoluteFile.toPath().normalize()
        require(path.startsWith(base) && path != base) { "保留资源越出应用目录" }
        var current = base
        for (part in base.relativize(path)) {
            current = current.resolve(part)
            require(!Files.isSymbolicLink(current)) { "保留资源包含链接" }
        }
        require(path.toFile().canonicalPath.startsWith(privateRoot.canonicalPath + File.separator))
        return path.toFile()
    }

    private fun regularFiles(folder: File): List<File> {
        owned(folder)
        val files = mutableListOf<File>()
        Files.walk(folder.toPath()).use { paths -> paths.forEach { path ->
            check(!Files.isSymbolicLink(path)) { "保留资源包含链接" }
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                check(files.size < MAX_ENTRIES)
                files.add(path.toFile())
            } else require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
        } }
        return files
    }

    private fun removeOwnedTree(folder: File) {
        require(folder.canonicalFile.parentFile == root.canonicalFile)
        owned(folder)
        Files.walk(folder.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) }
        }
    }

    private fun safePath(raw: String): String {
        require(raw.length in 1..1024 && raw.none { it.code < 32 } && ':' !in raw && '\\' !in raw)
        require(!raw.startsWith('/') && raw.split('/').all { it.isNotEmpty() && it != "." && it != ".." })
        return raw
    }

    private companion object {
        const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
        const val MAX_ENTRY_BYTES = 256L * 1024 * 1024
        const val MAX_PREFERENCE_BYTES = 8L * 1024 * 1024
        const val MAX_ENTRIES = 50_000
        const val MAX_SNAPSHOTS = 8
        val snapshotPattern = Regex("snapshot-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        val prefixes = listOf(".dsh/profiles", "profiles", ".dsh/plugin-profiles", ".dsh/plugins", "plugins",
            ".dsh/skills", "skills", ".dsh/storages", ".dsh/sessions", "storages", "sessions",
            ".dafeiyu/android/model-history")
        val locks = ConcurrentHashMap<String, Mutex>()
    }
}
