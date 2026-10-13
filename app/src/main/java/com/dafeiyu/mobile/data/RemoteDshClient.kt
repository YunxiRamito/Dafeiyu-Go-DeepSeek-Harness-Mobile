package com.dafeiyu.mobile.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.util.UUID

/**
 * Aries-backed DSH remote transport. It carries only bounded JSON envelopes;
 * the PC bridge owns DSH authentication and never receives Android credentials.
 * A caller keeps requestId and cursor across reconnects, so a retry polls the
 * existing task instead of submitting another prompt.
 */
interface RemoteDshTransport {
    suspend fun connectionState(): JSONObject
    suspend fun submit(request: RemoteDshClient.Request): JSONObject
    suspend fun poll(scope: RemoteDshScope, cursor: String? = null, limit: Int = 100): RemoteDshClient.PollResult
    suspend fun acknowledge(scope: RemoteDshScope, event: RemoteDshClient.Event): JSONObject
    suspend fun acknowledgeWeb(scope: RemoteDshScope, event: RemoteDshClient.Event): JSONObject = acknowledge(scope, event)
    suspend fun cancelObservation(scope: RemoteDshScope, requestId: String): JSONObject
    /** Transports without a native durable ledger have nothing further to retire. */
    suspend fun retireTransferReceipt(scope: RemoteDshScope, requestId: String, operation: String, terminalSequence: Long): JSONObject =
        JSONObject().put("retired", true)
}

fun interface RemoteDshProviderCall {
    suspend fun call(method: String, params: JSONObject, expectedRemoteScope: RemoteDshScope?): JSONObject
    /** Native hosts authenticate the frozen call inside the Provider lock. Other
     * transports keep explicit before/after checks and never claim that admission. */
    suspend fun callInScope(method: String, params: JSONObject, scope: RemoteDshScope, capability: String,
        acknowledgeMessageId: String? = null): JSONObject {
        val detached = JSONObject(params.toString())
        LocalPluginIpcPolicy.NativeScopedAdmissions.validate(method, detached, scope.toJson(), capability, acknowledgeMessageId)
        val before = call("get_connection_state", JSONObject(), null)
        scope.requireCurrent(before, requireOnline = true)
        RemoteDshClient.requireScopedCapability(before, scope.groupId, capability)
        if (method == "send_encrypted_message" && detached.optJSONObject("body")?.optString("operation") == "session.prompt" &&
            detached.getJSONObject("body").getJSONObject("payload").optJSONArray("content")?.let { content ->
                (0 until content.length()).any { content.optJSONObject(it)?.optString("type") == "file" }
            } == true) RemoteDshClient.requireScopedCapability(before, scope.groupId, "remote_attachments")
        val response = call(method, detached, scope)
        val result = if (acknowledgeMessageId == null) response else {
            require(response.optString("message_id").isNotEmpty())
            call("acknowledge_message", JSONObject().put("group_id", scope.groupId).put("message_id", acknowledgeMessageId), scope)
                .also { require(it.optBoolean("acknowledged")) }
        }
        val after = call("get_connection_state", JSONObject(), null)
        scope.requireCurrent(after)
        RemoteDshClient.requireScopedCapability(after, scope.groupId, capability)
        return result
    }
    suspend fun retireTransferReceipt(scope: RemoteDshScope, requestId: String, operation: String): JSONObject =
        JSONObject().put("retired", true)
}

class RemoteDshClient(private val providerCall: RemoteDshProviderCall) : RemoteDshTransport {
    constructor(provider: AriesConnectionProvider) : this(object : RemoteDshProviderCall {
        override suspend fun call(method: String, params: JSONObject, expectedRemoteScope: RemoteDshScope?): JSONObject =
            if (method == "send_encrypted_message" && params.optString("kind") == REQUEST_KIND && expectedRemoteScope != null &&
                params.optJSONObject("body")?.optString("operation") in CONSUMABLE_TRANSFER_OPERATIONS)
                provider.callNativeBulk(params, expectedRemoteScope)
            else provider.call(method, params, expectedRemoteScope = expectedRemoteScope)

        override suspend fun retireTransferReceipt(scope: RemoteDshScope, requestId: String, operation: String): JSONObject =
            provider.retireTransferReceipt(scope, requestId, operation)

        override suspend fun callInScope(method: String, params: JSONObject, scope: RemoteDshScope, capability: String,
            acknowledgeMessageId: String?): JSONObject =
            if (method == "send_encrypted_message" && params.optString("kind") == REQUEST_KIND &&
                params.optJSONObject("body")?.optString("operation") in CONSUMABLE_TRANSFER_OPERATIONS) {
                require(acknowledgeMessageId == null)
                provider.callNativeBulk(params, scope)
            } else provider.callNativeScoped(method, params, scope, capability, acknowledgeMessageId)
    })

