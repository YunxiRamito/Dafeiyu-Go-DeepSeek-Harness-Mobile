package com.dafeiyu.mobile.ui

import com.dafeiyu.mobile.data.AssistantActivityKind
import com.dafeiyu.mobile.data.AssistantActivityReference
import com.dafeiyu.mobile.data.AssistantContentBlockSnapshot
import com.dafeiyu.mobile.data.AssistantMessageSnapshot
import com.dafeiyu.mobile.data.AssistantMessageStatus
import com.dafeiyu.mobile.data.AssistantRunStatus
import com.dafeiyu.mobile.data.AssistantStreamEvent
import com.dafeiyu.mobile.data.AssistantStreamSnapshot
import com.dafeiyu.mobile.data.AssistantToolCallSnapshot
import java.net.URI
import java.time.Instant
import java.util.Locale
import org.json.JSONObject
import org.json.JSONTokener

internal enum class CompactActivityAction {
    WRITE_FILE, READ_FILE, EDIT_FILE, FILE_OPERATION, VIEW_IMAGE, RUN_COMMAND,
    CALL_SKILL, SKILL_OPERATION, CREATE_AGENT, SEARCH, BROWSE, TOOL,
}

internal data class CompactActivityFact(
    val action: CompactActivityAction,
    val succeeded: Boolean,
    val name: String? = null,
    val sent: Boolean = false,
    val received: Boolean = false,
    val queries: List<String> = emptyList(),
)

internal enum class CompactActivityKind(val label: String, val glyph: String) {
    FILE("文件", "document"),
    IMAGE("图片", "photo"),
    COMMAND("命令", "terminal"),
    SKILL("技能", "model"),
    AGENT("子智能体", "groupchat"),
    SEARCH("搜索", "search"),
    BROWSER("浏览", "link"),
    TOOL("工具", "settings"),
}

internal enum class CompactActivityStatus(val label: String, val active: Boolean = false) {
    RUNNING("运行中", true),
    WAITING("等待批准", true),
    QUEUED("准备中", true),
    FAILED("失败"),
    DENIED("已拒绝"),
    INCOMPLETE("未完成"),
    CANCELLED("已取消"),
    WAIT_CANCELLED("已取消等待"),
    COMPLETED("已完成"),
}

