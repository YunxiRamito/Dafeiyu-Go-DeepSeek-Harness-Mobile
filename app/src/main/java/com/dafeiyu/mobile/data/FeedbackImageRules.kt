package com.dafeiyu.mobile.data

/** PC feedback format limits, checked before allowing an Android bitmap allocation. */
internal object FeedbackImageRules {
    data class CheckedImage(val name: String, val mediaType: String, val width: Int, val height: Int)

    fun detectMediaType(bytes: ByteArray): String? = when {
        bytes.size >= 3 && u(bytes, 0) == 255 && u(bytes, 1) == 216 && u(bytes, 2) == 255 -> "image/jpeg"
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) -> "image/png"
        bytes.size >= 12 && ascii(bytes, 0) == "RIFF" && ascii(bytes, 8) == "WEBP" -> "image/webp"
        else -> null
    }

    fun validateUpload(fileName: String, bytes: ByteArray): CheckedImage {
        val name = fileName.replace('\\', '/').substringAfterLast('/')
        require(name.isNotBlank() && name.length <= 200 && name.none { it.isISOControl() || it == '"' }) { "图片文件名无效" }
        require(bytes.size in 1..DeveloperCenterClient.FEEDBACK_MAX_IMAGE_BYTES) { "每张图片最大 5 MiB" }
        val type = detectMediaType(bytes) ?: throw IllegalArgumentException("请选择 JPG、PNG 或 WebP 静态图片")
        val extension = name.substringAfterLast('.', "").lowercase()
        require(when (type) {
            "image/jpeg" -> extension in setOf("jpg", "jpeg")
            "image/png" -> extension == "png"
            else -> extension == "webp"
        }) { "图片扩展名与内容不一致" }
        val (width, height) = when (type) {
            "image/png" -> pngDimensions(bytes)
            "image/jpeg" -> jpegDimensions(bytes)
            else -> webpDimensions(bytes)
        }
        require(width in 1..10000 && height in 1..10000 && width.toLong() * height <= 20_000_000L) {
            "图片最多 2000 万像素，单边不超过 10000 像素"
        }
        return CheckedImage(name, type, width, height)
    }

    private fun pngDimensions(bytes: ByteArray): Pair<Int, Int> {
        require(bytes.size >= 33 && ascii(bytes, 12) == "IHDR") { "PNG 图片头无效" }
        var offset = 8
        while (offset + 12 <= bytes.size) {
            val length = uint32(bytes, offset, false)
            require(length <= bytes.size.toLong() - offset - 12) { "PNG 内容被截断" }
            require(ascii(bytes, offset + 4) != "acTL") { "请选择静态图片，不支持动图" }
            offset += length.toInt() + 12
            if (ascii(bytes, offset - length.toInt() - 8) == "IEND") break
        }
        return uint32(bytes, 16, false).toInt() to uint32(bytes, 20, false).toInt()
    }

    private fun jpegDimensions(bytes: ByteArray): Pair<Int, Int> {
        var offset = 2
        while (offset + 3 < bytes.size) {
            require(u(bytes, offset++) == 255) { "JPEG 标记无效" }
            while (offset < bytes.size && u(bytes, offset) == 255) offset++
            if (offset >= bytes.size) break
            val marker = u(bytes, offset++)
            if (marker == 217 || marker == 218) break
            if (marker == 1 || marker in 208..215) continue
            if (offset + 2 > bytes.size) break
            val length = uint16(bytes, offset, false)
            require(length >= 2 && length <= bytes.size - offset) { "JPEG 内容被截断" }
            if (marker in 192..207 && marker !in setOf(196, 200, 204) && length >= 7) {
                return uint16(bytes, offset + 5, false) to uint16(bytes, offset + 3, false)
            }
            offset += length
        }
        throw IllegalArgumentException("无法读取 JPEG 图片尺寸")
    }

    private fun webpDimensions(bytes: ByteArray): Pair<Int, Int> {
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val chunk = ascii(bytes, offset)
            val length = uint32(bytes, offset + 4, true)
            val data = offset + 8
            require(length <= bytes.size.toLong() - data) { "WebP 内容被截断" }
            if (chunk == "VP8X" && length >= 10) {
                require(u(bytes, data) and 2 == 0) { "请选择静态图片，不支持动图" }
                return (uint24(bytes, data + 4) + 1) to (uint24(bytes, data + 7) + 1)
            }
            if (chunk == "VP8L" && length >= 5 && u(bytes, data) == 47) {
                val bits = uint32(bytes, data + 1, true)
                return ((bits and 0x3fff).toInt() + 1) to (((bits shr 14) and 0x3fff).toInt() + 1)
            }
            if (chunk == "VP8 " && length >= 10 && u(bytes, data + 3) == 157 && u(bytes, data + 4) == 1 && u(bytes, data + 5) == 42) {
                return (uint16(bytes, data + 6, true) and 0x3fff) to (uint16(bytes, data + 8, true) and 0x3fff)
            }
            offset = data + length.toInt() + (length % 2).toInt()
        }
        throw IllegalArgumentException("无法读取 WebP 图片尺寸")
    }

    private fun u(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and 255
    private fun ascii(bytes: ByteArray, offset: Int): String = String(bytes, offset, 4, Charsets.US_ASCII)
    private fun uint24(bytes: ByteArray, offset: Int): Int = u(bytes, offset) or (u(bytes, offset + 1) shl 8) or (u(bytes, offset + 2) shl 16)
    private fun uint16(bytes: ByteArray, offset: Int, little: Boolean): Int = if (little) u(bytes, offset) or (u(bytes, offset + 1) shl 8)
        else (u(bytes, offset) shl 8) or u(bytes, offset + 1)
    private fun uint32(bytes: ByteArray, offset: Int, little: Boolean): Long = (0..3).fold(0L) { acc, index ->
        acc or (u(bytes, offset + index).toLong() shl (8 * if (little) index else 3 - index))
    }
}
