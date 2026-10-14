package com.dafeiyu.mobile.data

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class DshServiceStatus { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

data class DshServiceSnapshot(
    val status: DshServiceStatus = DshServiceStatus.STOPPED,
    val version: String? = null,
    val port: Int? = null,
    val baseUrl: String? = null,
    val accessUrl: String? = null,
    val pid: Int? = null,
    val healthCode: Int? = null,
    val detail: String = "DSH 服务已停止",
    val lastError: String? = null,
) {
    override fun toString(): String = "DshServiceSnapshot(status=$status, version=$version, port=$port, " +
        "pid=$pid, healthCode=$healthCode, detail=$detail, lastError=$lastError, accessUrl=[redacted])"
}

data class DshHealthResult(val code: Int, val bodyPrefix: String = "")

/** Each launch owns its pipes, token, exit watcher and state generation. */
class DshRuntimeManager private constructor(
    private val currentSlot: () -> DshRuntimeSlot?,
    private val launchProcess: suspend (DshRuntimeSlot, Int, File?) -> LinuxManagedProcess,
    private val queryHealth: suspend (String) -> DshHealthResult?,
    private val rollbackSlot: suspend () -> DshRuntimeSlot?,
    private val shared: Shared,
    private val startupTimeoutMs: Long,
) {
    constructor(
        context: Context,
        runtime: LinuxRuntimeManager = LinuxRuntimeManager(context),
        installer: DshRuntimeInstaller = DshRuntimeInstaller(context, runtime),
    ) : this(
        installer::current,
        { slot, port, workspace -> launchAndroid(context.applicationContext, runtime, installer, slot, port, workspace) },
        { url -> DshWebHealth.check(url) },
        installer::rollback,
        stores.computeIfAbsent(File(context.applicationContext.filesDir, "dsh-runtime").canonicalPath) { Shared() },
        60_000,
    )

    internal constructor(
        currentSlot: () -> DshRuntimeSlot?,
        launchProcess: suspend (DshRuntimeSlot, Int, File?) -> LinuxManagedProcess,
        queryHealth: suspend (String) -> DshHealthResult?,
        rollbackSlot: suspend () -> DshRuntimeSlot? = { null },
        startupTimeoutMs: Long = 60_000,
    ) : this(currentSlot, launchProcess, queryHealth, rollbackSlot, Shared(), startupTimeoutMs)

    val snapshot: StateFlow<DshServiceSnapshot> = shared.snapshot.asStateFlow()
    private val closed = AtomicBoolean(false)

    suspend fun refresh(): DshServiceSnapshot = withContext(Dispatchers.IO) {
        shared.current?.takeIf { !it.process.isAlive }?.let { target ->
            runCatching { cleanupSession(target) }.onFailure { reportCleanupFailure(target, it) }
        }
        val session = shared.lock.withLock {
            shared.current?.takeIf { !it.process.isAlive }?.let { finishedLocked(it, null) }
            val target = shared.current
            if (target == null && shared.snapshot.value.status != DshServiceStatus.ERROR) {
                val slot = currentSlot()
                shared.snapshot.value = DshServiceSnapshot(version = slot?.manifest?.dshVersion,
                    detail = if (slot == null) "DSH 运行包未安装" else "DSH 服务已停止")
            }
            target
        }
        if (session == null || shared.snapshot.value.status in setOf(DshServiceStatus.STARTING, DshServiceStatus.STOPPING)) return@withContext snapshot.value
        val access = session.accessUrl ?: return@withContext snapshot.value
        val health = queryHealth(access)?.takeIf { it.code == 200 }
        shared.lock.withLock {
            if (shared.current !== session || session.stopRequested || !session.process.isAlive) return@withLock snapshot.value
            shared.snapshot.value = shared.snapshot.value.copy(
                status = if (health != null) DshServiceStatus.RUNNING else DshServiceStatus.ERROR,
                healthCode = health?.code,
                detail = if (health != null) "正在运行 · 端口 ${session.port}" else "DSH 进程仍在运行，已认证网页暂时无法访问",
                lastError = if (health != null) null else "DSH 已认证网页健康检查失败",
            )
            snapshot.value
        }
    }

    suspend fun start(workspace: File? = null, requestedPort: Int? = null): DshServiceSnapshot = withContext(Dispatchers.IO) {
        check(!closed.get()) { "DSH 服务管理器已关闭" }
        shared.current?.takeIf { !it.process.isAlive }?.let { cleanupSession(it) }
        val session = shared.lock.withLock {
            shared.current?.takeIf { !it.process.isAlive }?.let { finishedLocked(it, null) }
            if (shared.current != null) return@withLock null
            val slot = currentSlot() ?: throw IOException("请先安装并校验 DSH 运行组件")
            val port = requestedPort ?: freePort()
            require(port in 1024..65535) { "DSH 端口无效" }
            val epoch = ++shared.epoch
            shared.snapshot.value = DshServiceSnapshot(status = DshServiceStatus.STARTING,
                version = slot.manifest.dshVersion, port = port, baseUrl = "http://127.0.0.1:$port/", detail = "正在启动 DSH 服务")
            try {
                // Adopt the actual native handle before caller cancellation can
                // leave it unowned. Health waiting remains cancellable below.
                withContext(NonCancellable) {
                    val process = launchProcess(slot, port, workspace)
                    Session(epoch, process, slot, port).also { created ->
                    shared.current = created
                    shared.snapshot.value = shared.snapshot.value.copy(pid = process.pid)
                    created.outputJob = shared.scope.launch(start = CoroutineStart.LAZY) { drain(created, process.output, false) }
                    created.errorJob = shared.scope.launch(start = CoroutineStart.LAZY) { drain(created, process.errors, true) }
                    if (process.retainsPipeLease) process.registerPipeReaders(checkNotNull(created.outputJob), checkNotNull(created.errorJob))
                    created.outputJob?.start()
                    created.errorJob?.start()
                    created.exitJob = shared.scope.launch {
                        val exit = runCatching { process.awaitExit() }.getOrNull()
                        try { cleanupSession(created, exit) }
                        catch (failure: Throwable) { reportCleanupFailure(created, failure) }
                    }
                    }
                }
            } catch (failure: Throwable) {
                shared.snapshot.value = shared.snapshot.value.copy(status = DshServiceStatus.ERROR,
                    detail = "DSH 服务启动失败：${safeMessage(failure)}", lastError = safeMessage(failure), pid = null)
                throw failure
            }
        } ?: return@withContext refresh()
        try {
            currentCoroutineContext().ensureActive()
            val health = waitForHealth(session)
            shared.lock.withLock {
                ensureOwned(session)
                shared.snapshot.value = shared.snapshot.value.copy(status = DshServiceStatus.RUNNING,
                    accessUrl = session.accessUrl, healthCode = health.code,
                    detail = "正在运行 · 端口 ${session.port}", lastError = null)
                snapshot.value
            }
        } catch (failure: Throwable) {
            failAndStop(session, failure)
            throw failure
        }
    }

    suspend fun stop(): DshServiceSnapshot = stopExpected(null)

    private suspend fun stopExpected(expected: Session?): DshServiceSnapshot = withContext(Dispatchers.IO) {
        val session = shared.lock.withLock {
            val target = shared.current ?: return@withLock null
            if (expected != null && target !== expected) return@withLock null
            target.stopRequested = true
            shared.snapshot.value = shared.snapshot.value.copy(status = DshServiceStatus.STOPPING, detail = "正在停止 DSH 服务")
            target
        } ?: return@withContext snapshot.value
        withContext(NonCancellable) {
            try {
                cleanupSession(session)
                shared.lock.withLock {
                    if (shared.current === session) finishedLocked(session, null)
                    if (shared.epoch == session.epoch && shared.current == null) shared.snapshot.value = stopped(session)
                }
            } catch (failure: Throwable) {
                shared.lock.withLock {
                    if (shared.current === session) shared.snapshot.value = shared.snapshot.value.copy(
                        status = DshServiceStatus.ERROR, detail = "DSH 服务停止失败；进程仍占用运行环境",
                        lastError = safeMessage(failure))
                }
                throw failure
            }
        }
        snapshot.value
    }

    suspend fun restart(workspace: File? = null, requestedPort: Int? = null): DshServiceSnapshot {
        stop()
        return start(workspace, requestedPort)
    }

    suspend fun rollbackAndRestart(workspace: File? = null, requestedPort: Int? = null): DshServiceSnapshot {
        stop()
        check(rollbackSlot() != null) { "没有可回退的 DSH 运行版本" }
        return start(workspace, requestedPort)
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            val target = shared.current
            if (target != null) shared.scope.launch { runCatching { stopExpected(target) } }
        }
        // Keep the application-owned watcher and lease if termination fails.
    }

    suspend fun health(): DshHealthResult? = withContext(Dispatchers.IO) {
        val session = shared.current ?: return@withContext null
        val access = session.accessUrl ?: return@withContext null
        if (!session.process.isAlive || session.stopRequested) return@withContext null
        val health = queryHealth(access)?.takeIf { it.code == 200 }
        if (shared.current === session && session.process.isAlive && !session.stopRequested) health else null
    }

    private suspend fun waitForHealth(session: Session): DshHealthResult {
        val deadline = System.nanoTime() + startupTimeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            ensureOwned(session)
            session.accessUrl?.let { access ->
                val health = queryHealth(access)?.takeIf { it.code == 200 }
                ensureOwned(session)
                if (health != null) return health
            }
            delay(100)
        }
        throw IOException("DSH 未在期限内提供本进程的已认证网页")
    }

    private fun ensureOwned(session: Session) {
        if (session.stopRequested || shared.epoch != session.epoch) throw CancellationException("DSH 启动已取消或替换")
        if (!session.process.isAlive) {
            val diagnostic = synchronized(session.logs) { sanitize(session.errorTail) }.ifBlank { "未提供已认证网页" }
            throw IOException("DSH 服务进程提前退出：$diagnostic")
        }
        if (shared.current !== session) throw CancellationException("DSH 启动已取消或替换")
    }

    private suspend fun failAndStop(session: Session, failure: Throwable) = withContext(NonCancellable) {
        val cancelledByStop = session.stopRequested && failure is CancellationException
        session.stopRequested = true
        val cleanup = runCatching { cleanupSession(session) }
        shared.lock.withLock {
            if (shared.current === session && session.cleanupComplete) shared.current = null
            if (shared.epoch == session.epoch && cancelledByStop && session.cleanupComplete) {
                shared.snapshot.value = stopped(session)
            } else if (shared.epoch == session.epoch) shared.snapshot.value = shared.snapshot.value.copy(
                status = DshServiceStatus.ERROR,
                detail = if (cleanup.isSuccess) "DSH 服务启动失败：${safeMessage(failure)}" else "DSH 启动失败，进程仍在清理",
                pid = session.process.pid.takeIf { session.process.isAlive }, healthCode = null, accessUrl = null,
                lastError = safeMessage(failure),
            )
        }
        cleanup.exceptionOrNull()?.let { failure.addSuppressed(it) }
    }

    private fun finishedLocked(session: Session, exit: Int?) {
        if (shared.current !== session || session.process.isAlive || !session.cleanupComplete) return
        shared.current = null
        shared.snapshot.value = if (session.stopRequested) stopped(session) else DshServiceSnapshot(
            status = DshServiceStatus.ERROR, version = session.slot.manifest.dshVersion,
            detail = "DSH 服务进程已退出（退出码 ${exit ?: "未知"}）",
            lastError = synchronized(session.logs) { sanitize(session.errorTail).ifBlank { "exit=${exit ?: "unknown"}" } },
        )
    }

    private fun stopped(session: Session) = DshServiceSnapshot(version = session.slot.manifest.dshVersion, detail = "DSH 服务已停止")

    /** A dead tracer still owns the runtime while its real reader Jobs are
     * incomplete. Keep this session reachable for a later cleanup retry. */
    private suspend fun cleanupSession(session: Session, exit: Int? = null) = withContext(NonCancellable + Dispatchers.IO) {
        withTimeout(30_000) {
            session.cleanupLock.withLock {
                if (session.cleanupComplete) return@withLock
                session.process.stop()
                if (session.process.retainsPipeLease) session.process.awaitExit()
                disposeReaders(session)
                if (session.process.retainsPipeLease) session.process.completePipeCleanup()
                session.cleanupComplete = true
                shared.lock.withLock { finishedLocked(session, exit) }
            }
        }
    }

    private suspend fun reportCleanupFailure(session: Session, failure: Throwable) = shared.lock.withLock {
        if (shared.current === session && !session.cleanupComplete) shared.snapshot.value = shared.snapshot.value.copy(
            status = DshServiceStatus.ERROR, detail = "DSH 服务仍在清理，运行环境继续保留",
            lastError = safeMessage(failure), healthCode = null, accessUrl = null)
    }

    private suspend fun disposeReaders(session: Session) {
        session.outputJob?.cancelAndJoin()
        session.errorJob?.cancelAndJoin()
    }

    private suspend fun drain(session: Session, input: InputStream, error: Boolean) = withContext(Dispatchers.IO) {
        val reader = InputStreamReader(input, Charsets.UTF_8)
        val buffer = CharArray(4096)
        val line = StringBuilder()
        var oversize = false
        while (isActive) {
            val count = runCatching { reader.read(buffer) }.getOrDefault(-1)
            if (count < 0) break
            if (error) {
                synchronized(session.logs) { session.errorTail = (session.errorTail + String(buffer, 0, count)).takeLast(8192) }
            } else {
                for (index in 0 until count) {
                    val character = buffer[index]
                    if (character == '\n') {
                        if (!oversize && session.accessUrl == null && shared.current === session) {
                            DshWebHealth.accessLine(line.toString(), session.port)?.let { session.accessUrl = it }
                        }
                        line.setLength(0)
                        oversize = false
                    } else if (!oversize) {
                        if (line.length >= 8192) { line.setLength(0); oversize = true }
                        else line.append(character)
                    }
                }
            }
            // Continue draining after the retained tail is full so verbose
            // services never block on a pipe and memory stays bounded.
        }
    }

    private class Session(val epoch: Long, val process: LinuxManagedProcess, val slot: DshRuntimeSlot, val port: Int) {
        val cleanupLock = Mutex()
        @Volatile var cleanupComplete = false
        @Volatile var accessUrl: String? = null
        @Volatile var stopRequested = false
        val logs = Any()
        var errorTail = ""
        var outputJob: Job? = null
        var errorJob: Job? = null
        var exitJob: Job? = null
    }

    private class Shared {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val lock = Mutex()
        val snapshot = MutableStateFlow(DshServiceSnapshot())
        @Volatile var current: Session? = null
        @Volatile var epoch = 0L
    }

    companion object {
        const val PROCESS_ID = "dsh-service"
        private val stores = ConcurrentHashMap<String, Shared>()
        internal fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

        private suspend fun launchAndroid(app: Context, runtime: LinuxRuntimeManager, installer: DshRuntimeInstaller,
            slot: DshRuntimeSlot, port: Int, workspace: File?): LinuxManagedProcess = installer.withCurrentSlot(slot.manifest.digest()) { validated ->
            runtime.refresh()
            check(runtime.snapshot.value.any { it.id == "node" && it.status == LinuxComponentStatus.READY }) { "Node.js 组件尚未就绪" }
            val identity = runtime.nodeIdentity()
            check(identity.platform == "linux" && identity.architecture == validated.manifest.nodeArchitecture) { "DSH 运行包与当前 Linux ABI 不匹配" }
            check(identity.version == "v${validated.manifest.nodeVersion.removePrefix("v")}") {
                "DSH 需要 Node.js ${validated.manifest.nodeVersion}，实际为 ${identity.version}"
            }
            DshArchiveLaunchContract.verifyOwningBundle(validated.directory, validated.manifest.dshVersion)
            val pluginManifest = app.assets.open("connection-plugin/manifest.json").use {
                ConnectionPackageValidator.manifest(ConnectionPackageValidator.readBounded(it, ConnectionPackageValidator.MAX_MANIFEST))
            }
            val plugin = ConnectionPluginPackage(app).ensureBundled(DshArchiveLaunchContract.CONNECT_VERSION)
            val pluginSlot = DshArchiveLaunchContract.verifyPreparedPackage(pluginManifest, plugin)
            val mobileUiInstaller = DshMobileUiPackage(app)
            val mobileUi = mobileUiInstaller.ensureBundled()
            val home = File(app.filesDir, "dsh-home")
            require(!LinuxArchiveExtractor.isSymlink(home) && (home.isDirectory || home.mkdirs())) { "无法创建 DSH 数据目录" }
            DshExtensionTransaction.requireReady(home)
            val providerOrder = ModelProviderStore(app).prepareDshProviderOrder()
            // Durable session cwd identities must survive switching the active
            // workspace. Only this application-owned parent is shared; the
            // selected workspace retains its existing /workspace alias.
            val workspaces = File(app.filesDir, "linux-workspaces")
            require(!LinuxArchiveExtractor.isSymlink(workspaces) && (workspaces.isDirectory || workspaces.mkdirs())) {
                "无法创建 DSH 工作区映射"
            }
            val stableCwd = workspace?.canonicalFile?.takeIf { folder ->
                val parent = workspaces.canonicalFile
                folder == parent || folder.path.startsWith(parent.path + File.separator)
            }?.relativeTo(workspaces.canonicalFile)?.invariantSeparatorsPath?.let { relative ->
                "/dafeiyu-workspaces" + if (relative.isBlank()) "" else "/$relative"
            }
            val launch = File(app.noBackupFilesDir, "dsh-android-launch")
            require(!LinuxArchiveExtractor.isSymlink(launch) && (launch.isDirectory || launch.mkdirs()))
            for (name in listOf("android-launch.mjs", "android-sandbox.mjs", "android-provider-order.mjs", "android-persistence.mjs", "android-mobile-ui.mjs")) {
                val target = File(launch, name)
                require(!LinuxArchiveExtractor.isSymlink(target))
                app.assets.open("dsh-runtime/$name").use { input -> target.outputStream().use { input.copyTo(it) } }
            }
            runtime.startManagedProcess(PROCESS_ID,
                DshArchiveLaunchContract.command(app.applicationInfo.nativeLibraryDir, port, mobileUi.guestPath),
                mapOf("/dafeiyu-dsh" to validated.directory, "/dafeiyu-dsh-home" to home, "/dafeiyu-dsh-launch" to launch,
                    "/dafeiyu-workspaces" to workspaces, "/dafeiyu-provider-order" to providerOrder,
                    "/dafeiyu-extension-packages" to mobileUi.sources,
                    DshArchiveLaunchContract.CONNECT_MOUNT to pluginSlot), workspace,
                DshArchiveLaunchContract.environment(), retainLeaseUntilPipesComplete = true, guestWorkingDirectory = stableCwd,
                beforeStart = {
                    DshExtensionTransaction.requireReady(home)
                    DshArchiveLaunchContract.verifyOwningBundle(validated.directory, validated.manifest.dshVersion)
                    DshArchiveLaunchContract.verifyPreparedPackage(pluginManifest, plugin)
                    mobileUiInstaller.verifyInstalled(mobileUi)
                    LinuxDshArchiveEnvironment.validate(DshArchiveLaunchContract.environment(),
                        mapOf(LinuxDshArchiveEnvironment.BUNDLE to validated.directory, LinuxDshArchiveEnvironment.HOME to home))
                })
        }

        private fun safeMessage(failure: Throwable): String = sanitize(failure.message ?: "未知运行错误")
        private fun sanitize(value: String): String = value
            .replace(Regex("https?://[^\\s)]+"), "[URL omitted]")
            .replace(Regex("(?i)(token|api[_ -]?key|authorization|password)([=: ]+)[^\\s,;]+"), "$1$2[redacted]")
            .replace(Regex("\\u001B\\[[0-9;]*m"), "").takeLast(600)
    }
}

