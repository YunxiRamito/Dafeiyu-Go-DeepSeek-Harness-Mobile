package com.dafeiyu.mobile.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import org.json.JSONObject

/** Offline protocol rules shared by the transport and pairing input parser. */
internal object AriesProtocolPolicy {
    data class PairingInput(
        val endpoint: String,
        val code: String?,
        val group: String?,
        val nodeId: String?,
    )

    class ResponseTooLarge : IOException("Aries response exceeds the byte limit")

    data class ControlEventPage(val nextCursor: String, val finished: Boolean, val rotationRequired: Boolean)

    /** /events returns a persistent sequence, including the unchanged sequence on an empty page. */
    fun controlEventPage(response: JSONObject, after: String, currentEpoch: ULong): ControlEventPage {
        fun decimal(value: Any?, string: Boolean): ULong {
            require(if (string) value is String else value is Number) { "Aries event sequence type is invalid" }
            val text = value.toString()
            require(text.isNotEmpty() && text.all { it in '0'..'9' }) { "Aries event sequence is invalid" }
            return requireNotNull(text.toULongOrNull()) { "Aries event sequence exceeds uint64" }
        }
        val previous = decimal(after, true)
        val nextText = response.opt("next_cursor")
        val next = decimal(nextText, true)
        val items = requireNotNull(response.optJSONArray("items")) { "Aries event items are missing" }
        require(items.length() <= 200) { "Aries event page exceeds the requested limit" }
        var sequence = previous
        var rotation = false
        for (index in 0 until items.length()) {
            val item = requireNotNull(items.optJSONObject(index)) { "Aries event item is invalid" }
            val itemSequence = decimal(item.opt("sequence"), false)
            require(itemSequence > sequence) { "Aries events are not strictly ordered" }
            sequence = itemSequence
            when (item.opt("type")) {
                "control" -> {
                    val event = requireNotNull(item.optJSONObject("event")) { "Aries control event is missing" }
                    require(decimal(event.opt("sequence"), false) == sequence) { "Aries control sequence does not match" }
                    val requiredEpoch = decimal(event.opt("required_epoch"), false)
                    val completed = event.opt("completed_at")
                    require(completed == null || completed == JSONObject.NULL || completed is String) {
                        "Aries control completion is invalid"
                    }
                    rotation = rotation || (completed == null || completed == JSONObject.NULL || completed == "") &&
                        requiredEpoch > currentEpoch
                }
                "message" -> require(item.optJSONObject("message") != null) { "Aries event message is missing" }
                else -> throw IllegalArgumentException("Aries event type is unsupported")
            }
        }
        require(next == sequence) { "Aries event cursor does not match the processed sequence" }
        return ControlEventPage(nextText as String, items.length() == 0, rotation)
    }

    fun requireIdempotencyKey(value: String): String {
        require(value.length in 8..128 && value.none(Char::isISOControl)) { "Idempotency-Key 无效" }
        return value
    }

    fun idempotencyHeader(method: String, path: String, signed: Boolean, key: String?): String? {
        if (key == null) return null
        val validated = requireIdempotencyKey(key)
        require(signed || (method == "POST" && path == "/api/v1/public/devices/enroll")) {
            "Idempotency-Key requires a signed request or device enrollment"
        }
        return validated
    }

    fun parsePairingInput(raw: String): PairingInput {
        val text = raw.trim()
        require(text.length in 1..4096) { "配对二维码内容无效" }
        if (text.startsWith("{")) {
            val json = JSONObject(text)
            val endpoint = normalizedEndpoint(jsonString(json, "endpoint"), jsonString(json, "server"))
            return PairingInput(
                endpoint,
                alias("code", listOfNotNull(jsonString(json, "code"), jsonString(json, "invite_code"))),
                jsonString(json, "group"),
                jsonString(json, "node_id"),
            )
        }
        val uri = runCatching { URI(text) }.getOrElse {
            throw IllegalArgumentException("不是受支持的 Aries 配对内容", it)
        }
        require(uri.scheme.equals("aries", true) || uri.scheme.equals("https", true)) {
            "不是受支持的 Aries 配对内容"
        }
        require(uri.userInfo == null && uri.fragment == null && !uri.host.isNullOrBlank()) {
            "配对地址无效"
        }
        val endpoint = if (uri.scheme.equals("aries", true)) {
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { "配对地址无效" }
            URI("https", null, uri.host, if (uri.port > 0) uri.port else 10240, null, null, null).toString()
        } else {
            text.substringBefore('?')
        }
        val query = uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).map { pair ->
            val fields = pair.split('=', limit = 2)
            URLDecoder.decode(fields[0], "UTF-8") to
                URLDecoder.decode(fields.getOrElse(1) { "" }, "UTF-8")
        }
        fun values(vararg names: String): List<String> =
            query.filter { it.first in names }.map { checkedString(it.second, it.first) }
                .filter(String::isNotEmpty)
        return PairingInput(
            normalizedEndpoint(endpoint),
            alias("code", values("code", "invite_code")),
            alias("group", values("group")),
            alias("node_id", values("node_id")),
        )
    }

    fun readResponse(input: InputStream, maxBytes: Int, checkActive: () -> Unit = {}): String {
        require(maxBytes in 1 until Int.MAX_VALUE)
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(minOf(maxBytes + 1, 8192))
        while (true) {
            checkActive()
            val read = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
            checkActive()
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
            if (output.size() > maxBytes) throw ResponseTooLarge()
        }
        return output.toString("UTF-8")
    }

    private fun jsonString(json: JSONObject, name: String): String? {
        if (!json.has(name) || json.isNull(name)) return null
        val value = json.opt(name)
        require(value is String) { "二维码字段 $name 无效" }
        return checkedString(value, name).takeIf(String::isNotEmpty)
    }

    private fun checkedString(value: String, name: String): String {
        require(value.none(Char::isISOControl)) { "二维码字段 $name 无效" }
        return value.trim()
    }

    private fun alias(name: String, values: List<String>): String? {
        require(values.distinct().size <= 1) { "二维码字段 $name 存在冲突" }
        return values.firstOrNull()
    }

    private fun normalizedEndpoint(vararg values: String?): String {
        val normalized = values.filterNotNull().map {
            SettingsContract.normalizeAriesEndpoint(it)
                ?: throw IllegalArgumentException("二维码中的 Aries 地址必须使用 HTTPS 主机地址")
        }
        return alias("endpoint", normalized)
            ?: throw IllegalArgumentException("二维码中没有 Aries 地址")
    }
}
