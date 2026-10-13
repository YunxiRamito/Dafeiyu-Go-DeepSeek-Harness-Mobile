package com.dafeiyu.mobile.data

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** The UI never owns a remote task. Observation and execution have separate lifetimes. */
class RemoteDshApplicationController internal constructor(
    private val transport: RemoteDshTransport,
    private val coordinatorFactory: (RemoteDshScope) -> RemoteDshSessionCoordinator,
    private val selectionFile: (RemoteDshScope) -> RemoteDshJournal,
    private val pollIntervalMillis: Long = 1500L,
    private val resultTimeoutMillis: Long = 30_000L,
) : AutoCloseable {
    constructor(context: Context, provider: AriesConnectionProvider = AriesConnectionProvider(context)) : this(
        RemoteDshClient(provider),
        { binding -> RemoteDshSessionCoordinator(context.applicationContext, RemoteDshClient(provider), binding) },
        { binding -> RemoteDshSessionCoordinator.diskJournal(File(context.noBackupFilesDir,
            "remote-dsh/ui/${binding.storageKey}.json")) },
    )

    constructor(context: Context, host: RemoteDshProviderCall) : this(
        RemoteDshClient(host),
        { binding -> RemoteDshSessionCoordinator(context.applicationContext, RemoteDshClient(host), binding) },
        { binding -> RemoteDshSessionCoordinator.diskJournal(File(context.noBackupFilesDir,
            "remote-dsh/ui/${binding.storageKey}.json")) },
    )

    data class State(
        val connection: JSONObject? = null,
        val projection: RemoteDshSessionCoordinator.State? = null,
        val selectedWorkspaceId: String? = null,
        val selectedSessionId: String? = null,
        val pendingPrompt: Boolean = false,
        val busy: Boolean = false,
        val error: String? = null,
        val webAccessUrl: String? = null,
        val webSuspended: Boolean = false,
        val pendingLifecycleOperations: List<String> = emptyList(),
    ) {
        val connected: Boolean get() = connection?.optBoolean("server_connected") == true
        val remoteOnline: Boolean get() = connection?.optJSONObject("remote_device")?.optString("online") == "online"
        val selectedView: RemoteDshActivityMapper.View? get() = projection?.sessions?.get(selectedSessionId)
        val running: Boolean get() = selectedView?.activity?.runStatus == AssistantRunStatus.RUNNING ||
            projection?.workspace?.sessions?.any { it.id == selectedSessionId && it.running } == true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()
    private val bindingMutex = Mutex()
    private val webMutex = Mutex()
    private val childMutex = Mutex()
    private val activeRequests = AtomicInteger()
    private val stateMutable = MutableStateFlow(State())
    val state: StateFlow<State> = stateMutable.asStateFlow()
    internal val pollDiagnostic: RemoteDshSessionCoordinator.PollDiagnostic? get() = coordinator?.lastPollDiagnostic
    private var coordinator: RemoteDshSessionCoordinator? = null
    private var projectionJob: Job? = null
    private var connectionJob: Job? = null
    private var lifecycleRecoveryJob: Job? = null
    private var uiDocument = JSONObject()
    private var active = false
    private var foreground = true
    private var powerSave = false
    private var selectedObservation: String? = null
    private var webProxy: RemoteDshLoopbackProxy? = null
    @Volatile private var webGeneration = 0L

    fun setActive(value: Boolean) {
        active = value
        if (!value) {
            connectionJob?.cancel(); connectionJob = null
            lifecycleRecoveryJob?.cancel()
            coordinator?.stopObserving()
            closeWeb()
        } else if (connectionJob?.isActive != true) {
            connectionJob = scope.launch {
                while (active) {
                    if (foreground || !powerSave) runCatching {
                        connect()
                        if (state.value.remoteOnline && state.value.projection?.workspace?.catalog == null) refresh()
                    }.onFailure { failure ->
                        if (failure is CancellationException) throw failure
                        stateMutable.update { it.copy(error = remoteFailure(failure)) }
                    }
                    delay(15_000)
                }
            }
        }
    }

    fun setForeground(value: Boolean, powerSaving: Boolean) {
        foreground = value; powerSave = powerSaving
        stateMutable.update { it.copy(webSuspended = !value && powerSaving) }
        if (!value && powerSaving) {
            lifecycleRecoveryJob?.cancel()
            closeWeb()
        }
        scope.launch {
            coordinator?.setForeground(value, powerSaving)
            if (active && value) runCatching { connect(); refresh() }.onFailure {
                if (it is CancellationException) throw it
                stateMutable.update { previous -> previous.copy(error = remoteFailure(it)) }
            }
        }
    }

    suspend fun connect() = bindingMutex.withLock {
        val connection = transport.connectionState()
        stateMutable.update { it.copy(connection = JSONObject(connection.toString())) }
        if (connection.optString("pairing_status") != "paired") {
            if (connection.optString("pairing_status") in setOf("unpaired", "revoked") ||
                connection.optJSONObject("terminal_receipt") != null) {
                coordinator?.stopObserving(); coordinator = null
                projectionJob?.cancel(); projectionJob = null
                lifecycleRecoveryJob?.cancel()
                closeWeb()
                uiDocument = JSONObject().put("schema", 1)
                selectedObservation = null
                stateMutable.update { it.copy(projection = null, selectedWorkspaceId = null, selectedSessionId = null,
                    pendingPrompt = false, busy = false, pendingLifecycleOperations = emptyList()) }
            }
            return@withLock
        }
        val binding = RemoteDshScope.fromState(connection)
        if (coordinator?.binding != binding) {
            coordinator?.stopObserving(); projectionJob?.cancel()
            lifecycleRecoveryJob?.cancel()
            closeWeb()
            val next = coordinatorFactory(binding)
            uiDocument = selectionFile(binding).read()?.also { validateUiDocument(it, binding) }
                ?: JSONObject().put("schema", 1)
            coordinator = next
            selectedObservation = null
            stateMutable.update { it.copy(projection = next.state.value,
                selectedWorkspaceId = uiDocument.remoteOptionalString("workspace"),
                selectedSessionId = uiDocument.remoteOptionalString("session"),
                pendingPrompt = uiDocument.optJSONObject("prompt") != null,
                pendingLifecycleOperations = lifecycleOperations(uiDocument), error = null) }
            projectionJob = scope.launch { next.state.collect { projection ->
                if (coordinator === next) stateMutable.update { it.copy(projection = projection,
                    error = projection.errorCode?.let(::remoteCodeMessage) ?: it.error) }
                if (coordinator === next && active && (foreground || !powerSave)) {
                    try { observeChildren(next, projection) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { stateMutable.update { it.copy(error = remoteFailure(failure)) } }
                }
            } }
        }
        coordinator?.setForeground(foreground, powerSave)
        if (active) coordinator?.start(scope, pollIntervalMillis)
        coordinator?.let(::scheduleLifecycleRecovery)
    }

    suspend fun refresh() = operationMutex.withLock {
        connect()
        val current = current()
        for (operation in listOf("workspace.list", "session.list", "models.catalog", "settings.describe")) {
            requestResult(current, key("read"), operation, JSONObject())
        }
        observeControl(current)
        reconcileSelection(current)
        stateMutable.update { it.copy(error = null) }
    }

    suspend fun selectWorkspace(id: String) = operationMutex.withLock {
        val current = current()
        val workspace = current.state.value.workspace.workspaces.firstOrNull { it.id == id }
            ?: error("remote_workspace_missing")
        val session = workspace.sessionIds.firstOrNull()
        saveSelection(current, id, session)
        session?.let { follow(current, it) }
    }

    suspend fun selectSession(id: String) = operationMutex.withLock {
        val current = current()
        val workspace = current.state.value.workspace.workspaces.firstOrNull { id in it.sessionIds }
            ?: error("remote_session_missing")
        saveSelection(current, workspace.id, id)
        follow(current, id)
    }

    suspend fun resolveWorkspace(name: String, path: String? = null, expectedScope: RemoteDshScope? = null): JSONObject = operationMutex.withLock {
        connect()
        val current = current(expectedScope)
        val payload = JSONObject().put("name", name.trim()).also { if (path != null) it.put("path", path) }
        val result = requestResult(current, key("workspace"), "workspace.resolve", payload) as JSONObject
        if (result.optString("status") == "resolved") {
            val workspace = result.getJSONObject("workspace")
            saveSelection(current, workspace.getString("workspaceId"), null)
        }
        result
    }

    suspend fun listDirectory(path: String? = null, expectedScope: RemoteDshScope? = null): RemoteWorkspaceStore.DirectoryListing = operationMutex.withLock {
        connect()
        val current = current(expectedScope)
        requestResult(current, key("directory"), "directory.list", JSONObject().also { if (path != null) it.put("path", path) })
        current.state.value.workspace.directories.last()
    }

    suspend fun newSession() = operationMutex.withLock {
        val current = current()
        require(uiDocument.optJSONObject("prompt") == null) { "remote_prompt_pending" }
        val workspace = state.value.selectedWorkspaceId ?: error("remote_workspace_missing")
        val created = requestResult(current, key("create"), "session.create", JSONObject().put("workspaceId", workspace)) as JSONObject
        val session = created.getString("sessionId")
        requestResult(current, key("list"), "workspace.list", JSONObject())
        requestResult(current, key("sessions"), "session.list", JSONObject())
        saveSelection(current, workspace, session)
        follow(current, session)
    }

    /** Retry a saved intent with the same key and content; never issue a new prompt after a lost receipt. */
    suspend fun send(text: String, attachments: List<JSONObject> = emptyList(), expectedScope: RemoteDshScope? = null): Boolean = operationMutex.withLock {
        require(text.isNotBlank() || attachments.isNotEmpty())
        require(attachments.size <= 32)
        attachments.forEach { part ->
            require(part.length() == 2 && part.optString("type") == "file" &&
                part.getString("uploadId").matches(Regex("[A-Za-z0-9_-]{1,128}")))
        }
        val content = org.json.JSONArray().also { values ->
            if (text.isNotBlank()) values.put(JSONObject().put("type", "text").put("text", text))
            attachments.forEach { values.put(JSONObject(it.toString())) }
        }
        connect()
        val current = current(expectedScope)
        var pending = uiDocument.optJSONObject("prompt")
        if (pending == null) {
            val workspace = state.value.selectedWorkspaceId ?: error("remote_workspace_missing")
            pending = JSONObject().put("key", key("prompt")).put("text", text).put("content", content).put("workspace", workspace)
                .put("session", state.value.selectedSessionId)
            saveDocument(current, JSONObject(uiDocument.toString()).put("prompt", pending))
        } else {
            val savedContent = pending.optJSONArray("content") ?: org.json.JSONArray().put(JSONObject().put("type", "text").put("text", pending.getString("text")))
            require(pending.getString("text") == text && remoteJsonHash(savedContent) == remoteJsonHash(content)) { "remote_prompt_pending" }
        }
        val intent = checkNotNull(pending)
        val operationKey = intent.getString("key")
        val sessionId = intent.remoteOptionalString("session") ?: run {
            val created = requestResult(current, "${operationKey}_create", "session.create",
                JSONObject().put("workspaceId", intent.getString("workspace"))) as JSONObject
            created.getString("sessionId").also { session ->
                intent.put("session", session)
                saveDocument(current, JSONObject(uiDocument.toString()).put("prompt", intent))
            }
        }
        follow(current, sessionId)
        // Aries admission alone does not confirm that the computer has accepted
        // the task. Keep the saved composer intent until its persistent receipt.
        requestResult(current, operationKey, "session.prompt", JSONObject().put("sessionId", sessionId)
            .put("mode", "queue").put("content", intent.optJSONArray("content") ?: content),
            if (attachments.isNotEmpty()) "remote_attachments" else null)
        saveDocument(current, JSONObject(uiDocument.toString()).also { it.remove("prompt") }
            .put("workspace", intent.getString("workspace")).put("session", sessionId))
        stateMutable.update { it.copy(error = null) }
        true
    }

    suspend fun cancelTask() = operationMutex.withLock {
        val current = current()
        val session = state.value.selectedSessionId ?: return@withLock
        requestResult(current, key("cancel"), "session.cancel", JSONObject().put("sessionId", session))
    }

    /** Preparation is also durable: retrying a lost receipt must reuse its removal ticket. */
    suspend fun prepareWorkspaceRemoval(workspaceId: String, expectedScope: RemoteDshScope? = null): RemoteWorkspaceStore.Removal = operationMutex.withLock {
        connect()
        val current = current(expectedScope)
        val result = lifecycleResult(current, "workspace.remove.prepare", JSONObject().put("workspaceId", workspaceId))
        current.state.value.workspace.removals.first { it.ticket == result.getString("ticket") }
    }

    suspend fun removeWorkspace(ticket: String, stopRunning: Boolean = false, expectedScope: RemoteDshScope? = null): RemoteWorkspaceStore.Removal = operationMutex.withLock {
        connect()
        val current = current(expectedScope)
        val result = lifecycleResult(current, "workspace.remove", JSONObject().put("ticket", ticket).put("stopTasks", stopRunning),
            refreshSelection = true, refreshArchives = true)
        current.state.value.workspace.removals.first { it.ticket == result.getString("ticket") }
    }

    /** The UI supplies the exact computer path only after the independent file confirmation. */
    suspend fun deleteWorkspaceFiles(ticket: String, confirmedPath: String, expectedScope: RemoteDshScope? = null): RemoteWorkspaceStore.Removal = operationMutex.withLock {
        connect()
        val current = current(expectedScope)
        val removal = current.state.value.workspace.removals.firstOrNull { it.ticket == ticket }
            ?: error("remote_removal_missing")
        require(removal.workspace.path == confirmedPath) { "remote_delete_path_changed" }
        val result = lifecycleResult(current, "workspace.files.delete", JSONObject().put("ticket", ticket)
            .put("confirmedPath", confirmedPath).put("confirmDelete", true))
        current.state.value.workspace.removals.first { it.ticket == result.getString("ticket") }
    }

    suspend fun restoreRemovedWorkspace(ticket: String, expectedScope: RemoteDshScope? = null): JSONObject = operationMutex.withLock {
        connect()
        lifecycleResult(current(expectedScope), "workspace.removed.restore", JSONObject().put("ticket", ticket),
            refreshSelection = true, refreshArchives = true)
    }

    suspend fun refreshArchive(expectedScope: RemoteDshScope? = null) = operationMutex.withLock {
        connect()
        val current = current(expectedScope)
        requestResult(current, key("removed"), "workspace.removed.list", JSONObject())
        requestResult(current, key("archived"), "session.archived.list", JSONObject())
        stateMutable.update { it.copy(error = null) }
    }

    suspend fun archiveSession(sessionId: String, stopRunning: Boolean = false, expectedScope: RemoteDshScope? = null) = operationMutex.withLock {
        connect()
        lifecycleResult(current(expectedScope), "session.archive", JSONObject().put("sessionId", sessionId).put("stopTasks", stopRunning),
            refreshSelection = true, refreshArchives = true)
        Unit
    }

    /** A removed workspace must be restored first; never hide a declined restoration. */
    suspend fun restoreArchivedSession(sessionId: String, expectedScope: RemoteDshScope? = null) = operationMutex.withLock {
        connect()
        val result = lifecycleResult(current(expectedScope), "session.restore", JSONObject().put("sessionId", sessionId),
            refreshSelection = true, refreshArchives = true)
        require(result.getBoolean("restored")) { "remote_restore_workspace_first" }
    }

    suspend fun prepareArchivedSessionDeletion(sessionId: String, expectedScope: RemoteDshScope? = null): RemoteWorkspaceStore.ArchiveDeletion = operationMutex.withLock {
        connect()
        val current = current(expectedScope)
        ensureBinding(current, "remote_archive_delete")
        val archive = current.state.value.workspace.archives.firstOrNull { it.sessionId == sessionId }
            ?: error("remote_archive_missing")
        val result = lifecycleResult(current, "session.archived.delete.prepare", JSONObject().put("sessionId", sessionId))
        val ticket = RemoteWorkspaceStore.parseArchiveDeletion(current.binding, result)
        require(ticket.sessionId == sessionId && !ticket.deleted &&
            (archive.archiveVersion == null || archive.archiveVersion == ticket.archiveVersion)) { "remote_archive_version_changed" }
        ticket
    }

    /** The original prepared receipt, not a caller-supplied ID or path, authorizes this confirmation. */
    suspend fun deleteArchivedSession(ticket: RemoteWorkspaceStore.ArchiveDeletion,
        expectedScope: RemoteDshScope? = null): RemoteWorkspaceStore.ArchiveDeletion = operationMutex.withLock {
        connect()
        val current = current(expectedScope ?: ticket.scope)
        ensureBinding(current, "remote_archive_delete")
        require(ticket.scope == current.binding && ticket.executionDevice == "remote_computer" &&
            ticket.status == "prepared" && !ticket.deleted) { "remote_archive_confirmation_scope_changed" }
        val payload = JSONObject().put("ticket", ticket.ticket).put("confirmDelete", true)
        val fingerprint = remoteJsonHash(JSONObject().put("operation", "session.archived.delete").put("payload", payload))
        // An uncertain submitted confirmation must poll its original request,
        // even if a later catalogue now reflects the successful deletion.
        if (uiDocument.optJSONObject("lifecycle")?.has(fingerprint) != true) {
            val cached = current.state.value.workspace.archiveDeletions.firstOrNull { it.ticket == ticket.ticket }
                ?: error("remote_archive_confirmation_missing")
            require(cached.sameConfirmation(ticket)) { "remote_archive_confirmation_changed" }
            if (!cached.deleted) {
                val archive = current.state.value.workspace.archives.firstOrNull { it.sessionId == ticket.sessionId }
                    ?: error("remote_archive_missing")
                require(archive.archiveVersion == ticket.archiveVersion && archive.archivedAt == ticket.archivedAt) {
                    "remote_archive_version_changed"
                }
            }
        }
        val result = lifecycleResult(current, "session.archived.delete", payload,
            refreshSelection = true, refreshArchives = true, archiveConfirmation = ticket)
        RemoteWorkspaceStore.parseArchiveDeletion(current.binding, result, ticket).also {
            require(it.deleted && it.status == "deleted") { "remote_archive_delete_not_confirmed" }
        }
    }

    /** Legacy callers cannot bypass the separate, exact confirmation dialog. */
    suspend fun deleteArchivedSession(sessionId: String) {
        require(sessionId.isNotBlank())
        throw AriesProviderException("unsupported_method")
    }

    suspend fun selectModel(provider: String, model: String, effort: String?) = operationMutex.withLock {
        val current = current()
        val catalog = current.state.value.workspace.catalog ?: error("remote_catalog_missing")
        val choice = catalog.providers.firstOrNull { it.id == provider }?.models?.firstOrNull { it.id == model }
            ?: error("remote_model_missing")
        require(provider in catalog.routable)
        require(effort == null || choice.efforts.any { it.id == effort })
        requestResult(current, key("selection"), "settings.update", JSONObject().put("provider", provider)
            .put("model", model).put("reasoningEffort", effort ?: JSONObject.NULL))
    }

    suspend fun selectSandbox(value: String) = operationMutex.withLock {
        val current = current()
        require(current.state.value.workspace.settings?.options?.any { it.value == value } == true)
        requestResult(current, key("sandbox"), "settings.update", JSONObject().put("sandbox", value))
    }

    /** Only an advertised, verified tunnel can acquire a phone loopback listener. */
    suspend fun openWeb(): String = webMutex.withLock {
        connect()
        require(active && (foreground || !powerSave)) { "connection_unavailable" }
        val current = current()
        val gateway = RemoteDshWebClient(transport, current.binding)
        gateway.ensureAvailable()
        state.value.webAccessUrl?.let { return@withLock it }
        val generation = webGeneration
        val proxy = RemoteDshLoopbackProxy(gateway, scope)
        try {
            val access = proxy.start()
            ensureBinding(current)
            if (generation != webGeneration || !active || (!foreground && powerSave))
                throw CancellationException("Remote webpage lifecycle changed")
            webProxy = proxy
            stateMutable.update { it.copy(webAccessUrl = access.url) }
            access.url
        } catch (failure: Throwable) { proxy.close(); throw failure }
    }

    fun closeWeb() {
        webGeneration++
        webProxy?.close(); webProxy = null
        stateMutable.update { it.copy(webAccessUrl = null) }
    }

    override fun close() {
        active = false
        coordinator?.stopObserving()
        closeWeb()
        scope.cancel()
    }

    private fun current(expectedScope: RemoteDshScope? = null): RemoteDshSessionCoordinator =
        (coordinator ?: error("connection_unavailable")).also {
            if (expectedScope != null && it.binding != expectedScope) throw AriesProviderException("conflict")
        }

    /** Explicit user-selected downloads use the shared Coordinator pump. */
    suspend fun artifactRequest(operation: String, payload: JSONObject, expectedScope: RemoteDshScope): JSONObject {
        require(operation in RemoteDshClient.ARTIFACT_OPERATIONS)
        return transferRequest(key("artifact"), operation, payload, expectedScope, "remote_artifacts")
    }

    suspend fun attachmentRequest(operationKey: String, operation: String, payload: JSONObject,
        expectedScope: RemoteDshScope): JSONObject {
        require(operation in RemoteDshClient.ATTACHMENT_OPERATIONS)
        return transferRequest(operationKey, operation, payload, expectedScope, "remote_attachments")
    }

    /** Attachments must be uploaded into a real, stable PC Session first. */
    suspend fun prepareAttachmentSession(expectedScope: RemoteDshScope): String = operationMutex.withLock {
        connect()
        val current = current(expectedScope)
        ensureBinding(current, "remote_attachments")
        state.value.selectedSessionId?.let { return@withLock it }
        require(uiDocument.optJSONObject("prompt") == null) { "remote_prompt_pending" }
        val workspace = state.value.selectedWorkspaceId ?: error("remote_workspace_missing")
        var pending = uiDocument.optJSONObject("attachmentSessionCreation")
        if (pending == null) {
            pending = JSONObject().put("key", key("attachment_session")).put("workspace", workspace)
            saveDocument(current, JSONObject(uiDocument.toString()).put("attachmentSessionCreation", pending))
        }
        val intent = checkNotNull(pending)
        require(intent.getString("workspace") == workspace) { "remote_prompt_pending" }
        val created = requestResult(current, intent.getString("key"), "session.create",
            JSONObject().put("workspaceId", workspace), "remote_attachments") as JSONObject
        val session = created.getString("sessionId")
        saveDocument(current, JSONObject(uiDocument.toString()).also { it.remove("attachmentSessionCreation") }
            .put("workspace", workspace).put("session", session))
        follow(current, session)
        session
    }

    private suspend fun transferRequest(operationKey: String, operation: String, payload: JSONObject,
        expectedScope: RemoteDshScope, capability: String): JSONObject {
        if (!active || !foreground && powerSave) throw AriesProviderException("request_cancelled")
        if (coordinator == null) connect()
        val current = current(expectedScope)
        try {
            val result = requestResult(current, operationKey, operation, payload, capability) as? JSONObject
                ?: throw AriesProviderException("protocol_error")
            return JSONObject(result.toString())
        } finally {
            withContext(NonCancellable) { current.consumeTransferReceipt(operationKey) }
        }
    }

    private suspend fun requestResult(current: RemoteDshSessionCoordinator, operationKey: String,
        operation: String, payload: JSONObject, capability: String? = null): Any? {
        activeRequests.incrementAndGet()
        stateMutable.update { it.copy(busy = activeRequests.get() > 0) }
        try {
            current.submit(operationKey, operation, payload)
            val snapshot = withTimeout(resultTimeoutMillis) { current.state.first { state ->
                state.errorCode != null || state.requests.any { it.operationKey == operationKey && it.status in TERMINAL }
            } }
            val result = snapshot.requests.first { it.operationKey == operationKey }
            if (result.status !in TERMINAL) snapshot.errorCode?.let { throw AriesProviderException(it) }
            if (result.status != RemoteDshSessionCoordinator.RequestStatus.SUCCEEDED)
                throw AriesProviderException(result.errorCode ?: "remote_execution_failed")
            ensureBinding(current, capability)
            return result.result
        } finally { activeRequests.decrementAndGet(); stateMutable.update { it.copy(busy = activeRequests.get() > 0) } }
    }

    private suspend fun follow(current: RemoteDshSessionCoordinator, session: String) {
        observeControl(current)
        if (selectedObservation != session) {
            val previous = selectedObservation
            selectedObservation = session
            if (previous != null) {
                current.state.value.requests.filter { it.operation == "session.follow" &&
                    it.operationKey.startsWith("follow_${remoteJsonHash(previous)}_") && it.status !in TERMINAL && it.observing }
                    .forEach { observation ->
                        try { current.cancelObservation(observation.operationKey) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* The durable stop intent prevents automatic resubscription. */ }
                    }
            }
        }
        val existing = current.state.value.requests.lastOrNull { it.operation == "session.follow" &&
            it.operationKey.startsWith("follow_${remoteJsonHash(session)}_") && it.observing }
        if (existing?.status !in setOf(RemoteDshSessionCoordinator.RequestStatus.SUBMITTED,
                RemoteDshSessionCoordinator.RequestStatus.STREAMING))
            current.observeSession("follow_${remoteJsonHash(session)}_${UUID.randomUUID().toString().replace("-", "")}", session)
    }

    private suspend fun observeControl(current: RemoteDshSessionCoordinator) {
        if (current.state.value.requests.none { it.operationKey == "control" })
            current.observeControl("control")
    }

    private suspend fun observeChildren(current: RemoteDshSessionCoordinator,
        projection: RemoteDshSessionCoordinator.State) = childMutex.withLock {
        val parent = state.value.selectedSessionId
        val children = parent?.let { projection.sessions[it]?.activity?.subagents }.orEmpty().take(32)
        val desiredPrefixes = children.map { child ->
            "child_${remoteJsonHash("$parent/${child.agentId}")}_"
        }.toSet()
        current.state.value.requests.filter { it.operation == "session.follow" && it.operationKey.startsWith("child_") &&
            desiredPrefixes.none(it.operationKey::startsWith) && it.status !in TERMINAL && it.observing }
            .forEach { current.cancelObservation(it.operationKey) }
        if (parent == null) return@withLock
        for (child in children) {
            val prefix = "child_${remoteJsonHash("$parent/${child.agentId}")}_"
            if (current.state.value.requests.none { it.operationKey.startsWith(prefix) && it.status !in TERMINAL && it.observing }) {
                current.observeChild("$prefix${UUID.randomUUID().toString().replace("-", "")}", parent, child.agentId)
            }
        }
    }

    private suspend fun ensureBinding(current: RemoteDshSessionCoordinator, capability: String? = null) {
        if (coordinator !== current) throw AriesProviderException("conflict")
        val connection = transport.connectionState()
        current.binding.requireCurrent(connection)
        if (capability != null && connection.optJSONObject("capabilities")?.optBoolean(capability) != true)
            throw AriesProviderException("unsupported_method")
    }

    private suspend fun reconcileSelection(current: RemoteDshSessionCoordinator) {
        val snapshot = current.state.value.workspace
        val workspace = snapshot.workspaces.firstOrNull { it.id == state.value.selectedWorkspaceId } ?: snapshot.workspaces.firstOrNull()
        val available = snapshot.sessions.map(RemoteWorkspaceStore.Session::id).toSet() - snapshot.archives.map(RemoteWorkspaceStore.Archive::sessionId).toSet()
        val session = workspace?.sessionIds?.let { ids -> state.value.selectedSessionId?.takeIf { it in ids && it in available }
            ?: ids.firstOrNull { it in available } }
        saveSelection(current, workspace?.id, session)
        if (session != null) follow(current, session) else {
            val previous = selectedObservation
            selectedObservation = null
            current.state.value.requests.filter { it.operation == "session.follow" && previous != null &&
                it.operationKey.startsWith("follow_${remoteJsonHash(previous)}_") && it.status !in TERMINAL && it.observing }
                .forEach { current.cancelObservation(it.operationKey) }
        }
    }

    private fun canRecoverLifecycle(current: RemoteDshSessionCoordinator): Boolean =
        coordinator === current && active && (foreground || !powerSave) && state.value.connected &&
            state.value.remoteOnline && state.value.connection?.optString("pairing_status") == "paired" &&
            state.value.connection?.optJSONObject("capabilities")?.let {
                it.optBoolean("remote_workspace_lifecycle") || it.optBoolean("remote_archive_delete")
            } == true

    /** Only previously persisted, exact user actions resume. A connection never grants a new action. */
    private fun scheduleLifecycleRecovery(current: RemoteDshSessionCoordinator) {
        if (!canRecoverLifecycle(current) || (uiDocument.optJSONObject("lifecycle")?.length() ?: 0) == 0 ||
            lifecycleRecoveryJob?.isActive == true) return
        lifecycleRecoveryJob = scope.launch {
            try {
                operationMutex.withLock {
                    if (!canRecoverLifecycle(current)) return@withLock
                    val intents = JSONObject((uiDocument.optJSONObject("lifecycle") ?: JSONObject()).toString())
                    for (fingerprint in intents.keys()) {
                        if (!canRecoverLifecycle(current)) return@withLock
                        ensureBinding(current)
                        val intent = intents.getJSONObject(fingerprint)
                        if (uiDocument.optJSONObject("lifecycle")?.has(fingerprint) != true) continue
                        val operation = intent.getString("operation")
                        val payload = intent.getJSONObject("payload")
                        if (state.value.connection?.optJSONObject("capabilities")?.optBoolean(lifecycleCapability(operation)) != true) continue
                        val confirmation = intent.optJSONObject("archiveConfirmation")?.let(RemoteWorkspaceStore::archiveDeletionFromJson)
                        confirmation?.let { require(it.scope == current.binding) { "remote_archive_confirmation_scope_changed" } }
                        if (operation == "workspace.files.delete") {
                            require(payload.getBoolean("confirmDelete")) { "remote_lifecycle_payload_invalid" }
                            val removal = current.state.value.workspace.removals.firstOrNull { it.ticket == payload.getString("ticket") }
                                ?: error("remote_removal_missing")
                            require(removal.workspace.path == payload.getString("confirmedPath")) { "remote_delete_path_changed" }
                        }
                        val refresh = operation in SELECTION_LIFECYCLE_OPERATIONS
                        lifecycleResult(current, operation, payload, refreshSelection = refresh, refreshArchives = refresh,
                            archiveConfirmation = confirmation)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (coordinator === current) stateMutable.update { it.copy(error = remoteFailure(failure)) }
            }
        }
    }

    /** UI interruption only stops waiting. The saved mutation remains owned by its exact scope. */
    private suspend fun lifecycleResult(current: RemoteDshSessionCoordinator, operation: String, payload: JSONObject,
        refreshSelection: Boolean = false, refreshArchives: Boolean = false,
        archiveConfirmation: RemoteWorkspaceStore.ArchiveDeletion? = null): JSONObject {
        ensureBinding(current, lifecycleCapability(operation))
        val fingerprint = remoteJsonHash(JSONObject().put("operation", operation).put("payload", payload))
        val intents = uiDocument.optJSONObject("lifecycle") ?: JSONObject()
        val saved = intents.optJSONObject(fingerprint)
        val confirmation = archiveConfirmation?.toJson() ?: saved?.optJSONObject("archiveConfirmation")
        if (saved != null && archiveConfirmation != null) require(
            RemoteWorkspaceStore.archiveDeletionFromJson(saved.getJSONObject("archiveConfirmation")) == archiveConfirmation) {
            "remote_archive_confirmation_changed"
        }
        val subject = lifecycleSubject(operation, payload, confirmation)
        require(intents.keys().asSequence().none { candidate -> candidate != fingerprint &&
            intents.getJSONObject(candidate).let { lifecycleSubject(it.getString("operation"), it.getJSONObject("payload"),
                it.optJSONObject("archiveConfirmation")) } == subject }) { "remote_lifecycle_pending" }
        val intent = saved ?: JSONObject().put("key", key("lifecycle")).put("operation", operation)
            .put("payload", JSONObject(payload.toString())).also { value ->
                if (confirmation != null) value.put("archiveConfirmation", JSONObject(confirmation.toString()))
                require(intents.length() < 32) { "remote_lifecycle_pending_limit" }
                val nextIntents = JSONObject(intents.toString()).put(fingerprint, value)
                saveDocument(current, JSONObject(uiDocument.toString()).put("lifecycle", nextIntents))
            }
        val operationKey = intent.getString("key")
        val result: JSONObject
        try {
            result = requestResult(current, operationKey, operation, intent.getJSONObject("payload"), lifecycleCapability(operation)) as JSONObject
        } catch (failure: Throwable) {
            // A definite rejection cannot be retried under another payload or
            // mistaken for success. Transport uncertainty keeps the exact ID.
            if (failure !is CancellationException && current.state.value.requests.any {
                    it.operationKey == operationKey && it.status == RemoteDshSessionCoordinator.RequestStatus.FAILED })
                completeLifecycle(current, fingerprint)
            throw failure
        }
        if (operation in RemoteDshClient.ARCHIVE_DELETE_OPERATIONS) {
            val proof = confirmation?.let(RemoteWorkspaceStore::archiveDeletionFromJson)
            val receipt = RemoteWorkspaceStore.parseArchiveDeletion(current.binding, result, proof)
            require(if (operation == "session.archived.delete.prepare") !receipt.deleted && receipt.sessionId == payload.getString("sessionId")
                else receipt.deleted && receipt.ticket == payload.getString("ticket")) { "remote_archive_delete_not_confirmed" }
        }
        // The mutation keeps its original key. Each retry reads current state,
        // rather than reusing a successful snapshot from a previous refresh.
        val readKey = key("refresh")
        if (refreshSelection) {
            requestResult(current, "${readKey}_workspaces", "workspace.list", JSONObject())
            requestResult(current, "${readKey}_sessions", "session.list", JSONObject())
        }
        if (refreshArchives) {
            requestResult(current, "${readKey}_removed", "workspace.removed.list", JSONObject())
            requestResult(current, "${readKey}_archives", "session.archived.list", JSONObject())
        }
        if (refreshSelection) reconcileSelection(current)
        completeLifecycle(current, fingerprint)
        stateMutable.update { it.copy(error = null) }
        return JSONObject(result.toString())
    }

    private suspend fun completeLifecycle(current: RemoteDshSessionCoordinator, fingerprint: String) {
        val intents = JSONObject((uiDocument.optJSONObject("lifecycle") ?: JSONObject()).toString()).also { it.remove(fingerprint) }
        saveDocument(current, JSONObject(uiDocument.toString()).put("lifecycle", intents))
    }

    private fun validateUiDocument(value: JSONObject, expectedScope: RemoteDshScope? = null) {
        require(value.optInt("schema") == 1)
        value.optJSONObject("lifecycle")?.let { intents ->
            require(intents.length() <= 32)
            val keys = mutableSetOf<String>()
            intents.keys().forEach { fingerprint ->
                val intent = intents.getJSONObject(fingerprint)
                val operation = intent.getString("operation")
                require(operation in RemoteDshClient.LIFECYCLE_OPERATIONS)
                require(intent.getString("key").matches(Regex("[A-Za-z0-9_-]{1,128}")) && keys.add(intent.getString("key")))
                require(fingerprint == remoteJsonHash(JSONObject().put("operation", operation).put("payload", intent.getJSONObject("payload"))))
                if (operation == "session.archived.delete") {
                    val proof = RemoteWorkspaceStore.archiveDeletionFromJson(intent.getJSONObject("archiveConfirmation"))
                    require(proof.status == "prepared" && !proof.deleted && (expectedScope == null || proof.scope == expectedScope))
                    require(intent.getJSONObject("payload").getString("ticket") == proof.ticket &&
                        intent.getJSONObject("payload").getBoolean("confirmDelete")) { "remote_archive_confirmation_changed" }
                } else require(!intent.has("archiveConfirmation")) { "remote_lifecycle_payload_invalid" }
                lifecycleSubject(operation, intent.getJSONObject("payload"), intent.optJSONObject("archiveConfirmation"))
            }
        }
    }

    private fun lifecycleOperations(value: JSONObject): List<String> = value.optJSONObject("lifecycle")?.let { intents ->
        intents.keys().asSequence().map { intents.getJSONObject(it).getString("operation") }.toList()
    }.orEmpty()

    private fun lifecycleSubject(operation: String, payload: JSONObject, confirmation: JSONObject? = null): String = when {
        operation == "session.archived.delete" -> {
            val proof = RemoteWorkspaceStore.archiveDeletionFromJson(confirmation ?: error("remote_archive_confirmation_missing"))
            require(proof.ticket == payload.getString("ticket")) { "remote_archive_confirmation_changed" }
            "session:${proof.sessionId}"
        }
        payload.has("ticket") -> "ticket:${payload.getString("ticket")}"
        payload.has("sessionId") -> "session:${payload.getString("sessionId")}"
        payload.has("workspaceId") -> "workspace:${payload.getString("workspaceId")}"
        else -> error("remote_lifecycle_payload_invalid")
    }

    private suspend fun saveSelection(current: RemoteDshSessionCoordinator, workspace: String?, session: String?) =
        saveDocument(current, JSONObject(uiDocument.toString()).put("workspace", workspace).put("session", session))

    private suspend fun saveDocument(current: RemoteDshSessionCoordinator, next: JSONObject) =
        withContext(NonCancellable + Dispatchers.IO) { bindingMutex.withLock {
            ensureBinding(current)
            validateUiDocument(next, current.binding)
            remoteValidateJson(next, 1024 * 1024)
            selectionFile(current.binding).write(next)
            uiDocument = next
            stateMutable.update { it.copy(selectedWorkspaceId = next.remoteOptionalString("workspace"),
                selectedSessionId = next.remoteOptionalString("session"), pendingPrompt = next.optJSONObject("prompt") != null,
                pendingLifecycleOperations = lifecycleOperations(next)) }
        } }

    companion object {
        private val SELECTION_LIFECYCLE_OPERATIONS = setOf("workspace.remove", "workspace.removed.restore", "session.archive", "session.restore", "session.archived.delete")
        private fun lifecycleCapability(operation: String) = if (operation in RemoteDshClient.ARCHIVE_DELETE_OPERATIONS)
            "remote_archive_delete" else "remote_workspace_lifecycle"
        private val TERMINAL = setOf(RemoteDshSessionCoordinator.RequestStatus.SUCCEEDED,
            RemoteDshSessionCoordinator.RequestStatus.FAILED, RemoteDshSessionCoordinator.RequestStatus.UNCERTAIN)
        private fun key(prefix: String) = "${prefix}_${UUID.randomUUID().toString().replace("-", "")}"
        fun remoteFailure(failure: Throwable): String = remoteCodeMessage(
            (failure as? AriesProviderException)?.code ?: failure.message.orEmpty())
        private fun remoteCodeMessage(code: String): String = when (code) {
            "remote_offline" -> "电脑当前离线；已接收的任务会继续执行"
            "revoked", "conflict" -> "配对关系已变化，请重新确认目标电脑"
            "unsupported_method" -> "电脑尚未启用此连接能力，请更新双端连接插件"
            "remote_workspace_missing", "remote_session_missing" -> "请先选择电脑上的工作区与会话"
            "remote_prompt_pending" -> "上次发送仍待确认，请用原内容重试；不会重复执行任务"
            "request_uncertain" -> "电脑未能确认任务接收状态，请检查原会话；没有重新提交任务"
            "remote_catalog_missing", "remote_model_missing" -> "请先刷新电脑的模型目录"
            "remote_removal_missing" -> "请先刷新电脑的工作区移除记录"
            "remote_delete_path_changed" -> "电脑目录已变化，请重新确认实际路径"
            "remote_restore_workspace_first" -> "请先还原所属的电脑工作区，再还原对话"
            "remote_archive_missing", "remote_archive_version_changed" -> "归档已变化，请刷新并重新确认该对话"
            "remote_archive_confirmation_missing", "remote_archive_confirmation_changed", "remote_archive_confirmation_scope_changed" -> "删除确认已失效，请重新确认当前电脑和归档对话"
            "remote_archive_delete_not_confirmed" -> "电脑尚未返回精确的永久删除结果，归档记录已保留"
            "remote_lifecycle_pending_limit" -> "仍有未确认的远程操作，请先完成原操作"
            "remote_lifecycle_pending" -> "上次操作仍待电脑确认，请先用原操作重试"
            else -> "连接或远程操作未完成，请检查 Aries 与电脑 DSH 状态"
        }
    }
}
