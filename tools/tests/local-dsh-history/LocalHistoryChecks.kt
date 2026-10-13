package com.dafeiyu.mobile.data

import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

private class Storage : LocalDshHistoryBackend {
    val artifacts = linkedMapOf<String, JSONObject>()
    var writes = 0
    var failWrite = false
    var wrongReceipt = false
    override suspend fun list() = artifacts.values.map { JSONObject().put("header", JSONObject(it.getJSONObject("header").toString())) }
    override suspend fun read(id: String) = JSONObject(artifacts.getValue(id).toString())
    override suspend fun write(artifact: JSONObject): JSONObject {
        if (failWrite) error("injected storage failure")
        check(artifact.getJSONArray("events").remoteObjects(120000).none { it.getString("type") in setOf("tool/call", "tool/result") })
        val id = artifact.getJSONObject("header").getString("id")
        if (artifacts.containsKey(id)) check(remoteJsonHash(artifacts.getValue(id)) == remoteJsonHash(artifact))
        artifacts[id] = JSONObject(artifact.toString())
        writes++
        return JSONObject().put("created", true).put("sessionId", if (wrongReceipt) "wrong" else id)
    }
    fun append(id: String, text: String) {
        val artifact = artifacts.getValue(id)
        val events = artifact.getJSONArray("events")
        fun event(type: String, data: JSONObject, surface: Boolean = false) {
            events.put(JSONObject().put("seq", events.length()).put("time", 2000).put("type", type).put("data", data)
                .also { if (surface) it.put("surfaceOp", "append") })
        }
        event("turn/start", JSONObject().put("turn", 10))
        event("step/start", JSONObject().put("turn", 10).put("step", 1))
        event("user/message", JSONObject().put("id", "dsh-user-$text").put("role", "user")
            .put("source", JSONObject().put("kind", "user"))
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "continue $text"))), true)
        event("assistant/message", JSONObject().put("turn", 10).put("step", 1).put("stream", JSONArray())
            .put("message", JSONObject().put("id", "dsh-assistant-$text").put("role", "assistant")
                .put("source", JSONObject().put("kind", "model").put("provider", "fixture").put("model", "offline"))
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))
                    .put(JSONObject().put("type", "reasoning").put("text", "public thinking")))), true)
        event("tool/call", JSONObject().put("callId", "tool-$text").put("name", "read_file").put("arguments", "{\"path\":\"example\"}"))
        event("tool/result", JSONObject().put("turn", 10).put("step", 1).put("message",
            JSONObject().put("id", "dsh-tool-$text").put("role", "tool").put("toolCallId", "tool-$text")
                .put("source", JSONObject().put("kind", "tool"))
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "offline result")))), true)
        event("step/end", JSONObject().put("turn", 10).put("step", 1))
        event("turn/end", JSONObject().put("turn", 10).put("reason", JSONObject().put("kind", "completed")))
        DshSharedHistoryAdapter.decode(artifact)
    }
}

fun main(args: Array<String>) = runBlocking {
    val root = File(args.single()).apply { check(mkdirs() || isDirectory) }
    var checks = 0
    fun pass(label: String) { checks++; println("PASS $label") }
    val native = WorkspaceSnapshot(listOf(Workspace("w", "workspace")), listOf(Conversation("c", "chat", "w")),
        mapOf("c" to listOf(ChatMessage(8, "user", "question", attachmentIds = listOf("owned-attachment")),
            ChatMessage(12, "assistant", "answer"))), "w", "c")
    val storage = Storage()
    val directory = File(root, "round-trip")
    val controller = LocalDshHistoryController(directory, storage) { 1000L }
    val session = requireNotNull(controller.publish(native).selectedSessionId)
    check(storage.writes == 1 && storage.artifacts.size == 1)
    pass("native publication creates durable compatible history without executable tools")
    val restarted = LocalDshHistoryController(directory, storage) { 9999L }
    check(restarted.publish(native).selectedSessionId == session && storage.writes == 1)
    pass("restart and identical publication reuse the same session without writing again")
    storage.append(session, "new answer")
    val imported = restarted.read(native)
    val values = imported.snapshot.messages.getValue("c")
    check(values.size == 4 && values.take(2) == native.messages.getValue("c"))
    check(values.last().streamSnapshot!!.toolCalls.single().result == "offline result")
    check(values.last().streamSnapshot!!.messages.single().blocks.any {
        it is AssistantContentBlockSnapshot.Reasoning && it.text == "public thinking"
    })
    pass("DSH additions retain native IDs, attachments, public thinking and tool provenance")
    restarted.acknowledge(imported)
    val repeated = restarted.read(imported.snapshot)
    check(repeated.snapshot == imported.snapshot && repeated.warnings.isEmpty())
    check(restarted.publish(imported.snapshot).selectedSessionId == session && storage.writes == 1)
    pass("acknowledged round-trip neither duplicates history nor replays old tools")
    val edited = imported.snapshot.copy(messages = imported.snapshot.messages + ("c" to
        (values + ChatMessage(30, "user", "native edit"))))
    storage.append(session, "DSH edit")
    val conflict = restarted.read(edited)
    check(conflict.snapshot.messages.getValue("c") == edited.messages.getValue("c"))
    check(conflict.snapshot.conversations.size == 2 && conflict.warnings.any { it.contains("两个版本") })
    pass("concurrent native and DSH edits preserve both versions for user selection")
    restarted.acknowledge(conflict)
    val archived = conflict.snapshot.copy(conversations = conflict.snapshot.conversations.map { it.copy(archivedAtMillis = 3000L) })
    storage.append(session, "archived DSH edit")
    val afterArchive = restarted.read(archived)
    check(afterArchive.snapshot == archived && File(directory, "artifacts").listFiles()!!.single().isFile)
    pass("archived conversations stay archived while the untouched DSH artifact is retained")
    val pending1 = restarted.read(conflict.snapshot)
    val pending2 = restarted.read(conflict.snapshot)
    restarted.acknowledge(pending1)
    check(runCatching { restarted.acknowledge(pending2) }.isFailure)
    pass("stale import acknowledgement cannot overwrite newer link state")
    val missingStorage = Storage()
    val missingDir = File(root, "write-failure")
    val failed = LocalDshHistoryController(missingDir, missingStorage) { 1000L }
    missingStorage.failWrite = true
    check(runCatching { failed.publish(native) }.isFailure && missingStorage.artifacts.isEmpty())
    missingStorage.failWrite = false
    val retried = LocalDshHistoryController(missingDir, missingStorage) { 9999L }.publish(native)
    check(retried.selectedSessionId == session && missingStorage.artifacts.getValue(session).getJSONObject("header").getLong("createdAt") == 1000L)
    pass("failed physical write leaves a stable intent and a safe idempotent retry")
    val wrong = Storage().apply { wrongReceipt = true }
    check(runCatching { LocalDshHistoryController(File(root, "wrong-receipt"), wrong).publish(native) }.isFailure)
    pass("wrong durable write receipt cannot select a DSH session")
    val corrupt = File(root, "corrupt").apply { mkdirs() }
    File(corrupt, "links.json").writeText("{broken")
    check(runCatching { LocalDshHistoryController(corrupt, storage).publish(native) }.isFailure)
    check(File(corrupt, "links.json").readText() == "{broken")
    pass("corrupt link journal is preserved and never replaced by a blank index")
    println("LOCAL_DSH_HISTORY_CHECKS=$checks; production exchange and mappings, injected durable storage/JVM AtomicFile; paidModelRequests=0")
}
