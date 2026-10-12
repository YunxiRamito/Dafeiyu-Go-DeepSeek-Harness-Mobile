package com.dafeiyu.mobile.data

import java.util.TreeMap

/**
 * Provider-neutral event contract for OpenAI Responses and Anthropic Messages normalization.
 * Adapters assign each normalized event a monotonically increasing, contiguous sequence number;
 * providers without sequence numbers should be numbered locally. Only explicitly user-visible
 * reasoning summaries/thinking may carry text. Hidden raw chain-of-thought must be omitted and,
 * if needed, represented by opaque metadata only. Tool/command data is display-only: no execution
 * or network behavior exists here.
 */
sealed interface AssistantStreamEvent {
    val sequenceNumber: Long

    data class MessageStarted(override val sequenceNumber: Long, val messageId: String, val role: String = "assistant") : AssistantStreamEvent
    data class TextDelta(override val sequenceNumber: Long, val messageId: String, val delta: String, val blockId: String = "text") : AssistantStreamEvent
    data class ReasoningSummaryDelta(override val sequenceNumber: Long, val messageId: String, val blockId: String, val delta: String) : AssistantStreamEvent
    /** Emit only when policy permits display. There is deliberately no hidden-reasoning text event. */
    data class UserVisibleThinkingDelta(override val sequenceNumber: Long, val messageId: String, val blockId: String, val delta: String) : AssistantStreamEvent
    /** Done events replace accumulated text with an authoritative snapshot, never append it. */
    data class ContentBlockDone(
        override val sequenceNumber: Long, val messageId: String, val blockId: String,
        val kind: AssistantReasoningKind? = null, val finalText: String? = null,
    ) : AssistantStreamEvent
    data class ReasoningOpaqueMetadata(
        override val sequenceNumber: Long,
        val messageId: String,
        val blockId: String,
        val provider: String,
        val kind: AssistantReasoningKind = AssistantReasoningKind.SUMMARY,
        val hasSignature: Boolean = false,
        val values: Map<String, String> = emptyMap(),
    ) : AssistantStreamEvent
    data class MessageCompleted(override val sequenceNumber: Long, val messageId: String, val finishReason: String? = null) : AssistantStreamEvent
    data class MessageIncomplete(override val sequenceNumber: Long, val messageId: String, val reason: String? = null) : AssistantStreamEvent
    data class MessageFailed(override val sequenceNumber: Long, val messageId: String, val error: String, val errorCode: String? = null) : AssistantStreamEvent
    data class MessageCancelled(override val sequenceNumber: Long, val messageId: String, val reason: String? = null) : AssistantStreamEvent

    enum class ToolKind { TOOL, COMMAND }
    enum class ApprovalRequirement { NONE, REQUIRED, POLICY_DEPENDENT }
    enum class ToolStatus { PENDING, STREAMING_ARGUMENTS, READY, AWAITING_APPROVAL, APPROVED, RUNNING, SUCCEEDED, FAILED, DENIED, CANCELLED, INCOMPLETE }

    data class ToolCallStarted(
        override val sequenceNumber: Long, val callId: String, val name: String,
        val kind: ToolKind = ToolKind.TOOL, val initialArguments: String = "",
        val approvalRequirement: ApprovalRequirement = ApprovalRequirement.NONE,
        val messageId: String? = null,
    ) : AssistantStreamEvent
    data class ToolArgumentsDelta(override val sequenceNumber: Long, val callId: String, val delta: String) : AssistantStreamEvent
    data class ToolArgumentsDone(override val sequenceNumber: Long, val callId: String, val arguments: String? = null) : AssistantStreamEvent
    data class ToolOutputDelta(override val sequenceNumber: Long, val callId: String, val delta: String) : AssistantStreamEvent
    data class ToolApprovalRequired(override val sequenceNumber: Long, val callId: String, val reason: String? = null) : AssistantStreamEvent
    data class ToolApprovalDecision(override val sequenceNumber: Long, val callId: String, val approved: Boolean, val reason: String? = null) : AssistantStreamEvent
    data class ToolStatusChanged(override val sequenceNumber: Long, val callId: String, val status: ToolStatus) : AssistantStreamEvent
    data class ToolResult(override val sequenceNumber: Long, val callId: String, val displayContent: String) : AssistantStreamEvent
    data class ToolError(override val sequenceNumber: Long, val callId: String, val message: String, val code: String? = null) : AssistantStreamEvent

