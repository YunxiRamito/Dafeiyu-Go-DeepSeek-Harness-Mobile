package com.dafeiyu.mobile.data

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

private var passed = 0
private fun checkCase(name: String, block: () -> Unit) {
    block()
    passed++
    println("PASS $name")
}

private fun source(kind: String) = JSONObject().put("kind", kind)
private fun text(value: String) = JSONObject().put("type", "text").put("text", value)
private fun message(id: String, role: String, value: String, origin: JSONObject) = JSONObject()
    .put("id", id).put("role", role).put("source", origin).put("content", JSONArray().put(text(value)))
private fun event(sequence: Int, type: String, data: JSONObject, surface: Any? = null) = JSONObject()
    .put("seq", sequence).put("time", 1000).put("type", type).put("data", data)
    .also { if (surface != null) it.put("surfaceOp", surface) }
private fun header(id: String) = JSONObject().put("version", 4).put("id", id)
    .put("createdAt", 1000).put("isSeeded", false).put("delegationDepth", 0)
private fun artifact(id: String, rows: JSONArray) = JSONObject().put("header", header(id)).put("events", rows)
private fun expectRejected(block: () -> Unit) {
    var rejected = false
    try { block() } catch (_: IllegalArgumentException) { rejected = true }
    check(rejected)
}

