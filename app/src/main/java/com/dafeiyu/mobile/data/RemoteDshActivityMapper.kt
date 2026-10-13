package com.dafeiyu.mobile.data

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Display-only DSH journal projection. It cannot dispatch a tool or replay model context. */
class RemoteDshActivityMapper(val sessionId: String) {
    data class Attachment(val messageId: String, val id: String, val name: String?, val path: String?,
        val byteLength: Long?, val mediaType: String?, val type: String)
    data class View(val sessionId: String, val cwd: String?, val cursor: Long, val hasMore: Boolean,
        val messages: List<ChatMessage>, val activity: AssistantStreamSnapshot,
        val userRpcIds: Map<String, String>, val attachments: List<Attachment>)

    private var cursor = -1L
    private var cwd: String? = null
    private var hasMore = false
    private var durable = JSONArray()
    private var active: JSONObject? = null
    private var projections = JSONObject()

    init { require(sessionId.isNotEmpty() && sessionId.length <= 512 && sessionId.none(Char::isISOControl)) }

    fun applyFollowFrame(raw: JSONObject) {
        val frame = publicFrame(raw)
        when (frame.getString("type")) {
            "snapshot" -> {
                val header = frame.getJSONObject("header")
                require(header.getString("id") == sessionId) { "remote_session_mismatch" }
                cwd = header.remoteOptionalString("cwd")
                cursor = frame.remoteSequenceCursor("cursor")
                hasMore = frame.optBoolean("hasMore")
                durable = JSONArray()
                for (record in frame.getJSONArray("records").remoteObjects(MAX_EVENTS)) {
                    val event = record.getJSONObject("event")
                    require(event.remoteLong("seq") <= cursor)
                    insertDurable(event)
                }
                projections = frame.optJSONObject("projections")?.optJSONObject("values") ?: JSONObject()
                val baseline = frame.optJSONObject("assistantStream")
                active = baseline?.optJSONObject("activeAttempt")?.let { attempt ->
                    JSONObject(attempt.toString()).put("revision", baseline.remoteLong("revision"))
                }
            }
            "event" -> {
                val event = frame.getJSONObject("event")
                val seq = event.remoteLong("seq")
                if (seq <= cursor) return
                insertDurable(event)
                cursor = seq
            }
            "assistant-stream" -> applyAssistantFrame(frame.getJSONObject("frame"))
            else -> error("remote_follow_frame_invalid")
        }
        require(durable.length() <= MAX_EVENTS)
    }

    fun applyProjection(key: String, value: Any?) {
        val safe = publicProjection(key, value) ?: return
        projections.put(key, safe)
    }

    fun encode(): JSONObject = JSONObject().put("schema", 1).put("sessionId", sessionId).put("cwd", cwd)
        .put("cursor", cursor).put("hasMore", hasMore).put("events", JSONArray(durable.toString()))
        .put("active", active?.let { JSONObject(it.toString()) }).put("projections", JSONObject(projections.toString()))