/** Package preparation is not a capability claim; the preloader verifies the actual fixed service realm. */
internal object DshArchiveLaunchContract {
    const val CONNECT_VERSION = "0.3.0"
    const val CONNECT_MOUNT = "/dafeiyu-connection-slot"
    const val PRELOAD = "$CONNECT_MOUNT/package/scripts/archive-preload.mjs"

    fun environment(): Map<String, String> = mapOf("DSH_HOME" to LinuxDshArchiveEnvironment.HOME,
        LinuxDshArchiveEnvironment.ANCHOR_ENV to LinuxDshArchiveEnvironment.INSTALL_ANCHOR,
        LinuxDshArchiveEnvironment.DIRECTORY_ENV to LinuxDshArchiveEnvironment.ARCHIVE_DIRECTORY)

    fun command(nativeDirectory: String, port: Int, mobileUiPath: String? = null): List<String> {
        require(port in 1024..65535)
        require(mobileUiPath == null || mobileUiPath.matches(Regex("/dafeiyu-extension-packages/mobile-ui/[a-f0-9]{64}/package")))
        return listOf("/usr/local/bin/node", "--import", PRELOAD, "/dafeiyu-dsh-launch/android-launch.mjs",
            LinuxDshArchiveEnvironment.BUNDLE, nativeDirectory) +
            (mobileUiPath?.let { listOf("--dafeiyu-mobile-ui", it) } ?: emptyList()) +
            listOf("web", "--host", "127.0.0.1", "--port", port.toString(), "--no-open")
    }

