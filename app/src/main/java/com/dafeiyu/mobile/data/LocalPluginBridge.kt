package com.dafeiyu.mobile.data

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * Private app bridge. It uses an abstract Unix socket, checks peer UID, and
 * requires a fresh capability plus request nonce for every call.
 */
class LocalPluginBridge(context: Context) : AutoCloseable {
    private val provider = AriesConnectionProvider(context.applicationContext)
    private val socketName = "dafeiyu-plugin-" + UUID.randomUUID().toString().replace("-", "")
    private val capability = AriesCrypto.encode(ByteArray(32).also(SecureRandom()::nextBytes))
    private val generation = UUID.randomUUID().toString()
    private val session = LocalPluginIpcPolicy.Session(Process.myUid(), Process.myPid(), generation, capability)
    private val nativeAdmissions = LocalPluginIpcPolicy.NativeAdmissions()
    private val scopedAdmissions = LocalPluginIpcPolicy.NativeScopedAdmissions()
    private val server = LocalServerSocket(socketName)
    private val acceptor = Executors.newSingleThreadExecutor()
    private val clients = ThreadPoolExecutor(
        LocalPluginIpcPolicy.MAX_CLIENTS, LocalPluginIpcPolicy.MAX_CLIENTS, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(LocalPluginIpcPolicy.MAX_CLIENTS),
    )
    private val deadlines = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private val sockets = Collections.synchronizedSet(mutableSetOf<LocalSocket>())
    private val lifecycle = Any()
    private var started = false
    @Volatile private var closed = false
    private data class MappedExecutable(
        val path: String, val device: Long, val inode: Long, val size: Long,
        val modifiedSeconds: Long, val modifiedNanos: Long, val mappings: List<String>,
    )
    private data class ProcessStamp(
        val identity: LocalPluginIpcPolicy.RuntimeIdentity, val startTicks: Long,
        val executable: String, val tracerPid: Int, val mappedExecutable: MappedExecutable? = null,
    )
    private var boundRuntime: ProcessStamp? = null
    private var boundLauncher: ProcessStamp? = null

