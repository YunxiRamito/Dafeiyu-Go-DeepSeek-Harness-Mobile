package com.dafeiyu.mobile.data

/** Only complete backend answers enter the next turn; failed/demo output and thinking stay local. */
object OfficialChatHistory {
    fun build(
        messages: List<ChatMessage>,
        includeToolTurns: Boolean = false,
        imageData: Map<String, String> = emptyMap(),
        // A complete catalog snapshot: true for images, false for text attachments.
        attachmentCatalog: Map<String, Boolean>? = null,
    ): List<DeepSeekChatClient.HistoryMessage> {
        val history = mutableListOf<DeepSeekChatClient.HistoryMessage>()
        fun userHistory(user: ChatMessage): DeepSeekChatClient.HistoryMessage {
            val images = user.attachmentIds.mapNotNull { id ->
                if (attachmentCatalog == null) imageData[id]
                else {
                    val isImage = attachmentCatalog[id]
                        ?: throw IllegalArgumentException("attachment_metadata_missing")
                    if (isImage) imageData[id]?.takeIf { it.isNotBlank() }
                        ?: throw IllegalArgumentException("attachment_image_missing")
                    else null
                }
            }
            return DeepSeekChatClient.HistoryMessage("user", user.modelInput ?: user.text, images = images)
        }
        var pendingUser: ChatMessage? = null
        messages.forEach { message ->
            if (message.role == "user") pendingUser = message
            else if (message.role == "assistant") {
                val user = pendingUser
                val snapshot = message.streamSnapshot
                if (user != null && snapshot?.source == AssistantStreamSource.BACKEND &&
                    snapshot.isFinal &&
                    (snapshot.runStatus == null || snapshot.runStatus == AssistantRunStatus.COMPLETED) &&
                    (snapshot.toolCalls.isEmpty() || includeToolTurns &&
                        snapshot.toolCalls.all {
                            it.result != null && it.status in setOf(AssistantStreamEvent.ToolStatus.SUCCEEDED,
                                AssistantStreamEvent.ToolStatus.FAILED) && snapshot.activityOrder.any { reference ->
                                reference.kind == AssistantActivityKind.TOOL && reference.id == it.callId &&
                                    snapshot.messages.any { part -> part.messageId == reference.messageId }
                            }
                        }) &&
                    snapshot.messages.isNotEmpty() && snapshot.messages.all {
                        it.status == AssistantMessageStatus.COMPLETED
                    }) {
                    val text = snapshot.messages.flatMap { it.blocks }
                        .filterIsInstance<AssistantContentBlockSnapshot.Text>().joinToString("") { it.text }
                    if (text.isNotBlank()) {
                        history += userHistory(user)
                        if (!includeToolTurns) {
                            history += DeepSeekChatClient.HistoryMessage("assistant", text)
                        } else {
                            // The activity order ties each provider turn to the calls it actually produced.
                            snapshot.messages.forEach { part ->
                                val callIds = snapshot.activityOrder
                                    .filter { it.kind == AssistantActivityKind.TOOL && it.messageId == part.messageId }
                                    .map { it.id }.toSet()
                                val calls = snapshot.toolCalls.filter { it.callId in callIds }
                                history += DeepSeekChatClient.HistoryMessage(
                                    "assistant",
                                    part.blocks.filterIsInstance<AssistantContentBlockSnapshot.Text>()
                                        .joinToString("") { it.text },
                                    reasoningContent = part.blocks.filterIsInstance<AssistantContentBlockSnapshot.Reasoning>()
                                        .filter { it.kind == AssistantReasoningKind.USER_VISIBLE_THINKING }
                                        .joinToString("") { it.text },
                                    toolCalls = calls.map {
                                        DeepSeekChatClient.FunctionCall(it.callId, it.name.orEmpty(), it.arguments)
                                    },
                                )
                                calls.forEach {
                                    history += DeepSeekChatClient.HistoryMessage("tool", it.result.orEmpty(),
                                        toolCallId = it.callId)
                                }
                            }
                        }
                    }
                }
                pendingUser = null
            }
        }
        pendingUser?.let { history += userHistory(it) }
        return history
    }
}
