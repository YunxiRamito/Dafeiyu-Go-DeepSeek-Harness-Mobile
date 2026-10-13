package com.dafeiyu.mobile.data

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Controller, WorkspaceStore, Android AtomicFile, fixed DSH storage
 * helper and exclusive runtime lease. Every conversation, home and journal is
 * isolated; the installed Ubuntu/Node/DSH slot is only reused, never changed.
 * No provider credentials, Agent prompt or external model request are used. */
@RunWith(AndroidJUnit4::class)
class LocalDshHistoryDeletionSmoke {
    @Test
    fun deleteEverySharedVersionAndColdRead(): Unit = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        check(target.packageName == "com.dafeiyu.mobile.dev" && android.os.Build.SUPPORTED_ABIS.first() == "x86_64")
        val id = UUID.randomUUID().toString()
        val files = File(target.filesDir, "local-history-deletion-smoke/$id").apply { check(mkdirs()) }
        val vault = File(target.noBackupFilesDir, "local-history-deletion-smoke/$id").apply { check(mkdirs()) }
        val cache = File(target.cacheDir, "local-history-deletion-smoke/$id").apply { check(mkdirs()) }
        val isolated = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getNoBackupFilesDir(): File = vault
            override fun getCacheDir(): File = cache
        }
        val runtime = LinuxRuntimeManager(target)
        val installer = DshRuntimeInstaller(target, runtime)
        val bridge = DshSharedPersistenceBridge(isolated, runtime, installer)
        val store = WorkspaceStore(isolated)
        fun controller() = LocalDshHistoryController(isolated, bridge)
        val reportFile = File(target.filesDir, "local-history-deletion-smoke-report.json")
        val cases = JSONArray()
        val report = JSONObject().put("schema", 1).put("runId", id).put("package", target.packageName)
            .put("startedAt", System.currentTimeMillis()).put("checks", cases).put("passed", false)
            .put("actualAndroidController", true).put("actualAndroidAtomicFile", true)
            .put("actualBridgeAndDshHelper", true).put("paidModelRequests", 0)
        fun persist() { reportFile.writeText(report.toString(2), Charsets.UTF_8) }
        suspend fun checkCase(name: String, body: suspend () -> JSONObject) {
            val item = JSONObject().put("name", name).put("passed", false)
            cases.put(item); persist()
            try { item.put("evidence", withTimeout(180_000) { body() }).put("passed", true) }
            catch (failure: Throwable) { item.put("error", failure.javaClass.simpleName); throw failure }
            finally { persist() }
            Log.i("LocalHistoryDeletionQA", "PASS ${cases.length()} $name")
        }
        val deleteId = "delete-$id"
        val keepId = "keep-$id"
        val versions = linkedSetOf<String>()
        var keepSessionId = ""
        var snapshot = WorkspaceSnapshot(listOf(Workspace("qa", "Shared history QA")),
            listOf(Conversation(deleteId, "Delete every version", "qa"), Conversation(keepId, "Keep unrelated history", "qa")),
            mapOf(deleteId to listOf(ChatMessage(1, "user", "offline question"), ChatMessage(2, "assistant", "offline answer")),
                keepId to listOf(ChatMessage(20, "user", "unrelated offline question"), ChatMessage(21, "assistant", "unrelated offline answer"))),
            "qa", deleteId)
        lateinit var intent: LocalDshHistoryDeletionIntent
        lateinit var receipt: LocalDshHistoryDeletionReceipt
        val sessions = File(files, "dsh-home/sessions")
        val rawCopies = File(files, "local-dsh-history/artifacts")
        val protocolCopies = File(files, "native-model-history")
        try {
            checkCase("fixed installed runtime is idle and available without changing its slot") {
                check(runtime.managedProcesses.value.none { it.running }) { "Stop QA-owned runtime processes before this isolated test" }
                val slot = checkNotNull(installer.current()) { "Run the production runtime installation smoke first" }
                val identity = runtime.nodeIdentity()
                check(identity.platform == "linux" && identity.architecture == slot.manifest.nodeArchitecture)
                JSONObject().put("dshVersion", slot.manifest.dshVersion).put("nodeVersion", identity.version)
                    .put("nodeArchitecture", identity.architecture).put("installedSlotDigest", slot.manifest.digest())
            }
            checkCase("real native publisher creates three exact DSH versions and one unrelated history") {
                for (version in 1..3) {
                    if (version > 1) snapshot = snapshot.copy(messages = snapshot.messages + (deleteId to
                        (snapshot.messages.getValue(deleteId) + ChatMessage((version * 2 - 1).toLong(), "user", "offline version $version") +
                            ChatMessage((version * 2).toLong(), "assistant", "offline response $version"))))
                    store.save(snapshot)
                    val published = controller().publish(snapshot)
                    check(published.warnings.isEmpty())
                    versions += checkNotNull(published.selectedSessionId)
                }
                check(versions.size == 3)
                val listed = bridge.listArtifacts(sessions).getJSONArray("items").remoteObjects(1000)
                check(listed.size == 4)
                keepSessionId = listed.single { it.getJSONObject("header").getString("id") !in versions }
                    .getJSONObject("header").getString("id")
                val mappingBeforeRead = checkNotNull(RemoteDshJsonFile(File(files, "local-dsh-history/links.json"),
                    4 * 1024 * 1024).read())
                val imported = controller().read(snapshot)
                report.put("firstImportDiagnostics", importDiagnostics(snapshot, imported, mappingBeforeRead,
                    rawCopies, versions + keepSessionId)); persist()
                check(imported.warnings.isEmpty()) { "UNEXPECTED_FIRST_IMPORT_WARNINGS_${imported.warnings.size}" }
                check(imported.snapshot == snapshot) { "FIRST_IMPORT_SNAPSHOT_CHANGED_SEE_SAFE_DIAGNOSTICS" }
                store.save(imported.snapshot); controller().acknowledge(imported)
                NativeTranscriptStore(isolated).save(deleteId,
                    RuntimeModel("deepseek", "offline-fixture", "Offline fixture", "deepseek", emptyList()), 6L,
                    JSONObject().put("systemPrompt", "offline storage only").put("messages", JSONArray()
                        .put(JSONObject().put("role", "assistant").put("content", "owned raw model transcript"))), true)
                check(versions.all { File(rawCopies, "${hash(it)}.json").isFile })
                check(File(protocolCopies, "${hash(deleteId)}.json").isFile)
                JSONObject().put("linkedVersions", versions.size).put("nativeSessions", listed.size)
            }
            checkCase("durable explicit deletion intent binds all versions without removing any history") {
                snapshot = snapshot.copy(conversations = snapshot.conversations.map {
                    if (it.id == deleteId) it.copy(archivedAtMillis = System.currentTimeMillis()) else it
                }, selectedConversationId = keepId)
                store.save(snapshot)
                val archived = snapshot.conversations.single { it.id == deleteId }
                intent = controller().preparePermanentDeletion(archived, "confirmed-$id")
                check(intent.sharedSessionIds.toSet() == versions && intent.requiresDsh && intent.phase == "prepared")
                check(controller().preparePermanentDeletion(archived, "repeat-$id") == intent)
                check(bridge.listArtifacts(sessions).getJSONArray("items").length() == 4)
                check(store.initialize()?.conversations?.contains(archived) == true)
                check(runCatching { controller().read(snapshot) }.isFailure)
                JSONObject().put("stableRequestId", intent.requestId).put("sharedVersions", intent.sharedSessionIds.size)
                    .put("appArchiveStillPresent", true).put("prepareMutatesNativeHistory", false)
            }
            checkCase("actual exclusive bridge deletes every version before the app index can commit") {
                receipt = controller().deletePermanentHistory(intent)
                check(receipt.sharedSessionIds == intent.sharedSessionIds)
                check(controller().pendingPermanentDeletions().single().phase == "native_deleted")
                check(store.initialize()?.conversations?.any { it.id == deleteId } == true)
                val survivors = bridge.listArtifacts(sessions).getJSONArray("items").remoteObjects(1000)
                check(survivors.map { it.getJSONObject("header").getString("id") } == listOf(keepSessionId))
                check(DshSharedHistoryAdapter.decode(bridge.readArtifact(sessions, keepSessionId)).compatibleMessages
                    .map { it.text } == snapshot.messages.getValue(keepId).map { it.text })
                check(runCatching { controller().acknowledgePermanentDeletion(receipt) }.isFailure)
                JSONObject().put("physicallyRemovedVersions", versions.size).put("unrelatedNativeHistoryPreserved", true)
                    .put("phoneIndexWaitsForCommit", true)
            }
            checkCase("real AtomicFile index commit precedes exact cache cleanup and directory fsync") {
                val committed = store.commitPermanentDeletion(receipt)
                check(committed.conversations.none { it.id == deleteId } && committed.messages[deleteId] == null)
                check(committed.conversations.single().id == keepId && committed.selectedConversationId == keepId)
                controller().acknowledgePermanentDeletion(receipt)
                for (sessionId in versions) for (suffix in listOf(".json", ".json.bak", ".json.new"))
                    check(!File(rawCopies, hash(sessionId) + suffix).exists())
                for (suffix in listOf(".json", ".json.bak", ".json.new"))
                    check(!File(protocolCopies, hash(deleteId) + suffix).exists())
                check(File(rawCopies, "${hash(keepSessionId)}.json").isFile)
                check(controller().pendingPermanentDeletions().isEmpty())
                JSONObject().put("phoneConversationRemoved", true).put("ownedRawCopiesRemoved", true)
                    .put("unrelatedRawCopyPreserved", true).put("cleanupAcknowledged", true)
            }
            checkCase("cold Controller and Store cannot reimport deleted history or save it from a stale snapshot") {
                val coldStore = WorkspaceStore(isolated)
                coldStore.save(snapshot)
                val coldSnapshot = checkNotNull(coldStore.initialize())
                check(coldSnapshot.conversations.map { it.id } == listOf(keepId) && coldSnapshot.messages[deleteId] == null)
                val coldBridge = DshSharedPersistenceBridge(isolated, runtime, installer)
                val coldController = LocalDshHistoryController(isolated, coldBridge)
                val mappingBeforeRead = checkNotNull(RemoteDshJsonFile(File(files, "local-dsh-history/links.json"),
                    4 * 1024 * 1024).read())
                val imported = coldController.read(coldSnapshot)
                report.put("coldImportDiagnostics", importDiagnostics(coldSnapshot, imported, mappingBeforeRead,
                    rawCopies, listOf(keepSessionId))); persist()
                check(imported.warnings.isEmpty()) { "UNEXPECTED_COLD_IMPORT_WARNINGS_${imported.warnings.size}" }
                check(imported.snapshot == coldSnapshot) { "COLD_IMPORT_SNAPSHOT_CHANGED_SEE_SAFE_DIAGNOSTICS" }
                coldStore.save(imported.snapshot); coldController.acknowledge(imported)
                val published = coldController.publish(checkNotNull(coldStore.initialize()))
                check(published.warnings.isEmpty() && published.selectedSessionId == keepSessionId)
                check(coldBridge.listArtifacts(sessions).getJSONArray("items").length() == 1)
                check(coldController.deletePermanentHistory(intent) == receipt)
                coldStore.commitPermanentDeletion(receipt); coldController.acknowledgePermanentDeletion(receipt)
                check(coldController.pendingPermanentDeletions().isEmpty())
                JSONObject().put("coldNativeSessionCount", 1).put("staleSnapshotResurrection", false)
                    .put("coldReadResurrection", false).put("sameIntentRetryIdempotent", true)
            }
            report.put("passed", true).put("finishedAt", System.currentTimeMillis())
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                withTimeout(15_000) { while (runtime.hasProcessLease(files) || runtime.hasProcessLease(vault)) delay(100) }
                check(files.deleteRecursively() && vault.deleteRecursively() && cache.deleteRecursively())
                report.put("testStoresRemoved", true).put("testRuntimeLeasesReleased", true)
                persist()
            }
        }
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    /** Structure and opaque digests only: no body, title, path, credential,
     * protocol context or exception text is copied into the test report. */
    private fun importDiagnostics(expected: WorkspaceSnapshot, imported: LocalDshHistoryImport,
        before: JSONObject, cache: File, sessionIds: Collection<String>): JSONObject {
        fun snapshotSummary(value: WorkspaceSnapshot): JSONObject = JSONObject()
            .put("workspaceIds", JSONArray(value.workspaces.map { hash(it.id) }))
            .put("conversationIds", JSONArray(value.conversations.map { hash(it.id) }))
            .put("messageKeys", JSONArray(value.messages.keys.sorted().map(::hash)))
            .put("selectedWorkspaceId", hash(value.selectedWorkspaceId))
            .put("selectedConversationId", hash(value.selectedConversationId))
            .put("conversations", JSONArray(value.conversations.map { conversation -> JSONObject()
                .put("id", hash(conversation.id)).put("workspaceId", hash(conversation.workspaceId))
                .put("archived", conversation.archivedAtMillis != null)
                .put("messageCount", value.messages[conversation.id].orEmpty().size)
                .put("nativeDigest", LocalDshHistoryController.fingerprint(conversation, value.messages[conversation.id].orEmpty()))
                .put("messages", JSONArray(value.messages[conversation.id].orEmpty().map { message -> JSONObject()
                    .put("id", message.id).put("role", message.role).put("textDigest", hash(message.text))
                    .put("modelInputPresent", message.modelInput != null)
                    .put("modelInputDigest", message.modelInput?.let(::hash) ?: JSONObject.NULL)
                    .put("activityPresent", message.streamSnapshot != null)
                    .put("activityDigest", message.streamSnapshot?.let { remoteJsonHash(AssistantStreamSnapshotJson.encode(it)) }
                        ?: JSONObject.NULL).put("attachmentCount", message.attachmentIds.size) })) }))
        val priorLinks = before.getJSONObject("links")
        val afterLinks = imported.journal.getJSONObject("links")
        val artifacts = JSONArray(sessionIds.sorted().map { id ->
            val prior = priorLinks.optJSONObject(id)
            val after = afterLinks.optJSONObject(id)
            val item = JSONObject().put("sessionId", hash(id))
                .put("nativeIdBefore", prior?.optString("nativeId")?.let(::hash) ?: JSONObject.NULL)
                .put("nativeIdAfter", after?.optString("nativeId")?.let(::hash) ?: JSONObject.NULL)
                .put("nativeIdChanged", prior?.optString("nativeId") != after?.optString("nativeId"))
                .put("fingerprintVersionBefore", prior?.optInt("artifactFingerprintVersion", -1) ?: -1)
                .put("fingerprintVersionAfter", after?.optInt("artifactFingerprintVersion", -1) ?: -1)
            val owned = RemoteDshJsonFile(File(cache, "${hash(id)}.json"), 25 * 1024 * 1024).read()
            item.put("cachedArtifactPresent", owned != null)
            if (owned != null) {
                val full = remoteJsonHash(owned)
                val writeEnvelope = remoteJsonHash(JSONObject().put("header", owned.getJSONObject("header"))
                    .put("events", owned.getJSONArray("events")))
                item.put("inheritedEventCountPresent", owned.has("inheritedEventCount"))
                    .put("inheritedEventCount", owned.optLong("inheritedEventCount", -1))
                    .put("readEnvelopeDigest", full).put("writeEnvelopeDigest", writeEnvelope)
                    .put("readEnvelopeMatchesBefore", full == prior?.optString("artifactFingerprint"))
                    .put("writeEnvelopeMatchesBefore", writeEnvelope == prior?.optString("artifactFingerprint"))
                    .put("readEnvelopeMatchesAfter", full == after?.optString("artifactFingerprint"))
            }
            item
        })
        return JSONObject().put("exactSnapshotEqual", expected == imported.snapshot)
            .put("warningCount", imported.warnings.size).put("warningDigests", JSONArray(imported.warnings.map(::hash)))
            .put("conflictWarningCount", imported.warnings.count { it.contains("两个版本") })
            .put("expected", snapshotSummary(expected)).put("actual", snapshotSummary(imported.snapshot))
            .put("artifacts", artifacts)
    }
}
