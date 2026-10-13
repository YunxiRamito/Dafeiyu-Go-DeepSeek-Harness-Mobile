package com.dafeiyu.mobile.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/** Page-owned bounded staging. URI grants and original filenames are never sent to the service. */
class FeedbackAttachmentStore(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val session = File(context.applicationContext.cacheDir, "feedback-attachments/${UUID.randomUUID()}")

    class Attachment internal constructor(
        val id: String, val name: String, val bytes: Int, val width: Int, val height: Int,
        internal val file: File,
    )

    suspend fun importImage(uri: Uri): Attachment = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "请使用系统照片选择器选择图片" }
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= DeveloperCenterClient.FEEDBACK_MAX_IMAGE_BYTES) { "每张图片最大 5 MiB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw IllegalArgumentException("无法读取已授权图片")
        val type = FeedbackImageRules.detectMediaType(bytes) ?: throw IllegalArgumentException("仅支持 JPG、PNG、WebP 静态图片")
        val id = UUID.randomUUID().toString()
        val name = "image-$id.${when (type) { "image/jpeg" -> "jpg"; "image/png" -> "png"; else -> "webp" }}"
        val checked = FeedbackImageRules.validateUpload(name, bytes)
        require(session.isDirectory || session.mkdirs()) { "无法准备图片缓存" }
        val file = File(session, name)
        try {
            coroutineContext.ensureActive()
            file.writeBytes(bytes)
            // Header dimensions alone do not establish that the bitmap is decodable.
            thumbnailFile(file, max(checked.width, checked.height)).recycle()
            coroutineContext.ensureActive()
            Attachment(id, name, bytes.size, checked.width, checked.height, file)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    suspend fun upload(attachments: List<Attachment>): List<DeveloperCenterClient.FeedbackUploadImage> = withContext(Dispatchers.IO) {
        require(attachments.size <= DeveloperCenterClient.FEEDBACK_MAX_IMAGES) { "最多添加 5 张图片" }
        attachments.map { attachment ->
            coroutineContext.ensureActive()
            requireOwned(attachment)
            require(attachment.file.length() == attachment.bytes.toLong()) { "图片缓存已改变，请重新选择" }
            DeveloperCenterClient.FeedbackUploadImage.from(attachment.name, attachment.file.readBytes())
        }
    }

    suspend fun thumbnail(attachment: Attachment): Bitmap = withContext(Dispatchers.IO) {
        requireOwned(attachment)
        thumbnailFile(attachment.file, max(attachment.width, attachment.height))
    }

    fun remove(attachment: Attachment) {
        requireOwned(attachment)
        attachment.file.delete()
    }

    fun clear() {
        // Never traverse a URI, a user path, or a directory outside this session.
        if (Files.isSymbolicLink(session.toPath())) return
        session.listFiles()?.forEach { file ->
            if (file.isFile && !Files.isSymbolicLink(file.toPath()) && file.canonicalFile.parentFile == session.canonicalFile) file.delete()
        }
        session.delete()
    }

    private fun requireOwned(attachment: Attachment) {
        require(!Files.isSymbolicLink(attachment.file.toPath()) && attachment.file.canonicalFile.parentFile == session.canonicalFile &&
            attachment.file.isFile && attachment.file.length() in 1..DeveloperCenterClient.FEEDBACK_MAX_IMAGE_BYTES.toLong()) {
            "图片缓存无效，请重新选择"
        }
    }

    private fun thumbnailFile(file: File, longestSide: Int): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        require(info.size.width in 1..10000 && info.size.height in 1..10000 && info.size.width.toLong() * info.size.height <= 20_000_000L) {
            "解码器返回的图片尺寸超出限制"
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetSampleSize((longestSide / 768).coerceAtLeast(1))
        decoder.setOnPartialImageListener { false }
    }
}
