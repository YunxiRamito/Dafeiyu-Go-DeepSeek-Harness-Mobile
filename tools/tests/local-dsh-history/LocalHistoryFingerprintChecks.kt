package com.dafeiyu.mobile.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Storage fixture reproduces both real wire envelopes: the writer receives
 * header/events, and the reader returns inheritedEventCount as well. */
private class FingerprintBackend : LocalDshHistoryBackend {
    val artifacts = linkedMapOf<String, JSONObject>()
    override suspend fun list() = artifacts.values.map { JSONObject().put("header", it.getJSONObject("header")) }
    override suspend fun read(id: String): JSONObject = JSONObject(artifacts.getValue(id).toString()).also {
        if (!it.has("inheritedEventCount")) it.put("inheritedEventCount", 0)
    }
    override suspend fun write(artifact: JSONObject): JSONObject {
        val id = artifact.getJSONObject("header").getString("id")
        artifacts[id] = JSONObject(artifact.toString())
        return JSONObject().put("sessionId", id)
    }
    override suspend fun deleteHistory(frame: JSONObject): JSONObject {
        val ids = frame.getJSONArray("sessionIds").let { array -> (0 until array.length()).map(array::getString) }
        ids.forEach(artifacts::remove)
        return JSONObject().put("requestId", frame.getString("requestId")).put("nativeId", frame.getString("nativeId"))
            .put("deleted", true).put("status", "complete").put("executionDevice", "local_phone")
            .put("items", JSONArray(ids.map { id -> JSONObject().put("sessionId", id).put("status", "deleted")
                .put("ticket", "ticket-$id") }))
    }
}

private data class FingerprintFixture(val context: Context, val store: WorkspaceStore,
    val backend: FingerprintBackend, val snapshot: WorkspaceSnapshot, val ids: List<String>, val keepSessionId: String) {
    val directory get() = File(context.filesDir, "local-dsh-history")
    fun controller() = LocalDshHistoryController(directory, backend, { 5000L }, { store.initialize(5000L) })
    fun journalFile() = RemoteDshJsonFile(File(directory, "links.json"), 4 * 1024 * 1024)
}

private suspend fun fixture(root: File, name: String, versions: Int): FingerprintFixture {
    val context = Context(File(root, "$name/files").apply { check(mkdirs()) })
    val store = WorkspaceStore(context)
    val backend = FingerprintBackend()
    val controller = LocalDshHistoryController(File(context.filesDir, "local-dsh-history"), backend,
        { 1000L }, { store.initialize(5000L) })
    var snapshot = WorkspaceSnapshot(listOf(Workspace("w", "workspace")),
        listOf(Conversation("native-delete", "Native history", "w"), Conversation("native-keep", "Unrelated history", "w")),
        mapOf("native-delete" to listOf(ChatMessage(1, "user", "question"), ChatMessage(2, "assistant", "answer")),
            "native-keep" to listOf(ChatMessage(20, "user", "unrelated question"), ChatMessage(21, "assistant", "unrelated answer"))),
        "w", "native-delete")
    val ids = mutableListOf<String>()
    for (version in 1..versions) {
        if (version > 1) snapshot = snapshot.copy(messages = snapshot.messages + ("native-delete" to
            (snapshot.messages.getValue("native-delete") + ChatMessage((version * 2 - 1).toLong(), "user", "question $version") +
                ChatMessage((version * 2).toLong(), "assistant", "answer $version"))))
        store.save(snapshot)
        val publication = controller.publish(snapshot)
        check(publication.warnings.isEmpty())
        ids += checkNotNull(publication.selectedSessionId)
    }
    check(ids.distinct().size == versions && backend.artifacts.size == versions + 1)
    return FingerprintFixture(context, store, backend, snapshot, ids,
        backend.artifacts.keys.single { it !in ids })
}

private fun editAssistant(artifact: JSONObject, text: String) {
    artifact.getJSONArray("events").remoteObjects(1000).last { it.getString("type") == "assistant/message" }
        .getJSONObject("data").getJSONObject("message").getJSONArray("content").getJSONObject(0).put("text", text)
}

