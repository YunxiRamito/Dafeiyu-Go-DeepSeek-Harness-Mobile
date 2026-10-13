package com.dafeiyu.mobile.data

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioned, Android-owned backup data layer.
 *
 * This class only serializes data explicitly listed by [BackupInput]. It does not
 * serialize arbitrary repository state, so credentials, attachment URIs and device
 * identifiers cannot enter the backup accidentally.
 */
class AndroidBackupStore {
    companion object {
        const val SCHEMA = "dafeiyu-android-backup"
        const val SCHEMA_VERSION = 4
        const val MAX_BACKUP_BYTES = 2 * 1024 * 1024
        const val MAX_WORKSPACES = 200
        const val MAX_CONVERSATIONS = 100
        const val MAX_MESSAGES_PER_CONVERSATION = 1_000
        const val MAX_MESSAGES_TOTAL = 10_000

        private const val MAX_ID_LENGTH = 128
        private const val MAX_WORKSPACE_NAME_LENGTH = 80
        private const val MAX_CONVERSATION_TITLE_LENGTH = 100
        private const val MAX_MESSAGE_LENGTH = 65_536
        private const val MAX_ACTIVITY_ITEMS = 512
        private const val MAX_STREAM_BYTES = 512 * 1024

        private val THEME_MODES = setOf("system", "light", "dark")
        private val DOWNLOAD_SOURCES = setOf("official", "cdn", "backend")
        private val MESSAGE_ROLES = setOf("user", "assistant")
        private val SAFE_ID = Regex("[A-Za-z0-9._:-]{1,$MAX_ID_LENGTH}")
        private val FORBIDDEN_KEYS = setOf(
            "uri",
            "uris",
            "attachment",
            "attachments",
            "apikey",
            "apikeys",
            "api_key",
            "api_keys",
            "credential",
            "credentials",
            "token",
            "tokens",
            "secret",
            "secrets",
            "password",
            "privatekey",
            "private_key",
            "installationid",
            "installation_id",
            "machineid",
            "machine_id",
            "deviceid",
            "device_id",
            "serial",
            "serialnumber",
            "androidid",
        )
    }

    /** A workspace snapshot intentionally has no external-tree URI field. */
    data class BackupWorkspace(
        val id: String,
        val name: String,
    )

    data class BackupConversation(
        val id: String,
        val title: String,
        val workspaceId: String,
        val archivedAtMillis: Long? = null,
        val archiveOrigin: BackupArchiveOrigin? = null,
    )

    /** Portable provenance contains no external grants or machine-specific paths. */
    data class BackupArchiveOrigin(
        val workspaceId: String,
        val workspaceName: String,
        val workspaceOrder: Int,
        val conversationOrder: Int,
    )

    data class BackupMessage(
        val id: Long,
        val role: String,
        val text: String,
        val streamSnapshot: AssistantStreamSnapshot? = null,
        val modelInput: String? = null,
    )

    /** Settings allowlist. Do not add credentials or device identity to this type. */
    data class BackupSettings(
        val themeMode: String = "system",
        val reduceTransparency: Boolean = false,
        val reduceMotion: Boolean = false,
        val notificationsEnabled: Boolean = true,
        val keepRunning: Boolean = true,
        val downloadSource: String = "official",
    )

    /** Public snapshot boundary for callers whose repository fields are private. */
    data class BackupInput(
        val workspaces: List<BackupWorkspace>,
        val conversations: List<BackupConversation>,
        val messages: Map<String, List<BackupMessage>>,
        val selectedWorkspaceId: String,
        val selectedConversationId: String,
        val settings: BackupSettings,
    )

    /** Immutable result of a fully validated import. It does not mutate app state. */
    data class ValidatedBackup(
        val schema: String,
        val schemaVersion: Int,
        val exportedAtEpochMillis: Long,
        val workspaces: List<BackupWorkspace>,
        val conversations: List<BackupConversation>,
        val messages: Map<String, List<BackupMessage>>,
        val selectedWorkspaceId: String,
        val selectedConversationId: String,
        val settings: BackupSettings,
    )

