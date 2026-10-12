package com.dafeiyu.mobile.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.system.measureTimeMillis
import org.json.JSONObject

private class RepeatedStream(private var remaining: Long, private val failAt: Long? = null) : InputStream() {
    private var consumed = 0L
    var closed = false
    override fun read(): Int {
        if (failAt != null && consumed >= failAt) throw IOException("Synthetic provider read failure")
        if (remaining <= 0) return -1
        consumed++
        remaining--
        return 'x'.code
    }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (failAt != null && consumed >= failAt) throw IOException("Synthetic provider read failure")
        if (remaining <= 0) return -1
        val count = minOf(remaining, length.toLong(), failAt?.minus(consumed) ?: Long.MAX_VALUE).toInt()
        bytes.fill('x'.code.toByte(), offset, offset + count)
        consumed += count
        remaining -= count
        return count
    }
    override fun close() { closed = true }
}

fun main(args: Array<String>) {
    val runRoot = File(args.single()).canonicalFile
    check(runRoot.mkdirs())
    var checks = 0
    fun case(name: String, action: (ConversationAttachmentStore, ContentResolver, File) -> Unit) {
        val privateRoot = File(runRoot, "case-${++checks}").apply { check(mkdir()) }
        val resolver = ContentResolver()
        val store = ConversationAttachmentStore(Context(privateRoot, resolver))
        println("RUN $name")
        System.out.flush()
        val elapsedMillis = measureTimeMillis { action(store, resolver, privateRoot) }
        check(resolver.cursors.all { it.closed })
        println("PASS $name (${elapsedMillis}ms)")
    }
    var sourceCounter = 0
    fun source(
        resolver: ContentResolver,
        bytes: ByteArray,
        name: String? = "example.txt",
        mime: String? = "text/plain",
        size: Long? = bytes.size.toLong(),
    ): Uri {
        val uri = Uri.parse("content://fixture/${++sourceCounter}")
        resolver.sources[uri.toString()] = ContentResolver.Source(name, mime, size) { ByteArrayInputStream(bytes) }
        return uri
    }
    fun repeated(
        resolver: ContentResolver,
        size: Long,
        declared: Long? = size,
        mime: String = "application/octet-stream",
        stream: RepeatedStream? = null,
    ): Uri {
        val uri = Uri.parse("content://fixture/${++sourceCounter}")
        resolver.sources[uri.toString()] = ContentResolver.Source("large.bin", mime, declared) {
            stream ?: RepeatedStream(size)
        }
        return uri
    }
    fun rejected(action: () -> Unit) {
        check(runCatching(action).isFailure) { "Operation should have failed" }
    }
    fun noPending(root: File) {
        check(File(root, "conversation-attachments").listFiles().orEmpty().none { it.name.startsWith(".pending-") })
    }
    val ten = ConversationAttachmentStore.MAX_ATTACHMENT_BYTES
    val textLimit = ConversationAttachmentStore.MAX_TEXT_BYTES

    case("image data URLs preserve bytes and supported MIME; complete request is bounded") { store, resolver, _ ->
        val samples = listOf(
            "image/png" to byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10),
            "image/jpeg" to byteArrayOf(255.toByte(), 216.toByte(), 255.toByte(), 1),
            "image/gif" to "GIF89a".toByteArray(),
            "image/webp" to "RIFFabcdWEBP".toByteArray(),
        )
        val attachments = samples.map { (mime, bytes) ->
            store.importAttachment(source(resolver, bytes, "image", mime))
        }
        val dataUrls = store.readImageDataUrls(attachments.map { it.id } + attachments.first().id)
        check(dataUrls.size == 4)
        samples.zip(attachments).forEach { (sample, attachment) ->
            val (mime, bytes) = sample
            val data = dataUrls.getValue(attachment.id)
            check(data.startsWith("data:$mime;base64,") && !data.contains('\n'))
            check(java.util.Base64.getDecoder().decode(data.substringAfter(',')).contentEquals(bytes))
        }
        check(store.readImageDataUrl(attachments.first().id) == dataUrls[attachments.first().id])
        check(store.readImageDataUrls(emptyList()).isEmpty())
        val text = store.importAttachment(source(resolver, byteArrayOf(97)))
        rejected { store.readImageDataUrl(text.id) }
        rejected { store.readImageDataUrl("../escape") }
    }
    case("image preparation rejects MIME spoofing and changed payload on every read") { store, resolver, root ->
        val image = store.importAttachment(source(resolver, byteArrayOf(255.toByte(), 216.toByte(), 255.toByte()), "x", "image/jpeg"))
        val payload = store.resolveFile(image.id)
        payload.writeBytes(byteArrayOf(1, 2, 3))
        rejected { store.readImageDataUrl(image.id) }
        payload.writeBytes(byteArrayOf(255.toByte(), 216.toByte(), 255.toByte()))
        val metadata = File(root, "conversation-attachments/${image.id}/metadata.json")
        val json = JSONObject(metadata.readText()).put("mimeType", "image/png")
        metadata.writeText(json.toString())
        rejected { store.readImageDataUrl(image.id) }
    }
    case("image preparation validates 25 MiB request quota before encoding") { store, resolver, _ ->
        fun png(size: Int): ByteArray = ByteArray(size).also {
            byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10).copyInto(it)
        }
        val images = listOf(10, 10, 6).map {
            store.importAttachment(source(resolver, png(it * 1024 * 1024), "x.png", "image/png"))
        }
        rejected { store.readImageDataUrls(images.map { it.id }) }
        val smaller = (1..7).map {
            store.importAttachment(source(resolver, png(8), "x.png", "image/png"))
        }
        rejected { store.readImageDataUrls(smaller.map { it.id }) }
        check(store.list().size == 10)
    }

    case("UTF-8 SAF copy, safe name, restart metadata and local URI") { store, resolver, root ->
        val bytes = "hello \u4E16\u754C\n".toByteArray(Charsets.UTF_8)
        val uri = source(resolver, bytes, "../../bad\\name\u0000.txt", " Text/Plain; charset=UTF-8 ")
        val attachment = store.importAttachment(uri)
        check(attachment.name.none { it == '/' || it == '\\' || it == '\u0000' })
        check(attachment.mimeType == "text/plain" && attachment.textSupported && !attachment.isImage)
        check(attachment.sizeBytes == bytes.size.toLong())
        check(store.readText(attachment.id) == String(bytes, Charsets.UTF_8))
        check(store.resolveFile(attachment.id).readBytes().contentEquals(bytes))
        val restarted = ConversationAttachmentStore(Context(root, resolver))
        check(restarted.load(attachment.id) == attachment && restarted.list() == listOf(attachment))
        check(attachment.uriString.startsWith("file:"))
        check(!File(attachment.localPath).parentFile.resolve("metadata.json").readText().contains("content://"))
        check(resolver.sources.containsKey(uri.toString()))
    }
    case("missing names and MIME stay opaque; no extension-based text claim") { store, resolver, _ ->
        val attachment = store.importAttachment(source(resolver, byteArrayOf(0, 1), null, null))
        check(attachment.name == "attachment" && attachment.mimeType == "application/octet-stream")
        check(!attachment.textSupported && !attachment.isImage)
        rejected { store.readText(attachment.id) }
    }
    case("PNG metadata persists but image never becomes text input") { store, resolver, root ->
        val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 0)
        val attachment = store.importAttachment(source(resolver, png, "photo.png", "image/png"))
        check(attachment.isImage && !attachment.textSupported)
        check(ConversationAttachmentStore(Context(root, resolver)).load(attachment.id) == attachment)
        rejected { store.readText(attachment.id) }
        rejected { store.readTexts(listOf(attachment.id)) }
    }
    case("MIME mismatch and unsupported executable/image types preserve old files") { store, resolver, root ->
        val prior = store.importAttachment(source(resolver, "prior".toByteArray()))
        listOf("image/png", "application/vnd.android.package-archive", "image/svg+xml", "bad MIME").forEach { mime ->
            rejected { store.importAttachment(source(resolver, "bad".toByteArray(), mime = mime)) }
        }
        check(store.readText(prior.id) == "prior" && store.list().size == 1)
        noPending(root)
    }
    case("10 MiB exact limit; declared and streaming oversized imports roll back") { store, resolver, root ->
        val prior = store.importAttachment(repeated(resolver, ten))
        check(prior.sizeBytes == ten)
        rejected { store.importAttachment(repeated(resolver, ten + 1)) }
        rejected { store.importAttachment(repeated(resolver, ten + 1, declared = null)) }
        rejected { store.importAttachment(repeated(resolver, ten + 1, declared = 1)) }
        check(store.resolveFile(prior.id).length() == ten && store.list().size == 1)
        noPending(root)
    }
    case("25 MiB selection is separate; 128 MiB storage exact limit and deletion restores capacity") { store, resolver, root ->
        val first = store.importAttachment(repeated(resolver, ten))
        repeat(11) { store.importAttachment(repeated(resolver, ten)) }
        store.importAttachment(repeated(resolver, 8L * 1024 * 1024))
        check(store.list().sumOf { it.sizeBytes } == ConversationAttachmentStore.MAX_STORE_BYTES)
        check(ConversationAttachmentStore.MAX_TOTAL_BYTES == 25L * 1024 * 1024)
        rejected { store.importAttachment(repeated(resolver, 1, declared = null)) }
        check(store.delete(first.id) && !store.delete(first.id))
        check(store.importAttachment(repeated(resolver, 1)).sizeBytes == 1L)
        noPending(root)
    }
    case("provider failure closes stream, removes staging, preserves source and old copy") { store, resolver, root ->
        val sourceFile = File(root, "original.txt").apply { writeText("original") }
        val uri = Uri.parse("content://fixture/original")
        resolver.sources[uri.toString()] = ContentResolver.Source("original.txt", "text/plain", sourceFile.length()) {
            sourceFile.inputStream()
        }
        val prior = store.importAttachment(uri)
        val broken = RepeatedStream(12000, failAt = 8192)
        rejected { store.importAttachment(repeated(resolver, 12000, stream = broken)) }
        check(broken.closed && sourceFile.readText() == "original" && store.readText(prior.id) == "original")
        check(store.delete(prior.id) && sourceFile.readText() == "original")
        noPending(root)
    }
    case("strict UTF-8 rejects invalid and binary text; BOM is removed") { store, resolver, root ->
        listOf(
            byteArrayOf(0xC3.toByte(), 0x28), byteArrayOf(0xFF.toByte()), byteArrayOf(65, 0, 66),
            "\u0081".toByteArray(Charsets.UTF_8),
        ).forEach {
            rejected { store.importAttachment(source(resolver, it)) }
        }
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hello".toByteArray()
        check(store.readText(store.importAttachment(source(resolver, bom)).id) == "hello")
        noPending(root)
    }
    case("256 KiB single and 512 KiB aggregate text limits count UTF-8 bytes") { store, resolver, _ ->
        val a = store.importAttachment(repeated(resolver, textLimit, mime = "text/plain"))
        val b = store.importAttachment(repeated(resolver, textLimit, mime = "application/json"))
        val extra = store.importAttachment(source(resolver, "x".toByteArray()))
        val large = store.importAttachment(repeated(resolver, textLimit + 1, mime = "text/plain"))
        check(store.readText(a.id).toByteArray(Charsets.UTF_8).size.toLong() == textLimit)
        check(store.readTexts(listOf(a.id, b.id, a.id)).size == 2)
        rejected { store.readTexts(listOf(a.id, b.id, extra.id)) }
        rejected { store.readText(large.id) }
        val multibyte = store.importAttachment(source(resolver, "\u4E16".repeat(90000).toByteArray()))
        rejected { store.readText(multibyte.id) }
    }
    case("invalid IDs and file/http URIs cannot access outside files") { store, resolver, root ->
        val victim = File(root, "victim").apply { writeText("keep") }
        listOf("../victim", victim.absolutePath, "", "foo", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA").forEach { id ->
            rejected { store.load(id) }
            rejected { store.resolveFile(id) }
            rejected { store.delete(id) }
        }
        rejected { store.importAttachment(Uri.parse("file:///tmp/source")) }
        rejected { store.importAttachment(Uri.parse("https://example.test/file")) }
        rejected { store.importAttachment(Uri.parse("content:/no-authority")) }
        check(victim.readText() == "keep")
        check(store.load("00000000-0000-0000-0000-000000000000") == null)
        check(resolver.sources.isEmpty())
    }
    case("tampered metadata, size and unexpected nested files fail closed") { store, resolver, _ ->
        val attachment = store.importAttachment(source(resolver, "unchanged".toByteArray()))
        val payload = File(attachment.localPath)
        val metadata = File(payload.parentFile, "metadata.json")
        val original = metadata.readText()
        metadata.writeText(JSONObject(original).put("id", "wrong").toString())
        rejected { store.load(attachment.id) }
        metadata.writeText(JSONObject(original).put("isImage", true).toString())
        rejected { store.load(attachment.id) }
        metadata.writeText(original)
        payload.appendText("changed")
        rejected { store.load(attachment.id) }
        val extra = File(payload.parentFile, "unexpected").apply { writeText("keep") }
        rejected { store.delete(attachment.id) }
        check(extra.readText() == "keep" && payload.isFile)
    }
    case("multiple store instances serialize aggregate quota") { store, resolver, root ->
        repeat(11) { store.importAttachment(repeated(resolver, ten)) }
        store.importAttachment(repeated(resolver, 8L * 1024 * 1024))
        val uris = List(4) { repeated(resolver, 5L * 1024 * 1024) }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = uris.map { uri ->
                pool.submit(Callable {
                    runCatching { ConversationAttachmentStore(Context(root, resolver)).importAttachment(uri) }.isSuccess
                })
            }
            check(futures.count { it.get() } == 2)
            check(store.list().sumOf { it.sizeBytes } == ConversationAttachmentStore.MAX_STORE_BYTES)
        } finally {
            pool.shutdownNow()
        }
    }
    case("512 entry storage limit persists and explicit deletion releases an entry") { store, resolver, root ->
        val uri = source(resolver, byteArrayOf())
        val first = store.importAttachment(uri)
        val managedRoot = File(first.localPath).parentFile.parentFile
        val metadata = File(File(first.localPath).parentFile, "metadata.json").readText()
        // Preseed valid persisted entries; importing each one rescans all prior paths.
        repeat(ConversationAttachmentStore.MAX_STORE_ATTACHMENTS - 2) {
            val id = UUID.randomUUID().toString()
            val directory = File(managedRoot, id).apply { check(mkdir()) }
            File(directory, "content").writeBytes(byteArrayOf())
            File(directory, "metadata.json").writeText(JSONObject(metadata).put("id", id).toString())
        }
        check(managedRoot.listFiles()!!.size == ConversationAttachmentStore.MAX_STORE_ATTACHMENTS - 1)
        val last = store.importAttachment(uri)
        check(store.load(last.id) == last)
        rejected { store.importAttachment(uri) }
        check(managedRoot.listFiles()!!.size == ConversationAttachmentStore.MAX_STORE_ATTACHMENTS)
        check(ConversationAttachmentStore(Context(root, resolver)).list().size ==
            ConversationAttachmentStore.MAX_STORE_ATTACHMENTS)
        check(store.delete(first.id))
        check(managedRoot.listFiles()!!.size == ConversationAttachmentStore.MAX_STORE_ATTACHMENTS - 1)
        store.importAttachment(uri)
        check(store.list().size == ConversationAttachmentStore.MAX_STORE_ATTACHMENTS)
        noPending(root)
    }
    case("managed root and payload symlinks cannot escape sandbox") { store, resolver, root ->
        val attachment = store.importAttachment(source(resolver, "safe".toByteArray()))
        val outside = File(root, "outside").apply { writeText("outside survives") }
        val payload = File(attachment.localPath)
        val linkProbe = File(root, "link-probe").toPath()
        val allowed = runCatching { Files.createSymbolicLink(linkProbe, outside.toPath()) }.isSuccess
        if (allowed) {
            Files.delete(linkProbe)
            check(payload.delete())
            Files.createSymbolicLink(payload.toPath(), outside.toPath())
            rejected { store.load(attachment.id) }
            rejected { store.resolveFile(attachment.id) }
            rejected { store.delete(attachment.id) }
            check(outside.readText() == "outside survives")
            val otherRoot = File(root, "other-private").apply { check(mkdir()) }
            Files.createSymbolicLink(File(otherRoot, "conversation-attachments").toPath(), root.toPath())
            val redirected = ConversationAttachmentStore(Context(otherRoot, resolver))
            rejected { redirected.importAttachment(source(resolver, "no".toByteArray())) }
            rejected { redirected.load(attachment.id) }
        } else {
            println("SKIP real symlink fixture: host lacks symlink privilege")
        }
    }
    case("JavaScript code MIME is read as plain UTF-8 and never executed") { store, resolver, _ ->
        val code = "throw new Error('DO NOT EXECUTE');\n"
        val attachment = store.importAttachment(source(resolver, code.toByteArray(), "code.js", "application/javascript"))
        check(attachment.textSupported && store.readText(attachment.id) == code)
    }
    case("octet-stream code requires complete UTF-8 evidence and is revalidated on reopen") { store, resolver, root ->
        val code = "fun main() { println(\"hello\") }\n"
        val attachment = store.importAttachment(source(resolver, code.toByteArray(), "sample.kt", "application/octet-stream"))
        check(attachment.textSupported && !attachment.isImage && attachment.mimeType == "application/octet-stream")
        check(attachment.textEvidence == "code-extension-utf8" && store.readText(attachment.id) == code)
        val reopened = ConversationAttachmentStore(Context(root, resolver))
        check(reopened.load(attachment.id) == attachment)
        val payload = store.resolveFile(attachment.id)
        val changed = payload.readBytes().also { it[it.lastIndex] = 0 }
        payload.writeBytes(changed)
        rejected { reopened.load(attachment.id) }
        rejected { reopened.readText(attachment.id) }
        check(store.delete(attachment.id))
    }
    case("octet-stream invalid binary code and unknown/null MIME remain opaque") { store, resolver, _ ->
        listOf(byteArrayOf(0xFF.toByte(), 1), byteArrayOf(65, 0, 66), byteArrayOf(0xC3.toByte(), 40)).forEach { bytes ->
            val attachment = store.importAttachment(source(resolver, bytes, "sample.kt", "application/octet-stream"))
            check(!attachment.textSupported && attachment.textEvidence == null)
            check(store.load(attachment.id) == attachment)
            rejected { store.readText(attachment.id) }
        }
        listOf("unknown.dat" to "application/octet-stream", "sample.kt" to null).forEach { (name, mime) ->
            val attachment = store.importAttachment(source(resolver, "valid UTF-8".toByteArray(), name, mime))
            check(!attachment.textSupported && attachment.textEvidence == null)
        }
        val uri = repeated(resolver, textLimit + 1)
        resolver.sources[uri.toString()] = resolver.sources.getValue(uri.toString()).copy(name = "too-large.py")
        val large = store.importAttachment(uri)
        check(!large.textSupported)
    }
    println("PASS $checks attachment boundary cases; temporary fixtures: ${runRoot.absolutePath}")
}