internal data class CompactActivityRow(
    val id: String,
    val kind: CompactActivityKind,
    val round: String,
    val count: Int,
    val names: List<String>,
    val statuses: List<CompactActivityStatus>,
    val notice: String?,
    val facts: List<CompactActivityFact>,
    val references: List<AssistantActivityReference> = emptyList(),
) {
    val leadingGlyphCount: Int
        get() = if (kind == CompactActivityKind.AGENT) {
            facts.count { it.action == CompactActivityAction.CREATE_AGENT && it.succeeded }.coerceIn(1, 3)
        } else 1
    val headline: String
        get() {
            if (kind in setOf(CompactActivityKind.SEARCH, CompactActivityKind.BROWSER)) {
                val browser = kind == CompactActivityKind.BROWSER
                return facts.zip(statuses).groupBy { it.second }.map { (status, entries) ->
                    val queries = if (browser) entries.mapNotNull { it.first.name }.distinct()
                        else entries.flatMap { it.first.queries }.distinct()
                    val action = when (status) {
                        CompactActivityStatus.COMPLETED -> if (browser) "已读取" else "搜索了"
                        CompactActivityStatus.RUNNING -> if (browser) "正在读取" else "正在搜索"
                        CompactActivityStatus.FAILED -> if (browser) "读取失败：" else "搜索失败："
                        CompactActivityStatus.DENIED -> if (browser) "读取已拒绝：" else "搜索已拒绝："
                        CompactActivityStatus.CANCELLED, CompactActivityStatus.WAIT_CANCELLED ->
                            if (browser) "读取已取消：" else "搜索已取消："
                        CompactActivityStatus.INCOMPLETE -> if (browser) "读取未完成：" else "搜索未完成："
                        CompactActivityStatus.WAITING -> if (browser) "等待批准读取" else "等待批准搜索"
                        CompactActivityStatus.QUEUED -> if (browser) "待读取" else "待搜索"
                    }
                    if (queries.isEmpty()) "$action（${if (browser) "网址" else "查询"}未提供）" else {
                        val preview = queries.take(3).joinToString("、") {
                            compactPreview(it, if (browser) 96 else 48)
                        }
                        "$action$preview" + if (queries.size > 3) {
                            " 等 ${queries.size} 项${if (browser) "网页" else "查询"}"
                        } else ""
                    }
                }.joinToString(" · ")
            }
            val successful = facts.filter { it.succeeded }
            val actions = successful.groupBy { it.action }.map { (action, entries) ->
                val number = entries.size
                when (action) {
                    CompactActivityAction.WRITE_FILE -> "编写了 $number 个文件"
                    CompactActivityAction.READ_FILE -> "查看了 $number 个文件"
                    CompactActivityAction.EDIT_FILE -> "完成了 $number 项文件修改"
                    CompactActivityAction.FILE_OPERATION -> "完成了 $number 项文件操作"
                    CompactActivityAction.VIEW_IMAGE -> "查看了 $number 个图片"
                    CompactActivityAction.RUN_COMMAND -> "运行了 $number 个命令"
                    CompactActivityAction.CALL_SKILL -> {
                        val skills = entries.mapNotNull { it.name }.distinct()
                        "已调用 Skill $number 次" + if (skills.isEmpty()) "" else "：${skills.joinToString("、")}"
                    }
                    CompactActivityAction.SKILL_OPERATION -> "完成了 $number 项 Skill 工具操作"
                    CompactActivityAction.CREATE_AGENT -> "已建立 $number 个子智能体"
                    CompactActivityAction.SEARCH -> "完成了 $number 次搜索"
                    CompactActivityAction.BROWSE -> "读取了 $number 个网页"
                    CompactActivityAction.TOOL -> "完成了 $number 项工具操作"
                }
            }.toMutableList()
            val sent = facts.count { it.sent }
            val received = facts.count { it.received }
            if (sent > 0) actions += "已发送 $sent 条消息"
            if (received > 0) actions += "收到 $received 条通知"
            val remaining = facts.count { !it.succeeded }
            if (remaining > 0 || actions.isEmpty()) {
                val operation = when (kind) {
                    CompactActivityKind.FILE -> "文件操作"
                    CompactActivityKind.IMAGE -> "图片查看"
                    CompactActivityKind.COMMAND -> "命令执行"
                    CompactActivityKind.SKILL -> "Skill 工具操作"
                    CompactActivityKind.AGENT -> "子智能体创建"
                    CompactActivityKind.SEARCH -> "搜索"
                    CompactActivityKind.BROWSER -> "网页读取"
                    CompactActivityKind.TOOL -> "工具操作"
                }
                actions += "${if (actions.isEmpty()) "" else "另有 "}$operation ${if (remaining > 0) remaining else count} 项"
            }
            return actions.joinToString(" · ")
        }
    val statusLabel: String
        get() = statuses.groupingBy { it }.eachCount().entries
            .sortedBy { it.key.ordinal }
            .joinToString(" · ") { (status, count) ->
                if (statuses.size == 1) status.label else "$count ${status.label}"
            }
    val hasError: Boolean
        get() = statuses.any { it in setOf(
            CompactActivityStatus.FAILED, CompactActivityStatus.DENIED, CompactActivityStatus.INCOMPLETE,
        ) }
}

internal data class CompactActivitySummary(
    val rows: List<CompactActivityRow>,
    val isRunning: Boolean,
    val timeline: List<CompactActivityEntry> = emptyList(),
) {
    val count: Int get() = rows.sumOf { it.count }
    val agentCount: Int get() = rows.filter { it.kind == CompactActivityKind.AGENT }.sumOf { it.count }
    val hasError: Boolean get() = rows.any { it.hasError }
}

internal sealed interface CompactActivityEntry {
    val id: String
    data class Reasoning(
        val reference: AssistantActivityReference,
        val block: AssistantContentBlockSnapshot.Reasoning,
    ) : CompactActivityEntry {
        override val id: String
            get() = "reasoning-${reference.messageId}-${block.kind}-${block.blockId}"
    }
    data class Activity(val row: CompactActivityRow) : CompactActivityEntry {
        override val id: String get() = "row-${row.id}"
    }
}

private data class CompactActivityGroup(
    val round: String,
    val boundary: Int,
    val kind: CompactActivityKind,
)

private sealed interface CompactActivityPosition {
    data class Group(val group: CompactActivityGroup) : CompactActivityPosition
    data class Thinking(val entry: CompactActivityEntry.Reasoning) : CompactActivityPosition
}

