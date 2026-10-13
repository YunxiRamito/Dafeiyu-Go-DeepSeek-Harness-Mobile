package com.dafeiyu.mobile.glasslab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class LabPhotoBackground(val data: String, val width: Int, val height: Int)

/** Bounded, offline image backgrounds. Only the selected image's JPEG is retained, never its URI. */
internal object LabBackgroundImages {
    const val BUILTIN_PATH = "preview-backgrounds/reference.png"
    const val MAX_IMAGE_BYTES = 114688
    const val MAX_DATA_CHARACTERS = 152920
    private const val MAX_EDGE = 1280
    private const val MIN_EDGE = 64
    private val JPEG_QUALITIES = intArrayOf(90, 80, 70, 60, 50, 40)

    fun hasReference(context: Context): Boolean = try {
        context.assets.open(BUILTIN_PATH).use { }
        true
    } catch (_: Exception) {
        false
    }

    suspend fun importPhoto(context: Context, uri: Uri): LabPhotoBackground = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        var decoded: Bitmap? = null
        try {
            val bitmap = decodeLimited(ImageDecoder.createSource(context.contentResolver, uri))
            decoded = bitmap
            currentCoroutineContext().ensureActive()
            encodePhoto(bitmap)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw IOException("无法导入这张图片，请选择可读取的完整图片", error)
        } finally {
            decoded?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    suspend fun load(context: Context, source: String, data: String): ImageBitmap? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        var decoded: Bitmap? = null
        try {
            val decoderSource = when (source) {
                "none" -> return@withContext null
                "reference" -> ImageDecoder.createSource(context.assets, BUILTIN_PATH)
                "photo" -> {
                    if (data.isEmpty() || data.length > MAX_DATA_CHARACTERS || data.length % 4 != 0) {
                        return@withContext null
                    }
                    if (data.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it != '+' && it != '/' && it != '=' }) {
                        return@withContext null
                    }
                    val bytes = Base64.decode(data, Base64.NO_WRAP)
                    if (bytes.size > MAX_IMAGE_BYTES || bytes.size < 3 ||
                        (bytes[0].toInt() and 255) != 255 || (bytes[1].toInt() and 255) != 216 || (bytes[2].toInt() and 255) != 255) {
                        return@withContext null
                    }
                    ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                }
                else -> return@withContext null
            }
            val bitmap = decodeLimited(decoderSource)
            decoded = bitmap
            currentCoroutineContext().ensureActive()
            // asImageBitmap retains this software bitmap. Do not recycle it after transfer.
            bitmap.asImageBitmap()
        } catch (cancelled: CancellationException) {
            decoded?.let { if (!it.isRecycled) it.recycle() }
            throw cancelled
        } catch (_: Exception) {
            decoded?.let { if (!it.isRecycled) it.recycle() }
            null
        }
    }

    /** Header-time sizing avoids allocating the original image's full-resolution pixel buffer. */
    private fun decodeLimited(source: ImageDecoder.Source): Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val width = info.size.width
        val height = info.size.height
        if (width <= 0 || height <= 0) throw IOException("图片尺寸无效")
        val factor = min(1.0, MAX_EDGE.toDouble() / max(width, height).toDouble())
        decoder.setTargetSize(max(1, (width * factor).roundToInt()), max(1, (height * factor).roundToInt()))
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        decoder.setOnPartialImageListener { false }
        // ImageDecoder applies encoded orientation; manual rotation would apply it twice.
    }

    private suspend fun encodePhoto(decoded: Bitmap): LabPhotoBackground {
        // JPEG has no alpha. Composite selected transparent images against white deliberately.
        var working = if (decoded.hasAlpha()) Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply { drawColor(Color.WHITE); drawBitmap(decoded, 0f, 0f, null) }
        } else decoded
        try {
            while (true) {
                for (quality in JPEG_QUALITIES) {
                    currentCoroutineContext().ensureActive()
                    val output = CappedJpegOutput()
                    if (!working.compress(Bitmap.CompressFormat.JPEG, quality, output)) throw IOException("图片编码失败")
                    currentCoroutineContext().ensureActive()
                    if (!output.overflow) {
                        val data = Base64.encodeToString(output.bytes(), Base64.NO_WRAP)
                        if (data.length > MAX_DATA_CHARACTERS) throw IOException("图片数据超过保存上限")
                        return LabPhotoBackground(data, working.width, working.height)
                    }
                }
                val largest = max(working.width, working.height)
                if (largest <= MIN_EDGE) throw IOException("图片压缩后仍超过保存上限")
                val nextLargest = max(MIN_EDGE, (largest * .75f).roundToInt()).coerceAtMost(largest - 1)
                val factor = nextLargest.toDouble() / largest.toDouble()
                currentCoroutineContext().ensureActive()
                val resized = Bitmap.createScaledBitmap(working, max(1, (working.width * factor).roundToInt()),
                    max(1, (working.height * factor).roundToInt()), true)
                val previous = working
                working = resized
                if (previous !== decoded && previous !== resized) previous.recycle()
            }
        } finally {
            if (working !== decoded && !working.isRecycled) working.recycle()
        }
    }

    /** Discard overflow during JPEG attempts instead of allocating arbitrary-size encoded data. */
    private class CappedJpegOutput : OutputStream() {
        private val buffer = ByteArrayOutputStream(MAX_IMAGE_BYTES)
        var overflow = false
            private set

        override fun write(value: Int) {
            if (buffer.size() < MAX_IMAGE_BYTES) buffer.write(value) else overflow = true
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (offset < 0 || length < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
            val available = MAX_IMAGE_BYTES - buffer.size()
            val accepted = min(available, length)
            if (accepted > 0) buffer.write(bytes, offset, accepted)
            if (accepted < length) overflow = true
        }

        fun bytes(): ByteArray = buffer.toByteArray()
    }
}
