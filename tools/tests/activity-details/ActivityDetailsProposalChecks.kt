package com.dafeiyu.mobile.ui.proposal

import com.dafeiyu.mobile.data.*

private fun snapshot(
    tools: List<AssistantToolCallSnapshot> = emptyList(),
    agents: List<AssistantSubagentSnapshot> = emptyList(),
    order: List<AssistantActivityReference> = emptyList(),
) = AssistantStreamSnapshot(emptyList(), tools, agents, emptyList(), emptyList(), null,
    null, null, emptyList(), emptyList(), false, activityOrder = order)

private fun tool(
    id: String,
    name: String = "exec_command",
    result: String? = null,
    status: AssistantStreamEvent.ToolStatus = AssistantStreamEvent.ToolStatus.SUCCEEDED,
) = AssistantToolCallSnapshot(id, name, AssistantStreamEvent.ToolKind.TOOL, """{"command":"echo hello"}""",
    status, AssistantStreamEvent.ApprovalRequirement.NONE, result = result)

fun main() {
    val real = snapshot(listOf(tool("t", result = "actual\noutput")))
    check(proposedActivityDetails(real, false).isEmpty())
    check(proposedActivityDetails(real.copy(runStatus = AssistantRunStatus.RUNNING), false).isEmpty())
    val output = proposedActivityDetails(real, true).single()
    check(output.sections.map { it.label } == listOf("输出"))
    check(output.sections.single().content.text == "actual\noutput")
    check(proposedActivityDetails(real, true, true).single().sections.first().label == "参数")
    check(proposedActivityDetails(snapshot(listOf(tool("empty"))), true).single().sections.isEmpty())
    val failed = tool("failed", result = "partial", status = AssistantStreamEvent.ToolStatus.FAILED)
        .copy(error = "real error", errorCode = "REAL_CODE")
    val errorDetail = proposedActivityDetails(snapshot(listOf(failed)), true).single()
    check(errorDetail.status == "失败" && errorDetail.sections.map { it.label } == listOf("输出", "错误", "错误代码"))

    val parentResult = """{"ok":true,"data":{"task_id":"child","run_requested":true,"start_error":null,"result_truncated":true}}"""
    val parent = tool("p", "create_subagent", parentResult)
    val child = AssistantSubagentSnapshot("child", "Research", "p",
        AssistantStreamEvent.SubagentStatus.COMPLETED, "真实回传", "收到了消息")
    val agentSource = snapshot(listOf(parent), listOf(child), listOf(
        AssistantActivityReference(AssistantActivityKind.SUBAGENT, "child"),
        AssistantActivityReference(AssistantActivityKind.TOOL, "p", "round1"),
        AssistantActivityReference(AssistantActivityKind.TOOL, "p", "round1")))
    val details = proposedActivityDetails(agentSource, true)
    check(details.size == 2 && details.first().kind == AssistantActivityKind.SUBAGENT)
    val agent = details.first()
    check(agent.roundMessageId == "round1" && agent.parentCallId == "p")
    check(agent.sentEvidence && agent.receivedEvidence && agent.sourceTruncated)
    check(agent.sections.first().content.text == "真实回传")
    val waitingChild = child.copy(status = AssistantStreamEvent.SubagentStatus.CANCELLED,
        output = "", detail = "等待取消")
    val cancelled = proposedActivityDetails(agentSource.copy(subagents = listOf(waitingChild)), true).first()
    check(cancelled.status == "已取消等待，未确认子任务停止")
    check(cancelled.sentEvidence && !cancelled.receivedEvidence)
    val draftParent = parent.copy(result = """{"ok":true,"data":{"task_id":"child","run_requested":false,"start_error":null}}""")
    val draft = proposedActivityDetails(agentSource.copy(toolCalls = listOf(draftParent), subagents = listOf(
        child.copy(status = AssistantStreamEvent.SubagentStatus.PENDING, output = "", detail = "草稿"))), true).first()
    check(!draft.sentEvidence && !draft.receivedEvidence && draft.sections.none { it.label == "公开输出" })
    val wrongChild = proposedActivityDetails(agentSource.copy(subagents = listOf(waitingChild.copy(
        agentId = "other", parentCallId = "unknown"))), true).last()
    check(wrongChild.roundMessageId == null && !wrongChild.sentEvidence && !wrongChild.sourceTruncated)
    val missingStartError = parent.copy(result = """{"ok":true,"data":{"task_id":"child","run_requested":true}}""")
    check(!proposedActivityDetails(agentSource.copy(toolCalls = listOf(missingStartError),
        subagents = listOf(waitingChild)), true).first().sentEvidence)

    val id = "12345678-1234-1234-1234-123456789abc"
    val fileResult = """{"ok":true,"data":{"output_id":"$id","storage":"managed_app"}}"""
    val file = tool("file", "write_text_file", fileResult)
    check(proposedActivityDetails(snapshot(listOf(file)), true).single().managedOutputId == id)
    listOf(
        file.copy(status = AssistantStreamEvent.ToolStatus.FAILED),
        file.copy(name = "arbitrary_tool"),
        file.copy(result = fileResult.replace("managed_app", "external")),
        file.copy(result = fileResult.replace(id, "../escape")),
        file.copy(result = fileResult.replace("\"ok\":true", "\"ok\":false")),
        file.copy(result = "invalid"),
    ).forEach { check(proposedActivityDetails(snapshot(listOf(it)), true).single().managedOutputId == null) }

    val long = "a".repeat(16_383) + "\uD83D\uDE00" + "tail"
    val longDetail = proposedActivityDetails(snapshot(listOf(tool("long", result = long))), true).single()
        .sections.single().content
    check(longDetail.previewClipped && longDetail.text.length == 16_383)
    check(!longDetail.text.last().isHighSurrogate())
    val maliciousText = "<script>execute()</script>\n**just data**\u202e\u0000"
    val inert = proposedActivityDetails(snapshot(listOf(tool("data", result = maliciousText))), true).single()
        .sections.single().content
    check(inert.text.startsWith("<script>execute()</script>\n**just data**"))
    check('\u202e' !in inert.text && '\u0000' !in inert.text)
    check(real.toolCalls.single().result == "actual\noutput") { "Projection modified source" }
    val textOnly = real.copy(toolCalls = emptyList(), messages = listOf(AssistantMessageSnapshot("m", "assistant",
        AssistantMessageStatus.COMPLETED, listOf(AssistantContentBlockSnapshot.Text("t", "收到3条消息"),
            AssistantContentBlockSnapshot.Reasoning("r", AssistantReasoningKind.USER_VISIBLE_THINKING, "private")))))
    check(proposedActivityDetails(textOnly, true).isEmpty())
    val web = tool("web", "web_search", """{"ok":true,"data":{"query":"actual","results":[]}}""")
    val browse = tool("browser", "browser", """{"ok":true,"data":{"text":"real page","truncated":true}}""")
    val webDetails = proposedActivityDetails(snapshot(listOf(web, browse)), true)
    check(webDetails.map { it.title } == listOf("web_search", "browser"))
    check(!webDetails.first().sourceTruncated && webDetails.last().sourceTruncated)
    check(proposedActivityDetails(real.copy(isFinal = true), false).isEmpty())
    println("PASS test-only proposal: explicit user-open gate; output/arguments separation; truthful empty/error/cancellation; parent-round scope; real notification evidence; source/preview truncation; bounded inert text; managed ID validation; no model/reasoning fabrication. No production UI or network executed.")
}
