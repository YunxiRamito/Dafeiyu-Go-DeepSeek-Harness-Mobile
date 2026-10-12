package com.dafeiyu.mobile.data

import com.dafeiyu.mobile.ui.compactActivityHistory
import com.dafeiyu.mobile.ui.compactActivitySummary
import com.dafeiyu.mobile.ui.compactWebReceipts
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

private class NextUserFixture(url: URL, response: String) : HttpsURLConnection(url) {
    val sent = ByteArrayOutputStream()
    var closed = false
    private val input = ByteArrayInputStream(response.toByteArray(Charsets.UTF_8))
    override fun connect() {}
    override fun disconnect() { closed = true }
    override fun usingProxy() = false
    override fun getResponseCode() = 200
    override fun getContentType() = "text/event-stream"
    override fun getInputStream() = input
    override fun getOutputStream() = sent
    override fun getCipherSuite() = "offline-next-user-fixture"
    override fun getLocalCertificates(): Array<Certificate>? = null
    override fun getServerCertificates(): Array<Certificate> = emptyArray()
}

private fun reply(id: String? = null, name: String? = null, arguments: String = "{}"): String {
    val delta = JSONObject().put("role", "assistant").put("reasoning_content", "fixture reasoning")
    if (id == null) delta.put("content", "FINAL_ANSWER_ONLY")
    else delta.put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", id)
        .put("type", "function").put("function", JSONObject().put("name", name).put("arguments", arguments))))
    val payload = JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0)
        .put("delta", delta).put("finish_reason", if (id == null) "stop" else "tool_calls")))
    return "data: $payload\n\ndata: [DONE]\n\n"
}

