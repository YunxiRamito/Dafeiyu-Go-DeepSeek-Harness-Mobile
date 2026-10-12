package com.dafeiyu.mobile.data

enum class ChatRunStatus { Running, Completed, Failed, Cancelled }

internal const val MAX_METRIC_TRACKED_IDS = 256
internal const val MAX_METRIC_ID_LENGTH = 256

/** Exact until the memory budget is exhausted; never evict IDs and invent a duplicate count. */
private class BoundedMetricCounter {
    private val ids = mutableSetOf<String>()
    private var known = true
    val count: Int? get() = if (known) ids.size else null

    fun record(id: String) {
        if (!known) return
        if (id.length > MAX_METRIC_ID_LENGTH) {
            invalidate()
            return
        }
        if (id.isBlank() || id in ids) return
        if (ids.size == MAX_METRIC_TRACKED_IDS) invalidate() else ids += id
    }

    private fun invalidate() {
        known = false
        ids.clear()
    }
}

fun ChatRunStatus.toAssistantRunStatus(): AssistantRunStatus = when (this) {
    ChatRunStatus.Running -> AssistantRunStatus.RUNNING
    ChatRunStatus.Completed -> AssistantRunStatus.COMPLETED
    ChatRunStatus.Failed -> AssistantRunStatus.FAILED
    ChatRunStatus.Cancelled -> AssistantRunStatus.CANCELLED
}

fun AssistantRunStatus.toChatRunStatus(): ChatRunStatus = when (this) {
    AssistantRunStatus.RUNNING -> ChatRunStatus.Running
    AssistantRunStatus.COMPLETED -> ChatRunStatus.Completed
    AssistantRunStatus.FAILED, AssistantRunStatus.INCOMPLETE -> ChatRunStatus.Failed
    AssistantRunStatus.CANCELLED -> ChatRunStatus.Cancelled
}

/** All timestamps use one monotonic clock. Persist elapsed duration, not a reboot-relative timestamp. */
data class ChatRunMetrics(
    val messageId: Long,
    val conversationId: String,
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    val rounds: Int? = null,
    val steps: Int? = null,
    val usage: AssistantUsage? = null,
    val status: ChatRunStatus = ChatRunStatus.Running,
    val elapsedMillis: Long? = null,
    /** True only after the first actual assistant model round emitted MessageCompleted. */
    val firstRoundCompleted: Boolean = false,
) {
    init {
        require(messageId >= 0 && conversationId.isNotBlank())
        require(startedAtMillis == null || startedAtMillis >= 0)
        require(finishedAtMillis == null || finishedAtMillis >= 0)
        require(startedAtMillis == null || finishedAtMillis == null || finishedAtMillis >= startedAtMillis)
        require(rounds == null || rounds >= 0)
        require(steps == null || steps >= 0)
        require(elapsedMillis == null || elapsedMillis >= 0)
    }

    /** Reported cumulative token use, or an exact input + output sum; never an estimate. */
    val totalTokens: Long?
        get() = usage?.totalTokens?.takeIf { it >= 0 } ?: run {
            val input = usage?.inputTokens?.takeIf { it >= 0 } ?: return null
            val output = usage.outputTokens?.takeIf { it >= 0 } ?: return null
            if (input > Long.MAX_VALUE - output) null else input + output
        }

    /** Cached input / cumulative input. This is a cache hit rate, not context occupancy. */
    val cacheHitPercent: Double?
        get() {
            val input = usage?.inputTokens?.takeIf { it > 0 } ?: return null
            val cached = usage.cachedInputTokens?.takeIf { it in 0..input } ?: return null
            return cached.toDouble() / input.toDouble() * 100.0
        }

    /** Average reported output tokens over the whole session, including model/tool waits. */
    val tokensPerSecond: Double?
        get() {
            val output = usage?.outputTokens?.takeIf { it >= 0 } ?: return null
            val duration = elapsedMillis?.takeIf { it > 0 } ?: return null
            return output.toDouble() / duration.toDouble() * 1000.0
        }
}

/**
 * Feed accepted events in sequence order from one real session.
 * UsageReported MUST already contain session cumulative usage (OfficialToolSession's contract).
 * Repeated usage snapshots replace fields; they are never summed here.
 * MessageCompleted ends a model round, not a multi-round model/tool session.
 */
