package com.dafeiyu.mobile.data

import android.content.Context
import android.util.AtomicFile
import com.dafeiyu.mobile.glasslab.LabDocument
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject

/** Digest-addressed files and an atomic selection index; a rejected import preserves the active theme. */
internal class ThemeStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "themes")
    private val lock = locks[(root.absolutePath.hashCode() and Int.MAX_VALUE) % locks.size]
    var recoveryNotice: String? = null
        private set

    fun load(): ThemeSelection = synchronized(lock) {
        recoveryNotice = null
        val index = File(root, "selection.json")
        if (!index.isFile && !File(root, "selection.json.bak").isFile) return@synchronized ThemeSelection()
        try {
            val json = JSONObject(ThemeDocuments.utf8(readAtomic(index, 64 * 1024)))
            require(json.getInt("schema") == 1)
            val custom = json.optJSONObject("custom")?.let { readTheme(it, true) }
            val installed = json.getJSONArray("installed")
            require(installed.length() <= MAX_INSTALLED)
            val entries = (0 until installed.length()).mapNotNull { i -> readTheme(installed.getJSONObject(i), false) }
            require(entries.map { it.id }.distinct().size == entries.size)
            val selected = json.getString("activeId")
            val valid = selected == ThemeDocuments.DEFAULT_ID || selected == ThemeDocuments.CUSTOM_ID && custom != null ||
                entries.any { it.id == selected }
            if (!valid) recoveryNotice = "上次主题无法读取，已恢复默认；主题原文件保留。"
            ThemeSelection(if (valid) selected else ThemeDocuments.DEFAULT_ID, custom, entries)
        } catch (_: Exception) {
            recoveryNotice = "上次主题记录无法读取，已恢复默认；主题原文件保留。"
            ThemeSelection()
        }
    }

    fun save(selection: ThemeSelection): Unit = synchronized(lock) {
        require(selection.installed.size <= MAX_INSTALLED)
        val installed = JSONArray().apply { selection.installed.forEach { put(writeTheme(it, false)) } }
        val json = JSONObject().put("schema", 1).put("activeId", selection.activeId)
            .put("installed", installed).put("custom", selection.custom?.let { writeTheme(it, true) } ?: JSONObject.NULL)
        require(selection.activeId == ThemeDocuments.DEFAULT_ID || selection.active() != null)
        writeAtomic(File(root, "selection.json"), json.toString(2).toByteArray(Charsets.UTF_8))
        // An intentional new import may replace the custom document thousands of times.
        // Keep only documents referred to by the committed index, under the same store lock.
        val keep = buildSet {
            for (index in 0 until installed.length()) add(installed.getJSONObject(index).getString("sha256"))
            json.optJSONObject("custom")?.let { add(it.getString("sha256")) }
        }
        val documents = File(root, "documents")
        if (!Files.isSymbolicLink(documents.toPath())) documents.listFiles()?.forEach { candidate ->
            if (candidate.name.matches(Regex("[a-f0-9]{64}\\.json(?:\\.bak|\\.new)?")) &&
                candidate.name.take(64) !in keep && !Files.isSymbolicLink(candidate.toPath())) {
                // A failed load leaves its corrupt document available for recovery.
                // Prune only verified, healthy documents replaced by an intentional import.
                val healthy = runCatching {
                    val bytes = candidate.inputStream().use { ThemeDocuments.read(it) }
                    require(ThemeDocuments.sha256(bytes) == candidate.name.take(64))
                    ThemeDocuments.decode(bytes)
                }.isSuccess
                if (healthy) candidate.delete()
            }
        }
    }

    fun loadMarket(): ThemeMarketPage? = synchronized(lock) {
        try { ThemeMarketCodec.decode(readAtomic(File(root, "market.json"), ThemeMarketCodec.MAX_PAGE_BYTES)) }
        catch (_: Exception) { null }
    }

    fun saveMarket(page: ThemeMarketPage): Unit = synchronized(lock) {
        writeAtomic(File(root, "market.json"), ThemeMarketCodec.encode(page))
    }

    private fun readTheme(entry: JSONObject, custom: Boolean): InstalledTheme? = try {
        val id = entry.getString("id")
        require(if (custom) id == ThemeDocuments.CUSTOM_ID else ThemeDocuments.safeId(id) == id)
        val digest = entry.getString("sha256")
        require(digest.matches(Regex("[a-f0-9]{64}")))
        val bytes = readAtomic(File(root, "documents/$digest.json"), LabDocument.MAX_IMPORT_BYTES)
        require(ThemeDocuments.sha256(bytes) == digest)
        val sourceDigest = entry.optString("sourceSha256", digest).also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        InstalledTheme(id, ThemeDocuments.safeTitle(entry.getString("title")), entry.getInt("revision").also { require(it >= 0) },
            sourceDigest, ThemeDocuments.decode(bytes))
    } catch (_: Exception) {
        recoveryNotice = "部分已安装主题无法读取，原文件保留；你可以重新导入或下载。"
        null
    }

    private fun writeTheme(theme: InstalledTheme, custom: Boolean): JSONObject {
        require(if (custom) theme.id == ThemeDocuments.CUSTOM_ID else ThemeDocuments.safeId(theme.id) == theme.id)
        require(theme.revision >= 0)
        require(theme.sha256.matches(Regex("[a-f0-9]{64}")))
        val bytes = theme.document.encode().toByteArray(Charsets.UTF_8)
        require(bytes.size <= LabDocument.MAX_IMPORT_BYTES) { "主题文件超过大小限制" }
        val digest = ThemeDocuments.sha256(bytes)
        // Disk storage uses a canonical document digest; catalog digest remains separately metadata-bound.
        writeAtomic(File(root, "documents/$digest.json"), bytes)
        return JSONObject().put("id", theme.id).put("title", ThemeDocuments.safeTitle(theme.title))
            .put("revision", theme.revision).put("sha256", digest).put("sourceSha256", theme.sha256)
    }

    private fun readAtomic(file: File, maximum: Int): ByteArray {
        require(!Files.isSymbolicLink(root.toPath()) && !Files.isSymbolicLink(file.toPath()))
        return AtomicFile(file).openRead().use { ThemeDocuments.read(it, maximum) }
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        val parent = requireNotNull(file.parentFile)
        require(!Files.isSymbolicLink(root.toPath()) && !Files.isSymbolicLink(parent.toPath()) &&
            !Files.isSymbolicLink(file.toPath())) { "主题保存位置无效" }
        require(parent.isDirectory || parent.mkdirs()) { "无法保存主题" }
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (failure: Exception) { atomic.failWrite(output); throw failure }
        require(readAtomic(file, maxOf(bytes.size, 1)).contentEquals(bytes)) { "主题落盘校验失败" }
    }

    companion object {
        const val MAX_INSTALLED = 40
        private val locks = Array(16) { Any() }
    }
}
