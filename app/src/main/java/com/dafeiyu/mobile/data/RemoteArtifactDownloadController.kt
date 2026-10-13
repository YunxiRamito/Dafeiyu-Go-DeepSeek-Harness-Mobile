package com.dafeiyu.mobile.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject

/** User initiated downloads only; no polling, eager file copy or automatic public Downloads writes. */
class RemoteArtifactDownloadController internal constructor(
    private val root: File,
    private val call: RemoteArtifactCall,
) : AutoCloseable {
    constructor(context: Context, controller: RemoteDshApplicationController) : this(
        File(context.noBackupFilesDir, "remote-artifact-downloads"),
        RemoteArtifactCall { operation, payload, binding -> controller.artifactRequest(operation, payload, binding) },
    )
    data class State(val busy: Boolean = false, val fileName: String? = null, val receivedBytes: Long = 0,
        val totalBytes: Long? = null, val completed: File? = null, val sha256: String? = null,
        val binding: RemoteDshScope? = null, val detail: String? = null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable.asStateFlow()
    private var job: Job? = null
    @Volatile private var proxy: RemoteArtifactLoopback? = null
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    suspend fun list(binding: RemoteDshScope, workspaceId: String, directory: String = "", cursor: String? = null): RemoteArtifactListing {
        RemoteArtifactProtocol.path(directory, true)
        val payload = JSONObject().put("workspaceId", workspaceId).put("directory", directory).put("limit", 100)
        cursor?.let { payload.put("cursor", it) }
        return RemoteArtifactProtocol.listing(call.request("artifact.list", payload, binding), workspaceId, directory)
    }
    @Synchronized
    fun download(binding: RemoteDshScope, workspaceId: String, entry: RemoteArtifactEntry): Boolean {
        if (job?.isActive == true || entry.directory) return false
        mutable.value = State(busy = true, fileName = entry.name, totalBytes = entry.size, binding = binding)
        job = scope.launch {
            gate.withLock {
                var ticket: RemoteArtifactTicket? = null
                var local: RemoteArtifactLoopback? = null
                try {
                    ticket = RemoteArtifactProtocol.ticket(call.request("artifact.prepare", JSONObject()
                        .put("workspaceId", workspaceId).put("relativePath", entry.relativePath).put("version", entry.version), binding), workspaceId, entry)
                    val prepared = ticket
                    suspend fun validateScope() {
                        val result = call.request("artifact.read", JSONObject().put("ticket", prepared.ticket).put("offset", 0).put("length", 1), binding)
                        RemoteArtifactProtocol.bytes(result, prepared, 0, 1)
                    }
                    local = RemoteArtifactLoopback(binding, prepared, call) { validateScope() }
                    proxy = local
                    val resumeIdentity = hex(MessageDigest.getInstance("SHA-256").digest(
                        "${binding.storageKey}\u0000$workspaceId\u0000${entry.relativePath}\u0000${entry.version}\u0000${prepared.sha256}"
                            .toByteArray(Charsets.UTF_8)))
                    val destination = File(root, "$resumeIdentity.bin")
                    val result = ArtifactDownloader(root, { client }).download(ArtifactDownloadRequest(
                        urls = listOf(local.url), destination = destination, checksum = ArtifactChecksum("SHA-256", prepared.sha256),
                        expectedSize = prepared.size, maxBytes = RemoteArtifactProtocol.MAX_BYTES,
                        allowedLoopbackPort = local.port, loopbackResumeIdentity = resumeIdentity,
                    )) { progress ->
                        mutable.value = mutable.value.copy(receivedBytes = progress.receivedBytes, totalBytes = progress.totalBytes)
                    }
                    validateScope()
                    currentCoroutineContext().ensureActive()
                    mutable.value = mutable.value.copy(busy = false, receivedBytes = prepared.size, totalBytes = prepared.size,
                        completed = result.file, sha256 = prepared.sha256, detail = "已下载到手机，可以保存到文件")
                } catch (cancelled: CancellationException) {
                    mutable.value = mutable.value.copy(busy = false, completed = null, sha256 = null, detail = "下载已暂停；再次点击可继续")
                    throw cancelled
                } catch (failure: Exception) {
                    mutable.value = mutable.value.copy(busy = false, completed = null, sha256 = null,
                        detail = when ((failure as? AriesProviderException)?.code) {
                            "conflict" -> "文件或连接设备已变化，请刷新后重新下载"
                            "unsupported_method", "unsupported" -> "电脑尚未启用文件下载"
                            else -> "文件下载未完成，请检查连接后重试"
                        })
                } finally {
                    local?.close()
                    if (proxy === local) proxy = null
                    ticket?.let { prepared -> withContext(NonCancellable) {
                        withTimeoutOrNull(4_000) { runCatching { call.request("artifact.close", JSONObject().put("ticket", prepared.ticket), binding) } }
                    } }
                }
            }
        }
        return true
    }
    fun onRelationship(binding: RemoteDshScope?) {
        if (mutable.value.busy && mutable.value.binding != binding) cancel()
    }
    fun onPowerState(foreground: Boolean, powerSaving: Boolean) {
        if (!foreground && powerSaving) cancel()
    }
    fun cancel() { proxy?.close(); job?.cancel() }
    suspend fun export(context: Context, uri: Uri, expected: State): Boolean = withContext(Dispatchers.IO) {
        require(uri.scheme == "content")
        val file = requireNotNull(expected.completed)
        require(mutable.value.completed == file && mutable.value.sha256 == expected.sha256 && !mutable.value.busy)
        val canonicalRoot = root.canonicalFile
        require(file.canonicalFile == file.absoluteFile && file.parentFile?.canonicalFile == canonicalRoot &&
            !Files.isSymbolicLink(file.toPath()) && file.name.matches(Regex("[a-f0-9]{64}\\.bin")))
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        require(hex(digest.digest()) == expected.sha256 && file.length() == expected.totalBytes)
        require(mutable.value.completed == file && mutable.value.sha256 == expected.sha256)
        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { output -> file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count) }
        } }
        true
    }
    override fun close() { cancel(); scope.cancel(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
