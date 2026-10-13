package com.dafeiyu.mobile.data

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

enum class ConnectPluginHostStatus { STOPPED, STARTING, RUNNING, FAILED, PAUSED }

/** A loaded identity is published only after the real Node process calls the native Provider. */
data class ConnectPluginHostSnapshot(
    val status: ConnectPluginHostStatus = ConnectPluginHostStatus.STOPPED,
    val version: String? = null,
    val archiveSha256: String? = null,
    val archiveSizeBytes: Long? = null,
    val nodePid: Int? = null,
    val launcherPid: Int? = null,
    val reason: String? = null,
)

/** One application-owned Connect process; LinuxRuntimeManager retains its actual slot lease. */
class ConnectPluginHost private constructor(context: Context) : RemoteDshProviderCall {
    private val app = context.applicationContext
    private val root = File(app.noBackupFilesDir, "connection-host")
    private val runtime = LinuxRuntimeManager(app)
    private val packages = ConnectionPluginPackage(app)
    private val lifecycle = Mutex()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(ConnectPluginHostSnapshot())
    val snapshot: StateFlow<ConnectPluginHostSnapshot> = mutable.asStateFlow()
    private var live: Live? = null
    private var backgroundPaused = false
    private var resumeAfterPause = false

    private class Live(val process: LinuxManagedProcess, val bridge: LocalPluginBridge) {
        val hello = CompletableDeferred<JSONObject>()
        val ready = CompletableDeferred<JSONObject>()
        val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
        val writes = Mutex()
        var reader: Job? = null
        var stderr: Job? = null
        @Volatile var stopping = false
        @Volatile var healthy = false
    }

    suspend fun start(): ConnectPluginHostSnapshot = withContext(Dispatchers.IO) {
        lifecycle.withLock { startLocked() }
    }