    /**
     * Captures the current app state without exporting any URI or device identity.
     * The persisted WorkspaceStore supplies messages for non-selected conversations;
     * the selected conversation is overlaid with the repository's current in-memory
     * messages so a recent edit is included even before the debounced save completes.
     */
    fun capture(repository: DemoRepository, context: Context): BackupInput {
        val currentWorkspaces = repository.workspaces.map { BackupWorkspace(it.id, it.name) }
        val currentConversations = repository.conversations.map {
            BackupConversation(it.id, it.title, it.workspaceId, it.archivedAtMillis,
                it.archiveOrigin?.let { origin -> BackupArchiveOrigin(origin.workspace.id, origin.workspace.name,
                    origin.workspaceOrder, origin.conversationOrder) })
        }

        val persisted = runCatching {
            WorkspaceStore(context.applicationContext).initialize()
        }.getOrNull()

        val messages = currentConversations.associate { conversation ->
            val persistedMessages = persisted?.messages?.get(conversation.id).orEmpty().map {
                portableMessage(it)
            }
            val currentMessages = if (conversation.id == repository.selectedConversationId) {
                repository.messages.map(::portableMessage)
            } else {
                persistedMessages
            }
            conversation.id to currentMessages
        }

        return BackupInput(
            workspaces = currentWorkspaces,
            conversations = currentConversations,
            messages = messages,
            selectedWorkspaceId = repository.selectedWorkspaceId,
            selectedConversationId = repository.selectedConversationId,
            settings = BackupSettings(
                themeMode = repository.themeMode,
                reduceTransparency = repository.reduceTransparency,
                reduceMotion = repository.reduceMotion,
                notificationsEnabled = repository.notificationsEnabled,
                keepRunning = repository.keepRunning,
                downloadSource = repository.downloadSource,
            ),
        )
    }

