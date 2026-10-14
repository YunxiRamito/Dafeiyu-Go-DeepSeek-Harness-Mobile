package com.dafeiyu.mobile.data

import org.json.JSONArray
import org.json.JSONObject

/** Portable provider credentials only; device identity, arbitrary namespaces and host paths are excluded. */
object DymSharedCredentials {
    private val reference = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val route = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    private val cloudPaths = setOf("GOOGLE_APPLICATION_CREDENTIALS", "AWS_SHARED_CREDENTIALS_FILE", "AWS_CONFIG_FILE")

    /**
     * The selector is part of the portable contract.  A selector with no
     * matching ref is an explicit removal, while an archive without a
     * selector is an older/unknown archive and must not clear a live key.
     */
    data class OfficialSelection(val known: Boolean, val reference: String?, val key: String?)

    fun validate(document: JSONObject) {
        require(document.getInt("version") == 1 && document.length() <= 6)
        require(document.keys().asSequence().all { it in setOf("version", "refs", "records", "portableFiles", "officialRef", "launcherDeepSeekKey") })
        val refs = document.getJSONObject("refs")
        require(refs.length() <= 128)
        refs.keys().forEach { name -> require(reference.matches(name)); secret(refs.getString(name)) }
        if (document.has("officialRef")) require(document.get("officialRef") is String && reference.matches(document.getString("officialRef")))
        if (document.has("launcherDeepSeekKey")) { require(document.get("launcherDeepSeekKey") is String); secret(document.getString("launcherDeepSeekKey")) }
        val records = document.getJSONObject("records")
        require(records.length() <= 127)
        val files = document.optJSONObject("portableFiles") ?: JSONObject()
        require(files.length() <= 127)
        records.keys().forEach { name ->
            require(name.startsWith("llm-pi-ai/") && route.matches(name.removePrefix("llm-pi-ai/")))
            val raw = JSONObject(records.getJSONObject(name).toString())
            val value = when (raw.getString("kind")) {
                "grant" -> raw.getJSONObject("payload")
                "api-key" -> raw.apply { remove("kind"); put("type", "api_key") }
                else -> error("不支持此 DSH 凭据记录")
            }
            files.optJSONObject(name)?.let { value.put("files", JSONObject(it.toString())) }
            // Platform paths are not credentials. Fixed content will be rebound inside the target home.
            value.optJSONObject("env")?.let { environment ->
                cloudPaths.forEach { name -> environment.remove(name) }
            }
            ModelProviderCredential.validate(value)
        }
        files.keys().forEach { name -> require(records.has(name)) }
    }

    fun capture(raw: JSONObject, providerConfiguration: JSONObject, retained: JSONObject?, actualOfficialRef: String? = null): JSONObject {
        // Pass the live selector when DSH is available.  A retained selector is
        // only a compatibility fallback; its retained key is never authority
        // over an explicit native tombstone.
        val officialRef = actualOfficialRef?.also { require(reference.matches(it)) }
            ?: retained?.optString("officialRef")?.takeIf { reference.matches(it) }
            ?: providerConfiguration.optJSONArray("providers")?.let { providers ->
                (0 until providers.length()).asSequence().map { providers.getJSONObject(it) }
                    .firstOrNull { it.optString("id") == "deepseek" }
                    ?.optString("apiKeyEnv")?.takeIf { reference.matches(it) }
            } ?: "DEEPSEEK_API_KEY"
        require(reference.matches(officialRef))
        val expected = mutableSetOf(officialRef)
        retained?.getJSONObject("refs")?.keys()?.forEach { expected += it }
        val providers = providerConfiguration.getJSONArray("providers")
        for (index in 0 until providers.length()) {
            val id = providers.getJSONObject(index).getString("id")
            expected += "DAFEIYU_PROVIDER_${id.uppercase().replace('-', '_')}"
            providers.getJSONObject(index).optString("apiKeyEnv").takeIf { reference.matches(it) }?.let { expected += it }
        }
        val refs = JSONObject()
        raw.getJSONObject("refs").keys().forEach { name ->
            if (name in expected && (name == officialRef || !name.startsWith("DAFEIYU_PROVIDER_DEEPSEEK_")))
                refs.put(name, raw.getJSONObject("refs").get(name))
        }
        val records = JSONObject()
        val files = JSONObject()
        raw.getJSONObject("records").keys().forEach { name ->
            if (name.startsWith("llm-pi-ai/") && route.matches(name.removePrefix("llm-pi-ai/"))) {
                records.put(name, JSONObject(raw.getJSONObject("records").getJSONObject(name).toString()))
                raw.optJSONObject("portableFiles")?.optJSONObject(name)?.let { files.put(name, JSONObject(it.toString())) }
                records.getJSONObject(name).optJSONObject("env")?.let { environment ->
                    cloudPaths.forEach { environment.remove(it) }
                }
            }
        }
        val result = JSONObject().put("version", 1).put("officialRef", officialRef).put("refs", refs)
            .put("records", records).put("portableFiles", files)
        val nativeKey = providerConfiguration.getJSONObject("credentials").optJSONObject("deepseek")
            ?.optString("key")?.takeIf { it.isNotEmpty() }
        if (nativeKey == null) refs.remove(officialRef)
        else {
            refs.put(officialRef, nativeKey)
            result.put("launcherDeepSeekKey", nativeKey)
        }
        validate(result)
        return result
    }

