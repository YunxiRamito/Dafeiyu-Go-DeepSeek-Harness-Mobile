package com.dafeiyu.mobile.data

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class DymHistoryConversion(
    val conversations: AndroidBackupStore.ValidatedBackup?,
    val convertedSessions: Int,
    val warnings: List<String>,
)

/** PC physical session files become display history, never an executable replay. */
class DymHistoryConverter(private val nativeStore: AndroidBackupStore = AndroidBackupStore()) {
    /** Use the first-party reader whenever it is available. The raw v4 fallback
     * only projects compatible display content; it does not validate DSH lifecycle. */
    suspend fun convert(result: DymImportResult, native: AndroidBackupStore.ValidatedBackup? = null,
        readPhysicalArtifact: (suspend (DymStagedFile) -> JSONObject)? = null): DymHistoryConversion =
        withContext(Dispatchers.IO) {
            val warnings = mutableListOf<String>()
            val files = result.groupFiles["sessions"].orEmpty().filter { sessionFile.matches(it.file.name) }
            val selected = files.groupBy { logicalDirectory(it.archivePath) }.values.map { candidates ->
                // DSH's format catalogue gives current generation precedence.
                candidates.maxWith(compareBy<DymStagedFile> { generation(it.file.name) }
                    .thenBy { it.file.name.endsWith(".zstd") })
            }.sortedBy { it.archivePath }
            val workspaces = native?.workspaces?.toMutableList() ?: mutableListOf()
            val conversations = native?.conversations?.toMutableList() ?: mutableListOf()
            val messages = native?.messages?.toMutableMap() ?: linkedMapOf()
            var converted = 0
            for (resource in selected) {
                currentCoroutineContext().ensureActive()
                try {
                    validateSource(result, resource)
                    val compatibilityOnly = readPhysicalArtifact == null && resource.file.name == "session.v4.jsonl"
                    val artifact = when {
                        readPhysicalArtifact != null -> readPhysicalArtifact(resource)
                        compatibilityOnly -> readV4(resource.file)
                        else -> error("该历史需要已安装的 DSH 运行组件解码")
                    }
                    val history = DshSharedHistoryAdapter.decode(artifact)
                    val sessionKey = stableId("pc-session", history.sessionId + "/" + (history.cwd ?: logicalDirectory(resource.archivePath)))
                    if (conversations.any { it.id == sessionKey }) continue
                    val convertedMessages = compatibleWithActivities(history)
                    if (convertedMessages.isEmpty()) {
                        warnings += "会话没有可兼容的正文，原始记录已保留：${displayTitle(history)}"
                        continue
                    }
                    val workspaceKey = stableId("pc-workspace", history.cwd ?: logicalDirectory(resource.archivePath).substringBeforeLast('/'))
                    val workspace = AndroidBackupStore.BackupWorkspace(workspaceKey, workspaceTitle(history, resource.archivePath))
                    val conversation = AndroidBackupStore.BackupConversation(sessionKey, displayTitle(history), workspaceKey)
                    val candidateWorkspaces = if (workspaces.any { it.id == workspaceKey }) workspaces.toList() else workspaces + workspace
                    val input = AndroidBackupStore.BackupInput(candidateWorkspaces, conversations + conversation,
                        messages + (sessionKey to convertedMessages), native?.selectedWorkspaceId ?: workspaceKey,
                        native?.selectedConversationId ?: sessionKey, native?.settings ?: AndroidBackupStore.BackupSettings())
                    // The same bounded allowlist used for native imports validates
                    // every converted session before it becomes app state.
                    nativeStore.import(nativeStore.export(input))
                    if (workspaces.none { it.id == workspaceKey }) workspaces += workspace
                    conversations += conversation
                    messages[sessionKey] = convertedMessages
                    converted++
                    if (compatibilityOnly) warnings += "未安装 DSH 运行组件，仅转换兼容正文，未完成 DSH 格式校验：${displayTitle(history)}"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    warnings += "PC 会话转换未完成，原始记录已保留：${resource.archivePath}"
                }
            }
            val convertedBackup = if (converted == 0) native else nativeStore.import(nativeStore.export(
                AndroidBackupStore.BackupInput(workspaces, conversations, messages,
                    native?.selectedWorkspaceId ?: workspaces.first().id,
                    native?.selectedConversationId ?: conversations.first().id,
                    native?.settings ?: AndroidBackupStore.BackupSettings())))
            DymHistoryConversion(convertedBackup, converted, warnings.distinct().take(100))
        }

