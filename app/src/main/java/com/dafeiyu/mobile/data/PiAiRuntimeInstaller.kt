package com.dafeiyu.mobile.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
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

data class ModelRuntimeProgress(val completedPackages: Int, val totalPackages: Int, val detail: String,
    val receivedBytes: Long = 0, val totalBytes: Long? = null, val threads: Int = 0)

/** Installs only the reviewed dependency graph. npm scripts and mutable dependency resolution never run. */
class PiAiRuntimeInstaller(context: Context, private val runtime: LinuxRuntimeManager = LinuxRuntimeManager(context)) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "model-runtime")
    private val slots = File(root, "slots")
    private val receipt = AtomicFile(File(root, "active.json"))
    private val lock = locks.computeIfAbsent(root.canonicalPath) { Mutex() }
    private val lockText by lazy { app.assets.open("model-worker/runtime-lock.json").bufferedReader().use { it.readText() } }
    private val lockHash by lazy { MessageDigest.getInstance("SHA-256").digest(lockText.toByteArray()).joinToString("") { "%02x".format(it) } }

    fun current(): File? {
        if (!receipt.baseFile.exists()) return null
        val raw = receipt.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (output.size() <= 8192) {
                val count = input.read(buffer, 0, minOf(buffer.size, 8193 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            require(output.size() <= 8192); output.toString("UTF-8")
        }
        val state = JSONObject(raw)
        if (state.optInt("schema") != 1 || state.optString("lock_sha256") != lockHash) return null
        val name = state.getString("slot")
        require(name.matches(Regex("pi-ai-0[.]87[.]1-[a-f0-9-]{36}")))
        val directory = File(slots, name)
        require(!LinuxArchiveExtractor.isSymlink(directory) && directory.canonicalFile.parentFile == slots.canonicalFile)
        return directory.takeIf { File(it, "node_modules/@earendil-works/pi-ai/dist/index.js").isFile &&
            File(it, "installed-lock.json").isFile }
    }

    suspend fun install(source: String, progress: (ModelRuntimeProgress) -> Unit = {}): File = withContext(Dispatchers.IO) {
        lock.withLock {
            current()?.let { return@withLock it }
            require(source in setOf("official", "cdn", "backend"))
            require(runtime.managedProcesses.value.none { it.id == "model-worker" }) { "模型运行器正在使用组件，请先停止" }
            runtime.refresh()
            require(runtime.snapshot.value.first { it.id == "node" }.status == LinuxComponentStatus.READY) { "请先安装并检测 Ubuntu 和 Node.js" }
            for (directory in listOf(root, slots, File(root, "downloads"))) require(!LinuxArchiveExtractor.isSymlink(directory) &&
                (directory.isDirectory || directory.mkdirs()))
            val manifest = JSONObject(lockText)
            require(manifest.getInt("schema") == 1 && manifest.getString("version") == "0.87.1")
            val packages = manifest.getJSONArray("artifacts")
            require(packages.length() in 1..200 && root.usableSpace >= 512L * 1024 * 1024) { "模型运行组件需至少 512 MiB 可用空间" }
            val stage = File(slots, "stage-${UUID.randomUUID()}")
            require(stage.mkdir())
            var published = false
            var candidate = stage
            try {
                for (index in 0 until packages.length()) {
                    currentCoroutineContext().ensureActive()
                    val entry = packages.getJSONObject(index)
                    val relative = entry.getString("path")
                    require(relative.startsWith("node_modules/") && relative.matches(Regex("[A-Za-z0-9@_./-]{1,240}")) &&
                        relative.split('/').none { it == ".." || it.isEmpty() })
                    val packageName = entry.getString("name")
                    val url = entry.getString("url")
                    require(url.startsWith("https://registry.npmjs.org/") && url.endsWith(".tgz"))
                    val archive = File(root, "downloads/" + MessageDigest.getInstance("SHA-256").digest(entry.getString("integrity").toByteArray())
                        .joinToString("") { "%02x".format(it) } + ".tgz")
                    ComponentDownloadClient(app).downloadArtifact(ArtifactDownloadRequest(listOf(url), archive,
                        ArtifactChecksum.parse(entry.getString("integrity")), maxBytes = 64L * 1024 * 1024), source) { download ->
                        progress(ModelRuntimeProgress(index, packages.length(), "下载 $packageName ${entry.getString("version")}",
                            download.receivedBytes, download.totalBytes, download.threads))
                    }
                    val unpack = File(stage, "unpack-$index").apply { require(mkdir()) }
                    val coroutine = currentCoroutineContext()
                    val duplicatePaths = PiAiNpmArchivePolicy.identicalDuplicatePaths(archive, packageName,
                        entry.getString("version"), entry.getString("integrity")) { coroutine.ensureActive() }
                    archive.inputStream().use { input -> LinuxArchiveExtractor.extract(input, unpack,
                        entry.getLong("maximumExpandedBytes"), { coroutine.ensureActive() }, identicalRegularDuplicates = duplicatePaths) }
                    val children = unpack.listFiles() ?: error("无法读取包内容")
                    // DefinitelyTyped's fixed @types/node and @types/retry
                    // archives use node/ and retry/, rather than package/.
                    // Admit only one real directory, then verify its exact
                    // identity before moving any content into node_modules.
                    require(children.size == 1 && children.single().name in setOf("package", packageName.substringAfterLast('/')) &&
                        children.single().isDirectory && !LinuxArchiveExtractor.isSymlink(children.single())) { "npm 包归档布局无效" }
                    val metadata = File(children.single(), "package.json")
                    require(!LinuxArchiveExtractor.isSymlink(metadata) && metadata.isFile && metadata.length() in 1..(1024L * 1024) &&
                        metadata.canonicalFile.parentFile == children.single().canonicalFile) { "npm 包身份文件无效" }
                    val actual = JSONObject(metadata.readText())
                    require(actual.getString("name") == packageName && actual.getString("version") == entry.getString("version")) {
                        "npm 包身份与锁定目录不符"
                    }
                    val target = File(stage, relative)
                    require(target.canonicalPath.startsWith(stage.canonicalPath + File.separator) && !target.exists())
                    require(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
                    require(children.single().renameTo(target))
                    require(unpack.delete())
                    progress(ModelRuntimeProgress(index + 1, packages.length(), "已验证 $packageName"))
                }
                File(stage, "package.json").writeText("""{"name":"dafeiyu-go-model-runtime","version":"0.87.1","private":true,"type":"module"}""")
                File(stage, "installed-lock.json").writeText(lockText)
                currentCoroutineContext().ensureActive()
                val name = "pi-ai-0.87.1-${UUID.randomUUID()}"
                val destination = File(slots, name)
                require(stage.renameTo(destination))
                candidate = destination
                val output = receipt.startWrite()
                try {
                    output.write(JSONObject().put("schema", 1).put("slot", name).put("lock_sha256", lockHash).toString().toByteArray())
                    receipt.finishWrite(output)
                } catch (failure: Throwable) { receipt.failWrite(output); throw failure }
                published = true
                destination
            } finally { if (!published && candidate.exists()) LinuxArchiveExtractor.removeTree(candidate) }
        }
    }

    private companion object { val locks = ConcurrentHashMap<String, Mutex>() }
}

/** Exceptions witnessed in the exact published archives, never in arbitrary npm packages. */
internal object PiAiNpmArchivePolicy {
    private val reviewed = mapOf(
        "agent-base@7.1.4" to "sha512-MnA+YT8fwfJPgBx3m60MNqakm30XOkyIoH1y6huTQvC0PwZG7ki8NacLBcrPbNoo8vEZy7Jpuk7+jMO+CUovTQ==",
        "https-proxy-agent@7.0.6" to "sha512-vK9P5/iUfdl95AI+JVyUuIcVtd4ofvtrOr3HNtM2yxC9bnMbEdp3x01OhQNnjb8IJYi38VlTE3mBXwcfvywuSw==",
    )

    fun identicalDuplicatePaths(archive: File, name: String, version: String, integrity: String,
        checkCancelled: () -> Unit): Set<String> {
        if (reviewed["$name@$version"] != integrity) return emptySet()
        require(archive.isFile && !LinuxArchiveExtractor.isSymlink(archive) && archive.length() in 1..64L * 1024 * 1024) {
            "固定 npm 归档无效"
        }
        val checksum = ArtifactChecksum.parse(integrity)
        val digest = checksum.digestForSize(archive.length())
        archive.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        require(digest.digest().joinToString("") { "%02x".format(it) } == checksum.value) { "固定 npm 归档摘要不符" }
        return setOf("package/dist/index.js")
    }
}
