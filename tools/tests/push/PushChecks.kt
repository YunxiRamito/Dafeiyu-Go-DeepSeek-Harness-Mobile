package com.dafeiyu.mobile.data

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private var passes = 0
private inline fun verify(label: String, block: () -> Boolean) { check(block()) { "FAIL $label" }; println("PASS ${++passes} $label") }
private val preferences = PushPreferences(true, true, true, true, true, true)
private val capabilities = """{"schema":1,"protocol":"dafeiyu-go.android-push.v1","channels":["fcm"],"categories":["task","device","announcement","message","backend"],"configured":true}"""
private val noChannels = capabilities.replace("[\"fcm\"]", "[]").replace("\"configured\":true", "\"configured\":false")

fun main() = runBlocking {
    verify("empty server account capability stays unconfigured") {
        PushChannelPolicy.select(emptySet(), setOf("fcm"), "oppo", true).reason == "server-unconfigured"
    }
    verify("missing real SDK cannot fabricate registration") {
        PushChannelPolicy.select(setOf("fcm", "oppo"), emptySet(), "oppo", true).channel == null
    }
    verify("GMS preferred only when both real SDK and server configured") {
        PushChannelPolicy.select(setOf("fcm", "oppo"), setOf("fcm", "oppo"), "oppo", true).channel == "fcm"
    }
    verify("non-GMS OPPO route selected from actual availability") {
        PushChannelPolicy.select(setOf("fcm", "oppo"), setOf("oppo"), "oneplus", false).channel == "oppo"
    }
    verify("explicit unavailable selection does not fall back") {
        PushChannelPolicy.select(setOf("fcm"), setOf("fcm", "oppo"), "oppo", true, "oppo").let { it.channel == null && it.reason == "channel-unconfigured" }
    }
    verify("unknown platform identifier rejected") { runCatching { PushChannelPolicy.select(setOf("invented"), emptySet(), "oppo", false) }.isFailure }
    verify("control or unicode SDK token cannot enter wire request") {
        runCatching { PushSdkToken("fcm", "secret\ntoken") }.isFailure && runCatching { PushSdkToken("fcm", "secret中") }.isFailure
    }
    verify("token diagnostics are redacted") { !PushSdkToken("fcm", "test-sdk-token").toString().contains("test-sdk-token") }
    val signer = TestSigner()
    val client = PushRegistrationClient(signer) { method, path, bytes ->
        if (method == "GET") PushHttpResult(200, capabilities.toByteArray())
        else {
            val proof = JSONObject(checkNotNull(bytes).toString(Charsets.UTF_8))
            val payload = Base64.getUrlDecoder().decode(proof.getString("payload"))
            val actual = PushIdentity.canonical(path, proof.getString("installationKey"), proof.getLong("timestamp"), proof.getString("nonce"), payload)
            val verify = Signature.getInstance("SHA256withECDSA").apply { initVerify(signer.key.public); update(actual) }
            check(verify.verify(Base64.getUrlDecoder().decode(proof.getString("signature"))))
            if (path.endsWith("/register")) {
                val json = JSONObject(payload.toString(Charsets.UTF_8)); check(json.getString("token") == "test-sdk-token")
                check(json.getString("packageName") == "com.dafeiyu.mobile" && json.getLong("expectedRevision") == 0L)
                check(json.getJSONObject("preferences").getBoolean("announcements"))
            }
            val json = JSONObject(payload.toString(Charsets.UTF_8))
            val after = when {
                path.endsWith("/register") -> state(signer.installationKey(), json.getLong("expectedRevision") + 1)
                path.endsWith("/preferences") -> state(signer.installationKey(), json.getLong("expectedRevision") + 1,
                    settings = PushPreferences(true, true, true, false, true, true))
                path.endsWith("/unregister") -> state(signer.installationKey(), json.getLong("expectedRevision") + 1, status = "unregistered")
                else -> state(signer.installationKey(), 1)
            }
            PushHttpResult(200, after.toByteArray())
        }
    }
    val caps = client.capabilities()
    verify("production capabilities parser preserves real availability") { caps.configured && caps.channels == setOf("fcm") && signer.calls == 0 }
    val registered = client.register(PushSdkToken("fcm", "test-sdk-token"), preferences, 0)
    verify("typed register serializes exact formal package and cryptographic canonical") { registered.revision == 1L && registered.preferences == preferences && signer.calls == 1 }
    client.updatePreferences(preferences.copy(announcements = false), 1)
    verify("classification update uses separate signed endpoint") { signer.lastPath.endsWith("/preferences") }
    client.unregister(1)
    verify("unregister uses CAS without token disclosure") { signer.lastPath.endsWith("/unregister") && signer.lastPayload == "{\"expectedRevision\":1}" }
    client.state(); client.state()
    verify("state recovery signs fresh nonce instead of replaying write") { signer.nonces.distinct().size == signer.nonces.size && signer.lastPath.endsWith("/state") }
    verify("invalid revision cannot be sent") { runCatching { client.unregister(-1) }.isFailure }

    suspend fun reject(label: String, body: String, state: Boolean = false) {
        val malformed = PushRegistrationClient(signer) { _, _, _ -> PushHttpResult(200, body.toByteArray()) }
        verify(label) { runCatching { if (state) malformed.state() else malformed.capabilities() }.isFailure }
    }
    reject("duplicate capability fields rejected", capabilities.replace("\"schema\":1", "\"schema\":1,\"schema\":1"))
    reject("coerced capability boolean rejected", capabilities.replace("\"configured\":true", "\"configured\":\"true\""))
    reject("string schema does not become numeric", capabilities.replace("\"schema\":1", "\"schema\":\"1\""))
    reject("decimal schema does not become integer", capabilities.replace("\"schema\":1", "\"schema\":1.0"))
    reject("duplicate server channels rejected", capabilities.replace("[\"fcm\"]", "[\"fcm\",\"fcm\"]"))
    reject("unknown server channel rejected", capabilities.replace("[\"fcm\"]", "[\"fake\"]"))
    reject("empty channels cannot claim configuration", capabilities.replace("[\"fcm\"]", "[]"))
    reject("multiple JSON documents rejected", capabilities + "{}")
    reject("revision overflow rejected", state(signer.installationKey(), 1).replace("\"revision\":1", "\"revision\":9223372036854775808"), true)
    reject("foreign signed state owner rejected", state("b".repeat(64), 1), true)
    reject("nested duplicate preference rejected", state(signer.installationKey(), 1).replace("\"enabled\":true", "\"enabled\":true,\"enabled\":false"), true)
    reject("server non-object response rejected", "[]")
    val noConfig = PushRegistrationClient(signer) { _, _, _ -> PushHttpResult(200, noChannels.toByteArray()) }
    val before = signer.calls
    val noRegistration = runCatching { noConfig.register(PushSdkToken("fcm", "test-sdk-token"), preferences, 0) }.exceptionOrNull()
    verify("unconfigured server does not sign registration") { noRegistration is PushClientFailure && noRegistration.code == "channel-unconfigured" && signer.calls == before }
    val cas = PushRegistrationClient(signer) { _, _, _ -> PushHttpResult(409, "{\"error\":\"revision-conflict\"}".toByteArray()) }
    val conflict = runCatching { cas.unregister(0) }.exceptionOrNull()
    verify("real server CAS code preserved without automatic retry") { conflict is PushClientFailure && conflict.code == "revision-conflict" && conflict.httpStatus == 409 }
    val longFailure = PushRegistrationClient(signer) { _, _, _ -> PushHttpResult(403, "{\"error\":\"raw token private details\"}".toByteArray()) }
    val safe = runCatching { longFailure.state() }.exceptionOrNull()
    verify("untrusted error text cannot leak credentials") { safe is PushClientFailure && safe.code == "http-error" && !safe.message.orEmpty().contains("raw token") }
    val cancelled = CompletableDeferred<Unit>()
    val wait = PushRegistrationClient(signer) { _, _, _ -> try { delay(60_000); error("should cancel") } finally { cancelled.complete(Unit) } }
    runCatching { withTimeout(50) { wait.state() } }
    verify("caller cancellation reaches transport") { cancelled.isCompleted }

    val requests = AtomicInteger()
    val onlyCaps = PushRegistrationClient(TestSigner()) { method, _, _ -> check(method == "GET"); requests.incrementAndGet(); PushHttpResult(200, noChannels.toByteArray()) }
    val controller = PushSettingsController(onlyCaps, emptySet())
    controller.refresh(preferences)
    withTimeout(2_000) { while (requests.get() == 0 || controller.state.value.busy) delay(5) }
    verify("root controller shows no-config without signed identity or fake token") {
        !controller.state.value.registered && controller.state.value.serverChannels.isEmpty() && controller.state.value.detail.contains("尚未配置")
    }
    controller.updatePreferences(preferences.copy(announcements = false)); delay(100)
    verify("local notification preferences remain usable with no push account") { requests.get() == 1 && !controller.state.value.busy }
    controller.close()
    val recoverySigner = TestSigner()
    var saved = state(recoverySigner.installationKey(), 0, status = "unregistered")
    val writes = AtomicInteger()
    val recoveryClient = PushRegistrationClient(recoverySigner) { method, path, bytes ->
        when {
            method == "GET" -> PushHttpResult(200, capabilities.toByteArray())
            path.endsWith("/state") -> PushHttpResult(200, saved.toByteArray())
            path.endsWith("/register") -> {
                writes.incrementAndGet(); saved = state(recoverySigner.installationKey(), 1)
                throw PushClientFailure("network")
            }
            path.endsWith("/preferences") -> {
                val proof = JSONObject(checkNotNull(bytes).toString(Charsets.UTF_8))
                val payload = JSONObject(Base64.getUrlDecoder().decode(proof.getString("payload")).toString(Charsets.UTF_8))
                check(payload.getLong("expectedRevision") == 1L)
                saved = state(recoverySigner.installationKey(), 2, preferences.copy(announcements = false))
                PushHttpResult(200, saved.toByteArray())
            }
            else -> error("unexpected mutation")
        }
    }
    val recovery = PushSettingsController(recoveryClient, setOf("fcm"))
    recovery.registerFromSdk(PushSdkToken("fcm", "test-sdk-token"), preferences)
    withTimeout(2_000) { while (writes.get() == 0 || recovery.state.value.busy) delay(5) }
    verify("lost registration response does not invent success") { !recovery.state.value.registered }
    recovery.refresh(preferences.copy(announcements = false))
    withTimeout(2_000) { while (!recovery.state.value.registered || recovery.state.value.busy) delay(5) }
    verify("root controller recovers CAS revision through signed state") {
        JSONObject(saved).getLong("revision") == 2L && !JSONObject(saved).getJSONObject("preferences").getBoolean("announcements")
    }
    verify("lost response recovery never repeats SDK registration") { writes.get() == 1 }
    recovery.close()
    val started = CompletableDeferred<Unit>()
    val mutation = AtomicInteger()
    val cancellationClient = PushRegistrationClient(TestSigner()) { method, _, _ ->
        if (method == "GET") { started.complete(Unit); delay(60_000); PushHttpResult(200, capabilities.toByteArray()) }
        else { mutation.incrementAndGet(); error("a cancelled registration must never reach POST") }
    }
    val cancellationController = PushSettingsController(cancellationClient, setOf("fcm"))
    cancellationController.registerFromSdk(PushSdkToken("fcm", "test-sdk-token"), preferences)
    withTimeout(2_000) { started.await() }
    cancellationController.updatePreferences(preferences.copy(enabled = false))
    delay(100)
    verify("root controller cancellation reaches registration before POST") { mutation.get() == 0 && !cancellationController.state.value.registered && !cancellationController.state.value.busy }
    cancellationController.close()
    println("$passes PASS; production client/controller; signer and Android JSON/Base64 runtime are fixtures; real Keystore/TLS/OEM delivery pending")
}

private fun state(key: String, revision: Long, settings: PushPreferences = preferences, status: String = "registered"): String =
    JSONObject().put("installationKey", key).put("status", status).put("channel", if (status == "registered") "fcm" else JSONObject.NULL)
        .put("revision", revision).put("preferences", settings.json()).toString()

private class TestSigner : PushProofSigner {
    val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    var calls = 0; var lastPath = ""; var lastPayload = ""; val nonces = mutableListOf<String>()
    override fun installationKey(): String = PushIdentity.digest(key.public.encoded)
    override fun sign(path: String, payload: ByteArray): JSONObject {
        calls++; lastPath = path; lastPayload = payload.toString(Charsets.UTF_8)
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.SecureRandom().generateSeed(16))
        nonces += nonce; val timestamp = System.currentTimeMillis() / 1000
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(key.private); update(PushIdentity.canonical(path, installationKey(), timestamp, nonce, payload))
        }.sign()
        fun encoded(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return JSONObject().put("installationKey", installationKey()).put("publicKey", encoded(key.public.encoded))
            .put("timestamp", timestamp).put("nonce", nonce).put("payload", encoded(payload)).put("signature", encoded(signature))
    }
}
