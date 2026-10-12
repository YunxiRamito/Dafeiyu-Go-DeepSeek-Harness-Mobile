package com.dafeiyu.mobile.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.util.Locale
import java.util.Base64
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONObject

/**
 * Owns noBackupFilesDir/conversation-attachments only. Imports copy SAF content;
 * they never modify, delete, execute, or retain permissions to the original.
 * Call IO methods on Dispatchers.IO. Failed reads throw; load returns null only for a missing ID.
 */
class ConversationAttachmentStore(context: Context) {
    private val appContext = context.applicationContext
    private val base = appContext.noBackupFilesDir.canonicalFile
    private val root = File(base, "conversation-attachments")

    companion object {
        const val MAX_ATTACHMENT_BYTES = 10L * 1024L * 1024L
        /** Composer selection limit. The caller checks its selected IDs against this value. */
        const val MAX_TOTAL_BYTES = 25L * 1024L * 1024L
        const val MAX_STORE_BYTES = 128L * 1024L * 1024L
        const val MAX_STORE_ATTACHMENTS = 512
        const val MAX_TEXT_BYTES = 256L * 1024L
        const val MAX_TOTAL_TEXT_BYTES = 512L * 1024L
        /** Local preparation limits; the caller must also bound the complete JSON request. */
        const val MAX_TOTAL_IMAGE_BYTES = MAX_TOTAL_BYTES
        const val MAX_IMAGE_DATA_URL_BYTES = 36L * 1024L * 1024L
        const val MAX_PREPARED_IMAGES = 6
        private const val MAX_METADATA_BYTES = 16L * 1024L
        private val lock = ReentrantLock()
        private val idPattern = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private val mimePattern = Regex("[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*")
        private val textApplications = setOf(
            "application/json", "application/ld+json", "application/xml",
            "application/yaml", "application/x-yaml", "application/toml",
            "application/javascript", "application/ecmascript", "application/x-javascript",
            "application/sql",
        )
        private val opaqueTypes = setOf(
            "application/octet-stream", "application/pdf", "application/zip",
            "application/gzip", "application/x-gzip", "application/x-7z-compressed",
            "application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.oasis.opendocument.text",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.oasis.opendocument.presentation",
        )
        private val imageTypes = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
        private val codeExtensions = setOf(
            "kt", "kts", "java", "py", "pyi", "js", "jsx", "mjs", "cjs", "ts", "tsx",
            "c", "h", "cc", "cpp", "cxx", "hpp", "cs", "go", "rs", "swift", "m", "mm",
            "rb", "php", "lua", "r", "dart", "scala", "sc", "sh", "bash", "zsh",
            "ps1", "psm1", "psd1", "sql", "vue", "svelte", "css", "scss", "sass", "less",
        )
    }

