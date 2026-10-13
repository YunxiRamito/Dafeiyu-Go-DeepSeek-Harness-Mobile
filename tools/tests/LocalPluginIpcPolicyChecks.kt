package com.dafeiyu.mobile.data

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

fun main() {
    var checks = 0
    fun test(name: String, action: () -> Unit) {
        action()
        checks++
        println("PASS $name")
    }
    fun rejected(action: () -> Unit): LocalPluginIpcPolicy.Failure {
        val failure = runCatching(action).exceptionOrNull()
        check(failure is LocalPluginIpcPolicy.Failure) { "expected IPC policy failure, got $failure" }
        return failure
    }
    fun invalid(action: () -> Unit) {
        check(runCatching(action).exceptionOrNull() is IllegalArgumentException)
    }
    fun capability(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })

    test("parses a Linux runtime identity with one app UID and direct parent") {
        val identity = LocalPluginIpcPolicy.runtimeIdentity(
            """
            Name:	node
            State:	S (sleeping)
            Pid:	2345
            PPid:	1234
            Uid:	10123	10123	10123	10123
            """.trimIndent(),
        )
        check(identity == LocalPluginIpcPolicy.RuntimeIdentity(2345, 1234, 10123))
    }
    test("rejects missing or inconsistent process identity fields") {
        invalid { LocalPluginIpcPolicy.runtimeIdentity("Pid:\t1\nPPid:\t2\nUid:\t3\t4\t3\t3\n") }
        invalid { LocalPluginIpcPolicy.runtimeIdentity("Pid:\t0\nPPid:\t2\nUid:\t3\t3\t3\t3\n") }
        invalid { LocalPluginIpcPolicy.runtimeIdentity("Pid:\t1\nPPid:\t2\n") }
        invalid { LocalPluginIpcPolicy.runtimeIdentity("Pid:\t1\nPid:\t4\nPPid:\t2\nUid:\t3\t3\t3\t3\n") }
    }

    val session = LocalPluginIpcPolicy.Session(10123, 1234, "01234567-89ab-cdef-0123-456789abcdef", capability())
    test("does not admit any peer before the actual runtime is bound") {
        check(!session.acceptsPeer(10123, 2345))
        check(session.acquirePeer(10123, 2345) == null)
    }
    test("binds only a positive direct child with the app UID") {
        invalid { session.bindRuntime(LocalPluginIpcPolicy.RuntimeIdentity(2345, 9999, 10123)) }
        invalid { session.bindRuntime(LocalPluginIpcPolicy.RuntimeIdentity(2345, 1234, 10124)) }
        invalid { session.bindRuntime(LocalPluginIpcPolicy.RuntimeIdentity(1234, 1234, 10123)) }
        session.bindRuntime(LocalPluginIpcPolicy.RuntimeIdentity(2345, 1234, 10123))
        check(session.acceptsPeer(10123, 2345))
        check(!session.acceptsPeer(10124, 2345))
        check(!session.acceptsPeer(10123, 2346))
    }
    test("keeps the runtime binding single-use") {
        invalid { session.bindRuntime(LocalPluginIpcPolicy.RuntimeIdentity(2346, 1234, 10123)) }
    }
    test("PRoot binding admits exactly the verified launcher child with the app UID") {
        val traced = LocalPluginIpcPolicy.Session(10123, 1234, "01234567-89ab-cdef-0123-456789abcdef", capability())
        val node = LocalPluginIpcPolicy.RuntimeIdentity(3456, 2345, 10123)
        val launcher = LocalPluginIpcPolicy.RuntimeIdentity(2345, 1234, 10123)
        invalid { traced.bindRuntime(node, launcher.copy(parentPid = 9999)) }
        invalid { traced.bindRuntime(node, launcher.copy(uid = 10124)) }
        invalid { traced.bindRuntime(node.copy(parentPid = 7777), launcher) }
        invalid { traced.bindRuntime(node.copy(uid = 10124), launcher) }
        traced.bindRuntime(node, launcher)
        check(traced.acceptsPeer(10123, 3456))
        check(!traced.acceptsPeer(10123, 2345) && !traced.acceptsPeer(10124, 3456))
        traced.close()
    }

    fun request(nonce: String = "a".repeat(24)): JSONObject =
        JSONObject()
            .put("protocol", LocalPluginIpcPolicy.PROTOCOL)
            .put("request_id", "req_001")
            .put("method", "get_connection_state")
            .put("params", JSONObject())
            .put("generation", "01234567-89ab-cdef-0123-456789abcdef")
            .put("capability", capability())
            .put("nonce", nonce)

    test("accepts the shared Android envelope with object params") {
        val accepted = session.validate(request())
        check(accepted.id == "req_001" && accepted.method == "get_connection_state")
        check(accepted.params.length() == 0)
    }
    test("preserves a validated remote scope for serialized native admission") {
        val scope = JSONObject().put("node_id", "node_1").put("group_id", "group_1")
            .put("local_device_id", "phone_1").put("remote_device_id", "pc_1")
        val result = session.validate(request("s".repeat(24)).put("remote_scope", scope))
        scope.put("group_id", "changed")
        check(result.remoteScope?.getString("group_id") == "group_1")
        for (value in listOf(JSONObject.NULL, JSONArray(), "bad", JSONObject(scope.toString()).put("extra", true),
                JSONObject(scope.toString()).put("node_id", 1), JSONObject(scope.toString()).put("remote_device_id", "phone_1"))) {
            check(rejected { session.validate(request().put("remote_scope", value)) }.code == "protocol_error")
        }
    }
    test("rejects non-object params instead of coercing to an empty object") {
        for (value in listOf(JSONArray(), "wrong", 1, JSONObject.NULL)) {
            rejected { session.validate(request().put("params", value)) }
        }
        rejected { session.validate(request().apply { remove("params") }) }
    }
    test("rejects a long request id without truncating it") {
        val failure = rejected { session.validate(request().put("request_id", "x".repeat(129))) }
        check(failure.code == "protocol_error")
    }
    test("rejects wrong request id types and control characters") {
        rejected { session.validate(request().put("request_id", 7)) }
        rejected { session.validate(request().put("request_id", "req\nbad")) }
    }
    test("rejects generation and capability mismatches") {
        check(rejected { session.validate(request().put("generation", "01234567-89ab-cdef-0123-456789abcdee")) }.code == "permission_denied")
        check(rejected { session.validate(request().put("capability", "A".repeat(43))) }.code == "permission_denied")
    }
    test("rejects malformed and reused nonces") {
        check(rejected { session.validate(request("short")) }.code == "permission_denied")
        session.validate(request("b".repeat(24)))
        check(rejected { session.validate(request("b".repeat(24))) }.code == "permission_denied")
    }
    test("rejects invalid method types without reaching the provider") {
        rejected { session.validate(request().put("method", 1)) }
        rejected { session.validate(request().put("method", "bad\nmethod")) }
    }
    test("accepts exact id length and echoes its original bytes") {
        val id = "i".repeat(128)
        check(session.validate(request("c".repeat(24)).put("request_id", id)).id == id)
    }
    test("authentication fields are strings without JSON coercion") {
        for (name in listOf("capability", "generation", "nonce")) {
            for (value in listOf(7, JSONObject.NULL, JSONObject(), JSONArray(), true)) {
                check(rejected { session.validate(request().put(name, value)) }.code == "permission_denied")
            }
        }
        check(rejected { session.validate(request().put("protocol", 7)) }.code == "protocol_error")
    }
    test("concurrent replay permits exactly one nonce acceptance") {
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val attempts = (0 until 8).map {
                pool.submit<Boolean> {
                    start.await()
                    runCatching { session.validate(request("d".repeat(24))) }.isSuccess
                }
            }
            start.countDown()
            check(attempts.count { it.get(10, TimeUnit.SECONDS) } == 1)
        } finally {
            pool.shutdownNow()
        }
    }

    test("request limit includes the terminating LF and accepts the exact boundary") {
        val content = "{}" + " ".repeat(LocalPluginIpcPolicy.MAX_REQUEST_BYTES - 3)
        check(content.toByteArray(StandardCharsets.UTF_8).size == LocalPluginIpcPolicy.MAX_REQUEST_BYTES - 1)
        check(LocalPluginIpcPolicy.readRequest(ByteArrayInputStream((content + "\n").toByteArray()))?.length() == 0)
    }
    test("rejects one byte over the request limit") {
        val content = "{}" + " ".repeat(LocalPluginIpcPolicy.MAX_REQUEST_BYTES - 2)
        check(rejected { LocalPluginIpcPolicy.readRequest(ByteArrayInputStream((content + "\n").toByteArray())) }.code == "protocol_error")
    }
    test("rejects invalid UTF-8 and trailing JSON values") {
        check(rejected {
            LocalPluginIpcPolicy.readRequest(ByteArrayInputStream(byteArrayOf(0x7b, 0xC3.toByte(), 0x28, 0x7d, 0x0a)))
        }.code == "protocol_error")
        check(rejected {
            LocalPluginIpcPolicy.readRequest(ByteArrayInputStream("{} {}".toByteArray() + byteArrayOf(0x0a)))
        }.code == "protocol_error")
    }
    test("rejects incomplete frames and top level non-objects") {
        for (text in listOf("{}","[]\n", "null\n", "7\n", "\"text\"\n", "\n")) {
            check(rejected { LocalPluginIpcPolicy.readRequest(ByteArrayInputStream(text.toByteArray())) }.code == "protocol_error")
        }
        check(LocalPluginIpcPolicy.readRequest(ByteArrayInputStream(byteArrayOf())) == null)
    }
    test("response limit counts UTF-8 bytes and final LF") {
        val response = JSONObject().put("success", true).put("request_id", "req").put("data", "")
        val overhead = (response.toString() + "\n").toByteArray().size
        response.put("data", "x".repeat(LocalPluginIpcPolicy.MAX_RESPONSE_BYTES - overhead))
        check(LocalPluginIpcPolicy.responseBytes(response, "req").size == LocalPluginIpcPolicy.MAX_RESPONSE_BYTES)
        response.put("data", response.getString("data") + "x")
        check(JSONObject(String(LocalPluginIpcPolicy.responseBytes(response, "req"))).getString("error_code") == "protocol_error")
        response.put("data", "\u4e2d".repeat(LocalPluginIpcPolicy.MAX_RESPONSE_BYTES / 2))
        check(JSONObject(String(LocalPluginIpcPolicy.responseBytes(response, "req"))).getString("error_code") == "protocol_error")
    }
    test("response overflow fails closed to a bounded protocol error") {
        val oversized = JSONObject().put("success", true).put("request_id", "req").put("data", "x".repeat(LocalPluginIpcPolicy.MAX_RESPONSE_BYTES))
        val response = LocalPluginIpcPolicy.responseBytes(oversized, "req")
        check(response.size <= LocalPluginIpcPolicy.MAX_RESPONSE_BYTES)
        check(String(response, StandardCharsets.UTF_8).contains("\"error_code\":\"protocol_error\""))
    }
    test("client permits are bounded and released exactly once") {
        val permits = (0 until LocalPluginIpcPolicy.MAX_CLIENTS).map {
            session.acquirePeer(10123, 2345) ?: error("missing permit $it")
        }
        check(session.acquirePeer(10123, 2345) == null)
        permits[0].close()
        permits[0].close()
        val replacement = session.acquirePeer(10123, 2345) ?: error("missing replacement")
        check(session.acquirePeer(10123, 2345) == null)
        replacement.close()
        permits.drop(1).forEach { it.close() }
    }
    test("concurrent peer admission reserves sixteen bounded slots for data and control") {
        val pool = Executors.newFixedThreadPool(32)
        val start = CountDownLatch(1)
        val tried = CountDownLatch(32)
        val release = CountDownLatch(1)
        try {
            val attempts = (0 until 32).map {
                pool.submit<Boolean> {
                    start.await()
                    val permit = session.acquirePeer(10123, 2345)
                    tried.countDown()
                    release.await()
                    permit?.close()
                    permit != null
                }
            }
            start.countDown()
            check(tried.await(10, TimeUnit.SECONDS))
            check(session.acquirePeer(10123, 2345) == null)
            release.countDown()
            check(attempts.count { it.get(10, TimeUnit.SECONDS) } == LocalPluginIpcPolicy.MAX_CLIENTS)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }
    test("a full nonce ledger rejects fresh nonces without evicting old ones") {
        val bounded = LocalPluginIpcPolicy.Session(10123, 1234, "01234567-89ab-cdef-0123-456789abcdef", capability())
        bounded.bindRuntime(LocalPluginIpcPolicy.RuntimeIdentity(2345, 1234, 10123))
        repeat(LocalPluginIpcPolicy.MAX_NONCES) { bounded.validate(request(it.toString().padStart(24, '0'))) }
        check(rejected { bounded.validate(request("z".repeat(24))) }.code == "permission_denied")
        check(rejected { bounded.validate(request("0".repeat(24))) }.code == "permission_denied")
        bounded.close()
    }
    test("tracer identity has one exact nonnegative kernel field") {
        check(LocalPluginIpcPolicy.runtimeTracerPid("Pid: 2345\nTracerPid:\t3456\n") == 3456)
        check(LocalPluginIpcPolicy.runtimeTracerPid("TracerPid: 0\n") == 0)
        for (value in listOf("", "TracerPid: -1\n", "TracerPid: 3456\nTracerPid: 3456\n", "TracerPid: 12 34\n"))
            check(runCatching { LocalPluginIpcPolicy.runtimeTracerPid(value) }.isFailure)
    }
    val expectedNode = "/data/user/0/example/files/linux-runtime/slots/frozen/usr/local/bin/node"
    val nodeMaps = "00400000-00741000 r--p 00000000 fe:06 377477 $expectedNode\n" +
        "00741000-0300b000 r-xp 00341000 fe:06 377477 $expectedNode\n" +
        "600000000000-600000001000 r-xp 00004000 fe:06 101190 /data/app/frozen/lib/x86_64/liblinux_prootloader.so\n"
    test("PRoot node evidence binds exact immutable kernel mappings") {
        val evidence = LocalPluginIpcPolicy.mappedExecutableEvidence(nodeMaps, expectedNode, 65030L, 377477L)
        check(evidence.size == 2 && evidence.last().contains("r-xp"))
        check(evidence == LocalPluginIpcPolicy.mappedExecutableEvidence(nodeMaps, expectedNode, 65030L, 377477L))
    }
    test("mapped Node rejects another device or inode") {
        check(runCatching { LocalPluginIpcPolicy.mappedExecutableEvidence(nodeMaps, expectedNode, 65031L, 377477L) }.isFailure)
        check(runCatching { LocalPluginIpcPolicy.mappedExecutableEvidence(nodeMaps, expectedNode, 65030L, 377478L) }.isFailure)
    }
    test("loader mapping alone cannot impersonate Node") {
        check(runCatching { LocalPluginIpcPolicy.mappedExecutableEvidence(nodeMaps.substringAfterLast("377477 $expectedNode\n"), expectedNode, 65030L, 377477L) }.isFailure)
    }
    test("data-only or writable executable mappings cannot bind a Node runtime") {
        for (permission in listOf("r--p", "rwxp", "r-xs"))
            check(runCatching { LocalPluginIpcPolicy.mappedExecutableEvidence(nodeMaps.replace("r-xp 00341000", "$permission 00341000"), expectedNode, 65030L, 377477L) }.isFailure)
    }
    test("changed path or deleted executable is rejected") {
        for (suffix in listOf("-other", " (deleted)", "/injected"))
            check(runCatching { LocalPluginIpcPolicy.mappedExecutableEvidence(nodeMaps.replace(expectedNode, expectedNode + suffix), expectedNode, 65030L, 377477L) }.isFailure)
    }
    test("malformed executable mapping rejects before binding") {
        check(runCatching { LocalPluginIpcPolicy.mappedExecutableEvidence(nodeMaps.replace("fe:06", "invalid"), expectedNode, 65030L, 377477L) }.isFailure)
        check(runCatching { LocalPluginIpcPolicy.mappedExecutableEvidence(nodeMaps.replace("00400000-00741000", "bogus"), expectedNode, 65030L, 377477L) }.isFailure)
    }
    test("closing the session invalidates credentials and peers") {
        session.close()
        check(!session.acceptsPeer(10123, 2345))
        check(rejected { session.validate(request()) }.code == "permission_denied")
    }
    fun bulkParams(operation: String = "artifact.read"): JSONObject = JSONObject().put("kind", "dsh_remote_request")
        .put("group_id", "group-a").put("idempotency_key", "dsh-remote-android-r1")
        .put("body", JSONObject().put("protocol", "dafeiyu-go.remote-dsh.v1").put("request_id", "android-r1")
            .put("target_device_id", "computer").put("operation", operation)
            .put("payload", JSONObject().put("ticketId", "ticket-a").put("offset", 0).put("size", 48 * 1024)))
    fun bulkScope(): JSONObject = JSONObject().put("node_id", "node-a").put("group_id", "group-a")
        .put("local_device_id", "phone").put("remote_device_id", "computer")
    test("a claimed IPC scope alone cannot enter the native bulk path") {
        LocalPluginIpcPolicy.NativeAdmissions().use { admissions ->
            check(admissions.consume("send_encrypted_message", bulkParams(), bulkScope()) == null)
        }
    }
    test("native admission freezes every business field and exact typed scope") {
        LocalPluginIpcPolicy.NativeAdmissions().use { admissions ->
            val params = bulkParams()
            val scope = bulkScope()
            val frozen = JSONObject(params.toString())
            val token = admissions.register("send_encrypted_message", params, scope) ?: error("missing ticket")
            params.getJSONObject("body").getJSONObject("payload").put("offset", 48 * 1024)
            check(admissions.consume("send_encrypted_message", params, bulkScope()) == null)
            scope.put("remote_device_id", "other-computer")
            check(admissions.consume("send_encrypted_message", frozen, scope) == null)
            check(admissions.consume("acknowledge_message", frozen, bulkScope()) == null)
            check(admissions.consume("send_encrypted_message", frozen, bulkScope())?.getString("remote_device_id") == "computer")
            check(admissions.consume("send_encrypted_message", frozen, bulkScope()) == null)
            admissions.revoke(token)
        }
    }
    test("native fingerprint accepts reordered JSON and returns a detached scope once") {
        LocalPluginIpcPolicy.NativeAdmissions().use { admissions ->
            val original = bulkParams()
            admissions.register("send_encrypted_message", original, bulkScope())
            val reordered = JSONObject().put("body", original.getJSONObject("body"))
                .put("idempotency_key", original.getString("idempotency_key")).put("group_id", "group-a").put("kind", "dsh_remote_request")
            val result = admissions.consume("send_encrypted_message", reordered, bulkScope()) ?: error("missing admission")
            result.put("remote_device_id", "changed")
            check(admissions.consume("send_encrypted_message", original, bulkScope()) == null)
        }
    }
    test("task intents and opaque plugin calls never get native bulk admission") {
        LocalPluginIpcPolicy.NativeAdmissions().use { admissions ->
            for (operation in listOf("session.prompt", "attachment.prepare", "attachment.commit", "attachment.cancel", "artifact.prepare", "workspace.files.delete", "web.http")) {
                check(admissions.register("send_encrypted_message", bulkParams(operation), bulkScope()) == null)
            }
            check(admissions.register("get_connection_state", bulkParams(), bulkScope()) == null)
        }
    }
    test("timed out or revoked app requests cannot reuse native admission") {
        var now = 1L
        LocalPluginIpcPolicy.NativeAdmissions { now }.use { admissions ->
            val token = admissions.register("send_encrypted_message", bulkParams(), bulkScope())
            admissions.revoke(token)
            check(admissions.consume("send_encrypted_message", bulkParams(), bulkScope()) == null)
            admissions.register("send_encrypted_message", bulkParams(), bulkScope())
            now += TimeUnit.MILLISECONDS.toNanos(LocalPluginIpcPolicy.DEADLINE_MILLIS)
            check(admissions.consume("send_encrypted_message", bulkParams(), bulkScope()) == null)
        }
    }
    test("admission capacity is bounded and a new bridge cannot inherit old leases") {
        val old = LocalPluginIpcPolicy.NativeAdmissions()
        repeat(LocalPluginIpcPolicy.MAX_CLIENTS) { old.register("send_encrypted_message", bulkParams(), bulkScope()) }
        check(runCatching { old.register("send_encrypted_message", bulkParams(), bulkScope()) }.isFailure)
        old.close()
        check(old.consume("send_encrypted_message", bulkParams(), bulkScope()) == null)
        check(runCatching { old.register("send_encrypted_message", bulkParams(), bulkScope()) }.isFailure)
        LocalPluginIpcPolicy.NativeAdmissions().use { replacement ->
            check(replacement.consume("send_encrypted_message", bulkParams(), bulkScope()) == null)
        }
    }
    fun webAck(): JSONObject = JSONObject().put("group_id", "group-a").put("kind", "dsh_remote_ack")
        .put("idempotency_key", "web-ack-android-r1-0").put("body", JSONObject().put("protocol", "dafeiyu-go.remote-dsh.v1")
            .put("request_id", "android-r1").put("target_device_id", "computer").put("sequence", 0))
    test("scoped sideband is single use and plugin JSON alone never admits a call") {
        LocalPluginIpcPolicy.NativeScopedAdmissions().use { admissions ->
            val params = bulkParams("web.http")
            check(admissions.consume("send_encrypted_message", params, bulkScope()) == null)
            admissions.register("send_encrypted_message", params, bulkScope(), "remote_web_tunnel", null)
            check(admissions.consume("send_encrypted_message", params, bulkScope())?.capability == "remote_web_tunnel")
            check(admissions.consume("send_encrypted_message", params, bulkScope()) == null)
        }
    }
    test("scoped metadata cannot substitute method, body, exact scope or required capability") {
        LocalPluginIpcPolicy.NativeScopedAdmissions().use { admissions ->
            val params = bulkParams("web.http")
            admissions.register("send_encrypted_message", params, bulkScope(), "remote_web_tunnel", null)
            val changed = JSONObject(params.toString()).also { it.getJSONObject("body").getJSONObject("payload").put("offset", 1) }
            check(admissions.consume("send_encrypted_message", changed, bulkScope()) == null)
            check(admissions.consume("acknowledge_message", params, bulkScope()) == null)
            check(admissions.consume("send_encrypted_message", params, bulkScope().put("group_id", "foreign")) == null)
            check(runCatching { admissions.register("send_encrypted_message", params, bulkScope(), "remote_task", null) }.isFailure)
            check(admissions.consume("send_encrypted_message", params, bulkScope())?.scope?.getString("group_id") == "group-a")
        }
    }
    test("web combined ACK sideband fixes one exact local message and cannot be ambiguous") {
        LocalPluginIpcPolicy.NativeScopedAdmissions().use { admissions ->
            admissions.register("send_encrypted_message", webAck(), bulkScope(), "remote_web_tunnel", "msg-exact")
            check(runCatching { admissions.register("send_encrypted_message", webAck(), bulkScope(), "remote_web_tunnel", "msg-foreign") }.isFailure)
            val proof = admissions.consume("send_encrypted_message", webAck(), bulkScope()) ?: error("missing proof")
            check(proof.acknowledgeMessageId == "msg-exact")
            proof.scope.put("group_id", "changed")
            check(admissions.consume("send_encrypted_message", webAck(), bulkScope()) == null)
        }
    }
    test("combined ACK cannot be applied to task, malformed envelope or absent local message") {
        LocalPluginIpcPolicy.NativeScopedAdmissions().use { admissions ->
            check(runCatching { admissions.register("send_encrypted_message", bulkParams("session.prompt"), bulkScope(), "remote_task", "msg") }.isFailure)
            check(runCatching { admissions.register("send_encrypted_message", webAck(), bulkScope(), "remote_web_tunnel", null) }.isFailure)
            val malformed = webAck().also { it.getJSONObject("body").put("sequence", 0.5) }
            check(runCatching { admissions.register("send_encrypted_message", malformed, bulkScope(), "remote_web_tunnel", "msg") }.isFailure)
        }
    }
    test("expired, cancelled and closed scoped admissions cannot cross runtime generations") {
        var now = 1L
        val old = LocalPluginIpcPolicy.NativeScopedAdmissions { now }
        val token = old.register("send_encrypted_message", bulkParams("web.http"), bulkScope(), "remote_web_tunnel", null)
        old.revoke(token)
        check(old.consume("send_encrypted_message", bulkParams("web.http"), bulkScope()) == null)
        old.register("send_encrypted_message", bulkParams("web.http"), bulkScope(), "remote_web_tunnel", null)
        now += TimeUnit.MILLISECONDS.toNanos(LocalPluginIpcPolicy.DEADLINE_MILLIS)
        check(old.consume("send_encrypted_message", bulkParams("web.http"), bulkScope()) == null)
        old.close()
        check(runCatching { old.register("send_encrypted_message", bulkParams("web.http"), bulkScope(), "remote_web_tunnel", null) }.isFailure)
        LocalPluginIpcPolicy.NativeScopedAdmissions().use { replacement ->
            check(replacement.consume("send_encrypted_message", bulkParams("web.http"), bulkScope()) == null)
        }
    }
    println("Local plugin IPC policy checks: $checks PASS (offline, no Android socket/runtime)")
}
