package com.dafeiyu.mobile.data

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.json.JSONObject

private data class Entry(val name: String, val bytes: ByteArray = byteArrayOf(), val flag: Byte = TarConstants.LF_NORMAL)
private var checks = 0
private lateinit var fixtures: File

private inline fun pass(name: String, block: () -> Unit) {
    block()
    checks++
    println("PASS $name")
}

private inline fun rejects(name: String, block: () -> Unit) = pass(name) {
    check(runCatching(block).isFailure) { "Expected rejection: $name" }
}

private fun metadata(version: String = "0.2.0", scripts: JSONObject? = null): ByteArray =
    JSONObject().put("name", "dafeiyu-go-connect").put("version", version).put("type", "module")
        .put("main", "./lib/index.js").put("dsh", JSONObject()
            .put("pluginApi", ConnectionPackageValidator.PROTOCOL).put("entry", "./lib/index.js"))
        .also { if (scripts != null) it.put("scripts", scripts) }.toString().toByteArray()

private fun entries(version: String = "0.2.0", scripts: JSONObject? = null) = listOf(
    Entry("package/package.json", metadata(version, scripts)),
    Entry("package/lib/index.js", "export const version = '$version';".toByteArray()),
    Entry("package/lib/framework/launcher-provider.js", "export const provider = true;".toByteArray()),
)

private fun archive(entries: List<Entry>): ByteArray {
    val rawBytes = ByteArrayOutputStream()
        TarArchiveOutputStream(rawBytes).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            entries.forEach { item ->
                val entry = TarArchiveEntry(item.name, item.flag, true)
                if (item.flag == TarConstants.LF_NORMAL) entry.size = item.bytes.size.toLong()
                if (item.flag == TarConstants.LF_SYMLINK || item.flag == TarConstants.LF_LINK) {
                    entry.linkName = "../../outside"
                }
                tar.putArchiveEntry(entry)
                if (item.flag == TarConstants.LF_NORMAL) tar.write(item.bytes)
                tar.closeArchiveEntry()
            }
    }
    val raw = rawBytes.toByteArray()
    // Windows normalizes backslashes in TarArchiveEntry constructors; inject the actual wire header.
    var offset = 0
    entries.forEach { item ->
        if ('\\' in item.name) {
            raw.fill(0, offset, offset + 100)
            item.name.toByteArray().copyInto(raw, offset)
            raw.fill(32, offset + 148, offset + 156)
            val checksum = (offset until offset + 512).sumOf { raw[it].toInt() and 255 }
            (checksum.toString(8).padStart(6, '0') + "\u0000 ").toByteArray().copyInto(raw, offset + 148)
        }
        offset += 512 + if (item.flag == TarConstants.LF_NORMAL) ((item.bytes.size + 511) / 512) * 512 else 0
    }
    val bytes = ByteArrayOutputStream()
    GzipCompressorOutputStream(bytes).use { it.write(raw) }
    return bytes.toByteArray()
}

private fun manifest(archive: ByteArray, version: String = "0.2.0"): JSONObject = JSONObject()
    .put("protocol", ConnectionPackageValidator.PROTOCOL).put("version", version)
    .put("minimum_host_version", "1.8.0").put("platforms", org.json.JSONArray(listOf("android", "windows")))
    .put("sha256", MessageDigest.getInstance("SHA-256").digest(archive).joinToString("") { "%02x".format(it) })
    .put("size_bytes", archive.size).put("file_name", "dafeiyu-go-connect-$version.tgz")
    .put("archive_path", "/api/connection-plugin/releases/$version/package")

private fun parse(json: JSONObject) = ConnectionPackageValidator.manifest(json.toString().toByteArray())
private fun extract(bytes: ByteArray, version: String = "0.2.0"): File {
    val stage = File(fixtures, "extract-${java.util.UUID.randomUUID()}").apply { mkdir() }
    return ConnectionPackageValidator.extract(parse(manifest(bytes, version)), bytes, stage)
}

