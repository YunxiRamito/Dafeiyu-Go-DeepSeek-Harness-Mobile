package com.dafeiyu.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * One encrypted atomic transaction stores provider metadata, ordering and credentials.
 * Revision checks reject a stale worker refresh after a user edits/deletes its provider.
 */
class ModelProviderStore(context: Context) {
    private val file = File(context.applicationContext.noBackupFilesDir, "model-providers.v1.enc")
    private val atomic = AtomicFile(file)
    private val lock = locks.computeIfAbsent(file.canonicalPath) { Any() }
    private val dshOrder = DshProviderOrderProjection(context)

    fun read(): ModelProviderSnapshot = synchronized(lock) { snapshot(readLocked()) }

    internal fun prepareDshProviderOrder(): File = synchronized(lock) { dshOrder.publish(snapshot(readLocked())) }

    fun hasCredential(providerId: String): Boolean = synchronized(lock) {
        readLocked().getJSONObject("credentials").has(providerId)
    }

    fun upsert(config: ModelProviderConfig, credential: JSONObject? = null): ModelProviderSnapshot = synchronized(lock) {
        config.validated()
        val state = readLocked()
        val current = snapshot(state)
        val old = current.providers.firstOrNull { it.id == config.id }
        if (config.protected) require(old == null || old == config) { "内置提供商只能设置 API Key" }
        val list = current.providers.toMutableList()
        val index = list.indexOfFirst { it.id == config.id }
        if (index < 0) { require(list.size < 128); list += config } else list[index] = config
        state.put("providers", JSONArray(list.map { it.toJson() }))
        if (credential != null) {
            state.getJSONObject("credentials").put(config.id, ModelProviderCredential.validate(credential))
            if (config.id == "deepseek") state.remove("officialCredentialReceipt")
        }
        commit(state)
    }

    fun remove(providerId: String): ModelProviderSnapshot = synchronized(lock) {
        require(providerId != "deepseek") { "内置 DeepSeek 官方不可删除" }
        val state = readLocked()
        state.put("providers", JSONArray(snapshot(state).providers.filterNot { it.id == providerId }.map { it.toJson() }))
        state.getJSONObject("credentials").remove(providerId)
        commit(state)
    }

    fun reorder(order: List<String>): ModelProviderSnapshot = synchronized(lock) {
        val state = readLocked()
        val all = snapshot(state).providers
        require(order.distinct().size == order.size && order.toSet() == all.map { it.id }.toSet()) { "提供商排序已变化，请刷新后重试" }
        val byId = all.associateBy { it.id }
        state.put("providers", JSONArray(order.map { byId.getValue(it).toJson() }))
        commit(state)
    }

    /** Only trusted host adapters may request cleartext. Never expose this object to UI or logs. */
    internal fun workerConfiguration(): JSONObject = synchronized(lock) {
        readLocked().let { state -> JSONObject().put("revision", state.getLong("revision"))
            .put("providers", state.getJSONArray("providers")).put("credentials", state.getJSONObject("credentials")) }
    }

    internal fun persistWorkerCredential(providerId: String, value: JSONObject?, expectedRevision: Long): Long = synchronized(lock) {
        val state = readLocked()
        require(state.getLong("revision") == expectedRevision && snapshot(state).providers.any { it.id == providerId }) {
            "提供商配置已改变，拒绝覆盖较新的凭据"
        }
        if (value == null) state.getJSONObject("credentials").remove(providerId)
        else state.getJSONObject("credentials").put(providerId, ModelProviderCredential.validate(value))
        if (providerId == "deepseek") state.remove("officialCredentialReceipt")
        commit(state).revision
    }

    internal fun officialCredentialSnapshot(): OfficialNativeCredentialSnapshot = synchronized(lock) {
        val state = readLocked()
        val secret = state.getJSONObject("credentials").optJSONObject("deepseek")
        require(secret == null || secret.getString("type") == "api_key")
        OfficialNativeCredentialSnapshot(state.getLong("revision").toString(), secret?.getString("key"),
            state.optJSONObject("officialCredentialReceipt")?.getString("transactionId"), OfficialCredentialPortable.digest(state))
    }