    fun verifyOwningBundle(directory: File, version: String) {
        require(version == "0.2.1-alpha.1") { "归档预加载不支持未经审查的 DSH 版本" }
        val metadata = File(directory, LinuxDshArchiveEnvironment.ANCHOR_RELATIVE)
        require(metadata.isFile && !LinuxArchiveExtractor.isSymlink(metadata) &&
            metadata.canonicalPath.startsWith(directory.canonicalPath + File.separator)) { "实际 DSH 包身份文件缺失或越界" }
        val json = metadata.inputStream().use {
            org.json.JSONObject(ConnectionPackageValidator.readBounded(it, 64 * 1024).toString(Charsets.UTF_8))
        }
        require(json.optString("name") == "@deepseek-ai/dsh" && json.optString("version") == version && json.optString("license") == "MIT") {
            "归档预加载必须使用已审查的实际 DSH 包"
        }
    }

    fun verifyPreparedPackage(manifest: ConnectionPackageValidator.Manifest, plugin: File): File {
        val slot = plugin.parentFile ?: error("归档预加载缺少固定插件目录")
        require(manifest.version == CONNECT_VERSION && plugin.name == "package" && slot.name == manifest.sha256) {
            "归档预加载插件冻结包不匹配"
        }
        require(ConnectionPackageValidator.verifyInstalled(manifest, slot).canonicalFile == plugin.canonicalFile)
        for (relative in listOf("scripts/archive-preload.mjs", "lib/framework/remote-archive-dsh.js", "lib/framework/remote-archive.js")) {
            val file = File(plugin, relative)
            require(file.isFile && !LinuxArchiveExtractor.isSymlink(file) && file.length() in 1..1024L * 1024) {
                "固定插件缺少安全归档预加载入口"
            }
        }
        return slot
    }
}