fun main(args: Array<String>) = runBlocking {
    val root = File(args.single()).apply { check(mkdirs()) }
    var passed = 0
    fun pass(value: String) { passed++; println("PASS $value") }

    val shared = fixture(root, "shared", 3)
    var journal = checkNotNull(shared.journalFile().read())
    for (id in shared.ids + shared.keepSessionId) {
        check(shared.backend.artifacts.getValue(id).keys().asSequence().toSet() == setOf("header", "events"))
        val saved = journal.getJSONObject("links").getJSONObject(id)
        check(saved.getInt("artifactFingerprintVersion") == 1 && saved.getString("artifactFingerprint") ==
            remoteJsonHash(shared.backend.read(id)))
    }
    val firstRead = shared.controller().read(shared.snapshot)
    check(firstRead.warnings.isEmpty() && firstRead.snapshot == shared.snapshot)
    check(firstRead.journal.getJSONObject("links").remoteKeys().size == 4)
    pass("new publication stores a versioned logical digest matching the real read envelope; three unchanged versions never fork")

    // These simulate exactly the two legacy wire digests, not relaxed subset
    // checks or a manually accepted checksum from unrelated history.
    journal = checkNotNull(shared.journalFile().read())
    val links = journal.getJSONObject("links")
    links.getJSONObject(shared.ids[0]).remove("artifactFingerprintVersion")
    links.getJSONObject(shared.ids[0]).put("artifactFingerprint", remoteJsonHash(shared.backend.artifacts.getValue(shared.ids[0])))
    links.getJSONObject(shared.ids[1]).remove("artifactFingerprintVersion")
    links.getJSONObject(shared.ids[1]).put("artifactFingerprint", remoteJsonHash(shared.backend.read(shared.ids[1])))
    shared.journalFile().write(journal)
    val migrated = shared.controller().read(shared.snapshot)
    check(migrated.warnings.isEmpty() && migrated.snapshot == shared.snapshot)
    for (id in shared.ids) {
        val record = migrated.journal.getJSONObject("links").getJSONObject(id)
        check(record.getString("nativeId") == "native-delete" && record.getInt("artifactFingerprintVersion") == 1 &&
            record.getString("artifactFingerprint") == remoteJsonHash(shared.backend.read(id)))
    }
    check(!checkNotNull(shared.journalFile().read()).getJSONObject("links").getJSONObject(shared.ids[0]).has("artifactFingerprintVersion"))
    pass("exact legacy write and full-read digests migrate in the staged journal while preserving every original nativeId")
    shared.store.save(migrated.snapshot); shared.controller().acknowledge(migrated)
    val cold = shared.controller().read(checkNotNull(WorkspaceStore(shared.context).initialize()))
    check(cold.warnings.isEmpty() && cold.snapshot == shared.snapshot)
    check(shared.ids.all { cold.journal.getJSONObject("links").getJSONObject(it).getString("nativeId") == "native-delete" })
    pass("migration commits only after durable phone history and a cold multi-version reader leaves the native records exactly unchanged")

    val archived = shared.snapshot.copy(conversations = shared.snapshot.conversations.map {
        if (it.id == "native-delete") it.copy(archivedAtMillis = 0) else it
    }, selectedConversationId = "native-keep")
    shared.store.save(archived)
    val intent = shared.controller().preparePermanentDeletion(archived.conversations.first(), "all-migrated-versions")
    check(intent.sharedSessionIds.toSet() == shared.ids.toSet())
    val receipt = shared.controller().deletePermanentHistory(intent)
    shared.store.commitPermanentDeletion(receipt); shared.controller().acknowledgePermanentDeletion(receipt)
    check(shared.backend.artifacts.keys == setOf(shared.keepSessionId))
    val afterDeletion = checkNotNull(WorkspaceStore(shared.context).initialize())
    check(shared.controller().read(afterDeletion).snapshot == afterDeletion && afterDeletion.conversations.single().id == "native-keep")
    pass("permanent deletion after legacy migration still binds all three versions and preserves the unrelated native and physical fixture history")

    val inherited = fixture(root, "inherited-full", 1)
    inherited.backend.artifacts.getValue(inherited.ids.single()).put("inheritedEventCount", 2)
    journal = checkNotNull(inherited.journalFile().read())
    journal.getJSONObject("links").getJSONObject(inherited.ids.single()).apply {
        remove("artifactFingerprintVersion")
        put("artifactFingerprint", remoteJsonHash(inherited.backend.read(inherited.ids.single())))
    }
    inherited.journalFile().write(journal)
    val inheritedRead = inherited.controller().read(inherited.snapshot)
    check(inheritedRead.warnings.isEmpty() && inheritedRead.snapshot == inherited.snapshot)
    val countTwoDigest = inheritedRead.journal.getJSONObject("links").getJSONObject(inherited.ids.single()).getString("artifactFingerprint")
    check(countTwoDigest == remoteJsonHash(inherited.backend.read(inherited.ids.single())))
    inherited.store.save(inheritedRead.snapshot); inherited.controller().acknowledge(inheritedRead)
    inherited.backend.artifacts.getValue(inherited.ids.single()).put("inheritedEventCount", 3)
    val changedBoundary = inherited.controller().read(inherited.snapshot)
    val countThreeDigest = changedBoundary.journal.getJSONObject("links").getJSONObject(inherited.ids.single()).getString("artifactFingerprint")
    check(countThreeDigest != countTwoDigest && countThreeDigest == remoteJsonHash(inherited.backend.read(inherited.ids.single())))
    pass("exact legacy full-read migration retains nonzero inherited boundaries and a later boundary change produces a distinct digest")

    val unsafeLegacy = fixture(root, "inherited-write", 1)
    journal = checkNotNull(unsafeLegacy.journalFile().read())
    journal.getJSONObject("links").getJSONObject(unsafeLegacy.ids.single()).apply {
        remove("artifactFingerprintVersion")
        put("artifactFingerprint", remoteJsonHash(unsafeLegacy.backend.artifacts.getValue(unsafeLegacy.ids.single())))
    }
    unsafeLegacy.journalFile().write(journal)
    unsafeLegacy.backend.artifacts.getValue(unsafeLegacy.ids.single()).put("inheritedEventCount", 2)
    val editedNative = unsafeLegacy.snapshot.copy(messages = unsafeLegacy.snapshot.messages + ("native-delete" to
        (unsafeLegacy.snapshot.messages.getValue("native-delete") + ChatMessage(3, "user", "new native question") +
            ChatMessage(4, "assistant", "new native answer"))))
    val boundaryConflict = unsafeLegacy.controller().read(editedNative)
    check(boundaryConflict.warnings.any { it.contains("两个版本") } && boundaryConflict.snapshot.conversations.size == 3)
    check(boundaryConflict.snapshot.messages.getValue("native-delete") == editedNative.messages.getValue("native-delete"))
    check(boundaryConflict.journal.getJSONObject("links").getJSONObject(unsafeLegacy.ids.single()).getString("nativeId") != "native-delete")
    pass("a legacy write digest cannot hide a nonzero inherited boundary; concurrent native edits retain a separate DSH version")

    for (fault in listOf("future-version", "corrupt-digest", "unknown-envelope", "invalid-boundary")) {
        val invalid = fixture(root, fault, 1)
        journal = checkNotNull(invalid.journalFile().read())
        val record = journal.getJSONObject("links").getJSONObject(invalid.ids.single())
        when (fault) {
            "future-version" -> record.put("artifactFingerprintVersion", 44)
            "corrupt-digest" -> record.put("artifactFingerprint", "invalid")
            "unknown-envelope" -> invalid.backend.artifacts.getValue(invalid.ids.single()).put("futureEnvelopeField", true)
            else -> invalid.backend.artifacts.getValue(invalid.ids.single()).put("inheritedEventCount", 1.5)
        }
        invalid.journalFile().write(journal)
        val rejected = invalid.controller().read(invalid.snapshot)
        check(rejected.warnings.size == 1 && rejected.snapshot == invalid.snapshot &&
            rejected.journal.getJSONObject("links").getJSONObject(invalid.ids.single()).toString() == record.toString())
        if (fault == "future-version" || fault == "corrupt-digest") check(runCatching { invalid.controller().publish(invalid.snapshot) }.isFailure)
    }
    pass("future digest versions, corrupt digests, unknown envelopes and invalid inherited counts fail closed without rebinding native history")

    val edited = fixture(root, "dsh-edit", 1)
    editAssistant(edited.backend.artifacts.getValue(edited.ids.single()), "real DSH edited answer")
    val dshEdit = edited.controller().read(edited.snapshot)
    check(dshEdit.warnings.isEmpty() && dshEdit.snapshot.conversations.map { it.id } == edited.snapshot.conversations.map { it.id })
    check(dshEdit.snapshot.messages.getValue("native-delete").map { it.text } == listOf("question", "real DSH edited answer"))
    check(dshEdit.snapshot.messages.getValue("native-keep") == edited.snapshot.messages.getValue("native-keep"))
    check(dshEdit.journal.getJSONObject("links").getJSONObject(edited.ids.single()).getString("nativeId") == "native-delete")
    pass("a real first-party event edit is imported when native content is unchanged instead of being skipped by envelope normalization")

    val both = fixture(root, "both-edited", 3)
    editAssistant(both.backend.artifacts.getValue(both.ids.first()), "separate DSH edit")
    val bothRead = both.controller().read(both.snapshot)
    check(bothRead.warnings.count { it.contains("两个版本") } == 1 && bothRead.snapshot.conversations.size == 3)
    check(bothRead.snapshot.messages.getValue("native-delete") == both.snapshot.messages.getValue("native-delete"))
    val fork = bothRead.snapshot.conversations.single { it.id !in both.snapshot.conversations.map(Conversation::id) }
    check(bothRead.snapshot.messages.getValue(fork.id).map { it.text } == listOf("question", "separate DSH edit"))
    check(bothRead.journal.getJSONObject("links").getJSONObject(both.ids.first()).getString("nativeId") == fork.id)
    check(both.ids.drop(1).all { bothRead.journal.getJSONObject("links").getJSONObject(it).getString("nativeId") == "native-delete" })
    pass("genuine edits on both sides preserve one explicit conflict while the other unchanged versions keep their original group ownership")
    println("LOCAL_HISTORY_FINGERPRINT_CHECKS=$passed; synthetic persistence envelopes/JVM AtomicFile boundary; paidModelRequests=0")
}

private fun JSONObject.remoteKeys(): List<String> = keys().asSequence().toList()
