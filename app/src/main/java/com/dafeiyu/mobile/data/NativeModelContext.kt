package com.dafeiyu.mobile.data

import org.json.JSONArray
import org.json.JSONObject

/** Convert compatible public history without replaying historical tool calls. */
internal fun piContextFromHistory(history: List<DeepSeekChatClient.HistoryMessage>, model: RuntimeModel): JSONObject {
    val messages = JSONArray()
    val system = history.filter { it.role == "system" }.joinToString("\n\n") { it.content }
    history.filter { it.role in setOf("user", "assistant") }.forEach { item ->
        if (item.role == "user") {
            val message = PiAiTranscriptAdapter.user(item.content)
            if (item.images.isNotEmpty()) {
                val content = JSONArray().put(JSONObject().put("type", "text").put("text", item.content))
                item.images.forEach { image ->
                    require(image.startsWith("data:image/") && image.contains(";base64,")) { "图片上下文格式不支持" }
                    val mime = image.substringAfter("data:").substringBefore(';')
                    require(mime in setOf("image/jpeg", "image/png", "image/gif", "image/webp"))
                    content.put(JSONObject().put("type", "image").put("mimeType", mime)
                        .put("data", image.substringAfter(";base64,")))
                }
                message.put("content", content)
            }
            messages.put(message)
        } else if (item.content.isNotBlank()) {
            messages.put(PiAiTranscriptAdapter.assistant(JSONObject().put("role", "assistant")
                .put("api", model.api).put("provider", model.providerId).put("model", model.id)
                .put("stopReason", "stop").put("content", JSONArray().put(JSONObject()
                    .put("type", "text").put("text", item.content)))
                .put("usage", JSONObject().put("input", 0).put("output", 0).put("cacheRead", 0)
                    .put("cacheWrite", 0).put("totalTokens", 0))))
        }
    }
    return JSONObject().put("messages", messages).put("systemPrompt", system).also {
        require(it.toString().toByteArray().size <= 4 * 1024 * 1024) { "完整上下文超过 4 MiB" }
    }
}
