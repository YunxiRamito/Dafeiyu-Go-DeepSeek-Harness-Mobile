package com.dafeiyu.mobile.data

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

private var checks = 0
private fun test(name: String, block: () -> Unit) { block(); checks++; println("PASS $name") }
private fun refuses(block: () -> Unit) { check(runCatching(block).isFailure) }
private fun state(vararg items: ModelProviderConfig): JSONObject = JSONObject().put("schema", 1).put("revision", 17)
    .put("providers", JSONArray(items.map { it.toJson() })).put("credentials", JSONObject())
private fun namespace(profiles: JSONObject, user: JSONObject = profiles): JSONObject = JSONObject().put("ns", "llm-pi-ai")
    .put("revision", 7).put("value", JSONObject().put("providers", profiles)).put("user", JSONObject().put("providers", user))

fun main() = runBlocking {
    val direct = ModelProviderConfig.DEEPSEEK
    val disabled = ModelProviderConfig("offline-disabled", "Disabled", builtinProvider = "openai", enabled = false)
    val custom = ModelProviderConfig("offline-custom", "Custom", api = "openai-completions", baseUrl = "https://example.invalid/v1",
        modelsJson = "[{\"id\":\"fixture\",\"reasoning\":false}]")
    val alias = ModelProviderConfig("dsh-pi-deepseek", "Pi DeepSeek", builtinProvider = "deepseek", dshRoute = "deepseek")
    test("DSH route aliases serialize independently and cannot alter native protected identity") {
        check(ModelProviderConfig.fromJson(alias.toJson()) == alias)
        refuses { direct.copy(dshRoute = "deepseek").validated() }
        refuses { alias.copy(dshRoute = "deepseek-official").validated() }
    }
    val old = state(disabled, custom, direct).put("dshSync", DshSharedConfigurationMapping.syncReceipt(listOf(custom.id)))
    old.getJSONObject("credentials").put(disabled.id, JSONObject().put("type", "api_key").put("key", "OFFLINE_DISABLED"))
    val converted = JSONObject().put("providers", JSONArray().put(alias.toJson()))
    val credentials = JSONObject().put("refs", JSONObject().put("OFFICIAL_REF", "OFFLINE_DIRECT"))
        .put("records", JSONObject().put("llm-pi-ai/deepseek", JSONObject().put("kind", "api-key").put("key", "OFFLINE_PI")))
    val imported = DshSharedConfigurationMapping.importState(old, converted, credentials, "OFFICIAL_REF")
    test("live import preserves native order and disabled credentials but imports authoritative active routes") {
        val rows = imported.getJSONArray("providers")
        check(List(rows.length()) { rows.getJSONObject(it).getString("id") } == listOf(disabled.id, "deepseek", alias.id))
        check(imported.getJSONObject("credentials").getJSONObject("deepseek").getString("key") == "OFFLINE_DIRECT")
        check(imported.getJSONObject("credentials").getJSONObject(alias.id).getString("key") == "OFFLINE_PI")
        check(imported.getJSONObject("credentials").getJSONObject(disabled.id).getString("key") == "OFFLINE_DISABLED")
        check(DshSharedConfigurationMapping.managedRoutes(imported) == setOf("deepseek"))
    }
    test("initial read preserves unpublished enabled providers and official credentials") {
        val native = state(direct, custom)
        native.getJSONObject("credentials").put("deepseek", JSONObject().put("type", "api_key").put("key", "OFFLINE_NATIVE"))
        val empty = JSONObject().put("refs", JSONObject()).put("records", JSONObject())
        val next = DshSharedConfigurationMapping.importState(native, JSONObject().put("providers", JSONArray()), empty, "OFFICIAL_REF")
        check(next.getJSONArray("providers").length() == 2)
        check(next.getJSONObject("credentials").getJSONObject("deepseek").getString("key") == "OFFLINE_NATIVE")
        native.put("dshSync", DshSharedConfigurationMapping.syncReceipt(listOf(custom.id)).put("officialManaged", true))
        val owned = DshSharedConfigurationMapping.importState(native, JSONObject().put("providers", JSONArray()), empty, "OFFICIAL_REF")
        check(owned.getJSONArray("providers").length() == 1 && !owned.getJSONObject("credentials").has("deepseek"))
    }
    test("unowned metadata and credential collisions refuse import instead of overwriting either store") {
        val native = state(direct, alias)
        val conflicted = JSONObject().put("providers", JSONArray().put(alias.copy(name = "Different").toJson()))
        refuses { DshSharedConfigurationMapping.importState(native, conflicted, credentials, "OFFICIAL_REF") }
        native.getJSONObject("credentials").put(alias.id, JSONObject().put("type", "api_key").put("key", "DIFFERENT_NATIVE"))
        refuses { DshSharedConfigurationMapping.importState(native, converted, credentials, "OFFICIAL_REF") }
        check(native.getJSONObject("credentials").getJSONObject(alias.id).getString("key") == "DIFFERENT_NATIVE")
    }
    test("cloud credential paths are converted to portable encrypted-vault files") {
        val cloud = JSONObject().put("providers", JSONArray().put(ModelProviderConfig("google-vertex", "Vertex", builtinProvider = "google-vertex",
            dshRoute = "google-vertex").toJson()))
        val document = JSONObject().put("refs", JSONObject()).put("records", JSONObject().put("llm-pi-ai/google-vertex", JSONObject()
            .put("kind", "api-key").put("env", JSONObject().put("GOOGLE_APPLICATION_CREDENTIALS", "/dafeiyu-dsh-home/private.json")
                .put("GOOGLE_CLOUD_PROJECT", "offline"))))
            .put("portableFiles", JSONObject().put("llm-pi-ai/google-vertex", JSONObject().put("google_adc", "{\"type\":\"authorized_user\"}")))
        val next = DshSharedConfigurationMapping.importState(state(direct), cloud, document, "OFFICIAL_REF")
        val credential = next.getJSONObject("credentials").getJSONObject("google-vertex")
        check(!credential.getJSONObject("env").has("GOOGLE_APPLICATION_CREDENTIALS"))
        check(credential.getJSONObject("files").has("google_adc"))
    }
    val original = JSONObject().put(custom.id, JSONObject().put("displayName", "Before").put("api", "openai-completions")
        .put("apiKeyEnv", "EXISTING_REF").put("headers", JSONObject().put("X-Keep", "keep")))
        .put("foreign-route", JSONObject().put("displayName", "Foreign"))
    val ns = namespace(original)
    val proposed = JSONObject().put(custom.id, JSONObject().put("displayName", "After").put("api", "openai-completions")
        .put("apiKeyEnv", "DAFEIYU_PROVIDER_OFFLINE_CUSTOM"))
    test("publish preserves existing credential reference and refuses foreign route collision") {
        val prepared = DshSharedConfigurationMapping.prepareProfiles(ns, old, proposed)
        check(prepared.getJSONObject(custom.id).getString("apiKeyEnv") == "EXISTING_REF")
        refuses { DshSharedConfigurationMapping.prepareProfiles(ns, state(direct, custom), proposed) }
    }
    test("managed field edits preserve foreign routes and advanced headers") {
        val operations = DshSharedConfigurationMapping.mutations(ns, proposed, setOf(custom.id))
        check(operations.length() == 2)
        check((0 until operations.length()).all { operations.getJSONObject(it).getJSONArray("path").getString(1) == custom.id })
        check((0 until operations.length()).none { operations.getJSONObject(it).getJSONArray("path").getString(2) == "headers" })
    }
    test("removal applies only to prior owned routes and refuses immutable base route removal") {
        val operations = DshSharedConfigurationMapping.mutations(ns, JSONObject(), setOf(custom.id))
        check(operations.length() == 1 && operations.getJSONObject(0).getJSONArray("path").length() == 2)
        refuses { DshSharedConfigurationMapping.mutations(namespace(original, JSONObject()), JSONObject(), setOf(custom.id)) }
    }
    test("mutation verification requires effective removal and persisted user result") {
        val removal = JSONArray().put(JSONObject().put("op", "unset").put("path", JSONArray().put("providers").put(custom.id)))
        refuses { DshSharedConfigurationMapping.verifyMutation(namespace(original, JSONObject()), removal) }
        DshSharedConfigurationMapping.verifyMutation(namespace(JSONObject(), JSONObject()), removal)
    }
    test("exact stored models admit schema defaults in effective values but refuse lost or changed writes") {
        val models = JSONArray().put(JSONObject().put("id", "offline-runtime-model")
            .put("name", "offline-runtime-model").put("reasoningEfforts", false))
        val stored = JSONObject().put(custom.id, JSONObject().put("models", models))
        val resolvedModels = JSONArray(models.toString()).apply { getJSONObject(0).put("input", JSONArray())
            .put("compat", JSONObject().put("chatTemplateKwargs", JSONObject()).put("chatTemplateArgs", JSONObject())) }
        val effective = JSONObject().put(custom.id, JSONObject().put("models", resolvedModels))
        val operations = JSONArray().put(JSONObject().put("op", "set")
            .put("path", JSONArray().put("providers").put(custom.id).put("models")).put("value", models))
        DshSharedConfigurationMapping.verifyMutation(namespace(effective, stored), operations)
        refuses { DshSharedConfigurationMapping.verifyMutation(namespace(effective, JSONObject()), operations) }
        val altered = JSONObject(effective.toString()).apply { getJSONObject(custom.id).getJSONArray("models")
            .getJSONObject(0).put("id", "wrong-model") }
        refuses { DshSharedConfigurationMapping.verifyMutation(namespace(altered, stored), operations) }
        val appended = JSONObject(effective.toString()).apply { getJSONObject(custom.id).getJSONArray("models")
            .put(JSONObject().put("id", "unexpected-model")) }
        refuses { DshSharedConfigurationMapping.verifyMutation(namespace(appended, stored), operations) }
    }
    test("post-CAS cleanup preserves external reference names and only clears unreferenced app refs") {
        val managed = state(direct).put("dshSync", DshSharedConfigurationMapping.syncReceipt(listOf(custom.id)))
        val doc = JSONObject().put("revision", "a".repeat(64)).put("refs", JSONObject().put("EXISTING_REF", "OFFLINE_KEY"))
            .put("records", JSONObject().put("llm-pi-ai/${custom.id}", JSONObject().put("kind", "api-key").put("key", "OFFLINE_KEY")))
        fun described(value: JSONObject) = JSONObject().put("namespaces", JSONArray().put(value).put(JSONObject().put("ns", "llm-deepseek-api-key")
            .put("value", JSONObject().put("apiKeyEnv", "OFFICIAL_REF"))))
        val plan = DshSharedConfigurationMapping.credentialPublication(managed, JSONObject().put("providers", JSONObject()), described(ns), doc)
        val updates = DshSharedConfigurationMapping.retiredCredentialUpdates(plan, described(namespace(JSONObject())), doc)
        check(updates.getJSONArray("refs").length() == 0)
        check(updates.getJSONArray("records").getJSONObject(0).isNull("record"))
        val appRef = namespace(JSONObject().put(custom.id, JSONObject().put("apiKeyEnv", "DAFEIYU_PROVIDER_OFFLINE_CUSTOM")))
        doc.getJSONObject("refs").put("DAFEIYU_PROVIDER_OFFLINE_CUSTOM", "OFFLINE_KEY")
        val owned = DshSharedConfigurationMapping.credentialPublication(managed, JSONObject().put("providers", JSONObject()), described(appRef), doc)
        val cleanup = DshSharedConfigurationMapping.retiredCredentialUpdates(owned, described(namespace(JSONObject())), doc)
        check(cleanup.getJSONArray("refs").getJSONObject(0).isNull("value"))
    }
    test("catalog order follows launcher vault and keeps unrecognized groups stable") {
        val catalog = JSONObject().put("groups", JSONArray(listOf("foreign-a", "deepseek-official", "deepseek", "foreign-b").map { JSONObject().put("id", it) }))
        val ordered = DshSharedConfigurationMapping.orderCatalog(catalog, listOf(alias, direct)).getJSONArray("groups")
        check(List(ordered.length()) { ordered.getJSONObject(it).getString("id") } == listOf("deepseek", "deepseek-official", "foreign-a", "foreign-b"))
        check(catalog.getJSONArray("groups").getJSONObject(0).getString("id") == "foreign-a")
    }
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port = server.address.port
    val token = "A".repeat(43)
    val cookieName = "dsh-auth-" + Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest("127.0.0.1:$port".toByteArray()))
    var badEnvelope = false
    var authorizationRequests = 0
    var apiRequests = 0
    server.createContext("/") { request ->
        if (request.requestURI.path == "/") {
            check(request.requestURI.rawQuery == "token=$token")
            authorizationRequests++
            request.responseHeaders.add("Location", "./")
            request.responseHeaders.add("Cache-Control", "no-store")
            request.responseHeaders.add("Set-Cookie", "$cookieName=v1.fixture.${"B".repeat(43)}; HttpOnly; SameSite=Strict")
            request.sendResponseHeaders(303, -1)
        } else {
            apiRequests++
            val method = request.requestURI.path.removePrefix("/api/")
            check(method in setOf("settings/describe", "session/list", "session/projections") && request.requestURI.rawQuery == null)
            check(request.requestHeaders.getFirst("Cookie") == "$cookieName=v1.fixture.${"B".repeat(43)}")
            val incoming = JSONObject(request.requestBody.bufferedReader().use { it.readText() })
            check(incoming.getString("type") == "client-request" && incoming.getString("method") == method)
            val args = incoming.getJSONObject("payload").getJSONObject("args")
            if (method == "session/list") check(args.getJSONObject("_request").length() == 0)
            if (method == "session/projections") check(args.getJSONObject("request").getString("sessionId") == "offline-session")
            val data = JSONObject().put("type", "server-response").put("rpcId", if (badEnvelope) "wrong-id" else incoming.getString("rpcId"))
                .put("result", JSONObject().put("ok", true).put("value", JSONObject().put("fixture", true))).toString().toByteArray()
            request.responseHeaders.add("Content-Type", "application/json")
            request.sendResponseHeaders(200, data.size.toLong())
            request.responseBody.use { it.write(data) }
        }
        request.close()
    }
    server.start()
    try {
        val rpc = DshLocalConfigurationRpc()
        val url = "http://127.0.0.1:$port/?token=$token"
        check(rpc.call(url, "settings/describe", JSONObject()).getBoolean("fixture")); checks++
        println("PASS local authenticated RPC uses root token exchange, exact cookie, clean API and matching envelope")
        check(rpc.call(url, "session/list", JSONObject().put("_request", JSONObject())).getBoolean("fixture")); checks++
        println("PASS readonly session enumeration retains actual request envelope and authenticated transport")
        check(rpc.call(url, "session/projections", JSONObject().put("request", JSONObject().put("sessionId", "offline-session"))).getBoolean("fixture")); checks++
        println("PASS readonly session projections retain exact request and authenticated transport")
        badEnvelope = true
        check(runCatching { rpc.call(url, "settings/describe", JSONObject()) }.isFailure); checks++
        println("PASS mismatched RPC envelope fails without accepting unowned response")
        val previous = authorizationRequests
        for (unsafe in listOf(url.replace("127.0.0.1", "localhost"), url.replace("http:", "https:"), url + "&extra=1"))
            check(runCatching { rpc.call(unsafe, "settings/describe", JSONObject()) }.isFailure)
        check(runCatching { rpc.call(url, "credentials/read", JSONObject()) }.isFailure)
        check(runCatching { rpc.call(url, "session/publish", JSONObject()) }.isFailure)
        check(previous == authorizationRequests && apiRequests == 4); checks++
        println("PASS arbitrary endpoints and remote/malformed URLs refused before network access")
    } finally { server.stop(0) }
    checks += providerTransactionChecks()
    println("Shared configuration: $checks PASS; JVM loopback/mapper/transaction fixtures, no Android Keystore or model request.")
}
