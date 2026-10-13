@file:Suppress("UNUSED_PARAMETER")

package com.dafeiyu.mobile.data

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject

/** Only storage/Android identity/HTTP are substituted; the real Provider, policy and crypto execute. */
object BulkFixture {
    val localSeed = ByteArray(32) { it.toByte() }
    val peerSeed = ByteArray(32) { (it + 32).toByte() }
    val contentKey = ByteArray(32) { (it + 64).toByte() }
    val localPublic = AriesCrypto.publicKey(localSeed)
    val peerPublic = AriesCrypto.publicKey(peerSeed)
    val localId = "dev_" + AriesCrypto.hash(AriesCrypto.decode(localPublic)).take(32)
    val peerId = "dev_" + AriesCrypto.hash(AriesCrypto.decode(peerPublic)).take(32)
    val scope = RemoteDshScope("node_test", "grp_test", localId, peerId)
    val providerScope = AriesCrypto.hash(listOf("https://aries.test", "node_test", "grp_test", localId).joinToString("\n").toByteArray())
    var identityId: String? = localId
    var identityPublic = localPublic
    var members = true
    var peerActive = true
    var rotation = false
    var postFailure = false
    var postHook: (() -> Unit)? = null
    var saveHook: (() -> Unit)? = null
    var loadHook: (() -> Unit)? = null
    var gate: Gate? = null
    var http: BulkHttpFixture? = null
    var maxBytes = 0
    var saves = 0
    var ackEvents = true
    var eventCount = 1
    var invalidPage: JSONObject? = null
    var nodeCalls = 0
    var membershipCalls = 0
    var nodeCapabilities = emptySet<String>()
    var nodeFailure: Throwable? = null
    var membersFailure: Throwable? = null
    var presenceFailure: Throwable? = null
    var peerOnline = true
    var terminalStatus = "active"
    var serverGeneration = 1L
    private var sequence = 0L
    private val messages = ArrayList<JSONObject>()
    private val posted = LinkedHashMap<String, Pair<String, JSONObject>>()
    val inFlight = AtomicInteger(0)
    val maximumInFlight = AtomicInteger(0)
    var state = fresh()
    data class Gate(val target: Int, val reached: CompletableDeferred<Unit> = CompletableDeferred(),
                    val release: CompletableDeferred<Unit> = CompletableDeferred())

    fun fresh() = JSONObject().put("version", 1).put("bindings", JSONObject())
        .put("mutations", JSONObject()).put("transfers", JSONObject()).put("receipts", JSONObject())

    fun reset(context: Context) {
        context.preferences.put("aries_endpoint", "https://aries.test")
        context.preferences.put("aries_node_id", "node_test")
        context.preferences.put("aries_group_id", "grp_test")
        context.preferences.put("aries_device_id", localId)
        identityId = localId; identityPublic = localPublic; members = true; peerActive = true; rotation = false
        postFailure = false; postHook = null; saveHook = null; loadHook = null; gate = null; http = null; maxBytes = 0; saves = 0
        inFlight.set(0); maximumInFlight.set(0); sequence = 0; messages.clear(); posted.clear(); ackEvents = true
        eventCount = 1; invalidPage = null
        nodeCalls = 0; membershipCalls = 0; nodeCapabilities = emptySet(); terminalStatus = "active"; serverGeneration = 1L
        nodeFailure = null; membersFailure = null; presenceFailure = null; peerOnline = true
        state = fresh()
        state.getJSONObject("bindings").put(providerScope, JSONObject().put("content_key", AriesCrypto.encode(contentKey))
            .put("epoch", 1).put("peer_id", peerId).put("peer_public_key", peerPublic)
            .put("relationship_confirmed", true).put("remote_host_generation", "generation_1")
            .put("remote_host_capabilities", JSONArray().put("remote_task").put("remote_artifacts").put("remote_attachments")))
    }