    private suspend fun startLocked(): ConnectPluginHostSnapshot {
        check(!backgroundPaused) { "省电模式已暂停后台插件宿主，请回到前台" }
        live?.let { current ->
            if (current.healthy && current.process.isAlive && !current.stopping) return mutable.value
            stopLocked(ConnectPluginHostStatus.STOPPED)
        }
        mutable.value = ConnectPluginHostSnapshot(ConnectPluginHostStatus.STARTING)
        var instance: Live? = null
        var unownedBridge: LocalPluginBridge? = null
        try {
            check(root.isDirectory || root.mkdirs()) { "插件宿主目录不可用" }
            val state = File(root, "state").apply { check(isDirectory || mkdirs()) }
            val worker = prepareWorker()
            val manifest = app.assets.open("connection-plugin/manifest.json").use {
                ConnectionPackageValidator.manifest(ConnectionPackageValidator.readBounded(it, ConnectionPackageValidator.MAX_MANIFEST))
            }
            val pkg = packages.ensureBundled(VERSION)
            check(pkg.parentFile?.name == manifest.sha256 && manifest.version == VERSION) { "插件冻结包不匹配" }
            val bridge = LocalPluginBridge(app).also { unownedBridge = it; it.start() }
            val process = runtime.startManagedProcess(PROCESS_ID,
                listOf("/usr/local/bin/node", "--max-old-space-size=96", "/dafeiyu-connection-host/${worker.name}",
                    "/dafeiyu-connection-slot/package", "/dafeiyu-connection-state"),
                mounts = mapOf("/dafeiyu-connection-host" to root, "/dafeiyu-connection-slot" to checkNotNull(pkg.parentFile),
                    "/dafeiyu-connection-state" to state))
            val current = Live(process, bridge)
            instance = current
            unownedBridge = null
            live = current
            current.reader = ioScope.launch { readFrames(current) }
            current.stderr = ioScope.launch {
                // Raw stderr can contain upstream state. Drain it without recording credentials.
                runCatching { val buffer = ByteArray(4096); while (process.errors.read(buffer) >= 0) currentCoroutineContext().ensureActive() }
            }
            ioScope.launch {
                val exit = process.awaitExit()
                failPending(current, AriesProviderException("launcher_unavailable"))
                current.bridge.close()
                lifecycle.withLock {
                    if (live === current && !current.stopping) {
                        live = null
                        mutable.value = ConnectPluginHostSnapshot(ConnectPluginHostStatus.FAILED,
                            reason = "Connect 运行进程已退出（$exit）")
                    }
                }
            }
            val hello = withTimeout(20_000) { current.hello.await() }
            val nodePid = hello.getInt("pid")
            val architecture = when (Build.SUPPORTED_ABIS.firstOrNull()) { "arm64-v8a" -> "arm64"; "x86_64" -> "x64"; else -> "unsupported" }
            val version = hello.getString("node").removePrefix("v").split('.').map { it.toIntOrNull() ?: -1 }
            check(hello.optString("platform") == "linux" && hello.optString("architecture") == architecture &&
                version.size >= 2 && (version[0] > 22 || version[0] == 22 && version[1] >= 6)) { "模型运行组件的 Node.js 版本或 ABI 不支持 Connect" }
            val executable = File(app.filesDir, "linux-runtime/slots/${process.runtimeSlot}/usr/local/bin/node")
            bridge.bindRuntime(nodePid, process.pid, executable)
            write(current, JSONObject().put("kind", "configure").put("protocol", LocalPluginBridge.PROTOCOL).put("version", VERSION)
                .put("sha256", manifest.sha256).put("size_bytes", manifest.sizeBytes)
                .put("bridge", JSONObject().put("DAFEIYU_CONNECTION_SOCKET", bridge.name())
                    .put("DAFEIYU_CONNECTION_CAPABILITY", bridge.capabilityForProcess())
                    .put("DAFEIYU_CONNECTION_GENERATION", bridge.generationForProcess())))
            val ready = withTimeout(25_000) { current.ready.await() }
            check(process.isAlive && ready.getInt("pid") == nodePid && ready.optString("version") == VERSION &&
                ready.optString("protocol") == LocalPluginBridge.PROTOCOL && ready.optString("sha256") == manifest.sha256 &&
                ready.optLong("size_bytes") == manifest.sizeBytes &&
                ready.optJSONObject("state")?.optString("protocol") == LocalPluginBridge.PROTOCOL) { "Connect 宿主未能完成真实 Provider 握手" }
            current.healthy = true
            return ConnectPluginHostSnapshot(ConnectPluginHostStatus.RUNNING, VERSION, manifest.sha256, manifest.sizeBytes,
                nodePid, process.pid).also { mutable.value = it }
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                unownedBridge?.close()
                if (instance != null) stopLocked(ConnectPluginHostStatus.STOPPED)
            }
            mutable.value = ConnectPluginHostSnapshot(ConnectPluginHostStatus.FAILED,
                reason = if (error is CancellationException) "Connect 启动已取消" else error.message?.take(240) ?: "Connect 宿主启动失败")
            throw error
        }
    }

    override suspend fun call(method: String, params: JSONObject, expectedRemoteScope: RemoteDshScope?): JSONObject =
        callInternal(method, params, expectedRemoteScope, null, null)

    override suspend fun callInScope(method: String, params: JSONObject, scope: RemoteDshScope, capability: String,
        acknowledgeMessageId: String?): JSONObject =
        callInternal(method, params, scope, capability, acknowledgeMessageId)

    /** Trusted instrumentation observes the real Provider without widening the frozen wire. */
    internal suspend fun setNativeDiagnosticObserver(observer: ((AriesConnectionProvider.Diagnostic) -> Unit)?) = lifecycle.withLock {
        val current = live
        if (current == null) {
            if (observer != null) throw AriesProviderException("launcher_unavailable")
        } else current.bridge.setProviderDiagnosticObserver(observer)
    }

    private suspend fun callInternal(method: String, params: JSONObject, expectedRemoteScope: RemoteDshScope?,
        scopedCapability: String?, acknowledgeMessageId: String?): JSONObject =
        withContext(Dispatchers.IO) {
            require(method in METHODS) { "unsupported_method" }
            if (scopedCapability != null) LocalPluginIpcPolicy.NativeScopedAdmissions.validate(method, params,
                checkNotNull(expectedRemoteScope).toJson(), scopedCapability, acknowledgeMessageId)
            val current = lifecycle.withLock { startLocked(); checkNotNull(live) }
            val id = UUID.randomUUID().toString()
            val reply = CompletableDeferred<JSONObject>()
            synchronized(current.pending) {
                check(!current.stopping && current.healthy && current.process.isAlive) { "launcher_unavailable" }
                check(current.pending.size < LocalPluginIpcPolicy.MAX_CLIENTS) { "Connect 本机请求已达到并发上限" }
                current.pending[id] = reply
            }
            var nativeAdmission: String? = null
            var scopedAdmission: String? = null
            try {
                val frame = JSONObject().put("kind", "call").put("id", id).put("method", method).put("params", JSONObject(params.toString()))
                expectedRemoteScope?.let {
                    frame.put("remote_scope", it.toJson())
                    if (scopedCapability == null || method == "send_encrypted_message" &&
                        params.optString("kind") == RemoteDshClient.REQUEST_KIND &&
                        params.optJSONObject("body")?.optString("operation") in RemoteDshClient.CONSUMABLE_TRANSFER_OPERATIONS)
                        nativeAdmission = current.bridge.registerNativeBulk(method, frame.getJSONObject("params"), it)
                    else scopedAdmission = current.bridge.registerNativeScoped(method, frame.getJSONObject("params"), it,
                        scopedCapability, acknowledgeMessageId)
                }
                write(current, frame)
                val response = withTimeout(60_000) { reply.await() }
                if (response.opt("success") != true) throw AriesProviderException(response.optString("error_code", "protocol_error"))
                response.optJSONObject("data") ?: throw AriesProviderException("protocol_error")
            } catch (error: CancellationException) {
                withContext(NonCancellable) { runCatching { write(current, JSONObject().put("kind", "cancel").put("id", id)) } }
                throw error
            } finally {
                current.bridge.revokeNativeBulk(nativeAdmission)
                current.bridge.revokeNativeScoped(scopedAdmission)
                current.pending.remove(id, reply)
            }
        }

    override suspend fun retireTransferReceipt(scope: RemoteDshScope, requestId: String, operation: String): JSONObject =
        withContext(Dispatchers.IO) {
            val current = lifecycle.withLock {
                val value = live ?: throw AriesProviderException("launcher_unavailable")
                if (backgroundPaused || value.stopping || !value.healthy || !value.process.isAlive)
                    throw AriesProviderException("launcher_unavailable")
                value
            }
            val receipt = current.bridge.retireTransferReceipt(scope, requestId, operation)
            lifecycle.withLock { if (live !== current || current.stopping) throw AriesProviderException("launcher_unavailable") }
            receipt
        }

    suspend fun stop() = withContext(Dispatchers.IO) { lifecycle.withLock { stopLocked(ConnectPluginHostStatus.STOPPED) } }

    suspend fun restart(): ConnectPluginHostSnapshot = withContext(Dispatchers.IO) {
        lifecycle.withLock { stopLocked(ConnectPluginHostStatus.STOPPED); startLocked() }
    }

    /** Root calls this only for the power-saving background transition. */
    suspend fun setBackgroundPaused(paused: Boolean) = withContext(Dispatchers.IO) {
        lifecycle.withLock {
            if (backgroundPaused == paused) return@withLock
            backgroundPaused = paused
            if (paused) {
                resumeAfterPause = live != null
                stopLocked(ConnectPluginHostStatus.PAUSED)
            } else if (resumeAfterPause) {
                resumeAfterPause = false
                startLocked()
            } else mutable.value = ConnectPluginHostSnapshot()
        }
    }

    private suspend fun stopLocked(status: ConnectPluginHostStatus) {
        val current = live
        if (current != null) withContext(NonCancellable + Dispatchers.IO) {
            current.stopping = true
            current.healthy = false
            current.bridge.close()
            failPending(current, AriesProviderException("request_cancelled"))
            runCatching { write(current, JSONObject().put("kind", "dispose")) }
            withTimeoutOrNull(1500) { current.process.awaitExit() }
            current.process.stop()
            current.reader?.cancel()
            current.stderr?.cancel()
            live = null
        }
        mutable.value = ConnectPluginHostSnapshot(status)
    }

    private suspend fun write(current: Live, frame: JSONObject) {
        val bytes = (frame.toString() + "\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= LocalPluginIpcPolicy.MAX_REQUEST_BYTES) { "Connect 请求超过大小限制" }
        current.writes.withLock { check(current.process.isAlive) { "launcher_unavailable" }; current.process.input.write(bytes); current.process.input.flush() }
    }

    private suspend fun readFrames(current: Live) {
        try {
            BufferedInputStream(current.process.output).use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val frame = readFrame(input) ?: break
                    when (frame.optString("kind")) {
                        "hello" -> check(current.hello.complete(frame)) { "duplicate_host_handshake" }
                        "ready" -> check(current.ready.complete(frame)) { "duplicate_host_handshake" }
                        "response" -> current.pending[frame.optString("id")]?.complete(frame)
                        "failed" -> {
                            val stage = frame.optString("stage").takeIf { it in CONFIGURE_STAGES } ?: "unknown"
                            val code = frame.optString("error_code").takeIf { it in HOST_ERROR_CODES } ?: "protocol_error"
                            val diagnostic = frame.optString("diagnostic").takeIf { it in HOST_DIAGNOSTICS } ?: "unknown"
                            throw IllegalStateException("Connect 宿主握手失败（$stage / $code / $diagnostic）")
                        }
                        else -> throw AriesProviderException("protocol_error")
                    }
                }
                if (!current.stopping) throw AriesProviderException("launcher_unavailable")
            }
        } catch (error: Throwable) {
            if (!current.stopping) {
                current.healthy = false
                failPending(current, error)
                current.bridge.close()
                ioScope.launch {
                    lifecycle.withLock {
                        if (live === current) {
                            stopLocked(ConnectPluginHostStatus.FAILED)
                            mutable.value = mutable.value.copy(reason = "Connect 运行通道已断开")
                        }
                    }
                }
            }
        }
    }

    private fun failPending(current: Live, error: Throwable) {
        current.hello.completeExceptionally(error)
        current.ready.completeExceptionally(error)
        current.pending.values.forEach { it.completeExceptionally(error) }
    }

    private fun prepareWorker(): File {
        val bytes = app.assets.open("connection-host/worker.mjs").use { ConnectionPackageValidator.readBounded(it, 128 * 1024) }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val file = File(root, "worker-$sha.mjs")
        if (file.exists()) check(file.isFile && file.readBytes().contentEquals(bytes)) { "Connect 宿主脚本校验失败" }
        else {
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try { output.write(bytes); atomic.finishWrite(output) } catch (error: Throwable) { atomic.failWrite(output); throw error }
        }
        return file
    }

    companion object {
        const val VERSION = "0.3.0"
        private const val PROCESS_ID = "connect-plugin-host"
        private val CONFIGURE_STAGES = setOf("identity", "package", "archive", "import", "framework", "provider")
        private val HOST_DIAGNOSTICS = setOf("bridge_credentials", "ipc_encoding", "ipc_identity", "ipc_error_envelope",
            "ipc_data", "connection_snapshot", "native_protocol", "unknown")
        private val HOST_ERROR_CODES = setOf("launcher_unavailable", "connection_unavailable", "pairing_incomplete", "presence_unknown",
            "remote_offline", "revoked", "permission_denied", "transport_unavailable", "tls_failure", "request_timeout",
            "request_cancelled", "validation_failed", "unsupported", "disk_space_low", "conflict", "protocol_error")
        private val hosts = ConcurrentHashMap<String, ConnectPluginHost>()
        fun get(context: Context): ConnectPluginHost = hosts.computeIfAbsent(context.applicationContext.noBackupFilesDir.canonicalPath) {
            ConnectPluginHost(context.applicationContext)
        }
        private val METHODS = setOf("get_connection_state", "send_encrypted_message", "list_incoming_messages", "acknowledge_message",
            "create_file_transfer", "upload_file_chunk", "complete_file_transfer", "list_incoming_file_transfers", "download_file_chunk",
            "acknowledge_file_transfer", "get_group_file_cache_stats", "cleanup_group_file_cache")
        internal fun readFrame(input: InputStream): JSONObject? = LocalPluginIpcPolicy.readRequest(input, LocalPluginIpcPolicy.MAX_RESPONSE_BYTES)
    }
}
