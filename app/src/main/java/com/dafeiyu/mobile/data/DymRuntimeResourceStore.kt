package com.dafeiyu.mobile.data

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class DymResourceRestoreReport(val restoredFiles: Int, val preservedFiles: Int, val warnings: List<String>)

/**
 * Installs the PC launcher's portable data into the verified Android DSH_HOME.
 * This layer only copies and publishes files. It never invokes npm, plugin code,
 * a skill, a DSH prompt or a restored task. The caller owns the real process lease.
 */
class DymRuntimeResourceStore(private val privateRoot: File) {
    private val home = File(privateRoot, "dsh-home")
    private val lock = locks.computeIfAbsent(privateRoot.canonicalPath) { Mutex() }

    suspend fun restore(result: DymImportResult, requireIdle: () -> Unit): DymResourceRestoreReport =
        withContext(Dispatchers.IO) { lock.withLock {
            requireIdle()
            require(result.stagingRoot.canonicalFile.parentFile == File(privateRoot, "dym-staging").canonicalFile)
            val warnings = mutableListOf<String>()
            val mapped = linkedMapOf<String, DymStagedFile>()
            val manifests = linkedMapOf<String, DymStagedFile>()
            var preserved = 0
            for ((group, resources) in result.groupFiles) {
                if (group == "sessions") continue // Persistent history has its own controlled converter.
                for (resource in resources) {
                    currentCoroutineContext().ensureActive()
                    val relative = actualPath(resource.archivePath) ?: continue
                    sourceFile(result, resource)
                    val previous = mapped[relative]
                    if (previous != null) {
                        if (digest(previous.file) != digest(resource.file)) {
                            warnings += "资源路径冲突，已保留双方原始备份：$relative"
                            preserved++
                        }
                        continue
                    }
                    if (relative.matches(Regex("profiles/[^/]+/package\\.json"))) {
                        // Synthetic profile manifests take precedence over ordinary profile
                        // copies: the PC exporter rewrites their local dependency references.
                        val profile = relative.substringAfter("profiles/").substringBefore('/')
                        if (resource.archivePath.startsWith(".dsh/plugin-profiles/") || profile !in manifests)
                            manifests[profile] = resource
                    } else mapped[relative] = resource
                }
            }
            val units = mapped.entries.groupBy { unitPath(it.key) }
            val operations = owned(File(privateRoot, "dym-resource-operations"))
            check(operations.isDirectory || operations.mkdirs())
            val stage = owned(File(operations, "restore-${UUID.randomUUID()}"))
            check(stage.mkdir())
            val moved = mutableListOf<File>()
            val changedManifests = mutableListOf<Pair<File, ByteArray?>>()
            var restored = 0
            try {
                for ((unit, entries) in units) {
                    currentCoroutineContext().ensureActive()
                    val target = owned(File(home, unit))
                    if (exists(target)) {
                        preserved += entries.size
                        if (entries.any { (relative, resource) ->
                            val existing = owned(File(home, relative))
                            !existing.isFile || digest(existing) != digest(resource.file)
                        }) warnings += "已有资源已保留，导入版本仍在备份中：$unit"
                        continue
                    }
                    for ((relative, resource) in entries) {
                        val source = sourceFile(result, resource)
                        val candidate = owned(File(stage, relative))
                        check(candidate.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
                        copyVerified(source, candidate)
                    }
                }
                // Prepare manifests before publishing any unit, so malformed metadata
                // cannot leave a partially installed package set behind.
                val preparedManifests = manifests.map { (profile, resource) ->
                    val source = sourceFile(result, resource)
                    check(source.length() <= MAX_MANIFEST_BYTES) { "插件清单超过容量上限" }
                    val imported = validateManifest(JSONObject(source.readText()))
                    val target = owned(File(home, "profiles/$profile/package.json"))
                    val original = if (target.isFile) {
                        check(target.length() <= MAX_MANIFEST_BYTES)
                        target.readBytes()
                    } else null
                    val merged = if (original == null) portableManifest(imported, warnings) else
                        mergeManifest(validateManifest(JSONObject(String(original, Charsets.UTF_8))), imported, profile, warnings)
                    Triple(target, original, merged.toString(2).toByteArray(Charsets.UTF_8))
                }
                requireIdle()
                for ((unit, entries) in units) {
                    currentCoroutineContext().ensureActive()
                    val candidate = owned(File(stage, unit))
                    if (!exists(candidate)) continue
                    val target = owned(File(home, unit))
                    check(!exists(target)) { "资源在准备期间发生变化，请重新导入：$unit" }
                    check(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
                    requireIdle()
                    Files.move(candidate.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    moved += target
                    restored += entries.size
                }
                for ((target, original, bytes) in preparedManifests) {
                    currentCoroutineContext().ensureActive()
                    requireIdle()
                    check(if (original == null) !exists(target) else target.isFile && target.readBytes().contentEquals(original)) {
                        "插件清单在准备期间发生变化，请重新导入"
                    }
                    if (original != null && original.contentEquals(bytes)) { preserved++; continue }
                    check(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
                    changedManifests += target to original
                    publishBytes(target, bytes)
                    restored++
                }
                DymResourceRestoreReport(restored, preserved, warnings.distinct().take(100))
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    for ((target, original) in changedManifests.asReversed()) {
                        try { if (original == null) Files.deleteIfExists(owned(target).toPath()) else publishBytes(target, original) }
                        catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                    }
                    for (target in moved.asReversed()) {
                        try { removeOwned(target) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                    }
                }
                throw failure
            } finally { withContext(NonCancellable) { removeOwned(stage) } }
        } }

    /** These are real installed data roots; runtime packages are exported separately. */
    fun installedDirectories(includeProfiles: Boolean): Map<String, File> = linkedMapOf<String, File>().apply {
        for ((prefix, relative) in listOf(".dsh/skills" to "skills", ".dsh/plugins" to "plugins",
            ".dsh/sessions" to "sessions", ".dsh/storages" to "storages")) {
            val folder = owned(File(home, relative))
            if (folder.isDirectory) put(prefix, folder)
        }
        if (includeProfiles) {
            val folder = owned(File(home, "profiles"))
            if (folder.isDirectory) put(".dsh/profiles", folder)
        }
    }

    /** Snapshot only custom package directories named by the profile manifest. */
    suspend fun portablePlugins(destination: File): File = withContext(Dispatchers.IO) { lock.withLock {
        val output = owned(destination)
        check(!exists(output) && output.mkdirs())
        try {
            val profiles = owned(File(home, "profiles"))
            for (profile in profiles.listFiles().orEmpty().sortedBy { it.name }) {
                currentCoroutineContext().ensureActive()
                if (!owned(profile).isDirectory || profile.name == "node_modules") continue
                val manifestFile = owned(File(profile, "package.json"))
                if (!manifestFile.isFile) continue
                check(manifestFile.length() <= MAX_MANIFEST_BYTES)
                val manifest = validateManifest(JSONObject(manifestFile.readText()))
                val dependencies = manifest.optJSONObject("dependencies") ?: JSONObject()
                val customNames = dependencies.keys().asSequence().filterNot(::isRuntimePackage).toList()
                val profileOutput = owned(File(output, profile.name))
                check(profileOutput.mkdirs())
                for (name in customNames) {
                    requirePackageName(name)
                    val local = File(profile, "node_modules/$name")
                    val source = portableSource(if (exists(local)) local else File(profiles, "node_modules/$name"))
                    if (!source.isDirectory) continue
                    val target = owned(File(profileOutput, "node_modules/$name"))
                    copyTree(source, target)
                    val spec = dependencies.optString(name)
                    if (spec.startsWith("file:", true) || spec.startsWith("link:", true))
                        dependencies.put(name, "file:./node_modules/$name")
                }
                manifest.put("dependencies", dependencies)
                owned(File(profileOutput, "package.json")).writeText(manifest.toString(2))
            }
            output
        } catch (failure: Throwable) { withContext(NonCancellable) { removeOwned(output) }; throw failure }
    } }

    private fun mergeManifest(target: JSONObject, source: JSONObject, profile: String,
        warnings: MutableList<String>): JSONObject {
        val dependencies = target.optJSONObject("dependencies") ?: JSONObject().also { target.put("dependencies", it) }
        val existingDependencies = dependencies.keys().asSequence().toSet()
        val incoming = source.optJSONObject("dependencies") ?: JSONObject()
        for (name in incoming.keys()) {
            requirePackageName(name)
            if (isRuntimePackage(name)) continue
            if (dependencies.has(name)) {
                if (dependencies.getString(name) != incoming.getString(name))
                    warnings += "已有插件配置已保留：$profile/$name"
                continue
            }
            val spec = incoming.getString(name)
            dependencies.put(name, if (spec.startsWith("file:", true) || spec.startsWith("link:", true))
                "file:./node_modules/$name" else spec)
        }
        val dsh = target.optJSONObject("dsh") ?: JSONObject().also { target.put("dsh", it) }
        val settings = dsh.optJSONObject("profile") ?: JSONObject().also { dsh.put("profile", it) }
        val bundles = settings.optJSONArray("bundles") ?: JSONArray().also { settings.put("bundles", it) }
        val incomingBundles = source.optJSONObject("dsh")?.optJSONObject("profile")?.optJSONArray("bundles") ?: JSONArray()
        val existing = (0 until bundles.length()).map { bundles.getString(it) }.toMutableSet()
        for (index in 0 until incomingBundles.length()) {
            val name = incomingBundles.getString(index)
            if (!isRuntimePackage(name) && name !in existingDependencies && existing.add(name)) bundles.put(name)
        }
        return target
    }

    private fun portableManifest(manifest: JSONObject, warnings: MutableList<String>): JSONObject {
        manifest.optJSONObject("dependencies")?.let { dependencies ->
            for (name in dependencies.keys()) {
                val spec = dependencies.getString(name)
                if (!isRuntimePackage(name) && (spec.startsWith("file:", true) || spec.startsWith("link:", true)))
                    dependencies.put(name, "file:./node_modules/$name")
            }
        }
        if (manifest.has("scripts")) warnings += "插件清单含安装脚本，导入只恢复文件，未执行脚本"
        return manifest
    }

    private fun validateManifest(manifest: JSONObject): JSONObject {
        manifest.opt("dependencies")?.let { require(it is JSONObject) }
        manifest.optJSONObject("dependencies")?.let { dependencies ->
            for (name in dependencies.keys()) {
                requirePackageName(name)
                require(dependencies.get(name) is String && dependencies.getString(name).length <= 2048)
            }
        }
        manifest.optJSONObject("dsh")?.optJSONObject("profile")?.opt("bundles")?.let { value ->
            require(value is JSONArray && value.length() <= 1024)
            repeat(value.length()) { requirePackageName(value.getString(it)) }
        }
        return manifest
    }

    private fun actualPath(archivePath: String): String? {
        safePath(archivePath)
        val relative = when {
            archivePath.startsWith(".dsh/plugin-profiles/") -> "profiles/" + archivePath.removePrefix(".dsh/plugin-profiles/")
            archivePath.startsWith(".dsh/") -> archivePath.removePrefix(".dsh/")
            else -> archivePath
        }
        return relative.takeIf { it.substringBefore('/') in setOf("profiles", "skills", "plugins") }
    }

    private fun unitPath(relative: String): String {
        val parts = relative.split('/')
        if (parts.size >= 3 && parts[0] in setOf("skills", "plugins")) return parts.take(2).joinToString("/")
        if (parts.size >= 5 && parts[0] == "profiles" && parts[2] == "node_modules") {
            val count = if (parts[3].startsWith('@')) 5 else 4
            val name = parts.drop(3).take(count - 3).joinToString("/")
            requirePackageName(name)
            return parts.take(count).joinToString("/")
        }
        return relative
    }

    private fun sourceFile(result: DymImportResult, resource: DymStagedFile): File {
        safePath(resource.archivePath)
        val expected = File(result.stagingRoot, resource.archivePath)
        require(expected.absoluteFile == resource.file.absoluteFile)
        val source = owned(resource.file)
        require(source.isFile && source.length() <= MAX_ENTRY_BYTES)
        return source
    }

    private suspend fun copyVerified(source: File, target: File) {
        val before = digest(source)
        val length = source.length()
        check(length <= MAX_ENTRY_BYTES)
        source.inputStream().use { input -> target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            var copied = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                copied += count
                check(copied <= length)
                output.write(buffer, 0, count)
            }
            check(copied == length)
        } }
        check(digest(target) == before && digest(source) == before) { "资源在准备期间发生变化" }
    }

    private suspend fun copyTree(source: File, target: File) {
        portableSource(source)
        owned(target)
        check(target.mkdirs())
        var entries = 0
        var bytes = 0L
        val ancestors = mutableSetOf<String>()
        suspend fun visit(candidate: File, destination: File, depth: Int) {
            currentCoroutineContext().ensureActive()
            check(depth <= 128 && ++entries <= MAX_ENTRIES)
            val file = portableSource(candidate)
            owned(destination)
            if (file.isDirectory) {
                if (!ancestors.add(file.canonicalPath)) return
                try {
                    check(destination.isDirectory || destination.mkdirs())
                    for (child in file.listFiles().orEmpty().sortedBy { it.name }) visit(child, File(destination, child.name), depth + 1)
                    // pnpm places a package's dependencies beside its real directory.
                    // Materialize them inside its portable unit, matching PC export.
                    val manifestFile = portableSource(File(file, "package.json"))
                    if (manifestFile.isFile) {
                        check(manifestFile.length() <= MAX_MANIFEST_BYTES)
                        val manifest = JSONObject(manifestFile.readText())
                        val names = listOf("dependencies", "optionalDependencies").flatMap { group ->
                            manifest.optJSONObject(group)?.keys()?.asSequence()?.toList().orEmpty()
                        }.distinct()
                        for (name in names) {
                            requirePackageName(name)
                            if (File(destination, "node_modules/$name").isDirectory) continue
                            var ancestor: File? = file
                            var dependency: File? = null
                            while (ancestor != null && ancestor.canonicalPath.startsWith(home.canonicalPath + File.separator)) {
                                val candidateDependency = File(ancestor, "node_modules/$name")
                                if (exists(candidateDependency)) { dependency = candidateDependency; break }
                                ancestor = ancestor.parentFile
                            }
                            dependency?.let { visit(it, File(destination, "node_modules/$name"), depth + 1) }
                        }
                    }
                } finally { ancestors.remove(file.canonicalPath) }
            } else {
                check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "插件含特殊文件" }
                bytes += file.length()
                check(bytes <= MAX_TOTAL_BYTES)
                copyVerified(file, destination)
            }
        }
        visit(source, target, 0)
    }

    private fun portableSource(file: File): File {
        val canonical = file.canonicalFile
        require(listOf(File(home, "profiles"), File(home, "plugins")).any { root ->
            canonical.path.startsWith(root.canonicalPath + File.separator)
        }) { "插件链接指向已核对的数据目录之外" }
        return owned(canonical)
    }

    private fun publishBytes(target: File, bytes: ByteArray) {
        val partial = owned(File(target.parentFile, ".dym-manifest-${UUID.randomUUID()}"))
        try {
            partial.outputStream().use { output -> output.write(bytes); output.flush() }
            owned(target)
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(partial.toPath()) }
    }

    private fun digest(file: File): String {
        owned(file)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun owned(file: File): File {
        val base = privateRoot.absoluteFile.toPath().normalize()
        val path = file.absoluteFile.toPath().normalize()
        require(path.startsWith(base) && path != base) { "资源必须位于应用私有目录" }
        var current = base
        for (part in base.relativize(path)) {
            current = current.resolve(part)
            require(!Files.isSymbolicLink(current)) { "资源路径包含链接" }
        }
        require(path.toFile().canonicalPath.startsWith(privateRoot.canonicalPath + File.separator))
        return path.toFile()
    }

    private fun removeOwned(folder: File) {
        owned(folder)
        if (!exists(folder)) return
        Files.walk(folder.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun safePath(path: String) {
        require(path.length in 1..1024 && ':' !in path && '\\' !in path && path.none { it.code < 32 })
        require(!path.startsWith('/') && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." })
    }

    private fun requirePackageName(name: String) {
        require(name.matches(Regex("(?:@[a-z0-9_.-]+/)?[a-z0-9_.-]+")) && name.length <= 214 &&
            name.split('/').none { it == "." || it == ".." }) { "插件名称无效" }
    }

    private fun isRuntimePackage(name: String) = name.startsWith("@deepseek-ai/dsh-")
    private fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private companion object {
        const val MAX_MANIFEST_BYTES = 2L * 1024 * 1024
        const val MAX_ENTRY_BYTES = 256L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
        const val MAX_ENTRIES = 50_000
        val locks = ConcurrentHashMap<String, Mutex>()
    }
}
