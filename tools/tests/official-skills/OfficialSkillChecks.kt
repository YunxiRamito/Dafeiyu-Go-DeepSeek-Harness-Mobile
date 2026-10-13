package com.dafeiyu.mobile.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
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
import org.json.JSONArray
import org.json.JSONObject

private class SkillModelConnection(url: URL, private val response: String) : HttpsURLConnection(url) {
    val sent = ByteArrayOutputStream()
    var disconnected = false
    override fun connect() = Unit
    override fun disconnect() { disconnected = true }
    override fun usingProxy() = false
    override fun getResponseCode() = 200
    override fun getContentType() = "text/event-stream"
    override fun getInputStream() = ByteArrayInputStream(response.toByteArray())
    override fun getOutputStream() = sent
    override fun getCipherSuite() = "offline-skills"
    override fun getLocalCertificates(): Array<Certificate>? = null
    override fun getServerCertificates(): Array<Certificate> = emptyArray()
    override fun getPeerPrincipal(): Principal? = null
    override fun getLocalPrincipal(): Principal? = null
}

private fun response(id: String? = null, name: String = "", arguments: String = "{}"): String {
    val delta = JSONObject().put("role", "assistant")
    if (id == null) delta.put("content", "Used the installed skill.")
    else delta.put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", id)
        .put("type", "function").put("function", JSONObject().put("name", name).put("arguments", arguments))))
    val event = JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", delta)
        .put("finish_reason", if (id == null) "stop" else "tool_calls")))
    return "data: $event\n\ndata: [DONE]\n\n"
}