    fun start() = synchronized(lifecycle) {
        check(!closed) { "bridge_closed" }
        if (started) return@synchronized
        started = true
        acceptor.execute {
            while (!closed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                synchronized(lifecycle) {
                    val peer = runCatching { socket.peerCredentials }.getOrNull()
                    val permit = if (!closed && peer != null && currentRuntime(peer.uid, peer.pid))
                        session.acquirePeer(peer.uid, peer.pid) else null
                    if (permit == null) {
                        runCatching { socket.close() }
                    } else {
                        sockets.add(socket)
                        val expiresAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LocalPluginIpcPolicy.DEADLINE_MILLIS)
                        val deadline = deadlines.schedule(
                            { runCatching { socket.close() } }, LocalPluginIpcPolicy.DEADLINE_MILLIS, TimeUnit.MILLISECONDS,
                        )
                        try {
                            clients.execute {
                                try {
                                    socket.use { if (!closed) handle(it, expiresAt) }
                                } catch (_: Exception) {
                                    // Local transport failures do not expose credentials or provider errors.
                                } finally {
                                    deadline.cancel(false)
                                    sockets.remove(socket)
                                    permit.close()
                                }
                            }
                        } catch (_: java.util.concurrent.RejectedExecutionException) {
                            deadline.cancel(false)
                            sockets.remove(socket)
                            permit.close()
                            runCatching { socket.close() }
                        }
                    }
                }
            }
        }
    }
    fun name(): String = socketName
    fun capabilityForProcess(): String = capability
    fun generationForProcess(): String = generation

    /** Called by the application host before writing its frozen request to Node.
     * Neither admission tokens nor the retirement API are exposed through IPC. */
    internal fun registerNativeBulk(method: String, params: JSONObject, scope: RemoteDshScope): String? = synchronized(lifecycle) {
        val runtime = boundRuntime ?: throw AriesProviderException("launcher_unavailable")
        check(currentRuntime(runtime.identity.uid, runtime.identity.pid)) { "launcher_unavailable" }
        nativeAdmissions.register(method, params, scope.toJson())
    }

    internal fun revokeNativeBulk(token: String?) = nativeAdmissions.revoke(token)

    internal fun registerNativeScoped(method: String, params: JSONObject, scope: RemoteDshScope, capability: String,
        acknowledgeMessageId: String?): String = synchronized(lifecycle) {
        val runtime = boundRuntime ?: throw AriesProviderException("launcher_unavailable")
        check(currentRuntime(runtime.identity.uid, runtime.identity.pid)) { "launcher_unavailable" }
        scopedAdmissions.register(method, params, scope.toJson(), capability, acknowledgeMessageId)
    }

    internal fun revokeNativeScoped(token: String?) = scopedAdmissions.revoke(token)

    internal fun setProviderDiagnosticObserver(observer: ((AriesConnectionProvider.Diagnostic) -> Unit)?) {
        provider.setDiagnosticObserver(observer)
    }

    internal suspend fun retireTransferReceipt(scope: RemoteDshScope, requestId: String, operation: String): JSONObject {
        synchronized(lifecycle) {
            val runtime = boundRuntime ?: throw AriesProviderException("launcher_unavailable")
            if (!currentRuntime(runtime.identity.uid, runtime.identity.pid)) throw AriesProviderException("launcher_unavailable")
        }
        val receipt = provider.retireTransferReceipt(scope, requestId, operation)
        synchronized(lifecycle) { if (closed) throw AriesProviderException("launcher_unavailable") }
        return receipt
    }
    fun bindRuntime(pid: Int, launcherPid: Int? = null, expectedExecutable: File? = null) = synchronized(lifecycle) {
        check(!closed) { "bridge_closed" }
        require(pid > 0) { "invalid_runtime_identity" }
        val runtime = processStamp(pid, expectedExecutable)
        val launcher = launcherPid?.takeIf { runtime.identity.parentPid != Process.myPid() }?.let(::processStamp)
        launcher?.let {
            require(it.executable == File(providerNativeDirectory, "liblinux_proot.so").canonicalPath) {
                "invalid_runtime_launcher"
            }
        }
        expectedExecutable?.let { expected ->
            if (runtime.executable != expected.canonicalPath) require(
                launcher != null && runtime.executable == File(providerNativeDirectory, "liblinux_prootloader.so").canonicalPath &&
                    runtime.tracerPid == launcher.identity.pid && runtime.mappedExecutable != null,
            ) { "invalid_runtime_executable" }
        }
        session.bindRuntime(runtime.identity, launcher?.identity)
        boundRuntime = runtime
        boundLauncher = launcher
    }

    private val providerNativeDirectory = context.applicationContext.applicationInfo.nativeLibraryDir

    private fun processStamp(pid: Int, expectedExecutable: File? = null): ProcessStamp {
        fun read(name: String, limit: Int = 64 * 1024): String = File("/proc/$pid/$name").inputStream().use { input ->
            val bytes = ByteArray(limit + 1)
            var size = 0
            while (size < bytes.size) {
                val count = input.read(bytes, size, bytes.size - size)
                if (count < 0) break
                size += count
            }
            require(size <= limit) { "invalid_runtime_identity" }
            String(bytes, 0, size, Charsets.UTF_8)
        }
        val status = read("status")
        val identity = LocalPluginIpcPolicy.runtimeIdentity(status)
        require(identity.pid == pid) { "invalid_runtime_identity" }
        val stat = read("stat")
        val endName = stat.lastIndexOf(')')
        require(endName >= 0 && stat.substringBefore(' ').toIntOrNull() == pid) { "invalid_runtime_identity" }
        val values = stat.substring(endName + 1).trim().split(Regex("\\s+"))
        val startTicks = values.getOrNull(19)?.toLongOrNull()?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("invalid_runtime_identity")
        val executable = Files.readSymbolicLink(File("/proc/$pid/exe").toPath()).toFile().canonicalPath
        val mapped = expectedExecutable?.takeIf {
            executable == File(providerNativeDirectory, "liblinux_prootloader.so").canonicalPath
        }?.let { expected ->
            val path = expected.canonicalPath
            val info = Os.stat(path)
            require(OsConstants.S_ISREG(info.st_mode) && info.st_uid == Process.myUid() && info.st_size > 0 &&
                !Files.isSymbolicLink(expected.toPath())) { "invalid_runtime_executable" }
            MappedExecutable(path, info.st_dev, info.st_ino, info.st_size, info.st_mtim.tv_sec, info.st_mtim.tv_nsec,
                LocalPluginIpcPolicy.mappedExecutableEvidence(read("maps", 1024 * 1024), path, info.st_dev, info.st_ino))
        }
        return ProcessStamp(identity, startTicks, executable, LocalPluginIpcPolicy.runtimeTracerPid(status), mapped)
    }

    private fun currentRuntime(uid: Int, pid: Int): Boolean = synchronized(lifecycle) {
        val runtime = boundRuntime ?: return@synchronized false
        !closed && session.acceptsPeer(uid, pid) && runCatching {
            processStamp(pid, runtime.mappedExecutable?.let { File(it.path) }) == runtime &&
                (boundLauncher?.let { processStamp(it.identity.pid) == it } ?: true)
        }.getOrDefault(false)
    }

    private fun handle(socket: LocalSocket, expiresAt: Long) {
        socket.soTimeout = LocalPluginIpcPolicy.DEADLINE_MILLIS.toInt()
        var requestId = ""
        var method = "unvalidated"
        val response = try {
            val envelope = LocalPluginIpcPolicy.readRequest(BufferedInputStream(socket.inputStream)) ?: return
            val request = session.validate(envelope)
            requestId = request.id
            method = request.method
            val peer = socket.peerCredentials
            if (!currentRuntime(peer.uid, peer.pid)) throw LocalPluginIpcPolicy.Failure("permission_denied", requestId)
            val admittedScope = nativeAdmissions.consume(request.method, request.params, request.remoteScope)
                ?.let(RemoteDshScope::fromJson)
            val scopedAdmission = scopedAdmissions.consume(request.method, request.params, request.remoteScope)
            val remainingMillis = TimeUnit.NANOSECONDS.toMillis(expiresAt - System.nanoTime())
            if (remainingMillis <= 0) throw LocalPluginIpcPolicy.Failure("request_timeout", requestId)
            val data = runBlocking {
                withTimeout(remainingMillis) {
                    if (admittedScope != null && scopedAdmission != null) throw AriesProviderException("conflict")
                    if (scopedAdmission != null) provider.callNativeScoped(request.method, request.params,
                        RemoteDshScope.fromJson(scopedAdmission.scope), scopedAdmission.capability, scopedAdmission.acknowledgeMessageId)
                    else if (admittedScope != null) provider.callNativeBulk(request.params, admittedScope)
                    else provider.call(request.method, request.params, expectedRemoteScope = request.remoteScope?.let(RemoteDshScope::fromJson))
                }
            }
            if (!currentRuntime(peer.uid, peer.pid)) throw LocalPluginIpcPolicy.Failure("permission_denied", requestId)
            JSONObject().put("success", true).put("request_id", requestId).put("data", data)
        } catch (failure: LocalPluginIpcPolicy.Failure) {
            provider.diagnose("ipc.$method", failure)
            requestId = failure.requestId
            LocalPluginIpcPolicy.error(requestId, failure.code)
        } catch (failure: Exception) {
            provider.diagnose("ipc.$method", failure)
            val code = when (failure) {
                is AriesProviderException -> failure.code
                is javax.net.ssl.SSLException -> "tls_failure"
                is java.net.SocketTimeoutException, is TimeoutCancellationException -> "request_timeout"
                is AriesClient.AriesException -> when (failure.errorCode) {
                    "membership_required" -> "revoked"
                    "rotation_required", "idempotency_conflict", "generation_mismatch" -> "conflict"
                    else -> if (failure.httpStatus == 403) "permission_denied" else "connection_unavailable"
                }
                is IOException -> "connection_unavailable"
                else -> "protocol_error"
            }
            LocalPluginIpcPolicy.error(requestId, code)
        }
        if (!closed) {
            socket.outputStream.write(LocalPluginIpcPolicy.responseBytes(response, requestId))
            socket.outputStream.flush()
        }
    }

    override fun close() = synchronized(lifecycle) {
        if (closed) return@synchronized
        closed = true
        provider.setDiagnosticObserver(null)
        session.close()
        nativeAdmissions.close()
        scopedAdmissions.close()
        boundRuntime = null
        boundLauncher = null
        runCatching { server.close() }
        synchronized(sockets) { sockets.toList().forEach { runCatching { it.close() } }; sockets.clear() }
        acceptor.shutdownNow()
        clients.shutdownNow()
        deadlines.shutdownNow()
        Unit
    }

    companion object { const val PROTOCOL = LocalPluginIpcPolicy.PROTOCOL }
}
