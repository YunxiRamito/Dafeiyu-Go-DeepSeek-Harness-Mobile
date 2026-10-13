package com.dafeiyu.mobile.data

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** A storage-only Node process using the exact installed DSH persistence code.
 * Its input/output are private; no diagnostic can expose OAuth grants or keys. */
class DshSharedPersistenceBridge(
    context: Context,
    private val runtime: LinuxRuntimeManager = LinuxRuntimeManager(context),
    private val installer: DshRuntimeInstaller = DshRuntimeInstaller(context, runtime),
) {
    private val app = context.applicationContext
    private val lock = Mutex()

    suspend fun readArtifact(root: File, sessionId: String, compression: String = "zstd"): JSONObject =
        request(root, JSONObject().put("operation", "read").put("root", "/dafeiyu-shared-data")
            .put("sessionId", sessionId).put("compression", compression))

    /** Exact first-party metadata enumeration; no event replay or hand-built
     * directory/ID decoding. Compression is the actual configured encoding. */
    suspend fun listArtifacts(root: File, compression: String = "zstd", limit: Int = 1000, afterId: String? = null): JSONObject {
        require(compression in setOf("zstd", "none") && limit in 1..1000 && (afterId == null || afterId.length <= 512))
        return request(root, JSONObject().put("operation", "list").put("root", "/dafeiyu-shared-data")
            .put("compression", compression).put("limit", limit).put("afterId", afterId ?: JSONObject.NULL))
    }

    /** The complete staged Session root is mounted so first-party historical
     * migrations can inspect child membership and preserve compaction rules. */
    suspend fun readPortableArtifact(file: File): JSONObject {
        require(file.isFile && !LinuxArchiveExtractor.isSymlink(file))
        val directory = requireNotNull(file.parentFile)
        val root = requireNotNull(directory.parentFile?.parentFile)
        val relative = file.relativeTo(root).invariantSeparatorsPath
        require(!relative.startsWith("../"))
        return request(root, JSONObject().put("operation", "portable").put("file", "/dafeiyu-shared-data/$relative"))
    }

    suspend fun readCredentialDocument(): JSONObject = request(File(app.filesDir, "dsh-home"),
        JSONObject().put("operation", "credentials").put("home", "/dafeiyu-shared-data"))

    suspend fun writeCredentialDocument(updates: JSONObject, exclusiveRuntimeUse: Boolean = false): JSONObject = request(File(app.filesDir, "dsh-home"),
        JSONObject().put("operation", "credentials-write").put("home", "/dafeiyu-shared-data")
            .put("guestHome", "/dafeiyu-dsh-home").put("updates", updates), exclusiveRuntimeUse = exclusiveRuntimeUse)

    suspend fun convertProviderConfiguration(frame: JSONObject): JSONObject = request(File(app.filesDir, "dsh-home"),
        frame, scriptName = "shared-configuration.mjs")

    /** A real exclusive process lease closes the stop/check/launch race with
     * DSH and model workers. First-party ownership also protects existing IDs. */
    suspend fun writeCompatibleArtifact(artifact: JSONObject): JSONObject {
        check(runtime.managedProcesses.value.none { it.id == DshRuntimeManager.PROCESS_ID }) { "请先停止 DSH 服务后再导入会话" }
        val root = File(app.filesDir, "dsh-home/sessions").apply { require(isDirectory || mkdirs()) }
        return request(root, JSONObject().put("operation", "write").put("root", "/dafeiyu-shared-data")
            .put("artifact", artifact).put("compression", "zstd"), exclusiveRuntimeUse = true)
    }

    internal suspend fun deleteSharedHistory(frame: JSONObject): JSONObject {
        require(frame.optString("operation") == "delete-history" && frame.optBoolean("confirmDelete"))
        check(runtime.managedProcesses.value.none { it.running }) { "请先停止 DSH 服务、连接宿主和模型运行器后再永久删除共享历史" }
        val mappings = File(app.filesDir, "local-dsh-history")
        require(mappings.isDirectory && !LinuxArchiveExtractor.isSymlink(mappings)) { "共享历史映射缺失，原归档保留" }
        return request(mappings, frame, exclusiveRuntimeUse = true, scriptName = "shared-history-deletion.mjs")
    }

    private suspend fun request(data: File, frame: JSONObject, exclusiveRuntimeUse: Boolean = false,
        scriptName: String = "shared-persistence.mjs"): JSONObject = withContext(Dispatchers.IO) {
        lock.withLock {
            val operation = frame.getString("operation")
            require(operation in setOf("read", "list", "portable", "write", "credentials", "credentials-write", "to-dsh", "from-dsh", "delete-history"))
            val slot = installer.current() ?: throw IOException("请先安装 DSH 运行组件以读取其历史格式")
            val host = File(app.noBackupFilesDir, "dsh-shared-persistence").apply { require(isDirectory || mkdirs()) }
            require(!LinuxArchiveExtractor.isSymlink(host))
            require(scriptName in setOf("shared-persistence.mjs", "shared-configuration.mjs", "shared-history-deletion.mjs"))
            val script = File(host, scriptName)
            require(!LinuxArchiveExtractor.isSymlink(script))
            app.assets.open("dsh-runtime/$scriptName").use { input -> script.outputStream().use { input.copyTo(it) } }
            val publisher = File(host, "android-persistence.mjs")
            require(!LinuxArchiveExtractor.isSymlink(publisher))
            app.assets.open("dsh-runtime/android-persistence.mjs").use { input -> publisher.outputStream().use { input.copyTo(it) } }
            val deleting = operation == "delete-history"
            val home = File(app.filesDir, "dsh-home")
            val workspaces = File(app.filesDir, "linux-workspaces")
            val pluginManifest = if (deleting) app.assets.open("connection-plugin/manifest.json").use {
                ConnectionPackageValidator.manifest(ConnectionPackageValidator.readBounded(it, ConnectionPackageValidator.MAX_MANIFEST))
            } else null
            val plugin = if (deleting) ConnectionPluginPackage(app).ensureBundled(DshArchiveLaunchContract.CONNECT_VERSION) else null
            val pluginSlot = if (pluginManifest != null && plugin != null) DshArchiveLaunchContract.verifyPreparedPackage(pluginManifest, plugin) else null
            if (deleting) {
                require(!LinuxArchiveExtractor.isSymlink(home) && home.isDirectory) { "DSH 数据目录缺失，原归档保留" }
                require(!LinuxArchiveExtractor.isSymlink(workspaces) && (workspaces.isDirectory || workspaces.mkdirs()))
            }
            val process = installer.withCurrentSlot(slot.manifest.digest()) { current ->
                val command = listOf("/usr/local/bin/node", "--max-old-space-size=192") +
                    (if (deleting) listOf("--import", DshArchiveLaunchContract.PRELOAD) else emptyList()) +
                    listOf("/dafeiyu-shared-helper/$scriptName", "/dafeiyu-dsh")
                val mounts = mapOf("/dafeiyu-shared-helper" to host, "/dafeiyu-dsh" to current.directory, "/dafeiyu-shared-data" to data) +
                    (if (deleting) mapOf(LinuxDshArchiveEnvironment.HOME to home, "/dafeiyu-workspaces" to workspaces,
                        DshArchiveLaunchContract.CONNECT_MOUNT to checkNotNull(pluginSlot)) else emptyMap())
                runtime.startManagedProcess("dsh-shared-persistence", command, mounts,
                    environment = if (deleting) DshArchiveLaunchContract.environment() else emptyMap(),
                    exclusiveRuntimeUse = exclusiveRuntimeUse,
                    beforeStart = {
                        DshExtensionTransaction.requireReady(home)
                        if (deleting) {
                            DshArchiveLaunchContract.verifyOwningBundle(current.directory, current.manifest.dshVersion)
                            DshArchiveLaunchContract.verifyPreparedPackage(checkNotNull(pluginManifest), checkNotNull(plugin))
                        }
                    })
            }
            try {
                coroutineScope {
                    val output = async { process.output.use { input ->
                        val bytes = ByteArrayOutputStream()
                        val buffer = ByteArray(32 * 1024)
                        while (true) { val count = input.read(buffer); if (count < 0) break
                            require(bytes.size() + count <= 25 * 1024 * 1024) { "DSH 共享数据超过上限" }; bytes.write(buffer, 0, count) }
                        bytes.toString(Charsets.UTF_8.name())
                    } }
                    val errors = async { process.errors.use { input -> val buffer = ByteArray(4096); while (input.read(buffer) >= 0) buffer.fill(0) } }
                    try { withTimeout(90_000) {
                        val bytes = frame.toString().toByteArray()
                        try { require(bytes.size <= 24 * 1024 * 1024); process.input.use { it.write(bytes); it.flush() } }
                        finally { bytes.fill(0) }
                        val result = JSONObject(output.await())
                        errors.await()
                        if (process.awaitExit() != 0 || !result.optBoolean("ok")) {
                            // Only fixed protocol identifiers enter diagnostics;
                            // never include stderr, helper payloads or exceptions
                            // which may contain actual credentials or paths.
                            val code = result.optString("code").takeIf {
                                it in setOf("SHARED_STORAGE_FAILED", "SHARED_CONFIGURATION_FAILED", "SHARED_HISTORY_DELETION_FAILED")
                            } ?: "HELPER_FAILED"
                            val reason = result.optString("reason").takeIf {
                                it in setOf("ENOENT", "EACCES", "EPERM", "EINVAL", "EBUSY", "EAGAIN", "ERR_DLOPEN_FAILED",
                                    "MODULE_NOT_FOUND", "ERR_MODULE_NOT_FOUND", "ERR_INVALID_ARG_VALUE", "ERR_FLOCK_UNSUPPORTED_PLATFORM")
                            } ?: "INVALID"
                            throw IOException(if (deleting) "共享历史永久删除未完成；原归档保留，请稍后重试（$code/$reason）"
                            else if (operation == "credentials-write")
                                "DSH 凭据同步未完成，可能已写入部分修改；请刷新后重试（$operation/$code/$reason）"
                            else "DSH 共享数据校验失败；原记录保留（$operation/$code/$reason）")
                        }
                        result.getJSONObject("value")
                    } } catch (failure: Throwable) {
                        // Unblock pipe readers before structured cancellation
                        // joins them, including failed process-tree cleanup.
                        withContext(NonCancellable) {
                            runCatching { process.stop() }
                            runCatching { process.output.close() }
                            runCatching { process.errors.close() }
                            runCatching { process.input.close() }
                        }
                        throw failure
                    }
                }
            } finally { withContext(NonCancellable) { process.stop() } }
        }
    }
}