fun main(args: Array<String>) = runBlocking {
    val root = File(args.single()).apply { mkdirs() }
    var passed = 0
    suspend fun test(name: String, block: suspend () -> Unit) {
        withTimeout(10_000) { block() }
        passed++
        println("PASS $name")
    }
    suspend fun fails(block: suspend () -> Unit): Boolean = try { block(); false } catch (_: Exception) { true }
    fun markdown(name: String, policy: String = "", body: String = "Read references/how.md before applying.") =
        "---\nname: $name\ndescription: Offline installed skill\n$policy---\n$body\n"
    val home = File(root, "home")
    val store = SkillInstallStore(home)
    val bundle = File(root, "bundle").apply { mkdirs() }
    val source = File(bundle, "SKILL.md").apply { writeText(markdown("sample-skill")) }
    File(bundle, "references").mkdirs()
    File(bundle, "references/how.md").writeText("A😀B中文")
    File(bundle, "LICENSE").writeText("MIT fixture")
    store.publishLocal(bundle)
    val adapter = OfficialSkillAdapter(store)
    fun dispatcher(provider: OfficialToolSkillProvider? = adapter) = OfficialToolDispatcher(
        { error("Delegated task must never reach task admission") }, OfficialToolOutputStore(File(root, "outputs")),
        "skill-conversation", { "offline-model" }, Dispatchers.Unconfined, skillProvider = provider,
        allowTaskCreation = false, allowFileOutputs = false)
    fun tool(id: String, name: String, body: JSONObject = JSONObject()) = OfficialToolCall(id, name, body.toString())
    fun readBody(file: String? = null) = JSONObject().put("name", "sample-skill").also { if (file != null) it.put("file", file) }

    test("real installed skill is discovered and instructions are read through the production adapter") {
        val catalog = adapter.list(0, 30)
        check(catalog.getInt("total") == 1 && catalog.getJSONArray("items").getJSONObject(0).getString("name") == "sample-skill")
        val content = adapter.read("sample-skill", null, 0, 16000)
        check(content.getString("content").contains("Read references") && !content.getString("content").contains("description:"))
        check(content.getString("execution_device") == "android_local" && content.isNull("next_offset"))
    }
    test("reference pagination preserves surrogate pairs and rejects a split offset") {
        val first = adapter.read("sample-skill", "references/how.md", 0, 2)
        check(first.getString("content") == "A" && first.getInt("next_offset") == 1)
        val second = adapter.read("sample-skill", "references/how.md", 1, 2)
        check(second.getString("content") == "😀" && second.getInt("next_offset") == 3)
        check(fails { adapter.read("sample-skill", "references/how.md", 2, 2) })
    }
    test("invalid relative paths and malformed UTF-8 never become skill content") {
        for (path in listOf("../LICENSE", "/LICENSE", "references/../../LICENSE", "references\\how.md", "C:/secret", "references//how.md")) {
            check(fails { adapter.read("sample-skill", path, 0, 100) })
        }
        File(home, "skills/sample-skill/references/invalid.md").writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        check(fails { adapter.read("sample-skill", "references/invalid.md", 0, 100) })
    }
    test("disabled physical skill disappears from discovery and rejects an old read intent") {
        store.setEnabled("sample-skill", false)
        check(adapter.list(0, 30).getInt("total") == 0)
        check(fails { adapter.read("sample-skill", null, 0, 100) })
        store.setEnabled("sample-skill", true)
    }
    test("changed invocation flags and unsafe YAML are rechecked at read time") {
        val installed = File(home, "skills/sample-skill/SKILL.md")
        installed.writeText(markdown("sample-skill", "disable-model-invocation: true\nuser-invocable: true\n"))
        check(adapter.list(0, 30).getInt("total") == 0 && fails { adapter.read("sample-skill", null, 0, 100) })
        check(store.load("sample-skill", modelInvoked = false).metadata.userInvocable)
        installed.writeText("---\nname: sample-skill\ndescription: !!java/object:java.lang.ProcessBuilder {}\n---\nBad")
        check(fails { adapter.read("sample-skill", null, 0, 100) })
        installed.writeText(source.readText())
    }
    test("delegated schemas and execution both reject nested tasks and file output") {
        val restricted = dispatcher()
        val schema = restricted.schemas().toString()
        check("list_skills" in schema && "read_skill" in schema)
        for (name in listOf("create_subagent", "create_background_task", "list_tasks", "get_task", "write_text_file")) {
            check(name !in schema)
            val result = JSONObject(restricted.execute(tool("restricted-$name", name)).content)
            check(!result.getBoolean("ok") && result.getString("error") == "unsupported_tool")
        }
        check(!File(root, "outputs").exists() || File(root, "outputs").listFiles().isNullOrEmpty())
    }
    test("dispatcher preserves receipts and rejects invalid skill bounds or extra arguments") {
        val d = dispatcher()
        val result = JSONObject(d.execute(tool("read-valid", "read_skill", readBody())).content)
        check(result.getBoolean("ok") && result.getJSONObject("data").getString("content").contains("references"))
        for ((index, body) in listOf(readBody().put("limit", 1), readBody().put("offset", 1.5), readBody().put("url", "https://example.com")).withIndex()) {
            check(JSONObject(d.execute(tool("read-invalid-$index", "read_skill", body)).content).getString("error") == "invalid_arguments")
        }
        check(JSONObject(dispatcher(null).execute(tool("unavailable", "list_skills")).content).getString("error") == "skills_unavailable")
    }
    test("cancelled skill read is not cached as a successful side effect") {
        val entered = CompletableDeferred<Unit>()
        var calls = 0
        val delayed = object : OfficialToolSkillProvider {
            override suspend fun list(offset: Int, limit: Int): JSONObject = adapter.list(offset, limit)
            override suspend fun read(name: String, file: String?, offset: Int, limit: Int): JSONObject {
                calls++
                if (calls == 1) { entered.complete(Unit); awaitCancellation() }
                return adapter.read(name, file, offset, limit)
            }
        }
        val d = dispatcher(delayed)
        val call = tool("retry-read", "read_skill", readBody())
        val job = launch { d.execute(call) }
        entered.await(); job.cancelAndJoin()
        check(JSONObject(d.execute(call).content).getBoolean("ok") && calls == 2)
    }
    test("real model protocol discovers and reads skills then receives exact tool IDs without task schemas") {
        val responses = listOf(response("catalog-call", "list_skills"), response("skill-call", "read_skill", readBody().toString()), response())
        val connections = mutableListOf<SkillModelConnection>()
        val outputs = mutableListOf<OfficialToolResult>()
        val events = mutableListOf<AssistantStreamEvent>()
        val client = DeepSeekChatClient({ "offline-fixture-key" }, DeepSeekChatClient.Config(), { url ->
            SkillModelConnection(url, responses[connections.size]).also { connections += it }
        })
        OfficialToolSession.generate(client, DeepSeekChatClient.Request("offline-model", listOf(
            OfficialToolSession.systemMessage(), DeepSeekChatClient.HistoryMessage("user", "Use the installed skill"))),
            dispatcher(), { outputs += it }, { events += it })
        check(connections.size == 3 && connections.all { it.disconnected })
        check(outputs.map { it.callId } == listOf("catalog-call", "skill-call"))
        val requests = connections.map { JSONObject(it.sent.toString("UTF-8")) }
        check(requests.all { it.getJSONArray("tools").toString().let { schema -> "read_skill" in schema && "create_subagent" !in schema } })
        val messages = requests.last().getJSONArray("messages")
        check(messages.length() == 6)
        check(messages.getJSONObject(3).getString("tool_call_id") == "catalog-call")
        check(messages.getJSONObject(5).getString("tool_call_id") == "skill-call")
        check(JSONObject(messages.getJSONObject(5).getString("content")).getJSONObject("data").getString("content").contains("references"))
        check(events.filterIsInstance<AssistantStreamEvent.ToolResult>().map { it.callId } ==
            listOf("catalog-call", "skill-call"))
    }
    println("RESULT $passed/$passed real shared skill files + production adapter/dispatcher/model protocol; Android lease/Keystore require device validation; paidModelRequests=0")
}
