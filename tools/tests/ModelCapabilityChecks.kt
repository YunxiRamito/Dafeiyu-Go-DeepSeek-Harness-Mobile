package com.dafeiyu.mobile.data

import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.lang.reflect.Proxy
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

private var passed = 0
private fun test(name: String, block: () -> Unit) {
    block()
    println("PASS $name")
    passed++
}

private fun catalog(body: String): List<DeepSeekChatClient.Model> = DeepSeekModelCatalog.decode(
    JSONObject("""{"object":"list","data":[$body]}"""),
)

private val model = catalog("""{"id":"model-from-api","name":"From API","input_modalities":["text","image"],
    "context_window":1048576,"max_output_tokens":393216,
    "effort":{"supported_levels":["low","high","max"],"default_level":"high"}}""").single()
private val history = listOf(DeepSeekChatClient.HistoryMessage("user", "fixture only"))

private class OfflineConnection(url: URL, private val response: String, private val mime: String) : HttpsURLConnection(url) {
    val sent = ByteArrayOutputStream()
    override fun connect() {}
    override fun disconnect() {}
    override fun usingProxy() = false
    override fun getResponseCode() = 200
    override fun getContentType() = mime
    override fun getOutputStream() = sent
    override fun getInputStream(): InputStream = ByteArrayInputStream(response.toByteArray())
    override fun getCipherSuite() = "fixture"
    override fun getLocalCertificates(): Array<Certificate>? = null
    override fun getServerCertificates(): Array<Certificate> = emptyArray()
    override fun getPeerPrincipal(): Principal? = null
    override fun getLocalPrincipal(): Principal? = null
}

private fun encodedRequest(selection: DeepSeekThinkingPolicy.Selection, capability: DeepSeekChatClient.Model = model): JSONObject {
    lateinit var connection: OfflineConnection
    val client = DeepSeekChatClient({ "fixture-key" }, DeepSeekChatClient.Config(), { url ->
        OfflineConnection(url,
            """data: {"choices":[{"index":0,"delta":{"content":"fixture"},"finish_reason":"stop"}]}""" + "\n\n" +
                "data: [DONE]\n\n", "text/event-stream").also { connection = it }
    })
    val turn = runBlocking { client.generate(DeepSeekChatClient.Request(capability.id, history,
        thinking = selection.thinking, reasoningEffort = selection.reasoningEffort, modelCapabilities = capability)) {} }
    check(turn.completed)
    return JSONObject(connection.sent.toString("UTF-8"))
}

private fun invalid(request: DeepSeekChatClient.Request) {
    var connected = false
    var keyRead = false
    val client = DeepSeekChatClient({ keyRead = true; "fixture-key" }, DeepSeekChatClient.Config(), {
        connected = true
        error("Invalid request must never open a connection")
    })
    val error = runCatching { client.validateRequest(request) }.exceptionOrNull()
    check(error is DeepSeekChatClient.DeepSeekChatException && error.code == "INVALID_REASONING")
    check(!keyRead && !connected)
}

private class PreferenceFixture {
    val values = linkedMapOf<String, Any?>()
    val writes = mutableListOf<String>()
    private fun editor(): SharedPreferences.Editor {
        val pending = linkedMapOf<String, Any?>()
        return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putBoolean", "putString" -> {
                    pending[args!![0] as String] = args[1]
                    proxy
                }
                "apply", "commit" -> {
                    values.putAll(pending)
                    writes.addAll(pending.keys)
                    if (method.name == "commit") true else null
                }
                else -> error("Unsupported fixture editor method ${method.name}")
            }
        } as SharedPreferences.Editor
    }
    val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java)) { _, method, args ->
        when (method.name) {
            "getString", "getBoolean" -> values[args!![0] as String] ?: args[1]
            "edit" -> editor()
            else -> error("Unsupported fixture preference method ${method.name}")
        }
    } as SharedPreferences
}

