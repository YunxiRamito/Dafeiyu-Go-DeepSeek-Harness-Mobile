package com.dafeiyu.mobile.data

/** An execution instruction, separate from the provider's reasoning effort. */
object OfficialTokenSavingPolicy {
    const val INSTRUCTION = "Token-saving mode is enabled. Solve the user's task with the fewest useful steps and tokens. " +
        "Keep answers concise, avoid repeated exploration and redundant tool calls, batch independent work when possible, " +
        "and reuse successful tool results. Complete the requested task and preserve correctness; do not omit necessary work " +
        "or claim success without evidence to save tokens."

    fun applyToSystemMessage(
        message: DeepSeekChatClient.HistoryMessage,
        enabled: Boolean,
    ): DeepSeekChatClient.HistoryMessage {
        require(message.role == "system")
        return if (enabled && !message.content.contains(INSTRUCTION)) {
            message.copy(content = message.content + "\n\n" + INSTRUCTION)
        } else message
    }
}
