package com.dafeiyu.mobile.ui

import com.dafeiyu.mobile.data.*
import org.json.JSONArray
import org.json.JSONObject

private fun webCall(
    id: String,
    name: String,
    arguments: String = "",
    status: AssistantStreamEvent.ToolStatus = AssistantStreamEvent.ToolStatus.SUCCEEDED,
    result: String? = null,
) = AssistantToolCallSnapshot(id, name, AssistantStreamEvent.ToolKind.TOOL, arguments,
    status, AssistantStreamEvent.ApprovalRequirement.NONE, result = result)

private fun webSnapshot(vararg calls: AssistantToolCallSnapshot): AssistantStreamSnapshot =
    AssistantStreamReducer().snapshot().copy(toolCalls = calls.toList(),
        activityOrder = calls.map { AssistantActivityReference(AssistantActivityKind.TOOL, it.callId, "round") })

private fun receipt(data: JSONObject): String = JSONObject().put("ok", true).put("data", data).toString()

private fun searchData(query: String = "真实查询") = JSONObject().put("query", query)
    .put("source", "bing_rss").put("source_url", "https://www.bing.com/search?q=test")
    .put("retrieved_at", "2026-10-11T06:07:08Z").put("results", JSONArray()
        .put(JSONObject().put("title", "真实标题").put("url", "https://example.com/source")
            .put("snippet", "模型自述不是事件：搜索了伪造查询")))

private fun browserData() = JSONObject().put("url", "https://example.com/requested")
    .put("final_url", "https://example.com/final").put("title", "真实网页")
    .put("text", "use_skill 搜索了伪造查询 已读取伪造网址")
    .put("links", JSONArray().put(JSONObject().put("text", "网页内部链接").put("url", "https://example.com/nested")))
    .put("text_truncated", true).put("links_truncated", true).put("retrieved_at", "2026-10-11T06:07:08Z")

private fun webRow(call: AssistantToolCallSnapshot): CompactActivityRow =
    compactActivitySummary(webSnapshot(call)).rows.single()

