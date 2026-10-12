package com.dafeiyu.mobile.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Optional schema inside workspace index v2. No opaque reasoning payload is serialized.
 * Missing/unknown schemas leave the ordinary message text readable by older/newer clients.
 */
internal object AssistantStreamSnapshotJson {
    fun encode(s: AssistantStreamSnapshot): JSONObject = JSONObject().apply {
        put("schema", 1)
        put("source", s.source.name)
        put("isFinal", s.isFinal)
        put("runElapsedMillis", s.runElapsedMillis)
        put("runRounds", s.runRounds)
        put("runSteps", s.runSteps)
        put("runStatus", s.runStatus?.name)
        put("lastAppliedSequenceNumber", s.lastAppliedSequenceNumber)
        put("nextExpectedSequenceNumber", s.nextExpectedSequenceNumber)
        put("pendingSequenceNumbers", JSONArray(s.pendingSequenceNumbers))
        put("skippedSequenceGaps", array(s.skippedSequenceGaps) {
            JSONObject().put("firstMissing", it.firstMissing).put("lastMissing", it.lastMissing)
        })
        put("activityOrder", array(s.activityOrder) {
            JSONObject().put("kind", it.kind.name).put("id", it.id)
                .put("messageId", it.messageId).put("reasoningKind", it.reasoningKind?.name)
        })
        put("messages", array(s.messages) { m ->
            JSONObject().put("messageId", m.messageId).put("role", m.role).put("status", m.status.name)
                .put("finishReason", m.finishReason).put("incompleteReason", m.incompleteReason)
                .put("error", m.error).put("errorCode", m.errorCode)
                .put("blocks", array(m.blocks) { b ->
                    JSONObject().put("blockId", b.blockId).apply {
                        when (b) {
                            is AssistantContentBlockSnapshot.Text -> {
                                put("type", "text"); put("text", b.text); put("isComplete", b.isComplete)
                            }
                            is AssistantContentBlockSnapshot.Reasoning -> {
                                put("type", "reasoning"); put("kind", b.kind.name)
                                put("text", b.text); put("isComplete", b.isComplete)
                                b.metadata?.let { metadata ->
                                    put("metadata", JSONObject().put("provider", metadata.provider)
                                        .put("values", JSONObject(metadata.values.filterKeys { it.endsWith("_present") }
                                            .mapValues { "true" })))
                                }
                            }
                        }
                    }
                })
        })
        put("toolCalls", array(s.toolCalls) {
            JSONObject().put("callId", it.callId).put("name", it.name).put("kind", it.kind?.name)
                .put("arguments", it.arguments).put("status", it.status.name)
                .put("approvalRequirement", it.approvalRequirement.name).put("approvalReason", it.approvalReason)
                .put("result", it.result).put("error", it.error).put("errorCode", it.errorCode)
        })
        put("subagents", array(s.subagents) {
            JSONObject().put("agentId", it.agentId).put("name", it.name).put("parentCallId", it.parentCallId)
                .put("status", it.status.name).put("output", it.output).put("detail", it.detail).put("error", it.error)
        })
        put("searches", array(s.searches) {
            JSONObject().put("searchId", it.searchId).put("query", it.query).put("status", it.status.name)
                .put("error", it.error).put("results", array(it.results) { r ->
                    JSONObject().put("id", r.id).put("title", r.title).put("url", r.url)
                        .put("snippet", r.snippet).put("publishedAt", r.publishedAt)
                })
        })
        put("citations", array(s.citations) {
            JSONObject().put("messageId", it.messageId).put("searchId", it.searchId)
                .put("citation", citationJson(it.citation))
        })
        s.usage?.let {
            put("usage", JSONObject().put("inputTokens", it.inputTokens).put("outputTokens", it.outputTokens)
                .put("totalTokens", it.totalTokens).put("cachedInputTokens", it.cachedInputTokens)
                .put("reasoningTokens", it.reasoningTokens).put("cacheCreationInputTokens", it.cacheCreationInputTokens))
        }
    }

