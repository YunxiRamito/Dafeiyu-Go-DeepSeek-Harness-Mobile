package com.dafeiyu.mobile.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android instrumentation uses real Android Keystore, AtomicFile and
 * production ports; every synthetic secret stays
 * in UUID-owned stores. The DSH case additionally uses the actual installed
 * fixed bundle, its storage helper and authenticated loopback settings RPC.
 * No prompt/model/OAuth request, permission grant, install or network download.
 * Run individual methods with tools/run-official-credentials-device.ps1.
 */
@RunWith(AndroidJUnit4::class)
class OfficialCredentialDeviceSmoke {
    @Test
    fun nativeVaultRoundtripAndActualAtomicFileRecovery(): Unit = runBlocking {
        owned("native") { fixture, report ->
            val context = fixture.context("roundtrip")
            val providers = ModelProviderStore(context)
            val balance = BalanceStore(context)
            val binding = binding(context, providers, balance)
            val first = synthetic("first")
            val second = synthetic("second")
            var saveId = ""
            report.case("real-platform-save-and-reopen-both-vaults") {
                val numeric = JSONObject().put("revision", 1L)
                val numericReopened = JSONObject(numeric.toString())
                report.document.put("platformJsonNumberRepresentation", JSONObject()
                    .put("writtenType", numeric.get("revision").javaClass.simpleName)
                    .put("reopenedType", numericReopened.get("revision").javaClass.simpleName)
                    .put("journalSemanticEquality", OfficialCredentialJournalCodec.same(numeric, numericReopened)))
                report.persist()
                check(OfficialCredentialJournalCodec.same(numeric, numericReopened))
                check(!OfficialCredentialJournalCodec.same(JSONObject().put("revision", 9007199254740992L),
                    JSONObject().put("revision", 9007199254740993L)))
                val saved = nativeCommitted(binding.coordinator().beginNative(first))
                saveId = saved.transactionId
                requirePair(context, first, saveId)
                check(saved.dshPublicationPending && !binding.hasPending() && binding.hasDeferredPublication())
                check(!binding.requiresDshRecovery())
                val evidence = platformEvidence().put("transactionId", saveId)
                    .put("dshContactRequired", false).put("deferredPublication", true)
                for (file in encryptedFiles(context)) assertEncrypted(file, first)
                evidence
            }
            report.case("deferred-native-read-is-admitted-but-dsh-entry-is-refused") {
                val reopened = binding(context)
                var admitted = false
                reopened.withNativeReady { admitted = true; check(BalanceStore(context).readApiKeyForRequest() == first) }
                check(admitted)
                var dshAdmitted = false
                expectFailure { reopened.withDshReady { dshAdmitted = true } }
                check(!dshAdmitted)
                JSONObject().put("nativeAdmitted", true).put("dshAdmitted", false)
            }
            report.case("real-platform-replacement-has-new-durable-receipts") {
                val replaced = nativeCommitted(binding(context).coordinator().beginNative(second))
                check(replaced.transactionId != saveId)
                requirePair(context, second, replaced.transactionId)
                saveId = replaced.transactionId
                for (file in encryptedFiles(context)) {
                    assertEncrypted(file, first)
                    assertEncrypted(file, second)
                }
                JSONObject().put("transactionId", saveId).put("oldSecretAbsentFromCiphertext", true)
            }
            report.case("real-android-atomicfile-backup-restores-authenticated-ciphertexts") {
                val expectedJournal = AndroidOfficialCredentialJournal(context).read()
                val files = encryptedFiles(context)
                val originals = files.associateWith { it.readBytes() }
                // Exercise Android AtomicFile.openRead's legacy .bak recovery,
                // not a JVM stand-in and not a simulated process restart.
                for (file in files) {
                    File(file.path + ".bak").writeBytes(originals.getValue(file))
                    file.writeBytes(byteArrayOf(0x51, 0x41))
                }
                requirePair(context, second, saveId)
                check(OfficialCredentialJournalCodec.same(AndroidOfficialCredentialJournal(context).read(), expectedJournal))
                for (file in files) {
                    check(file.readBytes().contentEquals(originals.getValue(file)))
                    check(!File(file.path + ".bak").exists())
                }
                JSONObject().put("realAtomicFile", true).put("restoredAuthenticatedFiles", files.size)
                    .put("processRestartClaim", false)
            }
            report.case("transactional-delete-persists-tombstones-and-retains-platform-aliases") {
                val removed = nativeCommitted(binding(context).coordinator().beginNative(null))
                requirePair(context, null, removed.transactionId)
                check(!ModelProviderStore(context).hasCredential("deepseek") && !BalanceStore(context).hasKey())
                check(encryptedFiles(context).all { it.isFile })
                check(AndroidOfficialCredentialJournal(context).read().getJSONObject("nativeReceipt").getBoolean("keyRemoved"))
                platformEvidence().put("transactionId", removed.transactionId).put("tombstonesReopened", true)
                    .put("clearApiKeyNeverCalled", true)
            }
        }
    }

