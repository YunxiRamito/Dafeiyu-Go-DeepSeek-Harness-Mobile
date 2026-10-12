package com.dafeiyu.mobile.data

import android.content.Context
import org.json.JSONObject
import java.nio.file.Files

private var passed = 0
private fun test(name: String, run: () -> Unit) {
    run()
    passed++
    println("PASS $name")
}
private fun feed(adapter: AssistantStreamAdapter, reducer: AssistantStreamReducer, type: String, json: String) {
    adapter.accept(type, JSONObject(json)).forEach {
        check(reducer.apply(it).disposition == AssistantStreamReducer.ApplyDisposition.APPLIED) { "non-contiguous adapter sequence: $it" }
    }
}
private fun AssistantStreamSnapshot.text() = messages.flatMap { it.blocks }.filterIsInstance<AssistantContentBlockSnapshot.Text>().joinToString("") { it.text }
private fun AssistantStreamSnapshot.reasoning() = messages.flatMap { it.blocks }.filterIsInstance<AssistantContentBlockSnapshot.Reasoning>()

private fun toolWaitEvents(): List<AssistantStreamEvent> = listOf(
    AssistantStreamEvent.MessageStarted(0, "model-round-1"),
    AssistantStreamEvent.TextDelta(1, "model-round-1", "Checking weather"),
    AssistantStreamEvent.ToolCallStarted(2, "weather", "get_weather"),
    AssistantStreamEvent.ToolArgumentsDone(3, "weather", "{}"),
    AssistantStreamEvent.UsageReported(4, AssistantUsage(100, 20, 120, 50)),
    AssistantStreamEvent.MessageCompleted(5, "model-round-1", "tool_calls"),
    AssistantStreamEvent.ToolStatusChanged(6, "weather", AssistantStreamEvent.ToolStatus.RUNNING),
    AssistantStreamEvent.SubagentStarted(7, "worker", "worker", "weather"),
    AssistantStreamEvent.SearchStarted(8, "search", "weather"),
    AssistantStreamEvent.ToolResult(9, "already-finished", "retained result"),
)

private fun toolWaitReducer() = AssistantStreamReducer().apply {
    toolWaitEvents().forEach {
        check(apply(it).disposition == AssistantStreamReducer.ApplyDisposition.APPLIED)
    }
}

private fun AssistantStreamSnapshot.reloaded(): AssistantStreamSnapshot =
    AssistantStreamSnapshotJson.restored(
        requireNotNull(AssistantStreamSnapshotJson.decode(JSONObject(AssistantStreamSnapshotJson.encode(this).toString()))),
    )

