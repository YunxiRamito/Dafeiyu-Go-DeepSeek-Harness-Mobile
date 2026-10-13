package com.dafeiyu.mobile.data

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Only IO and Android identity storage are substituted; production provider and crypto are compiled. */
object ProviderFixture {
    val localSeed = ByteArray(32) { it.toByte() }
    val peerSeed = ByteArray(32) { (it + 32).toByte() }
    val contentKey = ByteArray(32) { (it + 64).toByte() }
    val localPublic get() = AriesCrypto.publicKey(localSeed)
    val peerPublic get() = AriesCrypto.publicKey(peerSeed)
    val localId get() = "dev_" + AriesCrypto.hash(AriesCrypto.decode(localPublic)).take(32)
    val peerId get() = "dev_" + AriesCrypto.hash(AriesCrypto.decode(peerPublic)).take(32)
    var identityId: String? = localId
    var identityPublic = localPublic
    var identityReads = 0
    val calls = mutableListOf<String>()
    var beforeNetwork: ((String) -> Unit)? = null
    var afterNetwork: ((String) -> Unit)? = null
    var afterSave: (() -> Unit)? = null
    var beforeLoad: (() -> Unit)? = null
    var state = emptyState()
    var saves = 0
    var loads = 0
    var delivery = JSONObject()
    var activeMembers = true
    var peerActive = true
    var rotation = false
    var applicationStatus = "awaiting_key"
    var applicationDevice = localId
    var applicationPublic = localPublic
    var failAck = false
    var remoteOnline: Boolean? = null
    var localName: String? = null
    var peerName: String? = null
    fun emptyState() = JSONObject().put("version", 1).put("bindings", JSONObject())
        .put("mutations", JSONObject()).put("transfers", JSONObject()).put("receipts", JSONObject())
    fun configure(context: Context) {
        context.preferences.put("aries_endpoint", "https://aries.test")
        context.preferences.put("aries_node_id", "node_test")
        context.preferences.put("aries_group_id", "grp_test")
        context.preferences.put("aries_device_id", localId)
    }
    fun reset(context: Context) {
        configure(context)
        identityId = localId; identityPublic = localPublic; identityReads = 0
        calls.clear(); beforeNetwork = null; afterNetwork = null
        afterSave = null; beforeLoad = null
        state = emptyState(); saves = 0; loads = 0; remoteOnline = null
        localName = null; peerName = null
        activeMembers = true; peerActive = true; rotation = false; failAck = false
        applicationStatus = "awaiting_key"; applicationDevice = localId; applicationPublic = localPublic
        val encoded = AriesCrypto.createPair(peerSeed, contentKey, "node_test", "grp_test", 1,
            "app_test", localId, localPublic)
        delivery = JSONObject().put("id", "kd_test").put("group_id", "grp_test")
            .put("recipient_id", localId).put("sender_id", peerId).put("epoch", 1).put("envelope", encoded)
    }
    suspend fun <T> network(name: String, action: () -> T): T {
        beforeNetwork?.invoke(name)
        calls.add(name)
        val result = action()
        afterNetwork?.invoke(name)
        return result
    }
    fun binding(): JSONObject {
        val hash = AriesCrypto.hash(listOf("https://aries.test", "node_test", "grp_test", localId)
            .joinToString("\n").toByteArray())
        return state.getJSONObject("bindings").optJSONObject(hash) ?: JSONObject()
    }
}