/** Explicit first-seen order wins. Missing legacy blocks use only their known message round. */
internal fun compactActivityOrder(snapshot: AssistantStreamSnapshot): List<AssistantActivityReference> {
    val content = snapshot.messages.flatMap { message ->
        message.blocks.map { block ->
            val reasoning = block as? AssistantContentBlockSnapshot.Reasoning
            AssistantActivityReference(
                if (reasoning == null) AssistantActivityKind.TEXT else AssistantActivityKind.REASONING,
                block.blockId, message.messageId, reasoning?.kind,
            )
        }
    }
    val contentByReference = content.associateBy { it }
    val contentByBlock = content.groupBy { it.kind to it.id }
    val available = snapshot.toolCalls.map { AssistantActivityReference(AssistantActivityKind.TOOL, it.callId) } +
        snapshot.subagents.map { AssistantActivityReference(AssistantActivityKind.SUBAGENT, it.agentId) } +
        snapshot.searches.map { AssistantActivityReference(AssistantActivityKind.SEARCH, it.searchId) }
    val activityKeys = available.map { it.kind to it.id }.toSet()
    val rounds = snapshot.activityOrder.filter { it.messageId != null &&
        (it.kind to it.id) in activityKeys }.associate { (it.kind to it.id) to it.messageId }
    val explicit = snapshot.activityOrder.mapNotNull { reference ->
        when (reference.kind) {
            AssistantActivityKind.TEXT, AssistantActivityKind.REASONING -> contentByReference[reference]
                ?: contentByBlock[reference.kind to reference.id]?.singleOrNull {
                    (reference.messageId == null || it.messageId == reference.messageId) &&
                    (reference.reasoningKind == null || it.reasoningKind == reference.reasoningKind) }
            AssistantActivityKind.CITATION -> reference
            else -> reference.takeIf { (it.kind to it.id) in activityKeys }
                ?.copy(messageId = rounds[reference.kind to reference.id] ?: reference.messageId)
        }
    }.distinct()
    val explicitContent = explicit.toHashSet()
    val contentIndices = content.withIndex().associate { it.value to it.index }
    val missing = content.filter { it !in explicitContent }.groupBy { it.messageId }.toMutableMap()
    val result = mutableListOf<AssistantActivityReference>()
    explicit.forEach { reference ->
        reference.messageId?.let { message ->
            val pending = missing[message].orEmpty()
            val contentIndex = contentIndices[reference]
            if (contentIndex == null) {
                missing.remove(message)?.let(result::addAll)
            } else {
                val (before, after) = pending.partition { contentIndices.getValue(it) < contentIndex }
                result += before
                if (after.isEmpty()) missing.remove(message) else missing[message] = after
            }
        }
        result += reference
    }
    result += missing.values.flatten()
    result += available.map { it.copy(messageId = rounds[it.kind to it.id]) }
    return result.distinct()
}

private fun compactToolName(tool: AssistantToolCallSnapshot): String =
    tool.name.orEmpty().lowercase(Locale.ROOT).substringAfterLast('.').substringAfterLast('/')

internal fun compactActivityKind(tool: AssistantToolCallSnapshot): CompactActivityKind {
    val name = compactToolName(tool)
    val words = name.split('_', '-')
    return when {
        tool.kind == AssistantStreamEvent.ToolKind.COMMAND ||
            name in setOf("shell", "bash", "run_command", "exec_command") -> CompactActivityKind.COMMAND
        "skill" in words || "skills" in words -> CompactActivityKind.SKILL
        "image" in words || "images" in words || name == "screenshot" -> CompactActivityKind.IMAGE
        name == "create_subagent" || name == "spawn_agent" -> CompactActivityKind.AGENT
        name == "browser" -> CompactActivityKind.BROWSER
        "file" in words || "files" in words || name in setOf("apply_patch", "read", "write", "edit") ->
            CompactActivityKind.FILE
        "search" in words -> CompactActivityKind.SEARCH
        else -> CompactActivityKind.TOOL
    }
}

/** Compact labels read only small metadata; full payloads belong to explicit receipt disclosures. */
private fun compactArguments(raw: String): JSONObject? {
    if (raw.length !in 2..8192 || raw.count { it == '{' || it == '[' } > 32) return null
    return runCatching { JSONObject(raw) }.getOrNull()
}

