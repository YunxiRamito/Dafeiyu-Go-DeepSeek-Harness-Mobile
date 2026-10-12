package com.dafeiyu.mobile.data

import android.content.Context
import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest

/** External trees are authorization references only. We never create files inside them. */
data class Workspace(val id: String, val name: String, val uri: String? = null)
data class Conversation(
    val id: String,
    val title: String,
    val workspaceId: String,
    val archivedAtMillis: Long? = null,
)
internal data class WorkspaceSnapshot(
    val workspaces: List<Workspace>,
    val conversations: List<Conversation>,
    val messages: Map<String, List<ChatMessage>>,
    val selectedWorkspaceId: String,
    val selectedConversationId: String,
)

internal data class WorkspaceSaveResult(
    val indexCommitted: Boolean,
    val recoveryCommitted: Boolean,
    val error: String? = null,
)

internal const val ARCHIVE_RETENTION_MILLIS: Long = 30L * 24L * 60L * 60L * 1000L
internal const val MAX_WORKSPACE_CONVERSATIONS: Int = 100


private fun JSONObject.archiveTimestamp(version: Int): Long? {
    if (version < 2 || !has("archivedAtMillis") || isNull("archivedAtMillis")) return null
    return (opt("archivedAtMillis") as? Number)?.toLong()?.takeIf { it >= 0L }
}

internal fun isArchiveExpired(conversation: Conversation, nowMillis: Long): Boolean {
    val archivedAt = conversation.archivedAtMillis ?: return false
    return nowMillis >= archivedAt && nowMillis - archivedAt >= ARCHIVE_RETENTION_MILLIS
}

/** A private, bounded index; attachments use owned IDs, never external picker grants. */
internal class WorkspaceStore(context: Context) {
    internal companion object {
        /** Shared across WorkspaceStore instances so repository saves and workers serialize file access. */
        internal val INDEX_LOCK = Any()
        const val MAX_INDEX_BYTES = 8 * 1024 * 1024
        const val MAX_RECOVERY_BYTES = 64 * 1024 * 1024
    }

    val root = File(context.filesDir, "Dafeiyu-Works")
    private val index = AtomicFile(File(root, "workspace-index.json"))
    private val recovery = AtomicFile(File(root, "workspace-recovery.json"))

    fun hasRecovery(): Boolean = synchronized(INDEX_LOCK) { recoverySnapshot() != null }

    /** Conservative union: a failed/new save must not invalidate the last durable references. */
    fun persistedAttachmentIds(): Set<String> = synchronized(INDEX_LOCK) {
        buildSet {
            for (file in listOf(index, recovery)) {
                if (!exists(file)) continue
                val json = if (file === index) readJson(index, MAX_INDEX_BYTES)
                    else recoverySnapshot() ?: continue
                check(json.optInt("version") in 1..2) { "无法读取此工作区索引版本" }
                val messages = json.optJSONObject("messages") ?: continue
                for (key in messages.keys()) {
                    val items = messages.optJSONArray(key) ?: continue
                    for (i in 0 until items.length()) {
                        val ids = items.optJSONObject(i)?.optJSONArray("attachmentIds") ?: continue
                        for (j in 0 until ids.length()) add(ids.getString(j))
                    }
                }
            }
        }
    }

    fun initialize(nowMillis: Long = System.currentTimeMillis()): WorkspaceSnapshot? =
        synchronized(INDEX_LOCK) { initializeLocked(nowMillis) }

