package com.dafeiyu.mobile.data

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/** Local two-way configuration adapter. This never accepts an Aries/remote URL:
 * provider editing, ordering and secret reads remain inside this application.
 * The Node helper uses only pinned schema/catalog/credential storage modules. */
class DshSharedConfigurationAdapter(
    context: Context,
    private val store: ModelProviderStore = ModelProviderStore(context),
    private val bridge: DshSharedPersistenceBridge = DshSharedPersistenceBridge(context),
) {
    private val home = File(context.applicationContext.filesDir, "dsh-home")
    private val lock = locks.computeIfAbsent(context.applicationContext.filesDir.canonicalPath) { Mutex() }
    private val rpc = DshLocalConfigurationRpc()

    /** Read the live DSH directory and private credentials, then commit through
     * the current device's Keystore. A concurrent native edit or DSH revision
     * change refuses the import, preserving both stores. */
    suspend fun readFromDsh(accessUrl: String): ModelProviderSnapshot = withContext(Dispatchers.IO) {
        lock.withLock {
            DshExtensionTransaction.requireReady(home)
            val current = store.exportPortable()
            val settings = rpc.call(accessUrl, "settings/describe", JSONObject())
            val namespace = DshSharedConfigurationMapping.namespace(settings, "llm-pi-ai")
            val profiles = namespace.getJSONObject("value").optJSONObject("providers") ?: JSONObject()
            val credentials = bridge.readCredentialDocument()
            val converted = bridge.convertProviderConfiguration(JSONObject().put("operation", "from-dsh").put("providers", profiles))
            val next = DshSharedConfigurationMapping.importState(current, converted, credentials,
                DshSharedConfigurationMapping.deepSeekCredentialReference(settings))
            next.getJSONObject("dshSync").put("settingsRevision", namespace.getLong("revision"))
                .put("credentialRevision", credentials.getString("revision"))
            val checked = rpc.call(accessUrl, "settings/describe", JSONObject())
            check(DshSharedConfigurationMapping.namespace(checked, "llm-pi-ai").getLong("revision") == namespace.getLong("revision")) {
                "DSH 提供商设置已变化，请刷新后重试"
            }
            check(DshSharedConfigurationMapping.deepSeekCredentialReference(checked) == DshSharedConfigurationMapping.deepSeekCredentialReference(settings)) {
                "DSH 官方提供商配置已变化，请刷新后重试"
            }
            check(bridge.readCredentialDocument().getString("revision") == credentials.getString("revision")) {
                "DSH 凭据已变化，请刷新后重试"
            }
            store.importPortableIfRevision(next, current.getLong("revision"))
        }
    }

    /** An explicit native-to-DSH publish changes only fields this app manages.
     * Profile headers, cloud parameters, advanced defaults and other namespaces
     * survive. Each setting mutation uses the real revision returned by DSH. */
    suspend fun writeToDsh(accessUrl: String): ModelProviderSnapshot = withContext(Dispatchers.IO) {
        lock.withLock {
            DshExtensionTransaction.requireReady(home)
            val current = store.exportPortable()
            val converted = bridge.convertProviderConfiguration(JSONObject().put("operation", "to-dsh").put("native", current))
            val settings = rpc.call(accessUrl, "settings/describe", JSONObject())
            check(settings.optBoolean("writable")) { "DSH 当前配置只读，无法同步提供商" }
            val namespace = DshSharedConfigurationMapping.namespace(settings, "llm-pi-ai")
            val profiles = DshSharedConfigurationMapping.prepareProfiles(namespace, current, converted.getJSONObject("providers"))
            converted.put("providers", profiles)
            val credentials = bridge.readCredentialDocument()
            val updates = DshSharedConfigurationMapping.credentialUpdates(current, converted,
                DshSharedConfigurationMapping.deepSeekCredentialReference(settings), namespace)
                .put("expectedRevision", credentials.getString("revision"))
            val operations = DshSharedConfigurationMapping.mutations(namespace, profiles, DshSharedConfigurationMapping.managedRoutes(current))
            check(store.read().revision == current.getLong("revision")) { "手机提供商已变化，请重试同步" }
            val credentialWrite = bridge.writeCredentialDocument(updates)
            var persistedRevision = namespace.getLong("revision")
            if (operations.length() > 0) {
                val result = rpc.call(accessUrl, "settings/mutate", JSONObject().put("ns", "llm-pi-ai")
                    .put("expectedRevision", namespace.getLong("revision")).put("ops", operations))
                check(result.getString("ns") == "llm-pi-ai" && result.getLong("revision") > namespace.getLong("revision")) {
                    "DSH 没有返回已持久化的提供商版本"
                }
                DshSharedConfigurationMapping.verifyMutation(result, operations)
                persistedRevision = result.getLong("revision")
            }
            check(store.read().revision == current.getLong("revision")) { "手机提供商已变化；DSH 已提交部分同步，请刷新后重试" }
            current.put("dshSync", DshSharedConfigurationMapping.syncReceipt(profiles.keys().asSequence().toList())
                .put("officialManaged", current.getJSONObject("credentials").has("deepseek") || current.optJSONObject("dshSync")?.optBoolean("officialManaged") == true)
                .put("settingsRevision", persistedRevision).put("credentialRevision", credentialWrite.getString("revision")))
            store.importPortableIfRevision(current, current.getLong("revision"))
        }
    }

    /** PC ordering belongs to the launcher, not to an invented DSH Config key.
     * Native DSH catalog selectors use the same vault order as light mode. */
    fun orderCatalog(catalog: JSONObject): JSONObject = DshSharedConfigurationMapping.orderCatalog(catalog, store.read().providers)

    private companion object { val locks = ConcurrentHashMap<String, Mutex>() }
}

