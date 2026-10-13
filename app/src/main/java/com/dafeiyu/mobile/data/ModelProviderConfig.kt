package com.dafeiyu.mobile.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Metadata only. Keys, OAuth refresh tokens and cloud credentials live in ModelProviderStore's vault. */
data class ModelProviderConfig(
    val id: String,
    val name: String,
    val builtinProvider: String? = null,
    val api: String? = null,
    val baseUrl: String? = null,
    val modelsJson: String = "[]",
    val enabled: Boolean = true,
    /** Local DSH route identity; independent from the protected native DeepSeek ID. */
    val dshRoute: String? = null,
) {
    val protected: Boolean get() = id == "deepseek"

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("enabled", enabled)
        .put("models", JSONArray(modelsJson)).also { result ->
            builtinProvider?.let { result.put("builtinProvider", it) }
            api?.let { result.put("api", it) }
            baseUrl?.let { result.put("baseUrl", it) }
            dshRoute?.let { result.put("dshRoute", it) }
        }

    fun validated(): ModelProviderConfig {
        require(IDENTIFIER.matches(id) && id !in setOf("dsh", "lite")) { "提供商 ID 无效" }
        require(name.isNotBlank() && name.length <= 160 && name.none(Char::isISOControl)) { "提供商名称无效" }
        require(builtinProvider == null || IDENTIFIER.matches(builtinProvider))
        require(dshRoute == null || IDENTIFIER.matches(dshRoute) && dshRoute != "deepseek-official") { "DSH 提供商路由无效" }
        require(api == null || api in APIS) { "不支持此提供商协议" }
        if (baseUrl != null) {
            val uri = URI(baseUrl)
            require(baseUrl.length <= 2048 && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
                uri.userInfo == null && uri.fragment == null && uri.query == null) { "模型接口必须为 HTTPS" }
        }
        require(modelsJson.toByteArray().size <= 1024 * 1024)
        val models = JSONArray(modelsJson)
        require(models.length() <= 1024)
        val ids = mutableSetOf<String>()
        for (index in 0 until models.length()) {
            val model = models.getJSONObject(index)
            val modelId = model.getString("id")
            require(modelId.isNotBlank() && modelId.length <= 512 && modelId.none(Char::isISOControl) && ids.add(modelId))
            require(!model.has("apiKey") && !model.has("headers")) { "凭据不能存入模型目录" }
        }
        require(builtinProvider != null || api != null && baseUrl != null && models.length() > 0) {
            "自定义提供商需填写协议、接口地址和模型"
        }
        if (protected) require(builtinProvider == "deepseek" && api == null && baseUrl == null && models.length() == 0 && enabled && dshRoute == null) {
            "内置 DeepSeek 提供商不可修改或禁用"
        }
        return this
    }

    companion object {
        val APIS = linkedSetOf("openai-completions", "mistral-conversations", "openai-responses", "azure-openai-responses",
            "openai-codex-responses", "anthropic-messages", "bedrock-converse-stream", "google-generative-ai", "google-vertex", "pi-messages")
        private val IDENTIFIER = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
        val DEEPSEEK = ModelProviderConfig("deepseek", "DeepSeek 官方", builtinProvider = "deepseek")

        fun fromJson(json: JSONObject): ModelProviderConfig = ModelProviderConfig(
            id = json.getString("id"), name = json.getString("name"),
            builtinProvider = json.nullableString("builtinProvider"), api = json.nullableString("api"),
            baseUrl = json.nullableString("baseUrl"), modelsJson = json.optJSONArray("models")?.toString() ?: "[]",
            enabled = json.optBoolean("enabled", true),
            dshRoute = json.nullableString("dshRoute"),
        ).validated()

        private fun JSONObject.nullableString(key: String): String? =
            if (!has(key) || isNull(key)) null else getString(key)
    }
}

data class ModelProviderSnapshot(val revision: Long, val providers: List<ModelProviderConfig>)

/** Fixed shape is pi-ai's native credential union; file content is encrypted with the rest of the credential. */
object ModelProviderCredential {
    fun validate(credential: JSONObject): JSONObject {
        require(credential.toString().toByteArray().size <= 1024 * 1024)
        when (credential.getString("type")) {
            "api_key" -> if (credential.has("key")) require(credential.getString("key").let {
                it.length <= 65_536 && it.none { char -> char == '\u0000' || char == '\r' || char == '\n' }
            })
            "oauth" -> require(credential.getString("refresh").isNotBlank() && credential.getString("access").isNotBlank() &&
                credential.getLong("expires") > 0)
            else -> error("不支持此凭据类型")
        }
        credential.optJSONObject("env")?.let { env ->
            require(env.length() <= 64)
            env.keys().forEach { key ->
                require(key.matches(Regex("[A-Z][A-Z0-9_]{0,79}")))
                require(key !in setOf("NODE_OPTIONS", "NODE_PATH", "LD_PRELOAD", "LD_LIBRARY_PATH", "PATH", "HOME", "SHELL",
                    "GOOGLE_APPLICATION_CREDENTIALS", "AWS_SHARED_CREDENTIALS_FILE", "AWS_CONFIG_FILE")) {
                    "请导入云凭据文件内容，不要填写手机无法访问的凭据路径"
                }
                require(env.getString(key).let { it.length <= 65_536 && '\u0000' !in it })
            }
        }
        credential.optJSONObject("files")?.let { files ->
            require(files.length() <= 3)
            files.keys().forEach { key ->
                require(key in setOf("google_adc", "aws_credentials", "aws_config"))
                require(files.getString(key).toByteArray().size <= 512 * 1024)
                if (key == "google_adc") JSONObject(files.getString(key))
            }
        }
        return JSONObject(credential.toString())
    }
}
