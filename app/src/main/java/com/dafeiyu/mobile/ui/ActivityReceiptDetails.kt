package com.dafeiyu.mobile.ui

import com.dafeiyu.mobile.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal data class ActivityReceiptText(
    val text: String,
    val clipped: Boolean = false,
    val sanitized: Boolean = false,
    val redacted: Boolean = false,
)

internal data class ActivityReceiptSection(val label: String, val content: ActivityReceiptText)

internal data class ActivityReceiptSource(
    val title: ActivityReceiptText,
    val url: String?,
    val snippet: ActivityReceiptText? = null,
    val publishedAt: String? = null,
)

internal data class ActivityReceiptDetails(
    val id: String,
    val title: String,
    val status: String,
    val hasError: Boolean,
    val identifiers: List<Pair<String, ActivityReceiptText>>,
    val arguments: ActivityReceiptText?,
    val sections: List<ActivityReceiptSection>,
    val sources: List<ActivityReceiptSource> = emptyList(),
    val links: List<ActivityReceiptSource> = emptyList(),
    val metadata: List<String> = emptyList(),
)

private const val RECEIPT_TEXT_LIMIT = 16_384
private val sensitiveReceiptKey =
    Regex("(?i)^(?:authorization|proxy[_-]?authorization|(?:api|private)[_-]?key|" +
        "(?:(?:access|refresh|auth)[_-]?)?token|password|passwd|secret|client[_-]?secret|cookie|set[_-]?cookie)$")

/** Bounded display-only JSON parsing; braces inside strings are inert. */
internal fun activityReceiptEnvelope(raw: String?): JSONObject? {
    if (raw == null || raw.length !in 2..327_680) return null
    var quoted = false
    var escaped = false
    var depth = 0
    var nodes = 0
    for (character in raw) {
        if (quoted) {
            if (escaped) escaped = false
            else if (character == '\\') escaped = true
            else if (character == '"') quoted = false
        } else when (character) {
            '"' -> quoted = true
            '{', '[' -> {
                if (++depth > 16 || ++nodes > 4096) return null
            }
            '}', ']' -> if (--depth < 0) return null
            ',' -> if (++nodes > 4096) return null
        }
    }
    if (quoted || depth != 0) return null
    return runCatching {
        val parser = JSONTokener(raw)
        (parser.nextValue() as? JSONObject)?.takeIf { parser.nextClean() == '\u0000' }
    }.getOrNull()
}

/** Replace control/bidi characters and broken surrogates; copy uses this same public text. */
internal fun activityReceiptText(raw: String, maximum: Int = RECEIPT_TEXT_LIMIT): ActivityReceiptText {
    var end = minOf(raw.length, maximum.coerceAtLeast(0))
    if (end > 0 && end < raw.length && raw[end - 1].isHighSurrogate() && raw[end].isLowSurrogate()) end--
    var sanitized = false
    val text = buildString(end) {
        for (index in 0 until end) {
            val character = raw[index]
            val invalid = (character.isISOControl() && character !in "\t\r\n") ||
                character in '\u202a'..'\u202e' || character in '\u2066'..'\u2069' ||
                character in setOf('\u061c', '\u200e', '\u200f') ||
                (character.isHighSurrogate() && (index + 1 >= end || !raw[index + 1].isLowSurrogate())) ||
                (character.isLowSurrogate() && (index == 0 || !raw[index - 1].isHighSurrogate()))
            append(if (invalid) '\uFFFD' else character)
            sanitized = sanitized || invalid
        }
    }
    return ActivityReceiptText(text, end < raw.length, sanitized)
}