fun main(args: Array<String>) = runBlocking {
    fixtures = File(args[0]).apply { mkdirs() }
    val frozen = File(args[1])
    val frozenManifest = File(args[2])
    val frozenBytes = frozen.readBytes()
    val approved = ConnectionPackageValidator.manifest(frozenManifest.readBytes())
    pass("approved frozen archive SHA and extraction") {
        check(approved.sha256 == "4f3657730459197835a014b7f7433b44cc652e5b32480cdb37c061b160e75bf2" &&
            approved.version == "0.3.0" && approved.sizeBytes == 200430L)
        val stage = File(fixtures, "frozen").apply { mkdir() }
        ConnectionPackageValidator.extract(approved, frozenBytes, stage)
        check(File(stage, "package/lib/index.js").isFile)
    }
    val valid = archive(entries(scripts = JSONObject().put("test", "node test.js")
        .put("check", "node --check lib/index.js").put("build", "node build.js")))
    pass("test/check/build scripts permitted") { extract(valid) }
    pass("explicit package and nested directories permitted") {
        extract(archive(listOf(Entry("package/", flag = TarConstants.LF_DIR),
            Entry("package/lib/", flag = TarConstants.LF_DIR)) + entries()))
    }
    rejects("manifest exceeds 8 KiB") { ConnectionPackageValidator.manifest(ByteArray(8193) { 32 }) }
    pass("bounded reader accepts exact limit") {
        check(ConnectionPackageValidator.readBounded(ByteArray(8192).inputStream(), 8192).size == 8192)
    }
    rejects("bounded reader rejects one byte above limit") {
        ConnectionPackageValidator.readBounded(ByteArray(8193).inputStream(), 8192)
    }
    val badManifest = mapOf<String, Any>(
        "protocol" to "other", "version" to "../0.2.0", "minimum_host_version" to "unbounded",
        "platforms" to org.json.JSONArray(listOf("windows")), "sha256" to "abc",
        "size_bytes" to 16777217, "file_name" to "../plugin.tgz",
        "archive_path" to "//host/plugin", "size_bytes_float" to 1.5,
    )
    badManifest.forEach { (key, value) ->
        rejects("manifest rejects $key") {
            parse(manifest(valid).put(if (key == "size_bytes_float") "size_bytes" else key, value))
        }
    }
    rejects("manifest rejects string archive size") { parse(manifest(valid).put("size_bytes", "${valid.size}")) }
    rejects("invalid UTF8 manifest") { ConnectionPackageValidator.manifest(byteArrayOf(0xC3.toByte(), 0x28)) }
    rejects("archive SHA mismatch") {
        ConnectionPackageValidator.extract(parse(manifest(valid)), valid.copyOf().also {
            it[0] = (it[0].toInt() xor 1).toByte()
        }, File(fixtures, "hash").apply { mkdir() })
    }
    rejects("archive size mismatch") {
        ConnectionPackageValidator.extract(parse(manifest(valid).put("size_bytes", valid.size + 1)), valid,
            File(fixtures, "size").apply { mkdir() })
    }
    listOf("package/../escape", "../escape", "/package/escape", "package/a/../../escape",
        "package//double", "package/./dot", "package\\escape", "package/C:escape",
        "package/control\nname", "other/file", "package/file/").forEach { path ->
        rejects("archive path $path") { extract(archive(entries() + Entry(path, byteArrayOf(1)))) }
    }
    rejects("duplicate path") { extract(archive(entries() + entries().first())) }
    rejects("case-folded duplicate path") {
        extract(archive(entries() + Entry("package/Package.json", metadata())))
    }
    rejects("normalized directory duplicate") {
        extract(archive(listOf(Entry("package/", flag = TarConstants.LF_DIR),
            Entry("package", flag = TarConstants.LF_DIR)) + entries()))
    }
    listOf(TarConstants.LF_SYMLINK, TarConstants.LF_LINK, TarConstants.LF_FIFO, TarConstants.LF_CHR).forEach { flag ->
        rejects("archive special type $flag") { extract(archive(entries() + Entry("package/link", flag = flag))) }
    }
    listOf("preinstall", "install", "postinstall", "prepare", "prepublish", "prepublishOnly",
        "publish", "postpublish", "prepack", "pack", "postpack", "dependencies", "pretest", "postbuild").forEach {
        rejects("lifecycle script $it") { extract(archive(entries(scripts = JSONObject().put(it, "echo forbidden")))) }
    }
    rejects("script must be a string") { extract(archive(entries(scripts = JSONObject().put("test", 42)))) }
    rejects("package metadata version mismatch") { extract(archive(entries("0.3.0"))) }
    rejects("missing entry") { extract(archive(entries().dropLast(1))) }
    rejects("DSH entry mismatch") {
        val bad = JSONObject(String(metadata())).getJSONObject("dsh").put("entry", "./other.js")
        val meta = JSONObject(String(metadata())).put("dsh", bad).toString().toByteArray()
        extract(archive(listOf(Entry("package/package.json", meta)) + entries().drop(1)))
    }
    rejects("oversized package metadata") {
        extract(archive(listOf(Entry("package/package.json", ByteArray(65537))) + entries().drop(1)))
    }
    pass("exactly 1024 archive entries") {
        extract(archive(entries() + (1..1021).map { Entry("package/f$it") }))
    }
    rejects("1025 archive entries") { extract(archive(entries() + (1..1022).map { Entry("package/f$it") })) }
    rejects("decompression exceeds 32 MiB") {
        extract(archive(entries() + Entry("package/bomb", ByteArray(32 * 1024 * 1024))))
    }
    rejects("corrupted gzip trailer even with matching SHA") {
        extract(valid.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
    }
    rejects("nonempty extraction staging directory") {
        val stage = File(fixtures, "occupied").apply { mkdir(); File(this, "old").writeText("keep") }
        ConnectionPackageValidator.extract(parse(manifest(valid)), valid, stage)
    }

    val store = File(fixtures, "private").apply { mkdir() }
    val assets = File(fixtures, "assets").apply { mkdir() }
    val manager = ConnectionPluginPackage(Context(store, assets))
    pass("empty store has no prepared package") { check(manager.current() == null && manager.currentVersion() == null) }
    val v1 = parse(manifest(valid))
    val oldPath = manager.install(v1, valid)
    val receiptFile = File(store, "connection-plugin/prepared.json")
    val oldReceipt = receiptFile.readBytes()
    pass("receipt declares prepared, not runtime loaded") {
        check(JSONObject(receiptFile.readText()).getString("state") == "prepared")
        check(manager.currentVersion() == "0.2.0")
    }
    val nextBytes = archive(entries("0.3.0"))
    val nextManifest = parse(manifest(nextBytes, "0.3.0"))
    rejects("failed update hash") { manager.install(nextManifest, nextBytes.copyOf().also { it[0] = 0 }) }
    pass("failed hash retains previous package and receipt") {
        check(manager.current() == oldPath && oldReceipt.contentEquals(receiptFile.readBytes()))
    }
    val malicious = archive(entries("0.3.0") + Entry("package/../outside", byteArrayOf(1)))
    rejects("failed update extraction") { manager.install(parse(manifest(malicious, "0.3.0")), malicious) }
    pass("failed extraction retains old state and removes staging") {
        check(manager.current() == oldPath && oldReceipt.contentEquals(receiptFile.readBytes()))
        check(File(store, "connection-plugin").listFiles()!!.none { it.name.startsWith("stage-") })
        check(!File(store, "connection-plugin/outside").exists())
    }
    AtomicFile.failFinish = true
    rejects("receipt commit failure") { manager.install(nextManifest, nextBytes) }
    AtomicFile.failFinish = false
    pass("commit failure retains old receipt and readable package") {
        check(oldReceipt.contentEquals(receiptFile.readBytes()) && manager.current() == oldPath)
        check(!File(receiptFile.path + ".new").exists())
    }
    AtomicFile.failStart = true
    rejects("receipt start failure") { manager.install(nextManifest, nextBytes) }
    AtomicFile.failStart = false
    pass("start failure retains old state") {
        check(oldReceipt.contentEquals(receiptFile.readBytes()) && manager.current() == oldPath)
    }
    val nextPath = manager.install(nextManifest, nextBytes)
    pass("successful preparation retains previous immutable slot") {
        check(manager.current() == nextPath && oldPath.isDirectory && manager.currentVersion() == "0.3.0")
    }
    pass("same version preparation preserves rollback target") {
        manager.install(nextManifest, nextBytes)
        check(JSONObject(receiptFile.readText()).getJSONObject("previous").getString("version") == "0.2.0")
    }
    pass("rollback to previous prepared package") {
        check(manager.rollbackPrepared() == "0.2.0" && manager.current() == oldPath)
    }
    pass("rollback survives a recreated manager") {
        val reopened = ConnectionPluginPackage(Context(store, assets))
        check(reopened.currentVersion() == "0.2.0")
        check(reopened.rollbackPrepared() == "0.3.0" && reopened.current() == nextPath)
    }
    val receiptBeforeRollback = receiptFile.readBytes()
    AtomicFile.failFinish = true
    rejects("rollback commit failure") { runBlocking { manager.rollbackPrepared() } }
    AtomicFile.failFinish = false
    pass("failed rollback keeps current preparation") {
        check(receiptBeforeRollback.contentEquals(receiptFile.readBytes()) && manager.current() == nextPath)
    }
    val entryFile = File(nextPath, "lib/index.js")
    val originalEntry = entryFile.readBytes()
    entryFile.writeText("tampered")
    rejects("current rejects modified extracted bytes") { manager.current() }
    entryFile.writeBytes(originalEntry)
    val extra = File(nextPath, "lib/injected.js").apply { writeText("injected") }
    rejects("current rejects unarchived files") { manager.current() }
    extra.delete()
    pass("unchanged full tree accepted after restart") {
        check(ConnectionPluginPackage(Context(store, assets)).current() == nextPath)
    }
    pass("AtomicFile backup recovered when base is absent") {
        val backup = File(receiptFile.path + ".bak")
        receiptFile.copyTo(backup)
        receiptFile.delete()
        check(manager.current() == nextPath)
    }
    val bundleDirectory = File(assets, "connection-plugin").apply { mkdir() }
    File(bundleDirectory, "manifest.json").writeBytes(approved.bytes)
    frozen.copyTo(File(bundleDirectory, approved.fileName))
    val bundledStore = File(fixtures, "bundled-private").apply { mkdir() }
    val bundled = ConnectionPluginPackage(Context(bundledStore, assets))
    pass("ensureBundled prepares verified frozen assets") {
        val preparedPath = bundled.ensureBundled()
        check(preparedPath.isDirectory && bundled.currentVersion() == approved.version)
    }
    pass("ensureBundled reuses current immutable slot") {
        check(bundled.ensureBundled() == bundled.current())
    }
    pass("no rollback available for first preparation") { check(bundled.rollbackPrepared() == null) }
    val firstReceipt = File(bundledStore, "connection-plugin/prepared.json").readBytes()
    rejects("invalid package scripts cannot replace an existing preparation") {
        val scriptAttack = archive(entries("0.3.0", JSONObject().put("postinstall", "launch payload")))
        bundled.install(parse(manifest(scriptAttack, "0.3.0")), scriptAttack)
    }
    pass("lifecycle failure retains old preparation receipt") {
        check(firstReceipt.contentEquals(File(bundledStore, "connection-plugin/prepared.json").readBytes()))
        check(bundled.currentVersion() == approved.version)
    }
    pass("host exact bundled version replaces an old prepared package without confusing it with loaded state") {
        bundled.install(v1, valid)
        check(bundled.currentVersion() == "0.2.0")
        val required = bundled.ensureBundled("0.3.0")
        check(required.parentFile.name == approved.sha256 && bundled.currentVersion() == "0.3.0")
        check(bundled.ensureBundled("0.3.0") == required)
    }
    rejects("host cannot load a different bundled protocol version") { runBlocking { bundled.ensureBundled("0.4.0") } }
    println("PASS $checks connection package checks (offline; no runtime load or TLS/E2E claim)")
}
