package com.dafeiyu.mobile.data

import java.io.File
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.json.JSONObject

data class DymExportInput(
    /** PC archive prefixes mapped to owned data directories, including portable custom plugin modules. */
    val directories: Map<String, File> = emptyMap(),
    val androidBackup: ByteArray? = null,
    val androidPreferences: JSONObject? = null,
    val pcPreferences: JSONObject? = null,
    val providerConfiguration: JSONObject? = null,
    val sharedCredentials: JSONObject? = null,
)

data class DymStagedFile(val archivePath: String, val file: File)

data class DymImportResult(
    val stagingRoot: File,
    val groupFiles: Map<String, List<DymStagedFile>>,
    val androidBackup: ByteArray?,
    val androidPreferences: JSONObject?,
    val pcPreferences: JSONObject?,
    val providerConfiguration: JSONObject?,
    val warnings: List<String>,
    val sharedCredentials: JSONObject? = null,
) {
    val partial: Boolean get() = warnings.isNotEmpty()
    val availableGroups: Set<String> get() = groupFiles.keys
}

/** Ordinary 7z, with the PC launcher group prefixes and independent platform preference files. */
class DymArchiveStore(private val privateRoot: File) {
    companion object {
        const val ANDROID_BACKUP = ".dafeiyu/android/workspace.json"
        const val ANDROID_PREFERENCES = ".dafeiyu/preferences/android.json"
        const val PC_PREFERENCES = ".dafeiyu/preferences/pc.json"
        const val PROVIDER_CONFIGURATION = ".dafeiyu/config/providers.json"
        const val SHARED_CREDENTIALS = ".dafeiyu/config/credentials.json"
        private const val MAX_ARCHIVE_BYTES = 1024L * 1024 * 1024
        private const val MAX_EXPANDED_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_ENTRY_BYTES = 256L * 1024 * 1024
        private const val MAX_ENTRIES = 50_000
        private const val MAX_JSON_BYTES = 8L * 1024 * 1024
        private val prefixes = linkedMapOf(
            ".dsh/profiles" to "profiles", "profiles" to "profiles",
            ".dsh/plugin-profiles" to "plugins", ".dsh/plugins" to "plugins", "plugins" to "plugins",
            ".dsh/skills" to "skills", "skills" to "skills",
            ".dsh/storages" to "sessions", ".dsh/sessions" to "sessions",
            "storages" to "sessions", "sessions" to "sessions",
            ".dafeiyu/android/model-history" to "native-history",
        )
        private val excludedPathParts = setOf(".git", ".cache", "aries", "aries-identity",
            "aries-groups", "connection-identity", "connection-cache", "push-tokens")
        private val excludedFiles = setOf("identity.json", "device-identity.json", "pairing.json",
            "node-pin.json", "private-key.json", "private_key.pem", "id_ed25519", "id_rsa")
    }

