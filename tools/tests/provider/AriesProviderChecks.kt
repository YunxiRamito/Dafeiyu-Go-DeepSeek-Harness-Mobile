package com.dafeiyu.mobile.data

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

fun main(args: Array<String>) = runBlocking {
    val root = File(args[0]).apply { mkdirs() }
    val frozen = JSONObject(File(args[1]).readText(StandardCharsets.UTF_8))
    var checks = 0
    suspend fun test(name: String, action: suspend () -> Unit) {
        try { action() } catch (error: Throwable) { throw AssertionError(name, error) }
        checks++
        println("PASS $name")
    }
    suspend fun denied(code: String, action: suspend () -> Unit) {
        val error = runCatching { action() }.exceptionOrNull()
        check(error is AriesProviderException && error.code == code) { "Expected $code, got $error" }
    }
    fun params() = JSONObject().put("group_id", "grp_test")
    fun context() = Context(File(root, "fixture").apply { mkdirs() })
    suspend fun pending(provider: AriesConnectionProvider): JSONObject {
        provider.saveApplication("https://aries.test", "node_test", "grp_test", "app_test")
        return requireNotNull(provider.pendingDelivery())
    }
    suspend fun confirm(provider: AriesConnectionProvider, review: JSONObject): JSONObject =
        provider.confirmDelivery(review.getString("endpoint"), review.getString("node_id"),
            review.getString("group_id"), review.getString("application_id"),
            review.getString("delivery_id"), review.getString("fingerprint"))

    test("all group methods require current group, reject missing, cross-group and nonstring ids") {
        for (method in AriesProviderScope.groupMethods) {
            AriesProviderScope.validate(method, params(), "grp_test")
            for (value in listOf<Any>("grp_other", "", 12, true, JSONObject.NULL)) {
                denied("permission_denied") {
                    AriesProviderScope.validate(method, JSONObject().put("group_id", value), "grp_test")
                }
            }
            denied("permission_denied") { AriesProviderScope.validate(method, JSONObject(), "grp_test") }
            denied("permission_denied") { AriesProviderScope.validate(method, params(), "") }
        }
    }
    test("state getter and polling subscription accept only empty or same-group scope") {
        for (method in AriesProviderScope.stateMethods) {
            AriesProviderScope.validate(method, JSONObject(), "")
            AriesProviderScope.validate(method, params(), "grp_test")
            denied("permission_denied") { AriesProviderScope.validate(method, params(), "grp_other") }
            denied("permission_denied") { AriesProviderScope.validate(method, JSONObject().put("group_id", JSONObject.NULL), "") }
        }
    }
    test("every method rejects tokens, key material, proof, rotation and raw HTTP control fields") {
        val forbidden = listOf("endpoint", "server_url", "node_id", "device_id", "content_key", "private_key",
            "token", "admin_token", "device_token", "signature", "epoch", "required_epoch", "headers",
            "http_method", "http_path", "url", "cookie", "Authorization", "api_key", "credentials",
            "contentKey", "privateKey", "SERVER_URL", "key_delivery", "application_id", "unknown")
        for (method in AriesProviderScope.groupMethods + AriesProviderScope.stateMethods) {
            for (field in forbidden) denied("permission_denied") {
                AriesProviderScope.validate(method, params().put(field, "untrusted"), "grp_test")
            }
        }
    }
    test("native pairing and unknown methods never enter the plugin provider") {
        val ctx = context(); ProviderFixture.reset(ctx)
        val provider = AriesConnectionProvider(ctx)
        for (method in listOf("confirmDelivery", "confirm_delivery", "save_application", "signedGet", "rotate_key", "arbitrary"))
            denied("unsupported_method") { provider.call(method, params()) }
        check(ProviderFixture.identityReads == 0 && ProviderFixture.calls.isEmpty() && ProviderFixture.saves == 0)
    }
    test("business body JSON is preserved without treating nested business keys as provider controls") {
        AriesProviderScope.validate("send_encrypted_message", params().put("kind", "test")
            .put("body", JSONObject().put("signature", "business text"))
            .put("idempotency_key", "op_test"), "grp_test")
    }
    test("forbidden top-level params fail before identity, vault and network access") {
        val ctx = context(); ProviderFixture.reset(ctx)
        denied("permission_denied") {
            AriesConnectionProvider(ctx).call("get_connection_state", JSONObject().put("endpoint", "https://other.test"))
        }
        check(ProviderFixture.identityReads == 0 && ProviderFixture.loads == 0 && ProviderFixture.calls.isEmpty())
    }
    test("unconfigured getter neither creates identity, loads keys nor accesses transport") {
        val ctx = context(); ProviderFixture.reset(ctx)
        ctx.preferences.put("aries_endpoint", "")
        val provider = AriesConnectionProvider(ctx)
        val state = provider.call("get_connection_state")
        check(!state.getBoolean("server_connected") && state.getString("pairing_status") == "unknown")
        check(state.isNull("remote_device") && state.getJSONObject("local_device").isNull("public_key"))
        check(ProviderFixture.identityReads == 0 && ProviderFixture.calls.isEmpty() && ProviderFixture.saves == 0 &&
            ProviderFixture.loads == 0)
        check(provider.savedApplication() == null && provider.pendingDelivery() == null)
        check(ProviderFixture.identityReads == 0 && ProviderFixture.calls.isEmpty() && ProviderFixture.loads == 0)
    }
    test("each incomplete selector returns unknown without identity or network") {
        for (field in listOf("aries_endpoint", "aries_node_id", "aries_group_id", "aries_device_id")) {
            val ctx = context(); ProviderFixture.reset(ctx)
            ctx.preferences.put(field, "")
            val state = AriesConnectionProvider(ctx).call("get_connection_state")
            check(state.getString("pairing_status") == "unknown")
            check(ProviderFixture.identityReads == 0 && ProviderFixture.calls.isEmpty())
        }
    }
    test("scope validation and binding selection use the same atomic preference snapshot") {
        val ctx = context(); ProviderFixture.reset(ctx)
        ctx.preferences.afterRead = { ctx.preferences.put("aries_group_id", "grp_other") }
        denied("conflict") { AriesConnectionProvider(ctx).call("get_group_file_cache_stats", params()) }
        check(ProviderFixture.calls.isEmpty() && ProviderFixture.saves == 0)
    }
    for (field in listOf("aries_endpoint", "aries_node_id", "aries_group_id", "aries_device_id")) {
        test("network result after changing $field is rejected before persistence") {
            val ctx = context(); ProviderFixture.reset(ctx)
            ProviderFixture.afterNetwork = { if (it == "node") ctx.preferences.put(field, "changed") }
            denied("conflict") { AriesConnectionProvider(ctx).call("get_connection_state") }
            check(ProviderFixture.calls == listOf("node") && ProviderFixture.saves == 0)
        }
    }
    test("switching away and back during network is still a conflict") {
        val ctx = context(); ProviderFixture.reset(ctx)
        ProviderFixture.afterNetwork = {
            if (it == "node") {
                ctx.preferences.put("aries_group_id", "grp_other")
                ctx.preferences.put("aries_group_id", "grp_test")
            }
        }
        denied("conflict") { AriesConnectionProvider(ctx).call("get_connection_state") }
        check(ProviderFixture.calls == listOf("node"))
    }
    test("preferences are rechecked even when Android-style listener callbacks are delayed") {
        val ctx = context(); ProviderFixture.reset(ctx)
        ctx.preferences.deferNotifications = true
        ProviderFixture.afterNetwork = { if (it == "node") ctx.preferences.put("aries_group_id", "grp_other") }
        denied("conflict") { AriesConnectionProvider(ctx).call("get_connection_state") }
        check(ProviderFixture.calls == listOf("node") && ProviderFixture.saves == 0)
        check(ctx.preferences.listenerCount == 0)
        ctx.preferences.notifyPending()
    }
    test("operation preference observers are removed after success and denial") {
        val ctx = context(); ProviderFixture.reset(ctx)
        val provider = AriesConnectionProvider(ctx)
        provider.call("get_connection_state")
        check(ctx.preferences.listenerCount == 0)
        denied("unsupported_method") { provider.call("unknown") }
        check(ctx.preferences.listenerCount == 0)
    }
    test("selected device must match the registered identity, never regenerated or inferred") {
        val ctx = context(); ProviderFixture.reset(ctx)
        ProviderFixture.identityId = null
        denied("conflict") { AriesConnectionProvider(ctx).call("get_connection_state") }
        check(ProviderFixture.calls.isEmpty() && ProviderFixture.saves == 0)
    }
    test("identity device change during network aborts before another authenticated request") {
        val ctx = context(); ProviderFixture.reset(ctx)
        ProviderFixture.afterNetwork = { if (it == "node") ProviderFixture.identityId = ProviderFixture.peerId }
        denied("conflict") { AriesConnectionProvider(ctx).call("get_connection_state") }
        check(ProviderFixture.calls == listOf("node") && ProviderFixture.saves == 0)
    }
    test("identity public key change during network aborts before another authenticated request") {
        val ctx = context(); ProviderFixture.reset(ctx)
        ProviderFixture.afterNetwork = { if (it == "node") ProviderFixture.identityPublic = ProviderFixture.peerPublic }
        denied("conflict") { AriesConnectionProvider(ctx).call("get_connection_state") }
        check(ProviderFixture.calls == listOf("node") && ProviderFixture.saves == 0)
    }
    test("saveApplication rejects each expected endpoint, node and group mismatch") {
        val ctx = context(); ProviderFixture.reset(ctx)
        val provider = AriesConnectionProvider(ctx)
        denied("conflict") { provider.saveApplication("https://other.test", "node_test", "grp_test", "app_test") }
        denied("conflict") { provider.saveApplication("https://aries.test", "node_other", "grp_test", "app_test") }
        denied("conflict") { provider.saveApplication("https://aries.test", "node_test", "grp_other", "app_test") }
        check(ProviderFixture.saves == 0 && ProviderFixture.calls.isEmpty())
    }
    test("application id persists across provider instances only in the selected scope") {
        val ctx = context(); ProviderFixture.reset(ctx)
        AriesConnectionProvider(ctx).saveApplication("https://aries.test", "node_test", "grp_test", "app_test")
        check(AriesConnectionProvider(ctx).savedApplication() == "app_test")
        ctx.preferences.put("aries_group_id", "grp_other")
        check(AriesConnectionProvider(ctx).savedApplication() == null)
        ctx.preferences.put("aries_group_id", "grp_test")
        check(AriesConnectionProvider(ctx).savedApplication() == "app_test")
    }
    test("application persistence overlapping preference change rolls back durable state") {
        val ctx = context(); ProviderFixture.reset(ctx)
        ProviderFixture.afterSave = { ctx.preferences.put("aries_group_id", "grp_other") }
        denied("conflict") {
            AriesConnectionProvider(ctx).saveApplication("https://aries.test", "node_test", "grp_test", "app_test")
        }
        check(ProviderFixture.state.getJSONObject("bindings").length() == 0)
    }
    test("member without a saved application has no pending delivery") {
        val ctx = context(); ProviderFixture.reset(ctx)
        check(AriesConnectionProvider(ctx).pendingDelivery() == null)
        check(ProviderFixture.calls.none { it == "application" || it.endsWith("/key-deliveries") })
    }
    test("pendingDelivery exposes exact scope and device with no key material") {
        val ctx = context(); ProviderFixture.reset(ctx)
        val review = pending(AriesConnectionProvider(ctx))
        check(review.getString("endpoint") == "https://aries.test" && review.getString("node_id") == "node_test")
        check(review.getString("group_id") == "grp_test" && review.getString("application_id") == "app_test")
        check(review.getString("device_id") == ProviderFixture.localId && review.getString("delivery_id") == "kd_test")
        check(review.getString("fingerprint") == AriesCrypto.fingerprint(ProviderFixture.peerPublic))
        check(!review.has("content_key") && !review.has("envelope") && !review.has("public_key") && !review.has("envelope_hash"))
    }
    test("pending review survives provider recreation and confirmation returns genuine paired state") {
        val ctx = context(); ProviderFixture.reset(ctx)
        val review = pending(AriesConnectionProvider(ctx))
        val state = confirm(AriesConnectionProvider(ctx), review)
        check(state.getString("pairing_status") == "paired")
        check(state.getJSONObject("capabilities").getBoolean("encrypted_messages"))
        check(ProviderFixture.binding().optString("pending_ack").isEmpty())
        check(AriesCrypto.decode(ProviderFixture.binding().getString("content_key")).contentEquals(ProviderFixture.contentKey))
        check(ProviderFixture.calls.any { it.endsWith("/key-deliveries/kd_test/ack") })
        check(state.getJSONObject("remote_device").getString("online") == "unknown")
    }
    test("device names roundtrip as display metadata without changing verified identity or pairing") {
        val ctx = context(); ProviderFixture.reset(ctx)
        val provider = AriesConnectionProvider(ctx)
        var state = provider.call("get_connection_state")
        check(state.getJSONObject("local_device").isNull("name"))
        check(state.getJSONObject("remote_device").isNull("name"))
        ProviderFixture.localName = "OPPO Find X8"
        ProviderFixture.peerName = "工作电脑"
        state = provider.call("get_connection_state")
        check(state.getJSONObject("local_device").getString("name") == "OPPO Find X8")
        val remote = state.getJSONObject("remote_device")
        check(remote.getString("name") == "工作电脑")
        check(remote.getString("id") == ProviderFixture.peerId && remote.getString("public_key") == ProviderFixture.peerPublic)
        check(state.getString("pairing_status") != "paired")
        val review = pending(provider)
        confirm(provider, review)
        val bindingBefore = ProviderFixture.binding().toString()
        ProviderFixture.peerName = "null"
        state = provider.call("get_connection_state")
        check(state.getJSONObject("remote_device").getString("name") == "null")
        check(state.getString("pairing_status") == "paired")
        check(ProviderFixture.binding().toString() == bindingBefore)
        check(state.getJSONArray("members").getJSONObject(1).getString("name") == "null")
    }
    test("legacy unscoped confirm cannot bypass expected-scope checks") {
        val ctx = context(); ProviderFixture.reset(ctx)
        val provider = AriesConnectionProvider(ctx)
        @Suppress("DEPRECATION")
        denied("permission_denied") { provider.confirmDelivery("kd_test", AriesCrypto.fingerprint(ProviderFixture.peerPublic)) }
        check(ProviderFixture.identityReads == 0 && ProviderFixture.calls.isEmpty())
    }
    test("confirmation requires a native pending review") {
        val ctx = context(); ProviderFixture.reset(ctx)
        val provider = AriesConnectionProvider(ctx)
        provider.saveApplication("https://aries.test", "node_test", "grp_test", "app_test")
        ProviderFixture.calls.clear()
        denied("conflict") {
            provider.confirmDelivery("https://aries.test", "node_test", "grp_test", "app_test",
                "kd_test", AriesCrypto.fingerprint(ProviderFixture.peerPublic))
        }
        check(ProviderFixture.calls.isEmpty() && ProviderFixture.binding().optString("content_key").isEmpty())
    }
    for (field in listOf("endpoint", "node_id", "group_id", "application_id", "delivery_id", "fingerprint")) {
        test("confirmation rejects stale or changed native $field before network") {
            val ctx = context(); ProviderFixture.reset(ctx)
            val provider = AriesConnectionProvider(ctx)
            val review = pending(provider).put(field, "wrong")
            ProviderFixture.calls.clear()
            denied("conflict") { confirm(provider, review) }
            check(ProviderFixture.calls.isEmpty() && ProviderFixture.binding().optString("content_key").isEmpty())
        }
    }
    test("changing saved application invalidates its earlier delivery review") {
        val ctx = context(); ProviderFixture.reset(ctx)
        val provider = AriesConnectionProvider(ctx)
        val review = pending(provider)
        provider.saveApplication("https://aries.test", "node_test", "grp_test", "app_other")
        denied("conflict") { confirm(provider, review) }
        check(ProviderFixture.binding().optString("content_key").isEmpty())
    }
    test("changing both selected and registered device invalidates the earlier native review") {
        val ctx = context(); ProviderFixture.reset(ctx)
        val provider = AriesConnectionProvider(ctx)
        val review = pending(provider)
        ctx.preferences.put("aries_device_id", ProviderFixture.peerId)
        ProviderFixture.identityId = ProviderFixture.peerId
        ProviderFixture.identityPublic = ProviderFixture.peerPublic
        ProviderFixture.calls.clear()
        denied("conflict") { confirm(provider, review) }
        check(ProviderFixture.calls.isEmpty() && ProviderFixture.binding().optString("content_key").isEmpty())
    }
    test("persisted review device and public identity must exactly match current identity") {
        for (field in listOf("device_id", "public_key")) {
            val ctx = context(); ProviderFixture.reset(ctx)
            val provider = AriesConnectionProvider(ctx); val review = pending(provider)
            ProviderFixture.binding().getJSONObject("delivery_review").put(field, "wrong")
            ProviderFixture.calls.clear()
            denied("conflict") { confirm(provider, review) }
            check(ProviderFixture.calls.isEmpty() && ProviderFixture.binding().optString("content_key").isEmpty())
        }
    }
    for (trigger in listOf("application", "/key-deliveries", "members", "/events?")) {
        test("switching selector during confirmation $trigger does not save a key or acknowledge") {
            val ctx = context(); ProviderFixture.reset(ctx)
            val provider = AriesConnectionProvider(ctx)
            val review = pending(provider)
            val before = ProviderFixture.state.toString()
            ProviderFixture.calls.clear()
            ProviderFixture.afterNetwork = {
                if (it.contains(trigger)) ctx.preferences.put("aries_group_id", "grp_other")
            }
            denied("conflict") { confirm(provider, review) }
            check(ProviderFixture.state.toString() == before)
            check(ProviderFixture.calls.none { it.endsWith("/ack") })
        }
    }
    test("key persistence overlapping group switch rolls back key and leaves review available") {
        val ctx = context(); ProviderFixture.reset(ctx)
        val provider = AriesConnectionProvider(ctx)
        val review = pending(provider)
        val before = ProviderFixture.state.toString()
        ProviderFixture.afterSave = { ctx.preferences.put("aries_group_id", "grp_other") }
        ProviderFixture.calls.clear()
        denied("conflict") { confirm(provider, review) }
        check(ProviderFixture.state.toString() == before && ProviderFixture.binding().optString("content_key").isEmpty())
        check(ProviderFixture.calls.none { it.endsWith("/ack") })
    }
    test("changing selection while loading previous durable state blocks key persistence") {
        val ctx = context(); ProviderFixture.reset(ctx)
        val provider = AriesConnectionProvider(ctx)
        val review = pending(provider)
        val before = ProviderFixture.state.toString()
        ProviderFixture.afterNetwork = {
            if (it.endsWith("/key-deliveries")) ProviderFixture.beforeLoad = {
                ctx.preferences.put("aries_endpoint", "https://other.test")
            }
        }
        denied("conflict") { confirm(provider, review) }
        check(ProviderFixture.state.toString() == before && ProviderFixture.binding().optString("content_key").isEmpty())
    }
    test("replacing signed envelope with same delivery id and fingerprint invalidates review") {
        val ctx = context(); ProviderFixture.reset(ctx)
        val provider = AriesConnectionProvider(ctx)
        val review = pending(provider)
        ProviderFixture.delivery.put("envelope", AriesCrypto.createPair(ProviderFixture.peerSeed,
            ProviderFixture.contentKey, "node_test", "grp_test", 1, "app_test", ProviderFixture.localId, ProviderFixture.localPublic))
        denied("conflict") { confirm(provider, review) }
        check(ProviderFixture.binding().optString("content_key").isEmpty())
    }
    test("wrong application device or public key cannot produce a review") {
        for (wrongDevice in listOf(true, false)) {
            val ctx = context(); ProviderFixture.reset(ctx)
            val provider = AriesConnectionProvider(ctx)
            provider.saveApplication("https://aries.test", "node_test", "grp_test", "app_test")
            if (wrongDevice) ProviderFixture.applicationDevice = ProviderFixture.peerId
            else ProviderFixture.applicationPublic = ProviderFixture.peerPublic
            denied("permission_denied") { provider.pendingDelivery() }
            check(ProviderFixture.binding().optString("content_key").isEmpty())
        }
    }
    test("revoked local member cannot consume a reviewed delivery") {
        val ctx = context(); ProviderFixture.reset(ctx)
        val provider = AriesConnectionProvider(ctx); val review = pending(provider)
        ProviderFixture.activeMembers = false; ProviderFixture.calls.clear()
        denied("pairing_incomplete") { confirm(provider, review) }
        check(ProviderFixture.binding().optString("content_key").isEmpty() && ProviderFixture.calls.none { it.endsWith("/ack") })
    }
    test("incomplete key rotation cannot consume or acknowledge a reviewed delivery") {
        val ctx = context(); ProviderFixture.reset(ctx)
        val provider = AriesConnectionProvider(ctx); val review = pending(provider)
        ProviderFixture.rotation = true; ProviderFixture.calls.clear()
        denied("pairing_incomplete") { confirm(provider, review) }
        check(ProviderFixture.binding().optString("content_key").isEmpty() && ProviderFixture.calls.none { it.endsWith("/ack") })
    }
    test("revocation between delivery fetch and key write is caught by authoritative refresh") {
        val ctx = context(); ProviderFixture.reset(ctx)
        val provider = AriesConnectionProvider(ctx); val review = pending(provider)
        ProviderFixture.afterNetwork = { if (it.endsWith("/key-deliveries")) ProviderFixture.activeMembers = false }
        denied("pairing_incomplete") { confirm(provider, review) }
        check(ProviderFixture.binding().optString("content_key").isEmpty())
    }
    test("failed network ack keeps durable pending key and cannot report paired") {
        val ctx = context(); ProviderFixture.reset(ctx)
        val provider = AriesConnectionProvider(ctx); val review = pending(provider)
        ProviderFixture.failAck = true
        check(runCatching { confirm(provider, review) }.isFailure)
        check(ProviderFixture.binding().getString("pending_ack") == "kd_test")
        ProviderFixture.failAck = false
        val state = AriesConnectionProvider(ctx).call("get_connection_state")
        check(state.getString("pairing_status") == "paired" && ProviderFixture.binding().optString("pending_ack").isEmpty())
    }
    test("revoked peer stops retrying previously pending key acknowledgement") {
        val ctx = context(); ProviderFixture.reset(ctx)
        val provider = AriesConnectionProvider(ctx); val review = pending(provider)
        ProviderFixture.failAck = true
        check(runCatching { confirm(provider, review) }.isFailure)
        ProviderFixture.failAck = false; ProviderFixture.peerActive = false; ProviderFixture.calls.clear()
        val state = provider.call("get_connection_state")
        check(state.getString("pairing_status") != "paired")
        check(ProviderFixture.binding().getString("pending_ack") == "kd_test")
        check(ProviderFixture.calls.none { it.endsWith("/ack") })
    }
    test("selector change during real ack retains pending acknowledgement in original scope") {
        val ctx = context(); ProviderFixture.reset(ctx)
        val provider = AriesConnectionProvider(ctx); val review = pending(provider)
        ProviderFixture.afterNetwork = { if (it.endsWith("/ack")) ctx.preferences.put("aries_group_id", "grp_other") }
        denied("conflict") { confirm(provider, review) }
        check(ProviderFixture.binding().getString("pending_ack") == "kd_test")
        check(ProviderFixture.state.getJSONObject("bindings").length() == 1)
    }
    test("unpaired active membership does not grant encrypted capabilities") {
        val ctx = context(); ProviderFixture.reset(ctx)
        val state = AriesConnectionProvider(ctx).call("get_connection_state")
        check(state.getString("pairing_status") == "awaiting_approval")
        check(!state.getJSONObject("capabilities").getBoolean("encrypted_messages"))
        check(!state.getJSONObject("capabilities").getBoolean("encrypted_files"))
    }
    test("remote online and offline values come from authoritative presence only") {
        for (online in listOf(true, false)) {
            val ctx = context(); ProviderFixture.reset(ctx)
            ProviderFixture.remoteOnline = online
            val state = AriesConnectionProvider(ctx).call("get_connection_state")
            val remote = state.getJSONObject("remote_device")
            check(remote.getString("id") == ProviderFixture.peerId)
            check(remote.getString("online") == if (online) "online" else "offline")
            check(remote.getString("last_seen_at") == "2026-10-11T08:00:00Z")
        }
    }
    test("business payload is frozen before a transport callback can mutate caller JSON") {
        val ctx = context(); ProviderFixture.reset(ctx)
        val provider = AriesConnectionProvider(ctx); confirm(provider, pending(provider))
        val request = params().put("kind", "test").put("idempotency_key", "op_original")
            .put("body", JSONObject().put("text", "original"))
        ProviderFixture.afterNetwork = {
            if (it == "node") {
                request.put("group_id", "grp_other")
                request.getJSONObject("body").put("text", "changed")
                request.put("token", "untrusted")
            }
        }
        provider.call("send_encrypted_message", request)
        val journal = ProviderFixture.state.getJSONObject("mutations")
        val sent = journal.keys().asSequence().map { journal.getJSONObject(it) }
            .first { it.getJSONObject("body").has("ciphertext") }.getJSONObject("body")
        val e = AriesCrypto.readContent(sent.getString("ciphertext"))
        val plain = AriesCrypto.openContent(ProviderFixture.contentKey, e, ProviderFixture.localPublic)
        check(JSONObject(String(plain, Charsets.UTF_8)).getJSONObject("body").getString("text") == "original")
        plain.fill(0)
    }
    test("stale publication response stays out of durable journal") {
        val ctx = context(); ProviderFixture.reset(ctx)
        val provider = AriesConnectionProvider(ctx); confirm(provider, pending(provider))
        ProviderFixture.afterNetwork = {
            if (it.endsWith("/messages")) ctx.preferences.put("aries_group_id", "grp_other")
        }
        denied("conflict") {
            provider.call("send_encrypted_message", params().put("kind", "test")
                .put("body", "test").put("idempotency_key", "op_test"))
        }
        val entries = ProviderFixture.state.getJSONObject("mutations")
        val message = entries.keys().asSequence().map { entries.getJSONObject(it) }
            .first { it.getJSONObject("body").has("ciphertext") }
        check(!message.has("response"))
        check(ProviderFixture.state.getJSONObject("bindings").length() == 1)
    }
    test("cancellation after delivery network response prevents key persistence and acknowledgement") {
        val ctx = context(); ProviderFixture.reset(ctx)
        val provider = AriesConnectionProvider(ctx); val review = pending(provider)
        val before = ProviderFixture.state.toString()
        coroutineScope {
            val child = async {
                ProviderFixture.afterNetwork = { if (it.endsWith("/key-deliveries")) throw CancellationException("UI closed") }
                confirm(provider, review)
            }
            check(runCatching { child.await() }.exceptionOrNull() is CancellationException)
        }
        check(ProviderFixture.state.toString() == before && ProviderFixture.binding().optString("content_key").isEmpty())
    }
    test("actual coroutine cancellation during key persistence rolls back durable key before ack") {
        val ctx = context(); ProviderFixture.reset(ctx)
        val provider = AriesConnectionProvider(ctx); val review = pending(provider)
        val before = ProviderFixture.state.toString()
        ProviderFixture.calls.clear()
        coroutineScope {
            val child = async {
                val job = currentCoroutineContext().job
                ProviderFixture.afterSave = { job.cancel(CancellationException("UI scope cancelled")) }
                confirm(provider, review)
            }
            check(runCatching { child.await() }.exceptionOrNull() is CancellationException)
        }
        check(ProviderFixture.state.toString() == before && ProviderFixture.binding().optString("content_key").isEmpty())
        check(ProviderFixture.calls.none { it.endsWith("/ack") } && ctx.preferences.listenerCount == 0)
    }

    val seed = AriesCrypto.decode(frozen.getString("seed_base64url"), 32)
    val key = AriesCrypto.decode(frozen.getString("content_key_base64url"), 32)
    val public = frozen.getString("public_key_base64url")
    val sender = frozen.getString("sender_id")
    val plaintext = frozen.getString("plaintext_utf8").toByteArray(Charsets.UTF_8)
    test("frozen PC Ed25519 seed derives byte-identical public identity") {
        check(AriesCrypto.publicKey(seed) == public)
        AriesCrypto.identity(sender, public)
    }
    test("frozen PC message authenticates Ed25519 and decrypts HMAC-derived AES-GCM key") {
        val e = AriesCrypto.readContent(frozen.getString("message_envelope_base64url"))
        check(AriesCrypto.openContent(key, e, public).contentEquals(plaintext))
        val aad = listOf(e.getString("protocol"), "1", e.getString("type"), e.getString("node_id"),
            e.getString("group_id"), e.getLong("epoch").toString(), e.getString("sender_id"), e.getString("object_id")).joinToString("\n")
        check(aad == frozen.getString("message_aad_utf8"))
    }
    test("frozen PC file manifest decrypts and matches all declared plaintext properties") {
        val e = AriesCrypto.readContent(frozen.getString("manifest_envelope_base64url"))
        val manifest = JSONObject(String(AriesCrypto.openContent(key, e, public), Charsets.UTF_8))
        val expected = frozen.getJSONObject("manifest")
        fun field(current: String, historical: String) = if (expected.has(current)) current else historical
        check(manifest.getString("relative_path") == expected.getString(field("relative_path", "RelativePath")))
        check(manifest.getLong("size") == expected.getLong(field("size", "Size")))
        check(manifest.getInt("chunk_size") == expected.getInt(field("chunk_size", "ChunkSize")))
        check(manifest.getInt("chunk_count") == expected.getInt(field("chunk_count", "ChunkCount")))
        check(manifest.getString("hash") == expected.getString(field("hash", "Hash")))
    }
    test("frozen PC raw block verifies exact hash, 92-byte overhead and chunk AAD") {
        val e = AriesCrypto.readContent(frozen.getString("manifest_envelope_base64url"))
        val raw = Base64.getDecoder().decode(frozen.getString("chunk_raw_base64"))
        check(AriesCrypto.encode(raw) == frozen.getString("chunk_raw_base64url"))
        check(AriesCrypto.hash(raw) == frozen.getString("ciphertext_sha256"))
        check(raw.size - plaintext.size == frozen.getInt("chunk_overhead"))
        val opened = AriesCrypto.openChunk(key, e, public, frozen.getInt("chunk_index"), raw)
        check(opened.contentEquals(plaintext))
        val manifest = frozen.getJSONObject("manifest")
        check(AriesCrypto.hash(opened) == manifest.getString(if (manifest.has("hash")) "hash" else "Hash"))
    }
    test("PC envelope rejects modified group, node, epoch, sender and object AAD") {
        for (field in listOf("node_id", "group_id", "sender_id", "object_id", "epoch")) {
            val e = AriesCrypto.readContent(frozen.getString("message_envelope_base64url"))
            e.put(field, if (field == "epoch") 2 else "tampered")
            check(runCatching { AriesCrypto.openContent(key, e, public) }.isFailure)
        }
    }
    test("PC raw block rejects wrong index, signature, nonce, tag and ciphertext") {
        val e = AriesCrypto.readContent(frozen.getString("manifest_envelope_base64url"))
        val raw = Base64.getDecoder().decode(frozen.getString("chunk_raw_base64"))
        check(runCatching { AriesCrypto.openChunk(key, e, public, 1, raw) }.isFailure)
        for (index in listOf(0, 12, raw.size - 80, raw.lastIndex)) {
            val corrupt = raw.copyOf(); corrupt[index] = (corrupt[index].toInt() xor 1).toByte()
            check(runCatching { AriesCrypto.openChunk(key, e, public, 0, corrupt) }.isFailure)
        }
    }
    test("authenticated empty file uses one 92-byte block and zero plaintext") {
        val e = AriesCrypto.envelope("file", "node_test", "grp_test", 1, sender, "empty_test")
        val raw = AriesCrypto.sealChunk(seed, key, e, 0, ByteArray(0))
        check(raw.size == 92 && AriesCrypto.openChunk(key, e, public, 0, raw).isEmpty())
    }
    test("pair key Ed25519-to-X25519, HKDF and GCM agree for distinct synthetic identities") {
        val encoded = AriesCrypto.createPair(ProviderFixture.peerSeed, key, "node_test", "grp_test",
            1, "app_test", sender, public)
        val e = AriesCrypto.readPair(encoded)
        check(AriesCrypto.openPair(seed, e).contentEquals(key))
        check(runCatching { AriesCrypto.openPair(ProviderFixture.peerSeed, e) }.isFailure)
        val tampered = JSONObject(e.toString()).put("application_id", "app_other")
        check(runCatching { AriesCrypto.readPair(AriesCrypto.encode(tampered.toString().toByteArray())) }.isFailure)
    }
    seed.fill(0); key.fill(0)
    println("Aries provider checks: $checks PASS (real provider/scope/crypto; isolated offline IO fixtures)")
    println("Frozen PC wire fixture: ${File(args[1]).canonicalPath}")
}
