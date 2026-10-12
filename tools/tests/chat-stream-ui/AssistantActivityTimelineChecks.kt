package com.dafeiyu.mobile.ui

import com.dafeiyu.mobile.data.*

private fun timelineThinking(id: String, message: String) =
    AssistantActivityReference(AssistantActivityKind.REASONING, id, message,
        AssistantReasoningKind.USER_VISIBLE_THINKING)

private fun timelineTool(id: String, message: String? = null) =
    AssistantActivityReference(AssistantActivityKind.TOOL, id, message)

private fun timelineCall(id: String, name: String = "search") =
    AssistantToolCallSnapshot(id, name, AssistantStreamEvent.ToolKind.TOOL, """{"query":"$id"}""",
        AssistantStreamEvent.ToolStatus.SUCCEEDED, AssistantStreamEvent.ApprovalRequirement.NONE)

private fun timelineMessage(message: String, vararg ids: String) =
    AssistantMessageSnapshot(message, "assistant", AssistantMessageStatus.IN_PROGRESS,
        ids.map { AssistantContentBlockSnapshot.Reasoning(it,
            AssistantReasoningKind.USER_VISIBLE_THINKING, "Public sentence $it.") })

private fun timelineIds(summary: CompactActivitySummary): List<String> =
    summary.timeline.map { entry ->
        when (entry) {
            is CompactActivityEntry.Reasoning -> entry.block.blockId
            is CompactActivityEntry.Activity -> entry.row.references.first().id
        }
    }