    @Test
    fun persistedRecoveryGatesAndExplicitLegacyResolution(): Unit = runBlocking {
        owned("recovery") { fixture, report ->
            report.case("lost-journal-ack-after-real-fsync-reopens-as-pending-and-recovers") {
                val context = fixture.context("lost-ack")
                val old = synthetic("old")
                val desired = synthetic("recovered")
                nativeCommitted(binding(context).coordinator().beginNative(old))
                val journal = AndroidOfficialCredentialJournal(context)
                val lostAck = LostJournalAcknowledgement(journal)
                val deniedDsh = DeniedDshPort()
                val failed = transaction(context, lostAck, deniedDsh).beginNative(desired)
                check(failed is OfficialCredentialOutcome.Pending && lostAck.injected)
                val reopenedJournal = AndroidOfficialCredentialJournal(context)
                check(reopenedJournal.read().getJSONObject("pending").getString("step") == "NATIVE_PROVIDER_CONFIRMED")
                check(ModelProviderStore(context).officialCredentialSnapshot().key == desired)
                check(BalanceStore(context).readApiKeyForRequest() == old)
                val controller = ModelRuntimeController.get(context)
                try {
                    assertRecoveryBlocked(reopenedJournal)
                    val failure = expectFailure { controller.refresh() }
                    check(failure is ModelRuntimeException && failure.code == "CREDENTIAL_RECOVERY_REQUIRED")
                    val recovered = nativeCommitted(transaction(context, reopenedJournal, deniedDsh).recover())
                    requirePair(context, desired, recovered.transactionId)
                    val gate = JournalModelRuntimeRecoveryAccess(AndroidOfficialCredentialJournal(context))
                    gate.withNativeReady { check(ModelProviderStore(context).hasCredential("deepseek")) }
                    controller.refresh()
                    check(!controller.state.value.workerRunning && deniedDsh.attempts == 0)
                    JSONObject().put("injectedFault", "lost acknowledgement after real AtomicFile CAS completed")
                        .put("transactionId", recovered.transactionId).put("controllerRecoveryGate", true)
                        .put("actualProcessRestart", false).put("dshCalls", deniedDsh.attempts)
                } finally {
                    withContext(NonCancellable) { controller.shutdown() }
                }
            }
            for (choice in OfficialNativeCredentialChoice.entries) {
                report.case("explicit-legacy-${choice.name.lowercase()}-resolves-real-vault-disagreement") {
                    val context = fixture.context("legacy-${choice.name.lowercase()}")
                    val providerValue = synthetic("legacy-provider")
                    val balanceValue = synthetic("legacy-balance")
                    val providers = ModelProviderStore(context)
                    val balance = BalanceStore(context)
                    providers.upsert(ModelProviderConfig.DEEPSEEK, apiKey(providerValue))
                    balance.setApiKey(balanceValue)
                    val before = encryptedFiles(context).filter(File::isFile).associateWith { it.readBytes() }
                    val coordinator = binding(context, providers, balance).coordinator()
                    val refused = coordinator.beginNative(synthetic("unexpected"))
                    check(refused is OfficialCredentialOutcome.Pending && refused.code == "NATIVE_VAULT_CONFLICT")
                    check(!coordinator.hasPending())
                    before.forEach { (file, bytes) -> check(file.readBytes().contentEquals(bytes)) }
                    val chosen = when (choice) {
                        OfficialNativeCredentialChoice.PROVIDER -> providerValue
                        OfficialNativeCredentialChoice.BALANCE -> balanceValue
                        OfficialNativeCredentialChoice.CLEAR -> null
                    }
                    val committed = nativeCommitted(coordinator.beginNativeResolution(choice,
                        providers.officialCredentialSnapshot().revision, balance.officialCredentialSnapshot().revision))
                    requirePair(context, chosen, committed.transactionId)
                    JSONObject().put("choice", choice.name).put("implicitOverwriteRefused", true)
                        .put("transactionId", committed.transactionId).put("nativeTombstone", chosen == null)
                }
            }
            report.case("stale-explicit-choice-cannot-overwrite-a-new-real-provider-save") {
                val context = fixture.context("stale-choice")
                val providers = ModelProviderStore(context)
                val balance = BalanceStore(context)
                providers.upsert(ModelProviderConfig.DEEPSEEK, apiKey(synthetic("stale-before")))
                balance.setApiKey(synthetic("stale-balance"))
                val captured = providers.officialCredentialSnapshot().revision
                val capturedBalance = balance.officialCredentialSnapshot().revision
                providers.upsert(ModelProviderConfig.DEEPSEEK, apiKey(synthetic("stale-newer")))
                val before = encryptedFiles(context).filter(File::isFile).associateWith { it.readBytes() }
                val coordinator = binding(context, providers, balance).coordinator()
                val result = coordinator.beginNativeResolution(OfficialNativeCredentialChoice.PROVIDER, captured, capturedBalance)
                check(result is OfficialCredentialOutcome.Pending && result.code == "NATIVE_CAS_CONFLICT")
                check(!coordinator.hasPending() && !coordinator.hasDeferredPublication())
                before.forEach { (file, bytes) -> check(file.readBytes().contentEquals(bytes)) }
                JSONObject().put("staleChoiceRefused", true).put("vaultCiphertextsUnchanged", true)
            }
        }
    }

    /** Successful prepare deliberately retains ONLY the UUID fixture for a
     * second instrumentation process. Runner always invokes cleanup in finally. */
    @Test
    fun prepareDurableFailureForActualProcessRestart(): Unit = runBlocking {
        requireArgument("official_restart_phase", "prepare")
        owned("restart", retainOnSuccess = true) { fixture, report ->
            report.case("prepare-real-durable-split-vault-and-pending-journal") {
                val context = fixture.context("phase-data")
                nativeCommitted(binding(context).coordinator().beginNative(synthetic("restart-before")))
                val lostAck = LostJournalAcknowledgement(AndroidOfficialCredentialJournal(context))
                val denied = DeniedDshPort()
                val result = transaction(context, lostAck, denied).beginNative(synthetic("restart-after"))
                check(result is OfficialCredentialOutcome.Pending && lostAck.injected && denied.attempts == 0)
                val state = AndroidOfficialCredentialJournal(context).read().getJSONObject("pending")
                check(state.getString("step") == "NATIVE_PROVIDER_CONFIRMED")
                assertRecoveryBlocked(AndroidOfficialCredentialJournal(context))
                // Persist ciphertext fingerprints only; never key material or
                // a plaintext-secret digest in marker/report/ADB output.
                fixture.record("preparePid", Process.myPid())
                fixture.record("transactionId", state.getString("id"))
                fixture.record("encryptedFiles", encryptedFileHashes(context))
                JSONObject().put("preparePid", Process.myPid()).put("durableJournalPhase", state.getString("step"))
                    .put("transactionId", state.getString("id")).put("ciphertextFiles", encryptedFiles(context).size)
                    .put("retainedForNextInstrumentation", true).put("linuxProcessesStarted", 0)
            }
        }
    }

