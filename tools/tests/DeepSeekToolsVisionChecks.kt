package com.dafeiyu.mobile.data

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

private var passed = 0
private val failures = mutableListOf<String>()
private fun test(name: String, block: () -> Unit) {
    try {
        block()
        passed++
        println("PASS $name")
    } catch (error: Throwable) {
        failures += name
        println("FAIL $name: ${error.message}")
    }
}

private class ToolVisionConnection(url: URL, body: String, private val streaming: Boolean) : HttpsURLConnection(url) {
    val sent = ByteArrayOutputStream()
    private val response = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
    var disconnected = false
    override fun connect() {}
    override fun disconnect() { disconnected = true }
    override fun usingProxy() = false
    override fun getResponseCode() = 200
    override fun getContentType() = if (streaming) "text/event-stream" else "application/json"
    override fun getInputStream() = response
    override fun getOutputStream() = sent
    override fun getCipherSuite() = "offline-fixture"
    override fun getLocalCertificates(): Array<Certificate>? = null
    override fun getServerCertificates(): Array<Certificate> = emptyArray()
    override fun getPeerPrincipal(): Principal? = null
    override fun getLocalPrincipal(): Principal? = null
}

private data class Run(
    val turn: DeepSeekChatClient.Turn,
    val events: List<AssistantStreamEvent>,
    val connections: List<ToolVisionConnection>,
    val keyReads: Int,
) {
    fun body() = JSONObject(connections.single().sent.toString("UTF-8"))
}

private fun chunk(delta: JSONObject, finish: String? = null) = JSONObject().put("choices",
    JSONArray().put(JSONObject().put("index", 0).put("delta", delta)
        .put("finish_reason", finish ?: JSONObject.NULL))).toString()
private fun stream(vararg chunks: String) = chunks.joinToString("") { "data: $it\r\n\r\n" } + "data: [DONE]\r\n\r\n"
private fun stopResponse() = stream(chunk(JSONObject().put("role", "assistant").put("content", "ok"), "stop"))
private fun schema() = JSONObject().put("type", "object").put("properties",
    JSONObject().put("query", JSONObject().put("type", "string")))
    .put("required", JSONArray().put("query")).put("additionalProperties", false)
private fun tool() = DeepSeekChatClient.FunctionTool("lookup", "Find \"quoted\"\nitems \u6d4b\u8bd5", schema())
private fun request() = DeepSeekChatClient.Request("fixture-model", listOf(DeepSeekChatClient.HistoryMessage("user", "hello")))
private fun run(
    request: DeepSeekChatClient.Request,
    response: String = stopResponse(),
    config: DeepSeekChatClient.Config = DeepSeekChatClient.Config(),
    afterFreeze: () -> Unit = {},
): Run = runBlocking {
    val connections = mutableListOf<ToolVisionConnection>()
    var keyReads = 0
    val client = DeepSeekChatClient({
        keyReads++
        afterFreeze()
        "offline-fixture-key"
    }, config, { url -> ToolVisionConnection(url, response, request.streaming).also { connections += it } })
    val events = mutableListOf<AssistantStreamEvent>()
    Run(client.generate(request) { events += it }, events, connections, keyReads)
}
private fun invalid(request: DeepSeekChatClient.Request, code: String = "INVALID_REQUEST") {
    val actual = run(request)
    check(!actual.turn.completed)
    check(actual.connections.isEmpty() && actual.keyReads == 0) { "invalid input reached transport or credentials" }
    check(actual.events.filterIsInstance<AssistantStreamEvent.MessageFailed>().single().errorCode == code)
}
private fun functionCall(arguments: Any, index: Int? = 0) = JSONObject()
    .put("id", "call-1").put("type", "function")
    .put("function", JSONObject().put("name", "lookup").put("arguments", arguments))
    .also { if (index != null) it.put("index", index) }
private fun toolResponse(arguments: Any = "{\"query\":\"x\"}") = stream(chunk(
    JSONObject().put("role", "assistant").put("reasoning_content", "public thinking")
        .put("tool_calls", JSONArray().put(functionCall(arguments))), "tool_calls"))
