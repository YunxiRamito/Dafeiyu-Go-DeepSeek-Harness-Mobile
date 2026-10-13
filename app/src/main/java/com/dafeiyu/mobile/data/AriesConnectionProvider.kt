package com.dafeiyu.mobile.data

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.security.MessageDigest
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

/** High-level provider matching dsh connection-plugin; crypto and Aries HTTP stay in this app. */
class AriesProviderException(val code: String) : Exception(code)

class AriesConnectionProvider(context: Context) {
    private val app = context.applicationContext
    private val client = AriesClient(app)
    private val identity = AriesIdentityStore(app)
    private val groups = AriesGroupStore(app)
    private val prefs = app.getSharedPreferences("dafeiyu_settings", Context.MODE_PRIVATE)
    private val vault = AriesProviderVault(app)
    private lateinit var store: JSONObject
    private lateinit var binding: JSONObject
    private lateinit var config: AriesClient.Config
    private lateinit var selected: AriesProviderScope.Selection
    private var localPublicKey = ""
    private lateinit var operationContext: kotlin.coroutines.CoroutineContext
    @Volatile private var selectionChanged = false
    private var scope = ""
    private var node = ""
    private var group = ""
    private var device = ""
    private var members = emptyList<AriesGroupStore.Member>()
    private var presence = emptyList<AriesGroupStore.Presence>()
    private var connected = false
    private var member = false
    private var rotationRequired = false
    private var nodeCapabilities = emptySet<String>()
    private var remoteHostCapabilities = emptySet<String>()
    /** Aries presence expires while the app is away from the pairing screen. */
    private var lastHeartbeatAtMillis = 0L
    private val heartbeatIntervalMillis = 30_000L

    /** App-internal test observation only. No URL, identity, request body, key,
     * exception message or stack is included, and nothing is added to Node IPC. */
    internal data class Diagnostic(val stage: String, val cause: String?, val rootCause: String?,
        val httpStatus: Int?, val serverCode: String?, val connected: Boolean, val member: Boolean,
        val peerPresence: String, val terminalStatus: String?, val selectorChanged: Boolean) {
        fun toJson(): JSONObject = JSONObject().put("stage", stage).put("cause", cause ?: JSONObject.NULL)
            .put("rootCause", rootCause ?: JSONObject.NULL).put("httpStatus", httpStatus ?: JSONObject.NULL)
            .put("serverCode", serverCode ?: JSONObject.NULL).put("connected", connected).put("member", member)
            .put("peerPresence", peerPresence).put("terminalStatus", terminalStatus ?: JSONObject.NULL)
            .put("selectorChanged", selectorChanged)
    }
    @Volatile private var diagnosticObserver: ((Diagnostic) -> Unit)? = null
    internal fun setDiagnosticObserver(observer: ((Diagnostic) -> Unit)?) { diagnosticObserver = observer }
    internal fun diagnose(stage: String, failure: Throwable? = null) {
        val observer = diagnosticObserver ?: return
        val category: (Throwable) -> String = { error -> when (error) {
            is kotlinx.coroutines.TimeoutCancellationException -> "coroutine_timeout"
            is kotlinx.coroutines.CancellationException -> "cancelled"
            is java.net.SocketTimeoutException -> "socket_timeout"
            is javax.net.ssl.SSLHandshakeException -> "tls_handshake"
            is javax.net.ssl.SSLPeerUnverifiedException -> "tls_peer"
            is javax.net.ssl.SSLException -> "tls_failure"
            is java.net.ConnectException -> "connect_failure"
            is java.net.UnknownHostException -> "unknown_host"
            is java.net.SocketException -> "socket_failure"
            is java.io.EOFException -> "eof"
            is java.io.IOException -> "io_failure"
            is AriesClient.AriesException -> "aries_http"
            is AriesProviderException -> "provider_policy"
            is org.json.JSONException -> "invalid_json"
            is IllegalArgumentException -> "invalid_data"
            is IllegalStateException -> "invalid_state"
            is SecurityException -> "permission_failure"
            else -> "other"
        } }
        val chain = mutableListOf<Throwable>()
        var current = failure
        while (chain.size < 8) {
            val candidate = current ?: break
            if (chain.any { it === candidate }) break
            chain.add(candidate); current = candidate.cause
        }
        val http = chain.filterIsInstance<AriesClient.AriesException>().firstOrNull()
        val code = http?.errorCode ?: chain.filterIsInstance<AriesProviderException>().firstOrNull()?.code
        val peer = if (::binding.isInitialized) binding.optString("peer_id") else ""
        val online = presence.firstOrNull { it.deviceId == peer }?.online
        val terminal = if (::binding.isInitialized) binding.optJSONObject("terminal_receipt")?.optString("status") else null
        val record = Diagnostic(stage, failure?.let(category), chain.lastOrNull()?.let(category),
            http?.httpStatus?.takeIf { it in 100..599 }, code?.let { if (it in DIAGNOSTIC_CODES) it else "other" },
            connected, member, if (online == null) "unknown" else if (online) "online" else "offline",
            terminal?.takeIf { it in setOf("unpaired", "left", "revoked", "group_closed") }, selectionChanged)
        // An observer failure cannot change validation, publication or cancellation.
        runCatching { observer(record) }
    }

    /** The selector is app-owned settings. Plugin callers cannot redirect credentials or select another group. */
    suspend fun call(method: String, params: JSONObject = JSONObject(), expectedRemoteScope: RemoteDshScope? = null): JSONObject = serialized {
        val selection = AriesProviderScope.selection(prefs.all)
        // Freeze caller-owned JSON and the app selector before network IO.
        val request = JSONObject(params.toString())
        AriesProviderScope.validate(method, request, selection.groupId)
        select(selection)
        refresh()
        // Check the frozen selector inside the same serialized admission as IO.
        // A peer/node change between a native state read and a send must fail.
        expectedRemoteScope?.requireCurrent(snapshot(), requireOnline = method !in AriesProviderScope.stateMethods)
        if (method in AriesProviderScope.stateMethods) return@serialized snapshot()
        if (!connected) fail("connection_unavailable")
        if (!member) fail("revoked")
        if (!paired()) fail("pairing_incomplete")
        if (rotationRequired) fail("conflict")
        val result = when (method) {
            "send_encrypted_message" -> sendMessage(request)
            "list_incoming_messages" -> messages(request)
            "acknowledge_message" -> ackMessage(request)
            "create_file_transfer" -> createFile(request)
            "upload_file_chunk" -> upload(request)
            "complete_file_transfer" -> complete(request)
            "list_incoming_file_transfers" -> files(request)
            "download_file_chunk" -> download(request)
            "acknowledge_file_transfer" -> ackFile(request)
            "get_group_file_cache_stats" -> get("/file-transfers/cache").also { require(it.getString("group_id") == group) }
            "cleanup_group_file_cache" -> cleanup(request)
            else -> fail("unsupported_method")
        }
        ensureCurrent()
        result
    }

    /** Only the application-owned Bridge can fold scope and capability checks
     * into this transaction. A plugin's remote_scope never selects this path. */
    internal suspend fun callNativeScoped(method: String, params: JSONObject, expectedScope: RemoteDshScope,
        capability: String, acknowledgeMessageId: String? = null): JSONObject = serialized {
        val request = JSONObject(params.toString())
        LocalPluginIpcPolicy.NativeScopedAdmissions.validate(method, request, expectedScope.toJson(), capability, acknowledgeMessageId)
        val selection = AriesProviderScope.selection(prefs.all)
        AriesProviderScope.validate(method, request, selection.groupId)
        select(selection)
        refresh()
        requireNativeScoped(expectedScope, capability)
        val generation = nativeGeneration()
        try {
            val response = when (method) {
                "send_encrypted_message" -> {
                    if (request.optJSONObject("body")?.optString("operation") == "session.prompt" &&
                        request.getJSONObject("body").getJSONObject("payload").optJSONArray("content")?.let { content ->
                            (0 until content.length()).any { content.optJSONObject(it)?.optString("type") == "file" }
                        } == true) requireNativeScoped(expectedScope, "remote_attachments")
                    acknowledgeMessageId?.let { id ->
                        val saved = store.optJSONObject("web_receipts")?.optJSONObject(scoped("message:$id")) ?: fail("permission_denied")
                        val body = request.getJSONObject("body")
                        if (saved.optString("request_id") != body.getString("request_id") ||
                            saved.optLong("event_sequence", -1) != body.getLong("sequence") ||
                            saved.optString("sender_id") != expectedScope.remoteDeviceId ||
                            saved.optString("generation") != generation) fail("conflict")
                    }
                    if (request.optString("kind") == RemoteDshClient.REQUEST_KIND &&
                        request.getJSONObject("body").optString("operation").startsWith("web.")) {
                        val requests = store.optJSONObject("web_requests") ?: JSONObject().also { store.put("web_requests", it) }
                        val id = scoped(request.getJSONObject("body").getString("request_id"))
                        requests.optString(id).takeIf { it.isNotEmpty() }?.let { if (it != generation) fail("conflict") }
                        if (requests.length() >= 1024 && !requests.has(id)) fail("disk_space_low")
                        requests.put(id, generation)
                        save()
                    }
                    sendMessage(request)
                }
                "list_incoming_messages" -> messages(request)
                "acknowledge_message" -> ackMessage(request)
                else -> fail("unsupported_method")
            }
            // Network success cannot publish data after a concurrent server
            // unpair or rotation. Local selector checks remain on every IO.
            verifyNativeGeneration(expectedScope, capability, generation)
            if (acknowledgeMessageId == null) response else {
                require(response.optString("message_id").isNotEmpty())
                ackMessage(JSONObject().put("message_id", acknowledgeMessageId)).also {
                    require(it.optBoolean("acknowledged"))
                }
            }.also { ensureCurrent() }
        } catch (failure: Throwable) {
            if (failure !is kotlinx.coroutines.CancellationException) {
                val authorizationFailure = failure is AriesClient.AriesException && failure.httpStatus in setOf(403, 404)
                if (authorizationFailure) checkDurableTerminal()
            }
            throw failure
        }
    }

