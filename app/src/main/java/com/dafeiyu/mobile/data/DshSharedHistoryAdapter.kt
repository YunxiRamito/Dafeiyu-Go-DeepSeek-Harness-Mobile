package com.dafeiyu.mobile.data

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Presentation of a durable event. The untouched event stays in private history,
 * while signatures, replay envelopes and old system instructions never enter UI
 * fields or the compatible context. */
data class DshSharedHistoryItem(
    val eventSeq: Long,
    val timestampMillis: Long,
    val role: String,
    val messageId: String? = null,
    val text: String = "",
    val publicThinking: String = "",
    val sourceJson: String = "{}",
    val toolCallId: String? = null,
    val toolName: String? = null,
    val argumentsJson: String? = null,
    val resultJson: String? = null,
    val interrupted: Boolean = false,
    val activeOnSurface: Boolean = false,
    val rawEventJson: String,
) {
    override fun toString(): String = "DshSharedHistoryItem(seq=$eventSeq, role=$role, interrupted=$interrupted, active=$activeOnSurface)"
}

data class DshSharedHistory(
    val sessionId: String,
    val title: String,
    val cwd: String?,
    val createdAt: Long,
    val items: List<DshSharedHistoryItem>,
    val compatibleMessages: List<ChatMessage>,
    val headerJson: String,
    val eventsJson: String,
    val interrupted: Boolean,
) {
    override fun toString(): String = "DshSharedHistory(id=$sessionId, items=${items.size}, compatible=${compatibleMessages.size}, interrupted=$interrupted)"
}

/** Fixed DSH 0.2.1-alpha.1 logical Session v4 boundary. Physical zstd and old
 * generations must be decoded by the pinned DSH persistence backend first.
 * This adapter never submits a prompt, activates an Agent or dispatches tools. */
object DshSharedHistoryAdapter {
    private val surfaceTypes = setOf("system/message", "developer/message", "user/message", "assistant/message", "tool/result")

    fun decode(artifact: JSONObject): DshSharedHistory {
        require(artifact.toString().toByteArray().size <= 24 * 1024 * 1024) { "DSH 历史超出一次导入上限" }
        val header = artifact.getJSONObject("header")
        require(header.getInt("version") == 4) { "需要先通过 DSH 持久层升级历史格式" }
        val id = header.getString("id").also { require(it.isNotBlank() && it.length <= 512) }
        val createdAt = header.getLong("createdAt").also { require(it >= 0) }
        val events = artifact.getJSONArray("events")
        require(events.length() <= 120_000)
        val surface = mutableListOf<Long>()
        val rows = mutableListOf<DshSharedHistoryItem>()
        val humanMessages = mutableSetOf<Long>()
        val openTurns = mutableSetOf<Long>()
        val interruptedTurns = mutableSetOf<Long>()
        val completedCalls = mutableSetOf<String>()
        var title = id
        for (index in 0 until events.length()) {
            val event = events.getJSONObject(index)
            val sequence = event.getLong("seq")
            require(sequence == index.toLong()) { "DSH 历史必须是从零开始的连续事件" }
            val time = event.getLong("time").also { require(it >= 0) }
            val type = event.getString("type")
            if (type in surfaceTypes) {
                when (val operation = event.get("surfaceOp")) {
                    "append" -> surface += sequence
                    is JSONObject -> {
                        require(operation.getString("op") == "replace")
                        val start = surface.indexOf(operation.getLong("startSeq"))
                        val end = surface.indexOf(operation.getLong("endSeq"))
                        require(start >= 0 && end >= start) { "DSH 替换区间不在当前会话表面" }
                        val sources = event.optJSONArray("sourceEventSeqs")
                        require(sources != null && surface.subList(start, end + 1).all { old ->
                            (0 until sources.length()).any { sources.getLong(it) == old }
                        }) { "DSH 替换事件缺少被替换来源" }
                        repeat(end - start + 1) { surface.removeAt(start) }
                        surface.add(start, sequence)
                    }
                    else -> error("DSH 会话表面操作无效")
                }
            } else require(!event.has("surfaceOp"))
            val data = event.optJSONObject("data") ?: continue
            when (type) {
                "turn/start" -> openTurns += data.getLong("turn")
                "turn/end" -> {
                    val turn = data.getLong("turn")
                    openTurns -= turn
                    if (data.optJSONObject("reason")?.optString("kind") in setOf("aborted", "error", "interrupted")) interruptedTurns += turn
                }
                "session/title" -> title = data.optString("title", id).take(512)
                "user/message", "assistant/message", "tool/result" -> {
                    val message = if (type == "user/message") data else data.getJSONObject("message")
                    val expectedRole = when (type) { "user/message" -> "user"; "assistant/message" -> "assistant"; else -> "tool" }
                    require(message.getString("role") == expectedRole)
                    val source = message.getJSONObject("source")
                    if (expectedRole == "user" && (source.optString("kind") == "user" || source.optString("form") == "recall")) humanMessages += sequence
                    val content = message.getJSONArray("content")
                    val text = blockText(content, "text")
                    val thinking = if (expectedRole == "assistant") blockText(content, "reasoning") else ""
                    val callId = if (expectedRole == "tool") message.getString("toolCallId") else null
                    if (callId != null) completedCalls += callId
                    rows += DshSharedHistoryItem(sequence, time, expectedRole, message.getString("id"), text, thinking,
                        safeSource(source).toString(), toolCallId = callId,
                        resultJson = if (expectedRole == "tool") message.toString() else null,
                        interrupted = data.optBoolean("interrupted"), rawEventJson = event.toString())
                }
                "tool/call" -> rows += DshSharedHistoryItem(sequence, time, "tool-call", toolCallId = data.getString("callId"),
                    toolName = data.getString("name"), argumentsJson = data.getString("arguments"),
                    sourceJson = JSONObject().put("kind", "tool").put("engine", "dsh").toString(), rawEventJson = event.toString())
                "assistant/attempt" -> rows += DshSharedHistoryItem(sequence, time, "attempt", interrupted = true,
                    sourceJson = JSONObject().put("engine", "dsh").toString(), rawEventJson = event.toString())
            }
        }
        val active = surface.toSet()
        val mapped = rows.map { item ->
            val turn = events.getJSONObject(item.eventSeq.toInt()).optJSONObject("data")?.optLong("turn", -1)
            item.copy(activeOnSurface = item.eventSeq in active,
                interrupted = item.interrupted || item.role == "tool-call" && item.toolCallId !in completedCalls ||
                    turn in openTurns || turn in interruptedTurns)
        }
        val bySeq = mapped.associateBy { it.eventSeq }
        val compatible = surface.mapNotNull { seq ->
            val item = bySeq[seq] ?: return@mapNotNull null
            if (item.text.isBlank() || item.role !in setOf("user", "assistant") || item.role == "user" && seq !in humanMessages) null
            else ChatMessage(seq + 1, item.role, item.text, modelInput = item.text)
        }
        return DshSharedHistory(id, title, header.optString("cwd").takeIf { it.isNotBlank() }, createdAt,
            mapped, compatible, header.toString(), events.toString(), openTurns.isNotEmpty() || interruptedTurns.isNotEmpty() || mapped.any { it.interrupted })
    }

