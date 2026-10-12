package com.dafeiyu.mobile.data

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * DeepSeek Chat Completions normalization, independent of HTTP and UI.
 * Only public reasoning_content is rendered. Tool calls are proposals, never executions.
 * Sources: https://api-docs.deepseek.com/api/create-chat-completion
 * https://api-docs.deepseek.com/guides/thinking_mode
 */
class DeepSeekChatProtocol(
    val messageId: String = "deepseek-${UUID.randomUUID()}",
    startSequenceNumber: Long = 0L,
    private val toolsConnected: Boolean = false,
) {
    val source = AssistantStreamSource.BACKEND
    private var sequence = startSequenceNumber
    private var started = false
    private var terminal = false
    private var finishReason: String? = null
    private var hasText = false
    private var hasThinking = false
    private data class Tool(val id: String, val name: String)
    private val tools = linkedMapOf<Int, Tool>()

    init {
        require(validId(messageId)) { "Invalid message id" }
        require(startSequenceNumber >= 0L && startSequenceNumber < Long.MAX_VALUE)
    }

    /** A JSON chunk, or the complete JSON response when streaming is disabled. */
    fun accept(body: JSONObject, streaming: Boolean = true): List<AssistantStreamEvent> {
        val previousSequence = sequence
        return try { acceptChecked(body, streaming) }
        catch (error: Exception) {
            // Invalid chunks emit nothing; the subsequent failure must stay contiguous.
            sequence = previousSequence
            throw error
        }
    }

    private fun acceptChecked(body: JSONObject, streaming: Boolean): List<AssistantStreamEvent> {
        if (terminal) return emptyList()
        if (body.has("error")) return fail("DeepSeek returned an API error", "PROVIDER_ERROR")
        val choices = body.optJSONArray("choices") ?: throw malformed()
        if (choices.length() > 1 || (!streaming && choices.length() != 1)) throw malformed()
        val events = mutableListOf<AssistantStreamEvent>()
        if (choices.length() == 1) {
            val choice = choices.optJSONObject(0) ?: throw malformed()
            if (choice.optInt("index", -1) != 0) throw malformed()
            val content = choice.optJSONObject(if (streaming) "delta" else "message") ?: throw malformed()
            val role = content.stringOrNull("role")
            if (role != null && role != "assistant") throw malformed()
            if (!started) {
                started = true
                events += AssistantStreamEvent.MessageStarted(sequence++, messageId)
            }
            val nextFinish = choice.stringOrNull("finish_reason")
            if (finishReason != null && (content.stringOrNull("content").orEmpty().isNotEmpty() ||
                    content.stringOrNull("reasoning_content").orEmpty().isNotEmpty() || content.has("tool_calls"))) {
                throw malformed()
            }
            content.stringOrNull("reasoning_content")?.takeIf { it.isNotEmpty() }?.let {
                hasThinking = true
                events += AssistantStreamEvent.UserVisibleThinkingDelta(sequence++, messageId, "thinking", it)
            }
            content.stringOrNull("content")?.takeIf { it.isNotEmpty() }?.let {
                hasText = true
                events += AssistantStreamEvent.TextDelta(sequence++, messageId, it)
            }
            val calls = content.optJSONArray("tool_calls")
            if (calls != null) {
                for (position in 0 until calls.length()) {
                    val call = calls.optJSONObject(position) ?: throw malformed()
                    val index = if (streaming) call.optInt("index", -1) else position
                    if (index !in 0..127) throw malformed()
                    val function = call.optJSONObject("function") ?: throw malformed()
                    var tool = tools[index]
                    if (tool == null) {
                        val id = call.stringOrNull("id") ?: throw malformed()
                        val name = function.stringOrNull("name") ?: throw malformed()
                        if (!validId(id) || !validId(name) || tools.values.any { it.id == id } ||
                            call.stringOrNull("type") != "function") throw malformed()
                        tool = Tool(id, name)
                        tools[index] = tool
                        events += AssistantStreamEvent.ToolCallStarted(sequence++, id, name, messageId = messageId)
                    } else if (call.stringOrNull("id")?.let { it != tool.id } == true ||
                        function.stringOrNull("name")?.let { it != tool.name } == true) {
                        throw malformed()
                    }
                    function.stringOrNull("arguments")?.takeIf { it.isNotEmpty() }?.let {
                        events += AssistantStreamEvent.ToolArgumentsDelta(sequence++, tool.id, it)
                    }
                }
            }
            if (nextFinish != null) {
                if (finishReason != null && finishReason != nextFinish) throw malformed()
                finishReason = nextFinish
            }
        }
        body.optJSONObject("usage")?.let { usage ->
            events += AssistantStreamEvent.UsageReported(sequence++, AssistantUsage(
                inputTokens = usage.token("prompt_tokens"),
                outputTokens = usage.token("completion_tokens"),
                totalTokens = usage.token("total_tokens"),
                cachedInputTokens = usage.token("prompt_cache_hit_tokens")
                    ?: usage.optJSONObject("prompt_tokens_details")?.token("cached_tokens"),
                reasoningTokens = usage.optJSONObject("completion_tokens_details")?.token("reasoning_tokens"),
            ))
        }
        if (!streaming) events += end()
        return events
    }

    /** Called on [DONE] or clean EOF. A finish_reason is required even after [DONE]. */
    fun end(): List<AssistantStreamEvent> {
        if (terminal) return emptyList()
        terminal = true
        val events = mutableListOf<AssistantStreamEvent>()
        if (hasText) events += AssistantStreamEvent.ContentBlockDone(sequence++, messageId, "text")
        if (hasThinking) events += AssistantStreamEvent.ContentBlockDone(
            sequence++, messageId, "thinking", AssistantReasoningKind.USER_VISIBLE_THINKING,
        )
        val finishedArguments = finishReason in setOf("stop", "tool_calls")
        tools.values.forEach {
            events += if (finishedArguments) AssistantStreamEvent.ToolArgumentsDone(sequence++, it.id)
            else AssistantStreamEvent.ToolStatusChanged(sequence++, it.id, AssistantStreamEvent.ToolStatus.INCOMPLETE)
        }
        events += if ((finishReason == "stop" && tools.isEmpty()) ||
            (toolsConnected && tools.isNotEmpty() && finishedArguments)) {
            AssistantStreamEvent.MessageCompleted(sequence++, messageId, finishReason)
        } else {
            val reason = when (finishReason) {
                null -> "eof_without_finish_reason"
                "tool_calls" -> "tool_execution_not_connected"
                "stop" -> "tool_execution_not_connected"
                "length", "content_filter", "insufficient_system_resource", "aborted" -> finishReason
                else -> "unknown_finish_reason"
            }
            AssistantStreamEvent.MessageIncomplete(sequence++, messageId, reason)
        }
        return events
    }

    fun fail(message: String, code: String): List<AssistantStreamEvent> {
        if (terminal) return emptyList()
        terminal = true
        return listOf(AssistantStreamEvent.MessageFailed(sequence++, messageId, message, code))
    }

    fun cancel(): List<AssistantStreamEvent> {
        if (terminal) return emptyList()
        terminal = true
        return listOf(AssistantStreamEvent.MessageCancelled(sequence++, messageId, "user_cancelled"))
    }

    /** Transport cancellation can interrupt delivery of an already normalized batch. */
    internal fun cancelAfterDelivered(nextSequenceNumber: Long): List<AssistantStreamEvent> {
        sequence = nextSequenceNumber
        terminal = false
        return cancel()
    }

    private fun JSONObject.token(name: String): Long? {
        val value = opt(name)
        if (value == null || value === JSONObject.NULL) return null
        if (value !is Number) throw malformed()
        val token = value.toString().toLongOrNull() ?: throw malformed()
        if (token < 0L) throw malformed()
        return token
    }

    private fun JSONObject.stringOrNull(name: String): String? {
        val value = opt(name)
        if (value == null || value === JSONObject.NULL) return null
        return value as? String ?: throw malformed()
    }

    private fun validId(value: String) = value.isNotBlank() && value.length <= 512 && value.none { it.isISOControl() }
    private fun malformed() = IOException("Invalid DeepSeek response")
}