fun main() {
    var cases = 0
    fun verify(name: String, check: () -> Unit) {
        check()
        cases++
        println("PASS timeline $cases: $name")
    }
    val base = AssistantStreamReducer().snapshot().copy(
        messages = listOf(timelineMessage("round", "think1", "think2")),
        toolCalls = listOf(timelineCall("s1"), timelineCall("s2"), timelineCall("s3")),
        activityOrder = listOf(timelineThinking("think1", "round"), timelineTool("s1", "round"),
            timelineThinking("think2", "round"), timelineTool("s2", "round"), timelineTool("s3", "round")),
    )
    verify("reasoning/search/reasoning/search, merge only after the boundary") {
        val summary = compactActivitySummary(base)
        check(timelineIds(summary) == listOf("think1", "s1", "think2", "s2"))
        check(summary.rows.map { it.count } == listOf(1, 2))
        check(summary.rows.flatMap { it.references }.map { it.id } == listOf("s1", "s2", "s3"))
    }
    verify("hiding public reasoning preserves separated receipts") {
        val activities = compactActivitySummary(base).timeline.filterIsInstance<CompactActivityEntry.Activity>()
        check(activities.map { it.row.count } == listOf(1, 2))
    }
    verify("real reducer event order across model rounds") {
        val reducer = AssistantStreamReducer()
        reducer.apply(AssistantStreamEvent.UserVisibleThinkingDelta(0, "r1", "t1", "First."))
        reducer.apply(AssistantStreamEvent.ToolCallStarted(1, "a", "search", messageId = "r1"))
        reducer.apply(AssistantStreamEvent.ToolStatusChanged(2, "a", AssistantStreamEvent.ToolStatus.SUCCEEDED))
        reducer.apply(AssistantStreamEvent.UserVisibleThinkingDelta(3, "r2", "t2", "Second."))
        reducer.apply(AssistantStreamEvent.ToolCallStarted(4, "b", "search", messageId = "r2"))
        check(timelineIds(compactActivitySummary(reducer.snapshot())) == listOf("t1", "a", "t2", "b"))
        check(compactActivitySummary(reducer.snapshot()).rows.map { it.round } ==
            listOf("message:r1", "message:r2"))
    }
    verify("same-round search/command/search preserves each first-seen position") {
        val mixed = base.copy(toolCalls = listOf(timelineCall("s1"), timelineCall("cmd", "exec_command"),
            timelineCall("s2"), timelineCall("s3")),
            activityOrder = listOf(timelineThinking("think1", "round"), timelineTool("s1", "round"),
                timelineTool("cmd", "round"), timelineTool("s2", "round"),
                timelineThinking("think2", "round"), timelineTool("s3", "round")))
        val summary = compactActivitySummary(mixed)
        check(timelineIds(summary) == listOf("think1", "s1", "cmd", "s2", "think2", "s3"))
        check(summary.rows.map { it.count } == listOf(1, 1, 1, 1))
        check(summary.rows.flatMap { it.references }.map { it.id } == listOf("s1", "cmd", "s2", "s3"))
    }
    verify("each intervening tool kind separates adjacent search batches") {
        listOf("exec_command", "read_file", "view_image", "use_skill", "create_subagent",
            "browser", "get_weather").forEach { name ->
            val mixed = base.copy(messages = emptyList(),
                toolCalls = listOf(timelineCall("s1"), timelineCall("s2"), timelineCall("middle", name),
                    timelineCall("s3"), timelineCall("s4")),
                activityOrder = listOf("s1", "s2", "middle", "s3", "s4").map { timelineTool(it, "round") })
            val summary = compactActivitySummary(mixed)
            check(timelineIds(summary) == listOf("s1", "middle", "s3")) { "Lost chronology around $name" }
            check(summary.rows.map { it.count } == listOf(2, 1, 2))
            check(summary.rows.flatMap { it.references }.map { it.id } == listOf("s1", "s2", "middle", "s3", "s4"))
        }
    }
    verify("real search events cannot merge across a command in the same message") {
        val reducer = AssistantStreamReducer()
        reducer.apply(AssistantStreamEvent.UserVisibleThinkingDelta(0, "round", "think", "First."))
        reducer.apply(AssistantStreamEvent.SearchStarted(1, "s1", "First query"))
        reducer.apply(AssistantStreamEvent.ToolCallStarted(2, "cmd", "exec_command", messageId = "round"))
        reducer.apply(AssistantStreamEvent.SearchStarted(3, "s2", "Second query"))
        reducer.apply(AssistantStreamEvent.SearchStarted(4, "s3", "Third query"))
        val summary = compactActivitySummary(reducer.snapshot())
        check(timelineIds(summary) == listOf("think", "s1", "cmd", "s2"))
        check(summary.rows.map { it.count } == listOf(1, 1, 2))
    }
    verify("new public text block separates receipts without inventing a thinking row") {
        val mixed = base.copy(messages = listOf(AssistantMessageSnapshot("round", "assistant",
            AssistantMessageStatus.IN_PROGRESS, listOf(AssistantContentBlockSnapshot.Text("text", "Update.")))),
            toolCalls = base.toolCalls.take(2),
            activityOrder = listOf(timelineTool("s1", "round"),
                AssistantActivityReference(AssistantActivityKind.TEXT, "text", "round"), timelineTool("s2", "round")))
        check(timelineIds(compactActivitySummary(mixed)) == listOf("s1", "s2"))
        check(compactActivitySummary(mixed).rows.map { it.count } == listOf(1, 1))
    }
    verify("repeated block IDs remain scoped to message and reasoning kind") {
        val repeated = base.copy(messages = listOf(timelineMessage("r1", "same"), timelineMessage("r2", "same")),
            activityOrder = listOf(timelineThinking("same", "r1"), timelineTool("s1", "r1"),
                timelineThinking("same", "r2"), timelineTool("s2", "r2"), timelineTool("s3", "r2")))
        val entries = compactActivitySummary(repeated).timeline
        check(entries.filterIsInstance<CompactActivityEntry.Reasoning>().map { it.reference.messageId } ==
            listOf("r1", "r2"))
        check(entries.map { it.id }.distinct().size == entries.size)
    }
    verify("duplicate and enriched call references keep one receipt") {
        val duplicate = base.copy(activityOrder = listOf(timelineThinking("think1", "round"),
            timelineTool("s1"), timelineTool("s1", "round")) + base.activityOrder.drop(2))
        val summary = compactActivitySummary(duplicate)
        check(summary.count == 3)
        check(timelineIds(summary) == listOf("think1", "s1", "think2", "s2"))
    }
    verify("missing legacy reasoning order is restored by known tool rounds") {
        val legacy = base.copy(messages = listOf(timelineMessage("r1", "t1"), timelineMessage("r2", "t2")),
            toolCalls = base.toolCalls.take(2),
            activityOrder = listOf(timelineTool("s1", "r1"), timelineTool("s2", "r2")))
        check(timelineIds(compactActivitySummary(legacy)) == listOf("t1", "s1", "t2", "s2"))
    }
    verify("unavailable stale reasoning does not invent a boundary") {
        val stale = base.copy(messages = emptyList(),
            activityOrder = listOf(timelineTool("s1", "round"), timelineThinking("ghost", "round"),
                timelineTool("s2", "round"), timelineTool("s3", "round")))
        check(compactActivitySummary(stale).rows.single().count == 3)
    }
    verify("partial legacy order retains known content order within a message") {
        val partial = base.copy(activityOrder = listOf(timelineThinking("think1", "round"),
            timelineTool("s1", "round"), timelineTool("s2", "round"), timelineTool("s3", "round")))
        val reasoningEntries = compactActivitySummary(partial).timeline.filterIsInstance<CompactActivityEntry.Reasoning>()
        check(reasoningEntries.map { it.block.blockId } == listOf("think1", "think2"))
        check(compactActivityOrder(partial).filter { it.kind == AssistantActivityKind.TOOL }
            .map { it.id } == listOf("s1", "s2", "s3"))
    }
    verify("real blank public block still separates tool batches") {
        val blank = base.copy(messages = listOf(base.messages.single().copy(blocks =
            base.messages.single().blocks.map { (it as AssistantContentBlockSnapshot.Reasoning).copy(text = "") })))
        check(compactActivitySummary(blank).timeline.all { it is CompactActivityEntry.Activity })
        check(compactActivitySummary(blank).rows.map { it.count } == listOf(1, 2))
    }
    verify("summary and visible thinking with same ID stay distinct") {
        val thinking = base.messages.single().blocks.first() as AssistantContentBlockSnapshot.Reasoning
        val kinds = base.copy(messages = listOf(base.messages.single().copy(blocks = listOf(thinking,
            thinking.copy(kind = AssistantReasoningKind.SUMMARY)))),
            activityOrder = listOf(timelineThinking("think1", "round"), timelineTool("s1", "round"),
                AssistantActivityReference(AssistantActivityKind.REASONING, "think1", "round",
                    AssistantReasoningKind.SUMMARY), timelineTool("s2", "round"), timelineTool("s3", "round")))
        check(compactActivitySummary(kinds).timeline.filterIsInstance<CompactActivityEntry.Reasoning>()
            .map { it.block.kind }.distinct().size == 2)
        check(compactActivitySummary(kinds).rows.size == 2)
    }
    verify("late child stays with its parent before subsequent reasoning") {
        val parent = timelineCall("parent", "create_subagent")
        val child = AssistantSubagentSnapshot("child", "Child", "parent",
            AssistantStreamEvent.SubagentStatus.COMPLETED, "Real child output")
        val delayed = base.copy(toolCalls = listOf(parent, timelineCall("s2")), subagents = listOf(child),
            activityOrder = listOf(timelineThinking("think1", "round"), timelineTool("parent", "round"),
                timelineThinking("think2", "round"), timelineTool("s2", "round"),
                AssistantActivityReference(AssistantActivityKind.SUBAGENT, "child")))
        val summary = compactActivitySummary(delayed)
        check(timelineIds(summary) == listOf("think1", "child", "think2", "s2"))
        check(summary.agentCount == 1)
        check(summary.rows.first().references.map { it.id }.toSet() == setOf("parent", "child"))
        check(activityReceiptDetails(delayed, summary.rows.first().references, true).map { it.id }.toSet() ==
            setOf("tool:parent", "agent:child"))
    }
    verify("parent batches separated by reasoning never merge") {
        val separated = base.copy(toolCalls = listOf(timelineCall("p1", "create_subagent"),
            timelineCall("p2", "create_subagent")),
            subagents = listOf(
                AssistantSubagentSnapshot("c1", "One", "p1", AssistantStreamEvent.SubagentStatus.RUNNING, ""),
                AssistantSubagentSnapshot("c2", "Two", "p2", AssistantStreamEvent.SubagentStatus.COMPLETED, "done")),
            activityOrder = listOf(timelineThinking("think1", "round"), timelineTool("p1", "round"),
                timelineThinking("think2", "round"), timelineTool("p2", "round")))
        val summary = compactActivitySummary(separated)
        check(summary.rows.map { it.count } == listOf(1, 1) && summary.agentCount == 2)
        check(timelineIds(summary) == listOf("think1", "c1", "think2", "c2"))
    }
    verify("adjacent sibling parents stay grouped when children arrive in reverse completion order") {
        val siblings = base.copy(messages = emptyList(),
            toolCalls = listOf(timelineCall("p1", "create_subagent"), timelineCall("p2", "create_subagent"),
                timelineCall("search")),
            subagents = listOf(
                AssistantSubagentSnapshot("c1", "One", "p1", AssistantStreamEvent.SubagentStatus.RUNNING, ""),
                AssistantSubagentSnapshot("c2", "Two", "p2", AssistantStreamEvent.SubagentStatus.COMPLETED, "done")),
            activityOrder = listOf(timelineTool("p1", "round"), timelineTool("p2", "round"),
                timelineTool("search", "round"), AssistantActivityReference(AssistantActivityKind.SUBAGENT, "c2"),
                AssistantActivityReference(AssistantActivityKind.SUBAGENT, "c1")))
        val summary = compactActivitySummary(siblings)
        check(summary.rows.map { it.kind } == listOf(CompactActivityKind.AGENT, CompactActivityKind.SEARCH))
        check(summary.rows.map { it.count } == listOf(2, 1) && summary.agentCount == 2)
        check(summary.rows.first().references.map { it.id }.toSet() == setOf("p1", "p2", "c1", "c2"))
        check(activityReceiptDetails(siblings, summary.rows.first().references, true).map { it.id }.toSet() ==
            setOf("tool:p1", "tool:p2", "agent:c1", "agent:c2"))
    }
    verify("parent batches separated by another tool never merge even with late children") {
        val separated = base.copy(messages = emptyList(),
            toolCalls = listOf(timelineCall("p1", "create_subagent"), timelineCall("cmd", "exec_command"),
                timelineCall("p2", "create_subagent")),
            subagents = listOf(
                AssistantSubagentSnapshot("c1", "One", "p1", AssistantStreamEvent.SubagentStatus.COMPLETED, "done"),
                AssistantSubagentSnapshot("c2", "Two", "p2", AssistantStreamEvent.SubagentStatus.RUNNING, "")),
            activityOrder = listOf(timelineTool("p1", "round"), timelineTool("cmd", "round"),
                timelineTool("p2", "round"), AssistantActivityReference(AssistantActivityKind.SUBAGENT, "c2"),
                AssistantActivityReference(AssistantActivityKind.SUBAGENT, "c1")))
        val summary = compactActivitySummary(separated)
        check(timelineIds(summary) == listOf("c1", "cmd", "c2"))
        check(summary.rows.map { it.count } == listOf(1, 1, 1) && summary.agentCount == 2)
    }
    verify("late child progress does not split a newer adjacent search batch") {
        val delayed = base.copy(messages = emptyList(),
            toolCalls = listOf(timelineCall("parent", "create_subagent"), timelineCall("s1"), timelineCall("s2")),
            subagents = listOf(AssistantSubagentSnapshot("child", "Child", "parent",
                AssistantStreamEvent.SubagentStatus.COMPLETED, "done")),
            activityOrder = listOf(timelineTool("parent", "round"), timelineTool("s1", "round"),
                AssistantActivityReference(AssistantActivityKind.SUBAGENT, "child"), timelineTool("s2", "round")))
        val summary = compactActivitySummary(delayed)
        check(timelineIds(summary) == listOf("child", "s1"))
        check(summary.rows.map { it.count } == listOf(1, 2))
        check(summary.rows.last().references.map { it.id } == listOf("s1", "s2"))
    }
    verify("streaming deltas retain disclosure IDs and the original block object") {
        val before = compactActivitySummary(base)
        val changed = base.copy(messages = listOf(base.messages.single().copy(blocks =
            base.messages.single().blocks.map { (it as AssistantContentBlockSnapshot.Reasoning)
                .copy(text = it.text + " Latest public sentence.") })))
        val after = compactActivitySummary(changed)
        check(before.timeline.map { it.id } == after.timeline.map { it.id })
        check(after.timeline.filterIsInstance<CompactActivityEntry.Reasoning>().first().block ===
            changed.messages.single().blocks.first())
    }
    verify("large reasoning stays shared; terminal fold and manual reopen survive") {
        val large = base.copy(messages = listOf(base.messages.single().copy(blocks = listOf(
            (base.messages.single().blocks.first() as AssistantContentBlockSnapshot.Reasoning)
                .copy(text = "x".repeat(200_000))))), isFinal = true)
        val summary = compactActivitySummary(large)
        check(summary.timeline.filterIsInstance<CompactActivityEntry.Reasoning>().single().block ===
            large.messages.single().blocks.single())
        check(!summary.isRunning)
        check(!compactActivityExpanded(true, true, false))
        check(compactActivityExpanded(true, false, false))
    }
    println("PASS: $cases activity timeline cases using real production projection/reducer.")
}