    fun decode(json: JSONObject?): AssistantStreamSnapshot? {
        if (json == null || json.optInt("schema") != 1) return null
        return runCatching {
            AssistantStreamSnapshot(
                messages = objects(json, "messages") { m ->
                    AssistantMessageSnapshot(
                        m.getString("messageId"), m.getString("role"), enumValueOf(m.getString("status")),
                        objects(m, "blocks") { b ->
                            when (b.getString("type")) {
                                "text" -> AssistantContentBlockSnapshot.Text(
                                    b.getString("blockId"), b.getString("text"), b.optBoolean("isComplete"),
                                )
                                "reasoning" -> AssistantContentBlockSnapshot.Reasoning(
                                    b.getString("blockId"), enumValueOf(b.getString("kind")), b.getString("text"),
                                    b.optJSONObject("metadata")?.let { metadata ->
                                        val values = metadata.optJSONObject("values")
                                        AssistantReasoningMetadata(metadata.getString("provider"),
                                            values?.keys()?.asSequence()?.filter { it.endsWith("_present") }
                                                ?.associateWith { "true" }.orEmpty())
                                    }, b.optBoolean("isComplete"),
                                )
                                else -> error("Unknown content block")
                            }
                        }, m.stringOrNull("finishReason"), m.stringOrNull("incompleteReason"),
                        m.stringOrNull("error"), m.stringOrNull("errorCode"),
                    )
                },
                toolCalls = objects(json, "toolCalls") {
                    AssistantToolCallSnapshot(it.getString("callId"), it.stringOrNull("name"),
                        it.stringOrNull("kind")?.let { k -> enumValueOf<AssistantStreamEvent.ToolKind>(k) },
                        it.getString("arguments"), enumValueOf(it.getString("status")),
                        enumValueOf(it.getString("approvalRequirement")), it.stringOrNull("approvalReason"),
                        it.stringOrNull("result"), it.stringOrNull("error"), it.stringOrNull("errorCode"))
                },
                subagents = objects(json, "subagents") {
                    AssistantSubagentSnapshot(it.getString("agentId"), it.stringOrNull("name"), it.stringOrNull("parentCallId"),
                        enumValueOf(it.getString("status")), it.getString("output"), it.stringOrNull("detail"), it.stringOrNull("error"))
                },
                searches = objects(json, "searches") {
                    AssistantSearchSnapshot(it.getString("searchId"), it.stringOrNull("query"), enumValueOf(it.getString("status")),
                        objects(it, "results") { r ->
                            AssistantSearchResult(r.getString("id"), r.getString("title"), r.stringOrNull("url"),
                                r.stringOrNull("snippet"), r.stringOrNull("publishedAt"))
                        }, it.stringOrNull("error"))
                },
                citations = objects(json, "citations") {
                    AssistantCitationSnapshot(readCitation(it.getJSONObject("citation")), it.stringOrNull("messageId"), it.stringOrNull("searchId"))
                },
                usage = json.optJSONObject("usage")?.let {
                    AssistantUsage(it.longOrNull("inputTokens"), it.longOrNull("outputTokens"), it.longOrNull("totalTokens"),
                        it.longOrNull("cachedInputTokens"), it.longOrNull("reasoningTokens"), it.longOrNull("cacheCreationInputTokens"))
                },
                lastAppliedSequenceNumber = json.longOrNull("lastAppliedSequenceNumber"),
                nextExpectedSequenceNumber = json.longOrNull("nextExpectedSequenceNumber"),
                pendingSequenceNumbers = json.optJSONArray("pendingSequenceNumbers")?.let { a ->
                    List(a.length()) { a.getLong(it) }
                }.orEmpty(),
                skippedSequenceGaps = objects(json, "skippedSequenceGaps") {
                    AssistantSequenceGap(it.getLong("firstMissing"), it.getLong("lastMissing"))
                },
                isFinal = json.optBoolean("isFinal"),
                source = enumValueOf(json.getString("source")),
                activityOrder = objects(json, "activityOrder") {
                    AssistantActivityReference(enumValueOf(it.getString("kind")), it.getString("id"), it.stringOrNull("messageId"),
                        it.stringOrNull("reasoningKind")?.let { k -> enumValueOf<AssistantReasoningKind>(k) })
                },
                runElapsedMillis = json.longOrNull("runElapsedMillis")?.takeIf { it >= 0 },
                runRounds = json.longOrNull("runRounds")?.takeIf { it in 0..Int.MAX_VALUE }?.toInt(),
                runSteps = json.longOrNull("runSteps")?.takeIf { it in 0..Int.MAX_VALUE }?.toInt(),
                runStatus = json.stringOrNull("runStatus")?.let { value ->
                    AssistantRunStatus.entries.firstOrNull { it.name == value }
                },
            )
        }.getOrNull()
    }