    @Test
    fun recoverDurableFailureAfterActualProcessRestart(): Unit = runBlocking {
        requireArgument("official_restart_phase", "recover")
        owned("restart", reopen = true) { fixture, report ->
            report.case("new-android-process-reads-unchanged-real-ciphertexts-and-blocks-use") {
                val marker = fixture.marker()
                val preparePid = marker.getInt("preparePid")
                check(preparePid > 0 && preparePid != Process.myPid()) { "QA_REQUIRES_DIFFERENT_PROCESS" }
                val context = fixture.context("phase-data", reopen = true)
                check(sameJson(marker.getJSONObject("encryptedFiles"), encryptedFileHashes(context)))
                val journal = AndroidOfficialCredentialJournal(context)
                check(journal.read().getJSONObject("pending").getString("id") == marker.getString("transactionId"))
                assertRecoveryBlocked(journal)
                JSONObject().put("preparePid", preparePid).put("recoveryPid", Process.myPid())
                    .put("actualProcessRestart", true).put("ciphertextsUnchangedAcrossRestart", true)
            }
            report.case("new-android-process-recovers-real-native-receipts-without-dsh") {
                val context = fixture.context("phase-data", reopen = true)
                val denied = DeniedDshPort()
                val recovered = nativeCommitted(transaction(context, AndroidOfficialCredentialJournal(context), denied).recover())
                check(recovered.transactionId == fixture.marker().getString("transactionId"))
                requirePair(context, synthetic("restart-after"), recovered.transactionId)
                val gate = JournalModelRuntimeRecoveryAccess(AndroidOfficialCredentialJournal(context))
                gate.withNativeReady { check(BalanceStore(context).hasKey()) }
                check(denied.attempts == 0)
                JSONObject().put("transactionId", recovered.transactionId).put("dshCalls", denied.attempts)
                    .put("nativeUseAdmittedAfterRecovery", true).put("actualProcessRestart", true)
            }
        }
    }

