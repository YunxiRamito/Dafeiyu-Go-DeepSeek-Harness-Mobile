package com.dafeiyu.mobile.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

interface RemoteDshJournal {
    fun read(): JSONObject?
    fun write(value: JSONObject)
}

/** Native remote requests have durable IDs. Restarting observation never resubmits a new prompt. */
class RemoteDshSessionCoordinator(
    val binding: RemoteDshScope,
    private val transport: RemoteDshTransport,
    private val workspaces: RemoteWorkspaceStore,
    private val journal: RemoteDshJournal,
) {
    constructor(context: Context, client: RemoteDshClient, binding: RemoteDshScope) : this(binding, client,
        RemoteWorkspaceStore(context), diskJournal(File(context.noBackupFilesDir, "remote-dsh/requests/${binding.storageKey}.json")))

    enum class RequestStatus { INTENT, SUBMITTED, STREAMING, SUCCEEDED, FAILED, UNCERTAIN }
    data class RequestView(val operationKey: String, val requestId: String, val operation: String,
        val status: RequestStatus, val result: Any?, val errorCode: String?, val nextSequence: Long,
        val observing: Boolean = true)
    data class State(val binding: RemoteDshScope, val requests: List<RequestView>, val sessions: Map<String, RemoteDshActivityMapper.View>,
        val workspace: RemoteWorkspaceStore.Snapshot, val observing: Boolean, val connected: Boolean,
        val errorCode: String? = null)

    private val mutex = Mutex()
    private val retirements = Mutex()
    private var document = journal.read()?.also(::validateDocument) ?: freshDocument()
    private var observing = true
    private var connected = false
    private var lastError: String? = null
    /** App-internal diagnostic only; no raw exception text, path or payload. */
    internal data class PollDiagnostic(val stage: String, val cause: String, val code: String?) {
        fun toJson(): JSONObject = JSONObject().put("stage", stage).put("cause", cause).put("code", code ?: JSONObject.NULL)
    }
    @Volatile internal var lastPollDiagnostic: PollDiagnostic? = null
        private set
    private var loop: Job? = null
    private val stateMutable = MutableStateFlow(makeState())
    val state: StateFlow<State> = stateMutable.asStateFlow()

    /** operationKey belongs to the app's saved composer action, not a newly generated retry. */
    suspend fun submit(operationKey: String, operation: String, payload: JSONObject): RequestView = withContext(Dispatchers.IO) {
        require(operationKey.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(operation in OPERATIONS)
        remoteValidateJson(payload)
        val detached = JSONObject(payload.toString())
        val fingerprint = remoteJsonHash(JSONObject().put("operation", operation).put("payload", detached))
        val request = mutex.withLock {
            val previous = requests(document).firstOrNull { it.getString("key") == operationKey }
            if (previous != null) {
                if (previous.getString("fingerprint") != fingerprint) throw AriesProviderException("conflict")
                return@withLock JSONObject(previous.toString())
            }
            val next = copyDocument()
            pruneReadReceipts(next)
            require(requests(next).size < MAX_REQUESTS) { "remote_request_journal_full" }
            val value = JSONObject().put("key", operationKey).put("id", RemoteDshClient.newRequestId()).put("operation", operation)
                .put("payload", detached).put("fingerprint", fingerprint).put("status", RequestStatus.INTENT.name)
                .put("next", 0).put("pending", JSONObject()).put("fingerprints", JSONObject())
            next.getJSONArray("requests").put(value)
            commit(next)
            JSONObject(value.toString())
        }
        // A successful transport admission or any terminal receipt means this
        // action already exists. Only a still-uncertain send retries the same ID.
        if (request.getString("status") != RequestStatus.INTENT.name) return@withContext requestView(request)
        try {
            requireTransportBinding(requireOnline = true)
            transport.submit(RemoteDshClient.Request(binding.groupId, operation, detached, request.getString("id"), binding))
            mutex.withLock {
                val next = copyDocument()
                val saved = requestById(next, request.getString("id"))
                if (saved.getString("status") == RequestStatus.INTENT.name) saved.put("status", RequestStatus.SUBMITTED.name)
                saved.remove("error")
                connected = true; lastError = null
                commit(next)
                requestView(saved)
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            mutex.withLock {
                val next = copyDocument()
                val saved = requestById(next, request.getString("id"))
                saved.put("error", code(error))
                connected = false; lastError = code(error)
                commit(next)
            }
            throw error
        }
    }

    suspend fun prompt(operationKey: String, sessionId: String, text: String): RequestView {
        require(text.isNotBlank())
        return submit(operationKey, "session.prompt", JSONObject().put("sessionId", sessionId)
            .put("mode", "queue")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))))
    }

    suspend fun observeSession(operationKey: String, sessionId: String): RequestView = submit(operationKey, "session.follow",
        JSONObject().put("address", JSONObject().put("kind", "session").put("sessionId", sessionId)).put("maxMessages", 200))

    suspend fun observeChild(operationKey: String, parentSessionId: String, childSessionId: String, mode: String = "unknown"): RequestView {
        require(parentSessionId.matches(Regex("[A-Za-z0-9_-]{1,128}")) && childSessionId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(mode in setOf("one-shot", "continuable", "unknown"))
        return submit(operationKey, "session.follow", JSONObject().put("address", JSONObject().put("kind", "subagent")
            .put("parentSessionId", parentSessionId).put("childSessionId", childSessionId).put("mode", mode)).put("maxMessages", 200))
    }

    suspend fun observeControl(operationKey: String): RequestView = submit(operationKey, "session.control", JSONObject())
    suspend fun cancelTask(operationKey: String, sessionId: String): RequestView =
        submit(operationKey, "session.cancel", JSONObject().put("sessionId", sessionId))

    /** Background power saving pauses IO. It does not stop a task admitted by the computer. */
    suspend fun setForeground(foreground: Boolean, powerSave: Boolean) = mutex.withLock {
        observing = foreground || !powerSave
        stateMutable.value = makeState()
    }

    fun start(scope: CoroutineScope, pollIntervalMillis: Long = 1500L): Job {
        require(pollIntervalMillis in 250L..60_000L)
        loop?.takeIf { it.isActive }?.let { return it }
        return scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (state.value.observing) {
                    try { pollOnce() } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        mutex.withLock {
                            connected = false; lastError = code(error)
                            if (lastError in setOf("revoked", "conflict")) observing = false
                            stateMutable.value = makeState()
                        }
                    }
                }
                delay(pollIntervalMillis)
            }
        }.also { loop = it }
    }

    fun stopObserving() { loop?.cancel(); loop = null }

    /** Save pending frames, projections and the exclusive Aries cursor before any acknowledgement. */
    suspend fun pollOnce(limit: Int = 100): State = withContext(Dispatchers.IO) {
        val cursor = mutex.withLock { if (!observing) return@withContext state.value; document.getString("cursor") }
        var stage = "binding.before"
        try {
            requireTransportBinding(requireOnline = true)
            stage = "ack.before"
            flushAcknowledgements()
            stage = "transport.poll"
            val page = transport.poll(binding, cursor, limit)
            stage = "binding.after"
            requireTransportBinding()
            stage = "journal.receive"
            mutex.withLock {
                val next = copyDocument()
                for (event in page.events) receive(next, event)
                val nextCursor = page.nextCursor
                if (nextCursor != null) {
                    require(nextCursor.matches(Regex("[0-9]{1,64}")))
                    require(java.math.BigInteger(nextCursor) >= java.math.BigInteger(next.getString("cursor"))) { "remote_cursor_regressed" }
                    next.put("cursor", nextCursor)
                }
                connected = true; lastError = null
                commit(next)
            }
            stage = "ack.after"
            flushAcknowledgements()
            stage = "transfer.retire"
            flushTransferRetirements()
            stage = "observation.resume"
            resumeClosedObservations()
            lastPollDiagnostic = null
            state.value
        } catch (failure: Throwable) {
            lastPollDiagnostic = PollDiagnostic(stage, when (failure) {
                is kotlinx.coroutines.TimeoutCancellationException -> "coroutine_timeout"
                is CancellationException -> "cancelled"
                is AriesProviderException -> "provider_policy"
                is java.io.IOException -> "io_failure"
                is org.json.JSONException -> "invalid_json"
                is IllegalArgumentException -> "invalid_data"
                is IllegalStateException -> "invalid_state"
                else -> "other"
            }, (failure as? AriesProviderException)?.code?.let {
                if (it in setOf("revoked", "conflict", "connection_unavailable", "remote_offline", "unsupported_method",
                    "permission_denied", "protocol_error", "request_timeout", "disk_space_low", "launcher_unavailable")) it else "other"
            })
            throw failure
        }
    }

    /** The final production client authenticates every scoped send, poll and
     * ACK inside its Native transaction, or explicit before/after fallback.
     * Arbitrary transports retain the independent checks used by this owner;
     * no JSON capability or caller flag can opt into the production path. */
    private suspend fun requireTransportBinding(requireOnline: Boolean = false) {
        if (transport !is RemoteDshClient) binding.requireCurrent(transport.connectionState(), requireOnline)
    }

    /** Cancel observation explicitly; the host separates this from session.cancel. */
    suspend fun cancelObservation(operationKey: String): JSONObject = withContext(Dispatchers.IO) {
        val request = mutex.withLock {
            val next = copyDocument()
            val found = requests(next).firstOrNull { it.getString("key") == operationKey } ?: throw AriesProviderException("permission_denied")
            require(found.getString("operation") in STREAM_OPERATIONS)
            // Save the user's stop intent before IO. A delayed close/restart
            // receipt cannot turn an explicitly cancelled observation back on.
            found.put("continueObservation", false)
            commit(next)
            JSONObject(found.toString())
        }
        require(request.getString("operation") in STREAM_OPERATIONS)
        binding.requireCurrent(transport.connectionState(), requireOnline = true)
        transport.cancelObservation(binding, request.getString("id"))
    }

    /** Bulk results are consumed by the explicit transfer controller, then
     * retired only after their saved frames have been acknowledged. Their
     * operations are exact range reads or offset/digest-idempotent writes. */
    suspend fun consumeTransferReceipt(operationKey: String) {
        mutex.withLock {
            val value = requests(document).firstOrNull { it.getString("key") == operationKey } ?: return@withLock
            if (value.getString("operation") !in CONSUMABLE_TRANSFER_OPERATIONS || value.getString("status") !in TERMINAL_STATUSES) return@withLock
            val next = copyDocument()
            requestById(next, value.getString("id")).put("consumed", true)
            commit(next)
        }
    }

    /** Persist consumption before Native retirement. A failed native call or
     * app journal commit keeps the same ID available for an idempotent retry. */
    private suspend fun flushTransferRetirements() = retirements.withLock {
        val candidates = mutex.withLock { retirementCandidates(document).map { JSONObject(it.toString()) } }
        for (value in candidates) {
            val receipt = try {
                transport.retireTransferReceipt(binding, value.getString("id"), value.getString("operation"), value.getLong("terminalSequence"))
                    .also { require(it.optBoolean("retired")) { "native_transfer_retirement_rejected" } }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutex.withLock { lastError = code(error); stateMutable.value = makeState() }
                return@withLock
            }
            check(receipt.optBoolean("retired"))
            mutex.withLock {
                val next = copyDocument()
                val removable = retirementCandidates(next).any { it.getString("id") == value.getString("id") }
                if (removable) {
                    next.put("requests", JSONArray().also { kept -> requests(next).filter {
                        it.getString("id") != value.getString("id")
                    }.forEach(kept::put) })
                    commit(next)
                }
            }
        }
    }

    private fun retirementCandidates(next: JSONObject): List<JSONObject> {
        val waiting = next.getJSONObject("acknowledgements").let { pending -> pending.keys().asSequence()
            .map { pending.getJSONObject(it).getString("requestId") }.toSet() }
        return requests(next).filter { it.getString("operation") in CONSUMABLE_TRANSFER_OPERATIONS && it.optBoolean("consumed") &&
            it.has("terminalSequence") && it.getString("status") in TERMINAL_STATUSES &&
            it.getJSONObject("pending").length() == 0 && it.getString("id") !in waiting }
    }

    private fun pruneReadReceipts(next: JSONObject) {
        val values = requests(next)
        val waiting = next.getJSONObject("acknowledgements").let { pending -> pending.keys().asSequence()
            .map { pending.getJSONObject(it).getString("requestId") }.toSet() }
        val eligible = values.filter { it.getString("operation") in READ_OPERATIONS && it.getString("operation") !in CONSUMABLE_TRANSFER_OPERATIONS &&
            it.getString("status") in TERMINAL_STATUSES &&
            it.getJSONObject("pending").length() == 0 && it.getString("id") !in waiting }
        val remove = (if (values.size >= 192) eligible.dropLast(64) else emptyList()).map { it.getString("id") }.toSet()
        if (remove.isEmpty()) return
        next.put("requests", JSONArray().also { kept -> values.filter { it.getString("id") !in remove }.forEach(kept::put) })
    }

    /** DSH follow reconnects with a fresh durable snapshot; it has no after-cursor request field. */
    private suspend fun resumeClosedObservations() {
        val resumes = mutex.withLock {
            if (!observing) return
            val next = copyDocument()
            val waiting = next.getJSONObject("acknowledgements").let { pending -> pending.keys().asSequence()
                .map { pending.getJSONObject(it).getString("requestId") }.toSet() }
            val candidates = requests(next).filter { value -> value.getString("operation") in STREAM_OPERATIONS &&
                value.optBoolean("continueObservation", true) && value.getString("id") !in waiting &&
                (value.getString("status") == RequestStatus.SUCCEEDED.name ||
                    value.getString("status") in setOf(RequestStatus.FAILED.name, RequestStatus.UNCERTAIN.name) &&
                    value.optString("error") in setOf("validation_failed", "request_uncertain")) }
            if (candidates.isEmpty()) return
            candidates.forEach { value ->
                val original = value.getString("id")
                value.put("previousObservationId", original).put("generation", value.optInt("generation") + 1)
                    .put("id", RemoteDshClient.newRequestId()).put("status", RequestStatus.INTENT.name).put("next", 0)
                    .put("pending", JSONObject()).put("fingerprints", JSONObject())
                value.remove("error"); value.remove("result")
            }
            commit(next)
            candidates.map { JSONObject(it.toString()) }
        }
        for (value in resumes) submit(value.getString("key"), value.getString("operation"), value.getJSONObject("payload"))
    }

    private fun receive(next: JSONObject, event: RemoteDshClient.Event) {
        require(event.senderDeviceId == binding.remoteDeviceId && event.sequence in 0L..1025L && event.type in EVENT_TYPES)
        require(event.messageId.isNotEmpty() && event.messageId.length <= 256)
        remoteValidateJson(event.payload)
        val request = requests(next).firstOrNull { it.getString("id") == event.requestId } ?: return
        val fingerprint = remoteJsonHash(JSONObject().put("type", event.type).put("payload", event.payload))
        val fingerprints = request.getJSONObject("fingerprints")
        val sequence = event.sequence.toString()
        if (fingerprints.has(sequence) && fingerprints.getString(sequence) != fingerprint) throw AriesProviderException("conflict")
        val acknowledgement = JSONObject().put("requestId", event.requestId).put("sequence", event.sequence)
            .put("type", event.type).put("messageId", event.messageId).put("sender", event.senderDeviceId)
        next.getJSONObject("acknowledgements").put(event.messageId, acknowledgement)
        if (fingerprints.has(sequence)) return
        require(fingerprints.length() < 1026)
        val payload = safePayload(request.getString("operation"), event.type, event.payload)
        fingerprints.put(sequence, fingerprint)
        request.getJSONObject("pending").put(sequence, JSONObject().put("type", event.type).put("payload", payload))
        drain(next, request)
        // A host restart may reserve an undelivered sequence. A durable
        // uncertainty receipt must never entice the UI to execute a new prompt.
        if (event.type == "error" && (event.payload as? JSONObject)?.optString("code") == "request_uncertain") {
            request.put("status", RequestStatus.UNCERTAIN.name).put("error", "request_uncertain")
        }
    }

    private fun drain(next: JSONObject, request: JSONObject) {
        val pending = request.getJSONObject("pending")
        while (pending.has(request.getLong("next").toString())) {
            val sequence = request.getLong("next").toString()
            val frame = pending.getJSONObject(sequence)
            val type = frame.getString("type")
            val payload = frame.opt("payload")
            if (request.getString("operation") in CONSUMABLE_TRANSFER_OPERATIONS && type in setOf("result", "error") &&
                (payload as? JSONObject)?.optString("code") != "request_uncertain") request.put("terminalSequence", sequence.toLong())
            when (type) {
                "accepted" -> request.put("status", RequestStatus.SUBMITTED.name)
                "result" -> {
                    request.put("result", payload)
                    val operation = request.getString("operation")
                    if (operation in RemoteDshClient.ARCHIVE_DELETE_OPERATIONS) {
                        val receipt = payload as JSONObject
                        val input = request.getJSONObject("payload")
                        require(if (operation == "session.archived.delete.prepare") receipt.getString("sessionId") == input.getString("sessionId")
                            else receipt.getString("ticket") == input.getString("ticket")) { "remote_archive_confirmation_changed" }
                    }
                    if (operation in METADATA_OPERATIONS) workspaces.applyOperationResult(binding, operation, payload)
                    request.put("status", if (operation in STREAM_OPERATIONS) RequestStatus.STREAMING.name else RequestStatus.SUCCEEDED.name)
                }
                "error" -> {
                    val error = (payload as? JSONObject)?.optString("code").takeUnless { it.isNullOrBlank() } ?: "remote_execution_failed"
                    request.put("error", error).put("status", if (error == "request_uncertain") RequestStatus.UNCERTAIN.name else RequestStatus.FAILED.name)
                }
                "closed" -> request.put("status", RequestStatus.SUCCEEDED.name)
                "event" -> applyActivity(next, request, payload as JSONObject)
            }
            pending.remove(sequence)
            request.put("next", request.getLong("next") + 1)
        }
    }

    private fun applyActivity(next: JSONObject, request: JSONObject, frame: JSONObject) {
        val sessions = next.getJSONObject("sessions")
        if (request.getString("operation") == "session.follow") {
            val payload = request.getJSONObject("payload")
            val address = payload.optJSONObject("address")
            val id = address?.remoteOptionalString("sessionId") ?: address?.remoteOptionalString("childSessionId") ?: payload.remoteId("sessionId")
            val mapper = sessions.optJSONObject(id)?.let(RemoteDshActivityMapper::restore) ?: RemoteDshActivityMapper(id)
            mapper.applyFollowFrame(frame)
            sessions.put(id, mapper.encode())
        } else if (request.getString("operation") == "session.control") {
            when (frame.getString("type")) {
                "baseline" -> {
                    val baselines = frame.getJSONObject("value").getJSONObject("projections")
                    require(baselines.length() <= 1000)
                    baselines.keys().forEach { id ->
                        val values = baselines.getJSONObject(id).getJSONObject("values")
                        values.keys().forEach { key -> applyProjection(sessions, id, key, values.opt(key)) }
                    }
                }
                "projection" -> applyProjection(sessions, frame.remoteId("sessionId"), frame.remoteId("key"), frame.opt("value"))
                else -> error("remote_control_frame_invalid")
            }
        }
        require(sessions.length() <= 1000)
        request.put("status", RequestStatus.STREAMING.name)
    }

    private fun applyProjection(sessions: JSONObject, id: String, key: String, value: Any?) {
        val safe = RemoteDshActivityMapper.publicProjection(key, value) ?: return
        val mapper = sessions.optJSONObject(id)?.let(RemoteDshActivityMapper::restore) ?: RemoteDshActivityMapper(id)
        mapper.applyProjection(key, safe)
        sessions.put(id, mapper.encode())
    }

    private suspend fun flushAcknowledgements() {
        val pending = mutex.withLock { document.getJSONObject("acknowledgements").let { acknowledgements ->
            acknowledgements.keys().asSequence().map { JSONObject(acknowledgements.getJSONObject(it).toString()) }.toList()
        } }
        for (value in pending) {
            val event = RemoteDshClient.Event(value.getString("requestId"), value.getLong("sequence"), value.getString("type"),
                null, value.getString("messageId"), value.getString("sender"))
            val receipt = transport.acknowledge(binding, event)
            require(receipt.optBoolean("acknowledged") == true) { "remote_acknowledgement_rejected" }
            mutex.withLock {
                val next = copyDocument()
                next.getJSONObject("acknowledgements").remove(event.messageId)
                pruneReadReceipts(next)
                commit(next)
            }
        }
    }

    private fun safePayload(operation: String, type: String, value: Any?): Any? {
        if (type == "event" && operation == "session.follow") return RemoteDshActivityMapper.publicFrame(value as JSONObject)
        if (type == "event" && operation == "session.control") {
            val frame = value as JSONObject
            if (frame.getString("type") == "projection") return JSONObject().put("type", "projection")
                .put("sessionId", frame.remoteId("sessionId")).put("key", frame.remoteId("key"))
                .put("seq", frame.remoteLong("seq")).put("value", RemoteDshActivityMapper.publicProjection(frame.getString("key"), frame.opt("value")))
            require(frame.getString("type") == "baseline")
            val source = frame.getJSONObject("value").getJSONObject("projections")
            require(source.length() <= 1000)
            val projections = JSONObject()
            source.keys().forEach { id ->
                val baseline = source.getJSONObject(id)
                val values = JSONObject()
                baseline.getJSONObject("values").let { sourceValues -> sourceValues.keys().forEach { key ->
                    RemoteDshActivityMapper.publicProjection(key, sourceValues.opt(key))?.let { values.put(key, it) }
                } }
                projections.put(id, JSONObject().put("asOfSeq", baseline.remoteSequenceCursor("asOfSeq")).put("values", values))
            }
            return JSONObject().put("type", "baseline").put("value", JSONObject().put("projections", projections))
        }
        // Finite adapter results contain catalog/paths/selection only. The host
        // applies the same positive projection before encryption and transport.
        return when (value) { is JSONObject -> JSONObject(value.toString()); is JSONArray -> JSONArray(value.toString()); else -> value }
    }

    private fun commit(next: JSONObject) {
        validateDocument(next)
        journal.write(next)
        document = next
        stateMutable.value = makeState()
    }
    private fun makeState(): State {
        val values = document.getJSONObject("sessions")
        val views = values.keys().asSequence().associateWith { RemoteDshActivityMapper.restore(values.getJSONObject(it)).view() }
        val enriched = views.mapValues { (_, view) ->
            val children = view.activity.subagents.map { row ->
                val child = views[row.agentId] ?: return@map row
                val status = when (child.activity.runStatus) {
                    AssistantRunStatus.RUNNING -> AssistantStreamEvent.SubagentStatus.RUNNING
                    AssistantRunStatus.COMPLETED -> AssistantStreamEvent.SubagentStatus.COMPLETED
                    AssistantRunStatus.FAILED -> AssistantStreamEvent.SubagentStatus.FAILED
                    AssistantRunStatus.CANCELLED -> AssistantStreamEvent.SubagentStatus.CANCELLED
                    AssistantRunStatus.INCOMPLETE -> AssistantStreamEvent.SubagentStatus.INCOMPLETE
                    null -> row.status
                }
                row.copy(status = status, output = child.messages.filter { it.role == "assistant" }.lastOrNull()?.text ?: row.output)
            }
            view.copy(activity = view.activity.copy(subagents = children), messages = view.messages.map { message ->
                message.copy(streamSnapshot = message.streamSnapshot?.let { snapshot ->
                    snapshot.copy(subagents = children.filter { child -> snapshot.subagents.any { it.agentId == child.agentId } })
                })
            })
        }
        return State(binding, requests(document).map(::requestView), enriched, workspaces.read(binding), observing, connected, lastError)
    }
    private fun requestView(value: JSONObject) = RequestView(value.getString("key"), value.getString("id"), value.getString("operation"),
        RequestStatus.valueOf(value.getString("status")), value.opt("result").takeUnless { it == JSONObject.NULL },
        value.remoteOptionalString("error"), value.getLong("next"), value.optBoolean("continueObservation", true))
    private fun requests(value: JSONObject) = value.getJSONArray("requests").remoteObjects(MAX_REQUESTS)
    private fun requestById(value: JSONObject, id: String) = requests(value).first { it.getString("id") == id }
    private fun copyDocument() = JSONObject(document.toString())
    private fun freshDocument() = JSONObject().put("schema", 1).put("scope", binding.toJson()).put("cursor", "0")
        .put("requests", JSONArray()).put("sessions", JSONObject()).put("acknowledgements", JSONObject())
    private fun validateDocument(value: JSONObject) {
        remoteValidateJson(value, 8 * 1024 * 1024)
        require(value.optInt("schema") == 1 && RemoteDshScope.fromJson(value.getJSONObject("scope")) == binding)
        require(value.getString("cursor").matches(Regex("[0-9]{1,64}")))
        val requests = requests(value)
        require(requests.map { it.getString("id") }.toSet().size == requests.size && requests.map { it.getString("key") }.toSet().size == requests.size)
        requests.forEach {
            require(it.getString("id").matches(Regex("[A-Za-z0-9_-]{8,128}")) && it.getString("operation") in OPERATIONS)
            require(!it.has("consumed") || it.get("consumed") is Boolean && it.getString("operation") in CONSUMABLE_TRANSFER_OPERATIONS)
            require(!it.has("terminalSequence") || it.getString("operation") in CONSUMABLE_TRANSFER_OPERATIONS &&
                it.remoteLong("terminalSequence") in 0..1025 && it.getLong("terminalSequence") < it.getLong("next"))
            RequestStatus.valueOf(it.getString("status"))
            require(it.getLong("next") in 0..1026 && it.getJSONObject("pending").length() <= 1026 && it.getJSONObject("fingerprints").length() <= 1026)
            require(it.getString("fingerprint") == remoteJsonHash(JSONObject().put("operation", it.getString("operation")).put("payload", it.getJSONObject("payload"))))
        }
        require(value.getJSONObject("sessions").length() <= 1000 && value.getJSONObject("acknowledgements").length() <= 4096)
    }
    private fun code(error: Throwable): String = when (error) {
        is AriesProviderException -> error.code
        is java.io.IOException -> "connection_unavailable"
        is IllegalArgumentException, is org.json.JSONException -> "protocol_error"
        else -> "remote_storage_or_protocol_failed"
    }

    companion object {
        private const val MAX_REQUESTS = 256
        private val STREAM_OPERATIONS = setOf("session.follow", "session.control")
        private val EVENT_TYPES = setOf("accepted", "result", "error", "event", "closed")
        private val METADATA_OPERATIONS = setOf("workspace.list", "workspace.resolve", "session.list", "models.catalog", "directory.list", "settings.describe", "settings.update", "session.selectModel", "permissions.catalog") + RemoteDshClient.LIFECYCLE_OPERATIONS
        private val CONSUMABLE_TRANSFER_OPERATIONS = RemoteDshClient.CONSUMABLE_TRANSFER_OPERATIONS
        private val READ_OPERATIONS = setOf("workspace.list", "session.list", "models.catalog", "directory.list", "settings.describe", "permissions.catalog", "workspace.removed.list", "session.archived.list") + CONSUMABLE_TRANSFER_OPERATIONS
        private val TERMINAL_STATUSES = setOf(RequestStatus.SUCCEEDED.name, RequestStatus.FAILED.name)
        private val OPERATIONS = METADATA_OPERATIONS + STREAM_OPERATIONS + setOf("session.create", "session.prompt", "session.cancel") + RemoteDshClient.ARTIFACT_OPERATIONS + RemoteDshClient.ATTACHMENT_OPERATIONS
        fun diskJournal(file: File): RemoteDshJournal = object : RemoteDshJournal {
            private val persistence = RemoteDshJsonFile(file)
            override fun read() = persistence.read()
            override fun write(value: JSONObject) = persistence.write(value)
        }
    }
}
