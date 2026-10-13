package com.dafeiyu.mobile.data

/**
 * DeepSeek controls are derived from the selected model's real /models metadata.
 * `none` is deliberately absent from effort.supported_levels: the official chat
 * protocol declares it separately as the switch to non-thinking mode.
 */
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

    data class Option(val id: String, val label: String)
    data class Selection(val thinking: Boolean?, val reasoningEffort: String?)

    fun canToggle(model: DeepSeekChatClient.Model): Boolean =
        canToggle(model.id) || model.supportedEffortLevels.isNotEmpty()

    /** API ordering is significant; no unadvertised effort is added. */
    fun options(model: DeepSeekChatClient.Model): List<Option> {
        validateMetadata(model)
        if (model.supportedEffortLevels.isNotEmpty()) return buildList {
            if (canToggle(model)) add(Option("none", "不思考"))
            if (model.defaultEffortLevel == null) add(Option("default", "默认推理"))
            model.supportedEffortLevels.forEach { add(Option(it, label(it))) }
        }
        // Old catalogs did not expose effort. Keep only documented toggle support
        // until the user refreshes the catalog; never fabricate low/high/max.
        return if (canToggle(model.id)) listOf(Option("none", "不思考"), Option("default", "思考"))
        else emptyList()
    }

    fun defaultOptionId(model: DeepSeekChatClient.Model): String? {
        val options = options(model)
        return model.defaultEffortLevel?.takeIf { effort -> options.any { it.id == effort } }
            ?: options.firstOrNull { it.id == "default" }?.id
    }

    /** Switching or refreshing models retains a choice only if the new model supports it. */
    fun normalizeOptionId(model: DeepSeekChatClient.Model, selectedId: String?): String? =
        selectedId?.takeIf { selected -> options(model).any { it.id == selected } }
            ?: defaultOptionId(model)

    fun selection(model: DeepSeekChatClient.Model, optionId: String?): Selection {
        if (optionId == null) {
            if (options(model).isNotEmpty()) return selection(model, defaultOptionId(model))
            return Selection(null, null)
        }
        require(options(model).any { it.id == optionId }) { "The selected model does not support this reasoning level" }
        return when (optionId) {
            "none" -> Selection(false, "none")
            "default" -> Selection(true, null)
            else -> Selection(true, optionId)
        }
    }

    /** Fail local preflight on stale capability or contradictory toggle/effort values. */
    internal fun validateRequest(
        modelId: String,
        thinking: Boolean?,
        reasoningEffort: String?,
        modelCapabilities: DeepSeekChatClient.Model?,
    ): Selection {
        if (modelCapabilities == null) {
            // Existing callers that only supply thinking keep their compatibility path.
            require(reasoningEffort == null) { "Refresh the selected model's capability before selecting a reasoning level" }
            return Selection(thinking, null)
        }
        require(modelCapabilities.id == modelId) { "Reasoning capability belongs to another model" }
        validateMetadata(modelCapabilities)
        if (reasoningEffort == null) {
            require(thinking == null || canToggle(modelCapabilities)) { "The selected model does not declare thinking control" }
            return Selection(thinking, null)
        }
        if (reasoningEffort == "none") {
            require(canToggle(modelCapabilities)) { "The selected model does not declare non-thinking mode" }
            require(thinking != true) { "Thinking toggle conflicts with reasoning level" }
            return Selection(false, "none")
        }
        require(reasoningEffort in modelCapabilities.supportedEffortLevels) {
            "The selected model does not support this reasoning level"
        }
        require(thinking != false) { "Thinking toggle conflicts with reasoning level" }
        return Selection(true, reasoningEffort)
    }

    internal fun validateMetadata(model: DeepSeekChatClient.Model) {
        val levels = model.supportedEffortLevels
        require(levels.size <= 16 && levels.distinct().size == levels.size)
        require(levels.all { it.isNotBlank() && it.length <= 80 && it.none(Char::isISOControl) &&
            it !in setOf("none", "default") }) { "Invalid DeepSeek effort capability" }
        require(model.defaultEffortLevel == null || model.defaultEffortLevel in levels) {
            "Default DeepSeek effort is not a supported level"
        }
    }

    private fun label(level: String): String = when (level) {
        "low" -> "低"
        "high" -> "高"
        "max" -> "最高"
        else -> level
    }
}
