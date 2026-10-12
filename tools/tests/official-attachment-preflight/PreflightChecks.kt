package com.dafeiyu.mobile.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) = runBlocking {
    val root = File(args.single()).also { check(it.mkdirs()) }
    val resolver = ContentResolver()
    val store = ConversationAttachmentStore(Context(root, resolver))
    var keyReads = 0
    var networkCalls = 0
    val client = DeepSeekChatClient(
        { keyReads++; error("Preflight read credentials") },
        DeepSeekChatClient.Config(),
        { networkCalls++; error("Preflight opened a connection") },
    )
    val flash = ModelChoice("deepseek-flash", "DeepSeek", "Flash", emptyList(), -1, false)
    fun fixture() = PreflightSubject(store, client)
    fun image(bytes: ByteArray, mime: String = "image/png"): ConversationAttachment {
        val uri = Uri.parse("content://fixture/${UUID.randomUUID()}")
        resolver.sources[uri.toString()] = ContentResolver.Source("image", mime, bytes.size.toLong()) {
            bytes.inputStream()
        }
        return store.importAttachment(uri)
    }
    fun png(size: Int) = ByteArray(size).also {
        byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10).copyInto(it)
    }
    val reducer = AssistantStreamReducer(source = AssistantStreamSource.BACKEND)
    reducer.apply(AssistantStreamEvent.MessageStarted(0, "answer"))
    reducer.apply(AssistantStreamEvent.TextDelta(1, "answer", "complete answer"))
    reducer.apply(AssistantStreamEvent.MessageCompleted(2, "answer"))
    val complete = reducer.finish()
    fun prior(file: ConversationAttachment) = listOf(
        ChatMessage(1, "user", "old image", attachmentIds = listOf(file.id)),
        ChatMessage(2, "assistant", "complete answer", complete),
    )
    var passed = 0
    suspend fun case(name: String, action: suspend () -> Unit) {
        action()
        passed++
        println("PASS $name")
    }
    case("exact-model-and-current-catalog-modality-gates") {
        val subject = fixture()
        check(subject.acceptsImages(flash))
        check(subject.acceptsImages(flash.copy(id = "deepseek-v4-flash-vision-exp")))
        check(!subject.acceptsImages(flash.copy(id = "deepseek-v4-pro")))
        check(!subject.acceptsImages(flash.copy(id = "guessed-vision")))
        check(subject.acceptsImages(flash.copy(id = "catalog-confirmed", inputModalities = listOf("image"))))
        check(!subject.acceptsImages(flash.copy(isExample = true, inputModalities = listOf("image"))))
    }
    case("four-image-formats-full-production-preflight") {
        val subject = fixture()
        val files = listOf(
            image(png(8)),
            image(byteArrayOf(255.toByte(), 216.toByte(), 255.toByte()), "image/jpeg"),
            image("GIF89a".toByteArray(), "image/gif"),
            image("RIFFabcdWEBP".toByteArray(), "image/webp"),
        )
        files.forEach(subject::register)
        val candidate = ChatMessage(3, "user", "describe", attachmentIds = files.map { it.id })
        check(subject.preflight(candidate, flash))
        check(subject.result.last().images.size == 4)
        check(subject.result.dropLast(1).all { it.images.isEmpty() })
    }
    case("required-history-image-gates-text-only-candidate") {
        val subject = fixture()
        val old = image(png(8)).also(subject::register)
        subject.conversationMessages["conversation"] = prior(old)
        val candidate = ChatMessage(3, "user", "follow-up")
        check(!subject.preflight(candidate, flash.copy(id = "deepseek-v4-pro")))
        check(subject.preflight(candidate, flash))
        check(subject.result.filter { it.role == "user" }.first().images.size == 1)
        check(subject.result.last().images.isEmpty())
    }
    case("filtered-demo-image-is-not-required-or-read") {
        val subject = fixture()
        val large = image(png(2_800_001)).also(subject::register)
        val demo = complete.copy(source = AssistantStreamSource.DEMO)
        subject.conversationMessages["conversation"] = listOf(
            ChatMessage(1, "user", "demo", attachmentIds = listOf(large.id)),
            ChatMessage(2, "assistant", "demo answer", demo),
        )
        check(subject.preflight(ChatMessage(3, "user", "new"), flash.copy(id = "deepseek-v4-pro")))
        check(subject.result.all { it.images.isEmpty() })
    }
    case("raw-limit-exact-pass-overflow-retains-input-and-files") {
        val subject = fixture()
        val exact = image(png(2_800_000)).also(subject::register)
        val candidate = ChatMessage(3, "user", "describe", attachmentIds = listOf(exact.id))
        check(subject.preflight(candidate, flash))
        val large = image(png(2_800_001)).also(subject::register)
        val rejected = candidate.copy(attachmentIds = listOf(large.id))
        check(!subject.preflight(rejected, flash))
        check(rejected.attachmentIds == listOf(large.id) && store.load(large.id) != null)
        check(subject.notices.last().contains("4 MiB"))
    }
    case("complete-json-not-raw-budget-decides-final-fit") {
        val subject = fixture()
        val file = image(png(2_800_000)).also(subject::register)
        val candidate = ChatMessage(3, "user", "describe", modelInput = "a".repeat(600_000),
            attachmentIds = listOf(file.id))
        check(!subject.preflight(candidate, flash))
        check(candidate.modelInput!!.length == 600_000 && store.load(file.id) != null)
    }
    case("reused-id-budget-dedup-but-serialized-occurrences-count") {
        val subject = fixture()
        val file = image(png(1_600_000)).also(subject::register)
        subject.conversationMessages["conversation"] = prior(file)
        val candidate = ChatMessage(3, "user", "same again", attachmentIds = listOf(file.id))
        check(!subject.preflight(candidate, flash))
        check(store.load(file.id) != null)
    }
    case("seven-distinct-context-images-fail-with-files-preserved") {
        val subject = fixture()
        val files = List(7) { image(png(8)).also(subject::register) }
        subject.conversationMessages["conversation"] = files.take(6).flatMapIndexed { index, file ->
            listOf(
                ChatMessage(index * 2L, "user", "image", attachmentIds = listOf(file.id)),
                ChatMessage(index * 2L + 1, "assistant", "answer", complete),
            )
        }
        check(!subject.preflight(ChatMessage(20, "user", "last", attachmentIds = listOf(files.last().id)), flash))
        check(files.all { store.load(it.id) != null })
    }
    case("changed-magic-or-missing-file-fails-without-network") {
        val subject = fixture()
        val file = image(png(8)).also(subject::register)
        store.resolveFile(file.id).writeBytes(ByteArray(8))
        check(!subject.preflight(ChatMessage(3, "user", "image", attachmentIds = listOf(file.id)), flash))
        store.delete(file.id)
        check(!subject.preflight(ChatMessage(3, "user", "image", attachmentIds = listOf(file.id)), flash))
    }
    case("missing-catalog-history-reference-fails-before-model-or-transport") {
        val subject = fixture()
        val file = image(png(8))
        val history = prior(file)
        subject.conversationMessages["conversation"] = history
        val candidate = ChatMessage(3, "user", "follow-up", modelInput = "draft input")
        for (model in listOf(flash, flash.copy(id = "deepseek-v4-pro"))) {
            check(!subject.preflight(candidate, model))
            check(subject.result.isEmpty() && subject.notices.last().contains("草稿已保留"))
            check(subject.conversationMessages["conversation"] == history)
            check(candidate.modelInput == "draft input" && store.load(file.id) != null)
        }
    }
    case("missing-catalog-current-reference-fails-and-preserves-attachment") {
        val subject = fixture()
        val file = image(png(8))
        val candidate = ChatMessage(3, "user", "draft", attachmentIds = listOf(file.id))
        check(!subject.preflight(candidate, flash))
        check(candidate.attachmentIds == listOf(file.id) && store.load(file.id) != null)
        check(subject.result.isEmpty() && subject.notices.last().contains("草稿已保留"))
    }
    case("excluded-demo-or-incomplete-missing-reference-does-not-block") {
        for (snapshot in listOf(complete.copy(source = AssistantStreamSource.DEMO),
            complete.copy(isFinal = false))) {
            val subject = fixture()
            subject.conversationMessages["conversation"] = listOf(
                ChatMessage(1, "user", "excluded", attachmentIds = listOf("missing")),
                ChatMessage(2, "assistant", "excluded answer", snapshot),
            )
            check(subject.preflight(ChatMessage(3, "user", "new"), flash.copy(id = "deepseek-v4-pro")))
            check(subject.result.none { it.images.isNotEmpty() || it.content == "excluded" })
        }
    }
    case("known-text-reference-remains-text-only-and-preserves-model-input") {
        val subject = fixture()
        subject.importedAttachments["text-id"] = AttachmentPreview("text-id", "text/plain", 12)
        subject.conversationMessages["conversation"] = listOf(
            ChatMessage(1, "user", "display text", modelInput = "actual attachment text",
                attachmentIds = listOf("text-id")),
            ChatMessage(2, "assistant", "complete answer", complete),
        )
        check(subject.preflight(ChatMessage(3, "user", "new"), flash.copy(id = "deepseek-v4-pro")))
        check(subject.result.first { it.role == "user" }.content == "actual attachment text")
        check(subject.result.all { it.images.isEmpty() })
    }
    case("strict-history-rejects-partial-or-empty-image-map-and-unknown-metadata") {
        fun rejects(code: String, action: () -> Unit) {
            val failure = runCatching(action).exceptionOrNull()
            check(failure is IllegalArgumentException && failure.message == code)
        }
        val user = ChatMessage(1, "user", "image", attachmentIds = listOf("image-id"))
        rejects("attachment_metadata_missing") {
            OfficialChatHistory.build(listOf(user), true, emptyMap(), emptyMap())
        }
        for (imageData in listOf(emptyMap(), mapOf("image-id" to ""))) {
            rejects("attachment_image_missing") {
                OfficialChatHistory.build(listOf(user), true, imageData, mapOf("image-id" to true))
            }
        }
        val mixed = user.copy(attachmentIds = listOf("text-id", "image-id"))
        val history = OfficialChatHistory.build(listOf(mixed), true, mapOf("image-id" to "image-data"),
            mapOf("text-id" to false, "image-id" to true))
        check(history.single().images == listOf("image-data"))
    }
    check(keyReads == 0 && networkCalls == 0)
    println("PASS $passed production preflight scenarios; zero credentials/network")
}
