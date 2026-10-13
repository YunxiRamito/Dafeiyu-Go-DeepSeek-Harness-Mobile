package com.dafeiyu.mobile.data

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

data class DshInstallProgress(
    val phase: String,
    val receivedBytes: Long = 0,
    val totalBytes: Long? = null,
    val expandedBytes: Long = 0,
    val threads: Int = 0,
)

data class DshRuntimeSlot(
    val directory: File,
    val manifest: DshRuntimeManifest,
    val installedAt: Long,
)

/** Candidate-slot installation for the complete DSH Node bundle. */
class DshRuntimeInstaller(
    context: Context,
    private val runtime: LinuxRuntimeManager = LinuxRuntimeManager(context),
    private val downloader: suspend (ArtifactDownloadRequest, String, (ArtifactDownloadProgress) -> Unit) -> ArtifactDownloadResult =
        { request, source, progress -> ComponentDownloadClient(context).downloadArtifact(request, source, progress) },
) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "dsh-runtime")
    private val slots = File(root, "slots")
    private val downloads = File(root, "downloads")
    private val active = AtomicFile(File(root, "active.json"))
    private val previous = AtomicFile(File(root, "previous.json"))
    private val lock = locks.computeIfAbsent(root.canonicalPath) { Mutex() }

    fun current(): DshRuntimeSlot? = runCatching { readCurrent() }.getOrNull()

    fun canRollback(): Boolean = runCatching { readSlot(previous) != null }.getOrDefault(false)

    /** Serializes publication with the real process launch acquiring its lease. */
    internal suspend fun <T> withCurrentSlot(manifestDigest: String, launch: suspend (DshRuntimeSlot) -> T): T = lock.withLock {
        val slot = readCurrent() ?: error("DSH 运行包未安装")
        check(slot.manifest.digest() == manifestDigest) { "DSH 版本已变化，请重试启动" }
        launch(slot)
    }

    /** Swap the last published runtime back into the active receipt without deleting either slot. */
    suspend fun rollback(): DshRuntimeSlot? = withContext(Dispatchers.IO) {
        lock.withLock {
            check(runtime.managedProcesses.value.isEmpty()) { "运行时仍有进程占用，不能回退 DSH" }
            val state = readState() ?: return@withLock null
            val old = state.optJSONObject("previous") ?: return@withLock null
            val current = state.getJSONObject("active")
            val candidate = decodeSlot(old)
            writeState(old, current)
            candidate
        }
    }

    suspend fun install(
        manifest: DshRuntimeManifest,
        source: String,
        progress: (DshInstallProgress) -> Unit = {},
    ): DshRuntimeSlot = withContext(Dispatchers.IO) {
        lock.withLock {
            require(source in setOf("official", "cdn", "backend")) { "未知下载源" }
            require(manifest.dependencyLockSha256 != null) { "DSH 完整依赖包必须提供锁定清单摘要" }
            current()?.takeIf { it.manifest.digest() == manifest.digest() }?.let { return@withLock it }
            check(runtime.managedProcesses.value.isEmpty()) {
                "运行时仍有进程占用，请先停止 DSH 和模型运行器后再安装"
            }
            ensureDirectories()
            require(root.usableSpace >= manifest.maximumExpandedBytes + manifest.archiveSize + 128L * 1024 * 1024) {
                "可用空间不足；安装需要保留候选环境和回滚空间"
            }
            val archive = File(downloads, "${manifest.runtimeId}-${manifest.archiveSha256.lowercase()}.tar.gz")
            val downloaded = downloader(
                ArtifactDownloadRequest(
                    urls = listOf(manifest.archiveUrl),
                    destination = archive,
                    checksum = ArtifactChecksum("SHA-256", manifest.archiveSha256),
                    expectedSize = manifest.archiveSize,
                    maxBytes = manifest.archiveSize,
                ),
                source,
            ) { value ->
                progress(DshInstallProgress("下载并校验 DSH 运行包", value.receivedBytes, value.totalBytes, threads = value.threads))
            }
            check(downloaded.file.canonicalFile == archive.canonicalFile) { "下载器返回了错误的 DSH 归档路径" }
            verifyArchive(archive, manifest)
            val stage = File(slots, "stage-${UUID.randomUUID()}")
            require(stage.mkdir()) { "无法创建 DSH 候选目录" }
            var published: File? = null
            var committed = false
            try {
                val coroutine = currentCoroutineContext()
                var expanded = 0L
                progress(DshInstallProgress("安全解包 DSH 运行包"))
                archive.inputStream().use { input ->
                    LinuxArchiveExtractor.extract(input, stage, manifest.maximumExpandedBytes, {
                        coroutine.ensureActive()
                    }) { amount ->
                        expanded = amount
                        progress(DshInstallProgress("安全解包 DSH 运行包", expandedBytes = amount))
                    }
                }
                val content = normalizeBundleRoot(stage, manifest) { coroutine.ensureActive() }
                validateBundle(content, manifest)
                progress(DshInstallProgress("验证 Node、DSH 版本及已认证网页", expandedBytes = expanded))
                probeCandidate(content, manifest)
                coroutine.ensureActive()
                val name = "${manifest.runtimeId}-${manifest.archiveSha256.take(16)}-${UUID.randomUUID()}"
                val destination = File(slots, name)
                require(stage.renameTo(destination)) { "无法提交 DSH 候选目录" }
                published = destination
                val receipt = JSONObject()
                    .put("schema", 1)
                    .put("slot", name)
                    .put("installed_at", System.currentTimeMillis())
                    .put("manifest_sha256", manifest.digest())
                    .put("manifest", manifest.toJson())
                writeState(receipt, readState()?.optJSONObject("active"))
                committed = true
                DshRuntimeSlot(destination, manifest, receipt.getLong("installed_at"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                // Never unlink a candidate still owned by a failed cleanup.
                val probeStillRunning = runtime.hasProcessLease(stage) || published?.let(runtime::hasProcessLease) == true
                if (!committed && !probeStillRunning) runCatching { LinuxArchiveExtractor.removeTree(stage) }
                if (published != null && !committed && !probeStillRunning) runCatching { LinuxArchiveExtractor.removeTree(published!!) }
            }
        }
    }

    private fun readCurrent(): DshRuntimeSlot? = readState()?.optJSONObject("active")?.let(::decodeSlot)

    private fun readSlot(file: AtomicFile): Pair<DshRuntimeSlot, ByteArray>? {
        val receipt = readState()?.optJSONObject(if (file === previous) "previous" else "active") ?: return null
        return decodeSlot(receipt) to receipt.toString().toByteArray(Charsets.UTF_8)
    }

    private fun decodeSlot(receipt: JSONObject): DshRuntimeSlot {
        require(receipt.optInt("schema") == 1)
        val manifest = DshRuntimeManifest.fromJson(receipt.getJSONObject("manifest"))
        require(receipt.getString("manifest_sha256") == manifest.digest())
        val slotName = receipt.getString("slot")
        require(slotName.matches(Regex("[A-Za-z0-9._-]{1,180}")))
        val directory = File(slots, slotName)
        require(directory.canonicalFile.parentFile == slots.canonicalFile && !LinuxArchiveExtractor.isSymlink(directory))
        require(directory.isDirectory)
        validateBundle(directory, manifest)
        return DshRuntimeSlot(directory, manifest, receipt.optLong("installed_at", 0L))
    }

    private fun readState(): JSONObject? {
        val raw = readReceiptBytes(active) ?: return null
        val value = JSONObject(String(raw, Charsets.UTF_8))
        if (value.optInt("schema") == 2) return value
        require(value.optInt("schema") == 1)
        return JSONObject().put("schema", 2).put("active", value).apply {
            readReceiptBytes(previous)?.let { put("previous", JSONObject(String(it, Charsets.UTF_8))) }
        }
    }

    private fun writeState(current: JSONObject, previousSlot: JSONObject?) {
        val value = JSONObject().put("schema", 2).put("active", current)
        previousSlot?.let { value.put("previous", it) }
        // Both pointers change in a single AtomicFile transaction. A crash
        // cannot leave a half-completed active/previous swap.
        writeReceipt(active, value.toString().toByteArray(Charsets.UTF_8))
    }

    private suspend fun probeCandidate(directory: File, manifest: DshRuntimeManifest) {
        runtime.refresh()
        check(runtime.snapshot.value.any { it.id == "node" && it.status == LinuxComponentStatus.READY }) {
            "Node.js 组件尚未就绪，无法验证 DSH"
        }
        val identity = runtime.nodeIdentity()
        check(identity.platform == "linux" && identity.architecture == manifest.nodeArchitecture) { "DSH Linux 依赖包 ABI 与本机不匹配" }
        check(identity.version == "v${manifest.nodeVersion.removePrefix("v")}") { "DSH 需要 Node ${manifest.nodeVersion}，本机实际为 ${identity.version}" }
        probeVersion(directory, manifest)
        val probeHome = File(root, "probe-home-${UUID.randomUUID()}")
        require(probeHome.mkdir()) { "无法创建隔离的 DSH 候选数据目录" }
        val manager = DshRuntimeManager(
            currentSlot = { DshRuntimeSlot(directory, manifest, 0) },
            launchProcess = { slot, port, _ -> runtime.startManagedProcess(
                "dsh-probe-${UUID.randomUUID()}", listOf("/usr/local/bin/node", "/dafeiyu-dsh/${manifest.entryPoint}",
                    "web", "--host", "127.0.0.1", "--port", port.toString(), "--no-open"),
                mapOf("/dafeiyu-dsh" to slot.directory, "/dafeiyu-dsh-home" to probeHome),
                environment = mapOf("DSH_HOME" to "/dafeiyu-dsh-home")) },
            queryHealth = { url -> DshWebHealth.check(url) },
        )
        try {
            val result = manager.start()
            check(result.status == DshServiceStatus.RUNNING && result.healthCode == 200) { "DSH 候选网页验证失败" }
        } finally {
            withContext(NonCancellable) {
                manager.stop()
                manager.close()
                if (!runtime.hasProcessLease(probeHome)) LinuxArchiveExtractor.removeTree(probeHome)
            }
        }
    }

    private suspend fun probeVersion(directory: File, manifest: DshRuntimeManifest) {
        val process = runtime.startManagedProcess(
            id = "dsh-probe-${UUID.randomUUID()}".take(76),
            guestCommand = listOf("/usr/local/bin/node", "-e", VERSION_PROBE, "/dafeiyu-dsh/${manifest.entryPoint}"),
            mounts = mapOf("/dafeiyu-dsh" to directory),
        )
        try {
            val output = ByteArrayOutputStream()
            val errors = ByteArrayOutputStream()
            val text = withTimeout(30_000) {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    for ((input, target) in listOf(process.output to output, process.errors to errors)) {
                        while (input.available() > 0) {
                            require(target.size() < 64 * 1024) { "DSH 版本探测输出超过限制" }
                            val buffer = ByteArray(minOf(8192, input.available(), 64 * 1024 - target.size()))
                            val count = input.read(buffer)
                            if (count < 0) break
                            target.write(buffer, 0, count)
                        }
                    }
                    val value = output.toString(Charsets.UTF_8.name())
                    if (value.lines().any { it.startsWith("__DSH_PROBE_DONE__=") }) return@withTimeout value
                    check(process.isAlive) { "DSH 版本探测提前退出" }
                    delay(10)
                }
                @Suppress("UNREACHABLE_CODE") ""
            }
            val lines = text.trim().lines().filter { it.isNotBlank() }
            check(lines == listOf(manifest.dshVersion, "__DSH_PROBE_DONE__=0")) { "DSH CLI 实际版本与依赖包描述不符或入口无法载入" }
        } finally {
            withContext(NonCancellable) { process.stop() }
        }
    }

    private suspend fun verifyArchive(archive: File, manifest: DshRuntimeManifest) {
        require(archive.isFile && !LinuxArchiveExtractor.isSymlink(archive) && archive.length() == manifest.archiveSize) { "DSH 归档大小不符" }
        require(fileSha256(archive) == manifest.archiveSha256.lowercase()) { "DSH 归档 SHA-256 不符" }
    }

    private fun validateBundle(directory: File, manifest: DshRuntimeManifest) {
        require(directory.isDirectory && !LinuxArchiveExtractor.isSymlink(directory))
        for (relative in manifest.bundleRequiredFiles) {
            val file = File(directory, relative)
            require(file.canonicalFile.path.startsWith(directory.canonicalFile.path + File.separator)) { "DSH 文件越界" }
            require(file.isFile) { "DSH 运行包缺少 $relative" }
        }
        val entry = File(directory, manifest.entryPoint)
        require(entry.canonicalFile.path.startsWith(directory.canonicalFile.path + File.separator) && entry.isFile) {
            "DSH 入口文件缺失"
        }
        val packageJson = File(directory, "node_modules/@deepseek-ai/dsh/package.json")
        if (packageJson.isFile) {
            val version = JSONObject(packageJson.readText()).optString("version")
            require(version == manifest.dshVersion) { "DSH 包版本 $version 与描述 ${manifest.dshVersion} 不符" }
        }
        manifest.dependencyLockSha256?.let { digest ->
            val dependencyLock = File(directory, "dsh-bundle.lock.json")
            require(dependencyLock.isFile && !LinuxArchiveExtractor.isSymlink(dependencyLock) && dependencyLock.length() <= 4 * 1024 * 1024) { "DSH 依赖锁定清单缺失或过大" }
            val actual = MessageDigest.getInstance("SHA-256").digest(dependencyLock.readBytes()).joinToString("") { "%02x".format(it) }
            require(actual == digest.lowercase()) { "DSH 依赖锁定清单摘要不符" }
            val lock = JSONObject(dependencyLock.readText())
            require(lock.getString("dshVersion") == manifest.dshVersion && lock.getString("architecture") == manifest.nodeArchitecture) { "DSH 依赖锁定清单版本或 ABI 不符" }
        }
    }

    private fun normalizeBundleRoot(stage: File, manifest: DshRuntimeManifest, checkCancelled: () -> Unit): File {
        if (File(stage, manifest.entryPoint).isFile) return stage
        val candidates = stage.listFiles()?.filter { it.isDirectory && !LinuxArchiveExtractor.isSymlink(it) }.orEmpty()
        val matching = candidates.filter { File(it, manifest.entryPoint).isFile }
        require(matching.size == 1) { "DSH 运行包根目录不明确" }
        require(stage.listFiles()?.size == 1) { "DSH 归档包装目录包含额外内容" }
        val root = matching.single()
        val temporary = File(slots, "normalize-${UUID.randomUUID()}")
        try {
            checkCancelled()
            require(root.renameTo(temporary)) { "无法整理 DSH 归档根目录" }
            LinuxArchiveExtractor.removeTree(stage)
            require(temporary.renameTo(stage))
            return stage
        } catch (failure: Throwable) {
            runCatching { LinuxArchiveExtractor.removeTree(temporary) }
            throw failure
        }
    }

    private fun ensureDirectories() {
        for (directory in listOf(root, slots, downloads)) {
            require(!LinuxArchiveExtractor.isSymlink(directory) && (directory.isDirectory || directory.mkdirs()))
        }
    }

    private companion object {
        val locks = ConcurrentHashMap<String, Mutex>()
        const val VERSION_PROBE = "const {spawn}=require('node:child_process');const p=spawn(process.execPath,[process.argv[1],'--version'],{stdio:['ignore','pipe','pipe']});p.stdout.pipe(process.stdout,{end:false});p.stderr.pipe(process.stderr,{end:false});p.on('error',()=>{console.log('__DSH_PROBE_DONE__=launch_error');process.stdin.resume();});p.on('close',code=>{console.log('__DSH_PROBE_DONE__='+code);process.stdin.resume();});"

        suspend fun fileSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun readReceiptBytes(file: AtomicFile): ByteArray? {
            if (!file.baseFile.exists()) return null
            val output = ByteArrayOutputStream()
            file.openRead().use { input ->
                val buffer = ByteArray(8192)
                while (output.size() <= 128 * 1024) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 128 * 1024 + 1 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
            require(output.size() <= 128 * 1024) { "DSH 收据过大" }
            return output.toByteArray()
        }

        fun writeReceipt(file: AtomicFile, bytes: ByteArray) {
            require(bytes.size in 1..128 * 1024)
            val output = file.startWrite()
            try {
                output.write(bytes)
                file.finishWrite(output)
            } catch (failure: Throwable) {
                file.failWrite(output)
                throw failure
            }
        }
    }
}

