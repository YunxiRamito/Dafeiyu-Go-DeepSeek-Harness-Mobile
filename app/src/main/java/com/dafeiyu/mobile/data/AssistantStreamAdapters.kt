package com.dafeiyu.mobile.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One adapter per provider stream, consumed in transport order. Unknown events consume no local
 * sequence number. Network/SSE framing and backend execution are outside this API.
 */
interface AssistantStreamAdapter {
    val source: AssistantStreamSource
    fun accept(eventType: String, payload: JSONObject): List<AssistantStreamEvent>
}

class OpenAiResponsesStreamAdapter(
    private val allowPublicThinking: Boolean = true,
) : AssistantStreamAdapter {
    override val source = AssistantStreamSource.OPENAI
    private var messageId = "openai-response"
    private var next = 0L
    private val callIds = mutableMapOf<String, String>()
    private val outputIds = mutableMapOf<Int, String>()
    private val startedTools = mutableSetOf<String>()
    private val startedSearches = mutableSetOf<String>()
    private val shellCommands = linkedMapOf<String, MutableMap<Int, String>>()
    private var lastProviderSequence = -1L

    override fun accept(eventType: String, payload: JSONObject): List<AssistantStreamEvent> {
        payload.longOrNull("sequence_number")?.let {
            if (it <= lastProviderSequence) return emptyList()
            lastProviderSequence = it
        }
        val events = mutableListOf<AssistantStreamEvent>()
        fun emit(make: (Long) -> AssistantStreamEvent) { events += make(next++) }
        fun tool(id: String, name: String, kind: AssistantStreamEvent.ToolKind = AssistantStreamEvent.ToolKind.TOOL) {
            if (startedTools.add(id)) emit { AssistantStreamEvent.ToolCallStarted(it, id, name, kind) }
        }
        fun search(id: String, query: String? = null) {
            if (startedSearches.add(id)) emit { AssistantStreamEvent.SearchStarted(it, id, query) }
        }
        fun citation(annotation: JSONObject, blockId: String, index: Int) {
            emit { AssistantStreamEvent.CitationAdded(it, providerCitation(annotation, "$blockId:citation:$index", blockId), messageId) }
        }
        fun item(item: JSONObject, outputIndex: Int, done: Boolean) {
            val id = item.stringOrNull("id") ?: "output-$outputIndex"
            outputIds[outputIndex] = id
            val call = item.stringOrNull("call_id") ?: id
            callIds[id] = call
            when (item.optString("type")) {
                "function_call", "custom_tool_call" -> {
                    tool(call, item.optString("name", "tool"))
                    val args = item.stringOrNull("arguments") ?: item.stringOrNull("input")
                    if (done) emit { AssistantStreamEvent.ToolArgumentsDone(it, call, args) }
                    else if (!args.isNullOrEmpty()) emit { AssistantStreamEvent.ToolArgumentsDelta(it, call, args) }
                }
                "shell_call", "local_shell_call" -> {
                    tool(call, "shell", AssistantStreamEvent.ToolKind.COMMAND)
                    if (done) emit { AssistantStreamEvent.ToolArgumentsDone(it, call, item.optJSONObject("action")?.toString()) }
                }
                "shell_call_output" -> {
                    tool(call, "shell", AssistantStreamEvent.ToolKind.COMMAND)
                    if (done) shellOutput(item.optJSONArray("output"), call, ::emit)
                }
                "mcp_call", "code_interpreter_call" -> {
                    tool(call, item.optString("name", item.optString("type")),
                        if (item.optString("type") == "code_interpreter_call") AssistantStreamEvent.ToolKind.COMMAND else AssistantStreamEvent.ToolKind.TOOL)
                    if (done) {
                        val error = item.opt("error")?.takeUnless { it == JSONObject.NULL }
                        if (error != null) emit { AssistantStreamEvent.ToolError(it, call, error.toString()) }
                        else if (item.has("output") && !item.isNull("output")) emit { AssistantStreamEvent.ToolResult(it, call, item.opt("output")?.toString().orEmpty()) }
                        else if (item.optString("status") == "completed")
                            emit { AssistantStreamEvent.ToolStatusChanged(it, call, AssistantStreamEvent.ToolStatus.SUCCEEDED) }
                    }
                }
                "mcp_approval_request" -> {
                    tool(id, item.optString("name", "mcp"))
                    emit { AssistantStreamEvent.ToolArgumentsDone(it, id, item.stringOrNull("arguments")) }
                    emit { AssistantStreamEvent.ToolApprovalRequired(it, id, item.stringOrNull("server_label")) }
                }
                "web_search_call", "file_search_call" -> {
                    val action = item.optJSONObject("action")
                    search(id, action?.stringOrNull("query") ?: item.optJSONArray("queries")?.toString())
                    val results = item.optJSONArray("results") ?: action?.optJSONArray("sources")
                    results?.objects()?.forEachIndexed { index, r ->
                        emit { AssistantStreamEvent.SearchResultAdded(it, id,
                            AssistantSearchResult(r.stringOrNull("file_id") ?: "$id:result:$index",
                                r.optString("title", r.optString("filename")), r.stringOrNull("url"),
                                r.stringOrNull("text") ?: r.stringOrNull("snippet"))) }
                    }
                    if (done && item.optString("status") == "completed")
                        emit { AssistantStreamEvent.SearchStatusChanged(it, id, AssistantStreamEvent.SearchStatus.COMPLETED) }
                }
                "message" -> if (done) item.optJSONArray("content")?.objects()?.forEachIndexed { index, b ->
                    val blockId = "$id:text:$index"
                    if (b.optString("type") == "output_text") {
                        emit { AssistantStreamEvent.ContentBlockDone(it, messageId, blockId, finalText = b.stringOrNull("text")) }
                        b.optJSONArray("annotations")?.objects()?.forEachIndexed { n, a -> citation(a, blockId, n) }
                    }
                }
                "reasoning" -> {
                    if (item.has("encrypted_content") && !item.isNull("encrypted_content")) emit {
                        AssistantStreamEvent.ReasoningOpaqueMetadata(it, messageId, id, "openai",
                            values = mapOf("encrypted_present" to "true"))
                    }
                    if (done) item.optJSONArray("summary")?.objects()?.forEachIndexed { index, b ->
                        if (b.optString("type") == "summary_text") emit {
                            AssistantStreamEvent.ContentBlockDone(it, messageId, "$id:summary:$index",
                                AssistantReasoningKind.SUMMARY, b.stringOrNull("text"))
                        }
                    }
                }
            }
        }
        val response = payload.optJSONObject("response")
        response?.stringOrNull("id")?.let { messageId = it }
        val rawItem = payload.stringOrNull("item_id") ?: outputIds[payload.optInt("output_index")] ?: "output-${payload.optInt("output_index")}"
        val call = callIds[rawItem] ?: rawItem
        val textBlock = "$rawItem:text:${payload.optInt("content_index")}"
        val reasoningBlock = "$rawItem:summary:${payload.optInt("summary_index")}"
        val thinkingBlock = "$rawItem:thinking:${payload.optInt("content_index")}"
        when (eventType) {
            "response.created", "response.in_progress" -> emit { AssistantStreamEvent.MessageStarted(it, messageId) }
            "response.output_text.delta" -> emit { AssistantStreamEvent.TextDelta(it, messageId, payload.optString("delta"), textBlock) }
            "response.output_text.done" -> emit { AssistantStreamEvent.ContentBlockDone(it, messageId, textBlock, finalText = payload.stringOrNull("text")) }
            "response.reasoning_summary_text.delta" -> if (payload.optString("delta").isNotEmpty())
                emit { AssistantStreamEvent.ReasoningSummaryDelta(it, messageId, reasoningBlock, payload.optString("delta")) }
            "response.reasoning_summary_text.done" -> emit {
                AssistantStreamEvent.ContentBlockDone(it, messageId, reasoningBlock, AssistantReasoningKind.SUMMARY, payload.stringOrNull("text"))
            }
            "response.reasoning_text.delta" -> if (allowPublicThinking && payload.optString("delta").isNotEmpty())
                emit { AssistantStreamEvent.UserVisibleThinkingDelta(it, messageId, thinkingBlock, payload.optString("delta")) }
            "response.reasoning_text.done" -> if (allowPublicThinking) emit {
                AssistantStreamEvent.ContentBlockDone(it, messageId, thinkingBlock, AssistantReasoningKind.USER_VISIBLE_THINKING, payload.stringOrNull("text"))
            }
            "response.output_item.added", "response.output_item.done" -> payload.optJSONObject("item")?.let {
                item(it, payload.optInt("output_index"), eventType.endsWith(".done"))
            }
            "response.function_call_arguments.delta", "response.custom_tool_call_input.delta", "response.mcp_call_arguments.delta",
            "response.code_interpreter_call_code.delta" -> emit { AssistantStreamEvent.ToolArgumentsDelta(it, call, payload.optString("delta")) }
            "response.function_call_arguments.done", "response.custom_tool_call_input.done", "response.mcp_call_arguments.done",
            "response.code_interpreter_call_code.done" -> emit {
                AssistantStreamEvent.ToolArgumentsDone(it, call, payload.stringOrNull("arguments") ?: payload.stringOrNull("input") ?: payload.stringOrNull("code"))
            }
            "response.shell_call_command.added", "response.shell_call_command.delta", "response.shell_call_command.done" -> {
                tool(call, "shell", AssistantStreamEvent.ToolKind.COMMAND)
                val commands = shellCommands.getOrPut(call) { sortedMapOf() }
                val commandIndex = payload.optInt("command_index")
                commands[commandIndex] = if (eventType.endsWith(".delta"))
                    commands[commandIndex].orEmpty() + payload.optString("delta")
                else payload.optString("command")
                // "command.done" completes proposed text, not an operating-system process.
                if (eventType.endsWith(".done")) emit { AssistantStreamEvent.ToolArgumentsDone(it, call, JSONArray(commands.values.toList()).toString()) }
            }
            "response.shell_call_output_content.delta" -> {
                payload.optJSONObject("delta")?.let { d ->
                    emit { AssistantStreamEvent.ToolOutputDelta(it, call, d.optString("stdout") + d.optString("stderr")) }
                }
            }
            "response.shell_call_output_content.done" -> shellOutput(payload.optJSONArray("output"), call, ::emit)
            "response.output_text.annotation.added" -> payload.optJSONObject("annotation")?.let {
                citation(it, textBlock, payload.optInt("annotation_index"))
            }
            "response.web_search_call.in_progress", "response.web_search_call.searching",
            "response.file_search_call.in_progress", "response.file_search_call.searching" -> search(rawItem)
            "response.web_search_call.completed", "response.file_search_call.completed" -> {
                search(rawItem)
                emit { AssistantStreamEvent.SearchStatusChanged(it, rawItem, AssistantStreamEvent.SearchStatus.COMPLETED) }
            }
            "response.mcp_call.in_progress", "response.code_interpreter_call.in_progress", "response.code_interpreter_call.interpreting" ->
                emit { AssistantStreamEvent.ToolStatusChanged(it, call, AssistantStreamEvent.ToolStatus.RUNNING) }
            "response.mcp_call.completed", "response.code_interpreter_call.completed" ->
                emit { AssistantStreamEvent.ToolStatusChanged(it, call, AssistantStreamEvent.ToolStatus.SUCCEEDED) }
            "response.mcp_call.failed" -> emit { AssistantStreamEvent.ToolError(it, call, "provider_tool_failed") }
            "response.completed", "response.incomplete", "response.failed" -> {
                response?.optJSONArray("output")?.objects()?.forEachIndexed { index, output -> item(output, index, true) }
                response?.optJSONObject("usage")?.let { usage -> emit { AssistantStreamEvent.UsageReported(it, openAiUsage(usage)) } }
                when (eventType) {
                    "response.completed" -> emit { AssistantStreamEvent.MessageCompleted(it, messageId, "completed") }
                    "response.incomplete" -> emit { AssistantStreamEvent.MessageIncomplete(it, messageId, response?.optJSONObject("incomplete_details")?.stringOrNull("reason")) }
                    else -> emit {
                        AssistantStreamEvent.MessageFailed(it, messageId, response?.optJSONObject("error")?.optString("message", "provider_failed") ?: "provider_failed",
                            response?.optJSONObject("error")?.stringOrNull("code"))
                    }
                }
            }
            "error" -> emit { AssistantStreamEvent.MessageFailed(it, messageId, payload.optString("message", "provider_error"), payload.stringOrNull("code")) }
        }
        return events
    }

    private fun shellOutput(output: JSONArray?, call: String, emit: ((Long) -> AssistantStreamEvent) -> Unit) {
        val chunks = output?.objects().orEmpty()
        if (chunks.isEmpty()) return
        val text = chunks.joinToString("") { it.optString("stdout") + it.optString("stderr") }
        val outcomes = chunks.mapNotNull { it.optJSONObject("outcome") }
        val failure = outcomes.firstOrNull { it.optString("type") == "timeout" || (it.optString("type") == "exit" && it.optInt("exit_code") != 0) }
        if (failure != null) {
            emit { AssistantStreamEvent.ToolOutputDelta(it, call, text) }
            emit { AssistantStreamEvent.ToolError(it, call, failure.toString(), failure.optString("type")) }
        } else if (outcomes.size == chunks.size && outcomes.all { it.optString("type") == "exit" && it.optInt("exit_code") == 0 })
            emit { AssistantStreamEvent.ToolResult(it, call, text) }
        else emit { AssistantStreamEvent.ToolOutputDelta(it, call, text) }
    }
}

