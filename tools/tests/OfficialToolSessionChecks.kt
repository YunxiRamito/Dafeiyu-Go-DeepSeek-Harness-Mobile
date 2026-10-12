package com.dafeiyu.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection

private class SessionConnection(url: URL, response: String) : HttpsURLConnection(url) {
    val sent = ByteArrayOutputStream()
    private val input = ByteArrayInputStream(response.toByteArray(Charsets.UTF_8))
    var disconnected = false
    override fun connect() {}
    override fun disconnect() { disconnected = true }
    override fun usingProxy() = false
    override fun getResponseCode() = 200
    override fun getContentType() = "text/event-stream"
    override fun getInputStream() = input
    override fun getOutputStream() = sent
    override fun getCipherSuite() = "session-offline-fixture"
    override fun getLocalCertificates(): Array<Certificate>? = null
    override fun getServerCertificates(): Array<Certificate> = emptyArray()
    override fun getPeerPrincipal(): Principal? = null
    override fun getLocalPrincipal(): Principal? = null
}
private data class SessionProposal(val id: String, val name: String, val arguments: String = "{}")
private fun sessionResponse(
    calls: List<SessionProposal> = emptyList(),
    reasoning: String? = null,
    text: String? = null,
    finish: String = if (calls.isEmpty()) "stop" else "tool_calls",
    usages: List<JSONObject> = emptyList(),
): String {
    val delta = JSONObject().put("role", "assistant")
    reasoning?.let { delta.put("reasoning_content", it) }
    text?.let { delta.put("content", it) }
    if (calls.isNotEmpty()) delta.put("tool_calls", JSONArray().also { array ->
        calls.forEachIndexed { index, call ->
            array.put(JSONObject().put("index", index).put("id", call.id).put("type", "function")
                .put("function", JSONObject().put("name", call.name).put("arguments", call.arguments)))
        }
    })
    val chunk = JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0)
        .put("delta", delta).put("finish_reason", finish)))
    return buildString {
        append("data: $chunk\n\n")
        usages.forEach { usage ->
            append("data: ${JSONObject().put("choices", JSONArray()).put("usage", usage)}\n\n")
        }
        append("data: [DONE]\n\n")
    }
}
private class SessionTaskStore(
    private val beforeSave: suspend (List<BackgroundTask>) -> Unit = {},
) : BackgroundTaskStore {
    @Volatile private var records: List<BackgroundTask> = emptyList()
    override suspend fun load(): List<BackgroundTask> = records
    override suspend fun save(tasks: List<BackgroundTask>) {
        beforeSave(tasks)
        records = BackgroundTaskJson.decode(BackgroundTaskJson.encode(tasks))
    }
}
private val sessionModelReady: (String) -> BackgroundTaskAvailability = { BackgroundTaskAvailability(true) }
private fun subagent(id: String, prompt: String, run: Boolean? = true): SessionProposal {
    val arguments = JSONObject().put("title", prompt).put("prompt", prompt).put("model", "fixture-model")
    run?.let { arguments.put("run", it) }
    return SessionProposal(id, "create_subagent", arguments.toString())
}
private fun SessionRun.toolData(index: Int): JSONObject {
    val message = body(1).getJSONArray("messages").getJSONObject(index + 2)
    check(message.getString("role") == "tool")
    val response = JSONObject(message.getString("content"))
    check(response.getBoolean("ok"))
    return response.getJSONObject("data")
}
private fun usage(input: Long, output: Long, total: Long, cached: Long, reasoning: Long): JSONObject =
    JSONObject().put("prompt_tokens", input).put("completion_tokens", output).put("total_tokens", total)
        .put("prompt_cache_hit_tokens", cached)
        .put("completion_tokens_details", JSONObject().put("reasoning_tokens", reasoning))
private data class SessionRun(
    val events: List<AssistantStreamEvent>,
    val outputs: List<OfficialToolResult>,
    val connections: List<SessionConnection>,
    val store: OfficialToolOutputStore,
) {
    fun body(index: Int) = JSONObject(connections[index].sent.toString("UTF-8"))
    fun snapshot(): AssistantStreamSnapshot {
        val reducer = AssistantStreamReducer(startSequenceNumber = 101, source = AssistantStreamSource.BACKEND)
        events.forEachIndexed { index, event ->
            check(event.sequenceNumber == 101L + index) { "sequence gap at $index" }
            check(reducer.apply(event).disposition == AssistantStreamReducer.ApplyDisposition.APPLIED)
        }
        return reducer.finish()
    }
}
private suspend fun session(
    root: File,
    responses: List<String>,
    throwOnOutput: Boolean = false,
    engine: BackgroundTaskEngine? = null,
    subagentWaitMillis: Long = 5_000,
    onModelRequest: (Int) -> Unit = {},
    onToolOutput: suspend (OfficialToolResult) -> Unit = {},
    dispatcherFactory: ((OfficialToolOutputStore) -> OfficialToolDispatcher)? = null,
): SessionRun {
    check(root.mkdir())
    val store = OfficialToolOutputStore(root) { 1L }
    val dispatcher = dispatcherFactory?.invoke(store) ?: OfficialToolDispatcher(
        { engine }, store, "session-fixture", { "fixture-model" },
        Dispatchers.Unconfined, subagentWaitMillis = subagentWaitMillis)
    val connections = mutableListOf<SessionConnection>()
    val client = DeepSeekChatClient({ "offline-session-key" }, DeepSeekChatClient.Config(), { url ->
        check(connections.size < responses.size) { "unexpected extra model round" }
        onModelRequest(connections.size)
        SessionConnection(url, responses[connections.size]).also { connections += it }
    })
    val events = mutableListOf<AssistantStreamEvent>()
    val outputs = mutableListOf<OfficialToolResult>()
    OfficialToolSession.generate(client, DeepSeekChatClient.Request("fixture-model",
        listOf(DeepSeekChatClient.HistoryMessage("user", "explicit task")), sequenceStart = 101), dispatcher,
        onOutput = {
            // Outputs must exist before success is announced in event callbacks.
            it.outputs.forEach { output -> check(store.get(output.id) == output) }
            outputs += it
            check(events.none { event -> event is AssistantStreamEvent.ToolResult && event.callId == it.callId })
            if (throwOnOutput) error("fixture-output-commit-failure")
            onToolOutput(it)
        },
        onEvent = { events += it },
    )
    check(connections.all { it.disconnected })
    return SessionRun(events, outputs, connections, store)
}