private fun receiptPayload(raw: String, envelope: JSONObject? = activityReceiptEnvelope(raw)): ActivityReceiptText {
    val json = envelope ?: return activityReceiptText(raw)
    var redacted = false
    fun publicValue(value: Any): Any = when (value) {
        is JSONObject -> JSONObject().also { target ->
            value.keys().forEach { key ->
                target.put(key, if (sensitiveReceiptKey.matches(key)) {
                    redacted = true
                    "[已隐藏]"
                } else publicValue(value.get(key)))
            }
        }
        is JSONArray -> JSONArray().also { target ->
            for (index in 0 until value.length()) target.put(publicValue(value.get(index)))
        }
        else -> value
    }
    val publicJson = publicValue(json) as JSONObject
    return activityReceiptText(publicJson.toString(2)).copy(redacted = redacted)
}

private fun receiptDate(value: Any?): String? {
    val text = value as? String ?: return null
    if (text.length > 128 || activityReceiptText(text, 128).sanitized) return null
    return text.takeIf {
        runCatching { Instant.parse(it) }.isSuccess ||
            runCatching { OffsetDateTime.parse(it) }.isSuccess ||
            runCatching { LocalDate.parse(it) }.isSuccess ||
            runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME) }.isSuccess
    }
}

private fun receiptSection(label: String, text: String?): ActivityReceiptSection? =
    text?.takeIf { it.isNotEmpty() }?.let { ActivityReceiptSection(label, activityReceiptText(it)) }

private fun receiptSource(
    title: String?,
    url: Any?,
    snippet: String? = null,
    date: Any? = null,
): ActivityReceiptSource = ActivityReceiptSource(
    activityReceiptText(title?.takeIf { it.isNotBlank() } ?: "来源", 512),
    compactWebUrl(url), snippet?.let { activityReceiptText(it) }, receiptDate(date),
)

private fun toolReceipt(tool: AssistantToolCallSnapshot): ActivityReceiptDetails {
    val web = tool.name in setOf("web_search", "browser")
    val envelope = if (web) compactWebEnvelope(tool) else activityReceiptEnvelope(tool.result)
    val status = compactReceiptStatus(tool, envelope)
    // Only a validated successful web receipt supplies provenance, never page body or model prose.
    val data = if (web) {
        if (status == CompactActivityStatus.COMPLETED) compactWebData(tool, envelope) else null
    } else envelope?.takeIf { it.opt("ok") == true }?.optJSONObject("data")
    val sources = when (tool.name) {
        "web_search" -> data?.optJSONArray("results")?.let { results ->
            (0 until minOf(results.length(), 8)).mapNotNull { index ->
                results.optJSONObject(index)?.let { source ->
                    receiptSource(source.opt("title") as? String, source.opt("url"),
                        source.opt("snippet") as? String, source.opt("published_at"))
                }
            }
        }.orEmpty()
        "browser" -> listOfNotNull(data?.let {
            receiptSource(it.opt("title") as? String, it.opt("final_url") ?: it.opt("url"))
                .let { source -> if (source.url == null) source.copy(url = compactWebUrl(it.opt("url"))) else source }
        })
        else -> emptyList()
    }
    val links = if (tool.name == "browser") data?.optJSONArray("links")?.let { items ->
        (0 until minOf(items.length(), 24)).mapNotNull { index ->
            items.optJSONObject(index)?.let { receiptSource(it.opt("text") as? String, it.opt("url")) }
        }
    }.orEmpty() else emptyList()
    val sections = buildList {
        if (tool.name == "browser" && data != null) {
            receiptSection("网页正文", data.opt("text") as? String)?.let(::add)
            if (data.opt("text") == "") add(ActivityReceiptSection("网页正文", activityReceiptText("正文为空")))
        } else if (tool.name != "web_search" || data == null) {
            tool.result?.let {
                add(ActivityReceiptSection("工具结果",
                    receiptPayload(it, envelope ?: if (web) activityReceiptEnvelope(it) else null)))
            }
        }
        receiptSection("错误", tool.error)?.let(::add)
        receiptSection("错误代码", tool.errorCode)?.let(::add)
        receiptSection("回执错误", envelope?.opt("error") as? String)?.let(::add)
        receiptSection("批准说明", tool.approvalReason)?.let(::add)
        if (tool.result == null) add(ActivityReceiptSection("工具结果", activityReceiptText(
            if (status.active) "尚未收到工具结果" else "未提供工具结果",
        )))
        if (web && status == CompactActivityStatus.INCOMPLETE &&
            tool.status == AssistantStreamEvent.ToolStatus.SUCCEEDED) {
            add(ActivityReceiptSection("回执说明", activityReceiptText("工具结果缺少有效回执")))
        }
    }
    val metadata = buildList {
        (data?.opt("source") as? String)?.let { add("来源服务 · ${activityReceiptText(it, 128).text}") }
        compactWebUrl(data?.opt("source_url"))?.let { add("服务 URL · $it") }
        receiptDate(data?.opt("retrieved_at"))?.let { add("读取时间 · $it") }
        if (data?.opt("text_truncated") == true) add("来源正文已截断")
        if (data?.opt("links_truncated") == true) add("来源链接列表已截断")
        if (data?.opt("result_truncated") == true) add("来源结果已截断")
        if (data?.opt("truncated") == true) add("来源列表已截断")
        if ((data?.optJSONArray("results")?.length() ?: 0) > 8) add("仅显示前 8 项来源")
        if ((data?.optJSONArray("links")?.length() ?: 0) > 24) add("仅显示前 24 项网页内链接")
        if (tool.name == "web_search" && data?.optJSONArray("results")?.length() == 0) add("未返回搜索结果")
        if (status != CompactActivityStatus.COMPLETED && tool.result != null) add("已收到的结果不代表调用成功")
    }
    return ActivityReceiptDetails("tool:${tool.callId}", activityReceiptText(tool.name ?: "工具", 128).text,
        status.label, status in setOf(CompactActivityStatus.FAILED, CompactActivityStatus.DENIED,
            CompactActivityStatus.INCOMPLETE),
        listOf("调用 ID" to activityReceiptText(tool.callId, 512)),
        tool.arguments.takeIf { it.isNotEmpty() }?.let(::receiptPayload), sections,
        sources, links, metadata)
}

