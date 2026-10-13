package com.dafeiyu.mobile.data

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

data class IncompatibleExtensionVersion(val name: String, val version: String, val runtimeVersion: String,
    val peerRequirements: Map<String, String>)
data class ExtensionBuildCandidate(val kind: String, val key: String, val digest: String, val scripts: Map<String, String>) {
    internal fun approval(): JSONObject = JSONObject().put("kind", kind).put("key", key).put("digest", digest)
}

internal fun extensionBuildCandidates(result: JSONObject): List<ExtensionBuildCandidate> {
    val rows = result.optJSONArray("buildCandidates") ?: return emptyList()
    require(rows.length() <= 256) { "待授权构建脚本超过上限" }
    return List(rows.length()) { index ->
        val row = rows.getJSONObject(index)
        val scripts = row.getJSONObject("scripts")
        val kind = row.getString("kind"); val key = row.getString("key"); val digest = row.getString("digest")
        require(kind in setOf("prepare", "dependency") && key.length in 1..356 && digest.matches(Regex("[a-f0-9]{64}")) &&
            scripts.length() in 1..4 && scripts.keys().asSequence().all { it in setOf("preinstall", "install", "postinstall", "prepare") })
        val commands = scripts.keys().asSequence().associateWith { name -> scripts.getString(name).also { require(it.length in 1..16_384) } }
        ExtensionBuildCandidate(kind, key, digest, commands)
    }
}

/** A retry retains the actual fixed install source; temporary registry credentials are excluded. */
class ExtensionManagementFailure internal constructor(message: String,
    val incompatible: List<IncompatibleExtensionVersion>,
    internal val retryFrame: JSONObject,
    val builds: List<ExtensionBuildCandidate> = emptyList(),
) : IOException(message) {
    internal var sourceRecord: JSONObject? = null
}

