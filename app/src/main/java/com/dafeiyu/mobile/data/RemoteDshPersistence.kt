package com.dafeiyu.mobile.data

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Every remote cache is bound to a node, relationship and explicit execution device. */
data class RemoteDshScope(
    val nodeId: String,
    val groupId: String,
    val localDeviceId: String,
    val remoteDeviceId: String,
) {
    init {
        listOf(nodeId, groupId, localDeviceId, remoteDeviceId).forEach { require(it.matches(IDENTIFIER)) }
        require(localDeviceId != remoteDeviceId)
    }

    val storageKey: String get() = remoteJsonHash(toJson())
    fun toJson(): JSONObject = JSONObject().put("node_id", nodeId).put("group_id", groupId)
        .put("local_device_id", localDeviceId).put("remote_device_id", remoteDeviceId)

    fun requireCurrent(state: JSONObject, requireOnline: Boolean = false) {
        if (state.optString("node_id") != nodeId || state.optString("group_id") != groupId ||
            state.optJSONObject("local_device")?.optString("id") != localDeviceId ||
            state.optJSONObject("remote_device")?.optString("id") != remoteDeviceId)
            throw AriesProviderException("conflict")
        if (state.optString("pairing_status") in setOf("unpaired", "revoked") ||
            state.optJSONObject("terminal_receipt") != null) throw AriesProviderException("revoked")
        if (requireOnline && (state.optBoolean("server_connected") != true ||
                state.optString("pairing_status") != "paired")) throw AriesProviderException("connection_unavailable")
        if (requireOnline && state.optJSONObject("remote_device")?.optString("online") != "online")
            throw AriesProviderException("remote_offline")
    }

    companion object {
        private val IDENTIFIER = Regex("[A-Za-z0-9_-]{1,128}")
        fun fromState(state: JSONObject): RemoteDshScope = RemoteDshScope(state.getString("node_id"),
            state.getString("group_id"), state.getJSONObject("local_device").getString("id"),
            state.getJSONObject("remote_device").getString("id"))
        fun fromJson(value: JSONObject): RemoteDshScope = RemoteDshScope(value.getString("node_id"),
            value.getString("group_id"), value.getString("local_device_id"), value.getString("remote_device_id"))
    }
}

/** AtomicFile owns crash recovery. A corrupt cache is reported, never silently replaced. */
internal class RemoteDshJsonFile(private val file: File, private val byteLimit: Int = 8 * 1024 * 1024) {
    private val atomic = AtomicFile(file)

    fun read(): JSONObject? {
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        val bytes = atomic.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= byteLimit) { "remote_cache_too_large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return JSONObject(bytes.toString(Charsets.UTF_8)).also { remoteValidateJson(it, byteLimit) }
    }

    fun write(value: JSONObject) {
        val bytes = remoteValidateJson(value, byteLimit)
        val parent = file.parentFile ?: error("remote_cache_directory_missing")
        check(parent.isDirectory || parent.mkdirs()) { "remote_cache_directory_unavailable" }
        var output: java.io.FileOutputStream? = null
        try {
            output = atomic.startWrite()
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            output?.let(atomic::failWrite)
            throw error
        }
    }
}

internal fun remoteValidateJson(value: Any?, byteLimit: Int = RemoteDshClient.MAX_PAYLOAD_BYTES): ByteArray {
    var items = 0
    fun visit(item: Any?, depth: Int) {
        require(depth <= 32 && ++items <= 100_000) { "remote_json_limit" }
        when (item) {
            null, JSONObject.NULL -> Unit
            is JSONObject -> item.keys().forEach { key ->
                require(key.length <= 512 && key !in setOf("__proto__", "constructor", "prototype"))
                visit(item.get(key), depth + 1)
            }
            is JSONArray -> for (index in 0 until item.length()) visit(item.get(index), depth + 1)
            is String -> require(item.length <= byteLimit)
            is Number -> require(item.toDouble().isFinite())
            is Boolean -> Unit
            else -> error("remote_json_type")
        }
    }
    visit(value, 0)
    return (when (value) {
        is JSONObject, is JSONArray -> value.toString()
        else -> remoteJsonLiteral(value)
    }).toByteArray(Charsets.UTF_8).also { require(it.size <= byteLimit) { "remote_json_limit" } }
}

internal fun remoteJsonHash(value: Any?): String {
    fun canonical(item: Any?): String = when (item) {
        is JSONObject -> item.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonical(item.get(it))
        }
        is JSONArray -> (0 until item.length()).joinToString(",", "[", "]") { canonical(item.get(it)) }
        else -> remoteJsonLiteral(item)
    }
    remoteValidateJson(value, 8 * 1024 * 1024)
    return MessageDigest.getInstance("SHA-256").digest(canonical(value).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

private fun remoteJsonLiteral(value: Any?): String = JSONArray().put(value ?: JSONObject.NULL).toString().let {
    it.substring(1, it.length - 1)
}

internal fun JSONObject.remoteId(key: String): String = getString(key).also {
    require(it.isNotEmpty() && it.length <= 512 && it.none { character -> character.isISOControl() })
}
internal fun JSONObject.remoteLong(key: String): Long = (get(key) as? Number)?.let {
    it.toLong().takeIf { integer -> integer >= 0 && integer.toDouble() == it.toDouble() }
} ?: error("remote_invalid_number")
/** DSH's empty-history watermark is -1. Actual event sequences remain non-negative. */
internal fun JSONObject.remoteSequenceCursor(key: String): Long = (get(key) as? Number)?.let {
    it.toLong().takeIf { integer -> integer >= -1 && integer.toDouble() == it.toDouble() }
} ?: error("remote_invalid_cursor")
internal fun JSONObject.remoteOptionalString(key: String): String? = (opt(key) as? String)?.also {
    require(it.length <= 16_384 && !it.contains('\u0000'))
}
internal fun JSONArray.remoteObjects(max: Int): List<JSONObject> {
    require(length() <= max)
    return List(length()) { getJSONObject(it) }
}
