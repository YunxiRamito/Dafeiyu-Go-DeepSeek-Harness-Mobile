package com.dafeiyu.mobile.data

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private class ToolMemoryStore : BackgroundTaskStore {
    var records = emptyList<BackgroundTask>()
    var fail = false
    override suspend fun load() = records
    override suspend fun save(tasks: List<BackgroundTask>) {
        if (fail) error("PRIVATE_STORAGE_DETAILS")
        records = BackgroundTaskJson.decode(BackgroundTaskJson.encode(tasks))
    }
}

private class ToolPausedDispatcher : CoroutineDispatcher() {
    val pending = ConcurrentLinkedQueue<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) { pending.add(block) }
    fun drain() {
        var limit = 10_000
        while (true) {
            val next = pending.poll() ?: break
            check(limit-- > 0)
            next.run()
        }
    }
}

private fun call(name: String, arguments: JSONObject = JSONObject(), id: String = UUID.randomUUID().toString()) =
    OfficialToolCall(id, name, arguments.toString())
private fun taskArgs(run: Boolean? = null) =
    JSONObject().put("title", "Independent task").put("prompt", "Compute a plain public answer").apply {
        if (run != null) put("run", run)
    }
private fun OfficialToolResult.json() = JSONObject(content)
private fun OfficialToolResult.ok(): JSONObject {
    check(json().getBoolean("ok")) { content }
    return json().getJSONObject("data")
}
private fun OfficialToolResult.failed(code: String? = null) {
    check(!json().getBoolean("ok"))
    if (code != null) check(json().getString("error") == code) { content }
}
private suspend fun rejected(action: suspend () -> Unit) {
    check(runCatching { action() }.isFailure)
}
private suspend fun waitFor(condition: () -> Boolean) = withTimeout(5_000) {
    while (!condition()) delay(5)
}

