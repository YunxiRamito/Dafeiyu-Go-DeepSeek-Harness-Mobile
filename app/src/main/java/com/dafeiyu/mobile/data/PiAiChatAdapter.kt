package com.dafeiyu.mobile.data

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

data class PiAiChatRequest(
    val providerId: String,
    val modelId: String,
    val context: JSONObject,
    val reasoning: String? = null,
    val requestId: String = UUID.randomUUID().toString(),
    val sequenceStart: Long = 0,
    val allowPublicThinking: Boolean = true,
)

/** A frozen tool call is data. Only the Android dispatcher may execute it. */
class PiAiToolCall internal constructor(
    val id: String,
    val name: String,
    private val argumentsJson: String,
    val namespace: String? = null,
) {
    fun arguments(): JSONObject = JSONObject(argumentsJson)
    override fun toString(): String = "PiAiToolCall(id=$id, name=$name)"
}

class PiAiChatTurn internal constructor(
    val snapshot: AssistantStreamSnapshot,
    private val nativeMessageJson: String?,
) {
    val completed: Boolean get() = snapshot.messages.isNotEmpty() &&
        snapshot.messages.all { it.status == AssistantMessageStatus.COMPLETED }

    /** Keep opaque signatures in trusted transcript storage, never in the UI snapshot. */
    fun assistantHistory(): JSONObject? = nativeMessageJson?.let { JSONObject(it) }

    fun toolCalls(): List<PiAiToolCall> {
        if (!completed) return emptyList()
        val content = assistantHistory()?.optJSONArray("content") ?: return emptyList()
        return (0 until content.length()).mapNotNull { index ->
            content.optJSONObject(index)?.takeIf { it.optString("type") == "toolCall" }?.let {
                PiAiToolCall(it.getString("id"), it.getString("name"), it.getJSONObject("arguments").toString(),
                    it.opt("namespace") as? String)
            }
        }
    }

    override fun toString(): String = "PiAiChatTurn(completed=$completed)"
}

