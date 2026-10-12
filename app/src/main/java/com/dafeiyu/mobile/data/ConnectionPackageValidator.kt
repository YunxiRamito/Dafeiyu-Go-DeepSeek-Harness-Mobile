package com.dafeiyu.mobile.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.security.MessageDigest
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONObject

/** Validation and extraction only: preparing a package never starts its runtime. */
internal object ConnectionPackageValidator {
    const val PROTOCOL = "dafeiyu-go.connection.v1"
    const val MAX_MANIFEST = 8 * 1024
    const val MAX_ARCHIVE = 16 * 1024 * 1024
    const val MAX_UNPACKED = 32L * 1024 * 1024
    const val MAX_ENTRIES = 1024
    private const val MAX_PACKAGE_JSON = 64 * 1024
    // Includes tar padding/headers and bounded extended metadata as well as file contents.
    private const val MAX_TAR_STREAM = MAX_UNPACKED + MAX_ENTRIES * 4096L
    private val versionPattern = Regex("[0-9]{1,9}\\.[0-9]{1,9}\\.[0-9]{1,9}")
    private val lifecycleScripts = setOf(
        "install", "prepare", "publish", "pack", "version", "dependencies",
        "start", "stop", "restart",
    )

    data class Manifest(
        val version: String,
        val sha256: String,
        val sizeBytes: Long,
        val fileName: String,
        val archivePath: String,
        val bytes: ByteArray,
    )

    fun manifest(bytes: ByteArray): Manifest {
        require(bytes.isNotEmpty() && bytes.size <= MAX_MANIFEST) { "plugin_manifest_size_invalid" }
        val json = JSONObject(utf8(bytes))
        val version = string(json, "version")
        val sha = string(json, "sha256")
        val fileName = string(json, "file_name")
        val archivePath = string(json, "archive_path")
        val size = json.get("size_bytes")
        require(size is Int || size is Long) { "plugin_archive_size_invalid" }
        val sizeBytes = (size as Number).toLong()
        require(versionPattern.matches(version) && versionPattern.matches(string(json, "minimum_host_version"))) {
            "plugin_version_invalid"
        }
        require(string(json, "protocol") == PROTOCOL) { "plugin_protocol_invalid" }
        val platforms = json.getJSONArray("platforms")
        require(platforms.length() in 1..16 && (0 until platforms.length()).all { platforms.get(it) is String } &&
            (0 until platforms.length()).any { platforms.get(it) == "android" }) { "plugin_platform_invalid" }
        require(sha.matches(Regex("[0-9a-fA-F]{64}"))) { "plugin_archive_hash_invalid" }
        require(sizeBytes in 1..MAX_ARCHIVE.toLong()) { "plugin_archive_size_invalid" }
        require(fileName == "dafeiyu-go-connect-$version.tgz" &&
            archivePath == "/api/connection-plugin/releases/$version/package") { "plugin_archive_path_invalid" }
        // minimum_host_version describes the PC launcher; Android validates the protocol instead.
        return Manifest(version, sha.lowercase(), sizeBytes, fileName, archivePath, bytes.copyOf())
    }

    fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
            if (read < 0) break
            require(output.size().toLong() + read <= limit) { "plugin_input_too_large" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    fun verifyArchive(manifest: Manifest, archive: ByteArray) {
        require(archive.size.toLong() == manifest.sizeBytes && archive.size <= MAX_ARCHIVE &&
            sha256(archive) == manifest.sha256) { "plugin_archive_hash_mismatch" }
    }

    fun extract(manifest: Manifest, archive: ByteArray, stage: File): File {
        verifyArchive(manifest, archive)
        require(stage.isDirectory && !Files.isSymbolicLink(stage.toPath()) && stage.list().orEmpty().isEmpty()) {
            "plugin_stage_invalid"
        }
        walkArchive(archive) { name, directory, size, input ->
            val destination = safeFile(stage, name)
            if (directory) {
                check(destination.mkdirs() || destination.isDirectory) { "plugin_extract_failed" }
            } else {
                check(destination.parentFile!!.mkdirs() || destination.parentFile!!.isDirectory) { "plugin_extract_failed" }
                destination.outputStream().use { output -> copyEntry(input, size) { bytes, count -> output.write(bytes, 0, count) } }
            }
        }
        return File(stage, "package").also { validatePackage(it, manifest.version) }
    }

    /** Checks the whole installed tree against its pinned original archive, including extra files. */
    fun verifyInstalled(manifest: Manifest, slot: File): File {
        require(slot.isDirectory && !Files.isSymbolicLink(slot.toPath())) { "plugin_slot_invalid" }
        val archiveFile = safeFile(slot, "archive.tgz")
        require(archiveFile.isFile && !Files.isSymbolicLink(archiveFile.toPath())) { "plugin_archive_missing" }
        val archive = archiveFile.inputStream().use { readBounded(it, MAX_ARCHIVE) }
        verifyArchive(manifest, archive)
        val expectedFiles = HashSet<String>()
        val allowedDirectories = hashSetOf("package")
        walkArchive(archive) { name, directory, size, input ->
            val destination = safeFile(slot, name)
            var parent = name.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                allowedDirectories.add(parent)
                parent = parent.substringBeforeLast('/', "")
            }
            require(!Files.isSymbolicLink(destination.toPath())) { "plugin_installed_link" }
            if (directory) {
                allowedDirectories.add(name)
                require(destination.isDirectory) { "plugin_installed_content_mismatch" }
            } else {
                expectedFiles.add(name)
                require(destination.isFile && destination.length() == size) { "plugin_installed_content_mismatch" }
                val expectedDigest = MessageDigest.getInstance("SHA-256")
                copyEntry(input, size) { bytes, count -> expectedDigest.update(bytes, 0, count) }
                val actualDigest = destination.inputStream().use { sha256Stream(it) }
                require(MessageDigest.isEqual(expectedDigest.digest(), actualDigest)) { "plugin_installed_content_mismatch" }
            }
        }
        val pkg = safeFile(slot, "package")
        var entries = 0
        pkg.walkTopDown().onEnter {
            require(!Files.isSymbolicLink(it.toPath())) { "plugin_installed_link" }
            true
        }.forEach { file ->
            require(++entries <= MAX_ENTRIES * 2) { "plugin_installed_entries_invalid" }
            val name = file.relativeTo(slot).invariantSeparatorsPath
            require(!Files.isSymbolicLink(file.toPath()) &&
                if (file.isDirectory) name in allowedDirectories else name in expectedFiles && file.isFile) {
                "plugin_installed_content_mismatch"
            }
        }
        validatePackage(pkg, manifest.version)
        return pkg
    }

