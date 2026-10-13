package com.dafeiyu.mobile.data

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal interface LocalDshHistoryBackend {
    suspend fun list(): List<JSONObject>
    suspend fun read(id: String): JSONObject
    suspend fun write(artifact: JSONObject): JSONObject
    suspend fun deleteHistory(frame: JSONObject): JSONObject = error("共享历史永久删除尚未接入，原归档保留")
}

internal data class LocalDshHistoryPublication(val selectedSessionId: String?, val warnings: List<String>)
internal data class LocalDshHistoryImport(
    val snapshot: WorkspaceSnapshot,
    val warnings: List<String>,
    internal val journal: JSONObject,
    internal val expectedRevision: Long,
)

internal data class LocalDshHistoryDeletionIntent(
    val requestId: String,
    val conversationId: String,
    val conversationSha256: String,
    val sharedSessionIds: List<String>,
    val requiresDsh: Boolean,
    val phase: String,
)

internal data class LocalDshHistoryDeletionReceipt(
    val requestId: String,
    val conversationId: String,
    val conversationSha256: String,
    val sharedSessionIds: List<String>,
    val deletedAtMillis: Long,
)

/** Persistent history exchange. This calls the pinned storage backend, never an
 * Agent or prompt endpoint. Native edits and DSH forks keep separate records. */
