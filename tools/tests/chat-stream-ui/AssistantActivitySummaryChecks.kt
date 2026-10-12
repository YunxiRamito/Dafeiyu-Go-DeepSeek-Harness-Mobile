package com.dafeiyu.mobile.ui

import com.dafeiyu.mobile.data.*

private fun tool(
    id: String,
    name: String,
    status: AssistantStreamEvent.ToolStatus = AssistantStreamEvent.ToolStatus.SUCCEEDED,
    kind: AssistantStreamEvent.ToolKind = AssistantStreamEvent.ToolKind.TOOL,
    arguments: String = "",
) = AssistantToolCallSnapshot(id, name, kind, arguments, status, AssistantStreamEvent.ApprovalRequirement.NONE)

private fun snapshot(
    tools: List<AssistantToolCallSnapshot> = emptyList(),
    agents: List<AssistantSubagentSnapshot> = emptyList(),
    order: List<AssistantActivityReference> = emptyList(),
    final: Boolean = false,
    messages: List<AssistantMessageSnapshot> = emptyList(),
) = AssistantStreamSnapshot(messages, tools, agents, emptyList(), emptyList(), null,
    null, null, emptyList(), emptyList(), final, activityOrder = order)

private fun reference(id: String, message: String? = null) =
    AssistantActivityReference(AssistantActivityKind.TOOL, id, message)

