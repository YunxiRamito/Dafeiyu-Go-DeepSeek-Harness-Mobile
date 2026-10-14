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
import kotlinx.coroutines.withTimeout
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
    val managedProcesses: StateFlow<List<LinuxManagedProcessInfo>> = shared.processSnapshot.asStateFlow()

    /**
     * A long-lived process owns an active-slot lease, not the script execution mutex.
     * Component replacement is refused until its tracer has actually exited.
     */
    suspend fun startManagedProcess(
        id: String,
        guestCommand: List<String>,
        mounts: Map<String, File> = emptyMap(),
        workspace: File? = null,
        environment: Map<String, String> = emptyMap(),
        exclusiveRuntimeUse: Boolean = false,
        retainLeaseUntilPipesComplete: Boolean = false,
        guestWorkingDirectory: String? = null,
        beforeStart: (() -> Unit)? = null,
        expectedRuntimeIdentity: LinuxManagedRuntimeIdentity? = null,
        nativeProcessCreated: ((LinuxManagedStartupOwnership) -> Unit)? = null,
    ): LinuxManagedProcess = withContext(Dispatchers.IO) {
        require(id.matches(Regex("[a-z0-9][a-z0-9.-]{0,79}")))
        require(guestCommand.isNotEmpty() && guestCommand.size <= 64 && guestCommand.first().startsWith('/'))
        require(guestCommand.sumOf { it.length } <= 16_384 && guestCommand.all { argument ->
            argument.isNotEmpty() && argument.none { it == '\u0000' }
        })
        val privateRoots = listOf(app.filesDir.canonicalFile, app.noBackupFilesDir.canonicalFile)
        fun privateFile(file: File): File = file.canonicalFile.also { resolved ->
            require(file.exists() && !LinuxArchiveExtractor.isSymlink(file) && ':' !in resolved.path &&
                privateRoots.any { resolved.path.startsWith(it.path + File.separator) }) { "进程挂载必须位于应用私有目录" }
        }
        require(mounts.size <= 24)
        val resolvedMounts = mounts.map { (guest, host) ->
            require(guest.matches(Regex("/[A-Za-z0-9._/-]{1,180}")) && guest.split('/').none { it == ".." } &&
                guest !in setOf("/proc", "/sys", "/dev", "/system", "/apex", "/usr", "/etc", "/root"))
            guest to privateFile(host)
        }
        val resolvedWorkspace = workspace?.let { privateFile(it).also { folder -> require(folder.isDirectory) } }
        val cwd = guestWorkingDirectory?.let { requested ->
            require(requested.matches(Regex("/[A-Za-z0-9._/-]{1,180}")) && requested.split('/').none { it == ".." }) {
                "进程工作目录必须是受控挂载的绝对目录"
            }
            val binding = (resolvedMounts + listOfNotNull(resolvedWorkspace?.let { "/workspace" to it }))
                .filter { (guest, _) -> requested == guest || requested.startsWith("$guest/") }
                .maxByOrNull { (guest, _) -> guest.length }
            require(binding != null) { "进程工作目录不在本次私有挂载中" }
            val target = File(binding.second, requested.removePrefix(binding.first).trimStart('/'))
            val canonical = target.canonicalFile
            require(target.isDirectory && !LinuxArchiveExtractor.isSymlink(target) &&
                (canonical == binding.second || canonical.path.startsWith(binding.second.path + File.separator))) {
                "进程工作目录越过受控挂载"
            }
            requested
        } ?: if (resolvedWorkspace == null) "/root" else "/workspace"
        // Only reviewed application-owned settings can enter the guest. In
        // particular, no host credential, proxy, NODE_OPTIONS or loader env is
        // inherited or accepted from callers.
        LinuxDshArchiveEnvironment.validate(environment, resolvedMounts.toMap())
        shared.operations.withLock {
            checkNoFailedCleanup()
            requireNoUnregisteredManagedOwnersLocked()
            if (exclusiveRuntimeUse) check(shared.managed.values.none { it.holdsRuntimeLease }) {
                "运行时仍有进程占用，请先停止服务和模型运行器"
            }
            unavailableReason()?.let { throw IOException(it) }
            ensureLayout()
            prepareNative()
            check(!shared.managed.containsKey(id) && shared.managed.size < 4) { "已有同名服务或常驻进程达到上限" }
            val directory = activeRoot() ?: throw IOException("请先安装 Ubuntu 和 Node.js 组件")
            expectedRuntimeIdentity?.let { requireRuntimeIdentityLocked(directory, it) }
            val native = File(app.applicationInfo.nativeLibraryDir)
            LinuxGuestMounts.prepare(directory, platformDirectoryBinds() + native.path +
                listOfNotNull(resolvedWorkspace?.let { "/workspace" }) + resolvedMounts.map { it.first })
            val argv = arrayListOf(File(native, "liblinux_proot.so").path, "-L", "--kill-on-exit", "-0",
                "--rootfs=${directory.path}", "--cwd=$cwd")
            for (path in listOf("/dev", "/proc", "/sys", "/system", "/apex")) if (File(path).exists()) argv += listOf("-b", path)
            argv += listOf("-b", "/dev/urandom:/dev/random", "-b", "/proc/self/fd:/dev/fd")
            // Landlock must be able to read/exec PRoot's immutable APK loader.
            // This trusted app path is outside the translated guest rootfs.
            argv += listOf("-b", "${native.path}:${native.path}")
            resolvedWorkspace?.let { argv += listOf("-b", "${it.path}:/workspace") }
            resolvedMounts.forEach { (guest, host) -> argv += listOf("-b", "${host.path}:$guest") }
            argv += guestCommand
            val pidFile = File(root, "pid-${UUID.randomUUID()}")
            val launch = listOf("/system/bin/sh", "-c", "printf '%s\\n' \"\$\$\" > \"\$1\"; shift; exec \"\$@\"",
                "dafeiyu-managed-linux", pidFile.path) + argv
            val builder = ProcessBuilder(launch).directory(root)
            builder.environment().apply {
                clear()
                putAll(managedEnvironment(environment, resolvedMounts.toMap()))
            }
            // The guard and OS launch share the same lock as exclusive data
            // helpers. A journal cannot be published between this check and
            // the acquisition of the real managed-process lease.
            beforeStart?.invoke()
            val process = builder.start()
            shared.leasedResources[process] = listOfNotNull(directory, resolvedWorkspace) + resolvedMounts.map { it.second }
            var owner: OwnedProcess? = null
            val startup = nativeProcessCreated?.let {
                LinuxManagedStartupOwnership(process, stop = {
                    shared.operations.withLock {
                        if (process.isAlive) stop(process, owner ?: readOwner(pidFile, File(native, "liblinux_proot.so").path))
                    }
                }, release = { token ->
                    shared.operations.withLock {
                        check(!process.isAlive && shared.nativeStartups[token] === process && token.owns(process)) { "HOST_UNAVAILABLE" }
                        shared.failedCleanup.remove(process)
                        shared.leasedResources.remove(process)
                        pidFile.delete()
                        shared.nativeStartups.remove(token, process)
                    }
                }).also { token -> shared.nativeStartups[token] = process }
            }
            try {
                startup?.let { nativeProcessCreated?.invoke(it) }
                val start = System.nanoTime()
                while (process.isAlive && owner == null && System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1)) {
                    currentCoroutineContext().ensureActive()
                    owner = readOwner(pidFile, File(native, "liblinux_proot.so").path)
                    if (owner == null) delay(10)
                }
                check(process.isAlive && owner != null) { "常驻进程启动失败或无法核实 PRoot 进程身份" }
                val identity = checkNotNull(owner)
                lateinit var managed: LinuxManagedProcess
                managed = LinuxManagedProcess(id, identity.pid, directory.name, process,
                    retainLeaseUntilPipesComplete = retainLeaseUntilPipesComplete,
                    releaseRetainedLease = { expected ->
                        shared.operations.withLock {
                            check(expected === managed && !process.isAlive && shared.managed[id] === managed &&
                                (!exclusiveRuntimeUse || shared.exclusiveManaged[id] === managed) && shared.leasedResources.containsKey(process)) {
                                "PIPE_CLEANUP_PENDING"
                            }
                            shared.managed.remove(id, managed)
                            shared.exclusiveManaged.remove(id, managed)
                            shared.leasedResources.remove(process)
                            updateProcessSnapshot()
                        }
                    }) {
                    withContext(NonCancellable + Dispatchers.IO) { stop(process, identity) }
                }
                shared.managed[id] = managed
                if (exclusiveRuntimeUse) shared.exclusiveManaged[id] = managed
                updateProcessSnapshot()
                shared.scope.async {
                    try { process.waitFor() }
                    finally {
                        pidFile.delete()
                        // Retained workers report native exit promptly, but a
                        // still-running pipe reader continues owning the exact
                        // managed/exclusive/resource registrations until the
                        // verified native completePipeCleanup release callback.
                        if (!retainLeaseUntilPipesComplete) {
                            shared.managed.remove(id, managed)
                            shared.exclusiveManaged.remove(id, managed)
                            shared.leasedResources.remove(process)
                        }
                        updateProcessSnapshot()
                        managed.onExited()
                    }
                }
                startup?.let { token ->
                    token.transferTo(managed)
                    shared.nativeStartups.remove(token, process)
                }
                managed
            } catch (failure: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) {
                    try { if (process.isAlive) stop(process, owner) }
                    finally {
                        retainFailedCleanup(process, directory, pidFile)
                        runCatching { process.outputStream.close() }
                        runCatching { process.inputStream.close() }
                        runCatching { process.errorStream.close() }
                    }
                }
                throw failure
            }
        }
    }

    suspend fun stopManagedProcess(id: String) { shared.managed[id]?.stop() }

    /** This is the very same clear/rebuild source used by ProcessBuilder.
     * The caller cannot substitute imported host or credential environment. */
    internal fun managedLaunchEnvironment(environment: Map<String, String>, mounts: Map<String, File>): Map<String, String> =
        managedEnvironment(environment, mounts)

    private fun managedEnvironment(environment: Map<String, String>, mounts: Map<String, File>): Map<String, String> {
        LinuxDshArchiveEnvironment.validate(environment, mounts)
        val native = File(app.applicationInfo.nativeLibraryDir)
        return mapOf("ANDROID_ROOT" to "/system", "ANDROID_DATA" to "/data", "HOME" to "/root",
            "USER" to "root", "LOGNAME" to "root", "LANG" to "C.UTF-8", "TERM" to "dumb", "TMPDIR" to "/tmp",
            "PATH" to "/root/dsh-bin:/usr/local/bin:/usr/bin:/bin", "PROOT_TMP_DIR" to File(root, "tmp").path,
            "PROOT_LOADER" to File(native, "liblinux_prootloader.so").path,
            "PROOT_LOADER_32" to File(native, "liblinux_prootloader32.so").path,
            "LD_LIBRARY_PATH" to "${File(root, "lib").path}:${native.path}",
            "OTEL_SDK_DISABLED" to "true") + environment.toMap()
    }

    /** Captures the actual published root, receipt, Node and native inputs;
     * the returned object is local host data, never a journal/backup field. */
    internal suspend fun captureManagedRuntimeIdentity(): LinuxManagedRuntimeIdentity = withContext(Dispatchers.IO) {
        shared.operations.withLock {
            checkNoFailedCleanup()
            requireNoUnregisteredManagedOwnersLocked()
            unavailableReason()?.let { throw IOException(it) }
            ensureLayout(); prepareNative()
            runtimeIdentityLocked(activeRoot() ?: error("HOST_UNAVAILABLE"))
        }
    }

    /** Runs for every authenticated helper challenge, under the real shared
     * operations mutex; a current pointer/hash mismatch never becomes approval. */
    internal suspend fun requireManagedRuntimeIdentity(helper: LinuxManagedProcess, expected: LinuxManagedRuntimeIdentity) =
        withContext(Dispatchers.IO) { shared.operations.withLock {
            requireNoUnregisteredManagedOwnersLocked()
            check(shared.managed[helper.info.id] === helper && shared.exclusiveManaged[helper.info.id] === helper &&
                helper.isAlive && helper.holdsRuntimeLease) { "HOST_UNAVAILABLE" }
            requireRuntimeIdentityLocked(activeRoot() ?: error("HOST_UNAVAILABLE"), expected)
        } }

    private fun requireRuntimeIdentityLocked(directory: File, expected: LinuxManagedRuntimeIdentity) {
        check(runtimeIdentityLocked(directory) == expected) { "HOST_UNAVAILABLE" }
    }

    /** A cold application process has a new shared map. A private pid marker
     * with a still-live, same-UID PRoot cannot be treated as synthetic idle.
     * Do not delete markers or signal an unregistered/reused PID here. */
    private fun requireNoUnregisteredManagedOwnersLocked() {
        val executable = File(app.applicationInfo.nativeLibraryDir, "liblinux_proot.so").canonicalPath
        for (marker in root.listFiles().orEmpty().filter { it.name.startsWith("pid-") }) {
            require(marker.name.matches(Regex("pid-[a-fA-F0-9-]{36}")) && marker.isFile &&
                !LinuxArchiveExtractor.isSymlink(marker) && marker.length() in 1..32 && marker.canonicalFile.parentFile == root.canonicalFile) {
                "HOST_UNAVAILABLE"
            }
            val pid = marker.readText(Charsets.US_ASCII).trim().toIntOrNull()?.takeIf { it > 1 } ?: error("HOST_UNAVAILABLE")
            if (!File("/proc/$pid").exists()) continue
            val owner = ownedProcess(pid, executable)
            if (owner != null) {
                check(shared.managed.values.any { it.pid == pid && it.isAlive } &&
                    shared.leasedResources.keys.any { native -> native.isAlive &&
                        shared.managed.values.any { it.pid == pid && it.ownsProcess(native) } }) { "HOST_UNAVAILABLE" }
            } else {
                val status = File("/proc/$pid/status").inputStream().use { ConnectionPackageValidator.readBounded(it, 64 * 1024) }
                    .toString(Charsets.US_ASCII)
                val uid = status.lineSequence().single { it.startsWith("Uid:") }.substringAfter(':').trim()
                    .split(Regex("\\s+")).first().toInt()
                check(uid != android.os.Process.myUid() || Os.readlink("/proc/$pid/exe") != executable) { "HOST_UNAVAILABLE" }
            }
        }
    }

    private fun runtimeIdentityLocked(directory: File): LinuxManagedRuntimeIdentity {
        for (suffix in listOf("", ".bak", ".new")) require(!LinuxArchiveExtractor.isSymlink(File(root, "active.json$suffix"))) {
            "HOST_UNAVAILABLE"
        }
        val raw = active.openRead().use { ConnectionPackageValidator.readBounded(it, 8192) }
        require(JSONObject(raw.toString(Charsets.UTF_8)).getString("slot") == directory.name) { "HOST_UNAVAILABLE" }
        val node = File(directory, "usr/local/bin/node")
        var cursor = node
        while (cursor != directory) {
            require(!LinuxArchiveExtractor.isSymlink(cursor)) { "HOST_UNAVAILABLE" }
            cursor = cursor.parentFile ?: error("HOST_UNAVAILABLE")
        }
        require(node.isFile && node.length() in 1..192L * 1024 * 1024 &&
            node.canonicalPath.startsWith(directory.canonicalPath + File.separator)) { "HOST_UNAVAILABLE" }
        val native = File(app.applicationInfo.nativeLibraryDir)
        val hashes = LinuxNativeRelease.hashes(Build.SUPPORTED_ABIS.firstOrNull() ?: error("HOST_UNAVAILABLE"))
        for ((name, expected) in hashes) {
            val file = File(native, name)
            require(file.isFile && !LinuxArchiveExtractor.isSymlink(file) && file.inputStream().use { digest(it) } == expected) {
                "HOST_UNAVAILABLE"
            }
        }
        return LinuxManagedRuntimeIdentity(directory.canonicalFile, sha256(raw), node.canonicalFile,
            node.inputStream().use { digest(it) }, native.canonicalFile,
            sha256(hashes.toSortedMap().entries.joinToString("\n") { "${it.key}:${it.value}" }.toByteArray(Charsets.UTF_8)))
    }

    /** A controlled storage helper calls this for each authenticated lock-held
     * challenge. The actual exclusive helper stays alive and registered; this
     * method does not mark the runtime idle or release a filesystem lease.
     * Failed cleanup or any other real process refuses the positive approval. */
    internal suspend fun requireExclusiveManagedLease(helper: LinuxManagedProcess, mountedDirectories: List<File>) =
        shared.operations.withLock {
            val id = helper.info.id
            check(shared.failedCleanup.keys.none { it.isAlive } && helper.isAlive &&
                shared.managed[id] === helper && shared.exclusiveManaged[id] === helper &&
                shared.managed.values.none { it !== helper && it.holdsRuntimeLease }) { "HOST_UNAVAILABLE" }
            val owned = shared.leasedResources.entries.singleOrNull { (process, _) ->
                process.isAlive && helper.ownsProcess(process)
            } ?: error("HOST_UNAVAILABLE")
            check(shared.leasedResources.keys.none { it !== owned.key && hasRetainedProcessLease(it) }) { "HOST_UNAVAILABLE" }
            val leased = owned.value.map { it.canonicalFile }.toSet()
            check(mountedDirectories.isNotEmpty() && mountedDirectories.all { directory ->
                val target = directory.canonicalFile
                !LinuxArchiveExtractor.isSymlink(directory) && directory.exists() && target in leased
            } && helper.isAlive) { "HOST_UNAVAILABLE" }
        }

    /** Includes processes whose failed startup could not yet be cleaned up. */
    internal fun hasProcessLease(directory: File): Boolean {
        val target = directory.canonicalFile
        return shared.leasedResources.any { (process, resources) -> hasRetainedProcessLease(process) && resources.any { resource ->
            val leased = resource.canonicalFile
            leased == target || leased.path.startsWith(target.path + File.separator)
        } }
    }

    /** One lock covers the idle check, restore mutation and future launches.
     * Failed-start processes retain their real filesystem leases until exit. */
    internal suspend fun <T> withIdleDataMutation(directories: List<File>, block: suspend () -> T): T =
        shared.operations.withLock {
            checkNoFailedCleanup()
            check(shared.managed.values.none { it.holdsRuntimeLease }) { "运行时仍有进程占用，请先停止服务和模型运行器" }
            check(directories.none(::hasProcessLease)) { "目标数据目录仍被运行进程占用" }
            block()
        }

    /** The probe stays alive until the host releases it, so even a fast Node
     * invocation retains the same verified PRoot lease as a normal service. */
    internal suspend fun nodeIdentity(): LinuxNodeIdentity = withContext(Dispatchers.IO) {
        val process = startManagedProcess(
            "node-probe-${UUID.randomUUID()}",
            listOf("/usr/local/bin/node", "-e",
                "console.log(JSON.stringify({version:process.version,arch:process.arch,platform:process.platform}));process.stdin.resume();"),
        )
        try {
            val line = withTimeout(10_000) {
                val bytes = ByteArrayOutputStream()
                while (bytes.size() <= 512) {
                    currentCoroutineContext().ensureActive()
                    if (process.output.available() == 0) {
                        check(process.isAlive) { "Node.js 探测进程提前退出" }
                        delay(10)
                        continue
                    }
                    val value = process.output.read()
                    check(value >= 0) { "Node.js 探测没有输出" }
                    if (value == 10) break
                    bytes.write(value)
                }
                require(bytes.size() <= 512) { "Node.js 探测输出超过限制" }
                bytes.toString(Charsets.UTF_8.name())
            }
            val value = JSONObject(line)
            LinuxNodeIdentity(value.getString("version"), value.getString("arch"), value.getString("platform"))
        } finally {
            withContext(NonCancellable) { process.stop() }
        }
    }

    private fun updateProcessSnapshot() {
        shared.processSnapshot.value = shared.managed.values.map { it.info }.sortedBy { it.id }
    }

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
            checkNoFailedCleanup()
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
        timeoutSeconds: Int = 30, source: String? = null,
        sandboxMode: String = LinuxSandboxContract.FULL_ACCESS,
        expectedSandboxIdentity: LinuxSandboxIdentity? = null): LinuxExecutionResult = withContext(Dispatchers.IO) {
        require(script.toByteArray(Charsets.UTF_8).size <= 512 * 1024) { "脚本超过 512 KiB" }
        require(timeoutSeconds in 1..120) { "执行超时必须为 1–120 秒" }
        require(language in setOf("bash", "shell", "sh", "node", "javascript", "js", "python", "python3")) { "不支持此脚本语言" }
        require(source == null || source in setOf("official", "cdn", "backend")) { "未知下载源" }
        require(sandboxMode in LinuxSandboxContract.modes) { "未知沙箱档位" }
        val folder = workspace.canonicalFile
        val privateRoots = listOf(app.filesDir.canonicalFile, app.noBackupFilesDir.canonicalFile)
        require(folder.isDirectory && privateRoots.any { folder.path.startsWith(it.path + File.separator) }) {
            "执行工作区必须位于应用私有目录"
        }
        require(':' !in folder.path) { "工作区路径不能含冒号" }
        shared.operations.withLock {
            checkNoFailedCleanup()
            unavailableReason()?.let { throw IOException(it) }
            ensureLayout()
            prepareNative()
            val directory = activeRoot() ?: throw IOException("请先在组件页安装 Ubuntu")
            if (expectedSandboxIdentity != null) check(sandboxIdentityLocked(directory) == expectedSandboxIdentity) {
                "运行环境已变化，请重新验证沙箱；脚本未执行"
            }
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
                if (sandboxMode == LinuxSandboxContract.FULL_ACCESS) run(directory, command, timeoutSeconds, folder, request)
                    .copy(sandboxMode = sandboxMode, sandboxEnforcement = "android-uid")
                else {
                    val sandbox = prepareSandbox()
                    val probe = run(directory, listOf(LinuxSandboxContract.GUEST_LAUNCHER, "--probe"), 10, sandboxMount = sandbox)
                    check(probe.exitCode == 0 && !probe.timedOut && !probe.outputTruncated && probe.stdout.trim() == LinuxSandboxContract.PROBE_COMPLETE) {
                        "当前 Android 内核不能完整实施所选沙箱；脚本未执行，请检查运行详情或改用完全权限"
                    }
                    val result = run(directory, LinuxSandboxContract.confine(command, sandboxMode, app.applicationInfo.nativeLibraryDir),
                        timeoutSeconds, folder, request, sandbox)
                    if (result.exitCode == 125 && result.stderr.lineSequence().any { it.startsWith("landlock-run:") })
                        throw IOException("沙箱运行器无法启动所选脚本；已拒绝无约束执行")
                    check(!result.stderr.contains("landlock-run: partial enforcement")) { "当前内核沙箱能力不完整；无法兑现所选约束" }
                    result.copy(sandboxMode = sandboxMode, sandboxEnforcement = "full")
                }
            } finally { if (!hasProcessLease(request)) request.delete() }
        }
    }

    /** A slot-bound execution identity. No persisted diagnostic report grants
     * access; callers pass this exact identity back into execute's mutex. */
    suspend fun sandboxIdentity(): LinuxSandboxIdentity = withContext(Dispatchers.IO) {
        shared.operations.withLock {
            checkNoFailedCleanup()
            unavailableReason()?.let { throw IOException(it) }
            ensureLayout(); prepareNative()
            val directory = activeRoot() ?: throw IOException("请先安装 Ubuntu 组件")
            sandboxIdentityLocked(directory)
        }
    }

    private fun sandboxIdentityLocked(directory: File): LinuxSandboxIdentity {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: error("设备架构不可用")
        prepareSandbox()
        for (suffix in listOf("", ".bak", ".new")) require(!LinuxArchiveExtractor.isSymlink(File(root, "active.json$suffix"))) {
            "运行环境回执路径无效"
        }
        val receiptDigest = active.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size, 8193 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
                require(output.size() <= 8192) { "运行环境回执过大" }
            }
            val bytes = output.toByteArray()
            require(bytes.size in 1..8192) { "运行环境回执无效" }
            require(JSONObject(bytes.toString(Charsets.UTF_8)).getString("slot") == directory.name) {
                "运行环境回执在检查期间变化"
            }
            sha256(bytes)
        }
        val nativeDigest = sha256(LinuxNativeRelease.hashes(abi).toSortedMap().entries
            .joinToString("\n") { "${it.key}:${it.value}" }.toByteArray(Charsets.UTF_8))
        val kernel = Os.uname()
        return LinuxSandboxIdentity(directory.name, abi, receiptDigest, LinuxSandboxContract.digest(abi), nativeDigest,
            sha256(app.applicationInfo.nativeLibraryDir.toByteArray(Charsets.UTF_8)),
            sha256("${kernel.sysname}\n${kernel.release}\n${kernel.version}\n${Build.FINGERPRINT}".toByteArray(Charsets.UTF_8)))
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    /** Kernel functional status only; end-to-end file-effect proof is reported
     * separately by DshSandboxProbe and Android instrumentation. */
    suspend fun probeSandbox(): LinuxSandboxStatus = withContext(Dispatchers.IO) {
        shared.operations.withLock {
            checkNoFailedCleanup()
            unavailableReason()?.let { throw IOException(it) }
            ensureLayout(); prepareNative()
            val directory = activeRoot() ?: throw IOException("请先安装 Ubuntu 组件")
            val sandbox = prepareSandbox()
            val result = run(directory, listOf(LinuxSandboxContract.GUEST_LAUNCHER, "--probe"), 10, sandboxMount = sandbox)
            if (result.exitCode == 0 && !result.timedOut && !result.outputTruncated && result.stdout.trim() == LinuxSandboxContract.PROBE_COMPLETE)
                LinuxSandboxStatus("full", "Linux 内核完整实施 Landlock；实际执行还需受控加载器与工作区路径，Android UID/SELinux 限制始终存在")
            else LinuxSandboxStatus("unavailable", "当前内核没有完整 Landlock 执行能力，受限脚本会被拒绝；未改为无约束执行")
        }
    }

    private fun prepareSandbox(): File {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: error("设备架构不可用")
        val expected = LinuxSandboxContract.digest(abi)
        val folder = File(root, "sandbox-native")
        require(!LinuxArchiveExtractor.isSymlink(folder) && (folder.isDirectory || folder.mkdirs()))
        val launcher = File(folder, "landlock-run")
        require(!LinuxArchiveExtractor.isSymlink(launcher))
        app.assets.open("linux-sandbox/$abi/landlock-run").use { input -> launcher.outputStream().use { input.copyTo(it) } }
        require(launcher.length() in 1..128 * 1024 && launcher.inputStream().use { digest(it) } == expected) { "Landlock 原生资源摘要不匹配" }
        Os.chmod(launcher.path, 0x1c0)
        return folder
    }

    private suspend fun installLocked(id: String, source: String) {
        val previousStates = shared.snapshot.value
        var candidate: File? = null
        var committed = false
        try {
            checkNoFailedCleanup()
            check(shared.managed.isEmpty()) { "模型运行器或 DSH 正在使用环境；请先停止服务再安装组件" }
            unavailableReason()?.let { throw IOException(it) }
            ensureLayout()
            prepareNative()
            if (Build.SUPPORTED_ABIS.firstOrNull() == "x86_64") {
                installX64Locked(id, source)
                return
            }
            val coroutine = currentCoroutineContext()
            val cancelCheck = { coroutine.ensureActive() }
            val current = activeRoot()
            current?.let(LinuxGuestMounts::repairLegacy)
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
            if (!committed && candidate != null && !hasFailedLease(candidate)) runCatching { LinuxArchiveExtractor.removeTree(candidate) }
        }
    }

    /** QA uses actual x86_64 executables, never the emulator's ARM translation.
     * This fixed standalone archive includes all five shared guest components. */
    private suspend fun installX64Locked(id: String, source: String) {
        val manifest = x64Manifest()
        val coroutine = currentCoroutineContext()
        val cancelCheck = { coroutine.ensureActive() }
        val current = activeRoot()
        current?.let(LinuxGuestMounts::repairLegacy)
        val cloneBytes = current?.let { LinuxArchiveExtractor.treeBytes(it, cancelCheck) } ?: 0L
        val archiveFile = File(downloads, "${manifest.runtimeId}.tar.gz")
        val cached = archiveFile.isFile && archiveFile.length() == manifest.archiveSize &&
            archiveFile.inputStream().use { digest(it, cancelCheck) } == manifest.archiveSha256.lowercase()
        val requiredBytes = Math.addExact(cloneBytes, manifest.maximumExpandedBytes) +
            (if (cached) 0L else manifest.archiveSize) + 64L * 1024 * 1024
        require(root.usableSpace >= requiredBytes) { "可用空间不足；安装需 ${requiredBytes / (1024 * 1024)} MiB 用于下载和候选环境" }
        var candidate: File? = null
        var committed = false
        try {
            update(id, "下载并校验原生 x86_64 Linux 组件资源", null)
            val archive = ComponentDownloadClient(app).downloadArtifact(
                ArtifactDownloadRequest(listOf(manifest.archiveUrl), archiveFile,
                    ArtifactChecksum("SHA-256", manifest.archiveSha256), manifest.archiveSize, manifest.archiveSize),
                source,
            ) { progress ->
                update(id, "下载组件：${progress.receivedBytes / (1024 * 1024)} / ${manifest.archiveSize / (1024 * 1024)} MiB" +
                    (progress.reason?.let { "；$it" } ?: ""), progress.receivedBytes.toFloat() / manifest.archiveSize)
            }.file
            require(archive.canonicalFile == archiveFile.canonicalFile && archive.length() == manifest.archiveSize &&
                archive.inputStream().use { digest(it, cancelCheck) } == manifest.archiveSha256.lowercase()) { "x86_64 Linux 资源大小或摘要不匹配" }
            val stage = File(slots, "stage-${UUID.randomUUID()}")
            require(stage.mkdir()) { "无法创建候选运行环境" }
            candidate = stage
            if (current != null) {
                update(id, "复制当前环境，保留用户目录", null)
                LinuxArchiveExtractor.copyTree(current, stage, cancelCheck)
            }
            update(id, "安全解包原生 x86_64 组件资源", null)
            archive.inputStream().use { input ->
                LinuxArchiveExtractor.extract(input, stage, manifest.maximumExpandedBytes, cancelCheck,
                    selected = { path -> current == null || listOf("root", "home", "workspace").none {
                        path == it || path.startsWith("$it/")
                    } }) { expanded -> update(id, "解包组件：${expanded / (1024 * 1024)} MiB", null) }
            }
            verifyX64Lock(stage, manifest)
            configureGuest(stage, "python", source)
            configureGuest(stage, "pnpm", source)
            update(id, "启动原生 x86_64 PRoot 并检测全部组件", null)
            val states = probeStates(stage)
            states.firstOrNull { it.status != LinuxComponentStatus.READY }?.let {
                throw IOException("${it.title} 尚未就绪：${it.detail}")
            }
            cancelCheck()
            val name = "${manifest.runtimeId}-${UUID.randomUUID()}"
            val published = File(slots, name)
            require(stage.renameTo(published)) { "无法提交候选环境" }
            candidate = published
            val receipt = JSONObject().put("schema", 2).put("slot", name).put("abi", manifest.abi)
                .put("runtime_id", manifest.runtimeId).put("archive_sha256", manifest.archiveSha256.lowercase())
                .put("lock_sha256", manifest.lockSha256.lowercase()).put("source", source)
            val output = active.startWrite()
            try {
                output.write(receipt.toString().toByteArray(Charsets.UTF_8))
                active.finishWrite(output)
            } catch (failure: Exception) { active.failWrite(output); throw failure }
            committed = true
            shared.snapshot.value = states
            if (current != null) runCatching { LinuxArchiveExtractor.removeTree(current) }
        } finally {
            if (!committed && candidate != null && !hasFailedLease(candidate)) runCatching { LinuxArchiveExtractor.removeTree(candidate) }
        }
    }

    private fun x64Manifest(): LinuxGuestRuntimeManifest {
        val bytes = app.assets.open("linux-runtime/x86_64-release.json").use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= 64 * 1024) {
                val read = input.read(buffer, 0, minOf(buffer.size, 64 * 1024 + 1 - output.size()))
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        require(bytes.size <= 64 * 1024) { "x86_64 组件清单过大" }
        return LinuxGuestRuntimeManifest.fromJson(JSONObject(bytes.toString(Charsets.UTF_8)))
    }

    private fun verifyX64Lock(directory: File, manifest: LinuxGuestRuntimeManifest) {
        val file = File(directory, "etc/dafeiyu-runtime-lock.json")
        require(!LinuxArchiveExtractor.isSymlink(file) && file.isFile && file.length() in 1..2L * 1024 * 1024 &&
            file.canonicalPath.startsWith(directory.canonicalPath + File.separator) &&
            file.inputStream().use { digest(it) } == manifest.lockSha256.lowercase()) { "x86_64 组件锁定记录校验失败" }
        val lock = JSONObject(file.readText(Charsets.UTF_8))
        require(lock.getInt("schema") == 1 && lock.getString("runtimeId") == manifest.runtimeId &&
            lock.getString("architecture") == manifest.abi && lock.getString("ubuntuVersion") == "24.04.3" &&
            lock.getString("nodeVersion") == "24.19.0" && !lock.getBoolean("upstreamScriptsExecuted")) { "x86_64 组件来源或版本不匹配" }
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
            if [ -x /usr/local/bin/node ]; then v=$(/usr/local/bin/node --version 2>/dev/null) && printf 'DAFEIYU_COMPONENT_node=%s\n' "${'$'}v"; /usr/local/bin/node -e 'console.log("DAFEIYU_ARCH="+process.platform+"/"+process.arch)' 2>/dev/null; fi
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
        val nativeArchitecture = result.stdout.lineSequence().filter { it.startsWith("DAFEIYU_ARCH=") }.singleOrNull()
            ?.substringAfter('=')
        val expectedArchitecture = if (Build.SUPPORTED_ABIS.firstOrNull() == "x86_64") "linux/x64" else "linux/arm64"
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
            if (valid && result.exitCode == 0 && !result.timedOut && nativeArchitecture == expectedArchitecture) component.copy(
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
        workspace: File? = null, request: File? = null, sandboxMount: File? = null): LinuxExecutionResult = coroutineScope {
        val native = File(app.applicationInfo.nativeLibraryDir)
        LinuxGuestMounts.prepare(directory, platformDirectoryBinds() + native.path +
            listOfNotNull(workspace?.let { "/workspace" }, sandboxMount?.let { LinuxSandboxContract.GUEST_NATIVE }),
            listOfNotNull(request?.let { "/dafeiyu-script" }))
        val argv = arrayListOf(File(native, "liblinux_proot.so").path, "-L", "--kill-on-exit", "-0",
            "--rootfs=${directory.path}", "--cwd=${if (workspace == null) "/root" else "/workspace"}")
        for (path in listOf("/dev", "/proc", "/sys", "/system", "/apex")) if (File(path).exists()) argv += listOf("-b", path)
        argv += listOf("-b", "/dev/urandom:/dev/random", "-b", "/proc/self/fd:/dev/fd")
        argv += listOf("-b", "${native.path}:${native.path}")
        if (workspace != null) argv += listOf("-b", "${workspace.path}:/workspace")
        if (request != null) argv += listOf("-b", "${request.path}:/dafeiyu-script")
        if (sandboxMount != null) argv += listOf("-b", "${sandboxMount.path}:${LinuxSandboxContract.GUEST_NATIVE}")
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
        shared.leasedResources[process] = listOfNotNull(directory, workspace, request, sandboxMount)
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
                    retainFailedCleanup(process, directory, pidFile)
                    runCatching { process.inputStream.close() }
                    runCatching { process.errorStream.close() }
                    output.cancel()
                    errors.cancel()
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

    private fun checkNoFailedCleanup() {
        check(shared.nativeStartups.isEmpty()) { "上次实际进程启动清理尚未完成，运行环境继续保留" }
        check(shared.failedCleanup.keys.none { it.isAlive }) { "上次 PRoot 清理尚未完成；仍保留运行环境，请等待进程退出" }
        check(shared.exclusiveManaged.values.none { it.holdsRuntimeLease }) { "正在写入共享记录或仍在清理管道，请等待实际完成" }
    }

    private fun hasRetainedProcessLease(process: Process): Boolean = process.isAlive ||
        shared.managed.values.any { it.ownsProcess(process) && it.holdsRuntimeLease }

    private fun hasFailedLease(directory: File): Boolean = shared.failedCleanup.any { (process, leased) ->
        process.isAlive && leased.canonicalFile == directory.canonicalFile
    }

    /** A failed cancellation must retain its exact guest filesystem. A watcher
     * releases the lease only after the actual OS process has terminated. */
    private fun retainFailedCleanup(process: Process, directory: File, pidFile: File) {
        if (!process.isAlive) {
            shared.leasedResources.remove(process)
            pidFile.delete()
            return
        }
        if (shared.failedCleanup.putIfAbsent(process, directory) == null) {
            shared.scope.async {
                try { process.waitFor() }
                finally {
                    if (!process.isAlive) {
                        val resources = shared.leasedResources.remove(process).orEmpty()
                        shared.failedCleanup.remove(process, directory)
                        pidFile.delete()
                        // A candidate slot may have been left behind when its
                        // probe could not be stopped. Once the exact process
                        // exits, reclaim only the uncommitted stage; active and
                        // published slots are never removed by this watcher.
                        if (directory.parentFile?.canonicalFile == slots.canonicalFile &&
                            directory.name.startsWith("stage-")) {
                            runCatching { LinuxArchiveExtractor.removeTree(directory) }
                        }
                        resources.filter { it.parentFile?.canonicalFile == File(root, "requests").canonicalFile }
                            .forEach { request -> runCatching { request.delete() } }
                    }
                }
            }
        }
    }

    private fun ensureLayout() {
        for (directory in listOf(root, slots, downloads, File(root, "tmp"), File(root, "lib"))) {
            require(!LinuxArchiveExtractor.isSymlink(directory) && (directory.isDirectory || directory.mkdirs())) { "运行环境目录无效" }
        }
    }

    private fun unavailableReason(): String? = when {
        shared.exclusiveManaged.values.any { it.holdsRuntimeLease } -> "持久化进程或管道清理尚未完成；运行环境仍被真实租约占用"
        Build.SUPPORTED_ABIS.firstOrNull() !in setOf("arm64-v8a", "x86_64") -> "此发行资源支持原生 arm64-v8a / x86_64；当前主架构 ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()} 暂不支持，不能使用 ARM 翻译执行 PRoot"
        app.applicationInfo.nativeLibraryDir == null -> "APK 原生执行目录不可用"
        !File(app.applicationInfo.nativeLibraryDir, "liblinux_proot.so").isFile -> "APK 未包含已校验的 PRoot 原生执行器；请安装完整发行包"
        else -> null
    }

    private fun prepareNative() {
        val native = File(app.applicationInfo.nativeLibraryDir)
        val hashes = LinuxNativeRelease.hashes(Build.SUPPORTED_ABIS.first())
        for ((name, expected) in hashes) {
            val file = File(native, name)
            require(file.isFile && file.length() > 0 && file.inputStream().use { digest(it) } == expected) { "原生运行文件校验失败：$name" }
        }
        for ((packaged, soname) in mapOf("liblinux_talloc.so" to "libtalloc.so.2", "liblinux_androidshmem.so" to "libandroid-shmem.so")) {
            val destination = File(root, "lib/$soname")
            if (!destination.isFile || destination.inputStream().use { digest(it) } != hashes.getValue(packaged)) {
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
        val abi = Build.SUPPORTED_ABIS.firstOrNull()
        val manifest = if (abi == "x86_64") x64Manifest() else null
        val prefix = if (manifest != null) {
            require(receipt.getInt("schema") == 2 && receipt.getString("abi") == manifest.abi &&
                receipt.getString("runtime_id") == manifest.runtimeId &&
                receipt.getString("archive_sha256") == manifest.archiveSha256.lowercase() &&
                receipt.getString("lock_sha256") == manifest.lockSha256.lowercase()) { "x86_64 运行环境回执与发行资源不匹配" }
            manifest.runtimeId
        } else {
            require(abi == "arm64-v8a" && receipt.getInt("schema") == 1 &&
                receipt.getString("apk_sha256") == LinuxRuntimeRelease.APK_SHA256)
            LinuxRuntimeRelease.TAG
        }
        val name = receipt.getString("slot")
        require(name.startsWith("$prefix-") && name.matches(Regex("[A-Za-z0-9._-]{1,128}")))
        val directory = File(slots, name)
        require(!LinuxArchiveExtractor.isSymlink(directory) && directory.isDirectory && directory.canonicalFile.parentFile == slots.canonicalFile) { "运行环境回执目录无效" }
        if (manifest != null) verifyX64Lock(directory, manifest)
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
        val nativeStartups = ConcurrentHashMap<LinuxManagedStartupOwnership, Process>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val operations = Mutex()
        val startLock = Mutex()
        val snapshot = MutableStateFlow(LinuxRuntimeRelease.initial())
        val managed = ConcurrentHashMap<String, LinuxManagedProcess>()
        val exclusiveManaged = ConcurrentHashMap<String, LinuxManagedProcess>()
        val failedCleanup = ConcurrentHashMap<Process, File>()
        val leasedResources = ConcurrentHashMap<Process, List<File>>()
        val processSnapshot = MutableStateFlow<List<LinuxManagedProcessInfo>>(emptyList())
        @Volatile var installJob: Deferred<Unit>? = null
    }

    private companion object {
        const val MAX_OUTPUT = 512 * 1024
        val stores = ConcurrentHashMap<String, Shared>()
        fun platformDirectoryBinds(): List<String> = listOf("/dev", "/proc", "/sys", "/system", "/apex")
            .filter { File(it).exists() }
    }
}

/** Only the owning DSH launcher can supply these exact application-private paths. */
internal object LinuxDshArchiveEnvironment {
    const val ANCHOR_ENV = "DAFEIYU_DSH_INSTALL_ANCHOR"
    const val DIRECTORY_ENV = "DAFEIYU_DSH_ARCHIVE_DIRECTORY"
    const val BUNDLE = "/dafeiyu-dsh"
    const val HOME = "/dafeiyu-dsh-home"
    const val ANCHOR_RELATIVE = "node_modules/@deepseek-ai/dsh/package.json"
    const val INSTALL_ANCHOR = "$BUNDLE/$ANCHOR_RELATIVE"
    const val ARCHIVE_RELATIVE = "dafeiyu-connection-plugin/workspace-lifecycle"
    const val ARCHIVE_DIRECTORY = "$HOME/$ARCHIVE_RELATIVE"

    fun validate(environment: Map<String, String>, mounts: Map<String, File>) {
        require(environment.keys.all { it in setOf("DSH_HOME", ANCHOR_ENV, DIRECTORY_ENV) }) { "不支持的运行进程环境变量" }
        environment["DSH_HOME"]?.let { home ->
            require(mounts[home]?.isDirectory == true) { "DSH_HOME 必须指向本次挂载的应用私有目录" }
        }
        if (ANCHOR_ENV !in environment && DIRECTORY_ENV !in environment) return
        require(environment[ANCHOR_ENV] == INSTALL_ANCHOR && environment[DIRECTORY_ENV] == ARCHIVE_DIRECTORY &&
            environment["DSH_HOME"] == HOME) { "归档预加载路径必须是本次 DSH 的固定私有映射" }
        val bundle = mounts[BUNDLE] ?: error("归档预加载缺少 DSH 运行包挂载")
        val anchor = File(bundle, ANCHOR_RELATIVE)
        verifyPath(bundle, anchor)
        require(anchor.isFile && anchor.length() in 1..64L * 1024) { "归档预加载必须指向实际 DSH 包身份文件" }
        val home = mounts[HOME] ?: error("归档预加载缺少 DSH 数据目录挂载")
        val directory = File(home, ARCHIVE_RELATIVE)
        verifyPath(home, directory)
        require(!directory.exists() || directory.isDirectory) { "归档状态路径必须为目录" }
    }

    private fun verifyPath(root: File, target: File) {
        require(root.isDirectory && !LinuxArchiveExtractor.isSymlink(root)) { "归档预加载挂载不能为链接" }
        val boundary = root.canonicalFile
        val resolved = target.canonicalFile
        require(resolved.path.startsWith(boundary.path + File.separator)) { "归档预加载路径越出私有挂载" }
        var current: File? = target
        while (current != null && current != root) {
            require(!LinuxArchiveExtractor.isSymlink(current)) { "归档预加载路径不能包含链接" }
            require(current == target || !current.exists() || current.isDirectory) { "归档预加载父路径必须为目录" }
            current = current.parentFile
        }
        require(current == root) { "归档预加载路径不在本次挂载中" }
    }
}

/** PRoot creates absent bind destinations with mode 000. Keep only the
 * application's admitted mount targets traversable, so a subsequent slot
 * clone can read them without relaxing archive or guest-user permissions. */
internal object LinuxGuestMounts {
    private val legacyDirectories = listOf("/dev", "/proc", "/sys", "/system", "/apex", "/workspace",
        "/dafeiyu-dsh", "/dafeiyu-dsh-home", "/dafeiyu-dsh-launch", "/dafeiyu-workspaces",
        "/dafeiyu-model-host", "/dafeiyu-model-component", "/dafeiyu-shared-helper", "/dafeiyu-shared-data",
        "/dafeiyu-sandbox-probe", LinuxSandboxContract.GUEST_NATIVE, "/dafeiyu-connection-host",
        "/dafeiyu-connection-slot", "/dafeiyu-connection-state", "/dafeiyu-extension-helper",
        "/dafeiyu-extension-packages", "/dafeiyu-provider-order")

    fun repairLegacy(root: File) {
        val boundary = boundary(root)
        for (guest in legacyDirectories) {
            val target = File(boundary, guest.removePrefix("/"))
            val mode = mode(target) ?: continue
            require(OsConstants.S_ISDIR(mode)) { "已有运行环境的挂载目标不是目录：$guest" }
            repairEmptyDirectory(target, mode, guest)
        }
        val script = File(boundary, "dafeiyu-script")
        mode(script)?.let { mode ->
            require(OsConstants.S_ISREG(mode)) { "已有脚本挂载目标类型无效" }
            if (mode and 0x1ff == 0) {
                require(script.length() == 0L) { "已有脚本挂载目标并非空占位文件" }
                Os.chmod(script.path, 0x180)
            }
        }
    }

    fun prepare(root: File, directories: List<String>, files: List<String> = emptyList()) {
        val boundary = boundary(root)
        for (guest in directories.distinct()) directory(boundary, guest)
        for (guest in files.distinct()) {
            val parts = parts(guest)
            if (parts.size > 1) directory(boundary, "/" + parts.dropLast(1).joinToString("/"))
            val target = File(boundary, parts.joinToString("/"))
            val current = mode(target)
            if (current == null) {
                require(target.createNewFile()) { "无法准备文件挂载目标" }
                Os.chmod(target.path, 0x180)
            } else {
                require(OsConstants.S_ISREG(current)) { "文件挂载目标不能是链接或特殊文件" }
                if (current and 0x1ff == 0) {
                    require(guest == "/dafeiyu-script" && target.length() == 0L) { "不可修复非空文件挂载目标" }
                    Os.chmod(target.path, 0x180)
                }
            }
        }
    }

    private fun boundary(root: File): File {
        require(!LinuxArchiveExtractor.isSymlink(root) && root.isDirectory) { "挂载目标根目录无效" }
        return root.canonicalFile
    }

    private fun parts(guest: String): List<String> {
        require(guest.startsWith('/') && guest.length in 2..512 &&
            guest.none { it.isISOControl() || it == '\\' || it == ':' }) { "挂载目标路径无效" }
        return guest.removePrefix("/").split('/').also { parts ->
            require(parts.all { it.isNotEmpty() && it != "." && it != ".." }) { "挂载目标路径越界" }
        }
    }

    private fun directory(boundary: File, guest: String) {
        var target = boundary
        val currentParts = ArrayList<String>()
        for (part in parts(guest)) {
            currentParts += part
            target = File(target, part)
            val current = mode(target)
            if (current == null) {
                require(target.mkdir()) { "无法准备目录挂载目标" }
                Os.chmod(target.path, 0x1c0)
            } else {
                require(OsConstants.S_ISDIR(current)) { "目录挂载目标不能是链接或特殊文件" }
                if (current and 0x1ff == 0) {
                    val path = "/" + currentParts.joinToString("/")
                    require(path in legacyDirectories) { "不可修复未知的不可读挂载目录" }
                    repairEmptyDirectory(target, current, path)
                }
            }
            require(target.canonicalPath.startsWith(boundary.path + File.separator)) { "挂载目标越出运行环境" }
        }
    }

    private fun repairEmptyDirectory(target: File, mode: Int, guest: String) {
        if (mode and 0x1ff != 0) return
        var admitted = false
        Os.chmod(target.path, 0x1c0)
        try {
            require(target.listFiles()?.isEmpty() == true) { "不可修复非空或不可读的旧挂载目录：$guest" }
            admitted = true
        } finally {
            if (!admitted) Os.chmod(target.path, mode and 0x1ff)
        }
    }

    private fun mode(file: File): Int? = try { Os.lstat(file.path).st_mode }
    catch (missing: android.system.ErrnoException) {
        if (missing.errno != OsConstants.ENOENT) throw missing
        null
    }
}
