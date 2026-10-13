package com.dafeiyu.mobile.data

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CancellationException
import org.json.JSONArray
import org.json.JSONObject

fun main() {
    var checks = 0
    fun test(name: String, action: () -> Unit) {
        action()
        checks++
        println("PASS $name")
    }
    fun rejected(action: () -> Unit) {
        check(runCatching(action).exceptionOrNull() is IllegalArgumentException)
    }
    val enrollment = "/api/v1/public/devices/enroll"
    val key = "android-offline-key"
    test("unsigned enrollment sends the exact validated key") {
        check(AriesProtocolPolicy.idempotencyHeader("POST", enrollment, false, key) == key)
    }
    test("signed mutation preserves canonical key bytes") {
        check(AriesProtocolPolicy.idempotencyHeader("POST", "/api/v1/public/groups/g/leave", true, key) == key)
    }
    test("reads and challenge without key remain unsigned") {
        check(AriesProtocolPolicy.idempotencyHeader("GET", "/healthz", false, null) == null)
        check(AriesProtocolPolicy.idempotencyHeader("POST", "/api/v1/public/devices/challenge", false, null) == null)
    }
    test("unsigned key rejected on every non-enrollment endpoint") {
        for (path in listOf("/healthz", "/api/v1/public/devices/challenge", "/api/v1/public/invites/c/applications")) {
            rejected { AriesProtocolPolicy.idempotencyHeader("POST", path, false, key) }
        }
    }
    test("unsigned enrollment exception requires exact POST path") {
        for (method in listOf("GET", "PUT", "post")) {
            rejected { AriesProtocolPolicy.idempotencyHeader(method, enrollment, false, key) }
        }
        for (path in listOf("$enrollment/", "$enrollment?x=1", "$enrollment#x", "$enrollment/extra")) {
            rejected { AriesProtocolPolicy.idempotencyHeader("POST", path, false, key) }
        }
    }
    test("invalid keys rejected in unsigned and signed policies") {
        for (signed in listOf(false, true)) {
            for (invalid in listOf("", "short", "a".repeat(129), "android-\r\nInjected: x")) {
                rejected { AriesProtocolPolicy.idempotencyHeader("POST", enrollment, signed, invalid) }
            }
        }
    }
    test("key limits remain inclusive") {
        for (size in listOf(8, 128)) {
            check(AriesProtocolPolicy.idempotencyHeader("POST", enrollment, false, "a".repeat(size)) == "a".repeat(size))
        }
    }
    fun parse(text: String) = AriesProtocolPolicy.parsePairingInput(text)
    test("actual PC QR JSON resolves invite_code server and node") {
        val parsed = parse("""{"protocol":"dafeiyu-aries-pair","version":1,"server":"https://pc.test:10240","node_id":"node_1","invite_code":"pc-code","expires_at":"2026-10-11T00:00:00Z","creator_public_key":"fixture"}""")
        check(parsed.endpoint == "https://pc.test:10240" && parsed.code == "pc-code" && parsed.nodeId == "node_1")
    }
    test("legacy QR JSON remains supported") {
        val parsed = parse("""{"endpoint":"https://pc.test","code":"legacy","group":"Group"}""")
        check(parsed.code == "legacy" && parsed.group == "Group")
    }
    test("equal JSON code aliases accepted") {
        check(parse("""{"server":"https://pc.test","code":"same","invite_code":"same"}""").code == "same")
    }
    test("conflicting JSON code aliases rejected") {
        rejected { parse("""{"server":"https://pc.test","code":"first","invite_code":"second"}""") }
    }
    test("invalid JSON code type rejected rather than coerced") {
        rejected { parse("""{"server":"https://pc.test","invite_code":123}""") }
    }
    test("JSON controls rejected") {
        rejected { parse("""{"server":"https://pc.test","invite_code":"bad\ncode"}""") }
    }
    test("equivalent endpoint aliases normalized") {
        check(parse("""{"server":"https://pc.test/","endpoint":"https://pc.test","invite_code":"a"}""").endpoint == "https://pc.test")
    }
    test("conflicting endpoint aliases rejected") {
        rejected { parse("""{"server":"https://other.test","endpoint":"https://pc.test","invite_code":"a"}""") }
    }
    test("insecure and path-bearing JSON endpoints rejected") {
        for (endpoint in listOf("http://pc.test", "https://pc.test/path", "https://user@pc.test", "https://pc.test?x=1")) {
            rejected { parse("""{"server":"$endpoint","invite_code":"a"}""") }
        }
    }
    test("HTTPS query invite_code decoded") {
        val parsed = parse("https://pc.test:10240/?invite_code=A%2BB%2FC%3D&node_id=node%5F1")
        check(parsed.code == "A+B/C=" && parsed.nodeId == "node_1" && parsed.endpoint == "https://pc.test:10240")
    }
    test("aries scheme and explicit port supported") {
        check(parse("aries://pc.test?invite_code=a").endpoint == "https://pc.test:10240")
        check(parse("aries://pc.test:10443?code=a").endpoint == "https://pc.test:10443")
    }
    test("equal query aliases and repeats accepted") {
        check(parse("https://pc.test?code=a&invite_code=a&code=a").code == "a")
    }
    test("conflicting query aliases rejected") {
        rejected { parse("https://pc.test?code=a&invite_code=b") }
    }
    test("conflicting repeated query values rejected") {
        rejected { parse("https://pc.test?invite_code=a&invite_code=b") }
        rejected { parse("https://pc.test?node_id=n1&node_id=n2&code=a") }
    }
    test("encoded query key cannot bypass conflict check") {
        rejected { parse("https://pc.test?code=a&invite%5Fcode=b") }
    }
    test("malformed percent encoding and decoded controls rejected") {
        rejected { parse("https://pc.test?invite_code=%ZZ") }
        rejected { parse("https://pc.test?invite_code=bad%0Acode") }
    }
    test("query fragment and user info rejected") {
        rejected { parse("https://pc.test?invite_code=a#invite_code=b") }
        rejected { parse("aries://user@pc.test?code=a") }
    }
    test("legacy query group retained") {
        check(parse("https://pc.test?code=a&group=Example%20Group").group == "Example Group")
    }
    test("missing code stays absent for caller validation") {
        check(parse("""{"server":"https://pc.test","invite_code":null}""").code == null)
    }
    test("malformed JSON and excessive input rejected") {
        check(runCatching { parse("{invalid") }.isFailure)
        rejected { parse("a".repeat(4097)) }
    }
    test("response reader accepts exact byte limit") {
        val bytes = "abcd".toByteArray()
        check(AriesProtocolPolicy.readResponse(ByteArrayInputStream(bytes), bytes.size) == "abcd")
    }
    test("response reader detects oversize before reading entire stream") {
        var consumed = 0
        val input = object : InputStream() {
            override fun read(): Int { consumed++; return 'a'.code }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                buffer.fill('a'.code.toByte(), offset, offset + length)
                consumed += length
                return length
            }
        }
        check(runCatching { AriesProtocolPolicy.readResponse(input, 32) }.exceptionOrNull() is AriesProtocolPolicy.ResponseTooLarge)
        check(consumed == 33)
    }
    test("response byte limit handles multibyte UTF8") {
        val text = "\u4e2d\u6587"
        val bytes = text.toByteArray(Charsets.UTF_8)
        check(AriesProtocolPolicy.readResponse(ByteArrayInputStream(bytes), bytes.size) == text)
        check(runCatching {
            AriesProtocolPolicy.readResponse(ByteArrayInputStream(bytes), bytes.size - 1)
        }.exceptionOrNull() is AriesProtocolPolicy.ResponseTooLarge)
    }
    test("response cancellation before read consumes nothing") {
        val input = ByteArrayInputStream("data".toByteArray())
        check(runCatching {
            AriesProtocolPolicy.readResponse(input, 10) { throw CancellationException("cancelled") }
        }.exceptionOrNull() is CancellationException)
        check(input.available() == 4)
    }
    test("response cancellation during read propagates") {
        var cancelled = false
        val input = object : ByteArrayInputStream("data".toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length)
                cancelled = true
                return count
            }
        }
        check(runCatching {
            AriesProtocolPolicy.readResponse(input, 10) { if (cancelled) throw CancellationException("cancelled") }
        }.exceptionOrNull() is CancellationException)
    }
    fun control(sequence: Long, epoch: Long, completed: Any? = null) = JSONObject()
        .put("sequence", sequence).put("type", "control").put("event", JSONObject()
            .put("sequence", sequence).put("required_epoch", epoch).also {
                if (completed != null) it.put("completed_at", completed)
            })
    fun page(cursor: Any, vararg values: JSONObject) = JSONObject().put("items", JSONArray(values.toList()))
        .put("next_cursor", cursor)
    test("empty event page preserves the persistent decimal cursor and terminates") {
        for (cursor in listOf("0", "42", "18446744073709551615")) {
            val result = AriesProtocolPolicy.controlEventPage(page(cursor), cursor, 1uL)
            check(result.finished && result.nextCursor == cursor && !result.rotationRequired)
        }
    }
    test("control rotation is read from the event envelope and prevents stale key publishing") {
        val result = AriesProtocolPolicy.controlEventPage(page("5", control(5, 2)), "0", 1uL)
        check(result.rotationRequired && !result.finished && result.nextCursor == "5")
        check(!AriesProtocolPolicy.controlEventPage(page("5", control(5, 2, "2026-10-13T00:00:00Z")), "0", 1uL).rotationRequired)
        check(!AriesProtocolPolicy.controlEventPage(page("5", control(5, 1)), "0", 1uL).rotationRequired)
    }
    test("mixed message and control pages maintain the exact last processed sequence") {
        val message = JSONObject().put("sequence", 6).put("type", "message").put("message", JSONObject().put("sequence", 6))
        val result = AriesProtocolPolicy.controlEventPage(page("9", message, control(9, 3, JSONObject.NULL)), "5", 2uL)
        check(result.rotationRequired && result.nextCursor == "9" && !result.finished)
        check(AriesProtocolPolicy.controlEventPage(page("9"), result.nextCursor, 2uL).finished)
    }
    test("event cursor rejects regressions, unexplained jumps and opaque application cursors") {
        for (cursor in listOf<Any>("4", "6", "", "null", "-1", "1.0", "opaque", "18446744073709551616", 5)) {
            rejected { AriesProtocolPolicy.controlEventPage(page(cursor), "5", 1uL) }
        }
        rejected { AriesProtocolPolicy.controlEventPage(page("8", control(7, 1)), "5", 1uL) }
        rejected { AriesProtocolPolicy.controlEventPage(JSONObject().put("items", JSONArray()), "0", 1uL) }
    }
    test("malformed control records and duplicate sequences cannot bypass rotation checks") {
        rejected { AriesProtocolPolicy.controlEventPage(page("7", control(7, 1), control(7, 1)), "0", 1uL) }
        rejected { AriesProtocolPolicy.controlEventPage(page("7", control(8, 1), control(7, 1)), "0", 1uL) }
        for (mutation in listOf<(JSONObject) -> Unit>(
            { it.remove("event") },
            { it.getJSONObject("event").put("sequence", 6) },
            { it.getJSONObject("event").remove("required_epoch") },
            { it.getJSONObject("event").put("required_epoch", "2") },
            { it.getJSONObject("event").put("completed_at", false) },
            { it.put("sequence", 7.5) },
            { it.put("type", "unknown") },
        )) rejected { AriesProtocolPolicy.controlEventPage(page("7", control(7, 2).also(mutation)), "0", 1uL) }
        val excessive = page("0").put("items", JSONArray((1..201).map { control(it.toLong(), 1) }))
        rejected { AriesProtocolPolicy.controlEventPage(excessive, "0", 1uL) }
    }
    println("Aries protocol checks: $checks PASS (offline, production policy/parser)")
}
