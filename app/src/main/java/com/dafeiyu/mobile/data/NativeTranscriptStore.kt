package com.dafeiyu.mobile.data

import android.content.Context
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** Opaque protocol history stays private; a failed turn can never become runnable context. */
internal class NativeTranscriptStore(private val directory: File) {
    constructor(context: Context) : this(File(context.filesDir, "native-model-history"))

    fun prepare(conversationId: String, model: RuntimeModel, precedingMessageId: Long?,
        history: List<DeepSeekChatClient.HistoryMessage>): JSONObject {
        val compatible = piContextFromHistory(history, model)
        val saved = file(conversationId).read() ?: return compatible
        require(saved.optInt("schema") == 1) { "原始模型历史格式不支持" }
        if (!saved.optBoolean("completed") || saved.optString("provider") != model.providerId ||
            saved.optString("model") != model.id || precedingMessageId == null ||
            saved.optLong("through") != precedingMessageId) return compatible
        val previous = JSONObject(saved.getJSONObject("context").toString())
        val current = compatible.getJSONArray("messages").let { messages ->
            messages.optJSONObject(messages.length() - 1)?.takeIf { it.optString("role") == "user" }
        } ?: return compatible
        previous.getJSONArray("messages").put(JSONObject(current.toString()))
        previous.put("systemPrompt", compatible.optString("systemPrompt"))
        remoteValidateJson(previous, MAX_BYTES)
        return previous
    }

    fun save(conversationId: String, model: RuntimeModel, throughMessageId: Long, context: JSONObject, completed: Boolean) {
        require(throughMessageId >= 0)
        remoteValidateJson(context, MAX_BYTES)
        file(conversationId).write(JSONObject().put("schema", 1).put("provider", model.providerId).put("model", model.id)
            .put("through", throughMessageId).put("completed", completed).put("context", JSONObject(context.toString())))
    }

    private fun file(conversationId: String): RemoteDshJsonFile {
        require(conversationId.isNotBlank() && conversationId.length <= 512)
        val name = MessageDigest.getInstance("SHA-256").digest(conversationId.toByteArray()).joinToString("") { "%02x".format(it) }
        return RemoteDshJsonFile(File(directory, "$name.json"), MAX_BYTES + 4096)
    }

    companion object { private const val MAX_BYTES = 4 * 1024 * 1024 }
}