private fun agentReceipt(agent: AssistantSubagentSnapshot): ActivityReceiptDetails {
    val status = when (agent.status) {
        AssistantStreamEvent.SubagentStatus.PENDING -> "准备中"
        AssistantStreamEvent.SubagentStatus.STARTING -> "启动中"
        AssistantStreamEvent.SubagentStatus.RUNNING -> "运行中"
        AssistantStreamEvent.SubagentStatus.COMPLETED -> "已完成"
        AssistantStreamEvent.SubagentStatus.FAILED -> "失败"
        AssistantStreamEvent.SubagentStatus.CANCELLED -> "已取消等待，未确认子任务停止"
        AssistantStreamEvent.SubagentStatus.INCOMPLETE -> "未完成"
    }
    return ActivityReceiptDetails("agent:${agent.agentId}",
        activityReceiptText(agent.name ?: "子智能体", 128).text, status,
        agent.status in setOf(AssistantStreamEvent.SubagentStatus.FAILED, AssistantStreamEvent.SubagentStatus.INCOMPLETE),
        listOfNotNull("子智能体 ID" to activityReceiptText(agent.agentId, 512),
            agent.parentCallId?.let { "父调用 ID" to activityReceiptText(it, 512) }),
        null, listOfNotNull(receiptSection("公开输出", agent.output),
            receiptSection("状态说明", agent.detail), receiptSection("错误", agent.error)))
}