    internal fun compareAndSetOfficialCredential(expectedRevision: String, key: String?, transactionId: String) = synchronized(lock) {
        require(transactionId.matches(Regex("[a-f0-9]{32}")))
        val state = readLocked()
        check(state.getLong("revision").toString() == expectedRevision) { "NATIVE_CAS_CONFLICT" }
        if (key == null) state.getJSONObject("credentials").remove("deepseek")
        else state.getJSONObject("credentials").put("deepseek", ModelProviderCredential.validate(JSONObject().put("type", "api_key").put("key", key)))
        state.put("officialCredentialReceipt", JSONObject().put("schema", 1).put("transactionId", transactionId))
        commit(state)
    }

    /** Full restore and private receipt commit together. External portable
     * markers are discarded; a lost acknowledgement is proved by both the
     * marker and a digest of the actual current full configuration. */
    internal fun compareAndSetPortableOfficialCredential(expectedRevision: String, portable: JSONObject, transactionId: String) = synchronized(lock) {
        require(transactionId.matches(Regex("[a-f0-9]{32}")))
        val before = readLocked()
        check(before.getLong("revision").toString() == expectedRevision) { "NATIVE_CAS_CONFLICT" }
        val state = OfficialCredentialPortable.sanitize(portable)
        val digest = OfficialCredentialPortable.digest(state)
        state.put("revision", before.getLong("revision"))
        state.put("officialCredentialReceipt", JSONObject().put("schema", 1).put("transactionId", transactionId).put("configurationDigest", digest))
        commit(state)
    }

    internal fun confirmOfficialCredentialProjection(key: String?, transactionId: String, configurationDigest: String?) = synchronized(lock) {
        val state = readLocked()
        val secret = state.getJSONObject("credentials").optJSONObject("deepseek")?.getString("key")
        check(secret == key && state.optJSONObject("officialCredentialReceipt")?.getString("transactionId") == transactionId &&
            (configurationDigest == null || OfficialCredentialPortable.digest(state) == configurationDigest)) { "NATIVE_CHANGED" }
        dshOrder.publish(snapshot(state))
    }

    /** Explicit backup operation; callers must explain that the archive contains credentials. */
    internal fun exportPortable(): JSONObject = synchronized(lock) { JSONObject(readLocked().toString()).apply { remove("officialCredentialReceipt") } }

    /** Explicit .dym restore writes portable cleartext back through this device's Keystore. */
    internal fun importPortable(portable: JSONObject): ModelProviderSnapshot = synchronized(lock) {
        importLocked(portable, readLocked().getLong("revision"))
    }

    /** A live DSH read cannot overwrite edits made while its helper was running. */
    internal fun importPortableIfRevision(portable: JSONObject, expectedRevision: Long): ModelProviderSnapshot = synchronized(lock) {
        val revision = readLocked().getLong("revision")
        check(revision == expectedRevision) { "手机提供商已变化，请刷新后重试同步" }
        importLocked(portable, revision)
    }

    private fun importLocked(portable: JSONObject, revision: Long): ModelProviderSnapshot {
        val state = JSONObject(portable.toString())
        // Device-local ownership must never be restored from a portable backup.
        state.remove("officialCredentialReceipt")
        require(state.getInt("schema") == 1)
        val incoming = snapshot(state)
        val ids = incoming.providers.map { it.id }.toSet()
        state.getJSONObject("credentials").let { credentials -> credentials.keys().forEach { id ->
            require(id in ids); ModelProviderCredential.validate(credentials.getJSONObject(id))
        } }
        validateSyncReceipt(state)
        state.put("revision", revision)
        return commit(state)
    }

    private fun snapshot(state: JSONObject): ModelProviderSnapshot {
        val providers = state.getJSONArray("providers")
        require(providers.length() in 1..128)
        val list = List(providers.length()) { ModelProviderConfig.fromJson(providers.getJSONObject(it)) }
        require(list.map { it.id }.distinct().size == list.size && list.count { it.protected } == 1)
        return ModelProviderSnapshot(state.getLong("revision"), list)
    }

