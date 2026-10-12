package com.dafeiyu.mobile.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

private class AwaitAuditStore : BackgroundTaskStore {
    private var records = emptyList<BackgroundTask>()
    override suspend fun load() = records
    override suspend fun save(tasks: List<BackgroundTask>) { records = tasks.toList() }
}

private class AwaitAuditConnection(url: URL, response: String) : HttpsURLConnection(url) {
    val sent = ByteArrayOutputStream()
    private val received = ByteArrayInputStream(response.toByteArray(Charsets.UTF_8))
    override fun connect() {}
    override fun disconnect() {}
    override fun usingProxy() = false
    override fun getResponseCode() = 200
    override fun getContentType() = "text/event-stream"
    override fun getInputStream() = received
    override fun getOutputStream() = sent
    override fun getCipherSuite() = "offline-await-audit"
    override fun getLocalCertificates(): Array<Certificate>? = null
    override fun getServerCertificates(): Array<Certificate> = emptyArray()
    override fun getPeerPrincipal(): Principal? = null
    override fun getLocalPrincipal(): Principal? = null
}

private fun awaitAuditCall(id: String, run: Boolean = true) = OfficialToolCall(id, "create_subagent",
    JSONObject().put("title", id).put("prompt", id).put("model", "offline")
        .put("run", run).toString())

private fun awaitAuditResponse(calls: List<OfficialToolCall>): String {
    val delta = JSONObject().put("role", "assistant")
    if (calls.isEmpty()) delta.put("content", "Parent answer")
    else delta.put("tool_calls", JSONArray(calls.mapIndexed { index, call ->
        JSONObject().put("index", index).put("id", call.id).put("type", "function")
            .put("function", JSONObject().put("name", call.name).put("arguments", call.arguments))
    }))
    val chunk = JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0)
        .put("delta", delta).put("finish_reason", if (calls.isEmpty()) "stop" else "tool_calls")))
    return "data: $chunk\n\ndata: [DONE]\n\n"
}

private val awaitAuditReady: (String) -> BackgroundTaskAvailability = { BackgroundTaskAvailability(true) }

private fun awaitAuditDispatcher(root: File, provider: () -> BackgroundTaskEngine?, waitMillis: Long = 250) =
    OfficialToolDispatcher(provider, OfficialToolOutputStore(root), "audit-parent", { "offline" },
        Dispatchers.Unconfined, subagentWaitMillis = waitMillis)

private data class AwaitAuditRun(val events: List<AssistantStreamEvent>, val nextHistory: JSONArray) {
    fun data(index: Int): JSONObject =
        JSONObject(nextHistory.getJSONObject(index + 2).getString("content")).getJSONObject("data")
}

private suspend fun awaitAuditSession(
    dispatcher: OfficialToolDispatcher,
    calls: List<OfficialToolCall>,
    admitted: suspend (OfficialToolResult) -> Unit = {},
    event: suspend (AssistantStreamEvent) -> Unit = {},
    nextRequest: () -> Unit = {},
): AwaitAuditRun {
    val connections = mutableListOf<AwaitAuditConnection>()
    val responses = listOf(awaitAuditResponse(calls), awaitAuditResponse(emptyList()))
    val client = DeepSeekChatClient({ "offline-fixture-key" }, DeepSeekChatClient.Config(), { url ->
        check(connections.size < 2) { "unexpected parent request" }
        if (connections.size == 1) nextRequest()
        AwaitAuditConnection(url, responses[connections.size]).also { connections += it }
    })
    val events = mutableListOf<AssistantStreamEvent>()
    OfficialToolSession.generate(client, DeepSeekChatClient.Request("offline",
        listOf(DeepSeekChatClient.HistoryMessage("user", "explicit children")), sequenceStart = 51),
        dispatcher, onOutput = admitted, onEvent = { events += it; event(it) })
    check(connections.size == 2)
    events.forEachIndexed { index, value -> check(value.sequenceNumber == 51L + index) }
    return AwaitAuditRun(events, JSONObject(connections[1].sent.toString("UTF-8")).getJSONArray("messages"))
}