    suspend fun export(destination: File, input: DymExportInput,
        progress: (String, Long) -> Unit = { _, _ -> }): File = withContext(Dispatchers.IO) {
        val target = ownedFile(destination)
        check(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        val partial = File(target.parentFile, target.name + ".partial-" + UUID.randomUUID())
        val seen = HashSet<String>()
        var expanded = 0L
        var entries = 0
        try {
            SevenZOutputFile(partial).use { archive ->
                suspend fun add(name: String, size: Long, stream: () -> InputStream) {
                    val path = archivePath(name)
                    require(seen.add(path.lowercase())) { "备份路径重复：$path" }
                    check(++entries <= MAX_ENTRIES && size in 0..MAX_ENTRY_BYTES && expanded + size <= MAX_EXPANDED_BYTES) {
                        "备份内容超过容量上限"
                    }
                    val entry = SevenZArchiveEntry().apply { this.name = path; this.size = size; isDirectory = false }
                    archive.putArchiveEntry(entry)
                    var copied = 0L
                    stream().use { source ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            copied += count
                            check(copied <= size) { "备份文件在扫描后发生变化：$path" }
                            archive.write(buffer, 0, count)
                        }
                    }
                    check(copied == size) { "备份文件在扫描后发生变化：$path" }
                    archive.closeArchiveEntry()
                    expanded += copied
                    progress(path, expanded)
                    check(partial.length() <= MAX_ARCHIVE_BYTES) { "备份压缩包超过容量上限" }
                }
                for ((prefix, folder) in input.directories) {
                    require(prefix in prefixes) { "不支持的 DSH 备份分组：$prefix" }
                    val base = ownedFile(folder)
                    if (!base.isDirectory) continue
                    val files = mutableListOf<File>()
                    Files.walkFileTree(base.toPath(), object : SimpleFileVisitor<java.nio.file.Path>() {
                        override fun preVisitDirectory(dir: java.nio.file.Path, attrs: BasicFileAttributes): FileVisitResult {
                            val relative = base.toPath().relativize(dir)
                            if (relative.toString().isNotEmpty() && excluded("$prefix/${relative.toString().replace('\\', '/')}"))
                                return FileVisitResult.SKIP_SUBTREE
                            check(relative.nameCount <= 128) { "备份目录层级过深" }
                            return FileVisitResult.CONTINUE
                        }
                        override fun visitFile(file: java.nio.file.Path, attrs: BasicFileAttributes): FileVisitResult {
                            if (!attrs.isRegularFile || attrs.isSymbolicLink || file.fileName.toString().lowercase() in excludedFiles)
                                return FileVisitResult.CONTINUE
                            check(files.size < MAX_ENTRIES) { "备份文件数量过多" }
                            files.add(file.toFile())
                            return FileVisitResult.CONTINUE
                        }
                    })
                    for (file in files.sortedBy { it.path }) {
                        ownedFile(file)
                        val name = "$prefix/${file.relativeTo(base).invariantSeparatorsPath}"
                        if (excluded(name)) continue
                        add(name, file.length()) { file.inputStream() }
                    }
                }
                val metadata = linkedMapOf<String, ByteArray>()
                input.androidBackup?.let { metadata[ANDROID_BACKUP] = it }
                input.androidPreferences?.let { validatePortablePreferences(it); metadata[ANDROID_PREFERENCES] = it.toString().toByteArray() }
                input.pcPreferences?.let { validatePortablePreferences(it); metadata[PC_PREFERENCES] = it.toString().toByteArray() }
                input.providerConfiguration?.let { validateProviderConfiguration(it); metadata[PROVIDER_CONFIGURATION] = it.toString().toByteArray() }
                input.sharedCredentials?.let { validatePortablePreferences(it); DymSharedCredentials.validate(it); metadata[SHARED_CREDENTIALS] = it.toString().toByteArray() }
                for ((path, bytes) in metadata) {
                    require(bytes.size <= MAX_JSON_BYTES)
                    add(path, bytes.size.toLong()) { bytes.inputStream() }
                }
            }
            currentCoroutineContext().ensureActive()
            check(partial.length() <= MAX_ARCHIVE_BYTES)
            ownedFile(target)
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            target
        } finally { partial.delete() }
    }