/** One adapter per stream. Unknown events consume no sequence number. No tool is run here. */
class PiAiStreamAdapter(
    private val messageId: String,
    sequenceStart: Long = 0,
    private val allowPublicThinking: Boolean = true,
) {
    private var next = sequenceStart
    private var started = false
    private var terminal = false
    private var provider = "pi-ai"
    private val tools = mutableMapOf<Int, String>()
    private val toolNames = mutableMapOf<String, String>()
    private val argumentDeltas = mutableMapOf<Int, StringBuilder>()
    private var nativeMessage: String? = null

    init { require(messageId.isNotBlank() && messageId.length <= 160 && sequenceStart >= 0) }

    fun accept(event: JSONObject): List<AssistantStreamEvent> {
        if (terminal) return emptyList()
        val type = event.getString("type")
        if (type !in EVENT_TYPES) return emptyList()
        val result = mutableListOf<AssistantStreamEvent>()
        fun emit(factory: (Long) -> AssistantStreamEvent) { result += factory(next++) }
        if (!started) {
            started = true
            emit { AssistantStreamEvent.MessageStarted(it, messageId) }
        }
        val index = event.optInt("contentIndex", 0)
        require(index in 0..511) { "模型内容块过多" }
        val blockId = "$messageId:block:$index"
        fun tool(call: JSONObject?, final: Boolean = false) {
            if (call == null) return
            val id = (call.opt("id") as? String)?.takeIf { it.isNotBlank() } ?: return
            val name = (call.opt("name") as? String)?.takeIf { it.isNotBlank() } ?: return
            require(id.length <= 160 && name.length <= 160 && id.none(Char::isISOControl) && name.none(Char::isISOControl))
            val old = tools[index]
            require(old == null || old == id) { "流中的工具 ID 已改变" }
            tools[index] = id
            val previousName = toolNames.putIfAbsent(id, name)
            require(previousName == null || previousName == name) { "流中的工具名称已改变" }
            if (previousName == null) {
                emit { AssistantStreamEvent.ToolCallStarted(it, id, name,
                    approvalRequirement = AssistantStreamEvent.ApprovalRequirement.POLICY_DEPENDENT, messageId = messageId) }
                argumentDeltas.remove(index)?.takeIf { it.isNotEmpty() }?.let { buffered ->
                    emit { AssistantStreamEvent.ToolArgumentsDelta(it, id, buffered.toString()) }
                }
            }
            if (final) {
                val arguments = call.getJSONObject("arguments")
                require(arguments.toString().toByteArray().size <= MAX_CONTENT_BYTES)
                emit { AssistantStreamEvent.ToolArgumentsDone(it, id, arguments.toString()) }
            }
        }
        fun textDone(content: JSONObject, contentIndex: Int) {
            val id = "$messageId:block:$contentIndex"
            when (content.getString("type")) {
                "text" -> emit { AssistantStreamEvent.ContentBlockDone(it, messageId, id,
                    finalText = content.getString("text")) }
                "thinking" -> {
                    val redacted = content.optBoolean("redacted")
                    if (content.has("thinkingSignature")) emit {
                        AssistantStreamEvent.ReasoningOpaqueMetadata(it, messageId, id, provider,
                            AssistantReasoningKind.USER_VISIBLE_THINKING, hasSignature = true,
                            values = if (redacted) mapOf("redacted_present" to "true") else emptyMap())
                    }
                    if (allowPublicThinking && !redacted) emit {
                        AssistantStreamEvent.ContentBlockDone(it, messageId, id,
                            AssistantReasoningKind.USER_VISIBLE_THINKING, content.getString("thinking"))
                    }
                }
            }
        }
        when (type) {
            "start" -> provider = event.optJSONObject("partial")?.optString("provider")?.takeIf { it.isNotBlank() } ?: provider
            "text_delta" -> emit { AssistantStreamEvent.TextDelta(it, messageId, boundedText(event, "delta"), blockId) }
            "text_end" -> emit { AssistantStreamEvent.ContentBlockDone(it, messageId, blockId,
                finalText = boundedText(event, "content")) }
            "thinking_delta" -> if (allowPublicThinking) emit {
                AssistantStreamEvent.UserVisibleThinkingDelta(it, messageId, blockId, boundedText(event, "delta"))
            }
            "thinking_end" -> if (allowPublicThinking) emit {
                AssistantStreamEvent.ContentBlockDone(it, messageId, blockId,
                    AssistantReasoningKind.USER_VISIBLE_THINKING, boundedText(event, "content"))
            }
            "toolcall_start" -> tool(event.optJSONObject("toolCall"))
            "toolcall_delta" -> {
                tool(event.optJSONObject("toolCall"))
                val delta = boundedText(event, "delta")
                val id = tools[index]
                if (id != null) emit { AssistantStreamEvent.ToolArgumentsDelta(it, id, delta) }
                else {
                    val buffer = argumentDeltas.getOrPut(index) { StringBuilder() }
                    require(buffer.length + delta.length <= MAX_CONTENT_BYTES)
                    buffer.append(delta)
                }
            }
            "toolcall_end" -> tool(event.getJSONObject("toolCall"), final = true)
            "done" -> {
                val message = PiAiTranscriptAdapter.assistant(event.getJSONObject("message"))
                nativeMessage = message.toString()
                provider = message.getString("provider")
                val content = message.getJSONArray("content")
                for (contentIndex in 0 until content.length()) {
                    val block = content.getJSONObject(contentIndex)
                    textDone(block, contentIndex)
                    if (block.optString("type") == "toolCall") {
                        val id = block.getString("id")
                        if (!toolNames.containsKey(id)) {
                            val name = block.getString("name")
                            toolNames[id] = name
                            emit { AssistantStreamEvent.ToolCallStarted(it, id, name,
                                approvalRequirement = AssistantStreamEvent.ApprovalRequirement.POLICY_DEPENDENT, messageId = messageId) }
                        }
                        emit { AssistantStreamEvent.ToolArgumentsDone(it, id, block.getJSONObject("arguments").toString()) }
                    }
                }
                message.optJSONObject("usage")?.let { usage -> emit { AssistantStreamEvent.UsageReported(it, piUsage(usage)) } }
                terminal = true
                when (message.getString("stopReason")) {
                    "stop", "toolUse" -> emit { AssistantStreamEvent.MessageCompleted(it, messageId, message.getString("stopReason")) }
                    else -> emit { AssistantStreamEvent.MessageIncomplete(it, messageId, message.getString("stopReason")) }
                }
            }
            "error" -> {
                terminal = true
                if (event.optString("reason") == "aborted") emit { AssistantStreamEvent.MessageCancelled(it, messageId, "provider_cancelled") }
                else emit { AssistantStreamEvent.MessageFailed(it, messageId, "模型请求失败，请检查提供商配置、认证和网络", "PROVIDER_FAILED") }
            }
        }
        return result
    }

    fun finishWithoutTerminal(): List<AssistantStreamEvent> = if (terminal) emptyList() else {
        terminal = true
        listOf(AssistantStreamEvent.MessageIncomplete(next++, messageId, "worker_missing_terminal"))
    }

    fun failure(cancelled: Boolean): List<AssistantStreamEvent> = if (terminal) emptyList() else {
        terminal = true
        if (cancelled) listOf(AssistantStreamEvent.MessageCancelled(next++, messageId, "cancelled"))
        else listOf(AssistantStreamEvent.MessageFailed(next++, messageId, "模型运行器退出或请求失败", "WORKER_FAILED"))
    }

    internal fun turn(snapshot: AssistantStreamSnapshot) = PiAiChatTurn(snapshot, nativeMessage)

    private fun boundedText(json: JSONObject, key: String): String = json.getString(key).also {
        require(it.toByteArray().size <= MAX_CONTENT_BYTES)
    }

    private companion object {
        const val MAX_CONTENT_BYTES = 1024 * 1024
        val EVENT_TYPES = setOf("start", "text_start", "text_delta", "text_end", "thinking_start", "thinking_delta", "thinking_end",
            "toolcall_start", "toolcall_delta", "toolcall_end", "done", "error")
    }
}