    @Test
    fun deferredPublicationWithActualPinnedDshAndRealCas(): Unit = runBlocking {
        requireArgument("official_real_dsh", "true")
        val expectedManifest = arguments().getString("official_dsh_manifest")
        require(expectedManifest?.matches(Regex("[a-f0-9]{64}")) == true) { "QA_PINNED_MANIFEST_REQUIRED" }
        owned("dsh") { fixture, report ->
            val context = fixture.context("live-home")
            val target = target()
            val runtime = LinuxRuntimeManager(target)
            val installer = DshRuntimeInstaller(target, runtime)
            val slot = checkNotNull(installer.current()) { "QA_ALREADY_INSTALLED_DSH_REQUIRED" }
            check(slot.manifest.dshVersion == PINNED_DSH && slot.manifest.abi == "x86_64" && slot.manifest.digest() == expectedManifest)
            check(runtime.managedProcesses.value.isEmpty() && !runtime.hasProcessLease(target.filesDir) && !runtime.hasProcessLease(target.noBackupFilesDir)) {
                "QA_RUNTIME_BUSY_DO_NOT_STOP_USER_SERVICE"
            }
            val bridge = DshSharedPersistenceBridge(context, runtime, installer)
            val binding = AndroidOfficialCredentialBinding(context, ModelProviderStore(context), BalanceStore(context), bridge)
            val rpc = DshLocalConfigurationRpc()
            var service = DshRuntimeManager(context, runtime, installer)
            var access = ""
            var firstReference = ""
            var secondReference = ""
            var removedReference = ""
            var firstPid = 0
            try {
                report.case("offline-native-save-needs-no-dsh-process-and-blocks-dsh-entry") {
                    nativeCommitted(binding.coordinator().beginNative(synthetic("dsh-first")))
                    check(binding.hasDeferredPublication() && runtime.managedProcesses.value.isEmpty())
                    var admitted = false
                    expectFailure { binding.withDshReady { admitted = true } }
                    check(!admitted)
                    JSONObject().put("nativeCommittedWhileDshStopped", true).put("deferredPublication", true)
                }
                report.case("actual-installed-fixed-linux-dsh-starts-in-isolated-home") {
                    fixture.record("dshLeaseMayBeActive", true)
                    val workspace = File(context.filesDir, "workspace").apply { check(mkdir()) }
                    val started = withTimeout(90_000) { service.start(workspace) }
                    check(started.status == DshServiceStatus.RUNNING && started.healthCode == 200 && started.version == PINNED_DSH)
                    access = checkNotNull(started.accessUrl)
                    firstPid = checkNotNull(started.pid)
                    check(firstPid > 0)
                    fixture.record("dshPid", firstPid)
                    JSONObject().put("dshVersion", started.version).put("nodeVersion", slot.manifest.nodeVersion)
                        .put("runtimeManifestSha256", expectedManifest).put("pid", firstPid).put("healthCode", started.healthCode)
                        .put("homeIsUuidOwned", true).put("actualProcess", true)
                }
                report.case("actual-helper-and-live-settings-cas-publish-native-deferred-key") {
                    val committed = committed(binding.coordinator(access).publishDeferred())
                    firstReference = committed.reference
                    verifyLiveReceipt(context, bridge, rpc, access, firstReference, synthetic("dsh-first"))
                    binding.withDshReady { Unit }
                    JSONObject().put("transactionId", committed.transactionId).put("reference", committed.reference)
                        .put("realStorageHelper", true).put("realAuthenticatedLoopbackSettingsRpc", true)
                }
                report.case("actual-helper-rejects-stale-credential-cas-with-no-partial-overwrite") {
                    val before = bridge.readCredentialDocument()
                    val nextRef = "DAFEIYU_PROVIDER_QA_${UUID.randomUUID().toString().replace("-", "") }"
                    val staleRef = "DAFEIYU_PROVIDER_QA_${UUID.randomUUID().toString().replace("-", "") }"
                    val staleUpdates = DshCandidateCredentialStaging.prepare(before,
                        JSONArray().put(JSONObject().put("ref", staleRef).put("value", synthetic("cas-stale"))), before.getString("revision"))
                    bridge.stageCredentialReferences(JSONArray().put(JSONObject().put("ref", nextRef).put("value", synthetic("cas-live"))),
                        before.getString("revision"))
                    val newer = bridge.readCredentialDocument()
                    check(newer.getString("revision") != before.getString("revision"))
                    check(expectFailure { bridge.writeCredentialDocument(staleUpdates) } is DshCredentialRevisionConflictException)
                    val after = bridge.readCredentialDocument()
                    check(after.getString("revision") == newer.getString("revision"))
                    check(after.getJSONObject("refs").getString(nextRef) == synthetic("cas-live") && !after.getJSONObject("refs").has(staleRef))
                    check(after.getJSONObject("refs").getString(firstReference) == synthetic("dsh-first"))
                    JSONObject().put("actualHelperStaleCasRejected", true).put("newerDocumentUnchanged", true)
                        .put("failedCandidateAbsent", true)
                }
                report.case("actual-live-settings-refuse-stale-revision-without-overwriting-new-value") {
                    val before = officialNamespace(rpc.call(access, "settings/describe", JSONObject()))
                    val alternate = "DAFEIYU_PROVIDER_QA_${UUID.randomUUID().toString().replace("-", "") }"
                    val credentials = bridge.readCredentialDocument()
                    bridge.stageCredentialReferences(JSONArray().put(JSONObject().put("ref", alternate).put("value", synthetic("dsh-first"))),
                        credentials.getString("revision"))
                    mutate(rpc, access, before, alternate)
                    val newer = officialNamespace(rpc.call(access, "settings/describe", JSONObject()))
                    check(newer.getLong("revision") > before.getLong("revision") && selectedReference(newer) == alternate)
                    check(expectFailure { mutate(rpc, access, before, firstReference) } is DshSettingsRevisionConflictException)
                    val after = officialNamespace(rpc.call(access, "settings/describe", JSONObject()))
                    check(after.getLong("revision") == newer.getLong("revision") && selectedReference(after) == alternate)
                    mutate(rpc, access, after, firstReference)
                    JSONObject().put("namespace", before.getString("ns")).put("oldRevision", before.getLong("revision"))
                        .put("newRevision", newer.getLong("revision")).put("staleRejected", true).put("newerValuePreserved", true)
                }
                report.case("actual-dsh-replacement-and-delete-retain-old-references-and-native-tombstones") {
                    nativeCommitted(binding.coordinator().beginNative(synthetic("dsh-second")))
                    val replacement = committed(binding.coordinator(access).publishDeferred())
                    secondReference = replacement.reference
                    verifyLiveReceipt(context, bridge, rpc, access, secondReference, synthetic("dsh-second"))
                    check(replacement.oldReferenceRetained && bridge.readCredentialDocument().getJSONObject("refs")
                        .getString(firstReference) == synthetic("dsh-first"))
                    nativeCommitted(binding.coordinator().beginNative(null))
                    val removed = committed(binding.coordinator(access).publishDeferred())
                    removedReference = removed.reference
                    verifyLiveReceipt(context, bridge, rpc, access, removedReference, null)
                    val refs = bridge.readCredentialDocument().getJSONObject("refs")
                    check(refs.getString(firstReference) == synthetic("dsh-first") && refs.getString(secondReference) == synthetic("dsh-second"))
                    check(!refs.has(removedReference) && removed.oldReferenceRetained)
                    platformEvidence().put("reference", removedReference).put("oldReferencesRetained", true)
                        .put("missingSelectedReferenceRepresentsDeletion", true)
                }
                report.case("real-dsh-process-restart-reopens-committed-settings-and-deletion") {
                    service.stop(); service.close()
                    waitLeases(runtime, fixture)
                    check(installer.current()?.manifest?.digest() == expectedManifest)
                    service = DshRuntimeManager(context, runtime, installer)
                    val started = withTimeout(90_000) { service.start(File(context.filesDir, "workspace")) }
                    check(started.status == DshServiceStatus.RUNNING && started.healthCode == 200)
                    val restartedPid = checkNotNull(started.pid)
                    check(restartedPid > 0 && restartedPid != firstPid)
                    access = checkNotNull(started.accessUrl)
                    fixture.record("dshPid", restartedPid)
                    verifyLiveReceipt(context, bridge, rpc, access, removedReference, null, sameServiceProcess = false)
                    JSONObject().put("oldPid", firstPid).put("newPid", restartedPid).put("actualDshProcessRestart", true)
                        .put("deletionReopened", true).put("runtimeSlotUnchanged", true).put("settingsRevisionScope", "service process")
                }
                report.case("ordinary-provider-actual-publish-replace-delete-and-reverse-read-retain-old-refs") {
                    val store = ModelProviderStore(context)
                    val adapter = DshSharedConfigurationAdapter(context, store, bridge)
                    val provider = ModelProviderConfig("qa-credential-provider", "QA synthetic provider", api = "openai-completions",
                        baseUrl = "https://qa-credential.invalid/v1", dshRoute = "qa-credential-provider",
                        modelsJson = "[{\"id\":\"qa-model\",\"name\":\"QA model\",\"reasoning\":false,\"input\":[\"text\"],\"contextWindow\":8192,\"maxTokens\":1024}]")
                    suspend fun configuredReference(): String {
                        val settings = rpc.call(access, "settings/describe", JSONObject())
                        check(selectedReference(officialNamespace(settings)) == removedReference)
                        return DshSharedConfigurationMapping.namespace(settings, "llm-pi-ai")
                            .getJSONObject("value").getJSONObject("providers").getJSONObject(provider.dshRoute!!).getString("apiKeyEnv")
                    }
                    store.upsert(provider, apiKey(synthetic("ordinary-first")))
                    adapter.writeToDsh(access)
                    val oldRef = configuredReference()
                    check(oldRef.startsWith("DAFEIYU_PROVIDER_") && bridge.readCredentialDocument().getJSONObject("refs")
                        .getString(oldRef) == synthetic("ordinary-first"))
                    store.upsert(provider, apiKey(synthetic("ordinary-second")))
                    adapter.writeToDsh(access)
                    val newRef = configuredReference()
                    check(newRef != oldRef)
                    val afterReplace = bridge.readCredentialDocument().getJSONObject("refs")
                    check(afterReplace.getString(oldRef) == synthetic("ordinary-first") && afterReplace.getString(newRef) == synthetic("ordinary-second"))
                    adapter.readFromDsh(access)
                    check(store.workerConfiguration().getJSONObject("credentials").getJSONObject(provider.id).getString("key") == synthetic("ordinary-second"))
                    check(!BalanceStore(context).hasKey() && !store.hasCredential("deepseek"))
                    store.persistWorkerCredential(provider.id, null, store.read().revision)
                    adapter.writeToDsh(access)
                    val deletedRef = configuredReference()
                    val afterDelete = bridge.readCredentialDocument().getJSONObject("refs")
                    check(deletedRef != newRef && !afterDelete.has(deletedRef))
                    check(afterDelete.getString(oldRef) == synthetic("ordinary-first") && afterDelete.getString(newRef) == synthetic("ordinary-second"))
                    adapter.readFromDsh(access)
                    check(!store.hasCredential(provider.id) && !store.hasCredential("deepseek") && !BalanceStore(context).hasKey())
                    check(selectedReference(officialNamespace(rpc.call(access, "settings/describe", JSONObject()))) == removedReference)
                    JSONObject().put("provider", provider.id).put("freshReferenceOnReplacement", true)
                        .put("missingReferenceOnDeletion", true).put("oldReferencesRetained", true)
                        .put("actualReverseRead", true).put("officialDeletionPreserved", true).put("providerRequests", 0)
                }
            } finally {
                withContext(NonCancellable) {
                    try {
                        service.stop(); service.close(); waitLeases(runtime, fixture)
                        fixture.record("dshLeaseMayBeActive", false)
                        check(installer.current()?.manifest?.digest() == expectedManifest) { "QA_RUNTIME_SLOT_CHANGED" }
                        report.document.put("ownedDshStopped", true).put("installedRuntimeSlotPreserved", true)
                    } finally { report.persist() }
                }
            }
        }
    }

