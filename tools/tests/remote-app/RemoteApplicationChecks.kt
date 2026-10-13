package com.dafeiyu.mobile.data

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

private val binding = RemoteDshScope("fixture-node", "fixture-group", "fixture-phone", "fixture-computer")

private class Journal : RemoteDshJournal {
    var value: JSONObject? = null
    var fail = false
    override fun read(): JSONObject? = value?.let { JSONObject(it.toString()) }
    override fun write(value: JSONObject) {
        if (fail) throw IOException("fixture disk failure")
        this.value = JSONObject(value.toString())
    }
}

private class Computer : RemoteDshTransport {
    var connection = binding.toJson().put("local_device", JSONObject().put("id", binding.localDeviceId))
        .put("remote_device", JSONObject().put("id", binding.remoteDeviceId).put("online", "online"))
        .put("pairing_status", "paired").put("server_connected", true)
        .put("capabilities", JSONObject().put("remote_web_tunnel", false).put("remote_workspace_lifecycle", true)
            .put("remote_archive_delete", true).put("remote_artifacts", true).put("remote_attachments", true))
    val requests = mutableListOf<RemoteDshClient.Request>()
    val observationsCancelled = mutableListOf<String>()
    private val pending = mutableListOf<RemoteDshClient.Event>()
    private var cursor = 0L
    var failPromptSend = false
    var holdPromptReceipt = false
    var rejectPrompt = false
    var persistSettings = true
    var executedPrompts = 0
    var failLifecycleSend: String? = null
    var failOperationSend: String? = null
    var holdLifecycleReceipt: String? = null
    var rejectLifecycle = false
    var missingRestoreDirectory = false
    var workspacePresent = true
    var workspaceId = "pc-workspace"
    var holdArtifactReceipt = false
    var rejectAcknowledgement = false
    var failRetirement = false
    var onRetirement: (() -> Unit)? = null
    val retirementAttempts = mutableListOf<String>()
    val retired = mutableSetOf<String>()
    private val acknowledged = mutableSetOf<String>()
    private val chunk = java.util.Base64.getEncoder().encodeToString(ByteArray(48 * 1024) { (it % 251).toByte() })
    val archived = mutableSetOf<String>()
    val removals = mutableMapOf<String, JSONObject>()
    val executedLifecycle = mutableMapOf<String, Int>()
    val archiveDeletionReceipts = mutableMapOf<String, JSONObject>()
    var archiveVersion = "fixture-archive-v1"
    private val admitted = mutableSetOf<String>()

    override suspend fun connectionState() = synchronized(this) { JSONObject(connection.toString()) }
    override suspend fun submit(request: RemoteDshClient.Request): JSONObject = synchronized(this) {
        requests += request
        if (request.operation == failOperationSend) throw IOException("fixture transport failed")
        if (request.operation == "session.prompt" && failPromptSend) throw IOException("fixture send failed")
        if (request.operation == failLifecycleSend) throw IOException("fixture lifecycle transport failed")
        if (admitted.add(request.requestId)) {
            when (request.operation) {
                "session.prompt" -> {
                    if (!rejectPrompt) executedPrompts++
                    if (!holdPromptReceipt) enqueue(request, if (rejectPrompt) "error" else "result",
                        if (rejectPrompt) JSONObject().put("code", "permission_denied") else JSONObject().put("queued", true))
                }
                "session.follow", "session.control" -> enqueue(request, "result", JSONObject().put("stream", true))
                else -> {
                    if (request.operation == "artifact.read" && holdArtifactReceipt) return@synchronized JSONObject().put("message_id", "held")
                    if (request.operation in RemoteDshClient.LIFECYCLE_OPERATIONS) {
                        executedLifecycle[request.operation] = (executedLifecycle[request.operation] ?: 0) + 1
                        if (request.operation == holdLifecycleReceipt) return@synchronized JSONObject().put("message_id", "held")
                        if (rejectLifecycle && !request.operation.endsWith(".list")) {
                            enqueue(request, "error", JSONObject().put("code", "permission_denied"))
                            return@synchronized JSONObject().put("message_id", "rejected")
                        }
                    }
                    enqueue(request, "result", result(request))
                }
            }
        }
        JSONObject().put("message_id", "admitted-${request.requestId}")
    }
    fun receipt(request: RemoteDshClient.Request) = synchronized(this) { enqueue(request, "result", JSONObject().put("queued", true)) }
    fun lifecycleReceipt(request: RemoteDshClient.Request) = synchronized(this) { enqueue(request, "result", result(request)) }
    private fun enqueue(request: RemoteDshClient.Request, type: String, payload: Any?) {
        pending += RemoteDshClient.Event(request.requestId, 0, type, payload,
            "receipt-${request.requestId}", binding.remoteDeviceId)
    }
    override suspend fun poll(scope: RemoteDshScope, cursor: String?, limit: Int): RemoteDshClient.PollResult = synchronized(this) {
        val page = pending.take(limit)
        pending.removeAll(page.toSet())
        this.cursor += page.size
        RemoteDshClient.PollResult(page, this.cursor.toString(), false)
    }
    override suspend fun acknowledge(scope: RemoteDshScope, event: RemoteDshClient.Event): JSONObject = synchronized(this) {
        if (rejectAcknowledgement) throw IOException("fixture ACK unavailable")
        acknowledged += event.requestId
        JSONObject().put("acknowledged", true)
    }
    override suspend fun retireTransferReceipt(scope: RemoteDshScope, requestId: String, operation: String, terminalSequence: Long): JSONObject = synchronized(this) {
        scope.requireCurrent(connection)
        check(operation in RemoteDshClient.CONSUMABLE_TRANSFER_OPERATIONS && terminalSequence == 0L)
        check(requestId in acknowledged) { "Native retirement preceded durable ACK" }
        retirementAttempts += requestId
        if (failRetirement) throw IOException("fixture retirement unavailable")
        retired += requestId
        onRetirement?.invoke()
        JSONObject().put("retired", true)
    }
    override suspend fun cancelObservation(scope: RemoteDshScope, requestId: String): JSONObject {
        observationsCancelled += requestId
        return JSONObject().put("submitted", true)
    }
    private fun result(request: RemoteDshClient.Request): Any = when (request.operation) {
        "workspace.list" -> JSONObject().put("workspaces", JSONArray().also { if (workspacePresent) it.put(workspace()) })
        "session.list" -> JSONArray().also { if (workspacePresent) for (id in listOf("pc-session", "pc-session-two")) {
            if (id !in archived) it.put(session(id))
        } }
        "models.catalog" -> JSONObject().put("default", selection()).put("routableProviders", JSONArray().put("pc-provider"))
            .put("groups", JSONArray().put(JSONObject().put("id", "pc-provider").put("name", "Computer provider")
                .put("models", JSONArray().put(JSONObject().put("id", "pc-model").put("name", "Computer model")
                    .put("reasoning", JSONObject().put("efforts", JSONArray().put(JSONObject().put("id", "medium")
                        .put("name", "Medium"))).put("defaultEffort", "medium")))))).put("failures", JSONArray())
        "settings.describe" -> JSONObject().put("selection", selection()).put("permissions", JSONObject()
            .put("defaultPreset", "read-only").put("defaultOptions", JSONArray()
                .put(JSONObject().put("value", "read-only").put("label", "Read only"))))
        "settings.update" -> JSONObject().put("selected", selection()).put("persisted", persistSettings)
        "session.create" -> JSONObject().put("sessionId", "pc-new-session")
        "workspace.resolve" -> JSONObject().put("status", "needs_directory").put("name", request.payload.getString("name"))
        "artifact.read" -> JSONObject().put("ticket", "fixture-artifact-ticket").put("offset", request.payload.getLong("offset"))
            .put("bytesBase64", chunk).put("size", 32 * 1024 * 1024).put("version", "1".repeat(64)).put("sha256", "2".repeat(64))
        "attachment.write", "attachment.status" -> JSONObject().put("uploadId", request.payload.getString("uploadId")).put("received", 48 * 1024)
        "attachment.prepare", "attachment.commit", "attachment.cancel" -> JSONObject().put("uploadId", "fixture-upload").put("status", "ready")
        "workspace.remove.prepare" -> removal("prepared").also { removals["pc-removal-ticket"] = JSONObject(it.toString()) }
        "workspace.remove" -> removal("removed").also {
            workspacePresent = false; archived += listOf("pc-session", "pc-session-two")
            removals["pc-removal-ticket"] = JSONObject(it.toString())
        }
        "workspace.files.delete" -> removal("files_deleted").also { removals["pc-removal-ticket"] = JSONObject(it.toString()) }
        "workspace.removed.list" -> JSONObject().put("removals", JSONArray(removals.values.filter {
            it.getString("phase") != "prepared"
        }))
        "workspace.removed.restore" -> if (missingRestoreDirectory) removal("removed").put("status", "needs_directory") else {
            workspacePresent = true; workspaceId = "pc-restored-workspace"; archived.clear(); removals.clear()
            removal("restored").put("status", "restored").put("restoredId", workspaceId)
                .put("restoredSessions", JSONArray().put("pc-session").put("pc-session-two"))
        }
        "session.archive" -> archive(request.payload.getString("sessionId")).also {
            archived += request.payload.getString("sessionId"); it.put("archived", true).put("tasksStopped", true)
        }
        "session.archived.list" -> JSONObject().put("archives", JSONArray(archived.map(::archive)))
        "session.archived.delete.prepare" -> JSONObject().put("ticket", "ticket-${request.requestId}")
            .put("sessionId", request.payload.getString("sessionId")).put("archiveVersion", archiveVersion)
            .put("archivedAt", 1000).put("expiresAt", 1000L + 30L * 86_400_000)
            .put("executionDevice", "remote_computer").put("status", "prepared").put("deleted", false)
            .also { archiveDeletionReceipts[it.getString("ticket")] = JSONObject(it.toString()) }
        "session.archived.delete" -> JSONObject(archiveDeletionReceipts.getValue(request.payload.getString("ticket")).toString())
            .also { check(request.payload.getBoolean("confirmDelete")); it.put("status", "deleted").put("deleted", true).put("deletedAt", 2000)
                archived -= it.getString("sessionId") }
        "session.restore" -> JSONObject().put("sessionId", request.payload.getString("sessionId")).also {
            if (workspacePresent) { archived -= request.payload.getString("sessionId"); it.put("restored", true) }
            else it.put("restored", false).put("status", "restore_workspace_first").put("path", "G:/computer/work")
        }
        else -> error("unexpected operation ${request.operation}")
    }
    private fun workspace() = JSONObject().put("workspaceId", workspaceId).put("title", "Computer workspace")
        .put("path", "G:/computer/work").put("sessionIds", JSONArray().put("pc-session").put("pc-session-two"))
    private fun removal(phase: String) = JSONObject().put("ticket", "pc-removal-ticket").put("workspace", workspace())
        .put("order", 0).put("phase", phase).put("createdAt", 1000).put("removedAt", if (phase == "prepared") JSONObject.NULL else 2000)
        .put("restoredId", JSONObject.NULL).put("filesDeleted", phase == "files_deleted").put("executionDevice", "remote_computer")
        .put("filesDeleteRequiresSeparateConfirmation", true).put("filesIdentityVerified", true).put("stopRequired", false)
        .put("activity", JSONArray().put(JSONObject().put("sessionId", "pc-session").put("running", false).put("queued", 0).put("activity", 0)))
    private fun archive(id: String) = JSONObject().put("sessionId", id).put("workspace", workspace()).put("order", 0)
        .put("archivedAt", 1000).put("expiresAt", 1000L + 30L * 86_400_000).put("remainingDays", 30)
        .put("archiveVersion", archiveVersion)
    private fun session(id: String) = JSONObject().put("sessionId", id).put("cwd", "G:/computer/work")
        .put("running", false).put("blank", false).put("agentAvailable", true).put("updatedAt", 1000)
    private fun selection() = JSONObject().put("provider", "pc-provider").put("model", "pc-model").put("reasoningEffort", "medium")
}