    private fun initializeLocked(nowMillis: Long): WorkspaceSnapshot? {
        check(root.isDirectory || root.mkdirs()) { "无法创建 Dafeiyu-Works" }
        val json = recoverySnapshot() ?: if (exists(index)) readJson(index, MAX_INDEX_BYTES) else {
            val defaultFolder = File(root, "默认工作区")
            check(defaultFolder.isDirectory || defaultFolder.mkdirs()) { "无法创建默认工作区" }
            return null
        }
        val version = json.optInt("version")
        check(version == 1 || version == 2) { "无法读取此工作区索引版本" }
        val workspaces = buildList<Workspace> {
            val array = json.optJSONArray("workspaces") ?: JSONArray()
            for (i in 0 until minOf(array.length(), 200)) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id")
                val name = item.optString("name").take(80)
                if (id.isNotBlank() && name.isNotBlank() && none { it.id == id }) {
                    val uri = item.optString("uri").takeIf { it.startsWith("content://") }
                    add(Workspace(id, name, uri))
                }
            }
        }
        check(workspaces.isNotEmpty()) { "本地工作区索引没有可用工作区" }
        val conversations = buildList<Conversation> {
            val array = json.optJSONArray("conversations") ?: JSONArray()
            for (i in 0 until minOf(array.length(), MAX_WORKSPACE_CONVERSATIONS)) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id")
                val workspaceId = item.optString("workspaceId")
                val archivedAtMillis = item.archiveTimestamp(version)
                if (id.isNotBlank() && workspaces.any { it.id == workspaceId } && none { it.id == id }) {
                    add(Conversation(id, item.optString("title", "新对话").take(100), workspaceId, archivedAtMillis))
                }
            }
        }
        val encodedMessages = json.optJSONObject("messages") ?: JSONObject()
        val loadedMessages = conversations.associate { conversation ->
            val array = encodedMessages.optJSONArray(conversation.id) ?: JSONArray()
            conversation.id to buildList<ChatMessage> {
                for (i in 0 until minOf(array.length(), 1000)) {
                    val item = array.optJSONObject(i) ?: continue
                    val role = item.optString("role")
                    if (role == "user" || role == "assistant") {
                        val stream = AssistantStreamSnapshotJson.decode(item.optJSONObject("streamSnapshot"))
                            ?.let(AssistantStreamSnapshotJson::restored)
                        val attachmentIds = item.optJSONArray("attachmentIds")
                        add(ChatMessage(
                            item.optLong("id"), role, item.optString("text"), stream,
                            item.optString("modelInput").takeIf { it.isNotBlank() },
                            buildList {
                                for (j in 0 until minOf(attachmentIds?.length() ?: 0, 6)) {
                                    attachmentIds?.optString(j)?.takeIf {
                                        it.matches(Regex("[a-fA-F0-9-]{36}"))
                                    }?.let { add(it) }
                                }
                            },
                        ))
                    }
                }
            }
        }
        val expiredIds = conversations.asSequence()
            .filter { isArchiveExpired(it, nowMillis) }
            .map { it.id }
            .toSet()
        val retainedConversations = conversations.filterNot { it.id in expiredIds }
        val selectedWorkspaceId = json.optString("selectedWorkspaceId")
            .takeIf { id -> workspaces.any { it.id == id } }
            ?: workspaces.first().id
        val retainedIds = retainedConversations.mapTo(HashSet()) { it.id }
        val selectedConversationId = json.optString("selectedConversationId")
            .takeIf { id -> retainedConversations.any { it.id == id && it.workspaceId == selectedWorkspaceId && it.archivedAtMillis == null } }
            ?: retainedConversations.firstOrNull { it.workspaceId == selectedWorkspaceId && it.archivedAtMillis == null }?.id.orEmpty()
        val snapshot = WorkspaceSnapshot(
            workspaces = workspaces,
            conversations = retainedConversations,
            messages = loadedMessages.filterKeys { it in retainedIds },
            selectedWorkspaceId = selectedWorkspaceId,
            selectedConversationId = selectedConversationId,
        )
        // Expired conversations and their messages are removed durably before initialization returns.
        if (expiredIds.isNotEmpty()) saveWithRecoveryLocked(snapshot)
        return snapshot
    }

    /**
     * Purges expired archives directly from the persisted index. This can be invoked by a
     * background worker without constructing a repository; only matching conversations and
     * their message entries are removed, and the updated JSON is committed through AtomicFile.
     */
    fun purgeExpiredArchives(nowMillis: Long): Int = synchronized(INDEX_LOCK) {
        val json = recoverySnapshot() ?: if (exists(index)) readJson(index, MAX_INDEX_BYTES)
            else return@synchronized 0
        val version = json.optInt("version")
        check(version == 1 || version == 2) { "无法读取此工作区索引版本" }
        if (version < 2) return@synchronized 0

        val conversations = json.optJSONArray("conversations") ?: JSONArray()
        val expiredIndexes = HashSet<Int>()
        val expiredIds = HashSet<String>()
        for (i in 0 until conversations.length()) {
            val item = conversations.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isBlank()) continue
            val conversation = Conversation(
                id = id,
                title = item.optString("title", "新对话"),
                workspaceId = item.optString("workspaceId"),
                archivedAtMillis = item.archiveTimestamp(version),
            )
            if (isArchiveExpired(conversation, nowMillis)) {
                expiredIndexes += i
                expiredIds += id
            }
        }
        if (expiredIndexes.isEmpty()) return@synchronized 0

        val retained = JSONArray()
        val retainedActiveIds = HashSet<String>()
        val retainedConversationIds = HashSet<String>()
        for (i in 0 until conversations.length()) {
            val item = conversations.opt(i)
            if (i in expiredIndexes) continue
            retained.put(item)
            val retainedConversation = item as? JSONObject ?: continue
            val id = retainedConversation.optString("id")
            retainedConversationIds += id
            if (retainedConversation.archiveTimestamp(version) == null) retainedActiveIds += id
        }
        json.put("conversations", retained)
        val messages = json.optJSONObject("messages") ?: JSONObject()
        expiredIds.filterNot { it in retainedConversationIds }.forEach { messages.remove(it) }
        json.put("messages", messages)

        val selectedId = json.optString("selectedConversationId")
        if (selectedId in expiredIds && selectedId !in retainedActiveIds) {
            val selectedWorkspaceId = json.optString("selectedWorkspaceId", "default")
            var replacementId = ""
            for (i in 0 until retained.length()) {
                val item = retained.optJSONObject(i) ?: continue
                if (item.optString("workspaceId") == selectedWorkspaceId && item.archiveTimestamp(version) == null) {
                    replacementId = item.optString("id")
                    break
                }
            }
            json.put("selectedConversationId", replacementId)
        }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_INDEX_BYTES) {
            writeAtomically(bytes)
            clearRecovery()
        } else {
            writeRecovery(bytes)
        }
        expiredIndexes.size
    }

    fun createInternalWorkspace(id: String) {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        val folder = File(root, id)
        check(folder.isDirectory || folder.mkdirs()) { "无法创建工作目录" }
    }

    /** External SAF trees are references only; this method never touches their URI. */
    fun canDeleteInternalWorkspaceFolder(workspace: Workspace): Boolean =
        synchronized(INDEX_LOCK) {
            if (!linuxWorkspaceFolderEmpty(workspace.id)) return@synchronized false
            if (workspace.uri != null) return@synchronized true
            val folder = checkedInternalWorkspaceFolder(workspace.id)
            !folder.exists() || (folder.isDirectory && folder.listFiles()?.isEmpty() == true)
        }

    /**
     * Deletes one already validated, empty application-owned directory. The operation is
     * deliberately non-recursive so an unexpected child preserves the directory.
     */
    fun deleteInternalWorkspaceFolderIfEmpty(workspace: Workspace): Boolean =
        synchronized(INDEX_LOCK) {
            if (!linuxWorkspaceFolderEmpty(workspace.id)) return@synchronized false
            if (workspace.uri != null) return@synchronized true
            val folder = checkedInternalWorkspaceFolder(workspace.id)
            if (!folder.exists()) return@synchronized true
            if (!folder.isDirectory || folder.listFiles()?.isEmpty() != true) return@synchronized false
            folder.delete()
        }

    /** Stream reference metadata; stale recovery references conservatively retain the folder. */
    fun deleteUnreferencedInternalWorkspaceFolder(workspace: Workspace): Boolean =
        synchronized(INDEX_LOCK) {
            if (!exists(index) && !exists(recovery)) return@synchronized false
            for (file in listOf(index, recovery)) {
                if (!exists(file)) continue
                val limit = if (file === index) MAX_INDEX_BYTES else MAX_RECOVERY_BYTES
                file.openRead().use { input ->
                    JsonReader(BoundedWorkspaceInput(input, limit).reader(Charsets.UTF_8)).use { reader ->
                        val referenced = if (file === recovery) {
                            reader.beginObject()
                            var schema = 0
                            var foundSchema = false
                            var foundSnapshot = false
                            var foundReference = false
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "schema" -> {
                                        check(!foundSchema) { "恢复文件包含重复版本字段" }
                                        foundSchema = true
                                        schema = reader.nextInt()
                                    }
                                    "snapshot" -> {
                                        check(!foundSnapshot) { "恢复文件包含重复快照字段" }
                                        foundSnapshot = true
                                        foundReference = readWorkspaceReference(reader, workspace.id)
                                    }
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                            check(schema == 1 && foundSnapshot) { "无法确认恢复文件的工作区引用" }
                            foundReference
                        } else readWorkspaceReference(reader, workspace.id)
                        check(reader.peek() == JsonToken.END_DOCUMENT) { "工作区索引包含额外内容" }
                        if (referenced) return@synchronized false
                    }
                }
            }
            deleteInternalWorkspaceFolderIfEmpty(workspace)
        }

    private fun readWorkspaceReference(reader: JsonReader, workspaceId: String): Boolean {
        reader.beginObject()
        var version = 0
        var foundVersion = false
        var foundWorkspaces = false
        var referenced = false
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "version" -> {
                    check(!foundVersion) { "工作区索引包含重复版本字段" }
                    foundVersion = true
                    version = reader.nextInt()
                }
                "workspaces" -> {
                    check(!foundWorkspaces) { "工作区索引包含重复目录字段" }
                    foundWorkspaces = true
                    reader.beginArray()
                    while (reader.hasNext()) {
                        reader.beginObject()
                        var foundId = false
                        while (reader.hasNext()) {
                            if (reader.nextName() == "id") {
                                check(!foundId) { "工作区包含重复 id 字段" }
                                foundId = true
                                if (reader.nextString() == workspaceId) referenced = true
                            } else reader.skipValue()
                        }
                        reader.endObject()
                        check(foundId) { "工作区缺少 id 字段" }
                    }
                    reader.endArray()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        check(version in 1..2 && foundWorkspaces) { "无法确认索引的工作区引用" }
        return referenced
    }

    private class BoundedWorkspaceInput(input: InputStream, private val limit: Int) : FilterInputStream(input) {
        private var count = 0
        override fun read(): Int = super.read().also {
            if (it >= 0) count++
            check(count <= limit) { "工作区文件过大，已保留原文件" }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, minOf(length, limit + 1 - count)).also {
                if (it > 0) count += it
                check(count <= limit) { "工作区文件过大，已保留原文件" }
            }
    }

    /** Scripts have an owned working copy even when the visible workspace is a SAF reference. */
    private fun linuxWorkspaceFolderEmpty(id: String): Boolean {
        require(id.matches(Regex("[a-zA-Z0-9-]+"))) { "工作目录 id 无效" }
        val base = File(root.parentFile, "linux-workspaces")
        val folder = File(base, id)
        if (base.canonicalFile != base.absoluteFile || folder.canonicalFile != folder.absoluteFile ||
            java.nio.file.Files.isSymbolicLink(base.toPath()) ||
            java.nio.file.Files.isSymbolicLink(folder.toPath())) return false
        return !folder.exists() || folder.isDirectory && folder.listFiles()?.isEmpty() == true
    }

    private fun checkedInternalWorkspaceFolder(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]+"))) { "工作目录 id 无效" }
        check(!root.exists() || root.isDirectory) { "工作区根目录路径不可用" }
        val folder = File(root, if (id == "default") "默认工作区" else id)
        check(folder.canonicalFile.parentFile == root.canonicalFile) { "工作目录路径越界" }
        check(root.canonicalFile == root.absoluteFile &&
            !java.nio.file.Files.isSymbolicLink(root.toPath())) { "工作区根目录路径不可用" }
        check(!java.nio.file.Files.isSymbolicLink(folder.toPath())) { "工作目录路径不可用" }
        return folder
    }

    fun save(snapshot: WorkspaceSnapshot) = synchronized(INDEX_LOCK) { saveLocked(snapshot) }

    private fun saveLocked(snapshot: WorkspaceSnapshot) {
        val bytes = encode(snapshot)
        check(bytes.size <= MAX_INDEX_BYTES) { "本地对话索引已超过 8MiB，无法保存" }
        writeAtomically(bytes)
        clearRecovery()
    }

    /** Uses the same exact bytes as save; preserves complete output when the index cannot commit. */
    fun saveWithRecovery(snapshot: WorkspaceSnapshot): WorkspaceSaveResult =
        synchronized(INDEX_LOCK) { saveWithRecoveryLocked(snapshot) }

    private fun saveWithRecoveryLocked(snapshot: WorkspaceSnapshot): WorkspaceSaveResult {
        val bytes = encode(snapshot)
        try {
            check(bytes.size <= MAX_INDEX_BYTES) { "本地对话索引已超过 8MiB，无法保存" }
            writeAtomically(bytes)
            clearRecovery()
            return WorkspaceSaveResult(indexCommitted = true, recoveryCommitted = false)
        } catch (error: Exception) {
            try {
                writeRecovery(bytes)
            } catch (recoveryError: Exception) {
                recoveryError.addSuppressed(error)
                throw recoveryError
            }
            return WorkspaceSaveResult(false, true, error.message ?: "索引写入失败")
        }
    }

    /** No message source is truncated to make a size check pass. */
    fun encode(snapshot: WorkspaceSnapshot): ByteArray {
        val json = JSONObject().put("version", 2)
            .put("selectedWorkspaceId", snapshot.selectedWorkspaceId)
        json.put("workspaces", JSONArray().apply {
            snapshot.workspaces.forEach { workspace ->
                put(JSONObject().put("id", workspace.id).put("name", workspace.name)
                    .apply { workspace.uri?.let { put("uri", it) } })
            }
        })
        // A foreground snapshot can have been captured before a worker's purge. Filter
        // expired archives again while writing so that a delayed save cannot resurrect them.
        val persistedConversations = snapshot.conversations
            .filterNot { isArchiveExpired(it, System.currentTimeMillis()) }
            .take(MAX_WORKSPACE_CONVERSATIONS)
        val persistedConversationIds = persistedConversations.mapTo(HashSet()) { it.id }
        val selectedConversationId = snapshot.selectedConversationId
            .takeIf { it in persistedConversationIds && persistedConversations.any { c ->
                c.id == it && c.archivedAtMillis == null
            } }
            ?: persistedConversations.firstOrNull { it.workspaceId == snapshot.selectedWorkspaceId && it.archivedAtMillis == null }
                ?.id.orEmpty()
        json.put("selectedConversationId", selectedConversationId)
        json.put("conversations", JSONArray().apply {
            persistedConversations.forEach { conversation ->
                put(JSONObject().put("id", conversation.id).put("title", conversation.title)
                    .put("workspaceId", conversation.workspaceId)
                    .apply { conversation.archivedAtMillis?.let { put("archivedAtMillis", it) } })
            }
        })
        val knownConversationIds = persistedConversationIds
        json.put("messages", JSONObject().apply {
            snapshot.messages.forEach { (id, messages) ->
                if (id in knownConversationIds) put(id, JSONArray().apply {
                    messages.takeLast(1000).forEach {
                        put(JSONObject().put("id", it.id).put("role", it.role).put("text", it.text)
                            .apply {
                                it.streamSnapshot?.let { stream -> put("streamSnapshot", AssistantStreamSnapshotJson.encode(stream)) }
                                it.modelInput?.let { input -> put("modelInput", input) }
                                if (it.attachmentIds.isNotEmpty()) put("attachmentIds", JSONArray(it.attachmentIds.take(6)))
                            })
                    }
                })
            }
        })
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    private fun writeAtomically(bytes: ByteArray) {
        writeFile(index, bytes, MAX_INDEX_BYTES)
    }

    private fun writeFile(file: AtomicFile, bytes: ByteArray, maxBytes: Int) {
        check(bytes.size <= maxBytes) { "本地恢复文件超过容量上限，内容仍在内存中，请导出回复并释放容量后重试" }
        check(root.isDirectory || root.mkdirs()) { "无法创建 Dafeiyu-Works" }
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private fun clearRecovery() {
        recovery.delete()
        check(!exists(recovery)) { "恢复文件尚未合并，请重试保存" }
    }

    private fun exists(file: AtomicFile): Boolean =
        file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()

    private fun writeRecovery(bytes: ByteArray) {
        check(bytes.size <= MAX_RECOVERY_BYTES) { "本地恢复文件超过容量上限，内容仍在内存中，请导出回复并释放容量后重试" }
        val journal = JSONObject().put("schema", 1)
            .put("baseIndexSha256", indexDigest())
            .put("snapshot", JSONObject(String(bytes, Charsets.UTF_8)))
        writeFile(recovery, journal.toString().toByteArray(Charsets.UTF_8), MAX_RECOVERY_BYTES)
    }

    private fun recoverySnapshot(): JSONObject? {
        if (!exists(recovery)) return null
        val journal = readJson(recovery, MAX_RECOVERY_BYTES)
        check(journal.getInt("schema") == 1) { "无法读取恢复文件版本，已保留文件" }
        val snapshot = journal.getJSONObject("snapshot")
        val currentDigest = indexDigest()
        // An older journal must not resurrect data after a newer index commit/crash before reclamation.
        if (currentDigest != journal.getString("baseIndexSha256") &&
            currentDigest != digest(snapshot.toString().toByteArray(Charsets.UTF_8))) return null
        return snapshot
    }

    private fun indexDigest(): String =
        if (exists(index)) digest(readBytes(index, MAX_INDEX_BYTES)) else ""

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun readJson(file: AtomicFile, maxBytes: Int): JSONObject =
        JSONObject(String(readBytes(file, maxBytes), Charsets.UTF_8))

    private fun readBytes(file: AtomicFile, maxBytes: Int): ByteArray {
        val bytes = file.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
                if (count < 0) break
                if (count == 0) continue
                output.write(buffer, 0, count)
                check(output.size() <= maxBytes) { "工作区文件过大，已保留原文件" }
            }
            output.toByteArray()
        }
        check(bytes.size <= maxBytes) { "工作区文件过大，已保留原文件" }
        return bytes
    }
}
