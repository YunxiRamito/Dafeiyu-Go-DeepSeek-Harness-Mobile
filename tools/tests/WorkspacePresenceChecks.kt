package com.dafeiyu.mobile.data

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private fun platform(name: String, count: Int, other: Int = 0): JSONObject = JSONObject()
    .put("platform", name)
    .put("onlineCount", count)
    .put("otherVersionCount", other)
    .put("versions", JSONArray().apply {
        if (count > other) put(
            JSONObject().put("version", if (name == "pc") "1.7.5" else "0.1.14")
                .put("versionCode", if (name == "pc") JSONObject.NULL else 15)
                .put("count", count - other),
        )
    })

private fun combined(pc: Int = 2, android: Int = 3): JSONObject = JSONObject()
    .put("onlineCount", pc.toLong() + android)
    .put("onlineInstallations", pc.toLong() + android)
    .put("onlineWindowSeconds", 300)
    .put("platforms", JSONArray().put(platform("pc", pc)).put(platform("android", android)))

fun main(args: Array<String>) = runBlocking {
    var checks = 0
    fun verify(label: String, valid: Boolean) {
        check(valid) { label }
        checks++
        println("PASS $label")
    }
    val client = DeveloperCenterPresenceClient()
    val valid = client.parsePlatforms(combined())
    verify("one combined snapshot counts PC + Android", valid.onlineDeviceCount == 5 &&
        valid.pcOnlineCount == 2 && valid.androidOnlineCount == 3 && valid.onlineWindowSeconds == 300)
    verify("real zero remains available, never an error placeholder",
        client.parsePlatforms(combined(0, 0)).onlineDeviceCount == 0)
    val overflowGroups = combined().apply {
        getJSONArray("platforms").getJSONObject(1)
            .put("otherVersionCount", 2)
            .put("versions", JSONArray().put(JSONObject()
                .put("version", "0.1.14").put("versionCode", 15).put("count", 1)))
    }
    verify("version overflow retains the server's complete count",
        client.parsePlatforms(overflowGroups).onlineDeviceCount == 5)
    fun rejected(label: String, mutate: (JSONObject) -> Unit) {
        val payload = combined()
        mutate(payload)
        verify(label, runCatching { client.parsePlatforms(payload) }.isFailure)
    }
    rejected("duplicate platform rejected") {
        it.getJSONArray("platforms").put(1, platform("pc", 3))
    }
    rejected("third platform rejected") { it.getJSONArray("platforms").put(platform("pc", 0)) }
    rejected("missing mobile coverage rejected") { it.put("platforms", JSONArray().put(platform("pc", 5))) }
    rejected("unknown platform rejected") { it.getJSONArray("platforms").getJSONObject(1).put("platform", "ios") }
    rejected("combined count mismatch rejected") { it.put("onlineCount", 2) }
    rejected("installation count mismatch rejected") { it.put("onlineInstallations", 4) }
    rejected("missing installation total rejected") { it.remove("onlineInstallations") }
    rejected("fractional count rejected") { it.put("onlineCount", 5.5) }
    rejected("string count rejected") { it.put("onlineCount", "5") }
    rejected("negative count rejected") { it.getJSONArray("platforms").getJSONObject(1).put("onlineCount", -3) }
    rejected("version subtotal mismatch rejected") {
        it.getJSONArray("platforms").getJSONObject(1).put("otherVersionCount", 1)
    }
    rejected("duplicate version groups rejected") {
        val android = it.getJSONArray("platforms").getJSONObject(1)
        val version = android.getJSONArray("versions").getJSONObject(0).put("count", 1)
        android.put("otherVersionCount", 1).getJSONArray("versions").put(JSONObject(version.toString()))
    }
    rejected("zero version group rejected") {
        it.getJSONArray("platforms").getJSONObject(1).getJSONArray("versions").getJSONObject(0).put("count", 0)
    }
    rejected("PC versionCode rejected") {
        it.getJSONArray("platforms").getJSONObject(0).getJSONArray("versions").getJSONObject(0).put("versionCode", 15)
    }
    rejected("Android versionCode bounds enforced") {
        it.getJSONArray("platforms").getJSONObject(1).getJSONArray("versions").getJSONObject(0).put("versionCode", 2_100_000_001)
    }
    rejected("invalid online window rejected") { it.put("onlineWindowSeconds", 0) }
    rejected("blank version rejected") {
        it.getJSONArray("platforms").getJSONObject(1).getJSONArray("versions").getJSONObject(0).put("version", "")
    }
    rejected("control characters in versions rejected") {
        it.getJSONArray("platforms").getJSONObject(1).getJSONArray("versions").getJSONObject(0).put("version", "0.1\n.14")
    }
    verify("sum overflow rejected", runCatching { client.parsePlatforms(combined(Int.MAX_VALUE, 1)) }.isFailure)

    data class Reply(val code: Int, val body: String)
    val response = AtomicReference(Reply(200, combined().toString()))
    val legacyRequests = AtomicInteger()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/api/presence/platforms") { exchange ->
        val reply = response.get()
        val bytes = reply.body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(reply.code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    server.createContext("/api/presence") { exchange ->
        legacyRequests.incrementAndGet()
        val bytes = JSONObject().put("onlineCount", 2).put("onlineWindowSeconds", 300)
            .toString().toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    try {
        val local = DeveloperCenterPresenceClient(
            DeveloperCenterPresenceClient.Config(baseUrl = "http://127.0.0.1:${server.address.port}"),
        )
        verify("GET probe reads unified endpoint", local.probe().onlineDeviceCount == 5 && legacyRequests.get() == 0)
        response.set(Reply(404, "{}"))
        val legacy = local.probe()
        verify("404 fallback stays PC-only with unknown total/mobile",
            legacy.serviceState == DeveloperCenterPresenceClient.ServiceState.ONLINE &&
                legacy.pcOnlineCount == 2 && legacy.onlineDeviceCount == null &&
                legacy.androidOnlineCount == null && legacyRequests.get() == 1)
        response.set(Reply(405, "{}"))
        verify("405 supports the same legacy contract", local.probe().onlineDeviceCount == null &&
            legacyRequests.get() == 2)
        response.set(Reply(500, "{}"))
        val failed = local.probe()
        verify("HTTP failure never fabricates zero or falls back",
            failed.serviceState == DeveloperCenterPresenceClient.ServiceState.OFFLINE &&
                failed.onlineDeviceCount == null && legacyRequests.get() == 2)
        response.set(Reply(200, combined().put("onlineCount", 99).toString()))
        verify("invalid successful response remains an identifiable error",
            local.probe().serviceState == DeveloperCenterPresenceClient.ServiceState.OFFLINE &&
                legacyRequests.get() == 2)
        response.set(Reply(302, "{}"))
        verify("redirect rejected", local.probe().serviceState == DeveloperCenterPresenceClient.ServiceState.OFFLINE)
        response.set(Reply(200, " ".repeat(32_769)))
        verify("oversized response bounded and rejected",
            local.probe().serviceState == DeveloperCenterPresenceClient.ServiceState.OFFLINE)
        response.set(Reply(200, "not JSON"))
        verify("malformed body rejected", local.probe().onlineDeviceCount == null)
        val unconfigured = DeveloperCenterPresenceClient(DeveloperCenterPresenceClient.Config(baseUrl = null)).probe()
        verify("unconfigured endpoint remains explicit",
            unconfigured.serviceState == DeveloperCenterPresenceClient.ServiceState.PREVIEW &&
                unconfigured.onlineDeviceCount == null)
    } finally {
        server.stop(0)
    }
    println("$checks presence checks passed")
    if ("--live" in args) {
        val live = client.probe()
        println("LIVE state=${live.serviceState} total=${live.onlineDeviceCount} PC=${live.pcOnlineCount} " +
            "Android=${live.androidOnlineCount} window=${live.onlineWindowSeconds}")
    }
}
