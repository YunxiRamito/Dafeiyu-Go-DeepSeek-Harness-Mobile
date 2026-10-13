package com.dafeiyu.mobile.data

import org.json.JSONArray
import org.json.JSONObject

/** One decoder for remote metadata and persisted catalogs, including legacy UI-only caches. */
object DeepSeekModelCatalog {
    private const val MAX_MODELS = 1024
    private const val MAX_CACHE_CHARACTERS = 512_000

    fun decode(root: JSONObject): List<DeepSeekChatClient.Model> {
        require(root.optString("object") == "list") { "Invalid DeepSeek model catalog" }
        val data = root.optJSONArray("data") ?: error("Missing DeepSeek model list")
        require(data.length() <= MAX_MODELS)
        val ids = mutableSetOf<String>()
        return List(data.length()) { index ->
            val item = data.optJSONObject(index) ?: error("Invalid DeepSeek model")
            val id = item.optionalString("id", 512) ?: error("Missing DeepSeek model ID")
            require(id.isNotBlank() && ids.add(id)) { "Invalid or duplicate DeepSeek model ID" }
            val effortValue = item.opt("effort")
            val effort = when (effortValue) {
                null, JSONObject.NULL -> null
                is JSONObject -> effortValue
                else -> error("Invalid DeepSeek effort capability")
            }
            if (effort != null) require(effort.optJSONArray("supported_levels") != null) {
                "Missing supported DeepSeek effort levels"
            }
            DeepSeekChatClient.Model(
                id = id,
                name = item.optionalString("name", 512),
                ownedBy = item.optionalString("owned_by", 512),
                contextWindow = item.optionalLong("context_window"),
                maxOutputTokens = item.optionalLong("max_output_tokens"),
                inputModalities = item.strings("input_modalities", 16, 64),
                outputModalities = item.strings("output_modalities", 16, 64),
                supportedEffortLevels = effort?.strings("supported_levels", 16, 80).orEmpty(),
                defaultEffortLevel = effort?.optionalString("default_level", 80),
            ).also(DeepSeekThinkingPolicy::validateMetadata)
        }
    }

    fun encodeCache(models: List<DeepSeekChatClient.Model>): String {
        require(models.size <= MAX_MODELS && models.map { it.id }.distinct().size == models.size)
        val data = JSONArray()
        models.forEach { model ->
            DeepSeekThinkingPolicy.validateMetadata(model)
            val item = JSONObject().put("id", model.id)
                .put("input_modalities", JSONArray(model.inputModalities))
                .put("output_modalities", JSONArray(model.outputModalities))
            model.name?.let { item.put("name", it) }
            model.ownedBy?.let { item.put("owned_by", it) }
            model.contextWindow?.let { item.put("context_window", it) }
            model.maxOutputTokens?.let { item.put("max_output_tokens", it) }
            if (model.supportedEffortLevels.isNotEmpty() || model.defaultEffortLevel != null) {
                val effort = JSONObject().put("supported_levels", JSONArray(model.supportedEffortLevels))
                model.defaultEffortLevel?.let { effort.put("default_level", it) }
                item.put("effort", effort)
            }
            data.put(item)
        }
        val root = JSONObject().put("object", "list").put("cache_version", 1).put("data", data)
        // Enforce the same source constraints before writing anything to preferences.
        decode(root)
        return root.toString().also { require(it.length <= MAX_CACHE_CHARACTERS) }
    }

    fun decodeCache(raw: String): List<DeepSeekChatClient.Model> {
        require(raw.length <= MAX_CACHE_CHARACTERS)
        val trimmed = raw.trimStart()
        if (trimmed.startsWith("[")) return decode(JSONObject().put("object", "list").put("data", JSONArray(raw)))
        val root = JSONObject(raw)
        require(root.optInt("cache_version", 1) == 1) { "Unsupported DeepSeek catalog cache" }
        return decode(root)
    }

    private fun JSONObject.optionalString(name: String, limit: Int): String? {
        val value = opt(name)
        if (value == null || value === JSONObject.NULL) return null
        require(value is String && value.length <= limit && value.none(Char::isISOControl))
        return value
    }

    private fun JSONObject.optionalLong(name: String): Long? {
        val value = opt(name)
        if (value == null || value === JSONObject.NULL) return null
        val result = (value as? Number)?.toString()?.toLongOrNull() ?: error("Invalid model token limit")
        require(result >= 0)
        return result
    }

    private fun JSONObject.strings(name: String, limit: Int, stringLimit: Int): List<String> {
        if (!has(name) || isNull(name)) return emptyList()
        val array = optJSONArray(name) ?: error("Invalid model capability list")
        require(array.length() <= limit)
        return List(array.length()) { index ->
            val value = array.opt(index)
            require(value is String && value.isNotBlank() && value.length <= stringLimit && value.none(Char::isISOControl))
            value
        }
    }
}