    @Synchronized fun binding(): JSONObject = state.getJSONObject("bindings").getJSONObject(providerScope)
    @Synchronized fun copyState(): JSONObject = JSONObject(state.toString())
    @Synchronized fun save(value: JSONObject) {
        val encoded = value.toString().toByteArray()
        check(encoded.size <= 16 * 1024 * 1024) { "real_vault_limit" }
        maxBytes = maxOf(maxBytes, encoded.size)
        state = JSONObject(value.toString()); saves++
        saveHook?.also { saveHook = null }?.invoke()
    }
    @Synchronized fun snapshot(after: String): JSONObject {
        invalidPage?.let { return JSONObject(it.toString()) }
        val cursor = after.toLong()
        val page = messages.filter { it.getLong("sequence") > cursor }.take(4)
        return JSONObject().put("items", JSONArray().also { array -> page.forEach { array.put(JSONObject(it.toString())) } })
            .put("next_cursor", (page.lastOrNull()?.getLong("sequence") ?: cursor).toString())
    }
    @Synchronized fun append(sender: String, seed: ByteArray, plain: JSONObject): JSONObject {
        sequence++
        val ciphertext = AriesCrypto.sealContent(seed, contentKey,
            AriesCrypto.envelope("message", "node_test", "grp_test", 1, sender, "obj_$sequence"), plain.toString().toByteArray())
        val result = JSONObject().put("id", "msg_$sequence").put("group_id", "grp_test").put("sender_id", sender)
            .put("epoch", 1).put("ciphertext", ciphertext).put("sequence", sequence).put("created_at", "2026-10-13T00:00:00Z")
        messages.add(result)
        return JSONObject(result.toString())
    }
    fun event(request: String, type: String = "result", number: Long = 0): JSONObject = append(peerId, peerSeed,
        JSONObject().put("kind", RemoteDshClient.EVENT_KIND).put("body", JSONObject()
            .put("protocol", RemoteDshClient.PROTOCOL).put("request_id", request).put("target_device_id", localId)
            .put("sequence", number).put("type", type).put("payload", JSONObject().put("ok", true))))

    suspend fun post(body: JSONObject, key: String): JSONObject {
        val count = inFlight.incrementAndGet()
        maximumInFlight.updateAndGet { maxOf(it, count) }
        try {
            val durable = copyState().getJSONObject("mutations")
            check(durable.keys().asSequence().any {
                val entry = durable.getJSONObject(it)
                entry.optString("key") == key && entry.optJSONObject("body")?.toString() == body.toString()
            }) { "ciphertext_not_durable_before_transport" }
            http?.let { return it.post(body, key) }
            gate?.let { waiting -> if (count >= waiting.target) waiting.reached.complete(Unit); waiting.release.await() }
            postHook?.invoke()
            if (postFailure) error("synthetic_network_failure")
            synchronized(this) {
                posted[key]?.let { old -> check(old.first == body.toString()) { "changed_ciphertext_retry" }; return JSONObject(old.second.toString()) }
                val item = JSONObject().put("id", "msg_${++sequence}").put("group_id", "grp_test").put("sender_id", localId)
                    .put("epoch", body.getLong("epoch")).put("ciphertext", body.getString("ciphertext"))
                    .put("sequence", sequence).put("created_at", "2026-10-13T00:00:00Z")
                messages.add(item)
                val envelope = AriesCrypto.readContent(body.getString("ciphertext"))
                val plain = AriesCrypto.openContent(contentKey, envelope, localPublic)
                val decoded = try { JSONObject(plain.toString(Charsets.UTF_8)) } finally { plain.fill(0) }
                val request = decoded.optJSONObject("body")?.optString("request_id")
                if (decoded.optString("kind") == RemoteDshClient.REQUEST_KIND && request != null && ackEvents) {
                    for (index in 0 until eventCount) event(request, if (index == eventCount - 1) "result" else "accepted", index.toLong())
                }
                val result = JSONObject().put("message_id", item.getString("id")).put("generation", 1)
                posted[key] = body.toString() to result
                return JSONObject(result.toString())
            }
        } finally { inFlight.decrementAndGet() }
    }

    @Synchronized fun postedCount() = posted.size
    @Synchronized fun mutateBinding(action: (JSONObject) -> Unit) { action(binding()) }
    @Synchronized fun tamperMessage(id: String, action: (JSONObject) -> Unit) { action(messages.first { it.getString("id") == id }) }
}

class AriesIdentityStore(context: Context) {
    data class Identity(val deviceId: String?, val publicKeyBase64Url: String)
    fun identity() = Identity(BulkFixture.identityId, BulkFixture.identityPublic)
    fun <T> withSeed(action: (ByteArray) -> T): T = BulkFixture.localSeed.copyOf().let {
        try { action(it) } finally { it.fill(0) }
    }
}

