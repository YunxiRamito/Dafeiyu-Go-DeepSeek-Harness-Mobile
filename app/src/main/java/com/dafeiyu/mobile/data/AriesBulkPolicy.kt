package com.dafeiyu.mobile.data

import org.json.JSONArray
import org.json.JSONObject

/** These operations are observations or exact offset/digest writes, never task intents. */
internal object AriesBulkPolicy {
    val operations = setOf("artifact.list", "artifact.read", "artifact.close", "attachment.write", "attachment.status")
    private val requestId = Regex("[A-Za-z0-9_-]{8,128}")
    private const val MAX_RANGES = 4096

    data class Request(val id: String, val operation: String, val idempotencyKey: String) {
        val capability: String get() = if (operation.startsWith("artifact.")) "remote_artifacts" else "remote_attachments"
    }

    /** Called only after app-owned admission, not from a claimed JSON scope. */
    fun request(params: JSONObject, scope: RemoteDshScope): Request {
        if (params.optString("kind") != RemoteDshClient.REQUEST_KIND || params.optString("group_id") != scope.groupId)
            fail("permission_denied")
        val body = params.optJSONObject("body") ?: fail("protocol_error")
        if (body.keys().asSequence().toSet() != setOf("protocol", "request_id", "target_device_id", "operation", "payload") ||
            body.optString("protocol") != RemoteDshClient.PROTOCOL || body.optString("target_device_id") != scope.remoteDeviceId)
            fail("permission_denied")
        val id = body.opt("request_id") as? String ?: fail("protocol_error")
        val operation = body.opt("operation") as? String ?: fail("protocol_error")
        if (!id.matches(requestId) || operation !in operations) fail("unsupported_method")
        val key = params.opt("idempotency_key") as? String ?: fail("protocol_error")
        if (key != "dsh-remote-$id") fail("conflict")
        val payload = body.optJSONObject("payload") ?: fail("protocol_error")
        require(payload.length() <= 64)
        remoteValidateJson(payload)
        return Request(id, operation, key)
    }

    fun sequence(value: Any?): ULong {
        if (value !is Number) fail("protocol_error")
        return decimal(value.toString())
    }

    fun decimal(value: String): ULong {
        if (value.isEmpty() || value.length > 20 || value.any { it !in '0'..'9' }) fail("protocol_error")
        return value.toULongOrNull() ?: fail("protocol_error")
    }

    /** Never invent a sequence from a page cursor: each ID must carry its actual server sequence. */
    fun validatePage(page: JSONObject, cursor: String): JSONArray {
        var previous = decimal(cursor)
        val items = page.optJSONArray("items") ?: fail("protocol_error")
        if (items.length() > 4) fail("protocol_error")
        val ids = HashSet<String>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: fail("protocol_error")
            val sequence = sequence(item.opt("sequence"))
            if (sequence <= previous || !ids.add(item.getString("id"))) fail("protocol_error")
            previous = sequence
        }
        val next = page.opt("next_cursor") as? String ?: fail("protocol_error")
        if (decimal(next) != previous) fail("protocol_error")
        return items
    }

    fun suppressed(binding: JSONObject, sequence: ULong): Boolean {
        val ranges = binding.optJSONArray("bulk_ack_ranges") ?: return false
        if (ranges.length() > MAX_RANGES) fail("protocol_error")
        for (index in 0 until ranges.length()) {
            val range = ranges.getJSONArray(index)
            if (range.length() != 2) fail("protocol_error")
            val first = decimal(range.getString(0))
            val last = decimal(range.getString(1))
            if (first > last) fail("protocol_error")
            if (sequence in first..last) return true
        }
        return false
    }

    /** Compact only known suppressed IDs; gaps containing an unacknowledged message remain gaps. */
    fun suppress(binding: JSONObject, sequence: ULong) {
        val old = binding.optJSONArray("bulk_ack_ranges") ?: JSONArray()
        if (old.length() > MAX_RANGES) fail("protocol_error")
        val ranges = ArrayList<Pair<ULong, ULong>>(old.length() + 1)
        for (index in 0 until old.length()) {
            val range = old.getJSONArray(index)
            if (range.length() != 2) fail("protocol_error")
            val first = decimal(range.getString(0))
            val last = decimal(range.getString(1))
            if (first > last) fail("protocol_error")
            ranges.add(first to last)
        }
        ranges.add(sequence to sequence)
        ranges.sortBy { it.first }
        val merged = ArrayList<Pair<ULong, ULong>>()
        for (range in ranges) {
            val last = merged.lastOrNull()
            if (last != null && (range.first <= last.second || last.second != ULong.MAX_VALUE && range.first == last.second + 1uL))
                merged[merged.lastIndex] = last.first to maxOf(last.second, range.second)
            else merged.add(range)
        }
        if (merged.size > MAX_RANGES) fail("disk_space_low")
        binding.put("bulk_ack_ranges", JSONArray().also { result ->
            merged.forEach { result.put(JSONArray().put(it.first.toString()).put(it.second.toString())) }
        })
    }

    private fun fail(code: String): Nothing = throw AriesProviderException(code)
}