    private fun readLocked(): JSONObject {
        if (!file.exists() && !File(file.path + ".bak").exists()) return JSONObject().put("schema", 1).put("revision", 0)
            .put("providers", JSONArray().put(ModelProviderConfig.DEEPSEEK.toJson())).put("credentials", JSONObject())
        val sealed = atomic.openRead().use { input ->
            val content = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (content.size() <= MAX_BYTES) {
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - content.size()))
                if (count < 0) break
                content.write(buffer, 0, count)
            }
            val bytes = content.toByteArray()
            require(bytes.size in 30..MAX_BYTES) { "提供商配置大小无效" }; bytes
        }
        try {
            require(sealed[0] == 1.toByte() && sealed[1] == 12.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed.copyOfRange(2, 14)))
            val clear = cipher.doFinal(sealed, 14, sealed.size - 14)
            return try { JSONObject(clear.toString(Charsets.UTF_8)).also { state ->
                require(state.getInt("schema") == 1 && state.getLong("revision") >= 0)
                snapshot(state)
                validateSyncReceipt(state)
                val known = snapshot(state).providers.map { it.id }.toSet()
                state.getJSONObject("credentials").let { credentials -> credentials.keys().forEach { id ->
                    require(id in known); ModelProviderCredential.validate(credentials.getJSONObject(id))
                } }
            } } finally { clear.fill(0) }
        } finally { sealed.fill(0) }
    }

    private fun commit(state: JSONObject): ModelProviderSnapshot {
        state.put("revision", Math.addExact(state.getLong("revision"), 1))
        val result = snapshot(state)
        val clear = state.toString().toByteArray()
        try {
            require(clear.size <= MAX_BYTES - 64)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(clear)
            val sealed = ByteBuffer.allocate(2 + cipher.iv.size + encrypted.size).put(1).put(cipher.iv.size.toByte())
                .put(cipher.iv).put(encrypted).array()
            try {
                val output = atomic.startWrite()
                try { output.write(sealed); atomic.finishWrite(output) }
                catch (error: Throwable) { atomic.failWrite(output); throw error }
            } finally { sealed.fill(0); encrypted.fill(0) }
        } finally { clear.fill(0) }
        dshOrder.publish(result)
        return result
    }

    private fun validateSyncReceipt(state: JSONObject) {
        if (state.has("officialCredentialReceipt")) {
            val receipt = state.getJSONObject("officialCredentialReceipt")
            require(receipt.getInt("schema") == 1 && setOf("schema", "transactionId").all(receipt::has) &&
                receipt.keys().asSequence().all { it in setOf("schema", "transactionId", "configurationDigest") } &&
                receipt.getString("transactionId").matches(Regex("[a-f0-9]{32}")))
            if (receipt.has("configurationDigest")) require(receipt.getString("configurationDigest").matches(Regex("[a-f0-9]{64}")))
        }
        state.optJSONObject("dshSync")?.let { receipt ->
            require(receipt.getInt("schema") == 1)
            val routes = receipt.getJSONArray("routes")
            require(routes.length() <= 127)
            val ids = List(routes.length()) { routes.getString(it) }
            require(ids.distinct().size == ids.size && ids.all { it.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) && it != "deepseek-official" })
            if (receipt.has("officialManaged")) require(receipt.get("officialManaged") is Boolean)
            if (receipt.has("settingsRevision")) require(receipt.getLong("settingsRevision") >= 0)
            if (receipt.has("credentialRevision")) require(receipt.getString("credentialRevision").matches(Regex("[a-f0-9]{64}")))
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        require(!file.exists() && !File(file.path + ".bak").exists()) { "提供商密钥不可用，不能覆盖现有配置" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    private companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        const val KEY_ALIAS = "dafeiyu_model_providers_aes_v1"
        val locks = ConcurrentHashMap<String, Any>()
    }
}
