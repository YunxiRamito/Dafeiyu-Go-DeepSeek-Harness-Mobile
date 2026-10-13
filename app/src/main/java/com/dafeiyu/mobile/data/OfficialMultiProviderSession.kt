package com.dafeiyu.mobile.data

import java.security.MessageDigest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

data class OfficialMultiProviderResult(val transcriptJson: String, val rounds: Int, val completed: Boolean) {
    fun transcript(): JSONObject = JSONObject(transcriptJson)
}

/** Native pi-ai requests and opaque signatures stay in the transcript; Android schedules every tool. */
object OfficialMultiProviderSession {
    const val MAX_ROUNDS = OfficialToolSession.MAX_ROUNDS

    fun tools(dispatcher: OfficialToolDispatcher): List<JSONObject> = schemas(dispatcher.schemas())

    suspend fun generate(
        runtime: ModelRuntimeController,
        request: PiAiChatRequest,
        dispatcher: OfficialToolDispatcher,
        onOutput: suspend (OfficialToolResult) -> Unit,
        onEvent: suspend (AssistantStreamEvent) -> Unit,
        onTranscript: suspend (JSONObject) -> Unit = {},
    ): OfficialMultiProviderResult = runtime.withSession(request.providerId) {
        generate(request, tools(dispatcher), { turn, callback -> runtime.generate(turn, callback) },
            dispatcher::execute, dispatcher::awaitSubagent, onOutput, onEvent, onTranscript)
    }