class AriesIdentityStore(context: Context) {
    data class Identity(val deviceId: String?, val publicKeyBase64Url: String)
    fun identity(): Identity {
        ProviderFixture.identityReads++
        return Identity(ProviderFixture.identityId, ProviderFixture.identityPublic)
    }
    fun <T> withSeed(action: (ByteArray) -> T): T {
        val seed = ProviderFixture.localSeed.copyOf()
        return try { action(seed) } finally { seed.fill(0) }
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
    class AriesException(val httpStatus: Int? = null) : Exception()
    suspend fun node(config: Config): Result<Node> = ProviderFixture.network("node") {
        Result.success(Node("node_test"))
    }
    suspend fun heartbeat(
        config: Config,
        clientVersion: String? = null,
        expectedNodeId: String? = null,
    ): Result<Heartbeat> = Result.success(
        Heartbeat(ProviderFixture.localId, online = true, clientVersion = clientVersion)
    )
    suspend fun <T> signedGet(config: Config, path: String, parse: (JSONObject) -> T): Result<T> =
        ProviderFixture.network(path) {
            Result.success(parse(when {
                path.contains("/events?") -> {
                    val after = path.substringAfter("after=").substringBefore('&')
                    JSONObject().put("items", JSONArray().also {
                        if (ProviderFixture.rotation && after == "0") it.put(JSONObject().put("sequence", 1)
                            .put("type", "control").put("event", JSONObject().put("sequence", 1)
                                .put("required_epoch", 2).put("completed_at", "")))
                    }).put("next_cursor", if (ProviderFixture.rotation && after == "0") "1" else after)
                }
                path.endsWith("/key-deliveries") -> JSONObject().put("items", JSONArray().put(ProviderFixture.delivery))
                path.endsWith("/file-transfers/cache") -> JSONObject().put("group_id", "grp_test")
                path.contains("/messages?") -> JSONObject().put("items", JSONArray()).put("next_cursor", "0")
                else -> error("Unexpected GET $path")
            }))
        }
    suspend fun signedMutation(config: Config, method: String, path: String, body: JSONObject, key: String): JSONObject =
        ProviderFixture.network(path) {
            if (path.contains("/key-deliveries/") && ProviderFixture.failAck) error("Offline ack")
            JSONObject().put("message_id", "msg_test").put("acknowledged", true)
        }
    suspend fun signedRaw(config: Config, method: String, path: String,
        bytes: ByteArray = ByteArray(0), key: String? = null): ByteArray =
        ProviderFixture.network("$method $path") { error("Raw transport not used by these fixtures") }
}

class AriesGroupStore(context: Context) {
    data class Member(val deviceId: String, val publicKey: String, val role: String, val active: Boolean,
        val deviceName: String? = null)
    data class Presence(val deviceId: String, val online: Boolean, val lastSeenAt: String?)
    data class ApplicationDetail(val applicationId: String, val deviceId: String, val publicKey: String, val status: String)
    suspend fun listMembers(group: String, config: AriesClient.Config): Result<List<Member>> =
        ProviderFixture.network("members") {
            Result.success(buildList {
                if (ProviderFixture.activeMembers) add(Member(ProviderFixture.localId, ProviderFixture.localPublic, "member", true, ProviderFixture.localName))
                if (ProviderFixture.peerActive) add(Member(ProviderFixture.peerId, ProviderFixture.peerPublic, "creator", true, ProviderFixture.peerName))
            })
        }
    suspend fun getPresence(group: String, config: AriesClient.Config): Result<List<Presence>> =
        ProviderFixture.network("presence") { Result.success(ProviderFixture.remoteOnline?.let {
            listOf(Presence(ProviderFixture.peerId, it, "2026-10-11T08:00:00Z"))
        }.orEmpty()) }
    suspend fun getApplication(group: String, application: String, config: AriesClient.Config): Result<ApplicationDetail> =
        ProviderFixture.network("application") {
            Result.success(ApplicationDetail(application, ProviderFixture.applicationDevice,
                ProviderFixture.applicationPublic, ProviderFixture.applicationStatus))
        }
}

internal class AriesProviderVault(context: Context) {
    val directory = File(context.noBackupFilesDir, "provider-fixture").apply { mkdirs() }
    fun load(): JSONObject {
        ProviderFixture.loads++
        val result = JSONObject(ProviderFixture.state.toString())
        ProviderFixture.beforeLoad?.also { ProviderFixture.beforeLoad = null }?.invoke()
        return result
    }
    fun save(state: JSONObject) {
        ProviderFixture.state = JSONObject(state.toString())
        ProviderFixture.saves++
        ProviderFixture.afterSave?.also { ProviderFixture.afterSave = null }?.invoke()
    }
    companion object {
        fun write(path: File, bytes: ByteArray) { path.parentFile?.mkdirs(); path.writeBytes(bytes) }
    }
}

object LocalPluginBridge { const val PROTOCOL = "dafeiyu-go.connection.v1" }