    fun importAttachment(uri: Uri): ConversationAttachment = lock.withLock {
        require(uri.scheme == "content" && !uri.authority.isNullOrBlank()) {
            "Only SAF content URIs can be imported"
        }
        checkedRoot()
        val resolver = appContext.contentResolver
        var sourceName: String? = null
        var declaredSize: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) sourceName = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        declaredSize = cursor.getLong(sizeIndex).takeIf { it >= 0L }
                    }
                }
            }
        val name = safeName(sourceName)
        val sourceMime = resolver.getType(uri)
        val mime = normalizeMime(sourceMime)
        requireSupportedMime(mime)
        require(declaredSize == null || declaredSize!! <= MAX_ATTACHMENT_BYTES) {
            "Attachment exceeds 10 MiB"
        }
        val remaining = MAX_STORE_BYTES - totalPayloadBytes()
        require(remaining >= 0L && (declaredSize == null || declaredSize!! <= remaining)) {
            "Attachment storage exceeds 128 MiB"
        }
        val id = UUID.randomUUID().toString()
        val pending = checkedChild(root, ".pending-$id")
        check(pending.mkdir()) { "Cannot create attachment staging directory" }
        var committed = false
        try {
            val payload = checkedChild(pending, "content")
            var copied = 0L
            val input = resolver.openInputStream(uri) ?: throw IOException("Cannot open selected attachment")
            input.use { source ->
                FileOutputStream(payload).use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        copied += count
                        require(copied <= MAX_ATTACHMENT_BYTES) { "Attachment exceeds 10 MiB" }
                        require(copied <= remaining) { "Attachment storage exceeds 128 MiB" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            val evidence = when {
                isTextMime(mime) -> {
                    validateTextFile(payload)
                    "mime-utf8"
                }
                sourceMime != null && isCodeCandidate(name, mime) && copied <= MAX_TEXT_BYTES &&
                    isValidSmallText(payload) -> "code-extension-utf8"
                else -> null
            }
            val text = evidence != null
            if (mime in imageTypes) requireImageSignature(payload, mime)
            val target = directory(id)
            check(!target.exists()) { "Attachment ID already exists" }
            val attachment = ConversationAttachment(
                id, name, mime, copied, File(target, "content").absolutePath, text, mime in imageTypes, evidence,
            )
            val metadata = JSONObject()
                .put("version", 1).put("id", id).put("name", name).put("mimeType", mime)
                .put("sizeBytes", copied).put("textSupported", text).put("isImage", attachment.isImage)
                .put("textEvidence", evidence ?: JSONObject.NULL)
            FileOutputStream(checkedChild(pending, "metadata.json")).use { output ->
                output.write(metadata.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            check(pending.renameTo(target)) { "Cannot commit attachment" }
            committed = true
            attachment
        } finally {
            if (!committed) removeOwnedDirectory(pending)
        }
    }

    fun load(id: String): ConversationAttachment? = lock.withLock { loadLocked(id) }

    fun resolveFile(id: String): File = lock.withLock {
        val attachment = loadLocked(id) ?: throw IOException("Attachment not found")
        checkedChild(directory(attachment.id), "content")
    }

    fun readText(id: String): String = readTexts(listOf(id)).getValue(id)

    /**
     * Use this method once for the complete request's attachment IDs. Repeated
     * readText calls alone cannot enforce a request's aggregate 512 KiB limit.
     * Duplicate IDs are read once. Images and opaque files are never injected.
     */
    fun readTexts(ids: Collection<String>): Map<String, String> = lock.withLock {
        val attachments = ids.distinct().map { id ->
            loadLocked(id) ?: throw IOException("Attachment not found: $id")
        }
        var total = 0L
        attachments.forEach { attachment ->
            require(attachment.textSupported && !attachment.isImage) { "Attachment is not supported text" }
            require(attachment.sizeBytes <= MAX_TEXT_BYTES) { "Text attachment exceeds 256 KiB" }
            total += attachment.sizeBytes
            require(total <= MAX_TOTAL_TEXT_BYTES) { "Request attachment text exceeds 512 KiB" }
        }
        val result = linkedMapOf<String, String>()
        attachments.forEach { attachment ->
            val file = checkedChild(directory(attachment.id), "content")
            val bytes = readBounded(file, MAX_TEXT_BYTES)
            check(bytes.size.toLong() == attachment.sizeBytes) { "Attachment changed during read" }
            result[attachment.id] = decodeText(bytes)
        }
        result
    }

    fun readImageDataUrl(id: String): String = readImageDataUrls(listOf(id)).getValue(id)

    /**
     * Prepares local copies only. Call once for all images in the complete request
     * on IO, after checking model capability. No credential, upload or network use.
     * Signature checking identifies JPEG/PNG/GIF/WebP; it does not decode pixels.
     */
    fun readImageDataUrls(ids: Collection<String>): Map<String, String> = lock.withLock {
        val uniqueIds = ids.distinct()
        require(uniqueIds.size <= MAX_PREPARED_IMAGES) { "At most 6 images may be prepared per request" }
        val attachments = uniqueIds.map { id ->
            loadLocked(id) ?: throw IOException("Attachment not found: $id")
        }
        var rawTotal = 0L
        var encodedTotal = 0L
        attachments.forEach { attachment ->
            require(attachment.isImage && attachment.mimeType in imageTypes && !attachment.textSupported) {
                "Attachment is not a supported image"
            }
            require(attachment.sizeBytes in 1..MAX_ATTACHMENT_BYTES) { "Image exceeds 10 MiB" }
            rawTotal += attachment.sizeBytes
            encodedTotal += 4L * ((attachment.sizeBytes + 2L) / 3L) +
                "data:${attachment.mimeType};base64,".length
            require(rawTotal <= MAX_TOTAL_IMAGE_BYTES) { "Request images exceed 25 MiB" }
            require(encodedTotal <= MAX_IMAGE_DATA_URL_BYTES) { "Image data URLs exceed 36 MiB" }
        }
        val result = linkedMapOf<String, String>()
        attachments.forEach { attachment ->
            val bytes = readBounded(checkedChild(directory(attachment.id), "content"), MAX_ATTACHMENT_BYTES)
            check(bytes.size.toLong() == attachment.sizeBytes) { "Attachment changed during read" }
            requireImageSignature(bytes, minOf(bytes.size, 12), attachment.mimeType)
            result[attachment.id] = "data:${attachment.mimeType};base64," +
                Base64.getEncoder().encodeToString(bytes)
        }
        result
    }

    /** Returns persisted attachments, including images and opaque local-only files. */
    fun list(): List<ConversationAttachment> = lock.withLock {
        val children = checkedRoot().listFiles() ?: throw IOException("Cannot enumerate attachment storage")
        children.filter { idPattern.matches(it.name) }
            .sortedBy { it.name }.map { loadLocked(it.name) ?: throw IOException("Attachment disappeared") }
    }

    /** Deletes only a validated managed ID and its two owned files, never the source URI. */
    fun delete(id: String): Boolean = lock.withLock {
        checkedRoot()
        val dir = directory(id)
        if (!dir.exists()) false else {
            removeOwnedDirectory(dir)
            true
        }
    }

    private fun loadLocked(id: String): ConversationAttachment? {
        checkedRoot()
        val dir = directory(id)
        if (!dir.exists()) return null
        check(dir.isDirectory) { "Invalid attachment directory" }
        val payload = checkedChild(dir, "content")
        val metadata = checkedChild(dir, "metadata.json")
        check(payload.isFile && metadata.isFile) { "Incomplete attachment" }
        val json = JSONObject(decodeText(readBounded(metadata, MAX_METADATA_BYTES)))
        check(json.getInt("version") == 1 && json.getString("id") == id) { "Invalid attachment metadata" }
        val name = json.getString("name")
        check(name == safeName(name)) { "Invalid attachment name" }
        val mime = json.getString("mimeType")
        check(mime == normalizeMime(mime)) { "Invalid attachment MIME" }
        requireSupportedMime(mime)
        val size = json.getLong("sizeBytes")
        check(size in 0..MAX_ATTACHMENT_BYTES && size == payload.length()) { "Invalid attachment size" }
        val evidence = if (json.has("textEvidence") && !json.isNull("textEvidence")) {
            json.getString("textEvidence")
        } else if (isTextMime(mime)) {
            "mime-utf8"
        } else null
        val text = when (evidence) {
            "mime-utf8" -> {
                check(isTextMime(mime)) { "Invalid text MIME evidence" }
                true
            }
            "code-extension-utf8" -> {
                check(isCodeCandidate(name, mime) && size <= MAX_TEXT_BYTES && isValidSmallText(payload)) {
                    "Code attachment UTF-8 evidence no longer matches"
                }
                true
            }
            null -> {
                check(!isTextMime(mime)) { "Missing text MIME evidence" }
                false
            }
            else -> error("Invalid attachment text evidence")
        }
        val image = mime in imageTypes
        check(json.getBoolean("textSupported") == text && json.getBoolean("isImage") == image) {
            "Invalid attachment type metadata"
        }
        return ConversationAttachment(id, name, mime, size, payload.absolutePath, text, image, evidence)
    }

    private fun checkedRoot(): File {
        check(base.isDirectory) { "App private directory is unavailable" }
        checkedChild(base, root.name)
        check(root.isDirectory || root.mkdir()) { "Cannot create private attachment directory" }
        return checkedChild(base, root.name)
    }

    private fun directory(id: String): File {
        require(idPattern.matches(id)) { "Invalid attachment ID" }
        return checkedChild(root, id)
    }

    private fun checkedChild(parent: File, name: String): File {
        val child = File(parent, name)
        val canonical = child.canonicalFile
        check(!Files.isSymbolicLink(child.toPath()) &&
            canonical == child.absoluteFile &&
            canonical.parentFile == parent.absoluteFile) { "Attachment path escapes managed storage" }
        return child
    }

    private fun totalPayloadBytes(): Long {
        var total = 0L
        var count = 0
        val children = root.listFiles() ?: throw IOException("Cannot enumerate attachment storage")
        children.forEach { entry ->
            if (idPattern.matches(entry.name) ||
                (entry.name.startsWith(".pending-") && idPattern.matches(entry.name.removePrefix(".pending-")))) {
                count++
                require(count < MAX_STORE_ATTACHMENTS) { "Attachment storage is limited to 512 files" }
                val dir = checkedChild(root, entry.name)
                check(dir.isDirectory) { "Invalid attachment directory" }
                val file = checkedChild(dir, "content")
                if (file.exists()) {
                    check(file.isFile) { "Invalid attachment payload" }
                    total += file.length()
                    require(total <= MAX_STORE_BYTES) { "Attachment storage exceeds 128 MiB" }
                }
            }
        }
        return total
    }

    private fun removeOwnedDirectory(dir: File) {
        checkedChild(root, dir.name)
        val children = dir.listFiles() ?: throw IOException("Cannot enumerate attachment directory")
        check(children.all { it.name == "content" || it.name == "metadata.json" }) {
            "Unexpected file in attachment directory"
        }
        val files = children.map {
            checkedChild(dir, it.name).also { child -> check(child.isFile) { "Invalid attachment file" } }
        }
        files.forEach { check(it.delete()) { "Cannot delete managed attachment file" } }
        check(dir.delete()) { "Cannot delete managed attachment directory" }
    }

    private fun validateTextFile(file: File) {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        InputStreamReader(file.inputStream(), decoder).use { reader ->
            val buffer = CharArray(4096)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                for (index in 0 until count) requireTextCharacter(buffer[index])
            }
        }
    }

    private fun readBounded(file: File, limit: Long): ByteArray {
        require(file.length() <= limit) { "Attachment read limit exceeded" }
        val result = ByteArrayOutputStream()
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                require(result.size().toLong() + count <= limit) { "Attachment read limit exceeded" }
                result.write(buffer, 0, count)
            }
        }
        return result.toByteArray()
    }

    private fun decodeText(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF").also { text ->
            text.forEach(::requireTextCharacter)
        }
    }

    private fun requireTextCharacter(char: Char) {
        require(!Character.isISOControl(char) || char == '\n' || char == '\r' || char == '\t') {
            "Attachment contains binary control characters"
        }
    }

    private fun safeName(value: String?): String {
        val cleaned = value.orEmpty().take(512).map { char ->
            if (char == '/' || char == '\\' || char in "<>:\"|?*" ||
                Character.isISOControl(char) || Character.getType(char) == Character.FORMAT.toInt()) '_' else char
        }.joinToString("").trim().trim('.').take(120).trim().trim('.')
        return cleaned.ifBlank { "attachment" }
    }

    private fun normalizeMime(value: String?): String {
        val mime = value?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            ?: "application/octet-stream"
        require(mimePattern.matches(mime)) { "Invalid attachment MIME type" }
        return mime
    }

    private fun isTextMime(mime: String): Boolean = mime.startsWith("text/") || mime in textApplications

    private fun isCodeCandidate(name: String, mime: String): Boolean =
        mime == "application/octet-stream" &&
            name.substringAfterLast('.', "").lowercase(Locale.ROOT) in codeExtensions

    private fun isValidSmallText(file: File): Boolean = try {
        decodeText(readBounded(file, MAX_TEXT_BYTES))
        true
    } catch (_: CharacterCodingException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun requireSupportedMime(mime: String) {
        require(isTextMime(mime) || mime in imageTypes || mime in opaqueTypes) {
            "Unsupported attachment MIME type"
        }
    }

    private fun requireImageSignature(file: File, mime: String) {
        val header = ByteArray(12)
        val count = file.inputStream().use { input ->
            var read = 0
            while (read < header.size) {
                val next = input.read(header, read, header.size - read)
                if (next < 0) break
                if (next == 0) continue
                read += next
            }
            read
        }
        requireImageSignature(header, count, mime)
    }

    private fun requireImageSignature(header: ByteArray, count: Int, mime: String) {
        fun starts(vararg bytes: Int) = count >= bytes.size &&
            bytes.indices.all { header[it].toInt() and 255 == bytes[it] }
        fun ascii(offset: Int, value: String) = count >= offset + value.length &&
            value.indices.all { header[offset + it].toInt() == value[it].code }
        val valid = when (mime) {
            "image/png" -> starts(137, 80, 78, 71, 13, 10, 26, 10)
            "image/jpeg" -> starts(255, 216, 255)
            "image/gif" -> ascii(0, "GIF87a") || ascii(0, "GIF89a")
            "image/webp" -> ascii(0, "RIFF") && ascii(8, "WEBP")
            else -> false
        }
        require(valid) { "Image signature does not match MIME type" }
    }
}