    private suspend fun call(method: String, params: JSONObject = JSONObject(), expectedRemoteScope: RemoteDshScope? = null): JSONObject =
        providerCall.call(method, params, expectedRemoteScope)
    data class Request(
        val groupId: String,
        val operation: String,
        val payload: JSONObject,
        val requestId: String = newRequestId(),
        val scope: RemoteDshScope? = null,
    )

    data class Event(
        val requestId: String,
        val sequence: Long,
        val type: String,
        val payload: Any?,
        val messageId: String,
        val senderDeviceId: String,
    )

    data class PollResult(val events: List<Event>, val nextCursor: String?, val closed: Boolean)

    override suspend fun connectionState(): JSONObject = call("get_connection_state")

    override suspend fun retireTransferReceipt(scope: RemoteDshScope, requestId: String, operation: String, terminalSequence: Long): JSONObject {
        requireRequestId(requestId)
        require(operation in CONSUMABLE_TRANSFER_OPERATIONS && terminalSequence in 0..MAX_SEQUENCE)
        // Native may already have retired the original and ACK mutation before
        // our app journal commit failed. Check that idempotent state first so a
        // retry never creates new ciphertext under a consumed server key.
        val previous = providerCall.retireTransferReceipt(scope, requestId, operation)
        if (previous.optBoolean("retired")) return previous
        require(previous.length() == 2 && previous.opt("retired") == false && previous.opt("ack_required") == true) {
            "native_transfer_retirement_rejected"
        }
        val state = connectionState()
        scope.requireCurrent(state, requireOnline = true)
        requireRemoteCapability(state, scope.groupId, capabilityFor(operation))
        call("send_encrypted_message", JSONObject().put("group_id", scope.groupId).put("kind", "dsh_remote_ack")
            .put("body", JSONObject().put("protocol", PROTOCOL).put("request_id", requestId)
                .put("target_device_id", scope.remoteDeviceId).put("operation", operation).put("sequence", terminalSequence))
            .put("idempotency_key", "bulk-ack-$requestId-$operation-$terminalSequence"), expectedRemoteScope = scope)
        return providerCall.retireTransferReceipt(scope, requestId, operation).also {
            require(it.optBoolean("retired")) { "native_transfer_retirement_rejected" }
        }
    }

    override suspend fun submit(request: Request): JSONObject {
        validateRequest(request)
        val binding = request.scope ?: RemoteDshScope.fromState(connectionState()).also {
            require(it.groupId == request.groupId)
        }
        val peer = binding.remoteDeviceId
        val envelope = JSONObject()
            .put("protocol", PROTOCOL)
            .put("request_id", request.requestId)
            .put("target_device_id", peer)
            .put("operation", request.operation)
            .put("payload", JSONObject(request.payload.toString()))
        val response = providerCall.callInScope("send_encrypted_message", JSONObject()
            .put("group_id", request.groupId)
            .put("kind", REQUEST_KIND)
            .put("body", envelope)
            .put("idempotency_key", "dsh-remote-${request.requestId}"), binding, capabilityFor(request.operation))
        return JSONObject().put("request_id", request.requestId).put("message_id", response.getString("message_id"))
    }

