package com.dafeiyu.mobile.data

/** The repository supplies these facts without giving the policy access to Android or disk APIs. */
internal data class ConversationDeleteFacts(
    val hasMessages: Boolean = false,
    val hasDraft: Boolean = false,
    val hasAttachments: Boolean = false,
    val hasTasks: Boolean = false,
    val hasOutputs: Boolean = false,
)

internal fun isAutoCreatedEmptyConversation(
    conversation: Conversation,
    facts: ConversationDeleteFacts,
): Boolean = conversation.archivedAtMillis == null &&
    conversation.title == "新对话" &&
    !facts.hasMessages &&
    !facts.hasDraft &&
    !facts.hasAttachments &&
    !facts.hasTasks &&
    !facts.hasOutputs

internal fun canDeleteEmptyWorkspace(
    workspaceId: String,
    workspaceCount: Int,
    conversations: List<Conversation>,
    factsByConversationId: Map<String, ConversationDeleteFacts>,
): Boolean {
    if (workspaceCount < 1) return false
    return conversations
        .filter { it.workspaceId == workspaceId }
        .all { isAutoCreatedEmptyConversation(it, factsByConversationId[it.id] ?: ConversationDeleteFacts()) }
}

internal fun firstValidConversation(
    conversations: List<Conversation>,
    workspaceIds: Set<String>,
): Conversation? = conversations.firstOrNull {
    it.workspaceId in workspaceIds && it.archivedAtMillis == null
}
