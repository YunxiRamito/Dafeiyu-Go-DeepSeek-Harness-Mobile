package com.dafeiyu.mobile.data

import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONObject
import org.json.JSONTokener

/** Owns only noBackupFilesDir/official-tool-outputs. All disk methods run on the caller's IO dispatcher. */
class OfficialToolOutputStore(
    noBackupFilesDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val base = noBackupFilesDir.canonicalFile
    private val root = File(base, "official-tool-outputs")

    companion object {
        const val MAX_FILES = 100
        const val MAX_STORE_BYTES = 16L * 1024 * 1024
        private const val MAX_METADATA_BYTES = 8192L
        private val lock = ReentrantLock()
    }

    fun write(filename: String, text: String, conversationId: String): OfficialToolOutput = lock.withLock {
        OfficialToolContract.filename(filename)
        OfficialToolContract.safeText(text, OfficialToolContract.MAX_TEXT_CHARACTERS, allowEmpty = true)
        OfficialToolContract.conversationId(conversationId)
        val bytes = OfficialToolContract.utf8(text)
        require(bytes.size <= OfficialToolContract.MAX_TEXT_BYTES)
        val children = children()
        require(children.size < MAX_FILES)
        val used = children.sumOf { directory ->
            checked(directory, "content.txt").length() + checked(directory, "metadata.json").length()
        }
        val id = UUID.randomUUID().toString()
        val record = OfficialToolOutput(id, filename, bytes.size.toLong(), clock(), conversationId)
        require(record.createdAtMillis >= 0)
        val metadata = JSONObject().put("version", 1).put("id", id).put("filename", filename)
            .put("size_bytes", record.sizeBytes).put("created_at", record.createdAtMillis)
            .put("conversation_id", conversationId).toString().toByteArray(Charsets.UTF_8)
        require(metadata.size <= MAX_METADATA_BYTES && used + bytes.size + metadata.size <= MAX_STORE_BYTES)
        val pending = checked(root, ".pending-$id")
        check(pending.mkdir())
        var committed = false
        try {
            fun writeFile(name: String, content: ByteArray) {
                FileOutputStream(checked(pending, name)).use {
                    it.write(content)
                    it.fd.sync()
                }
            }
            writeFile("content.txt", bytes)
            writeFile("metadata.json", metadata)
            val target = checked(root, id)
            check(!target.exists() && pending.renameTo(target))
            committed = true
            record
        } finally {
            if (!committed) {
                for (name in listOf("content.txt", "metadata.json")) {
                    val file = checked(pending, name)
                    if (file.exists()) check(file.delete())
                }
                check(pending.delete())
            }
        }
    }

    fun get(id: String): OfficialToolOutput? = lock.withLock {
        children()
        load(id)
    }

    fun list(): List<OfficialToolOutput> = lock.withLock {
        children().filter { OfficialToolContract.uuidPattern.matches(it.name) }
            .map { load(it.name) ?: throw IOException("output_missing") }
    }

    fun resolveFile(id: String): File = lock.withLock {
        children()
        check(load(id) != null)
        checked(checked(root, id), "content.txt")
    }

    /** Caller owns the SAF destination stream. No arbitrary destination path is accepted. */
    fun copyTo(id: String, destination: OutputStream): OfficialToolOutput = lock.withLock {
        children()
        val record = load(id) ?: throw IOException("output_missing")
        val bytes = read(checked(checked(root, id), "content.txt"), OfficialToolContract.MAX_TEXT_BYTES.toLong())
        check(bytes.size.toLong() == record.sizeBytes)
        destination.write(bytes)
        destination.flush()
        record
    }

    private fun checked(parent: File, name: String): File {
        val child = File(parent, name)
        check(!Files.isSymbolicLink(child.toPath()) && child.canonicalFile.parentFile == parent.canonicalFile)
        return child
    }

    private fun children(): List<File> {
        check(base.isDirectory && !Files.isSymbolicLink(root.toPath()) && root.canonicalFile.parentFile == base)
        check(root.isDirectory || root.mkdir())
        return (root.listFiles() ?: throw IOException("output_storage_failed")).toList().also { children ->
            check(children.size <= MAX_FILES)
            children.forEach {
                check(OfficialToolContract.uuidPattern.matches(it.name) ||
                    (it.name.startsWith(".pending-") && OfficialToolContract.uuidPattern.matches(it.name.removePrefix(".pending-"))))
                check(checked(root, it.name).isDirectory)
                val entries = it.listFiles() ?: throw IOException("output_storage_failed")
                check(entries.all { entry -> entry.name in setOf("content.txt", "metadata.json") })
                entries.forEach { entry -> check(checked(it, entry.name).isFile) }
            }
        }
    }

    private fun load(id: String): OfficialToolOutput? {
        require(OfficialToolContract.uuidPattern.matches(id))
        val directory = checked(root, id)
        if (!directory.exists()) return null
        val parser = JSONTokener(OfficialToolContract.decodeUtf8(read(checked(directory, "metadata.json"), MAX_METADATA_BYTES)))
        val json = parser.nextValue() as? JSONObject ?: error("invalid_output")
        require(parser.nextClean() == '\u0000' && json.opt("version") == 1 && json.opt("id") == id)
        val filename = OfficialToolContract.filename(json.get("filename") as? String ?: error("invalid_output"))
        val conversationId = OfficialToolContract.conversationId(json.get("conversation_id") as? String ?: error("invalid_output"))
        fun number(field: String): Long {
            val value = json.get(field)
            require(value is Int || value is Long)
            return (value as Number).toLong().also { require(it >= 0) }
        }
        val size = number("size_bytes")
        require(size <= OfficialToolContract.MAX_TEXT_BYTES)
        val payload = read(checked(directory, "content.txt"), OfficialToolContract.MAX_TEXT_BYTES.toLong())
        require(size == payload.size.toLong())
        OfficialToolContract.safeText(OfficialToolContract.decodeUtf8(payload),
            OfficialToolContract.MAX_TEXT_CHARACTERS, allowEmpty = true)
        return OfficialToolOutput(id, filename, size, number("created_at"), conversationId)
    }

    private fun read(file: File, max: Long): ByteArray {
        require(file.isFile && file.length() <= max)
        return file.inputStream().use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= max) {
                val count = stream.read(buffer, 0, minOf(buffer.size, max.toInt() + 1 - output.size()))
                if (count < 0) break
                if (count > 0) output.write(buffer, 0, count)
            }
            val bytes = output.toByteArray()
            require(bytes.size <= max)
            bytes
        }
    }
}