    override suspend fun poll(scope: RemoteDshScope, cursor: String?, limit: Int): PollResult {
        val groupId = scope.groupId
        requireGroup(groupId)
        require(limit in 1..100)
        val peer = scope.remoteDeviceId
        val local = scope.localDeviceId
        require(cursor == null || cursor.matches(Regex("[0-9]{1,64}")))
        val page = providerCall.callInScope("list_incoming_messages", JSONObject().put("group_id", groupId)
            .put("cursor", cursor ?: "0").put("limit", limit), scope, "remote_task")
        val events = ArrayList<Event>()
        val messages = page.getJSONArray("messages")
        require(messages.length() <= limit)
        for (index in 0 until messages.length()) {
            currentCoroutineContext().ensureActive()
            val message = messages.getJSONObject(index)
            if (message.optString("kind") != EVENT_KIND || message.optString("sender_device_id") != peer) continue
            val body = message.optJSONObject("body") ?: continue
            if (body.optString("protocol") != PROTOCOL || body.optString("target_device_id") != local ||
                !body.optString("request_id").matches(REQUEST_ID) || body.optString("type") !in EVENT_TYPES ||
                body.toString().toByteArray(Charsets.UTF_8).size > MAX_ENVELOPE_BYTES) continue
            val rawSequence = body.opt("sequence")
            if (rawSequence !is Number) continue
            val sequence = rawSequence.toLong()
            if (sequence < 0L || sequence > MAX_SEQUENCE || rawSequence.toDouble() != sequence.toDouble()) continue
            events += Event(body.getString("request_id"), sequence, body.getString("type"), body.opt("payload"),
                message.getString("message_id"), peer)
        }
        // Callers commit the events and cursor to their own durable journal
        // before acknowledge(). Returning a frame is not proof of persistence.
        val next = page.optString("next_cursor").takeIf { it.isNotEmpty() && it != "null" }
        require(next == null || next.matches(Regex("[0-9]{1,64}")))
        return PollResult(events.sortedWith(compareBy<Event> { it.requestId }.thenBy { it.sequence }),
            next,
            events.any { it.type == "closed" })
    }

    override suspend fun acknowledge(scope: RemoteDshScope, event: Event): JSONObject {
        val groupId = scope.groupId
        requireGroup(groupId); requireRequestId(event.requestId)
        if (event.senderDeviceId != scope.remoteDeviceId)
            throw AriesProviderException("conflict")
        require(event.messageId.isNotEmpty() && event.messageId.length <= 256)
        return providerCall.callInScope("acknowledge_message", JSONObject().put("group_id", groupId)
            .put("message_id", event.messageId), scope, "remote_task")
    }

    override suspend fun acknowledgeWeb(scope: RemoteDshScope, event: Event): JSONObject {
        requireRequestId(event.requestId)
        require(event.sequence in 0..MAX_SEQUENCE && event.senderDeviceId == scope.remoteDeviceId)
        // A local Aries receipt only suppresses redelivery on this phone. The
        // encrypted ACK is the PC host's real finite transport window.
        return providerCall.callInScope("send_encrypted_message", JSONObject().put("group_id", scope.groupId).put("kind", "dsh_remote_ack")
            .put("body", JSONObject().put("protocol", PROTOCOL).put("request_id", event.requestId)
                .put("target_device_id", scope.remoteDeviceId).put("sequence", event.sequence))
            .put("idempotency_key", "web-ack-${event.requestId}-${event.sequence}"), scope, "remote_web_tunnel", event.messageId)
    }

    /** Stops an observation stream. Cancelling a PC task uses the session.cancel operation. */
    override suspend fun cancelObservation(scope: RemoteDshScope, requestId: String): JSONObject {
        val groupId = scope.groupId
        requireGroup(groupId); requireRequestId(requestId)
        val response = providerCall.callInScope("send_encrypted_message", JSONObject()
            .put("group_id", groupId).put("kind", CANCEL_KIND)
            .put("body", JSONObject().put("protocol", PROTOCOL).put("request_id", requestId).put("operation", "cancel")
                .put("target_device_id", scope.remoteDeviceId))
            .put("idempotency_key", "dsh-remote-cancel-$requestId"), scope, "remote_task")
        return JSONObject().put("request_id", requestId).put("message_id", response.getString("message_id")).put("submitted", true)
    }

    private fun requireRemoteCapability(state: JSONObject, groupId: String, capability: String) =
        requireScopedCapability(state, groupId, capability)