internal class LocalDshHistoryController(
    private val directory: File,
    private val backend: LocalDshHistoryBackend,
    private val now: () -> Long = System::currentTimeMillis,
    private val snapshotForDeletion: (() -> WorkspaceSnapshot?)? = null,
) {
    constructor(context: Context, bridge: DshSharedPersistenceBridge = DshSharedPersistenceBridge(context)) :
        this(File(context.filesDir, "local-dsh-history"), androidBackend(context, bridge),
            snapshotForDeletion = { WorkspaceStore(context).initialize() })
    private val lock = locks.computeIfAbsent(directory.canonicalPath) { Mutex() }
    private val index = RemoteDshJsonFile(File(directory, "links.json"), 4 * 1024 * 1024)
    private val deletionFile = RemoteDshJsonFile(File(directory, "permanent-deletions.json"), 4 * 1024 * 1024)

    suspend fun publish(snapshot: WorkspaceSnapshot): LocalDshHistoryPublication = withContext(Dispatchers.IO) {
        lock.withLock {
            requireNoPendingDeletion()
            val state = journal()
            val links = state.getJSONObject("links")
            val available = backend.list().map { it.getJSONObject("header").getString("id") }.toSet()
            val warnings = mutableListOf<String>()
            var selected: String? = null
            for (conversation in snapshot.conversations.filter { it.archivedAtMillis == null }) {
                val messages = snapshot.messages[conversation.id].orEmpty()
                val fingerprint = fingerprint(conversation, messages)
                val saved = links.keys().asSequence().map { it to links.getJSONObject(it) }.firstOrNull { (id, item) ->
                    id in available && item.optString("nativeId") == conversation.id && item.optString("nativeFingerprint") == fingerprint
                }
                if (saved != null) {
                    requireSupportedArtifactFingerprint(saved.second)
                    if (conversation.id == snapshot.selectedConversationId) selected = saved.first
                    continue
                }
                val sessionId = "android-light-" + hash(conversation.id + "/" + fingerprint)
                val record = links.optJSONObject(sessionId) ?: JSONObject().put("nativeId", conversation.id)
                    .put("workspaceId", conversation.workspaceId).put("createdAt", now())
                    .put("nativeFingerprint", fingerprint).put("messageIds", JSONObject())
                links.put(sessionId, record)
                // Write the intent first so a retry uses the same timestamp/ID.
                save(state)
                try {
                    val provenance = JSONArray()
                    val aliases = JSONObject()
                    messages.forEach { message ->
                        aliases.put("android-" + hash("$sessionId/${message.id}/${message.role}"), message.id)
                        message.streamSnapshot?.let { activity -> provenance.put(JSONObject().put("messageId", message.id)
                            .put("activity", AssistantStreamSnapshotJson.encode(activity))) }
                    }
                    val artifact = DshSharedHistoryAdapter.fromNative(sessionId, conversation.title,
                        "/dafeiyu-workspaces/${conversation.workspaceId}", messages, record.getLong("createdAt"), provenance)
                    val artifactFingerprint = logicalArtifactFingerprint(artifact)
                    record.put("messageIds", aliases).put("artifactFingerprint", artifactFingerprint)
                        .put("artifactFingerprintVersion", ARTIFACT_FINGERPRINT_VERSION)
                    save(state)
                    val receipt = backend.write(artifact)
                    check(receipt.getString("sessionId") == sessionId) { "DSH 会话写入回执不匹配" }
                    record.put("messageIds", aliases).put("published", true).put("artifactFingerprint", artifactFingerprint)
                        .put("artifactFingerprintVersion", ARTIFACT_FINGERPRINT_VERSION)
                    save(state)
                    if (conversation.id == snapshot.selectedConversationId) selected = sessionId
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { warnings += "未能共享「${conversation.title.take(80)}」，原对话保留" }
            }
            if (snapshot.conversations.any { it.id == snapshot.selectedConversationId && it.archivedAtMillis == null })
                check(selected != null) { "当前对话未能写入 DSH，原对话保留；请重试" }
            LocalDshHistoryPublication(selected, warnings)
        }
    }

    /** The caller must durably commit this snapshot before acknowledging links. */
    suspend fun read(snapshot: WorkspaceSnapshot): LocalDshHistoryImport = withContext(Dispatchers.IO) {
        lock.withLock {
            requireNoPendingDeletion()
            val state = journal()
            val expected = state.getLong("revision")
            val links = state.getJSONObject("links")
            val workspaces = snapshot.workspaces.toMutableList()
            val conversations = snapshot.conversations.toMutableList()
            val messages = snapshot.messages.toMutableMap()
            val warnings = mutableListOf<String>()
            for (entry in backend.list()) {
                val header = entry.getJSONObject("header")
                if (header.optInt("delegationDepth") != 0 || header.optBoolean("isSeeded")) continue
                val sessionId = header.getString("id")
                try {
                    val artifact = backend.read(sessionId)
                    val history = DshSharedHistoryAdapter.decode(artifact)
                    check(history.sessionId == sessionId)
                    val artifactFingerprint = logicalArtifactFingerprint(artifact)
                    RemoteDshJsonFile(File(directory, "artifacts/${hash(sessionId)}.json"), 25 * 1024 * 1024).write(artifact)
                    val saved = links.optJSONObject(sessionId)
                    saved?.let(::requireSupportedArtifactFingerprint)
                    val original = conversations.firstOrNull { it.id == saved?.optString("nativeId") }
                    if (original?.archivedAtMillis != null) continue
                    if (original != null && saved != null && matchesStoredArtifact(saved, artifact, artifactFingerprint)) {
                        // A legacy envelope match is migrated only after the
                        // caller durably commits this import and acknowledges
                        // its journal. Keep the original nativeId and aliases.
                        saved.put("artifactFingerprint", artifactFingerprint)
                            .put("artifactFingerprintVersion", ARTIFACT_FINGERPRINT_VERSION)
                        continue
                    }
                    val workspace = original?.workspaceId ?: history.cwd?.removePrefix("/dafeiyu-workspaces/")
                        ?.takeIf { id -> workspaces.any { it.id == id } } ?: run {
                        if (history.cwd == null || history.cwd == "/workspace") snapshot.selectedWorkspaceId else {
                            val id = "dsh-space-${hash(history.cwd).take(24)}"
                            if (workspaces.none { it.id == id }) workspaces += Workspace(id,
                                history.cwd.substringAfterLast('/').ifBlank { "DSH 工作区" }.take(80))
                            id
                        }
                    }
                    val conflict = original != null && fingerprint(original, messages[original.id].orEmpty()) != saved?.optString("nativeFingerprint")
                    val nativeId = if (conflict) "dsh-fork-${hash(sessionId + artifactFingerprint).take(32)}"
                        else original?.id ?: "dsh-${hash(sessionId).take(32)}"
                    val current = conversations.firstOrNull { it.id == nativeId }
                    if (current == null && conversations.size >= MAX_WORKSPACE_CONVERSATIONS) {
                        warnings += "会话已达上限，DSH 原记录仍保留：${history.title.take(80)}"
                        continue
                    }
                    val previous = if (conflict) emptyList() else messages[nativeId].orEmpty()
                    val aliases = if (conflict) JSONObject() else saved?.optJSONObject("messageIds")?.let { JSONObject(it.toString()) } ?: JSONObject()
                    val projected = DymHistoryConverter().compatibleWithActivities(history)
                    val bySequence = history.items.associateBy { it.eventSeq }
                    val used = previous.map { it.id }.toMutableSet()
                    var nextId = (used.maxOrNull() ?: 0L) + 1L
                    val converted = projected.map { message ->
                        val eventId = bySequence[message.id - 1]?.messageId ?: "event-${message.id - 1}"
                        val id = (aliases.opt(eventId) as? Number)?.toLong() ?: run {
                            while (nextId in used) nextId++
                            nextId++.also { aliases.put(eventId, it); used += it }
                        }
                        previous.firstOrNull { it.id == id && (it.modelInput ?: it.text) == message.text }
                            ?: ChatMessage(id, message.role, message.text, message.streamSnapshot, message.modelInput)
                    }
                    require(converted.map { it.id }.distinct().size == converted.size)
                    require(converted.size <= 1000) { "会话超过原生历史保存上限，原记录保留" }
                    if (converted.isEmpty()) continue
                    val conversation = (current ?: Conversation(nativeId, history.title.take(80), workspace))
                        .copy(title = (if (conflict) "DSH 版本 · " else "") + history.title.take(64))
                    val position = conversations.indexOfFirst { it.id == nativeId }
                    if (position < 0) conversations += conversation else conversations[position] = conversation
                    messages[nativeId] = converted
                    links.put(sessionId, JSONObject().put("nativeId", nativeId).put("workspaceId", workspace)
                        .put("createdAt", history.createdAt).put("nativeFingerprint", fingerprint(conversation, converted))
                        .put("artifactFingerprint", artifactFingerprint).put("artifactFingerprintVersion", ARTIFACT_FINGERPRINT_VERSION)
                        .put("messageIds", aliases).put("published", true))
                    if (conflict) warnings += "「${history.title.take(80)}」两边都有更改，已保留两个版本，请选择要续聊的对话"
                    if (history.interrupted) warnings += "「${history.title.take(80)}」含未完成活动，仅导入历史，不恢复执行"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { warnings += "DSH 会话读取未完成，原记录保留：${sessionId.take(80)}" }
            }
            LocalDshHistoryImport(snapshot.copy(workspaces = workspaces, conversations = conversations, messages = messages),
                warnings.distinct().take(100), state, expected)
        }
    }

    suspend fun acknowledge(result: LocalDshHistoryImport) = withContext(Dispatchers.IO) {
        lock.withLock {
            requireNoPendingDeletion()
            check(journal().getLong("revision") == result.expectedRevision) { "历史共享记录已变化，原历史保留，请刷新" }
            save(result.journal)
        }
    }

    /** A stable local user intent binds every version, including uncertain
     * publications. This only records intent; no history is removed here. */
    suspend fun preparePermanentDeletion(conversation: Conversation, requestId: String = UUID.randomUUID().toString()):
        LocalDshHistoryDeletionIntent = withContext(Dispatchers.IO) {
        lock.withLock {
            require(conversation.archivedAtMillis != null && validIdentity(requestId)) { "只能永久删除已归档对话" }
            val snapshot = checkNotNull(snapshotForDeletion?.invoke()) { "无法核对持久对话，原归档保留" }
            val durable = snapshot.conversations.singleOrNull { it.id == conversation.id }
            check(durable == conversation) { "归档已变化，请刷新后重试" }
            val fingerprint = deletionFingerprint(conversation, snapshot.messages[conversation.id].orEmpty())
            val state = deletionJournal()
            val records = state.getJSONArray("intents").remoteObjects(1000)
            val existing = records.firstOrNull { it.getString("conversationId") == conversation.id && it.getString("phase") != "cancelled" }
            if (existing != null) {
                check(existing.getString("conversationSha256") == fingerprint) { "待删除对话内容已变化，原归档保留" }
                return@withLock intent(existing)
            }
            check(records.none { it.getString("requestId") == requestId }) { "永久删除请求身份冲突" }
            check(records.size < 1000) { "永久删除记录已达容量上限，原归档保留" }
            val mapping = journal()
            val links = mapping.getJSONObject("links")
            val ids = links.keys().asSequence().filter { id ->
                links.getJSONObject(id).optString("nativeId") == conversation.id
            }.sorted().toList()
            require(ids.size <= 256 && ids.all(::validIdentity)) { "共享历史版本过多，原归档保留" }
            val entry = JSONObject().put("requestId", requestId).put("conversationId", conversation.id)
                .put("conversationSha256", fingerprint).put("sessionIds", JSONArray(ids)).put("phase", "prepared")
                .put("mappingRevision", mapping.getLong("revision")).put("mappingSha256", remoteJsonHash(mapping))
                .put("createdAt", now())
            state.getJSONArray("intents").put(entry)
            deletionFile.write(state)
            intent(entry)
        }
    }

    suspend fun pendingPermanentDeletions(): List<LocalDshHistoryDeletionIntent> = withContext(Dispatchers.IO) {
        lock.withLock { deletionJournal().getJSONArray("intents").remoteObjects(1000)
            .filter { it.getString("phase") !in setOf("committed", "cancelled") }.map(::intent) }
    }

    suspend fun cancelPermanentDeletion(value: LocalDshHistoryDeletionIntent) = withContext(Dispatchers.IO) {
        lock.withLock {
            val state = deletionJournal(); val record = record(state, value)
            check(record.getString("phase") == "prepared") { "永久删除已开始，不能取消；请重试完成清理" }
            record.put("phase", "cancelled"); deletionFile.write(state)
        }
    }

    /** Calling this is the explicit confirmation (or authorized 30-day sweep).
     * A failed helper leaves a durable deleting intent and all app records. */
    suspend fun deletePermanentHistory(value: LocalDshHistoryDeletionIntent): LocalDshHistoryDeletionReceipt =
        withContext(Dispatchers.IO) {
        lock.withLock {
            val state = deletionJournal(); val entry = record(state, value)
            if (entry.getString("phase") in setOf("native_deleted", "committed")) return@withLock receipt(entry)
            check(entry.getString("phase") in setOf("prepared", "deleting")) { "永久删除请求已取消" }
            val mapping = journal()
            check(mapping.getLong("revision") == entry.getLong("mappingRevision") &&
                remoteJsonHash(mapping) == entry.getString("mappingSha256")) { "共享历史映射已变化，原归档保留" }
            entry.put("phase", "deleting"); deletionFile.write(state)
            if (value.sharedSessionIds.isNotEmpty()) {
                val frame = JSONObject().put("operation", "delete-history").put("requestId", value.requestId)
                    .put("nativeId", value.conversationId).put("sessionIds", JSONArray(value.sharedSessionIds))
                    .put("mappingRevision", entry.getLong("mappingRevision")).put("mappingSha256", entry.getString("mappingSha256"))
                    .put("confirmDelete", true)
                val reply = backend.deleteHistory(frame)
                requireNativeDeletionReceipt(reply, value.requestId, value.conversationId, value.sharedSessionIds)
                entry.put("nativeReceipt", reply)
            }
            entry.put("deletedAtMillis", now()).put("phase", "native_deleted")
            deletionFile.write(state)
            receipt(entry)
        }
    }

    /** Only call after WorkspaceStore.commitPermanentDeletion has durably
     * fenced and removed the app record. Stable native tombstones remain. */
    suspend fun acknowledgePermanentDeletion(value: LocalDshHistoryDeletionReceipt) = withContext(Dispatchers.IO) {
        lock.withLock {
            val state = deletionJournal()
            val entry = state.getJSONArray("intents").remoteObjects(1000).single { it.getString("requestId") == value.requestId }
            check(receipt(entry) == value && entry.getString("phase") in setOf("native_deleted", "committed")) { "永久删除回执不匹配" }
            if (entry.getString("phase") == "committed") return@withLock
            check(snapshotForDeletion?.invoke()?.conversations?.none { it.id == value.conversationId } == true) {
                "手机对话尚未持久删除，已保留共享映射"
            }
            for (id in value.sharedSessionIds) deleteOwnedHistoryCopy(File(directory, "artifacts"), hash(id))
            deleteOwnedHistoryCopy(File(checkNotNull(directory.parentFile), "native-model-history"), hash(value.conversationId))
            val mapping = journal(); val links = mapping.getJSONObject("links")
            for (id in value.sharedSessionIds) {
                val link = links.optJSONObject(id) ?: continue
                check(link.optString("nativeId") == value.conversationId) { "共享历史归属已变化，已保留映射" }
                links.remove(id)
            }
            if (value.sharedSessionIds.isNotEmpty()) save(mapping)
            entry.put("phase", "committed"); deletionFile.write(state)
        }
    }

    private fun requireNoPendingDeletion() {
        check(deletionJournal().getJSONArray("intents").remoteObjects(1000).none {
            it.getString("phase") !in setOf("committed", "cancelled")
        }) { "共享历史永久删除尚未完成，请完成或取消后再切换引擎" }
    }

    /** Delete only a named application-owned cache and AtomicFile siblings.
     * A partial failure is retried while the durable intent stays native_deleted. */
    private fun deleteOwnedHistoryCopy(parent: File, digest: String) {
        require(digest.matches(Regex("[0-9a-f]{64}")))
        require(!LinuxArchiveExtractor.isSymlink(parent) &&
            parent.canonicalPath.startsWith(checkNotNull(directory.parentFile).canonicalPath + File.separator)) {
            "历史副本路径越界，已保留删除回执"
        }
        if (!parent.exists()) return
        require(parent.isDirectory) { "历史副本目录无效" }
        for (suffix in listOf(".json", ".json.bak", ".json.new")) {
            val file = File(parent, digest + suffix)
            require(!LinuxArchiveExtractor.isSymlink(file) && file.canonicalFile.parentFile == parent.canonicalFile) {
                "历史副本不能是链接，已保留删除回执"
            }
            if (file.exists()) {
                require(file.isFile && file.delete() && !file.exists()) { "历史副本清理未完成，请重试" }
            }
        }
        // Flush unlinks before the committed receipt becomes durable. If this
        // fails, native_deleted remains retryable even when the copies vanished.
        val descriptor = Os.open(parent.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
        try {
            require(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) { "历史副本目录已变化，请重试" }
            Os.fsync(descriptor)
        } finally { Os.close(descriptor) }
    }

    private fun deletionJournal(): JSONObject = (deletionFile.read() ?: JSONObject().put("schema", 1).put("intents", JSONArray())).also { state ->
        require(state.getInt("schema") == 1)
        val records = state.getJSONArray("intents").remoteObjects(1000)
        require(records.map { it.getString("requestId") }.distinct().size == records.size)
        records.forEach { entry ->
            require(validIdentity(entry.getString("requestId")) && validIdentity(entry.getString("conversationId")) &&
                entry.getString("conversationSha256").matches(Regex("[0-9a-f]{64}")) &&
                entry.getString("phase") in setOf("prepared", "deleting", "native_deleted", "committed", "cancelled") &&
                entry.getLong("mappingRevision") >= 0 && entry.getString("mappingSha256").matches(Regex("[0-9a-f]{64}")))
            val ids = entry.getJSONArray("sessionIds").let { array -> (0 until array.length()).map(array::getString) }
            require(ids.size <= 256 && ids.distinct().size == ids.size && ids.all(::validIdentity))
            if (entry.getString("phase") in setOf("native_deleted", "committed")) receipt(entry)
        }
    }

    private fun record(state: JSONObject, value: LocalDshHistoryDeletionIntent): JSONObject =
        state.getJSONArray("intents").remoteObjects(1000).single { it.getString("requestId") == value.requestId }.also {
            check(intent(it).copy(phase = value.phase) == value) { "永久删除请求身份或内容不匹配" }
        }

    private fun intent(entry: JSONObject) = LocalDshHistoryDeletionIntent(entry.getString("requestId"),
        entry.getString("conversationId"), entry.getString("conversationSha256"),
        entry.getJSONArray("sessionIds").let { array -> (0 until array.length()).map(array::getString) },
        entry.getJSONArray("sessionIds").length() > 0, entry.getString("phase"))

    private fun receipt(entry: JSONObject): LocalDshHistoryDeletionReceipt {
        require(entry.getString("phase") in setOf("native_deleted", "committed") && entry.getLong("deletedAtMillis") >= 0)
        val intent = intent(entry)
        if (intent.sharedSessionIds.isNotEmpty()) requireNativeDeletionReceipt(entry.getJSONObject("nativeReceipt"),
            intent.requestId, intent.conversationId, intent.sharedSessionIds)
        return LocalDshHistoryDeletionReceipt(intent.requestId, intent.conversationId, intent.conversationSha256,
            intent.sharedSessionIds, entry.getLong("deletedAtMillis"))
    }

    private fun journal(): JSONObject = (index.read() ?: JSONObject().put("schema", 1).put("revision", 0).put("links", JSONObject())).also {
        require(it.getInt("schema") == 1 && it.getLong("revision") >= 0 && it.getJSONObject("links").length() <= 10000)
    }

    private fun save(state: JSONObject) {
        state.put("revision", Math.addExact(state.getLong("revision"), 1L))
        index.write(state)
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Mutex>()
        private const val ARTIFACT_FINGERPRINT_VERSION = 1

        /** Logical Session identity has the same shape before and after the
         * pinned persistence reader adds its inherited-event boundary. */
        private fun logicalArtifact(artifact: JSONObject): JSONObject {
            require(artifact.keys().asSequence().all { it in setOf("header", "events", "inheritedEventCount") }) {
                "共享历史包含未知封装字段，原记录保留"
            }
            val inherited = if (!artifact.has("inheritedEventCount")) 0L else {
                val value = artifact.get("inheritedEventCount") as? Number
                    ?: error("共享历史继承边界无效，原记录保留")
                val count = value.toLong()
                require(count >= 0 && value.toDouble().isFinite() && value.toDouble() <= 9_007_199_254_740_991.0 &&
                    count.toDouble() == value.toDouble()) { "共享历史继承边界无效，原记录保留" }
                count
            }
            return JSONObject().put("header", artifact.getJSONObject("header"))
                .put("events", artifact.getJSONArray("events")).put("inheritedEventCount", inherited)
        }

        private fun logicalArtifactFingerprint(artifact: JSONObject): String = remoteJsonHash(logicalArtifact(artifact))

        private fun requireSupportedArtifactFingerprint(record: JSONObject) {
            if (record.has("artifactFingerprintVersion")) {
                val version = record.get("artifactFingerprintVersion") as? Number
                require(version != null && version.toDouble() == ARTIFACT_FINGERPRINT_VERSION.toDouble()) {
                    "共享历史摘要版本不支持，原记录保留"
                }
            }
            require(record.getString("artifactFingerprint").matches(Regex("[0-9a-f]{64}"))) {
                "共享历史摘要损坏，原记录保留"
            }
        }

        private fun matchesStoredArtifact(record: JSONObject, artifact: JSONObject, normalized: String): Boolean {
            requireSupportedArtifactFingerprint(record)
            val saved = record.getString("artifactFingerprint")
            if (record.has("artifactFingerprintVersion")) return saved == normalized
            // Unversioned readers formerly hashed the exact full read envelope.
            if (saved == remoteJsonHash(artifact)) return true
            // The old writer omitted this field. A nonzero inherited boundary
            // must never be erased to make that older digest appear unchanged.
            val logical = logicalArtifact(artifact)
            return logical.getLong("inheritedEventCount") == 0L && saved == remoteJsonHash(JSONObject()
                .put("header", artifact.getJSONObject("header")).put("events", artifact.getJSONArray("events")))
        }

        private fun validIdentity(value: String) = value.matches(Regex("[A-Za-z0-9_-]{1,128}")) &&
            value !in setOf("__proto__", "constructor", "prototype")

        internal fun deletionFingerprint(conversation: Conversation, messages: List<ChatMessage>): String = remoteJsonHash(
            JSONObject().put("conversation", fingerprint(conversation, messages))
                .put("archivedAtMillis", conversation.archivedAtMillis ?: JSONObject.NULL)
                .put("archiveOrigin", conversation.archiveOrigin?.let { origin -> JSONObject()
                    .put("workspaceId", origin.workspace.id).put("workspaceName", origin.workspace.name)
                    .put("workspaceUri", origin.workspace.uri ?: JSONObject.NULL).put("workspaceOrder", origin.workspaceOrder)
                    .put("conversationOrder", origin.conversationOrder).put("directoryPaths", JSONArray(origin.directoryPaths))
                } ?: JSONObject.NULL))

        internal fun requireDurableDeletionReceipt(directory: File, value: LocalDshHistoryDeletionReceipt) {
            val state = RemoteDshJsonFile(File(directory, "permanent-deletions.json"), 4 * 1024 * 1024).read()
                ?: error("缺少持久永久删除回执，原归档保留")
            require(state.getInt("schema") == 1)
            val entry = state.getJSONArray("intents").remoteObjects(1000).single { it.getString("requestId") == value.requestId }
            val ids = entry.getJSONArray("sessionIds").let { array -> (0 until array.length()).map(array::getString) }
            check(entry.getString("phase") in setOf("native_deleted", "committed") &&
                entry.getString("conversationId") == value.conversationId && entry.getString("conversationSha256") == value.conversationSha256 &&
                entry.getLong("deletedAtMillis") == value.deletedAtMillis && ids == value.sharedSessionIds) { "永久删除回执与持久记录不匹配" }
            if (ids.isNotEmpty()) requireNativeDeletionReceipt(entry.getJSONObject("nativeReceipt"),
                value.requestId, value.conversationId, ids)
        }

        private fun requireNativeDeletionReceipt(reply: JSONObject, requestId: String, conversationId: String,
            sessionIds: List<String>) {
            check(reply.getString("requestId") == requestId && reply.getString("nativeId") == conversationId &&
                reply.getBoolean("deleted") && reply.getString("status") == "complete" &&
                reply.getString("executionDevice") == "local_phone") { "共享历史删除回执不匹配，原归档保留" }
            val results = reply.getJSONArray("items").remoteObjects(256)
            check(sessionIds.distinct().size == sessionIds.size && sessionIds.all(::validIdentity) &&
                results.map { it.getString("sessionId") }.toSet() == sessionIds.toSet() &&
                results.size == sessionIds.size && results.all { item ->
                    item.getString("status") in setOf("deleted", "not_materialized") &&
                        (item.getString("status") != "deleted" || validIdentity(item.getString("ticket")))
                }) { "共享历史尚未全部删除，原归档保留" }
        }
        private fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

        internal fun fingerprint(conversation: Conversation, messages: List<ChatMessage>): String = remoteJsonHash(
            JSONObject().put("id", conversation.id).put("workspace", conversation.workspaceId).put("title", conversation.title)
                .put("messages", JSONArray(messages.map { message -> JSONObject().put("id", message.id).put("role", message.role)
                    .put("text", message.text).put("modelInput", message.modelInput ?: JSONObject.NULL)
                    .put("attachments", JSONArray(message.attachmentIds))
                    .put("activity", message.streamSnapshot?.let(AssistantStreamSnapshotJson::encode) ?: JSONObject.NULL) })))

        private fun androidBackend(context: Context, bridge: DshSharedPersistenceBridge): LocalDshHistoryBackend {
            val sessions = File(context.filesDir, "dsh-home/sessions").apply { check(isDirectory || mkdirs()) }
            return object : LocalDshHistoryBackend {
                override suspend fun list(): List<JSONObject> {
                    val result = mutableListOf<JSONObject>()
                    var after: String? = null
                    do {
                        val page = bridge.listArtifacts(sessions, afterId = after)
                        result += page.getJSONArray("items").remoteObjects(1000)
                        require(result.size <= 20000)
                        val next = page.opt("nextAfterId") as? String
                        check(!page.getBoolean("truncated") || next != null && next != after)
                        after = next
                    } while (after != null)
                    return result
                }
                override suspend fun read(id: String): JSONObject = bridge.readArtifact(sessions, id)
                override suspend fun write(artifact: JSONObject): JSONObject = bridge.writeCompatibleArtifact(artifact)
                override suspend fun deleteHistory(frame: JSONObject): JSONObject = bridge.deleteSharedHistory(frame)
            }
        }
    }
}