    /** A failure-safe final runner phase. It only removes verified fixtures
     * for this run and refuses a possible active DSH mount. No shell rm. */
    @Test
    fun cleanupOwnedFixtures(): Unit = runBlocking {
        requireArgument("official_cleanup", "true")
        val target = target()
        val guard = CommonStateGuard(target)
        val report = Report(target, runId(), "cleanup")
        try {
            for (phase in PHASES) report.case("cleanup-$phase") {
                val fixture = OwnedFixture.open(target, runId(), phase, mustExist = false)
                if (fixture == null) JSONObject().put("alreadyAbsent", true)
                else {
                    fixture.cleanup()
                    JSONObject().put("uuidFixtureRemoved", true)
                }
            }
            guard.verify()
            report.document.put("passed", true).put("commonStatePreserved", true)
        } finally {
            report.document.put("completed", true).put("finishedAt", System.currentTimeMillis())
            report.persist()
        }
    }

    private suspend fun owned(phase: String, reopen: Boolean = false, retainOnSuccess: Boolean = false,
                              action: suspend (OwnedFixture, Report) -> Unit) {
        val target = target()
        val guard = CommonStateGuard(target)
        val report = Report(target, runId(), phase + if (reopen) "-recovered" else "")
        var fixture: OwnedFixture? = null
        var succeeded = false
        var failure: Throwable? = null
        try {
            fixture = if (reopen) checkNotNull(OwnedFixture.open(target, runId(), phase, mustExist = true))
                else OwnedFixture.create(target, runId(), phase)
            action(fixture, report)
            guard.verify()
            succeeded = true
        } catch (error: Throwable) {
            failure = error
            report.document.put("errorClass", error.javaClass.name)
        } finally {
            withContext(NonCancellable) {
                try {
                    if (retainOnSuccess && succeeded) report.document.put("retainedForRestartPhase", true).put("cleanupComplete", false)
                    else {
                        fixture?.cleanup()
                        report.document.put("cleanupComplete", true)
                    }
                    guard.verify()
                    report.document.put("commonStatePreserved", true).put("commonKeystoreAliasesPreserved", true)
                } catch (cleanupFailure: Throwable) {
                    succeeded = false
                    report.document.put("cleanupComplete", false).put("cleanupErrorClass", cleanupFailure.javaClass.name)
                    if (failure == null) failure = cleanupFailure
                } finally {
                    report.document.put("passed", succeeded).put("completed", true).put("finishedAt", System.currentTimeMillis())
                    report.persist()
                }
            }
        }
        // Never propagate a production exception message/stack/cause which
        // may embed credentials; per-case report keeps classes and safe facts.
        failure?.let { throw AssertionError("QA_OFFICIAL_CREDENTIAL_FAILURE:${phase}:${it.javaClass.simpleName}") }
    }

    private fun binding(context: Context, providers: ModelProviderStore = ModelProviderStore(context), balance: BalanceStore = BalanceStore(context)) =
        AndroidOfficialCredentialBinding(context, providers, balance, DshSharedPersistenceBridge(context))

    private fun transaction(context: Context, journal: OfficialCredentialJournalPort, dsh: OfficialDshCredentialPort): OfficialDeepSeekCredentialTransaction {
        val providers = ModelProviderStore(context)
        val balance = BalanceStore(context)
        return OfficialDeepSeekCredentialTransaction(journal, object : OfficialNativeCredentialPort {
            override suspend fun read() = providers.officialCredentialSnapshot()
            override suspend fun compareAndSet(expectedRevision: String, key: String?, transactionId: String) {
                providers.compareAndSetOfficialCredential(expectedRevision, key, transactionId)
            }
            override suspend fun confirmProjection(key: String?, transactionId: String, configurationDigest: String?) {
                providers.confirmOfficialCredentialProjection(key, transactionId, configurationDigest)
            }
        }, object : OfficialNativeCredentialPort {
            override suspend fun read() = balance.officialCredentialSnapshot()
            override suspend fun compareAndSet(expectedRevision: String, key: String?, transactionId: String) {
                balance.compareAndSetOfficialCredential(expectedRevision, key, transactionId)
            }
        }, dsh)
    }

    private fun requirePair(context: Context, desired: String?, id: String) {
        val provider = ModelProviderStore(context).officialCredentialSnapshot()
        val balance = BalanceStore(context).officialCredentialSnapshot()
        check(provider.key == desired && balance.key == desired && provider.transactionId == id && balance.transactionId == id) {
            "QA_NATIVE_PAIR_NOT_CONFIRMED"
        }
    }

    private suspend fun assertRecoveryBlocked(journal: AndroidOfficialCredentialJournal) {
        val gate = JournalModelRuntimeRecoveryAccess(journal)
        var readAdmitted = false
        val failure = expectFailure { gate.withNativeReady { readAdmitted = true } }
        check(failure is ModelRuntimeException && failure.code == "CREDENTIAL_RECOVERY_REQUIRED" && !readAdmitted)
        var callbackAdmitted = false
        expectFailure { gate.tryWithNativeReady { callbackAdmitted = true } }
        check(!callbackAdmitted)
    }