    private fun capabilityFor(operation: String) = when (operation) {
        in ARCHIVE_DELETE_OPERATIONS -> "remote_archive_delete"
        in ARTIFACT_OPERATIONS -> "remote_artifacts"
        in ATTACHMENT_OPERATIONS -> "remote_attachments"
        in WORKSPACE_LIFECYCLE_OPERATIONS -> "remote_workspace_lifecycle"
        "web.http", "web.socket", "web.socket.send", "web.socket.close" -> "remote_web_tunnel"
        "directory.list", "workspace.list", "workspace.resolve" -> "remote_directory"
        "session.selectModel", "settings.describe", "settings.update", "permissions.catalog" -> "remote_settings"
        else -> "remote_task"
    }

    private fun validateRequest(request: Request) {
        requireGroup(request.groupId); requireRequestId(request.requestId)
        require(request.operation in OPERATIONS)
        require(request.payload.length() <= 64)
        require(request.payload.toString().toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES)
        remoteValidateJson(request.payload)
        require(request.scope == null || request.scope.groupId == request.groupId)
    }

    companion object {
        internal fun requireScopedCapability(state: JSONObject, groupId: String, capability: String) {
        if (state.optString("group_id") != groupId) throw AriesProviderException("conflict")
        if (state.optBoolean("server_connected") != true || state.optString("pairing_status") != "paired")
            throw AriesProviderException("connection_unavailable")
        if (state.optJSONObject("remote_device")?.optString("online") != "online")
            throw AriesProviderException("remote_offline")
        if (state.optJSONObject("capabilities")?.optBoolean(capability) != true)
            throw AriesProviderException("unsupported_method")
        }
        const val PROTOCOL = "dafeiyu-go.remote-dsh.v1"
        const val REQUEST_KIND = "dsh_remote_request"
        const val EVENT_KIND = "dsh_remote_event"
        const val CANCEL_KIND = "dsh_remote_cancel"
        const val CAPABILITY_KIND = "dsh_remote_capabilities"
        const val MAX_PAYLOAD_BYTES = 128 * 1024
        private const val MAX_ENVELOPE_BYTES = 192 * 1024
        private const val MAX_SEQUENCE = 1025L
        private val REQUEST_ID = Regex("[A-Za-z0-9_-]{8,128}")
        private val EVENT_TYPES = setOf("accepted", "result", "error", "event", "closed")
        val WORKSPACE_LIFECYCLE_OPERATIONS = setOf("workspace.remove.prepare", "workspace.remove", "workspace.files.delete", "workspace.removed.list", "workspace.removed.restore", "session.archive", "session.restore", "session.archived.list")
        val ARCHIVE_DELETE_OPERATIONS = setOf("session.archived.delete.prepare", "session.archived.delete")
        val LIFECYCLE_OPERATIONS = WORKSPACE_LIFECYCLE_OPERATIONS + ARCHIVE_DELETE_OPERATIONS
        val ARTIFACT_OPERATIONS = setOf("artifact.list", "artifact.prepare", "artifact.read", "artifact.close")
        val ATTACHMENT_OPERATIONS = setOf("attachment.prepare", "attachment.write", "attachment.status", "attachment.commit", "attachment.cancel")
        val CONSUMABLE_TRANSFER_OPERATIONS = setOf("artifact.list", "artifact.read", "artifact.close", "attachment.write", "attachment.status")
        private val OPERATIONS = setOf("session.create", "session.selectModel", "session.prompt", "session.cancel", "session.control", "session.follow", "session.list", "directory.list", "workspace.list", "workspace.resolve", "models.catalog", "settings.describe", "settings.update", "permissions.catalog", "web.http", "web.socket", "web.socket.send", "web.socket.close") + LIFECYCLE_OPERATIONS + ARTIFACT_OPERATIONS + ATTACHMENT_OPERATIONS
        fun newRequestId(): String = "android-" + UUID.randomUUID().toString().replace("-", "")
        private fun requireGroup(value: String) { require(value.matches(Regex("[A-Za-z0-9_-]{1,128}"))) }
        private fun requireRequestId(value: String) { require(value.matches(REQUEST_ID)) }
    }
}