    /** Restoring display data never resumes a provider stream or backend operation. */
    fun restored(s: AssistantStreamSnapshot): AssistantStreamSnapshot {
        if (s.isFinal && s.runStatus != AssistantRunStatus.RUNNING) return s
        val restoredStatus = when {
            s.runStatus != null && s.runStatus != AssistantRunStatus.RUNNING -> s.runStatus
            s.messages.any { it.status == AssistantMessageStatus.FAILED } -> AssistantRunStatus.FAILED
            s.messages.any { it.status == AssistantMessageStatus.CANCELLED } -> AssistantRunStatus.CANCELLED
            else -> AssistantRunStatus.INCOMPLETE
        }
        return s.copy(
            isFinal = true,
            runStatus = restoredStatus,
            messages = s.messages.map {
                if (it.status in setOf(AssistantMessageStatus.CREATED, AssistantMessageStatus.IN_PROGRESS))
                    it.copy(status = AssistantMessageStatus.INCOMPLETE, incompleteReason = "process_restarted")
                else it
            },
            toolCalls = s.toolCalls.map {
                if (it.status in setOf(AssistantStreamEvent.ToolStatus.PENDING, AssistantStreamEvent.ToolStatus.STREAMING_ARGUMENTS,
                        AssistantStreamEvent.ToolStatus.APPROVED, AssistantStreamEvent.ToolStatus.RUNNING))
                    it.copy(status = AssistantStreamEvent.ToolStatus.INCOMPLETE)
                else it
            },
            subagents = s.subagents.map {
                if (it.status in setOf(AssistantStreamEvent.SubagentStatus.PENDING, AssistantStreamEvent.SubagentStatus.STARTING,
                        AssistantStreamEvent.SubagentStatus.RUNNING))
                    it.copy(status = AssistantStreamEvent.SubagentStatus.INCOMPLETE, detail = "process_restarted")
                else it
            },
            searches = s.searches.map {
                if (it.status in setOf(AssistantStreamEvent.SearchStatus.PENDING, AssistantStreamEvent.SearchStatus.RUNNING))
                    it.copy(status = AssistantStreamEvent.SearchStatus.INCOMPLETE, error = "process_restarted")
                else it
            },
        )
    }

    private fun citationJson(c: AssistantCitation) = JSONObject().put("id", c.id).put("url", c.url).put("title", c.title)
        .put("quote", c.quote).put("startIndex", c.startIndex).put("endIndex", c.endIndex)
        .put("fileId", c.fileId).put("documentIndex", c.documentIndex).put("startPage", c.startPage)
        .put("endPage", c.endPage).put("blockId", c.blockId)

    private fun readCitation(c: JSONObject) = AssistantCitation(
        c.getString("id"), c.stringOrNull("url"), c.stringOrNull("title"), c.stringOrNull("quote"),
        c.longOrNull("startIndex")?.toInt(), c.longOrNull("endIndex")?.toInt(), c.stringOrNull("fileId"),
        c.longOrNull("documentIndex")?.toInt(), c.longOrNull("startPage")?.toInt(), c.longOrNull("endPage")?.toInt(),
        c.stringOrNull("blockId"),
    )
    private fun <T> array(items: List<T>, encode: (T) -> JSONObject) = JSONArray().apply { items.forEach { put(encode(it)) } }
    private fun <T> objects(json: JSONObject, key: String, decode: (JSONObject) -> T): List<T> =
        json.optJSONArray(key)?.let { a -> List(a.length()) { decode(a.getJSONObject(it)) } }.orEmpty()
}

internal fun JSONObject.stringOrNull(key: String): String? = opt(key) as? String
internal fun JSONObject.longOrNull(key: String): Long? = (opt(key) as? Number)?.toLong()
