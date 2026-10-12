package com.dafeiyu.mobile.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

private var passed = 0
private fun test(name: String, block: () -> Unit) {
    block()
    println("PASS $name")
    passed++
}
private fun chunk(delta: String = "{}", finish: String = "null", usage: String = "null"): String =
    """{"id":"provider-id","choices":[{"index":0,"delta":$delta,"finish_reason":$finish}],"usage":$usage}"""
private fun sse(vararg chunks: String) = chunks.joinToString("") { "data: $it\r\n\r\n" }
private fun reader(text: String, line: Int = 4096, event: Int = 8192, total: Int = 65536) =
    DeepSeekSseReader(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), line, event, total)
private fun reduce(events: List<AssistantStreamEvent>): AssistantStreamSnapshot {
    val reducer = AssistantStreamReducer(source = AssistantStreamSource.BACKEND)
    events.forEachIndexed { index, event ->
        check(event.sequenceNumber == index.toLong())
        check(reducer.apply(event).disposition == AssistantStreamReducer.ApplyDisposition.APPLIED)
    }
    return reducer.finish()
}
private class FakeConnection(
    url: URL,
    private val body: InputStream,
    private val status: Int = 200,
    private val mime: String = "text/event-stream",
    private val onDisconnect: () -> Unit = {},
) : HttpsURLConnection(url) {
    val sent = ByteArrayOutputStream()
    @Volatile var disconnected = false
    var errorBodyRead = false
    override fun connect() {}
    override fun disconnect() { disconnected = true; onDisconnect() }
    override fun usingProxy() = false
    override fun getResponseCode() = status
    override fun getContentType() = mime
    override fun getInputStream() = body
    override fun getErrorStream(): InputStream {
        errorBodyRead = true
        return ByteArrayInputStream("SECRET_KEY echoed prompt".toByteArray())
    }
    override fun getOutputStream() = sent
    override fun getCipherSuite() = "fixture"
    override fun getLocalCertificates(): Array<Certificate>? = null
    override fun getServerCertificates(): Array<Certificate> = emptyArray()
    override fun getPeerPrincipal(): Principal? = null
    override fun getLocalPrincipal(): Principal? = null
}
private val request = DeepSeekChatClient.Request("model-from-list", listOf(
    DeepSeekChatClient.HistoryMessage("user", "hello"),
))
private fun client(
    body: String,
    status: Int = 200,
    mime: String = "text/event-stream",
    config: DeepSeekChatClient.Config = DeepSeekChatClient.Config(),
): Pair<DeepSeekChatClient, () -> FakeConnection> {
    lateinit var connection: FakeConnection
    val client = DeepSeekChatClient({ "SECRET_KEY" }, config, { url ->
        FakeConnection(url, ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)), status, mime).also { connection = it }
    })
    return client to { connection }
}
private fun generate(client: DeepSeekChatClient, req: DeepSeekChatClient.Request = request): List<AssistantStreamEvent> =
    runBlocking {
        val events = mutableListOf<AssistantStreamEvent>()
        client.generate(req) { events += it }
        events
    }

private fun historyAnswer(text: String = "answer"): AssistantStreamSnapshot {
    val protocol = DeepSeekChatProtocol("history-answer")
    return reduce(protocol.accept(JSONObject(chunk(JSONObject().put("role", "assistant").put("content", text)
        .put("reasoning_content", "PRIVATE_HISTORY_THINKING").toString(), finish = "\"stop\""))) + protocol.end())
}
private fun h(role: String, content: String) = DeepSeekChatClient.HistoryMessage(role, content)

