package com.dafeiyu.mobile.data

import org.json.JSONArray
import org.json.JSONObject

/** Portable provider credentials only; device identity, arbitrary namespaces and host paths are excluded. */
object DymSharedCredentials {
    private val reference = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val route = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    private val cloudPaths = setOf("GOOGLE_APPLICATION_CREDENTIALS", "AWS_SHARED_CREDENTIALS_FILE", "AWS_CONFIG_FILE")

    fun validate(document: JSONObject) {
        require(document.getInt("version") == 1 && document.length() <= 6)
        require(document.keys().asSequence().all { it in setOf("version", "refs", "records", "portableFiles", "officialRef", "launcherDeepSeekKey") })
        val refs = document.getJSONObject("refs")
        require(refs.length() <= 128)
        refs.keys().forEach { name -> require(reference.matches(name)); secret(refs.getString(name)) }
        document.optString("officialRef", "DEEPSEEK_API_KEY").let { require(reference.matches(it)) }
        if (document.has("launcherDeepSeekKey")) secret(document.getString("launcherDeepSeekKey"))
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

    fun capture(raw: JSONObject, providerConfiguration: JSONObject, retained: JSONObject?): JSONObject {
        val officialRef = retained?.optString("officialRef", "DEEPSEEK_API_KEY") ?: "DEEPSEEK_API_KEY"
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
        raw.getJSONObject("refs").keys().forEach { name -> if (name in expected) refs.put(name, raw.getJSONObject("refs").get(name)) }
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
        providerConfiguration.getJSONObject("credentials").optJSONObject("deepseek")?.optString("key")?.takeIf { it.isNotEmpty() }
            ?.let { result.put("launcherDeepSeekKey", it) }
        validate(result)
        return result
    }

    /** A PC launcher-only Key is restored through the native Keystore even without Node/DSH installed. */
    fun nativeConfiguration(document: JSONObject, current: JSONObject): JSONObject? {
        validate(document)
        val refs = document.getJSONObject("refs")
        val key = document.optString("launcherDeepSeekKey").takeIf { it.isNotEmpty() }
            ?: refs.optString(document.optString("officialRef", "DEEPSEEK_API_KEY")).takeIf { it.isNotEmpty() }
            ?: return null
        return JSONObject(current.toString()).apply {
            getJSONObject("credentials").put("deepseek", JSONObject().put("type", "api_key").put("key", key))
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
        document.getJSONObject("refs").let { rows -> rows.keys().forEach { if (it in allowed) refs.put(JSONObject().put("ref", it).put("value", rows.get(it))) } }
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
