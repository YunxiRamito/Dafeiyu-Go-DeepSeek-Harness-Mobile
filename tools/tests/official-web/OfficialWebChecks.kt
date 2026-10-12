package com.dafeiyu.mobile.data

import java.io.File
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.buffer
import okio.source
import org.json.JSONObject
import org.json.JSONArray

private class WebModelConnection(url: URL, response: String) : HttpsURLConnection(url) {
    val sent = ByteArrayOutputStream()
    var disconnected = false
    private val input = ByteArrayInputStream(response.toByteArray(Charsets.UTF_8))
    override fun connect() {}
    override fun disconnect() { disconnected = true }
    override fun usingProxy() = false
    override fun getResponseCode() = 200
    override fun getContentType() = "text/event-stream"
    override fun getInputStream() = input
    override fun getOutputStream() = sent
    override fun getCipherSuite() = "offline-web-session"
    override fun getLocalCertificates(): Array<Certificate>? = null
    override fun getServerCertificates(): Array<Certificate> = emptyArray()
    override fun getPeerPrincipal(): Principal? = null
    override fun getLocalPrincipal(): Principal? = null
}

private fun modelResponse(id: String? = null, name: String? = null, arguments: String = "{}"): String {
    val delta = JSONObject().put("role", "assistant").put("reasoning_content", "fixture reasoning")
    if (id == null) delta.put("content", "Sources read")
    else delta.put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", id)
        .put("type", "function").put("function", JSONObject().put("name", name).put("arguments", arguments))))
    val response = JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0)
        .put("delta", delta).put("finish_reason", if (id == null) "stop" else "tool_calls")))
    return "data: $response\n\ndata: [DONE]\n\n"
}

private fun page(body: String, type: String = "text/html", status: Int = 200, location: String? = null) =
    OfficialWebResponse(status, type, body.toByteArray(), location)
private fun rss(items: String) = page("<rss><channel>$items</channel></rss>", "application/rss+xml")
private fun item(title: String = "Android", url: String = "https://developer.android.com/", snippet: String = "Docs") =
    "<item><title>$title</title><link>$url</link><description>$snippet</description></item>"
private suspend fun fails(code: String, action: suspend () -> Unit) {
    try { action(); error("expected $code") }
    catch (failed: OfficialWebException) { check(failed.code == code) { "${failed.code} != $code" } }
}