class ChatRunMetricsCollector(
    private val messageId: Long,
    private val conversationId: String,
    private val startedAtMillis: Long,
) {
    private val rounds = BoundedMetricCounter()
    private val executedTools = BoundedMetricCounter()
    private var usage: AssistantUsage? = null
    private var status = ChatRunStatus.Running
    private var lastObservedAtMillis = startedAtMillis
    private var finishedAtMillis: Long? = null
    private var lastSequence = -1L
    private var firstAssistantMessageId: String? = null
    private var firstRoundCompleted = false

    init {
        require(messageId >= 0 && conversationId.isNotBlank() && startedAtMillis >= 0)
    }

    @Synchronized
    fun onEvent(event: AssistantStreamEvent, monotonicMillis: Long): ChatRunMetrics {
        observeTime(monotonicMillis)
        if (finishedAtMillis != null || event.sequenceNumber <= lastSequence) return metrics()
        lastSequence = event.sequenceNumber
        when (event) {
            is AssistantStreamEvent.MessageStarted -> {
                if (event.role == "assistant") {
                    rounds.record(event.messageId)
                    if (firstAssistantMessageId == null &&
                        event.messageId.isNotBlank() && event.messageId.length <= MAX_METRIC_ID_LENGTH) {
                        firstAssistantMessageId = event.messageId
                    }
                }
            }
            is AssistantStreamEvent.MessageCompleted -> {
                if (event.messageId == firstAssistantMessageId) firstRoundCompleted = true
            }
            is AssistantStreamEvent.ToolStatusChanged -> {
                if (event.status == AssistantStreamEvent.ToolStatus.RUNNING) {
                    executedTools.record(event.callId)
                }
            }
            is AssistantStreamEvent.UsageReported -> usage = mergeUsage(usage, event.usage)
            is AssistantStreamEvent.MessageFailed, is AssistantStreamEvent.MessageIncomplete ->
                terminate(ChatRunStatus.Failed)
            is AssistantStreamEvent.MessageCancelled -> terminate(ChatRunStatus.Cancelled)
            else -> Unit
        }
        return metrics()
    }

    @Synchronized
    fun snapshot(monotonicMillis: Long): ChatRunMetrics {
        observeTime(monotonicMillis)
        return metrics()
    }

    /** Call once after the entire session returns; the first terminal state stays authoritative. */
    @Synchronized
    fun finish(status: ChatRunStatus, monotonicMillis: Long): ChatRunMetrics {
        require(status != ChatRunStatus.Running)
        observeTime(monotonicMillis)
        if (finishedAtMillis == null) terminate(status)
        return metrics()
    }

    private fun observeTime(now: Long) {
        require(now >= 0)
        // Older callbacks/ticks cannot rewind the timer; a finished run remains frozen.
        if (finishedAtMillis == null) lastObservedAtMillis = maxOf(lastObservedAtMillis, now)
    }

    private fun terminate(next: ChatRunStatus) {
        status = next
        finishedAtMillis = lastObservedAtMillis
    }

    private fun metrics() = ChatRunMetrics(
        messageId = messageId,
        conversationId = conversationId,
        startedAtMillis = startedAtMillis,
        finishedAtMillis = finishedAtMillis,
        rounds = rounds.count,
        steps = executedTools.count,
        usage = usage,
        status = status,
        elapsedMillis = (finishedAtMillis ?: lastObservedAtMillis) - startedAtMillis,
        firstRoundCompleted = firstRoundCompleted,
    )

    private fun mergeUsage(old: AssistantUsage?, next: AssistantUsage) = AssistantUsage(
        inputTokens = next.inputTokens?.takeIf { it >= 0 } ?: old?.inputTokens,
        outputTokens = next.outputTokens?.takeIf { it >= 0 } ?: old?.outputTokens,
        totalTokens = next.totalTokens?.takeIf { it >= 0 } ?: old?.totalTokens,
        cachedInputTokens = next.cachedInputTokens?.takeIf { it >= 0 } ?: old?.cachedInputTokens,
        reasoningTokens = next.reasoningTokens?.takeIf { it >= 0 } ?: old?.reasoningTokens,
        cacheCreationInputTokens = next.cacheCreationInputTokens?.takeIf { it >= 0 } ?: old?.cacheCreationInputTokens,
    )
}

/**
 * Rehydrate display data without using a persisted monotonic timestamp as today's clock.
 * A final snapshot cannot prove RUNNING was observed for every failed tool, or distinguish
 * synthetic failure messages from real round starts. Supply persisted counts, or leave unknown.
 */
fun chatRunMetricsFromSnapshot(
    messageId: Long,
    conversationId: String,
    snapshot: AssistantStreamSnapshot,
    elapsedMillis: Long? = snapshot.runElapsedMillis,
    rounds: Int? = snapshot.runRounds,
    steps: Int? = snapshot.runSteps,
    status: ChatRunStatus? = null,
): ChatRunMetrics = ChatRunMetrics(
    messageId = messageId,
    conversationId = conversationId,
    rounds = rounds,
    steps = steps,
    usage = snapshot.usage,
    elapsedMillis = elapsedMillis,
    firstRoundCompleted = snapshot.messages.firstOrNull { it.role == "assistant" }
        ?.status == AssistantMessageStatus.COMPLETED,
    status = status ?: snapshot.runStatus?.toChatRunStatus() ?: when {
        snapshot.messages.any { it.status == AssistantMessageStatus.FAILED } -> ChatRunStatus.Failed
        snapshot.messages.any { it.status == AssistantMessageStatus.CANCELLED } -> ChatRunStatus.Cancelled
        snapshot.messages.any { it.status == AssistantMessageStatus.INCOMPLETE } -> ChatRunStatus.Failed
        !snapshot.isFinal -> ChatRunStatus.Running
        snapshot.toolCalls.any { it.status in setOf(
            AssistantStreamEvent.ToolStatus.PENDING, AssistantStreamEvent.ToolStatus.STREAMING_ARGUMENTS,
            AssistantStreamEvent.ToolStatus.APPROVED, AssistantStreamEvent.ToolStatus.RUNNING,
            AssistantStreamEvent.ToolStatus.INCOMPLETE,
        ) } -> ChatRunStatus.Failed
        snapshot.subagents.any { it.status in setOf(
            AssistantStreamEvent.SubagentStatus.PENDING, AssistantStreamEvent.SubagentStatus.STARTING,
            AssistantStreamEvent.SubagentStatus.RUNNING, AssistantStreamEvent.SubagentStatus.INCOMPLETE,
        ) } -> ChatRunStatus.Failed
        snapshot.searches.any { it.status in setOf(
            AssistantStreamEvent.SearchStatus.PENDING, AssistantStreamEvent.SearchStatus.RUNNING,
            AssistantStreamEvent.SearchStatus.INCOMPLETE,
        ) } -> ChatRunStatus.Failed
        snapshot.messages.isNotEmpty() && snapshot.messages.all { it.status == AssistantMessageStatus.COMPLETED } ->
            ChatRunStatus.Completed
        else -> ChatRunStatus.Failed
    },
)