    private suspend fun verifyLiveReceipt(context: Context, bridge: DshSharedPersistenceBridge, rpc: DshLocalConfigurationRpc,
                                         access: String, reference: String, desired: String?, sameServiceProcess: Boolean = true) {
        val state = AndroidOfficialCredentialJournal(context).read()
        check(!state.has("pending") && !state.has("deferred"))
        val receipt = state.getJSONObject("receipt")
        check(receipt.getString("reference") == reference && receipt.getBoolean("keyRemoved") == (desired == null))
        requirePair(context, desired, receipt.getString("id"))
        val namespace = officialNamespace(rpc.call(access, "settings/describe", JSONObject()))
        // Fixed SettingsForms keeps revisions in a process-local Map. A new
        // PID starts from zero; durable user/effective selectors and vault
        // ownership still have to match the completed receipt exactly.
        check(namespace.getLong("revision") >= 0)
        if (sameServiceProcess) check(namespace.getLong("revision") >= receipt.getLong("settingsRevision"))
        check(namespace.getString("ns") == receipt.getString("namespace") &&
            selectedReference(namespace) == reference && namespace.getJSONObject("user").getString("apiKeyEnv") == reference)
        val refs = bridge.readCredentialDocument().getJSONObject("refs")
        check(if (desired == null) !refs.has(reference) else refs.getString(reference) == desired)
    }

    private fun officialNamespace(settings: JSONObject): JSONObject {
        check(settings.getBoolean("writable"))
        val items = settings.getJSONArray("namespaces")
        val matches = (0 until items.length()).map(items::getJSONObject).filter { it.optString("ns") in setOf("llm-deepseek", "llm-deepseek-api-key") }
        check(matches.size == 1) { "QA_OFFICIAL_NAMESPACE_AMBIGUOUS" }
        return matches.single()
    }

    private fun selectedReference(namespace: JSONObject): String = namespace.getJSONObject("value").getString("apiKeyEnv")

    private suspend fun mutate(rpc: DshLocalConfigurationRpc, access: String, namespace: JSONObject, reference: String): JSONObject =
        rpc.call(access, "settings/mutate", JSONObject().put("ns", namespace.getString("ns")).put("expectedRevision", namespace.getLong("revision"))
            .put("ops", JSONArray().put(JSONObject().put("op", "set").put("path", JSONArray().put("apiKeyEnv")).put("value", reference))))

    private suspend fun waitLeases(runtime: LinuxRuntimeManager, fixture: OwnedFixture) = withTimeout(20_000) {
        while (runtime.managedProcesses.value.isNotEmpty() || runtime.hasProcessLease(fixture.target.filesDir) ||
            runtime.hasProcessLease(fixture.target.noBackupFilesDir) || runtime.hasProcessLease(fixture.files) ||
            runtime.hasProcessLease(fixture.vault) || runtime.hasProcessLease(fixture.cache)) delay(100)
    }

    private class LostJournalAcknowledgement(private val actual: AndroidOfficialCredentialJournal) : OfficialCredentialJournalPort {
        var injected: Boolean = false
            private set
        override val transactionMutex: Mutex get() = actual.transactionMutex
        override fun read(): JSONObject = actual.read()
        override fun compareAndSet(expectedRevision: Long, replacement: JSONObject): JSONObject {
            val persisted = actual.compareAndSet(expectedRevision, replacement)
            if (!injected && persisted.optJSONObject("pending")?.optString("step") == "NATIVE_PROVIDER_CONFIRMED") {
                injected = true
                throw IOException("QA_INJECTED_LOST_ACK")
            }
            return persisted
        }
    }

    /** An explicit forbidden-call sentinel for offline paths, not a fake DSH
     * used to claim real service/CAS coverage. */
    private class DeniedDshPort : OfficialDshCredentialPort {
        var attempts = 0
            private set
        private fun forbidden(): Nothing { attempts++; error("QA_NATIVE_TEST_MUST_NOT_CONTACT_DSH") }
        override suspend fun describe(): JSONObject = forbidden()
        override suspend fun credentials(): JSONObject = forbidden()
        override suspend fun stageReferences(candidates: JSONArray, expectedRevision: String): JSONObject = forbidden()
        override suspend fun mutate(namespace: String, expectedRevision: Long, reference: String): JSONObject = forbidden()
    }

    private class Report(target: Context, run: String, phase: String) {
        private val directory = File(checkNotNull(target.getExternalFilesDir(null)), "qa/official-credential-device-$run")
        private val file = File(directory, "$phase.json")
        private val cases = JSONArray()
        val document: JSONObject = JSONObject().put("schema", 1).put("runId", run).put("phase", phase)
            .put("package", target.packageName).put("pid", Process.myPid()).put("sdk", Build.VERSION.SDK_INT)
            .put("abi", Build.SUPPORTED_ABIS.first()).put("startedAt", System.currentTimeMillis())
            .put("passed", false).put("completed", false).put("paidModelRequests", 0).put("modelRequests", 0)
            .put("remoteServiceRequests", 0).put("permissionGrants", 0).put("downloads", 0)
            .put("networkScope", if (phase == "dsh") "actual authenticated loopback DSH only" else "none")
            .put("results", cases)
        init {
            check(!Files.isSymbolicLink(directory.toPath()) && (directory.isDirectory || directory.mkdirs()))
            check(!file.exists() && !Files.isSymbolicLink(file.toPath())) { "QA_REPORT_ALREADY_EXISTS" }
            persist()
        }
        fun persist() { file.writeText(document.toString(2), Charsets.UTF_8) }
        suspend fun case(name: String, action: suspend () -> JSONObject) {
            val row = JSONObject().put("name", name).put("passed", false).put("startedAt", System.currentTimeMillis())
            cases.put(row); persist()
            try { row.put("evidence", withTimeout(180_000) { action() }).put("passed", true) }
            catch (error: Throwable) {
                row.put("errorClass", error.javaClass.name)
                if (error is SafeOutcomeFailure) row.put("outcome", error.outcome).put("code", error.code)
                row.put("codeLocations", JSONArray().also { locations ->
                    error.stackTrace.filter { it.className.startsWith("com.dafeiyu.mobile.") }.take(8).forEach { frame ->
                        locations.put(JSONObject().put("class", frame.className).put("method", frame.methodName)
                            .put("file", frame.fileName ?: "unknown").put("line", frame.lineNumber))
                    }
                })
                throw error
            }
            finally { row.put("finishedAt", System.currentTimeMillis()); persist() }
        }
    }