fun main(args: Array<String>) = runBlocking {
    val root = File(args.first()).apply { mkdirs() }
    var passed = 0
    var failed = 0
    suspend fun test(name: String, action: suspend () -> Unit) {
        try {
            withTimeout(5_000) { action() }
            passed++
            println("PASS $name")
        } catch (failure: Throwable) {
            failed++
            println("FAIL $name: ${failure.javaClass.simpleName}: ${failure.message}")
            failure.printStackTrace(System.out)
        }
    }
    fun client(handler: suspend (HttpUrl) -> OfficialWebResponse) =
        OfficialWebClient(OfficialWebTransport(handler)) { 1_792_000_000_000L }
    fun dispatcher(provider: OfficialToolWebProvider? = null) = OfficialToolDispatcher({ null },
        OfficialToolOutputStore(File(root, "outputs-$passed")), "conversation", { "model" },
        taskDispatcher = Dispatchers.Unconfined, webProvider = provider)
    fun call(name: String, args: JSONObject, id: String = "web-$passed") = OfficialToolCall(id, name, args.toString())

    test("web schema opt-in and session advertised tools") {
        check(OfficialToolContract.schemas().length() == 5)
        val dispatcher = dispatcher(client { rss(item()) })
        val tools = OfficialToolSession.tools(dispatcher)
        check(tools.map { it.name }.takeLast(2) == listOf("web_search", "browser"))
        check(OfficialToolContract.schemas(includeWeb = true).length() == 7)
    }
    test("Bing RSS structured title snippet URL and limit") {
        val result = client { url ->
            check(url.host == "www.bing.com" && url.queryParameter("q") == "today 中文")
            check(url.queryParameter("format") == "rss")
            rss(item("One", snippet = "&lt;b&gt;escaped &amp;amp; safe&lt;/b&gt;") + item("Two"))
        }.search("today 中文", 1)
        check(result.getString("source") == "bing_rss")
        check(result.getJSONArray("results").length() == 1)
        check(result.getJSONArray("results").getJSONObject(0).getString("snippet") == "escaped & safe")
        check(result.getString("retrieved_at").endsWith("Z"))
    }
    test("Bing failure falls back to real DDG result HTML") {
        val result = client { url ->
            if (url.host == "www.bing.com") page("", status = 429)
            else page("""<div class="result"><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2F">Example</a><a class="result__snippet">Real snippet</a></div>""")
        }.search("public search", 5)
        check(result.getString("source") == "duckduckgo_html")
        check(result.getJSONArray("fallback_errors").getJSONObject(0).getString("error") == "web_http_429")
        check(result.getJSONArray("results").getJSONObject(0).getString("url") == "https://example.com/")
    }
    test("challenge failures honest and include both sources") {
        val failure = runCatching { client { url ->
            if (url.host == "www.bing.com") page("challenge") else page("<form id='challenge-form'></form>")
        }.search("query", 5) }.exceptionOrNull() as OfficialWebException
        check(failure.code == "web_search_unavailable")
        val attempts = checkNotNull(failure.details).getJSONArray("attempts")
        check(attempts.length() == 2)
        check(attempts.getJSONObject(1).getString("error") == "web_search_challenge")
    }
    test("empty RSS not fabricated as success") {
        fails("web_search_unavailable") { client { rss("") }.search("query", 5) }
    }
    test("DTD XML rejected without loading external entity") {
        val failure = runCatching {
            client { page("<!DOCTYPE rss SYSTEM 'https://localhost/secret'><rss><channel>${item()}</channel></rss>",
                "application/rss+xml") }.search("query", 5)
        }.exceptionOrNull() as OfficialWebException
        check(checkNotNull(failure.details).getJSONArray("attempts").getJSONObject(0)
            .getString("error") == "web_xml_doctype")
    }
    test("DOM text removes active content and resolves links") {
        val result = client {
            page("<html><head><title>Example &amp; title</title></head><body><main>Hello &lt;world&gt;<script>steal()</script>" +
                "<a href='/next#part'>Next</a><a href='javascript:alert(1)'>Bad</a>" +
                "<a href='https://127.0.0.1/'>Private</a><a href='/next#other'>Duplicate</a></main></body></html>")
        }.browse("https://example.com/page")
        check(result.getString("title") == "Example & title")
        check(!result.getString("text").contains("steal()"))
        check(result.getJSONArray("links").length() == 1)
        check(result.getJSONArray("links").getJSONObject(0).getString("url") == "https://example.com/next")
    }
    test("plain text preserved rather than interpreted as HTML") {
        val text = "a < b\n\"quoted\" \\ newline\n<script>ordinary text</script>"
        val result = client { page(text, "text/plain") }.browse("https://example.com/")
        check(result.getString("text") == text)
        check(JSONObject(result.toString()).getString("text") == text)
    }
    test("redirect checks every hop and records final URL") {
        val visited = mutableListOf<String>()
        val result = client { url ->
            visited += url.encodedPath
            if (url.encodedPath == "/") page("", status = 302, location = "/final")
            else page("<title>Final</title><main>final content</main>")
        }.browse("https://example.com/")
        check(visited == listOf("/", "/final"))
        check(result.getString("final_url") == "https://example.com/final")
    }
    test("redirect to private address rejected before next request") {
        var requests = 0
        fails("web_private_address") {
            client { requests++; page("", status = 302, location = "https://169.254.169.254/") }
                .browse("https://example.com/")
        }
        check(requests == 1)
    }
    test("redirect loop limited to four requests") {
        var requests = 0
        fails("web_redirect_limit") { client { requests++; page("", status = 302, location = "/again") }
            .browse("https://example.com/") }
        check(requests == 4)
    }
    test("missing location downgrade and credential redirects fail before another request") {
        for (location in listOf(null, "http://example.com/final", "https://user:pw@example.com/final",
            "https://example.com:8443/final", "https://example.com/final\n")) {
            var requests = 0
            val failure = runCatching {
                client { requests++; page("", status = 302, location = location) }.browse("https://example.com/")
            }.exceptionOrNull()
            check(failure is OfficialWebException) { "accepted redirect $location" }
            check(requests == 1) { "sent additional request for rejected redirect" }
        }
    }
    test("default HTTPS port and fragments normalize before transport") {
        val visited = mutableListOf<String>()
        client { visited += it.toString(); page("public", "text/plain") }
            .browse("https://example.com:443/path#part")
        check(visited == listOf("https://example.com/path"))
    }
    test("schemes credentials ports ambiguous URLs blocked before transport") {
        var requests = 0
        val client = client { requests++; page("no") }
        for (url in listOf("http://example.com/", "file:///etc/passwd", "https://user:pw@example.com/",
            "https://example.com:8443/", "https://example.com\\@127.0.0.1/", "https://example.com/\n",
            "https://2130706433/", "https://localhost/", "https://server.internal/")) {
            check(runCatching { client.browse(url) }.exceptionOrNull() is OfficialWebException) { url }
        }
        check(requests == 0)
        fails("web_url_invalid") { client.browse("https://example.com/" + "a".repeat(2048)) }
    }
    test("IPv4 IPv6 reserved and transition address policy") {
        for (host in listOf("0.0.0.0", "10.1.2.3", "127.0.0.1", "172.20.1.2", "192.168.0.1",
            "100.64.0.1", "198.18.0.1", "192.0.2.1", "224.0.0.1", "240.0.0.1",
            "::1", "fc00::1", "fe80::1", "::ffff:127.0.0.1", "64:ff9b::a00:1", "2002:a00:1::1",
            "2001:db8::1", "2001::1")) {
            check(!OfficialWebPolicy.publicAddress(InetAddress.getByName(host))) { host }
        }
        check(OfficialWebPolicy.publicAddress(InetAddress.getByName("8.8.8.8")))
        check(OfficialWebPolicy.publicAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }
    test("DNS rejects mixed public private answers and returns checked addresses") {
        val public = InetAddress.getByName("8.8.8.8")
        val private = InetAddress.getByName("10.0.0.1")
        check(runCatching { OfficialWebDns { listOf(public, private) }.lookup("example.com") }
            .exceptionOrNull() is OfficialWebException)
        val answers = listOf(public)
        check(OfficialWebDns { answers }.lookup("example.com") === answers)
        check(runCatching { OfficialWebDns { emptyList() }.lookup("example.com") }
            .exceptionOrNull() is OfficialWebException)
        val reserved = InetAddress.getByName("2001:db8::1")
        check(runCatching { OfficialWebDns { listOf(public, reserved) }.lookup("example.com") }
            .exceptionOrNull() is OfficialWebException)
    }
    test("response over one MiB rejected and binary refused") {
        fails("web_response_too_large") { client { page("a".repeat(OfficialWebPolicy.MAX_BYTES + 1)) }
            .browse("https://example.com/") }
        fails("web_content_type") { client { page("binary", "application/octet-stream") }
            .browse("https://example.com/") }
    }
    test("exact one MiB allowed and byte limit counts multibyte UTF8") {
        val accepted = client { page("a".repeat(OfficialWebPolicy.MAX_BYTES), "text/plain") }
            .browse("https://example.com/")
        check(accepted.getBoolean("text_truncated"))
        check(accepted.getString("text").length == 12_000)
        fails("web_response_too_large") {
            client { page("中".repeat(OfficialWebPolicy.MAX_BYTES / 3 + 1), "text/plain") }
                .browse("https://example.com/")
        }
    }
    test("real HTTP transport rejects declared and streamed oversize bodies and sends no credentials") {
        for (declared in listOf(true, false)) {
            var consumed = 0
            var closed = false
            var requests = 0
            val input = object : InputStream() {
                override fun read(): Int = if (consumed++ < OfficialWebPolicy.MAX_BYTES + 1) 'a'.code else -1
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    val count = minOf(length, OfficialWebPolicy.MAX_BYTES + 1 - consumed)
                    if (count <= 0) return -1
                    bytes.fill('a'.code.toByte(), offset, offset + count)
                    consumed += count
                    return count
                }
                override fun close() { closed = true }
            }
            val body = object : ResponseBody() {
                private val buffered = input.source().buffer()
                override fun contentType() = "text/plain; charset=utf-8".toMediaType()
                override fun contentLength() = if (declared) OfficialWebPolicy.MAX_BYTES + 1L else -1L
                override fun source() = buffered
            }
            val fixtureClient = OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                val request = chain.request()
                for (name in listOf("Authorization", "Proxy-Authorization", "Cookie", "X-API-Key")) {
                    check(request.header(name) == null) { "unexpected credential header $name" }
                }
                check(request.header("Accept")?.contains("text/html") == true)
                check(request.method == "GET")
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                    .message("offline fixture").body(body).build()
            }.build()
            val transport = OfficialWebHttpTransport()
            // Inject an interceptor-only HTTP client without changing the production constructor.
            OfficialWebHttpTransport::class.java.getDeclaredField("client").apply { isAccessible = true }
                .set(transport, fixtureClient)
            try {
                fails("web_response_too_large") {
                    OfficialWebClient(transport) { 0L }.browse("https://example.com/")
                }
                check(requests == 1 && closed)
                check(if (declared) consumed == 0 else consumed == OfficialWebPolicy.MAX_BYTES + 1)
            } finally {
                fixtureClient.dispatcher.executorService.shutdownNow()
                fixtureClient.connectionPool.evictAll()
            }
        }
    }
    test("empty content and invalid plain text encoding are honest failures") {
        fails("web_empty_response") { client { page("") }.browse("https://example.com/") }
        fails("web_encoding_invalid") {
            client { OfficialWebResponse(200, "text/plain", byteArrayOf(0xc3.toByte(), 0x28)) }
                .browse("https://example.com/")
        }
    }
    test("large browser output remains valid bounded JSON with truthful flags") {
        val result = client { page("<title>large</title><main>" + "中".repeat(15_000) + "</main>" +
            (1..30).joinToString("") { "<a href='/next$it'>Link $it</a>" }) }.browse("https://example.com/")
        check(result.getString("text").length == 12_000)
        check(result.getBoolean("text_truncated") && result.getBoolean("links_truncated"))
        check(result.getJSONArray("links").length() == 20)
        val dispatched = dispatcher(object : OfficialToolWebProvider {
            override suspend fun search(query: String, limit: Int) = error("unused")
            override suspend fun browse(url: String) = result
        }).execute(call("browser", JSONObject().put("url", "https://example.com/")))
        check(JSONObject(dispatched.content).getBoolean("ok"))
        check(dispatched.content.toByteArray().size <= OfficialToolContract.MAX_REPLY_BYTES)
    }
    test("dispatcher executes caches and validates tools with real provider") {
        var requests = 0
        val dispatcher = dispatcher(client { requests++; rss(item()) })
        val search = call("web_search", JSONObject().put("query", "query"), "one")
        val result = dispatcher.execute(search)
        check(JSONObject(result.content).getBoolean("ok"))
        check(dispatcher.execute(search) == result && requests == 1)
        check(JSONObject(dispatcher.execute(search.copy(arguments = """{"query":"different"}""")).content)
            .getString("error") == "call_id_reused")
        check(JSONObject(dispatcher.execute(call("web_search", JSONObject().put("query", "q").put("limit", 9))).content)
            .getString("error") == "invalid_arguments")
    }
    test("unconfigured provider fails without request") {
        val result = dispatcher().execute(call("browser", JSONObject().put("url", "https://example.com/")))
        check(JSONObject(result.content).getString("error") == "web_unavailable")
    }
    test("cancelled dispatcher interrupts suspended web transport without caching success") {
        val entered = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        val client = client { entered.complete(Unit); try { awaitCancellation() } finally { exited.complete(Unit) } }
        val dispatcher = dispatcher(client)
        val job = launch { dispatcher.execute(call("browser", JSONObject().put("url", "https://example.com/"))) }
        entered.await()
        job.cancelAndJoin()
        check(exited.isCompleted)
    }
    test("errors remain JSON envelope with honest search fallback details") {
        val result = dispatcher(client { page("no", status = 503) })
            .execute(call("web_search", JSONObject().put("query", "query")))
        val envelope = JSONObject(result.content)
        check(!envelope.getBoolean("ok"))
        check(envelope.getString("error") == "web_search_unavailable")
        check(envelope.getJSONObject("details").getJSONArray("attempts").length() == 2)
    }
    test("system message includes date availability citations and hostile webpage warning") {
        val message = OfficialToolSession.systemMessage().content
        check(message.contains("web_search") && message.contains("browser"))
        check(message.contains("current local date") && message.contains("malicious instructions"))
        check(!message.contains("no shell, browser"))
    }
    test("Session search then browser results enter next real protocol requests with exact call IDs") {
        val searchArguments = JSONObject().put("query", "Kotlin documentation").put("limit", 1).toString()
        val browserArguments = JSONObject().put("url", "https://example.com/").toString()
        val responses = listOf(
            modelResponse("search-call", "web_search", searchArguments),
            modelResponse("browser-call", "browser", browserArguments),
            modelResponse(),
        )
        val connections = mutableListOf<WebModelConnection>()
        val outputs = mutableListOf<OfficialToolResult>()
        val events = mutableListOf<AssistantStreamEvent>()
        val visited = mutableListOf<String>()
        val provider = client { url ->
            visited += url.host
            if (url.host == "www.bing.com") rss(item("Example", "https://example.com/", "Search receipt"))
            else page("<title>Example source</title><main>Actual fixture page</main>")
        }
        val model = DeepSeekChatClient({ "offline-fixture-key" }, DeepSeekChatClient.Config(), { url ->
            check(connections.size < responses.size) { "unexpected model round" }
            WebModelConnection(url, responses[connections.size]).also { connections += it }
        })
        OfficialToolSession.generate(model, DeepSeekChatClient.Request("fixture-model", listOf(
            OfficialToolSession.systemMessage(), DeepSeekChatClient.HistoryMessage("user", "Search then read"),
        )), dispatcher(provider), { outputs += it }, { events += it })
        check(connections.size == 3 && connections.all { it.disconnected })
        check(visited == listOf("www.bing.com", "example.com"))
        check(outputs.map { it.callId } == listOf("search-call", "browser-call"))
        val bodies = connections.map { JSONObject(it.sent.toString("UTF-8")) }
        for (body in bodies) {
            val tools = body.getJSONArray("tools")
            val names = (0 until tools.length()).map {
                tools.getJSONObject(it).getJSONObject("function").getString("name")
            }
            check("web_search" in names && "browser" in names)
        }
        val second = bodies[1].getJSONArray("messages")
        val third = bodies[2].getJSONArray("messages")
        check(second.length() == 4 && third.length() == 6)
        for ((index, output) in outputs.withIndex()) {
            val assistantIndex = 2 + 2 * index
            val assistant = third.getJSONObject(assistantIndex)
            check(assistant.getString("role") == "assistant")
            check(assistant.getString("reasoning_content") == "fixture reasoning")
            val call = assistant.getJSONArray("tool_calls").getJSONObject(0)
            check(call.getString("id") == output.callId)
            check(call.getJSONObject("function").getString("name") == output.name)
            check(call.getJSONObject("function").getString("arguments") ==
                if (index == 0) searchArguments else browserArguments)
            val tool = third.getJSONObject(assistantIndex + 1)
            check(tool.getString("role") == "tool")
            check(tool.getString("tool_call_id") == output.callId)
            check(tool.getString("content") == output.content)
            check(JSONObject(tool.getString("content")).getBoolean("ok"))
        }
        check(second.getJSONObject(3).getString("content") == outputs[0].content)
        val search = JSONObject(outputs[0].content).getJSONObject("data")
        check(search.getString("source") == "bing_rss")
        check(search.getJSONArray("results").getJSONObject(0).getString("url") == "https://example.com/")
        val browser = JSONObject(outputs[1].content).getJSONObject("data")
        check(browser.getString("final_url") == "https://example.com/")
        check(browser.getString("text") == "Actual fixture page")
        val reducer = AssistantStreamReducer(source = AssistantStreamSource.BACKEND)
        events.forEach { check(reducer.apply(it).disposition == AssistantStreamReducer.ApplyDisposition.APPLIED) }
        val snapshot = reducer.finish()
        check(snapshot.messages.size == 3 && snapshot.messages.all { it.status == AssistantMessageStatus.COMPLETED })
        check(snapshot.toolCalls.size == 2 &&
            snapshot.toolCalls.all { it.status == AssistantStreamEvent.ToolStatus.SUCCEEDED })
        File(root, "session-web-request-history.json").writeText(bodies[2].toString(2))
    }
    println("RESULT $passed PASS $failed FAIL (offline fixtures; no real model calls)")
    check(failed == 0) { "$failed production web checks failed" }
    if (args.getOrNull(1) == "live") {
        val live = OfficialWebClient()
        for (query in listOf("Android developer", "今天 新闻")) {
            val result = runCatching { live.search(query, 3) }
            File(root, "live-search-${if (query.startsWith("Android")) "en" else "zh"}.json")
                .writeText(result.getOrNull()?.toString(2) ?: JSONObject().put("ok", false)
                    .put("error", (result.exceptionOrNull() as? OfficialWebException)?.code ?: "unexpected").toString())
            println("LIVE search [$query]: " + result.fold(
                { "${it.getString("source")} ${it.getJSONArray("results").length()} results" },
                { (it as? OfficialWebException)?.code ?: it.javaClass.simpleName }))
        }
        val result = runCatching { live.browse("https://example.com/") }
        File(root, "live-browser.json").writeText(result.getOrNull()?.toString(2) ?: JSONObject().put("ok", false)
            .put("error", (result.exceptionOrNull() as? OfficialWebException)?.code ?: "unexpected").toString())
        println("LIVE browser: " + result.fold({ "${it.getString("title")} ${it.getString("text").length} chars" },
            { (it as? OfficialWebException)?.code ?: it.javaClass.simpleName }))
    }
}