internal object DshSharedConfigurationMapping {
    private val managedFields = listOf("displayName", "api", "baseURL", "models", "apiKeyEnv")

    fun namespace(settings: JSONObject, name: String): JSONObject {
        val items = settings.getJSONArray("namespaces")
        return (0 until items.length()).map { items.getJSONObject(it) }.singleOrNull { it.getString("ns") == name }
            ?: error("DSH 缺少 $name 配置接口")
    }

    fun deepSeekCredentialReference(settings: JSONObject): String {
        val descriptors = settings.getJSONArray("namespaces")
        val matches = (0 until descriptors.length()).map { descriptors.getJSONObject(it) }
            .filter { it.optString("ns") in setOf("llm-deepseek", "llm-deepseek-api-key") }
        require(matches.size == 1) { "DSH 官方 DeepSeek 配置接口缺失或不唯一" }
        val reference = matches.single().getJSONObject("value").optString("apiKeyEnv", "DEEPSEEK_API_KEY")
        require(reference.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
        return reference
    }

    fun importState(current: JSONObject, converted: JSONObject, credentials: JSONObject, officialRef: String): JSONObject {
        val proposed = converted.getJSONArray("providers")
        val old = current.getJSONArray("providers")
        val oldProviders = List(old.length()) { ModelProviderConfig.fromJson(old.getJSONObject(it)) }
        val oldSecrets = current.getJSONObject("credentials")
        val previousRoutes = managedRoutes(current)
        val providers = mutableListOf(ModelProviderConfig.DEEPSEEK)
        val secrets = JSONObject()
        val refs = credentials.getJSONObject("refs")
        val records = credentials.getJSONObject("records")
        if (refs.has(officialRef)) {
            val direct = ModelProviderCredential.validate(JSONObject().put("type", "api_key").put("key", refs.getString(officialRef)))
            if (current.optJSONObject("dshSync")?.optBoolean("officialManaged") != true)
                oldSecrets.optJSONObject("deepseek")?.let { require(sameJson(it, direct)) { "手机和 DSH 的官方 API Key 尚未同步，请先解决凭据冲突" } }
            secrets.put("deepseek", direct)
        }
        else if (current.optJSONObject("dshSync")?.optBoolean("officialManaged") != true)
            oldSecrets.optJSONObject("deepseek")?.let { secrets.put("deepseek", JSONObject(it.toString())) }
        for (index in 0 until proposed.length()) {
            val raw = proposed.getJSONObject(index)
            val config = ModelProviderConfig.fromJson(raw)
            require(!config.protected)
            val route = raw.getString("dshRoute")
            val existing = oldProviders.firstOrNull { it.id == config.id }
            if (existing != null && route !in previousRoutes) require(existing.copy(dshRoute = route) == config) {
                "手机和 DSH 有同名但尚未同步的提供商，请先解决配置冲突；现有配置保留"
            }
            providers += config
            val reference = raw.optString("apiKeyEnv").takeIf { it.isNotBlank() && it != "null" }
            val record = records.optJSONObject("llm-pi-ai/$route")
            val value = if (reference != null && refs.has(reference)) JSONObject().put("type", "api_key").put("key", refs.getString(reference))
                else if (record?.optString("kind") == "grant") JSONObject(record.getJSONObject("payload").toString())
                else if (record?.optString("kind") == "api-key") JSONObject(record.toString()).apply { remove("kind"); put("type", "api_key") }
                else null
            if (value != null) {
                credentials.optJSONObject("portableFiles")?.optJSONObject("llm-pi-ai/$route")?.let { files ->
                    value.put("files", JSONObject(files.toString()))
                    value.optJSONObject("env")?.let { env ->
                        for (key in listOf("GOOGLE_APPLICATION_CREDENTIALS", "AWS_SHARED_CREDENTIALS_FILE", "AWS_CONFIG_FILE")) env.remove(key)
                    }
                }
                val validated = ModelProviderCredential.validate(value)
                if (route !in previousRoutes) oldSecrets.optJSONObject(config.id)?.let {
                    require(sameJson(it, validated)) { "手机和 DSH 有尚未同步的不同凭据，请先解决冲突；现有凭据保留" }
                }
                secrets.put(config.id, validated)
            } else if (route !in previousRoutes) oldSecrets.optJSONObject(config.id)?.let { secrets.put(config.id, JSONObject(it.toString())) }
        }
        // Disabled providers were deliberately not published to DSH. Preserve
        // their private settings and credentials when reading live routes.
        for (index in 0 until old.length()) {
            val config = ModelProviderConfig.fromJson(old.getJSONObject(index))
            if ((!config.enabled || (config.dshRoute ?: config.id) !in previousRoutes) && providers.none { it.id == config.id }) {
                providers += config
                current.getJSONObject("credentials").optJSONObject(config.id)?.let { secrets.put(config.id, JSONObject(it.toString())) }
            }
        }
        val order = old.let { rows -> List(rows.length()) { rows.getJSONObject(it).getString("id") } }
        val byId = providers.associateBy { it.id }
        val ordered = (order.filter { it in byId } + providers.map { it.id }.filter { it !in order }).map { byId.getValue(it) }
        return JSONObject().put("schema", 1).put("revision", current.getLong("revision"))
            .put("providers", JSONArray(ordered.map { it.toJson() })).put("credentials", secrets)
            .put("dshSync", syncReceipt((0 until proposed.length()).map { proposed.getJSONObject(it).getString("dshRoute") })
                .put("officialManaged", refs.has(officialRef) || current.optJSONObject("dshSync")?.optBoolean("officialManaged") == true))
    }

    fun managedRoutes(current: JSONObject): Set<String> = current.optJSONObject("dshSync")?.optJSONArray("routes")?.let { rows ->
        List(rows.length()) { rows.getString(it) }.toSet()
    } ?: emptySet()

    fun syncReceipt(routes: List<String>): JSONObject = JSONObject().put("schema", 1).put("routes", JSONArray(routes))

    /** Previously unknown DSH routes cannot be silently claimed by a newly
     * added native ID. Read/import first to make ownership explicit. */
    fun prepareProfiles(namespace: JSONObject, current: JSONObject, profiles: JSONObject): JSONObject {
        val copy = JSONObject(profiles.toString())
        val old = namespace.getJSONObject("value").optJSONObject("providers") ?: JSONObject()
        val owned = managedRoutes(current)
        for (route in copy.keys()) {
            require(!old.has(route) || route in owned) { "DSH 已有同名提供商，请先读取 DSH 设置后再同步" }
            val candidate = copy.getJSONObject(route)
            if (candidate.has("apiKeyEnv")) old.optJSONObject(route)?.optString("apiKeyEnv")?.takeIf {
                it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))
            }?.let { candidate.put("apiKeyEnv", it) }
        }
        return copy
    }

    fun credentialUpdates(current: JSONObject, converted: JSONObject, officialRef: String, namespace: JSONObject): JSONObject {
        val refs = JSONArray()
        val records = JSONArray()
        val secrets = current.getJSONObject("credentials")
        val nativeProviders = current.getJSONArray("providers")
        val published = converted.getJSONObject("providers")
        if (!secrets.has("deepseek") && current.optJSONObject("dshSync")?.optBoolean("officialManaged") == true)
            refs.put(JSONObject().put("ref", officialRef).put("value", JSONObject.NULL))
        for (index in 0 until nativeProviders.length()) {
            val config = ModelProviderConfig.fromJson(nativeProviders.getJSONObject(index))
            val credential = secrets.optJSONObject(config.id) ?: continue
            ModelProviderCredential.validate(credential)
            if (config.protected) {
                require(credential.getString("type") == "api_key" && credential.has("key"))
                refs.put(JSONObject().put("ref", officialRef).put("value", credential.getString("key")))
            } else if (config.enabled) {
                val route = config.dshRoute ?: config.id
                val profile = published.getJSONObject(route)
                if (profile.has("apiKeyEnv")) refs.put(JSONObject().put("ref", profile.getString("apiKeyEnv"))
                    .put("value", credential.getString("key")))
                val record = if (credential.getString("type") == "oauth") JSONObject().put("kind", "grant")
                    .put("payload", JSONObject(credential.toString()))
                else JSONObject(credential.toString()).apply { remove("type"); remove("files"); put("kind", "api-key") }
                records.put(JSONObject().put("provider", route).put("record", record).also {
                    credential.optJSONObject("files")?.let { files -> it.put("files", JSONObject(files.toString())) }
                })
            }
        }
        val old = namespace.getJSONObject("value").optJSONObject("providers") ?: JSONObject()
        val activeReferences = published.keys().asSequence().mapNotNull { published.getJSONObject(it).optString("apiKeyEnv").takeIf(String::isNotBlank) }.toSet()
        for (route in managedRoutes(current).filterNot(published::has)) {
            records.put(JSONObject().put("provider", route).put("record", JSONObject.NULL))
            val reference = old.optJSONObject(route)?.optString("apiKeyEnv")
            if (reference != null && reference.startsWith("DAFEIYU_PROVIDER_") && reference !in activeReferences &&
                (0 until refs.length()).none { refs.getJSONObject(it).getString("ref") == reference }) {
                refs.put(JSONObject().put("ref", reference).put("value", JSONObject.NULL))
            }
        }
        val uniqueReferences = JSONObject()
        for (index in 0 until refs.length()) {
            val item = refs.getJSONObject(index)
            val reference = item.getString("ref")
            require(!uniqueReferences.has(reference) || sameJson(uniqueReferences.get(reference), item.get("value"))) {
                "多个提供商使用同一凭据名称但内容不同，请先调整 DSH 凭据配置"
            }
            uniqueReferences.put(reference, item.get("value"))
        }
        return JSONObject().put("refs", JSONArray(uniqueReferences.keys().asSequence().map { reference ->
            JSONObject().put("ref", reference).put("value", uniqueReferences.get(reference))
        }.toList())).put("records", records)
    }

    fun mutations(namespace: JSONObject, profiles: JSONObject, ownedRoutes: Set<String>): JSONArray {
        val operations = JSONArray()
        val old = namespace.getJSONObject("value").optJSONObject("providers") ?: JSONObject()
        for (route in profiles.keys()) {
            val candidate = profiles.getJSONObject(route)
            val original = old.optJSONObject(route)
            for (field in managedFields) {
                if (candidate.has(field)) {
                    if (original == null || !original.has(field) || !sameJson(candidate.get(field), original.get(field)))
                        operations.put(JSONObject().put("op", "set").put("path", JSONArray().put("providers").put(route).put(field)).put("value", candidate.get(field)))
                } else if (original?.has(field) == true)
                    operations.put(JSONObject().put("op", "unset").put("path", JSONArray().put("providers").put(route).put(field)))
            }
        }
        for (route in ownedRoutes.filterNot(profiles::has)) {
            if (namespace.optJSONObject("user")?.optJSONObject("providers")?.has(route) == true)
                operations.put(JSONObject().put("op", "unset").put("path", JSONArray().put("providers").put(route)))
            // A route originating in immutable base config cannot be removed
            // using a user-level unset. Refuse rather than claim it vanished.
            else require(!old.has(route)) { "DSH 基础配置中的提供商不可在手机移除，请修改其来源配置" }
        }
        return operations
    }

    fun verifyMutation(result: JSONObject, operations: JSONArray) {
        val root = result.getJSONObject("value")
        val stored = result.getJSONObject("user")
        for (index in 0 until operations.length()) {
            val operation = operations.getJSONObject(index)
            val path = operation.getJSONArray("path")
            var owner: JSONObject? = root
            var user: JSONObject? = stored
            for (depth in 0 until path.length() - 1) owner = owner?.optJSONObject(path.getString(depth))
            for (depth in 0 until path.length() - 1) user = user?.optJSONObject(path.getString(depth))
            val key = path.getString(path.length() - 1)
            if (operation.getString("op") == "set") {
                // Config resolves schema defaults inside model arrays and
                // nested compat objects. The raw user layer proves the exact
                // durable write; the effective layer must still contain every
                // submitted value, including complete array order/length.
                val submitted = operation.get("value")
                check(user?.has(key) == true && sameJson(user.get(key), submitted) &&
                    owner?.has(key) == true && containsSubmitted(owner.get(key), submitted)) {
                    "DSH 返回的配置与已提交修改不一致"
                }
            }
            // Unset may reveal a base/default field again. The namespace user
            // document, not the effective merged value, proves persistence.
            else {
                check(user?.has(key) != true) { "DSH 用户配置仍保留已移除字段" }
                if (path.length() == 2) check(owner?.has(key) != true) { "DSH 基础配置仍保留该提供商，请修改其来源配置" }
            }
        }
    }

    private fun containsSubmitted(actual: Any?, submitted: Any?): Boolean = when {
        actual is JSONObject && submitted is JSONObject -> submitted.keys().asSequence().all { key ->
            actual.has(key) && containsSubmitted(actual.get(key), submitted.get(key))
        }
        actual is JSONArray && submitted is JSONArray -> actual.length() == submitted.length() &&
            (0 until submitted.length()).all { containsSubmitted(actual.get(it), submitted.get(it)) }
        else -> sameJson(actual, submitted)
    }

    fun orderCatalog(catalog: JSONObject, providers: List<ModelProviderConfig>): JSONObject {
        val copy = JSONObject(catalog.toString())
        val groups = copy.getJSONArray("groups")
        val order = providers.map { if (it.protected) "deepseek-official" else it.dshRoute ?: it.id }
        val rows = List(groups.length()) { groups.getJSONObject(it) }
        copy.put("groups", JSONArray(rows.sortedWith(compareBy { group -> order.indexOf(group.getString("id")).let { if (it < 0) Int.MAX_VALUE else it } })))
        return copy
    }

    private fun sameJson(left: Any?, right: Any?): Boolean = when {
        left is JSONObject && right is JSONObject -> left.keys().asSequence().toSet() == right.keys().asSequence().toSet() &&
            left.keys().asSequence().all { sameJson(left.get(it), right.get(it)) }
        left is JSONArray && right is JSONArray -> left.length() == right.length() && (0 until left.length()).all { sameJson(left.get(it), right.get(it)) }
        else -> left == right
    }
}