/** Executes the pinned manager under a real runtime lease; the helper journals and restores within that lease. */
class DshExtensionBridge(
    context: Context,
    private val runtime: LinuxRuntimeManager = LinuxRuntimeManager(context),
    private val installer: DshRuntimeInstaller = DshRuntimeInstaller(context, runtime),
) {
    private val app = context.applicationContext
    private val home = File(app.filesDir, "dsh-home")
    val sourceRoot = File(app.filesDir, "dsh-extension-packages")
    private val lock = locks.computeIfAbsent(home.canonicalPath) { Mutex() }
    fun available(): Boolean = installer.current() != null
    fun recoveryRequired(): Boolean = DshExtensionTransaction.pending(home)

    suspend fun request(frame: JSONObject): JSONObject = withContext(Dispatchers.IO) { lock.withLock {
        val slot = installer.current() ?: throw IOException("请先在组件页安装 Ubuntu、Node.js、pnpm 和 DSH 运行组件")
        val operation = frame.getString("operation")
        require(operation in setOf("list", "install", "enable", "remove", "repair", "recover", "exemption", "build"))
        if (operation != "recover") DshExtensionTransaction.requireReady(home)
        val mutating = operation != "list" || !File(home, "profiles/web/package.json").isFile
        ExtensionFiles.directory(home); ExtensionFiles.directory(sourceRoot)
        val host = File(app.noBackupFilesDir, "dsh-extension-helper").apply { ExtensionFiles.directory(this) }
        for (name in listOf("plugin-management.mjs", "plugin-build-adapter.mjs")) {
            val script = File(host, name)
            require(!java.nio.file.Files.isSymbolicLink(script.toPath()))
            app.assets.open("dsh-runtime/$name").use { input -> script.outputStream().use { input.copyTo(it) } }
        }
        val process = installer.withCurrentSlot(slot.manifest.digest()) { current ->
            runtime.startManagedProcess("dsh-extension-${UUID.randomUUID()}", listOf("/usr/local/bin/node", "--max-old-space-size=256",
                "/dafeiyu-extension-helper/plugin-management.mjs", "/dafeiyu-dsh", "/dafeiyu-dsh-home"),
                mapOf("/dafeiyu-extension-helper" to host, "/dafeiyu-dsh" to current.directory,
                    "/dafeiyu-dsh-home" to home, "/dafeiyu-extension-packages" to sourceRoot),
                environment = mapOf("DSH_HOME" to "/dafeiyu-dsh-home"), exclusiveRuntimeUse = mutating,
                beforeStart = if (operation == "recover") null else ({ DshExtensionTransaction.requireReady(home) }))
        }
        var primaryFailure: Throwable? = null
        try {
            val value = coroutineScope {
                val output = async { process.output.use { input ->
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        require(bytes.size() + count <= 2 * 1024 * 1024) { "插件管理响应超过上限" }; bytes.write(buffer, 0, count)
                    }
                    JSONObject(ExtensionFiles.utf8(bytes.toByteArray()))
                } }
                val errors = async { process.errors.use { input -> val buffer = ByteArray(4 * 1024); while (input.read(buffer) >= 0) buffer.fill(0) } }
                try { withTimeout(if (mutating) 15 * 60_000L else 45_000L) {
                    frame.put("sourceRoot", "/dafeiyu-extension-packages")
                    val bytes = frame.toString().toByteArray()
                    try { require(bytes.size <= 128 * 1024); process.input.use { it.write(bytes); it.flush() } }
                    finally { bytes.fill(0) }
                    val response = output.await(); errors.await()
                    check(process.awaitExit() == 0 && response.optBoolean("ok")) { safeError(response.optString("error", "DSH 插件管理失败")) }
                    val result = response.getJSONObject("value")
                    if (result.optString("application") in setOf("failed", "cancelled")) {
                        val type = result.optString("errorCode").ifBlank { result.optString("packageKind").ifBlank { "unknown" } }
                        val incompatibility = result.optJSONArray("incompatible")
                        val issues = if (incompatibility == null) emptyList() else List(incompatibility.length()) { index ->
                            val item = incompatibility.getJSONObject(index)
                            val peers = item.optJSONObject("peers") ?: JSONObject()
                            IncompatibleExtensionVersion(item.getString("name"), item.getString("version"), item.getString("runtimeVersion"),
                                peers.keys().asSequence().associateWith { key -> peers.getString(key) })
                        }
                        require(issues.size <= 128 && issues.all { it.name.length <= 256 && it.version.length <= 100 &&
                            it.runtimeVersion.length <= 100 && it.peerRequirements.size <= 64 })
                        val retry = JSONObject(frame.toString()).apply { remove("registry"); remove("sourceRoot") }
                        throw ExtensionManagementFailure("${friendlyError(type)}\n${safeError(result.optString("output"))}", issues, retry,
                            extensionBuildCandidates(result))
                    }
                    result
                } } catch (failure: Throwable) {
                    withContext(NonCancellable) {
                        runCatching { process.stop() }; runCatching { process.input.close() }
                        runCatching { process.output.close() }; runCatching { process.errors.close() }
                    }
                    throw failure
                }
            }
            value
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            withContext(NonCancellable) {
                try { process.stop() } catch (cleanupFailure: Throwable) {
                    if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure)
                    else throw cleanupFailure
                }
            }
        }
    } }

    private fun friendlyError(kind: String): String = when (kind) {
        "pnpm-missing" -> "没有可用的 pnpm，请安装或修复该组件"
        "not-bundle" -> "该包没有有效的 DSH bundle，配置已恢复"
        "incompatible-version" -> "插件与当前 DSH 版本不兼容，原配置已保留"
        "build-blocked" -> "插件需要额外构建脚本，尚未获得用户许可"
        "build-consent-required" -> "插件需要构建脚本，请确认列出的精确版本和具体脚本后继续"
        "management-required", "not-removable" -> "该内置 bundle 是引擎管理所必需，不能停用或移除"
        "network" -> "插件依赖下载失败，请检查下载源和网络后重试"
        "integrity" -> "插件或依赖摘要校验未通过"
        "timeout" -> "插件管理操作超时"
        else -> "插件管理失败（$kind）；原配置已保留"
    }

    private fun safeError(value: String): String = value.replace(Regex("http://127\\.0\\.0\\.1:\\d+/[a-f0-9]{48}/"), "[本机校验注册表]/")
        .replace(Regex("(?i)(bearer|api[_-]?key|token|password|secret)\\s*[=: ]\\s*[^\\s,;]+"), "[凭据已隐藏]").take(8_192)

    private companion object { val locks = ConcurrentHashMap<String, Mutex>() }
}