private fun unavailable(result: OfficialToolResult) {
    val response = JSONObject(result.content)
    check(!response.getBoolean("ok") && response.getString("error") == "subagent_result_unavailable")
    check(!response.has("data") && !result.content.contains("foreign-private-result"))
}

// Fault injection exercises ownership changes and recovery snapshots that public APIs prohibit.
@Suppress("UNCHECKED_CAST")
private fun replaceAuditSnapshot(engine: BackgroundTaskEngine, transform: (BackgroundTask) -> BackgroundTask) {
    val field = BackgroundTaskEngine::class.java.getDeclaredField("values").apply { isAccessible = true }
    val state = field.get(engine) as MutableStateFlow<List<BackgroundTask>>
    state.value = state.value.map(transform)
}

fun main(args: Array<String>) = runBlocking {
    val root = File(args.single()).apply { check(mkdirs() || isDirectory) }
    var passed = 0
    suspend fun test(name: String, action: suspend () -> Unit) {
        withTimeout(12_000) { action() }
        passed++
        println("PASS $name")
    }
    test("four sibling waits share one window and queued timeout retains its real state") {
        val release = CompletableDeferred<Unit>()
        val runs = AtomicInteger()
        BackgroundTaskEngine.open(AwaitAuditStore(), BackgroundTaskRunner { _, _ ->
            runs.incrementAndGet()
            release.await()
            BackgroundTaskResult("eventual answer")
        }, awaitAuditReady).use { engine ->
            try {
                var admittedAt = 0L
                var elapsedMillis = 0L
                val calls = (0..3).map { awaitAuditCall("batch-$it") }
                val actual = awaitAuditSession(awaitAuditDispatcher(File(root, "batch"), { engine }, 500),
                    calls, admitted = { if (it.callId == "batch-3") admittedAt = System.nanoTime() },
                    nextRequest = { elapsedMillis = (System.nanoTime() - admittedAt) / 1_000_000 })
                // Four sequential waits take >= 2000 ms. Leave scheduler headroom above one 500 ms wait.
                check(elapsedMillis in 450..1500) { "batch waited $elapsedMillis ms" }
                println("EVIDENCE four 500ms waits: batch elapsed=${elapsedMillis}ms")
                check(engine.tasks.value.size == 4 && runs.get() == 2)
                check((0..3).all { actual.data(it).getBoolean("wait_timed_out") })
                check(actual.data(2).getString("status") == "QUEUED" &&
                    actual.data(3).getString("status") == "QUEUED")
                val queued = actual.events.filterIsInstance<AssistantStreamEvent.SubagentStatusChanged>()
                    .filter { it.detail?.startsWith("QUEUED:") == true || it.detail?.startsWith("QUEUED：") == true }
                check(queued.size == 2 && queued.all {
                    it.status == AssistantStreamEvent.SubagentStatus.INCOMPLETE
                })
                val finalResults = actual.events.filterIsInstance<AssistantStreamEvent.ToolResult>()
                    .filter { JSONObject(it.displayContent).optJSONObject("data")?.has("wait_timed_out") == true }
                check(finalResults.map { it.callId } == calls.map { it.id })
                check((0..3).map { actual.nextHistory.getJSONObject(it + 2).getString("tool_call_id") } ==
                    calls.map { it.id })
            } finally { release.complete(Unit) }
        }
    }
    test("out of order sibling completion still emits and encodes results serially in call order") {
        coroutineScope {
            val entered = List(2) { CompletableDeferred<Unit>() }
            val release = List(2) { CompletableDeferred<Unit>() }
            BackgroundTaskEngine.open(AwaitAuditStore(), BackgroundTaskRunner { request, _ ->
                val index = request.prompt.removePrefix("order-").toInt()
                entered[index].complete(Unit)
                release[index].await()
                BackgroundTaskResult("answer-$index")
            }, awaitAuditReady).use { engine ->
                val parent = async {
                    awaitAuditSession(awaitAuditDispatcher(File(root, "order"), { engine }, 5000),
                        (0..1).map { awaitAuditCall("order-$it") })
                }
                try {
                    entered.forEach { it.await() }
                    release[1].complete(Unit)
                    engine.tasks.first { it.any { task -> task.title == "order-1" &&
                        task.status == BackgroundTaskStatus.SUCCEEDED } }
                    check(!parent.isCompleted)
                    release[0].complete(Unit)
                    val actual = parent.await()
                    check(actual.data(0).getString("result") == "answer-0" &&
                        actual.data(1).getString("result") == "answer-1")
                    check(actual.events.filterIsInstance<AssistantStreamEvent.SubagentCompleted>()
                        .map { it.agentId } == engine.tasks.value.map { it.id })
                } finally { release.forEach { it.complete(Unit) }; parent.cancelAndJoin() }
            }
        }
    }
    test("STOPPING timeout preserves cleanup state and never claims running or completion") {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        BackgroundTaskEngine.open(AwaitAuditStore(), BackgroundTaskRunner { _, _ ->
            entered.complete(Unit)
            try { release.await(); BackgroundTaskResult("unpublished") }
            finally { withContext(NonCancellable) { cleanup.await() } }
        }, awaitAuditReady).use { engine ->
            try {
                val actual = awaitAuditSession(awaitAuditDispatcher(File(root, "stopping"), { engine }),
                    listOf(awaitAuditCall("stopping")), admitted = {
                        entered.await()
                        engine.stop(JSONObject(it.content).getJSONObject("data").getString("task_id"))
                    })
                check(actual.data(0).getString("status") == "STOPPING" &&
                    actual.data(0).getBoolean("wait_timed_out") && actual.data(0).getString("result").isEmpty())
                val update = actual.events.filterIsInstance<AssistantStreamEvent.SubagentStatusChanged>().last()
                check(update.status == AssistantStreamEvent.SubagentStatus.INCOMPLETE &&
                    update.detail?.startsWith("STOPPING") == true)
                check(actual.events.none { it is AssistantStreamEvent.SubagentCompleted })
                val reducer = AssistantStreamReducer(startSequenceNumber = 51)
                actual.events.forEach { reducer.apply(it) }
                check(reducer.finish().subagents.single().detail?.startsWith("STOPPING") == true)
            } finally { cleanup.complete(Unit); release.complete(Unit) }
        }
    }
    test("DRAFT recovery snapshot at timeout stays a draft and unfinished wait") {
        val release = CompletableDeferred<Unit>()
        BackgroundTaskEngine.open(AwaitAuditStore(), BackgroundTaskRunner { _, _ ->
            release.await(); BackgroundTaskResult("later")
        }, awaitAuditReady).use { engine ->
            try {
                val actual = awaitAuditSession(awaitAuditDispatcher(File(root, "draft-timeout"), { engine }),
                    listOf(awaitAuditCall("draft-timeout")), admitted = {
                        replaceAuditSnapshot(engine) { task -> task.copy(status = BackgroundTaskStatus.DRAFT) }
                    })
                check(actual.data(0).getString("status") == "DRAFT" && actual.data(0).getBoolean("wait_timed_out"))
                val update = actual.events.filterIsInstance<AssistantStreamEvent.SubagentStatusChanged>().last()
                check(update.status == AssistantStreamEvent.SubagentStatus.INCOMPLETE &&
                    update.detail?.startsWith("DRAFT") == true)
            } finally { release.complete(Unit) }
        }
    }
    test("creation followed by unavailable model emits start failure without claiming message sent") {
        val availabilityCalls = AtomicInteger()
        val runs = AtomicInteger()
        BackgroundTaskEngine.open(AwaitAuditStore(), BackgroundTaskRunner { _, _ ->
            runs.incrementAndGet(); BackgroundTaskResult("not started")
        }, { BackgroundTaskAvailability(availabilityCalls.incrementAndGet() == 1, "became unavailable") }).use { engine ->
            val actual = awaitAuditSession(awaitAuditDispatcher(File(root, "start-error"), { engine }, 5000),
                listOf(awaitAuditCall("start-error")))
            val data = actual.data(0)
            check(data.getString("status") == "DRAFT" && data.getString("start_error") == "task_not_started")
            check(runs.get() == 0 && engine.tasks.value.size == 1)
            check(actual.events.filterIsInstance<AssistantStreamEvent.SubagentFailed>().size == 1)
            check(actual.events.filterIsInstance<AssistantStreamEvent.SubagentStatusChanged>()
                .none { it.detail == "已发送消息" })
            check(actual.events.none { it is AssistantStreamEvent.SubagentCompleted })
        }
    }
    test("run false draft remains a normal successful creation without waiting or failed event") {
        BackgroundTaskEngine.open(AwaitAuditStore()).use { engine ->
            val actual = awaitAuditSession(awaitAuditDispatcher(File(root, "draft"), { engine }, 5000),
                listOf(awaitAuditCall("draft", false)))
            check(actual.data(0).getString("status") == "DRAFT" && !actual.data(0).getBoolean("run_requested"))
            check(actual.events.none { it is AssistantStreamEvent.SubagentFailed })
        }
    }
    test("forged call id content malformed JSON and foreign dispatcher results fail before waiting") {
        val release = CompletableDeferred<Unit>()
        BackgroundTaskEngine.open(AwaitAuditStore(), BackgroundTaskRunner { _, _ ->
            release.await(); BackgroundTaskResult("real")
        }, awaitAuditReady).use { engine ->
            try {
                val dispatcher = awaitAuditDispatcher(File(root, "forged"), { engine }, 5000)
                val initial = dispatcher.execute(awaitAuditCall("owned"))
                val foreign = awaitAuditDispatcher(File(root, "foreign-dispatcher"), { engine }, 5000)
                val content = JSONObject(initial.content).also {
                    it.getJSONObject("data").put("task_id", "00000000-0000-0000-0000-000000000001")
                }.toString()
                withTimeout(500) {
                    unavailable(dispatcher.awaitSubagent(initial.copy(callId = "unknown")))
                    unavailable(dispatcher.awaitSubagent(initial.copy(content = content)))
                    unavailable(dispatcher.awaitSubagent(initial.copy(content = "{")))
                    unavailable(foreign.awaitSubagent(initial))
                }
                check(engine.tasks.value.size == 1)
            } finally { release.complete(Unit) }
        }
    }
    test("wrong conversation or task kind is rejected before waiting even with matching ledger result") {
        for (wrongKind in listOf(false, true)) {
            val release = CompletableDeferred<Unit>()
            BackgroundTaskEngine.open(AwaitAuditStore(), BackgroundTaskRunner { _, _ ->
                release.await(); BackgroundTaskResult("foreign-private-result")
            }, awaitAuditReady).use { engine ->
                try {
                    val dispatcher = awaitAuditDispatcher(File(root, "before-$wrongKind"), { engine }, 5000)
                    val initial = dispatcher.execute(awaitAuditCall("before"))
                    replaceAuditSnapshot(engine) {
                        if (wrongKind) it.copy(kind = BackgroundTaskKind.BACKGROUND)
                        else it.copy(parentConversationId = "foreign-parent")
                    }
                    withTimeout(500) { unavailable(dispatcher.awaitSubagent(initial)) }
                } finally { release.complete(Unit) }
            }
        }
    }
    test("ownership change while waiting returns error envelope rather than a whole session exception") {
        coroutineScope {
            for (wrongKind in listOf(false, true)) {
                val release = CompletableDeferred<Unit>()
                BackgroundTaskEngine.open(AwaitAuditStore(), BackgroundTaskRunner { _, _ ->
                    release.await(); BackgroundTaskResult("foreign-private-result")
                }, awaitAuditReady).use { engine ->
                    try {
                        val dispatcher = awaitAuditDispatcher(File(root, "after-$wrongKind"), { engine }, 5000)
                        val initial = dispatcher.execute(awaitAuditCall("after"))
                        val waiter = async(start = CoroutineStart.UNDISPATCHED) { dispatcher.awaitSubagent(initial) }
                        check(!waiter.isCompleted)
                        replaceAuditSnapshot(engine) {
                            it.copy(status = BackgroundTaskStatus.SUCCEEDED, result = "foreign-private-result",
                                kind = if (wrongKind) BackgroundTaskKind.BACKGROUND else it.kind,
                                parentConversationId = if (wrongKind) it.parentConversationId else "foreign-parent")
                        }
                        unavailable(waiter.await())
                    } finally { release.complete(Unit) }
                }
            }
        }
    }
    test("deleted task during wait returns unavailable without crashing or restarting") {
        coroutineScope {
            val release = CompletableDeferred<Unit>()
            BackgroundTaskEngine.open(AwaitAuditStore(), BackgroundTaskRunner { _, _ ->
                release.await(); BackgroundTaskResult("deleted")
            }, awaitAuditReady).use { engine ->
                try {
                    val dispatcher = awaitAuditDispatcher(File(root, "delete"), { engine }, 5000)
                    val initial = dispatcher.execute(awaitAuditCall("delete"))
                    val waiter = async(start = CoroutineStart.UNDISPATCHED) { dispatcher.awaitSubagent(initial) }
                    engine.delete(engine.tasks.value.single().id)
                    unavailable(waiter.await())
                    check(engine.tasks.value.isEmpty())
                } finally { release.complete(Unit) }
            }
        }
    }
    test("missing or replaced selected engine is rejected before and after suspension") {
        coroutineScope {
            val release = CompletableDeferred<Unit>()
            BackgroundTaskEngine.open(AwaitAuditStore(), BackgroundTaskRunner { _, _ ->
                release.await(); BackgroundTaskResult("real")
            }, awaitAuditReady).use { engine ->
                BackgroundTaskEngine.open(AwaitAuditStore()).use { other ->
                    try {
                        var selected: BackgroundTaskEngine? = engine
                        val dispatcher = awaitAuditDispatcher(File(root, "engine-scope"), { selected }, 5000)
                        val initial = dispatcher.execute(awaitAuditCall("engine-scope"))
                        selected = null
                        withTimeout(500) { unavailable(dispatcher.awaitSubagent(initial)) }
                        selected = other
                        withTimeout(500) { unavailable(dispatcher.awaitSubagent(initial)) }
                        selected = engine
                        val waiter = async(start = CoroutineStart.UNDISPATCHED) { dispatcher.awaitSubagent(initial) }
                        selected = other
                        release.complete(Unit)
                        unavailable(waiter.await())
                    } finally { release.complete(Unit) }
                }
            }
        }
    }
    test("cancelled waiting releases no task and ledger replay still creates exactly once") {
        coroutineScope {
            val release = CompletableDeferred<Unit>()
            val runs = AtomicInteger()
            BackgroundTaskEngine.open(AwaitAuditStore(), BackgroundTaskRunner { _, _ ->
                runs.incrementAndGet(); release.await(); BackgroundTaskResult("real result")
            }, awaitAuditReady).use { engine ->
                try {
                    val dispatcher = awaitAuditDispatcher(File(root, "cancel-ledger"), { engine }, 5000)
                    val call = awaitAuditCall("cancel-ledger")
                    val initial = dispatcher.execute(call)
                    val waiter = async(start = CoroutineStart.UNDISPATCHED) { dispatcher.awaitSubagent(initial) }
                    withTimeout(500) {
                        val reading = dispatcher.execute(OfficialToolCall("read", "get_task",
                            JSONObject().put("task_id", engine.tasks.value.single().id).toString()))
                        check(JSONObject(reading.content).getBoolean("ok"))
                    }
                    waiter.cancelAndJoin()
                    check(waiter.isCancelled && dispatcher.execute(call) == initial)
                    check(engine.tasks.value.size == 1)
                    release.complete(Unit)
                    val terminal = dispatcher.awaitSubagent(initial)
                    check(JSONObject(terminal.content).getJSONObject("data").getString("result") == "real result")
                    check(runs.get() == 1)
                } finally { release.complete(Unit) }
            }
        }
    }
    println("PASS $passed focused official subagent await checks (injected transport/runners; zero network/model calls)")
}
