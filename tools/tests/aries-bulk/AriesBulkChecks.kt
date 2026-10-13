package com.dafeiyu.mobile.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

fun main(args: Array<String>) = runBlocking {
    val root = File(args[0]).apply { mkdirs() }
    val skipLongTransfer = args.getOrNull(1) == "quick"
    var passed = 0
    suspend fun test(name: String, body: suspend () -> Unit) {
        try { body() } catch (error: Throwable) { throw AssertionError(name, error) }
        passed++; println("PASS $name")
    }
    fun context() = Context(File(root, "fixture").apply { mkdirs() }).also(BulkFixture::reset)
    fun request(id: String, operation: String = "artifact.read", bytes: String = "") = JSONObject()
        .put("group_id", "grp_test").put("kind", RemoteDshClient.REQUEST_KIND).put("idempotency_key", "dsh-remote-$id")
        .put("body", JSONObject().put("protocol", RemoteDshClient.PROTOCOL).put("request_id", id)
            .put("target_device_id", BulkFixture.peerId).put("operation", operation)
            .put("payload", JSONObject().put("offset", 0).put("bytes", bytes)))
    suspend fun denied(code: String, body: suspend () -> Unit) {
        val error = runCatching { body() }.exceptionOrNull()
        check(error is AriesProviderException && error.code == code) { "expected=$code actual=$error" }
    }
    fun ackParams(id: String, operation: String, sequence: Long) = JSONObject()
        .put("group_id", "grp_test").put("kind", "dsh_remote_ack").put("idempotency_key", "bulk-ack-$id-$operation-$sequence")
        .put("body", JSONObject().put("protocol", RemoteDshClient.PROTOCOL).put("request_id", id)
            .put("target_device_id", BulkFixture.peerId).put("operation", operation).put("sequence", sequence))
    suspend fun acknowledge(provider: AriesConnectionProvider, cursor: String, requestId: String): String {
        var after = cursor
        var terminalSequence: Long? = null
        while (true) {
            val page = provider.call("list_incoming_messages", JSONObject().put("group_id", "grp_test").put("cursor", after), BulkFixture.scope)
            val messages = page.getJSONArray("messages")
            for (index in 0 until messages.length()) {
                val message = messages.getJSONObject(index)
                if (message.optJSONObject("body")?.optString("request_id") != requestId) continue
                val body = message.getJSONObject("body")
                if (body.optString("type") in setOf("result", "error")) terminalSequence = body.getLong("sequence")
                val result = provider.call("acknowledge_message", JSONObject().put("group_id", "grp_test")
                    .put("message_id", message.getString("message_id")), BulkFixture.scope)
                check(result.getBoolean("acknowledged") && result.getString("acknowledgement_scope") == "local_durable")
            }
            val next = page.getString("next_cursor")
            if (next == after) {
                terminalSequence?.let { sequence ->
                    val entry = BulkFixture.copyState().getJSONObject("mutations")
                        .getJSONObject("${BulkFixture.providerScope}:message:dsh-remote-$requestId")
                    val operation = entry.getJSONObject("bulk").getString("operation")
                    val probe = provider.retireTransferReceipt(BulkFixture.scope, requestId, operation)
                    check(!probe.getBoolean("retired") && probe.getBoolean("ack_required"))
                    provider.call("send_encrypted_message", ackParams(requestId, operation, sequence), BulkFixture.scope)
                }
                return next
            }
            after = next
        }
    }

    test("policy accepts only five exact safe native operations; every task/prepare/commit intent fails") {
        context()
        for (operation in AriesBulkPolicy.operations) check(AriesBulkPolicy.request(request("native-test", operation), BulkFixture.scope).operation == operation)
        for (operation in listOf("session.prompt", "session.create", "settings.update", "artifact.prepare", "attachment.prepare", "attachment.commit", "attachment.cancel"))
            denied("unsupported_method") { AriesBulkPolicy.request(request("native-test", operation), BulkFixture.scope) }
    }
    test("native request rejects forged protocol, target, idempotency, claimed payload shape and control fields") {
        val ctx = context(); val provider = AriesConnectionProvider(ctx)
        for (field in listOf("protocol", "target_device_id")) {
            val value = request("native-test"); value.getJSONObject("body").put(field, "forged")
            denied("permission_denied") { provider.callNativeBulk(value, BulkFixture.scope) }
        }
        denied("conflict") { provider.callNativeBulk(request("native-test").put("idempotency_key", "foreign-key"), BulkFixture.scope) }
        denied("permission_denied") { provider.callNativeBulk(request("native-test").put("epoch", 1), BulkFixture.scope) }
        val shape = request("native-test"); shape.getJSONObject("body").put("extra", true)
        denied("permission_denied") { provider.callNativeBulk(shape, BulkFixture.scope) }
        check(BulkFixture.postedCount() == 0)
    }
    for (width in listOf(4, 8)) test("actual production Provider permits $width overlapping signed transport calls with durable ciphertext") {
        val ctx = context(); val provider = AriesConnectionProvider(ctx)
        val gate = BulkFixture.Gate(width); BulkFixture.gate = gate
        coroutineScope {
            val tasks = (0 until width).map { index -> async { provider.callNativeBulk(request("parallel-$width-$index"), BulkFixture.scope) } }
            withTimeout(15_000) { gate.reached.await() }
            check(BulkFixture.inFlight.get() == width && BulkFixture.maximumInFlight.get() == width)
            check(BulkFixture.copyState().getJSONObject("mutations").length() == width)
            gate.release.complete(Unit); tasks.awaitAll().forEach { check(it.getString("message_id").isNotEmpty()) }
        }
        val entries = BulkFixture.copyState().getJSONObject("mutations")
        check(entries.length() == width && entries.keys().asSequence().all { entries.getJSONObject(it).has("response") })
        check(ctx.preferences.listenerCount == 0)
    }
    for (width in listOf(4, 8)) test("real loopback HTTP server observes $width concurrent encrypted Provider POST requests before any response is released") {
        val ctx = context(); val provider = AriesConnectionProvider(ctx)
        BulkHttpFixture(width).use { http ->
            BulkFixture.http = http
            coroutineScope {
                val tasks = (0 until width).map { index -> async { provider.callNativeBulk(request("http-$width-$index"), BulkFixture.scope) } }
                try {
                    withTimeout(15_000) { http.reached.await() }
                    check(http.inFlight.get() == width && http.maximum.get() == width && http.received.get() == width)
                } finally { http.releaseResponses() }
                tasks.awaitAll().forEach { check(it.getString("message_id").startsWith("http_")) }
            }
            check(BulkFixture.copyState().getJSONObject("mutations").length() == width)
        }
        BulkFixture.http = null
        println("EVIDENCE loopbackHttp=true serverMaximumInFlight=$width productionProvider=true actualAndroidHttp=false publicAries=false")
    }
    test("twelve parallel callers never exceed eight HTTP operations; newest vault responses are merged without lost updates") {
        val ctx = context(); val provider = AriesConnectionProvider(ctx); val gate = BulkFixture.Gate(8); BulkFixture.gate = gate
        coroutineScope {
            val tasks = (0 until 12).map { index -> async { provider.callNativeBulk(request("bounded-$index"), BulkFixture.scope) } }
            withTimeout(15_000) { gate.reached.await() }; delay(50)
            check(BulkFixture.inFlight.get() == 8); gate.release.complete(Unit); tasks.awaitAll()
        }
        check(BulkFixture.maximumInFlight.get() == 8 && BulkFixture.postedCount() == 12)
        val entries = BulkFixture.copyState().getJSONObject("mutations")
        check(entries.length() == 12 && entries.keys().asSequence().all { entries.getJSONObject(it).has("response") })
    }
    test("two Provider instances share the global eight-permit bound and vault merge gate") {
        val ctx = context(); val providers = listOf(AriesConnectionProvider(ctx), AriesConnectionProvider(ctx))
        val gate = BulkFixture.Gate(8); BulkFixture.gate = gate
        coroutineScope {
            val tasks = (0 until 10).map { index -> async { providers[index % 2].callNativeBulk(request("instances-$index"), BulkFixture.scope) } }
            withTimeout(15_000) { gate.reached.await() }; check(BulkFixture.inFlight.get() == 8)
            gate.release.complete(Unit); tasks.awaitAll()
        }
        check(BulkFixture.maximumInFlight.get() == 8 && BulkFixture.copyState().getJSONObject("mutations").length() == 10)
    }
    test("ordinary plugin calls remain serialized even with an otherwise valid remote_scope") {
        val ctx = context(); val provider = AriesConnectionProvider(ctx); val gate = BulkFixture.Gate(1); BulkFixture.gate = gate
        coroutineScope {
            val first = async { provider.call("send_encrypted_message", request("plugin-first"), BulkFixture.scope) }
            withTimeout(15_000) { gate.reached.await() }
            val second = async { provider.call("send_encrypted_message", request("plugin-second"), BulkFixture.scope) }
            delay(50); check(BulkFixture.inFlight.get() == 1)
            gate.release.complete(Unit); awaitAll(first, second)
        }
        check(BulkFixture.maximumInFlight.get() == 1)
        check(BulkFixture.copyState().getJSONObject("mutations").keys().asSequence().none {
            BulkFixture.copyState().getJSONObject("mutations").getJSONObject(it).has("bulk")
        })
    }
    test("retry reuses identical encrypted body and idempotency; concurrent duplicate results do not create another message") {
        val ctx = context(); val provider = AriesConnectionProvider(ctx); val gate = BulkFixture.Gate(2); BulkFixture.gate = gate
        coroutineScope {
            val tasks = (0..1).map { async { provider.callNativeBulk(request("stable-retry"), BulkFixture.scope) } }
            withTimeout(15_000) { gate.reached.await() }; gate.release.complete(Unit)
            val results = tasks.awaitAll(); check(results[0].getString("message_id") == results[1].getString("message_id"))
        }
        check(BulkFixture.postedCount() == 1 && BulkFixture.copyState().getJSONObject("mutations").length() == 1)
        val old = BulkFixture.copyState().getJSONObject("mutations").toString()
        provider.callNativeBulk(request("stable-retry"), BulkFixture.scope)
        check(BulkFixture.postedCount() == 1 && old == BulkFixture.copyState().getJSONObject("mutations").toString())
        denied("conflict") { provider.callNativeBulk(request("stable-retry", bytes = "changed"), BulkFixture.scope) }
    }
    test("cancelled HTTP keeps its exact uncertain journal but releases all slots and selection observers") {
        val ctx = context(); val provider = AriesConnectionProvider(ctx); val gate = BulkFixture.Gate(1); BulkFixture.gate = gate
        coroutineScope {
            val task = async { provider.callNativeBulk(request("cancelled-request"), BulkFixture.scope) }
            withTimeout(15_000) { gate.reached.await() }; task.cancel()
            check(runCatching { task.await() }.exceptionOrNull() is CancellationException)
        }
        check(ctx.preferences.listenerCount == 0 && BulkFixture.inFlight.get() == 0 && BulkFixture.postedCount() == 0)
        val uncertain = BulkFixture.copyState().getJSONObject("mutations").toString()
        BulkFixture.gate = null
        provider.callNativeBulk(request("cancelled-request"), BulkFixture.scope)
        check(BulkFixture.postedCount() == 1 && uncertain.contains("ciphertext"))
    }
    for ((name, mutate, code) in listOf(
        Triple("membership revoked", { BulkFixture.members = false }, "revoked"),
        Triple("peer revoked", { BulkFixture.peerActive = false }, "pairing_incomplete"),
        Triple("rotation required", { BulkFixture.rotation = true }, "conflict"),
        Triple("epoch changed", { BulkFixture.mutateBinding { it.put("epoch", 2) } }, "conflict"),
        Triple("host generation changed", { BulkFixture.mutateBinding { it.put("remote_host_generation", "generation_2") } }, "conflict"),
        Triple("host capability revoked", { BulkFixture.mutateBinding { it.put("remote_host_capabilities", JSONArray().put("remote_task")) } }, "unsupported_method"),
        Triple("content key changed", { BulkFixture.mutateBinding { it.put("content_key", AriesCrypto.encode(ByteArray(32) { 7 })) } }, "conflict")
    )) test("$name during lock-free IO refuses the response and leaves the original uncertain intent") {
        val ctx = context(); val provider = AriesConnectionProvider(ctx)
        BulkFixture.postHook = mutate
        denied(code) { provider.callNativeBulk(request("scope-change"), BulkFixture.scope) }
        check(BulkFixture.copyState().getJSONObject("mutations").keys().asSequence().all {
            !BulkFixture.copyState().getJSONObject("mutations").getJSONObject(it).has("response")
        })
        check(ctx.preferences.listenerCount == 0)
    }
    for (field in listOf("aries_endpoint", "aries_node_id", "aries_group_id", "aries_device_id")) test("$field switches away and back during IO still invalidate the native admission") {
        val ctx = context(); val provider = AriesConnectionProvider(ctx)
        val original = ctx.preferences.all[field] as String
        BulkFixture.postHook = { ctx.preferences.put(field, "changed"); ctx.preferences.put(field, original) }
        denied("conflict") { provider.callNativeBulk(request("selector-test"), BulkFixture.scope) }
        check(ctx.preferences.listenerCount == 0)
    }
    test("local identity changes outside the Provider lock are rejected before response merge") {
        val ctx = context(); val provider = AriesConnectionProvider(ctx)
        BulkFixture.postHook = { BulkFixture.identityPublic = BulkFixture.peerPublic }
        denied("conflict") { provider.callNativeBulk(request("identity-test"), BulkFixture.scope) }
    }
    test("retirement requires a genuine peer terminal frame and all exact frames to be locally acknowledged") {
        val ctx = context(); val provider = AriesConnectionProvider(ctx)
        BulkFixture.eventCount = 2
        provider.callNativeBulk(request("retire-real"), BulkFixture.scope)
        denied("conflict") { provider.retireTransferReceipt(BulkFixture.scope, "retire-real", "artifact.read") }
        val page = provider.call("list_incoming_messages", JSONObject().put("group_id", "grp_test").put("cursor", "0"), BulkFixture.scope)
        val frames = page.getJSONArray("messages")
        check(frames.length() == 2)
        provider.call("acknowledge_message", JSONObject().put("group_id", "grp_test")
            .put("message_id", frames.getJSONObject(1).getString("message_id")), BulkFixture.scope)
        denied("conflict") { provider.retireTransferReceipt(BulkFixture.scope, "retire-real", "artifact.read") }
        provider.call("acknowledge_message", JSONObject().put("group_id", "grp_test")
            .put("message_id", frames.getJSONObject(0).getString("message_id")), BulkFixture.scope)
        val missing = provider.retireTransferReceipt(BulkFixture.scope, "retire-real", "artifact.read")
        check(!missing.getBoolean("retired") && missing.getBoolean("ack_required"))
        provider.call("send_encrypted_message", ackParams("retire-real", "artifact.read", 1), BulkFixture.scope)
        check(provider.retireTransferReceipt(BulkFixture.scope, "retire-real", "artifact.read").getBoolean("retired"))
        check(provider.retireTransferReceipt(BulkFixture.scope, "retire-real", "artifact.read").getBoolean("already_retired"))
        check(BulkFixture.copyState().getJSONObject("mutations").length() == 0 && BulkFixture.copyState().getJSONObject("receipts").length() == 0)
        val replay = provider.call("list_incoming_messages", JSONObject().put("group_id", "grp_test").put("cursor", "0"), BulkFixture.scope)
        check(replay.getJSONArray("messages").length() == 0)
    }
    test("retirement cannot remove prompt/settings/prepare/commit intents or bulk entries admitted by a plugin") {
        val ctx = context(); val provider = AriesConnectionProvider(ctx)
        provider.call("send_encrypted_message", request("plugin-retire"), BulkFixture.scope)
        denied("permission_denied") { provider.retireTransferReceipt(BulkFixture.scope, "plugin-retire", "artifact.read") }
        for (operation in listOf("session.prompt", "settings.update", "artifact.prepare", "attachment.prepare", "attachment.commit"))
            denied("unsupported_method") { provider.retireTransferReceipt(BulkFixture.scope, "preserve-intent", operation) }
        check(BulkFixture.copyState().getJSONObject("mutations").length() == 1)
    }
    test("unacknowledged unrelated messages stay gaps in compact ACK ranges and survive bulk retirement") {
        val ctx = context(); val provider = AriesConnectionProvider(ctx)
        val unrelated = BulkFixture.append(BulkFixture.peerId, BulkFixture.peerSeed, JSONObject().put("kind", "unrelated").put("body", JSONObject().put("keep", true)))
        provider.callNativeBulk(request("keep-gaps"), BulkFixture.scope)
        acknowledge(provider, "0", "keep-gaps")
        provider.retireTransferReceipt(BulkFixture.scope, "keep-gaps", "artifact.read")
        val replay = provider.call("list_incoming_messages", JSONObject().put("group_id", "grp_test").put("cursor", "0"), BulkFixture.scope).getJSONArray("messages")
        check(replay.length() == 1 && replay.getJSONObject(0).getString("message_id") == unrelated.getString("id"))
        check(!AriesBulkPolicy.suppressed(BulkFixture.binding(), unrelated.getLong("sequence").toULong()))
    }
    test("message pages reject wrong cursor, unordered sequence and fractional sequence before receipt persistence") {
        val ctx = context(); val provider = AriesConnectionProvider(ctx)
        provider.call("get_connection_state")
        val state = BulkFixture.copyState().toString()
        for (bad in listOf(
            JSONObject().put("items", JSONArray()).put("next_cursor", "1"),
            JSONObject().put("items", JSONArray().put(JSONObject().put("id", "m1").put("sequence", 2)).put(JSONObject().put("id", "m2").put("sequence", 1))).put("next_cursor", "1"),
            JSONObject().put("items", JSONArray().put(JSONObject().put("id", "m1").put("sequence", 1.5))).put("next_cursor", "1")
        )) {
            BulkFixture.invalidPage = bad
            denied("protocol_error") { provider.call("get_connection_state") }
            check(BulkFixture.copyState().toString() == state)
        }
    }
    test("ACK intervals cover uint64 edge without overflow and never bridge unacknowledged gaps") {
        context(); val value = JSONObject()
        AriesBulkPolicy.suppress(value, ULong.MAX_VALUE)
        AriesBulkPolicy.suppress(value, ULong.MAX_VALUE - 1uL)
        AriesBulkPolicy.suppress(value, 4uL); AriesBulkPolicy.suppress(value, 6uL)
        check(value.getJSONArray("bulk_ack_ranges").length() == 3 && !AriesBulkPolicy.suppressed(value, 5uL))
        AriesBulkPolicy.suppress(value, 5uL)
        check(value.getJSONArray("bulk_ack_ranges").length() == 2 && AriesBulkPolicy.suppressed(value, ULong.MAX_VALUE))
    }
    test("current-epoch own messages require real signature/content verification before durable suppression") {
        val ctx = context(); val provider = AriesConnectionProvider(ctx)
        val own = BulkFixture.append(BulkFixture.localId, BulkFixture.localSeed, JSONObject().put("kind", "own").put("body", JSONObject()))
        provider.call("get_connection_state")
        check(AriesBulkPolicy.suppressed(BulkFixture.binding(), own.getLong("sequence").toULong()))
        val forged = BulkFixture.append(BulkFixture.localId, BulkFixture.localSeed, JSONObject().put("kind", "own").put("body", JSONObject()))
        BulkFixture.tamperMessage(forged.getString("id")) { item ->
            val envelope = AriesCrypto.readContent(item.getString("ciphertext"))
            envelope.put("signature", AriesCrypto.encode(ByteArray(64)))
            item.put("ciphertext", AriesCrypto.encode(envelope.toString().toByteArray()))
        }
        check(runCatching { provider.call("get_connection_state") }.isFailure)
        check(!AriesBulkPolicy.suppressed(BulkFixture.binding(), forged.getLong("sequence").toULong()))
    }
    test("historical own epoch keeps prior skip behavior without adding a current-epoch suppression interval") {
        val ctx = context(); val provider = AriesConnectionProvider(ctx)
        val old = BulkFixture.append(BulkFixture.localId, BulkFixture.localSeed, JSONObject().put("kind", "own").put("body", JSONObject()))
        BulkFixture.mutateBinding { it.put("epoch", 2) }
        provider.call("get_connection_state")
        check(!AriesBulkPolicy.suppressed(BulkFixture.binding(), old.getLong("sequence").toULong()))
    }
    test("confirmed encrypted bulk ACK and original request retire together; unknown ACK body cannot be reclaimed") {
        val ctx = context(); val provider = AriesConnectionProvider(ctx)
        provider.callNativeBulk(request("ack-body-test"), BulkFixture.scope)
        BulkFixture.ackEvents = false
        val page = provider.call("list_incoming_messages", JSONObject().put("group_id", "grp_test").put("cursor", "0"), BulkFixture.scope)
        val message = page.getJSONArray("messages").getJSONObject(0)
        provider.call("acknowledge_message", JSONObject().put("group_id", "grp_test").put("message_id", message.getString("message_id")), BulkFixture.scope)
        val forged = ackParams("ack-body-test", "artifact.read", 0)
        forged.getJSONObject("body").put("operation", "attachment.commit")
        provider.call("send_encrypted_message", forged, BulkFixture.scope)
        denied("conflict") { provider.retireTransferReceipt(BulkFixture.scope, "ack-body-test", "artifact.read") }
        check(BulkFixture.copyState().getJSONObject("mutations").length() == 2 && BulkFixture.copyState().getJSONObject("receipts").length() == 1)
    }
    test("uncertain ACK send is retried with its exact ciphertext; retiring twice never recreates ACK") {
        val ctx = context(); val provider = AriesConnectionProvider(ctx)
        provider.callNativeBulk(request("ack-uncertain"), BulkFixture.scope)
        val page = provider.call("list_incoming_messages", JSONObject().put("group_id", "grp_test").put("cursor", "0"), BulkFixture.scope)
        provider.call("acknowledge_message", JSONObject().put("group_id", "grp_test")
            .put("message_id", page.getJSONArray("messages").getJSONObject(0).getString("message_id")), BulkFixture.scope)
        BulkFixture.postFailure = true
        check(runCatching { provider.call("send_encrypted_message", ackParams("ack-uncertain", "artifact.read", 0), BulkFixture.scope) }.isFailure)
        val marker = provider.retireTransferReceipt(BulkFixture.scope, "ack-uncertain", "artifact.read")
        check(!marker.getBoolean("retired") && marker.getBoolean("ack_required"))
        val old = BulkFixture.copyState().getJSONObject("mutations").getJSONObject("${BulkFixture.providerScope}:message:bulk-ack-ack-uncertain-artifact.read-0")
            .getJSONObject("body").toString()
        BulkFixture.postFailure = false
        val resumed = AriesConnectionProvider(ctx)
        resumed.call("send_encrypted_message", ackParams("ack-uncertain", "artifact.read", 0), BulkFixture.scope)
        check(old == BulkFixture.copyState().getJSONObject("mutations").getJSONObject("${BulkFixture.providerScope}:message:bulk-ack-ack-uncertain-artifact.read-0").getJSONObject("body").toString())
        check(resumed.retireTransferReceipt(BulkFixture.scope, "ack-uncertain", "artifact.read").getInt("retired_mutations") == 2)
        val sends = BulkFixture.postedCount()
        check(AriesConnectionProvider(ctx).retireTransferReceipt(BulkFixture.scope, "ack-uncertain", "artifact.read").getBoolean("already_retired"))
        check(sends == BulkFixture.postedCount() && BulkFixture.copyState().getJSONObject("mutations").length() == 0)
    }
    fun webRequest(id: String) = request(id, "web.http").also {
        it.getJSONObject("body").put("payload", JSONObject().put("method", "GET").put("path", "/")
            .put("headers", JSONObject()).put("body_base64", ""))
    }
    fun webAcknowledgement(id: String, sequence: Long = 0) = JSONObject().put("group_id", "grp_test")
        .put("kind", "dsh_remote_ack").put("idempotency_key", "web-ack-$id-$sequence")
        .put("body", JSONObject().put("protocol", RemoteDshClient.PROTOCOL).put("request_id", id)
            .put("target_device_id", BulkFixture.peerId).put("sequence", sequence))
    fun enableWeb() {
        BulkFixture.nodeCapabilities = setOf("group_lifecycle")
        BulkFixture.mutateBinding { it.getJSONArray("remote_host_capabilities").put("remote_web_tunnel") }
    }
    test("native scoped request authenticates once and keeps the application gate separate from an authenticated host capability") {
        val ctx = context(); enableWeb(); val provider = AriesConnectionProvider(ctx)
        check(!provider.call("get_connection_state").getJSONObject("capabilities").getBoolean("remote_web_tunnel"))
        val before = BulkFixture.nodeCalls
        val result = provider.callNativeScoped("send_encrypted_message", webRequest("web-native-request"), BulkFixture.scope, "remote_web_tunnel")
        check(result.getString("message_id").isNotEmpty() && BulkFixture.nodeCalls == before + 1)
        check(BulkFixture.copyState().getJSONObject("web_requests").has("${BulkFixture.providerScope}:web-native-request"))
    }
    test("combined web ACK verifies the real received request/sequence and commits encrypted plus durable local receipts together") {
        val ctx = context(); enableWeb(); val provider = AriesConnectionProvider(ctx)
        provider.callNativeScoped("send_encrypted_message", webRequest("web-ack-request"), BulkFixture.scope, "remote_web_tunnel")
        val page = provider.callNativeScoped("list_incoming_messages", JSONObject().put("group_id", "grp_test").put("cursor", "0"),
            BulkFixture.scope, "remote_task")
        val event = page.getJSONArray("messages").getJSONObject(0)
        val before = BulkFixture.nodeCalls
        val receipt = provider.callNativeScoped("send_encrypted_message", webAcknowledgement("web-ack-request"),
            BulkFixture.scope, "remote_web_tunnel", event.getString("message_id"))
        check(receipt.getBoolean("acknowledged") && receipt.getString("acknowledgement_scope") == "local_durable")
        check(BulkFixture.nodeCalls == before + 1 && BulkFixture.postedCount() == 2)
        check(BulkFixture.copyState().getJSONObject("receipts").getBoolean("${BulkFixture.providerScope}:message:${event.getString("message_id")}"))
        val repeated = provider.callNativeScoped("send_encrypted_message", webAcknowledgement("web-ack-request"),
            BulkFixture.scope, "remote_web_tunnel", event.getString("message_id"))
        check(repeated.getBoolean("acknowledged") && BulkFixture.postedCount() == 2)
    }
    test("unknown, foreign request and wrong sequence ACK never suppress a received frame") {
        val ctx = context(); enableWeb(); val provider = AriesConnectionProvider(ctx)
        provider.callNativeScoped("send_encrypted_message", webRequest("web-foreign-ack"), BulkFixture.scope, "remote_web_tunnel")
        val page = provider.callNativeScoped("list_incoming_messages", JSONObject().put("group_id", "grp_test").put("cursor", "0"),
            BulkFixture.scope, "remote_task")
        val event = page.getJSONArray("messages").getJSONObject(0).getString("message_id")
        for ((params, id) in listOf(webAcknowledgement("web-foreign-ack") to "msg-unknown",
            webAcknowledgement("web-foreign-id") to event, webAcknowledgement("web-foreign-ack", 1) to event)) {
            check(runCatching { provider.callNativeScoped("send_encrypted_message", params, BulkFixture.scope, "remote_web_tunnel", id) }.isFailure)
        }
        check(BulkFixture.postedCount() == 1 && !BulkFixture.copyState().getJSONObject("receipts")
            .getBoolean("${BulkFixture.providerScope}:message:$event"))
    }
    test("failed encrypted ACK leaves local receipt pending and retries its exact durable ciphertext") {
        val ctx = context(); enableWeb(); val provider = AriesConnectionProvider(ctx)
        provider.callNativeScoped("send_encrypted_message", webRequest("web-uncertain-ack"), BulkFixture.scope, "remote_web_tunnel")
        val page = provider.callNativeScoped("list_incoming_messages", JSONObject().put("group_id", "grp_test").put("cursor", "0"),
            BulkFixture.scope, "remote_task")
        val id = page.getJSONArray("messages").getJSONObject(0).getString("message_id")
        BulkFixture.postFailure = true
        check(runCatching { provider.callNativeScoped("send_encrypted_message", webAcknowledgement("web-uncertain-ack"),
            BulkFixture.scope, "remote_web_tunnel", id) }.isFailure)
        check(!BulkFixture.copyState().getJSONObject("receipts").getBoolean("${BulkFixture.providerScope}:message:$id"))
        val key = "${BulkFixture.providerScope}:message:web-ack-web-uncertain-ack-0"
        val frozen = BulkFixture.copyState().getJSONObject("mutations").getJSONObject(key).getJSONObject("body").toString()
        BulkFixture.postFailure = false
        check(AriesConnectionProvider(ctx).callNativeScoped("send_encrypted_message", webAcknowledgement("web-uncertain-ack"),
            BulkFixture.scope, "remote_web_tunnel", id).getBoolean("acknowledged"))
        check(frozen == BulkFixture.copyState().getJSONObject("mutations").getJSONObject(key).getJSONObject("body").toString())
    }
    for (selector in listOf("aries_endpoint", "aries_node_id", "aries_group_id", "aries_device_id"))
        test("native scoped business result is refused when $selector changes away and back during network IO") {
            val ctx = context(); enableWeb(); val provider = AriesConnectionProvider(ctx)
            val old = ctx.preferences.all[selector] as String
            BulkFixture.postHook = { ctx.preferences.put(selector, "foreign"); ctx.preferences.put(selector, old) }
            denied("conflict") { provider.callNativeScoped("send_encrypted_message", webRequest("web-selector-change"),
                BulkFixture.scope, "remote_web_tunnel") }
            check(ctx.preferences.listenerCount == 0)
        }
    test("exact server unpair after an accepted send refuses stale result and persists only that durable terminal") {
        val ctx = context(); enableWeb(); val provider = AriesConnectionProvider(ctx)
        BulkFixture.postHook = { BulkFixture.terminalStatus = "group_closed" }
        denied("revoked") { provider.callNativeScoped("send_encrypted_message", webRequest("web-unpair-during-send"),
            BulkFixture.scope, "remote_web_tunnel") }
        val terminal = BulkFixture.binding().getJSONObject("terminal_receipt")
        check(terminal.getString("group_id") == "grp_test" && terminal.getString("device_id") == BulkFixture.localId &&
            terminal.getString("status") == "group_closed" && !BulkFixture.binding().has("content_key"))
    }
    test("server epoch rotation during business IO prevents ACK and leaves the received frame pending") {
        val ctx = context(); enableWeb(); val provider = AriesConnectionProvider(ctx)
        provider.callNativeScoped("send_encrypted_message", webRequest("web-rotate-ack"), BulkFixture.scope, "remote_web_tunnel")
        val page = provider.callNativeScoped("list_incoming_messages", JSONObject().put("group_id", "grp_test").put("cursor", "0"),
            BulkFixture.scope, "remote_task")
        val id = page.getJSONArray("messages").getJSONObject(0).getString("message_id")
        BulkFixture.postHook = { BulkFixture.serverGeneration = 2 }
        denied("conflict") { provider.callNativeScoped("send_encrypted_message", webAcknowledgement("web-rotate-ack"),
            BulkFixture.scope, "remote_web_tunnel", id) }
        check(!BulkFixture.copyState().getJSONObject("receipts").getBoolean("${BulkFixture.providerScope}:message:$id"))
    }
    test("missing current host capability rejects before sending while ordinary plugin scope has no atomic admission") {
        val ctx = context(); val provider = AriesConnectionProvider(ctx)
        denied("unsupported_method") { provider.callNativeScoped("send_encrypted_message", webRequest("web-disabled-gate"),
            BulkFixture.scope, "remote_web_tunnel") }
        check(BulkFixture.postedCount() == 0)
    }
    test("native diagnostic preserves a swallowed node HTTP failure and root transport cause without server text or identifiers") {
        val ctx = context(); val provider = AriesConnectionProvider(ctx)
        val observed = mutableListOf<AriesConnectionProvider.Diagnostic>()
        provider.setDiagnosticObserver(observed::add)
        BulkFixture.nodeFailure = AriesClient.AriesException(503, "untrusted_code_with_secret",
            java.net.SocketTimeoutException("untrusted_transport_message"))
        val state = provider.call("get_connection_state")
        check(!state.getBoolean("server_connected") && BulkFixture.binding().has("content_key"))
        val record = observed.single { it.stage == "refresh.node" }
        check(record.cause == "aries_http" && record.rootCause == "socket_timeout" && record.httpStatus == 503 && record.serverCode == "other")
        check(!record.connected && record.peerPresence == "unknown" && record.terminalStatus == null)
        val json = record.toJson()
        check(json.keys().asSequence().toSet() == setOf("stage", "cause", "rootCause", "httpStatus", "serverCode", "connected",
            "member", "peerPresence", "terminalStatus", "selectorChanged"))
        check(listOf("untrusted", BulkFixture.localId, BulkFixture.peerId, "aries.test", "grp_test").none { it in json.toString() })
    }
    test("native diagnostic identifies failed membership refresh without treating a network outage as durable revocation") {
        val ctx = context(); val provider = AriesConnectionProvider(ctx)
        val observed = mutableListOf<AriesConnectionProvider.Diagnostic>()
        provider.setDiagnosticObserver(observed::add)
        BulkFixture.membersFailure = AriesClient.AriesException(503, "service_unavailable", java.net.ConnectException("untrusted_endpoint"))
        val state = provider.call("get_connection_state")
        val record = observed.single { it.stage == "refresh.members" }
        check(record.cause == "aries_http" && record.rootCause == "connect_failure" && record.httpStatus == 503 && record.serverCode == "service_unavailable")
        check(!state.getBoolean("server_connected") && !BulkFixture.binding().has("terminal_receipt") && BulkFixture.binding().has("content_key"))
    }
    test("native diagnostic distinguishes unknown presence after lookup failure from an authoritative offline peer") {
        val ctx = context(); val provider = AriesConnectionProvider(ctx)
        val observed = mutableListOf<AriesConnectionProvider.Diagnostic>()
        provider.setDiagnosticObserver(observed::add)
        BulkFixture.presenceFailure = java.net.UnknownHostException("untrusted_hostname")
        val unknown = provider.call("get_connection_state")
        val failed = observed.last { it.stage == "refresh.presence" }
        check(failed.cause == "unknown_host" && failed.rootCause == "unknown_host" && failed.connected && failed.member && failed.peerPresence == "unknown")
        check(unknown.getBoolean("server_connected") && unknown.getJSONObject("remote_device").getString("online") == "unknown")
        BulkFixture.presenceFailure = null; BulkFixture.peerOnline = false
        val offline = provider.call("get_connection_state")
        val actual = observed.last { it.stage == "refresh.presence" }
        check(actual.cause == null && actual.rootCause == null && actual.peerPresence == "offline" && actual.terminalStatus == null)
        check(offline.getBoolean("server_connected") && offline.getString("pairing_status") == "paired" &&
            offline.getJSONObject("remote_device").getString("online") == "offline")
    }
    test("native diagnostic observer exceptions and removal cannot alter production state or ciphertext") {
        val ctx = context(); val provider = AriesConnectionProvider(ctx)
        var observations = 0
        provider.setDiagnosticObserver { observations++; error("untrusted_observer_failure") }
        val state = provider.call("get_connection_state")
        check(observations >= 3 && state.getString("pairing_status") == "paired" && state.getBoolean("server_connected"))
        val before = observations
        provider.setDiagnosticObserver(null)
        provider.call("get_connection_state")
        check(observations == before && BulkFixture.postedCount() == 0 && BulkFixture.binding().has("content_key"))
    }
    if (!skipLongTransfer) test("1100 real encrypted chunk requests/ACK sends and 4400 received ACK frames stay bounded beyond former limits") {
        val ctx = context(); val provider = AriesConnectionProvider(ctx)
        BulkFixture.eventCount = 4
        val bytes = "A".repeat(64 * 1024)
        val intents = BulkFixture.state.getJSONObject("mutations")
        for (name in listOf("session.prompt", "settings.update", "attachment.prepare", "attachment.commit"))
            intents.put("${BulkFixture.providerScope}:intent:$name", JSONObject().put("sentinel", name))
        var cursor = "0"
        for (index in 0 until 1100) {
            val id = "long-transfer-$index"
            provider.callNativeBulk(request(id, "attachment.write", bytes), BulkFixture.scope)
            cursor = acknowledge(provider, cursor, id)
            check(provider.retireTransferReceipt(BulkFixture.scope, id, "attachment.write").getBoolean("retired"))
            check(BulkFixture.copyState().getJSONObject("mutations").length() == 4)
            check(BulkFixture.copyState().getJSONObject("receipts").length() == 0)
            if (index % 200 == 199) println("EVIDENCE chunks=${index + 1} ackFrames=${(index + 1) * 4} peakVaultBytes=${BulkFixture.maxBytes}")
        }
        val state = BulkFixture.copyState()
        check(BulkFixture.postedCount() == 2200 && BulkFixture.maxBytes < 512 * 1024)
        check(state.getJSONObject("bindings").getJSONObject(BulkFixture.providerScope).getJSONArray("bulk_ack_ranges").length() == 1)
        for (name in listOf("session.prompt", "settings.update", "attachment.prepare", "attachment.commit"))
            check(state.getJSONObject("mutations").getJSONObject("${BulkFixture.providerScope}:intent:$name").getString("sentinel") == name)
        println("EVIDENCE totalRequestBytes=${1100L * bytes.length} confirmedSends=2200 acknowledgedFrames=4400 peakVaultBytes=${BulkFixture.maxBytes} retainedIntents=4")
    }
    println("RESULT $passed/$passed PASS productionProvider=true realCrypto=true transport=synthetic android=false publicAries=false paidRequests=0")
}