class AnthropicMessagesStreamAdapter(
    private val allowPublicThinking: Boolean = true,
) : AssistantStreamAdapter {
    override val source = AssistantStreamSource.ANTHROPIC
    private var messageId = "anthropic-message"
    private var next = 0L
    private data class Block(val type: String, val callId: String? = null, var hasText: Boolean = false)
    private val blocks = mutableMapOf<Int, Block>()
    private var stopReason: String? = null

    override fun accept(eventType: String, payload: JSONObject): List<AssistantStreamEvent> {
        val events = mutableListOf<AssistantStreamEvent>()
        fun emit(make: (Long) -> AssistantStreamEvent) { events += make(next++) }
        val index = payload.optInt("index")
        val blockId = "block-$index"
        fun thinking(text: String) {
            if (allowPublicThinking && text.isNotEmpty()) {
                blocks[index]?.hasText = true
                emit { AssistantStreamEvent.UserVisibleThinkingDelta(it, messageId, blockId, text) }
            }
        }
        fun citation(c: JSONObject, n: Int) {
            emit { AssistantStreamEvent.CitationAdded(it, providerCitation(c, "$messageId:$blockId:citation:$n", blockId), messageId) }
        }
        when (eventType) {
            "message_start" -> payload.optJSONObject("message")?.let { m ->
                messageId = m.optString("id", messageId)
                emit { AssistantStreamEvent.MessageStarted(it, messageId, m.optString("role", "assistant")) }
                m.optJSONObject("usage")?.let { u -> emit { AssistantStreamEvent.UsageReported(it, anthropicUsage(u)) } }
            }
            "content_block_start" -> payload.optJSONObject("content_block")?.let { b ->
                val type = b.optString("type")
                blocks[index] = Block(type, b.stringOrNull("id") ?: b.stringOrNull("tool_use_id"))
                when (type) {
                    "text" -> {
                        if (b.optString("text").isNotEmpty()) {
                            blocks[index]?.hasText = true
                            emit { AssistantStreamEvent.TextDelta(it, messageId, b.optString("text"), blockId) }
                        }
                        b.optJSONArray("citations")?.objects()?.forEachIndexed { n, c -> citation(c, n) }
                    }
                    "thinking" -> thinking(b.optString("thinking"))
                    "redacted_thinking" -> emit {
                        AssistantStreamEvent.ReasoningOpaqueMetadata(it, messageId, blockId, "anthropic",
                            AssistantReasoningKind.USER_VISIBLE_THINKING, values = mapOf("redacted_present" to "true"))
                    }
                    "tool_use", "server_tool_use" -> {
                        val call = blocks.getValue(index).callId ?: blockId
                        val input = b.optJSONObject("input")?.takeIf { it.length() > 0 }?.toString().orEmpty()
                        val name = b.optString("name", "tool")
                        val kind = if (type == "server_tool_use" && name in setOf("bash_code_execution", "code_execution"))
                            AssistantStreamEvent.ToolKind.COMMAND else AssistantStreamEvent.ToolKind.TOOL
                        emit { AssistantStreamEvent.ToolCallStarted(it, call, name, kind, input) }
                        if (type == "server_tool_use") {
                            emit { AssistantStreamEvent.ToolStatusChanged(it, call, AssistantStreamEvent.ToolStatus.RUNNING) }
                            if (b.optString("name") == "web_search") emit {
                                AssistantStreamEvent.SearchStarted(it, call, b.optJSONObject("input")?.stringOrNull("query"))
                            }
                        }
                    }
                    "web_search_tool_result" -> {
                        val call = b.stringOrNull("tool_use_id") ?: blockId
                        val content = b.opt("content")
                        if (content is JSONArray) {
                            content.objects().forEachIndexed { n, r ->
                                emit { AssistantStreamEvent.SearchResultAdded(it, call,
                                    AssistantSearchResult("$call:$n", r.optString("title"), r.stringOrNull("url"), publishedAt = r.stringOrNull("page_age"))) }
                            }
                            emit { AssistantStreamEvent.SearchStatusChanged(it, call, AssistantStreamEvent.SearchStatus.COMPLETED) }
                            emit { AssistantStreamEvent.ToolStatusChanged(it, call, AssistantStreamEvent.ToolStatus.SUCCEEDED) }
                        } else if (content is JSONObject) {
                            emit { AssistantStreamEvent.SearchStatusChanged(it, call, AssistantStreamEvent.SearchStatus.FAILED, content.stringOrNull("error_code")) }
                            emit { AssistantStreamEvent.ToolError(it, call, content.optString("error_code", "search_failed")) }
                        }
                    }
                    "bash_code_execution_tool_result", "code_execution_tool_result", "text_editor_code_execution_tool_result" -> {
                        val call = b.stringOrNull("tool_use_id") ?: blockId
                        b.optJSONObject("content")?.let { c ->
                            val output = c.optString("stdout") + c.optString("stderr")
                            val code = c.longOrNull("return_code")
                            if (c.has("error_code") || (code != null && code != 0L)) {
                                emit { AssistantStreamEvent.ToolOutputDelta(it, call, output) }
                                emit { AssistantStreamEvent.ToolError(it, call, c.optString("error_code", "exit_$code")) }
                            } else if (code == 0L) emit { AssistantStreamEvent.ToolResult(it, call, output) }
                            else emit { AssistantStreamEvent.ToolOutputDelta(it, call, output) }
                        }
                    }
                }
            }
            "content_block_delta" -> payload.optJSONObject("delta")?.let { d ->
                when (d.optString("type")) {
                    "text_delta" -> if (blocks[index]?.type == "text") {
                        blocks[index]?.hasText = true
                        emit { AssistantStreamEvent.TextDelta(it, messageId, d.optString("text"), blockId) }
                    }
                    "thinking_delta" -> if (blocks[index]?.type == "thinking") thinking(d.optString("thinking"))
                    "signature_delta" -> emit {
                        AssistantStreamEvent.ReasoningOpaqueMetadata(it, messageId, blockId, "anthropic",
                            AssistantReasoningKind.USER_VISIBLE_THINKING, hasSignature = true)
                    }
                    "input_json_delta" -> blocks[index]?.callId?.let { call ->
                        emit { AssistantStreamEvent.ToolArgumentsDelta(it, call, d.optString("partial_json")) }
                    }
                    "citations_delta" -> d.optJSONObject("citation")?.let { c -> citation(c, next.toInt()) }
                }
            }
            "content_block_stop" -> blocks[index]?.let { b ->
                when (b.type) {
                    "text" -> if (b.hasText) emit { AssistantStreamEvent.ContentBlockDone(it, messageId, blockId) }
                    "thinking" -> if (b.hasText) emit { AssistantStreamEvent.ContentBlockDone(it, messageId, blockId, AssistantReasoningKind.USER_VISIBLE_THINKING) }
                    "tool_use", "server_tool_use" -> b.callId?.let { call -> emit { AssistantStreamEvent.ToolArgumentsDone(it, call) } }
                }
            }
            "message_delta" -> {
                payload.optJSONObject("delta")?.stringOrNull("stop_reason")?.let { stopReason = it }
                payload.optJSONObject("usage")?.let { u -> emit { AssistantStreamEvent.UsageReported(it, anthropicUsage(u)) } }
            }
            "message_stop" -> if (stopReason == "max_tokens" || stopReason == "model_context_window_exceeded")
                emit { AssistantStreamEvent.MessageIncomplete(it, messageId, stopReason) }
            else emit { AssistantStreamEvent.MessageCompleted(it, messageId, stopReason) }
            "error" -> payload.optJSONObject("error")?.let { e ->
                emit { AssistantStreamEvent.MessageFailed(it, messageId, e.optString("message", "provider_error"), e.stringOrNull("type")) }
            }
        }
        return events
    }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
private fun openAiUsage(u: JSONObject) = AssistantUsage(
    u.longOrNull("input_tokens"), u.longOrNull("output_tokens"), u.longOrNull("total_tokens"),
    u.optJSONObject("input_tokens_details")?.longOrNull("cached_tokens"),
    u.optJSONObject("output_tokens_details")?.longOrNull("reasoning_tokens"),
)
private fun anthropicUsage(u: JSONObject) = AssistantUsage(
    inputTokens = u.longOrNull("input_tokens"), outputTokens = u.longOrNull("output_tokens"),
    cachedInputTokens = u.longOrNull("cache_read_input_tokens"), cacheCreationInputTokens = u.longOrNull("cache_creation_input_tokens"),
)
private fun providerCitation(c: JSONObject, id: String, blockId: String) = AssistantCitation(
    id = id, url = c.stringOrNull("url"), title = c.stringOrNull("title") ?: c.stringOrNull("filename") ?: c.stringOrNull("document_title"),
    quote = c.stringOrNull("cited_text"),
    startIndex = (c.longOrNull("start_index") ?: c.longOrNull("start_char_index"))?.toInt(),
    endIndex = (c.longOrNull("end_index") ?: c.longOrNull("end_char_index"))?.toInt(),
    fileId = c.stringOrNull("file_id"), documentIndex = c.longOrNull("document_index")?.toInt(),
    startPage = c.longOrNull("start_page_number")?.toInt(), endPage = c.longOrNull("end_page_number")?.toInt(), blockId = blockId,
)
