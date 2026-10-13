package com.dafeiyu.mobile.data

import org.json.JSONObject

data class AndroidDymPreferencePlan(val groups: Map<String, JSONObject>, val warnings: List<String>)

/** Portable preferences contain visible choices only, with no grants, peers or device identity. */
object AndroidDymPreferences {
    fun capture(repository: DemoRepository): JSONObject = JSONObject().put("schema", 1).put("groups", JSONObject()
        .put("appearance", JSONObject().put("themeMode", repository.themeMode)
            .put("accentColor", repository.accentColorKey).put("glassMaterial", repository.glassMaterial)
            .put("pageTransitions", repository.pageTransitions).put("springButtons", repository.springButtons)
            .put("powerSavingMode", repository.powerSavingMode).put("reduceTransparency", repository.reduceTransparency)
            .put("reduceMotion", repository.reduceMotion).put("interfaceScale", repository.interfaceScale))
        .put("notifications", JSONObject().put("enabled", repository.notificationsEnabled)
            .put("live", repository.liveIslandNotifications).put("announcements", repository.announcementNotifications)
            .put("devices", repository.deviceNotifications).put("messages", repository.messageNotifications)
            .put("backend", repository.backendStatusNotifications))
        .put("engine", JSONObject().put("engineMode", repository.engineMode)
            .put("connectionMode", repository.connectionMode).put("autoStartDsh", repository.autoStartDsh)
            .put("keepRunning", repository.keepRunning))
        .put("official", JSONObject().put("streaming", repository.officialEngineStreaming)
            .put("showReasoning", repository.officialEngineShowReasoning)
            .put("expandActivities", repository.officialEngineExpandActivities)
            .put("tokenSavingMode", repository.tokenSavingMode)
            .put("sandboxMode", repository.localSandboxMode))
        .put("downloads", JSONObject().put("source", repository.downloadSource)
            .put("updatePolicy", repository.updatePolicy).put("wifiOnly", repository.updateWifiOnly)
            .put("cleanupCache", repository.autoCleanupCache).put("dshVersionChannel", repository.dshVersionChannel)
            .put("dshUpdatePolicy", repository.dshUpdatePolicy)))

    /** Each group is independently checked, so a bad preference never drops valid conversations. */
    fun parse(json: JSONObject): AndroidDymPreferencePlan {
        val warnings = mutableListOf<String>()
        val accepted = linkedMapOf<String, JSONObject>()
        if (json.opt("schema") != 1 || json.optJSONObject("groups") == null) {
            return AndroidDymPreferencePlan(emptyMap(), listOf("手机偏好版本或分组无效，已跳过手机偏好"))
        }
        val groups = json.getJSONObject("groups")
        for (name in groups.keys()) {
            try {
                val group = groups.getJSONObject(name)
                validate(name, group)
                accepted[name] = JSONObject(group.toString())
            } catch (_: Exception) {
                warnings += "${label(name)}偏好无效，已跳过这一组"
            }
        }
        return AndroidDymPreferencePlan(accepted, warnings)
    }