class AriesClient(context: Context) {
    data class Config(val baseUrl: String = "https://aries.test")
    data class Node(val nodeId: String?, val capabilities: Set<String> = emptySet())
    data class Heartbeat(
        val deviceId: String,
        val online: Boolean,
        val lastSeenAt: String? = null,
        val expiresAt: String? = null,
        val clientVersion: String? = null,
        val deviceName: String? = null,
    )
    class AriesException(val httpStatus: Int? = null, val errorCode: String? = null, cause: Throwable? = null) :
        Exception("untrusted_server_message", cause)
    suspend fun node(config: Config): Result<Node> {
        BulkFixture.nodeCalls++
        BulkFixture.nodeFailure?.let { return Result.failure(it) }
        return Result.success(Node("node_test", BulkFixture.nodeCapabilities))
    }
    suspend fun heartbeat(
        config: Config,
        clientVersion: String? = null,
        expectedNodeId: String? = null,
    ): Result<Heartbeat> = Result.success(
        Heartbeat(BulkFixture.localId, online = true, clientVersion = clientVersion)
    )
    suspend fun <T> signedGet(config: Config, path: String, parse: (JSONObject) -> T): Result<T> = Result.success(parse(when {
        path.contains("/events?") -> {
            val after = path.substringAfter("after=").substringBefore('&')
            val items = JSONArray()
            if (BulkFixture.rotation && after == "0") items.put(JSONObject().put("sequence", 1).put("type", "control")
                .put("event", JSONObject().put("sequence", 1).put("required_epoch", 2).put("completed_at", "")))
            JSONObject().put("items", items).put("next_cursor", if (items.length() > 0) "1" else after)
        }
        path.contains("/messages?") -> BulkFixture.snapshot(path.substringAfter("after=").substringBefore('&'))
        path.endsWith("/key-deliveries") -> JSONObject().put("items", JSONArray())
        path.endsWith("/membership-status") || path.endsWith("/pairing-status") -> JSONObject()
            .put("group_id", "grp_test").put("device_id", BulkFixture.localId).put("status", BulkFixture.terminalStatus)
            .put("generation", BulkFixture.serverGeneration).put("changed_at", "2026-10-13T00:00:00Z")
        else -> error("unexpected_synthetic_get")
    }))
    suspend fun signedMutation(config: Config, method: String, path: String, body: JSONObject, key: String): JSONObject {
        check(method == "POST" && path.endsWith("/messages"))
        return BulkFixture.post(body, key)
    }
    suspend fun signedRaw(config: Config, method: String, path: String, bytes: ByteArray = ByteArray(0), key: String? = null): ByteArray =
        error("raw_io_not_expected")
}

class AriesGroupStore(context: Context) {
    data class Member(val deviceId: String, val publicKey: String, val role: String, val active: Boolean, val deviceName: String? = null)
    data class Presence(val deviceId: String, val online: Boolean, val lastSeenAt: String?)
    data class ApplicationDetail(val applicationId: String, val deviceId: String, val publicKey: String, val status: String)
    suspend fun listMembers(group: String, config: AriesClient.Config): Result<List<Member>> {
        BulkFixture.membershipCalls++
        BulkFixture.membersFailure?.let { return Result.failure(it) }
        return Result.success(buildList {
        if (BulkFixture.members) add(Member(BulkFixture.localId, BulkFixture.localPublic, "member", true))
        if (BulkFixture.peerActive) add(Member(BulkFixture.peerId, BulkFixture.peerPublic, "creator", true))
        })
    }
    suspend fun getPresence(group: String, config: AriesClient.Config): Result<List<Presence>> =
        BulkFixture.presenceFailure?.let { Result.failure(it) }
            ?: Result.success(listOf(Presence(BulkFixture.peerId, BulkFixture.peerOnline, "2026-10-13T00:00:00Z")))
    suspend fun getApplication(group: String, application: String, config: AriesClient.Config): Result<ApplicationDetail> = error("not_expected")
}

internal class AriesProviderVault(context: Context) {
    val directory = File(context.noBackupFilesDir, "bulk-fixture").apply { mkdirs() }
    fun load(): JSONObject {
        val result = BulkFixture.copyState()
        BulkFixture.loadHook?.also { BulkFixture.loadHook = null }?.invoke()
        return result
    }
    fun save(state: JSONObject) = BulkFixture.save(state)
    companion object { fun write(path: File, bytes: ByteArray) { path.parentFile?.mkdirs(); path.writeBytes(bytes) } }
}

object LocalPluginBridge { const val PROTOCOL = "dafeiyu-go.connection.v1" }