private class Fixture(root: File, name: String) {
    val computer = Computer()
    val selection = Journal()
    private val selections = mutableMapOf<String, Journal>()
    private val requests = mutableMapOf<String, Journal>()
    private val workspaceRoot = File(root, name)
    fun controller(timeout: Long = 5000L, pollInterval: Long = 250L) = RemoteDshApplicationController(computer, { scope ->
        RemoteDshSessionCoordinator(scope, computer, RemoteWorkspaceStore(workspaceRoot),
            requests.getOrPut(scope.storageKey) { Journal() })
    }, { scope -> if (scope == binding) selection else selections.getOrPut(scope.storageKey) { Journal() } },
        pollIntervalMillis = pollInterval, resultTimeoutMillis = timeout)
    suspend fun ready(): RemoteDshApplicationController = controller().also {
        it.setActive(true)
        it.refresh()
    }
}

private suspend fun fails(body: suspend () -> Unit): Throwable {
    try { body() } catch (failure: Throwable) {
        if (failure is CancellationException) throw failure
        return failure
    }
    error("Expected fixture failure")
}

fun main(args: Array<String>) = runBlocking {
    val root = File(args.single()).apply { mkdirs() }
    var passes = 0
    suspend fun case(name: String, body: suspend () -> Unit) { body(); passes++; println("PASS $name") }
    case("empty DSH watermarks accept -1 while event sequences remain non-negative") {
        val empty = JSONObject().put("type", "snapshot")
            .put("header", JSONObject().put("id", "empty-session"))
            .put("cursor", -1).put("hasMore", false)
            .put("records", JSONArray())
            .put("projections", JSONObject().put("asOfSeq", -1).put("values", JSONObject()))
        val public = RemoteDshActivityMapper.publicFrame(empty)
        check(public.getLong("cursor") == -1L)
        check(public.getJSONObject("projections").getLong("asOfSeq") == -1L)
        val mapper = RemoteDshActivityMapper("empty-session")
        mapper.applyFollowFrame(empty)
        check(mapper.view().cursor == -1L)
        val negativeEvent = JSONObject().put("type", "event").put("event", JSONObject()
            .put("type", "turn/start").put("seq", -1).put("time", 0).put("data", JSONObject()))
        check(runCatching { RemoteDshActivityMapper.publicFrame(negativeEvent) }.isFailure)
    }
    case("remote selections persist separately from phone workspace files") {
        val fixture = Fixture(root, "selection")
        val controller = fixture.ready()
        controller.selectSession("pc-session-two")
        controller.setActive(false)
        val restored = fixture.controller()
        restored.connect()
        check(restored.state.value.selectedWorkspaceId == "pc-workspace")
        check(restored.state.value.selectedSessionId == "pc-session-two")
        check(File(root, "selection").walkTopDown().filter { it.isFile }.all { it.name == "${binding.storageKey}.json" })
        restored.setActive(false)
    }
    case("transport failure keeps prompt content and retry reuses durable request ID") {
        val fixture = Fixture(root, "retry")
        val controller = fixture.ready()
        fixture.computer.failPromptSend = true
        fails { controller.send("same task") }
        check(controller.state.value.pendingPrompt)
        val first = fixture.computer.requests.last { it.operation == "session.prompt" }
        fails { controller.send("changed task") }
        check(fixture.computer.requests.count { it.operation == "session.prompt" } == 1)
        fixture.computer.failPromptSend = false
        check(controller.send("same task"))
        val retries = fixture.computer.requests.filter { it.operation == "session.prompt" }
        check(retries.size == 2 && retries.all { it.requestId == first.requestId })
        check(!controller.state.value.pendingPrompt && fixture.computer.executedPrompts == 1)
        controller.setActive(false)
    }
    case("Aries admission is insufficient; composer clears only on computer receipt") {
        val fixture = Fixture(root, "admission")
        val controller = fixture.ready()
        fixture.computer.holdPromptReceipt = true
        val sending = async { controller.send("wait for persistent receipt") }
        withTimeout(5000) { while (fixture.computer.requests.none { it.operation == "session.prompt" }) delay(10) }
        delay(100)
        check(controller.state.value.pendingPrompt && !sending.isCompleted)
        fixture.computer.receipt(fixture.computer.requests.last { it.operation == "session.prompt" })
        check(sending.await() && !controller.state.value.pendingPrompt)
        controller.setActive(false)
    }
    case("cancelled waiting composer preserves admitted intent without cancelling computer task") {
        val fixture = Fixture(root, "cancel-wait")
        val controller = fixture.ready()
        fixture.computer.holdPromptReceipt = true
        val sending = async { controller.send("continue on computer") }
        withTimeout(5000) { while (fixture.computer.requests.none { it.operation == "session.prompt" }) delay(10) }
        sending.cancel()
        try { sending.await() } catch (_: CancellationException) { }
        check(controller.state.value.pendingPrompt)
        check(fixture.computer.requests.none { it.operation == "session.cancel" })
        controller.setActive(false)
    }
    case("failed computer receipt never masquerades as sent task") {
        val fixture = Fixture(root, "rejected")
        val controller = fixture.ready()
        fixture.computer.rejectPrompt = true
        check((fails { controller.send("rejected") } as AriesProviderException).code == "permission_denied")
        check(controller.state.value.pendingPrompt && fixture.computer.executedPrompts == 0)
        controller.setActive(false)
    }
    case("failed composer persistence cannot submit a computer task") {
        val fixture = Fixture(root, "disk")
        val controller = fixture.ready()
        fixture.selection.fail = true
        fails { controller.send("never admitted") }
        check(fixture.computer.requests.none { it.operation == "session.prompt" })
        controller.setActive(false)
    }
    case("unknown presence preserves confirmed selection; terminal relation clears projection") {
        val fixture = Fixture(root, "presence")
        val controller = fixture.ready()
        fixture.computer.connection.put("pairing_status", "unknown").put("server_connected", false)
        controller.connect()
        check(controller.state.value.selectedSessionId == "pc-session")
        fixture.computer.connection.put("pairing_status", "revoked")
        controller.connect()
        check(controller.state.value.projection == null && controller.state.value.selectedSessionId == null)
        check(!controller.state.value.pendingPrompt)
        controller.setActive(false)
    }
    case("changing execution device cannot reuse prior computer selection") {
        val fixture = Fixture(root, "device")
        val controller = fixture.ready()
        fixture.computer.connection.put("remote_device", JSONObject().put("id", "second-pc").put("online", "online"))
        controller.connect()
        check(controller.state.value.selectedWorkspaceId == null && controller.state.value.selectedSessionId == null)
        controller.setActive(false)
    }
    case("changing selected session stops observation only; global control has one stable intent") {
        val fixture = Fixture(root, "follow")
        val controller = fixture.ready()
        controller.selectSession("pc-session-two")
        check(fixture.computer.observationsCancelled.isNotEmpty())
        controller.selectSession("pc-session")
        check(fixture.computer.requests.count { it.operation == "session.follow" } == 3)
        check(fixture.computer.requests.none { it.operation == "session.cancel" })
        check(fixture.computer.requests.count { it.operation == "session.control" } == 1)
        controller.setActive(false)
    }
    case("global selection requires true persistent settings receipt") {
        val fixture = Fixture(root, "persist-settings")
        val controller = fixture.ready()
        fixture.computer.persistSettings = false
        fails { controller.selectModel("pc-provider", "pc-model", "medium") }
        check(controller.state.value.projection?.workspace?.settings?.selection?.model == "pc-model")
        controller.setActive(false)
    }
    case("unadvertised webpage tunnel cannot start phone proxy or submit a webpage request") {
        val fixture = Fixture(root, "web-capability")
        val controller = fixture.ready()
        check((fails { controller.openWeb() } as AriesProviderException).code == "unsupported_method")
        check(controller.state.value.webAccessUrl == null && fixture.computer.requests.none { it.operation.startsWith("web.") })
        controller.setForeground(false, true)
        check(controller.state.value.webSuspended)
        check(fixture.computer.requests.none { it.operation == "session.cancel" })
        controller.setActive(false)
    }

    case("workspace removal persists exact mutation ID across controller restart and updates selection only after receipt") {
        val fixture = Fixture(root, "removal-retry")
        val controller = fixture.ready()
        val prepared = controller.prepareWorkspaceRemoval("pc-workspace", binding)
        check(prepared.workspace.path == "G:/computer/work" && !prepared.stopRequired)
        fixture.computer.failLifecycleSend = "workspace.remove"
        fails { controller.removeWorkspace(prepared.ticket, expectedScope = binding) }
        check(controller.state.value.selectedWorkspaceId == "pc-workspace")
        check(controller.state.value.pendingLifecycleOperations == listOf("workspace.remove"))
        val first = fixture.computer.requests.last { it.operation == "workspace.remove" }
        controller.close()
        fixture.computer.failLifecycleSend = null
        val restored = fixture.controller()
        restored.setActive(true)
        restored.connect()
        val recovered = withTimeout(5000) { restored.state.first { state ->
            state.pendingLifecycleOperations.isEmpty() && state.projection?.workspace?.removals?.any { it.phase == "removed" } == true
        } }
        val removed = recovered.projection!!.workspace.removals.single { it.ticket == prepared.ticket }
        check(removed.phase == "removed" && !removed.filesDeleted)
        check(restored.state.value.selectedWorkspaceId == null && restored.state.value.selectedSessionId == null)
        check(restored.state.value.projection!!.workspace.archives.size == 2)
        check(restored.state.value.pendingLifecycleOperations.isEmpty())
        check(fixture.computer.requests.filter { it.operation == "workspace.remove" }.all { it.requestId == first.requestId })
        check(fixture.computer.executedLifecycle["workspace.remove"] == 1)
        restored.close()
    }
    case("interrupted mutation waiting does not cancel computer work or resubmit an admitted deletion") {
        val fixture = Fixture(root, "lifecycle-admission")
        val controller = fixture.ready()
        val prepared = controller.prepareWorkspaceRemoval("pc-workspace")
        fixture.computer.holdLifecycleReceipt = "workspace.remove"
        val removing = async { controller.removeWorkspace(prepared.ticket) }
        withTimeout(5000) { while (fixture.computer.requests.none { it.operation == "workspace.remove" }) delay(10) }
        removing.cancel()
        try { removing.await() } catch (_: CancellationException) { }
        check(controller.state.value.pendingLifecycleOperations == listOf("workspace.remove"))
        check(controller.state.value.selectedWorkspaceId == "pc-workspace")
        val original = fixture.computer.requests.single { it.operation == "workspace.remove" }
        fixture.computer.lifecycleReceipt(original)
        check(controller.removeWorkspace(prepared.ticket).phase == "removed")
        check(fixture.computer.requests.count { it.operation == "workspace.remove" } == 1)
        check(fixture.computer.requests.none { it.operation == "session.cancel" })
        controller.close()
    }
    case("pending mutation cannot change stop confirmation under a fresh request ID") {
        val fixture = Fixture(root, "lifecycle-payload")
        val controller = fixture.ready()
        val prepared = controller.prepareWorkspaceRemoval("pc-workspace")
        fixture.computer.failLifecycleSend = "workspace.remove"
        fails { controller.removeWorkspace(prepared.ticket, stopRunning = false) }
        check(fails { controller.removeWorkspace(prepared.ticket, stopRunning = true) }.message == "remote_lifecycle_pending")
        val attempts = fixture.computer.requests.filter { it.operation == "workspace.remove" }
        check(attempts.map { it.requestId }.toSet().size == 1 && attempts.all { !it.payload.getBoolean("stopTasks") })
        check(controller.state.value.selectedWorkspaceId == "pc-workspace")
        controller.close()
    }
    case("separate disk confirmation rejects a changed computer path before publishing any file deletion") {
        val fixture = Fixture(root, "lifecycle-disk")
        val controller = fixture.ready()
        val prepared = controller.prepareWorkspaceRemoval("pc-workspace")
        controller.removeWorkspace(prepared.ticket)
        fails { controller.deleteWorkspaceFiles(prepared.ticket, "G:/computer/other", binding) }
        check(fixture.computer.requests.none { it.operation == "workspace.files.delete" })
        check(controller.state.value.pendingLifecycleOperations.isEmpty())
        check(controller.deleteWorkspaceFiles(prepared.ticket, "G:/computer/work", binding).filesDeleted)
        val request = fixture.computer.requests.single { it.operation == "workspace.files.delete" }
        check(request.payload.getBoolean("confirmDelete") && request.payload.getString("confirmedPath") == "G:/computer/work")
        controller.close()
    }
    case("missing remote directory keeps removal and archive; successful restore reselects real new registry ID") {
        val fixture = Fixture(root, "lifecycle-restore")
        val controller = fixture.ready()
        val prepared = controller.prepareWorkspaceRemoval("pc-workspace")
        controller.removeWorkspace(prepared.ticket)
        fixture.computer.missingRestoreDirectory = true
        check(controller.restoreRemovedWorkspace(prepared.ticket, binding).getString("status") == "needs_directory")
        check(controller.state.value.selectedWorkspaceId == null && controller.state.value.projection!!.workspace.archives.size == 2)
        fixture.computer.missingRestoreDirectory = false
        check(controller.restoreRemovedWorkspace(prepared.ticket, binding).getString("status") == "restored")
        check(controller.state.value.selectedWorkspaceId == "pc-restored-workspace" && controller.state.value.selectedSessionId == "pc-session")
        check(controller.state.value.projection!!.workspace.archives.isEmpty())
        controller.close()
    }
    case("per-session archive replaces selection only after true stopped receipt and restoration clears exact archive") {
        val fixture = Fixture(root, "lifecycle-session")
        val controller = fixture.ready()
        controller.archiveSession("pc-session", expectedScope = binding)
        check(controller.state.value.selectedSessionId == "pc-session-two")
        check(controller.state.value.projection!!.workspace.archives.single().sessionId == "pc-session")
        controller.restoreArchivedSession("pc-session", binding)
        check(controller.state.value.selectedSessionId == "pc-session-two")
        check(controller.state.value.projection!!.workspace.archives.isEmpty())
        controller.close()
    }
    case("failed follow-up refresh keeps completed mutation intent; retry refreshes without reexecuting archive") {
        val fixture = Fixture(root, "lifecycle-refresh-retry")
        val controller = fixture.ready()
        fixture.computer.failOperationSend = "workspace.list"
        fails { controller.archiveSession("pc-session") }
        check(controller.state.value.pendingLifecycleOperations == listOf("session.archive"))
        fixture.computer.failOperationSend = null
        controller.archiveSession("pc-session")
        check(fixture.computer.executedLifecycle["session.archive"] == 1)
        check(controller.state.value.pendingLifecycleOperations.isEmpty() && controller.state.value.selectedSessionId == "pc-session-two")
        controller.close()
    }
    case("definite permission rejection remains unsuccessful and a later confirmation uses a new intent") {
        val fixture = Fixture(root, "lifecycle-rejection")
        val controller = fixture.ready()
        fixture.computer.rejectLifecycle = true
        check((fails { controller.archiveSession("pc-session") } as AriesProviderException).code == "permission_denied")
        check(controller.state.value.pendingLifecycleOperations.isEmpty() && controller.state.value.selectedSessionId == "pc-session")
        fixture.computer.rejectLifecycle = false
        controller.archiveSession("pc-session")
        check(fixture.computer.requests.filter { it.operation == "session.archive" }.map { it.requestId }.toSet().size == 2)
        controller.close()
    }
    case("confirmation is bound to the original four-part scope even if connect switches computers first") {
        val fixture = Fixture(root, "lifecycle-scope")
        val controller = fixture.ready()
        fixture.computer.connection.put("remote_device", JSONObject().put("id", "second-pc").put("online", "online"))
        check((fails { controller.prepareWorkspaceRemoval("pc-workspace", binding) } as AriesProviderException).code == "conflict")
        check(fixture.computer.requests.none { it.operation == "workspace.remove.prepare" })
        check(controller.state.value.pendingLifecycleOperations.isEmpty())
        controller.close()
    }
    case("permanent archive deletion stays unavailable without the controlled DSH persistence capability") {
        val fixture = Fixture(root, "lifecycle-delete-capability")
        val controller = fixture.ready()
        check((fails { controller.deleteArchivedSession("pc-session") } as AriesProviderException).code == "unsupported_method")
        check(fixture.computer.requests.none { it.operation == "session.archived.delete" })
        check(controller.state.value.pendingLifecycleOperations.isEmpty())
        controller.close()
    }

    case("restart observes an already admitted mutation without submitting or cancelling computer work") {
        val fixture = Fixture(root, "lifecycle-reconnect-admitted")
        val controller = fixture.ready()
        val prepared = controller.prepareWorkspaceRemoval("pc-workspace")
        fixture.computer.holdLifecycleReceipt = "workspace.remove"
        val waiting = async { controller.removeWorkspace(prepared.ticket, stopRunning = true) }
        withTimeout(5000) { while (fixture.computer.requests.none { it.operation == "workspace.remove" }) delay(10) }
        waiting.cancel()
        try { waiting.await() } catch (_: CancellationException) { }
        val admitted = fixture.computer.requests.single { it.operation == "workspace.remove" }
        controller.close()
        val resumed = fixture.controller().also { it.setActive(true); it.connect() }
        fixture.computer.lifecycleReceipt(admitted)
        withTimeout(5000) { resumed.state.first { it.pendingLifecycleOperations.isEmpty() &&
            it.projection?.workspace?.removals?.any { removal -> removal.phase == "removed" } == true } }
        check(fixture.computer.requests.count { it.operation == "workspace.remove" } == 1)
        check(admitted.payload.getBoolean("stopTasks") && fixture.computer.requests.none { it.operation == "session.cancel" })
        resumed.close()
    }
    case("recovery after partial refresh uses fresh reads and the exact successful mutation") {
        val fixture = Fixture(root, "lifecycle-reconnect-refresh")
        val controller = fixture.ready()
        fixture.computer.failOperationSend = "session.archived.list"
        fails { controller.archiveSession("pc-session") }
        val mutation = fixture.computer.requests.single { it.operation == "session.archive" }
        val previousReads = fixture.computer.requests.filter { it.operation == "workspace.list" }.map { it.requestId }.toSet()
        controller.close()
        fixture.computer.failOperationSend = null
        fixture.computer.workspaceId = "pc-current-workspace"
        val resumed = fixture.controller().also { it.setActive(true); it.connect() }
        val recovered = withTimeout(5000) { resumed.state.first { it.pendingLifecycleOperations.isEmpty() &&
            it.selectedWorkspaceId == "pc-current-workspace" } }
        check(recovered.selectedSessionId == "pc-session-two" && recovered.projection!!.workspace.archives.single().sessionId == "pc-session")
        check(fixture.computer.executedLifecycle["session.archive"] == 1)
        check(fixture.computer.requests.single { it.operation == "session.archive" }.requestId == mutation.requestId)
        check(fixture.computer.requests.filter { it.operation == "workspace.list" }.any { it.requestId !in previousReads })
        resumed.close()
    }
    case("power saving preserves authorized intent in background and resumes only in foreground") {
        val fixture = Fixture(root, "lifecycle-reconnect-background")
        val controller = fixture.ready()
        fixture.computer.failLifecycleSend = "session.archive"
        fails { controller.archiveSession("pc-session", stopRunning = true) }
        val original = fixture.computer.requests.single { it.operation == "session.archive" }
        controller.close()
        fixture.computer.failLifecycleSend = null
        val resumed = fixture.controller()
        resumed.setForeground(false, powerSaving = true)
        resumed.setActive(true)
        resumed.connect()
        delay(400)
        check(resumed.state.value.pendingLifecycleOperations == listOf("session.archive"))
        check(fixture.computer.requests.count { it.operation == "session.archive" } == 1)
        resumed.setForeground(true, powerSaving = true)
        withTimeout(5000) { resumed.state.first { it.pendingLifecycleOperations.isEmpty() &&
            it.projection?.workspace?.archives?.any { archive -> archive.sessionId == "pc-session" } == true } }
        val attempts = fixture.computer.requests.filter { it.operation == "session.archive" }
        check(attempts.all { it.requestId == original.requestId && it.payload.getBoolean("stopTasks") })
        resumed.close()
    }
    case("saved file deletion resumes only with its previously confirmed exact path") {
        val fixture = Fixture(root, "lifecycle-reconnect-files")
        val controller = fixture.ready()
        val prepared = controller.prepareWorkspaceRemoval("pc-workspace")
        controller.removeWorkspace(prepared.ticket)
        fixture.computer.failLifecycleSend = "workspace.files.delete"
        fails { controller.deleteWorkspaceFiles(prepared.ticket, prepared.workspace.path) }
        val original = fixture.computer.requests.single { it.operation == "workspace.files.delete" }
        controller.close()
        fixture.computer.failLifecycleSend = null
        val resumed = fixture.controller().also { it.setActive(true); it.connect() }
        withTimeout(5000) { resumed.state.first { it.pendingLifecycleOperations.isEmpty() &&
            it.projection?.workspace?.removals?.any { removal -> removal.filesDeleted } == true } }
        check(fixture.computer.requests.filter { it.operation == "workspace.files.delete" }.all {
            it.requestId == original.requestId && it.payload.getBoolean("confirmDelete") &&
                it.payload.getString("confirmedPath") == prepared.workspace.path
        })
        check(fixture.computer.executedLifecycle["workspace.files.delete"] == 1)
        resumed.close()
    }
    case("directory browsing and selection reject paths confirmed for a different computer") {
        val fixture = Fixture(root, "directory-scope-confirmation")
        val controller = fixture.ready()
        fixture.computer.connection.put("remote_device", JSONObject().put("id", "changed-pc").put("online", "online"))
        check((fails { controller.listDirectory("G:/computer/work", binding) } as AriesProviderException).code == "conflict")
        check((fails { controller.resolveWorkspace("Work", "G:/computer/work", binding) } as AriesProviderException).code == "conflict")
        check(fixture.computer.requests.none { it.operation in setOf("directory.list", "workspace.resolve") })
        controller.close()
    }

    case("bulk transfer receipts are consumed after ACK beyond both 256 requests and the 8 MiB journal bound") {
        val fixture = Fixture(root, "artifact-consumption")
        val controller = fixture.ready()
        repeat(38) { batch ->
            (batch * 8 until minOf(300, batch * 8 + 8)).map { block -> async {
                val result = controller.artifactRequest("artifact.read", JSONObject().put("ticket", "fixture-artifact-ticket")
                    .put("offset", block * 48 * 1024L).put("length", 48 * 1024), binding)
                check(result.getLong("offset") == block * 48 * 1024L && result.getString("bytesBase64").length == 64 * 1024)
            } }.awaitAll()
        }
        check(fixture.computer.requests.count { it.operation == "artifact.read" } == 300)
        withTimeout(5000) { controller.state.first { state -> state.projection?.requests?.none { it.operation == "artifact.read" } == true } }
        check(fixture.computer.retired.size == 300)
        controller.close()
    }
    case("concurrent transfer calls share the pump and keep busy until the last receipt completes") {
        val fixture = Fixture(root, "artifact-concurrent")
        val controller = fixture.ready()
        fixture.computer.holdArtifactReceipt = true
        val first = async { controller.artifactRequest("artifact.read", JSONObject().put("offset", 0), binding) }
        val second = async { controller.artifactRequest("artifact.read", JSONObject().put("offset", 48 * 1024), binding) }
        withTimeout(5000) { controller.state.first { state -> state.projection?.requests?.count { it.operation == "artifact.read" } == 2 } }
        check(controller.state.value.busy)
        val reads = synchronized(fixture.computer) { fixture.computer.requests.filter { it.operation == "artifact.read" } }
        fixture.computer.lifecycleReceipt(reads.first { it.payload.getLong("offset") == 0L })
        first.await()
        check(controller.state.value.busy && !second.isCompleted)
        fixture.computer.lifecycleReceipt(reads.first { it.payload.getLong("offset") != 0L })
        second.await()
        withTimeout(5000) { controller.state.first { !it.busy } }
        controller.close()
    }
    case("transfer results are refused after capability or exact computer binding changes") {
        val fixture = Fixture(root, "artifact-boundary")
        val controller = fixture.ready()
        fixture.computer.connection.getJSONObject("capabilities").put("remote_artifacts", false)
        check((fails { controller.artifactRequest("artifact.read", JSONObject().put("offset", 0), binding) } as AriesProviderException).code == "unsupported_method")
        fixture.computer.connection.getJSONObject("capabilities").put("remote_artifacts", true)
        fixture.computer.connection.put("remote_device", JSONObject().put("id", "other-pc").put("online", "online"))
        check((fails { controller.artifactRequest("artifact.read", JSONObject().put("offset", 0), binding) } as AriesProviderException).code == "conflict")
        controller.close()
    }
    case("power saving pauses file work in the background and attachment writes use consumable stable action keys") {
        val fixture = Fixture(root, "attachment-consumption")
        val controller = fixture.ready()
        controller.setForeground(false, powerSaving = true)
        check((fails { controller.attachmentRequest("upload_chunk_1", "attachment.write", JSONObject().put("uploadId", "fixture-upload"), binding) } as AriesProviderException).code == "request_cancelled")
        check(fixture.computer.requests.none { it.operation.startsWith("attachment.") })
        controller.setForeground(true, powerSaving = true)
        controller.attachmentRequest("upload_chunk_1", "attachment.write", JSONObject().put("uploadId", "fixture-upload"), binding)
        withTimeout(5000) { controller.state.first { state -> state.projection?.requests?.none { it.operation == "attachment.write" } == true } }
        controller.attachmentRequest("upload_commit", "attachment.commit", JSONObject().put("uploadId", "fixture-upload"), binding)
        check(controller.state.value.projection?.requests?.any { it.operation == "attachment.commit" && it.status == RemoteDshSessionCoordinator.RequestStatus.SUCCEEDED } == true)
        controller.close()
    }
    case("selected file upload remains in the durable prompt and retries cannot substitute another upload") {
        val fixture = Fixture(root, "attachment-prompt")
        val controller = fixture.ready()
        check(controller.prepareAttachmentSession(binding) == "pc-session")
        fixture.computer.failPromptSend = true
        val selected = JSONObject().put("type", "file").put("uploadId", "fixture-upload")
        fails { controller.send("read this", listOf(selected), binding) }
        val original = fixture.computer.requests.single { it.operation == "session.prompt" }
        fails { controller.send("read this", listOf(JSONObject().put("type", "file").put("uploadId", "different-upload")), binding) }
        check(fixture.computer.requests.count { it.operation == "session.prompt" } == 1)
        fixture.computer.failPromptSend = false
        controller.send("read this", listOf(selected), binding)
        val attempts = fixture.computer.requests.filter { it.operation == "session.prompt" }
        check(attempts.size == 2 && attempts.all { it.requestId == original.requestId &&
            it.payload.getJSONArray("content").getJSONObject(1).getString("uploadId") == "fixture-upload" })
        check(fixture.computer.executedPrompts == 1)
        controller.close()
    }
    case("ACK and failed native retirement retain the consumed action across coordinator restart") {
        val computer = Computer()
        val journal = Journal()
        val workspaces = RemoteWorkspaceStore(File(root, "native-retirement-restart"))
        val coordinator = RemoteDshSessionCoordinator(binding, computer, workspaces, journal)
        val view = coordinator.submit("read_once", "artifact.read", JSONObject().put("offset", 0))
        computer.rejectAcknowledgement = true
        fails { coordinator.pollOnce() }
        coordinator.consumeTransferReceipt("read_once")
        check(computer.retirementAttempts.isEmpty() && coordinator.state.value.requests.single().requestId == view.requestId)
        check(journal.value!!.getJSONArray("requests").getJSONObject(0).getBoolean("consumed"))
        computer.rejectAcknowledgement = false
        computer.failRetirement = true
        coordinator.pollOnce()
        check(computer.retirementAttempts == listOf(view.requestId) && coordinator.state.value.requests.size == 1)
        computer.failRetirement = false
        val resumed = RemoteDshSessionCoordinator(binding, computer, workspaces, journal)
        resumed.pollOnce()
        check(computer.retirementAttempts == listOf(view.requestId, view.requestId))
        check(resumed.state.value.requests.isEmpty() && computer.requests.size == 1)
    }
    case("failed app commit after native retirement retries the same idempotent retirement") {
        val computer = Computer()
        val journal = Journal()
        val workspaces = RemoteWorkspaceStore(File(root, "native-retirement-commit"))
        val coordinator = RemoteDshSessionCoordinator(binding, computer, workspaces, journal)
        val view = coordinator.submit("write_once", "attachment.write", JSONObject().put("uploadId", "fixture-upload"))
        coordinator.pollOnce()
        coordinator.consumeTransferReceipt("write_once")
        computer.onRetirement = { journal.fail = true }
        check(fails { coordinator.pollOnce() } is IOException)
        check(view.requestId in computer.retired && coordinator.state.value.requests.size == 1)
        journal.fail = false
        computer.onRetirement = null
        val resumed = RemoteDshSessionCoordinator(binding, computer, workspaces, journal)
        resumed.pollOnce()
        check(computer.retirementAttempts == listOf(view.requestId, view.requestId))
        check(resumed.state.value.requests.isEmpty() && computer.requests.size == 1)
    }
    case("native consumption never retires a mutation intent or an unconfirmed transfer") {
        val computer = Computer()
        val journal = Journal()
        val coordinator = RemoteDshSessionCoordinator(binding, computer, RemoteWorkspaceStore(File(root, "native-retirement-intent")), journal)
        coordinator.submit("commit_once", "attachment.commit", JSONObject().put("uploadId", "fixture-upload"))
        coordinator.pollOnce()
        coordinator.consumeTransferReceipt("commit_once")
        computer.holdArtifactReceipt = true
        coordinator.submit("unknown_read", "artifact.read", JSONObject().put("offset", 0))
        coordinator.consumeTransferReceipt("unknown_read")
        check(computer.retirementAttempts.isEmpty() && coordinator.state.value.requests.size == 2)
    }
    case("Coordinator delegates only the final production client's scoped admission and retains generic transport checks") {
        val computer = Computer()
        computer.connection.getJSONObject("capabilities").put("remote_task", true)
        var observations = 0
        var sends = 0
        var polls = 0
        val client = RemoteDshClient(object : RemoteDshProviderCall {
            override suspend fun call(method: String, params: JSONObject, expectedRemoteScope: RemoteDshScope?): JSONObject = when (method) {
                "get_connection_state" -> { observations++; computer.connectionState() }
                "send_encrypted_message" -> { check(expectedRemoteScope == binding); sends++; JSONObject().put("message_id", "scoped-send-one") }
                "list_incoming_messages" -> { check(expectedRemoteScope == binding); polls++;
                    JSONObject().put("messages", JSONArray()).put("next_cursor", "0") }
                else -> error("Unexpected scoped fixture method")
            }
        })
        val coordinator = RemoteDshSessionCoordinator(binding, client, RemoteWorkspaceStore(File(root, "scoped-client-admission")), Journal())
        coordinator.prompt("exact_prompt", "pc-session", "one task")
        coordinator.pollOnce()
        check(sends == 1 && polls == 1 && observations == 4)
        check(coordinator.state.value.requests.single().status == RemoteDshSessionCoordinator.RequestStatus.SUBMITTED)
        var genericObservations = 0
        val generic = object : RemoteDshTransport by computer {
            override suspend fun connectionState(): JSONObject { genericObservations++; return computer.connectionState() }
        }
        RemoteDshSessionCoordinator(binding, generic, RemoteWorkspaceStore(File(root, "generic-admission")), Journal()).pollOnce()
        check(genericObservations == 2)
    }
    case("production client after-send authorization failure cannot become a submitted Coordinator intent") {
        val computer = Computer()
        computer.connection.getJSONObject("capabilities").put("remote_task", true)
        var sends = 0
        val journal = Journal()
        val client = RemoteDshClient(object : RemoteDshProviderCall {
            override suspend fun call(method: String, params: JSONObject, expectedRemoteScope: RemoteDshScope?): JSONObject = when (method) {
                "get_connection_state" -> computer.connectionState()
                "send_encrypted_message" -> {
                    sends++; computer.connection.put("pairing_status", "revoked")
                    JSONObject().put("message_id", "revoked-after-send")
                }
                else -> error("Unexpected scoped fixture method")
            }
        })
        val coordinator = RemoteDshSessionCoordinator(binding, client, RemoteWorkspaceStore(File(root, "scoped-send-revoked")), journal)
        check((fails { coordinator.prompt("revoked_prompt", "pc-session", "one task") } as AriesProviderException).code == "revoked")
        check(sends == 1 && coordinator.state.value.requests.single().status == RemoteDshSessionCoordinator.RequestStatus.INTENT)
        check(journal.value!!.getJSONArray("requests").getJSONObject(0).getString("error") == "revoked")
    }
    case("production client after-poll revocation prevents a terminal frame and cursor from entering the app journal") {
        val computer = Computer()
        computer.connection.getJSONObject("capabilities").put("remote_task", true)
        var requestId = ""
        val journal = Journal()
        val client = RemoteDshClient(object : RemoteDshProviderCall {
            override suspend fun call(method: String, params: JSONObject, expectedRemoteScope: RemoteDshScope?): JSONObject = when (method) {
                "get_connection_state" -> computer.connectionState()
                "send_encrypted_message" -> { requestId = params.getJSONObject("body").getString("request_id");
                    JSONObject().put("message_id", "scope-send-two") }
                "list_incoming_messages" -> {
                    computer.connection.put("pairing_status", "revoked")
                    JSONObject().put("next_cursor", "1").put("messages", JSONArray().put(JSONObject()
                        .put("message_id", "revoked-poll-frame").put("kind", RemoteDshClient.EVENT_KIND)
                        .put("sender_device_id", binding.remoteDeviceId).put("body", JSONObject().put("protocol", RemoteDshClient.PROTOCOL)
                            .put("request_id", requestId).put("target_device_id", binding.localDeviceId).put("sequence", 0)
                            .put("type", "result").put("payload", JSONObject().put("queued", true)))))
                }
                else -> error("Unexpected scoped fixture method")
            }
        })
        val coordinator = RemoteDshSessionCoordinator(binding, client, RemoteWorkspaceStore(File(root, "scoped-poll-revoked")), journal)
        coordinator.prompt("old_scope_prompt", "pc-session", "one task")
        check((fails { coordinator.pollOnce() } as AriesProviderException).code == "revoked")
        check(journal.value!!.getString("cursor") == "0" &&
            coordinator.state.value.requests.single().status == RemoteDshSessionCoordinator.RequestStatus.SUBMITTED)
        check(journal.value!!.getJSONObject("acknowledgements").length() == 0)
        check(coordinator.lastPollDiagnostic?.stage == "transport.poll" &&
            coordinator.lastPollDiagnostic?.cause == "provider_policy" && coordinator.lastPollDiagnostic?.code == "revoked")
    }
    case("production client rejects a changed scope after polling without advancing the durable app cursor") {
        val computer = Computer()
        computer.connection.getJSONObject("capabilities").put("remote_task", true)
        val journal = Journal()
        val client = RemoteDshClient(object : RemoteDshProviderCall {
            override suspend fun call(method: String, params: JSONObject, expectedRemoteScope: RemoteDshScope?): JSONObject = when (method) {
                "get_connection_state" -> computer.connectionState()
                "list_incoming_messages" -> {
                    computer.connection.put("group_id", "new-relationship")
                    JSONObject().put("messages", JSONArray()).put("next_cursor", "9")
                }
                else -> error("Unexpected changed scope fixture method")
            }
        })
        val coordinator = RemoteDshSessionCoordinator(binding, client, RemoteWorkspaceStore(File(root, "scoped-poll-changed")), journal)
        check((fails { coordinator.pollOnce() } as AriesProviderException).code == "conflict")
        check(journal.value == null && !coordinator.state.value.connected && coordinator.state.value.requests.isEmpty())
        check(coordinator.lastPollDiagnostic?.stage == "transport.poll" && coordinator.lastPollDiagnostic?.code == "conflict")
    }
    case("client sends one exact stable encrypted bulk ACK and skips it after native retirement") {
        val computer = Computer()
        val sent = mutableListOf<JSONObject>()
        var retired = false
        var retireCalls = 0
        val provider = object : RemoteDshProviderCall {
            override suspend fun call(method: String, params: JSONObject, expectedRemoteScope: RemoteDshScope?): JSONObject = when (method) {
                "get_connection_state" -> computer.connectionState()
                "send_encrypted_message" -> { check(expectedRemoteScope == binding); sent += JSONObject(params.toString()); JSONObject().put("message_id", "bulk-ACK-admitted") }
                else -> error("Unexpected retirement method")
            }
            override suspend fun retireTransferReceipt(scope: RemoteDshScope, requestId: String, operation: String): JSONObject {
                check(scope == binding && requestId == "client-retirement-123" && operation == "attachment.write")
                retireCalls++
                if (retired) return JSONObject().put("retired", true)
                if (sent.isEmpty()) return JSONObject().put("retired", false).put("ack_required", true)
                retired = true
                return JSONObject().put("retired", true)
            }
        }
        val client = RemoteDshClient(provider)
        client.retireTransferReceipt(binding, "client-retirement-123", "attachment.write", 7)
        val ack = sent.single()
        check(ack.getString("kind") == "dsh_remote_ack" && ack.getString("group_id") == binding.groupId)
        check(ack.getString("idempotency_key") == "bulk-ack-client-retirement-123-attachment.write-7")
        val body = ack.getJSONObject("body")
        check(body.length() == 5 && body.getString("protocol") == RemoteDshClient.PROTOCOL &&
            body.getString("target_device_id") == binding.remoteDeviceId && body.getString("operation") == "attachment.write" && body.getLong("sequence") == 7L)
        client.retireTransferReceipt(binding, "client-retirement-123", "attachment.write", 7)
        check(sent.size == 1 && retireCalls == 3)
        check(fails { client.retireTransferReceipt(binding, "client-retirement-123", "attachment.commit", 7) } is IllegalArgumentException)
        check(sent.size == 1)
    }
    case("client rejects ambiguous native retirement responses without publishing an ACK") {
        var sent = 0
        val client = RemoteDshClient(object : RemoteDshProviderCall {
            override suspend fun call(method: String, params: JSONObject, expectedRemoteScope: RemoteDshScope?): JSONObject {
                if (method == "send_encrypted_message") sent++
                return Computer().connectionState()
            }
            override suspend fun retireTransferReceipt(scope: RemoteDshScope, requestId: String, operation: String) =
                JSONObject().put("retired", false).put("unrecognized", true)
        })
        check(fails { client.retireTransferReceipt(binding, "ambiguous-retirement-123", "artifact.read", 0) } is IllegalArgumentException)
        check(sent == 0)
    }

    case("archive deletion preparation is non-destructive and the exact receipt survives controller restart") {
        val fixture = Fixture(root, "archive-delete-prepare")
        fixture.computer.archived += "pc-session"
        val app = fixture.ready()
        app.refreshArchive(binding)
        val proof = app.prepareArchivedSessionDeletion("pc-session", binding)
        check(!proof.deleted && proof.status == "prepared" && proof.scope == binding && proof.archiveVersion == "fixture-archive-v1")
        check(app.state.value.projection!!.workspace.archives.any { it.sessionId == "pc-session" })
        check(app.state.value.projection!!.workspace.archiveDeletions.single() == proof)
        app.close()
        val resumed = fixture.controller()
        resumed.setActive(true); resumed.connect()
        check(resumed.state.value.projection!!.workspace.archiveDeletions.single() == proof)
        val receipt = resumed.deleteArchivedSession(proof, binding)
        check(receipt.sameConfirmation(proof) && receipt.deleted && receipt.status == "deleted")
        check(resumed.state.value.projection!!.workspace.archives.none { it.sessionId == "pc-session" })
        check(resumed.state.value.projection!!.workspace.workspaces.all { "pc-session" !in it.sessionIds })
        check(fixture.computer.executedLifecycle["session.archived.delete"] == 1)
        resumed.close()
    }
    case("changed archive epoch cannot consume an old independent confirmation") {
        val fixture = Fixture(root, "archive-delete-epoch")
        fixture.computer.archived += "pc-session"
        val app = fixture.ready(); app.refreshArchive(binding)
        val proof = app.prepareArchivedSessionDeletion("pc-session", binding)
        fixture.computer.archiveVersion = "fixture-archive-v2"
        app.refreshArchive(binding)
        check(fails { app.deleteArchivedSession(proof, binding) }.message == "remote_archive_version_changed")
        check(fixture.computer.requests.none { it.operation == "session.archived.delete" })
        check(app.state.value.projection!!.workspace.archives.single().archiveVersion == "fixture-archive-v2")
        app.close()
    }
    case("archive deletion requires the exact prepared cache, execution device and relationship") {
        val fixture = Fixture(root, "archive-delete-scope")
        fixture.computer.archived += "pc-session"
        val app = fixture.ready(); app.refreshArchive(binding)
        val proof = app.prepareArchivedSessionDeletion("pc-session", binding)
        fails { app.deleteArchivedSession(proof.copy(ticket = "unissued-ticket"), binding) }
        fails { app.deleteArchivedSession(proof.copy(executionDevice = "local_phone"), binding) }
        fails { app.deleteArchivedSession(proof.copy(scope = binding.copy(groupId = "another-group")), binding) }
        check(fixture.computer.requests.none { it.operation == "session.archived.delete" })
        app.close()
    }
    case("uncertain archive deletion blocks restore on the same session and resumes the original request") {
        val fixture = Fixture(root, "archive-delete-recover")
        fixture.computer.archived += "pc-session"
        val app = fixture.controller(timeout = 1000, pollInterval = 250)
        app.setActive(true); app.refresh(); app.refreshArchive(binding)
        val proof = app.prepareArchivedSessionDeletion("pc-session", binding)
        fixture.computer.holdLifecycleReceipt = "session.archived.delete"
        try { app.deleteArchivedSession(proof, binding); error("expected timeout") } catch (_: kotlinx.coroutines.TimeoutCancellationException) { }
        val original = fixture.computer.requests.single { it.operation == "session.archived.delete" }
        check(fails { app.restoreArchivedSession("pc-session", binding) }.message == "remote_lifecycle_pending")
        check(fixture.selection.value!!.getJSONObject("lifecycle").length() == 1)
        app.close()
        fixture.computer.holdLifecycleReceipt = null
        fixture.computer.lifecycleReceipt(original)
        val resumed = fixture.controller(pollInterval = 250)
        resumed.setActive(true); resumed.connect()
        withTimeout(5000) { resumed.state.first { it.pendingLifecycleOperations.isEmpty() } }
        check(fixture.computer.requests.filter { it.operation == "session.archived.delete" }.all { it.requestId == original.requestId })
        check(fixture.computer.executedLifecycle["session.archived.delete"] == 1)
        check(resumed.state.value.projection!!.workspace.archiveDeletions.single().deleted)
        resumed.close()
    }
    case("terminal archive deletion protects delayed lists and removed workspace session metadata") {
        val store = RemoteWorkspaceStore(File(root, "archive-delete-tombstone"))
        val computer = Computer()
        val workspace = JSONObject().put("workspaceId", "removed-workspace").put("title", "Removed")
            .put("path", "G:/computer/work").put("sessionIds", JSONArray().put("pc-session").put("pc-session-two"))
        val removal = JSONObject().put("ticket", "removal-one").put("workspace", workspace).put("order", 0)
            .put("phase", "removed").put("createdAt", 500).put("removedAt", 1000).put("filesDeleted", false)
            .put("executionDevice", "remote_computer").put("filesDeleteRequiresSeparateConfirmation", true)
        store.applyOperationResult(binding, "workspace.removed.list", JSONObject().put("removals", JSONArray().put(removal)))
        val prepared = JSONObject().put("ticket", "delete-one").put("sessionId", "pc-session").put("archiveVersion", "archive-one")
            .put("archivedAt", JSONObject.NULL).put("expiresAt", JSONObject.NULL).put("executionDevice", "remote_computer")
            .put("status", "prepared").put("deleted", false)
        store.applyOperationResult(binding, "session.archived.delete.prepare", prepared)
        store.applyOperationResult(binding, "session.archived.delete", JSONObject(prepared.toString()).put("status", "deleted")
            .put("deleted", true).put("deletedAt", 2000))
        store.applyOperationResult(binding, "workspace.removed.list", JSONObject().put("removals", JSONArray().put(removal)))
        check(store.read(binding).removals.single().workspace.sessionIds == listOf("pc-session-two"))
        computer.archived += "pc-session"
        val staleArchive = JSONObject().put("sessionId", "pc-session").put("order", 0).put("archivedAt", JSONObject.NULL)
            .put("expiresAt", JSONObject.NULL).put("remainingDays", JSONObject.NULL)
        store.applyOperationResult(binding, "session.archived.list", JSONObject().put("archives", JSONArray().put(staleArchive)))
        check(store.read(binding).archives.isEmpty() && store.read(binding).archiveDeletions.single().deleted)
    }
    case("partial, mismatched or private archive receipts never become durable deletion success") {
        val store = RemoteWorkspaceStore(File(root, "archive-delete-invalid"))
        val prepared = JSONObject().put("ticket", "delete-two").put("sessionId", "pc-session").put("archiveVersion", "archive-two")
            .put("archivedAt", 1000).put("expiresAt", 1000L + 30L * 86_400_000).put("executionDevice", "remote_computer")
            .put("status", "prepared").put("deleted", false)
        store.applyOperationResult(binding, "session.archived.delete.prepare", prepared)
        val terminal = JSONObject(prepared.toString()).put("status", "deleted").put("deleted", true).put("deletedAt", 2000)
        for (bad in listOf(JSONObject(terminal.toString()).put("ticket", "another-ticket"),
            JSONObject(terminal.toString()).put("sessionId", "another-session"), JSONObject(terminal.toString()).put("archiveVersion", "archive-three"),
            JSONObject(terminal.toString()).put("executionDevice", "local_phone"), JSONObject(terminal.toString()).put("status", "deleting"),
            JSONObject(terminal.toString()).put("deletedAt", JSONObject.NULL), JSONObject(terminal.toString()).put("privatePath", "/secret"))) {
            fails { store.applyOperationResult(binding, "session.archived.delete", bad) }
            check(!store.read(binding).archiveDeletions.single().deleted)
        }
    }

    val model = RuntimeModel("provider-one", "model-one", "One", "openai-responses", emptyList())
    val opaque = JSONObject().put("messages", JSONArray().put(JSONObject().put("role", "assistant")
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "private previous")))
        .put("opaqueSignature", "fixture-signature")))
    fun history() = listOf(DeepSeekChatClient.HistoryMessage("system", "new instruction"),
        DeepSeekChatClient.HistoryMessage("assistant", "public previous"), DeepSeekChatClient.HistoryMessage("user", "next question"))
    val store = NativeTranscriptStore(File(root, "transcripts"))
    case("completed same-model opaque transcript resumes with one current user message") {
        store.save("conversation", model, 7, opaque, true)
        val resumed = store.prepare("conversation", model, 7, history())
        val messages = resumed.getJSONArray("messages")
        check(messages.length() == 2 && messages.getJSONObject(0).getString("opaqueSignature") == "fixture-signature")
        check(messages.getJSONObject(1).getString("role") == "user")
        check(resumed.getString("systemPrompt") == "new instruction")
        check(store.prepare("conversation", model, 7, history()).getJSONArray("messages").length() == 2)
    }
    case("failed turn cannot restore opaque context or historical tool execution") {
        store.save("interrupted", model, 7, opaque, false)
        val prepared = store.prepare("interrupted", model, 7, history())
        check(!prepared.toString().contains("fixture-signature") && prepared.toString().contains("public previous"))
    }
    case("different provider/model or preceding message uses compatible public history") {
        check(!store.prepare("conversation", model.copy(providerId = "provider-two"), 7, history()).toString().contains("fixture-signature"))
        check(!store.prepare("conversation", model.copy(id = "model-two"), 7, history()).toString().contains("fixture-signature"))
        check(!store.prepare("conversation", model, 8, history()).toString().contains("fixture-signature"))
        check(!store.prepare("conversation", model, null, history()).toString().contains("fixture-signature"))
    }
    case("opaque record is detached and hostile conversation IDs cannot escape storage") {
        val mutable = JSONObject(opaque.toString())
        store.save("../../outside", model, 9, mutable, true)
        mutable.getJSONArray("messages").getJSONObject(0).put("opaqueSignature", "changed")
        val saved = store.prepare("../../outside", model, 9, history())
        check(saved.toString().contains("fixture-signature") && !saved.toString().contains("changed"))
        check(File(root, "transcripts").listFiles()!!.all { it.name.matches(Regex("[a-f0-9]{64}\\.json")) })
    }
    case("oversized opaque context is rejected before replacing completed transcript") {
        fails { store.save("conversation", model, 7, JSONObject().put("oversized", "x".repeat(4 * 1024 * 1024)), true) }
        check(store.prepare("conversation", model, 7, history()).toString().contains("fixture-signature"))
    }
    println("$passes/$passes PASS; real production controllers/persistence with synthetic transport and Android AtomicFile boundary")
}