/** Pinned DSH browser-auth contract: exact launch line, 303 cookie exchange,
 * then a 200 HTML document with the host-injected boot graph. */
internal object DshWebHealth {
    fun accessLine(line: String, port: Int): String? {
        val clean = line.replace(Regex("\\u001B\\[[0-9;]*m"), "").trim()
        if (!clean.startsWith("dsh web: ")) return null
        val candidate = clean.removePrefix("dsh web: ").substringBefore(" (LAN: ")
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (uri.scheme != "http" || uri.host != "127.0.0.1" || uri.port != port || uri.rawPath != "/" ||
            uri.userInfo != null || uri.fragment != null || uri.rawQuery?.matches(Regex("token=[A-Za-z0-9_-]{43}")) != true) return null
        return candidate
    }

    fun check(accessUrl: String): DshHealthResult? {
        val uri = runCatching { URI(accessUrl) }.getOrNull() ?: return null
        if (accessLine("dsh web: $accessUrl", uri.port) == null || uri.port !in 1024..65535) return null
        val first = connection(uri) ?: return null
        val cookie = try {
            if (first.responseCode != 303 || first.getHeaderField("Location") != "./" ||
                first.getHeaderField("Cache-Control") != "no-store") return null
            val name = "dsh-auth-" + Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest("127.0.0.1:${uri.port}".toByteArray(Charsets.UTF_8)))
            val raw = first.getHeaderField("Set-Cookie") ?: return null
            raw.substringBefore(';').takeIf { it.matches(Regex("${Regex.escape(name)}=v1\\.[A-Za-z0-9_-]{1,2048}\\.[A-Za-z0-9_-]{43}")) } ?: return null
        } catch (_: Exception) { return null } finally { first.disconnect() }
        val next = connection(URI("http://127.0.0.1:${uri.port}/")) ?: return null
        return try {
            next.setRequestProperty("Cookie", cookie)
            if (next.responseCode != 200 || next.contentType?.startsWith("text/html") != true) return null
            val body = String(next.inputStream.use { readBody(it) }, Charsets.UTF_8)
            if (!body.contains("__DSH_BOOT__") || !body.contains("<html", ignoreCase = true)) null
            else DshHealthResult(200, "DSH Web authenticated")
        } catch (_: Exception) { null } finally { next.disconnect() }
    }

    private fun connection(uri: URI): HttpURLConnection? = runCatching {
        (uri.toURL().openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 1000; readTimeout = 1000
            instanceFollowRedirects = false; useCaches = false
        }
    }.getOrNull()

    private fun readBody(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= 256 * 1024) {
            val count = input.read(buffer, 0, minOf(buffer.size, 256 * 1024 + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        require(output.size() <= 256 * 1024) { "DSH 网页健康响应超过限制" }
        return output.toByteArray()
    }
}
