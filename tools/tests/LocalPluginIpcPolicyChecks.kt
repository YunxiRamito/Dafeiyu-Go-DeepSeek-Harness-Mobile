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
    test("concurrent peer admission never exceeds four live permits") {
        val pool = Executors.newFixedThreadPool(16)
        val start = CountDownLatch(1)
        val tried = CountDownLatch(16)
        val release = CountDownLatch(1)
        try {
            val attempts = (0 until 16).map {
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
    test("closing the session invalidates credentials and peers") {
        session.close()
        check(!session.acceptsPeer(10123, 2345))
        check(rejected { session.validate(request()) }.code == "permission_denied")
    }
    println("Local plugin IPC policy checks: $checks PASS (offline, no Android socket/runtime)")
}