fun main() {
    test("reorder and duplicates") {
        val r = AssistantStreamReducer()
        check(r.apply(AssistantStreamEvent.TextDelta(1, "m", "answer")).disposition == AssistantStreamReducer.ApplyDisposition.BUFFERED)
        r.apply(AssistantStreamEvent.MessageStarted(0, "m"))
        check(r.snapshot().text() == "answer")
        check(r.apply(AssistantStreamEvent.TextDelta(1, "m", "wrong")).disposition == AssistantStreamReducer.ApplyDisposition.DUPLICATE)
    }
    test("gap and incomplete stream") {
        val r = AssistantStreamReducer()
        r.apply(AssistantStreamEvent.TextDelta(2, "m", "partial"))
        val s = r.finish()
        check(s.skippedSequenceGaps == listOf(AssistantSequenceGap(0, 1)))
        check(s.messages.single().status == AssistantMessageStatus.INCOMPLETE)
        check(r.apply(AssistantStreamEvent.MessageCompleted(3, "m")).disposition == AssistantStreamReducer.ApplyDisposition.AFTER_FINISH)
    }
    test("done snapshots and activity order") {
        val r = AssistantStreamReducer()
        r.apply(AssistantStreamEvent.UserVisibleThinkingDelta(0, "m", "think", "public"))
        r.apply(AssistantStreamEvent.TextDelta(1, "m", "delta"))
        r.apply(AssistantStreamEvent.ContentBlockDone(2, "m", "text", finalText = "final"))
        r.apply(AssistantStreamEvent.TextDelta(3, "m", "ignored"))
        check(r.snapshot().text() == "final")
        check(r.snapshot().activityOrder.map { it.kind } == listOf(AssistantActivityKind.REASONING, AssistantActivityKind.TEXT))
    }
    test("opaque thinking flags only") {
        val r = AssistantStreamReducer()
        r.apply(AssistantStreamEvent.ReasoningOpaqueMetadata(0, "m", "opaque", "anthropic", hasSignature = true,
            values = mapOf("encrypted_present" to "SECRET", "raw" to "SECRET")))
        check(r.snapshot().reasoning().isEmpty())
        check(!AssistantStreamSnapshotJson.encode(r.snapshot()).toString().contains("SECRET"))
    }
    test("tool ready differs from execution") {
        val r = AssistantStreamReducer()
        r.apply(AssistantStreamEvent.ToolCallStarted(0, "c", "shell", AssistantStreamEvent.ToolKind.COMMAND))
        r.apply(AssistantStreamEvent.ToolArgumentsDelta(1, "c", "pwd"))
        r.apply(AssistantStreamEvent.ToolArgumentsDone(2, "c"))
        r.apply(AssistantStreamEvent.ToolOutputDelta(3, "c", "/workspace"))
        check(r.finish().toolCalls.single().status == AssistantStreamEvent.ToolStatus.READY)
    }
    test("approval denial and errors") {
        val r = AssistantStreamReducer()
        r.apply(AssistantStreamEvent.ToolApprovalRequired(0, "c", "confirm"))
        r.apply(AssistantStreamEvent.ToolApprovalDecision(1, "c", false))
        r.apply(AssistantStreamEvent.ToolResult(2, "c", "must not override denial"))
        r.apply(AssistantStreamEvent.ToolError(3, "other", "failed", "E"))
        check(r.snapshot().toolCalls.first().status == AssistantStreamEvent.ToolStatus.DENIED)
        check(r.snapshot().toolCalls.last().errorCode == "E")
    }
    test("cancel propagates and terminal results survive") {
        val r = AssistantStreamReducer()
        r.apply(AssistantStreamEvent.ToolStatusChanged(0, "c", AssistantStreamEvent.ToolStatus.RUNNING))
        r.apply(AssistantStreamEvent.SubagentStarted(1, "a", "worker", "c"))
        r.apply(AssistantStreamEvent.SearchStarted(2, "s", "query"))
        r.apply(AssistantStreamEvent.ToolResult(3, "done", "ok"))
        r.apply(AssistantStreamEvent.MessageCancelled(4, "m", "user_cancelled"))
        val s = r.finish()
        check(s.toolCalls.first().status == AssistantStreamEvent.ToolStatus.CANCELLED)
        check(s.toolCalls.last().status == AssistantStreamEvent.ToolStatus.SUCCEEDED)
        check(s.subagents.single().status == AssistantStreamEvent.SubagentStatus.CANCELLED)
        check(s.searches.single().status == AssistantStreamEvent.SearchStatus.CANCELLED)
    }
    test("unfinished activities incomplete") {
        val r = AssistantStreamReducer()
        r.apply(AssistantStreamEvent.ToolStatusChanged(0, "c", AssistantStreamEvent.ToolStatus.RUNNING))
        r.apply(AssistantStreamEvent.SubagentStarted(1, "a", "worker"))
        r.apply(AssistantStreamEvent.SearchStarted(2, "s"))
        val s = r.finish()
        check(s.toolCalls.single().status == AssistantStreamEvent.ToolStatus.INCOMPLETE)
        check(s.subagents.single().status == AssistantStreamEvent.SubagentStatus.INCOMPLETE)
        check(s.searches.single().status == AssistantStreamEvent.SearchStatus.INCOMPLETE)
    }
    test("OpenAI nested payloads usage citations and summaries") {
        val a = OpenAiResponsesStreamAdapter()
        val r = AssistantStreamReducer(source = a.source)
        feed(a, r, "response.created", """{"sequence_number":0,"response":{"id":"resp"}}""")
        feed(a, r, "future_unknown", """{"sequence_number":1}""")
        feed(a, r, "response.reasoning_summary_text.delta", """{"sequence_number":2,"item_id":"rs","summary_index":0,"delta":"public summary"}""")
        feed(a, r, "response.output_text.delta", """{"sequence_number":3,"item_id":"msg","content_index":0,"delta":"Hello"}""")
        feed(a, r, "response.output_text.done", """{"sequence_number":4,"item_id":"msg","content_index":0,"text":"Hello world"}""")
        feed(a, r, "response.output_text.annotation.added", """{"sequence_number":5,"item_id":"msg","content_index":0,"annotation_index":0,"annotation":{"type":"url_citation","url":"https://example.com","title":"source","start_index":0,"end_index":5}}""")
        feed(a, r, "response.completed", """{"sequence_number":6,"response":{"id":"resp","usage":{"input_tokens":20,"output_tokens":10,"total_tokens":30,"input_tokens_details":{"cached_tokens":4},"output_tokens_details":{"reasoning_tokens":3}}}}""")
        val s = r.finish()
        check(s.text() == "Hello world" && s.reasoning().single().text == "public summary")
        check(s.usage?.cachedInputTokens == 4L && s.usage?.reasoningTokens == 3L)
        check(s.citations.single().citation.startIndex == 0)
        check(s.source == AssistantStreamSource.OPENAI)
        check(a.accept("response.completed", JSONObject("""{"sequence_number":6,"response":{"id":"resp"}}""")).isEmpty())
    }
    test("OpenAI calls done remain ready; MCP approval") {
        val a = OpenAiResponsesStreamAdapter()
        val r = AssistantStreamReducer(source = a.source)
        feed(a, r, "response.output_item.added", """{"output_index":0,"item":{"id":"fc","call_id":"call","type":"function_call","name":"run"}}""")
        feed(a, r, "response.function_call_arguments.delta", """{"item_id":"fc","delta":"{}"}""")
        feed(a, r, "response.function_call_arguments.done", """{"item_id":"fc","arguments":"{}"}""")
        feed(a, r, "response.output_item.done", """{"output_index":1,"item":{"id":"approve","type":"mcp_approval_request","name":"run","arguments":"{}","server_label":"server"}}""")
        feed(a, r, "response.completed", """{"response":{"id":"resp"}}""")
        val s = r.finish()
        check(s.toolCalls.first().callId == "call" && s.toolCalls.first().status == AssistantStreamEvent.ToolStatus.READY)
        check(s.toolCalls.last().status == AssistantStreamEvent.ToolStatus.AWAITING_APPROVAL)
    }
    test("OpenAI search results and opaque reasoning filtered") {
        val a = OpenAiResponsesStreamAdapter()
        val r = AssistantStreamReducer(source = a.source)
        feed(a, r, "response.output_item.done", """{"output_index":0,"item":{"id":"s","type":"web_search_call","status":"completed","action":{"query":"q","sources":[{"title":"S","url":"https://example.com"}]}}}""")
        feed(a, r, "response.output_item.done", """{"output_index":1,"item":{"id":"opaque","type":"reasoning","encrypted_content":"SECRET","summary":[]}}""")
        val s = r.finish()
        check(s.searches.single().results.single().title == "S")
        check(s.reasoning().isEmpty() && !AssistantStreamSnapshotJson.encode(s).toString().contains("SECRET"))
    }
    test("Anthropic public thinking signature and redacted blocks") {
        val a = AnthropicMessagesStreamAdapter()
        val r = AssistantStreamReducer(source = a.source)
        feed(a, r, "message_start", """{"message":{"id":"m","role":"assistant","usage":{"input_tokens":5}}}""")
        feed(a, r, "content_block_start", """{"index":0,"content_block":{"type":"thinking","thinking":""}}""")
        feed(a, r, "content_block_delta", """{"index":0,"delta":{"type":"thinking_delta","thinking":"public"}}""")
        feed(a, r, "content_block_delta", """{"index":0,"delta":{"type":"signature_delta","signature":"SECRET"}}""")
        feed(a, r, "content_block_stop", """{"index":0}""")
        feed(a, r, "content_block_start", """{"index":1,"content_block":{"type":"redacted_thinking","data":"SECRET"}}""")
        feed(a, r, "content_block_stop", """{"index":1}""")
        feed(a, r, "message_delta", """{"delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":8}}""")
        feed(a, r, "message_stop", "{}")
        val s = r.finish()
        check(s.reasoning().single().text == "public" && s.reasoning().single().isComplete)
        check(s.reasoning().single().metadata?.values?.get("signature_present") == "true")
        check(s.usage?.inputTokens == 5L && s.usage?.outputTokens == 8L)
        check(!AssistantStreamSnapshotJson.encode(s).toString().contains("SECRET"))
    }
    test("Anthropic thinking policy and tool id mapping") {
        val a = AnthropicMessagesStreamAdapter(allowPublicThinking = false)
        val r = AssistantStreamReducer(source = a.source)
        feed(a, r, "content_block_start", """{"index":0,"content_block":{"type":"thinking","thinking":"hidden by policy"}}""")
        feed(a, r, "content_block_stop", """{"index":0}""")
        feed(a, r, "ping", "{}")
        feed(a, r, "content_block_start", """{"index":1,"content_block":{"type":"tool_use","id":"tool","name":"run","input":{}}}""")
        feed(a, r, "content_block_delta", """{"index":1,"delta":{"type":"input_json_delta","partial_json":"{\"cmd\":\"pwd\"}"}}""")
        feed(a, r, "content_block_stop", """{"index":1}""")
        feed(a, r, "message_delta", """{"delta":{"stop_reason":"tool_use"}}""")
        feed(a, r, "message_stop", "{}")
        val s = r.finish()
        check(s.reasoning().isEmpty())
        check(s.toolCalls.single().callId == "tool" && s.toolCalls.single().status == AssistantStreamEvent.ToolStatus.READY)
        check(s.messages.single().status == AssistantMessageStatus.COMPLETED)
    }
    test("Anthropic max tokens and server search") {
        val a = AnthropicMessagesStreamAdapter()
        val r = AssistantStreamReducer(source = a.source)
        feed(a, r, "content_block_start", """{"index":0,"content_block":{"type":"server_tool_use","id":"search","name":"web_search","input":{}}}""")
        feed(a, r, "content_block_start", """{"index":1,"content_block":{"type":"web_search_tool_result","tool_use_id":"search","content":[{"type":"web_search_result","title":"S","url":"https://example.com","encrypted_content":"SECRET"}]}}""")
        feed(a, r, "message_delta", """{"delta":{"stop_reason":"max_tokens"}}""")
        feed(a, r, "message_stop", "{}")
        val s = r.finish()
        check(s.searches.single().status == AssistantStreamEvent.SearchStatus.COMPLETED)
        check(s.messages.single().status == AssistantMessageStatus.INCOMPLETE)
        check(!AssistantStreamSnapshotJson.encode(s).toString().contains("SECRET"))
    }
    test("OpenAI shell proposals and explicit process outcome") {
        val a = OpenAiResponsesStreamAdapter()
        val r = AssistantStreamReducer(source = a.source)
        feed(a, r, "response.output_item.added", """{"output_index":0,"item":{"id":"shell","call_id":"call","type":"shell_call"}}""")
        feed(a, r, "response.shell_call_command.done", """{"output_index":0,"command_index":0,"command":"pwd"}""")
        check(r.snapshot().toolCalls.single().status == AssistantStreamEvent.ToolStatus.READY)
        feed(a, r, "response.output_item.added", """{"output_index":1,"item":{"id":"out","call_id":"call","type":"shell_call_output"}}""")
        feed(a, r, "response.shell_call_output_content.delta", """{"item_id":"out","delta":{"stdout":"/work","stderr":""},"command_index":0}""")
        feed(a, r, "response.shell_call_output_content.done", """{"item_id":"out","output":[{"stdout":"/work","stderr":"","outcome":{"type":"exit","exit_code":0}}],"command_index":0}""")
        val s = r.finish()
        check(s.toolCalls.single().status == AssistantStreamEvent.ToolStatus.SUCCEEDED)
        check(s.toolCalls.single().result == "/work")
    }
    test("OpenAI public reasoning and provider incomplete errors") {
        val a = OpenAiResponsesStreamAdapter()
        val r = AssistantStreamReducer(source = a.source)
        feed(a, r, "response.reasoning_text.delta", """{"item_id":"rs","content_index":0,"delta":"public"}""")
        feed(a, r, "response.reasoning_text.done", """{"item_id":"rs","content_index":0,"text":"public"}""")
        feed(a, r, "response.incomplete", """{"response":{"id":"openai-response","incomplete_details":{"reason":"max_output_tokens"}}}""")
        val s = r.finish()
        check(s.reasoning().single().kind == AssistantReasoningKind.USER_VISIBLE_THINKING)
        check(s.messages.single().status == AssistantMessageStatus.INCOMPLETE)
        val hidden = OpenAiResponsesStreamAdapter(false)
        check(hidden.accept("response.reasoning_text.delta", JSONObject("""{"delta":"hidden"}""")).isEmpty())
        val failed = OpenAiResponsesStreamAdapter()
        val failure = AssistantStreamReducer()
        feed(failed, failure, "response.failed", """{"response":{"id":"f","error":{"message":"broken","code":"E"}}}""")
        check(failure.finish().messages.single().errorCode == "E")
    }
    test("Anthropic server command exit status") {
        val a = AnthropicMessagesStreamAdapter()
        val r = AssistantStreamReducer(source = a.source)
        feed(a, r, "content_block_start", """{"index":0,"content_block":{"type":"server_tool_use","id":"run","name":"bash_code_execution","input":{"command":"false"}}}""")
        feed(a, r, "content_block_start", """{"index":1,"content_block":{"type":"bash_code_execution_tool_result","tool_use_id":"run","content":{"type":"bash_code_execution_result","stdout":"","stderr":"failed","return_code":1}}}""")
        val s = r.finish()
        check(s.toolCalls.single().kind == AssistantStreamEvent.ToolKind.COMMAND)
        check(s.toolCalls.single().status == AssistantStreamEvent.ToolStatus.FAILED)
    }
    val r = AssistantStreamReducer(source = AssistantStreamSource.BACKEND)
    r.apply(AssistantStreamEvent.MessageStarted(0, "m"))
    r.apply(AssistantStreamEvent.TextDelta(1, "m", "answer"))
    r.apply(AssistantStreamEvent.ReasoningSummaryDelta(2, "m", "summary", "public"))
    r.apply(AssistantStreamEvent.ToolCallStarted(3, "c", "shell", AssistantStreamEvent.ToolKind.COMMAND))
    r.apply(AssistantStreamEvent.ToolApprovalRequired(4, "c", "permission"))
    r.apply(AssistantStreamEvent.SubagentStarted(5, "a", "worker", "c"))
    r.apply(AssistantStreamEvent.SubagentTextDelta(6, "a", "output"))
    r.apply(AssistantStreamEvent.SubagentCompleted(7, "a", "summary"))
    r.apply(AssistantStreamEvent.SearchStarted(8, "s", "query"))
    r.apply(AssistantStreamEvent.SearchResultAdded(9, "s", AssistantSearchResult("result", "title", "https://example.com", "snippet", "today")))
    r.apply(AssistantStreamEvent.SearchStatusChanged(10, "s", AssistantStreamEvent.SearchStatus.COMPLETED))
    r.apply(AssistantStreamEvent.CitationAdded(11, AssistantCitation("cite", quote = "quote", fileId = "file", documentIndex = 0, startPage = 1, endPage = 2, blockId = "text"), "m", "s"))
    r.apply(AssistantStreamEvent.UsageReported(12, AssistantUsage(10, 20, 30, 1, 2, 3)))
    r.apply(AssistantStreamEvent.MessageCompleted(13, "m", "end"))
    val complete = r.finish()
    test("full snapshot JSON roundtrip and unknown schema") {
        val json = AssistantStreamSnapshotJson.encode(complete)
        check(AssistantStreamSnapshotJson.decode(JSONObject(json.toString())) == complete)
        check(AssistantStreamSnapshotJson.decode(JSONObject("""{"schema":999}""")) == null)
        check(AssistantStreamSnapshotJson.decode(null) == null)
    }
    test("snapshot preserves pending sequences and gaps") {
        val pending = AssistantStreamReducer()
        pending.apply(AssistantStreamEvent.TextDelta(3, "m", "partial"))
        val active = pending.snapshot()
        check(AssistantStreamSnapshotJson.decode(AssistantStreamSnapshotJson.encode(active)) == active)
        val final = pending.finish()
        check(AssistantStreamSnapshotJson.decode(AssistantStreamSnapshotJson.encode(final)) == final)
    }
    test("process restart interrupts unfinished work") {
        val active = AssistantStreamReducer()
        active.apply(AssistantStreamEvent.ToolStatusChanged(0, "c", AssistantStreamEvent.ToolStatus.RUNNING))
        active.apply(AssistantStreamEvent.SubagentStarted(1, "a", "worker"))
        active.apply(AssistantStreamEvent.SearchStarted(2, "s"))
        active.apply(AssistantStreamEvent.TextDelta(3, "m", "partial"))
        val restored = AssistantStreamSnapshotJson.restored(AssistantStreamSnapshotJson.decode(AssistantStreamSnapshotJson.encode(active.snapshot()))!!)
        check(restored.isFinal && restored.messages.single().incompleteReason == "process_restarted")
        check(restored.toolCalls.single().status == AssistantStreamEvent.ToolStatus.INCOMPLETE)
        check(restored.subagents.single().status == AssistantStreamEvent.SubagentStatus.INCOMPLETE)
        check(restored.searches.single().status == AssistantStreamEvent.SearchStatus.INCOMPLETE)
    }
    test("distinct session cancellation during tool wait survives JSON restore") {
        val reducer = toolWaitReducer()
        val collector = ChatRunMetricsCollector(42, "conversation", 1000)
        toolWaitEvents().forEachIndexed { index, event -> collector.onEvent(event, 1100L + index * 100) }
        val finished = collector.finish(ChatRunStatus.Cancelled, 2500)
        val event = AssistantStreamEvent.MessageCancelled(10, "official-session-cancel-42", "user_cancelled")
        check(reducer.apply(event).disposition == AssistantStreamReducer.ApplyDisposition.APPLIED)
        val snapshot = reducer.finish().copy(
            runStatus = finished.status.toAssistantRunStatus(),
            runElapsedMillis = finished.elapsedMillis, runRounds = finished.rounds, runSteps = finished.steps,
        )
        val restored = snapshot.reloaded()
        check(restored == snapshot)
        check(restored.messages.first().status == AssistantMessageStatus.COMPLETED)
        check(restored.messages.last().messageId == "official-session-cancel-42")
        check(restored.messages.last().status == AssistantMessageStatus.CANCELLED)
        check(restored.toolCalls.first().status == AssistantStreamEvent.ToolStatus.CANCELLED)
        check(restored.toolCalls.last().result == "retained result")
        check(restored.toolCalls.last().status == AssistantStreamEvent.ToolStatus.SUCCEEDED)
        check(restored.subagents.single().status == AssistantStreamEvent.SubagentStatus.CANCELLED)
        check(restored.searches.single().status == AssistantStreamEvent.SearchStatus.CANCELLED)
        val metrics = chatRunMetricsFromSnapshot(42, "conversation", restored)
        check(metrics.status == ChatRunStatus.Cancelled && metrics.elapsedMillis == 1500L)
        check(metrics.rounds == 1 && metrics.steps == 1 && metrics.totalTokens == 120L)
        check(metrics.tokensPerSecond == 20.0 / 1.5)
        check(collector.snapshot(10000) == finished)
        check(reducer.apply(event.copy(sequenceNumber = 11)).disposition == AssistantStreamReducer.ApplyDisposition.AFTER_FINISH)
    }
    test("completed model ID cancellation propagates without overwriting model terminal") {
        val reducer = toolWaitReducer()
        val event = AssistantStreamEvent.MessageCancelled(10, "model-round-1", "user_cancelled")
        reducer.apply(event)
        check(reducer.apply(event).disposition == AssistantStreamReducer.ApplyDisposition.DUPLICATE)
        val restored = reducer.finish().copy(runStatus = AssistantRunStatus.CANCELLED).reloaded()
        check(restored.messages.single().status == AssistantMessageStatus.COMPLETED)
        check(restored.toolCalls.first().status == AssistantStreamEvent.ToolStatus.CANCELLED)
        check(restored.subagents.single().status == AssistantStreamEvent.SubagentStatus.CANCELLED)
        check(restored.searches.single().status == AssistantStreamEvent.SearchStatus.CANCELLED)
        check(restored.toolCalls.last().status == AssistantStreamEvent.ToolStatus.SUCCEEDED)
        check(chatRunMetricsFromSnapshot(42, "conversation", restored).status == ChatRunStatus.Cancelled)
    }
    test("restart during tool wait interrupts explicit and legacy session") {
        for (status in listOf(AssistantRunStatus.RUNNING, null)) {
            val active = toolWaitReducer().snapshot().copy(
                runStatus = status, runElapsedMillis = 999, runRounds = 1, runSteps = 1,
            )
            val restored = active.reloaded()
            check(restored.isFinal && restored.runStatus == AssistantRunStatus.INCOMPLETE)
            check(restored.messages.single().status == AssistantMessageStatus.COMPLETED)
            check(restored.toolCalls.first().status == AssistantStreamEvent.ToolStatus.INCOMPLETE)
            check(restored.toolCalls.last().result == "retained result")
            check(restored.subagents.single().detail == "process_restarted")
            check(restored.searches.single().error == "process_restarted")
            check(restored.usage == active.usage && restored.text() == active.text())
            val metrics = chatRunMetricsFromSnapshot(42, "conversation", restored)
            check(metrics.status == ChatRunStatus.Failed && metrics.elapsedMillis == 999L)
            check(metrics.rounds == 1 && metrics.steps == 1)
            check(restored.reloaded() == restored)
        }
    }
    test("restart between successful tool result and next model round remains incomplete") {
        val reducer = toolWaitReducer()
        reducer.apply(AssistantStreamEvent.ToolResult(10, "weather", "sunny"))
        reducer.apply(AssistantStreamEvent.SubagentCompleted(11, "worker"))
        reducer.apply(AssistantStreamEvent.SearchStatusChanged(12, "search", AssistantStreamEvent.SearchStatus.COMPLETED))
        val restored = reducer.snapshot().copy(runStatus = AssistantRunStatus.RUNNING).reloaded()
        check(restored.toolCalls.all { it.status == AssistantStreamEvent.ToolStatus.SUCCEEDED })
        check(restored.runStatus == AssistantRunStatus.INCOMPLETE)
        check(chatRunMetricsFromSnapshot(42, "conversation", restored).status == ChatRunStatus.Failed)
    }
    test("explicit terminal session statuses survive restore even without final stream flag") {
        for (status in AssistantRunStatus.entries.filter { it != AssistantRunStatus.RUNNING }) {
            for (isFinal in listOf(true, false)) {
                val snapshot = complete.copy(runStatus = status, isFinal = isFinal,
                    runElapsedMillis = 2500, runRounds = 2, runSteps = 1)
                val restored = snapshot.reloaded()
                check(restored.runStatus == status && restored.isFinal)
                check(restored.runElapsedMillis == 2500L && restored.runRounds == 2 && restored.runSteps == 1)
                check(chatRunMetricsFromSnapshot(42, "conversation", restored).status == status.toChatRunStatus())
                check(restored.reloaded() == restored)
            }
        }
    }
    test("final stream with running session and empty initial session cannot restore completed") {
        val runningFinal = complete.copy(runStatus = AssistantRunStatus.RUNNING).reloaded()
        check(runningFinal.runStatus == AssistantRunStatus.INCOMPLETE)
        check(chatRunMetricsFromSnapshot(42, "conversation", runningFinal).status == ChatRunStatus.Failed)
        val empty = AssistantStreamReducer().snapshot().copy(runStatus = AssistantRunStatus.RUNNING).reloaded()
        check(empty.messages.isEmpty() && empty.runStatus == AssistantRunStatus.INCOMPLETE)
        check(chatRunMetricsFromSnapshot(42, "conversation", empty).status == ChatRunStatus.Failed)
    }
    test("legacy cancellation and failure remain terminal on restart") {
        for (event in listOf(
            AssistantStreamEvent.MessageCancelled(0, "m", "user_cancelled"),
            AssistantStreamEvent.MessageFailed(0, "m", "request failed"),
        )) {
            val reducer = AssistantStreamReducer()
            reducer.apply(event)
            val restored = reducer.snapshot().reloaded()
            val expected = if (event is AssistantStreamEvent.MessageCancelled)
                AssistantRunStatus.CANCELLED else AssistantRunStatus.FAILED
            check(restored.runStatus == expected)
            check(chatRunMetricsFromSnapshot(42, "conversation", restored).status == expected.toChatRunStatus())
        }
    }
    test("metrics JSON roundtrip rejects negative counters and accepts legacy or unknown status") {
        val measured = complete.copy(runStatus = AssistantRunStatus.COMPLETED,
            runElapsedMillis = 9876543210, runRounds = 3, runSteps = 2)
        check(measured.reloaded() == measured)
        for (field in listOf("runElapsedMillis", "runRounds", "runSteps")) {
            val decoded = requireNotNull(AssistantStreamSnapshotJson.decode(
                AssistantStreamSnapshotJson.encode(measured).put(field, -1)))
            check(when (field) {
                "runElapsedMillis" -> decoded.runElapsedMillis == null
                "runRounds" -> decoded.runRounds == null
                else -> decoded.runSteps == null
            })
            check(decoded.messages == measured.messages && decoded.runStatus == measured.runStatus)
        }
        for (field in listOf("runRounds", "runSteps")) {
            val decoded = requireNotNull(AssistantStreamSnapshotJson.decode(
                AssistantStreamSnapshotJson.encode(measured).put(field, Int.MAX_VALUE.toLong() + 1)))
            check(if (field == "runRounds") decoded.runRounds == null else decoded.runSteps == null)
        }
        val legacy = AssistantStreamSnapshotJson.encode(measured)
        listOf("runStatus", "runElapsedMillis", "runRounds", "runSteps").forEach(legacy::remove)
        val decoded = requireNotNull(AssistantStreamSnapshotJson.decode(legacy))
        check(decoded == complete)
        check(chatRunMetricsFromSnapshot(42, "conversation", decoded).tokensPerSecond == null)
        for (value in listOf<Any>("FUTURE_STATUS", 7, JSONObject.NULL)) {
            val unknown = requireNotNull(AssistantStreamSnapshotJson.decode(
                AssistantStreamSnapshotJson.encode(measured).put("runStatus", value)))
            check(unknown.runStatus == null && unknown.messages == measured.messages)
        }
    }
    test("WorkspaceStore durably restores interrupted session and preserves terminal session metrics") {
        val root = Files.createTempDirectory("assistant-run-persistence-test").toFile()
        try {
            val store = WorkspaceStore(Context(root))
            for (status in AssistantRunStatus.entries) {
                val stream = toolWaitReducer().snapshot().copy(runStatus = status,
                    runElapsedMillis = 8000, runRounds = 1, runSteps = 1)
                val workspace = WorkspaceSnapshot(listOf(Workspace("default", "Default")),
                    listOf(Conversation("conversation", "Topic", "default")),
                    mapOf("conversation" to listOf(ChatMessage(42, "assistant", stream.text(), stream))),
                    "default", "conversation")
                store.save(workspace)
                val restored = requireNotNull(store.initialize()).messages.getValue("conversation").single().streamSnapshot!!
                val expected = if (status == AssistantRunStatus.RUNNING) AssistantRunStatus.INCOMPLETE else status
                check(restored.runStatus == expected)
                val metrics = chatRunMetricsFromSnapshot(42, "conversation", restored)
                check(metrics.status == expected.toChatRunStatus())
                check(metrics.elapsedMillis == 8000L && metrics.rounds == 1 && metrics.steps == 1)
                check(metrics.totalTokens == 120L && metrics.cacheHitPercent == 50.0)
                store.save(workspace.copy(messages = mapOf("conversation" to
                    listOf(ChatMessage(42, "assistant", restored.text(), restored)))))
                check(store.initialize()!!.messages.getValue("conversation").single().streamSnapshot == restored)
            }
        } finally {
            check(root.deleteRecursively())
        }
    }
    test("WorkspaceStore saves and loads snapshot; legacy v1 v2 migration") {
        val root = Files.createTempDirectory("assistant-stream-test").toFile()
        try {
            val store = WorkspaceStore(Context(root))
            val snapshot = WorkspaceSnapshot(listOf(Workspace("default", "Default")),
                listOf(Conversation("conversation", "Topic", "default")),
                mapOf("conversation" to listOf(ChatMessage(1, "assistant", "answer", complete))),
                "default", "conversation")
            store.save(snapshot)
            check(store.initialize() == snapshot)
            val index = root.resolve("Dafeiyu-Works/workspace-index.json")
            for (version in listOf(1, 2)) {
                val legacy = JSONObject(index.readText()).put("version", version)
                legacy.getJSONObject("messages").getJSONArray("conversation").getJSONObject(0).remove("streamSnapshot")
                index.writeText(legacy.toString())
                check(store.initialize()!!.messages.getValue("conversation").single().streamSnapshot == null)
                store.save(snapshot)
            }
        } finally {
            check(root.deleteRecursively())
        }
    }
    println("Assistant stream checks: $passed passed")
}
