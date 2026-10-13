package com.dafeiyu.mobile.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

data class InstalledExtensionPackage(val name: String, val version: String, val description: String, val enabled: Boolean,
    val installed: Boolean, val builtIn: Boolean, val removable: Boolean, val error: String?, val source: String,
    val canUpdate: Boolean, val rows: List<String>)
data class ExtensionCompatibilityExemption(val packageVersion: String, val runtimeVersion: String)
data class LiveExtensionPlugin(val entryId: String, val moduleName: String, val enabled: Boolean,
    val phase: String?, val readOnlyReason: String?)
data class ExtensionPackageCatalog(val items: List<InstalledExtensionPackage>, val warnings: List<String>, val available: Boolean,
    val exemptions: List<ExtensionCompatibilityExemption> = emptyList(), val builds: List<ExtensionBuildCandidate> = emptyList())
data class ExtensionPackageResult(val name: String?, val version: String?, val warnings: List<String>, val restartRequired: Boolean)

/** Source acquisition is app-owned; installation/compatibility/selection are owned by the actual DSH manager. */
class ExtensionPackageStore(context: Context, private val bridge: DshExtensionBridge = DshExtensionBridge(context),
    private val github: GithubExtensionSource = GithubExtensionSource(context)) {
    private val app = context.applicationContext
    private val sources = bridge.sourceRoot
    private val records = File(app.filesDir, "dsh-home/.mobile-plugins")
    private val lock = locks.computeIfAbsent(sources.canonicalPath) { Mutex() }
    private val service = DshRuntimeManager(app)
    private val liveClient = DshExtensionLiveClient()
    val serviceSnapshot get() = service.snapshot
    fun recoveryRequired(): Boolean = bridge.recoveryRequired()
    suspend fun recover(): ExtensionPackageResult = decodeResult(bridge.request(JSONObject().put("operation", "recover")))

    suspend fun liveEntries(): List<LiveExtensionPlugin> {
        val current = service.snapshot.value
        if (current.status != DshServiceStatus.RUNNING) return emptyList()
        val rows = liveClient.call(requireNotNull(current.accessUrl), "pluginManager/listPlugins", JSONObject()) as? JSONArray
            ?: throw IOException("DSH 没有返回有效的插件入口目录")
        require(rows.length() <= 2000) { "DSH 插件入口目录超过上限" }
        return List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            LiveExtensionPlugin(row.getString("entryId"), row.getString("moduleName"), row.getBoolean("enabled"),
                row.optString("fiberPhase").takeIf { it in setOf("pending", "loading", "active", "failed", "unloading") },
                row.optString("readOnlyReason").takeIf { it.isNotBlank() })
        }
    }

    suspend fun setPluginEntryEnabled(entry: LiveExtensionPlugin, enabled: Boolean): ExtensionPackageResult {
        require(entry.readOnlyReason == null && entry.entryId.length in 1..4096)
        val current = service.snapshot.value
        check(current.status == DshServiceStatus.RUNNING && current.accessUrl != null) { "DSH 服务已停止，请重新启动并刷新目录" }
        val outcome = liveClient.call(requireNotNull(current.accessUrl), "pluginManager/setPluginEnabled",
            JSONObject().put("id", entry.entryId).put("enabled", enabled)) as? JSONObject ?: throw IOException("DSH 未返回有效插件结果")
        check(outcome.optString("application") !in setOf("failed", "cancelled")) {
            "DSH 未应用插件状态（${outcome.optJSONObject("error")?.optString("code") ?: "unknown"}），请刷新目录"
        }
        check(outcome.optString("application") != "overridden") { "此插件状态被更高优先级配置覆盖，当前选择未生效" }
        return decodeResult(outcome)
    }

    suspend fun scan(): ExtensionPackageCatalog = withContext(Dispatchers.IO) {
        if (!bridge.available()) return@withContext ExtensionPackageCatalog(emptyList(), listOf("请先安装 DSH、Ubuntu、Node.js 和 pnpm 运行组件"), false)
        val result = bridge.request(JSONObject().put("operation", "list"))
        val rows = result.getJSONArray("bundles")
        val items = List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val name = row.getString("name")
            val record = readRecord(name)
            val plugins = row.optJSONArray("rows")
            InstalledExtensionPackage(name, row.optString("version"), row.optString("description"), row.getBoolean("enabled"),
                row.getBoolean("installed"), !row.optBoolean("removable") && row.optString("readOnlyReason") == "management-required",
                row.optBoolean("removable"), row.optString("errorCode").takeIf { it.isNotBlank() },
                record?.optString("url")?.takeIf { it.isNotBlank() } ?: row.optString("source").ifBlank { "随 DSH 安装提供" },
                record?.optString("kind") in setOf("github", "npm"),
                if (plugins == null) emptyList() else List(plugins.length()) { plugins.getJSONObject(it).optString("name") }.filter { it.isNotBlank() })
        }
        val warnings = result.optJSONArray("warnings")
        val exemptions = result.optJSONObject("exemptions") ?: JSONObject()
        val accepted = exemptions.keys().asSequence().sorted().flatMap { packageVersion ->
            val versions = exemptions.getJSONArray(packageVersion)
            List(versions.length()) { index -> ExtensionCompatibilityExemption(packageVersion, versions.getString(index)) }.asSequence()
        }.toList()
        ExtensionPackageCatalog(items, if (warnings == null) emptyList() else List(warnings.length()) { warnings.getString(it) }, true,
            accepted, extensionBuildCandidates(result))
    }

    suspend fun installNpm(spec: String, source: String, enabled: Boolean = true,
        progress: (ArtifactDownloadProgress) -> Unit = {}): ExtensionPackageResult = withContext(Dispatchers.IO) { lock.withLock {
        check(bridge.available()) { "请先安装 DSH 运行组件" }
        NpmArtifactRegistry(app, source, progress).use { registry ->
            val metadata = registry.resolve(spec)
            val name = metadata.getString("name"); val version = metadata.getString("version")
            val record = JSONObject().put("schema", 1).put("kind", "npm").put("spec", spec).put("name", name)
                .put("version", version).put("integrity", metadata.getJSONObject("dist").getString("integrity"))
                .put("url", "https://www.npmjs.com/package/$name").put("license", metadata.optString("license"))
                .put("installedAt", System.currentTimeMillis())
            val outcome = try { bridge.request(JSONObject().put("operation", "install").put("spec", "$name@$version")
                .put("registry", registry.url).put("enabled", enabled)) }
            catch (failure: ExtensionManagementFailure) { failure.sourceRecord = record; throw failure }
            saveRecord(name, record)
            decodeResult(outcome)
        }
    } }

    suspend fun installGithub(reference: GithubExtensionReference, source: String, enabled: Boolean = true,
        progress: (ArtifactDownloadProgress) -> Unit = {}): ExtensionPackageResult = withContext(Dispatchers.IO) { lock.withLock {
        check(bridge.available()) { "请先安装 DSH 运行组件" }
        ExtensionFiles.directory(sources)
        val stage = File(sources, "source-${UUID.randomUUID()}")
        var retained = false
        try {
            val fixed = github.resolve(reference)
            val selected = github.download(fixed, stage, source, progress)
            require(selected.isDirectory) { "请选择带 package.json 的插件目录" }
            val manifest = readManifest(selected)
            val name = manifest.getString("name")
            val record = JSONObject().put("schema", 1).put("kind", "github").put("owner", reference.owner)
                .put("repository", reference.repository).put("path", reference.path).put("ref", reference.ref).put("commit", fixed.commit)
                .put("url", fixed.fixedUrl).put("license", manifest.optString("license")).put("installedAt", System.currentTimeMillis())
            // Once a manager can reference this source, cancellation after commit must never remove it.
            retained = true
            val result = installPrepared(selected, source, enabled, record, progress)
            result.copy(name = result.name ?: name)
        } finally { if (!retained && stage.exists()) ExtensionFiles.deleteOwned(sources, stage) }
    } }

    suspend fun import(uri: Uri, source: String, enabled: Boolean = true,
        progress: (ArtifactDownloadProgress) -> Unit = {}): ExtensionPackageResult = withContext(Dispatchers.IO) { lock.withLock {
        check(bridge.available()) { "请先安装 DSH 运行组件" }
        ExtensionFiles.directory(sources)
        val stage = File(sources, "source-${UUID.randomUUID()}").apply { ExtensionFiles.directory(this) }
        var retained = false
        try {
            val original = File(stage, "original.tgz")
            val input = app.contentResolver.openInputStream(uri) ?: throw IOException("无法读取所选插件包")
            input.use { ExtensionFiles.copy(it, original, ExtensionFiles.MAX_PACKAGE_BYTES) }
            val unpacked = ExtensionFiles.unpack(original, File(stage, "contents"))
            val selected = findPackage(unpacked)
            val record = JSONObject().put("schema", 1).put("kind", "local").put("sha256", ExtensionFiles.sha256(original))
                .put("url", "本地插件包").put("installedAt", System.currentTimeMillis())
            retained = true
            val result = installPrepared(selected, source, enabled, record, progress)
            result
        } finally { if (!retained && stage.exists()) ExtensionFiles.deleteOwned(sources, stage) }
    } }

    suspend fun setEnabled(name: String, enabled: Boolean): ExtensionPackageResult = decodeResult(bridge.request(
        JSONObject().put("operation", "enable").put("name", name).put("enabled", enabled)))

    suspend fun setVersionExemption(exemption: ExtensionCompatibilityExemption, enabled: Boolean,
        acceptRisk: Boolean = false): ExtensionPackageResult = decodeResult(bridge.request(JSONObject().put("operation", "exemption")
        .put("packageVersion", exemption.packageVersion).put("runtimeVersion", exemption.runtimeVersion)
        .put("enabled", enabled).put("acceptRisk", acceptRisk)))

    /** Approval applies only to the exact rejected package/runtime pairs, then retries the pinned source. */
    suspend fun retryWithVersionConsent(failure: ExtensionManagementFailure, source: String,
        acceptRisk: Boolean, progress: (ArtifactDownloadProgress) -> Unit = {}): ExtensionPackageResult = withContext(Dispatchers.IO) {
        require(acceptRisk && failure.incompatible.isNotEmpty()) { "必须明确确认精确版本兼容风险" }
        lock.withLock {
            for (issue in failure.incompatible) setVersionExemption(
                ExtensionCompatibilityExemption("${issue.name}@${issue.version}", issue.runtimeVersion), true, acceptRisk = true)
            NpmArtifactRegistry(app, source, progress).use { registry ->
                val frame = JSONObject(failure.retryFrame.toString())
                require(frame.optString("operation") in setOf("install", "enable", "repair")) { "该操作不可作为安装重试" }
                frame.put("registry", registry.url)
                val outcome = try { bridge.request(frame) }
                catch (retryFailure: ExtensionManagementFailure) { retryFailure.sourceRecord = failure.sourceRecord; throw retryFailure }
                failure.sourceRecord?.let { record -> saveRecord(record.getString("name"), record) }
                decodeResult(outcome)
            }
        }
    }

    suspend fun retryWithBuildConsent(failure: ExtensionManagementFailure, source: String, acceptRisk: Boolean,
        progress: (ArtifactDownloadProgress) -> Unit = {}): ExtensionPackageResult = withContext(Dispatchers.IO) { lock.withLock {
        require(acceptRisk && failure.builds.isNotEmpty()) { "必须明确同意执行列出的构建脚本" }
        NpmArtifactRegistry(app, source, progress).use { registry ->
            val frame = JSONObject(failure.retryFrame.toString())
            require(frame.optString("operation") == "install") { "该请求不是受支持的构建安装重试" }
            frame.put("registry", registry.url).put("acceptBuildRisk", true)
                .put("approvedBuilds", JSONArray(failure.builds.map { it.approval() }))
            val result = try { bridge.request(frame) }
            catch (retryFailure: ExtensionManagementFailure) { retryFailure.sourceRecord = failure.sourceRecord; throw retryFailure }
            failure.sourceRecord?.let { record -> saveRecord(record.getString("name"), record) }
            decodeResult(result)
        }
    } }

    suspend fun buildDependencies(candidates: List<ExtensionBuildCandidate>, source: String, acceptRisk: Boolean,
        progress: (ArtifactDownloadProgress) -> Unit = {}): ExtensionPackageResult = withContext(Dispatchers.IO) { lock.withLock {
        require(acceptRisk && candidates.isNotEmpty() && candidates.all { it.kind == "dependency" })
        NpmArtifactRegistry(app, source, progress).use { registry ->
            decodeResult(bridge.request(JSONObject().put("operation", "build").put("registry", registry.url)
                .put("acceptBuildRisk", true).put("approvedBuilds", JSONArray(candidates.map { it.approval() }))))
        }
    } }

    suspend fun update(name: String, source: String, progress: (ArtifactDownloadProgress) -> Unit = {}): ExtensionPackageResult {
        val item = scan().items.firstOrNull { it.name == name } ?: throw IOException("插件不存在")
        val record = withContext(Dispatchers.IO) { readRecord(name) } ?: throw IOException("没有更新来源，请重新导入原插件包修复")
        return when (record.getString("kind")) {
            "github" -> installGithub(GithubExtensionReference(record.getString("owner"), record.getString("repository"),
                record.optString("path"), record.getString("ref")), source, item.enabled, progress)
            "npm" -> installNpm(record.getString("spec"), source, item.enabled, progress)
            else -> throw IOException("本地包不能在线更新；请重新选择插件包")
        }
    }

    suspend fun repair(source: String, name: String? = null, progress: (ArtifactDownloadProgress) -> Unit = {}): ExtensionPackageResult = withContext(Dispatchers.IO) {
        lock.withLock { NpmArtifactRegistry(app, source, progress).use { registry ->
            decodeResult(bridge.request(JSONObject().put("operation", "repair").put("registry", registry.url)
                .apply { name?.let { put("name", it) } }))
        } }
    }

    suspend fun remove(name: String, source: String, progress: (ArtifactDownloadProgress) -> Unit = {}): ExtensionPackageResult = withContext(Dispatchers.IO) {
        lock.withLock { NpmArtifactRegistry(app, source, progress).use { registry ->
            val outcome = bridge.request(JSONObject().put("operation", "remove").put("name", name).put("registry", registry.url))
            val file = recordFile(name)
            if (file.exists()) {
                val backup = File(records, "removed-${System.currentTimeMillis()}-${UUID.randomUUID()}.json")
                Files.move(file.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
            decodeResult(outcome)
        } }
    }

    private suspend fun installPrepared(selected: File, source: String, enabled: Boolean, record: JSONObject,
        progress: (ArtifactDownloadProgress) -> Unit): ExtensionPackageResult {
        currentCoroutineContext().ensureActive()
        val manifest = readManifest(selected)
        val name = manifest.getString("name")
        require(selected.canonicalPath.startsWith(sources.canonicalPath + File.separator))
        val guest = "/dafeiyu-extension-packages/" + selected.relativeTo(sources).invariantSeparatorsPath
        record.put("name", name).put("version", manifest.getString("version")).put("sourceDirectory", selected.relativeTo(sources).invariantSeparatorsPath)
        NpmArtifactRegistry(app, source, progress).use { registry ->
            val result = try { bridge.request(JSONObject().put("operation", "install").put("spec", "file:$guest")
                .put("registry", registry.url).put("enabled", enabled)) }
            catch (failure: ExtensionManagementFailure) { failure.sourceRecord = record; throw failure }
            saveRecord(name, record)
            return decodeResult(result)
        }
    }

    private fun readManifest(folder: File): JSONObject {
        val value = JSONObject(ExtensionFiles.text(File(folder, "package.json"), 1024 * 1024L))
        require(NpmExtensionSpec.isValid(value.getString("name")) && NpmExtensionSpec.packageName(value.getString("name")) == value.getString("name"))
        require(value.getString("version").matches(Regex("[a-zA-Z0-9.+_-]{1,100}")) && value.optJSONObject("dsh")?.optJSONObject("bundle")?.has("patch") == true) {
            "该目录没有有效的 DSH bundle 和版本信息"
        }
        require(NpmArtifactRegistry.dependencySourcesAllowed(value)) { "插件引用了未接入统一下载层的依赖源" }
        for (name in listOf(".npmrc", ".pnpmfile.cjs", ".pnpmfile.mjs")) require(!File(folder, name).exists()) { "本地包包含自定义 npm 配置或 pnpm hook，不能自动执行" }
        return value
    }
    private fun findPackage(folder: File): File {
        if (File(folder, "package.json").isFile) return folder
        val children = folder.listFiles().orEmpty().filter { it.isDirectory }
        require(children.size == 1) { "请选择只包含一个插件 package.json 的包" }
        return findPackage(children.single())
    }
    private fun recordFile(name: String): File {
        val id = MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(records, "$id.json")
    }
    private fun readRecord(name: String): JSONObject? = recordFile(name).takeIf { it.isFile }?.let { JSONObject(ExtensionFiles.text(it, 64 * 1024L)) }
    private fun saveRecord(name: String, value: JSONObject) { ExtensionFiles.directory(records); ExtensionFiles.atomic(recordFile(name), value.toString()) }
    private fun decodeResult(value: JSONObject): ExtensionPackageResult {
        val warnings = value.optJSONArray("warnings")
        return ExtensionPackageResult(value.optString("bundle").takeIf { it.isNotBlank() }, value.optString("version").takeIf { it.isNotBlank() },
            if (warnings == null) emptyList() else List(warnings.length()) { warnings.getString(it) }, value.optString("application") == "restart-required")
    }
    private companion object { val locks = ConcurrentHashMap<String, Mutex>() }
}