    /** Stages validated regular files. No current preferences, runtime or conversation is changed. */
    suspend fun stageImport(source: File,
        validateAndroidBackup: (ByteArray) -> Unit,
        validateAndroidPreferences: (JSONObject) -> Unit = {},
        progress: (String, Long) -> Unit = { _, _ -> }): DymImportResult = withContext(Dispatchers.IO) {
        val archiveFile = ownedFile(source)
        check(archiveFile.isFile && archiveFile.length() <= MAX_ARCHIVE_BYTES) { "备份文件不可用或超过容量上限" }
        val staging = File(privateRoot, "dym-staging/${UUID.randomUUID()}")
        ownedFile(staging)
        check(staging.mkdirs())
        val seen = HashSet<String>()
        val groups = linkedMapOf<String, MutableList<DymStagedFile>>()
        val warnings = mutableListOf<String>()
        val metadata = mutableMapOf<String, ByteArray>()
        var total = 0L
        var entryCount = 0
        try {
            SevenZFile.builder().setFile(archiveFile).setMaxMemoryLimitKb(128 * 1024)
                .setTryToRecoverBrokenArchives(false).get().use { archive ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = archive.nextEntry ?: break
                    check(++entryCount <= MAX_ENTRIES) { "备份条目数量超过限制" }
                    val path = archivePath(entry.name)
                    check(seen.add(path.lowercase())) { "备份包含重复路径：$path" }
                    val unixType = if (entry.hasWindowsAttributes) (entry.windowsAttributes ushr 16) and 0xf000 else 0
                    check(!entry.isAntiItem && !(entry.hasWindowsAttributes && entry.windowsAttributes and 0x400 != 0) &&
                        unixType in setOf(0, 0x4000, 0x8000)) {
                        "备份包含删除指令或链接：$path"
                    }
                    check(entry.size in 0..MAX_ENTRY_BYTES && total + entry.size <= MAX_EXPANDED_BYTES) { "备份展开容量超过限制" }
                    total += entry.size
                    if (entry.isDirectory) {
                        check(entry.size == 0L) { "备份目录包含异常数据" }
                        continue
                    }
                    val group = groupForPath(path)
                    val isMetadata = path in setOf(ANDROID_BACKUP, ANDROID_PREFERENCES, PC_PREFERENCES, PROVIDER_CONFIGURATION, SHARED_CREDENTIALS)
                    if (excluded(path)) {
                        warnings += "已排除设备身份或运行缓存：$path"
                        continue
                    }
                    if (group == null && !isMetadata) {
                        if (warnings.size < 100) warnings += "未识别的备份内容已跳过：$path"
                        continue
                    }
                    check(!isMetadata || entry.size <= MAX_JSON_BYTES) { "备份偏好内容过大" }
                    val target = File(staging, path)
                    check(target.canonicalPath.startsWith(staging.canonicalPath + File.separator))
                    check(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
                    var copied = 0L
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = archive.read(buffer)
                            if (count < 0) break
                            copied += count
                            check(copied <= entry.size)
                            output.write(buffer, 0, count)
                        }
                    }
                    check(copied == entry.size) { "备份内容不完整：$path" }
                    progress(path, total)
                    if (isMetadata) metadata[path] = target.readBytes()
                    else groups.getOrPut(requireNotNull(group)) { mutableListOf() }.add(DymStagedFile(path, target))
                }
            }
            var nativeBackup: ByteArray? = null
            metadata[ANDROID_BACKUP]?.let { bytes ->
                try { validateAndroidBackup(bytes); nativeBackup = bytes }
                catch (error: Exception) { warnings += "手机对话导入失败：${error.message}" }
            }
            fun parse(path: String, validate: (JSONObject) -> Unit): JSONObject? {
                val bytes = metadata[path] ?: return null
                return try { JSONObject(String(bytes, Charsets.UTF_8)).also(validate) }
                catch (error: Exception) { warnings += "$path 导入失败：${error.message}"; null }
            }
            val android = parse(ANDROID_PREFERENCES) { validatePortablePreferences(it); validateAndroidPreferences(it) }
            val pc = parse(PC_PREFERENCES, ::validatePortablePreferences)
            val providers = parse(PROVIDER_CONFIGURATION, ::validateProviderConfiguration)
            val credentials = parse(SHARED_CREDENTIALS) { validatePortablePreferences(it); DymSharedCredentials.validate(it) }
            DymImportResult(staging, groups.mapValues { it.value.toList() }, nativeBackup, android, pc, providers,
                warnings.distinct().take(100), credentials)
        } catch (cancelled: CancellationException) {
            discardStaging(staging); throw cancelled
        } catch (error: Exception) {
            discardStaging(staging); throw error
        }
    }

    /** Files remain staged until the caller coordinates stopped runtimes and conflict choices. */
    fun discard(result: DymImportResult) = discardStaging(result.stagingRoot)

    private fun discardStaging(staging: File) {
        val expected = File(privateRoot, "dym-staging").canonicalFile
        check(staging.canonicalFile.parentFile == expected && !Files.isSymbolicLink(staging.toPath()))
        if (staging.exists()) {
            Files.walkFileTree(staging.toPath(), object : SimpleFileVisitor<java.nio.file.Path>() {
                override fun visitFile(file: java.nio.file.Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file); return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: java.nio.file.Path, error: java.io.IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(dir); return FileVisitResult.CONTINUE
                }
            })
        }
    }

    private fun archivePath(raw: String?): String {
        require(!raw.isNullOrBlank() && raw.length <= 1024 && raw.none { it.code < 32 }) { "备份路径无效" }
        val path = raw.replace('\\', '/').removeSuffix("/")
        require(!path.startsWith('/') && ':' !in path && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
            "备份路径越界：$raw"
        }
        return path
    }

    private fun groupForPath(path: String): String? = prefixes.entries.firstOrNull {
        path.startsWith(it.key + "/")
    }?.value

    private fun excluded(path: String): Boolean {
        val parts = path.split('/')
        if (parts.any { it.lowercase() in excludedPathParts } || parts.last().lowercase() in excludedFiles) return true
        if (parts.none { it.equals("node_modules", ignoreCase = true) }) return false
        // The PC exporter materializes reviewed custom packages under this synthetic
        // prefix, including their nested dependencies. Other node_modules are caches.
        if (parts.size < 4 || parts[0] != ".dsh" || parts[1] != "plugin-profiles" || parts[3] != "node_modules") return true
        if (parts.getOrNull(4) == "@deepseek-ai" && parts.getOrNull(5)?.startsWith("dsh-") == true) return true
        return false
    }

    private fun ownedFile(file: File): File {
        val root = privateRoot.canonicalFile
        val originalBase = privateRoot.absoluteFile.toPath().normalize()
        val canonicalBase = root.toPath()
        val path = file.absoluteFile.toPath().normalize()
        // Android may expose /data/user/0 while canonical traversal yields
        // /data/data. Both name the same trusted root; links below it remain forbidden.
        val base = when {
            path.startsWith(originalBase) && path != originalBase -> originalBase
            path.startsWith(canonicalBase) && path != canonicalBase -> canonicalBase
            else -> throw IllegalArgumentException("备份文件必须位于应用私有目录")
        }
        var current = base
        for (part in base.relativize(path)) {
            current = current.resolve(part)
            require(!Files.isSymbolicLink(current)) { "备份路径包含链接" }
        }
        val actual = file.canonicalFile
        require(actual.path.startsWith(root.path + File.separator)) {
            "备份文件必须位于应用私有目录"
        }
        return actual
    }

    private fun validatePortablePreferences(json: JSONObject) {
        val forbidden = setOf("deviceid", "device_id", "installationid", "installation_id", "privatekey", "private_key",
            "pairing", "ariesidentity", "aries_identity", "nodepin", "node_pin", "pushtoken", "push_token")
        validateNoIdentity(json, forbidden)
    }

    private fun validateProviderConfiguration(json: JSONObject) {
        validatePortablePreferences(json)
        check(json.toString().toByteArray().size <= MAX_JSON_BYTES) { "提供商配置超过上限" }
    }

    private fun validateNoIdentity(json: JSONObject, forbidden: Set<String>, depth: Int = 0) {
        check(depth <= 32) { "偏好内容层级过深" }
        for (key in json.keys()) {
            check(key.lowercase() !in forbidden) { "偏好包含不可迁移的设备身份：$key" }
            when (val value = json.opt(key)) {
                is JSONObject -> validateNoIdentity(value, forbidden, depth + 1)
                is org.json.JSONArray -> for (index in 0 until value.length()) {
                    value.optJSONObject(index)?.let { validateNoIdentity(it, forbidden, depth + 1) }
                }
            }
        }
    }
}