fun main(args: Array<String>) = runBlocking {
    val fixtureRoot = Path.of(args.single())
    Files.createDirectories(fixtureRoot)
    var passed = 0
    var rootNumber = 0
    fun outputs(): OfficialToolOutputStore {
        val root = fixtureRoot.resolve("run-${UUID.randomUUID()}-${rootNumber++}")
        Files.createDirectories(root)
        return OfficialToolOutputStore(root.toFile()) { 100L }
    }
    fun dispatcher(engine: BackgroundTaskEngine?, output: OfficialToolOutputStore = outputs(),
        conversation: String = "conversation-one", model: () -> String? = { "real-model" }) =
        OfficialToolDispatcher({ engine }, output, conversation, model, Dispatchers.Unconfined)
    suspend fun test(name: String, timeoutMillis: Long = 12_000, block: suspend () -> Unit) {
        withTimeout(timeoutMillis) { block() }
        passed++
        println("PASS $name")
    }
    test("five bounded function schemas without beta strict or required tool choice") {
        val schemas = OfficialToolContract.schemas()
        check(schemas.length() == 5)
        for (index in 0 until schemas.length()) {
            val tool = schemas.getJSONObject(index)
            check(tool.getString("type") == "function")
            val function = tool.getJSONObject("function")
            check(function.getString("name") in OfficialToolContract.names && !function.has("strict"))
            check(!function.getJSONObject("parameters").getBoolean("additionalProperties"))
        }
        schemas.remove(0)
        check(OfficialToolContract.schemas().length() == 5)
    }
    test("unconfigured subagent creates a persisted draft without any execution") {
        val store = ToolMemoryStore()
        BackgroundTaskEngine.open(store).use { engine ->
            val result = dispatcher(engine).execute(call("create_subagent", taskArgs()))
            check(result.ok().getString("status") == "DRAFT")
            val task = store.records.single()
            check(task.kind == BackgroundTaskKind.SUBAGENT && task.parentConversationId == "conversation-one")
            check(task.status == BackgroundTaskStatus.DRAFT)
        }
    }
    test("explicit run requires configured model and unavailable run creates no false task") {
        BackgroundTaskEngine.open(ToolMemoryStore()).use { engine ->
            dispatcher(engine).execute(call("create_background_task", taskArgs(true))).failed("model_unavailable")
            check(engine.tasks.value.isEmpty())
        }
    }
    test("explicit subagent executes independent injected runner with bounded two slots") {
        val execution = ToolPausedDispatcher()
        val requests = mutableListOf<BackgroundTaskRequest>()
        BackgroundTaskEngine.open(ToolMemoryStore(), BackgroundTaskRunner { request, _ ->
            requests += request
            awaitCancellation()
        }, { BackgroundTaskAvailability(true) }, execution).use { engine ->
            val tools = dispatcher(engine)
            repeat(3) { tools.execute(call("create_subagent", taskArgs(true))).ok() }
            execution.drain()
            check(requests.size == 2)
            check(requests.all { it.kind == BackgroundTaskKind.SUBAGENT &&
                it.parentConversationId == "conversation-one" && it.prompt == "Compute a plain public answer" })
            check(engine.tasks.value.count { it.status == BackgroundTaskStatus.QUEUED } == 1)
        }
        execution.drain()
    }
    test("same call ID replays one result and changed payload is rejected") {
        BackgroundTaskEngine.open(ToolMemoryStore()).use { engine ->
            val tools = dispatcher(engine)
            val request = call("create_subagent", taskArgs(), "stable-call")
            val first = tools.execute(request)
            check(tools.execute(request) == first && engine.tasks.value.size == 1)
            tools.execute(request.copy(arguments = taskArgs().put("title", "Changed").toString())).failed("call_id_reused")
            check(engine.tasks.value.size == 1)
        }
    }
    test("concurrent same call ID cannot create duplicates") {
        BackgroundTaskEngine.open(ToolMemoryStore()).use { engine ->
            val tools = dispatcher(engine)
            val request = call("create_background_task", taskArgs(), "parallel-call")
            val results = ConcurrentLinkedQueue<OfficialToolResult>()
            val jobs = List(12) { launch(Dispatchers.Default) { results.add(tools.execute(request)) } }
            jobs.forEach { it.join() }
            check(engine.tasks.value.size == 1 && results.toSet().size == 1)
        }
    }
    test("session creation limit is four and persisted engine cap is fifty") {
        BackgroundTaskEngine.open(ToolMemoryStore()).use { engine ->
            val tools = dispatcher(engine)
            repeat(4) { tools.execute(call("create_subagent", taskArgs())).ok() }
            tools.execute(call("create_subagent", taskArgs())).failed("task_creation_limit")
            repeat(46) { engine.create("Task $it", "real-model", "Input") }
            dispatcher(engine).execute(call("create_subagent", taskArgs())).failed("task_limit")
            check(engine.tasks.value.size == 50)
        }
    }
    test("batch and per turn total calls are bounded") {
        val tools = dispatcher(null)
        rejected { tools.executeBatch(List(5) { call("list_tasks") }) }
        val repeated = call("list_tasks")
        rejected { tools.executeBatch(listOf(repeated, repeated)) }
        repeat(16) { tools.execute(call("list_tasks")).failed("task_engine_unavailable") }
        tools.execute(call("list_tasks")).failed("tool_call_limit")
    }
    test("malformed, nested, coercive and duplicate JSON is rejected") {
        val tools = dispatcher(null)
        val malformed = listOf(
            "[]", "null", "{}", // {} is structurally valid but missing required file fields.
            "{\"filename\":\"a.txt\",\"text\":\"x\"}{}",
            "{'filename':'a.txt','text':'x'}", "{filename:\"a.txt\",text:\"x\"}",
            "{\"filename\":\"a.txt\",\"text\":\"x\",}",
            "{\"filename\":\"a.txt\",\"text\":\"x\",\"text\":\"y\"}",
            "{\"filename\":\"a.txt\",\"text\":\"x\",\"te\\u0078t\":\"y\"}",
            "{\"filename\":\"a.txt\",\"text\":{\"value\":\"x\"}}",
            "{\"filename\":\"a.txt\",\"text\":\"x\",\"extra\":\"y\"}",
            "{\"filename\":\"a.txt\",\"text\":true}",
            "{\"filename\":\"a.txt\",\"text\":\"x\", \"extra\": " + "[".repeat(2000) + "]".repeat(2000) + "}",
        )
        for (arguments in malformed) {
            tools.execute(OfficialToolCall(UUID.randomUUID().toString(), "write_text_file", arguments)).failed("invalid_arguments")
        }
    }
    test("strict run boolean and integer list bounds reject type coercion") {
        BackgroundTaskEngine.open(ToolMemoryStore()).use { engine ->
            val tools = dispatcher(engine)
            tools.execute(call("create_subagent", taskArgs().put("run", "true"))).failed("invalid_arguments")
            tools.execute(call("create_subagent", taskArgs().put("model", JSONObject.NULL))).failed("invalid_arguments")
            for (limit in listOf<Any>(0, 51, "1", true, JSONObject.NULL)) {
                tools.execute(call("list_tasks", JSONObject().put("limit", limit))).failed("invalid_arguments")
            }
            tools.execute(OfficialToolCall("fractional-limit", "list_tasks", "{\"limit\":1.0}")).failed("invalid_arguments")
            check(engine.tasks.value.isEmpty())
        }
    }
    test("unknown tools and invalid IDs do not invoke task or file side effects") {
        val tools = dispatcher(null)
        tools.execute(OfficialToolCall("x".repeat(129), "write_text_file", "{}")).failed("invalid_call_id")
        tools.execute(call("shell", JSONObject().put("command", "anything"))).failed("unsupported_tool")
        tools.execute(call("network_search")).failed("unsupported_tool")
    }
    test("task list and get enforce conversation association and exclude prompts") {
        BackgroundTaskEngine.open(ToolMemoryStore()).use { engine ->
            val own = engine.create("Owned", "real-model", "PRIVATE_PROMPT", BackgroundTaskKind.SUBAGENT, "conversation-one")
            val other = engine.create("Other", "real-model", "PRIVATE_OTHER_PROMPT", BackgroundTaskKind.SUBAGENT, "conversation-two")
            val tools = dispatcher(engine)
            val list = tools.execute(call("list_tasks"))
            check(list.ok().getJSONArray("tasks").length() == 1 && !list.content.contains("PRIVATE_PROMPT"))
            tools.execute(call("get_task", JSONObject().put("task_id", own.id))).ok()
            tools.execute(call("get_task", JSONObject().put("task_id", other.id))).failed("task_not_found")
        }
    }
    test("get task exposes completed public result and explicitly flags truncation without rerun") {
        val calls = AtomicInteger()
        BackgroundTaskEngine.open(ToolMemoryStore(), BackgroundTaskRunner { _, _ ->
            calls.incrementAndGet(); BackgroundTaskResult("answer ".repeat(5000))
        }, { BackgroundTaskAvailability(true) }).use { engine ->
            val task = engine.create("Result", "real-model", "Input", parentId = "conversation-one")
            engine.enqueue(task.id)
            waitFor { engine.tasks.value.single().status == BackgroundTaskStatus.SUCCEEDED }
            val result = dispatcher(engine).execute(call("get_task", JSONObject().put("task_id", task.id))).ok()
            check(result.getBoolean("result_truncated"))
            check(result.getString("result").length == OfficialToolContract.MAX_RESULT_CHARACTERS && calls.get() == 1)
        }
    }
    test("real UTF8 text file survives store reopen and copies exact bytes to export stream") {
        val root = fixtureRoot.resolve("reopen-${UUID.randomUUID()}")
        Files.createDirectories(root)
        val output = OfficialToolOutputStore(root.toFile())
        val text = "Actual UTF-8 \u6d4b\u8bd5\nSecond line\tend"
        val request = call("write_text_file", JSONObject().put("filename", "\u62a5\u544a.txt").put("text", text))
        val tools = dispatcher(null, output)
        val result = tools.execute(request)
        val record = result.outputs.single()
        check(result.ok().getString("output_id") == record.id)
        check(Files.readString(output.resolveFile(record.id).toPath()) == text)
        check(tools.execute(request) == result && output.list().size == 1)
        val reopened = OfficialToolOutputStore(root.toFile())
        check(reopened.get(record.id) == record)
        val destination = ByteArrayOutputStream()
        reopened.copyTo(record.id, destination)
        check(destination.toByteArray().contentEquals(text.toByteArray(Charsets.UTF_8)))
        check(!result.content.contains(root.toString()) && !result.content.contains("file://"))
    }
    test("arbitrary filenames, credentials and malformed unicode cannot reach output storage") {
        val output = outputs()
        val tools = dispatcher(null, output)
        for (filename in listOf("../secret.txt", "C:\\secret.txt", "/secret.txt", "a/b.txt",
            ".hidden.txt", "a..txt", "script.sh", "valid.txt\n", "a:stream.txt")) {
            tools.execute(call("write_text_file", JSONObject().put("filename", filename).put("text", "plain"))).failed()
        }
        for (text in listOf("sk-examplecredential123", "Bearer examplecredential123", "\uD800", "\u0000")) {
            tools.execute(call("write_text_file", JSONObject().put("filename", "safe.txt").put("text", text))).failed()
        }
        check(output.list().isEmpty())
    }
    test("task credentials and oversized prompts are refused without persisted tasks") {
        BackgroundTaskEngine.open(ToolMemoryStore()).use { engine ->
            val tools = dispatcher(engine)
            for (prompt in listOf("api_key=examplecredential123", "x".repeat(32_769))) {
                tools.execute(call("create_subagent", taskArgs().put("prompt", prompt))).failed("invalid_arguments")
            }
            check(engine.tasks.value.isEmpty())
        }
    }
    test("arguments and file text limits reject without truncation") {
        val tools = dispatcher(null)
        tools.execute(call("write_text_file", JSONObject().put("filename", "large.txt")
            .put("text", "x".repeat(65_537)))).failed("invalid_arguments")
        tools.execute(OfficialToolCall("oversized", "write_text_file", " ".repeat(OfficialToolContract.MAX_ARGUMENT_BYTES + 1)))
            .failed("invalid_arguments")
    }
    test("four files per turn and one hundred globally remain bounded", timeoutMillis = 30_000) {
        val root = fixtureRoot.resolve("quota-${UUID.randomUUID()}")
        Files.createDirectories(root)
        val output = OfficialToolOutputStore(root.toFile())
        val tools = dispatcher(null, output)
        repeat(4) { tools.execute(call("write_text_file", JSONObject().put("filename", "same.txt").put("text", "real $it"))).ok() }
        tools.execute(call("write_text_file", JSONObject().put("filename", "next.txt").put("text", "real"))).failed("file_creation_limit")
        // Populate valid existing records to test the quota without repeating synchronous disk commits.
        repeat(96) {
            val id = UUID.randomUUID().toString()
            val directory = Files.createDirectory(root.resolve("official-tool-outputs").resolve(id))
            Files.writeString(directory.resolve("content.txt"), "")
            Files.writeString(directory.resolve("metadata.json"), JSONObject().put("version", 1)
                .put("id", id).put("filename", "file.txt").put("size_bytes", 0)
                .put("created_at", 100).put("conversation_id", "conversation-one").toString())
        }
        rejected { output.write("overflow.txt", "", "conversation-one") }
        check(OfficialToolOutputStore(root.toFile()).list().size == 100)
    }
    test("missing model allows manual configured ID drafts and never invents a model") {
        BackgroundTaskEngine.open(ToolMemoryStore()).use { engine ->
            val tools = dispatcher(engine, model = { null })
            tools.execute(call("create_background_task", taskArgs())).failed("model_required")
            tools.execute(call("create_background_task", taskArgs().put("model", "explicit-id"))).ok()
            check(engine.tasks.value.single().model == "explicit-id")
        }
    }
    test("storage errors are sanitized and remembered rather than retried") {
        val store = ToolMemoryStore()
        BackgroundTaskEngine.open(store).use { engine ->
            val tools = dispatcher(engine)
            val request = call("create_subagent", taskArgs())
            store.fail = true
            val first = tools.execute(request)
            first.failed("operation_failed")
            check(!first.content.contains("PRIVATE_STORAGE_DETAILS"))
            store.fail = false
            check(tools.execute(request) == first && engine.tasks.value.isEmpty())
        }
    }
    test("output storage rejects symlinks or unexpected directory escapes where platform supports links") {
        val root = fixtureRoot.resolve("symlinks-${UUID.randomUUID()}")
        val outside = fixtureRoot.resolve("outside-${UUID.randomUUID()}")
        Files.createDirectories(root)
        Files.createDirectories(outside)
        val store = OfficialToolOutputStore(root.toFile())
        val managed = root.resolve("official-tool-outputs")
        val linked = runCatching { Files.createSymbolicLink(managed, outside); true }.getOrDefault(false)
        if (linked) {
            rejected { store.write("test.txt", "real", "conversation-one") }
            check(Files.list(outside).use { it.count() } == 0L)
        } else {
            Files.createDirectories(managed)
            Files.writeString(managed.resolve("unexpected.txt"), "existing")
            rejected { store.write("test.txt", "real", "conversation-one") }
            check(Files.readString(managed.resolve("unexpected.txt")) == "existing")
        }
    }
    test("caller cancellation cannot duplicate a committed task when replaying call ID") {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var records = emptyList<BackgroundTask>()
        val store = object : BackgroundTaskStore {
            override suspend fun load() = records
            override suspend fun save(tasks: List<BackgroundTask>) {
                if (tasks.isNotEmpty()) { entered.complete(Unit); release.await() }
                records = tasks
            }
        }
        BackgroundTaskEngine.open(store).use { engine ->
            val tools = dispatcher(engine)
            val request = call("create_background_task", taskArgs())
            val operation = launch { tools.execute(request) }
            entered.await()
            operation.cancel()
            release.complete(Unit)
            operation.join()
            tools.execute(request).ok()
            check(records.size == 1 && engine.tasks.value.size == 1)
        }
    }
    test("weather is advertised only with installed provider and city plus country are bounded") {
        val requested = ConcurrentLinkedQueue<Pair<String, String?>>()
        val provider = OfficialToolWeatherProvider { city, country ->
            requested.add(city to country)
            JSONObject().put("city", city).put("country", country).put("temperature_c", 18)
        }
        val tools = OfficialToolDispatcher({ null }, outputs(), "conversation-one",
            { "real-model" }, Dispatchers.Unconfined, provider)
        check(tools.schemas().length() == 6 && dispatcher(null).schemas().length() == 5)
        val request = call("get_weather", JSONObject().put("city", "\u6cf0\u5b89").put("country", "cn"))
        val result = tools.execute(request)
        check(result.ok().getInt("temperature_c") == 18)
        check(requested.single() == ("\u6cf0\u5b89" to "CN"))
        check(tools.execute(request) == result && requested.size == 1)
        for (args in listOf(JSONObject().put("city", "https://example.com"),
            JSONObject().put("city", "Town").put("country", "China"),
            JSONObject().put("city", "Town").put("url", "https://example.com"))) {
            tools.execute(call("get_weather", args)).failed("invalid_arguments")
        }
        check(requested.size == 1)
        dispatcher(null).execute(call("get_weather", JSONObject().put("city", "Town"))).failed("weather_unavailable")
    }
    test("weather errors never expose provider secrets or oversize data") {
        for (provider in listOf(
            OfficialToolWeatherProvider { _, _ -> error("PRIVATE_PROVIDER_DETAILS") },
            OfficialToolWeatherProvider { _, _ -> JSONObject().put("api_key", "examplecredential123") },
            OfficialToolWeatherProvider { _, _ -> JSONObject().put("data",
                JSONObject().put("authorization", "examplecredential123")) },
            OfficialToolWeatherProvider { _, _ -> JSONObject().put("text", "Bearer examplecredential123") },
            OfficialToolWeatherProvider { _, _ -> JSONObject().put("text", "x".repeat(32_769)) },
        )) {
            val tools = OfficialToolDispatcher({ null }, outputs(), "conversation-one",
                { null }, Dispatchers.Unconfined, provider)
            val result = tools.execute(call("get_weather", JSONObject().put("city", "Town")))
            result.failed()
            check(!result.content.contains("PRIVATE_PROVIDER_DETAILS") && !result.content.contains("examplecredential123"))
        }
    }
    test("weather cancellation propagates to suspend provider without running local side effects") {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val output = outputs()
        val tools = OfficialToolDispatcher({ null }, output, "conversation-one",
            { null }, Dispatchers.Unconfined, OfficialToolWeatherProvider { _, _ ->
                entered.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            })
        val operation = launch { tools.execute(call("get_weather", JSONObject().put("city", "Town"))) }
        entered.await()
        operation.cancel()
        operation.join()
        cancelled.await()
        check(output.list().isEmpty())
    }
    test("weather location selection forwards bounded IDs only to a selection provider") {
        val selected = mutableListOf<Long>()
        val provider = object : OfficialToolWeatherSelectionProvider {
            override suspend fun weather(city: String, countryCode: String?) =
                JSONObject().put("status", "choices")
            override suspend fun weather(city: String, countryCode: String?, locationId: Long): JSONObject {
                check(city == "Town" && countryCode == "CN")
                selected += locationId
                return JSONObject().put("status", "weather").put("location_id", locationId)
            }
        }
        val tools = OfficialToolDispatcher({ null }, outputs(), "conversation-one",
            { null }, Dispatchers.Unconfined, provider)
        check(tools.schemas().getJSONObject(5).getJSONObject("function").getJSONObject("parameters")
            .getJSONObject("properties").has("location_id"))
        tools.execute(call("get_weather", JSONObject().put("city", "Town").put("country", "cn")
            .put("location_id", 123L))).ok()
        for (id in listOf<Any>(0, -1, "123", true, JSONObject.NULL)) {
            tools.execute(call("get_weather", JSONObject().put("city", "Town")
                .put("location_id", id))).failed("invalid_arguments")
        }
        tools.execute(OfficialToolCall("fractional-weather", "get_weather",
            "{\"city\":\"Town\",\"location_id\":1.0}")).failed("invalid_arguments")
        check(selected == listOf(123L))
        val legacy = OfficialToolDispatcher({ null }, outputs(), "conversation-one",
            { null }, Dispatchers.Unconfined, OfficialToolWeatherProvider { _, _ -> JSONObject() })
        legacy.execute(call("get_weather", JSONObject().put("city", "Town")
            .put("location_id", 123L))).failed("weather_selection_unavailable")
    }
    println("PASS $passed official tool checks (managed files and injected computation; no paid calls)")
}