private fun searchReceipt(search: AssistantSearchSnapshot): ActivityReceiptDetails {
    val status = when (search.status) {
        AssistantStreamEvent.SearchStatus.PENDING -> "准备中"
        AssistantStreamEvent.SearchStatus.RUNNING -> "运行中"
        AssistantStreamEvent.SearchStatus.COMPLETED -> "已完成"
        AssistantStreamEvent.SearchStatus.FAILED -> "失败"
        AssistantStreamEvent.SearchStatus.CANCELLED -> "已取消"
        AssistantStreamEvent.SearchStatus.INCOMPLETE -> "未完成"
    }
    return ActivityReceiptDetails("search:${search.searchId}", "搜索", status,
        search.status in setOf(AssistantStreamEvent.SearchStatus.FAILED, AssistantStreamEvent.SearchStatus.INCOMPLETE),
        listOf("搜索 ID" to activityReceiptText(search.searchId, 512)), search.query?.let(::activityReceiptText),
        listOfNotNull(receiptSection("错误", search.error)),
        search.results.take(24).map { receiptSource(it.title, it.url, it.snippet, it.publishedAt) },
        metadata = buildList {
            if (search.results.size > 24) add("仅显示前 24 项来源")
            if (search.status != AssistantStreamEvent.SearchStatus.COMPLETED && search.results.isNotEmpty()) {
                add("部分搜索结果，搜索未完成")
            }
            if (search.status == AssistantStreamEvent.SearchStatus.COMPLETED && search.results.isEmpty()) {
                add("未返回搜索结果")
            }
        })
}

/** A collapsed child label reads only identifiers/names; it never parses a result payload. */
internal fun activityReceiptTitle(
    snapshot: AssistantStreamSnapshot,
    reference: AssistantActivityReference,
): String? {
    val title = when (reference.kind) {
        AssistantActivityKind.TOOL -> snapshot.toolCalls.firstOrNull { it.callId == reference.id }
            ?.let { it.name ?: "工具" }
        AssistantActivityKind.SUBAGENT -> snapshot.subagents.firstOrNull { it.agentId == reference.id }
            ?.let { it.name ?: "子智能体" }
        AssistantActivityKind.SEARCH -> snapshot.searches.firstOrNull { it.searchId == reference.id }?.let { "搜索" }
        else -> null
    }
    return title?.let { activityReceiptText(it, 128).text }
}

/** Only real row references are projected, after the user's explicit disclosure action. No IO. */
internal fun activityReceiptDetails(
    snapshot: AssistantStreamSnapshot,
    references: List<AssistantActivityReference>,
    openedByUser: Boolean,
): List<ActivityReceiptDetails> {
    if (!openedByUser) return emptyList()
    return references.distinctBy { it.kind to it.id }.mapNotNull { reference ->
        when (reference.kind) {
            AssistantActivityKind.TOOL -> snapshot.toolCalls.firstOrNull { it.callId == reference.id }?.let(::toolReceipt)
            AssistantActivityKind.SUBAGENT -> snapshot.subagents.firstOrNull { it.agentId == reference.id }?.let(::agentReceipt)
            AssistantActivityKind.SEARCH -> snapshot.searches.firstOrNull { it.searchId == reference.id }?.let(::searchReceipt)
            else -> null
        }
    }
}

internal fun activityReceiptCopyText(receipt: ActivityReceiptDetails): String = buildString {
    appendLine("${receipt.title} · ${receipt.status}")
    receipt.identifiers.forEach { (label, value) -> appendLine("$label · ${value.text}") }
    fun section(label: String, value: ActivityReceiptText) {
        appendLine("\n$label")
        appendLine(value.text)
        if (value.clipped) appendLine("显示正文已截断，复制仅包含显示部分")
        if (value.sanitized) appendLine("控制字符已替换")
        if (value.redacted) appendLine("敏感字段已隐藏")
    }
    receipt.arguments?.let { section("参数", it) }
    receipt.sections.forEach { section(it.label, it.content) }
    fun sources(label: String, values: List<ActivityReceiptSource>) {
        values.forEach {
            section(label, it.title)
            appendLine(it.url ?: "未提供可打开的安全 URL")
            it.publishedAt?.let { date -> appendLine("发布时间 · $date") }
            it.snippet?.let { snippet -> section("摘要", snippet) }
        }
    }
    sources("来源", receipt.sources)
    sources("网页内链接", receipt.links)
    receipt.metadata.forEach(::appendLine)
}.trimEnd()
