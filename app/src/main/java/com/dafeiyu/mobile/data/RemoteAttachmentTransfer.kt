package com.dafeiyu.mobile.data

import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Uses the existing coordinator's single Aries event pump. This uploader
 * never polls, claims another cursor, or submits a prompt by itself. */
fun interface RemoteAttachmentRequest {
    suspend fun execute(operationKey: String, operation: String, payload: JSONObject): JSONObject
}

class RemoteAttachmentTransfer(
    private val binding: RemoteDshScope,
    private val request: RemoteAttachmentRequest,
    private val connectionState: suspend () -> JSONObject,
) {
    data class Progress(val uploadId: String, val receivedBytes: Long, val totalBytes: Long)
    data class Uploaded(val uploadId: String, val sessionId: String, val name: String, val size: Long,
        val sha256: String, val file: JSONObject) {
        fun content(): JSONObject = JSONObject().put("type", "file").put("uploadId", uploadId)
    }
    private data class Source(val canonical: File, val device: Long, val inode: Long, val size: Long, val modified: Long)

    /** operationKey is the persisted composer action; identical retries derive
     * the same upload id and cannot replace another selection's bytes. */
    suspend fun upload(operationKey: String, sessionId: String, selectedFile: File, displayName: String,
        progress: (Progress) -> Unit = {}): Uploaded = withContext(Dispatchers.IO) {
        require(operationKey.matches(IDENTIFIER) && sessionId.matches(IDENTIFIER))
        require(displayName.isNotBlank() && displayName.trim() == displayName && displayName.toByteArray().size <= 255 &&
            displayName.none { it.code < 32 || it.code == 127 || it == '/' || it == '\\' } && displayName !in setOf(".", ".."))
        current()
        val source = source(selectedFile)
        require(source.size in 0..MAX_BYTES) { "附件不得超过 512 MiB" }
        RandomAccessFile(source.canonical, "r").use { input ->
            val opened = Os.fstat(input.fd)
            require(opened.st_dev == source.device && opened.st_ino == source.inode && opened.st_size == source.size) {
                "选中的附件已变化，请重新选择"
            }
            val digest = hash(input, source)
            val identity = JSONObject().put("scope", binding.toJson()).put("operationKey", operationKey)
                .put("sessionId", sessionId).put("name", displayName).put("size", source.size).put("sha256", digest)
            val uploadId = "attach-" + remoteJsonHash(identity).take(56)
            val prefix = "attachment-$uploadId"
            try {
                current(); unchanged(source, input)
                val prepared = request.execute("$prefix-prepare", "attachment.prepare", JSONObject().put("uploadId", uploadId)
                    .put("sessionId", sessionId).put("name", displayName).put("size", source.size).put("sha256", digest))
                validate(prepared, uploadId, sessionId, source.size, digest)
                // A lost acknowledgement may leave an old prepare response in
                // the host journal. A fresh status reads the durable offset.
                val status = request.execute("$prefix-status-${UUID.randomUUID()}", "attachment.status",
                    JSONObject().put("uploadId", uploadId))
                validate(status, uploadId, sessionId, source.size, digest)
                var offset = status.getLong("received")
                require(offset in 0..source.size)
                progress(Progress(uploadId, offset, source.size))
                val buffer = ByteArray(CHUNK_BYTES)
                while (offset < source.size) {
                    currentCoroutineContext().ensureActive(); current(); unchanged(source, input)
                    val count = minOf(buffer.size.toLong(), source.size - offset).toInt()
                    input.seek(offset); input.readFully(buffer, 0, count)
                    val bytes = buffer.copyOf(count)
                    val response = request.execute("$prefix-write-$offset", "attachment.write", JSONObject().put("uploadId", uploadId)
                        .put("offset", offset).put("bytesBase64", Base64.getEncoder().encodeToString(bytes))
                        .put("chunkSha256", sha256(bytes)))
                    validate(response, uploadId, sessionId, source.size, digest)
                    val acknowledged = response.getLong("received")
                    require(acknowledged in (offset + count)..source.size) { "电脑返回了无效的附件上传进度" }
                    offset = acknowledged
                    progress(Progress(uploadId, offset, source.size))
                }
                current(); unchanged(source, input)
                require(hash(input, source) == digest) { "附件在上传期间发生变化，请重新选择" }
                val committed = request.execute("$prefix-commit", "attachment.commit", JSONObject().put("uploadId", uploadId))
                validate(committed, uploadId, sessionId, source.size, digest)
                require(committed.getString("status") == "ready" && committed.getLong("received") == source.size)
                val content = committed.getJSONObject("content")
                val file = committed.getJSONObject("file")
                require(content.length() == 2 && content.getString("type") == "file" && content.getString("uploadId") == uploadId)
                require(file.length() == 3 && file.getString("attachmentId") == "sha256:$digest" && file.getLong("bytes") == source.size)
                val actualName = file.getString("name")
                require(actualName.isNotEmpty() && actualName.toByteArray().size <= 255 &&
                    actualName.none { it.code < 32 || it.code == 127 || it == '/' || it == '\\' })
                current(); unchanged(source, input)
                Uploaded(uploadId, sessionId, actualName, source.size, digest, JSONObject(file.toString()))
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    // Revocation never creates a request under a new pairing.
                    runCatching { withTimeout(5_000) { cancel(uploadId) } }
                }
                throw cancelled
            }
        }
    }

    suspend fun cancel(uploadId: String): Unit = withContext(Dispatchers.IO) {
        require(uploadId.matches(IDENTIFIER)); current()
        val response = request.execute("attachment-$uploadId-cancel", "attachment.cancel", JSONObject().put("uploadId", uploadId))
        require(response.length() == 2 && response.getString("uploadId") == uploadId && response.getBoolean("cancelled"))
        current()
    }

    private suspend fun current() {
        currentCoroutineContext().ensureActive()
        val state = connectionState()
        binding.requireCurrent(state, requireOnline = true)
        if (state.optJSONObject("capabilities")?.optBoolean("remote_attachments") != true)
            throw AriesProviderException("unsupported_method")
    }
    private fun source(file: File): Source {
        val canonical = file.canonicalFile
        val info = Os.lstat(file.absolutePath)
        require(canonical == file.absoluteFile && canonical.isFile && OsConstants.S_ISREG(info.st_mode)) {
            "附件必须是已选择的普通文件"
        }
        return Source(canonical, info.st_dev, info.st_ino, info.st_size, canonical.lastModified())
    }
    private fun unchanged(source: Source, input: RandomAccessFile) {
        val now = Os.lstat(source.canonical.path); val opened = Os.fstat(input.fd)
        require(OsConstants.S_ISREG(now.st_mode) && source.canonical.canonicalFile == source.canonical &&
            now.st_dev == source.device && now.st_ino == source.inode && now.st_size == source.size &&
            opened.st_dev == source.device && opened.st_ino == source.inode && opened.st_size == source.size &&
            source.canonical.lastModified() == source.modified) { "选中的附件已变化，请重新选择" }
    }
    private suspend fun hash(input: RandomAccessFile, source: Source): String {
        input.seek(0)
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(CHUNK_BYTES)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(bytes)
            if (count < 0) break
            total += count; require(total <= source.size)
            digest.update(bytes, 0, count)
        }
        unchanged(source, input); require(total == source.size)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun validate(result: JSONObject, uploadId: String, sessionId: String, size: Long, digest: String) {
        require(result.getString("uploadId") == uploadId && result.getString("sessionId") == sessionId &&
            result.getLong("size") == size && result.getString("sha256") == digest && result.getInt("chunkBytes") == CHUNK_BYTES &&
            result.getString("status") in setOf("uploading", "ready") && result.getLong("received") in 0..size)
    }
    companion object {
        const val CHUNK_BYTES = 48 * 1024
        const val MAX_BYTES = 512L * 1024 * 1024
        private val IDENTIFIER = Regex("[A-Za-z0-9_-]{1,128}")
        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