    fun apply(repository: DemoRepository, plan: AndroidDymPreferencePlan): List<String> {
        val failures = mutableListOf<String>()
        for ((name, group) in plan.groups) {
            // Recheck immediately before mutation; the preview cannot grant permission to unsafe values.
            try {
                validate(name, group)
                when (name) {
                    "appearance" -> {
                        repository.updateThemeMode(group.getString("themeMode"))
                        repository.updateAccentColor(group.getString("accentColor"))
                        repository.updateGlassMaterial(group.getBoolean("glassMaterial"))
                        repository.updatePageTransitions(group.getBoolean("pageTransitions"))
                        repository.updateSpringButtons(group.getBoolean("springButtons"))
                        repository.updatePowerSavingMode(group.getBoolean("powerSavingMode"))
                        repository.updateReduceTransparency(group.getBoolean("reduceTransparency"))
                        repository.updateReduceMotion(group.getBoolean("reduceMotion"))
                        repository.updateInterfaceScale(group.getDouble("interfaceScale").toFloat())
                    }
                    "notifications" -> {
                        repository.updateNotificationsEnabled(group.getBoolean("enabled"))
                        repository.updateLiveIslandNotifications(group.getBoolean("live"))
                        repository.updateAnnouncementNotifications(group.getBoolean("announcements"))
                        repository.updateDeviceNotifications(group.getBoolean("devices"))
                        repository.updateMessageNotifications(group.getBoolean("messages"))
                        repository.updateBackendStatusNotifications(group.getBoolean("backend"))
                    }
                    "engine" -> {
                        repository.updateAutoStartDsh(group.getBoolean("autoStartDsh"))
                        repository.updateKeepRunning(group.getBoolean("keepRunning"))
                        repository.updateConnectionMode(group.getString("connectionMode"))
                        repository.updateEngineMode(group.getString("engineMode"))
                    }
                    "official" -> {
                        repository.updateOfficialEngineStreaming(group.getBoolean("streaming"))
                        repository.updateOfficialEngineShowReasoning(group.getBoolean("showReasoning"))
                        repository.updateOfficialEngineExpandActivities(group.getBoolean("expandActivities"))
                        repository.updateTokenSavingMode(group.getBoolean("tokenSavingMode"))
                        if (group.has("sandboxMode")) {
                            val mode = group.getString("sandboxMode")
                            check(repository.restoreLocalSandboxPreference(mode) && repository.localSandboxMode == mode) {
                                "本地沙箱偏好未完成持久化"
                            }
                        }
                    }
                    "downloads" -> {
                        repository.updateDownloadSource(group.getString("source"))
                        repository.updateUpdatePolicy(group.getString("updatePolicy"))
                        repository.updateUpdateWifiOnly(group.getBoolean("wifiOnly"))
                        repository.updateAutoCleanupCache(group.getBoolean("cleanupCache"))
                        repository.updateDshVersionChannel(group.getString("dshVersionChannel"))
                        repository.updateDshUpdatePolicy(group.getString("dshUpdatePolicy"))
                    }
                }
            } catch (_: Exception) {
                failures += "${label(name)}偏好保存失败，其他有效内容已继续导入"
            }
        }
        return failures
    }

    private fun validate(name: String, group: JSONObject) {
        fun booleans(vararg keys: String) = keys.forEach { require(group.get(it) is Boolean) }
        fun choice(key: String, choices: Set<String>) = require(group.get(key) is String && group.getString(key) in choices)
        val keys = when (name) {
            "appearance" -> {
                choice("themeMode", SettingsContract.THEME_MODES)
                require(group.get("accentColor") is String && group.getString("accentColor").matches(Regex("[a-z0-9-]{1,32}")))
                booleans("glassMaterial", "pageTransitions", "springButtons", "powerSavingMode", "reduceTransparency", "reduceMotion")
                require(group.get("interfaceScale") is Number && group.getDouble("interfaceScale") in .85..1.15)
                setOf("themeMode", "accentColor", "glassMaterial", "pageTransitions", "springButtons", "powerSavingMode", "reduceTransparency", "reduceMotion", "interfaceScale")
            }
            "notifications" -> {
                booleans("enabled", "live", "announcements", "devices", "messages", "backend")
                setOf("enabled", "live", "announcements", "devices", "messages", "backend")
            }
            "engine" -> {
                choice("engineMode", SettingsContract.ENGINE_MODES)
                choice("connectionMode", SettingsContract.CONNECTION_MODES)
                booleans("autoStartDsh", "keepRunning")
                setOf("engineMode", "connectionMode", "autoStartDsh", "keepRunning")
            }
            "official" -> {
                booleans("streaming", "showReasoning", "expandActivities", "tokenSavingMode")
                val required = setOf("streaming", "showReasoning", "expandActivities", "tokenSavingMode")
                if (group.has("sandboxMode")) {
                    choice("sandboxMode", LinuxSandboxContract.modes)
                    required + "sandboxMode"
                } else required
            }
            "downloads" -> {
                choice("source", SettingsContract.DOWNLOAD_SOURCES)
                choice("updatePolicy", SettingsContract.UPDATE_POLICIES)
                choice("dshVersionChannel", SettingsContract.DSH_VERSION_CHANNELS)
                choice("dshUpdatePolicy", SettingsContract.UPDATE_POLICIES)
                booleans("wifiOnly", "cleanupCache")
                setOf("source", "updatePolicy", "wifiOnly", "cleanupCache", "dshVersionChannel", "dshUpdatePolicy")
            }
            else -> error("不支持的偏好分组")
        }
        require(group.keys().asSequence().toSet() == keys)
    }

    private fun label(name: String): String = when (name) {
        "appearance" -> "外观"
        "notifications" -> "通知"
        "engine" -> "引擎"
        "official" -> "轻引擎"
        "downloads" -> "下载与更新"
        else -> "未识别的"
    }
}
