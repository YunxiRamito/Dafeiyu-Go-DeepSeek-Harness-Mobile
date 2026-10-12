package com.dafeiyu.mobile.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence boundary for settings that affect model aggregation and local runtime policy.
 *
 * This class owns only encoding, defaults, and value normalization. UI state remains in
 * DemoRepository so existing callers can keep using the repository as their state holder.
 */
class SettingsRuntimeStore(private val preferences: SharedPreferences) {
    fun read(): SettingsRuntimeSnapshot {
        val providerSettings = readModelProviderSettings()
        return SettingsRuntimeSnapshot(
            modelProviders = providerSettings,
            virtualMachine = VirtualMachineSettings(
                profile = normalizeVirtualMachineProfile(
                    preferences.getString(KEY_VM_PROFILE, DEFAULT_VM_PROFILE).orEmpty(),
                ),
                hardwareAcceleration = preferences.getBoolean(
                    KEY_VM_HARDWARE_ACCELERATION,
                    DEFAULT_VM_HARDWARE_ACCELERATION,
                ),
                backgroundRunning = preferences.getBoolean(
                    KEY_VM_BACKGROUND_RUNNING,
                    DEFAULT_VM_BACKGROUND_RUNNING,
                ),
            ),
            downloadSource = SettingsContract.downloadSource(
                preferences.getString(KEY_DOWNLOAD_SOURCE, DEFAULT_DOWNLOAD_SOURCE).orEmpty(),
            ),
            updatePolicy = SettingsContract.updatePolicy(
                preferences.getString(KEY_UPDATE_POLICY, DEFAULT_UPDATE_POLICY).orEmpty(),
            ),
            dshUpdatePolicy = SettingsContract.updatePolicy(
                preferences.getString(KEY_DSH_UPDATE_POLICY, DEFAULT_UPDATE_POLICY).orEmpty(),
            ),
        )
    }

    fun readModelProviderSettings(): ModelProviderSettings {
        val order = normalizeModelProviderOrder(readStringArray(KEY_MODEL_PROVIDER_ORDER))
        val persistedEnabled = readBooleanMap(KEY_MODEL_PROVIDER_ENABLED)
        val enabledById = LinkedHashMap<String, Boolean>(order.size)
        order.forEach { providerId ->
            enabledById[providerId] = persistedEnabled[providerId] ?: DEFAULT_MODEL_PROVIDER_ENABLED
        }
        return ModelProviderSettings(order = order, enabledById = enabledById)
    }

    fun setModelProviderOrder(order: List<String>): ModelProviderSettings {
        val current = readModelProviderSettings()
        return setModelProviderSettings(order, current.enabledById)
    }

    fun setModelProviderEnabled(providerId: String, enabled: Boolean): ModelProviderSettings {
        val normalizedId = normalizeProviderId(providerId) ?: return readModelProviderSettings()
        val current = readModelProviderSettings()
        val order = if (normalizedId in current.order) {
            current.order
        } else {
            current.order + normalizedId
        }
        val enabledById = LinkedHashMap(current.enabledById)
        enabledById[normalizedId] = enabled
        return setModelProviderSettings(order, enabledById)
    }

    fun setModelProviderSettings(
        order: List<String>,
        enabledById: Map<String, Boolean>,
    ): ModelProviderSettings {
        val normalized = normalizeModelProviderSettings(order, enabledById)
        val orderJson = JSONArray()
        normalized.order.forEach { orderJson.put(it) }
        val enabledJson = JSONObject()
        normalized.order.forEach { providerId ->
            enabledJson.put(providerId, normalized.enabledById[providerId] ?: DEFAULT_MODEL_PROVIDER_ENABLED)
        }
        preferences.edit()
            .putString(KEY_MODEL_PROVIDER_ORDER, orderJson.toString())
            .putString(KEY_MODEL_PROVIDER_ENABLED, enabledJson.toString())
            .apply()
        return normalized
    }

    fun setVirtualMachineStrategy(
        profile: String,
        hardwareAcceleration: Boolean,
        backgroundRunning: Boolean,
    ): VirtualMachineSettings {
        val normalized = VirtualMachineSettings(
            profile = normalizeVirtualMachineProfile(profile),
            hardwareAcceleration = hardwareAcceleration,
            backgroundRunning = backgroundRunning,
        )
        preferences.edit()
            .putString(KEY_VM_PROFILE, normalized.profile)
            .putBoolean(KEY_VM_HARDWARE_ACCELERATION, normalized.hardwareAcceleration)
            .putBoolean(KEY_VM_BACKGROUND_RUNNING, normalized.backgroundRunning)
            .apply()
        return normalized
    }

    fun setVirtualMachineProfile(profile: String): String {
        val normalized = normalizeVirtualMachineProfile(profile)
        preferences.edit().putString(KEY_VM_PROFILE, normalized).apply()
        return normalized
    }

