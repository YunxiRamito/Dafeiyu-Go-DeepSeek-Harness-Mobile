package com.dafeiyu.mobile.data

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
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

    fun start() = synchronized(lifecycle) {
        check(!closed) { "bridge_closed" }
        if (started) return@synchronized
        started = true
        acceptor.execute {
            while (!closed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                synchronized(lifecycle) {
                    val peer = runCatching { socket.peerCredentials }.getOrNull()
                    val permit = if (!closed && peer != null) session.acquirePeer(peer.uid, peer.pid) else null
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
    fun bindRuntime(pid: Int) = synchronized(lifecycle) {
        check(!closed) { "bridge_closed" }
        require(pid > 0) { "invalid_runtime_identity" }
        val status = File("/proc/$pid/status").inputStream().use { input ->
            val bytes = ByteArray(64 * 1024 + 1)
            var size = 0
            while (size < bytes.size) {
                val count = input.read(bytes, size, bytes.size - size)
                if (count < 0) break
                size += count
            }
            require(size <= 64 * 1024) { "invalid_runtime_identity" }
            String(bytes, 0, size, Charsets.UTF_8)
        }
        val identity = LocalPluginIpcPolicy.runtimeIdentity(status)
        require(identity.pid == pid) { "invalid_runtime_identity" }
        session.bindRuntime(identity)
    }

    private fun handle(socket: LocalSocket, expiresAt: Long) {
        socket.soTimeout = LocalPluginIpcPolicy.DEADLINE_MILLIS.toInt()
        var requestId = ""
        val response = try {
            val envelope = LocalPluginIpcPolicy.readRequest(BufferedInputStream(socket.inputStream)) ?: return
            val request = session.validate(envelope)
            requestId = request.id
            val peer = socket.peerCredentials
            if (!session.acceptsPeer(peer.uid, peer.pid)) throw LocalPluginIpcPolicy.Failure("permission_denied", requestId)
            val remainingMillis = TimeUnit.NANOSECONDS.toMillis(expiresAt - System.nanoTime())
            if (remainingMillis <= 0) throw LocalPluginIpcPolicy.Failure("request_timeout", requestId)
            val data = runBlocking {
                withTimeout(remainingMillis) {
                    provider.call(request.method, request.params)
                }
            }
            JSONObject().put("success", true).put("request_id", requestId).put("data", data)
        } catch (failure: LocalPluginIpcPolicy.Failure) {
            requestId = failure.requestId
            LocalPluginIpcPolicy.error(requestId, failure.code)
        } catch (failure: Exception) {
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
        session.close()
        runCatching { server.close() }
        synchronized(sockets) { sockets.toList().forEach { runCatching { it.close() } }; sockets.clear() }
        acceptor.shutdownNow()
        clients.shutdownNow()
        deadlines.shutdownNow()
        Unit
    }

    companion object { const val PROTOCOL = LocalPluginIpcPolicy.PROTOCOL }
}