fun main(args: Array<String>) {
    val output = File(args.single()).apply { check(isDirectory || mkdirs()) }
    val nativeMessages = listOf(ChatMessage(1, "user", "USER QUESTION"), ChatMessage(2, "assistant", "ASSISTANT ANSWER"))
    val closed = DshSharedHistoryAdapter.fromNative("android/closed", "Closed history", "/workspace", nativeMessages, 1000,
        JSONArray().put(JSONObject().put("tool", "OFFLINE LOG ONLY")))
    checkCase("native history restores only compatible text and retains log-only provenance") {
        val restored = DshSharedHistoryAdapter.decode(closed)
        check(restored.title == "Closed history")
        check(restored.compatibleMessages.map { it.text } == nativeMessages.map { it.text })
        check(!restored.interrupted)
        check(restored.eventsJson.contains("OFFLINE LOG ONLY"))
        check(restored.compatibleMessages.none { it.text.contains("OFFLINE LOG ONLY") })
    }
    closed.toString().also { File(output, "closed.json").writeText(it) }
    checkCase("unanswered imported history is interrupted and contains no queued execution") {
        val value = DshSharedHistoryAdapter.fromNative("android/unanswered", "Unanswered", null, nativeMessages.take(1), 1000)
        check(DshSharedHistoryAdapter.decode(value).interrupted)
        check(!value.toString().contains("agent/inbox"))
        File(output, "unanswered.json").writeText(value.toString())
    }
    checkCase("incomplete assistant closes durably without claiming success") {
        val reducer = AssistantStreamReducer()
        reducer.apply(AssistantStreamEvent.MessageStarted(0, "partial"))
        reducer.apply(AssistantStreamEvent.TextDelta(1, "partial", "PARTIAL"))
        reducer.apply(AssistantStreamEvent.MessageIncomplete(2, "partial", "import interrupted"))
        val value = DshSharedHistoryAdapter.fromNative("android/interrupted", "Interrupted", null,
            listOf(nativeMessages.first(), ChatMessage(2, "assistant", "PARTIAL", reducer.snapshot())), 1000)
        val restored = DshSharedHistoryAdapter.decode(value)
        check(restored.interrupted && restored.items.single { it.role == "assistant" }.interrupted)
        File(output, "interrupted.json").writeText(value.toString())
    }
    checkCase("consecutive assistant messages and empty histories remain storage-only") {
        val value = DshSharedHistoryAdapter.fromNative("android/consecutive", "Consecutive", null,
            nativeMessages + ChatMessage(3, "assistant", "SECOND ANSWER") + ChatMessage(4, "tool", "DO NOT RUN"), 1000)
        check(DshSharedHistoryAdapter.decode(value).compatibleMessages.map { it.text } ==
            listOf("USER QUESTION", "ASSISTANT ANSWER", "SECOND ANSWER"))
        File(output, "consecutive.json").writeText(value.toString())
        val empty = DshSharedHistoryAdapter.fromNative("android/empty", "Empty", null, emptyList(), 1000)
        check(DshSharedHistoryAdapter.decode(empty).compatibleMessages.isEmpty())
        File(output, "empty.json").writeText(empty.toString())
    }
    checkCase("surface replacements exclude old text but preserve exact provenance") {
        val rows = JSONArray()
            .put(event(0, "user/message", message("u1", "user", "OLD USER", source("user")), "append"))
            .put(event(1, "assistant/message", JSONObject().put("message", message("a1", "assistant", "OLD ANSWER", source("model"))), "append"))
            .put(event(2, "user/message", message("recall", "user", "COMPATIBLE SUMMARY", source("recall-plugin").put("form", "recall")),
                JSONObject().put("op", "replace").put("startSeq", 0).put("endSeq", 1)).put("sourceEventSeqs", JSONArray().put(0).put(1)))
        val restored = DshSharedHistoryAdapter.decode(artifact("surface", rows))
        check(restored.compatibleMessages.map { it.text } == listOf("COMPATIBLE SUMMARY"))
        check(restored.items.size == 3 && restored.items.take(2).none { it.activeOnSurface })
        check(restored.eventsJson.contains("OLD USER"))
        val missingSource = JSONObject(rows.getJSONObject(2).toString()).apply { remove("sourceEventSeqs") }
        expectRejected { DshSharedHistoryAdapter.decode(artifact("invalid", JSONArray().put(rows[0]).put(rows[1]).put(missingSource))) }
    }
    checkCase("public reasoning displays separately and replay signatures never enter compatible context") {
        val model = source("model").put("provider", "fixture").put("model", "fixture-model").put("replayState", "REPLAY_SECRET")
        val value = message("a", "assistant", "VISIBLE ANSWER", model)
        value.getJSONArray("content").put(JSONObject().put("type", "reasoning").put("text", "PUBLIC THINKING"))
            .put(JSONObject().put("type", "reasoning").put("text", "REDACTED").put("redacted", true))
        val restored = DshSharedHistoryAdapter.decode(artifact("thinking", JSONArray()
            .put(event(0, "system/message", JSONObject().put("message", message("s", "system", "OLD INSTRUCTIONS", source("system-prompt"))), "append"))
            .put(event(1, "user/message", message("catalog", "user", "CATALOG INSTRUCTIONS", source("skill-plugin").put("form", "instructions")), "append"))
            .put(event(2, "assistant/message", JSONObject().put("message", value), "append"))))
        check(restored.compatibleMessages.map { it.text } == listOf("VISIBLE ANSWER"))
        check(restored.items.single { it.role == "assistant" }.publicThinking == "PUBLIC THINKING")
        check(restored.items.none { it.sourceJson.contains("REPLAY_SECRET") })
        check(restored.eventsJson.contains("REPLAY_SECRET"))
        check(!restored.toString().contains("VISIBLE ANSWER"))
    }
    checkCase("missing tool results and open turns remain interrupted rather than re-executing") {
        val rows = JSONArray().put(event(0, "turn/start", JSONObject().put("turn", 1)))
            .put(event(1, "tool/call", JSONObject().put("turn", 1).put("step", 1).put("callId", "missing")
                .put("name", "run_script").put("arguments", "{\"script\":\"DO NOT RUN\"}")))
        val restored = DshSharedHistoryAdapter.decode(artifact("open", rows))
        check(restored.interrupted && restored.items.single().interrupted)
        check(restored.compatibleMessages.isEmpty())
        check(restored.items.single().toolName == "run_script")
    }
    checkCase("noncontiguous sequences and duplicate native message IDs are rejected") {
        expectRejected { DshSharedHistoryAdapter.decode(artifact("gap", JSONArray().put(event(1, "turn/start", JSONObject().put("turn", 1))))) }
        expectRejected { DshSharedHistoryAdapter.fromNative("duplicate", "Invalid", null, nativeMessages + nativeMessages.first(), 1000) }
    }
    println("DSH shared history production mapper: $passed PASS; no process, Agent or model launched.")
}