    /** No DSH runtime is required to avoid resurrecting a retained old key. */
    fun captureWithoutDsh(providerConfiguration: JSONObject, retained: JSONObject?): JSONObject = capture(
        retained ?: JSONObject().put("version", 1).put("refs", JSONObject()).put("records", JSONObject()).put("portableFiles", JSONObject()),
        providerConfiguration, retained)

    /**
     * Return the selected official reference and whether the backup explicitly
     * knows its value.  This is used by the native transaction when DSH is not
     * installed; it deliberately does not infer a key from another ref.
     */
    fun officialSelection(document: JSONObject): OfficialSelection {
        validate(document)
        val selected = document.optString("officialRef").takeIf { reference.matches(it) }
        if (selected == null) {
            val legacy = document.optString("launcherDeepSeekKey").takeIf { it.isNotEmpty() }
                ?: document.getJSONObject("refs").optString("DEEPSEEK_API_KEY").takeIf { it.isNotEmpty() }
            return OfficialSelection(legacy != null, if (legacy != null) "DEEPSEEK_API_KEY" else null, legacy)
        }
        val key = document.optString("launcherDeepSeekKey").takeIf { it.isNotEmpty() }
            ?: document.getJSONObject("refs").optString(selected).takeIf { it.isNotEmpty() }
        return OfficialSelection(true, selected, key)
    }

    /** A PC launcher-only Key is restored through the native Keystore even without Node/DSH installed. */
    fun nativeConfiguration(document: JSONObject, current: JSONObject): JSONObject? {
        val selected = officialSelection(document)
        if (!selected.known) return null
        return JSONObject(current.toString()).apply {
            val credentials = getJSONObject("credentials")
            if (selected.key == null) credentials.remove("deepseek")
            else credentials.put("deepseek", JSONObject().put("type", "api_key").put("key", selected.key))
        }
    }

    fun updates(document: JSONObject, revision: String, providerConfiguration: JSONObject? = null): JSONObject {
        validate(document)
        require(revision.matches(Regex("[a-f0-9]{64}")))
        val allowed = mutableSetOf(document.optString("officialRef", "DEEPSEEK_API_KEY"))
        providerConfiguration?.optJSONArray("providers")?.let { providers ->
            for (index in 0 until providers.length()) {
                val provider = providers.getJSONObject(index)
                val id = provider.getString("id")
                require(route.matches(id))
                allowed += "DAFEIYU_PROVIDER_${id.uppercase().replace('-', '_')}"
                provider.optString("apiKeyEnv").takeIf { reference.matches(it) }?.let { allowed += it }
            }
        }
        val refs = JSONArray()
        val officialRef = document.optString("officialRef", "DEEPSEEK_API_KEY")
        document.getJSONObject("refs").let { rows -> rows.keys().forEach {
            if (it in allowed && it != officialRef && it != "DEEPSEEK_API_KEY" && !it.startsWith("DAFEIYU_PROVIDER_DEEPSEEK_"))
                refs.put(JSONObject().put("ref", it).put("value", rows.get(it)))
        } }
        val records = JSONArray()
        document.getJSONObject("records").let { rows -> rows.keys().forEach { name ->
            val record = JSONObject(rows.getJSONObject(name).toString())
            record.optJSONObject("env")?.let { environment -> cloudPaths.forEach { environment.remove(it) } }
            records.put(JSONObject().put("provider", name.removePrefix("llm-pi-ai/")).put("record", record).apply {
                document.optJSONObject("portableFiles")?.optJSONObject(name)?.let { put("files", JSONObject(it.toString())) }
            })
        } }
        return JSONObject().put("expectedRevision", revision).put("refs", refs).put("records", records)
    }

    private fun secret(value: String) { require(value.isNotEmpty() && value.length <= 65_536 && value.none { it == '\u0000' || it == '\r' || it == '\n' }) }
}
