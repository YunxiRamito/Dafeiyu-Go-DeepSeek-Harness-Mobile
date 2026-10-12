package com.dafeiyu.mobile.data

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Installs Ubuntu userspace and tools with PRoot. It provides neither a VM nor a security boundary. */
class LinuxRuntimeManager(context: Context) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "linux-runtime")
    private val shared = stores.computeIfAbsent(root.canonicalPath) { Shared() }
    private val slots = File(root, "slots")
    private val downloads = File(root, "downloads")
    private val active = AtomicFile(File(root, "active.json"))
    val snapshot: StateFlow<List<LinuxComponentState>> = shared.snapshot.asStateFlow()

    /** Install belongs to the application scope; leaving a settings page does not abandon it. */
    suspend fun install(id: String, source: String) {
        require(id in LinuxRuntimeRelease.ids) { "未知 Linux 组件" }
        require(source in setOf("official", "cdn", "backend")) { "未知下载源" }
        val task = shared.startLock.withLock {
            check(shared.installJob?.isActive != true) { "已有组件正在安装" }
            shared.scope.async(start = CoroutineStart.LAZY) {
                shared.operations.withLock { installLocked(id, source) }
            }.also { shared.installJob = it; it.start() }
        }
        task.await()
    }

    fun cancelInstall() { shared.installJob?.cancel(CancellationException("用户取消 Linux 安装")) }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        shared.operations.withLock {
            val unavailable = unavailableReason()
            if (unavailable != null) {
                shared.snapshot.value = LinuxRuntimeRelease.initial().map {
                    it.copy(status = LinuxComponentStatus.UNSUPPORTED, detail = unavailable)
                }
            } else {
                ensureLayout()
                prepareNative()
                val directory = activeRoot()
                shared.snapshot.value = if (directory == null) LinuxRuntimeRelease.initial() else probeStates(directory)
            }
        }
    }

    suspend fun execute(language: String, script: String, workspace: File,
        timeoutSeconds: Int = 30, source: String? = null): LinuxExecutionResult = withContext(Dispatchers.IO) {
        require(script.toByteArray(Charsets.UTF_8).size <= 512 * 1024) { "脚本超过 512 KiB" }
        require(timeoutSeconds in 1..120) { "执行超时必须为 1–120 秒" }
        require(language in setOf("bash", "shell", "sh", "node", "javascript", "js", "python", "python3")) { "不支持此脚本语言" }
        require(source == null || source in setOf("official", "cdn", "backend")) { "未知下载源" }
        val folder = workspace.canonicalFile
        val privateRoots = listOf(app.filesDir.canonicalFile, app.noBackupFilesDir.canonicalFile)
        require(folder.isDirectory && privateRoots.any { folder.path.startsWith(it.path + File.separator) }) {
            "执行工作区必须位于应用私有目录"
        }
        require(':' !in folder.path) { "工作区路径不能含冒号" }
        shared.operations.withLock {
            unavailableReason()?.let { throw IOException(it) }
            ensureLayout()
            prepareNative()
            val directory = activeRoot() ?: throw IOException("请先在组件页安装 Ubuntu")
            if (source != null) configureSource(directory, source)
            val normalized = when (language) {
                "node", "javascript", "js" -> "node"
                "python", "python3" -> "python"
                else -> "bash"
            }
            val suffix = when (normalized) { "node" -> "js"; "python" -> "py"; else -> "sh" }
            val request = File(root, "requests/${UUID.randomUUID()}.$suffix")
            require(request.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
            request.writeText(script, Charsets.UTF_8)
            Os.chmod(request.path, 0x180)
            try {
                val command = when (normalized) {
                    "node" -> listOf("/usr/local/bin/node", "/dafeiyu-script")
                    "python" -> listOf("/usr/bin/python3.12", "/dafeiyu-script")
                    else -> listOf("/bin/bash", "/dafeiyu-script")
                }
                run(directory, command, timeoutSeconds, folder, request)
            } finally { request.delete() }
        }
    }

    private suspend fun installLocked(id: String, source: String) {
        val previousStates = shared.snapshot.value
        var candidate: File? = null
        var committed = false
        try {
            unavailableReason()?.let { throw IOException(it) }
            ensureLayout()
            prepareNative()
            val coroutine = currentCoroutineContext()
            val cancelCheck = { coroutine.ensureActive() }
            val current = activeRoot()
            val assets = ArrayList<LinuxRuntimeAsset>()
            if (current == null || id == "ubuntu" || id == "node") assets += LinuxRuntimeRelease.ubuntu
            when (id) {
                "git" -> assets += LinuxRuntimeRelease.git
                "python" -> { assets += LinuxRuntimeRelease.python; assets += LinuxRuntimeRelease.pythonSupport }
                "pnpm" -> assets += LinuxRuntimeRelease.pnpm
            }
            val cloneBytes = current?.let { LinuxArchiveExtractor.treeBytes(it, cancelCheck) } ?: 0L
            val apkCache = File(downloads, "dsha-${LinuxRuntimeRelease.TAG}.apk")
            val cached = apkCache.isFile && apkCache.length() == 286_371_170L &&
                apkCache.inputStream().use { digest(it, cancelCheck) } == LinuxRuntimeRelease.APK_SHA256
            val downloadBytes = if (cached) 0L else 286_371_170L
            val requiredBytes = Math.addExact(cloneBytes, assets.sumOf { it.expandedBytes }) + downloadBytes + 64L * 1024 * 1024
            require(root.usableSpace >= requiredBytes) { "可用空间不足；安装需 ${requiredBytes / (1024 * 1024)} MiB 用于下载、候选环境和回滚" }
            update(id, "下载并验证 ${LinuxRuntimeRelease.TAG} 安装资源", null)
            val apk = ComponentDownloadClient(app).download(LinuxRuntimeRelease.APK_URL,
                LinuxRuntimeRelease.APK_SHA256, File(downloads, "dsha-${LinuxRuntimeRelease.TAG}.apk"), source) { received, total ->
                update(id, "下载并校验组件资源：${received / (1024 * 1024)} MiB" +
                    if (total != null) " / ${total / (1024 * 1024)} MiB" else "", total?.takeIf { it > 0 }?.let { received.toFloat() / it })
            }
            val stage = File(slots, "stage-${UUID.randomUUID()}")
            require(stage.mkdir()) { "无法创建候选运行环境" }
            candidate = stage
            // All components use one guest filesystem. An overlay is built on a clone, never the active slot.
            if (current != null) {
                update(id, "复制当前环境，保留已安装组件", null)
                LinuxArchiveExtractor.copyTree(current, stage, cancelCheck)
            }
            ZipFile(apk).use { archive ->
                for (asset in assets) {
                    cancelCheck()
                    update(id, "校验 ${asset.name}", null)
                    val entry = archive.getEntry("assets/${asset.name}") ?: throw IOException("固定安装包缺少 ${asset.name}")
                    require(entry.size in 1..128L * 1024 * 1024) { "组件压缩资源大小无效" }
                    val actual = archive.getInputStream(entry).use { digest(it, cancelCheck) }
                    require(actual == asset.sha256) { "${asset.name} SHA-256 校验失败" }
                    update(id, "安全解包 ${asset.name}", null)
                    archive.getInputStream(entry).use { input ->
                        LinuxArchiveExtractor.extract(input, stage, asset.expandedBytes, cancelCheck, selected = { path ->
                            current == null || asset != LinuxRuntimeRelease.ubuntu ||
                                listOf("root", "home", "workspace").none { path == it || path.startsWith("$it/") }
                        }) { expanded ->
                            update(id, "解包 ${asset.name}：${expanded / (1024 * 1024)} MiB", null)
                        }
                    }
                }
            }
            configureGuest(stage, id, source)
            update(id, "启动 PRoot 并检测真实版本", null)
            val states = probeStates(stage)
            require(states.first { it.id == "ubuntu" }.status == LinuxComponentStatus.READY) {
                states.first { it.id == "ubuntu" }.detail
            }
            require(states.first { it.id == id }.status == LinuxComponentStatus.READY) { states.first { it.id == id }.detail }
            cancelCheck()
            val name = "${LinuxRuntimeRelease.TAG}-${UUID.randomUUID()}"
            val published = File(slots, name)
            require(stage.renameTo(published)) { "无法提交候选环境" }
            candidate = published
            val receipt = JSONObject().put("schema", 1).put("slot", name).put("release", LinuxRuntimeRelease.TAG)
                .put("apk_sha256", LinuxRuntimeRelease.APK_SHA256).put("source", source)
            val output = active.startWrite()
            try {
                output.write(receipt.toString().toByteArray(Charsets.UTF_8))
                active.finishWrite(output)
            } catch (failure: Exception) { active.failWrite(output); throw failure }
            committed = true
            shared.snapshot.value = states
            // Readers/executors share the same mutex; the previous immutable slot is now unreferenced.
            if (current != null) runCatching { LinuxArchiveExtractor.removeTree(current) }
        } catch (cancelled: CancellationException) {
            shared.snapshot.value = previousStates.map { if (it.id == id) it.copy(detail = "安装已取消；原环境保留", progress = null) else it }
            throw cancelled
        } catch (failure: Exception) {
            val unsupported = unavailableReason()
            shared.snapshot.value = previousStates.map {
                if (it.id == id || unsupported != null) it.copy(status = if (unsupported != null) LinuxComponentStatus.UNSUPPORTED else LinuxComponentStatus.ERROR,
                    detail = unsupported ?: (failure.message ?: "组件安装失败"), progress = null) else it
            }
            throw failure
        } finally {
            if (!committed && candidate != null) runCatching { LinuxArchiveExtractor.removeTree(candidate) }
        }
    }

    private fun configureGuest(directory: File, id: String, source: String) {
        for (path in listOf("tmp", "root", "root/.cache", "root/dsh-bin", "etc", "workspace")) {
            require(File(directory, path).let { it.isDirectory || it.mkdirs() }) { "无法准备运行目录" }
        }
        writeGuest(directory, "etc/resolv.conf", "nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        writeGuest(directory, "etc/hosts", "127.0.0.1 localhost\n::1 localhost\n")
        configureSource(directory, source)
        if (id == "python") {
            val command = File(directory, "usr/bin/python3")
            if (command.exists() || LinuxArchiveExtractor.isSymlink(command)) require(command.delete())
            Os.symlink("python3.12", command.path)
        }
        if (id == "pnpm") writeGuest(directory, "root/dsh-bin/pnpm",
            "#!/bin/sh\nexec /usr/local/bin/node /usr/local/lib/dsha-pnpm/bin/pnpm.cjs \"\$@\"\n", true)
        // PRoot guest root is a uid presentation; every process still has the app's Android uid.
        val passwd = File(directory, "etc/passwd")
        if (!passwd.exists()) writeGuest(directory, "etc/passwd", "root:x:0:0:root:/root:/bin/bash\n")
    }

    private fun configureSource(directory: File, source: String) {
        writeGuest(directory, "etc/dafeiyu-download-source", source)
        if (source == "backend") {
            val resource = app.resources.getIdentifier("dafeiyu_download_ca", "raw", app.packageName)
            require(resource != 0) { "下载服务器 CA 资源缺失" }
            val ca = app.resources.openRawResource(resource).use { it.readBytes() }
            require(ca.size <= 128 * 1024)
            writeGuest(directory, "etc/dafeiyu-download-ca.pem", ca.toString(Charsets.US_ASCII))
        }
    }

    private fun writeGuest(directory: File, path: String, text: String, executable: Boolean = false) {
        val file = File(directory, path)
        require(file.parentFile?.canonicalPath?.startsWith(directory.canonicalPath + File.separator) == true)
        require(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        if (LinuxArchiveExtractor.isSymlink(file)) require(file.delete())
        file.writeText(text, Charsets.UTF_8)
        Os.chmod(file.path, if (executable) 0x1c0 else 0x180)
    }

    private suspend fun probeStates(directory: File): List<LinuxComponentState> {
        val command = """
            if [ -r /etc/os-release ] && [ -x /bin/bash ]; then . /etc/os-release; printf 'DAFEIYU_COMPONENT_ubuntu=%s\n' "${'$'}VERSION_ID"; fi
            if [ -x /usr/local/bin/node ]; then v=$(/usr/local/bin/node --version 2>/dev/null) && printf 'DAFEIYU_COMPONENT_node=%s\n' "${'$'}v"; fi
            if [ -x /usr/bin/git ]; then v=$(/usr/bin/git --version 2>/dev/null) && printf 'DAFEIYU_COMPONENT_git=%s\n' "${'$'}v"; fi
            if [ -f /usr/local/lib/dsha-pnpm/bin/pnpm.cjs ]; then v=$(/usr/local/bin/node /usr/local/lib/dsha-pnpm/bin/pnpm.cjs --version 2>/dev/null) && printf 'DAFEIYU_COMPONENT_pnpm=%s\n' "${'$'}v"; fi
            if [ -x /usr/bin/python3.12 ]; then v=$(/usr/bin/python3.12 -c 'import sys,ssl,sqlite3,readline; print(sys.version.split()[0])' 2>/dev/null) && printf 'DAFEIYU_COMPONENT_python=%s\n' "${'$'}v"; fi
            exit 0
        """.trimIndent()
        val result = run(directory, listOf("/bin/bash", "-c", command), 30)
        val versions = result.stdout.lineSequence().mapNotNull { line ->
            if (!line.startsWith("DAFEIYU_COMPONENT_") || '=' !in line) null
            else line.substringAfter("DAFEIYU_COMPONENT_").substringBefore('=') to line.substringAfter('=').trim()
        }.toMap()
        return LinuxRuntimeRelease.initial().map { component ->
            val version = versions[component.id].orEmpty()
            val valid = when (component.id) {
                "ubuntu" -> version == "24.04"
                "node" -> version == "v24.19.0"
                "git" -> version == "git version 2.43.0"
                "pnpm" -> version == "10.34.5"
                "python" -> version.startsWith("3.12.")
                else -> false
            }
            if (valid && result.exitCode == 0 && !result.timedOut) component.copy(
                status = LinuxComponentStatus.READY, version = version.removePrefix("v").removePrefix("git version "),
                detail = if (component.id == "ubuntu") "版本检测通过；PRoot 用户空间，共享 Android 内核，无需 root"
                    else "在 Ubuntu 中启动并检测通过")
            else component.copy(status = if (component.id == "ubuntu" || version.isNotEmpty()) LinuxComponentStatus.ERROR else LinuxComponentStatus.MISSING,
                version = version, detail = if (result.timedOut) "版本检测超时，尚未就绪"
                    else if (result.exitCode != 0) "PRoot 启动失败：${result.stderr.take(600)}"
                    else if (version.isNotEmpty()) "版本与固定发行资源不符"
                    else "组件尚未安装或启动检测失败")
        }
    }

    private suspend fun run(directory: File, guestCommand: List<String>, timeoutSeconds: Int,
        workspace: File? = null, request: File? = null): LinuxExecutionResult = coroutineScope {
        val native = File(app.applicationInfo.nativeLibraryDir)
        val argv = arrayListOf(File(native, "liblinux_proot.so").path, "-L", "--kill-on-exit", "-0",
            "--rootfs=${directory.path}", "--cwd=${if (workspace == null) "/root" else "/workspace"}")
        for (path in listOf("/dev", "/proc", "/sys", "/system", "/apex")) if (File(path).exists()) argv += listOf("-b", path)
        argv += listOf("-b", "/dev/urandom:/dev/random", "-b", "/proc/self/fd:/dev/fd")
        if (workspace != null) argv += listOf("-b", "${workspace.path}:/workspace")
        if (request != null) argv += listOf("-b", "${request.path}:/dafeiyu-script")
        argv += guestCommand
        val pidFile = File(root, "pid-${UUID.randomUUID()}")
        // Arguments stay separate. The trusted Android shell records its pid, then exec preserves it for PRoot.
        val launch = listOf("/system/bin/sh", "-c", "printf '%s\\n' \"\$\$\" > \"\$1\"; shift; exec \"\$@\"",
            "dafeiyu-linux", pidFile.path) + argv
        val builder = ProcessBuilder(launch).directory(root)
        val environment = builder.environment()
        environment.clear()
        environment.putAll(mapOf("ANDROID_ROOT" to "/system", "ANDROID_DATA" to "/data", "HOME" to "/root",
            "USER" to "root", "LOGNAME" to "root", "LANG" to "C.UTF-8", "TERM" to "dumb", "TMPDIR" to "/tmp",
            "PATH" to "/root/dsh-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "PROOT_TMP_DIR" to File(root, "tmp").path, "PROOT_LOADER" to File(native, "liblinux_prootloader.so").path,
            "PROOT_LOADER_32" to File(native, "liblinux_prootloader32.so").path,
            "LD_LIBRARY_PATH" to "${File(root, "lib").path}:${native.path}"))
        val source = File(directory, "etc/dafeiyu-download-source").takeIf { it.isFile && it.length() <= 32 }
            ?.readText(Charsets.UTF_8)?.trim() ?: "official"
        environment["npm_config_registry"] = if (source == "backend") "${ComponentDownloadSources.BACKEND}/api/npm/"
            else ComponentDownloadSources.npmRegistry(source)
        environment["npm_config_strict_ssl"] = "true"
        environment["GIT_CONFIG_COUNT"] = if (source == "backend") "3" else "1"
        environment["GIT_CONFIG_KEY_0"] = "http.sslVerify"
        environment["GIT_CONFIG_VALUE_0"] = "true"
        if (source == "backend") {
            environment["npm_config_cafile"] = "/etc/dafeiyu-download-ca.pem"
            environment["NODE_EXTRA_CA_CERTS"] = "/etc/dafeiyu-download-ca.pem"
            environment["GIT_CONFIG_KEY_1"] = "http.${ComponentDownloadSources.BACKEND}/.sslCAInfo"
            environment["GIT_CONFIG_VALUE_1"] = "/etc/dafeiyu-download-ca.pem"
            environment["GIT_CONFIG_KEY_2"] = "url.${ComponentDownloadSources.BACKEND}/api/git/.insteadOf"
            environment["GIT_CONFIG_VALUE_2"] = "https://github.com/"
        }
        val process = builder.start()
        var owner: OwnedProcess? = null
        process.outputStream.close()
        val output = async(Dispatchers.IO) { capture(process.inputStream) }
        val errors = async(Dispatchers.IO) { capture(process.errorStream) }
        var timedOut = false
        try {
            val ownershipStarted = System.nanoTime()
            while (process.isAlive && owner == null && System.nanoTime() - ownershipStarted < TimeUnit.MILLISECONDS.toNanos(1000)) {
                currentCoroutineContext().ensureActive()
                owner = readOwner(pidFile, File(native, "liblinux_proot.so").path)
                if (owner == null) delay(10)
            }
            check(!process.isAlive || owner != null) { "无法识别 PRoot 执行进程" }
            val start = System.nanoTime()
            while (process.isAlive) {
                currentCoroutineContext().ensureActive()
                if (System.nanoTime() - start >= TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())) {
                    timedOut = true
                    stop(process, owner)
                    break
                }
                delay(40)
            }
            // A spawned background child may inherit the pipes; PRoot's kill-on-exit owns its tracees.
            val drainStarted = System.nanoTime()
            while ((!output.isCompleted || !errors.isCompleted) &&
                System.nanoTime() - drainStarted < TimeUnit.SECONDS.toNanos(2)) {
                currentCoroutineContext().ensureActive()
                delay(20)
            }
            val drainTruncated = !output.isCompleted || !errors.isCompleted
            if (!output.isCompleted) process.inputStream.close()
            if (!errors.isCompleted) process.errorStream.close()
            val out = output.await()
            val err = errors.await()
            LinuxExecutionResult(if (timedOut) 124 else process.exitValue(), out.text, err.text, timedOut, drainTruncated || out.truncated || err.truncated)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    if (process.isAlive) {
                        // Cancellation can arrive during the initial shell-to-PRoot exec transition.
                        val cleanupStarted = System.nanoTime()
                        while (owner == null && process.isAlive &&
                            System.nanoTime() - cleanupStarted < TimeUnit.MILLISECONDS.toNanos(1000)) {
                            owner = readOwner(pidFile, File(native, "liblinux_proot.so").path)
                            if (owner == null) delay(10)
                        }
                        stop(process, owner)
                    }
                } finally {
                    process.inputStream.close()
                    process.errorStream.close()
                    output.cancel()
                    errors.cancel()
                    pidFile.delete()
                }
            }
        }
    }

    private data class Captured(val text: String, val truncated: Boolean)
    private fun capture(input: InputStream): Captured {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var truncated = false
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                val kept = minOf(read, MAX_OUTPUT - result.size())
                if (kept > 0) result.write(buffer, 0, kept)
                if (kept < read) truncated = true
            }
        } catch (closed: IOException) { truncated = true }
        return Captured(result.toString("UTF-8"), truncated)
    }

    private data class OwnedProcess(val pid: Int, val started: String)

    private fun readOwner(pidFile: File, executable: String): OwnedProcess? = runCatching {
        if (!pidFile.isFile || pidFile.length() !in 1..32) null else {
            val pid = pidFile.readText(Charsets.US_ASCII).trim().toIntOrNull()?.takeIf { it > 1 }
            pid?.let { ownedProcess(it, executable) }
        }
    }.getOrNull()

    private fun ownedProcess(pid: Int, executable: String): OwnedProcess? = runCatching {
        val status = File("/proc/$pid/status").readText(Charsets.US_ASCII).take(8192)
        val uid = status.lineSequence().first { it.startsWith("Uid:") }.substringAfter(':').trim().substringBefore('\t').substringBefore(' ').toInt()
        val parent = status.lineSequence().first { it.startsWith("PPid:") }.substringAfter(':').trim().toInt()
        if (uid != android.os.Process.myUid() || parent != android.os.Process.myPid() || Os.readlink("/proc/$pid/exe") != executable) null
        else OwnedProcess(pid, processStart(pid))
    }.getOrNull()

    private fun processStart(pid: Int): String = File("/proc/$pid/stat").readText(Charsets.US_ASCII)
        .substringAfterLast(") ").split(' ')[19]

    private fun stop(process: Process, owner: OwnedProcess?) {
        // PRoot ignores SIGTERM. SIGQUIT runs its handler that kills every traced guest first.
        if (owner != null && runCatching { processStart(owner.pid) == owner.started }.getOrDefault(false)) {
            runCatching { Os.kill(owner.pid, OsConstants.SIGCONT) }
            runCatching { Os.kill(owner.pid, OsConstants.SIGQUIT) }
            if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) {
                // Recheck identity before a second request. Never kill a different process after PID reuse.
                if (runCatching { processStart(owner.pid) == owner.started }.getOrDefault(false)) {
                    runCatching { Os.kill(owner.pid, OsConstants.SIGCONT) }
                    runCatching { Os.kill(owner.pid, OsConstants.SIGQUIT) }
                }
                check(process.waitFor(1500, TimeUnit.MILLISECONDS)) { "PRoot 清理尚未完成；宿主内核未响应取消信号" }
            }
        } else {
            // Do not SIGKILL a tracer whose tracees cannot be identified; report the actual host limitation.
            process.destroy()
            check(process.waitFor(1500, TimeUnit.MILLISECONDS)) { "无法识别 PRoot 子进程，宿主未允许有界清理；执行未完成" }
        }
    }

    private fun ensureLayout() {
        for (directory in listOf(root, slots, downloads, File(root, "tmp"), File(root, "lib"))) {
            require(!LinuxArchiveExtractor.isSymlink(directory) && (directory.isDirectory || directory.mkdirs())) { "运行环境目录无效" }
        }
    }

    private fun unavailableReason(): String? = when {
        Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a" -> "此发行资源支持原生 arm64-v8a；当前主架构 ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()} 暂不支持，不能使用 ARM 翻译执行 PRoot"
        app.applicationInfo.nativeLibraryDir == null -> "APK 原生执行目录不可用"
        !File(app.applicationInfo.nativeLibraryDir, "liblinux_proot.so").isFile -> "APK 未包含已校验的 PRoot 原生执行器；请安装完整发行包"
        else -> null
    }

    private fun prepareNative() {
        val native = File(app.applicationInfo.nativeLibraryDir)
        for ((name, expected) in NATIVE_HASHES) {
            val file = File(native, name)
            require(file.isFile && file.length() > 0 && file.inputStream().use { digest(it) } == expected) { "原生运行文件校验失败：$name" }
        }
        for ((packaged, soname) in mapOf("liblinux_talloc.so" to "libtalloc.so.2", "liblinux_androidshmem.so" to "libandroid-shmem.so")) {
            val destination = File(root, "lib/$soname")
            if (!destination.isFile || destination.inputStream().use { digest(it) } != NATIVE_HASHES.getValue(packaged)) {
                require(!LinuxArchiveExtractor.isSymlink(destination))
                File(native, packaged).copyTo(destination, overwrite = true)
                Os.chmod(destination.path, 0x180)
            }
        }
    }

    private fun activeRoot(): File? {
        val bytes = try { active.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val read = input.read(buffer, 0, minOf(buffer.size, 8193 - output.size()))
                if (read < 0) break
                output.write(buffer, 0, read)
                require(output.size() <= 8192) { "运行环境回执过大" }
            }
            output.toByteArray()
        } } catch (missing: java.io.FileNotFoundException) {
            if (active.baseFile.exists()) throw missing
            return null
        }
        require(bytes.size <= 8192) { "运行环境回执过大" }
        val receipt = JSONObject(bytes.toString(Charsets.UTF_8))
        require(receipt.getInt("schema") == 1 && receipt.getString("apk_sha256") == LinuxRuntimeRelease.APK_SHA256)
        val name = receipt.getString("slot")
        require(name.startsWith(LinuxRuntimeRelease.TAG + "-") && name.matches(Regex("[A-Za-z0-9.-]{1,96}")))
        val directory = File(slots, name)
        require(!LinuxArchiveExtractor.isSymlink(directory) && directory.isDirectory && directory.canonicalFile.parentFile == slots.canonicalFile) { "运行环境回执目录无效" }
        return directory
    }

    private fun update(id: String, detail: String, progress: Float?) {
        shared.snapshot.value = shared.snapshot.value.map {
            if (it.id == id) it.copy(status = LinuxComponentStatus.INSTALLING, detail = detail, progress = progress?.coerceIn(0f, 1f)) else it
        }
    }

    private fun digest(input: InputStream, checkCancelled: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            checkCancelled()
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class Shared {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val operations = Mutex()
        val startLock = Mutex()
        val snapshot = MutableStateFlow(LinuxRuntimeRelease.initial())
        @Volatile var installJob: Deferred<Unit>? = null
    }

    private companion object {
        const val MAX_OUTPUT = 512 * 1024
        val stores = ConcurrentHashMap<String, Shared>()
        val NATIVE_HASHES = mapOf(
            "liblinux_proot.so" to "1545b85b312505db6eb6908ff8b2ded0a77a3bd689c50ae85aa7c1d8445dd717",
            "liblinux_prootloader.so" to "cbdef0e652c2b78af25d867e1719fdebbb0915e25aae2dd35b3b5c1835f6b551",
            "liblinux_prootloader32.so" to "c429996fee730e1a4fe12625b22dab5a5bc2847a4cae274d36fd4a19cd8299a8",
            "liblinux_talloc.so" to "742b438c4d09e276985a61d44164c9de207b6d8cc268f3018cb3001961bcb309",
            "liblinux_androidshmem.so" to "84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731",
        )
    }
}
