package com.dafeiyu.mobile.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Shares the isolated public pairing/cleanup only; all archive calls use the real Host and Controller. */
@RunWith(AndroidJUnit4::class)
class RemoteDshPublicArchiveSmoke {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val root by lazy { File(target.filesDir, "public-web-audit") }
    private val config by lazy { JSONObject(File(root, "phone-config.json").readText(Charsets.UTF_8)) }
    private val group by lazy { config.getString("group_id") }
    private val node by lazy { config.getString("node_id") }
    private val app by lazy {
        check(target.packageName == "com.dafeiyu.mobile.dev" && group.matches(ID))
        val vault = File(target.noBackupFilesDir, "public-web-audit/$group").apply { check(isDirectory || mkdirs()) }
        object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = vault
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                target.getSharedPreferences("public-web-$group-$name", mode)
        }
    }
    private var checks = 0
    private val diagnosticFrames = Collections.synchronizedList(mutableListOf<JSONObject>())
    private val diagnosticStarted = System.nanoTime()
    private fun trace(stage: String, metadata: JSONObject) {
        synchronized(diagnosticFrames) {
            if (diagnosticFrames.size < 4096) diagnosticFrames.add(JSONObject()
                .put("elapsedMillis", (System.nanoTime() - diagnosticStarted) / 1_000_000)
                .put("stage", stage).put("metadata", metadata))
        }
    }
    private fun traceController(current: RemoteDshApplicationController?) {
        current?.let {
            trace("controller.final", JSONObject().put("connected", it.state.value.connected)
                .put("remoteOnline", it.state.value.remoteOnline).put("poll", it.pollDiagnostic?.toJson() ?: JSONObject.NULL)
                .put("requests", JSONArray().also { rows -> it.state.value.projection?.requests?.forEach { request ->
                    rows.put(JSONObject().put("operation", request.operation).put("status", request.status.name)
                        .put("nextSequence", request.nextSequence).put("errorCode", request.errorCode?.let { code ->
                            if (code in SAFE_ERRORS) code else "other"
                        } ?: JSONObject.NULL))
                } }))
        }
    }
    private fun writeDiagnostics() = write("phone-frame-trace.json", JSONObject().put("group_id", group)
        .put("frames", JSONArray(synchronized(diagnosticFrames) { diagnosticFrames.toList() }))
        .put("paidModelCalls", 0).put("privatePayloadIncluded", false))
    private fun pass(name: String) { checks++; Log.i(TAG, "PASS $checks $name") }
    private fun write(name: String, value: JSONObject) {
        check(name.matches(Regex("[a-z0-9-]+\\.json")) && (root.isDirectory || root.mkdirs()))
        val temporary = File(root, "$name.tmp")
        temporary.writeText(value.toString(), Charsets.UTF_8)
        check(temporary.renameTo(File(root, name)))
    }
    private fun isolated() {
        check(config.getString("endpoint") == "https://202.189.21.218:10240" && node.matches(ID))
        assertEquals(group, app.getSharedPreferences("dafeiyu_settings", Context.MODE_PRIVATE).getString("aries_group_id", ""))
        assertEquals(200430L, config.getLong("connect_size"))
        assertEquals("4f3657730459197835a014b7f7433b44cc652e5b32480cdb37c061b160e75bf2", config.getString("connect_sha256"))
    }
    private fun phaseResult(phase: String, success: Boolean): JSONObject = JSONObject().put("phase", phase)
        .put("group_id", group).put("device_id", AriesIdentityStore(app).identity().deviceId)
        .put("success", success).put("checks", checks).put("paidModelCalls", 0).put("productionArchiveCapability", false)

    private suspend fun startHost(host: ConnectPluginHost): RemoteDshScope {
        val loaded = host.start()
        assertEquals(ConnectPluginHostStatus.RUNNING, loaded.status)
        assertEquals(config.getString("connect_sha256"), loaded.archiveSha256)
        assertEquals(config.getLong("connect_size"), loaded.archiveSizeBytes)
        host.setNativeDiagnosticObserver { trace("native.provider", it.toJson()) }
        val paired = withTimeout(90_000) {
            var state = host.call("get_connection_state", JSONObject(), null)
            while (!state.getJSONObject("capabilities").optBoolean("remote_archive_delete") ||
                state.optJSONObject("remote_device")?.optString("online") != "online") {
                delay(750); state = host.call("get_connection_state", JSONObject(), null)
            }
            state
        }
        assertEquals("paired", paired.getString("pairing_status"))
        assertEquals(group, paired.getString("group_id"))
        assertEquals(config.getString("pc_device_id"), paired.getJSONObject("remote_device").getString("id"))
        assertEquals(config.getString("pc_public_key"), paired.getJSONObject("remote_device").getString("public_key"))
        return RemoteDshScope.fromState(paired)
    }

    @Test fun prepareConfirmAndRejectOldArchiveEpoch() = runBlocking {
        isolated()
        val provider = AriesConnectionProvider(app)
        val host = ConnectPluginHost.get(app)
        var controller: RemoteDshApplicationController? = null
        var success = false
        try {
            val delivery = withTimeout(90_000) {
                var review: JSONObject? = null
                while (review == null) { review = provider.pendingDelivery(); if (review == null) delay(750) }
                review
            }
            assertEquals(config.getString("pc_device_id"), delivery.getString("sender_id"))
            assertEquals(config.getString("pc_fingerprint"), delivery.getString("fingerprint"))
            val application = JSONObject(File(root, "phone-application.json").readText()).getString("application_id")
            assertEquals(application, delivery.getString("application_id"))
            provider.confirmDelivery(config.getString("endpoint"), node, group, application,
                delivery.getString("delivery_id"), delivery.getString("fingerprint"))
            pass("real content key and exact PC confirmation completed without issuing a prompt")
            val binding = startHost(host)
            write("phone-archive-scope.json", binding.toJson())
            pass("actual Android Node uses the same frozen package and authenticated archive advertisement")
            var current = RemoteDshApplicationController(app, host).also { controller = it; it.setActive(true); it.connect() }
            withTimeout(90_000) { current.state.first { it.projection?.workspace?.catalog != null } }
            val deleteId = config.getString("delete_session_id")
            val epochId = config.getString("epoch_session_id")
            val revokeId = config.getString("revoke_session_id")
            val initial = current.state.value.projection!!.workspace
            assertTrue(initial.workspaces.any { it.id == config.getString("workspace_id") })
            assertTrue(listOf(deleteId, epochId, revokeId).all { id -> initial.sessions.any { it.id == id } })
            for (session in listOf(deleteId, epochId, revokeId)) current.archiveSession(session, expectedScope = binding)
            assertTrue(listOf(deleteId, epochId, revokeId).all { id -> current.state.value.projection!!.workspace.archives.any { it.sessionId == id } })
            pass("three actual native PC sessions are archived through production Controller requests")
            val deletion = current.prepareArchivedSessionDeletion(deleteId, binding)
            assertFalse(deletion.deleted); assertEquals("prepared", deletion.status)
            assertEquals(binding, deletion.scope); assertEquals("remote_computer", deletion.executionDevice)
            assertNotNull(deletion.archivedAt); assertEquals(30L * 86_400_000, deletion.expiresAt!! - deletion.archivedAt!!)
            assertTrue(current.state.value.projection!!.workspace.archives.any { it.sessionId == deleteId })
            write("phone-archive-prepared.json", deletion.toJson())
            val epochTicket = current.prepareArchivedSessionDeletion(epochId, binding)
            val revokeTicket = current.prepareArchivedSessionDeletion(revokeId, binding)
            write("phone-archive-revoke-ticket.json", revokeTicket.toJson())
            pass("preparation preserves archives and binds exact epoch, execution device and thirty-day retention")
            current.restoreArchivedSession(epochId, binding)
            current.archiveSession(epochId, expectedScope = binding)
            assertTrue(current.state.value.projection!!.workspace.archives.single { it.sessionId == epochId }.archiveVersion != epochTicket.archiveVersion)
            val rejected = rejection { current.deleteArchivedSession(epochTicket, binding) }
            assertEquals("remote_archive_version_changed", rejected.message)
            assertTrue(current.state.value.projection!!.workspace.archives.any { it.sessionId == epochId })
            pass("restore and rearchive reject the independently prepared ticket from the earlier archive epoch")
            current.close(); controller = null
            current = RemoteDshApplicationController(app, host).also { controller = it; it.setActive(true); it.connect() }
            val cached = current.state.value.projection!!.workspace.archiveDeletions.single { it.ticket == deletion.ticket }
            assertTrue(cached.sameConfirmation(deletion) && !cached.deleted)
            pass("a new Controller reads the exact non-destructive confirmation from durable Android storage")
            val receipt = current.deleteArchivedSession(deletion, binding)
            assertTrue(receipt.sameConfirmation(deletion) && receipt.deleted && receipt.status == "deleted")
            assertNotNull(receipt.deletedAt)
            assertTrue(current.state.value.projection!!.workspace.archives.none { it.sessionId == deleteId })
            assertTrue(listOf(epochId, revokeId).all { id -> current.state.value.projection!!.workspace.archives.any { it.sessionId == id } })
            write("phone-archive-deleted-receipt.json", receipt.toJson())
            pass("independent confirmation receives exact durable deleted terminal and preserves the other archives")
            success = true
            write("phone-archive-delete.json", phaseResult("hot", true).put("sessionId", deleteId))
        } catch (failure: Throwable) {
            write("phone-archive-failure.json", JSONObject().put("phase", "hot").put("category", failure.javaClass.simpleName)
                .put("code", (failure as? AriesProviderException)?.code ?: JSONObject.NULL).put("checks", checks))
            throw failure
        } finally {
            withContext(NonCancellable) {
                traceController(controller)
                controller?.close(); host.setNativeDiagnosticObserver(null); host.stop(); writeDiagnostics()
                write("phone-archive-hot-result.json", phaseResult("hot", success))
            }
        }
        Unit
    }

    @Test fun coldControllerRetainsDeletionFence() = runBlocking {
        isolated()
        val host = ConnectPluginHost.get(app)
        var controller: RemoteDshApplicationController? = null
        var success = false
        try {
            val retained = RemoteDshScope.fromJson(JSONObject(File(root, "phone-archive-scope.json").readText()))
            val receipt = RemoteWorkspaceStore.archiveDeletionFromJson(JSONObject(File(root, "phone-archive-deleted-receipt.json").readText()))
            val binding = startHost(host)
            assertEquals(retained, binding); assertEquals(binding, receipt.scope)
            pass("cold Android Host remains bound to the exact retained relationship and byte-identical plugin")
            val current = RemoteDshApplicationController(app, host).also { controller = it; it.setActive(true); it.connect() }
            val restored = current.state.value.projection!!.workspace.archiveDeletions.single { it.ticket == receipt.ticket }
            assertEquals(receipt, restored); assertTrue(restored.deleted)
            pass("fresh Controller reads the exact terminal deletion fence before refreshing PC catalogues")
            current.refreshArchive(binding)
            val persisted = RemoteWorkspaceStore(app).read(binding)
            assertTrue(persisted.archives.none { it.sessionId == receipt.sessionId })
            assertTrue(persisted.sessions.none { it.id == receipt.sessionId })
            assertTrue(persisted.workspaces.all { receipt.sessionId !in it.sessionIds })
            assertTrue(listOf(config.getString("epoch_session_id"), config.getString("revoke_session_id")).all { id ->
                persisted.archives.any { it.sessionId == id }
            })
            pass("real cold PC catalogue cannot revive the deleted session and retains both surviving archives")
            val prepared = RemoteWorkspaceStore.archiveDeletionFromJson(JSONObject(File(root, "phone-archive-revoke-ticket.json").readText()))
            assertTrue(persisted.archiveDeletions.single { it.ticket == prepared.ticket }.sameConfirmation(prepared))
            pass("the exact surviving prepared ticket remains available for the subsequent revocation check")
            success = true
        } catch (failure: Throwable) {
            write("phone-archive-failure.json", JSONObject().put("phase", "cold").put("category", failure.javaClass.simpleName)
                .put("code", (failure as? AriesProviderException)?.code ?: JSONObject.NULL).put("checks", checks))
            throw failure
        } finally {
            withContext(NonCancellable) {
                traceController(controller)
                controller?.close(); host.setNativeDiagnosticObserver(null); host.stop(); writeDiagnostics()
                write("phone-finished.json", phaseResult("cold", success))
            }
        }
        Unit
    }

    @Test fun revokedRelationshipCannotPrepareOrCommit() = runBlocking {
        isolated()
        val provider = AriesConnectionProvider(app)
        val device = AriesIdentityStore(app).identity().deviceId
        val terminalState = withTimeout(90_000) {
            var state = provider.call("get_connection_state")
            while (state.optJSONObject("terminal_receipt") == null) { delay(1000); state = provider.call("get_connection_state") }
            state
        }
        val terminal = terminalState.getJSONObject("terminal_receipt")
        assertEquals(group, terminal.getString("group_id")); assertEquals(device, terminal.getString("device_id"))
        assertEquals("group_closed", terminal.getString("status")); assertEquals("revoked", terminalState.getString("pairing_status"))
        pass("actual public unpair produces the exact persistent group terminal before old-scope calls")
        val prepared = RemoteWorkspaceStore.archiveDeletionFromJson(JSONObject(File(root, "phone-archive-revoke-ticket.json").readText()))
        val binding = RemoteDshScope.fromJson(JSONObject(File(root, "phone-archive-scope.json").readText()))
        assertEquals(binding, prepared.scope)
        // The real Native Provider is intentionally used after its Host has stopped;
        // it must reject the old relationship before any encrypted mutation is sent.
        val current = RemoteDshApplicationController(app, provider)
        try {
            val prepareFailure = rejection { current.prepareArchivedSessionDeletion(prepared.sessionId, binding) }
            assertEquals("connection_unavailable", prepareFailure.message)
            pass("revoked relationship cannot prepare another deletion using its saved scope")
            val commitFailure = rejection { current.deleteArchivedSession(prepared, binding) }
            assertEquals("connection_unavailable", commitFailure.message)
            pass("revoked relationship cannot commit its earlier independent confirmation")
            val persisted = RemoteWorkspaceStore(app).read(binding)
            assertTrue(persisted.archiveDeletions.single { it.ticket == prepared.ticket }.status == "prepared")
            assertTrue(persisted.archives.any { it.sessionId == prepared.sessionId })
            pass("rejected stale confirmation never becomes local deletion success")
            write("phone-archive-revoked.json", phaseResult("revoked", true).put("terminal", terminal)
                .put("prepareRejection", "connection_unavailable").put("commitRejection", "connection_unavailable"))
        } finally { current.close() }
        Unit
    }

    private suspend fun rejection(block: suspend () -> Unit): Throwable {
        try { block() }
        catch (failure: Throwable) {
            if (failure is CancellationException || failure is AssertionError) throw failure
            return failure
        }
        throw AssertionError("Old archive authority was accepted")
    }

    companion object {
        private const val TAG = "DafeiyuPublicArchiveQA"
        private val ID = Regex("[A-Za-z0-9_-]{1,128}")
        private val SAFE_ERRORS = setOf("connection_unavailable", "remote_offline", "revoked", "conflict", "unsupported_method",
            "protocol_error", "permission_denied", "request_timeout", "disk_space_low", "remote_storage_or_protocol_failed")
    }
}
