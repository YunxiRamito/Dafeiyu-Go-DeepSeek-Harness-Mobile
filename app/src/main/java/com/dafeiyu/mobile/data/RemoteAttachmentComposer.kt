package com.dafeiyu.mobile.data

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The durable composer action owns upload identity. Uploading never executes
 * a prompt; the existing controller remains its only execution authority. */
class RemoteAttachmentComposer internal constructor(
    private val journal: (RemoteDshScope) -> RemoteDshJournal,
    private val transport: Transport,
) {
    data class Source(val id: String, val name: String, val size: Long, val file: File)
    data class State(val busy: Boolean = false, val binding: RemoteDshScope? = null,
        val name: String? = null, val receivedBytes: Long = 0, val totalBytes: Long = 0)
    internal interface Transport {
        suspend fun state(): JSONObject
        suspend fun prepareSession(binding: RemoteDshScope): String
        suspend fun upload(binding: RemoteDshScope, action: String, session: String, source: Source,
            progress: (RemoteAttachmentTransfer.Progress) -> Unit): JSONObject
        suspend fun send(binding: RemoteDshScope, workspace: String, session: String, text: String, parts: List<JSONObject>): Boolean
        fun pendingPrompt(): Boolean
        fun selection(): Pair<String?, String?>
    }
    constructor(context: Context, controller: RemoteDshApplicationController, host: RemoteDshProviderCall) : this(
        { binding -> RemoteDshSessionCoordinator.diskJournal(File(context.noBackupFilesDir,
            "remote-dsh/composer/${binding.storageKey}.json")) },
        object : Transport {
            override suspend fun state(): JSONObject = host.call("get_connection_state", JSONObject(), null)
            override suspend fun prepareSession(binding: RemoteDshScope): String = controller.prepareAttachmentSession(binding)
            override suspend fun upload(binding: RemoteDshScope, action: String, session: String, source: Source,
                progress: (RemoteAttachmentTransfer.Progress) -> Unit): JSONObject = RemoteAttachmentTransfer(binding,
                RemoteAttachmentRequest { key, operation, payload -> controller.attachmentRequest(key, operation, payload, binding) },
                { state() }).upload(action, session, source.file, source.name, progress).content()
            override suspend fun send(binding: RemoteDshScope, workspace: String, session: String,
                text: String, parts: List<JSONObject>): Boolean {
                require(selection() == (workspace to session)) { "工作区或对话已变化，附件和草稿已保留" }
                return controller.send(text, parts, binding)
            }
            override fun pendingPrompt(): Boolean = controller.state.value.pendingPrompt
            override fun selection(): Pair<String?, String?> = controller.state.value.let { it.selectedWorkspaceId to it.selectedSessionId }
        },
    )
    private val gate = Mutex()
    private val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable.asStateFlow()
    @Volatile private var activeJob: Job? = null

    suspend fun send(binding: RemoteDshScope, workspace: String, text: String, sources: List<Source>,
        validateComposer: suspend () -> Unit): Boolean = withContext(Dispatchers.IO) {
        gate.withLock {
            require(workspace.matches(IDENTIFIER) && sources.size in 1..6)
            require(sources.map { it.id }.distinct().size == sources.size)
            val total = sources.sumOf { source ->
                require(source.id.matches(IDENTIFIER) && source.size in 0..ConversationAttachmentStore.MAX_ATTACHMENT_BYTES)
                source.size
            }
            require(total <= ConversationAttachmentStore.MAX_TOTAL_BYTES)
            activeJob = currentCoroutineContext()[Job]
            mutable.value = State(busy = true, binding = binding, totalBytes = total)
            try {
                suspend fun current() {
                    currentCoroutineContext().ensureActive()
                    validateComposer()
                    binding.requireCurrent(transport.state(), requireOnline = true)
                    require(transport.selection().first == workspace) { "工作区已变化，草稿已保留" }
                }
                current()
                val session = transport.prepareSession(binding)
                current()
                val signature = remoteJsonHash(JSONObject().put("workspace", workspace).put("session", session).put("text", text)
                    .put("sources", JSONArray().also { entries -> sources.forEach { source -> entries.put(JSONObject()
                        .put("id", source.id).put("name", source.name).put("size", source.size)) } }))
                val file = journal(binding)
                val previous = file.read()
                if (previous != null) {
                    require(previous.getInt("version") == 1 && RemoteDshScope.fromJson(previous.getJSONObject("scope")) == binding)
                    require(previous.getString("action").matches(IDENTIFIER))
                    require(previous.getString("signature").matches(Regex("[a-f0-9]{64}")))
                    if (previous.getString("signature") != signature && transport.pendingPrompt())
                        error("电脑尚未确认上一次发送，请保留原附件和正文重试")
                }
                val action = previous?.takeIf { it.getString("signature") == signature && !it.optBoolean("completed") }
                    ?.getString("action") ?: "composer-${UUID.randomUUID()}"
                file.write(JSONObject().put("version", 1).put("scope", binding.toJson()).put("action", action)
                    .put("signature", signature).put("completed", false))
                var completedBytes = 0L
                val parts = sources.mapIndexed { index, source ->
                    current()
                    transport.upload(binding, "$action-$index", session, source) { progress ->
                        mutable.value = State(true, binding, source.name, completedBytes + progress.receivedBytes, total)
                    }.also { part ->
                        require(part.length() == 2 && part.getString("type") == "file" && part.getString("uploadId").matches(IDENTIFIER))
                        completedBytes += source.size
                    }
                }
                current()
                require(transport.selection() == (workspace to session)) { "对话已变化，附件和草稿已保留" }
                val accepted = transport.send(binding, workspace, session, text, parts)
                // The controller already persisted the PC admission. A later
                // cache write failure must not turn that receipt into a retry.
                if (accepted) runCatching { file.write(JSONObject().put("version", 1).put("scope", binding.toJson()).put("action", action)
                    .put("signature", signature).put("completed", true)) }
                accepted
            } catch (cancelled: CancellationException) { throw cancelled }
            finally { activeJob = null; mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun cancel() { activeJob?.cancel() }
    fun onRelationship(binding: RemoteDshScope?) { if (mutable.value.busy && mutable.value.binding != binding) cancel() }
    fun onPowerState(foreground: Boolean, powerSaving: Boolean) { if (!foreground && powerSaving) cancel() }
    private companion object { val IDENTIFIER = Regex("[A-Za-z0-9_-]{1,128}") }
}