    /** Only the transport/dispatcher boundary is injected; fixtures exercise this production loop. */
    internal suspend fun generate(
        request: PiAiChatRequest,
        tools: List<JSONObject>,
        generateTurn: suspend (PiAiChatRequest, suspend (AssistantStreamEvent) -> Unit) -> PiAiChatTurn,
        executeTool: suspend (OfficialToolCall) -> OfficialToolResult,
        awaitSubagent: suspend (OfficialToolResult) -> OfficialToolResult,
        onOutput: suspend (OfficialToolResult) -> Unit,
        onEvent: suspend (AssistantStreamEvent) -> Unit,
        onTranscript: suspend (JSONObject) -> Unit = {},
        androidSystemPrompt: String = OfficialToolSession.systemMessage().content,
    ): OfficialMultiProviderResult {
        require(request.requestId.isNotBlank() && request.requestId.length <= 100)
        require(request.sequenceStart >= 0 && request.context.toString().toByteArray().size <= MAX_CONTEXT_BYTES)
        require(tools.size <= 64)
        val context = JSONObject(request.context.toString())
        val history = context.getJSONArray("messages")
        require(history.length() <= 4096)
        context.put("tools", JSONArray(tools.map { JSONObject(it.toString()) }))
        val suppliedPrompt = context.optString("systemPrompt")
        context.put("systemPrompt", if (suppliedPrompt.isBlank()) androidSystemPrompt
            else if (suppliedPrompt.contains(androidSystemPrompt)) suppliedPrompt else "$suppliedPrompt\n\n$androidSystemPrompt")
        var sequence = request.sequenceStart
        var totalCalls = 0
        var completedUsage: AssistantUsage? = null
        val seenCallIds = historyCallIds(history).toMutableSet()
        suspend fun emit(event: AssistantStreamEvent) {
            currentCoroutineContext().ensureActive()
            require(event.sequenceNumber == sequence && sequence < Long.MAX_VALUE) { "模型会话事件序号无效" }
            onEvent(event)
            sequence++
        }
        suspend fun persist() { onTranscript(JSONObject(context.toString())) }
        fun result(rounds: Int, complete: Boolean) = OfficialMultiProviderResult(context.toString(), rounds, complete)
        for (round in 0 until MAX_ROUNDS) {
            currentCoroutineContext().ensureActive()
            if (context.toString().toByteArray().size > MAX_CONTEXT_BYTES || history.length() > 4096) {
                emit(AssistantStreamEvent.MessageIncomplete(sequence, request.requestId, "context_limit"))
                return result(round, false)
            }
            var roundUsage: AssistantUsage? = null
            val turn = generateTurn(request.copy(context = JSONObject(context.toString()),
                requestId = "${request.requestId}.r$round", sequenceStart = sequence)) { event ->
                if (event is AssistantStreamEvent.UsageReported) {
                    roundUsage = mergeUsage(roundUsage, event.usage)
                    emit(event.copy(usage = addUsage(completedUsage, roundUsage)))
                } else emit(event)
            }
            completedUsage = addUsage(completedUsage, roundUsage)
            if (!turn.completed) return result(round + 1, false)
            val assistant = turn.assistantHistory() ?: return result(round + 1, false)
            val calls = turn.toolCalls()
            // Imported messages are readable history only. Even a repeated historical call ID is rejected.
            if (calls.any { !seenCallIds.add(it.id) }) {
                emit(AssistantStreamEvent.MessageIncomplete(sequence, request.requestId, "duplicate_tool_call_id"))
                return result(round + 1, false)
            }
            history.put(assistant)
            if (calls.isEmpty()) { persist(); return result(round + 1, true) }
            if (round == MAX_ROUNDS - 1 || calls.size > OfficialToolContract.MAX_BATCH_CALLS ||
                totalCalls + calls.size > OfficialToolContract.MAX_SESSION_CALLS) {
                for (call in calls) {
                    emit(AssistantStreamEvent.ToolError(sequence, call.id, "本次工具调用达到上限", "TOOL_LIMIT"))
                    val failure = toolFailure("TOOL_LIMIT")
                    history.put(PiAiTranscriptAdapter.toolResult(call, failure, true))
                }
                persist()
                emit(AssistantStreamEvent.MessageIncomplete(sequence, request.requestId, "tool_round_limit"))
                return result(round + 1, false)
            }
            totalCalls += calls.size
            val initialResults = mutableListOf<OfficialToolResult>()
            for (call in calls) {
                currentCoroutineContext().ensureActive()
                emit(AssistantStreamEvent.ToolStatusChanged(sequence, call.id, AssistantStreamEvent.ToolStatus.RUNNING))
                // Providers may use punctuation in IDs. Keep those native IDs in their transcript while
                // giving the Android side-effect ledger a deterministic, safe session-scoped ID.
                val result = executeTool(OfficialToolCall(dispatcherCallId(request.requestId, call.id), call.name,
                    call.arguments().toString()))
                onOutput(result.copy(callId = call.id))
                initialResults += result
                val response = JSONObject(result.content)
                if (!response.optBoolean("ok")) emit(AssistantStreamEvent.ToolError(sequence, call.id,
                    "工具执行失败", response.optString("error", "TOOL_FAILED")))
                emit(AssistantStreamEvent.ToolResult(sequence, call.id, result.content))
                emitSubagentCreated(call, response, sequence) { emit(it) }
            }
            // Begin all sibling waits together. Publishing stays ordered in the parent coroutine.
            coroutineScope {
                val waits = initialResults.map { initial -> async(start = CoroutineStart.UNDISPATCHED) { awaitSubagent(initial) } }
                for (index in initialResults.indices) {
                    val initial = initialResults[index]
                    val call = calls[index]
                    val resolved = waits[index].await()
                    if (resolved !== initial) {
                        onOutput(resolved.copy(callId = call.id))
                        emit(AssistantStreamEvent.ToolResult(sequence, call.id, resolved.content))
                        val taskId = JSONObject(initial.content).optJSONObject("data")?.optString("task_id").orEmpty()
                        if (taskId.isNotEmpty()) publishSubagentResult(taskId, JSONObject(resolved.content), { sequence }) { emit(it) }
                    }
                    val response = JSONObject(resolved.content)
                    history.put(PiAiTranscriptAdapter.toolResult(call, resolved.content, !response.optBoolean("ok")))
                }
            }
            persist()
        }
        return result(MAX_ROUNDS, false)
    }

    private suspend fun emitSubagentCreated(call: PiAiToolCall, response: JSONObject, start: Long,
        emit: suspend (AssistantStreamEvent) -> Unit) {
        if (!response.optBoolean("ok") || call.name != "create_subagent") return
        val data = response.getJSONObject("data")
        val taskId = data.getString("task_id")
        emit(AssistantStreamEvent.SubagentStarted(start, taskId, data.getString("title"), call.id))
        if (!data.isNull("start_error")) emit(AssistantStreamEvent.SubagentFailed(start + 1, taskId, data.getString("start_error")))
        else emit(AssistantStreamEvent.SubagentStatusChanged(start + 1, taskId, AssistantStreamEvent.SubagentStatus.PENDING,
            if (data.optBoolean("run_requested")) "已发送消息" else "草稿"))
    }

