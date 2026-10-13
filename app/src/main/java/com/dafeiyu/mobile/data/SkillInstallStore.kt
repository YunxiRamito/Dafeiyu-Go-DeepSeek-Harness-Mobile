package com.dafeiyu.mobile.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag

data class SkillMetadata(val name: String, val description: String, val whenToUse: String?,
    val disableModelInvocation: Boolean, val userInvocable: Boolean)
data class InstalledSkill(val metadata: SkillMetadata, val enabled: Boolean, val path: String,
    val source: String, val commit: String?, val canUpdate: Boolean, val userOwned: Boolean = true)
data class DamagedSkill(val id: String, val path: String, val error: String, val canRepair: Boolean)
data class SkillCatalog(val items: List<InstalledSkill>, val warnings: List<String>, val damaged: List<DamagedSkill> = emptyList())
data class InstalledSkillContent(val metadata: SkillMetadata, val body: String, val source: String, val path: String)

/** Match the installed DSH skill-filesystem contract, without evaluating YAML tags or arbitrary scripts. */
object SkillMarkdown {
    const val MAX_BYTES = 1024L * 1024
    private val names = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")

    fun parse(text: String): Pair<SkillMetadata, String> {
        require(text.toByteArray().size <= MAX_BYTES) { "技能说明超过 1 MiB" }
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n")
        require(normalized.startsWith("---\n")) { "SKILL.md 缺少 YAML frontmatter" }
        val ending = normalized.indexOf("\n---", 4)
        require(ending > 4 && (normalized.length == ending + 4 || normalized[ending + 4] == '\n')) { "技能 frontmatter 未闭合" }
        val header = normalized.substring(4, ending)
        require(header.length <= 128 * 1024)
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            setAllowRecursiveKeys(false)
            maxAliasesForCollections = 0
            nestingDepthLimit = 24
            codePointLimit = 128 * 1024
        }
        val yaml = Yaml(SafeConstructor(options))
        val seen = Collections.newSetFromMap(IdentityHashMap<Node, Boolean>())
        fun inspect(node: Node) {
            require(seen.add(node) && node.anchor == null && node.tag in setOf(Tag.STR, Tag.BOOL, Tag.INT, Tag.FLOAT, Tag.NULL, Tag.MAP, Tag.SEQ)) {
                "技能 YAML 不支持别名、递归或自定义标签"
            }
            when (node) {
                is MappingNode -> node.value.forEach { value ->
                    require((value.keyNode as? ScalarNode)?.value != "<<") { "技能 YAML 不支持 merge" }
                    inspect(value.keyNode); inspect(value.valueNode)
                }
                is SequenceNode -> node.value.forEach(::inspect)
            }
        }
        val node = requireNotNull(yaml.compose(StringReader(header))) { "技能 frontmatter 为空" }
        inspect(node)
        val mapping = yaml.load<Any>(header) as? Map<*, *> ?: throw IllegalArgumentException("技能 frontmatter 必须是映射")
        require(mapping.keys.all { it is String } && mapping.size <= 64) { "技能 frontmatter 的字段无效" }
        require(mapping.keys.none { it in setOf("disableModelInvocation", "modelInvocable", "userInvocable") }) {
            "请使用 disable-model-invocation / user-invocable 标准字段"
        }
        val name = mapping["name"] as? String ?: throw IllegalArgumentException("技能必须提供 name 字符串")
        require(name.length <= 128 && names.matches(name)) { "技能名只能使用小写字母、数字及单个连字符" }
        val description = mapping["description"] as? String ?: throw IllegalArgumentException("技能必须提供 description 字符串")
        require(description.isNotBlank() && description.length <= 16_384 && description.none { it == '\u0000' }) { "技能描述无效" }
        val whenToUse = mapping["whenToUse"]?.let { require(it is String && it.length <= 16_384); it }
        fun boolean(key: String, fallback: Boolean): Boolean {
            val value = mapping[key] ?: return fallback
            if (value is Boolean) return value
            return when (value.toString().lowercase()) {
                "true", "yes", "on", "1" -> true
                "false", "no", "off", "0" -> false
                else -> throw IllegalArgumentException("技能 $key 必须是有效 Boolean")
            }
        }
        return SkillMetadata(name, description, whenToUse, boolean("disable-model-invocation", false),
            boolean("user-invocable", true)) to normalized.substring(ending + 4).trimStart('\n')
    }
}