    enum class SubagentStatus { PENDING, STARTING, RUNNING, COMPLETED, FAILED, CANCELLED, INCOMPLETE }
    data class SubagentStarted(override val sequenceNumber: Long, val agentId: String, val name: String, val parentCallId: String? = null) : AssistantStreamEvent
    data class SubagentStatusChanged(override val sequenceNumber: Long, val agentId: String, val status: SubagentStatus, val detail: String? = null) : AssistantStreamEvent
    data class SubagentTextDelta(override val sequenceNumber: Long, val agentId: String, val delta: String) : AssistantStreamEvent
    data class SubagentCompleted(override val sequenceNumber: Long, val agentId: String, val summary: String? = null) : AssistantStreamEvent
    data class SubagentFailed(override val sequenceNumber: Long, val agentId: String, val error: String) : AssistantStreamEvent
    data class SubagentCancelled(override val sequenceNumber: Long, val agentId: String, val reason: String? = null) : AssistantStreamEvent

    enum class SearchStatus { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED, INCOMPLETE }
    data class SearchStarted(override val sequenceNumber: Long, val searchId: String, val query: String? = null) : AssistantStreamEvent
    data class SearchStatusChanged(override val sequenceNumber: Long, val searchId: String, val status: SearchStatus, val error: String? = null) : AssistantStreamEvent
    data class SearchResultAdded(override val sequenceNumber: Long, val searchId: String, val result: AssistantSearchResult) : AssistantStreamEvent
    data class CitationAdded(
        override val sequenceNumber: Long, val citation: AssistantCitation,
        val messageId: String? = null, val searchId: String? = null,
    ) : AssistantStreamEvent
    /** Usage fields are cumulative snapshots, not increments; null leaves the last known value. */
    data class UsageReported(override val sequenceNumber: Long, val usage: AssistantUsage) : AssistantStreamEvent
}

enum class AssistantMessageStatus { CREATED, IN_PROGRESS, COMPLETED, INCOMPLETE, FAILED, CANCELLED }
/** Session status is independent of the completion of any individual model round. */
enum class AssistantRunStatus { RUNNING, COMPLETED, FAILED, CANCELLED, INCOMPLETE }
enum class AssistantReasoningKind { SUMMARY, USER_VISIBLE_THINKING }
enum class AssistantStreamSource { DEMO, OPENAI, ANTHROPIC, BACKEND }
enum class AssistantActivityKind { TEXT, REASONING, TOOL, SUBAGENT, SEARCH, CITATION }
/** Stable first-seen order across different activity collections. */
data class AssistantActivityReference(
    val kind: AssistantActivityKind, val id: String, val messageId: String? = null,
    val reasoningKind: AssistantReasoningKind? = null,
)

sealed interface AssistantContentBlockSnapshot {
    val blockId: String
    data class Text(override val blockId: String, val text: String, val isComplete: Boolean = false) : AssistantContentBlockSnapshot
    data class Reasoning(
        override val blockId: String,
        val kind: AssistantReasoningKind,
        val text: String,
        val metadata: AssistantReasoningMetadata? = null,
        val isComplete: Boolean = false,
    ) : AssistantContentBlockSnapshot
}