/** Trusted, bounded transcript conversion; importing this data never resumes a task or runs a tool. */
object PiAiTranscriptAdapter {
    fun assistant(raw: JSONObject): JSONObject {
        require(raw.toString().toByteArray().size <= 4 * 1024 * 1024 && raw.getString("role") == "assistant")
        val content = raw.getJSONArray("content")
        require(content.length() <= 512)
        val normalized = JSONArray()
        val ids = mutableSetOf<String>()
        for (index in 0 until content.length()) {
            val block = content.getJSONObject(index)
            val type = block.getString("type")
            val copied = JSONObject().put("type", type)
            when (type) {
                "text" -> copied.put("text", text(block, "text"))
                "thinking" -> {
                    copied.put("thinking", if (block.optBoolean("redacted")) "" else text(block, "thinking"))
                    if (block.optBoolean("redacted")) copied.put("redacted", true)
                }
                "toolCall" -> {
                    val id = identifier(block, "id")
                    require(ids.add(id)) { "模型重复工具 ID" }
                    copied.put("id", id).put("name", identifier(block, "name"))
                        .put("arguments", JSONObject(block.getJSONObject("arguments").toString()))
                }
                else -> error("不支持此模型内容块")
            }
            for (key in when (type) {
                "text" -> listOf("textSignature")
                "thinking" -> listOf("thinkingSignature")
                else -> listOf("thoughtSignature", "namespace")
            }) if (block.has(key)) copied.put(key, text(block, key))
            normalized.put(copied)
        }
        val api = identifier(raw, "api")
        require(api in ModelProviderConfig.APIS)
        val stopReason = raw.getString("stopReason")
        require(stopReason in setOf("pending", "stop", "length", "toolUse", "error", "aborted", "deferred"))
        return JSONObject().put("role", "assistant").put("api", api).put("provider", identifier(raw, "provider"))
            .put("model", text(raw, "model")).put("content", normalized).put("stopReason", stopReason)
            .put("timestamp", raw.optLong("timestamp", 0).coerceAtLeast(0)).also { target ->
                for (key in listOf("responseId", "responseModel", "providerThinkingLevel")) {
                    if (raw.has(key)) target.put(key, text(raw, key))
                }
                raw.optJSONObject("usage")?.let { usage ->
                    val safe = JSONObject()
                    for (key in listOf("input", "output", "cacheRead", "cacheWrite", "cacheWrite1h", "reasoning", "totalTokens")) {
                        number(usage, key)?.let { safe.put(key, it) }
                    }
                    val costs = JSONObject()
                    for (key in listOf("input", "output", "cacheRead", "cacheWrite", "total")) {
                        val cost = (usage.optJSONObject("cost")?.opt(key) as? Number)?.toDouble()
                        costs.put(key, if (cost != null && cost.isFinite() && cost >= 0) cost else 0.0)
                    }
                    target.put("usage", safe.put("cost", costs))
                }
            }
    }

    fun user(text: String, timestamp: Long = System.currentTimeMillis()): JSONObject =
        JSONObject().put("role", "user").put("content", bounded(text)).put("timestamp", timestamp.coerceAtLeast(0))

