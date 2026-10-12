package com.dafeiyu.mobile.ui

import com.dafeiyu.mobile.data.OfficialToolContract
import com.dafeiyu.mobile.data.OfficialToolOutput
import com.dafeiyu.mobile.data.OfficialToolOutputStore
import java.io.File
import java.nio.file.Files
import org.json.JSONObject

fun main(args: Array<String>) {
    val root = File(args.single()).also { check(it.mkdirs()) }
    var passed = 0
    var skipped = 0
    fun checkCase(name: String, block: (File, OfficialToolOutputStore) -> Unit) {
        val base = File(root, "case-${passed + skipped}").also { check(it.mkdir()) }
        block(base, OfficialToolOutputStore(base) { 1234L })
        passed++
        println("PASS $name")
    }
    fun rejected(block: () -> Unit) {
        check(runCatching(block).isFailure) { "Unsafe or stale output was accepted" }
    }
    fun content(base: File, record: OfficialToolOutput) =
        File(base, "official-tool-outputs/${record.id}/content.txt")
    fun metadata(base: File, record: OfficialToolOutput) =
        File(base, "official-tool-outputs/${record.id}/metadata.json")

    checkCase("CJK multiline emoji exact UTF-8") { base, store ->
        val text = "\u6cf0\u5b89\nline two\t\uD83D\uDE00\r\n"
        val output = store.write("weather.txt", text, "conversation-one")
        check(readGeneratedFilePreview(base, output) == text)
    }
    checkCase("empty file is valid") { base, store ->
        check(readGeneratedFilePreview(base, store.write("empty.txt", "", "one")) == "")
    }
    checkCase("maximum character count") { base, store ->
        val text = "\u4e2d".repeat(OfficialToolContract.MAX_TEXT_CHARACTERS)
        check(readGeneratedFilePreview(base, store.write("large.txt", text, "one")) == text)
    }
    checkCase("metadata association cannot drift") { base, store ->
        val output = store.write("original.txt", "content", "one")
        listOf(output.copy(filename = "other.txt"), output.copy(sizeBytes = 0),
            output.copy(createdAtMillis = 99), output.copy(conversationId = "other"),
            output.copy(mimeType = "text/html")).forEach { altered ->
            rejected { readGeneratedFilePreview(base, altered) }
        }
    }
    checkCase("malformed IDs and missing output rejected") { base, store ->
        val output = store.write("text.txt", "content", "one")
        listOf("../external", output.id + "/..", "00000000-0000-0000-0000-000000000000").forEach {
            rejected { readGeneratedFilePreview(base, output.copy(id = it)) }
        }
    }
    checkCase("missing content rejected") { base, store ->
        val output = store.write("text.txt", "content", "one")
        check(content(base, output).delete())
        rejected { readGeneratedFilePreview(base, output) }
    }
    checkCase("missing metadata rejected") { base, store ->
        val output = store.write("text.txt", "content", "one")
        check(metadata(base, output).delete())
        rejected { readGeneratedFilePreview(base, output) }
    }
    checkCase("invalid UTF-8 rejected at same size") { base, store ->
        val output = store.write("text.txt", "ab", "one")
        content(base, output).writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        rejected { readGeneratedFilePreview(base, output) }
    }
    checkCase("content size mismatch rejected") { base, store ->
        val output = store.write("text.txt", "ab", "one")
        content(base, output).writeText("abc")
        rejected { readGeneratedFilePreview(base, output) }
    }
    checkCase("oversized stored bytes rejected") { base, store ->
        val output = store.write("text.txt", "a", "one")
        content(base, output).writeBytes(ByteArray(OfficialToolContract.MAX_TEXT_BYTES + 1) { 65 })
        rejected { readGeneratedFilePreview(base, output) }
    }
    checkCase("oversized metadata rejects before reading") { base, store ->
        val output = store.write("text.txt", "a", "one")
        metadata(base, output).writeText(" ".repeat(8193))
        rejected { readGeneratedFilePreview(base, output) }
    }
    checkCase("unsafe filename in metadata rejected") { base, store ->
        val output = store.write("text.txt", "a", "one")
        val file = metadata(base, output)
        file.writeText(JSONObject(file.readText()).put("filename", "../outside.txt").toString())
        rejected { readGeneratedFilePreview(base, output) }
    }
    checkCase("character limit survives valid byte count") { base, store ->
        val output = store.write("text.txt", "a", "one")
        val text = "a".repeat(OfficialToolContract.MAX_TEXT_CHARACTERS + 1)
        content(base, output).writeText(text)
        val file = metadata(base, output)
        file.writeText(JSONObject(file.readText()).put("size_bytes", text.length).toString())
        rejected { readGeneratedFilePreview(base, output.copy(sizeBytes = text.length.toLong())) }
    }
    checkCase("unsafe stored control characters rejected") { base, store ->
        val output = store.write("text.txt", "a", "one")
        content(base, output).writeBytes(byteArrayOf(0))
        rejected { readGeneratedFilePreview(base, output) }
    }
    checkCase("caller-provided negative or oversized size rejected") { base, store ->
        val output = store.write("text.txt", "a", "one")
        listOf(-1L, OfficialToolContract.MAX_TEXT_BYTES.toLong() + 1).forEach {
            rejected { readGeneratedFilePreview(base, output.copy(sizeBytes = it)) }
        }
    }
    val base = File(root, "symlink-case").also { check(it.mkdir()) }
    val store = OfficialToolOutputStore(base) { 1234L }
    val output = store.write("text.txt", "inside", "one")
    val outside = File(root, "outside.txt").also { it.writeText("outside") }
    check(content(base, output).delete())
    val linked = runCatching { Files.createSymbolicLink(content(base, output).toPath(), outside.toPath()) }
    if (linked.isSuccess) {
        rejected { readGeneratedFilePreview(base, output) }
        check(outside.readText() == "outside")
        passed++
        println("PASS symlink escape rejected")
    } else {
        skipped++
        println("SKIP symlink fixture: host cannot create symbolic links (${linked.exceptionOrNull()?.javaClass?.simpleName})")
    }
    println("PASS generated file preview: $passed cases, $skipped skipped")
}