private fun readPrefix(input: InputStream, maximum: Int): ByteArray {
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

/** User skills live in the exact DSH user home. A catalog entry is never used as a cached body. */
class SkillInstallStore private constructor(
    val home: File,
    private val app: Context?,
    private val github: GithubExtensionSource?,
    private val runtime: LinuxRuntimeManager?,
) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "dsh-home"), context.applicationContext,
        GithubExtensionSource(context.applicationContext), LinuxRuntimeManager(context.applicationContext))
    internal constructor(context: Context, runtime: LinuxRuntimeManager) : this(
        File(context.applicationContext.filesDir, "dsh-home"), context.applicationContext,
        GithubExtensionSource(context.applicationContext), runtime)
    internal constructor(home: File) : this(home, null, null, null)
    val root = File(home, "skills")
    private val management = File(home, ".mobile-skills")
    private val lock = locks.computeIfAbsent(home.canonicalPath) { Mutex() }

    suspend fun scan(workspace: File? = null): SkillCatalog = withContext(Dispatchers.IO) {
        lock.withLock { scanLocked(workspace) }
    }

    suspend fun catalogForModel(workspace: File? = null): SkillCatalog = scan(workspace).let { catalog ->
        catalog.copy(items = catalog.items.filter { it.enabled && !it.metadata.disableModelInvocation })
    }

    suspend fun load(name: String, modelInvoked: Boolean = true, workspace: File? = null): InstalledSkillContent =
        withContext(Dispatchers.IO) { lock.withLock {
            val item = scanLocked(workspace).items.firstOrNull { it.metadata.name == name && it.enabled }
                ?: throw IOException("技能不存在、未启用或说明校验失败：$name")
            check(if (modelInvoked) !item.metadata.disableModelInvocation else item.metadata.userInvocable) { "技能不允许本次调用方式" }
            val path = File(item.path)
            val (metadata, body) = SkillMarkdown.parse(ExtensionFiles.text(path, SkillMarkdown.MAX_BYTES))
            check(metadata.name == name && (if (modelInvoked) !metadata.disableModelInvocation else metadata.userInvocable)) {
                "技能调用规则已改变，请刷新目录"
            }
            InstalledSkillContent(metadata, body, item.source, path.path)
        } }

    suspend fun inspect(name: String): InstalledSkillContent = withContext(Dispatchers.IO) { lock.withLock {
        val item = scanLocked(null).items.firstOrNull { it.metadata.name == name } ?: throw IOException("技能不存在或校验失败")
        val (metadata, body) = SkillMarkdown.parse(ExtensionFiles.text(File(item.path), SkillMarkdown.MAX_BYTES))
        InstalledSkillContent(metadata, body, item.source, item.path)
    } }

    suspend fun install(reference: GithubExtensionReference, downloadSource: String,
        replace: Boolean = false, progress: (ArtifactDownloadProgress) -> Unit = {}): List<InstalledSkill> =
        installTracked(reference, reference.ref, downloadSource, replace, progress)

    private suspend fun installTracked(reference: GithubExtensionReference, trackingRef: String, downloadSource: String,
        replace: Boolean, progress: (ArtifactDownloadProgress) -> Unit): List<InstalledSkill> = withContext(Dispatchers.IO) {
        val source = requireNotNull(github)
        layout()
        val stage = File(management, "stage-${UUID.randomUUID()}")
        try {
            val fixed = source.resolve(reference)
            val folder = source.download(fixed, stage, downloadSource, progress)
            val record = JSONObject().put("schema", 1).put("kind", "github").put("owner", reference.owner)
                .put("repository", reference.repository).put("path", reference.path).put("ref", trackingRef)
                .put("commit", fixed.commit).put("url", fixed.fixedUrl).put("installedAt", System.currentTimeMillis())
            mutate { publishCandidates(folder, stage, record, replace) }
        } finally { if (stage.exists()) ExtensionFiles.deleteOwned(management, stage) }
    }

    suspend fun import(uri: Uri, replace: Boolean = false): List<InstalledSkill> = withContext(Dispatchers.IO) {
        val context = requireNotNull(app)
        layout()
        val stage = File(management, "stage-${UUID.randomUUID()}").apply { ExtensionFiles.directory(this) }
        try {
            val file = File(stage, "original")
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("无法读取所选技能文件")
            input.use { ExtensionFiles.copy(it, file, ExtensionFiles.MAX_PACKAGE_BYTES) }
            val unpacked = File(stage, "contents").apply { ExtensionFiles.directory(this) }
            val start = file.inputStream().use { readPrefix(it, 8) }
            val markdown = start.toString(Charsets.UTF_8).removePrefix("\uFEFF").startsWith("---")
            if (markdown) Files.copy(file.toPath(), File(unpacked, "SKILL.md").toPath())
            else ExtensionFiles.unpack(file, unpacked)
            val record = JSONObject().put("schema", 1).put("kind", "local").put("sha256", ExtensionFiles.sha256(file))
                .put("url", "本地文件").put("installedAt", System.currentTimeMillis())
            mutate { publishCandidates(unpacked, unpacked, record, replace) }
        } finally { if (stage.exists()) ExtensionFiles.deleteOwned(management, stage) }
    }

    suspend fun setEnabled(name: String, enabled: Boolean) = withContext(Dispatchers.IO) { mutate {
        val item = scanLocked(null).items.firstOrNull { it.metadata.name == name && it.userOwned }
            ?: throw IOException("用户技能不存在或校验失败")
        if (item.enabled == enabled) return@mutate
        val existing = File(item.path)
        val target = if (enabled) File(existing.parentFile, existing.name.removeSuffix(".disabled")) else File(existing.parentFile, existing.name + ".disabled")
        check(!target.exists() && !Files.isSymbolicLink(target.toPath())) { "技能启停路径已存在，请先修复冲突" }
        Files.move(existing.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } }

    /** Updates and repair retain the previous package in an application-private trash directory. */
    suspend fun update(name: String, downloadSource: String, progress: (ArtifactDownloadProgress) -> Unit = {}): List<InstalledSkill> {
        val reference = withContext(Dispatchers.IO) { lock.withLock {
            val item = scanLocked(null).items.firstOrNull { it.metadata.name == name } ?: throw IOException("技能不存在")
            val record = recordFor(File(item.path)) ?: throw IOException("本地技能没有在线更新来源，请重新选择文件修复")
            check(record.optString("kind") == "github") { "本地技能请重新选择文件修复" }
            GithubExtensionReference(record.getString("owner"), record.getString("repository"), record.optString("path"), record.getString("ref"))
        } }
        return install(reference, downloadSource, replace = true, progress)
    }

    suspend fun repair(name: String, downloadSource: String, progress: (ArtifactDownloadProgress) -> Unit = {}): List<InstalledSkill> {
        val record = withContext(Dispatchers.IO) { lock.withLock {
            val item = scanLocked(null).items.firstOrNull { it.metadata.name == name } ?: throw IOException("技能不存在")
            recordFor(File(item.path)) ?: throw IOException("本地技能请重新选择文件修复")
        } }
        check(record.optString("kind") == "github") { "本地技能请重新选择文件修复" }
        val reference = GithubExtensionReference(record.getString("owner"), record.getString("repository"), record.optString("path"), record.getString("commit"))
        return installTracked(reference, record.getString("ref"), downloadSource, replace = true, progress)
    }

    suspend fun repairDamaged(id: String, downloadSource: String,
        progress: (ArtifactDownloadProgress) -> Unit = {}): List<InstalledSkill> {
        val record = withContext(Dispatchers.IO) { lock.withLock {
            val entry = ownedEntry(id)
            check(scanLocked(null).damaged.any { it.id == id }) { "该技能已变化，请刷新后再修复" }
            recordFor(File(entry, "SKILL.md")) ?: throw IOException("缺少可靠来源，请重新选择技能包修复")
        } }
        check(record.optString("kind") == "github") { "本地技能请重新选择文件修复" }
        val reference = GithubExtensionReference(record.getString("owner"), record.getString("repository"),
            record.optString("path"), record.getString("commit"))
        return installTracked(reference, record.getString("ref"), downloadSource, replace = true, progress)
    }

    suspend fun removeDamaged(id: String) = withContext(Dispatchers.IO) { mutate {
        layout()
        check(scanLocked(null).damaged.any { it.id == id }) { "该技能已变化，请刷新后再移除" }
        val source = ownedEntry(id)
        Files.move(source.toPath(), File(management, "removed-${System.currentTimeMillis()}-${UUID.randomUUID()}").toPath(),
            StandardCopyOption.ATOMIC_MOVE)
    } }

    internal suspend fun publishLocal(folder: File, replace: Boolean = false): List<InstalledSkill> = withContext(Dispatchers.IO) {
        layout()
        mutate { publishCandidates(folder, folder, JSONObject().put("schema", 1).put("kind", "local")
            .put("url", "本地文件").put("installedAt", System.currentTimeMillis()), replace) }
    }

    suspend fun remove(name: String) = withContext(Dispatchers.IO) { mutate {
        layout()
        val item = scanLocked(null).items.firstOrNull { it.metadata.name == name && it.userOwned } ?: throw IOException("用户技能不存在")
        val document = File(item.path)
        val source = if (document.parentFile == root) document else requireNotNull(document.parentFile)
        val trash = File(management, "removed-${System.currentTimeMillis()}-${UUID.randomUUID()}")
        require(source.canonicalPath.startsWith(root.canonicalPath + File.separator))
        Files.move(source.toPath(), trash.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } }

    private fun scanLocked(workspace: File?): SkillCatalog {
        layout()
        val warnings = mutableListOf<String>()
        val damaged = mutableListOf<DamagedSkill>()
        val discovered = linkedMapOf<String, InstalledSkill>()
        val roots = buildList {
            workspace?.let { add(File(it, ".dsh/skills") to false); add(File(it, ".agents/skills") to false) }
            add(root to true)
        }
        for ((folder, userOwned) in roots) {
            if (!folder.exists()) continue
            if (!userOwned && !withinWorkspace(requireNotNull(workspace), folder)) {
                warnings += "已跳过工作区之外或含链接的技能根目录：${folder.path}"
                continue
            }
            if (!folder.isDirectory || Files.isSymbolicLink(folder.toPath())) { warnings += "技能根目录不可读取：${folder.path}"; continue }
            val children = folder.listFiles()?.sortedBy { it.name }
            if (children == null) { warnings += "无法扫描技能目录：${folder.path}"; continue }
            for (entry in children) {
                currentThreadInterrupted()
                if (entry.name == ".system" || entry.name.startsWith('.')) continue
                val documents = when {
                    Files.isSymbolicLink(entry.toPath()) -> { warnings += "已跳过链接技能：${entry.name}"; continue }
                    entry.isDirectory -> listOf(File(entry, "SKILL.md"), File(entry, "SKILL.md.disabled")).filter { it.exists() }
                    entry.isFile && (entry.name.endsWith(".md") || entry.name.endsWith(".md.disabled")) -> listOf(entry)
                    else -> continue
                }
                fun retainDamaged(error: String) {
                    if (!userOwned) return
                    val record = runCatching { recordFor(if (entry.isDirectory) File(entry, "SKILL.md") else entry) }.getOrNull()
                    damaged += DamagedSkill(entry.name, entry.path, error, entry.isDirectory && record?.optString("kind") == "github")
                }
                if (documents.size > 1) {
                    val error = "技能同时有启用和停用说明"; warnings += "$error：${entry.name}"; retainDamaged(error); continue
                }
                if (documents.isEmpty() && entry.isDirectory && File(entry, SOURCE_FILE).exists()) {
                    val error = "技能说明文件缺失"; warnings += "$error：${entry.name}"; retainDamaged(error)
                }
                for (document in documents) {
                    try {
                        val (metadata, _) = SkillMarkdown.parse(ExtensionFiles.text(document, SkillMarkdown.MAX_BYTES))
                        val record = if (userOwned) recordFor(document) else null
                        val item = InstalledSkill(metadata, !document.name.endsWith(".disabled"), document.path,
                            record?.optString("url")?.takeIf { it.isNotBlank() } ?: if (userOwned) "本机共享技能目录" else "当前工作区技能",
                            record?.optString("commit")?.takeIf { it.matches(Regex("[a-f0-9]{40}")) }, record?.optString("kind") == "github", userOwned)
                        if (!discovered.containsKey(metadata.name)) discovered[metadata.name] = item
                        else warnings += "同名技能由优先目录覆盖：${metadata.name}"
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        val message = error.message ?: "技能说明校验失败"
                        warnings += "${entry.name}：$message"; retainDamaged(message)
                    }
                }
            }
        }
        return SkillCatalog(discovered.values.toList(), warnings, damaged)
    }

    private suspend fun publishCandidates(folder: File, licenseRoot: File, source: JSONObject, replace: Boolean): List<InstalledSkill> {
        val candidates = findCandidates(folder)
        require(candidates.isNotEmpty()) { "扩展包内未找到 SKILL.md；请选具体技能子目录" }
        val parsed = candidates.map { (directory, document) -> Triple(directory, document, SkillMarkdown.parse(ExtensionFiles.text(document, SkillMarkdown.MAX_BYTES)).first) }
        require(parsed.map { it.third.name }.distinct().size == parsed.size) { "扩展包内有重复技能名" }
        val old = scanLocked(null).items.associateBy { it.metadata.name }
        check(replace || parsed.none { it.third.name in old || File(root, it.third.name).exists() }) { "同名技能已存在；选择更新或修复后再替换" }
        val prepared = mutableListOf<Pair<File, File>>()
        val moved = mutableListOf<Pair<File, File>>()
        val published = mutableListOf<File>()
        try {
            for ((directory, document, metadata) in parsed) {
                currentCoroutineContext().ensureActive()
                val candidate = File(management, "candidate-${UUID.randomUUID()}").apply { ExtensionFiles.directory(this) }
                prepared += candidate to File(root, metadata.name)
                if (directory != null) copyDirectory(directory, candidate) else Files.copy(document.toPath(), File(candidate, "SKILL.md").toPath())
                preserveLicenses(licenseRoot, candidate)
                val destination = File(root, metadata.name)
                val enabled = old[metadata.name]?.enabled ?: !(File(destination, "SKILL.md.disabled").exists() && !File(destination, "SKILL.md").exists())
                if (!enabled) Files.move(File(candidate, "SKILL.md").toPath(), File(candidate, "SKILL.md.disabled").toPath())
                ExtensionFiles.atomic(File(candidate, SOURCE_FILE), source.toString())
            }
            // Publication is cancellation-free after all candidates have passed validation; failures roll back every entry.
            for ((candidate, destination) in prepared) {
                val previous = old[destination.name]?.let { File(it.path).let { file -> if (file.parentFile == root) file else file.parentFile } }
                    ?: destination.takeIf { it.exists() }
                if (previous != null) {
                    require(!Files.isSymbolicLink(previous.toPath()))
                    val backup = File(management, "replaced-${System.currentTimeMillis()}-${UUID.randomUUID()}")
                    Files.move(previous.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    moved += backup to previous
                }
                Files.move(candidate.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                published += destination
            }
        } catch (failure: Throwable) {
            published.asReversed().forEach { ExtensionFiles.deleteOwned(root, it) }
            moved.asReversed().forEach { (backup, original) -> Files.move(backup.toPath(), original.toPath(), StandardCopyOption.ATOMIC_MOVE) }
            throw failure
        } finally { prepared.forEach { (candidate, _) -> if (candidate.exists()) ExtensionFiles.deleteOwned(management, candidate) } }
        return scanLocked(null).items.filter { item -> parsed.any { it.third.name == item.metadata.name } }
    }

    private fun findCandidates(folder: File): List<Pair<File?, File>> {
        if (folder.isFile && folder.name.endsWith(".md")) return listOf(null to folder)
        if (File(folder, "SKILL.md").isFile) return listOf(folder to File(folder, "SKILL.md"))
        val children = folder.listFiles().orEmpty().filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
        val direct = children.mapNotNull { directory -> File(directory, "SKILL.md").takeIf { it.isFile }?.let { directory to it } }
        if (direct.isNotEmpty()) return direct
        if (children.size == 1) return findCandidates(children.single())
        val conventional = File(folder, "skills")
        return if (conventional.isDirectory) findCandidates(conventional) else emptyList()
    }

    private suspend fun copyDirectory(source: File, destination: File) {
        var files = 0
        var bytes = 0L
        suspend fun visit(current: File) {
            currentCoroutineContext().ensureActive()
            require(!Files.isSymbolicLink(current.toPath())) { "技能包包含符号链接" }
            val relative = current.relativeTo(source).invariantSeparatorsPath
            if (relative.isEmpty()) current.listFiles().orEmpty().forEach { visit(it) }
            else {
                require(++files <= ExtensionFiles.MAX_FILES)
                val target = ExtensionFiles.resolve(destination, relative)
                if (current.isDirectory) { ExtensionFiles.directory(target); current.listFiles().orEmpty().forEach { visit(it) } }
                else {
                    require(current.isFile && bytes + current.length() <= ExtensionFiles.MAX_PACKAGE_BYTES)
                    current.inputStream().use { bytes += ExtensionFiles.copy(it, target, ExtensionFiles.MAX_PACKAGE_BYTES - bytes) }
                }
            }
        }
        visit(source)
    }

    private fun preserveLicenses(source: File, destination: File) {
        if (!source.isDirectory) return
        source.listFiles().orEmpty().filter { it.isFile && it.name.lowercase() in setOf("license", "license.md", "license.txt", "notice", "notice.md", "notice.txt", "copying") }
            .forEach { license ->
                require(!Files.isSymbolicLink(license.toPath()) && license.length() <= 1024 * 1024)
                val target = File(destination, "SOURCE-${license.name}")
                if (!target.exists()) Files.copy(license.toPath(), target.toPath())
            }
    }

    private fun recordFor(document: File): JSONObject? {
        val record = File(document.parentFile, SOURCE_FILE)
        return if (record.isFile && !Files.isSymbolicLink(record.toPath())) JSONObject(ExtensionFiles.text(record, 64 * 1024L)) else null
    }
    private fun ownedEntry(id: String): File {
        require(id.isNotBlank() && id.length <= 256 && id.none { it in setOf('/', '\\', '\u0000') } && id !in setOf(".", ".."))
        return File(root, id).also { entry ->
            require(entry.exists() && !Files.isSymbolicLink(entry.toPath()) && entry.canonicalFile.parentFile == root.canonicalFile) {
                "技能路径不再属于本机用户目录"
            }
        }
    }
    private fun withinWorkspace(workspace: File, folder: File): Boolean {
        val base = workspace.absoluteFile.normalize()
        var current = folder.absoluteFile.normalize()
        if (!current.path.startsWith(base.path + File.separator) ||
            !current.canonicalPath.startsWith(base.canonicalPath + File.separator)) return false
        while (current != base) {
            if (Files.isSymbolicLink(current.toPath())) return false
            current = current.parentFile ?: return false
        }
        return !Files.isSymbolicLink(base.toPath())
    }
    private fun layout() { ExtensionFiles.directory(home); ExtensionFiles.directory(root); ExtensionFiles.directory(management) }
    private suspend fun <T> mutate(block: suspend () -> T): T {
        val perform: suspend () -> T = { lock.withLock { DshExtensionTransaction.requireReady(home); block() } }
        return if (runtime != null) runtime.withIdleDataMutation(listOf(home)) { perform() } else perform()
    }
    private fun currentThreadInterrupted() { if (Thread.currentThread().isInterrupted) throw CancellationException("技能扫描已取消") }
    private companion object { const val SOURCE_FILE = ".dafeiyu-source.json"; val locks = ConcurrentHashMap<String, Mutex>() }
}