/** Presence flags only; opaque/signature payloads cannot enter the rendering contract. */
data class AssistantReasoningMetadata(val provider: String, val values: Map<String, String> = emptyMap())
data class AssistantMessageSnapshot(
    val messageId: String, val role: String, val status: AssistantMessageStatus,
    val blocks: List<AssistantContentBlockSnapshot>, val finishReason: String? = null,
    val incompleteReason: String? = null, val error: String? = null, val errorCode: String? = null,
)
data class AssistantToolCallSnapshot(
    val callId: String, val name: String?, val kind: AssistantStreamEvent.ToolKind?,
    val arguments: String, val status: AssistantStreamEvent.ToolStatus,
    val approvalRequirement: AssistantStreamEvent.ApprovalRequirement, val approvalReason: String? = null,
    val result: String? = null, val error: String? = null, val errorCode: String? = null,
)
data class AssistantSubagentSnapshot(
    val agentId: String, val name: String?, val parentCallId: String? = null,
    val status: AssistantStreamEvent.SubagentStatus, val output: String,
    val detail: String? = null, val error: String? = null,
)
data class AssistantSearchResult(val id: String, val title: String, val url: String? = null, val snippet: String? = null, val publishedAt: String? = null)
data class AssistantSearchSnapshot(
    val searchId: String, val query: String? = null, val status: AssistantStreamEvent.SearchStatus,
    val results: List<AssistantSearchResult>, val error: String? = null,
)
data class AssistantCitation(
    val id: String, val url: String? = null, val title: String? = null, val quote: String? = null,
    val startIndex: Int? = null, val endIndex: Int? = null,
    val fileId: String? = null, val documentIndex: Int? = null,
    val startPage: Int? = null, val endPage: Int? = null,
    val blockId: String? = null,
)
data class AssistantCitationSnapshot(val citation: AssistantCitation, val messageId: String? = null, val searchId: String? = null)
data class AssistantUsage(
    val inputTokens: Long? = null, val outputTokens: Long? = null, val totalTokens: Long? = null,
    val cachedInputTokens: Long? = null, val reasoningTokens: Long? = null,
    val cacheCreationInputTokens: Long? = null,
)
data class AssistantSequenceGap(val firstMissing: Long, val lastMissing: Long)
data class AssistantStreamSnapshot(
    val messages: List<AssistantMessageSnapshot>, val toolCalls: List<AssistantToolCallSnapshot>,
    val subagents: List<AssistantSubagentSnapshot>, val searches: List<AssistantSearchSnapshot>,
    val citations: List<AssistantCitationSnapshot>, val usage: AssistantUsage?,
    val lastAppliedSequenceNumber: Long?, val nextExpectedSequenceNumber: Long?,
    val pendingSequenceNumbers: List<Long>, val skippedSequenceGaps: List<AssistantSequenceGap>,
    val isFinal: Boolean,
    val source: AssistantStreamSource = AssistantStreamSource.BACKEND,
    val activityOrder: List<AssistantActivityReference> = emptyList(),
    val runElapsedMillis: Long? = null,
    val runRounds: Int? = null,
    val runSteps: Int? = null,
    val runStatus: AssistantRunStatus? = null,
)