    private fun requireNativeScoped(expectedScope: RemoteDshScope, capability: String) {
        diagnose("scoped.authorization")
        expectedScope.requireCurrent(snapshot(), requireOnline = true)
        if (!paired() || capability !in remoteHostCapabilities) fail("unsupported_method")
        if (capability == "remote_task" && "remote_task" !in remoteHostCapabilities) fail("unsupported_method")
    }

    private fun nativeGeneration(): String = hashText(listOf(node, group, device, binding.optString("peer_id"),
        binding.optString("peer_public_key"), binding.optString("epoch"), hashText(binding.optString("content_key")),
        binding.optString("remote_host_generation")).joinToString("\n"))

    private suspend fun verifyNativeGeneration(expectedScope: RemoteDshScope, capability: String, generation: String) {
        val statusPath = when {
            "group_lifecycle" in nodeCapabilities -> "/membership-status"
            "pairing_unpair" in nodeCapabilities -> "/pairing-status"
            else -> null
        }
        if (statusPath != null) {
            val receipt = get(statusPath)
            if (receipt.optString("group_id") != group || receipt.optString("device_id") != device) fail("protocol_error")
            if (acceptDurableTerminal(receipt, setOf("unpaired", "left", "revoked", "group_closed"))) fail("revoked")
            if (receipt.optString("status") != "active" || statusPath == "/membership-status" &&
                AriesBulkPolicy.sequence(receipt.opt("generation")) != binding.getLong("epoch").toULong()) fail("conflict")
        } else {
            // Old nodes have no durable terminal endpoint. Never clear their
            // keys from an arbitrary error, but require current membership.
            val current = checkedIo { groups.listMembers(group, config) }.getOrThrow().filter { it.active }
            if (current.none { it.deviceId == device && it.publicKey == localPublicKey } ||
                current.none { it.deviceId == expectedScope.remoteDeviceId && it.publicKey == binding.optString("peer_public_key") }) fail("revoked")
        }
        // Recheck the authoritative rotation stream after business IO. Reading
        // the same event twice is safe; no sequence or epoch is inferred.
        var cursor = "0"
        var pages = 0
        do {
            val page = AriesProtocolPolicy.controlEventPage(get("/events?after=$cursor&limit=200"), cursor,
                binding.getLong("epoch").toULong())
            rotationRequired = rotationRequired || page.rotationRequired
            if (page.finished) break
            require(++pages <= 100)
            cursor = page.nextCursor
        } while (true)
        requireNativeScoped(expectedScope, capability)
        if (rotationRequired || nativeGeneration() != generation) fail("conflict")
    }