    fun toolResult(call: PiAiToolCall, content: String, isError: Boolean, timestamp: Long = System.currentTimeMillis()): JSONObject =
        JSONObject().put("role", "toolResult").put("toolCallId", call.id).put("toolName", call.name)
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", bounded(content))))
            .put("isError", isError).put("timestamp", timestamp.coerceAtLeast(0))

    fun tool(name: String, description: String, parameters: JSONObject): JSONObject {
        require(name.isNotBlank() && name.length <= 160 && name.none(Char::isISOControl))
        require(parameters.toString().toByteArray().size <= 256 * 1024)
        return JSONObject().put("name", name).put("description", bounded(description))
            .put("parameters", JSONObject(parameters.toString()))
    }

    /** Compatible engine switching sends readable history, not stale tool requests. */
    fun compatibleContext(messages: List<ChatMessage>, providerId: String, modelId: String, api: String,
        systemPrompt: String? = null, tools: List<JSONObject> = emptyList()): JSONObject {
        require(messages.size <= 4096 && api in ModelProviderConfig.APIS && tools.size <= 64)
        val converted = JSONArray()
        for (message in messages) {
            if (message.role == "user") converted.put(user(message.modelInput ?: message.text))
            else if (message.role == "assistant") converted.put(assistant(JSONObject().put("role", "assistant")
                .put("api", api).put("provider", providerId).put("model", modelId).put("stopReason", "stop")
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", message.modelInput ?: message.text)))
                .put("usage", JSONObject().put("input", 0).put("output", 0).put("cacheRead", 0).put("cacheWrite", 0).put("totalTokens", 0))))
        }
        return JSONObject().put("messages", converted).put("tools", JSONArray(tools.map { JSONObject(it.toString()) })).also {
            systemPrompt?.let { prompt -> it.put("systemPrompt", bounded(prompt)) }
            require(it.toString().toByteArray().size <= 4 * 1024 * 1024)
        }
    }

    private fun identifier(json: JSONObject, key: String): String = json.getString(key).also {
        require(it.isNotBlank() && it.length <= 160 && it.none(Char::isISOControl))
    }
    private fun text(json: JSONObject, key: String): String = bounded(json.getString(key))
    private fun bounded(value: String): String = value.also { require(it.toByteArray().size <= 1024 * 1024) }
    private fun number(json: JSONObject, key: String): Long? = (json.opt(key) as? Number)?.let {
        val value = it.toDouble()
        require(value.isFinite() && value >= 0 && value <= Long.MAX_VALUE.toDouble() && value % 1.0 == 0.0)
        it.toLong()
    }
}

private fun piUsage(json: JSONObject): AssistantUsage {
    fun number(key: String) = (json.opt(key) as? Number)?.toLong()?.takeIf { it >= 0 }
    val input = number("input")
    val cacheRead = number("cacheRead")
    val cacheWrite = number("cacheWrite")
    val completeInput = if (input == null && cacheRead == null && cacheWrite == null) null
        else Math.addExact(Math.addExact(input ?: 0, cacheRead ?: 0), cacheWrite ?: 0)
    return AssistantUsage(completeInput, number("output"), number("totalTokens"), cacheRead, number("reasoning"), cacheWrite)
}

/** Binds the actual worker stream to the existing native activity reducer. Does not schedule tools. */
suspend fun PiAiNodeWorker.generate(request: PiAiChatRequest, onEvent: suspend (AssistantStreamEvent) -> Unit): PiAiChatTurn {
    val frozen = request.copy(context = JSONObject(request.context.toString()))
    val adapter = PiAiStreamAdapter(frozen.requestId, frozen.sequenceStart, frozen.allowPublicThinking)
    val reducer = AssistantStreamReducer(frozen.sequenceStart, AssistantStreamSource.BACKEND)
    class CallbackFailure(val original: Throwable) : RuntimeException()
    suspend fun emit(events: List<AssistantStreamEvent>) {
        for (event in events) {
            try { onEvent(event) } catch (failure: Throwable) { throw CallbackFailure(failure) }
            reducer.apply(event)
        }
    }
    try {
        stream(frozen.providerId, frozen.modelId, frozen.context, frozen.reasoning, frozen.requestId) { event -> emit(adapter.accept(event)) }
        emit(adapter.finishWithoutTerminal())
    } catch (failure: CallbackFailure) { throw failure.original }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: IOException) { emit(adapter.failure(cancelled = false)) }
    reducer.finish()
    return adapter.turn(reducer.snapshot())
}