    /** Build fresh persistent history only; existing DSH IDs are never overwritten.
     * A stable content identity makes retries idempotent without re-running any
     * previous prompt. Android tools stay in a log-only provenance event. */
    fun fromNative(sessionId: String, title: String, cwd: String?, messages: List<ChatMessage>,
        createdAt: Long, provenance: JSONArray = JSONArray()): JSONObject {
        require(sessionId.isNotBlank() && sessionId.length <= 512 && createdAt >= 0 && messages.size <= 20_000)
        require(messages.map { it.id }.distinct().size == messages.size)
        val header = JSONObject().put("version", 4).put("id", sessionId).put("createdAt", createdAt)
            .put("isSeeded", false).put("delegationDepth", 0).also { if (cwd != null) it.put("cwd", cwd) }
        val events = JSONArray()
        fun append(type: String, data: JSONObject, surface: Boolean = false, ignorable: Boolean = false) {
            events.put(JSONObject().put("type", type).put("seq", events.length()).put("time", createdAt).put("data", data)
                .also { if (surface) it.put("surfaceOp", "append"); if (ignorable) it.put("ignorable", true) })
        }
        append("session/title", JSONObject().put("title", title.take(512))
            .put("source", JSONObject().put("kind", "user")).put("messageSeqs", JSONArray()))
        var turn = 0
        var open = false
        var answered = false
        var interrupted = false
        fun closeTurn() {
            if (!open) return
            append("step/end", JSONObject().put("turn", turn).put("step", 1))
            val reason = if (answered && !interrupted) JSONObject().put("kind", "completed")
            else JSONObject().put("kind", "aborted").put("reason", JSONObject().put("kind", "legacy"))
            append("turn/end", JSONObject().put("turn", turn).put("reason", reason))
            open = false
        }
        for (message in messages) {
            if (message.role !in setOf("user", "assistant")) continue
            val text = message.modelInput ?: message.text
            require(text.toByteArray().size <= 2 * 1024 * 1024)
            if (text.isBlank()) continue
            if (!open || message.role == "user" || answered) {
                closeTurn()
                turn++
                append("turn/start", JSONObject().put("turn", turn))
                append("step/start", JSONObject().put("turn", turn).put("step", 1))
                open = true
                answered = false
                interrupted = false
            }
            val stableId = MessageDigest.getInstance("SHA-256").digest("$sessionId/${message.id}/${message.role}".toByteArray())
                .joinToString("") { "%02x".format(it) }
            val content = JSONArray().put(JSONObject().put("type", "text").put("text", text))
            val source = if (message.role == "user") JSONObject().put("kind", "user")
            else JSONObject().put("kind", "model").put("provider", "android-light").put("model", "compatible-history")
            val value = JSONObject().put("id", "android-$stableId").put("role", message.role).put("content", content).put("source", source)
            if (message.role == "user") append("user/message", value, surface = true)
            else {
                interrupted = message.streamSnapshot?.messages?.any { it.status != AssistantMessageStatus.COMPLETED } == true
                answered = true
                append("assistant/message", JSONObject().put("turn", turn).put("step", 1).put("message", value).put("stream", JSONArray())
                    .also { if (interrupted) it.put("interrupted", true) }, surface = true)
            }
        }
        closeTurn()
        if (provenance.length() > 0) append("android/history-source", JSONObject().put("engine", "official-light")
            .put("activity", JSONArray(provenance.toString())), ignorable = true)
        return JSONObject().put("header", header).put("events", events).also { decode(it) }
    }

    private fun blockText(blocks: JSONArray, type: String): String = buildString {
        require(blocks.length() <= 4096)
        for (index in 0 until blocks.length()) {
            val block = blocks.getJSONObject(index)
            if (block.optString("type") == type && !block.optBoolean("redacted")) {
                val value = block.getString("text")
                require(length + value.length <= 2 * 1024 * 1024)
                append(value)
            }
        }
    }

    private fun safeSource(raw: JSONObject): JSONObject = JSONObject().put("engine", "dsh").also { output ->
        for (key in listOf("kind", "provider", "model", "callId", "form")) if (raw.has(key) && !raw.isNull(key)) output.put(key, raw.getString(key))
    }
}