    private suspend fun publishSubagentResult(taskId: String, response: JSONObject, next: () -> Long,
        emit: suspend (AssistantStreamEvent) -> Unit) {
        if (!response.optBoolean("ok")) {
            emit(AssistantStreamEvent.SubagentFailed(next(), taskId, "结果不可用")); return
        }
        val data = response.getJSONObject("data")
        when (data.getString("status")) {
            "SUCCEEDED" -> {
                data.optString("result").takeIf { it.isNotEmpty() }?.let {
                    emit(AssistantStreamEvent.SubagentTextDelta(next(), taskId, it))
                }
                emit(AssistantStreamEvent.SubagentCompleted(next(), taskId, "收到了消息"))
            }
            "FAILED" -> emit(AssistantStreamEvent.SubagentFailed(next(), taskId, data.optString("error_code", "execution_failed")))
            "STOPPED", "INTERRUPTED" -> emit(AssistantStreamEvent.SubagentCancelled(next(), taskId, data.getString("status")))
            "RUNNING" -> emit(AssistantStreamEvent.SubagentStatusChanged(next(), taskId,
                AssistantStreamEvent.SubagentStatus.RUNNING, "仍在运行，等待已超时"))
            "DRAFT", "QUEUED", "STOPPING" -> emit(AssistantStreamEvent.SubagentStatusChanged(next(), taskId,
                AssistantStreamEvent.SubagentStatus.INCOMPLETE, "${data.getString("status")}：等待已超时"))
            else -> emit(AssistantStreamEvent.SubagentFailed(next(), taskId, "unknown_task_status"))
        }
    }

    internal fun dispatcherCallId(session: String, nativeId: String): String = "pi_" + MessageDigest.getInstance("SHA-256")
        .digest("$session\u0000$nativeId".toByteArray()).joinToString("") { "%02x".format(it) }

    private fun schemas(schemas: JSONArray) = List(schemas.length()) { index ->
        val function = schemas.getJSONObject(index).getJSONObject("function")
        PiAiTranscriptAdapter.tool(function.getString("name"), function.getString("description"), function.getJSONObject("parameters"))
    }

    private fun historyCallIds(history: JSONArray): Set<String> = buildSet {
        for (index in 0 until history.length()) {
            val message = history.optJSONObject(index) ?: continue
            val content = message.optJSONArray("content") ?: continue
            if (message.optString("role") != "assistant") continue
            for (blockIndex in 0 until content.length()) content.optJSONObject(blockIndex)?.let { block ->
                if (block.optString("type") == "toolCall") block.optString("id").takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }

    private fun toolFailure(code: String) = JSONObject().put("ok", false).put("error", code).toString()
    private fun mergeUsage(old: AssistantUsage?, next: AssistantUsage) = AssistantUsage(next.inputTokens ?: old?.inputTokens,
        next.outputTokens ?: old?.outputTokens, next.totalTokens ?: old?.totalTokens, next.cachedInputTokens ?: old?.cachedInputTokens,
        next.reasoningTokens ?: old?.reasoningTokens, next.cacheCreationInputTokens ?: old?.cacheCreationInputTokens)
    private fun addUsage(old: AssistantUsage?, next: AssistantUsage?): AssistantUsage {
        fun add(a: Long?, b: Long?): Long? = when { a == null -> b; b == null -> a; Long.MAX_VALUE - a < b -> Long.MAX_VALUE; else -> a + b }
        return AssistantUsage(add(old?.inputTokens, next?.inputTokens), add(old?.outputTokens, next?.outputTokens),
            add(old?.totalTokens, next?.totalTokens), add(old?.cachedInputTokens, next?.cachedInputTokens),
            add(old?.reasoningTokens, next?.reasoningTokens), add(old?.cacheCreationInputTokens, next?.cacheCreationInputTokens))
    }
    private const val MAX_CONTEXT_BYTES = 4 * 1024 * 1024
}