    /** Exports a captured snapshot as bounded UTF-8 JSON; no file is created. */
    fun export(input: BackupInput, exportedAtEpochMillis: Long = System.currentTimeMillis()): ByteArray {
        validateInput(input)
        require(exportedAtEpochMillis >= 0L) { "导出时间无效" }

        val json = JSONObject()
            .put("schema", SCHEMA)
            .put("schemaVersion", SCHEMA_VERSION)
            .put("exportedAtEpochMillis", exportedAtEpochMillis)
            .put("selectedWorkspaceId", input.selectedWorkspaceId)
            .put("selectedConversationId", input.selectedConversationId)
            .put("workspaces", JSONArray().apply {
                input.workspaces.forEach { workspace ->
                    put(JSONObject()
                        .put("id", workspace.id)
                        .put("name", workspace.name))
                }
            })
            .put("conversations", JSONArray().apply {
                input.conversations.forEach { conversation ->
                    put(JSONObject()
                        .put("id", conversation.id)
                        .put("title", conversation.title)
                        .put("workspaceId", conversation.workspaceId)
                        .put("archivedAtMillis", conversation.archivedAtMillis ?: JSONObject.NULL)
                        .apply { conversation.archiveOrigin?.let { origin ->
                            put("archiveOrigin", JSONObject().put("workspaceId", origin.workspaceId)
                                .put("workspaceName", origin.workspaceName).put("workspaceOrder", origin.workspaceOrder)
                                .put("conversationOrder", origin.conversationOrder))
                        } })
                }
            })
            .put("messages", JSONObject().apply {
                input.conversations.forEach { conversation ->
                    put(conversation.id, JSONArray().apply {
                        input.messages.getValue(conversation.id).forEach { message ->
                            put(JSONObject()
                                .put("id", message.id)
                                .put("role", message.role)
                                .put("text", message.text)
                                .apply {
                                    message.streamSnapshot?.let { put("streamSnapshot", AssistantStreamSnapshotJson.encode(it)) }
                                    message.modelInput?.let { put("modelInput", it) }
                                })
                        }
                    })
                }
            })
            .put("settings", JSONObject()
                .put("themeMode", input.settings.themeMode)
                .put("reduceTransparency", input.settings.reduceTransparency)
                .put("reduceMotion", input.settings.reduceMotion)
                .put("notificationsEnabled", input.settings.notificationsEnabled)
                .put("keepRunning", input.settings.keepRunning)
                .put("downloadSource", input.settings.downloadSource))

        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BACKUP_BYTES) {
            "备份超过 ${MAX_BACKUP_BYTES / 1024 / 1024} MiB 限制"
        }
        return bytes
    }

    /** Convenience capture + export entry point for a repository-backed backup. */
    fun export(repository: DemoRepository, context: Context): ByteArray =
        export(capture(repository, context))

    /**
     * Writes a validated backup directly to a Storage Access Framework destination.
     * The caller owns the user-facing document picker; this method only writes the
     * already bounded JSON and reports a missing or unwritable destination.
     */
    fun exportTo(
        context: Context,
        destination: Uri,
        input: BackupInput,
        exportedAtEpochMillis: Long = System.currentTimeMillis(),
    ): Int {
        val bytes = export(input, exportedAtEpochMillis)
        val output = context.contentResolver.openOutputStream(destination, "wt")
            ?: throw IllegalStateException("无法打开备份目标")
        output.use {
            it.write(bytes)
            it.flush()
        }
        return bytes.size
    }

    /** Captures and writes a repository-backed backup to a SAF destination. */
    fun exportTo(
        context: Context,
        destination: Uri,
        repository: DemoRepository,
    ): Int = exportTo(context, destination, capture(repository, context))

    /** Reads and validates a backup from a Storage Access Framework document. */
    fun importFrom(context: Context, source: Uri): ValidatedBackup {
        val input = context.contentResolver.openInputStream(source)
            ?: throw IllegalStateException("无法读取备份文件")
        return input.use(::import)
    }

    /** Reads at most [MAX_BACKUP_BYTES] plus one byte and validates the complete JSON. */
    fun import(input: InputStream): ValidatedBackup = import(readBounded(input))

    /** Validates a UTF-8 JSON backup and returns data ready for a caller to apply. */
    fun import(bytes: ByteArray): ValidatedBackup {
        require(bytes.size <= MAX_BACKUP_BYTES) {
            "备份超过 ${MAX_BACKUP_BYTES / 1024 / 1024} MiB 限制"
        }
        require(bytes.isNotEmpty()) { "备份文件为空" }

        val json = try {
            JSONObject(bytes.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            throw BackupFormatException("备份不是有效的 JSON", error)
        }
        return parseAndValidate(json)
    }

    private fun parseAndValidate(json: JSONObject): ValidatedBackup {
        rejectUnknownKeys(
            json,
            setOf(
                "schema",
                "schemaVersion",
                "exportedAtEpochMillis",
                "selectedWorkspaceId",
                "selectedConversationId",
                "workspaces",
                "conversations",
                "messages",
                "settings",
            ),
            "根对象",
        )
        rejectForbiddenKeys(json, "根对象")

        val schema = requiredString(json, "schema", "根对象", 64)
        if (schema != SCHEMA) throw BackupFormatException("不支持的备份类型")
        val version = requiredInt(json, "schemaVersion", "根对象")
        if (version !in 1..SCHEMA_VERSION) throw BackupFormatException("不支持的备份版本：$version")
        val exportedAt = requiredLong(json, "exportedAtEpochMillis", "根对象")
        if (exportedAt < 0L) throw BackupFormatException("导出时间无效")
        val selectedWorkspaceId = requiredString(json, "selectedWorkspaceId", "根对象", MAX_ID_LENGTH)
        val selectedConversationId = requiredString(json, "selectedConversationId", "根对象", MAX_ID_LENGTH, allowBlank = true)

        val workspaces = parseWorkspaces(requiredArray(json, "workspaces", "根对象"))
        val workspaceIds = workspaces.map { it.id }.toSet()
        if (selectedWorkspaceId !in workspaceIds) {
            throw BackupFormatException("选中的工作区不存在")
        }

        val conversations = parseConversations(requiredArray(json, "conversations", "根对象"), workspaceIds, version)
        val conversationIds = conversations.map { it.id }.toSet()
        if (selectedConversationId.isNotEmpty()) {
            val selected = conversations.firstOrNull { it.id == selectedConversationId }
                ?: throw BackupFormatException("选中的会话不存在")
            if (selected.workspaceId != selectedWorkspaceId) {
                throw BackupFormatException("选中的会话不属于选中的工作区")
            }
        }

        val messages = parseMessages(
            requiredObject(json, "messages", "根对象"),
            conversations,
            conversationIds,
            version,
        )
        val settings = parseSettings(requiredObject(json, "settings", "根对象"))

        return ValidatedBackup(
            schema = schema,
            schemaVersion = version,
            exportedAtEpochMillis = exportedAt,
            workspaces = workspaces,
            conversations = conversations,
            messages = messages,
            selectedWorkspaceId = selectedWorkspaceId,
            selectedConversationId = selectedConversationId,
            settings = settings,
        )
    }

    private fun validateInput(input: BackupInput) {
        if (input.workspaces.isEmpty()) throw BackupFormatException("至少需要一个工作区")
        if (input.workspaces.size > MAX_WORKSPACES) throw BackupFormatException("工作区数量超过限制")
        if (input.conversations.size > MAX_CONVERSATIONS) throw BackupFormatException("会话数量超过限制")

        val workspaceIds = input.workspaces.map {
            validateId(it.id, "工作区 id")
            validateText(it.name, "工作区名称", MAX_WORKSPACE_NAME_LENGTH, allowBlank = false)
            it.id
        }
        if (workspaceIds.size != workspaceIds.toSet().size) throw BackupFormatException("工作区 id 重复")
        val workspaceIdSet = workspaceIds.toSet()
        if (input.selectedWorkspaceId !in workspaceIdSet) throw BackupFormatException("选中的工作区不存在")

        val conversationIds = input.conversations.map {
            validateId(it.id, "会话 id")
            validateText(it.title, "会话标题", MAX_CONVERSATION_TITLE_LENGTH, allowBlank = false)
            if (it.archivedAtMillis != null && it.archivedAtMillis < 0L) throw BackupFormatException("归档时间无效")
            it.archiveOrigin?.let { origin -> validateArchiveOrigin(origin, it.workspaceId) }
            if (it.archiveOrigin != null && it.archivedAtMillis == null) throw BackupFormatException("未归档对话不能携带归档归属")
            if (it.workspaceId !in workspaceIdSet && (it.archivedAtMillis == null || it.archiveOrigin == null)) {
                throw BackupFormatException("会话引用了不存在的工作区")
            }
            it.id
        }
        if (conversationIds.size != conversationIds.toSet().size) throw BackupFormatException("会话 id 重复")
        val conversationIdSet = conversationIds.toSet()
        if (input.selectedConversationId.isNotEmpty()) {
            if (input.selectedConversationId !in conversationIdSet) throw BackupFormatException("选中的会话不存在")
            val conversation = input.conversations.first { it.id == input.selectedConversationId }
            if (conversation.workspaceId != input.selectedWorkspaceId) {
                throw BackupFormatException("选中的会话不属于选中的工作区")
            }
        }

        var totalMessages = 0
        input.conversations.forEach { conversation ->
            val values = input.messages[conversation.id]
                ?: throw BackupFormatException("缺少会话消息数组：${conversation.id}")
            if (values.size > MAX_MESSAGES_PER_CONVERSATION) {
                throw BackupFormatException("单个会话消息数量超过限制")
            }
            totalMessages += values.size
            if (totalMessages > MAX_MESSAGES_TOTAL) throw BackupFormatException("消息总数超过限制")
            values.forEach { message ->
                if (message.id < 0L) throw BackupFormatException("消息 id 无效")
                if (message.role !in MESSAGE_ROLES) throw BackupFormatException("消息角色无效")
                validateText(message.text, "消息正文", MAX_MESSAGE_LENGTH, allowBlank = true)
                message.modelInput?.let { validateText(it, "兼容消息上下文", MAX_MESSAGE_LENGTH, allowBlank = true) }
                message.streamSnapshot?.let {
                    if (message.role != "assistant") throw BackupFormatException("只有助手消息可包含活动记录")
                    validateStreamSnapshot(it)
                }
            }
        }
        if (input.messages.keys != conversationIdSet) throw BackupFormatException("消息映射包含未知会话")
        validateSettings(input.settings)
    }

    private fun parseWorkspaces(array: JSONArray): List<BackupWorkspace> {
        if (array.length() == 0) throw BackupFormatException("至少需要一个工作区")
        if (array.length() > MAX_WORKSPACES) throw BackupFormatException("工作区数量超过限制")
        val result = ArrayList<BackupWorkspace>(array.length())
        repeat(array.length()) { index ->
            val item = requiredObject(array, index, "workspaces[$index]")
            rejectUnknownKeys(item, setOf("id", "name"), "workspaces[$index]")
            rejectForbiddenKeys(item, "workspaces[$index]")
            result += BackupWorkspace(
                requiredId(item, "id", "workspaces[$index]"),
                requiredString(item, "name", "workspaces[$index]", MAX_WORKSPACE_NAME_LENGTH),
            )
        }
        if (result.map { it.id }.toSet().size != result.size) throw BackupFormatException("工作区 id 重复")
        return result
    }

    private fun parseConversations(array: JSONArray, workspaceIds: Set<String>, schemaVersion: Int): List<BackupConversation> {
        if (array.length() > MAX_CONVERSATIONS) throw BackupFormatException("会话数量超过限制")
        val result = ArrayList<BackupConversation>(array.length())
        repeat(array.length()) { index ->
            val item = requiredObject(array, index, "conversations[$index]")
            val allowedKeys = when {
                schemaVersion >= 3 -> setOf("id", "title", "workspaceId", "archivedAtMillis", "archiveOrigin")
                schemaVersion >= 2 -> setOf("id", "title", "workspaceId", "archivedAtMillis")
                else -> setOf("id", "title", "workspaceId")
            }
            rejectUnknownKeys(item, allowedKeys, "conversations[$index]")
            rejectForbiddenKeys(item, "conversations[$index]")
            val workspaceId = requiredId(item, "workspaceId", "conversations[$index]")
            val archivedAtMillis = if (schemaVersion >= 2 && item.has("archivedAtMillis") && !item.isNull("archivedAtMillis")) {
                requiredLong(item, "archivedAtMillis", "conversations[$index]").also {
                    if (it < 0L) throw BackupFormatException("归档时间无效")
                }
            } else null
            val archiveOrigin = if (schemaVersion >= 3 && item.has("archiveOrigin")) {
                val origin = requiredObject(item, "archiveOrigin", "conversations[$index]")
                rejectUnknownKeys(origin, setOf("workspaceId", "workspaceName", "workspaceOrder", "conversationOrder"), "archiveOrigin")
                BackupArchiveOrigin(requiredId(origin, "workspaceId", "archiveOrigin"),
                    requiredString(origin, "workspaceName", "archiveOrigin", MAX_WORKSPACE_NAME_LENGTH),
                    requiredInt(origin, "workspaceOrder", "archiveOrigin"),
                    requiredInt(origin, "conversationOrder", "archiveOrigin"))
                    .also { validateArchiveOrigin(it, workspaceId) }
            } else null
            if (archiveOrigin != null && archivedAtMillis == null) throw BackupFormatException("未归档对话不能携带归档归属")
            if (workspaceId !in workspaceIds && (archivedAtMillis == null || archiveOrigin == null)) {
                throw BackupFormatException("会话引用了不存在的工作区")
            }
            result += BackupConversation(
                requiredId(item, "id", "conversations[$index]"),
                requiredString(item, "title", "conversations[$index]", MAX_CONVERSATION_TITLE_LENGTH),
                workspaceId,
                archivedAtMillis,
                archiveOrigin,
            )
        }
        if (result.map { it.id }.toSet().size != result.size) throw BackupFormatException("会话 id 重复")
        return result
    }

    private fun validateArchiveOrigin(origin: BackupArchiveOrigin, workspaceId: String) {
        validateId(origin.workspaceId, "archiveOrigin.workspaceId")
        validateText(origin.workspaceName, "archiveOrigin.workspaceName", MAX_WORKSPACE_NAME_LENGTH, false)
        if (origin.workspaceId != workspaceId || origin.workspaceOrder < 0 || origin.conversationOrder < 0) {
            throw BackupFormatException("归档工作区归属无效")
        }
    }

    private fun parseMessages(
        json: JSONObject,
        conversations: List<BackupConversation>,
        conversationIds: Set<String>,
        schemaVersion: Int,
    ): Map<String, List<BackupMessage>> {
        rejectForbiddenKeys(json, "messages")
        val keys = json.keys().asSequence().toSet()
        if (keys != conversationIds) throw BackupFormatException("消息映射与会话列表不一致")

        var totalMessages = 0
        return conversations.associate { conversation ->
            val array = requiredArray(json, conversation.id, "messages")
            if (array.length() > MAX_MESSAGES_PER_CONVERSATION) {
                throw BackupFormatException("单个会话消息数量超过限制")
            }
            val parsed = ArrayList<BackupMessage>(array.length())
            repeat(array.length()) { index ->
                val item = requiredObject(array, index, "messages.${conversation.id}[$index]")
                rejectUnknownKeys(item, if (schemaVersion >= 4) setOf("id", "role", "text", "streamSnapshot", "modelInput")
                    else setOf("id", "role", "text"), "messages.${conversation.id}[$index]")
                rejectForbiddenKeys(item, "messages.${conversation.id}[$index]")
                val id = requiredLong(item, "id", "messages.${conversation.id}[$index]")
                if (id < 0L) throw BackupFormatException("消息 id 无效")
                val role = requiredString(item, "role", "messages.${conversation.id}[$index]", 16)
                if (role !in MESSAGE_ROLES) throw BackupFormatException("消息角色无效")
                val text = requiredString(item, "text", "messages.${conversation.id}[$index]", MAX_MESSAGE_LENGTH, allowBlank = true)
                val stream = if (schemaVersion >= 4 && item.has("streamSnapshot")) {
                    if (role != "assistant") throw BackupFormatException("只有助手消息可包含活动记录")
                    val value = requiredObject(item, "streamSnapshot", "messages.${conversation.id}[$index]")
                    if (value.toString().toByteArray(Charsets.UTF_8).size > MAX_STREAM_BYTES) {
                        throw BackupFormatException("单条活动记录超过容量上限")
                    }
                    val decoded = AssistantStreamSnapshotJson.decode(value)
                        ?: throw BackupFormatException("活动记录格式无效")
                    // The persistent display codec is lenient for legacy app files. A backup is
                    // untrusted: reject fields, opaque payloads and coercions it would discard.
                    requireCanonicalJson(value, AssistantStreamSnapshotJson.encode(decoded))
                    validateStreamSnapshot(decoded)
                    importedStream(decoded)
                } else null
                val modelInput = if (schemaVersion >= 4 && item.has("modelInput"))
                    requiredString(item, "modelInput", "messages.${conversation.id}[$index]", MAX_MESSAGE_LENGTH, allowBlank = true)
                    else null
                parsed += BackupMessage(id, role, text, stream, modelInput)
            }
            totalMessages += parsed.size
            if (totalMessages > MAX_MESSAGES_TOTAL) throw BackupFormatException("消息总数超过限制")
            conversation.id to parsed.toList()
        }
    }

    private fun portableMessage(message: ChatMessage) = BackupMessage(
        message.id, message.role, message.text, message.streamSnapshot, message.modelInput,
    )

    private fun validateStreamSnapshot(snapshot: AssistantStreamSnapshot) {
        val collections = listOf(snapshot.messages, snapshot.toolCalls, snapshot.subagents, snapshot.searches,
            snapshot.citations, snapshot.activityOrder, snapshot.pendingSequenceNumbers, snapshot.skippedSequenceGaps)
        if (collections.any { it.size > MAX_ACTIVITY_ITEMS } ||
            snapshot.messages.any { it.blocks.size > MAX_ACTIVITY_ITEMS } ||
            snapshot.searches.any { it.results.size > MAX_ACTIVITY_ITEMS }) {
            throw BackupFormatException("单条活动记录数量超过上限")
        }
        if (snapshot.messages.map { it.messageId }.distinct().size != snapshot.messages.size ||
            snapshot.toolCalls.map { it.callId }.distinct().size != snapshot.toolCalls.size ||
            snapshot.subagents.map { it.agentId }.distinct().size != snapshot.subagents.size ||
            snapshot.searches.map { it.searchId }.distinct().size != snapshot.searches.size) {
            throw BackupFormatException("活动记录包含重复标识")
        }
        val json = AssistantStreamSnapshotJson.encode(snapshot)
        if (json.toString().toByteArray(Charsets.UTF_8).size > MAX_STREAM_BYTES) {
            throw BackupFormatException("单条活动记录超过容量上限")
        }
        var values = 0
        fun inspect(value: Any?, depth: Int) {
            if (depth > 32 || ++values > 16_384) throw BackupFormatException("活动记录层级或数量超过上限")
            when (value) {
                is JSONObject -> value.keys().forEach { inspect(value.get(it), depth + 1) }
                is JSONArray -> repeat(value.length()) { inspect(value.get(it), depth + 1) }
                is String -> validateText(value, "活动内容", MAX_MESSAGE_LENGTH, allowBlank = true)
                is Number -> if (!value.toDouble().isFinite() || value.toDouble() < 0) {
                    throw BackupFormatException("活动记录数值无效")
                }
            }
        }
        inspect(json, 0)
    }

    private fun requireCanonicalJson(input: Any?, canonical: Any?, depth: Int = 0) {
        if (depth > 32) throw BackupFormatException("活动记录层级过深")
        val matches = when {
            input is JSONObject && canonical is JSONObject -> {
                val keys = input.keys().asSequence().toSet()
                if (keys != canonical.keys().asSequence().toSet()) false else {
                    keys.forEach { requireCanonicalJson(input.get(it), canonical.get(it), depth + 1) }
                    true
                }
            }
            input is JSONArray && canonical is JSONArray -> {
                if (input.length() != canonical.length()) false else {
                    repeat(input.length()) { requireCanonicalJson(input.get(it), canonical.get(it), depth + 1) }
                    true
                }
            }
            input is Number && canonical is Number -> input.toDouble().isFinite() &&
                input.toDouble() == canonical.toDouble() && input.toDouble() == input.toLong().toDouble()
            else -> input == canonical
        }
        if (!matches) throw BackupFormatException("活动记录包含未知字段、隐藏内容或错误类型")
    }

    /** Imported snapshots are display data; approvals and unfinished work never become runnable. */
    private fun importedStream(snapshot: AssistantStreamSnapshot): AssistantStreamSnapshot {
        val activeMessages = setOf(AssistantMessageStatus.CREATED, AssistantMessageStatus.IN_PROGRESS)
        val activeTools = setOf(AssistantStreamEvent.ToolStatus.PENDING, AssistantStreamEvent.ToolStatus.STREAMING_ARGUMENTS,
            AssistantStreamEvent.ToolStatus.READY, AssistantStreamEvent.ToolStatus.AWAITING_APPROVAL,
            AssistantStreamEvent.ToolStatus.APPROVED, AssistantStreamEvent.ToolStatus.RUNNING)
        val activeAgents = setOf(AssistantStreamEvent.SubagentStatus.PENDING, AssistantStreamEvent.SubagentStatus.STARTING,
            AssistantStreamEvent.SubagentStatus.RUNNING)
        val activeSearches = setOf(AssistantStreamEvent.SearchStatus.PENDING, AssistantStreamEvent.SearchStatus.RUNNING)
        val interrupted = !snapshot.isFinal || snapshot.runStatus == AssistantRunStatus.RUNNING ||
            snapshot.messages.any { it.status in activeMessages } || snapshot.toolCalls.any { it.status in activeTools } ||
            snapshot.subagents.any { it.status in activeAgents } || snapshot.searches.any { it.status in activeSearches }
        return snapshot.copy(
            isFinal = true,
            runStatus = if (interrupted) AssistantRunStatus.INCOMPLETE else snapshot.runStatus,
            pendingSequenceNumbers = emptyList(),
            messages = snapshot.messages.map { message -> if (message.status in activeMessages)
                message.copy(status = AssistantMessageStatus.INCOMPLETE, incompleteReason = "backup_imported") else message },
            toolCalls = snapshot.toolCalls.map { tool -> if (tool.status in activeTools)
                tool.copy(status = AssistantStreamEvent.ToolStatus.INCOMPLETE) else tool },
            subagents = snapshot.subagents.map { agent -> if (agent.status in activeAgents)
                agent.copy(status = AssistantStreamEvent.SubagentStatus.INCOMPLETE, detail = "backup_imported") else agent },
            searches = snapshot.searches.map { search -> if (search.status in activeSearches)
                search.copy(status = AssistantStreamEvent.SearchStatus.INCOMPLETE, error = "backup_imported") else search },
        )
    }

    private fun parseSettings(json: JSONObject): BackupSettings {
        rejectUnknownKeys(
            json,
            setOf(
                "themeMode",
                "reduceTransparency",
                "reduceMotion",
                "notificationsEnabled",
                "keepRunning",
                "downloadSource",
            ),
            "settings",
        )
        rejectForbiddenKeys(json, "settings")
        val settings = BackupSettings(
            themeMode = requiredString(json, "themeMode", "settings", 16),
            reduceTransparency = requiredBoolean(json, "reduceTransparency", "settings"),
            reduceMotion = requiredBoolean(json, "reduceMotion", "settings"),
            notificationsEnabled = requiredBoolean(json, "notificationsEnabled", "settings"),
            keepRunning = requiredBoolean(json, "keepRunning", "settings"),
            downloadSource = requiredString(json, "downloadSource", "settings", 32),
        )
        validateSettings(settings)
        return settings
    }

    private fun validateSettings(settings: BackupSettings) {
        if (settings.themeMode !in THEME_MODES) throw BackupFormatException("主题模式无效")
        if (settings.downloadSource !in DOWNLOAD_SOURCES) throw BackupFormatException("下载源无效")
    }

    private fun requiredId(json: JSONObject, key: String, path: String): String {
        val value = requiredString(json, key, path, MAX_ID_LENGTH)
        validateId(value, "$path.$key")
        return value
    }

    private fun validateId(value: String, path: String) {
        if (!SAFE_ID.matches(value)) throw BackupFormatException("$path 无效")
    }

    private fun requiredString(
        json: JSONObject,
        key: String,
        path: String,
        maxLength: Int,
        allowBlank: Boolean = false,
    ): String {
        if (!json.has(key) || json.isNull(key)) throw BackupFormatException("缺少 $path.$key")
        val value = json.get(key)
        if (value !is String) throw BackupFormatException("$path.$key 必须是字符串")
        validateText(value, "$path.$key", maxLength, allowBlank)
        return value
    }

    private fun validateText(value: String, path: String, maxLength: Int, allowBlank: Boolean) {
        if (!allowBlank && value.isBlank()) throw BackupFormatException("$path 不能为空")
        if (value.length > maxLength) throw BackupFormatException("$path 超过长度限制")
        if (value.any { it.code < 0x20 && it != '\n' && it != '\r' && it != '\t' }) {
            throw BackupFormatException("$path 包含非法控制字符")
        }
    }

    private fun requiredBoolean(json: JSONObject, key: String, path: String): Boolean {
        if (!json.has(key) || json.isNull(key)) throw BackupFormatException("缺少 $path.$key")
        val value = json.get(key)
        if (value !is Boolean) throw BackupFormatException("$path.$key 必须是布尔值")
        return value
    }

    private fun requiredInt(json: JSONObject, key: String, path: String): Int {
        val number = requiredNumber(json, key, path)
        if (number < Int.MIN_VALUE || number > Int.MAX_VALUE || number.toDouble() != number.toLong().toDouble()) {
            throw BackupFormatException("$path.$key 必须是整数")
        }
        return number.toInt()
    }

    private fun requiredLong(json: JSONObject, key: String, path: String): Long {
        val value = if (!json.has(key) || json.isNull(key)) null else json.get(key)
        if (value !is Number) throw BackupFormatException("$path.$key 必须是整数")
        val asDouble = value.toDouble()
        if (!asDouble.isFinite() || asDouble != value.toLong().toDouble()) {
            throw BackupFormatException("$path.$key 必须是整数")
        }
        return value.toLong()
    }

    private fun requiredNumber(json: JSONObject, key: String, path: String): Long {
        val value = if (!json.has(key) || json.isNull(key)) null else json.get(key)
        if (value !is Number) throw BackupFormatException("$path.$key 必须是数字")
        val asDouble = value.toDouble()
        if (!asDouble.isFinite() || asDouble != value.toLong().toDouble()) {
            throw BackupFormatException("$path.$key 必须是整数")
        }
        return value.toLong()
    }

    private fun requiredArray(json: JSONObject, key: String, path: String): JSONArray {
        if (!json.has(key) || json.isNull(key)) throw BackupFormatException("缺少 $path.$key")
        return json.optJSONArray(key) ?: throw BackupFormatException("$path.$key 必须是数组")
    }

    private fun requiredObject(json: JSONObject, key: String, path: String): JSONObject {
        if (!json.has(key) || json.isNull(key)) throw BackupFormatException("缺少 $path.$key")
        return json.optJSONObject(key) ?: throw BackupFormatException("$path.$key 必须是对象")
    }

    private fun requiredObject(array: JSONArray, index: Int, path: String): JSONObject {
        val value = array.optJSONObject(index) ?: throw BackupFormatException("$path 必须是对象")
        return value
    }

    private fun rejectUnknownKeys(json: JSONObject, allowed: Set<String>, path: String) {
        val unknown = json.keys().asSequence().filterNot { it in allowed }.toList()
        if (unknown.isNotEmpty()) throw BackupFormatException("$path 包含未知字段：${unknown.joinToString()}")
    }

    private fun rejectForbiddenKeys(json: JSONObject, path: String) {
        val forbidden = json.keys().asSequence().filter { key ->
            key.lowercase() in FORBIDDEN_KEYS
        }.toList()
        if (forbidden.isNotEmpty()) throw BackupFormatException("$path 包含禁止导入的敏感字段：${forbidden.joinToString()}")
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream(minOf(MAX_BACKUP_BYTES, 64 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > MAX_BACKUP_BYTES) {
                throw BackupFormatException("备份超过 ${MAX_BACKUP_BYTES / 1024 / 1024} MiB 限制")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

class BackupFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)


