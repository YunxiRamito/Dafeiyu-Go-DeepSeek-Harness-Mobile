package com.dafeiyu.mobile.data

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Private provider payload. Revision and local receipts are not portable. */
internal object OfficialCredentialPortable {
    const val MAX_BYTES = 8 * 1024 * 1024

    fun sanitize(configuration: JSONObject): JSONObject {
        try {
            require(configuration.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES - 64)
            val result = JSONObject(configuration.toString()).apply {
                remove("officialCredentialReceipt")
                put("revision", 0)
            }
            require(result.getInt("schema") == 1 && result.keys().asSequence().all {
                it in setOf("schema", "revision", "providers", "credentials", "dshSync")
            })
            val rows = result.getJSONArray("providers")
            require(rows.length() in 1..128)
            val providers = List(rows.length()) { ModelProviderConfig.fromJson(rows.getJSONObject(it)) }
            require(providers.map { it.id }.distinct().size == providers.size && providers.count { it.protected } == 1)
            val ids = providers.map { it.id }.toSet()
            result.getJSONObject("credentials").let { credentials -> credentials.keys().forEach { id ->
                require(id in ids)
                ModelProviderCredential.validate(credentials.getJSONObject(id))
            } }
            val official = result.getJSONObject("credentials").optJSONObject("deepseek")
            require(official == null || official.getString("type") == "api_key")
            official?.let { require(OfficialDeepSeekCredentialTransaction.normalize(it.getString("key")) == it.getString("key")) }
            result.optJSONObject("dshSync")?.let { sync ->
                require(sync.getInt("schema") == 1)
                val routes = sync.getJSONArray("routes")
                require(routes.length() <= 127)
                val values = List(routes.length()) { routes.getString(it) }
                require(values.distinct().size == values.size && values.all { it.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) && it != "deepseek-official" })
                if (sync.has("officialManaged")) require(sync.get("officialManaged") is Boolean)
                if (sync.has("settingsRevision")) require(sync.getLong("settingsRevision") >= 0)
                if (sync.has("credentialRevision")) require(sync.getString("credentialRevision").matches(Regex("[a-f0-9]{64}")))
            }
            return result
        } catch (_: Exception) {
            throw IllegalStateException("INVALID_PORTABLE")
        }
    }

    fun key(configuration: JSONObject): String? = sanitize(configuration).getJSONObject("credentials").optJSONObject("deepseek")?.getString("key")

    fun digest(configuration: JSONObject): String {
        val clear = canonical(sanitize(configuration)).toByteArray(Charsets.UTF_8)
        return try { MessageDigest.getInstance("SHA-256").digest(clear).joinToString("") { "%02x".format(it) } }
        finally { clear.fill(0) }
    }

    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix = "{", postfix = "}", separator = ",") {
            JSONObject.quote(it) + ":" + canonical(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]", separator = ",") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }
}
