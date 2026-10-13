package com.dafeiyu.mobile.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.json.JSONObject

private data class Item(val path: String, val bytes: ByteArray, val mode: Int = 0x1a4,
    val type: Byte = TarConstants.LF_NORMAL, val link: String = "")
private fun tar(items: List<Item>): ByteArray = ByteArrayOutputStream().also { bytes ->
    GZIPOutputStream(bytes).use { gzip -> TarArchiveOutputStream(gzip).use { output ->
        for (item in items) {
            val entry = TarArchiveEntry(item.path, item.type).apply {
                mode = item.mode
                size = if (item.type in setOf(TarConstants.LF_NORMAL, TarConstants.LF_OLDNORM)) item.bytes.size.toLong() else 0
                linkName = item.link
            }
            output.putArchiveEntry(entry)
            if (entry.size > 0) output.write(item.bytes)
            output.closeArchiveEntry()
        }
    } }
}.toByteArray()

fun main(args: Array<String>) {
    val root = File(args[0]).apply { check(mkdirs() || isDirectory) }
    val manifest = JSONObject(File(args[1]).readText())
    val cache = File(args[2])
    var passed = 0
    var candidates = 0
    fun test(name: String, body: () -> Unit) {
        try { body() } catch (error: Throwable) { throw AssertionError(name, error) }
        passed++; println("PASS $name")
    }
    fun destination() = File(root, "candidate-${++candidates}").apply { check(mkdir()) }
    fun extract(bytes: ByteArray, allow: Set<String> = emptySet(), maximum: Long = 1024,
        progress: (Long) -> Unit = {}, checkCancelled: () -> Unit = {}): File = destination().also {
        LinuxArchiveExtractor.extract(bytes.inputStream(), it, maximum, checkCancelled,
            progress = progress, identicalRegularDuplicates = allow)
    }
    fun rejected(body: () -> Unit) { check(runCatching(body).isFailure) }
    val path = "package/dist/index.js"
    val payload = "verified-content".toByteArray()
    val pair = listOf(Item(path, payload), Item(path, payload))
    val allow = setOf(path)
    test("default general extractor rejects even identical regular duplicate entries") {
        rejected { extract(tar(pair)) }
    }
    test("explicit identical-file compatibility consumes both copies without replacing the first") {
        val seen = mutableListOf<Long>()
        val result = extract(tar(pair), allow, progress = seen::add)
        check(File(result, path).readBytes().contentEquals(payload))
        check(seen == listOf(payload.size.toLong(), payload.size * 2L))
    }
    for ((name, second) in listOf(
        "content conflict" to Item(path, "different-bytes!".toByteArray()),
        "size conflict" to Item(path, byteArrayOf(1)),
        "mode conflict" to Item(path, payload, 0x1ed),
        "type conflict" to Item(path, payload, type = TarConstants.LF_OLDNORM),
        "symbolic link" to Item(path, byteArrayOf(), type = TarConstants.LF_SYMLINK, link = "../../../outside"),
        "hard link" to Item(path, byteArrayOf(), type = TarConstants.LF_LINK, link = "package/other"),
        "directory replacement" to Item(path, byteArrayOf(), type = TarConstants.LF_DIR),
    )) test("duplicate $name is refused and cannot overwrite prior content") {
        val result = destination()
        rejected { LinuxArchiveExtractor.extract(tar(listOf(pair.first(), second)).inputStream(), result, 1024, {},
            identicalRegularDuplicates = allow) }
        check(File(result, path).readBytes().contentEquals(payload))
    }
    test("duplicate expansion counts both copies against the locked budget") {
        rejected { extract(tar(pair), allow, maximum = payload.size.toLong()) }
    }
    test("duplicate replay count is bounded") {
        rejected { extract(tar(List(18) { pair.first() }), allow) }
    }
    test("duplicate target changed after initial write is refused without replacement") {
        val result = destination()
        rejected { LinuxArchiveExtractor.extract(tar(pair).inputStream(), result, 1024, {},
            progress = { expanded -> if (expanded == payload.size.toLong()) File(result, path).writeBytes(ByteArray(payload.size)) },
            identicalRegularDuplicates = allow) }
        check(File(result, path).readBytes().contentEquals(ByteArray(payload.size)))
    }
    test("duplicate compatibility leaves path traversal and unlisted paths refused") {
        rejected { extract(tar(listOf(Item("../outside", payload))), setOf("../outside")) }
        rejected { extract(tar(listOf(Item("package/unlisted", payload), Item("package/unlisted", payload))), allow) }
    }
    test("cancellation while consuming a duplicate leaves the first admitted file intact") {
        val result = destination(); var firstWritten = false
        rejected { LinuxArchiveExtractor.extract(tar(pair).inputStream(), result, 1024,
            { if (firstWritten) throw java.util.concurrent.CancellationException("fixture_cancel") },
            progress = { firstWritten = true }, identicalRegularDuplicates = allow) }
        check(File(result, path).readBytes().contentEquals(payload))
    }
    val entries = manifest.getJSONArray("artifacts")
    val verified = hashSetOf<String>()
    var reviewedDuplicates = 0
    ZipFile(cache).use { zip ->
        for (index in 0 until entries.length()) {
            val item = entries.getJSONObject(index)
            val integrity = item.getString("integrity")
            if (!verified.add(integrity)) continue
            val name = item.getString("name")
            val version = item.getString("version")
            val key = MessageDigest.getInstance("SHA-256").digest(integrity.toByteArray()).joinToString("") { "%02x".format(it) } + ".tgz"
            val entry = zip.entries().asSequence().single { it.name.endsWith("/$key") || it.name == key }
            val archive = File(root, key)
            zip.getInputStream(entry).use { source -> archive.outputStream().use { source.copyTo(it) } }
            val checksum = ArtifactChecksum.parse(integrity)
            val expected = checksum.digestForSize(archive.length()).digest(archive.readBytes()).joinToString("") { "%02x".format(it) }
            check(expected == checksum.value)
            test("fixed published npm archive $name@$version extracts with exact package identity") {
                val duplicatePaths = PiAiNpmArchivePolicy.identicalDuplicatePaths(archive, name, version, integrity) {}
                val result = destination()
                archive.inputStream().use { LinuxArchiveExtractor.extract(it, result, item.getLong("maximumExpandedBytes"), {},
                    identicalRegularDuplicates = duplicatePaths) }
                val children = result.listFiles()!!
                check(children.size == 1 && children.single().name in setOf("package", name.substringAfterLast('/')))
                val metadata = JSONObject(File(children.single(), "package.json").readText())
                check(metadata.getString("name") == name && metadata.getString("version") == version)
                if (duplicatePaths.isNotEmpty()) {
                    reviewedDuplicates++
                    rejected { archive.inputStream().use { LinuxArchiveExtractor.extract(it, destination(), item.getLong("maximumExpandedBytes"), {}) } }
                    check(PiAiNpmArchivePolicy.identicalDuplicatePaths(archive, name, "0.0.0", integrity) {}.isEmpty())
                    check(PiAiNpmArchivePolicy.identicalDuplicatePaths(archive, name, version, "changed") {}.isEmpty())
                    val corrupt = File(root, "corrupt-$key").apply { writeBytes(archive.readBytes().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }) }
                    rejected { PiAiNpmArchivePolicy.identicalDuplicatePaths(corrupt, name, version, integrity) {} }
                    println("EVIDENCE duplicateArchive=$name@$version exactIntegrity=true corruptedArchiveRejected=true defaultStillRejects=true")
                }
            }
        }
    }
    check(verified.size == 85 && reviewedDuplicates == 2)
    println("RESULT $passed/$passed PASS actualProductionExtractor=true fixedNpmArchives=85 exactDuplicateExceptions=2 androidChmod=false workerExecution=false paidRequests=0")
}