fun main() {
    test("SSE BOM comments CRLF multiline data") {
        val r = reader("\uFEFF: keep-alive\r\n\r\nid: unused\r\ndata: {\"a\":\r\ndata: 1}\r\n\r\ndata: [DONE]\r\n\r\n")
        check(JSONObject(r.nextData()!!).getInt("a") == 1)
        check(r.nextData() == "[DONE]")
        check(r.nextData() == null)
    }
    test("SSE CR LF EOF and empty data") {
        val r = reader("data:\r\rdata: one\n\ndata: two")
        check(r.nextData() == "")
        check(r.nextData() == "one")
        check(r.nextData() == "two")
        check(r.nextData() == null)
    }
    test("UTF8 network fragments and malformed UTF8") {
        val fragmented = object : InputStream() {
            val bytes = "data: \u6d4b\u8bd5\n\n".toByteArray()
            var at = 0
            override fun read() = if (at == bytes.size) -1 else bytes[at++].toInt() and 255
        }
        check(DeepSeekSseReader(fragmented, 100, 200, 300).nextData() == "\u6d4b\u8bd5")
        check(runCatching {
            DeepSeekSseReader(ByteArrayInputStream(byteArrayOf(100, 97, 116, 97, 58, 32, -61, 10)), 100, 200, 300).nextData()
        }.isFailure)
    }
    test("bounded line event and total including comments") {
        check(runCatching { reader("data: abcdef\n\n", line = 8).nextData() }.isFailure)
        check(runCatching { reader("data: abc\ndata: def\n\n", event = 12).nextData() }.isFailure)
        check(runCatching { reader(":123456789\n\n:123456789\n\n", total = 12).nextData() }.isFailure)
    }
    test("keepalive comments reset per event but still count toward total") {
        val r = reader(":ping\n\n".repeat(20) + "data: ok\n\n", line = 16, event = 16, total = 1024)
        check(r.nextData() == "ok")
    }
    test("public thinking and text independent and usage before final") {
        val usage = """{"prompt_tokens":10,"completion_tokens":8,"total_tokens":18,"prompt_cache_hit_tokens":4,"completion_tokens_details":{"reasoning_tokens":5}}"""
        val (c, connection) = client(sse(chunk("""{"role":"assistant","content":"answer","reasoning_content":"public"}"""),
            chunk(finish = "\"stop\"", usage = usage), "[DONE]"))
        val events = generate(c)
        val snapshot = reduce(events)
        check(snapshot.source == AssistantStreamSource.BACKEND)
        check(snapshot.messages.single().status == AssistantMessageStatus.COMPLETED)
        check(snapshot.messages.single().blocks.size == 2)
        check(snapshot.usage == AssistantUsage(10, 8, 18, 4, 5))
        check(events.indexOfFirst { it is AssistantStreamEvent.UsageReported } <
            events.indexOfFirst { it is AssistantStreamEvent.MessageCompleted })
        check(connection().disconnected)
    }
    test("body request uses text thinking stream_options and no tools") {
        val (c, connection) = client(sse(chunk(finish = "\"stop\""), "[DONE]"))
        generate(c)
        val actual = JSONObject(connection().sent.toString("UTF-8"))
        check(actual.getString("model") == request.model)
        check(actual.getJSONObject("thinking").getString("type") == "enabled")
        check(actual.getJSONObject("stream_options").getBoolean("include_usage"))
        check(!actual.has("tools") && !actual.has("reasoning_effort"))
        check(!connection().instanceFollowRedirects)
        check(connection().url.toString() == "https://api.deepseek.com/chat/completions")
    }
    test("null thinking omits property in stream and full body requests") {
        for (streaming in listOf(true, false)) {
            val body = if (streaming) sse(chunk(finish = "\"stop\""), "[DONE]")
            else """{"choices":[{"index":0,"message":{"role":"assistant","content":"answer"},"finish_reason":"stop"}]}"""
            val (c, connection) = client(body, mime = if (streaming) "text/event-stream" else "application/json")
            val snapshot = reduce(generate(c, request.copy(thinking = null, streaming = streaming)))
            check(snapshot.messages.single().status == AssistantMessageStatus.COMPLETED)
            val actual = JSONObject(connection().sent.toString("UTF-8"))
            check(!actual.has("thinking") && !actual.has("reasoning_effort"))
            check(actual.getString("model") == request.model)
            check(actual.getBoolean("stream") == streaming)
            check(actual.has("stream_options") == streaming)
        }
    }
    test("thinking policy permits only confirmed current IDs and Flash aliases") {
        for (id in listOf("deepseek-flash", "deepseek-v4-pro", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp")) {
            check(DeepSeekThinkingPolicy.canToggle(id)) { id }
        }
        for (id in listOf("deepseek-chat", "deepseek-reasoner", "deepseek-coder", "future-model",
                "", "DEEPSEEK-FLASH", " deepseek-flash", "deepseek-flash ", "deepseek-flash-custom")) {
            check(!DeepSeekThinkingPolicy.canToggle(id)) { id }
        }
    }
    test("nonstream full JSON with leading keepalive blanks") {
        val body = "\n\n" + """{"choices":[{"index":0,"message":{"role":"assistant","content":"answer","reasoning_content":"public"},"finish_reason":"stop"}],"usage":{"prompt_tokens":2,"completion_tokens":3,"total_tokens":5}}"""
        val (c, connection) = client(body, mime = "application/json; charset=utf-8")
        val snapshot = reduce(generate(c, request.copy(streaming = false, thinking = false)))
        check(snapshot.messages.single().status == AssistantMessageStatus.COMPLETED)
        check(snapshot.usage?.totalTokens == 5L)
        val actual = JSONObject(connection().sent.toString("UTF-8"))
        check(!actual.has("stream_options"))
        check(actual.getJSONObject("thinking").getString("type") == "disabled")
    }
    test("all provider truncations remain incomplete") {
        for (finish in listOf("length", "content_filter", "insufficient_system_resource", "aborted", "future_reason")) {
            val (c, _) = client(sse(chunk("""{"content":"partial"}"""), chunk(finish = "\"$finish\""), "[DONE]"))
            val message = reduce(generate(c)).messages.single()
            check(message.status == AssistantMessageStatus.INCOMPLETE) { finish }
            check(message.incompleteReason == if (finish == "future_reason") "unknown_finish_reason" else finish)
        }
    }
    test("DONE or EOF without finish reason is incomplete") {
        for (tail in listOf("", "data: [DONE]\n\n")) {
            val (c, _) = client(sse(chunk("""{"content":"partial"}""")) + tail)
            check(reduce(generate(c)).messages.single().incompleteReason == "eof_without_finish_reason")
        }
    }
    test("finish reason is terminal even when clean EOF lacks DONE") {
        val (c, _) = client(sse(chunk(finish = "\"stop\"")))
        check(reduce(generate(c)).messages.single().status == AssistantMessageStatus.COMPLETED)
    }
    test("tool index fragments become READY with no execution") {
        val (c, _) = client(sse(
            chunk("""{"tool_calls":[{"index":1,"id":"b","type":"function","function":{"name":"search","arguments":"{"}},{"index":0,"id":"a","type":"function","function":{"name":"shell","arguments":""}}]}"""),
            chunk("""{"tool_calls":[{"index":0,"function":{"arguments":"pwd"}},{"index":1,"function":{"arguments":"}"}}]}"""),
            chunk(finish = "\"tool_calls\""), "[DONE]"))
        val snapshot = reduce(generate(c))
        check(snapshot.toolCalls.all { it.status == AssistantStreamEvent.ToolStatus.READY && it.result == null })
        check(snapshot.toolCalls.associate { it.callId to it.arguments } == mapOf("a" to "pwd", "b" to "{}"))
        check(snapshot.messages.single().incompleteReason == "tool_execution_not_connected")
    }
    test("truncated tool arguments incomplete") {
        val (c, _) = client(sse(chunk("""{"tool_calls":[{"index":0,"id":"a","type":"function","function":{"name":"shell","arguments":"{"}}]}"""),
            chunk(finish = "\"length\""), "[DONE]"))
        check(reduce(generate(c)).toolCalls.single().status == AssistantStreamEvent.ToolStatus.INCOMPLETE)
    }
    test("ignore encrypted redacted signature fields") {
        val (c, _) = client(sse(chunk("""{"encrypted":"SECRET","redacted_thinking":"SECRET","signature":"SECRET","content":"ok"}"""),
            chunk(finish = "\"stop\""), "[DONE]"))
        check(!generate(c).toString().contains("SECRET"))
    }
    test("HTTP safe errors and redirects no error body read") {
        for (status in listOf(400, 401, 402, 403, 422, 429, 500, 503, 302)) {
            val (c, connection) = client("SECRET_KEY", status)
            val events = generate(c)
            val error = events.single() as AssistantStreamEvent.MessageFailed
            check(error.errorCode == "HTTP_$status" && error.error.contains("$status"))
            check(!events.toString().contains("SECRET_KEY"))
            check(!connection().errorBodyRead && connection().disconnected)
        }
    }
    test("malformed chunks fail without sequence gaps") {
        for (bad in listOf("{bad", chunk("""{"content":"partial","reasoning_content":123}"""),
            chunk(usage = """{"total_tokens":-1}"""), """{"error":{"message":"SECRET_KEY"}}""")) {
            val (c, _) = client(sse(chunk("""{"content":"first"}"""), bad))
            val events = generate(c)
            check(!events.toString().contains("SECRET_KEY"))
            check(reduce(events).messages.single().status == AssistantMessageStatus.FAILED)
        }
    }
    test("response and request limits and wrong content type") {
        val (large, _) = client("data: " + "x".repeat(200), config = DeepSeekChatClient.Config(
            maxLineBytes = 30, maxEventBytes = 60, maxResponseBytes = 100,
        ))
        check(generate(large).last() is AssistantStreamEvent.MessageFailed)
        val (small, _) = client("", config = DeepSeekChatClient.Config(maxRequestBytes = 1))
        check((generate(small).single() as AssistantStreamEvent.MessageFailed).errorCode == "REQUEST_LIMIT")
        val (wrong, _) = client("{}", mime = "text/html")
        check((generate(wrong).single() as AssistantStreamEvent.MessageFailed).errorCode == "PROTOCOL")
    }
    test("missing key and storage exceptions sanitized") {
        for (provider in listOf<() -> String?>({ null }, { throw IOException("SECRET_KEY") })) {
            val c = DeepSeekChatClient(provider, DeepSeekChatClient.Config(), { error("Must not connect") })
            val events = generate(c)
            check(events.single() is AssistantStreamEvent.MessageFailed)
            check(!events.toString().contains("SECRET_KEY"))
        }
    }
    test("models real IDs metadata and GET") {
        val json = """{"object":"list","data":[{"id":"server-real-id","name":"Server name","owned_by":"deepseek","context_window":1000,"max_output_tokens":200,"input_modalities":["text"],"output_modalities":["text"],"effort":{"supported_levels":["high"],"default_level":"high"}}]}"""
        val (c, connection) = client(json, mime = "application/json")
        val model = runBlocking { c.listModels() }.single()
        check(model.id == "server-real-id" && model.contextWindow == 1000L && model.defaultEffortLevel == "high")
        check(connection().requestMethod == "GET" && connection().url.toString() == "https://api.deepseek.com/models")
        check(connection().sent.size() == 0 && connection().disconnected)
    }
    test("models failure sanitized") {
        val (c, _) = client("""{"object":"list","data":[{"id":"duplicate"},{"id":"duplicate"}]}""", mime = "application/json")
        val error = runBlocking { runCatching { c.listModels() }.exceptionOrNull() }
        check(error is DeepSeekChatClient.DeepSeekChatException)
    }
    test("callback failures propagate and disconnect") {
        val (c, connection) = client(sse(chunk("""{"content":"hi"}""")))
        val failure = IllegalStateException("UI fixture failure")
        val error = runBlocking { runCatching { c.generate(request) { throw failure } }.exceptionOrNull() }
        check(error === failure && connection().disconnected)
    }
    test("explicit and coroutine cancellation disconnect blocked read") {
        for (explicit in listOf(true, false)) runBlocking {
            val entered = CountDownLatch(1)
            val released = CountDownLatch(1)
            val input = object : InputStream() {
                override fun read(): Int {
                    entered.countDown()
                    check(released.await(3, TimeUnit.SECONDS)) { "disconnect failed to release blocked read" }
                    throw IOException("connection closed")
                }
            }
            lateinit var connection: FakeConnection
            val c = DeepSeekChatClient({ "SECRET_KEY" }, DeepSeekChatClient.Config(), { url ->
                FakeConnection(url, input, onDisconnect = { released.countDown() }).also { connection = it }
            })
            val events = mutableListOf<AssistantStreamEvent>()
            val work = async { c.generate(request) { events += it } }
            withTimeout(3000) { while (entered.count > 0) delay(5) }
            check(runCatching { c.listModels() }.isFailure) // one operation per instance
            if (explicit) c.cancel() else work.cancel()
            withTimeout(3000) {
                if (explicit) work.await()
                else check(runCatching { work.await() }.exceptionOrNull() is CancellationException)
            }
            check(connection.disconnected)
            if (explicit) check(events.single() is AssistantStreamEvent.MessageCancelled)
        }
    }
    test("cancel before call does not cancel future request") {
        val (c, _) = client(sse(chunk(finish = "\"stop\""), "[DONE]"))
        c.cancel()
        check(generate(c).last() is AssistantStreamEvent.MessageCompleted)
        check(generate(c).first().sequenceNumber == 0L)
    }
    test("cancel during chunk delivery keeps cancellation sequence contiguous") {
        val (c, _) = client(sse(chunk("""{"content":"answer","reasoning_content":"public"}"""),
            chunk(finish = "\"stop\""), "[DONE]"))
        val events = mutableListOf<AssistantStreamEvent>()
        runBlocking {
            c.generate(request) {
                events += it
                if (it is AssistantStreamEvent.MessageStarted) c.cancel()
            }
        }
        check(events.size == 2 && events.last() is AssistantStreamEvent.MessageCancelled)
        check(reduce(events).messages.single().status == AssistantMessageStatus.CANCELLED)
    }
    test("cancel during nonstream batch still emits cancellation") {
        val body = """{"choices":[{"index":0,"message":{"role":"assistant","content":"answer"},"finish_reason":"stop"}]}"""
        val (c, _) = client(body, mime = "application/json")
        val events = mutableListOf<AssistantStreamEvent>()
        runBlocking {
            c.generate(request.copy(streaming = false)) {
                events += it
                if (it is AssistantStreamEvent.MessageStarted) c.cancel()
            }
        }
        check(reduce(events).messages.single().status == AssistantMessageStatus.CANCELLED)
    }
    test("history empty and current user only") {
        check(OfficialChatHistory.build(emptyList()).isEmpty())
        check(OfficialChatHistory.build(listOf(ChatMessage(1, "user", "current"))) == listOf(h("user", "current")))
    }
    test("attachment context survives history without local URIs or display labels") {
        val input = "Inspect this file\n\nAttachment: sample.kt\nfun hello() = 1"
        val user = ChatMessage(1, "user", "Inspect this file", modelInput = input,
            attachmentIds = listOf("a5ba00ea-6c88-41b4-8f25-1b569d925f04"))
        check(OfficialChatHistory.build(listOf(user)) == listOf(h("user", input)))
        val history = OfficialChatHistory.build(listOf(user,
            ChatMessage(2, "assistant", "answer", historyAnswer()), ChatMessage(3, "user", "continue")))
        check(history == listOf(h("user", input), h("assistant", "answer"), h("user", "continue")))
        check(history.none { it.content.contains("content://") || it.content.contains(user.attachmentIds.first()) })
    }
    test("history uses complete backend snapshot text and excludes thinking") {
        val snapshot = historyAnswer()
        val messages = listOf(ChatMessage(1, "user", "first"),
            ChatMessage(2, "assistant", "stale display text", snapshot), ChatMessage(3, "user", "current"))
        val built = OfficialChatHistory.build(messages)
        check(built == listOf(h("user", "first"), h("assistant", "answer"), h("user", "current")))
        check(!built.toString().contains("PRIVATE_HISTORY_THINKING"))
        check(messages[1].text == "stale display text")
        check(OfficialChatHistory.build(messages) == built)
    }
    test("history drops legacy demo and non-backend pairs") {
        val completed = historyAnswer("must not be sent")
        for (source in AssistantStreamSource.entries.filter { it != AssistantStreamSource.BACKEND }) {
            val built = OfficialChatHistory.build(listOf(ChatMessage(1, "user", "excluded user"),
                ChatMessage(2, "assistant", "excluded answer", completed.copy(source = source)),
                ChatMessage(3, "user", "current")))
            check(built == listOf(h("user", "current"))) { source }
        }
        check(OfficialChatHistory.build(listOf(ChatMessage(1, "user", "legacy user"),
            ChatMessage(2, "assistant", "legacy plain answer"), ChatMessage(3, "user", "current"))) ==
            listOf(h("user", "current")))
    }
    test("history drops all non-completed or non-final backend pairs") {
        val completed = historyAnswer()
        for (status in AssistantMessageStatus.entries.filter { it != AssistantMessageStatus.COMPLETED }) {
            val snapshot = completed.copy(messages = completed.messages.map { it.copy(status = status) })
            check(OfficialChatHistory.build(listOf(ChatMessage(1, "user", "excluded"),
                ChatMessage(2, "assistant", "excluded", snapshot), ChatMessage(3, "user", "current"))) ==
                listOf(h("user", "current"))) { status }
        }
        check(OfficialChatHistory.build(listOf(ChatMessage(1, "user", "excluded"),
            ChatMessage(2, "assistant", "excluded", completed.copy(isFinal = false)),
            ChatMessage(3, "user", "current"))) == listOf(h("user", "current")))
    }
    test("history accepts legacy or completed sessions and rejects every other run status") {
        val completed = historyAnswer()
        for (status in listOf<AssistantRunStatus?>(null) + AssistantRunStatus.entries) {
            for (includeTools in listOf(false, true)) {
                val built = OfficialChatHistory.build(listOf(
                    ChatMessage(1, "user", "previous"),
                    ChatMessage(2, "assistant", "fallback", completed.copy(runStatus = status)),
                    ChatMessage(3, "user", "current"),
                ), includeToolTurns = includeTools)
                val allowed = status == null || status == AssistantRunStatus.COMPLETED
                check(built.map { it.role to it.content } == if (allowed) listOf(
                    "user" to "previous", "assistant" to "answer", "user" to "current",
                ) else listOf("user" to "current")) { "status=$status tools=$includeTools" }
            }
        }
    }
    test("tool history requires session completion even when every model round and tool completed") {
        val toolSession = reduce(listOf(
            AssistantStreamEvent.MessageStarted(0, "tool-round"),
            AssistantStreamEvent.TextDelta(1, "tool-round", "Inspecting"),
            AssistantStreamEvent.ToolCallStarted(2, "history-call", "inspect",
                initialArguments = "{}", messageId = "tool-round"),
            AssistantStreamEvent.ToolArgumentsDone(3, "history-call"),
            AssistantStreamEvent.MessageCompleted(4, "tool-round", "tool_calls"),
            AssistantStreamEvent.ToolResult(5, "history-call", "inspection result"),
            AssistantStreamEvent.MessageStarted(6, "answer-round"),
            AssistantStreamEvent.TextDelta(7, "answer-round", "final answer"),
            AssistantStreamEvent.MessageCompleted(8, "answer-round", "stop"),
        ))
        check(toolSession.isFinal && toolSession.messages.all { it.status == AssistantMessageStatus.COMPLETED })
        for (status in listOf<AssistantRunStatus?>(null) + AssistantRunStatus.entries) {
            val built = OfficialChatHistory.build(listOf(
                ChatMessage(1, "user", "inspect previous"),
                ChatMessage(2, "assistant", "fallback", toolSession.copy(runStatus = status)),
                ChatMessage(3, "user", "current"),
            ), includeToolTurns = true)
            if (status == null || status == AssistantRunStatus.COMPLETED) {
                check(built.map { it.role to it.content } == listOf(
                    "user" to "inspect previous", "assistant" to "Inspecting",
                    "tool" to "inspection result", "assistant" to "final answer", "user" to "current",
                )) { status.toString() }
                check(built[1].toolCalls == listOf(DeepSeekChatClient.FunctionCall("history-call", "inspect", "{}")))
                check(built[2].toolCallId == "history-call")
            } else check(built == listOf(h("user", "current"))) { status.toString() }
        }
    }
    test("completed session status does not bypass snapshot and message completeness") {
        val completed = historyAnswer().copy(runStatus = AssistantRunStatus.COMPLETED)
        val variants = listOf(
            completed.copy(isFinal = false),
            completed.copy(source = AssistantStreamSource.DEMO),
            completed.copy(messages = emptyList()),
        ) + AssistantMessageStatus.entries.filter { it != AssistantMessageStatus.COMPLETED }.map { status ->
            completed.copy(messages = completed.messages.map { it.copy(status = status) })
        }
        for (snapshot in variants) {
            for (includeTools in listOf(false, true)) {
                check(OfficialChatHistory.build(listOf(
                    ChatMessage(1, "user", "excluded"),
                    ChatMessage(2, "assistant", "excluded", snapshot),
                    ChatMessage(3, "user", "current"),
                ), includeToolTurns = includeTools) == listOf(h("user", "current")))
            }
        }
    }
    test("interrupted restored pairs cannot leak into serialized context or disturb later pairs") {
        val completed = historyAnswer().copy(runStatus = AssistantRunStatus.COMPLETED)
        val messages = mutableListOf(
            ChatMessage(1, "user", "retained"),
            ChatMessage(2, "assistant", "fallback", completed),
        )
        for ((index, status) in AssistantRunStatus.entries.filter { it != AssistantRunStatus.COMPLETED }.withIndex()) {
            messages += ChatMessage(10L + index * 2, "user", "EXCLUDED_INPUT_$status",
                modelInput = "EXCLUDED_ATTACHMENT_TEXT_$status", attachmentIds = listOf("missing-image-$status"))
            messages += ChatMessage(11L + index * 2, "assistant", "EXCLUDED_DISPLAY_$status",
                historyAnswer("EXCLUDED_ROUND_$status").copy(runStatus = status))
        }
        messages += ChatMessage(30, "user", "later")
        messages += ChatMessage(31, "assistant", "fallback", historyAnswer("later answer"))
        messages += ChatMessage(32, "user", "current")
        val history = OfficialChatHistory.build(messages, includeToolTurns = true, attachmentCatalog = emptyMap())
        check(history.map { it.role to it.content } == listOf(
            "user" to "retained", "assistant" to "answer", "user" to "later",
            "assistant" to "later answer", "user" to "current",
        ))
        val (c, connection) = client(sse(chunk(finish = "\"stop\""), "[DONE]"))
        generate(c, request.copy(history = history))
        val body = connection().sent.toString("UTF-8")
        check(!body.contains("EXCLUDED_") && !body.contains("missing-image"))
        val sent = JSONObject(body).getJSONArray("messages")
        check(sent.length() == 5 && sent.getJSONObject(4).getString("content") == "current")
    }
    test("history drops tools empty snapshots and reasoning-only answers") {
        val completed = historyAnswer()
        val toolProtocol = DeepSeekChatProtocol("tool-history")
        val withTool = reduce(toolProtocol.accept(JSONObject(chunk(
            """{"tool_calls":[{"index":0,"id":"a","type":"function","function":{"name":"shell","arguments":"pwd"}}]}""",
            finish = "\"tool_calls\""))) + toolProtocol.end())
        val variants = listOf(
            completed.copy(toolCalls = withTool.toolCalls),
            completed.copy(messages = emptyList()),
            completed.copy(messages = completed.messages.map { message ->
                message.copy(blocks = message.blocks.filterIsInstance<AssistantContentBlockSnapshot.Reasoning>())
            }),
            historyAnswer(" \n "),
        )
        for (snapshot in variants) {
            check(OfficialChatHistory.build(listOf(ChatMessage(1, "user", "excluded"),
                ChatMessage(2, "assistant", "fallback must not enter history", snapshot),
                ChatMessage(3, "user", "current"))) == listOf(h("user", "current")))
        }
    }
    test("history pairs survive excluded intervening turns in order") {
        val completed = historyAnswer()
        val history = OfficialChatHistory.build(listOf(
            ChatMessage(1, "user", "one"), ChatMessage(2, "assistant", "stale", completed),
            ChatMessage(3, "user", "demo input"), ChatMessage(4, "assistant", "demo", completed.copy(source = AssistantStreamSource.DEMO)),
            ChatMessage(5, "user", "two"), ChatMessage(6, "assistant", "stale", historyAnswer("second answer")),
            ChatMessage(7, "user", "three"),
        ))
        check(history == listOf(h("user", "one"), h("assistant", "answer"),
            h("user", "two"), h("assistant", "second answer"), h("user", "three")))
    }
    test("history preserves text block order and excludes mixed incomplete messages") {
        val completed = historyAnswer()
        val message = completed.messages.single().copy(blocks = listOf(
            AssistantContentBlockSnapshot.Text("first", "A", true),
            AssistantContentBlockSnapshot.Reasoning("thinking", AssistantReasoningKind.USER_VISIBLE_THINKING, "SECRET", isComplete = true),
            AssistantContentBlockSnapshot.Text("second", "B", true),
        ))
        val snapshot = completed.copy(messages = listOf(message, message.copy(messageId = "other", blocks = listOf(
            AssistantContentBlockSnapshot.Text("third", "C", true),
        ))))
        val messages = listOf(ChatMessage(1, "user", "question"), ChatMessage(2, "assistant", "fallback", snapshot))
        check(OfficialChatHistory.build(messages) == listOf(h("user", "question"), h("assistant", "ABC")))
        val mixed = snapshot.copy(messages = listOf(message, message.copy(messageId = "other", status = AssistantMessageStatus.INCOMPLETE)))
        check(OfficialChatHistory.build(messages.map { if (it.role == "assistant") it.copy(streamSnapshot = mixed) else it }).isEmpty())
    }
    test("history orphan assistants and consecutive users do not create wrong pairs") {
        val completed = historyAnswer()
        check(OfficialChatHistory.build(listOf(
            ChatMessage(1, "assistant", "orphan", completed),
            ChatMessage(2, "user", "superseded"), ChatMessage(3, "user", "latest"),
            ChatMessage(4, "assistant", "answer", completed),
            ChatMessage(5, "assistant", "extra", completed), ChatMessage(6, "user", "current"),
        )) == listOf(h("user", "latest"), h("assistant", "answer"), h("user", "current")))
    }
    test("filtered history reaches serialized request without display fallback or thinking") {
        val history = OfficialChatHistory.build(listOf(
            ChatMessage(1, "user", "previous"), ChatMessage(2, "assistant", "DISPLAY_FALLBACK", historyAnswer()),
            ChatMessage(3, "user", "current"),
        ))
        val (c, connection) = client(sse(chunk(finish = "\"stop\""), "[DONE]"))
        generate(c, request.copy(history = history))
        val body = connection().sent.toString("UTF-8")
        check(!body.contains("DISPLAY_FALLBACK") && !body.contains("PRIVATE_HISTORY_THINKING"))
        val sent = JSONObject(body).getJSONArray("messages")
        check(sent.length() == 3 && sent.getJSONObject(2).getString("content") == "current")
        check(sent.getJSONObject(1).getString("content") == "answer")
    }
    println("$passed/$passed DeepSeek chat checks passed (offline fixtures; no credentials or paid calls)")
}