fun main() {
    val calls = listOf(
        tool("a", "write_text_file"),
        tool("b", "exec_command", kind = AssistantStreamEvent.ToolKind.COMMAND),
        tool("c", "read_file"),
        tool("d", "write_text_file"),
    )
    val grouped = compactActivitySummary(snapshot(calls, order = listOf(
        reference("a", "round1"), reference("b", "round1"),
        reference("c", "round1"), reference("d", "round2"),
    )))
    check(grouped.rows.size == 4)
    check(grouped.rows[0].count == 1 && grouped.rows[0].kind == CompactActivityKind.FILE)
    check(grouped.rows[1].kind == CompactActivityKind.COMMAND)
    check(grouped.rows[2].count == 1 && grouped.rows[2].kind == CompactActivityKind.FILE)
    check(grouped.rows[3].count == 1 && grouped.rows[3].round == "message:round2")
    check(grouped.count == 4)
    check(grouped.rows[0].headline == "编写了 1 个文件")
    check(grouped.rows[2].headline == "查看了 1 个文件")
    check(grouped.rows[1].headline == "运行了 1 个命令")
    check(grouped.rows[0].names.isEmpty()) { "Raw function names leaked into display names" }

    val legacy = compactActivitySummary(snapshot(calls, order = calls.map { reference(it.callId) }))
    check(legacy.rows.size == 3) { "Unscoped calls across another type were incorrectly merged" }
    check(legacy.rows.last().count == 2)
    val duplicate = compactActivitySummary(snapshot(listOf(calls.first()), order = listOf(
        reference("a"), reference("a", "round1"),
    )))
    check(duplicate.count == 1)

    val parents = listOf(tool("p1", "create_subagent"), tool("p2", "create_subagent"),
        tool("p3", "create_subagent"), tool("draft", "create_subagent"))
    val agents = listOf(
        AssistantSubagentSnapshot("child1", "One", "p1", AssistantStreamEvent.SubagentStatus.RUNNING, ""),
        AssistantSubagentSnapshot("child2", "Two", "p2", AssistantStreamEvent.SubagentStatus.COMPLETED, "received"),
        AssistantSubagentSnapshot("child3", "Three", "p3", AssistantStreamEvent.SubagentStatus.CANCELLED, ""),
    )
    val agentOrder = listOf(reference("p1", "round1"), reference("p2", "round1"),
        reference("p3", "round2"), reference("draft", "round2"),
        AssistantActivityReference(AssistantActivityKind.SUBAGENT, "child3"),
        AssistantActivityReference(AssistantActivityKind.SUBAGENT, "child2"),
        AssistantActivityReference(AssistantActivityKind.SUBAGENT, "child1"))
    val agentSummary = compactActivitySummary(snapshot(parents, agents, agentOrder))
    check(agentSummary.agentCount == 4) { "Parent creation and actual child were counted twice" }
    check(agentSummary.rows.size == 2)
    val firstRound = agentSummary.rows.single { it.round == "message:round1" }
    val secondRound = agentSummary.rows.single { it.round == "message:round2" }
    check(firstRound.count == 2 && secondRound.count == 2)
    check(agentSummary.rows.first().round == "message:round1")
    check(firstRound.statusLabel.contains("运行中") && firstRound.statusLabel.contains("已完成"))
    check(secondRound.statusLabel.contains("已取消等待"))
    check(secondRound.notice == "已取消等待，未确认子任务停止")
    check(!secondRound.statusLabel.contains("已停止"))
    check(firstRound.headline.contains("已建立 2 个子智能体"))
    check(agentSummary.isRunning)
    check(!compactActivitySummary(snapshot(parents, agents, agentOrder, final = true)).isRunning)

    val malformedOrder = compactActivitySummary(snapshot(listOf(parents.first()), listOf(agents.first()),
        listOf(AssistantActivityReference(AssistantActivityKind.SUBAGENT, "child1"), reference("p1", "round1"))))
    check(malformedOrder.count == 1 && malformedOrder.rows.single().round == "message:round1")
    val isolated = compactActivitySummary(snapshot(agents = listOf(agents[0], agents[1])))
    check(isolated.rows.size == 2) { "Different unknown parent rounds were merged" }

    check(compactActivityKind(tool("f", "write_text_file")) == CompactActivityKind.FILE)
    check(compactActivityKind(tool("i", "view_image")) == CompactActivityKind.IMAGE)
    check(compactActivityKind(tool("s", "use_skill")) == CompactActivityKind.SKILL)
    check(compactActivityKind(tool("u", "get_weather")) == CompactActivityKind.TOOL)
    check(compactActivityKind(tool("u", "skillet")) == CompactActivityKind.TOOL)
    check(compactActivitySummary(snapshot()).rows.isEmpty())
    val thinkingOnly = snapshot(messages = listOf(AssistantMessageSnapshot("m", "assistant",
        AssistantMessageStatus.IN_PROGRESS, listOf(AssistantContentBlockSnapshot.Reasoning(
            "thinking", AssistantReasoningKind.USER_VISIBLE_THINKING, "use_skill view_image")))))
    check(compactActivitySummary(thinkingOnly).rows.isEmpty()) { "Reasoning invented tool activity" }
    val textOnly = thinkingOnly.copy(messages = listOf(thinkingOnly.messages.first().copy(
        blocks = listOf(AssistantContentBlockSnapshot.Text("text", "搜索了天气和新闻")))))
    check(compactActivitySummary(textOnly).rows.isEmpty()) { "Model text invented search activity" }
    val searchReducer = AssistantStreamReducer()
    searchReducer.apply(AssistantStreamEvent.TextDelta(0, "searchRound1", "intro", "checking"))
    searchReducer.apply(AssistantStreamEvent.SearchStarted(1, "search1", "Compose 流式正文"))
    check(compactActivitySummary(searchReducer.snapshot()).rows.single().headline == "正在搜索Compose 流式正文")
    searchReducer.apply(AssistantStreamEvent.SearchResultAdded(2, "search1",
        AssistantSearchResult("result1", "Do not use the result title as a query")))
    searchReducer.apply(AssistantStreamEvent.SearchStatusChanged(3, "search1",
        AssistantStreamEvent.SearchStatus.COMPLETED))
    searchReducer.apply(AssistantStreamEvent.SearchStarted(4, "search2", "Android Markdown"))
    searchReducer.apply(AssistantStreamEvent.SearchStatusChanged(5, "search2",
        AssistantStreamEvent.SearchStatus.COMPLETED))
    check(compactActivitySummary(searchReducer.snapshot()).rows.single().headline ==
        "搜索了Compose 流式正文、Android Markdown")
    searchReducer.apply(AssistantStreamEvent.TextDelta(6, "searchRound2", "intro2", "checking again"))
    searchReducer.apply(AssistantStreamEvent.SearchStarted(7, "search3", "Android Markdown"))
    check(compactActivitySummary(searchReducer.snapshot()).rows.size == 2) { "Search rounds were merged" }
    val resultOnly = snapshot().copy(searches = listOf(AssistantSearchSnapshot("unknown", null,
        AssistantStreamEvent.SearchStatus.COMPLETED, listOf(
            AssistantSearchResult("unknownResult", "Invented query must not appear")))))
    check(compactActivitySummary(resultOnly).rows.single().headline == "搜索了（查询未提供）")
    val searchTools = listOf(
        tool("s1", "web_search", arguments = """{"query":"天气"}""")
            .copy(result = """{"ok":true,"data":{"query":"天气","results":[]}}"""),
        tool("s2", "search", arguments = """{"q":"天气"}"""),
        tool("s3", "search", arguments = """{"search_query":[{"q":"新闻"},{"query":"地图"},"路线"]}"""),
        tool("s4", "web_search", status = AssistantStreamEvent.ToolStatus.FAILED,
            arguments = """{"query":"失败查询"}"""),
        tool("s5", "web_search", status = AssistantStreamEvent.ToolStatus.READY,
            arguments = """{"query":"尚未执行"}"""),
    )
    val searchToolsRow = compactActivitySummary(snapshot(searchTools,
        order = searchTools.map { reference(it.callId, "searchToolRound") })).rows.single()
    check(searchToolsRow.headline == "搜索了天气、新闻、地图 等 4 项查询 · 搜索失败：失败查询 · 待搜索尚未执行")
    check(searchToolsRow.kind.glyph == "search")
    listOf("""{"query":"bad\nquery"}""", """{"query":"bad\u202equery"}""",
        """{"query":"${"a".repeat(129)}"}""", """{"query":123}""", "invalid",
        """{"queries":[${List(17) { "\"q$it\"" }.joinToString(",")}]}""").forEach { raw ->
        val unknown = compactActivitySummary(snapshot(listOf(tool("badSearch", "search", arguments = raw))))
        check(unknown.rows.single().headline == "搜索了（查询未提供）") { "Unsafe query was displayed" }
    }
    val longQuery = compactActivitySummary(snapshot(listOf(tool("longSearch", "search",
        arguments = """{"query":"${"a".repeat(128)}"}""")))).rows.single().headline
    check(longQuery == "搜索了${"a".repeat(48)}…")
    val explicitSearchRounds = resultOnly.copy(searches = listOf(
        resultOnly.searches.first().copy(searchId = "x", query = "first"),
        resultOnly.searches.first().copy(searchId = "y", query = "second")),
        activityOrder = listOf(AssistantActivityReference(AssistantActivityKind.SEARCH, "x", "r1"),
            AssistantActivityReference(AssistantActivityKind.SEARCH, "y", "r2")))
    check(compactActivitySummary(explicitSearchRounds).rows.size == 2)

    val semanticTools = listOf(
        tool("write", "write_text_file", arguments = """{"filename":"hello.txt","text":"secret contents"}"""),
        tool("failed", "write_text_file", status = AssistantStreamEvent.ToolStatus.FAILED),
        tool("pending", "exec_command", status = AssistantStreamEvent.ToolStatus.READY),
        tool("image", "view_image"),
        tool("skill", "use_skill", arguments = """{"name":"research","script":"not executed"}"""),
        tool("badSkill", "use_skill", status = AssistantStreamEvent.ToolStatus.FAILED,
            arguments = """{"name":"untrusted"}"""),
        tool("listSkills", "list_skills"),
    )
    val semantic = compactActivitySummary(snapshot(semanticTools,
        order = semanticTools.map { reference(it.callId, "semantic") }))
    val files = semantic.rows.single { it.kind == CompactActivityKind.FILE }
    check(files.headline == "编写了 1 个文件 · 另有 文件操作 1 项")
    check(files.names == listOf("hello.txt"))
    check(!semantic.rows.single { it.kind == CompactActivityKind.COMMAND }.headline.contains("运行了"))
    check(semantic.rows.single { it.kind == CompactActivityKind.IMAGE }.headline == "查看了 1 个图片")
    val skills = semantic.rows.single { it.kind == CompactActivityKind.SKILL }
    check(skills.headline.contains("已调用 Skill 1 次：research"))
    check(!skills.headline.contains("untrusted") && !skills.headline.contains("执行"))
    check(skills.headline.contains("完成了 1 项 Skill 工具操作"))
    listOf("""{"name":123}""", """{"name":"bad\nname"}""", "invalid",
        """{"name":"${"a".repeat(129)}"}""", """{"name":"${"a".repeat(8192)}"}""").forEach { raw ->
        val unknown = compactActivitySummary(snapshot(listOf(tool("s", "use_skill", arguments = raw))))
        check(unknown.rows.single().names.isEmpty())
        check(unknown.rows.single().headline == "已调用 Skill 1 次")
    }

    val notified = listOf(
        agents[0].copy(detail = "已发送消息"),
        agents[1].copy(detail = "收到了消息"),
    )
    val notifications = compactActivitySummary(snapshot(parents.take(2), notified, agentOrder.take(2)))
    check(notifications.rows.single().headline == "已建立 2 个子智能体 · 已发送 2 条消息 · 收到 1 条通知")
    check(notifications.agentCount == 2)
    check(notifications.rows.single().leadingGlyphCount == 2)
    val agentIcons = notifications.rows.single()
    check(agentIcons.copy(facts = emptyList()).leadingGlyphCount == 1)
    check(agentIcons.copy(facts = List(3) { agentIcons.facts.first() }).leadingGlyphCount == 3)
    check(agentIcons.copy(facts = List(10) { agentIcons.facts.first() }).leadingGlyphCount == 3)
    check(files.leadingGlyphCount == 1)
    val draft = compactActivitySummary(snapshot(listOf(parents.last()),
        listOf(agents[0].copy(parentCallId = "draft", detail = "草稿"))))
    check(!draft.rows.single().headline.contains("消息"))
    val failedParent = compactActivitySummary(snapshot(listOf(parents[0].copy(
        status = AssistantStreamEvent.ToolStatus.FAILED)), listOf(agents[0])))
    check(!failedParent.rows.single().headline.contains("已建立"))
    val failedChildWithSentResult = compactActivitySummary(snapshot(
        listOf(parents[0].copy(result = """{"ok":true,"data":{"run_requested":true,"start_error":null}}""")),
        listOf(agents[0].copy(status = AssistantStreamEvent.SubagentStatus.FAILED, detail = "失败"))))
    check(failedChildWithSentResult.rows.single().headline.contains("已发送 1 条消息"))
    check(!failedChildWithSentResult.rows.single().headline.contains("收到"))

    val oldMessage = AssistantMessageSnapshot("old", "assistant", AssistantMessageStatus.COMPLETED,
        listOf(AssistantContentBlockSnapshot.Text("oldText", "earlier process")))
    val finalMessage = oldMessage.copy(messageId = "final",
        blocks = listOf(AssistantContentBlockSnapshot.Text("finalText", "final answer")))
    val finished = snapshot(messages = listOf(oldMessage, finalMessage), final = true)
        .copy(runElapsedMillis = 1250)
    check(compactActivityHistory(finished) == listOf(oldMessage))
    check(compactActivityTitle(finished, compactActivitySummary(finished)) == "已完成 · 用时 1.2 秒")
    check(compactActivityTitle(finished.copy(runElapsedMillis = null),
        compactActivitySummary(finished)) == "已完成")
    check(compactActivityTitle(finished.copy(runElapsedMillis = 65000),
        compactActivitySummary(finished)) == "已完成 · 用时 1 分 5 秒")
    val failedMessage = finished.copy(messages = listOf(finalMessage.copy(status = AssistantMessageStatus.FAILED)))
    check(compactActivityTitle(failedMessage, compactActivitySummary(failedMessage)).startsWith("任务异常"))
    val activeChildFinal = snapshot(parents.take(1), agents.take(1), final = true)
    check(compactActivityTitle(activeChildFinal, compactActivitySummary(activeChildFinal)) == "本轮已结束")
    listOf(
        AssistantRunStatus.CANCELLED to "已停止回复",
        AssistantRunStatus.INCOMPLETE to "未完成",
        AssistantRunStatus.FAILED to "任务异常",
    ).forEach { (status, expected) ->
        val restored = finished.copy(runStatus = status)
        check(compactActivityTitle(restored, compactActivitySummary(restored)) == "$expected · 用时 1.2 秒") {
            "Old completed message overrode terminal run status $status"
        }
        val transitioning = restored.copy(isFinal = false, messages = listOf(
            finalMessage.copy(status = AssistantMessageStatus.IN_PROGRESS)))
        val transitionedSummary = compactActivitySummary(transitioning)
        check(!transitionedSummary.isRunning) { "Terminal run did not collapse its activity" }
        check(compactActivityTitle(transitioning, transitionedSummary).startsWith(expected))
    }
    val running = snapshot().copy(runStatus = AssistantRunStatus.RUNNING)
    check(compactActivitySummary(running).isRunning)
    check(compactActivityTitle(running, compactActivitySummary(running)) == "正在处理")

    check(compactActivityExpanded(false, null, true))
    check(!compactActivityExpanded(true, true, false))
    check(compactActivityExpanded(true, false, false)) { "Manual terminal reopen was lost" }
    check(!compactActivityExpanded(false, true, true)) { "Manual running collapse was lost" }
    check(!compactActivityExpanded(false, null, null)) { "Thinking no longer defaults collapsed" }
    println("PASS: adjacent per-message grouping; real search queries/statuses; safe bounded query merging; no text/result-title fabrication; parent-call deduplication; succeeded-only headlines; capped agent glyphs; safe Skill names; real notifications; elapsed time; restored run-status precedence/collapse; history; disclosure lifecycle.")
}