fun main(args: Array<String>) = runBlocking {
    val root = File(args.single()).apply { check(mkdirs() || isDirectory) }
    var passed = 0
    val failures = mutableListOf<String>()
    suspend fun test(name: String, block: suspend () -> Unit) {
        try {
            withTimeout(10_000) { block() }
            passed++
            println("PASS $name")
        } catch (error: Throwable) {
            failures += name
            println("FAIL $name: ${error.message}")
        }
    }
    test("tool-only first round preserves empty assistant content and each tool result before answer") {
        val actual = session(File(root, "tool-only"), listOf(
            sessionResponse(listOf(SessionProposal("call-a", "write_text_file",
                """{"filename":"a.txt","text":"first\nresult"}"""),
                SessionProposal("call-b", "write_text_file", """{"filename":"b.txt","text":"second"}"""))),
            sessionResponse(text = "Both saved"),
        ))
        check(actual.connections.size == 2 && actual.outputs.size == 2 && actual.store.list().size == 2)
        val messages = actual.body(1).getJSONArray("messages")
        check(messages.length() == 4)
        val assistant = messages.getJSONObject(1)
        check(assistant.getString("role") == "assistant" && assistant.getString("content").isEmpty())
        check(assistant.getJSONArray("tool_calls").length() == 2)
        actual.outputs.forEachIndexed { index, output ->
            val message = messages.getJSONObject(index + 2)
            check(message.getString("role") == "tool" && message.getString("tool_call_id") == output.callId)
            check(message.getString("content") == output.content && JSONObject(output.content).getBoolean("ok"))
        }
        val snapshot = actual.snapshot()
        check(snapshot.messages.size == 2 && snapshot.messages.all { it.status == AssistantMessageStatus.COMPLETED })
        check(snapshot.toolCalls.all { it.status == AssistantStreamEvent.ToolStatus.SUCCEEDED })
        val references = snapshot.activityOrder.filter { it.kind == AssistantActivityKind.TOOL }
        check(references.size == 2 && references.all { it.messageId == snapshot.messages[0].messageId })
    }
    test("three rounds preserve per-assistant reasoning and tool grouping with contiguous sequence") {
        val actual = session(File(root, "reasoning"), listOf(
            sessionResponse(listOf(SessionProposal("first", "list_tasks")), reasoning = "first thinking"),
            sessionResponse(listOf(SessionProposal("second", "get_task",
                """{"task_id":"00000000-0000-0000-0000-000000000000"}""")),
                reasoning = "second thinking", text = "checking"),
            sessionResponse(text = "unavailable"),
        ))
        val history = actual.body(2).getJSONArray("messages")
        check(history.length() == 5)
        check(history.getJSONObject(1).getString("reasoning_content") == "first thinking")
        check(history.getJSONObject(3).getString("reasoning_content") == "second thinking")
        check(history.getJSONObject(3).getString("content") == "checking")
        check(history.getJSONObject(2).getString("tool_call_id") == "first")
        check(history.getJSONObject(4).getString("tool_call_id") == "second")
        val snapshot = actual.snapshot()
        val references = snapshot.activityOrder.filter { it.kind == AssistantActivityKind.TOOL }
        check(references.map { it.messageId } == snapshot.messages.take(2).map { it.messageId })
        check(snapshot.toolCalls.all { it.status == AssistantStreamEvent.ToolStatus.FAILED && it.result != null })
        check(actual.outputs.all { !JSONObject(it.content).getBoolean("ok") })
    }
    test("unadvertised function is refused with error JSON and no output files then model resumes") {
        val actual = session(File(root, "unsupported"), listOf(
            sessionResponse(listOf(SessionProposal("unknown", "shell", """{"command":"echo nope"}"""))),
            sessionResponse(text = "refused"),
        ))
        val schemas = actual.body(0).getJSONArray("tools")
        check((0 until schemas.length()).none {
            schemas.getJSONObject(it).getJSONObject("function").getString("name") == "shell"
        })
        check(JSONObject(actual.outputs.single().content).getString("error") == "unsupported_tool")
        check(actual.store.list().isEmpty())
        val call = actual.snapshot().toolCalls.single()
        check(call.status == AssistantStreamEvent.ToolStatus.FAILED && call.result == actual.outputs.single().content)
        check(actual.body(1).getJSONArray("messages").getJSONObject(2).getString("tool_call_id") == "unknown")
    }
    test("malformed argument JSON produces invalid_arguments result and no side effect") {
        val actual = session(File(root, "malformed"), listOf(
            sessionResponse(listOf(SessionProposal("bad", "write_text_file", "{\"filename\":"))),
            sessionResponse(text = "invalid input"),
        ))
        check(JSONObject(actual.outputs.single().content).getString("error") == "invalid_arguments")
        check(actual.store.list().isEmpty())
        check(actual.snapshot().toolCalls.single().status == AssistantStreamEvent.ToolStatus.FAILED)
    }
    test("incomplete model tool turn never reaches dispatcher or output callback") {
        val actual = session(File(root, "incomplete"), listOf(sessionResponse(
            listOf(SessionProposal("partial", "write_text_file", """{"filename":"x.txt","text":"x"}""")),
            finish = "length",
        )))
        check(actual.connections.size == 1 && actual.outputs.isEmpty() && actual.store.list().isEmpty())
        check(actual.snapshot().messages.single().status == AssistantMessageStatus.INCOMPLETE)
    }
    test("six-round limit stops before dispatching final proposals and publishes incomplete marker") {
        val actual = session(File(root, "round-limit"), List(6) { round ->
            sessionResponse(listOf(SessionProposal("round-$round", "list_tasks")))
        })
        check(actual.connections.size == 6 && actual.outputs.size == 5)
        check(actual.events.filterIsInstance<AssistantStreamEvent.ToolError>().last().code == "TOOL_LIMIT")
        check(actual.events.last() is AssistantStreamEvent.MessageIncomplete)
        check(actual.snapshot().messages.last().incompleteReason == "tool_round_limit")
    }
    test("batch limit refuses five proposals before any dispatcher side effects") {
        val actual = session(File(root, "batch-limit"), listOf(sessionResponse(List(5) {
            SessionProposal("batch-$it", "list_tasks")
        })))
        check(actual.outputs.isEmpty() && actual.connections.size == 1)
        check(actual.events.filterIsInstance<AssistantStreamEvent.ToolError>().size == 5)
        check(actual.snapshot().messages.last().incompleteReason == "tool_round_limit")
    }
    test("session permits sixteen calls and refuses seventeenth before dispatch") {
        val actual = session(File(root, "call-limit"), List(5) { round ->
            sessionResponse(List(4) { index -> SessionProposal("limit-${round * 4 + index}", "list_tasks") })
        })
        check(actual.outputs.size == 16 && actual.connections.size == 5)
        check(actual.events.filterIsInstance<AssistantStreamEvent.ToolError>().count { it.code == "TOOL_LIMIT" } == 4)
        check(actual.snapshot().messages.last().incompleteReason == "tool_round_limit")
    }
    test("output callback failure propagates and prevents another model round") {
        val failure = runCatching {
            session(File(root, "callback-failure"), listOf(sessionResponse(listOf(
                SessionProposal("write", "write_text_file", """{"filename":"committed.txt","text":"committed"}"""),
            ))), throwOnOutput = true)
        }.exceptionOrNull()
        check(failure?.message == "fixture-output-commit-failure")
        val store = OfficialToolOutputStore(File(root, "callback-failure"))
        check(store.list().single().filename == "committed.txt")
    }
    test("duplicate call ID across rounds stops entire replay batch before any new side effect") {
        val original = """{"filename":"original.txt","text":"original"}"""
        for ((index, arguments) in listOf(original, """{"filename":"changed.txt","text":"changed"}""").withIndex()) {
            val actual = session(File(root, "duplicate-$index"), listOf(
                sessionResponse(listOf(SessionProposal("reused", "write_text_file", original))),
                sessionResponse(listOf(
                    SessionProposal("fresh", "write_text_file", """{"filename":"fresh.txt","text":"new"}"""),
                    SessionProposal("reused", "write_text_file", arguments),
                )),
            ))
            check(actual.connections.size == 2 && actual.outputs.size == 1)
            check(actual.outputs.single().callId == "reused")
            check(actual.store.list().single().filename == "original.txt")
            check(actual.events.filterIsInstance<AssistantStreamEvent.ToolResult>().size == 1)
            val marker = actual.events.last() as AssistantStreamEvent.MessageIncomplete
            check(marker.reason == "duplicate_tool_call_id")
            check(actual.snapshot().messages.last().incompleteReason == "duplicate_tool_call_id")
        }
    }
    test("three subagents are admitted before waiting and only terminal sibling results reach next request") {
        coroutineScope {
            val entered = List(3) { CompletableDeferred<Unit>() }
            val release = List(3) { CompletableDeferred<Unit>() }
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            val started = AtomicInteger()
            val requests = AtomicInteger()
            val store = SessionTaskStore()
            BackgroundTaskEngine.open(store, BackgroundTaskRunner { request, emit ->
                check(request.kind == BackgroundTaskKind.SUBAGENT &&
                    request.parentConversationId == "session-fixture" && !request.allowTaskCreation)
                val index = request.prompt.removePrefix("child-").toInt()
                val count = active.incrementAndGet()
                maximum.updateAndGet { maxOf(it, count) }
                started.incrementAndGet()
                try {
                    emit("progress-$index")
                    entered[index].complete(Unit)
                    release[index].await()
                    BackgroundTaskResult("answer-$index")
                } finally { active.decrementAndGet() }
            }, sessionModelReady).use { engine ->
                val parent = async {
                    session(File(root, "siblings"), listOf(
                        sessionResponse((0..2).map { subagent("child-call-$it", "child-$it") }),
                        sessionResponse(text = "combined sibling results"),
                    ), engine = engine, onModelRequest = { round ->
                        requests.incrementAndGet()
                        if (round == 1) {
                            check(engine.tasks.value.size == 3 &&
                                engine.tasks.value.all { it.status == BackgroundTaskStatus.SUCCEEDED })
                        }
                    })
                }
                try {
                    engine.tasks.first { it.size == 3 }
                    entered[0].await()
                    entered[1].await()
                    check(!parent.isCompleted && requests.get() == 1)
                    check(started.get() == 2 && maximum.get() == 2)
                    check(engine.tasks.value.count { it.status == BackgroundTaskStatus.QUEUED } == 1)
                    check(store.load().size == 3)
                    // Child zero stays blocked while child one releases the slot for child two.
                    release[1].complete(Unit)
                    entered[2].await()
                    check(started.get() == 3 && !parent.isCompleted && requests.get() == 1)
                    release[2].complete(Unit)
                    release[0].complete(Unit)
                    val actual = parent.await()
                    check(actual.connections.size == 2 && actual.outputs.size == 3 && maximum.get() == 2)
                    for (index in 0..2) {
                        val message = actual.body(1).getJSONArray("messages").getJSONObject(index + 2)
                        check(message.getString("tool_call_id") == "child-call-$index")
                        val data = actual.toolData(index)
                        check(data.getString("status") == "SUCCEEDED" &&
                            data.getString("result") == "answer-$index" && !data.getBoolean("wait_timed_out"))
                        check(!data.has("prompt") && !data.has("progress") && !data.has("reasoning"))
                    }
                    val snapshot = actual.snapshot()
                    check(snapshot.subagents.size == 3 &&
                        snapshot.subagents.all { it.status == AssistantStreamEvent.SubagentStatus.COMPLETED })
                    check(snapshot.subagents.map { it.output }.toSet() == setOf("answer-0", "answer-1", "answer-2"))
                    check(store.load().all { it.status == BackgroundTaskStatus.SUCCEEDED })
                } finally {
                    release.forEach { it.complete(Unit) }
                    parent.cancelAndJoin()
                }
            }
        }
    }
    test("failed subagent returns sanitized terminal failure and successful sibling result to parent") {
        BackgroundTaskEngine.open(SessionTaskStore(), BackgroundTaskRunner { request, _ ->
            if (request.prompt == "failure") error("fixture-private-error-details")
            BackgroundTaskResult("healthy sibling answer")
        }, sessionModelReady).use { engine ->
            val actual = session(File(root, "child-failure"), listOf(
                sessionResponse(listOf(subagent("failure-call", "failure"), subagent("healthy-call", "healthy"))),
                sessionResponse(text = "one failed one succeeded"),
            ), engine = engine)
            val failed = actual.toolData(0)
            check(failed.getString("status") == "FAILED" && failed.getString("error_code") == "execution_failed")
            check(failed.getString("result").isEmpty() && !failed.getBoolean("wait_timed_out"))
            check(!actual.body(1).toString().contains("fixture-private-error-details"))
            check(actual.toolData(1).getString("result") == "healthy sibling answer")
            check(actual.events.filterIsInstance<AssistantStreamEvent.SubagentFailed>().single().error == "execution_failed")
            check(actual.snapshot().subagents.map { it.status }.toSet() == setOf(
                AssistantStreamEvent.SubagentStatus.FAILED, AssistantStreamEvent.SubagentStatus.COMPLETED))
        }
    }
    test("stopping subagent waits for runner cleanup then returns STOPPED without a result") {
        coroutineScope {
            val entered = CompletableDeferred<Unit>()
            val cleanup = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            BackgroundTaskEngine.open(SessionTaskStore(), BackgroundTaskRunner { _, _ ->
                entered.complete(Unit)
                try {
                    release.await()
                    BackgroundTaskResult("must not publish")
                } finally {
                    withContext(NonCancellable) { cleanup.await() }
                }
            }, sessionModelReady).use { engine ->
                val parent = async {
                    session(File(root, "child-stopped"), listOf(
                        sessionResponse(listOf(subagent("stop-call", "stop"))), sessionResponse(text = "stopped"),
                    ), engine = engine, onModelRequest = { round ->
                        if (round == 1) check(engine.tasks.value.single().status == BackgroundTaskStatus.STOPPED)
                    })
                }
                try {
                    entered.await()
                    engine.stop(engine.tasks.value.single().id)
                    check(engine.tasks.value.single().status == BackgroundTaskStatus.STOPPING && !parent.isCompleted)
                    cleanup.complete(Unit)
                    val actual = parent.await()
                    check(actual.connections.size == 2)
                    val data = actual.toolData(0)
                    check(data.getString("status") == "STOPPED" && data.getString("result").isEmpty())
                    check(!data.getBoolean("wait_timed_out"))
                    check(actual.events.filterIsInstance<AssistantStreamEvent.SubagentCancelled>().single().reason == "STOPPED")
                    check(actual.snapshot().subagents.single().status == AssistantStreamEvent.SubagentStatus.CANCELLED)
                } finally {
                    cleanup.complete(Unit)
                    release.complete(Unit)
                    parent.cancelAndJoin()
                }
            }
        }
    }
    test("engine interruption returns INTERRUPTED without reporting success or restarting child") {
        coroutineScope {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val runs = AtomicInteger()
            BackgroundTaskEngine.open(SessionTaskStore(), BackgroundTaskRunner { _, _ ->
                runs.incrementAndGet()
                entered.complete(Unit)
                release.await()
                BackgroundTaskResult("not completed")
            }, sessionModelReady).use { engine ->
                val parent = async {
                    session(File(root, "child-interrupted"), listOf(
                        sessionResponse(listOf(subagent("interrupt-call", "interrupt"))),
                        sessionResponse(text = "interrupted"),
                    ), engine = engine)
                }
                try {
                    entered.await()
                    engine.close()
                    val actual = parent.await()
                    val data = actual.toolData(0)
                    check(data.getString("status") == "INTERRUPTED" && data.getString("result").isEmpty())
                    check(runs.get() == 1 && actual.connections.size == 2)
                    check(actual.events.filterIsInstance<AssistantStreamEvent.SubagentCancelled>().single().reason == "INTERRUPTED")
                    check(actual.events.none { it is AssistantStreamEvent.SubagentCompleted })
                } finally {
                    release.complete(Unit)
                    parent.cancelAndJoin()
                }
            }
        }
    }
    test("subagent wait timeout returns active status and task continues to a real result") {
        val release = CompletableDeferred<Unit>()
        val runs = AtomicInteger()
        val cancelled = AtomicInteger()
        BackgroundTaskEngine.open(SessionTaskStore(), BackgroundTaskRunner { _, _ ->
            runs.incrementAndGet()
            try {
                release.await()
                BackgroundTaskResult("answer after parent wait")
            } catch (failure: CancellationException) {
                cancelled.incrementAndGet()
                throw failure
            }
        }, sessionModelReady).use { engine ->
            try {
                val actual = session(File(root, "child-timeout"), listOf(
                    sessionResponse(listOf(subagent("timeout-call", "timeout"))),
                    sessionResponse(text = "still running"),
                ), engine = engine, subagentWaitMillis = 75)
                val data = actual.toolData(0)
                check(data.getBoolean("wait_timed_out") && data.getString("result").isEmpty())
                check(data.getString("status") in setOf("QUEUED", "RUNNING"))
                check(actual.events.none { it is AssistantStreamEvent.SubagentCompleted ||
                    it is AssistantStreamEvent.SubagentFailed || it is AssistantStreamEvent.SubagentCancelled })
                check(actual.events.filterIsInstance<AssistantStreamEvent.SubagentStatusChanged>().last().status ==
                    AssistantStreamEvent.SubagentStatus.RUNNING)
                release.complete(Unit)
                val completed = engine.awaitTerminal(data.getString("task_id"))
                check(completed.status == BackgroundTaskStatus.SUCCEEDED &&
                    completed.result == "answer after parent wait")
                check(runs.get() == 1 && cancelled.get() == 0 && actual.connections.size == 2)
            } finally { release.complete(Unit) }
        }
    }
    test("explicit and default drafts return immediately without execution or terminal waiting") {
        val runs = AtomicInteger()
        BackgroundTaskEngine.open(SessionTaskStore(), BackgroundTaskRunner { _, _ ->
            runs.incrementAndGet()
            BackgroundTaskResult("unexpected draft execution")
        }, sessionModelReady).use { engine ->
            val actual = session(File(root, "child-drafts"), listOf(
                sessionResponse(listOf(subagent("false-draft", "explicit draft", false),
                    subagent("default-draft", "default draft", null))),
                sessionResponse(text = "drafts saved"),
            ), engine = engine, subagentWaitMillis = 5_000)
            check(runs.get() == 0 && engine.tasks.value.all { it.status == BackgroundTaskStatus.DRAFT })
            for (index in 0..1) {
                val data = actual.toolData(index)
                check(data.getString("status") == "DRAFT" && !data.getBoolean("run_requested"))
                check(!data.has("wait_timed_out") && !data.has("result"))
            }
            check(actual.events.filterIsInstance<AssistantStreamEvent.ToolResult>().size == 2)
            check(actual.events.none { it is AssistantStreamEvent.SubagentCompleted })
        }
    }
    test("unconfigured subagent execution is refused before creating or starting a task") {
        BackgroundTaskEngine.open(SessionTaskStore()).use { engine ->
            val actual = session(File(root, "child-unconfigured"), listOf(
                sessionResponse(listOf(subagent("unconfigured", "cannot run"))),
                sessionResponse(text = "configuration required"),
            ), engine = engine)
            check(engine.tasks.value.isEmpty())
            val message = actual.body(1).getJSONArray("messages").getJSONObject(2)
            val response = JSONObject(message.getString("content"))
            check(!response.getBoolean("ok") && response.getString("error") == "model_unavailable")
            check(actual.events.none { it is AssistantStreamEvent.SubagentStarted })
        }
    }
    test("cancelling parent leaves running and queued independent children completing without another parent request") {
        coroutineScope {
            val entered = List(3) { CompletableDeferred<Unit>() }
            val release = CompletableDeferred<Unit>()
            val cancelled = AtomicInteger()
            val requests = AtomicInteger()
            val store = SessionTaskStore()
            BackgroundTaskEngine.open(store, BackgroundTaskRunner { request, _ ->
                val index = request.prompt.removePrefix("independent-").toInt()
                entered[index].complete(Unit)
                try {
                    release.await()
                    BackgroundTaskResult("independent answer $index")
                } catch (failure: CancellationException) {
                    cancelled.incrementAndGet()
                    throw failure
                }
            }, sessionModelReady).use { engine ->
                val parent = async {
                    session(File(root, "parent-cancel"), listOf(sessionResponse((0..2).map {
                        subagent("independent-call-$it", "independent-$it")
                    })), engine = engine, onModelRequest = { requests.incrementAndGet() })
                }
                try {
                    engine.tasks.first { it.size == 3 }
                    entered[0].await()
                    entered[1].await()
                    parent.cancelAndJoin()
                    check(parent.isCancelled && requests.get() == 1 && cancelled.get() == 0)
                    check(engine.tasks.value.count { it.status == BackgroundTaskStatus.RUNNING } == 2)
                    check(engine.tasks.value.count { it.status == BackgroundTaskStatus.QUEUED } == 1)
                    release.complete(Unit)
                    for (task in engine.tasks.value) {
                        val final = engine.awaitTerminal(task.id)
                        check(final.status == BackgroundTaskStatus.SUCCEEDED &&
                            final.result == "independent answer ${task.prompt.removePrefix("independent-")}")
                    }
                    entered[2].await()
                    check(cancelled.get() == 0 && requests.get() == 1)
                    check(store.load().all { it.status == BackgroundTaskStatus.SUCCEEDED })
                } finally {
                    release.complete(Unit)
                    parent.cancelAndJoin()
                }
            }
        }
    }
    test("three queued sibling waits share one timeout window and preserve queued labels in parent history") {
        val waitMillis = 650L
        val release = CompletableDeferred<Unit>()
        val blockersEntered = List(2) { CompletableDeferred<Unit>() }
        BackgroundTaskEngine.open(SessionTaskStore(), BackgroundTaskRunner { request, _ ->
            if (request.prompt.startsWith("blocker-")) {
                blockersEntered[request.prompt.removePrefix("blocker-").toInt()].complete(Unit)
                release.await()
            }
            BackgroundTaskResult("answer for ${request.prompt}")
        }, sessionModelReady).use { engine ->
            try {
                for (index in 0..1) {
                    val task = engine.create("Blocker $index", "fixture-model", "blocker-$index")
                    engine.enqueue(task.id)
                }
                blockersEntered.forEach { it.await() }
                var lastAdmissionAt = 0L
                var parentResumedAt = 0L
                val actual = session(File(root, "shared-wait-budget"), listOf(
                    sessionResponse((0..2).map { subagent("queued-call-$it", "queued-$it") }),
                    sessionResponse(text = "three children still queued"),
                ), engine = engine, subagentWaitMillis = waitMillis,
                    onToolOutput = { result ->
                        if (result.callId == "queued-call-2") lastAdmissionAt = System.nanoTime()
                    },
                    onModelRequest = { round ->
                        if (round == 1) parentResumedAt = System.nanoTime()
                    })
                val elapsedMillis = (parentResumedAt - lastAdmissionAt) / 1_000_000
                check(lastAdmissionAt > 0 && parentResumedAt > lastAdmissionAt)
                // The upper bound allows scheduling jitter but rejects three serial timeout budgets.
                check(elapsedMillis >= waitMillis / 2 && elapsedMillis < waitMillis * 2 + 150) {
                    "three waits took ${elapsedMillis}ms for a ${waitMillis}ms per-sibling budget"
                }
                println("INFO three sibling waits: ${elapsedMillis}ms (budget ${waitMillis}ms; serial would exceed ${waitMillis * 3}ms)")
                check(actual.connections.size == 2 && actual.outputs.size == 3)
                val snapshot = actual.snapshot()
                for (index in 0..2) {
                    val message = actual.body(1).getJSONArray("messages").getJSONObject(index + 2)
                    check(message.getString("tool_call_id") == "queued-call-$index")
                    val data = actual.toolData(index)
                    check(data.getString("status") == "QUEUED" && data.getBoolean("wait_timed_out"))
                    check(data.getString("result").isEmpty())
                    val taskId = data.getString("task_id")
                    val label = actual.events.filterIsInstance<AssistantStreamEvent.SubagentStatusChanged>()
                        .last { it.agentId == taskId }
                    check(label.status == AssistantStreamEvent.SubagentStatus.INCOMPLETE) {
                        "queued child was labeled ${label.status}: ${label.detail}"
                    }
                    check(label.detail.orEmpty().contains("QUEUED")) {
                        "queued child has no queued label: ${label.detail}"
                    }
                    check(snapshot.subagents.first { it.agentId == taskId }.status !=
                        AssistantStreamEvent.SubagentStatus.COMPLETED)
                }
                check(actual.events.none { it is AssistantStreamEvent.SubagentCompleted ||
                    it is AssistantStreamEvent.SubagentCancelled || it is AssistantStreamEvent.SubagentFailed })
            } finally { release.complete(Unit) }
        }
    }
    test("stopping timeout preserves stopping label and does not announce cancellation before cleanup finishes") {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        BackgroundTaskEngine.open(SessionTaskStore(), BackgroundTaskRunner { _, _ ->
            entered.complete(Unit)
            try {
                release.await()
                BackgroundTaskResult("should not be published")
            } finally { withContext(NonCancellable) { cleanup.await() } }
        }, sessionModelReady).use { engine ->
            try {
                val actual = session(File(root, "stopping-timeout"), listOf(
                    sessionResponse(listOf(subagent("stopping-call", "stopping"))),
                    sessionResponse(text = "child is stopping"),
                ), engine = engine, subagentWaitMillis = 100,
                    onToolOutput = { initial ->
                        entered.await()
                        val taskId = JSONObject(initial.content).getJSONObject("data").getString("task_id")
                        engine.stop(taskId)
                        check(engine.tasks.value.single().status == BackgroundTaskStatus.STOPPING)
                    })
                val data = actual.toolData(0)
                check(data.getString("status") == "STOPPING" && data.getBoolean("wait_timed_out"))
                check(data.getString("result").isEmpty())
                val label = actual.events.filterIsInstance<AssistantStreamEvent.SubagentStatusChanged>().last()
                check(label.status == AssistantStreamEvent.SubagentStatus.INCOMPLETE) {
                    "stopping child status label: ${label.status}, detail=${label.detail}"
                }
                check(label.detail.orEmpty().contains("STOPPING")) {
                    "stopping child has misleading label: ${label.detail}"
                }
                check(actual.events.none { it is AssistantStreamEvent.SubagentCancelled ||
                    it is AssistantStreamEvent.SubagentCompleted || it is AssistantStreamEvent.SubagentFailed })
                cleanup.complete(Unit)
                check(engine.awaitTerminal(data.getString("task_id")).status == BackgroundTaskStatus.STOPPED)
            } finally {
                cleanup.complete(Unit)
                release.complete(Unit)
            }
        }
    }
    test("start_error emits subagent failure while parent still receives healthy sibling terminal result") {
        val queuedWriteFailures = AtomicInteger()
        val runs = AtomicInteger()
        val store = SessionTaskStore { records ->
            if (records.any { it.prompt == "start-failure" && it.status == BackgroundTaskStatus.QUEUED }) {
                queuedWriteFailures.incrementAndGet()
                error("fixture queue commit failure")
            }
        }
        BackgroundTaskEngine.open(store, BackgroundTaskRunner { request, _ ->
            runs.incrementAndGet()
            check(request.prompt == "healthy-after-start-failure")
            BackgroundTaskResult("healthy terminal answer")
        }, sessionModelReady).use { engine ->
            val actual = session(File(root, "start-error"), listOf(
                sessionResponse(listOf(subagent("not-started", "start-failure"),
                    subagent("healthy-after-error", "healthy-after-start-failure"))),
                sessionResponse(text = "one did not start, one completed"),
            ), engine = engine)
            val failed = actual.toolData(0)
            check(failed.getBoolean("run_requested") && failed.getString("start_error") == "task_not_started")
            check(failed.getString("status") == "DRAFT" && !failed.optBoolean("wait_timed_out"))
            check(queuedWriteFailures.get() == 1 && runs.get() == 1)
            val taskId = failed.getString("task_id")
            val failure = actual.events.filterIsInstance<AssistantStreamEvent.SubagentFailed>()
                .single { it.agentId == taskId }
            check(failure.error.isNotBlank() && !failure.error.contains("fixture queue commit failure")) {
                "missing or unsanitized start_error event: ${failure.error}"
            }
            check(actual.events.none { it is AssistantStreamEvent.SubagentCompleted && it.agentId == taskId })
            check(actual.snapshot().subagents.first { it.agentId == taskId }.status ==
                AssistantStreamEvent.SubagentStatus.FAILED)
            val healthy = actual.toolData(1)
            check(healthy.getString("status") == "SUCCEEDED" && healthy.getString("result") == "healthy terminal answer")
            check(engine.tasks.value.first { it.id == taskId }.status == BackgroundTaskStatus.DRAFT)
            check(actual.connections.size == 2)
        }
    }
    test("forged ownership and malformed await results are isolated errors without contaminating healthy session") {
        BackgroundTaskEngine.open(SessionTaskStore(), BackgroundTaskRunner { request, _ ->
            BackgroundTaskResult("public answer ${request.prompt}")
        }, sessionModelReady).use { engine ->
            val foreign = engine.create("Foreign title", "fixture-model", "foreign",
                BackgroundTaskKind.SUBAGENT, "other-conversation")
            val wrongKind = engine.create("Background title", "fixture-model", "background",
                BackgroundTaskKind.BACKGROUND, "session-fixture")
            val unowned = engine.create("Unregistered title", "fixture-model", "unregistered",
                BackgroundTaskKind.SUBAGENT, "session-fixture")
            for (task in listOf(foreign, wrongKind, unowned)) {
                engine.enqueue(task.id)
                check(engine.awaitTerminal(task.id).status == BackgroundTaskStatus.SUCCEEDED)
            }
            lateinit var dispatcher: OfficialToolDispatcher
            var rejected = 0
            val actual = session(File(root, "ownership-forgery"), listOf(
                sessionResponse(listOf(subagent("owned-first", "owned-first"),
                    subagent("owned-second", "owned-second"))),
                sessionResponse(text = "only owned sibling results"),
            ), engine = engine,
                dispatcherFactory = { store ->
                    OfficialToolDispatcher({ engine }, store, "session-fixture", { "fixture-model" },
                        Dispatchers.Unconfined, subagentWaitMillis = 100).also { dispatcher = it }
                },
                onToolOutput = { initial ->
                    if (initial.callId == "owned-first") {
                        val original = JSONObject(initial.content)
                        val forgeries = listOf(foreign, wrongKind, unowned).map { victim ->
                            val forged = JSONObject(original.toString())
                            forged.getJSONObject("data").put("task_id", victim.id).put("title", "forged title")
                            initial.copy(content = forged.toString())
                        } + listOf(initial.copy(callId = "never-admitted"), initial.copy(content = "{"),
                            initial.copy(content = """{"ok":true}"""))
                        for (forged in forgeries) {
                            val attempt = runCatching { withTimeout(500) { dispatcher.awaitSubagent(forged) } }
                            check(attempt.isSuccess) { "forged await escaped as an exception: ${attempt.exceptionOrNull()}" }
                            val refused = JSONObject(attempt.getOrThrow().content)
                            check(!refused.optBoolean("ok")) { "forged result was accepted: $refused" }
                            check(refused.optString("error").isNotBlank())
                            check(!attempt.getOrThrow().content.contains("public answer"))
                            check(!attempt.getOrThrow().content.contains("Foreign title") &&
                                !attempt.getOrThrow().content.contains("Background title") &&
                                !attempt.getOrThrow().content.contains("Unregistered title"))
                            rejected++
                        }
                    }
                })
            check(rejected == 6 && actual.connections.size == 2)
            val nextBody = actual.body(1).toString()
            check(listOf(foreign.id, wrongKind.id, unowned.id, "forged title", "never-admitted")
                .none(nextBody::contains))
            check(actual.toolData(0).getString("result") == "public answer owned-first")
            check(actual.toolData(1).getString("result") == "public answer owned-second")
            check(actual.snapshot().subagents.size == 2 &&
                actual.snapshot().subagents.all { it.status == AssistantStreamEvent.SubagentStatus.COMPLETED })
        }
    }
    test("deleted owned child produces local await failure and does not abort healthy sibling or next model round") {
        val runnerEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        BackgroundTaskEngine.open(SessionTaskStore(), BackgroundTaskRunner { request, _ ->
            if (request.prompt == "deleted") {
                runnerEntered.complete(Unit)
                release.await()
            }
            BackgroundTaskResult("answer ${request.prompt}")
        }, sessionModelReady).use { engine ->
            try {
                var deletedId = ""
                val actual = session(File(root, "deleted-await"), listOf(
                    sessionResponse(listOf(subagent("deleted-call", "deleted"),
                        subagent("surviving-call", "surviving"))),
                    sessionResponse(text = "deleted child unavailable, sibling completed"),
                ), engine = engine, onToolOutput = { initial ->
                    if (initial.callId == "deleted-call") {
                        runnerEntered.await()
                        deletedId = JSONObject(initial.content).getJSONObject("data").getString("task_id")
                        engine.delete(deletedId)
                    }
                })
                val message = actual.body(1).getJSONArray("messages").getJSONObject(2)
                check(message.getString("tool_call_id") == "deleted-call")
                val error = JSONObject(message.getString("content"))
                check(!error.getBoolean("ok") && error.optString("error").isNotBlank())
                check(actual.events.filterIsInstance<AssistantStreamEvent.SubagentFailed>().any { it.agentId == deletedId })
                check(actual.toolData(1).getString("result") == "answer surviving")
                check(actual.connections.size == 2 &&
                    actual.snapshot().messages.all { it.status == AssistantMessageStatus.COMPLETED })
                check(engine.tasks.value.none { it.id == deletedId })
            } finally { release.complete(Unit) }
        }
    }
    test("multi-round usage sums rounds but replaces repeated and partial snapshots within each round") {
        val actual = session(File(root, "usage-sum"), listOf(
            sessionResponse(listOf(SessionProposal("usage-first", "list_tasks")), usages = listOf(
                usage(10, 4, 14, 2, 3), usage(12, 6, 18, 5, 4),
                JSONObject().put("prompt_tokens_details", JSONObject().put("cached_tokens", 5)),
            )),
            sessionResponse(listOf(SessionProposal("usage-second", "list_tasks")), usages = listOf(
                JSONObject().put("prompt_tokens", 7).put("completion_tokens", 2).put("total_tokens", 9)
                    .put("prompt_tokens_details", JSONObject().put("cached_tokens", 1))
                    .put("completion_tokens_details", JSONObject().put("reasoning_tokens", 1)),
                JSONObject().put("completion_tokens", 3).put("total_tokens", 10),
            )),
            sessionResponse(text = "finished", usages = listOf(usage(3, 4, 7, 0, 2))),
        ))
        val cumulative = actual.events.filterIsInstance<AssistantStreamEvent.UsageReported>().map { it.usage }
        check(cumulative == listOf(
            AssistantUsage(10, 4, 14, 2, 3), AssistantUsage(12, 6, 18, 5, 4),
            AssistantUsage(12, 6, 18, 5, 4), AssistantUsage(19, 8, 27, 6, 5),
            AssistantUsage(19, 9, 28, 6, 5), AssistantUsage(22, 13, 35, 6, 7),
        )) { "unexpected cumulative usage: $cumulative" }
        check(actual.connections.size == 3 && actual.snapshot().usage == AssistantUsage(22, 13, 35, 6, 7))
    }
    test("round without usage preserves known totals and later partial usage adds only supplied fields") {
        val actual = session(File(root, "usage-missing"), listOf(
            sessionResponse(listOf(SessionProposal("known-usage", "list_tasks")),
                usages = listOf(usage(10, 4, 14, 2, 3))),
            sessionResponse(listOf(SessionProposal("absent-usage", "list_tasks"))),
            sessionResponse(text = "partial usage", usages = listOf(JSONObject().put("prompt_tokens", 5))),
        ))
        check(actual.events.filterIsInstance<AssistantStreamEvent.UsageReported>().map { it.usage } ==
            listOf(AssistantUsage(10, 4, 14, 2, 3), AssistantUsage(15, 4, 14, 2, 3)))
        check(actual.snapshot().usage == AssistantUsage(15, 4, 14, 2, 3))
    }
    test("multi-round usage overflow saturates counters rather than wrapping negative") {
        val actual = session(File(root, "usage-overflow"), listOf(
            sessionResponse(listOf(SessionProposal("huge-usage", "list_tasks")),
                usages = listOf(usage(Long.MAX_VALUE - 1, Long.MAX_VALUE - 1, Long.MAX_VALUE - 1,
                    Long.MAX_VALUE - 1, Long.MAX_VALUE - 1))),
            sessionResponse(text = "bounded counters", usages = listOf(usage(4, 4, 4, 4, 4))),
        ))
        check(actual.snapshot().usage == AssistantUsage(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
            Long.MAX_VALUE, Long.MAX_VALUE))
    }
    println("Official tool session checks: $passed PASS, ${failures.size} FAIL (real session/dispatcher/output store; offline model transport)")
    check(failures.isEmpty()) { "Failed checks: ${failures.joinToString()}" }
}