    private fun walkArchive(archive: ByteArray, consume: (String, Boolean, Long, InputStream) -> Unit) {
        val paths = HashSet<String>()
        val caseInsensitivePaths = HashSet<String>()
        var total = 0L
        GzipCompressorInputStream(archive.inputStream(), true).use { gzip ->
            val bounded = LimitedInputStream(gzip, MAX_TAR_STREAM)
            TarArchiveInputStream(bounded).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    val directory = entry.linkFlag == TarConstants.LF_DIR
                    val regular = entry.linkFlag == TarConstants.LF_NORMAL || entry.linkFlag == TarConstants.LF_OLDNORM
                    val name = if (directory) entry.name.removeSuffix("/") else entry.name
                    require(name.length <= 512 && name.none(Char::isISOControl) && '\\' !in name && ':' !in name &&
                        (name == "package" && directory || name.startsWith("package/")) &&
                        name.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "plugin_entry_path_invalid" }
                    require(paths.add(name) && caseInsensitivePaths.add(name.lowercase(java.util.Locale.ROOT)) &&
                        paths.size <= MAX_ENTRIES) { "plugin_entry_duplicate_or_limit" }
                    require(!entry.isSymbolicLink && !entry.isLink && !entry.isSparse &&
                        (directory || regular) && entry.isCheckSumOK && tar.canReadEntryData(entry)) { "plugin_entry_type_invalid" }
                    require(entry.size in 0..MAX_UNPACKED && (!directory || entry.size == 0L)) { "plugin_entry_size_invalid" }
                    total += entry.size
                    require(total <= MAX_UNPACKED) { "plugin_unpacked_too_large" }
                    consume(name, directory, entry.size, tar)
                }
                // Consume padding and verify every gzip member/trailer within the same bound.
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = bounded.read(buffer)
                    if (read < 0) break
                    require((0 until read).all { buffer[it] == 0.toByte() }) { "plugin_archive_trailing_content" }
                }
            }
        }
    }

    private fun validatePackage(pkg: File, version: String) {
        val jsonFile = safeFile(pkg, "package.json")
        require(jsonFile.isFile && !Files.isSymbolicLink(jsonFile.toPath())) { "plugin_metadata_missing" }
        val json = JSONObject(utf8(jsonFile.inputStream().use { readBounded(it, MAX_PACKAGE_JSON) }))
        val dsh = json.getJSONObject("dsh")
        require(string(json, "name") == "dafeiyu-go-connect" && string(json, "version") == version &&
            string(json, "type") == "module" && string(json, "main") == "./lib/index.js" &&
            string(dsh, "pluginApi") == PROTOCOL && string(dsh, "entry") == "./lib/index.js") { "plugin_metadata_invalid" }
        if (json.has("scripts")) {
            val scripts = json.getJSONObject("scripts")
            scripts.keys().forEach { name ->
                require(name !in lifecycleScripts && !name.startsWith("pre") && !name.startsWith("post")) {
                    "plugin_lifecycle_script_forbidden"
                }
                require(scripts.get(name) is String) { "plugin_script_invalid" }
            }
        }
        listOf("lib/index.js", "lib/framework/launcher-provider.js").forEach {
            val entry = safeFile(pkg, it)
            require(entry.isFile && !Files.isSymbolicLink(entry.toPath())) { "plugin_entry_missing" }
        }
    }

    private fun safeFile(root: File, name: String): File {
        val file = File(root, name)
        require(file.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "plugin_entry_path_invalid" }
        return file
    }

    private fun copyEntry(input: InputStream, size: Long, write: (ByteArray, Int) -> Unit) {
        var copied = 0L
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            copied += read
            require(copied <= size) { "plugin_entry_size_invalid" }
            write(buffer, read)
        }
        require(copied == size) { "plugin_entry_truncated" }
    }

    private fun string(json: JSONObject, name: String): String {
        val value = json.get(name)
        require(value is String) { "plugin_manifest_field_invalid" }
        return value
    }

    private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun sha256Stream(input: InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_UNPACKED) { "plugin_unpacked_too_large" }
            digest.update(buffer, 0, read)
        }
        return digest.digest()
    }

    private class LimitedInputStream(private val source: InputStream, private val limit: Long) : InputStream() {
        private var count = 0L
        override fun read(): Int {
            val byte = source.read()
            if (byte >= 0) checkCount(1)
            return byte
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = source.read(buffer, offset, minOf(length.toLong(), limit - count + 1).toInt())
            if (read > 0) checkCount(read)
            return read
        }
        override fun skip(bytes: Long): Long {
            var remaining = bytes
            val buffer = ByteArray(8192)
            while (remaining > 0) {
                val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                remaining -= read
            }
            return bytes - remaining
        }
        private fun checkCount(read: Int) {
            count += read
            require(count <= limit) { "plugin_tar_stream_too_large" }
        }
    }
}