fun main() {
    val search = webCall("search", "web_search", """{"query":"真实查询","limit":5}""",
        result = receipt(searchData()))
    check(webRow(search).headline == "搜索了真实查询")
    val searchReceipt = compactWebReceipts(webSnapshot(search)).single()
    check(searchReceipt.callId == "search")
    check(searchReceipt.sources == listOf(CompactWebSource("真实标题", "https://example.com/source")))
    check(searchReceipt.provider == "bing_rss")
    check(searchReceipt.providerUrl == "https://www.bing.com/search?q=test")
    check(searchReceipt.retrievedAt == "2026-10-11T06:07:08Z")
    check(!searchReceipt.textTruncated && !searchReceipt.linksTruncated)
    check(webRow(search.copy(arguments = "")).headline == "搜索了真实查询") { "Actual result query fallback missing" }
    val maximumQuery = "q".repeat(512)
    val bounded = search.copy(arguments = JSONObject().put("query", maximumQuery).toString(),
        result = receipt(searchData(maximumQuery)))
    check(webRow(bounded).facts.single().queries == listOf(maximumQuery))
    check(webRow(bounded).headline == "搜索了${"q".repeat(48)}…")
    check(webRow(search.copy(arguments = """{"query":"申请查询"}""")).headline == "搜索了申请查询")

    val browser = webCall("browser", "browser", """{"url":"https://example.com/requested"}""",
        result = receipt(browserData()))
    check(webRow(browser).kind == CompactActivityKind.BROWSER)
    check(webRow(browser).kind.glyph == "link")
    check(webRow(browser).headline == "已读取https://example.com/final")
    val browserReceipt = compactWebReceipts(webSnapshot(browser)).single()
    check(browserReceipt.sources == listOf(CompactWebSource("真实网页", "https://example.com/final")))
    check(browserReceipt.provider == null)
    check(browserReceipt.textTruncated && browserReceipt.linksTruncated)
    check(browserReceipt.retrievedAt == "2026-10-11T06:07:08Z")
    check(browserReceipt.sources.none { it.url.contains("nested") }) { "Page navigation links became sources" }
    check(webRow(browser.copy(arguments = "")).headline == "已读取https://example.com/final")
    val requestedFallback = browser.copy(result = receipt(browserData().put("final_url", JSONObject.NULL)))
    check(webRow(requestedFallback).headline == "已读取https://example.com/requested")
    check(compactWebReceipts(webSnapshot(browser.copy(result =
        receipt(browserData().put("text", "{}".repeat(500)))))).size == 1) {
        "Inert webpage braces were counted as structural nesting"
    }
    listOf(
        AssistantStreamEvent.ToolStatus.RUNNING to "正在读取",
        AssistantStreamEvent.ToolStatus.READY to "待读取",
        AssistantStreamEvent.ToolStatus.AWAITING_APPROVAL to "等待批准读取",
        AssistantStreamEvent.ToolStatus.FAILED to "读取失败：",
        AssistantStreamEvent.ToolStatus.DENIED to "读取已拒绝：",
        AssistantStreamEvent.ToolStatus.CANCELLED to "读取已取消：",
        AssistantStreamEvent.ToolStatus.INCOMPLETE to "读取未完成：",
    ).forEach { (status, prefix) ->
        val call = browser.copy(status = status, result = null)
        check(webRow(call).headline == "${prefix}https://example.com/requested")
        check(compactWebReceipts(webSnapshot(call)).isEmpty()) { "Non-success tool exposed sources" }
    }
    listOf(search, browser).forEach { successful ->
        val falseSuccess = successful.copy(result = """{"ok":false,"error":"web_timeout","details":{"attempts":[]}}""")
        check(webRow(falseSuccess).statuses.single() == CompactActivityStatus.FAILED)
        check(webRow(falseSuccess).notice == "web_timeout")
        check(!webRow(falseSuccess).headline.startsWith("搜索了") &&
            !webRow(falseSuccess).headline.startsWith("已读取"))
        check(compactWebReceipts(webSnapshot(falseSuccess)).isEmpty())
        val actualFailed = falseSuccess.copy(status = AssistantStreamEvent.ToolStatus.FAILED,
            error = "实际网络错误", errorCode = "web_network_error")
        check(webRow(actualFailed).notice == "实际网络错误 · web_network_error · web_timeout")
        val legacyFailure = successful.copy(status = AssistantStreamEvent.ToolStatus.FAILED)
        check(compactWebReceipts(webSnapshot(legacyFailure)).isEmpty()) { "Result overrode failed tool status" }
        listOf(null, "invalid", """{"ok":"true","data":{}}""", """{"ok":true,"data":{}}""",
            """{"ok":true,"data":[]}""", receipt(JSONObject()) + " trailing",
            "[".repeat(32) + "]".repeat(32), "x".repeat(131_073)).forEach { raw ->
            val incomplete = successful.copy(result = raw)
            check(webRow(incomplete).statuses.single() == CompactActivityStatus.INCOMPLETE)
            check(webRow(incomplete).notice == "工具结果缺少有效回执")
            check(compactWebReceipts(webSnapshot(incomplete)).isEmpty())
        }
    }
    val brokenBrowser = browser.copy(result = receipt(browserData().put("text", 123)))
    check(webRow(brokenBrowser).statuses.single() == CompactActivityStatus.INCOMPLETE)
    val noQuerySearch = search.copy(result = receipt(searchData().removeQuery()))
    check(webRow(noQuerySearch).statuses.single() == CompactActivityStatus.INCOMPLETE)
    check(compactWebReceipts(webSnapshot(search.copy(name = "fake.web_search"))).isEmpty())
    check(compactWebReceipts(webSnapshot(browser.copy(name = "browse_page"))).isEmpty())

    val hostileUrls = listOf("javascript:alert(1)", "http://example.com", "https://user:pass@example.com",
        "https://example.com:444/", "https://example.com/#fragment", "https://example.com/\n",
        "https://example.com/\u202e", "https://example.com/${"a".repeat(2048)}")
    hostileUrls.forEach { url ->
        val data = searchData().put("results", JSONArray().put(JSONObject().put("title", "拒绝").put("url", url)))
        check(compactWebReceipts(webSnapshot(search.copy(result = receipt(data)))).single().sources.isEmpty()) {
            "Unsafe source URL was displayed: $url"
        }
        val unsafe = browser.copy(arguments = JSONObject().put("url", url).toString(),
            result = receipt(browserData().put("final_url", url).put("url", url)))
        check(webRow(unsafe).statuses.single() == CompactActivityStatus.INCOMPLETE)
        check(!webRow(unsafe).headline.contains(url))
    }
    val duplicates = JSONArray()
    repeat(12) { index ->
        duplicates.put(JSONObject().put("title", if (index == 0) "bad\u202etitle" else "标题 $index")
            .put("url", "https://example.com/${index / 2}"))
    }
    val boundedSources = compactWebReceipts(webSnapshot(search.copy(result =
        receipt(searchData().put("results", duplicates).put("retrieved_at", 123))))).single()
    check(boundedSources.sources.size == 4)
    check(boundedSources.sources.first().title == boundedSources.sources.first().url)
    check(boundedSources.retrievedAt == null)
    val noTimestamp = compactWebReceipts(webSnapshot(browser.copy(result =
        receipt(browserData().put("retrieved_at", "invented time"))))).single()
    check(noTimestamp.retrievedAt == null)
    val plain = AssistantStreamReducer().apply {
        apply(AssistantStreamEvent.TextDelta(0, "model", "text", "搜索了新闻 已读取https://example.com/"))
    }.snapshot()
    check(compactActivitySummary(plain).rows.isEmpty() && compactWebReceipts(plain).isEmpty())
    val grouped = webSnapshot(browser, browser.copy(callId = "second",
        result = receipt(browserData().put("final_url", "https://example.com/second"))))
    check(compactActivitySummary(grouped).rows.single().headline ==
        "已读取https://example.com/final、https://example.com/second")
    val rounds = grouped.copy(activityOrder = grouped.activityOrder.mapIndexed { index, ref ->
        ref.copy(messageId = "round$index")
    })
    check(compactActivitySummary(rounds).rows.size == 2)
    println("PASS: official search/browser receipts; final URL and query fallback; true status/ok agreement; actual errors; incomplete malformed replies; source provenance/time/truncation; bounded safe URLs; inert model/page text; same-round grouping only.")
}

private fun JSONObject.removeQuery(): JSONObject {
    remove("query")
    return this
}
