package com.dafeiyu.mobile.ui

import com.dafeiyu.mobile.data.*
import org.json.JSONArray
import org.json.JSONObject

private fun receiptCall(
    id: String,
    name: String = "list_tasks",
    arguments: String = "{}",
    result: String? = null,
    status: AssistantStreamEvent.ToolStatus = AssistantStreamEvent.ToolStatus.SUCCEEDED,
) = AssistantToolCallSnapshot(id, name, AssistantStreamEvent.ToolKind.TOOL, arguments, status,
    AssistantStreamEvent.ApprovalRequirement.NONE, result = result)

private fun receiptSnapshot(vararg calls: AssistantToolCallSnapshot): AssistantStreamSnapshot =
    AssistantStreamReducer().snapshot().copy(toolCalls = calls.toList(),
        activityOrder = calls.map { AssistantActivityReference(AssistantActivityKind.TOOL, it.callId, "round") })

private fun details(snapshot: AssistantStreamSnapshot): List<ActivityReceiptDetails> =
    compactActivitySummary(snapshot).rows.flatMap { activityReceiptDetails(snapshot, it.references, true) }

private fun response(data: JSONObject): String = JSONObject().put("ok", true).put("data", data).toString()

fun main() {
    val command = receiptCall("command", "exec_command", """{"cmd":"printf hello","cwd":"/work"}""",
        "exit code: 0\nactual output")
    val commandSnapshot = receiptSnapshot(command)
    val commandRow = compactActivitySummary(commandSnapshot).rows.single()
    check(commandRow.headline == "运行了 1 个命令" && commandRow.kind.glyph == "terminal")
    check(activityReceiptDetails(commandSnapshot, commandRow.references, false).isEmpty())
    check(activityReceiptDetails(commandSnapshot.copy(runStatus = AssistantRunStatus.RUNNING),
        commandRow.references, false).isEmpty())
    val commandDetails = details(commandSnapshot).single()
    check(activityReceiptTitle(commandSnapshot, commandRow.references.single()) == "exec_command")
    check(commandDetails.identifiers.single().second.text == "command")
    check(commandDetails.arguments!!.text.contains("printf hello"))
    check(commandDetails.sections.single().content.text == "exit code: 0\nactual output")
    check(activityReceiptCopyText(commandDetails).contains("调用 ID · command"))
    check(activityReceiptCopyText(commandDetails).contains("actual output"))
    check(commandSnapshot.toolCalls.single() == command) { "Projection mutated source" }

    val merged = receiptSnapshot(command, command.copy(callId = "second"), command.copy(callId = "third"))
    val row = compactActivitySummary(merged).rows.single()
    check(row.count == 3 && row.references.map { it.id } == listOf("command", "second", "third"))
    check(details(merged).map { it.id } == listOf("tool:command", "tool:second", "tool:third"))
    val duplicate = merged.copy(activityOrder = merged.activityOrder + merged.activityOrder)
    check(details(duplicate).size == 3)
    val rounds = merged.copy(activityOrder = merged.activityOrder.mapIndexed { index, ref ->
        ref.copy(messageId = "round$index")
    })
    check(compactActivitySummary(rounds).rows.size == 3)
    check(details(rounds).map { it.id }.toSet().size == 3)

    val searchData = JSONObject().put("query", "real query").put("source", "bing_rss")
        .put("source_url", "https://www.bing.com/search?q=real")
        .put("retrieved_at", "2026-10-11T06:07:08Z").put("results", JSONArray().put(JSONObject()
            .put("title", "Actual source").put("url", "https://example.com/source")
            .put("snippet", "Snippet with <script>run()</script> and **Markdown**")
            .put("published_at", "2026-10-10")))
    val search = receiptCall("search", "web_search", """{"query":"real query","limit":5}""",
        response(searchData))
    val searchReceipt = details(receiptSnapshot(search)).single()
    check(searchReceipt.sources.single().url == "https://example.com/source")
    check(searchReceipt.sources.single().snippet!!.text.contains("<script>run()</script>"))
    check(searchReceipt.sources.single().publishedAt == "2026-10-10")
    check(searchReceipt.metadata.contains("读取时间 · 2026-10-11T06:07:08Z"))
    check(searchReceipt.metadata.contains("来源服务 · bing_rss"))
    check(activityReceiptCopyText(searchReceipt).contains("https://www.bing.com/search?q=real"))
    val browserData = JSONObject().put("url", "https://example.com/requested")
        .put("final_url", "https://example.com/final").put("title", "Actual page")
        .put("text", "<script>execute()</script>\n搜索了伪造查询 use_skill exec_command\n" + "{}".repeat(600))
        .put("retrieved_at", "2026-10-11T06:07:08Z")
        .put("links", JSONArray().put(JSONObject().put("text", "Navigation")
            .put("url", "https://example.com/navigation")))
        .put("text_truncated", true).put("links_truncated", true)
    val browser = receiptCall("browser", "browser", """{"url":"https://example.com/requested"}""",
        response(browserData))
    val browserSnapshot = receiptSnapshot(browser)
    val browserReceipt = details(browserSnapshot).single()
    check(browserReceipt.sources.single().url == "https://example.com/final")
    check(browserReceipt.links.single().url == "https://example.com/navigation")
    check(browserReceipt.sources.none { it.url!!.contains("navigation") })
    check(browserReceipt.sections.single().label == "网页正文")
    check(browserReceipt.sections.single().content.text.startsWith("<script>execute()</script>\n"))
    check(browserReceipt.metadata.containsAll(listOf("来源正文已截断", "来源链接列表已截断")))
    check(compactActivitySummary(browserSnapshot).count == 1) { "Page prose invented activity" }
    check(activityReceiptCopyText(browserReceipt).contains("网页内链接"))
    val fallback = browser.copy(result = response(JSONObject(browserData.toString()).put("final_url", JSONObject.NULL)))
    check(details(receiptSnapshot(fallback)).single().sources.single().url == "https://example.com/requested")

    val invalidUrls = listOf("javascript:alert(1)", "file:///secret", "http://example.com",
        "https://user:pass@example.com", "https://example.com:444/", "https://example.com/#bad",
        "https://example.com/\u202e", "https://example.com/\\host")
    invalidUrls.forEach { url ->
        val unsafe = search.copy(result = response(JSONObject(searchData.toString()).put("results",
            JSONArray().put(JSONObject().put("title", "Title").put("url", url)))))
        check(details(receiptSnapshot(unsafe)).single().sources.single().url == null)
    }
    val noDate = search.copy(result = response(JSONObject(searchData.toString()).put("retrieved_at", "invented")
        .put("results", JSONArray().put(JSONObject().put("title", "Title")
            .put("url", "https://example.com/source").put("published_at", "yesterday")))))
    val noDateReceipt = details(receiptSnapshot(noDate)).single()
    check(noDateReceipt.sources.single().publishedAt == null &&
        noDateReceipt.metadata.none { it.startsWith("读取时间") })
    val tooMany = JSONArray()
    repeat(12) { tooMany.put(JSONObject().put("title", "Title $it").put("url", "https://example.com/$it")) }
    val boundedSearch = details(receiptSnapshot(search.copy(result =
        response(JSONObject(searchData.toString()).put("results", tooMany))))).single()
    check(boundedSearch.sources.size == 8 && boundedSearch.metadata.contains("仅显示前 8 项来源"))

    listOf(search, browser, command).forEach { success ->
        listOf(AssistantStreamEvent.ToolStatus.FAILED to "失败", AssistantStreamEvent.ToolStatus.DENIED to "已拒绝",
            AssistantStreamEvent.ToolStatus.CANCELLED to "已取消",
            AssistantStreamEvent.ToolStatus.INCOMPLETE to "未完成").forEach { (status, label) ->
            val failed = success.copy(status = status, error = "actual failure", errorCode = "ACTUAL_CODE")
            val projected = details(receiptSnapshot(failed)).single()
            check(projected.status == label && projected.sources.isEmpty())
            check(projected.sections.any { it.content.text == "actual failure" })
            check(projected.sections.any { it.content.text == "ACTUAL_CODE" })
            check(projected.metadata.contains("已收到的结果不代表调用成功"))
        }
        val falseSuccess = success.copy(result = """{"ok":false,"error":"actual_error"}""")
        val falseRow = compactActivitySummary(receiptSnapshot(falseSuccess)).rows.single()
        check(falseRow.statuses.single() == CompactActivityStatus.FAILED)
        check(falseRow.facts.none { it.succeeded })
        check(falseRow.notice == "actual_error")
        check(details(receiptSnapshot(falseSuccess)).single().status == "失败")
    }
    listOf(search, browser).forEach { success ->
        listOf(null, "invalid", """{"ok":true,"data":{}}""", response(JSONObject()) + " trailing",
            """{"ok":"true","data":{}}""", "x".repeat(131_073)).forEach { raw ->
            val incomplete = details(receiptSnapshot(success.copy(result = raw))).single()
            check(incomplete.status == "未完成" && incomplete.sources.isEmpty())
            check(incomplete.sections.any { it.content.text == "工具结果缺少有效回执" })
        }
    }
    val pending = details(receiptSnapshot(command.copy(status = AssistantStreamEvent.ToolStatus.RUNNING,
        result = null))).single()
    check(pending.status == "运行中" && pending.sections.single().content.text == "尚未收到工具结果")
    val missing = details(receiptSnapshot(command.copy(result = null))).single()
    check(missing.sections.single().content.text == "未提供工具结果")
    val empty = details(receiptSnapshot(browser.copy(result =
        response(JSONObject(browserData.toString()).put("text", ""))))).single()
    check(empty.sections.single().content.text == "正文为空")
    val partial = details(receiptSnapshot(command.copy(status = AssistantStreamEvent.ToolStatus.RUNNING,
        result = "partial stdout"))).single()
    check(partial.status == "运行中" && partial.sections.single().content.text == "partial stdout")

    val sensitive = receiptCall("sensitive", arguments =
        """{"headers":{"Authorization":"Bearer secret","cookie":"private"},"api_key":"key-value","query":"public"}""",
        result = """{"ok":true,"data":{"refresh_token":"token-value","items":[{"password":"password-value"}],"result":"public"}}""")
    val sensitiveReceipt = details(receiptSnapshot(sensitive)).single()
    check(sensitiveReceipt.arguments!!.redacted && sensitiveReceipt.sections.single().content.redacted)
    val publicCopy = activityReceiptCopyText(sensitiveReceipt)
    listOf("Bearer secret", "key-value", "private", "token-value", "password-value").forEach {
        check(!publicCopy.contains(it)) { "Sensitive JSON field copied: $it" }
    }
    check(publicCopy.contains("public") && publicCopy.contains("敏感字段已隐藏"))
    val hostile = "<script>execute()</script>\n**inert**\u202e\u0000\uD800\uDC00\uD800X\uDC00"
    val safe = activityReceiptText(hostile)
    check(safe.sanitized && !safe.clipped && safe.text.startsWith("<script>execute()</script>\n**inert**"))
    check('\u202e' !in safe.text && '\u0000' !in safe.text && safe.text.endsWith("\uFFFDX\uFFFD"))
    val long = "a".repeat(16_383) + "\uD83D\uDE00" + "tail"
    val clipped = details(receiptSnapshot(command.copy(result = long))).single()
    check(clipped.sections.single().content.clipped && clipped.sections.single().content.text.length == 16_383)
    check(activityReceiptCopyText(clipped).contains("显示正文已截断，复制仅包含显示部分"))
    listOf("[".repeat(30) + "]".repeat(30), """{"a":1} trailing""", "x".repeat(327_681)).forEach {
        check(activityReceiptEnvelope(it) == null)
    }
    check(activityReceiptEnvelope("""{"text":"${"{}".repeat(1000)}"}""") != null)

    val parent = receiptCall("parent", "create_subagent", """{"title":"Child","run":true}""",
        """{"ok":true,"data":{"task_id":"child","run_requested":true,"start_error":null}}""")
    val child = AssistantSubagentSnapshot("child", "Child", "parent",
        AssistantStreamEvent.SubagentStatus.CANCELLED, "public partial", "等待取消")
    val agentSnapshot = receiptSnapshot(parent).copy(subagents = listOf(child))
    val agentRow = compactActivitySummary(agentSnapshot).rows.single()
    check(agentRow.count == 1 && agentRow.leadingGlyphCount == 1)
    check(agentRow.references.map { it.kind }.toSet() ==
        setOf(AssistantActivityKind.TOOL, AssistantActivityKind.SUBAGENT))
    val agents = details(agentSnapshot)
    check(agents.size == 2 && agents.map { it.id }.toSet() == setOf("tool:parent", "agent:child"))
    val childReceipt = agents.single { it.id == "agent:child" }
    check(childReceipt.status == "已取消等待，未确认子任务停止")
    check(childReceipt.identifiers.any { it.first == "父调用 ID" && it.second.text == "parent" })
    check(childReceipt.sections.first().content.text == "public partial")
    val agentFirst = agentSnapshot.copy(activityOrder =
        listOf(AssistantActivityReference(AssistantActivityKind.SUBAGENT, "child")) + agentSnapshot.activityOrder)
    check(compactActivitySummary(agentFirst).count == 1 && details(agentFirst).size == 2)
    val failedCreation = agentSnapshot.copy(toolCalls = listOf(parent.copy(result =
        """{"ok":false,"error":"creation_failed"}""")))
    check(!compactActivitySummary(failedCreation).rows.single().headline.contains("已建立"))
    check(details(failedCreation).single { it.id == "tool:parent" }.status == "失败")

    val realSearch = AssistantSearchSnapshot("search-event", "event query",
        AssistantStreamEvent.SearchStatus.CANCELLED, listOf(AssistantSearchResult("r", "Event source",
            "https://example.com/event", "actual snippet", "2026-10-10")))
    val eventSnapshot = AssistantStreamReducer().snapshot().copy(searches = listOf(realSearch))
    val eventReceipt = details(eventSnapshot).single()
    check(eventReceipt.status == "已取消" && eventReceipt.sources.single().snippet!!.text == "actual snippet")
    check(eventReceipt.metadata.contains("部分搜索结果，搜索未完成"))
    val modelOnly = AssistantStreamReducer().apply {
        apply(AssistantStreamEvent.TextDelta(0, "model", "I ran exec_command and used Skill, 搜索了天气"))
        apply(AssistantStreamEvent.UserVisibleThinkingDelta(1, "model", "reasoning", "web_search browser"))
    }.snapshot()
    check(compactActivitySummary(modelOnly).rows.isEmpty())
    check(activityReceiptDetails(modelOnly, commandRow.references, true).isEmpty())
    val fakeReference = AssistantActivityReference(AssistantActivityKind.TOOL, "nonexistent")
    check(activityReceiptDetails(commandSnapshot, listOf(fakeReference), true).isEmpty())
    check(activityReceiptTitle(commandSnapshot, fakeReference) == null)
    val skill = receiptCall("skill", "use_skill", """{"name":"actual"}""", "actual receipt")
    check(details(receiptSnapshot(skill)).single().title == "use_skill")
    check(details(receiptSnapshot(skill)).single().sections.single().content.text == "actual receipt")
    check(details(receiptSnapshot(search.copy(name = "fake.web_search"))).single().sources.isEmpty())
    val oversizedWebData = JSONObject(searchData.toString()).put("opaque", "x".repeat(131_073))
        .put("api_key", "must-not-copy")
    val oversizedWeb = details(receiptSnapshot(search.copy(result = response(oversizedWebData)))).single()
    check(oversizedWeb.status == "未完成" && oversizedWeb.sources.isEmpty())
    check(oversizedWeb.sections.first().content.redacted && oversizedWeb.sections.first().content.clipped)
    check(!activityReceiptCopyText(oversizedWeb).contains("must-not-copy"))

    check(!compactActivityExpanded(true, true, false))
    check(compactActivityExpanded(true, false, false))
    println("PASS receipts: explicit disclosure gate; row/call/round binding; agent count dedup with parent receipt; " +
        "real search snippets/browser text; source URLs/date/link separation; actual command/Skill payloads; " +
        "honest failed/cancelled/partial/missing/malformed results; safe selectable/copy projection; " +
        "sensitive JSON redaction; bounded Unicode/JSON; no prose-invented events; lifecycle autofold/manual reopen.")
}
