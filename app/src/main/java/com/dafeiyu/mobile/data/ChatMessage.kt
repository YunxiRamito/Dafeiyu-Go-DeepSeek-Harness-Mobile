package com.dafeiyu.mobile.data

/** Existing three-argument callers remain compatible; activity data is optional on old messages. */
data class ChatMessage(
    val id: Long,
    val role: String,
    val text: String,
    val streamSnapshot: AssistantStreamSnapshot? = null,
    val modelInput: String? = null,
    val attachmentIds: List<String> = emptyList(),
)