fun main() {
    test("remote order and actual default are preserved") {
        check(model.supportedEffortLevels == listOf("low", "high", "max"))
        check(DeepSeekThinkingPolicy.options(model).map { it.id } == listOf("none", "low", "high", "max"))
        check(DeepSeekThinkingPolicy.defaultOptionId(model) == "high")
    }
    test("per-model subset does not fabricate low or max") {
        val high = model.copy(supportedEffortLevels = listOf("high"))
        check(DeepSeekThinkingPolicy.options(high).map { it.id } == listOf("none", "high"))
        check(DeepSeekThinkingPolicy.normalizeOptionId(high, "max") == "high")
    }
    test("missing server default uses default choice and omits explicit effort") {
        val noDefault = model.copy(defaultEffortLevel = null)
        check(DeepSeekThinkingPolicy.defaultOptionId(noDefault) == "default")
        check(DeepSeekThinkingPolicy.options(noDefault).map { it.id } == listOf("none", "default", "low", "high", "max"))
        val actual = encodedRequest(DeepSeekThinkingPolicy.selection(noDefault, null), noDefault)
        check(!actual.has("reasoning_effort"))
        check(actual.getJSONObject("thinking").getString("type") == "enabled")
    }
    test("cache roundtrip preserves capabilities modalities token limits") {
        val restored = DeepSeekModelCatalog.decodeCache(DeepSeekModelCatalog.encodeCache(listOf(model)))
        check(restored == listOf(model))
        check(DeepSeekThinkingPolicy.options(restored.single()) == DeepSeekThinkingPolicy.options(model))
    }
    test("old catalog cache supports only documented toggle without invented effort") {
        val restored = DeepSeekModelCatalog.decodeCache("""[{"id":"deepseek-flash","name":"Flash","input_modalities":["text"]}]""").single()
        check(restored.supportedEffortLevels.isEmpty())
        check(DeepSeekThinkingPolicy.options(restored).map { it.id } == listOf("none", "default"))
        check(DeepSeekThinkingPolicy.selection(restored, "default").reasoningEffort == null)
    }
    test("unknown model without metadata has no reasoning controls") {
        val unsupported = catalog("""{"id":"unknown-model"}""").single()
        check(DeepSeekThinkingPolicy.options(unsupported).isEmpty())
        check(DeepSeekThinkingPolicy.defaultOptionId(unsupported) == null)
        val selection = DeepSeekThinkingPolicy.selection(unsupported, null)
        check(selection == DeepSeekThinkingPolicy.Selection(null, null))
        val actual = encodedRequest(selection, unsupported)
        check(!actual.has("thinking") && !actual.has("reasoning_effort"))
    }
    test("new server-declared effort is displayed and preserved without a fixed global list") {
        val newer = model.copy(supportedEffortLevels = listOf("future-effort"), defaultEffortLevel = "future-effort")
        check(DeepSeekThinkingPolicy.options(newer).last() == DeepSeekThinkingPolicy.Option("future-effort", "future-effort"))
        check(encodedRequest(DeepSeekThinkingPolicy.selection(newer, "future-effort"), newer).getString("reasoning_effort") == "future-effort")
    }
    test("invalid default duplicates none and missing effort array are rejected") {
        for (effort in listOf(
            """{"supported_levels":["high"],"default_level":"max"}""",
            """{"supported_levels":["high","high"]}""",
            """{"supported_levels":["none","high"]}""",
            """{"default_level":"high"}""",
            """{"supported_levels":[12]}""",
        )) check(runCatching { catalog("""{"id":"invalid","effort":$effort}""") }.isFailure)
    }
    test("supported effort request carries enabled thinking and actual effort") {
        for (level in model.supportedEffortLevels) {
            val actual = encodedRequest(DeepSeekThinkingPolicy.selection(model, level))
            check(actual.getJSONObject("thinking").getString("type") == "enabled")
            check(actual.getString("reasoning_effort") == level)
        }
    }
    test("none request carries disabled thinking and none effort") {
        val actual = encodedRequest(DeepSeekThinkingPolicy.selection(model, "none"))
        check(actual.getJSONObject("thinking").getString("type") == "disabled")
        check(actual.getString("reasoning_effort") == "none")
    }
    test("unsupported contradictory unknown and wrong model requests fail before key read") {
        invalid(DeepSeekChatClient.Request(model.id, history, reasoningEffort = "low",
            modelCapabilities = model.copy(supportedEffortLevels = listOf("high"))))
        invalid(DeepSeekChatClient.Request(model.id, history, thinking = false, reasoningEffort = "high", modelCapabilities = model))
        invalid(DeepSeekChatClient.Request(model.id, history, thinking = true, reasoningEffort = "none", modelCapabilities = model))
        invalid(DeepSeekChatClient.Request(model.id, history, reasoningEffort = "high"))
        invalid(DeepSeekChatClient.Request("other-model", history, reasoningEffort = "high", modelCapabilities = model))
        invalid(DeepSeekChatClient.Request("unknown", history, thinking = false,
            modelCapabilities = model.copy(id = "unknown", supportedEffortLevels = emptyList(), defaultEffortLevel = null)))
    }
    test("new settings default token saving power saving and activity expansion to false") {
        val snapshot = SettingsRuntimeStore(PreferenceFixture().preferences).read()
        check(!snapshot.tokenSavingMode && !snapshot.powerSavingMode && !snapshot.officialEngineExpandActivities)
        val contract = SettingsSnapshot()
        check(!contract.tokenSavingMode && !contract.powerSavingMode && !contract.officialEngineExpandActivities)
    }
    test("settings flags survive recreation and preserve visual preference keys") {
        val prefs = PreferenceFixture()
        prefs.values["glass_material"] = false
        prefs.values["page_transitions"] = true
        prefs.values["spring_buttons"] = false
        val store = SettingsRuntimeStore(prefs.preferences)
        store.setTokenSavingMode(true)
        store.setPowerSavingMode(true)
        store.setOfficialEngineExpandActivities(true)
        val recreated = SettingsRuntimeStore(prefs.preferences).read()
        check(recreated.tokenSavingMode && recreated.powerSavingMode && recreated.officialEngineExpandActivities)
        store.setPowerSavingMode(false)
        check(!SettingsRuntimeStore(prefs.preferences).read().powerSavingMode)
        check(prefs.values["glass_material"] == false && prefs.values["page_transitions"] == true && prefs.values["spring_buttons"] == false)
        check(prefs.writes.none { it in setOf("glass_material", "page_transitions", "spring_buttons") })
    }
    test("power mode disables and locks effects then restores every original combination") {
        for (glass in listOf(false, true)) for (page in listOf(false, true)) for (spring in listOf(false, true)) {
            val on = PowerSavingPolicy.resolve(true, glass, page, spring)
            check(!on.glassMaterial && !on.pageTransitions && !on.springButtons && on.effectsLocked && on.reduceMotion)
            val off = PowerSavingPolicy.resolve(false, glass, page, spring)
            check(off.glassMaterial == glass && off.pageTransitions == page && off.springButtons == spring && !off.effectsLocked)
        }
    }
    test("foreground online remains available while power background connections and downloads pause") {
        val foreground = PowerSavingPolicy.resolve(true, true, true, true, appInForeground = true)
        check(foreground.persistentConnectionsAllowed && foreground.automaticDownloadsAllowed)
        val background = PowerSavingPolicy.resolve(true, true, true, true, appInForeground = false)
        check(!background.persistentConnectionsAllowed && !background.automaticDownloadsAllowed && background.pushNotificationsAllowed)
        val normal = PowerSavingPolicy.resolve(false, true, true, true, appInForeground = false)
        check(normal.persistentConnectionsAllowed && normal.automaticDownloadsAllowed)
    }
    test("accessibility preferences still reduce effective motion and transparency") {
        val effective = PowerSavingPolicy.resolve(false, true, true, true, reduceTransparency = true, reduceMotion = true)
        check(!effective.glassMaterial && !effective.pageTransitions && !effective.springButtons)
        check(effective.reduceTransparency && effective.reduceMotion && !effective.effectsLocked)
    }
    test("legacy fake engine providers and hybrid are normalized away") {
        check(SettingsRuntimeStore.normalizeModelProviderOrder(listOf("dsh", "custom", "lite", "deepseek", "custom")) ==
            listOf("custom", "deepseek"))
        check(SettingsRuntimeStore.normalizeModelProviderOrder(emptyList()) == listOf("deepseek"))
        check(SettingsContract.connectionMode("hybrid") == "local")
    }
    test("token saving instruction is enabled only explicitly and added once") {
        val base = DeepSeekChatClient.HistoryMessage("system", "Solve user tasks.")
        check(OfficialTokenSavingPolicy.applyToSystemMessage(base, false) == base)
        val enabled = OfficialTokenSavingPolicy.applyToSystemMessage(base, true)
        check(enabled.content.contains(OfficialTokenSavingPolicy.INSTRUCTION))
        check(OfficialTokenSavingPolicy.applyToSystemMessage(enabled, true) == enabled)
        check(runCatching { OfficialTokenSavingPolicy.applyToSystemMessage(history.first(), true) }.isFailure)
    }
    println("Model capability/settings checks: $passed PASS; offline transport and SharedPreferences fixtures, no device or paid API.")
}