    internal fun compatibleWithActivities(history: DshSharedHistory): List<AndroidBackupStore.BackupMessage> {
        val messages = history.compatibleMessages
        val rowsBySeq = history.items.associateBy { it.eventSeq }
        val assistantIndices = messages.withIndex().filter { it.value.role == "assistant" }.map { it.index }
        val toolCalls = history.items.filter { it.role == "tool-call" }
        val toolResults = history.items.filter { it.role == "tool" }.associateBy { it.toolCallId }
        val toolOwner = toolCalls.associateWith { call ->
            assistantIndices.lastOrNull { messages[it].id - 1 <= call.eventSeq }
                ?: assistantIndices.firstOrNull()
        }
        return messages.mapIndexed { index, message ->
            if (message.role != "assistant") return@mapIndexed AndroidBackupStore.BackupMessage(message.id, message.role, message.text,
                modelInput = message.modelInput)
            val row = rowsBySeq[message.id - 1] ?: error("会话活动缺少来源")
            val calls = toolOwner.filterValues { it == index }.keys.sortedBy { it.eventSeq }
            val messageId = row.messageId ?: "pc-message-${row.eventSeq}"
            val blocks = buildList {
                if (row.publicThinking.isNotEmpty()) add(AssistantContentBlockSnapshot.Reasoning(
                    "pc-thinking-${row.eventSeq}", AssistantReasoningKind.USER_VISIBLE_THINKING, row.publicThinking,
                    AssistantReasoningMetadata("dsh"), isComplete = !row.interrupted))
                add(AssistantContentBlockSnapshot.Text("pc-text-${row.eventSeq}", message.text, isComplete = !row.interrupted))
            }
            val tools = calls.map { call ->
                val originalId = requireNotNull(call.toolCallId)
                val result = toolResults[originalId]
                val failed = result?.resultJson?.let { JSONObject(it).optBoolean("isError") } == true
                AssistantToolCallSnapshot(originalId, call.toolName, AssistantStreamEvent.ToolKind.TOOL,
                    call.argumentsJson.orEmpty(), when {
                        call.interrupted || result == null || result.interrupted -> AssistantStreamEvent.ToolStatus.INCOMPLETE
                        failed -> AssistantStreamEvent.ToolStatus.FAILED
                        else -> AssistantStreamEvent.ToolStatus.SUCCEEDED
                    }, AssistantStreamEvent.ApprovalRequirement.NONE,
                    result = result?.text, error = result?.text?.takeIf { failed })
            }
            val interrupted = row.interrupted || index == assistantIndices.lastOrNull() && history.interrupted
            val stream = AssistantStreamSnapshot(
                messages = listOf(AssistantMessageSnapshot(messageId, "assistant", if (interrupted)
                    AssistantMessageStatus.INCOMPLETE else AssistantMessageStatus.COMPLETED, blocks,
                    incompleteReason = "backup_imported".takeIf { interrupted })),
                toolCalls = tools, subagents = emptyList(), searches = emptyList(), citations = emptyList(), usage = null,
                lastAppliedSequenceNumber = row.eventSeq, nextExpectedSequenceNumber = row.eventSeq + 1,
                pendingSequenceNumbers = emptyList(), skippedSequenceGaps = emptyList(), isFinal = true,
                source = AssistantStreamSource.BACKEND,
                activityOrder = buildList {
                    if (row.publicThinking.isNotEmpty()) add(AssistantActivityReference(AssistantActivityKind.REASONING,
                        "pc-thinking-${row.eventSeq}", messageId, AssistantReasoningKind.USER_VISIBLE_THINKING))
                    add(AssistantActivityReference(AssistantActivityKind.TEXT, "pc-text-${row.eventSeq}", messageId))
                    calls.forEach { add(AssistantActivityReference(AssistantActivityKind.TOOL, requireNotNull(it.toolCallId), messageId)) }
                }, runStatus = if (interrupted || tools.any { it.status == AssistantStreamEvent.ToolStatus.INCOMPLETE })
                    AssistantRunStatus.INCOMPLETE else AssistantRunStatus.COMPLETED,
            )
            AndroidBackupStore.BackupMessage(message.id, "assistant", message.text, stream, message.modelInput)
        }
    }

    private suspend fun readV4(file: File): JSONObject {
        check(file.length() <= MAX_SESSION_BYTES) { "会话文件过大" }
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            val first = reader.readLine() ?: error("会话文件为空")
            check(first.length <= MAX_LINE_CHARS)
            val header = JSONObject(first)
            require(header.getString("type") == "session" && header.getInt("version") == 4)
            header.remove("type")
            val events = JSONArray()
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = reader.readLine() ?: break
                check(line.length <= MAX_LINE_CHARS && events.length() < 120_000)
                require(line.isNotBlank()) { "会话包含空事件行" }
                events.put(JSONObject(line))
            }
            return JSONObject().put("header", header).put("events", events)
        }
    }

    private fun validateSource(result: DymImportResult, resource: DymStagedFile) {
        val expected = File(result.stagingRoot, resource.archivePath)
        require(expected.absoluteFile == resource.file.absoluteFile && resource.file.isFile && resource.file.length() <= MAX_SESSION_BYTES)
        require(resource.file.canonicalPath.startsWith(result.stagingRoot.canonicalPath + File.separator))
    }

    private fun logicalDirectory(path: String) = path.removePrefix(".dsh/").substringBeforeLast('/')
    private fun generation(name: String) = sessionFile.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    private fun displayTitle(history: DshSharedHistory) = clean(history.title, 100).ifBlank { "导入的 PC 会话" }
    private fun workspaceTitle(history: DshSharedHistory, path: String): String {
        val name = (history.cwd ?: logicalDirectory(path).substringBeforeLast('/')).replace('\\', '/')
            .trimEnd('/').substringAfterLast('/')
        return clean(name, 80).ifBlank { "PC 工作区" }
    }
    private fun clean(value: String, length: Int) = value.filter { it.code >= 32 }.take(length).trim()
    private fun stableId(prefix: String, value: String) = prefix + "-" + MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_SESSION_BYTES = 24L * 1024 * 1024
        const val MAX_LINE_CHARS = 2 * 1024 * 1024
        val sessionFile = Regex("session(?:\\.v([0-9]{1,3}))?\\.jsonl(?:\\.zstd)?")
    }
}