private val image = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aWZ0AAAAASUVORK5CYII="

fun main() {
    test("public preflight returns actual encoded bytes without credentials or connection") {
        var keyReads = 0
        var connections = 0
        val client = DeepSeekChatClient({ keyReads++; "unused" }, DeepSeekChatClient.Config(), {
            connections++
            error("preflight must never open transport")
        })
        val req = request().copy(tools = listOf(tool()), history = listOf(
            DeepSeekChatClient.HistoryMessage("user", "photo \"\n\u6d4b", images = listOf(image)),
        ))
        val bytes = client.validateRequest(req)
        check(bytes == run(req).connections.single().sent.size())
        check(keyReads == 0 && connections == 0)
    }
    test("4 MiB preflight accepts exact encoded boundary and rejects overflow with zero requests") {
        var keyReads = 0
        var connections = 0
        val client = DeepSeekChatClient({ keyReads++; "unused" }, DeepSeekChatClient.Config(), {
            connections++
            error("oversized preflight must never open transport")
        })
        val limit = 4 * 1024 * 1024
        val empty = request().copy(history = listOf(DeepSeekChatClient.HistoryMessage("user", "")))
        val overhead = client.validateRequest(empty)
        val exact = empty.copy(history = listOf(DeepSeekChatClient.HistoryMessage("user", "x".repeat(limit - overhead))))
        check(client.validateRequest(exact) == limit)
        val overflow = exact.copy(history = listOf(exact.history.single().copy(content = exact.history.single().content + "x")))
        val failure = runCatching { client.validateRequest(overflow) }.exceptionOrNull()
        check(failure is DeepSeekChatClient.DeepSeekChatException && failure.code == "REQUEST_LIMIT")
        invalid(overflow, "REQUEST_LIMIT")
        check(keyReads == 0 && connections == 0)
    }
    test("4 MiB preflight bounds UTF8 escaped content and image payload by serialized bytes") {
        var keyReads = 0
        var connections = 0
        val client = DeepSeekChatClient({ keyReads++; "unused" }, DeepSeekChatClient.Config(), {
            connections++
            error("oversized preflight must never open transport")
        })
        val requests = listOf(
            request().copy(history = listOf(DeepSeekChatClient.HistoryMessage("user", "\u6d4b".repeat(1_400_000)))),
            request().copy(history = listOf(DeepSeekChatClient.HistoryMessage("user", "\"".repeat(2_100_000)))),
            request().copy(history = listOf(DeepSeekChatClient.HistoryMessage("user", "",
                images = listOf("data:image/png;base64," + "AAAA".repeat(1_048_576))))),
            request().copy(tools = listOf(tool().copy(parameters = schema().put("large_fixture", "x".repeat(4 * 1024 * 1024))))),
        )
        requests.forEach { req ->
            val failure = runCatching { client.validateRequest(req) }.exceptionOrNull()
            check(failure is DeepSeekChatClient.DeepSeekChatException && failure.code == "REQUEST_LIMIT")
        }
        check(keyReads == 0 && connections == 0)
    }
    test("image history maps attachment IDs in message order and preserves modelInput") {
        val secondImage = image.replace("image/png", "image/jpeg")
        val user = ChatMessage(1, "user", "display label", modelInput = "actual model input",
            attachmentIds = listOf("second", "missing", "first"))
        val images = linkedMapOf("first" to image, "unattached" to image, "second" to secondImage)
        val history = OfficialChatHistory.build(listOf(user), imageData = images)
        check(history == listOf(DeepSeekChatClient.HistoryMessage("user", "actual model input",
            images = listOf(secondImage, image))))
        check(OfficialChatHistory.build(listOf(user)).single().images.isEmpty())
        check(OfficialChatHistory.build(listOf(user)).single().content == "actual model input")
        val encoded = run(request().copy(history = history)).body().getJSONArray("messages")
            .getJSONObject(0).getJSONArray("content")
        check(encoded.getJSONObject(1).getJSONObject("image_url").getString("url") == secondImage)
        check(encoded.getJSONObject(2).getJSONObject("image_url").getString("url") == image)
    }
    test("completed image history and pending user map independently without assistant images") {
        val snapshot = run(request()).turn.snapshot
        val messages = listOf(
            ChatMessage(1, "user", "old image", attachmentIds = listOf("old")),
            ChatMessage(2, "assistant", "display only", streamSnapshot = snapshot),
            ChatMessage(3, "user", "new image", attachmentIds = listOf("new")),
        )
        for (includeTools in listOf(false, true)) {
            val history = OfficialChatHistory.build(messages, includeTools, mapOf("old" to image, "new" to image))
            check(history.size == 3 && history.map { it.role } == listOf("user", "assistant", "user"))
            check(history[0].images == listOf(image) && history[2].images == listOf(image))
            check(history[1].images.isEmpty() && history[1].content == "ok")
        }
    }
    test("filtered demo or incomplete turns cannot leak their image mappings into next request") {
        val snapshot = run(request()).turn.snapshot
        val incomplete = run(request(), stream(chunk(JSONObject().put("content", "partial"), "length"))).turn.snapshot
        for (excluded in listOf(snapshot.copy(source = AssistantStreamSource.DEMO), incomplete)) {
            val messages = listOf(
                ChatMessage(1, "user", "excluded", attachmentIds = listOf("excluded-image")),
                ChatMessage(2, "assistant", "excluded", streamSnapshot = excluded),
                ChatMessage(3, "user", "current", attachmentIds = listOf("current-image")),
            )
            val history = OfficialChatHistory.build(messages, true,
                mapOf("excluded-image" to image, "current-image" to image))
            check(history == listOf(DeepSeekChatClient.HistoryMessage("user", "current", images = listOf(image))))
        }
    }
    test("default request omits tools and tool_choice and retains plain text") {
        val actual = run(request())
        check(!actual.body().has("tools") && !actual.body().has("tool_choice"))
        check(actual.body().getJSONArray("messages").getJSONObject(0).getString("content") == "hello")
        check(actual.turn.completed && actual.connections.single().disconnected)
    }
    test("tool schema and escaped description serialize structurally in both response modes") {
        for (streaming in listOf(true, false)) {
            val response = if (streaming) stopResponse() else
                """{"choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
            val actual = run(request().copy(tools = listOf(tool()), streaming = streaming), response)
            val encoded = actual.body().getJSONArray("tools").getJSONObject(0)
            check(encoded.getString("type") == "function")
            val function = encoded.getJSONObject("function")
            check(function.getString("name") == "lookup" && function.getString("description") == tool().description)
            check(function.getJSONObject("parameters").similar(schema()))
            check(actual.body().getString("tool_choice") == "auto" && actual.turn.completed)
        }
    }
    test("assistant reasoning_content and tool result preserve exact JSON escaping and roles") {
        val arguments = """{"query":"quote \" newline\n backslash\\ \u6d4b"}"""
        val result = "result \"\n\\\u0000\u6d4b\u8bd5"
        val req = request().copy(history = listOf(
            DeepSeekChatClient.HistoryMessage("user", "lookup"),
            DeepSeekChatClient.HistoryMessage("assistant", "", reasoningContent = "think \"\n\u6d4b",
                toolCalls = listOf(DeepSeekChatClient.FunctionCall("call-1", "lookup", arguments))),
            DeepSeekChatClient.HistoryMessage("tool", result, toolCallId = "call-1"),
        ), tools = listOf(tool()))
        val messages = run(req).body().getJSONArray("messages")
        val assistant = messages.getJSONObject(1)
        check(assistant.getString("reasoning_content") == req.history[1].reasoningContent)
        check(!assistant.has("thinking_content"))
        val call = assistant.getJSONArray("tool_calls").getJSONObject(0)
        check(call.getString("id") == "call-1" && call.getString("type") == "function")
        check(call.getJSONObject("function").getString("arguments") == arguments)
        val output = messages.getJSONObject(2)
        check(output.getString("role") == "tool" && output.getString("tool_call_id") == "call-1")
        check(output.getString("content") == result && !output.has("tool_calls"))
    }
    test("advertised fragmented tool turn completes with READY proposals and reconstructs assistant history") {
        val response = stream(
            chunk(JSONObject().put("role", "assistant").put("reasoning_content", "think ")
                .put("tool_calls", JSONArray().put(functionCall("{\"query\":")))),
            chunk(JSONObject().put("reasoning_content", "again").put("content", "preface")
                .put("tool_calls", JSONArray().put(JSONObject().put("index", 0)
                    .put("function", JSONObject().put("arguments", "\"x\"}")))), "tool_calls"),
        )
        val actual = run(request().copy(tools = listOf(tool())), response)
        check(actual.turn.completed && actual.turn.snapshot.isFinal)
        val call = actual.turn.snapshot.toolCalls.single()
        check(call.status == AssistantStreamEvent.ToolStatus.READY && call.result == null)
        val history = actual.turn.assistantHistory()
        check(history.role == "assistant" && history.content == "preface" && history.reasoningContent == "think again")
        check(history.toolCalls == listOf(DeepSeekChatClient.FunctionCall("call-1", "lookup", "{\"query\":\"x\"}")))
        check(actual.events.none { it is AssistantStreamEvent.ToolResult || it is AssistantStreamEvent.ToolOutputDelta })
    }
    test("unadvertised tool response stays incomplete and does not execute") {
        val actual = run(request(), toolResponse())
        check(!actual.turn.completed)
        check(actual.turn.snapshot.messages.single().incompleteReason == "tool_execution_not_connected")
        check(actual.turn.snapshot.toolCalls.single().status == AssistantStreamEvent.ToolStatus.READY)
    }
    test("nonstream tool proposal has same completed and history semantics when advertised") {
        val response = JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0)
            .put("message", JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
                .put("reasoning_content", "think").put("tool_calls", JSONArray().put(functionCall("{}", null))))
            .put("finish_reason", "tool_calls"))).toString()
        val actual = run(request().copy(streaming = false, tools = listOf(tool())), response)
        check(actual.turn.completed && actual.turn.assistantHistory().toolCalls.single().arguments == "{}")
    }
    test("two turns continue sequence without gaps and resubmit assistant reasoning plus tool result") {
        val first = run(request().copy(tools = listOf(tool()), sequenceStart = 37), toolResponse())
        val next = first.turn.snapshot.nextExpectedSequenceNumber!!
        val secondRequest = request().copy(sequenceStart = next, tools = listOf(tool()), history =
            request().history + first.turn.assistantHistory() +
                DeepSeekChatClient.HistoryMessage("tool", "{\"found\":true}", toolCallId = "call-1"))
        val second = run(secondRequest)
        val events = first.events + second.events
        events.forEachIndexed { index, event -> check(event.sequenceNumber == 37L + index) }
        val reducer = AssistantStreamReducer(startSequenceNumber = 37, source = AssistantStreamSource.BACKEND)
        events.forEach { check(reducer.apply(it).disposition == AssistantStreamReducer.ApplyDisposition.APPLIED) }
        check(reducer.finish().messages.all { it.status == AssistantMessageStatus.COMPLETED })
        check(second.events.first().sequenceNumber == next)
        check(second.body().getJSONArray("messages").getJSONObject(1).getString("reasoning_content") == "public thinking")
        check(second.body().getJSONArray("messages").getJSONObject(2).getString("tool_call_id") == "call-1")
    }
    test("request schema and nested parameters are frozen before credentials and connection hooks") {
        val parameters = schema()
        val tools = mutableListOf(DeepSeekChatClient.FunctionTool("lookup", "original", parameters))
        val images = mutableListOf(image)
        val calls = mutableListOf(DeepSeekChatClient.FunctionCall("call-1", "lookup", "{}"))
        val history = mutableListOf(
            DeepSeekChatClient.HistoryMessage("user", "photo", images = images),
            DeepSeekChatClient.HistoryMessage("assistant", "", toolCalls = calls),
        )
        val actual = run(request().copy(history = history, tools = tools), afterFreeze = {
            parameters.getJSONObject("properties").getJSONObject("query").put("type", "number")
            tools.clear(); images.clear(); calls.clear(); history.clear()
        })
        check(actual.body().getJSONArray("tools").getJSONObject(0).getJSONObject("function")
            .getJSONObject("parameters").getJSONObject("properties").getJSONObject("query").getString("type") == "string")
        check(actual.body().getJSONArray("messages").getJSONObject(0).getJSONArray("content").length() == 2)
        check(actual.body().getJSONArray("messages").getJSONObject(1).getJSONArray("tool_calls").length() == 1)
    }
    test("vision content encodes ordered text plus image_url parts with exact inline bytes") {
        for (streaming in listOf(true, false)) {
            val response = if (streaming) stopResponse() else
                """{"choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
            val actual = run(request().copy(streaming = streaming, history = listOf(
                DeepSeekChatClient.HistoryMessage("user", "photo \"\n\u6d4b", images = listOf(image, image)),
            )), response)
            val parts = actual.body().getJSONArray("messages").getJSONObject(0).getJSONArray("content")
            check(parts.length() == 3)
            check(parts.getJSONObject(0).getString("type") == "text")
            check(parts.getJSONObject(0).getString("text") == "photo \"\n\u6d4b")
            for (i in 1..2) {
                check(parts.getJSONObject(i).getString("type") == "image_url")
                check(parts.getJSONObject(i).getJSONObject("image_url").getString("url") == image)
            }
        }
    }
    test("malformed unsupported and local image references fail before transport") {
        for (value in listOf("https://example.invalid/photo.png", "file:///private/photo.png", "content://private",
            "data:image/svg+xml;base64,AAAA", "data:image/png;base64,", "data:image/png;base64,AAA",
            "data:image/png;base64,AA*A", "data:image/png;base64,AA A", "data:image/png;base64,AAAA\n")) {
            invalid(request().copy(history = listOf(DeepSeekChatClient.HistoryMessage("user", "", images = listOf(value)))))
        }
    }
    test("invalid Base64 padding is rejected before credentials and transport") {
        for (body in listOf("====", "AA=A", "A===", "=AAA", "AAAA====")) {
            check(runCatching { java.util.Base64.getDecoder().decode(body) }.isFailure)
            invalid(request().copy(history = listOf(DeepSeekChatClient.HistoryMessage("user", "",
                images = listOf("data:image/png;base64,$body")))))
        }
    }
    test("vision requires user role and at most six images") {
        for (role in listOf("system", "assistant", "tool")) {
            invalid(request().copy(history = listOf(DeepSeekChatClient.HistoryMessage(role, "", images = listOf(image)))))
        }
        check(run(request().copy(history = listOf(
            DeepSeekChatClient.HistoryMessage("user", "", images = List(6) { image })))).turn.completed)
        invalid(request().copy(history = listOf(
            DeepSeekChatClient.HistoryMessage("user", "", images = List(7) { image }))))
    }
    test("vision payload obeys total request byte bound before any credential access") {
        invalid(request().copy(history = listOf(DeepSeekChatClient.HistoryMessage("user", "",
            images = listOf("data:image/png;base64," + "AAAA".repeat(1024))))),
            config = DeepSeekChatClient.Config(maxRequestBytes = 512))
    }
    test("invalid tool schemas duplicate names and count fail before transport") {
        for (tools in listOf(
            listOf(tool().copy(name = "")), listOf(tool().copy(name = "bad\nname")),
            listOf(tool().copy(parameters = JSONObject().put("type", "array"))),
            listOf(tool().copy(description = "x".repeat(4097))),
            listOf(tool(), tool()), List(17) { tool().copy(name = "tool$it") },
        )) invalid(request().copy(tools = tools))
    }
    test("tool and reasoning history fields require their exact roles and identifiers") {
        for (message in listOf(
            DeepSeekChatClient.HistoryMessage("tool", "output"),
            DeepSeekChatClient.HistoryMessage("user", "hello", toolCallId = "call-1"),
            DeepSeekChatClient.HistoryMessage("assistant", "", toolCallId = "call-1"),
            DeepSeekChatClient.HistoryMessage("user", "", reasoningContent = "thinking"),
            DeepSeekChatClient.HistoryMessage("user", "", toolCalls = listOf(DeepSeekChatClient.FunctionCall("id", "lookup", "{}"))),
            DeepSeekChatClient.HistoryMessage("tool", "", toolCallId = "bad\nid"),
            DeepSeekChatClient.HistoryMessage("assistant", "", toolCalls = listOf(DeepSeekChatClient.FunctionCall("", "lookup", "{}"))),
            DeepSeekChatClient.HistoryMessage("assistant", "", toolCalls = listOf(DeepSeekChatClient.FunctionCall("id", "", "{}"))),
        )) invalid(request().copy(history = listOf(message)))
    }
    test("oversized history tool arguments and excessive calls are rejected before transport") {
        check(run(request().copy(history = listOf(DeepSeekChatClient.HistoryMessage("assistant", "",
            toolCalls = listOf(DeepSeekChatClient.FunctionCall("id", "lookup",
                "x".repeat(OfficialToolContract.MAX_ARGUMENT_BYTES))))))).turn.completed)
        invalid(request().copy(history = listOf(DeepSeekChatClient.HistoryMessage("assistant", "",
            toolCalls = listOf(DeepSeekChatClient.FunctionCall("id", "lookup",
                "x".repeat(OfficialToolContract.MAX_ARGUMENT_BYTES + 1)))))))
        invalid(request().copy(history = listOf(DeepSeekChatClient.HistoryMessage("assistant", "",
            toolCalls = List(17) { DeepSeekChatClient.FunctionCall("id$it", "lookup", "{}") }))))
    }
    test("provider nonstring arguments fail safely without tool result or execution") {
        val actual = run(request().copy(tools = listOf(tool())), toolResponse(JSONObject().put("query", "x")))
        check(!actual.turn.completed && actual.events.any { it is AssistantStreamEvent.MessageFailed })
        check(actual.events.none { it is AssistantStreamEvent.ToolResult || it is AssistantStreamEvent.ToolOutputDelta })
    }
    test("malformed argument JSON remains opaque proposal for runner validation, never executes") {
        val arguments = "{\"query\":"
        val actual = run(request().copy(tools = listOf(tool())), toolResponse(arguments))
        check(actual.turn.snapshot.toolCalls.single().arguments == arguments)
        check(actual.turn.snapshot.toolCalls.single().status == AssistantStreamEvent.ToolStatus.READY)
        check(runCatching { JSONObject(actual.turn.assistantHistory().toolCalls.single().arguments) }.isFailure)
        check(actual.events.none { it is AssistantStreamEvent.ToolResult || it is AssistantStreamEvent.ToolOutputDelta })
    }
    test("truncated advertised tool response remains incomplete without completed argument execution") {
        val response = stream(chunk(JSONObject().put("role", "assistant")
            .put("tool_calls", JSONArray().put(functionCall("{\"query\":"))), "length"))
        val actual = run(request().copy(tools = listOf(tool())), response)
        check(!actual.turn.completed && actual.turn.snapshot.messages.single().incompleteReason == "length")
        check(actual.turn.snapshot.toolCalls.single().status == AssistantStreamEvent.ToolStatus.INCOMPLETE)
    }
    println("DeepSeek tools/vision transport checks: $passed PASS, ${failures.size} FAIL (offline HTTPS fixtures; no tool execution or paid calls)")
    check(failures.isEmpty()) { "Failed checks: ${failures.joinToString()}" }
}

private fun invalid(request: DeepSeekChatClient.Request, config: DeepSeekChatClient.Config) {
    val actual = run(request, config = config)
    check(actual.connections.isEmpty() && actual.keyReads == 0)
    check(actual.events.filterIsInstance<AssistantStreamEvent.MessageFailed>().single().errorCode == "REQUEST_LIMIT")
}
