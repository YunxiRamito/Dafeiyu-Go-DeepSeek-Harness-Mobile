package com.dafeiyu.mobile.data

import android.content.Context
import android.system.Os
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

private class DeletionBackend : LocalDshHistoryBackend {
    val artifacts = linkedMapOf<String, JSONObject>()
    var deletionAttempts = 0
    var failAfterPhysicalDelete = false
    var wrongReceipt = false
    override suspend fun list() = artifacts.values.map { JSONObject().put("header", it.getJSONObject("header")) }
    override suspend fun read(id: String) = JSONObject(artifacts.getValue(id).toString())
    override suspend fun write(artifact: JSONObject): JSONObject {
        val id = artifact.getJSONObject("header").getString("id")
        artifacts[id] = JSONObject(artifact.toString())
        return JSONObject().put("sessionId", id)
    }
    override suspend fun deleteHistory(frame: JSONObject): JSONObject {
        deletionAttempts++
        val ids = frame.getJSONArray("sessionIds").let { values -> (0 until values.length()).map(values::getString) }
        ids.forEach(artifacts::remove)
        if (failAfterPhysicalDelete) error("injected physical-success reply-loss")
        return JSONObject().put("requestId", if (wrongReceipt) "wrong" else frame.getString("requestId"))
            .put("nativeId", frame.getString("nativeId")).put("status", "complete").put("deleted", true)
            .put("executionDevice", "local_phone").put("items", JSONArray(ids.map { id ->
                JSONObject().put("sessionId", id).put("status", "deleted").put("ticket", "ticket-$id").put("deletedAt", 1000)
            }))
    }
}

