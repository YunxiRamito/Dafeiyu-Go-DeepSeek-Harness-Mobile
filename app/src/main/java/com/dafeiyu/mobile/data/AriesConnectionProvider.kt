package com.dafeiyu.mobile.data

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.security.MessageDigest
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** The selector is app-owned settings. Plugin callers cannot redirect credentials or select another group. */
    suspend fun call(method: String, params: JSONObject = JSONObject()): JSONObject = serialized {
        val selection = AriesProviderScope.selection(prefs.all)
        // Freeze caller-owned JSON and the app selector before network IO.
        val request = JSONObject(params.toString())
        AriesProviderScope.validate(method, request, selection.groupId)
        select(selection)
        refresh()
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
        members = emptyList(); presence = emptyList()
        ensureCurrent()
    }

    private suspend fun refresh() {
        if (!selected.configured) return
        connected = false; member = false
        members = emptyList(); presence = emptyList()
        val actualNode = checkedIo { client.node(config) }.getOrNull() ?: return
        connected = actualNode.nodeId != null && actualNode.nodeId == node
        if (!connected || group.isEmpty() || device.isEmpty()) return
        val fetched = checkedIo { groups.listMembers(group, config) }
        if (fetched.isFailure) {
            val error = fetched.exceptionOrNull()
            if (error is AriesClient.AriesException && error.httpStatus == 403) return
            connected = false; return
        }
        members = fetched.getOrThrow().filter { it.active }
        members.forEach { AriesCrypto.identity(it.deviceId, it.publicKey) }
        member = members.any { it.deviceId == device && it.publicKey == localPublicKey }
        if (!member) return
        presence = checkedIo { groups.getPresence(group, config) }.getOrNull().orEmpty()
        // Without an authoritative control-event check, old content keys must not publish.
        var cursor = "0"
        var pages = 0
        do {
            val events = get("/events?after=$cursor&limit=200")
            rotationRequired = rotationRequired || events.getJSONArray("items").objects().any {
                it.optString("completed_at").isEmpty() && it.optLong("required_epoch") > binding.optLong("epoch")
            }
            val next = events.optString("next_cursor").takeUnless { it.isEmpty() || it == "null" }
            if (next == null) break
            require(next.toULongOrNull() != null && next.toULong() > cursor.toULong())
            require(++pages <= 100)
            cursor = next
        } while (true)
        if (!rotationRequired && binding.optString("content_key").isNotEmpty() &&
            members.any { it.deviceId == binding.optString("peer_id") && it.publicKey == binding.optString("peer_public_key") })
            acknowledgeKey()
    }

    private fun paired(): Boolean =
        connected && member && !rotationRequired && binding.optString("content_key").isNotEmpty() &&
            binding.optString("pending_ack").isEmpty() && members.size == 2 &&
            members.any {
                it.deviceId == binding.optString("peer_id") && it.publicKey == binding.optString("peer_public_key")
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
        mutation("/key-deliveries/${segment(id)}/ack", "key-ack:$id", hashText("{}")) { JSONObject() }
        binding.remove("pending_ack"); save()
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
        fun describe(m: AriesGroupStore.Member): JSONObject {
            val p = presence.firstOrNull { it.deviceId == m.deviceId }
            return JSONObject().put("id", m.deviceId).put("name", m.deviceName ?: JSONObject.NULL)
                .put("public_key", m.publicKey).put("role", m.role).put("active", true)
                .put("online", if (m.deviceId == device || !connected || p == null) "unknown" else if (p.online) "online" else "offline")
                .put("last_seen_at", p?.lastSeenAt ?: JSONObject.NULL)
        }
        return JSONObject().put("protocol", LocalPluginBridge.PROTOCOL).put("server_connected", connected)
            .put("group_id", group.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
            .put("pairing_status", when { !connected -> "unknown"; paired -> "paired"; member -> "awaiting_approval"
                else -> if (binding.optString("content_key").isNotEmpty()) "revoked" else "unpaired" })
            .put("local_device", JSONObject().put("id", device)
                .put("name", members.firstOrNull { it.deviceId == device }?.deviceName ?: JSONObject.NULL)
                .put("public_key", localPublicKey.takeIf { it.isNotEmpty() } ?: JSONObject.NULL))
            .put("remote_device", members.firstOrNull { it.deviceId != device }?.let(::describe) ?: JSONObject.NULL)
            .put("members", JSONArray(members.map(::describe)))
            .put("capabilities", JSONObject().put("workspace_sync", false).put("web_broadcast", false)
                .put("encrypted_messages", paired).put("encrypted_files", paired).put("presence", member)
                .put("file_cache_cleanup", paired).put("message_acknowledgement", "local_durable")
                .put("state_subscription", "poll").put("max_plaintext_chunk_bytes", CHUNK_LIMIT)
                .put("max_file_bytes", FILE_LIMIT).put("file_create", "local_staging_until_complete"))
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
    private fun journal(operation: String, fingerprint: String, body: () -> JSONObject): JSONObject {
        val map = store.getJSONObject("mutations")
        val id = scoped(operation)
        map.optJSONObject(id)?.let { if (it.getString("fingerprint") != fingerprint) fail("conflict"); return it }
        if (map.length() >= 1024) fail("disk_space_low")
        val entry = JSONObject().put("fingerprint", fingerprint).put("key", "amp-" + hashText(id)).put("body", body())
        map.put(id, entry); save(); return entry
    }
    private suspend fun mutation(suffix: String, operation: String, fingerprint: String, body: () -> JSONObject): JSONObject {
        val entry = journal(operation, fingerprint, body)
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
        val results = JSONArray(); val receipts = store.getJSONObject("receipts")
        withKey { key ->
            for (item in page.getJSONArray("items").objects()) {
                require(item.getString("group_id") == group)
                val sender = required(item, "sender_id"); val id = required(item, "id")
                if (sender == device || receipts.optBoolean(scoped("message:$id"))) continue
                val e = AriesCrypto.readContent(item.getString("ciphertext"))
                context(e, "message", sender, item.getLong("epoch"))
                val plain = AriesCrypto.openContent(key, e, senderKey(sender))
                try {
                    require(plain.size <= 256 * 1024)
                    val decoded = JSONObject(String(plain, Charsets.UTF_8))
                    if (receipts.length() >= 4096 && !receipts.has(scoped("message:$id"))) fail("disk_space_low")
                    results.put(JSONObject().put("message_id", id).put("kind", required(decoded, "kind"))
                        .put("body", decoded.get("body")).put("sender_device_id", sender).put("created_at", item.getString("created_at")))
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
        receipts.put(id, true); save()
        return JSONObject().put("acknowledged", true).put("acknowledgement_scope", "local_durable")
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
        private val lock = Mutex()
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