    /** Native admission is separate from call(): a plugin's JSON scope never enables lock-free IO. */
    suspend fun callNativeBulk(params: JSONObject, expectedScope: RemoteDshScope): JSONObject =
        withContext(Dispatchers.IO) {
            val request = JSONObject(params.toString())
            val operation = AriesBulkPolicy.request(request, expectedScope)
            bulkPermits.withPermit {
                val changed = AtomicBoolean(false)
                val observer = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == null || key in SELECTION_KEYS) changed.set(true)
                }
                prefs.registerOnSharedPreferenceChangeListener(observer)
                var admittedId: String? = null
                try {
                    val selector = AriesProviderScope.selection(prefs.all)
                    val token = serialized {
                        AriesProviderScope.validate("send_encrypted_message", request, selector.groupId)
                        select(selector)
                        refresh()
                        requireBulkScope(expectedScope, operation.capability)
                        if (changed.get()) fail("conflict")
                        val marker = bulkMarker(expectedScope, operation)
                        val id = scoped("message:${operation.idempotencyKey}")
                        val map = store.getJSONObject("mutations")
                        map.optJSONObject(id)?.let { existing ->
                            val previous = existing.optJSONObject("bulk") ?: fail("conflict")
                            if (remoteJsonHash(previous) != remoteJsonHash(marker)) fail("conflict")
                        }
                        val plain = JSONObject().put("kind", request.get("kind")).put("body", request.get("body"))
                            .toString().toByteArray(Charsets.UTF_8)
                        val entry = try {
                            require(plain.size <= 256 * 1024)
                            journal("message:${operation.idempotencyKey}", AriesCrypto.hash(plain), marker) {
                                val ciphertext = withKey { contentKey -> identity.withSeed { seed ->
                                    AriesCrypto.sealContent(seed, contentKey,
                                        envelope("message", "obj_" + hashText(scoped(operation.idempotencyKey))), plain)
                                } }
                                JSONObject().put("epoch", binding.getLong("epoch")).put("ciphertext", ciphertext)
                            }
                        } finally { plain.fill(0) }
                        val prepared = BulkSend(selector, localPublicKey, config.copy(), path("/messages"), id,
                            entry.getString("key"), entry.getString("fingerprint"), entry.getJSONObject("body").toString(),
                            marker.toString(), entry.optJSONObject("response")?.toString())
                        activeBulk[id] = (activeBulk[id] ?: 0) + 1
                        admittedId = id
                        prepared
                    }
                    run {
                        val context = currentCoroutineContext()
                        ensureBulkSelector(token, changed, context)
                        // Only immutable, already-journaled ciphertext crosses this lock boundary.
                        val response = token.cachedResponse?.let(::JSONObject) ?: client.signedMutation(
                            token.config, "POST", token.path, JSONObject(token.body), token.idempotencyKey)
                        ensureBulkSelector(token, changed, context)
                        serialized {
                            // Reload the newest vault: another transfer, ACK or native setting may have saved meanwhile.
                            select(token.selection)
                            val frozen = JSONObject(token.marker)
                            if (binding.optLong("epoch") != frozen.getLong("epoch") ||
                                binding.optString("peer_id") != frozen.getString("peer_id") ||
                                binding.optString("peer_public_key") != frozen.getString("peer_public_key") ||
                                hashText(binding.optString("content_key")) != frozen.getString("key_hash")) fail("conflict")
                            refresh()
                            ensureBulkSelector(token, changed, context)
                            requireBulkScope(expectedScope, operation.capability)
                            if (remoteJsonHash(bulkMarker(expectedScope, operation)) != remoteJsonHash(JSONObject(token.marker)))
                                fail("conflict")
                            val entry = store.getJSONObject("mutations").optJSONObject(token.journalId) ?: fail("conflict")
                            if (entry.optString("fingerprint") != token.fingerprint || entry.optString("key") != token.idempotencyKey ||
                                entry.optJSONObject("body")?.toString() != token.body ||
                                remoteJsonHash(entry.getJSONObject("bulk")) != remoteJsonHash(JSONObject(token.marker))) fail("conflict")
                            val messageId = required(response, "message_id")
                            if (response.has("generation") && AriesBulkPolicy.sequence(response.opt("generation")) != binding.getLong("epoch").toULong())
                                fail("conflict")
                            entry.optJSONObject("response")?.let { if (required(it, "message_id") != messageId) fail("conflict") }
                            entry.put("response", JSONObject(response.toString()))
                            save()
                            ensureBulkSelector(token, changed, context)
                            JSONObject().put("message_id", messageId)
                        }
                    }
                } finally {
                    try {
                        // Cancellation must release bookkeeping even if withContext never delivered its token.
                        admittedId?.let { id -> withContext(NonCancellable) { lock.withLock {
                            val count = activeBulk[id] ?: error("bulk_admission_missing")
                            if (count == 1) activeBulk.remove(id) else activeBulk[id] = count - 1
                        } } }
                    } finally { prefs.unregisterOnSharedPreferenceChangeListener(observer) }
                }
            }
        }

    /** The durable native consumer calls this after applying and ACKing every saved frame. No IPC method exposes it. */
    suspend fun retireTransferReceipt(expectedScope: RemoteDshScope, requestId: String, operation: String): JSONObject = serialized {
        if (operation !in AriesBulkPolicy.operations || !requestId.matches(Regex("[A-Za-z0-9_-]{8,128}")))
            fail("unsupported_method")
        select()
        refresh()
        val request = AriesBulkPolicy.Request(requestId, operation, "dsh-remote-$requestId")
        requireBulkScope(expectedScope, request.capability)
        val id = scoped("message:${request.idempotencyKey}")
        val entry = store.getJSONObject("mutations").optJSONObject(id)
            ?: return@serialized JSONObject().put("retired", true).put("already_retired", true)
        if ((activeBulk[id] ?: 0) != 0) fail("conflict")
        val marker = entry.optJSONObject("bulk") ?: fail("permission_denied")
        if (remoteJsonHash(marker) != remoteJsonHash(bulkMarker(expectedScope, request)) || entry.optJSONObject("response") == null)
            fail("conflict")
        val metadata = bulkReceipts()
        val frames = metadata.keys().asSequence().filter { key ->
            key.startsWith("$scope:") && metadata.getJSONObject(key).optString("request_id") == requestId
        }.toList()
        val receipts = store.getJSONObject("receipts")
        if (frames.isEmpty() || frames.any { !receipts.optBoolean(it) } ||
            frames.none { metadata.getJSONObject(it).optString("type") in setOf("result", "error") }) fail("conflict")
        val terminals = frames.map { metadata.getJSONObject(it) }.filter { it.optString("type") in setOf("result", "error") }
        val terminalSequences = terminals.map { it.getLong("event_sequence") }.distinct()
        if (terminalSequences.size != 1) fail("conflict")
        val terminalSequence = terminalSequences.single()
        if (frames.any { metadata.getJSONObject(it).getLong("event_sequence") > terminalSequence }) fail("conflict")
        val ackOperation = "bulk-ack-$requestId-$operation-$terminalSequence"
        val ackId = scoped("message:$ackOperation")
        val ackEntry = store.getJSONObject("mutations").optJSONObject(ackId)
        if (ackEntry == null || !verifyBulkAcknowledgement(ackEntry, expectedScope, requestId, operation, terminalSequence, ackOperation))
            return@serialized JSONObject().put("retired", false).put("ack_required", true)
        for (key in frames) {
            val frame = metadata.getJSONObject(key)
            if (frame.optString("operation") != operation || frame.optString("peer_id") != expectedScope.remoteDeviceId ||
                frame.optLong("epoch") != binding.getLong("epoch")) fail("conflict")
            AriesBulkPolicy.suppress(binding, AriesBulkPolicy.decimal(frame.getString("sequence")))
        }
        // Keep all task/create/settings/prepare/commit intents and every unrelated or unacknowledged receipt.
        frames.forEach { receipts.remove(it); metadata.remove(it) }
        store.getJSONObject("mutations").remove(id)
        store.getJSONObject("mutations").remove(ackId)
        save()
        JSONObject().put("retired", true).put("acknowledged_messages", frames.size).put("retired_mutations", 2)
    }

    private fun verifyBulkAcknowledgement(entry: JSONObject, expectedScope: RemoteDshScope, requestId: String,
                                          operation: String, sequence: Long, ackOperation: String): Boolean {
        if (entry.optString("key") != "amp-" + hashText(scoped("message:$ackOperation"))) fail("conflict")
        val encrypted = entry.optJSONObject("body") ?: fail("conflict")
        if (encrypted.optLong("epoch") != binding.getLong("epoch")) fail("conflict")
        val envelope = AriesCrypto.readContent(encrypted.getString("ciphertext"))
        context(envelope, "message", device, binding.getLong("epoch"))
        if (envelope.optString("object_id") != "obj_" + hashText(scoped(ackOperation))) fail("conflict")
        val plain = withKey { key -> AriesCrypto.openContent(key, envelope, localPublicKey) }
        try {
            if (AriesCrypto.hash(plain) != entry.optString("fingerprint")) fail("conflict")
            val decoded = JSONObject(plain.toString(Charsets.UTF_8))
            val expected = JSONObject().put("kind", "dsh_remote_ack").put("body", JSONObject()
                .put("protocol", RemoteDshClient.PROTOCOL).put("request_id", requestId)
                .put("target_device_id", expectedScope.remoteDeviceId).put("operation", operation).put("sequence", sequence))
            if (remoteJsonHash(decoded) != remoteJsonHash(expected)) fail("conflict")
        } finally { plain.fill(0) }
        if (!entry.has("response")) return false
        val response = entry.optJSONObject("response") ?: fail("conflict")
        required(response, "message_id")
        if (response.has("generation") && AriesBulkPolicy.sequence(response.opt("generation")) != binding.getLong("epoch").toULong())
            fail("conflict")
        return true
    }

    private data class BulkSend(
        val selection: AriesProviderScope.Selection,
        val publicKey: String,
        val config: AriesClient.Config,
        val path: String,
        val journalId: String,
        val idempotencyKey: String,
        val fingerprint: String,
        val body: String,
        val marker: String,
        val cachedResponse: String?,
    )

    private fun ensureBulkSelector(token: BulkSend, changed: AtomicBoolean, context: kotlin.coroutines.CoroutineContext) {
        context.ensureActive()
        val local = identity.identity()
        if (changed.get() || AriesProviderScope.selection(prefs.all) != token.selection ||
            local.deviceId != token.selection.deviceId || local.publicKeyBase64Url != token.publicKey) fail("conflict")
    }

    private fun requireBulkScope(expectedScope: RemoteDshScope, capability: String) {
        if (!connected) fail("connection_unavailable")
        if (!member || binding.optJSONObject("terminal_receipt") != null) fail("revoked")
        if (rotationRequired) fail("conflict")
        if (!paired()) fail("pairing_incomplete")
        expectedScope.requireCurrent(snapshot(), requireOnline = true)
        if (capability !in remoteHostCapabilities || binding.optString("remote_host_generation").isEmpty()) fail("unsupported_method")
    }

    private fun bulkMarker(expectedScope: RemoteDshScope, request: AriesBulkPolicy.Request) = JSONObject()
        .put("request_id", request.id).put("operation", request.operation).put("scope", expectedScope.toJson())
        .put("endpoint", config.baseUrl).put("public_key", localPublicKey)
        .put("peer_id", binding.getString("peer_id")).put("peer_public_key", binding.getString("peer_public_key"))
        .put("epoch", binding.getLong("epoch")).put("key_hash", hashText(binding.getString("content_key")))
        .put("generation", binding.getString("remote_host_generation"))
        .put("capabilities", JSONArray(remoteHostCapabilities.sorted()))

    private fun bulkReceipts(): JSONObject = store.optJSONObject("bulk_receipts")
        ?: JSONObject().also { store.put("bulk_receipts", it) }

    suspend fun saveApplication(endpoint: String, nodeId: String, groupId: String, applicationId: String) = serialized {
        select()
        requireExpected(endpoint, nodeId, groupId)
        requireSegment(applicationId)
        if (binding.optString("application_id").isNotEmpty() && binding.optString("application_id") != applicationId &&
            binding.optString("content_key").isNotEmpty()) fail("conflict")
        if (binding.optString("application_id") != applicationId) binding.remove("delivery_review")
        binding.put("application_id", applicationId)
        save()
    }

    suspend fun pendingDelivery(): JSONObject? = serialized {
        select(); refresh()
        if (!member || rotationRequired || binding.optString("content_key").isNotEmpty()) return@serialized null
        val application = binding.optString("application_id").takeIf { it.isNotEmpty() } ?: return@serialized null
        checkApplication(application)
        for (delivery in get("/key-deliveries").getJSONArray("items").objects()) {
            if (delivery.optString("recipient_id") != device) continue
            val envelope = validateDelivery(delivery, application)
            val review = JSONObject().put("endpoint", config.baseUrl).put("node_id", node).put("group_id", group)
                .put("application_id", application).put("device_id", device).put("public_key", localPublicKey)
                .put("delivery_id", required(delivery, "id")).put("envelope_hash", hashText(delivery.getString("envelope")))
                .put("sender_id", envelope.getString("sender_id"))
                .put("fingerprint", AriesCrypto.fingerprint(envelope.getString("sender_public_key")))
            binding.put("delivery_review", review)
            save()
            return@serialized JSONObject(review.toString()).also { it.remove("envelope_hash"); it.remove("public_key") }
        }
        binding.remove("delivery_review")
        save()
        null
    }

    /** Fingerprint must be confirmed in native UI, never accepted through plugin RPC. */
    suspend fun confirmDelivery(
        endpoint: String, nodeId: String, groupId: String, applicationId: String,
        deliveryId: String, fingerprint: String,
    ): JSONObject = serialized {
        select()
        requireExpected(endpoint, nodeId, groupId)
        requireSegment(applicationId); requireSegment(deliveryId)
        if (binding.optString("application_id") != applicationId) fail("conflict")
        val review = binding.optJSONObject("delivery_review") ?: fail("conflict")
        if (review.optString("endpoint") != config.baseUrl || review.optString("node_id") != node ||
            review.optString("group_id") != group || review.optString("application_id") != applicationId ||
            review.optString("device_id") != device || review.optString("public_key") != localPublicKey ||
            review.optString("delivery_id") != deliveryId || review.optString("fingerprint") != fingerprint) fail("conflict")
        refresh()
        if (!member || rotationRequired) fail("pairing_incomplete")
        if (binding.optString("content_key").isNotEmpty()) fail("conflict")
        checkApplication(applicationId)
        val delivery = get("/key-deliveries").getJSONArray("items").objects().firstOrNull {
            it.optString("id") == deliveryId
        } ?: fail("conflict")
        if (review.optString("envelope_hash") != hashText(delivery.getString("envelope"))) fail("conflict")
        // Re-read membership and rotation events after fetching the delivery, before accepting its key.
        refresh()
        if (!member || rotationRequired) fail("pairing_incomplete")
        val envelope = validateDelivery(delivery, applicationId)
        require(MessageDigest.isEqual(fingerprint.toByteArray(),
            AriesCrypto.fingerprint(envelope.getString("sender_public_key")).toByteArray()))
        ensureCurrent()
        val key = identity.withSeed { AriesCrypto.openPair(it, envelope) }
        try {
            ensureCurrent()
            binding.put("content_key", AriesCrypto.encode(key)).put("epoch", envelope.getLong("epoch"))
                .put("peer_id", envelope.getString("sender_id")).put("peer_public_key", envelope.getString("sender_public_key"))
                .put("pending_ack", deliveryId)
            binding.remove("delivery_review")
            save()
        } finally { key.fill(0) }
        acknowledgeKey()
        snapshot()
    }

    @Deprecated("Confirmation requires the native UI's reviewed connection scope")
    suspend fun confirmDelivery(deliveryId: String, fingerprint: String): JSONObject = fail("permission_denied")

    private suspend fun <T> serialized(action: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            lock.withLock {
                operationContext = currentCoroutineContext()
                selectionChanged = false
                val observer = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == null || key in SELECTION_KEYS) selectionChanged = true
                }
                prefs.registerOnSharedPreferenceChangeListener(observer)
                try { action() } finally { prefs.unregisterOnSharedPreferenceChangeListener(observer) }
            }
        }

    private fun select(selection: AriesProviderScope.Selection = AriesProviderScope.selection(prefs.all)) {
        selected = selection
        config = AriesClient.Config(selection.endpoint)
        node = selection.nodeId
        group = selection.groupId
        device = selection.deviceId
        localPublicKey = ""
        ensureCurrent()
        if (selection.configured) {
            requireSegment(node); requireSegment(group); requireSegment(device)
            val local = identity.identity()
            if (local.deviceId != device) fail("conflict")
            AriesCrypto.identity(device, local.publicKeyBase64Url)
            localPublicKey = local.publicKeyBase64Url
        }
        scope = AriesCrypto.hash(listOf(config.baseUrl, node, group, device).joinToString("\n").toByteArray())
        store = if (selection.configured) vault.load() else JSONObject().put("bindings", JSONObject())
        val bindings = store.getJSONObject("bindings")
        binding = bindings.optJSONObject(scope) ?: JSONObject().also { bindings.put(scope, it) }
        connected = false; member = false; rotationRequired = false
        nodeCapabilities = emptySet()
        remoteHostCapabilities = emptySet()
        members = emptyList(); presence = emptyList()
        ensureCurrent()
    }

    private suspend fun refresh() {
        if (!selected.configured) return
        connected = false; member = false
        members = emptyList(); presence = emptyList()
        val actualNode = checkedIo { client.node(config) }.also { diagnose("refresh.node", it.exceptionOrNull()) }.getOrNull() ?: return
        connected = actualNode.nodeId != null && actualNode.nodeId == node
        if (!connected || group.isEmpty() || device.isEmpty()) return
        nodeCapabilities = actualNode.capabilities
        val now = System.currentTimeMillis()
        if (lastHeartbeatAtMillis == 0L || now < lastHeartbeatAtMillis ||
            now - lastHeartbeatAtMillis >= heartbeatIntervalMillis) {
            // heartbeat() performs the same signed node pin check as pairing. A
            // failed refresh is diagnostic only: it cannot erase a confirmed
            // relationship or turn an authorization failure into online state.
            val heartbeat = checkedIo {
                client.heartbeat(config, clientVersion = "android", expectedNodeId = node)
            }
            diagnose("refresh.heartbeat", heartbeat.exceptionOrNull())
            if (heartbeat.isSuccess) lastHeartbeatAtMillis = System.currentTimeMillis()
        }
        val fetched = checkedIo { groups.listMembers(group, config) }
        diagnose("refresh.members", fetched.exceptionOrNull())
        if (fetched.isFailure) {
            val error = fetched.exceptionOrNull()
            if (error is AriesClient.AriesException && error.httpStatus in setOf(403, 404)) {
                checkDurableTerminal()
                return
            }
            connected = false; return
        }
        members = fetched.getOrThrow().filter { it.active }
        members.forEach { AriesCrypto.identity(it.deviceId, it.publicKey) }
        member = members.any { it.deviceId == device && it.publicKey == localPublicKey }
        if (!member) return
        val fetchedPresence = checkedIo { groups.getPresence(group, config) }
        presence = fetchedPresence.getOrNull().orEmpty()
        diagnose("refresh.presence", fetchedPresence.exceptionOrNull())
        // Without an authoritative control-event check, old content keys must not publish.
        var cursor = "0"
        var pages = 0
        val keyEpoch = if (binding.has("epoch")) requireNotNull(binding.opt("epoch")?.toString()?.toULongOrNull()) else 0uL
        do {
            val page = AriesProtocolPolicy.controlEventPage(get("/events?after=$cursor&limit=200"),
                cursor, keyEpoch)
            rotationRequired = rotationRequired || page.rotationRequired
            if (page.finished) break
            require(++pages <= 100)
            cursor = page.nextCursor
        } while (true)
        if (!rotationRequired && binding.optString("content_key").isNotEmpty() &&
            members.any { it.deviceId == binding.optString("peer_id") && it.publicKey == binding.optString("peer_public_key") })
            acknowledgeKey()
        if (!binding.optBoolean("relationship_confirmed") && !rotationRequired &&
            binding.optString("content_key").isNotEmpty() && binding.optString("pending_ack").isEmpty() &&
            "group_key_confirmation" in nodeCapabilities) {
            // Upgrade old local bindings only with a current-epoch, exact-recipient server proof.
            val self = get("/members").getJSONArray("items").objects().firstOrNull {
                it.optString("device_id") == device && it.optString("public_key") == localPublicKey
            }
            val deliveryId = self?.optString("key_delivery_id").orEmpty()
            val oldAck = store.getJSONObject("mutations").optJSONObject(scoped("key-ack:$deliveryId"))
                ?.optJSONObject("response")
            if (self != null && self.optString("key_confirmation_status") == "acknowledged" &&
                self.optString("key_recipient_id") == device && self.optLong("key_epoch") == binding.optLong("epoch") &&
                deliveryId.isNotEmpty() && oldAck?.optString("delivery_id") == deliveryId &&
                oldAck.optString("status") == "acknowledged") {
                binding.put("relationship_confirmed", true).put("confirmed_delivery_id", deliveryId)
                save()
            }
        }
        if (member) {
            val peer = members.firstOrNull { it.deviceId == binding.optString("peer_id") &&
                it.publicKey == binding.optString("peer_public_key") }
            if (peer != null) {
                val cached = JSONObject().put("id", peer.deviceId).put("name", peer.deviceName ?: JSONObject.NULL)
                    .put("public_key", peer.publicKey).put("role", peer.role).put("active", true)
                    .put("online", "unknown").put("last_seen_at", JSONObject.NULL)
                if (binding.optJSONObject("cached_peer")?.toString() != cached.toString()) {
                    binding.put("cached_peer", cached)
                    save()
                }
            }
            discoverRemoteHost()
        }
    }

    /** Capability announcements are authenticated by the current group key and sender identity. */
    private suspend fun discoverRemoteHost() {
        if (!paired()) return
        val response = messages(JSONObject().put("cursor", binding.optString("capability_cursor", "0")))
        val messages = response.optJSONArray("messages") ?: return
        var changed = false
        for (index in 0 until messages.length()) {
            val message = messages.getJSONObject(index)
            if (message.optString("kind") != RemoteDshClient.CAPABILITY_KIND ||
                message.optString("sender_device_id") != binding.optString("peer_id")) continue
            val body = message.optJSONObject("body") ?: continue
            if (body.optString("protocol") != RemoteDshClient.PROTOCOL ||
                body.optString("device_id") != binding.optString("peer_id")) continue
            val capabilities = body.optJSONArray("capabilities") ?: continue
            val values = (0 until capabilities.length()).mapNotNull { capabilities.optString(it).takeIf { value ->
                value in setOf("remote_task", "remote_directory", "remote_settings", "remote_web_tunnel", "remote_workspace_lifecycle",
                    "remote_archive_delete", "remote_artifacts", "remote_attachments")
            } }.toSet()
            remoteHostCapabilities = values
            binding.put("remote_host_capabilities", JSONArray(values.toList()))
            binding.put("remote_host_generation", body.optString("generation"))
            ackMessage(JSONObject().put("message_id", message.getString("message_id")))
            changed = true
        }
        // This cursor belongs only to capability discovery. Unknown messages
        // remain unacknowledged and other consumers have independent cursors.
        response.optString("next_cursor").takeIf { it.isNotEmpty() && it != "null" }?.let {
            if (binding.optString("capability_cursor") != it) {
                binding.put("capability_cursor", it)
                changed = true
            }
        }
        if (changed) save()
        if (remoteHostCapabilities.isEmpty()) {
            val cached = binding.optJSONArray("remote_host_capabilities") ?: return
            remoteHostCapabilities = (0 until cached.length()).map { cached.getString(it) }.toSet()
        }
    }

    /** Confirmation is durable; reachability and current execution authorization are separate. */
    private fun confirmedRelationship(): Boolean = binding.optBoolean("relationship_confirmed") &&
        binding.optJSONObject("terminal_receipt") == null && binding.optString("content_key").isNotEmpty() &&
        binding.optString("pending_ack").isEmpty()

    private fun paired(): Boolean =
        connected && member && !rotationRequired && confirmedRelationship() && members.any {
                it.deviceId == binding.optString("peer_id") && it.publicKey == binding.optString("peer_public_key")
            }

    /** A native UI operation. It is intentionally absent from the plugin's IPC method allowlist. */
    suspend fun unpair(endpoint: String, nodeId: String, groupId: String): JSONObject = serialized {
        select()
        requireExpected(endpoint, nodeId, groupId)
        refresh()
        if (!connected) fail("connection_unavailable")
        if ("pairing_unpair" !in nodeCapabilities) fail("unsupported_method")
        binding.optJSONObject("terminal_receipt")?.let { return@serialized JSONObject(it.toString()) }
        val receipt = mutation("/unpair", "unpair", hashText("{}")) { JSONObject() }
        if (!acceptDurableTerminal(receipt, setOf("unpaired"))) fail("protocol_error")
        JSONObject(receipt.toString())
    }

    private suspend fun checkDurableTerminal() {
        val suffix = when {
            "group_lifecycle" in nodeCapabilities -> "/membership-status"
            "pairing_unpair" in nodeCapabilities -> "/pairing-status"
            else -> return
        }
        // An arbitrary 404, timeout, permission error or stale response never clears credentials.
        val receipt = checkedIo { client.signedGet(config, path(suffix)) { it } }.getOrNull() ?: return
        acceptDurableTerminal(receipt, setOf("unpaired", "left", "revoked", "group_closed"))
    }

    private fun acceptDurableTerminal(receipt: JSONObject, statuses: Set<String>): Boolean {
        ensureCurrent()
        if (receipt.optString("group_id") != group || receipt.optString("device_id") != device ||
            receipt.optString("status") !in statuses) return false
        binding.put("terminal_receipt", JSONObject(receipt.toString()))
        diagnose("authorization.terminal")
        listOf("content_key", "epoch", "pending_ack", "delivery_review", "relationship_confirmed")
            .forEach(binding::remove)
        // Stop local content operations while preserving device identity and the node pin.
        member = false
        rotationRequired = false
        save()
        return true
    }

    suspend fun savedApplication(): String? = serialized {
        select()
        ensureCurrent()
        binding.optString("application_id").takeIf { it.isNotEmpty() }
    }

    private suspend fun acknowledgeKey() {
        val id = binding.optString("pending_ack")
        if (id.isEmpty()) return
        ensureCurrent()
        if (!connected || !member || rotationRequired ||
            members.none { it.deviceId == binding.optString("peer_id") && it.publicKey == binding.optString("peer_public_key") })
            fail("revoked")
        val receipt = mutation("/key-deliveries/${segment(id)}/ack", "key-ack:$id", hashText("{}")) { JSONObject() }
        if (receipt.optString("delivery_id") != id || receipt.optString("status") != "acknowledged")
            fail("protocol_error")
        binding.remove("pending_ack")
        binding.put("relationship_confirmed", true).put("confirmed_delivery_id", id)
        save()
    }

    private fun validateDelivery(delivery: JSONObject, application: String): JSONObject {
        val e = AriesCrypto.readPair(delivery.getString("envelope"))
        require(delivery.getString("group_id") == group && delivery.getString("recipient_id") == device &&
            e.getString("node_id") == node && e.getString("group_id") == group && e.getString("application_id") == application &&
            e.getString("recipient_id") == device && e.getString("recipient_public_key") == localPublicKey &&
            e.getString("sender_id") == delivery.getString("sender_id") && e.getLong("epoch") == delivery.getLong("epoch") &&
            e.getLong("epoch") > 0 && e.getString("sender_public_key") == senderKey(e.getString("sender_id")))
        return e
    }

    private fun snapshot(): JSONObject {
        ensureCurrent()
        val paired = paired()
        val confirmed = confirmedRelationship()
        fun describe(m: AriesGroupStore.Member): JSONObject {
            val p = presence.firstOrNull { it.deviceId == m.deviceId }
            return JSONObject().put("id", m.deviceId).put("name", m.deviceName ?: JSONObject.NULL)
                .put("public_key", m.publicKey).put("role", m.role).put("active", true)
                .put("online", if (m.deviceId == device || !connected || p == null) "unknown" else if (p.online) "online" else "offline")
                .put("last_seen_at", p?.lastSeenAt ?: JSONObject.NULL)
        }
        return JSONObject().put("protocol", LocalPluginBridge.PROTOCOL).put("server_connected", connected)
            .put("node_id", node.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
            .put("group_id", group.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
            .put("pairing_status", when { binding.optJSONObject("terminal_receipt") != null -> "revoked"
                paired || (!connected && confirmed) -> "paired"; !connected -> "unknown"
                member -> "awaiting_approval"; confirmed -> "unknown"; else -> "unpaired" })
            .put("relationship_confirmed", confirmed)
            .put("terminal_receipt", binding.optJSONObject("terminal_receipt") ?: JSONObject.NULL)
            .put("local_device", JSONObject().put("id", device)
                .put("name", members.firstOrNull { it.deviceId == device }?.deviceName ?: JSONObject.NULL)
                .put("public_key", localPublicKey.takeIf { it.isNotEmpty() } ?: JSONObject.NULL))
            .put("remote_device", members.firstOrNull { it.deviceId == binding.optString("peer_id") }?.let(::describe)
                ?: if (confirmed) binding.optJSONObject("cached_peer") ?: JSONObject.NULL else JSONObject.NULL)
            .put("members", JSONArray(members.map(::describe)))
            .put("capabilities", JSONObject().put("workspace_sync", false).put("web_broadcast", false)
                .put("encrypted_messages", paired).put("encrypted_files", paired).put("presence", member)
                .put("file_cache_cleanup", paired).put("message_acknowledgement", "local_durable")
                .put("state_subscription", "poll").put("max_plaintext_chunk_bytes", CHUNK_LIMIT)
                .put("max_file_bytes", FILE_LIMIT).put("file_create", "local_staging_until_complete")
                // Only the authenticated peer's actual 0.3.0 host announcement
                // enables remote execution; Aries is an opaque relay.
                .put("remote_task", paired && "remote_task" in remoteHostCapabilities)
                // Native coordinator/store apply bounded actual directory and
                // settings receipts. Both still require the authenticated host
                // advertisement; Aries node capabilities cannot enable them.
                .put("remote_directory", paired && "remote_directory" in remoteHostCapabilities)
                .put("remote_settings", paired && "remote_settings" in remoteHostCapabilities)
                .put("remote_workspace_lifecycle", paired && "remote_workspace_lifecycle" in remoteHostCapabilities)
                .put("remote_archive_delete", paired && "remote_archive_delete" in remoteHostCapabilities)
                .put("remote_artifacts", paired && "remote_artifacts" in remoteHostCapabilities)
                .put("remote_attachments", paired && "remote_attachments" in remoteHostCapabilities)
                // Web framing, local proxy and end-to-end authentication are
                // not installed yet. Never infer this from remote_task.
                .put("remote_web_tunnel", false))
    }

    private fun requireExpected(endpoint: String, nodeId: String, groupId: String) {
        ensureCurrent()
        if (!selected.configured || SettingsContract.normalizeAriesEndpoint(endpoint) != config.baseUrl ||
            nodeId != node || groupId != group) fail("conflict")
    }

    private fun ensureCurrent() {
        operationContext.ensureActive()
        if (selectionChanged || AriesProviderScope.selection(prefs.all) != selected) fail("conflict")
        if (localPublicKey.isNotEmpty()) {
            val local = identity.identity()
            if (local.deviceId != device || local.publicKeyBase64Url != localPublicKey) fail("conflict")
        }
    }

    private suspend fun <T> checkedIo(action: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        ensureCurrent()
        return try { action() } finally {
            ensureCurrent()
            currentCoroutineContext().ensureActive()
        }
    }

    private suspend fun checkApplication(application: String) {
        val detail = checkedIo { groups.getApplication(group, application, config) }.getOrThrow()
        if (detail.applicationId != application || detail.deviceId != device || detail.publicKey != localPublicKey ||
            detail.status !in setOf("awaiting_key", "approved")) fail("permission_denied")
    }

    private fun save() {
        ensureCurrent()
        val previous = vault.load()
        ensureCurrent()
        vault.save(store)
        // UI preference writes do not take the provider mutex. Roll back a write overlapping a switch.
        try { ensureCurrent() } catch (error: Throwable) {
            try { vault.save(previous) } catch (rollbackError: Throwable) { error.addSuppressed(rollbackError) }
            throw error
        }
    }
    private fun scoped(id: String) = "$scope:$id"
    private fun senderKey(id: String) = members.firstOrNull { it.deviceId == id }?.publicKey ?: fail("permission_denied")
    private fun envelope(type: String, id: String) = AriesCrypto.envelope(type, node, group, binding.getLong("epoch"), device, id)
    private fun key() = AriesCrypto.decode(required(binding, "content_key"), 32)
    private inline fun <T> withKey(action: (ByteArray) -> T): T {
        val key = key(); return try { action(key) } finally { key.fill(0) }
    }
    private fun context(e: JSONObject, type: String, sender: String, epoch: Long) {
        require(e.getString("type") == type && e.getString("node_id") == node && e.getString("group_id") == group &&
            e.getString("sender_id") == sender && e.getLong("epoch") == epoch && epoch == binding.getLong("epoch"))
    }
    private suspend fun get(suffix: String) = checkedIo { client.signedGet(config, path(suffix)) { it } }.getOrThrow()
    private fun path(suffix: String) = "/api/v1/public/groups/${segment(group)}$suffix"
    private fun journal(operation: String, fingerprint: String, bulk: JSONObject? = null, body: () -> JSONObject): JSONObject {
        val map = store.getJSONObject("mutations")
        val id = scoped(operation)
        map.optJSONObject(id)?.let { if (it.getString("fingerprint") != fingerprint) fail("conflict"); return it }
        if (map.length() >= 1024) fail("disk_space_low")
        val entry = JSONObject().put("fingerprint", fingerprint).put("key", "amp-" + hashText(id)).put("body", body())
        bulk?.let { entry.put("bulk", it) }
        map.put(id, entry); save(); return entry
    }
    private suspend fun mutation(suffix: String, operation: String, fingerprint: String, body: () -> JSONObject): JSONObject {
        val entry = journal(operation, fingerprint, body = body)
        entry.optJSONObject("response")?.let { return it }
        val result = checkedIo { client.signedMutation(config, "POST", path(suffix), entry.getJSONObject("body"), entry.getString("key")) }
        entry.put("response", result); save(); return result
    }
    private suspend fun sendMessage(p: JSONObject): JSONObject {
        val operation = required(p, "idempotency_key")
        require(p.has("body"))
        val plain = JSONObject().put("kind", required(p, "kind")).put("body", p.get("body")).toString().toByteArray()
        try {
            require(plain.size <= 256 * 1024)
            val result = mutation("/messages", "message:$operation", AriesCrypto.hash(plain)) {
                val encrypted = withKey { key -> identity.withSeed {
                    AriesCrypto.sealContent(it, key, envelope("message", "obj_" + hashText(scoped(operation))), plain)
                } }
                JSONObject().put("epoch", binding.getLong("epoch")).put("ciphertext", encrypted)
            }
            return JSONObject().put("message_id", required(result, "message_id"))
        } finally { plain.fill(0) }
    }
    private suspend fun messages(p: JSONObject): JSONObject {
        val cursor = p.optString("cursor").ifEmpty { "0" }
        require(cursor.matches(Regex("[0-9]{1,20}")) && cursor.toULongOrNull() != null)
        val limit = p.optInt("limit", 4); require(limit in 1..200)
        val page = get("/messages?after=$cursor&limit=${minOf(limit, 4)}")
        val items = AriesBulkPolicy.validatePage(page, cursor)
        val results = JSONArray(); val receipts = store.getJSONObject("receipts")
        withKey { key ->
            for (item in items.objects()) {
                require(item.getString("group_id") == group)
                val sender = required(item, "sender_id"); val id = required(item, "id")
                val sequence = AriesBulkPolicy.sequence(item.opt("sequence"))
                // Own messages have never been delivered by this Provider. Authenticate
                // current-epoch content before persisting its exact suppressed sequence.
                // Old own epochs keep the previous skip behavior without gaining a range.
                if (sender == device) {
                    if (item.getLong("epoch") == binding.getLong("epoch")) {
                        val own = AriesCrypto.readContent(item.getString("ciphertext"))
                        context(own, "message", device, binding.getLong("epoch"))
                        val plain = AriesCrypto.openContent(key, own, localPublicKey)
                        try { require(plain.size <= 256 * 1024) } finally { plain.fill(0) }
                        AriesBulkPolicy.suppress(binding, sequence)
                    }
                    continue
                }
                if (AriesBulkPolicy.suppressed(binding, sequence) || receipts.optBoolean(scoped("message:$id"))) continue
                val e = AriesCrypto.readContent(item.getString("ciphertext"))
                context(e, "message", sender, item.getLong("epoch"))
                val plain = AriesCrypto.openContent(key, e, senderKey(sender))
                try {
                    require(plain.size <= 256 * 1024)
                    val decoded = JSONObject(String(plain, Charsets.UTF_8))
                    if (receipts.length() >= 4096 && !receipts.has(scoped("message:$id"))) fail("disk_space_low")
                    results.put(JSONObject().put("message_id", id).put("kind", required(decoded, "kind"))
                        .put("body", decoded.get("body")).put("sender_device_id", sender).put("created_at", item.getString("created_at")))
                    rememberBulkFrame(decoded, sender, id, sequence)
                    rememberWebFrame(decoded, sender, id)
                    receipts.put(scoped("message:$id"), false)
                } finally { plain.fill(0) }
            }
        }
        save()
        return JSONObject().put("messages", results).put("next_cursor", page.optString("next_cursor"))
    }
    private fun ackMessage(p: JSONObject): JSONObject {
        val id = scoped("message:" + required(p, "message_id"))
        val receipts = store.getJSONObject("receipts")
        if (!receipts.has(id)) fail("permission_denied")
        receipts.put(id, true)
        save()
        return JSONObject().put("acknowledged", true).put("acknowledgement_scope", "local_durable")
    }

    private fun rememberWebFrame(decoded: JSONObject, sender: String, messageId: String) {
        if (decoded.optString("kind") != RemoteDshClient.EVENT_KIND || sender != binding.optString("peer_id")) return
        val body = decoded.optJSONObject("body") ?: return
        if (store.optJSONObject("web_requests")?.optString(scoped(body.optString("request_id"))) != nativeGeneration()) return
        val sequence = body.opt("sequence")
        if (body.optString("protocol") != RemoteDshClient.PROTOCOL || body.optString("target_device_id") != device ||
            !body.optString("request_id").matches(Regex("[A-Za-z0-9_-]{8,128}")) ||
            sequence !is Number || sequence.toDouble() != sequence.toLong().toDouble() || sequence.toLong() !in 0..1025) return
        val metadata = store.optJSONObject("web_receipts") ?: JSONObject().also { store.put("web_receipts", it) }
        val id = scoped("message:$messageId")
        if (metadata.length() >= 4096 && !metadata.has(id)) fail("disk_space_low")
        val receipt = JSONObject().put("request_id", body.getString("request_id")).put("event_sequence", sequence.toLong())
            .put("sender_id", sender).put("generation", nativeGeneration())
        metadata.optJSONObject(id)?.let { if (it.toString() != receipt.toString()) fail("conflict") }
        metadata.put(id, receipt)
    }

    private fun rememberBulkFrame(decoded: JSONObject, sender: String, messageId: String, sequence: ULong) {
        if (decoded.optString("kind") != RemoteDshClient.EVENT_KIND || sender != binding.optString("peer_id")) return
        val body = decoded.optJSONObject("body") ?: return
        val requestId = body.optString("request_id")
        if (body.optString("protocol") != RemoteDshClient.PROTOCOL || body.optString("target_device_id") != device ||
            !requestId.matches(Regex("[A-Za-z0-9_-]{8,128}")) || body.optString("type") !in setOf("accepted", "result", "error", "closed")) return
        val eventSequence = (body.opt("sequence") as? Number)?.let { number ->
            number.toLong().takeIf { it in 0..1025L && number.toDouble() == it.toDouble() }
        } ?: return
        val marker = store.getJSONObject("mutations").optJSONObject(scoped("message:dsh-remote-$requestId"))
            ?.optJSONObject("bulk") ?: return
        if (marker.optString("request_id") != requestId || marker.optString("operation") !in AriesBulkPolicy.operations ||
            marker.optString("peer_id") != sender || marker.optLong("epoch") != binding.getLong("epoch")) return
        val metadata = bulkReceipts()
        val id = scoped("message:$messageId")
        val frame = JSONObject().put("request_id", requestId).put("operation", marker.getString("operation"))
            .put("peer_id", sender).put("epoch", binding.getLong("epoch")).put("sequence", sequence.toString())
            .put("event_sequence", eventSequence).put("type", body.getString("type"))
        metadata.optJSONObject(id)?.let { if (remoteJsonHash(it) != remoteJsonHash(frame)) fail("conflict") }
        if (!metadata.has(id) && metadata.length() >= 4096) fail("disk_space_low")
        metadata.put(id, frame)
    }

    private fun manifest(p: JSONObject): JSONObject {
        val relative = p.optString("relative_path").ifEmpty { required(p, "file_id") }
        relativePath(relative)
        val size = p.getLong("size"); val chunk = p.optInt("chunk_size", CHUNK_LIMIT)
        require(size in 0..FILE_LIMIT && chunk in 1..CHUNK_LIMIT)
        val count = maxOf(1, ((size + chunk - 1) / chunk).toInt()); require(count <= 65536)
        return JSONObject().put("relative_path", relative).put("size", size).put("chunk_size", chunk)
            .put("chunk_count", count).put("hash", requireHash(p, "hash"))
    }
    private fun validateManifest(m: JSONObject) {
        relativePath(required(m, "relative_path"))
        val size = m.getLong("size"); val chunk = m.getInt("chunk_size"); val count = m.getInt("chunk_count")
        require(size in 0..FILE_LIMIT && chunk in 1..CHUNK_LIMIT && count in 1..65536 &&
            count.toLong() == maxOf(1L, (size + chunk - 1) / chunk))
        requireHash(m, "hash")
    }
    private fun createFile(p: JSONObject): JSONObject {
        val op = required(p, "idempotency_key"); val m = manifest(p)
        val targets = p.optJSONArray("targets")?.let { a -> (0 until a.length()).map { a.getString(it) } }
            ?: members.filter { it.deviceId != device }.map { it.deviceId }
        val sorted = targets.distinct().sorted()
        require(sorted.isNotEmpty() && sorted.size <= 200 && sorted.all { it != device && members.any { m -> m.deviceId == it } })
        val ttl = p.optInt("ttl_seconds", 604800); require(ttl in 1..604800)
        val id = "amt_" + hashText(scoped("file:$op"))
        journal("file:$op", hashText(m.toString() + "\n" + sorted.joinToString("\n") + "\n$ttl")) { JSONObject().put("local_id", id) }
        val transfers = store.getJSONObject("transfers")
        val t = transfers.optJSONObject(scoped(id)) ?: run {
            if (transfers.length() >= 256) fail("disk_space_low")
            val encoded = withKey { k -> identity.withSeed { AriesCrypto.sealContent(it, k, envelope("file", id), m.toString().toByteArray()) } }
            JSONObject().put("local_id", id).put("server_id", "").put("node_id", node).put("group_id", group)
                .put("epoch", binding.getLong("epoch")).put("sender_id", device).put("envelope", encoded).put("manifest", m)
                .put("targets", JSONArray(sorted)).put("ttl_seconds", ttl).put("outgoing", true)
                .put("completed", false).put("acknowledged", false).put("chunks", JSONObject()).put("uploaded", JSONObject())
                .also { transfers.put(scoped(id), it); save() }
        }
        return describe(t)
    }
    private fun describe(t: JSONObject): JSONObject {
        val m = t.getJSONObject("manifest")
        val id = if (t.getBoolean("outgoing")) t.getString("local_id") else t.getString("server_id")
        return JSONObject().put("transfer_id", id).put("file_id", id).put("server_file_id", t.optString("server_id").ifEmpty { null } ?: JSONObject.NULL)
            .put("group_id", group).put("relative_path", m.getString("relative_path")).put("size", m.getLong("size"))
            .put("chunk_size", m.getInt("chunk_size")).put("chunk_count", m.getInt("chunk_count")).put("hash", m.getString("hash"))
            .put("sender_device_id", t.getString("sender_id")).put("completed", t.getBoolean("completed"))
            .put("acknowledged", t.getBoolean("acknowledged"))
            .put("received_chunks", JSONArray(t.getJSONObject("chunks").keys().asSequence().map(String::toInt).sorted().toList()))
    }
    private fun transfer(p: JSONObject, outgoing: Boolean): JSONObject {
        val id = p.optString("transfer_id").ifEmpty { required(p, "file_id") }
        val t = store.getJSONObject("transfers").optJSONObject(scoped(id)) ?: fail("permission_denied")
        require(t.getBoolean("outgoing") == outgoing && t.getString("node_id") == node && t.getString("group_id") == group)
        if (t.getLong("epoch") != binding.getLong("epoch")) fail("conflict")
        return t
    }
    private fun index(p: JSONObject, t: JSONObject): Int =
        p.getInt("chunk_index").also { require(it in 0 until t.getJSONObject("manifest").getInt("chunk_count")) }
    private fun plainLength(t: JSONObject, i: Int): Int {
        val m = t.getJSONObject("manifest")
        return minOf(m.getInt("chunk_size").toLong(), maxOf(0L, m.getLong("size") - i.toLong() * m.getInt("chunk_size"))).toInt()
    }
    private fun chunkPath(t: JSONObject, index: Int): File =
        File(vault.directory, "chunks/" + hashText(scoped(
            if (t.getBoolean("outgoing")) "out:" + t.getString("local_id") else "in:" + t.getString("server_id")
        )) + "/$index.bin")
    private fun storeChunk(t: JSONObject, i: Int, raw: ByteArray, hash: String) {
        ensureCurrent()
        val path = chunkPath(t, i)
        val used = File(vault.directory, "chunks").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        if (used + raw.size - path.length() > CACHE_LIMIT) fail("disk_space_low")
        AriesProviderVault.write(path, raw)
        ensureCurrent()
        t.getJSONObject("chunks").put(i.toString(), hash); save()
    }
    private fun upload(p: JSONObject): JSONObject {
        val t = transfer(p, true); val i = index(p, t)
        val text = p.getString("bytes"); require(text.length <= 5600000)
        val plain = Base64.getDecoder().decode(text)
        try {
            require(plain.size == plainLength(t, i))
            if (p.has("chunk_count")) require(p.getInt("chunk_count") == t.getJSONObject("manifest").getInt("chunk_count"))
            val hash = AriesCrypto.hash(plain); if (p.has("hash")) require(requireHash(p, "hash") == hash)
            val saved = t.getJSONObject("chunks").optString(i.toString())
            if (saved.isNotEmpty()) { if (saved != hash) fail("conflict") }
            else {
                if (t.getBoolean("completed")) fail("conflict")
                val raw = withKey { k -> identity.withSeed { AriesCrypto.sealChunk(it, k, AriesCrypto.readContent(t.getString("envelope")), i, plain) } }
                storeChunk(t, i, raw, hash)
            }
            return JSONObject().put("accepted", true).put("chunk_index", i)
        } finally { plain.fill(0) }
    }
    private fun verifyFile(t: JSONObject) {
        val m = t.getJSONObject("manifest"); val chunks = t.getJSONObject("chunks")
        if (chunks.length() != m.getInt("chunk_count")) fail("conflict")
        val digest = MessageDigest.getInstance("SHA-256")
        withKey { k ->
            for (i in 0 until m.getInt("chunk_count")) {
                val plain = AriesCrypto.openChunk(k, AriesCrypto.readContent(t.getString("envelope")), senderKey(t.getString("sender_id")),
                    i, chunkPath(t, i).readBytes())
                try {
                    require(plain.size == plainLength(t, i) && AriesCrypto.hash(plain) == chunks.getString(i.toString()))
                    digest.update(plain)
                } finally { plain.fill(0) }
            }
        }
        require(hex(digest.digest()) == m.getString("hash"))
    }
    private suspend fun complete(p: JSONObject): JSONObject {
        val t = transfer(p, true)
        if (!t.getBoolean("completed")) {
            verifyFile(t)
            val m = t.getJSONObject("manifest"); val digest = MessageDigest.getInstance("SHA-256")
            for (i in 0 until m.getInt("chunk_count")) digest.update(chunkPath(t, i).readBytes())
            val body = JSONObject().put("epoch", t.getLong("epoch")).put("encrypted_metadata", t.getString("envelope"))
                .put("size_bytes", m.getLong("size") + m.getInt("chunk_count").toLong() * 92)
                .put("chunk_size_bytes", m.getInt("chunk_size") + 92).put("sha256", hex(digest.digest()))
                .put("ttl_seconds", t.getInt("ttl_seconds")).put("targets", t.getJSONArray("targets"))
            val id = t.getString("local_id")
            val created = mutation("/file-transfers", "create:$id", hashText(body.toString())) { body }
            t.put("server_id", required(created, "file_id")); save()
            for (i in 0 until m.getInt("chunk_count")) {
                if (t.getJSONObject("uploaded").optBoolean(i.toString())) continue
                checkedIo { client.signedRaw(config, "PUT", path("/file-transfers/${segment(t.getString("server_id"))}/chunks/$i"),
                    chunkPath(t, i).readBytes(), "amp-" + hashText(scoped("chunk:$id:$i"))) }
                t.getJSONObject("uploaded").put(i.toString(), true); save()
            }
            mutation("/file-transfers/${segment(t.getString("server_id"))}/complete", "complete:$id", hashText("{}")) { JSONObject() }
            t.put("completed", true); save()
        }
        return JSONObject().put("completed", true).put("transfer_id", t.getString("local_id")).put("server_file_id", t.getString("server_id"))
    }
    private suspend fun files(p: JSONObject): JSONObject {
        val cursor = p.optString("cursor").ifEmpty { "0" }; require(cursor.matches(Regex("[0-9]{1,10}")))
        val offset = cursor.toInt(); val limit = p.optInt("limit", 100); require(offset >= 0 && limit in 1..200)
        val page = get("/file-transfers?offset=$offset&limit=$limit"); val result = JSONArray()
        val transfers = store.getJSONObject("transfers")
        withKey { k ->
            for (item in page.getJSONArray("items").objects()) {
                require(item.getString("group_id") == group)
                if (item.getString("status") != "available" || item.getString("sender_id") == device) continue
                val sender = required(item, "sender_id"); val id = required(item, "id"); val encoded = item.getString("encrypted_metadata")
                require(encoded.length <= 16384)
                val e = AriesCrypto.readContent(encoded); context(e, "file", sender, item.getLong("epoch"))
                val plain = AriesCrypto.openContent(k, e, senderKey(sender))
                val m = try { JSONObject(String(plain, Charsets.UTF_8)) } finally { plain.fill(0) }
                validateManifest(m)
                require(item.getLong("size_bytes") == m.getLong("size") + m.getInt("chunk_count").toLong() * 92 &&
                    item.getInt("chunk_size_bytes") == m.getInt("chunk_size") + 92 && item.getInt("chunk_count") == m.getInt("chunk_count"))
                val t = transfers.optJSONObject(scoped(id)) ?: run {
                    if (transfers.length() >= 256) fail("disk_space_low")
                    JSONObject().put("local_id", e.getString("object_id")).put("server_id", id).put("node_id", node)
                        .put("group_id", group).put("sender_id", sender).put("epoch", e.getLong("epoch")).put("envelope", encoded)
                        .put("manifest", m).put("outgoing", false).put("completed", true).put("acknowledged", false)
                        .put("chunks", JSONObject()).also { transfers.put(scoped(id), it) }
                }
                require(t.getString("envelope") == encoded)
                if (!t.getBoolean("acknowledged")) result.put(describe(t))
            }
        }
        save()
        val next = page.optInt("next_offset", 0); require(next == 0 || next > offset)
        return JSONObject().put("transfers", result).put("next_cursor", if (next == 0) JSONObject.NULL else next.toString())
    }
    private suspend fun download(p: JSONObject): JSONObject {
        val t = transfer(p, false); val i = index(p, t)
        val raw = checkedIo { client.signedRaw(config, "GET", path("/file-transfers/${segment(t.getString("server_id"))}/chunks/$i")) }
        val plain = withKey { k -> AriesCrypto.openChunk(k, AriesCrypto.readContent(t.getString("envelope")), senderKey(t.getString("sender_id")), i, raw) }
        try {
            require(plain.size == plainLength(t, i))
            val hash = AriesCrypto.hash(plain); storeChunk(t, i, raw, hash)
            if (t.getJSONObject("chunks").length() == t.getJSONObject("manifest").getInt("chunk_count")) verifyFile(t)
            return JSONObject().put("bytes", Base64.getEncoder().encodeToString(plain)).put("hash", hash)
                .put("final", i == t.getJSONObject("manifest").getInt("chunk_count") - 1)
        } finally { plain.fill(0) }
    }
    private suspend fun ackFile(p: JSONObject): JSONObject {
        val t = transfer(p, false)
        if (t.getBoolean("acknowledged")) return JSONObject().put("acknowledged", true)
        verifyFile(t)
        val id = t.getString("server_id")
        val result = mutation("/file-transfers/${segment(id)}/ack", "ack:$id", hashText("{}")) { JSONObject() }
        t.put("acknowledged", true); save()
        removeChunks(t)
        return JSONObject().put("acknowledged", true).put("cleanup_pending", result.optBoolean("cleanup_pending"))
    }
    private fun removeChunks(t: JSONObject) {
        for (i in 0 until t.getJSONObject("manifest").getInt("chunk_count")) {
            ensureCurrent()
            chunkPath(t, i).delete()
            ensureCurrent()
        }
    }
    private suspend fun cleanup(p: JSONObject): JSONObject {
        val op = required(p, "idempotency_key"); val array = p.getJSONArray("file_ids"); require(array.length() in 1..100)
        val ids = (0 until array.length()).map { i ->
            val supplied = requireSegment(array.getString(i))
            store.getJSONObject("transfers").optJSONObject(scoped(supplied))?.let {
                it.optString("server_id").ifEmpty { fail("conflict") }
            } ?: supplied
        }.distinct().sorted()
        val body = JSONObject().put("file_ids", JSONArray(ids))
        val result = mutation("/file-transfers/cache/cleanup", "cleanup:$op", hashText(body.toString())) { body }
        store.getJSONObject("transfers").keys().asSequence().filter { it.startsWith("$scope:") }.forEach {
            val t = store.getJSONObject("transfers").getJSONObject(it)
            if (t.optString("server_id") in ids) removeChunks(t)
        }
        val deleted = result.optJSONArray("logical_deleted")?.length() ?: 0
        val already = result.optJSONArray("already_deleted")?.length() ?: 0
        val pending = result.optJSONArray("cleanup_pending")?.length() ?: 0
        return JSONObject().put("group_id", group).put("batch_id", op).put("requested", result.optInt("requested", ids.size))
            .put("logical_deleted", deleted).put("already_deleted", already).put("cleanup_pending", pending)
            .put("pending_physical", pending).put("physical_cleanup_complete", result.optBoolean("physical_cleanup_complete"))
            .put("physical_deleted", if (result.optBoolean("physical_cleanup_complete")) deleted + already else 0)
    }

    companion object {
        private val DIAGNOSTIC_CODES = setOf("connection_unavailable", "remote_offline", "revoked", "conflict", "unsupported_method",
            "protocol_error", "permission_denied", "pairing_incomplete", "request_timeout", "tls_failure", "disk_space_low",
            "membership_required", "rotation_required", "idempotency_conflict", "generation_mismatch", "rate_limited",
            "rate_limit_exceeded", "not_found", "group_closed", "node_mismatch", "response_too_large", "internal_error",
            "service_unavailable", "unauthorized", "invalid_signature", "invalid_nonce", "nonce_replayed", "timestamp_expired")
        private val lock = Mutex()
        private val bulkPermits = Semaphore(8)
        /** Accessed only while holding lock; includes all Provider instances in this process. */
        private val activeBulk = mutableMapOf<String, Int>()
        private val SELECTION_KEYS = setOf("aries_endpoint", "aries_node_id", "aries_group_id", "aries_device_id")
        const val CHUNK_LIMIT = 512 * 1024
        const val FILE_LIMIT = 64L * 1024 * 1024
        private const val CACHE_LIMIT = 128L * 1024 * 1024
        private fun fail(code: String): Nothing = throw AriesProviderException(code)
        private fun required(p: JSONObject, field: String): String {
            val value = p.getString(field); require(value.isNotEmpty() && value.length <= 512 && value.none(Char::isISOControl)); return value
        }
        private fun requireSegment(text: String): String {
            require(text.isNotEmpty() && text.length <= 128 && text.matches(Regex("[A-Za-z0-9_-]+"))); return text
        }
        private fun segment(text: String) = URLEncoder.encode(requireSegment(text), StandardCharsets.UTF_8.name()).replace("+", "%20")
        private fun relativePath(text: String) {
            require(text.isNotEmpty() && text.length <= 512 && text.none(Char::isISOControl) &&
                '\\' !in text && ':' !in text && text.split('/').none { it.isEmpty() || it in setOf(".", "..") })
        }
        private fun requireHash(p: JSONObject, field: String) =
            required(p, field).also { require(it.matches(Regex("[0-9a-f]{64}"))) }
        private fun hashText(text: String) = AriesCrypto.hash(text.toByteArray())
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    }
}