fun main(args: Array<String>) = runBlocking {
    val root = File(args.single()).apply { check(mkdirs()) }
    var passed = 0
    fun pass(text: String) { passed++; println("PASS $text") }
    fun native(): WorkspaceSnapshot = WorkspaceSnapshot(listOf(Workspace("w", "workspace")),
        listOf(Conversation("delete", "delete me", "w"), Conversation("keep", "keep me", "w")),
        mapOf("delete" to listOf(ChatMessage(1, "user", "question"), ChatMessage(2, "assistant", "answer")),
            "keep" to listOf(ChatMessage(3, "user", "unrelated"))), "w", "keep")

    val ctx = Context(File(root, "shared/files").apply { check(mkdirs()) })
    val store = WorkspaceStore(ctx)
    val backend = DeletionBackend()
    val historyDirectory = File(ctx.filesDir, "local-dsh-history")
    fun controller() = LocalDshHistoryController(historyDirectory, backend, { 5000L }, { store.initialize(5000L) })
    val first = native(); store.save(first); controller().publish(first)
    val second = first.copy(messages = first.messages + ("delete" to (first.messages.getValue("delete") + ChatMessage(4, "user", "another version"))))
    store.save(second); controller().publish(second)
    val archived = second.copy(conversations = second.conversations.map { if (it.id == "delete") it.copy(archivedAtMillis = 0L) else it })
    store.save(archived)
    val candidate = archived.conversations.first()
    check(store.initialize(ARCHIVE_RETENTION_MILLIS + 1000L)?.conversations?.contains(candidate) == true)
    check(store.expiredArchiveCandidates(ARCHIVE_RETENTION_MILLIS + 1000L) == listOf(candidate))
    check(store.purgeExpiredArchives(ARCHIVE_RETENTION_MILLIS + 1000L) == 0 && store.initialize()?.conversations?.contains(candidate) == true)
    pass("expired archives remain durable candidates; initialization, save and old sync purge cannot delete them")
    val intent = controller().preparePermanentDeletion(candidate, "stable-confirmation")
    check(intent.sharedSessionIds.size == 2 && intent.requiresDsh && intent.phase == "prepared")
    check(controller().preparePermanentDeletion(candidate, "new-request") == intent)
    check(backend.artifacts.size == 3)
    check(runCatching { controller().publish(archived) }.isFailure && runCatching { controller().read(archived) }.isFailure)
    pass("all exact shared versions are bound by one durable intent; repeated confirmation reuses it and pending exchange is blocked")
    controller().cancelPermanentDeletion(intent)
    val nextIntent = controller().preparePermanentDeletion(candidate, "second-confirmation")
    backend.failAfterPhysicalDelete = true
    check(runCatching { controller().deletePermanentHistory(nextIntent) }.isFailure)
    check(store.initialize()?.conversations?.contains(candidate) == true && controller().pendingPermanentDeletions().single().phase == "deleting")
    check(runCatching { controller().cancelPermanentDeletion(nextIntent) }.isFailure)
    pass("physical success followed by reply loss keeps app history and a stable deleting intent; cancellation cannot abandon it")
    backend.failAfterPhysicalDelete = false
    backend.wrongReceipt = true
    check(runCatching { controller().deletePermanentHistory(nextIntent) }.isFailure && store.initialize()?.conversations?.contains(candidate) == true)
    pass("wrong exact identity receipt never removes the app archive")
    backend.wrongReceipt = false
    val receipt = controller().deletePermanentHistory(nextIntent)
    check(receipt.sharedSessionIds == nextIntent.sharedSessionIds)
    check(runCatching { controller().acknowledgePermanentDeletion(receipt) }.isFailure)
    check(runCatching { store.commitPermanentDeletion(receipt.copy(conversationId = "keep")) }.isFailure)
    pass("native receipt needs exact Store confirmation; ack and forged identity cannot bypass it")
    val receiptFile = RemoteDshJsonFile(File(historyDirectory, "permanent-deletions.json"), 4 * 1024 * 1024)
    val validJournal = checkNotNull(receiptFile.read())
    val corruptJournal = JSONObject(validJournal.toString())
    corruptJournal.getJSONArray("intents").getJSONObject(1).getJSONObject("nativeReceipt")
        .getJSONArray("items").getJSONObject(0).put("status", "prepared")
    receiptFile.write(corruptJournal)
    check(runCatching { store.commitPermanentDeletion(receipt) }.isFailure &&
        runCatching { controller().deletePermanentHistory(nextIntent) }.isFailure &&
        store.initialize()?.conversations?.contains(candidate) == true)
    receiptFile.write(validJournal)
    pass("cold controller and Store validate persisted per-version native receipts, not only the outer completion phase")
    val rawCopies = File(historyDirectory, "artifacts").apply { check(mkdirs()) }
    for (id in nextIntent.sharedSessionIds) {
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        File(rawCopies, "$hash.json").writeText("owned raw history")
        File(rawCopies, "$hash.json.bak").writeText("owned raw backup")
    }
    val transcript = File(ctx.filesDir, "native-model-history").apply { check(mkdirs()) }
    val hash = java.security.MessageDigest.getInstance("SHA-256").digest("delete".toByteArray()).joinToString("") { "%02x".format(it) }
    File(transcript, "$hash.json").writeText("owned protocol")
    File(transcript, "$hash.json.bak").writeText("owned protocol backup")
    File(transcript, "unrelated.json").writeText("keep")
    AtomicFile.failStartFor = "workspace-index.json"
    val committed = try { store.commitPermanentDeletion(receipt) } finally { AtomicFile.failStartFor = null }
    check(committed.conversations.none { it.id == "delete" } && store.hasRecovery())
    Os.failFsync = true
    try { check(runCatching { controller().acknowledgePermanentDeletion(receipt) }.isFailure) }
    finally { Os.failFsync = false }
    check(controller().pendingPermanentDeletions().single().phase == "native_deleted")
    controller().acknowledgePermanentDeletion(receipt)
    check(Os.directorySyncs == 3)
    check(rawCopies.listFiles()!!.isEmpty() && !File(transcript, "$hash.json").exists() && !File(transcript, "$hash.json.bak").exists())
    check(File(transcript, "unrelated.json").isFile)
    pass("recovery commit precedes ack; exact raw history and protocol AtomicFile copies are removed, unrelated copies survive")
    WorkspaceStore(ctx).save(archived)
    val reloaded = WorkspaceStore(ctx).initialize()
    check(reloaded!!.conversations.none { it.id == "delete" } && reloaded.messages["delete"] == null && reloaded.selectedConversationId == "keep")
    check(controller().read(reloaded).snapshot == reloaded && backend.artifacts.size == 1)
    check(store.commitPermanentDeletion(receipt).conversations.none { it.id == "delete" })
    pass("persistent receipt fence prevents old snapshots and reconstructed history readers from resurrecting deletion")

    val nativeCtx = Context(File(root, "native-only/files").apply { check(mkdirs()) })
    val nativeStore = WorkspaceStore(nativeCtx)
    val noDsh = native().copy(conversations = native().conversations.map { if (it.id == "delete") it.copy(archivedAtMillis = 0L) else it })
    nativeStore.save(noDsh)
    val nativeBackend = DeletionBackend()
    val nativeHistory = LocalDshHistoryController(File(nativeCtx.filesDir, "local-dsh-history"), nativeBackend, { 6000L }, { nativeStore.initialize() })
    val only = nativeHistory.preparePermanentDeletion(noDsh.conversations.first(), "native-only-confirmation")
    check(!only.requiresDsh && only.sharedSessionIds.isEmpty())
    val nativeReceipt = nativeHistory.deletePermanentHistory(only)
    nativeStore.commitPermanentDeletion(nativeReceipt); nativeHistory.acknowledgePermanentDeletion(nativeReceipt)
    check(nativeBackend.deletionAttempts == 0 && nativeStore.initialize()!!.conversations.size == 1)
    pass("native-only archived conversations delete through a durable receipt without requiring an installed DSH slot")
    println("LOCAL_HISTORY_DELETION_CHECKS=$passed; synthetic backend/JVM AtomicFile boundary; paidModelRequests=0")
}