    private class CommonStateGuard(target: Context) {
        private val protected = listOf(
            File(target.noBackupFilesDir, "model-providers.v1.enc"), File(target.noBackupFilesDir, ".deepseek_api_key.v1.bin"),
            File(target.noBackupFilesDir, "official-deepseek-transaction.v1.enc"), File(target.filesDir, "dsh-provider-order/order.json"),
            File(target.filesDir, "dsh-home/.credentials.yaml"), File(target.filesDir, "dsh-home/settings.json"),
            File(target.filesDir, "dsh-home/cordis.patch.yml"), File(target.filesDir, "dsh-home/settings.yaml"),
            File(target.filesDir, "dsh-home/settings.yaml.imported"), File(target.filesDir, "dsh-home/profiles/web/cordis.patch.yml"),
            File(target.filesDir, "dsh-home/profiles/web/cordis.yml"), File(target.filesDir, "dsh-home/profiles/web/package.json"),
            File(target.filesDir, "linux-runtime/active.json"),
            File(target.filesDir, "dsh-runtime/active.json"), File(target.filesDir, "dsh-runtime/previous.json")
        ).flatMap { file -> listOf(file, File(file.path + ".bak"), File(file.path + ".new")) }
        // Digests are comparison-only and never emitted: protected files may
        // contain real user credentials even in the separate debug identity.
        private val before = protected.associateWith { fingerprint(it) }
        private val aliases = keystore().aliases().toList().toSet()
        fun verify() {
            before.forEach { (file, original) -> check(fingerprint(file) == original) { "QA_COMMON_STATE_CHANGED" } }
            check(keystore().aliases().toList().toSet().containsAll(aliases)) { "QA_COMMON_KEY_ALIAS_REMOVED" }
        }
    }