/** In-memory ordered reducer. Conflicting duplicate sequence numbers use first-received-wins. */
class AssistantStreamReducer(
    startSequenceNumber: Long = 0L,
    private val source: AssistantStreamSource = AssistantStreamSource.BACKEND,
) {
    enum class ApplyDisposition { APPLIED, BUFFERED, DUPLICATE, REJECTED, AFTER_FINISH }
    data class ApplyResult(val disposition: ApplyDisposition, val snapshot: AssistantStreamSnapshot, val diagnostic: String? = null)

    private data class MutableBlock(
        val blockId: String, val kind: AssistantReasoningKind?, var text: String = "",
        var metadata: AssistantReasoningMetadata? = null,
        var isComplete: Boolean = false,
    )
    private data class MutableMessage(
        val messageId: String, var role: String = "assistant", var status: AssistantMessageStatus = AssistantMessageStatus.CREATED,
        val blocks: LinkedHashMap<String, MutableBlock> = linkedMapOf(), var finishReason: String? = null,
        var incompleteReason: String? = null, var error: String? = null, var errorCode: String? = null,
    )
    private data class MutableTool(
        val callId: String, var name: String? = null, var kind: AssistantStreamEvent.ToolKind? = null,
        var arguments: String = "", var status: AssistantStreamEvent.ToolStatus = AssistantStreamEvent.ToolStatus.PENDING,
        var approvalRequirement: AssistantStreamEvent.ApprovalRequirement = AssistantStreamEvent.ApprovalRequirement.NONE,
        var approvalReason: String? = null, var result: String? = null, var error: String? = null, var errorCode: String? = null,
    )
    private data class MutableSubagent(
        val agentId: String, var name: String? = null, var parentCallId: String? = null,
        var status: AssistantStreamEvent.SubagentStatus = AssistantStreamEvent.SubagentStatus.PENDING,
        var output: String = "", var detail: String? = null, var error: String? = null,
    )
    private data class MutableSearch(
        val searchId: String, var query: String? = null,
        var status: AssistantStreamEvent.SearchStatus = AssistantStreamEvent.SearchStatus.PENDING,
        val results: LinkedHashMap<String, AssistantSearchResult> = linkedMapOf(), var error: String? = null,
    )

    private val pending = TreeMap<Long, AssistantStreamEvent>()
    private val messages = linkedMapOf<String, MutableMessage>()
    private val tools = linkedMapOf<String, MutableTool>()
    private val subagents = linkedMapOf<String, MutableSubagent>()
    private val searches = linkedMapOf<String, MutableSearch>()
    private val citations = linkedMapOf<String, AssistantCitationSnapshot>()
    private val skippedGaps = mutableListOf<AssistantSequenceGap>()
    private val activityOrder = linkedSetOf<AssistantActivityReference>()
    private val opaqueMetadata = linkedMapOf<String, AssistantReasoningMetadata>()
    private var currentUsage: AssistantUsage? = null
    private var lastApplied: Long? = null
    private var nextExpected: Long? = startSequenceNumber
    private var finished = false

    init { require(startSequenceNumber >= 0L && startSequenceNumber < Long.MAX_VALUE) }

    /** Buffer/apply one event; invalid, duplicate, and late events leave state unchanged. */
    @Synchronized
    fun apply(event: AssistantStreamEvent): ApplyResult {
        if (finished) return ApplyResult(ApplyDisposition.AFTER_FINISH, snapshot(), "stream already finished")
        if (event.sequenceNumber < 0L || event.sequenceNumber == Long.MAX_VALUE) {
            return ApplyResult(ApplyDisposition.REJECTED, snapshot(), "sequence number outside supported range")
        }
        if (!isValid(event)) return ApplyResult(ApplyDisposition.REJECTED, snapshot(), "invalid event identifier or usage value")
        val expected = nextExpected ?: return ApplyResult(ApplyDisposition.REJECTED, snapshot(), "sequence space exhausted")
        if (event.sequenceNumber < expected || pending.containsKey(event.sequenceNumber)) {
            return ApplyResult(ApplyDisposition.DUPLICATE, snapshot(), "sequence number already received")
        }
        if (event.sequenceNumber != expected && pending.size >= MAX_PENDING_EVENTS) return ApplyResult(ApplyDisposition.REJECTED, snapshot(), "out-of-order buffer full")
        pending[event.sequenceNumber] = event
        if (event.sequenceNumber != expected) return ApplyResult(ApplyDisposition.BUFFERED, snapshot())
        drainContiguous()
        return ApplyResult(ApplyDisposition.APPLIED, snapshot())
    }

    /** Flush buffered events in sequence order, record gaps, and close this reducer. */
    @Synchronized
    fun finish(): AssistantStreamSnapshot {
        if (finished) return snapshot()
        while (pending.isNotEmpty()) {
            val entry = pending.pollFirstEntry() ?: break
            val (sequence, event) = entry
            val expected = nextExpected ?: break
            if (sequence > expected) skippedGaps += AssistantSequenceGap(expected, sequence - 1L)
            applyOrdered(event)
            lastApplied = sequence
            nextExpected = sequence + 1L
        }
        messages.values.forEach { message ->
            if (message.status == AssistantMessageStatus.CREATED || message.status == AssistantMessageStatus.IN_PROGRESS) {
                message.status = AssistantMessageStatus.INCOMPLETE
                message.incompleteReason = "stream_ended_without_terminal_event"
            }
        }
        tools.values.filter {
            !isToolTerminal(it.status) && it.status != AssistantStreamEvent.ToolStatus.READY &&
                it.status != AssistantStreamEvent.ToolStatus.AWAITING_APPROVAL
        }.forEach { it.status = AssistantStreamEvent.ToolStatus.INCOMPLETE }
        subagents.values.filterNot { isSubagentTerminal(it.status) }.forEach {
            it.status = AssistantStreamEvent.SubagentStatus.INCOMPLETE
            it.detail = "stream_ended_without_terminal_event"
        }
        searches.values.filterNot { isSearchTerminal(it.status) }.forEach {
            it.status = AssistantStreamEvent.SearchStatus.INCOMPLETE
            it.error = "stream_ended_without_terminal_event"
        }
        finished = true
        return snapshot()
    }

    @Synchronized
    fun snapshot(): AssistantStreamSnapshot = AssistantStreamSnapshot(
        messages = messages.values.map { it.toSnapshot() },
        toolCalls = tools.values.map { it.toSnapshot() },
        subagents = subagents.values.map { it.toSnapshot() },
        searches = searches.values.map { it.toSnapshot() },
        citations = citations.values.toList(), usage = currentUsage,
        lastAppliedSequenceNumber = lastApplied, nextExpectedSequenceNumber = nextExpected,
        pendingSequenceNumbers = pending.keys.toList(), skippedSequenceGaps = skippedGaps.toList(), isFinal = finished,
        source = source, activityOrder = activityOrder.toList(),
    )

    private fun drainContiguous() {
        while (true) {
            val expected = nextExpected ?: return
            val event = pending.remove(expected) ?: return
            applyOrdered(event)
            lastApplied = expected
            nextExpected = expected + 1L
        }
    }

    private fun applyOrdered(event: AssistantStreamEvent) {
        when (event) {
            is AssistantStreamEvent.MessageStarted -> {
                val message = message(event.messageId)
                if (message.status == AssistantMessageStatus.CREATED) {
                    message.role = event.role
                    message.status = AssistantMessageStatus.IN_PROGRESS
                }
            }
            is AssistantStreamEvent.TextDelta -> writableMessage(event.messageId)?.let { appendBlock(it, event.blockId, null, event.delta) }
            is AssistantStreamEvent.ReasoningSummaryDelta -> writableMessage(event.messageId)?.let {
                appendBlock(it, event.blockId, AssistantReasoningKind.SUMMARY, event.delta)
            }
            is AssistantStreamEvent.UserVisibleThinkingDelta -> writableMessage(event.messageId)?.let {
                appendBlock(it, event.blockId, AssistantReasoningKind.USER_VISIBLE_THINKING, event.delta)
            }
            is AssistantStreamEvent.ContentBlockDone -> writableMessage(event.messageId)?.let {
                val block = contentBlock(it, event.blockId, event.kind)
                event.finalText?.let { text -> block.text = text }
                block.isComplete = true
            }
            is AssistantStreamEvent.ReasoningOpaqueMetadata -> writableMessage(event.messageId)?.let { message ->
                val key = "${message.messageId}:${blockKey(event.blockId, event.kind)}"
                val previous = opaqueMetadata[key]
                val merged = linkedMapOf<String, String>()
                previous?.values?.toSortedMap()?.let { merged.putAll(it) }
                event.values.toSortedMap().let { merged.putAll(it) }
                // Do not retain opaque payloads in UI snapshots or create empty thinking cards.
                val metadata = AssistantReasoningMetadata(
                    provider = event.provider,
                    values = merged.filterKeys { it.endsWith("_present") }.mapValues { "true" } +
                        if (event.hasSignature) mapOf("signature_present" to "true") else emptyMap(),
                )
                opaqueMetadata[key] = metadata
                message.blocks[blockKey(event.blockId, event.kind)]?.metadata = metadata
            }
            is AssistantStreamEvent.MessageCompleted -> terminalMessage(event.messageId) {
                it.status = AssistantMessageStatus.COMPLETED; it.finishReason = event.finishReason
            }
            is AssistantStreamEvent.MessageIncomplete -> terminalMessage(event.messageId) {
                it.status = AssistantMessageStatus.INCOMPLETE; it.incompleteReason = event.reason
            }
            is AssistantStreamEvent.MessageFailed -> terminalMessage(event.messageId) {
                it.status = AssistantMessageStatus.FAILED; it.error = event.error; it.errorCode = event.errorCode
            }
            is AssistantStreamEvent.MessageCancelled -> {
                terminalMessage(event.messageId) {
                    it.status = AssistantMessageStatus.CANCELLED; it.incompleteReason = event.reason
                }
                // Cancellation can arrive while tools run after this model round completed.
                tools.values.filterNot { tool -> isToolTerminal(tool.status) }.forEach { tool ->
                    tool.status = AssistantStreamEvent.ToolStatus.CANCELLED
                }
                subagents.values.filterNot { agent -> isSubagentTerminal(agent.status) }.forEach { agent ->
                    agent.status = AssistantStreamEvent.SubagentStatus.CANCELLED
                }
                searches.values.filterNot { search -> isSearchTerminal(search.status) }.forEach { search ->
                    search.status = AssistantStreamEvent.SearchStatus.CANCELLED
                }
            }
            is AssistantStreamEvent.ToolCallStarted -> {
                val tool = tool(event.callId, event.messageId)
                if (tool.name == null) tool.name = event.name
                if (tool.kind == null) tool.kind = event.kind
                if (tool.arguments.isEmpty()) tool.arguments = event.initialArguments
                if (tool.approvalRequirement == AssistantStreamEvent.ApprovalRequirement.NONE) {
                    tool.approvalRequirement = event.approvalRequirement
                }
            }
            is AssistantStreamEvent.ToolArgumentsDelta -> {
                val tool = tool(event.callId)
                if (!isToolTerminal(tool.status)) {
                    tool.arguments += event.delta
                    if (tool.status == AssistantStreamEvent.ToolStatus.PENDING) tool.status = AssistantStreamEvent.ToolStatus.STREAMING_ARGUMENTS
                }
            }
            is AssistantStreamEvent.ToolArgumentsDone -> {
                val tool = tool(event.callId)
                if (!isToolTerminal(tool.status)) {
                    event.arguments?.let { tool.arguments = it }
                    if (tool.status in setOf(AssistantStreamEvent.ToolStatus.PENDING, AssistantStreamEvent.ToolStatus.STREAMING_ARGUMENTS)) {
                        tool.status = AssistantStreamEvent.ToolStatus.READY
                    }
                }
            }
            is AssistantStreamEvent.ToolOutputDelta -> {
                val tool = tool(event.callId)
                if (!isToolTerminal(tool.status)) tool.result = tool.result.orEmpty() + event.delta
            }
            is AssistantStreamEvent.ToolApprovalRequired -> {
                val tool = tool(event.callId)
                if (!isToolTerminal(tool.status)) {
                    tool.approvalReason = event.reason
                    tool.approvalRequirement = AssistantStreamEvent.ApprovalRequirement.REQUIRED
                    tool.status = AssistantStreamEvent.ToolStatus.AWAITING_APPROVAL
                }
            }
            is AssistantStreamEvent.ToolApprovalDecision -> {
                val tool = tool(event.callId)
                if (!isToolTerminal(tool.status)) {
                    tool.approvalReason = event.reason
                    tool.status = if (event.approved) AssistantStreamEvent.ToolStatus.APPROVED else AssistantStreamEvent.ToolStatus.DENIED
                }
            }
            is AssistantStreamEvent.ToolStatusChanged -> {
                val tool = tool(event.callId)
                if (!isToolTerminal(tool.status)) tool.status = event.status
            }
            is AssistantStreamEvent.ToolResult -> {
                val tool = tool(event.callId)
                if (!isToolTerminal(tool.status) || tool.status in setOf(
                        AssistantStreamEvent.ToolStatus.SUCCEEDED, AssistantStreamEvent.ToolStatus.FAILED)) {
                    tool.result = event.displayContent
                    if (tool.status != AssistantStreamEvent.ToolStatus.FAILED) {
                        tool.status = AssistantStreamEvent.ToolStatus.SUCCEEDED
                    }
                }
            }
            is AssistantStreamEvent.ToolError -> {
                val tool = tool(event.callId)
                if (!isToolTerminal(tool.status)) {
                    tool.error = event.message; tool.errorCode = event.code; tool.status = AssistantStreamEvent.ToolStatus.FAILED
                }
            }
            is AssistantStreamEvent.SubagentStarted -> {
                val agent = subagent(event.agentId)
                if (agent.name == null) agent.name = event.name
                if (agent.parentCallId == null) agent.parentCallId = event.parentCallId
                if (agent.status == AssistantStreamEvent.SubagentStatus.PENDING) agent.status = AssistantStreamEvent.SubagentStatus.STARTING
            }
            is AssistantStreamEvent.SubagentStatusChanged -> {
                val agent = subagent(event.agentId)
                if (!isSubagentTerminal(agent.status)) {
                    agent.status = event.status; agent.detail = event.detail ?: agent.detail
                }
            }
            is AssistantStreamEvent.SubagentTextDelta -> {
                val agent = subagent(event.agentId)
                if (!isSubagentTerminal(agent.status)) agent.output += event.delta
            }
            is AssistantStreamEvent.SubagentCompleted -> {
                val agent = subagent(event.agentId)
                if (!isSubagentTerminal(agent.status)) {
                    agent.status = AssistantStreamEvent.SubagentStatus.COMPLETED
                    agent.detail = event.summary ?: agent.detail
                }
            }
            is AssistantStreamEvent.SubagentFailed -> {
                val agent = subagent(event.agentId)
                if (!isSubagentTerminal(agent.status)) { agent.status = AssistantStreamEvent.SubagentStatus.FAILED; agent.error = event.error }
            }
            is AssistantStreamEvent.SubagentCancelled -> {
                val agent = subagent(event.agentId)
                if (!isSubagentTerminal(agent.status)) {
                    agent.status = AssistantStreamEvent.SubagentStatus.CANCELLED; agent.detail = event.reason ?: agent.detail
                }
            }
            is AssistantStreamEvent.SearchStarted -> {
                val search = search(event.searchId)
                if (search.query == null) search.query = event.query
                if (search.status == AssistantStreamEvent.SearchStatus.PENDING) search.status = AssistantStreamEvent.SearchStatus.RUNNING
            }
            is AssistantStreamEvent.SearchStatusChanged -> {
                val search = search(event.searchId)
                if (!isSearchTerminal(search.status)) {
                    search.status = event.status; search.error = event.error ?: search.error
                }
            }
            is AssistantStreamEvent.SearchResultAdded -> {
                search(event.searchId).results.putIfAbsent(event.result.id, event.result)
            }
            is AssistantStreamEvent.CitationAdded -> {
                activityOrder += AssistantActivityReference(AssistantActivityKind.CITATION, event.citation.id, event.messageId)
                citations.putIfAbsent(event.citation.id, AssistantCitationSnapshot(event.citation, event.messageId, event.searchId))
            }
            is AssistantStreamEvent.UsageReported -> currentUsage = mergeUsage(currentUsage, event.usage)
        }
    }

    private fun message(id: String): MutableMessage = messages.getOrPut(id) { MutableMessage(id) }
    private fun writableMessage(id: String): MutableMessage? {
        val message = message(id)
        if (message.status == AssistantMessageStatus.CREATED) message.status = AssistantMessageStatus.IN_PROGRESS
        return message.takeUnless { isMessageTerminal(it.status) }
    }
    private fun terminalMessage(id: String, update: (MutableMessage) -> Unit) {
        val message = message(id)
        if (!isMessageTerminal(message.status)) update(message)
    }
    private fun appendBlock(message: MutableMessage, id: String, kind: AssistantReasoningKind?, delta: String) {
        val block = contentBlock(message, id, kind)
        if (!block.isComplete) block.text += delta
    }
    private fun contentBlock(message: MutableMessage, id: String, kind: AssistantReasoningKind?): MutableBlock {
        activityOrder += AssistantActivityReference(
            if (kind == null) AssistantActivityKind.TEXT else AssistantActivityKind.REASONING,
            id, message.messageId, kind,
        )
        return message.blocks.getOrPut(blockKey(id, kind)) {
            MutableBlock(id, kind, metadata = opaqueMetadata["${message.messageId}:${blockKey(id, kind)}"])
        }
    }
    private fun tool(id: String, messageId: String? = null): MutableTool = tools.getOrPut(id) {
        activityOrder += AssistantActivityReference(AssistantActivityKind.TOOL, id, messageId)
        MutableTool(id)
    }
    private fun subagent(id: String): MutableSubagent = subagents.getOrPut(id) {
        activityOrder += AssistantActivityReference(AssistantActivityKind.SUBAGENT, id)
        MutableSubagent(id)
    }
    private fun search(id: String): MutableSearch = searches.getOrPut(id) {
        activityOrder += AssistantActivityReference(AssistantActivityKind.SEARCH, id)
        MutableSearch(id)
    }

    private fun isValid(event: AssistantStreamEvent): Boolean {
        fun validId(value: String?): Boolean = value != null && value.isNotBlank() && value.length <= MAX_ID_LENGTH &&
            value.none { it.isISOControl() }
        return when (event) {
            is AssistantStreamEvent.MessageStarted -> validId(event.messageId) && validId(event.role)
            is AssistantStreamEvent.TextDelta -> validId(event.messageId) && validId(event.blockId)
            is AssistantStreamEvent.ReasoningSummaryDelta -> validId(event.messageId) && validId(event.blockId)
            is AssistantStreamEvent.UserVisibleThinkingDelta -> validId(event.messageId) && validId(event.blockId)
            is AssistantStreamEvent.ContentBlockDone -> validId(event.messageId) && validId(event.blockId)
            is AssistantStreamEvent.ReasoningOpaqueMetadata -> validId(event.messageId) && validId(event.blockId) &&
                validId(event.provider) && event.values.keys.all { validId(it) }
            is AssistantStreamEvent.MessageCompleted -> validId(event.messageId)
            is AssistantStreamEvent.MessageIncomplete -> validId(event.messageId)
            is AssistantStreamEvent.MessageFailed -> validId(event.messageId)
            is AssistantStreamEvent.MessageCancelled -> validId(event.messageId)
            is AssistantStreamEvent.ToolCallStarted -> validId(event.callId) && validId(event.name) &&
                (event.messageId == null || validId(event.messageId))
            is AssistantStreamEvent.ToolArgumentsDelta -> validId(event.callId)
            is AssistantStreamEvent.ToolArgumentsDone -> validId(event.callId)
            is AssistantStreamEvent.ToolOutputDelta -> validId(event.callId)
            is AssistantStreamEvent.ToolApprovalRequired -> validId(event.callId)
            is AssistantStreamEvent.ToolApprovalDecision -> validId(event.callId)
            is AssistantStreamEvent.ToolStatusChanged -> validId(event.callId)
            is AssistantStreamEvent.ToolResult -> validId(event.callId)
            is AssistantStreamEvent.ToolError -> validId(event.callId)
            is AssistantStreamEvent.SubagentStarted -> validId(event.agentId) && validId(event.name) &&
                (event.parentCallId == null || validId(event.parentCallId))
            is AssistantStreamEvent.SubagentStatusChanged -> validId(event.agentId)
            is AssistantStreamEvent.SubagentTextDelta -> validId(event.agentId)
            is AssistantStreamEvent.SubagentCompleted -> validId(event.agentId)
            is AssistantStreamEvent.SubagentFailed -> validId(event.agentId)
            is AssistantStreamEvent.SubagentCancelled -> validId(event.agentId)
            is AssistantStreamEvent.SearchStarted -> validId(event.searchId)
            is AssistantStreamEvent.SearchStatusChanged -> validId(event.searchId)
            is AssistantStreamEvent.SearchResultAdded -> validId(event.searchId) && validId(event.result.id)
            is AssistantStreamEvent.CitationAdded -> validId(event.citation.id) &&
                (event.messageId == null || validId(event.messageId)) && (event.searchId == null || validId(event.searchId))
            is AssistantStreamEvent.UsageReported -> listOfNotNull(
                event.usage.inputTokens, event.usage.outputTokens, event.usage.totalTokens,
                event.usage.cachedInputTokens, event.usage.reasoningTokens,
                event.usage.cacheCreationInputTokens,
            ).all { it >= 0L }
        }
    }

    private fun mergeUsage(old: AssistantUsage?, next: AssistantUsage) = AssistantUsage(
        inputTokens = next.inputTokens ?: old?.inputTokens,
        outputTokens = next.outputTokens ?: old?.outputTokens,
        totalTokens = next.totalTokens ?: old?.totalTokens,
        cachedInputTokens = next.cachedInputTokens ?: old?.cachedInputTokens,
        reasoningTokens = next.reasoningTokens ?: old?.reasoningTokens,
        cacheCreationInputTokens = next.cacheCreationInputTokens ?: old?.cacheCreationInputTokens,
    )
    private fun blockKey(id: String, kind: AssistantReasoningKind?): String = "${kind?.name ?: "TEXT"}:$id"
    private fun isMessageTerminal(s: AssistantMessageStatus) = s in setOf(
        AssistantMessageStatus.COMPLETED, AssistantMessageStatus.INCOMPLETE,
        AssistantMessageStatus.FAILED, AssistantMessageStatus.CANCELLED,
    )
    private fun isToolTerminal(s: AssistantStreamEvent.ToolStatus) = s in setOf(
        AssistantStreamEvent.ToolStatus.SUCCEEDED, AssistantStreamEvent.ToolStatus.FAILED,
        AssistantStreamEvent.ToolStatus.DENIED, AssistantStreamEvent.ToolStatus.CANCELLED,
        AssistantStreamEvent.ToolStatus.INCOMPLETE,
    )
    private fun isSubagentTerminal(s: AssistantStreamEvent.SubagentStatus) = s in setOf(
        AssistantStreamEvent.SubagentStatus.COMPLETED, AssistantStreamEvent.SubagentStatus.FAILED,
        AssistantStreamEvent.SubagentStatus.CANCELLED,
        AssistantStreamEvent.SubagentStatus.INCOMPLETE,
    )
    private fun isSearchTerminal(s: AssistantStreamEvent.SearchStatus) = s in setOf(
        AssistantStreamEvent.SearchStatus.COMPLETED, AssistantStreamEvent.SearchStatus.FAILED,
        AssistantStreamEvent.SearchStatus.CANCELLED,
        AssistantStreamEvent.SearchStatus.INCOMPLETE,
    )

    private fun MutableMessage.toSnapshot() = AssistantMessageSnapshot(
        messageId = messageId, role = role, status = status,
        blocks = blocks.values.map { block ->
            if (block.kind == null) AssistantContentBlockSnapshot.Text(block.blockId, block.text, block.isComplete)
            else AssistantContentBlockSnapshot.Reasoning(
                blockId = block.blockId, kind = block.kind, text = block.text,
                metadata = block.metadata?.let { it.copy(values = it.values.toMap()) },
                isComplete = block.isComplete,
            )
        },
        finishReason = finishReason, incompleteReason = incompleteReason, error = error, errorCode = errorCode,
    )
    private fun MutableTool.toSnapshot() = AssistantToolCallSnapshot(
        callId = callId, name = name, kind = kind, arguments = arguments, status = status,
        approvalRequirement = approvalRequirement, approvalReason = approvalReason,
        result = result, error = error, errorCode = errorCode,
    )
    private fun MutableSubagent.toSnapshot() = AssistantSubagentSnapshot(
        agentId = agentId, name = name, parentCallId = parentCallId, status = status,
        output = output, detail = detail, error = error,
    )
    private fun MutableSearch.toSnapshot() = AssistantSearchSnapshot(
        searchId = searchId, query = query, status = status, results = results.values.toList(), error = error,
    )

    companion object {
        private const val MAX_ID_LENGTH = 512
        private const val MAX_PENDING_EVENTS = 2_048
    }
}
