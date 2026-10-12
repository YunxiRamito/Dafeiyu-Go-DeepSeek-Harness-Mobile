package com.dafeiyu.mobile.data

import org.json.JSONObject

internal object AriesProviderScope {
    /** Read from SharedPreferences.all once, rather than mixing individually read settings. */
    data class Selection(
        val rawEndpoint: String,
        val nodeId: String,
        val groupId: String,
        val deviceId: String,
    ) {
        val endpoint: String get() = SettingsContract.normalizeAriesEndpoint(rawEndpoint).orEmpty()
        val configured: Boolean get() = endpoint.isNotEmpty() && nodeId.isNotEmpty() &&
            groupId.isNotEmpty() && deviceId.isNotEmpty()
    }

    fun selection(values: Map<String, *>): Selection = Selection(
        values["aries_endpoint"] as? String ?: "",
        values["aries_node_id"] as? String ?: "",
        values["aries_group_id"] as? String ?: "",
        values["aries_device_id"] as? String ?: "",
    )

    private val fields = mapOf(
        "get_connection_state" to emptySet(),
        "subscribe_connection_state" to emptySet(),
        "send_encrypted_message" to setOf("kind", "body", "idempotency_key"),
        "list_incoming_messages" to setOf("cursor", "limit"),
        "acknowledge_message" to setOf("message_id"),
        "create_file_transfer" to setOf("relative_path", "file_id", "size", "chunk_size", "hash", "idempotency_key", "targets", "ttl_seconds"),
        "upload_file_chunk" to setOf("transfer_id", "file_id", "chunk_index", "bytes", "hash", "chunk_count"),
        "complete_file_transfer" to setOf("transfer_id", "file_id"),
        "list_incoming_file_transfers" to setOf("cursor", "limit"),
        "download_file_chunk" to setOf("transfer_id", "file_id", "chunk_index"),
        "acknowledge_file_transfer" to setOf("transfer_id", "file_id"),
        "get_group_file_cache_stats" to emptySet(),
        "cleanup_group_file_cache" to setOf("file_ids", "idempotency_key"),
    )
    val stateMethods = setOf("get_connection_state", "subscribe_connection_state")
    val groupMethods = fields.keys - stateMethods

    fun validate(method: String, params: JSONObject, selectedGroup: String) {
        val allowed = fields[method] ?: throw AriesProviderException("unsupported_method")
        // Only business fields may cross IPC; arbitrary HTTP, proof, token and key fields fail closed.
        if (params.keys().asSequence().any { it != "group_id" && it !in allowed })
            throw AriesProviderException("permission_denied")
        if (method in groupMethods || params.has("group_id")) {
            val requested = params.opt("group_id")
            if (requested !is String || requested.isEmpty() || selectedGroup.isEmpty() || requested != selectedGroup)
                throw AriesProviderException("permission_denied")
        }
    }
}
