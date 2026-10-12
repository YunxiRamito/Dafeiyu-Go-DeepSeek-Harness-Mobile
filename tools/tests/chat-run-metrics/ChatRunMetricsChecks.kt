package com.dafeiyu.mobile.ui

import com.dafeiyu.mobile.data.AssistantMessageStatus
import com.dafeiyu.mobile.data.AssistantRunStatus
import com.dafeiyu.mobile.data.AssistantStreamEvent
import com.dafeiyu.mobile.data.AssistantStreamReducer
import com.dafeiyu.mobile.data.AssistantUsage
import com.dafeiyu.mobile.data.ChatRunMetrics
import com.dafeiyu.mobile.data.ChatRunMetricsCollector
import com.dafeiyu.mobile.data.ChatRunStatus
import com.dafeiyu.mobile.data.MAX_METRIC_ID_LENGTH
import com.dafeiyu.mobile.data.MAX_METRIC_TRACKED_IDS
import com.dafeiyu.mobile.data.chatRunMetricsFromSnapshot
import com.dafeiyu.mobile.data.toAssistantRunStatus
import com.dafeiyu.mobile.data.toChatRunStatus
import com.dafeiyu.mobile.ui.components.GlassMotion
import java.util.Locale

fun main() {
    var passed = 0
    fun case(name: String, action: () -> Unit) {
        action()
        passed++
        println("PASS $name")
    }
    fun collector() = ChatRunMetricsCollector(12, "conversation", 1000)
    fun usage(sequence: Long, input: Long? = null, output: Long? = null,
        total: Long? = null, cached: Long? = null) =
        AssistantStreamEvent.UsageReported(sequence, AssistantUsage(input, output, total, cached))

    case("unknown usage and elapsed never fabricate zero or speed") {
        val items = chatRunInfoItems(ChatRunMetrics(12, "conversation"))
        check(items == listOf("未知轮未知步骤 · 未知 t/s", "未知 tok · 中 未知", "未知"))
        val description = chatRunInfoDescription(ChatRunMetrics(12, "conversation"))
        check(description.contains("耗时 未知") && description.contains("任务进度未知"))
        check(description.contains("输入缓存命中率 未知"))
        val initial = collector().snapshot(1000)
        check(initial.rounds == 0 && initial.steps == 0 && initial.elapsedMillis == 0L)
        check(initial.tokensPerSecond == null)
    }
    case("three model rounds accumulate counts without ending session at each completion") {
        val c = collector()
        c.onEvent(AssistantStreamEvent.MessageStarted(0, "round1"), 1100)
        c.onEvent(usage(1, 100, 20, 120, 50), 1200)
        c.onEvent(AssistantStreamEvent.MessageCompleted(2, "round1"), 1300)
        check(c.snapshot(1400).status == ChatRunStatus.Running)
        c.onEvent(AssistantStreamEvent.MessageStarted(3, "round2"), 1500)
        c.onEvent(usage(4, 300, 50, 350, 150), 1800)
        c.onEvent(AssistantStreamEvent.MessageCompleted(5, "round2"), 1900)
        c.onEvent(AssistantStreamEvent.MessageStarted(6, "round3"), 2100)
        c.onEvent(usage(7, 450, 60, 510, 225), 2200)
        val finished = c.finish(ChatRunStatus.Completed, 3000)
        check(finished.rounds == 3 && finished.totalTokens == 510L && finished.usage?.outputTokens == 60L)
        check(finished.cacheHitPercent == 50.0 && finished.elapsedMillis == 2000L)
        check(finished.tokensPerSecond == 30.0)
    }
    case("cumulative snapshots replace usage and null fields preserve last known values") {
        val c = collector()
        c.onEvent(usage(0, 100, 20, 120, 40), 1100)
        c.onEvent(usage(1, 100, 20, 120, 40), 1200)
        c.onEvent(usage(2, cached = 50), 1300)
        val m = c.snapshot(2000)
        check(m.totalTokens == 120L && m.usage?.inputTokens == 100L && m.cacheHitPercent == 50.0)
    }
    case("duplicate and old accepted-event callbacks cannot double count") {
        val c = collector()
        c.onEvent(AssistantStreamEvent.MessageStarted(0, "same"), 1100)
        c.onEvent(AssistantStreamEvent.MessageStarted(1, "same"), 1200)
        c.onEvent(usage(2, total = 500), 1300)
        c.onEvent(usage(2, total = 999), 1400)
        c.onEvent(usage(1, total = 888), 1500)
        check(c.snapshot(1600).rounds == 1 && c.snapshot(1600).totalTokens == 500L)
    }
    case("only actually RUNNING tool calls count; proposals and refused calls do not") {
        val c = collector()
        c.onEvent(AssistantStreamEvent.ToolCallStarted(0, "refused", "lookup"), 1100)
        c.onEvent(AssistantStreamEvent.ToolError(1, "refused", "limit", "TOOL_LIMIT"), 1200)
        c.onEvent(AssistantStreamEvent.ToolStatusChanged(2, "denied", AssistantStreamEvent.ToolStatus.DENIED), 1300)
        c.onEvent(AssistantStreamEvent.ToolStatusChanged(3, "real", AssistantStreamEvent.ToolStatus.RUNNING), 1400)
        c.onEvent(AssistantStreamEvent.ToolStatusChanged(4, "real", AssistantStreamEvent.ToolStatus.RUNNING), 1500)
        c.onEvent(AssistantStreamEvent.ToolError(5, "real", "failed"), 1600)
        c.onEvent(AssistantStreamEvent.ToolResult(6, "real", "result"), 1700)
        c.onEvent(AssistantStreamEvent.ToolStatusChanged(7, "second", AssistantStreamEvent.ToolStatus.RUNNING), 1800)
        val m = c.snapshot(1900)
        check(m.steps == 2 && m.status == ChatRunStatus.Running)
    }
    case("elapsed uses whole-session monotonic clock; old ticks do not rewind") {
        val c = collector()
        check(c.snapshot(2000).elapsedMillis == 1000L)
        check(c.snapshot(1500).elapsedMillis == 1000L)
        check(c.snapshot(500).elapsedMillis == 1000L)
        val done = c.finish(ChatRunStatus.Completed, 2500)
        check(done.finishedAtMillis == 2500L && done.elapsedMillis == 1500L)
        check(c.snapshot(9999) == done)
    }
    case("failure freezes metrics and late usage cannot overwrite the terminal run") {
        val c = collector()
        c.onEvent(usage(0, output = 20), 1200)
        val failed = c.onEvent(AssistantStreamEvent.MessageFailed(1, "round1", "error"), 2000)
        check(failed.status == ChatRunStatus.Failed && failed.tokensPerSecond == 20.0)
        check(c.onEvent(usage(2, output = 1000), 5000) == failed)
        check(c.finish(ChatRunStatus.Completed, 6000) == failed)
    }
    case("cancellation and incomplete results have truthful final states") {
        val cancelled = collector().onEvent(AssistantStreamEvent.MessageCancelled(0, "round"), 1200)
        check(cancelled.status == ChatRunStatus.Cancelled)
        check(chatRunInfoItems(cancelled).last() == "未知 · 已取消")
        val incomplete = collector().onEvent(AssistantStreamEvent.MessageIncomplete(0, "round", "length"), 1300)
        check(incomplete.status == ChatRunStatus.Failed)
        check(chatRunInfoItems(incomplete).last() == "未知 · 失败")
    }
    case("explicit totals win; exact input plus output may derive a total without overflow") {
        check(ChatRunMetrics(1, "c", usage = AssistantUsage(100, 20)).totalTokens == 120L)
        check(ChatRunMetrics(1, "c", usage = AssistantUsage(100, null)).totalTokens == null)
        check(ChatRunMetrics(1, "c", usage = AssistantUsage(100, 20, 125)).totalTokens == 125L)
        check(ChatRunMetrics(1, "c", usage = AssistantUsage(Long.MAX_VALUE, 1)).totalTokens == null)
    }
    case("cache is weighted by cumulative input; zero or contradictory denominators stay unknown") {
        check(ChatRunMetrics(1, "c", usage = AssistantUsage(inputTokens = 400, cachedInputTokens = 125))
            .cacheHitPercent == 31.25)
        check(ChatRunMetrics(1, "c", usage = AssistantUsage(inputTokens = 0, cachedInputTokens = 0))
            .cacheHitPercent == null)
        check(ChatRunMetrics(1, "c", usage = AssistantUsage(inputTokens = 10, cachedInputTokens = 11))
            .cacheHitPercent == null)
        check(ChatRunMetrics(1, "c", usage = AssistantUsage(inputTokens = 10)).cacheHitPercent == null)
    }
    case("speed requires reported output and positive real elapsed; reported zero output is valid") {
        check(ChatRunMetrics(1, "c", elapsedMillis = 1000, usage = AssistantUsage(totalTokens = 20))
            .tokensPerSecond == null)
        check(ChatRunMetrics(1, "c", elapsedMillis = 0, usage = AssistantUsage(outputTokens = 20))
            .tokensPerSecond == null)
        check(ChatRunMetrics(1, "c", elapsedMillis = 2000, usage = AssistantUsage(outputTokens = 0))
            .tokensPerSecond == 0.0)
        check(ChatRunMetrics(1, "c", elapsedMillis = 2000, usage = AssistantUsage(outputTokens = 25))
            .tokensPerSecond == 12.5)
    }
    case("restored history uses persisted duration and leaves unproven counters unknown") {
        val reducer = AssistantStreamReducer()
        reducer.apply(AssistantStreamEvent.MessageStarted(0, "round"))
        reducer.apply(usage(1, 50, 10, 60, 25))
        reducer.apply(AssistantStreamEvent.MessageCompleted(2, "round"))
        val snapshot = reducer.finish()
        val restored = chatRunMetricsFromSnapshot(12, "conversation", snapshot, elapsedMillis = 2000)
        check(restored.status == ChatRunStatus.Completed && restored.tokensPerSecond == 5.0)
        check(restored.startedAtMillis == null && restored.finishedAtMillis == null)
        check(restored.rounds == null && restored.steps == null)
        val counted = chatRunMetricsFromSnapshot(12, "conversation", snapshot, 2000, 3, 2)
        check(counted.rounds == 3 && counted.steps == 2)
        val legacy = chatRunMetricsFromSnapshot(12, "conversation", snapshot)
        check(legacy.elapsedMillis == null && legacy.tokensPerSecond == null)
        check(snapshot.messages.single().status == AssistantMessageStatus.COMPLETED)
    }
    case("protocol status mappings and persisted session state override completed model rounds") {
        val reducer = AssistantStreamReducer()
        reducer.apply(AssistantStreamEvent.MessageStarted(0, "round"))
        reducer.apply(AssistantStreamEvent.MessageCompleted(1, "round", "tool_calls"))
        val snapshot = reducer.finish()
        for (status in ChatRunStatus.entries) {
            check(status.toAssistantRunStatus().toChatRunStatus() == status)
            val persisted = snapshot.copy(runStatus = status.toAssistantRunStatus(),
                runElapsedMillis = 4000, runRounds = 2, runSteps = 3)
            val metrics = chatRunMetricsFromSnapshot(12, "conversation", persisted)
            check(metrics.status == status && metrics.elapsedMillis == 4000L)
            check(metrics.rounds == 2 && metrics.steps == 3)
        }
        val interrupted = snapshot.copy(runStatus = AssistantRunStatus.INCOMPLETE)
        check(chatRunMetricsFromSnapshot(12, "conversation", interrupted).status == ChatRunStatus.Failed)
        check(chatRunInfoItems(chatRunMetricsFromSnapshot(12, "conversation", interrupted)).last() == "未知 · 失败")
        check(chatRunMetricsFromSnapshot(12, "conversation", interrupted,
            status = ChatRunStatus.Cancelled).status == ChatRunStatus.Cancelled)
    }
    case("legacy final snapshots with unfinished activities never imply session success") {
        for (kind in listOf("tool", "subagent", "search")) {
            val reducer = AssistantStreamReducer()
            reducer.apply(AssistantStreamEvent.MessageStarted(0, "round"))
            reducer.apply(AssistantStreamEvent.MessageCompleted(1, "round", "tool_calls"))
            reducer.apply(when (kind) {
                "tool" -> AssistantStreamEvent.ToolStatusChanged(2, "call", AssistantStreamEvent.ToolStatus.RUNNING)
                "subagent" -> AssistantStreamEvent.SubagentStarted(2, "worker", "worker")
                else -> AssistantStreamEvent.SearchStarted(2, "search", "query")
            })
            check(chatRunMetricsFromSnapshot(12, "conversation", reducer.snapshot()).status == ChatRunStatus.Running)
            check(chatRunMetricsFromSnapshot(12, "conversation", reducer.finish()).status == ChatRunStatus.Failed)
        }
        val emptyFinal = AssistantStreamReducer().finish()
        check(chatRunMetricsFromSnapshot(12, "conversation", emptyFinal).status == ChatRunStatus.Failed)
    }
    case("failed tool followed by a completed answer does not imply the whole session failed") {
        val reducer = AssistantStreamReducer()
        reducer.apply(AssistantStreamEvent.MessageStarted(0, "round1"))
        reducer.apply(AssistantStreamEvent.MessageCompleted(1, "round1", "tool_calls"))
        reducer.apply(AssistantStreamEvent.ToolStatusChanged(2, "call", AssistantStreamEvent.ToolStatus.RUNNING))
        reducer.apply(AssistantStreamEvent.ToolError(3, "call", "unavailable"))
        reducer.apply(AssistantStreamEvent.MessageStarted(4, "round2"))
        reducer.apply(AssistantStreamEvent.TextDelta(5, "round2", "The tool failed"))
        reducer.apply(AssistantStreamEvent.MessageCompleted(6, "round2"))
        check(chatRunMetricsFromSnapshot(12, "conversation", reducer.finish()).status == ChatRunStatus.Completed)
    }
    case("render labels follow theme-independent stable formatting and real status") {
        val oldLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val metrics = ChatRunMetrics(1, "c", rounds = 2, steps = 1,
                usage = AssistantUsage(100, 25, 125, 25), status = ChatRunStatus.Completed, elapsedMillis = 2000)
            check(chatRunInfoItems(metrics) == listOf("2轮1步骤 · 12.5 t/s", "125 tok · 中 25%", "未知 · 已完成"))
            check(chatRunInfoDescription(metrics).contains("全会话平均输出速度 12.5 token/秒"))
        } finally {
            Locale.setDefault(oldLocale)
        }
    }
    case("separate runs do not leak conversation identity usage counts or timer") {
        val a = collector()
        a.onEvent(AssistantStreamEvent.MessageStarted(0, "r1"), 1100)
        a.onEvent(usage(1, total = 500), 1200)
        val b = ChatRunMetricsCollector(99, "other", 3000).snapshot(4000)
        check(b.messageId == 99L && b.conversationId == "other")
        check(b.rounds == 0 && b.steps == 0 && b.usage == null && b.elapsedMillis == 1000L)
        check(a.snapshot(1500).totalTokens == 500L)
    }
    case("invalid metric inputs reject impossible timestamps and counts") {
        check(runCatching { ChatRunMetrics(1, "c", elapsedMillis = -1) }.isFailure)
        check(runCatching { ChatRunMetrics(1, "c", rounds = -1) }.isFailure)
        check(runCatching { ChatRunMetrics(1, "c", startedAtMillis = 10, finishedAtMillis = 5) }.isFailure)
        check(runCatching { collector().finish(ChatRunStatus.Running, 2000) }.isFailure)
    }
    case("requested compact format uses measured whole-session speed and actual cache hits") {
        val metrics = ChatRunMetrics(1, "c", rounds = 3, steps = 23,
            usage = AssistantUsage(2500, 500, 3000, 2400), elapsedMillis = 2500)
        check(chatRunInfoItems(metrics) == listOf("3轮23步骤 · 200 t/s", "3k tok · 中 96%", "未知"))
        val description = chatRunInfoDescription(metrics)
        check(description.contains("累计用量 3000 token") && description.contains("输入缓存命中率 96.0%"))
        check(description.contains("包含模型及工具等待") && description.contains("未提供任务总量"))
        check(!description.contains("上下文"))
    }
    case("decimal k m b abbreviations handle rounding promotions and Long bounds") {
        val expected = mapOf(
            0L to "0", 999L to "999", 1000L to "1k", 3050L to "3.1k",
            999_949L to "999.9k", 999_950L to "1m", 1_000_000L to "1m",
            3_500_000L to "3.5m", 999_949_999L to "999.9m", 999_950_000L to "1b",
            1_000_000_000L to "1b", 3_000_000_000L to "3b",
            Long.MAX_VALUE to "9223372036.9b",
        )
        expected.forEach { (value, label) -> check(compactChatRunTokens(value) == label) }
        check(runCatching { compactChatRunTokens(-1) }.isFailure)
    }
    case("unknown progress never turns counts usage time or terminal state into a percentage") {
        for (status in ChatRunStatus.entries) {
            val metrics = ChatRunMetrics(1, "c", rounds = 3, steps = 23, status = status,
                elapsedMillis = 999999, usage = AssistantUsage(100, 20, 120, 15))
            check(chatRunInfoItems(metrics).last().startsWith("未知"))
            check(!chatRunInfoItems(metrics).last().contains("%"))
            check(chatRunInfoDescription(metrics).contains("任务进度未知"))
        }
    }
    case("partial invalid and zero usage retain explicit unknown labels") {
        val partial = ChatRunMetrics(1, "c", usage = AssistantUsage(inputTokens = 100))
        check(chatRunInfoItems(partial)[0].endsWith("未知 t/s"))
        check(chatRunInfoItems(partial)[1] == "未知 tok · 中 未知")
        val invalid = ChatRunMetrics(1, "c", elapsedMillis = 1000,
            usage = AssistantUsage(-1, -5, -6, -2))
        check(invalid.totalTokens == null && invalid.tokensPerSecond == null && invalid.cacheHitPercent == null)
        val zero = ChatRunMetrics(1, "c", elapsedMillis = 1000, usage = AssistantUsage(0, 0, 0, 0))
        check(chatRunInfoItems(zero)[0].endsWith("0 t/s"))
        check(chatRunInfoItems(zero)[1] == "0 tok · 中 未知")
    }
    case("compact labels remain stable under locale and exact descriptions keep the original count") {
        val oldLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val metrics = ChatRunMetrics(1, "c", elapsedMillis = 2000,
                usage = AssistantUsage(1_234_562, 5, 1_234_567, 1_000_000))
            check(chatRunInfoItems(metrics)[1].startsWith("1.2m tok"))
            check(chatRunInfoDescription(metrics).contains("累计用量 1234567 token"))
            check(chatRunInfoItems(metrics)[0].endsWith("2.5 t/s"))
        } finally {
            Locale.setDefault(oldLocale)
        }
    }
    case("shared easing and reduced motion apply immediately without metric interpolation") {
        check(GlassMotion.scalar(GlassMotion.MorphMillis).durationMillis == 380)
        check(GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion = true).durationMillis == 0)
    }
    case("bounded exact counters accept budget then independently degrade to unknown") {
        val c = collector()
        var sequence = 0L
        repeat(MAX_METRIC_TRACKED_IDS) { index ->
            c.onEvent(AssistantStreamEvent.MessageStarted(sequence++, "round-$index"), 1100)
            c.onEvent(AssistantStreamEvent.ToolStatusChanged(sequence++, "tool-$index",
                AssistantStreamEvent.ToolStatus.RUNNING), 1100)
        }
        check(c.snapshot(1200).rounds == MAX_METRIC_TRACKED_IDS)
        check(c.snapshot(1200).steps == MAX_METRIC_TRACKED_IDS)
        c.onEvent(AssistantStreamEvent.MessageStarted(sequence++, "round-0"), 1200)
        check(c.snapshot(1200).rounds == MAX_METRIC_TRACKED_IDS)
        c.onEvent(AssistantStreamEvent.MessageStarted(sequence++, "overflow"), 1200)
        check(c.snapshot(1200).rounds == null && c.snapshot(1200).steps == MAX_METRIC_TRACKED_IDS)
        c.onEvent(AssistantStreamEvent.ToolStatusChanged(sequence++, "overflow-tool",
            AssistantStreamEvent.ToolStatus.RUNNING), 1200)
        repeat(1024) { index ->
            c.onEvent(AssistantStreamEvent.MessageStarted(sequence++, "late-$index"), 1300)
        }
        c.onEvent(usage(sequence, 100, 20, 120, 10), 1500)
        val done = c.finish(ChatRunStatus.Completed, 2000)
        check(done.rounds == null && done.steps == null && done.totalTokens == 120L)
        check(done.tokensPerSecond == 20.0 && done.cacheHitPercent == 10.0)
    }
    case("oversized IDs are never retained and blank non-assistant IDs never count") {
        val c = collector()
        c.onEvent(AssistantStreamEvent.MessageStarted(0, " "), 1100)
        c.onEvent(AssistantStreamEvent.MessageStarted(1, "user", role = "user"), 1100)
        check(c.snapshot(1100).rounds == 0)
        c.onEvent(AssistantStreamEvent.MessageStarted(2, "r".repeat(MAX_METRIC_ID_LENGTH)), 1100)
        check(c.snapshot(1100).rounds == 1)
        c.onEvent(AssistantStreamEvent.MessageStarted(3, "r".repeat(MAX_METRIC_ID_LENGTH + 1)), 1100)
        c.onEvent(AssistantStreamEvent.ToolStatusChanged(4, "t".repeat(MAX_METRIC_ID_LENGTH + 1),
            AssistantStreamEvent.ToolStatus.RUNNING), 1100)
        check(c.snapshot(1200).rounds == null && c.snapshot(1200).steps == null)
    }
    case("large text deltas are not stored or scanned by the metrics collector") {
        val c = collector()
        c.onEvent(AssistantStreamEvent.MessageStarted(0, "round"), 1000)
        val payload = "x".repeat(65_536)
        repeat(128) { index ->
            c.onEvent(AssistantStreamEvent.TextDelta(index + 1L, "round", payload), 1100)
        }
        c.onEvent(usage(129, 10, 20, 30, 5), 1500)
        repeat(128) { index ->
            val metrics = c.snapshot(2000 + index.toLong())
            check(metrics.rounds == 1 && metrics.steps == 0 && metrics.totalTokens == 30L)
        }
    }
    println("PASS $passed chat run metrics/display scenarios; no Android UI, network, keys or app build")
}