/** Exact pinned browser-auth/RPC envelope. Queries, cookies and response errors
 * never enter diagnostics; arbitrary endpoints and non-loopback URLs are refused. */
internal class DshLocalConfigurationRpc {
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()

    suspend fun call(accessUrl: String, endpoint: String, args: JSONObject): JSONObject {
        val allowed = setOf("settings/describe", "settings/mutate", "llm/listConfigurableProviders", "session/modelCatalog",
            "session/list", "session/projections")
        require(endpoint in allowed)
        val uri = URI(accessUrl)
        require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port in 1024..65535 && uri.rawPath == "/" &&
            uri.userInfo == null && uri.fragment == null && uri.rawQuery?.matches(Regex("token=[A-Za-z0-9_-]{43}")) == true) {
            "DSH 尚未提供有效的本机管理地址"
        }
        val bootstrap = request(Request.Builder().url(accessUrl).build()) { response ->
            check(response.code == 303 && response.header("Location") == "./" && response.header("Cache-Control") == "no-store") {
                "DSH 本机授权会话未建立"
            }
            val name = "dsh-auth-" + Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest("127.0.0.1:${uri.port}".toByteArray()))
            val cookie = response.header("Set-Cookie")?.substringBefore(';') ?: error("DSH 缺少本机会话凭据")
            check(cookie.matches(Regex("${Regex.escape(name)}=v1\\.[A-Za-z0-9_-]{1,2048}\\.[A-Za-z0-9_-]{43}"))) { "DSH 本机会话格式不匹配" }
            JSONObject().put("cookie", cookie)
        }
        val id = UUID.randomUUID().toString()
        val body = JSONObject().put("type", "client-request").put("rpcId", id).put("method", endpoint)
            .put("payload", JSONObject().put("args", args)).toString()
        require(body.toByteArray().size <= 8 * 1024 * 1024)
        return request(Request.Builder().url("http://127.0.0.1:${uri.port}/api/$endpoint")
            .header("Cookie", bootstrap.getString("cookie")).post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()) { response ->
            check(response.code == 200 && response.header("Content-Type")?.startsWith("application/json") == true) { "DSH 本机管理请求失败" }
            val bytes = ByteArrayOutputStream()
            requireNotNull(response.body).byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) { val size = input.read(buffer); if (size < 0) break
                    require(bytes.size() + size <= 8 * 1024 * 1024) { "DSH 管理响应超过上限" }; bytes.write(buffer, 0, size) }
            }
            val envelope = JSONObject(bytes.toString(Charsets.UTF_8.name()))
            check(envelope.getString("type") == "server-response" && envelope.getString("rpcId") == id) { "DSH 管理响应与请求不匹配" }
            val result = envelope.getJSONObject("result")
            if (!result.optBoolean("ok")) {
                val code = result.optJSONObject("error")?.optString("code")
                if (code == "settings/conflict") throw IOException("DSH 配置已变化，请刷新后重试")
                throw IOException("DSH 未接受配置修改，请检查服务和当前配置")
            }
            result.optJSONObject("value") ?: JSONObject()
        }
    }

    private suspend fun request(request: Request, decode: (Response) -> JSONObject): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("DSH 本机连接失败或已取消"))
            }
            override fun onResponse(call: Call, response: Response) {
                try { response.use { val value = decode(it); if (continuation.isActive) continuation.resume(value) } }
                catch (failure: Throwable) { if (continuation.isActive) continuation.resumeWithException(failure) }
            }
        })
    }
}
