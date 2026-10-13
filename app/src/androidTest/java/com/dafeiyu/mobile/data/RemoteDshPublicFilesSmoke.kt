package com.dafeiyu.mobile.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the existing public pairing/cleanup phases and its separate persistent relationship. */
@RunWith(AndroidJUnit4::class)
class RemoteDshPublicFilesSmoke {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val root by lazy { File(target.filesDir, "public-web-audit") }
    private val config by lazy { JSONObject(File(root, "phone-config.json").readText(Charsets.UTF_8)) }
    private val group by lazy { config.getString("group_id") }
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
    private fun pass(name: String) { checks++; Log.i(TAG, "PASS $checks $name") }
    private fun write(name: String, value: JSONObject) {
        val temporary = File(root, "$name.tmp")
        temporary.writeText(value.toString(), Charsets.UTF_8)
        check(temporary.renameTo(File(root, name)))
    }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    @Test fun realNativeBulkDownloadAndAttachmentCommit() = runBlocking {
        val endpoint = config.getString("endpoint")
        val node = config.getString("node_id")
        check(endpoint == "https://202.189.21.218:10240" && node.matches(ID))
        assertEquals(group, app.getSharedPreferences("dafeiyu_settings", Context.MODE_PRIVATE).getString("aries_group_id", ""))
        val provider = AriesConnectionProvider(app)
        val host = ConnectPluginHost.get(app)
        val network = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        val retired = AtomicInteger()
        val nativeErrors = AtomicInteger()
        var controller: RemoteDshApplicationController? = null
        var success = false
        val metrics = JSONObject()
        val frames = Collections.synchronizedList(mutableListOf<JSONObject>())
        val started = System.nanoTime()
        fun trace(stage: String, metadata: JSONObject) {
            if (frames.size < 4096) frames.add(JSONObject().put("elapsedMillis", (System.nanoTime() - started) / 1_000_000)
                .put("stage", stage).put("metadata", metadata))
        }
        try {
            val review = withTimeout(90_000) {
                var value: JSONObject? = null
                while (value == null) { value = provider.pendingDelivery(); if (value == null) delay(750) }
                value
            }
            assertEquals(config.getString("pc_device_id"), review.getString("sender_id"))
            assertEquals(config.getString("pc_fingerprint"), review.getString("fingerprint"))
            val application = JSONObject(File(root, "phone-application.json").readText()).getString("application_id")
            assertEquals(application, review.getString("application_id"))
            provider.confirmDelivery(endpoint, node, group, application, review.getString("delivery_id"), review.getString("fingerprint"))
            pass("exact PC content-key delivery confirmed with real Android identity")
            val loaded = host.start()
            assertEquals(config.getString("connect_sha256"), loaded.archiveSha256)
            assertEquals(config.getLong("connect_size"), loaded.archiveSizeBytes)
            assertEquals(ConnectPluginHostStatus.RUNNING, loaded.status)
            pass("actual Node/shared frozen package loaded before any file transfer")
            host.setNativeDiagnosticObserver { trace("native.provider", it.toJson()) }
            val paired = withTimeout(90_000) {
                var value = host.call("get_connection_state", JSONObject(), null)
                while (!value.getJSONObject("capabilities").optBoolean("remote_artifacts") ||
                    !value.getJSONObject("capabilities").optBoolean("remote_attachments") ||
                    value.optJSONObject("remote_device")?.optString("online") != "online") {
                    delay(750); value = host.call("get_connection_state", JSONObject(), null)
                }
                value
            }
            assertEquals("paired", paired.getString("pairing_status"))
            assertEquals(config.getString("pc_device_id"), paired.getJSONObject("remote_device").getString("id"))
            assertEquals(config.getString("pc_public_key"), paired.getJSONObject("remote_device").getString("public_key"))
            pass("file capabilities came from exact encrypted PC host advertisement")
            val binding = RemoteDshScope.fromState(paired)
            // This object forwards the real Native retirement hook. A lambda's
            // default hook would falsely count journal cleanup as Native proof.
            val calls = object : RemoteDshProviderCall {
                override suspend fun call(method: String, params: JSONObject, expectedRemoteScope: RemoteDshScope?): JSONObject {
                    val detail = JSONObject().put("method", method)
                    trace("native.call.start", detail)
                    try {
                        val result = host.call(method, params, expectedRemoteScope)
                        trace("native.call.done", detail)
                        if (method == "get_connection_state") trace("native.state", JSONObject()
                            .put("serverConnected", result.optBoolean("server_connected"))
                            .put("pairingStatus", result.optString("pairing_status"))
                            .put("peerPresence", result.optJSONObject("remote_device")?.optString("online") ?: "unknown"))
                        return result
                    } catch (failure: Throwable) {
                        trace("native.call.failure", JSONObject().put("method", method)
                            .put("code", (failure as? AriesProviderException)?.code ?: "other"))
                        throw failure
                    }
                }
                override suspend fun callInScope(method: String, params: JSONObject, scope: RemoteDshScope, capability: String,
                    acknowledgeMessageId: String?): JSONObject {
                    val detail = JSONObject().put("method", method).put("capability", capability)
                        .put("cursor", params.opt("cursor") ?: JSONObject.NULL)
                        .put("operation", params.optJSONObject("body")?.opt("operation") ?: JSONObject.NULL)
                    trace("native.scoped.start", detail)
                    try { return host.callInScope(method, params, scope, capability, acknowledgeMessageId)
                        .also { trace("native.scoped.done", detail) } }
                    catch (failure: Throwable) {
                        trace("native.scoped.failure", JSONObject(detail.toString())
                            .put("code", (failure as? AriesProviderException)?.code ?: "other"))
                        throw failure
                    }
                }
                override suspend fun retireTransferReceipt(scope: RemoteDshScope, requestId: String, operation: String): JSONObject {
                    return try {
                        host.retireTransferReceipt(scope, requestId, operation).also {
                            if (it.optBoolean("retired")) retired.incrementAndGet()
                        }
                    } catch (error: Throwable) { nativeErrors.incrementAndGet(); throw error }
                }
            }
            val current = RemoteDshApplicationController(app, calls)
            controller = current
            current.setActive(true)
            current.connect()
            withTimeout(90_000) { current.state.first { it.projection?.workspace?.catalog != null } }
            val workspace = config.getString("workspace_id")
            val downloads = File(app.noBackupFilesDir, "actual-file-transfer").apply { check(isDirectory || mkdirs()) }
            val listingValue = current.artifactRequest("artifact.list", JSONObject().put("workspaceId", workspace)
                .put("directory", "").put("limit", 100), binding)
            val listing = RemoteArtifactProtocol.listing(listingValue, workspace, "")
            assertEquals(3, listing.entries.size)
            pass("actual PC directory metadata listed without downloading user files")
            val artifacts = config.getJSONArray("artifacts")
            var maximumRanges = 0
            for (index in 0 until artifacts.length()) {
                val expected = artifacts.getJSONObject(index)
                val entry = listing.entries.single { it.name == expected.getString("name") }
                assertEquals(expected.getLong("size"), entry.size)
                val ticket = RemoteArtifactProtocol.ticket(current.artifactRequest("artifact.prepare", JSONObject()
                    .put("workspaceId", workspace).put("relativePath", entry.relativePath).put("version", entry.version), binding), workspace, entry)
                assertEquals(expected.getString("sha256"), ticket.sha256)
                val active = AtomicInteger()
                val maximum = AtomicInteger()
                val remote = RemoteArtifactCall { operation, payload, scope ->
                    val ranged = operation == "artifact.read" && payload.optInt("length") > 1
                    if (ranged) maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                    try { current.artifactRequest(operation, payload, scope) }
                    finally { if (ranged) active.decrementAndGet() }
                }
                val file = File(downloads, entry.name)
                try {
                    RemoteArtifactLoopback(binding, ticket, remote) {
                        binding.requireCurrent(calls.call("get_connection_state", JSONObject(), binding), requireOnline = true)
                    }.use { loopback ->
                        val result = ArtifactDownloader(downloads, { network }).download(ArtifactDownloadRequest(
                            listOf(loopback.url), file, ArtifactChecksum("SHA-256", ticket.sha256), ticket.size,
                            allowedLoopbackPort = loopback.port))
                        assertEquals(expected.getInt("threads"), result.threads)
                        assertEquals(ticket.size, result.file.length())
                        assertEquals(ticket.sha256, sha256(result.file))
                        if (ticket.size > 1024 * 1024) assertEquals(expected.getInt("threads"), maximum.get())
                        maximumRanges = maxOf(maximumRanges, maximum.get())
                        metrics.put(entry.name, JSONObject().put("threads", result.threads).put("maximumRemoteReads", maximum.get())
                            .put("size", result.file.length()).put("sha256", ticket.sha256))
                        pass("${result.threads} real loopback ranges downloaded ${entry.name} through production Native/public Aries/PC and verified digest")
                    }
                } finally {
                    current.artifactRequest("artifact.close", JSONObject().put("ticket", ticket.ticket), binding)
                }
            }
            assertEquals(8, maximumRanges)
            val attachment = File(downloads, "phone-upload.txt")
            attachment.outputStream().use { stream ->
                val buffer = ByteArray(48 * 1024) { (it * 17 % 239).toByte() }
                repeat(32) { stream.write(buffer) }
            }
            val transfer = RemoteAttachmentTransfer(binding,
                RemoteAttachmentRequest { key, operation, payload -> current.attachmentRequest(key, operation, payload, binding) },
                { calls.call("get_connection_state", JSONObject(), binding) })
            val uploaded = transfer.upload("actual-public-upload-${UUID.randomUUID()}", config.getString("session_id"), attachment, attachment.name)
            assertEquals(attachment.length(), uploaded.size)
            assertEquals(sha256(attachment), uploaded.sha256)
            assertEquals("sha256:${uploaded.sha256}", uploaded.file.getString("attachmentId"))
            pass("real Android attachment bytes committed to first-party PC store without submitting a prompt")
            withTimeout(90_000) {
                current.state.first { state -> state.projection?.requests?.none {
                    it.operation in RemoteDshClient.CONSUMABLE_TRANSFER_OPERATIONS
                } == true }
            }
            assertTrue(retired.get() > 220)
            current.close(); controller = null
            host.stop()
            // Inspect disk only after all local writers stop. AtomicFile.openRead
            // must not race a production finishWrite.
            val journal = AriesProviderVault(app).load()
            assertEquals(0, journal.optJSONObject("bulk_receipts")?.length() ?: 0)
            val mutations = journal.getJSONObject("mutations")
            assertTrue(mutations.length() < 96)
            assertTrue(journal.toString().toByteArray().size < 2 * 1024 * 1024)
            metrics.put("nativeRetired", retired.get()).put("nativeRetirementErrors", nativeErrors.get())
                .put("nativeMutationCount", mutations.length()).put("nativeJournalBytes", journal.toString().toByteArray().size)
            pass("real Native original-request and encrypted-consumption-ACK mutations retired, with bounded encrypted vault")
            success = true
        } finally {
            withContext(NonCancellable) {
                controller?.state?.value?.let { state ->
                    trace("controller.final", JSONObject().put("connected", state.connected)
                        .put("remoteOnline", state.remoteOnline).put("poll", controller?.pollDiagnostic?.toJson() ?: JSONObject.NULL)
                        .put("requests", JSONArray().also { records ->
                            state.projection?.requests?.forEach { request -> records.put(JSONObject()
                                .put("operation", request.operation).put("status", request.status.name)
                                .put("nextSequence", request.nextSequence)
                                .put("errorCode", request.errorCode?.let {
                                    if (it in SAFE_ERRORS) it else "other"
                                } ?: JSONObject.NULL)) }
                        }))
                }
                controller?.close(); host.setNativeDiagnosticObserver(null); host.stop()
                network.connectionPool.evictAll(); network.dispatcher.executorService.shutdown()
                write("phone-frame-trace.json", JSONObject().put("frames", JSONArray(synchronized(frames) { frames.toList() }))
                    .put("paidModelCalls", 0).put("privatePayloadIncluded", false))
                write("phone-file-metrics.json", metrics.put("checks", checks).put("publicAries", true)
                    .put("androidNativeHttpObserved", false).put("paidModelCalls", 0))
                write("phone-finished.json", JSONObject().put("group_id", group)
                    .put("device_id", AriesIdentityStore(app).identity().deviceId).put("checks", checks)
                    .put("success", success).put("paidModelCalls", 0).put("productionFileCapabilitiesEnabled", false))
            }
        }
        Unit
    }
    companion object {
        private const val TAG = "DafeiyuPublicFilesQA"
        private val ID = Regex("[A-Za-z0-9_-]{1,128}")
        private val SAFE_ERRORS = setOf("connection_unavailable", "remote_offline", "revoked", "conflict", "unsupported_method",
            "protocol_error", "permission_denied", "pairing_incomplete", "request_timeout", "tls_failure", "disk_space_low")
    }
}
