package com.dafeiyu.mobile.ui.proposal

import com.dafeiyu.mobile.data.AssistantActivityKind
import com.dafeiyu.mobile.data.AssistantStreamEvent
import com.dafeiyu.mobile.data.AssistantStreamSnapshot
import org.json.JSONObject

// Test-only proposal. No production source imports this file.
internal data class DetailText(val text: String, val previewClipped: Boolean)
internal data class DetailSection(val label: String, val content: DetailText)
internal data class DetailEntry(
    val id: String,
    val kind: AssistantActivityKind,
    val roundMessageId: String?,
    val parentCallId: String?,
    val title: String,
    val status: String,
    val sections: List<DetailSection>,
    val sentEvidence: Boolean = false,
    val receivedEvidence: Boolean = false,
    val sourceTruncated: Boolean = false,
    val managedOutputId: String? = null,
)

private fun preview(text: String, maxCharacters: Int = 16_384): DetailText {
    var end = minOf(text.length, maxCharacters)
    if (end > 0 && end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
    val bounded = text.take(end).map {
        if ((it.isISOControl() && it !in "\t\r\n") || it in '\u202a'..'\u202e' ||
            it in '\u2066'..'\u2069') '\uFFFD' else it
    }.joinToString("")
    return DetailText(bounded, end < text.length)
}

private fun envelope(raw: String?): JSONObject? {
    if (raw == null || raw.length !in 2..32_768 || raw.count { it == '{' || it == '[' } > 64) return null
    return runCatching { JSONObject(raw) }.getOrNull()
}

private fun label(raw: String?, fallback: String): String =
    preview(raw?.takeIf { it.isNotBlank() } ?: fallback, 128).text

private fun section(label: String, text: String?): DetailSection? =
    text?.takeIf { it.isNotEmpty() }?.let { DetailSection(label, preview(it)) }

/**
 * UI intent, not run state, gates payloads. A running summary or ordinary event row
 * must never open this panel automatically. Projection has no IO or execution callbacks.
 */
internal fun proposedActivityDetails(
    snapshot: AssistantStreamSnapshot,
    processOpenedByUser: Boolean,
    includeArguments: Boolean = false,
): List<DetailEntry> {
    if (!processOpenedByUser) return emptyList()
    val toolReferences = snapshot.activityOrder.filter { it.kind == AssistantActivityKind.TOOL }
    val rounds = toolReferences.mapNotNull { ref -> ref.messageId?.let { ref.id to it } }.toMap()
    val tools = snapshot.toolCalls.associateBy { it.callId }
    val toolEntries = snapshot.toolCalls.map { tool ->
        val response = envelope(tool.result)
        val data = response?.takeIf { it.opt("ok") == true }?.optJSONObject("data")
        val fileId = if (tool.name == "write_text_file" &&
            tool.status == AssistantStreamEvent.ToolStatus.SUCCEEDED &&
            data?.opt("storage") == "managed_app") {
            (data.opt("output_id") as? String)?.takeIf {
                it.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            }
        } else null
        val status = when (tool.status) {
            AssistantStreamEvent.ToolStatus.PENDING, AssistantStreamEvent.ToolStatus.STREAMING_ARGUMENTS,
            AssistantStreamEvent.ToolStatus.READY, AssistantStreamEvent.ToolStatus.APPROVED -> "准备中"
            AssistantStreamEvent.ToolStatus.AWAITING_APPROVAL -> "等待批准"
            AssistantStreamEvent.ToolStatus.RUNNING -> "运行中"
            AssistantStreamEvent.ToolStatus.SUCCEEDED -> "已完成"
            AssistantStreamEvent.ToolStatus.FAILED -> "失败"
            AssistantStreamEvent.ToolStatus.DENIED -> "已拒绝"
            AssistantStreamEvent.ToolStatus.CANCELLED -> "已取消"
            AssistantStreamEvent.ToolStatus.INCOMPLETE -> "未完成"
        }
        DetailEntry(
            id = "tool:${tool.callId}", kind = AssistantActivityKind.TOOL,
            roundMessageId = rounds[tool.callId], parentCallId = null,
            title = label(tool.name, "工具"), status = status,
            sections = listOfNotNull(
                if (includeArguments) section("参数", tool.arguments) else null,
                section("输出", tool.result),
                section("错误", tool.error),
                section("错误代码", tool.errorCode),
                section("批准说明", tool.approvalReason),
            ),
            sourceTruncated = tool.name in setOf("create_subagent", "get_task") &&
                data?.opt("result_truncated") == true ||
                tool.name in setOf("list_tasks", "browser") && data?.opt("truncated") == true,
            managedOutputId = fileId,
        )
    }
    val agentEntries = snapshot.subagents.map { agent ->
        val parent = agent.parentCallId?.let { tools[it] }
        val data = envelope(parent?.result)?.takeIf { it.opt("ok") == true }
            ?.optJSONObject("data")?.takeIf { it.opt("task_id") == agent.agentId }
        val received = agent.status == AssistantStreamEvent.SubagentStatus.COMPLETED &&
            agent.detail == "收到了消息"
        val sent = agent.detail == "已发送消息" || received ||
            parent?.let {
                it.name == "create_subagent" && it.status == AssistantStreamEvent.ToolStatus.SUCCEEDED &&
                    data?.opt("run_requested") == true && data.has("start_error") && data.isNull("start_error")
            } == true
        val status = when (agent.status) {
            AssistantStreamEvent.SubagentStatus.PENDING -> "准备中"
            AssistantStreamEvent.SubagentStatus.STARTING -> "启动中"
            AssistantStreamEvent.SubagentStatus.RUNNING -> "运行中"
            AssistantStreamEvent.SubagentStatus.COMPLETED -> "已完成"
            AssistantStreamEvent.SubagentStatus.FAILED -> "失败"
            AssistantStreamEvent.SubagentStatus.CANCELLED -> "已取消等待，未确认子任务停止"
            AssistantStreamEvent.SubagentStatus.INCOMPLETE -> "未完成"
        }
        DetailEntry(
            id = "agent:${agent.agentId}", kind = AssistantActivityKind.SUBAGENT,
            roundMessageId = agent.parentCallId?.let { rounds[it] }, parentCallId = agent.parentCallId,
            title = label(agent.name, "子智能体"), status = status,
            sections = listOfNotNull(section("公开输出", agent.output), section("状态说明", agent.detail),
                section("错误", agent.error)),
            sentEvidence = sent, receivedEvidence = received,
            sourceTruncated = parent?.name == "create_subagent" && data?.opt("result_truncated") == true,
        )
    }
    val entries = (toolEntries + agentEntries).associateBy { it.id }
    val order = snapshot.activityOrder.mapNotNull {
        when (it.kind) {
            AssistantActivityKind.TOOL -> "tool:${it.id}"
            AssistantActivityKind.SUBAGENT -> "agent:${it.id}"
            else -> null
        }
    }
    return (order + entries.keys).distinct().mapNotNull { entries[it] }
}
