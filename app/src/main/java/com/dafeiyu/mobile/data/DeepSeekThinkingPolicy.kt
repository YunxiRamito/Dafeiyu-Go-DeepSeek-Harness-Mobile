package com.dafeiyu.mobile.data

/** Confirmed thinking toggle support; model discovery and selection still use the remote list. */
object DeepSeekThinkingPolicy {
    // https://api-docs.deepseek.com/api/create-chat-completion
    // https://api-docs.deepseek.com/quick_start (Flash compatibility aliases)
    fun canToggle(modelId: String): Boolean = when (modelId) {
        "deepseek-flash",
        "deepseek-v4-pro",
        "deepseek-v4-flash",
        "deepseek-v4-flash-vision-exp" -> true
        else -> false
    }
}