    private class OwnedFixture private constructor(val target: Context, val run: String, val phase: String,
                                                   val files: File, val vault: File, val cache: File) {
        private val markerFile = File(files, "owner.json")
        private val markerLock = Any()
        fun marker(): JSONObject = synchronized(markerLock) { JSONObject(markerFile.readText()).also { validateMarker(it) } }
        fun record(name: String, value: Any) = synchronized(markerLock) {
            val next = marker(); next.put(name, value); markerFile.writeText(next.toString(), Charsets.UTF_8)
        }
        fun context(name: String, reopen: Boolean = false): Context {
            require(name.matches(Regex("[a-z0-9-]{1,80}")))
            fun child(parent: File): File = File(parent, name).also {
                check(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile == parent.canonicalFile)
                check(if (reopen) it.isDirectory else !it.exists() && it.mkdir())
            }
            val ownedFiles = child(files); val ownedVault = child(vault); val ownedCache = child(cache)
            return object : ContextWrapper(target) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = ownedFiles
                override fun getNoBackupFilesDir(): File = ownedVault
                override fun getCacheDir(): File = ownedCache
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    require(name.matches(Regex("[A-Za-z0-9_.-]{1,128}")))
                    val scoped = "official-qa-$run-$phase-$name"
                    synchronized(markerLock) {
                        val next = marker(); val names = next.getJSONArray("preferences")
                        if ((0 until names.length()).none { names.getString(it) == scoped }) names.put(scoped)
                        markerFile.writeText(next.toString(), Charsets.UTF_8)
                    }
                    return target.getSharedPreferences(scoped, mode)
                }
            }
        }
        fun cleanup() {
            val proof = marker()
            check(!proof.optBoolean("dshLeaseMayBeActive")) { "QA_POSSIBLE_OWNED_DSH_LEASE_RETAIN_FOR_REVIEW" }
            val preferences = proof.getJSONArray("preferences")
            for (index in 0 until preferences.length()) {
                val name = preferences.getString(index)
                check(name.startsWith("official-qa-$run-$phase-"))
                check(target.deleteSharedPreferences(name)) { "QA_SCOPED_PREFERENCE_CLEANUP_FAILED" }
            }
            // Order preserves the ownership marker if vault/cache removal
            // fails. Files.walkFileTree does not follow nested symlinks.
            for (root in listOf(vault, cache, files)) {
                deleteOwned(root)
                val runContainer = checkNotNull(root.parentFile)
                check(runContainer.name == run && runContainer.parentFile?.name == CATEGORY &&
                    runContainer.canonicalPath == runContainer.absolutePath && !Files.isSymbolicLink(runContainer.toPath()))
                if (runContainer.isDirectory && runContainer.list()?.isEmpty() == true) Files.delete(runContainer.toPath())
            }
        }
        private fun validateMarker(proof: JSONObject) {
            check(proof.getInt("schema") == 1 && proof.getString("suite") == CATEGORY && proof.getString("runId") == run &&
                proof.getString("phase") == phase && proof.getString("package") == target.packageName)
            check(proof.getString("files") == files.absolutePath && proof.getString("vault") == vault.absolutePath && proof.getString("cache") == cache.absolutePath)
        }
        private fun deleteOwned(root: File) {
            val base = when (root) { files -> target.filesDir; vault -> target.noBackupFilesDir; else -> target.cacheDir }
            val expected = File(base.canonicalFile, "$CATEGORY/$run/$phase")
            check(root.absolutePath == expected.absolutePath && root.canonicalPath == expected.absolutePath && !Files.isSymbolicLink(root.toPath()))
            if (!root.exists()) return
            val path = root.toPath()
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    check(file.startsWith(path)); Files.delete(file); return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                    if (error != null) throw error
                    check(directory.startsWith(path)); Files.delete(directory); return FileVisitResult.CONTINUE
                }
            })
        }
        companion object {
            fun create(target: Context, run: String, phase: String): OwnedFixture {
                val fixture = paths(target, run, phase)
                val roots = listOf(fixture.files, fixture.vault, fixture.cache)
                for (root in roots) {
                    check(root.canonicalPath == root.absolutePath && !Files.isSymbolicLink(root.toPath()) && !root.exists())
                }
                val created = mutableListOf<File>()
                try {
                    for (root in roots) { check(root.mkdirs()); created += root }
                    fixture.markerFile.writeText(JSONObject().put("schema", 1).put("suite", CATEGORY).put("runId", run)
                        .put("phase", phase).put("package", target.packageName).put("files", fixture.files.absolutePath)
                        .put("vault", fixture.vault.absolutePath).put("cache", fixture.cache.absolutePath)
                        .put("preferences", JSONArray()).put("dshLeaseMayBeActive", false).toString(), Charsets.UTF_8)
                } catch (failure: Throwable) {
                    // These roots were just created after the all-absent
                    // preflight; delete only those proven-owned paths.
                    for (root in created.asReversed()) fixture.deleteOwned(root)
                    throw failure
                }
                return fixture
            }
            fun open(target: Context, run: String, phase: String, mustExist: Boolean): OwnedFixture? {
                val fixture = paths(target, run, phase)
                if (!fixture.files.exists() && !fixture.vault.exists() && !fixture.cache.exists()) {
                    check(!mustExist) { "QA_RESTART_FIXTURE_MISSING" }; return null
                }
                check(fixture.files.canonicalPath == fixture.files.absolutePath && !Files.isSymbolicLink(fixture.files.toPath()) && fixture.markerFile.isFile)
                fixture.marker(); return fixture
            }
            private fun paths(target: Context, run: String, phase: String): OwnedFixture {
                require(run.matches(Regex("[a-f0-9]{32}")) && phase in PHASES)
                // Android may expose /data/user/0 through a trusted system
                // alias. Canonicalize that base, then reject aliasing of the
                // UUID descendants rather than rejecting the OS data root.
                return OwnedFixture(target, run, phase, File(target.filesDir.canonicalFile, "$CATEGORY/$run/$phase"),
                    File(target.noBackupFilesDir.canonicalFile, "$CATEGORY/$run/$phase"), File(target.cacheDir.canonicalFile, "$CATEGORY/$run/$phase"))
            }
        }
    }

    private companion object {
        const val CATEGORY = "official-credential-device"
        const val PINNED_DSH = "0.2.1-alpha.1"
        val PHASES = listOf("native", "recovery", "restart", "dsh")
        val ALIASES = listOf("dafeiyu_model_providers_aes_v1", "dafeiyu_deepseek_api_key_aes_v1", "dafeiyu_official_deepseek_transaction_aes_v1")
        fun arguments() = InstrumentationRegistry.getArguments()
        fun runId(): String = checkNotNull(arguments().getString("official_qa_run")).also { require(it.matches(Regex("[a-f0-9]{32}"))) }
        fun requireArgument(name: String, expected: String) = require(arguments().getString(name) == expected) { "QA_PHASE_OPT_IN_REQUIRED" }
        fun target(): Context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext.also {
            check(it.packageName == "com.dafeiyu.mobile.dev" && Build.SUPPORTED_ABIS.first() == "x86_64" && Build.HARDWARE in setOf("ranchu", "goldfish")) {
                "QA_DEBUG_EMULATOR_ONLY"
            }
            requireArgument("official_qa_avd", "Dafeiyu_QA_API_37")
        }
        fun synthetic(label: String) = "qa-official-only-$label-${runId()}"
        fun apiKey(value: String) = JSONObject().put("type", "api_key").put("key", value)
        fun nativeCommitted(outcome: OfficialCredentialOutcome): OfficialCredentialOutcome.NativeCommitted {
            if (outcome is OfficialCredentialOutcome.NativeCommitted) return outcome
            throw SafeOutcomeFailure(outcome.javaClass.simpleName,
                (outcome as? OfficialCredentialOutcome.Pending)?.code ?: "QA_EXPECTED_NATIVE_COMMIT")
        }
        fun committed(outcome: OfficialCredentialOutcome): OfficialCredentialOutcome.Committed {
            check(outcome is OfficialCredentialOutcome.Committed) { "QA_EXPECTED_DSH_COMMIT" }; return outcome
        }
        suspend fun expectFailure(action: suspend () -> Unit): Throwable {
            var caught: Throwable? = null
            try { action() } catch (failure: Exception) { caught = failure }
            return checkNotNull(caught) { "QA_EXPECTED_REJECTION" }
        }
        fun encryptedFiles(context: Context) = listOf("model-providers.v1.enc", ".deepseek_api_key.v1.bin", "official-deepseek-transaction.v1.enc")
            .map { File(context.noBackupFilesDir, it) }
        fun encryptedFileHashes(context: Context): JSONObject = JSONObject().also { output ->
            encryptedFiles(context).forEach { file -> check(file.isFile); output.put(file.name, sha256(file.readBytes())) }
        }
        fun assertEncrypted(file: File, secret: String) {
            check(file.isFile)
            val payload = file.readBytes()
            try { check(!payload.toString(Charsets.ISO_8859_1).contains(secret)) { "QA_SECRET_PRESENT_IN_CIPHERTEXT" } }
            finally { payload.fill(0) }
        }
        fun keystore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        fun platformEvidence(): JSONObject {
            val store = keystore()
            for (alias in ALIASES) {
                check(store.containsAlias(alias))
                val key = checkNotNull(store.getKey(alias, null))
                check(key.algorithm == "AES" && key.encoded == null) { "QA_EXPECTED_NONEXPORTABLE_ANDROID_KEYSTORE_AES" }
            }
            return JSONObject().put("platform", "AndroidKeyStore").put("actualAesAliases", ALIASES.size)
                .put("aesKeysNonexportable", true).put("hardwareBackedClaim", false)
        }
        fun fingerprint(file: File): String = if (Files.isSymbolicLink(file.toPath())) "symlink:" + Files.readSymbolicLink(file.toPath())
            else if (file.isFile) sha256(file.readBytes()) else if (file.exists()) "nonfile" else "absent"
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun sameJson(left: JSONObject, right: JSONObject): Boolean = left.keys().asSequence().toSet() == right.keys().asSequence().toSet() &&
            left.keys().asSequence().all { key -> left.getString(key) == right.getString(key) }
    }

    private class SafeOutcomeFailure(val outcome: String, val code: String) : IllegalStateException("QA_UNEXPECTED_OUTCOME")
}