fun main(args: Array<String>) = runBlocking {
    withTimeout(10_000) {
        val root = File(args.single()).apply { mkdirs() }
        val searchArgs = """{"query":"Kotlin documentation","limit":1}"""
        val browserArgs = """{"url":"https://example.com/requested"}"""
        val responses = listOf(reply("search-id", "web_search", searchArgs),
            reply("browser-id", "browser", browserArgs), reply(), reply())
        val connections = mutableListOf<NextUserFixture>()
        val visited = mutableListOf<String>()
        val outputs = mutableListOf<OfficialToolResult>()
        val reducer = AssistantStreamReducer(source = AssistantStreamSource.BACKEND)
        val provider = OfficialWebClient(OfficialWebTransport { url ->
            visited += url.toString()
            when (url.host) {
                "www.bing.com" -> OfficialWebResponse(200, "application/rss+xml",
                    ("<rss><channel><item><title>Fixture source</title>" +
                        "<link>https://example.com/requested</link><description>Search receipt</description>" +
                        "</item></channel></rss>").toByteArray())
                else -> if (url.encodedPath == "/requested")
                    OfficialWebResponse(302, "", byteArrayOf(), "/final")
                else OfficialWebResponse(200, "text/html",
                    "<title>Fixture page</title><main>Actual fixture page</main>".toByteArray())
            }
        }, { 1_791_720_000_000L })
        val dispatcher = OfficialToolDispatcher({ null }, OfficialToolOutputStore(File(root, "outputs")),
            "exclusive-fixture", { "fixture-model" }, taskDispatcher = Dispatchers.Unconfined,
            webProvider = provider)
        val model = DeepSeekChatClient({ "synthetic-offline-key" }, DeepSeekChatClient.Config(), { url ->
            check(connections.size < responses.size)
            NextUserFixture(url, responses[connections.size]).also { connections += it }
        })
        OfficialToolSession.generate(model, DeepSeekChatClient.Request("fixture-model",
            listOf(OfficialToolSession.systemMessage(), DeepSeekChatClient.HistoryMessage("user", "Search then read"))),
            dispatcher, { outputs += it }, {
                check(reducer.apply(it).disposition == AssistantStreamReducer.ApplyDisposition.APPLIED)
            })
        val snapshot = reducer.finish().copy(runStatus = AssistantRunStatus.COMPLETED)
        check(connections.size == 3 && connections.all { it.closed })
        check(visited.size == 3 && visited.last() == "https://example.com/final")
        check(outputs.map { it.callId } == listOf("search-id", "browser-id"))
        val conversation = listOf(ChatMessage(1, "user", "Search then read"),
            ChatMessage(2, "assistant", "DISPLAY_FALLBACK_MUST_NOT_BE_SENT", snapshot),
            ChatMessage(3, "user", "Continue from these sources"))
        val history = OfficialChatHistory.build(conversation, includeToolTurns = true)
        check(history.map { it.role } == listOf("user", "assistant", "tool", "assistant", "tool", "assistant", "user"))
        check(history[1].toolCalls.single() == DeepSeekChatClient.FunctionCall("search-id", "web_search", searchArgs))
        check(history[3].toolCalls.single() == DeepSeekChatClient.FunctionCall("browser-id", "browser", browserArgs))
        for ((index, output) in outputs.withIndex()) {
            val tool = history[2 + index * 2]
            check(tool.toolCallId == output.callId && tool.content == output.content)
            check(history[1 + index * 2].reasoningContent == "fixture reasoning")
        }
        check(history[5].content == "FINAL_ANSWER_ONLY")
        check(history.last().content == "Continue from these sources")
        check(history.none { it.content.contains("DISPLAY_FALLBACK") })
        println("PASS completed Session search/browser survives OfficialChatHistory for next user with exact IDs/arguments/results/reasoning")

        OfficialToolSession.generate(model, DeepSeekChatClient.Request("fixture-model",
            listOf(OfficialToolSession.systemMessage()) + history), dispatcher,
            { error("Next user unexpectedly executed another tool") }, {})
        val nextRequest = JSONObject(connections.last().sent.toString("UTF-8"))
        val messages = nextRequest.getJSONArray("messages")
        check(messages.length() == 8 && connections.size == 4 && connections.all { it.closed })
        for ((index, output) in outputs.withIndex()) {
            val assistant = messages.getJSONObject(2 + index * 2)
            check(assistant.getString("reasoning_content") == "fixture reasoning")
            check(assistant.getJSONArray("tool_calls").getJSONObject(0).getString("id") == output.callId)
            val tool = messages.getJSONObject(3 + index * 2)
            check(tool.getString("tool_call_id") == output.callId && tool.getString("content") == output.content)
        }
        check(messages.getJSONObject(6).getString("content") == "FINAL_ANSWER_ONLY")
        check(messages.getJSONObject(7).getString("content") == "Continue from these sources")
        File(root, "next-user-request.json").writeText(nextRequest.toString(2))
        println("PASS next user's real serialized protocol request contains both receipts and final answer; no live model or network")

        val receipts = compactWebReceipts(snapshot)
        check(receipts.map { it.callId } == listOf("search-id", "browser-id"))
        check(receipts[0].sources.single().url == "https://example.com/requested")
        check(receipts[1].sources.single().url == "https://example.com/final")
        check(receipts.all { it.retrievedAt != null })
        check(compactActivitySummary(snapshot).rows.size == 2)
        check(compactActivityHistory(snapshot).none { part ->
            part.blocks.filterIsInstance<AssistantContentBlockSnapshot.Text>().any { it.text == "FINAL_ANSWER_ONLY" }
        })
        println("PASS actual Session events yield search/browser UI receipts with redirect final URL and no duplicate final reply")

        val broken = listOf(
            snapshot.copy(isFinal = false),
            snapshot.copy(runStatus = AssistantRunStatus.INCOMPLETE),
            snapshot.copy(toolCalls = snapshot.toolCalls.map { it.copy(result = null) }),
            snapshot.copy(toolCalls = snapshot.toolCalls.map { it.copy(status = AssistantStreamEvent.ToolStatus.CANCELLED) }),
        )
        for (invalid in broken) {
            val rebuilt = OfficialChatHistory.build(conversation.map {
                if (it.role == "assistant") it.copy(streamSnapshot = invalid) else it
            }, includeToolTurns = true)
            check(rebuilt == listOf(DeepSeekChatClient.HistoryMessage("user", "Continue from these sources")))
        }
        println("PASS incomplete/nonfinal/cancelled/missing tool results excluded from next-user history")
        println("RESULT 4 PASS 0 FAIL (offline fixtures; no ADB; no real model calls)")
    }
}