private fun compactDisplayName(value: Any?, maximum: Int = 128): String? {
    val raw = value as? String ?: return null
    if (raw.length > maximum) return null
    val text = raw.trim().takeIf { it.isNotEmpty() } ?: return null
    return text.takeIf { valueText ->
        valueText.none { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' } &&
            runCatching { Charsets.UTF_8.newEncoder().canEncode(valueText) }.getOrDefault(false)
    }
}

private fun compactPreview(text: String, maximum: Int): String {
    if (text.length <= maximum) return text
    val end = if (text[maximum - 1].isHighSurrogate()) maximum - 1 else maximum
    return text.take(end) + "…"
}

private fun compactSearchQueries(arguments: JSONObject?, maximum: Int = 128): List<String> {
    if (arguments == null) return emptyList()
    val direct = listOf("query", "q").firstNotNullOfOrNull { compactDisplayName(arguments.opt(it), maximum) }
    if (direct != null) return listOf(direct)
    val queries = arguments.optJSONArray("queries") ?: arguments.optJSONArray("search_query")
        ?: return emptyList()
    if (queries.length() > 16) return emptyList()
    return (0 until queries.length()).mapNotNull { index ->
        val item = queries.opt(index)
        compactDisplayName(item, maximum) ?: (item as? JSONObject)?.let {
            compactDisplayName(it.opt("query"), maximum) ?: compactDisplayName(it.opt("q"), maximum)
        }
    }.distinct()
}

internal data class CompactWebSource(val title: String, val url: String)

internal data class CompactWebReceipt(
    val callId: String,
    val sources: List<CompactWebSource>,
    val provider: String?,
    val providerUrl: String?,
    val retrievedAt: String?,
    val textTruncated: Boolean,
    val linksTruncated: Boolean,
)

private fun compactWebTool(tool: AssistantToolCallSnapshot): Boolean =
    tool.name in setOf("web_search", "browser")

/** Parse bounded official display receipts only; never parse or execute webpage text as UI. */
internal fun compactWebEnvelope(tool: AssistantToolCallSnapshot): JSONObject? {
    if (!compactWebTool(tool)) return null
    val raw = tool.result ?: return null
    if (raw.length !in 2..131_072) return null
    var quoted = false
    var escaped = false
    var depth = 0
    var containers = 0
    // Bound structural nesting without treating webpage text containing braces as JSON structure.
    for (character in raw) {
        if (quoted) {
            if (escaped) escaped = false
            else if (character == '\\') escaped = true
            else if (character == '"') quoted = false
        } else when (character) {
            '"' -> quoted = true
            '{', '[' -> {
                depth++
                containers++
                if (depth > 16 || containers > 256) return null
            }
            '}', ']' -> depth--
        }
    }
    return runCatching {
        val parser = JSONTokener(raw)
        val json = parser.nextValue() as? JSONObject ?: return@runCatching null
        json.takeIf { parser.nextClean() == '\u0000' }
    }.getOrNull()
}

internal fun compactWebData(
    tool: AssistantToolCallSnapshot,
    envelope: JSONObject? = compactWebEnvelope(tool),
): JSONObject? =
    envelope?.takeIf { it.opt("ok") == true }?.optJSONObject("data")?.takeIf { data ->
        when (tool.name) {
            "web_search" -> compactDisplayName(data.opt("query"), 512) != null &&
                data.optJSONArray("results") != null
            "browser" -> (compactWebUrl(data.opt("final_url")) ?: compactWebUrl(data.opt("url"))) != null &&
                data.opt("text") is String
            else -> false
        }
    }

internal fun compactWebUrl(value: Any?): String? {
    if (value !is String || value.any { it.isWhitespace() || it.isISOControl() } || '\\' in value) return null
    val text = compactDisplayName(value, 2048) ?: return null
    return text.takeIf {
        runCatching {
            val uri = URI(it)
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                uri.fragment == null && uri.port in setOf(-1, 443)
        }.getOrDefault(false)
    }
}

private fun compactWebError(tool: AssistantToolCallSnapshot, envelope: JSONObject?): String? =
    listOfNotNull(
        compactDisplayName(tool.error, 512),
        compactDisplayName(tool.errorCode, 512),
        compactDisplayName(envelope?.opt("error"), 512),
    ).distinct().takeIf { it.isNotEmpty() }?.joinToString(" · ")

internal fun compactReceiptStatus(
    tool: AssistantToolCallSnapshot,
    envelope: JSONObject? = if (compactWebTool(tool)) compactWebEnvelope(tool) else activityReceiptEnvelope(tool.result),
): CompactActivityStatus {
    val original = compactToolStatus(tool.status)
    if (original != CompactActivityStatus.COMPLETED) return original
    if (!compactWebTool(tool)) {
        return if (envelope?.opt("ok") == false) {
            CompactActivityStatus.FAILED
        } else original
    }
    if (envelope?.opt("ok") == false) return CompactActivityStatus.FAILED
    return if (compactWebData(tool, envelope) != null) original else CompactActivityStatus.INCOMPLETE
}

internal fun compactWebReceipts(snapshot: AssistantStreamSnapshot): List<CompactWebReceipt> =
    snapshot.toolCalls.mapNotNull { tool ->
        if (!compactWebTool(tool)) return@mapNotNull null
        val envelope = compactWebEnvelope(tool)
        if (compactReceiptStatus(tool, envelope) != CompactActivityStatus.COMPLETED) {
            return@mapNotNull null
        }
        val data = compactWebData(tool, envelope) ?: return@mapNotNull null
        val sources = if (tool.name == "web_search") {
            data.optJSONArray("results")?.let { results ->
                (0 until minOf(results.length(), 8)).mapNotNull source@ { index ->
                    val result = results.optJSONObject(index) ?: return@source null
                    val url = compactWebUrl(result.opt("url")) ?: return@source null
                    CompactWebSource(compactDisplayName(result.opt("title"), 256) ?: url, url)
                }
            }.orEmpty()
        } else {
            val url = compactWebUrl(data.opt("final_url")) ?: compactWebUrl(data.opt("url"))
            listOfNotNull(url?.let { CompactWebSource(compactDisplayName(data.opt("title"), 256) ?: it, it) })
        }
        val timestamp = compactDisplayName(data.opt("retrieved_at"), 64)?.takeIf {
            runCatching { Instant.parse(it) }.isSuccess
        }
        CompactWebReceipt(tool.callId, sources.distinctBy { it.url },
            compactDisplayName(data.opt("source")), compactWebUrl(data.opt("source_url")),
            timestamp, data.opt("text_truncated") == true, data.opt("links_truncated") == true)
    }

private fun compactToolFact(
    tool: AssistantToolCallSnapshot,
    kind: CompactActivityKind,
    status: CompactActivityStatus,
    data: JSONObject?,
): CompactActivityFact {
    val name = compactToolName(tool)
    val words = name.split('_', '-')
    val arguments = compactArguments(tool.arguments)
    val action = when (kind) {
        CompactActivityKind.FILE -> when {
            name == "apply_patch" || "edit" in words || "patch" in words -> CompactActivityAction.EDIT_FILE
            "write" in words || name == "write" -> CompactActivityAction.WRITE_FILE
            "read" in words || name == "read" -> CompactActivityAction.READ_FILE
            else -> CompactActivityAction.FILE_OPERATION
        }
        CompactActivityKind.IMAGE -> if ("view" in words || name == "open_image") {
            CompactActivityAction.VIEW_IMAGE
        } else CompactActivityAction.TOOL
        CompactActivityKind.COMMAND -> CompactActivityAction.RUN_COMMAND
        CompactActivityKind.SKILL -> if (name == "skill" ||
            words.any { it in setOf("use", "invoke", "call", "run", "execute") }) {
            CompactActivityAction.CALL_SKILL
        } else CompactActivityAction.SKILL_OPERATION
        CompactActivityKind.AGENT -> CompactActivityAction.CREATE_AGENT
        CompactActivityKind.SEARCH -> CompactActivityAction.SEARCH
        CompactActivityKind.BROWSER -> CompactActivityAction.BROWSE
        CompactActivityKind.TOOL -> CompactActivityAction.TOOL
    }
    val keys = when (kind) {
        CompactActivityKind.SKILL -> listOf("skill_name", "skill", "name")
        CompactActivityKind.FILE, CompactActivityKind.IMAGE -> listOf("filename", "path", "file_path")
        CompactActivityKind.AGENT -> listOf("title", "name")
        else -> emptyList()
    }
    val queries = if (kind == CompactActivityKind.SEARCH) {
        compactSearchQueries(arguments, if (tool.name == "web_search") 512 else 128).ifEmpty {
            listOfNotNull(compactDisplayName(data?.opt("query"), 512))
        }
    } else emptyList()
    val displayName = if (kind == CompactActivityKind.BROWSER) {
        compactWebUrl(data?.opt("final_url")) ?: compactWebUrl(data?.opt("url"))
            ?: compactWebUrl(arguments?.opt("url"))
    } else queries.firstOrNull() ?: keys.firstNotNullOfOrNull { compactDisplayName(arguments?.opt(it)) }
    return CompactActivityFact(action, status == CompactActivityStatus.COMPLETED, displayName,
        queries = queries)
}

internal fun compactActivityHistory(snapshot: AssistantStreamSnapshot): List<AssistantMessageSnapshot> =
    snapshot.messages.dropLast(1).filter { message ->
        message.role == "assistant" && message.blocks.any {
            it is AssistantContentBlockSnapshot.Text && it.text.isNotBlank()
        }
    }

internal fun compactActivityTitle(snapshot: AssistantStreamSnapshot, summary: CompactActivitySummary): String {
    val terminal = snapshot.isFinal || snapshot.runStatus?.let { it != AssistantRunStatus.RUNNING } == true
    val title = when {
        snapshot.runStatus == AssistantRunStatus.CANCELLED -> "已停止回复"
        snapshot.runStatus == AssistantRunStatus.INCOMPLETE -> "未完成"
        snapshot.runStatus == AssistantRunStatus.FAILED -> "任务异常"
        !terminal -> if (summary.isRunning) "正在处理" else "任务过程"
        summary.hasError || snapshot.messages.any { it.status == AssistantMessageStatus.FAILED } -> "任务异常"
        snapshot.messages.any { it.status == AssistantMessageStatus.INCOMPLETE } -> "未完成"
        snapshot.messages.any { it.status == AssistantMessageStatus.CANCELLED } -> "已停止回复"
        summary.rows.any { row -> row.statuses.any { it.active } } -> "本轮已结束"
        summary.rows.any { row -> row.statuses.any {
            it == CompactActivityStatus.CANCELLED || it == CompactActivityStatus.WAIT_CANCELLED
        } } -> "已结束"
        else -> "已完成"
    }
    val elapsed = snapshot.runElapsedMillis?.takeIf { it >= 0 && terminal } ?: return title
    val seconds = elapsed / 1000
    val duration = when {
        seconds >= 3600 -> "${seconds / 3600} 小时 ${seconds % 3600 / 60} 分"
        seconds >= 60 -> "${seconds / 60} 分 ${seconds % 60} 秒"
        elapsed % 1000 == 0L -> "$seconds 秒"
        else -> "$seconds.${elapsed % 1000 / 100} 秒"
    }
    return "$title · 用时 $duration"
}

private fun compactToolStatus(status: AssistantStreamEvent.ToolStatus): CompactActivityStatus = when (status) {
    AssistantStreamEvent.ToolStatus.PENDING, AssistantStreamEvent.ToolStatus.STREAMING_ARGUMENTS,
    AssistantStreamEvent.ToolStatus.READY, AssistantStreamEvent.ToolStatus.APPROVED -> CompactActivityStatus.QUEUED
    AssistantStreamEvent.ToolStatus.AWAITING_APPROVAL -> CompactActivityStatus.WAITING
    AssistantStreamEvent.ToolStatus.RUNNING -> CompactActivityStatus.RUNNING
    AssistantStreamEvent.ToolStatus.SUCCEEDED -> CompactActivityStatus.COMPLETED
    AssistantStreamEvent.ToolStatus.FAILED -> CompactActivityStatus.FAILED
    AssistantStreamEvent.ToolStatus.DENIED -> CompactActivityStatus.DENIED
    AssistantStreamEvent.ToolStatus.CANCELLED -> CompactActivityStatus.CANCELLED
    AssistantStreamEvent.ToolStatus.INCOMPLETE -> CompactActivityStatus.INCOMPLETE
}

/** Merge adjacent activities within a round; late child progress updates its original parent row. */
internal fun compactActivitySummary(snapshot: AssistantStreamSnapshot): CompactActivitySummary {
    val tools = snapshot.toolCalls.associateBy { it.callId }
    val agents = snapshot.subagents.associateBy { it.agentId }
    val searches = snapshot.searches.associateBy { it.searchId }
    val explicitRounds = snapshot.activityOrder.filter { it.kind == AssistantActivityKind.TOOL && it.messageId != null }
        .associate { it.id to it.messageId!! }
    val parentCalls = agents.values.mapNotNull { it.parentCallId }.toSet()
    val order = compactActivityOrder(snapshot)
    val reasoning = snapshot.messages.flatMap { message ->
        message.blocks.filterIsInstance<AssistantContentBlockSnapshot.Reasoning>().map { block ->
            AssistantActivityReference(AssistantActivityKind.REASONING, block.blockId,
                message.messageId, block.kind) to block
        }
    }.toMap()
    val seen = mutableSetOf<Pair<AssistantActivityKind, String>>()
    val rows = linkedMapOf<CompactActivityGroup, CompactActivityRow>()
    val groupOrder = linkedSetOf<CompactActivityGroup>()
    val positions = mutableListOf<CompactActivityPosition>()
    val callRounds = mutableMapOf<String, String>()
    val callGroups = mutableMapOf<String, CompactActivityGroup>()
    var currentMessage: String? = null
    var boundary = 0
    var unscopedRun = 0
    var previousKind: CompactActivityKind? = null
    var previousWasUnscoped = false
    var previousGroup: CompactActivityGroup? = null

    fun observeMessage(message: String?) {
        if (message != null && currentMessage != message) {
            boundary++
            currentMessage = message
            previousWasUnscoped = false
        }
    }

    fun position(group: CompactActivityGroup) {
        if (groupOrder.add(group)) positions += CompactActivityPosition.Group(group)
    }

    fun adjacentGroup(message: String?, kind: CompactActivityKind): CompactActivityGroup {
        if (message == null && (!previousWasUnscoped || previousKind != kind)) unscopedRun++
        val round = message ?: "adjacent:$unscopedRun"
        if (previousGroup?.let { it.boundary == boundary && (it.round != round || it.kind != kind) } == true) {
            boundary++
        }
        val group = CompactActivityGroup(round, boundary, kind)
        previousKind = kind
        previousWasUnscoped = message == null
        previousGroup = group
        return group
    }

    fun add(
        reference: AssistantActivityReference,
        kind: CompactActivityKind,
        name: String?,
        status: CompactActivityStatus,
        notice: String?,
        knownRound: String? = null,
        fact: CompactActivityFact,
        knownGroup: CompactActivityGroup? = null,
    ): CompactActivityGroup {
        val message = knownRound ?: reference.messageId?.let { "message:$it" }
            ?: explicitRounds[reference.id].takeIf { reference.kind == AssistantActivityKind.TOOL }
            ?.let { "message:$it" } ?: currentMessage?.let { "message:$it" }
        val rowKey = knownGroup ?: adjacentGroup(message, kind)
        position(rowKey)
        val old = rows[rowKey]
        rows[rowKey] = CompactActivityRow(
            id = old?.id ?: "${reference.kind}:${reference.id}",
            kind = kind,
            round = rowKey.round,
            count = (old?.count ?: 0) + 1,
            names = (old?.names.orEmpty() + listOfNotNull(name?.takeIf { it.isNotBlank() })).distinct(),
            statuses = old?.statuses.orEmpty() + status,
            notice = notice?.takeIf { it.isNotBlank() } ?: old?.notice,
            facts = old?.facts.orEmpty() + fact,
            references = old?.references.orEmpty() + reference,
        )
        return rowKey
    }

    order.forEach { reference ->
        if (reference.kind in setOf(AssistantActivityKind.TEXT, AssistantActivityKind.REASONING)) {
            observeMessage(reference.messageId)
            boundary++
            if (reference.kind == AssistantActivityKind.REASONING) {
                reasoning[reference]?.let { block ->
                    if (block.text.isNotBlank()) positions += CompactActivityPosition.Thinking(
                        CompactActivityEntry.Reasoning(reference, block))
                }
            }
            previousWasUnscoped = false
            return@forEach
        }
        if (!seen.add(reference.kind to reference.id)) return@forEach
        when (reference.kind) {
            AssistantActivityKind.TOOL -> tools[reference.id]?.let { tool ->
                val kind = compactActivityKind(tool)
                observeMessage(explicitRounds[tool.callId] ?: reference.messageId)
                // A child event provides actual progress, so do not count its creation call again.
                if (kind == CompactActivityKind.AGENT && tool.callId in parentCalls) {
                    val round = explicitRounds[tool.callId]?.let { "message:$it" }
                        ?: currentMessage?.let { "message:$it" } ?: "parent:${tool.callId}"
                    callRounds[tool.callId] = round
                    val group = callGroups.getOrPut(tool.callId) { adjacentGroup(round, kind) }
                    position(group)
                } else {
                    val envelope = if (compactWebTool(tool)) compactWebEnvelope(tool) else activityReceiptEnvelope(tool.result)
                    val status = compactReceiptStatus(tool, envelope)
                    val fact = compactToolFact(tool, kind, status, compactWebData(tool, envelope))
                    val notice = if (compactWebTool(tool)) {
                        compactWebError(tool, envelope) ?: if (status == CompactActivityStatus.INCOMPLETE &&
                            tool.status == AssistantStreamEvent.ToolStatus.SUCCEEDED) "工具结果缺少有效回执"
                            else compactDisplayName(tool.approvalReason)
                    } else tool.error ?: compactDisplayName(envelope?.opt("error"), 512)
                        ?: tool.approvalReason
                    val group = add(reference, kind, fact.name, status, notice, fact = fact)
                    callRounds[tool.callId] = group.round
                    callGroups[tool.callId] = group
                }
            }
            AssistantActivityKind.SUBAGENT -> agents[reference.id]?.let { agent ->
                val status = when (agent.status) {
                    AssistantStreamEvent.SubagentStatus.PENDING, AssistantStreamEvent.SubagentStatus.STARTING ->
                        CompactActivityStatus.QUEUED
                    AssistantStreamEvent.SubagentStatus.RUNNING -> CompactActivityStatus.RUNNING
                    AssistantStreamEvent.SubagentStatus.COMPLETED -> CompactActivityStatus.COMPLETED
                    AssistantStreamEvent.SubagentStatus.FAILED -> CompactActivityStatus.FAILED
                    AssistantStreamEvent.SubagentStatus.CANCELLED -> CompactActivityStatus.WAIT_CANCELLED
                    AssistantStreamEvent.SubagentStatus.INCOMPLETE -> CompactActivityStatus.INCOMPLETE
                }
                val parentRound = agent.parentCallId?.let {
                    explicitRounds[it]?.let { message -> "message:$message" } ?: callRounds[it] ?: "parent:$it"
                }
                val notice = if (status == CompactActivityStatus.WAIT_CANCELLED) {
                    "已取消等待，未确认子任务停止"
                } else agent.error ?: agent.detail
                val parent = agent.parentCallId?.let { tools[it] }
                val parentSucceeded = parent?.let {
                    compactReceiptStatus(it) == CompactActivityStatus.COMPLETED
                } == true
                val received = agent.status == AssistantStreamEvent.SubagentStatus.COMPLETED &&
                    agent.detail == "收到了消息"
                val fact = CompactActivityFact(
                    CompactActivityAction.CREATE_AGENT,
                    parentSucceeded ||
                        (parent == null && agent.status != AssistantStreamEvent.SubagentStatus.PENDING &&
                            !agent.name.isNullOrBlank()),
                    compactDisplayName(agent.name),
                    sent = agent.detail == "已发送消息" || received || (
                        parentSucceeded &&
                            compactArguments(parent.result.orEmpty())?.optJSONObject("data")?.let {
                                it.opt("run_requested") == true && it.isNull("start_error")
                            } == true
                        ),
                    received = received,
                )
                val group = add(reference, CompactActivityKind.AGENT, fact.name, status, notice,
                    parentRound, fact, agent.parentCallId?.let { callGroups[it] })
                agent.parentCallId?.let { callGroups.putIfAbsent(it, group) }
                if (parent != null && compactActivityKind(parent) == CompactActivityKind.AGENT) {
                    rows[group]?.let { row ->
                        rows[group] = row.copy(references = (row.references +
                            AssistantActivityReference(AssistantActivityKind.TOOL, parent.callId)).distinctBy {
                            it.kind to it.id
                        })
                    }
                }
            }
            AssistantActivityKind.SEARCH -> searches[reference.id]?.let { search ->
                observeMessage(reference.messageId)
                val status = when (search.status) {
                    AssistantStreamEvent.SearchStatus.PENDING -> CompactActivityStatus.QUEUED
                    AssistantStreamEvent.SearchStatus.RUNNING -> CompactActivityStatus.RUNNING
                    AssistantStreamEvent.SearchStatus.COMPLETED -> CompactActivityStatus.COMPLETED
                    AssistantStreamEvent.SearchStatus.FAILED -> CompactActivityStatus.FAILED
                    AssistantStreamEvent.SearchStatus.CANCELLED -> CompactActivityStatus.CANCELLED
                    AssistantStreamEvent.SearchStatus.INCOMPLETE -> CompactActivityStatus.INCOMPLETE
                }
                val query = compactDisplayName(search.query)
                add(reference, CompactActivityKind.SEARCH, query, status, search.error,
                    fact = CompactActivityFact(CompactActivityAction.SEARCH,
                        search.status == AssistantStreamEvent.SearchStatus.COMPLETED, query,
                        queries = listOfNotNull(query)))
            }
            else -> previousWasUnscoped = false
        }
    }
    val values = groupOrder.mapNotNull { rows[it] }
    val active = values.any { row -> row.statuses.any { it.active } } ||
        snapshot.messages.any { it.status in setOf(AssistantMessageStatus.CREATED, AssistantMessageStatus.IN_PROGRESS) }
    val terminalRun = snapshot.runStatus?.let { it != AssistantRunStatus.RUNNING } == true
    return CompactActivitySummary(values,
        isRunning = !snapshot.isFinal && !terminalRun &&
            (active || snapshot.runStatus == AssistantRunStatus.RUNNING),
        timeline = positions.mapNotNull { entry ->
            when (entry) {
                is CompactActivityPosition.Thinking -> entry.entry
                is CompactActivityPosition.Group -> rows[entry.group]?.let { CompactActivityEntry.Activity(it) }
            }
        })
}

/** Only a lifecycle edge overrides a manual toggle; terminal snapshot updates cannot reclose it. */
internal fun compactActivityExpanded(expanded: Boolean, previousRunning: Boolean?, running: Boolean?): Boolean =
    if (running != null && running != previousRunning) running else expanded