/**
 * Byte-bounded SSE framing: LF/CRLF/CR, comments, BOM and multiline data.
 * Does not use unbounded readLine(), and decodes UTF-8 strictly across network fragments.
 */
internal class DeepSeekSseReader(
    private val input: InputStream,
    private val maxLineBytes: Int,
    private val maxEventBytes: Int,
    private val maxResponseBytes: Int,
    private val checkActive: () -> Unit = {},
) {
    private var total = 0L
    private var skipLf = false
    private var firstLine = true

    fun nextData(): String? {
        val data = StringBuilder()
        var eventBytes = 0
        var hasData = false
        while (true) {
            val line = nextLine()
            if (line == null || line.isEmpty()) {
                if (hasData) return data.toString()
                if (line == null) return null
                eventBytes = 0
                continue
            }
            eventBytes += line.toByteArray(StandardCharsets.UTF_8).size + 1
            if (eventBytes > maxEventBytes) throw IOException("DeepSeek SSE event limit exceeded")
            if (line.startsWith(":")) continue
            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            if (field != "data") continue
            var value = if (colon < 0) "" else line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)
            if (hasData) data.append('\n')
            data.append(value)
            hasData = true
        }
    }

    private fun nextLine(): String? {
        val bytes = ByteArrayOutputStream(minOf(maxLineBytes, 4096))
        while (true) {
            checkActive()
            val byte = input.read()
            if (byte >= 0 && ++total > maxResponseBytes) throw IOException("DeepSeek response limit exceeded")
            if (skipLf) {
                skipLf = false
                if (byte == 10) continue
            }
            if (byte < 0 && bytes.size() == 0) return null
            if (byte < 0 || byte == 10 || byte == 13) {
                skipLf = byte == 13
                val decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                val line = decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
                return if (firstLine) {
                    firstLine = false
                    line.removePrefix("\uFEFF")
                } else line
            }
            if (bytes.size() >= maxLineBytes) throw IOException("DeepSeek SSE line limit exceeded")
            bytes.write(byte)
        }
    }
}
