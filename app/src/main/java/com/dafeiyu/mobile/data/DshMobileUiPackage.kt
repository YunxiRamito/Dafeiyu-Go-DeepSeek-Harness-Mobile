package com.dafeiyu.mobile.data

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A signed-APK asset, separate from the identical PC/Android Connect package. */
internal class DshMobileUiPackage(context: Context) {
    private val app = context.applicationContext
    private val sources = File(app.filesDir, "dsh-extension-packages")
    private val root = File(sources, "mobile-ui")
    private val lock = locks.computeIfAbsent(root.absolutePath) { Mutex() }

    data class Installed(val directory: File, val sources: File, val guestPath: String)
    private data class Member(val name: String, val bytes: Long, val sha256: String)
    private data class Manifest(val file: String, val bytes: Int, val sha256: String, val members: List<Member>)

    suspend fun ensureBundled(): Installed = withContext(Dispatchers.IO) { lock.withLock {
        val coroutine = currentCoroutineContext()
        val manifest = manifest()
        directory(sources); directory(root)
        val target = File(root, manifest.sha256)
        if (!target.exists()) {
            val stage = File(root, "stage-${UUID.randomUUID()}")
            require(stage.mkdir()) { "无法准备手机网页插件" }
            try {
                val archive = app.assets.open("mobile-ui/${manifest.file}").use {
                    ConnectionPackageValidator.readBounded(it, MAX_ARCHIVE)
                }
                require(archive.size == manifest.bytes && digest(archive) == manifest.sha256) { "手机网页插件包校验失败" }
                archive.inputStream().use { input ->
                    LinuxArchiveExtractor.extract(input, stage, MAX_EXPANDED, { coroutine.ensureActive() })
                }
                verify(stage, manifest)
                coroutine.ensureActive()
                require(stage.renameTo(target)) { "手机网页插件提交失败" }
            } finally {
                // Only the unique unpublished staging tree owned by this installer.
                if (stage.exists()) LinuxArchiveExtractor.removeTree(stage)
            }
        }
        val packageDirectory = verify(target, manifest)
        Installed(packageDirectory, sources,
            "/dafeiyu-extension-packages/mobile-ui/${manifest.sha256}/package")
    } }

    /** Recheck the actual slot immediately before creating an executing process. */
    fun verifyInstalled(installed: Installed) {
        val manifest = manifest()
        require(installed.sources == sources && installed.guestPath ==
            "/dafeiyu-extension-packages/mobile-ui/${manifest.sha256}/package") { "手机网页插件来源不匹配" }
        require(verify(File(root, manifest.sha256), manifest).canonicalFile == installed.directory.canonicalFile)
    }

    private fun manifest(): Manifest {
        val document = app.assets.open("mobile-ui/manifest.json").use {
            JSONObject(ConnectionPackageValidator.readBounded(it, 16 * 1024).toString(Charsets.UTF_8))
        }
        require(document.getInt("schema") == 1 && document.getString("name") == "dafeiyu-mobile-ui" &&
            document.getString("version") == VERSION && document.getString("file_name") == "dafeiyu-mobile-ui-$VERSION.tgz")
        val rows = document.getJSONArray("files")
        require(rows.length() in 3..32)
        val members = List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val name = row.getString("path")
            require(name.startsWith("package/") && name.length <= 160 &&
                name.split('/').all { it.matches(Regex("[A-Za-z0-9._-]+")) && it !in setOf(".", "..") })
            Member(name, row.getLong("size_bytes").also { require(it in 1..MAX_EXPANDED) },
                row.getString("sha256").also { require(it.matches(Regex("[a-f0-9]{64}"))) })
        }
        require(members.map { it.name }.toSet().size == members.size &&
            members.map { it.name }.containsAll(listOf("package/package.json", "package/client/client.js", "package/LICENSE")))
        return Manifest(document.getString("file_name"), document.getInt("size_bytes").also { require(it in 1..MAX_ARCHIVE) },
            document.getString("sha256").also { require(it.matches(Regex("[a-f0-9]{64}"))) }, members)
    }

    private fun verify(slot: File, manifest: Manifest): File {
        require(slot.isDirectory && !LinuxArchiveExtractor.isSymlink(slot)) { "手机网页插件目录失效" }
        val expected = manifest.members.associateBy { it.name }
        val actual = mutableSetOf<String>()
        for (entry in slot.walkTopDown()) {
            require(!LinuxArchiveExtractor.isSymlink(entry)) { "手机网页插件不能包含链接" }
            if (entry == slot || entry.isDirectory) continue
            val name = entry.relativeTo(slot).invariantSeparatorsPath
            val member = expected[name] ?: error("手机网页插件包含未锁定文件")
            require(entry.isFile && entry.length() == member.bytes && entry.canonicalPath.startsWith(slot.canonicalPath + File.separator))
            val checksum = MessageDigest.getInstance("SHA-256")
            entry.inputStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; checksum.update(buffer, 0, count) }
            }
            require(checksum.digest().toHex() == member.sha256) { "手机网页插件文件校验失败" }
            actual.add(name)
        }
        require(actual == expected.keys) { "手机网页插件文件不完整" }
        val identity = JSONObject(File(slot, "package/package.json").readText(Charsets.UTF_8))
        require(identity.getString("name") == "dafeiyu-mobile-ui" && identity.getString("version") == VERSION &&
            identity.getString("license") == "MIT" && (identity.optJSONObject("dependencies")?.length() ?: 0) == 0)
        return File(slot, "package")
    }

    private fun directory(file: File) {
        require(!LinuxArchiveExtractor.isSymlink(file) && (file.isDirectory || file.mkdirs())) { "手机网页插件目录无法创建" }
        require(file.canonicalPath.startsWith(app.filesDir.canonicalPath + File.separator))
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        const val VERSION = "0.1.5"
        private const val MAX_ARCHIVE = 1024 * 1024
        private const val MAX_EXPANDED = 2L * 1024 * 1024
        private val locks = ConcurrentHashMap<String, Mutex>()
    }
}