    fun setVirtualMachineHardwareAcceleration(enabled: Boolean): Boolean {
        preferences.edit().putBoolean(KEY_VM_HARDWARE_ACCELERATION, enabled).apply()
        return enabled
    }

    fun setVirtualMachineBackgroundRunning(enabled: Boolean): Boolean {
        preferences.edit().putBoolean(KEY_VM_BACKGROUND_RUNNING, enabled).apply()
        return enabled
    }

    fun setDownloadSource(source: String): String {
        val normalized = SettingsContract.downloadSource(source)
        preferences.edit().putString(KEY_DOWNLOAD_SOURCE, normalized).apply()
        return normalized
    }

    fun setUpdatePolicy(policy: String): String {
        val normalized = SettingsContract.updatePolicy(policy)
        preferences.edit().putString(KEY_UPDATE_POLICY, normalized).apply()
        return normalized
    }

    fun setDshUpdatePolicy(policy: String): String {
        val normalized = SettingsContract.updatePolicy(policy)
        preferences.edit().putString(KEY_DSH_UPDATE_POLICY, normalized).apply()
        return normalized
    }

    private fun readStringArray(key: String): List<String> {
        val raw = preferences.getString(key, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    normalizeProviderId(array.optString(index, ""))?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun readBooleanMap(key: String): Map<String, Boolean> {
        val raw = preferences.getString(key, null).orEmpty()
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            val result = LinkedHashMap<String, Boolean>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val providerId = normalizeProviderId(keys.next()) ?: continue
                result[providerId] = json.optBoolean(providerId, DEFAULT_MODEL_PROVIDER_ENABLED)
            }
            result
        }.getOrDefault(emptyMap())
    }

    companion object {
        const val KEY_MODEL_PROVIDER_ORDER = "model_provider_order"
        const val KEY_MODEL_PROVIDER_ENABLED = "model_provider_enabled"
        const val KEY_VM_PROFILE = "vm_profile"
        const val KEY_VM_HARDWARE_ACCELERATION = "vm_hardware_acceleration"
        const val KEY_VM_BACKGROUND_RUNNING = "vm_background_running"
        const val KEY_DOWNLOAD_SOURCE = "download_source"
        const val KEY_UPDATE_POLICY = "update_policy"
        const val KEY_DSH_UPDATE_POLICY = "dsh_update_policy"

        const val DEFAULT_VM_PROFILE = "balanced"
        const val DEFAULT_VM_HARDWARE_ACCELERATION = true
        const val DEFAULT_VM_BACKGROUND_RUNNING = false
        const val DEFAULT_DOWNLOAD_SOURCE = "cdn"
        const val DEFAULT_UPDATE_POLICY = "remind"
        const val DEFAULT_MODEL_PROVIDER_ENABLED = true

        val DEFAULT_MODEL_PROVIDER_ORDER: List<String> = listOf(
            "deepseek",
            "dsh",
            "lite",
        )
        val VIRTUAL_MACHINE_PROFILES: Set<String> = setOf("battery", "balanced", "performance")

        fun normalizeProviderId(value: String): String? = value.trim().takeIf {
            it.isNotEmpty() && it.length <= 80 && !it.any { character -> Character.isISOControl(character) }
        }

        fun normalizeModelProviderOrder(order: List<String>): List<String> {
            val normalized = LinkedHashSet<String>()
            order.forEach { normalizeProviderId(it)?.let(normalized::add) }
            if (normalized.isEmpty()) normalized.addAll(DEFAULT_MODEL_PROVIDER_ORDER)
            DEFAULT_MODEL_PROVIDER_ORDER.forEach { normalized.add(it) }
            return normalized.toList()
        }

        fun normalizeModelProviderSettings(
            order: List<String>,
            enabledById: Map<String, Boolean>,
        ): ModelProviderSettings {
            val normalizedOrder = normalizeModelProviderOrder(order)
            val normalizedEnabled = LinkedHashMap<String, Boolean>(normalizedOrder.size)
            normalizedOrder.forEach { providerId ->
                normalizedEnabled[providerId] = enabledById[providerId] ?: DEFAULT_MODEL_PROVIDER_ENABLED
            }
            return ModelProviderSettings(normalizedOrder, normalizedEnabled)
        }

        fun normalizeVirtualMachineProfile(value: String): String =
            value.trim().takeIf { it in VIRTUAL_MACHINE_PROFILES } ?: DEFAULT_VM_PROFILE
    }
}

data class ModelProviderSettings(
    val order: List<String>,
    val enabledById: Map<String, Boolean>,
) {
    fun isEnabled(providerId: String): Boolean = enabledById[providerId] ?: false
}

data class VirtualMachineSettings(
    val profile: String,
    val hardwareAcceleration: Boolean,
    val backgroundRunning: Boolean,
)

data class SettingsRuntimeSnapshot(
    val modelProviders: ModelProviderSettings,
    val virtualMachine: VirtualMachineSettings,
    val downloadSource: String,
    val updatePolicy: String,
    val dshUpdatePolicy: String,
)