    fun view(): View {
        val reducer = AssistantStreamReducer(source = AssistantStreamSource.BACKEND)
        var sequence = 0L
        var rounds = 0
        var steps = 0
        var status: AssistantRunStatus? = null
        var usage = AssistantUsage()
        val rpcIds = linkedMapOf<String, String>()
        val attachments = mutableListOf<Attachment>()
        val subagents = linkedSetOf<String>()
        fun emit(create: (Long) -> AssistantStreamEvent) { reducer.apply(create(sequence++)) }
        fun applyUsage(value: JSONObject?, add: Boolean) {
            if (value == null) return
            fun token(key: String): Long? = (value.opt(key) as? Number)?.let {
                it.toLong().takeIf { integer -> integer in 0..1_000_000_000_000L && integer.toDouble() == it.toDouble() }
            }
            fun merge(previous: Long?, next: Long?) = if (next == null) previous else if (add) (previous ?: 0L) + next else next
            usage = AssistantUsage(merge(usage.inputTokens, token("inputTokens")), merge(usage.outputTokens, token("outputTokens")),
                merge(usage.totalTokens, token("totalTokens")), merge(usage.cachedInputTokens, token("cacheReadTokens")),
                merge(usage.reasoningTokens, token("reasoningTokens")), merge(usage.cacheCreationInputTokens, token("cacheWriteTokens")))
        }
        fun content(messageId: String, blocks: JSONArray, complete: Boolean) {
            for ((index, block) in blocks.remoteObjects(1024).withIndex()) {
                val blockId = "block-$index"
                when (block.optString("type")) {
                    "text" -> emit { AssistantStreamEvent.TextDelta(it, messageId, block.getString("text"), blockId) }
                    "reasoning" -> emit { AssistantStreamEvent.UserVisibleThinkingDelta(it, messageId, blockId, block.getString("text")) }
                    "tool-call" -> {
                        val id = block.remoteId("id")
                        emit { AssistantStreamEvent.ToolCallStarted(it, id, block.remoteId("name"),
                            initialArguments = block.getString("arguments"), messageId = messageId) }
                        emit { AssistantStreamEvent.ToolArgumentsDone(it, id, block.getString("arguments")) }
                    }
                    "image", "file" -> block.optJSONObject("attachment")?.let { attachment ->
                        attachments += Attachment(messageId, attachment.remoteId("attachmentId"), attachment.remoteOptionalString("name"),
                            null, (attachment.opt("bytes") as? Number)?.toLong()?.takeIf { it >= 0 },
                            attachment.remoteOptionalString("mediaType"), block.getString("type"))
                    }
                }
                if (complete && block.optString("type") in setOf("text", "reasoning")) emit {
                    AssistantStreamEvent.ContentBlockDone(it, messageId, blockId,
                        if (block.optString("type") == "reasoning") AssistantReasoningKind.USER_VISIBLE_THINKING else null)
                }
            }
        }
        fun chunks(messageId: String, stream: JSONArray) {
            var attemptUsage: JSONObject? = null
            for (record in stream.remoteObjects(MAX_CHUNKS)) {
                when (record.optString("type")) {
                    "text-chunks", "reasoning-chunks" -> {
                        val text = stringParts(record.getJSONArray("texts"))
                        val block = "block-${record.remoteLong("index")}"
                        if (record.getString("type") == "text-chunks") emit { AssistantStreamEvent.TextDelta(it, messageId, text, block) }
                        else emit { AssistantStreamEvent.UserVisibleThinkingDelta(it, messageId, block, text) }
                    }
                    "tool-call-chunks" -> {
                        val id = record.remoteId("id")
                        emit { AssistantStreamEvent.ToolCallStarted(it, id, record.remoteOptionalString("name") ?: "tool", messageId = messageId) }
                        emit { AssistantStreamEvent.ToolArgumentsDelta(it, id, stringParts(record.getJSONArray("args"))) }
                    }
                    "chunk" -> {
                        val chunk = record.getJSONObject("chunk")
                        val block = "block-${chunk.optLong("index")}" // block indexes are validated by publicChunk.
                        when (chunk.getString("type")) {
                            "usage" -> attemptUsage = chunk.optJSONObject("usage")
                            "text-delta" -> emit { AssistantStreamEvent.TextDelta(it, messageId, chunk.getString("text"), block) }
                            "reasoning-delta" -> emit { AssistantStreamEvent.UserVisibleThinkingDelta(it, messageId, block, chunk.getString("text")) }
                            "tool-call-delta" -> {
                                val id = chunk.remoteId("id")
                                emit { AssistantStreamEvent.ToolCallStarted(it, id, chunk.remoteOptionalString("name") ?: "tool", messageId = messageId) }
                                emit { AssistantStreamEvent.ToolArgumentsDelta(it, id, chunk.getString("argumentsDelta")) }
                            }
                            "block-end" -> {
                                val done = chunk.getJSONObject("block")
                                when (done.optString("type")) {
                                    "text", "reasoning" -> emit { AssistantStreamEvent.ContentBlockDone(it, messageId, block,
                                        if (done.getString("type") == "reasoning") AssistantReasoningKind.USER_VISIBLE_THINKING else null,
                                        done.getString("text")) }
                                    "tool-call" -> {
                                        val id = done.remoteId("id")
                                        emit { AssistantStreamEvent.ToolCallStarted(it, id, done.remoteId("name"), messageId = messageId) }
                                        emit { AssistantStreamEvent.ToolArgumentsDone(it, id, done.getString("arguments")) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            applyUsage(attemptUsage, true)
        }
        for (event in durable.remoteObjects(MAX_EVENTS)) {
            val data = event.getJSONObject("data")
            when (event.getString("type")) {
                "user/message" -> {
                    val id = data.remoteId("id")
                    emit { AssistantStreamEvent.MessageStarted(it, id, "user") }
                    content(id, data.getJSONArray("content"), true)
                    emit { AssistantStreamEvent.MessageCompleted(it, id) }
                    data.optJSONObject("source")?.remoteOptionalString("rpcId")?.let { rpcIds[it] = id }
                }
                "assistant/message" -> {
                    val message = data.getJSONObject("message")
                    val id = message.remoteId("id")
                    emit { AssistantStreamEvent.MessageStarted(it, id) }
                    content(id, message.getJSONArray("content"), true)
                    if (data.optBoolean("interrupted")) emit { AssistantStreamEvent.MessageIncomplete(it, id, "interrupted") }
                    else emit { AssistantStreamEvent.MessageCompleted(it, id) }
                    applyUsage(data.optJSONObject("usage"), true)
                }
                "assistant/attempt" -> {
                    val id = "attempt-${event.remoteLong("seq")}" 
                    emit { AssistantStreamEvent.MessageStarted(it, id) }
                    chunks(id, data.getJSONArray("stream"))
                    emit { AssistantStreamEvent.MessageIncomplete(it, id, "attempt_not_committed") }
                }
                "tool/call" -> {
                    val id = data.remoteId("callId")
                    emit { AssistantStreamEvent.ToolCallStarted(it, id, data.remoteId("name"), initialArguments = data.getString("arguments")) }
                    emit { AssistantStreamEvent.ToolArgumentsDone(it, id, data.getString("arguments")) }
                    emit { AssistantStreamEvent.ToolStatusChanged(it, id, AssistantStreamEvent.ToolStatus.RUNNING) }
                }
                "tool/result" -> {
                    val message = data.getJSONObject("message")
                    val id = message.remoteId("toolCallId")
                    val text = message.getJSONArray("content").remoteObjects(1024).filter { it.optString("type") == "text" }
                        .joinToString("\n") { it.getString("text") }
                    val error = data.optJSONObject("error")
                    if (message.optBoolean("isError") || error != null) emit { AssistantStreamEvent.ToolError(it, id,
                        error?.remoteOptionalString("reason") ?: text.ifBlank { "远端工具执行失败" }, error?.remoteOptionalString("code")) }
                    else emit { AssistantStreamEvent.ToolResult(it, id, text) }
                }
                "subagent/catalog" -> {
                    val id = data.remoteId("childId")
                    subagents += id
                    emit { AssistantStreamEvent.SubagentStarted(it, id, data.remoteOptionalString("label") ?: id) }
                }
                "turn/start" -> { rounds = maxOf(rounds, data.optInt("turn") + 1); status = AssistantRunStatus.RUNNING }
                "step/start" -> { steps++; status = AssistantRunStatus.RUNNING }
                "turn/end" -> status = when (data.optJSONObject("reason")?.optString("kind")) {
                    "completed" -> AssistantRunStatus.COMPLETED
                    "aborted" -> AssistantRunStatus.CANCELLED
                    "error" -> AssistantRunStatus.FAILED
                    else -> AssistantRunStatus.INCOMPLETE
                }
            }
        }
        active?.let { attempt ->
            val id = "active-${attempt.remoteId("attemptId")}" 
            emit { AssistantStreamEvent.MessageStarted(it, id) }
            chunks(id, attempt.getJSONArray("stream"))
            rounds = maxOf(rounds, attempt.optInt("turn") + 1)
            status = AssistantRunStatus.RUNNING
        }
        projections.optJSONArray("subagentCatalog")?.remoteObjects(256)?.forEach { row ->
            val id = row.remoteId("id")
            if (subagents.add(id)) emit { AssistantStreamEvent.SubagentStarted(it, id, row.remoteOptionalString("label") ?: id) }
        }
        val timing = projections.optJSONObject("subagentTiming")
        if (timing != null) status = when {
            timing.optJSONObject("active") != null -> AssistantRunStatus.RUNNING
            timing.opt("lastTurnCompleted") == true -> AssistantRunStatus.COMPLETED
            timing.opt("lastTurnCompleted") == false -> AssistantRunStatus.INCOMPLETE
            else -> status
        }
        if (usage != AssistantUsage()) emit { AssistantStreamEvent.UsageReported(it, usage) }
        val snapshot = reducer.snapshot().copy(runRounds = rounds.takeIf { it > 0 }, runSteps = steps.takeIf { it > 0 },
            runStatus = status, isFinal = status != null && status != AssistantRunStatus.RUNNING,
            runElapsedMillis = timing?.let { value -> (value.opt("settledMs") as? Number)?.toLong()?.let { settled ->
                val activeTiming = value.optJSONObject("active")
                (settled + (activeTiming?.let { it.remoteLong("through") - it.remoteLong("since") } ?: 0)).takeIf { it >= 0 }
            } })
        val lastAssistant = snapshot.messages.lastOrNull { it.role == "assistant" }
        val chats = snapshot.messages.map { message ->
            val text = message.blocks.filterIsInstance<AssistantContentBlockSnapshot.Text>().joinToString("\n") { it.text }
            val references = snapshot.activityOrder.filter { it.messageId == message.messageId ||
                (it.messageId == null && message == lastAssistant) }
            val activity = if (message.role == "assistant") snapshot.copy(messages = listOf(message), activityOrder = references,
                toolCalls = snapshot.toolCalls.filter { tool -> references.any { it.kind == AssistantActivityKind.TOOL && it.id == tool.callId } },
                subagents = if (message == lastAssistant) snapshot.subagents else emptyList()) else null
            ChatMessage(stableMessageId(message.messageId), message.role, text, activity,
                attachmentIds = attachments.filter { it.messageId == message.messageId }.map { it.id })
        }
        return View(sessionId, cwd, cursor, hasMore, chats, snapshot, rpcIds, attachments)
    }

    private fun insertDurable(event: JSONObject) {
        val seq = event.remoteLong("seq")
        val existing = durable.remoteObjects(MAX_EVENTS).firstOrNull { it.remoteLong("seq") == seq }
        if (existing != null) { require(remoteJsonHash(existing) == remoteJsonHash(event)) { "remote_event_conflict" }; return }
        val replacement = event.optJSONObject("surfaceOp")?.takeIf { it.optString("op") == "replace" }
        if (replacement != null) {
            val start = replacement.remoteLong("startSeq"); val end = replacement.remoteLong("endSeq")
            require(start <= end && end < seq)
            durable = JSONArray(durable.remoteObjects(MAX_EVENTS).filterNot { it.remoteLong("seq") in start..end })
        }
        durable.put(event)
        durable = JSONArray(durable.remoteObjects(MAX_EVENTS).sortedBy { it.remoteLong("seq") })
        if (event.optString("type") in setOf("assistant/message", "assistant/attempt") &&
            active?.let { it.optInt("turn") == event.getJSONObject("data").optInt("turn") &&
                it.optInt("step") == event.getJSONObject("data").optInt("step") } == true) active = null
    }

    private fun applyAssistantFrame(frame: JSONObject) {
        val revision = frame.remoteLong("revision")
        val previous = active
        when (frame.getString("type")) {
            "start" -> {
                if (previous != null && revision <= previous.remoteLong("revision")) return
                active = JSONObject(frame.toString()).put("nextIndex", 0).put("stream", JSONArray())
            }
            "chunk" -> {
                require(previous != null && previous.getString("attemptId") == frame.getString("attemptId")) { "remote_stream_baseline_required" }
                if (revision <= previous.remoteLong("revision")) return
                require(frame.remoteLong("index") == previous.remoteLong("nextIndex")) { "remote_stream_index_gap" }
                val stream = previous.getJSONArray("stream")
                require(stream.length() < MAX_CHUNKS)
                stream.put(JSONObject().put("type", "chunk").put("time", frame.opt("time")).put("chunk", frame.getJSONObject("chunk")))
                previous.put("nextIndex", previous.remoteLong("nextIndex") + 1).put("revision", revision)
            }
            "end" -> {
                if (previous == null || previous.getString("attemptId") != frame.getString("attemptId") || revision <= previous.remoteLong("revision")) return
                require(frame.remoteLong("index") == previous.remoteLong("nextIndex")) { "remote_stream_index_gap" }
                val outcome = frame.getJSONObject("outcome")
                if (outcome.getString("kind") == "abandoned" || (outcome.opt("seq") as? Number)?.toLong()?.let { it <= cursor } == true) active = null
                else previous.put("revision", revision)
            }
        }
    }

    private fun stableMessageId(messageId: String): Long = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256")
        .digest("$sessionId\u0000$messageId".toByteArray(Charsets.UTF_8))).long and Long.MAX_VALUE

    companion object {
        private const val MAX_EVENTS = 2000
        private const val MAX_CHUNKS = 4096

        fun restore(value: JSONObject): RemoteDshActivityMapper {
            require(value.optInt("schema") == 1)
            return RemoteDshActivityMapper(value.remoteId("sessionId")).apply {
                cwd = value.remoteOptionalString("cwd")
                cursor = value.getLong("cursor").also { require(it >= -1) }
                hasMore = value.optBoolean("hasMore")
                durable = JSONArray(value.getJSONArray("events").remoteObjects(MAX_EVENTS).map(::publicEvent))
                active = value.optJSONObject("active")?.let { attempt -> publicAttempt(attempt).put("revision", attempt.remoteLong("revision")) }
                val stored = value.optJSONObject("projections") ?: JSONObject()
                stored.keys().forEach { key -> publicProjection(key, stored.opt(key))?.let { projections.put(key, it) } }
            }
        }

        /** Positive field selection excludes model replay state, authentication and context headers. */
        fun publicFrame(value: JSONObject): JSONObject {
            remoteValidateJson(value)
            return when (value.getString("type")) {
                "snapshot" -> JSONObject().put("type", "snapshot").put("header", fields(value.getJSONObject("header"), "id", "cwd"))
                    .put("cursor", value.remoteSequenceCursor("cursor")).put("hasMore", value.getBoolean("hasMore"))
                    .put("records", JSONArray(value.getJSONArray("records").remoteObjects(MAX_EVENTS).map {
                        JSONObject().put("type", "event").put("event", publicEvent(it.getJSONObject("event")))
                    })).put("projections", value.optJSONObject("projections")?.let(::publicProjections))
                    .put("assistantStream", value.optJSONObject("assistantStream")?.let { baseline ->
                        JSONObject().put("revision", baseline.remoteLong("revision"))
                            .put("activeAttempt", baseline.optJSONObject("activeAttempt")?.let(::publicAttempt))
                    })
                "event" -> JSONObject().put("type", "event").put("event", publicEvent(value.getJSONObject("event")))
                "assistant-stream" -> {
                    val frame = value.getJSONObject("frame")
                    JSONObject().put("type", "assistant-stream").put("frame", fields(frame, "type", "attemptId", "revision", "startedAfterSeq", "turn", "step", "index", "time")
                        .put("chunk", frame.optJSONObject("chunk")?.let(::publicChunk))
                        .put("outcome", frame.optJSONObject("outcome")?.let { fields(it, "kind", "eventType", "seq") }))
                }
                else -> error("remote_follow_frame_invalid")
            }
        }

        private fun publicEvent(event: JSONObject): JSONObject {
            val type = event.remoteId("type")
            val data = event.optJSONObject("data") ?: JSONObject()
            val safe = when (type) {
                "user/message" -> publicMessage(data)
                "assistant/message" -> fields(data, "turn", "step", "interrupted").put("message", publicMessage(data.getJSONObject("message")))
                    .put("usage", data.optJSONObject("usage")?.let(::publicUsage))
                "assistant/attempt" -> fields(data, "turn", "step").put("stream", publicStream(data.getJSONArray("stream")))
                "tool/call" -> fields(data, "turn", "step", "callId", "name", "arguments")
                "tool/result" -> fields(data, "turn", "step").put("message", publicMessage(data.getJSONObject("message")))
                    .put("error", data.optJSONObject("error")?.let { fields(it, "name", "code", "reason") })
                "subagent/catalog" -> fields(data, "version", "childId", "childCreatedAt", "mode", "label")
                "turn/start", "step/start", "step/end" -> fields(data, "turn", "step")
                "turn/end" -> fields(data, "turn").put("reason", data.optJSONObject("reason")?.let { fields(it, "kind") })
                "model/selection" -> fields(data, "provider", "model", "reasoningEffort")
                else -> JSONObject()
            }
            return JSONObject().put("type", type).put("seq", event.remoteLong("seq")).put("time", event.remoteLong("time")).put("data", safe)
                .put("surfaceOp", event.optJSONObject("surfaceOp")?.let { fields(it, "op", "startSeq", "endSeq") }
                    ?: event.optString("surfaceOp").takeIf { it == "append" })
        }
        private fun publicMessage(message: JSONObject): JSONObject = fields(message, "id", "role", "toolCallId", "isError")
            .put("source", message.optJSONObject("source")?.let { fields(it, "kind", "provider", "model", "rpcId", "callId") })
            .put("content", JSONArray(message.getJSONArray("content").remoteObjects(1024).map(::publicBlock)))
        private fun publicBlock(block: JSONObject): JSONObject = when (block.getString("type")) {
            "text", "reasoning" -> fields(block, "type", "text")
            "tool-call" -> fields(block, "type", "id", "name", "arguments")
            "file", "image" -> fields(block, "type").put("attachment", block.optJSONObject("attachment")?.let {
                fields(it, "attachmentId", "name", "bytes", "mediaType", "width", "height")
            })
            else -> JSONObject().put("type", "unsupported")
        }
        private fun publicChunk(chunk: JSONObject): JSONObject {
            val result = fields(chunk, "type", "index", "blockType", "text", "id", "name", "argumentsDelta")
            if (chunk.has("index")) require(chunk.remoteLong("index") <= 1024)
            return result.put("block", chunk.optJSONObject("block")?.let(::publicBlock))
                .put("usage", chunk.optJSONObject("usage")?.let(::publicUsage))
                .put("reason", chunk.optJSONObject("reason")?.let { fields(it, "kind") })
        }
        private fun publicStream(stream: JSONArray) = JSONArray(stream.remoteObjects(MAX_CHUNKS).map { record ->
            when (record.getString("type")) {
                "text-chunks", "reasoning-chunks" -> fields(record, "type", "index", "time0", "dt", "texts")
                "tool-call-chunks" -> fields(record, "type", "index", "time0", "dt", "id", "name", "args")
                "chunk" -> fields(record, "type", "time").put("chunk", publicChunk(record.getJSONObject("chunk")))
                else -> error("remote_stream_record_invalid")
            }
        })
        private fun publicAttempt(value: JSONObject) = fields(value, "attemptId", "startedAfterSeq", "turn", "step", "nextIndex")
            .put("stream", publicStream(value.getJSONArray("stream")))
        private fun publicUsage(value: JSONObject) = fields(value, "inputTokens", "outputTokens", "totalTokens", "cacheReadTokens", "cacheWriteTokens", "reasoningTokens")
        private fun publicProjections(value: JSONObject): JSONObject {
            val result = JSONObject()
            val values = value.optJSONObject("values") ?: JSONObject()
            values.keys().forEach { key -> publicProjection(key, values.opt(key))?.let { result.put(key, it) } }
            return JSONObject().put("asOfSeq", value.remoteSequenceCursor("asOfSeq")).put("values", result)
        }
        fun publicProjection(key: String, value: Any?): Any? = when (key) {
            "subagentCatalog" -> (value as? JSONArray)?.let { JSONArray(it.remoteObjects(256).map { row -> fields(row, "id", "createdAt", "mode", "label") }) }
            "subagentTiming" -> (value as? JSONObject)?.let { fields(it, "settledMs", "lastTurnCompleted")
                .put("active", it.optJSONObject("active")?.let { active -> fields(active, "since", "through") }) }
            "modelSelection" -> (value as? JSONObject)?.let { JSONObject().put("lastUsed", it.optJSONObject("lastUsed")?.let { selection -> fields(selection, "provider", "model", "reasoningEffort") })
                .put("next", it.optJSONObject("next")?.let { selection -> fields(selection, "provider", "model", "reasoningEffort") }) }
            else -> null
        }
        private fun fields(value: JSONObject, vararg keys: String) = JSONObject().apply {
            keys.forEach { if (value.has(it) && !value.isNull(it)) put(it, value.get(it)) }
        }
        private fun stringParts(value: JSONArray): String {
            require(value.length() <= MAX_CHUNKS)
            return (0 until value.length()).joinToString("") { value.getString(it) }
        }
    }
}
