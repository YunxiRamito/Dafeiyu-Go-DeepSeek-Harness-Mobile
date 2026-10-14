package com.dafeiyu.mobile.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Candidate, NOT executed. Runs only in the dedicated x86_64 QA dev identity.
 * It uses actual AndroidKeyStore/AtomicFile, fixed installed Node/PRoot/DSH,
 * trusted application factory and final authenticated loopback RPC.
 *
 * Injection boundary is exact: the real helper returns its persistent write result, then
 * the fixture closes/joins its real session and throws before that result is
 * returned to the transaction coordinator. This is a lost host-to-coordinator
 * acknowledgement, NOT an intercepted private-pipe MAC or fake storage write.
 * No model/prompt/OAuth request, download, system permission or remote service.
 */
@RunWith(AndroidJUnit4::class)
class OfficialColdDshDeviceSmoke {
    @Test
    fun prepareColdWritesBeforeActualApplicationRestart(): Unit = runBlocking {
        requireArgument("official_cold_phase", "prepare")
        execute("prepare", create = true, retain = true) { fixture, report ->
            fixture.record("preparePid", Process.myPid())
            for (scenario in SCENARIOS) report.case("prepare-real-$scenario-after-write-ack-loss") {
                val context = fixture.context(scenario, reopen = false)
                val environment = environment(fixture, context)
                val journal = AndroidOfficialCredentialJournal(context)
                val plan = JSONObject().put("schema", 1).put("port", freePort()).put("cwd", "/root")
                initializeIsolatedProfile(fixture, context, environment, plan)
                val credentialFile = File(context.filesDir, "dsh-home/.credentials.yaml")
                val credentialText = if (credentialFile.isFile) credentialFile.readText() else ""
                report.document.put("initialCredentialShape", JSONObject()
                    .put("exists", credentialFile.isFile).put("bytes", credentialFile.length())
                    .put("blank", credentialText.isBlank())
                    .put("hasVersionField", Regex("(?m)^version:").containsMatchIn(credentialText))
                    .put("hasRefsField", Regex("(?m)^refs:").containsMatchIn(credentialText))
                    .put("hasRecordsField", Regex("(?m)^records:").containsMatchIn(credentialText)))
                val scope = scopeId(context)
                val old = secret(scenario, "before")
                val desired = if (scenario == "select-delete") null else secret(scenario, "after")
                // First commit establishes real native + cold DSH ownership.
                val firstSession = environment.binding.openColdStorage(environment.factory, plan)
                val initial = try {
                    val result = environment.binding.coldCoordinator(
                        ObservedColdCalls(firstSession.storage, report.document)).begin(old)
                    report.document.put("initialPendingStep", journal.read().optJSONObject("pending")?.optString("step") ?: JSONObject.NULL)
                    committed(result)
                }
                finally { firstSession.close(); awaitIdle(environment.runtime, fixture) }
                requireNativePair(context, old, initial.transactionId)
                val nativeBefore = nativeCiphertexts(context)
                val actualHelperPids = mutableSetOf<Int>()
                val actualNodePids = mutableSetOf<Int>()
                val injectedSession = environment.binding.openColdStorage(environment.factory, plan)
                val actualTracer = soleHelperPid(environment.runtime)
                val actualNode = checkNotNull(injectedSession.verifiedNodePid) { "QA_ACTUAL_NODE_PID_REQUIRED" }
                check(injectedSession.helperTracerPid == actualTracer && actualNode > 0)
                actualHelperPids += actualTracer
                actualNodePids += actualNode
                val loss = LostCoordinatorAcknowledgement(injectedSession.storage,
                    if (scenario == "stage-replace") "stage" else "select") {
                    injectedSession.close()
                    awaitIdle(environment.runtime, fixture)
                }
                val outcome = try { environment.binding.coldCoordinator(loss).begin(desired) }
                finally { injectedSession.close(); awaitIdle(environment.runtime, fixture) }
                check(loss.injected && outcome is OfficialCredentialOutcome.Pending)
                val state = journal.read()
                val pending = state.getJSONObject("pending")
                check(pending.getString("step") == if (scenario == "stage-replace") "STAGING" else "SETTINGS_SUBMITTING")
                check(!pending.has("kind") && pending.getJSONObject("dshStorage").has("launchPlan"))
                OfficialDshStorageProof.validatePending(pending)
                check(OfficialCredentialJournalCodec.same(plan, pending.getJSONObject("dshStorage").getJSONObject("launchPlan")))
                check(nativeCiphertexts(context) == nativeBefore)
                requireNativePair(context, old, initial.transactionId)
                assertNativeBlocked(context)
                val pendingCandidate = pending.getString("candidate")
                val inspect = environment.binding.openColdStorage(environment.factory, plan)
                val disk = try { inspect.storage.readStorage(pendingCandidate) }
                finally { inspect.close(); awaitIdle(environment.runtime, fixture) }
                val classification = OfficialDshStorageRecovery.classify(pending, disk)
                check(classification == if (scenario == "stage-replace") OfficialDshStorageRecovery.State.STAGED else OfficialDshStorageRecovery.State.SELECTED)
                check(if (desired == null) !disk.getBoolean("candidatePresent") && disk.isNull("candidateKey") else
                    disk.getBoolean("candidatePresent") && disk.getString("candidateKey") == desired)
                val marker = JSONObject().put("scopeId", scope).put("launchPlan", plan).put("preparePid", Process.myPid())
                    .put("transactionId", pending.getString("id")).put("candidate", pendingCandidate)
                    .put("initialId", initial.transactionId).put("oldReference", initial.reference)
                    .put("ciphertexts", ciphertextHashes(context)).put("step", pending.getString("step"))
                    .put("helperPids", JSONArray(actualHelperPids.toList())).put("nodePids", JSONArray(actualNodePids.toList()))
                fixture.record(scenario, marker)
                JSONObject().put("actualFixedHelper", true).put("actualPRootPid", actualHelperPids.single())
                    .put("actualVerifiedNodePid", actualNodePids.single())
                    .put("durablePhase", pending.getString("step")).put("actualDiskClass", classification.name)
                    .put("ackLossBoundary", "real helper result withheld from coordinator after real session exit")
                    .put("nativeVaultsUnchanged", true).put("concreteJournalValidated", true)
                    .put("applicationRestartClaim", false).put("retainedForNextInstrumentation", true)
            }
        }
    }

    @Test
    fun recoverColdWritesAfterActualApplicationRestart(): Unit = runBlocking {
        requireArgument("official_cold_phase", "recover")
        execute("recover", create = false, retain = false) { fixture, report ->
            val oldPid = fixture.marker().getInt("preparePid")
            check(oldPid > 0 && oldPid != Process.myPid()) { "QA_ACTUAL_NEW_ANDROID_PID_REQUIRED" }
            report.document.put("preparePid", oldPid).put("recoveryPid", Process.myPid()).put("actualApplicationRestart", true)
            for (scenario in SCENARIOS) {
                val context = fixture.context(scenario, reopen = true)
                val proof = fixture.marker().getJSONObject(scenario)
                val journal = AndroidOfficialCredentialJournal(context)
                val environment = environment(fixture, context)
                val scope = proof.getString("scopeId")
                check(scope == scopeId(context))
                val plan = proof.getJSONObject("launchPlan")
                val desired = if (scenario == "select-delete") null else secret(scenario, "after")
                report.case("$scenario-new-process-reads-real-authenticated-pending") {
                    val state = journal.read()
                    check(state.getJSONObject("pending").getString("id") == proof.getString("transactionId") &&
                        state.getJSONObject("pending").getString("step") == proof.getString("step"))
                    check(OfficialCredentialJournalCodec.same(ciphertextHashes(context), proof.getJSONObject("ciphertexts")))
                    requireNativePair(context, secret(scenario, "before"), proof.getString("initialId"))
                    assertNativeBlocked(context)
                    platformKeyProof()
                    JSONObject().put("actualNewAndroidPid", true).put("ciphertextsUnchangedAcrossRestart", true)
                        .put("actualEncryptedJournal", true).put("nativeUseRefusedBeforeRecovery", true)
                }
                report.case("$scenario-full-future-launch-binding-change-keeps-pending") {
                    val before = ciphertextHashes(context)
                    val original = journal.read()
                    val changedPlan = JSONObject(plan.toString()).put("port", alternatePort(plan.getInt("port")))
                    val changed = environment.factory.open(AndroidOfficialDshStorageAuthorizer(context, scope), scope, changedPlan)
                    val changedLive = try { changed.storage.readStorage(proof.getString("candidate")) }
                    finally { changed.close(); awaitIdle(environment.runtime, fixture) }
                    check(OfficialDshStorageRecovery.classify(original.getJSONObject("pending"), changedLive) == OfficialDshStorageRecovery.State.CONFLICT)
                    val conflictingSession = environment.factory.open(AndroidOfficialDshStorageAuthorizer(context, scope), scope, changedPlan)
                    val conflict = try { environment.binding.coldCoordinator(conflictingSession.storage).recover() }
                    finally { conflictingSession.close(); awaitIdle(environment.runtime, fixture) }
                    check(conflict is OfficialCredentialOutcome.Pending && journal.read().has("pending"))
                    check(OfficialCredentialJournalCodec.same(ciphertextHashes(context), before) &&
                        OfficialCredentialJournalCodec.same(original, journal.read()))
                    check(changedLive.getString("bindingDigest") != original.getJSONObject("pending").getJSONObject("dshStorage").getString("bindingDigest"))
                    requireNativePair(context, secret(scenario, "before"), proof.getString("initialId"))
                    JSONObject().put("actualFutureArgvPortChanged", true).put("completeBindingDigestChanged", true)
                        .put("exactPendingPreserved", true).put("nativeCiphertextsPreserved", true).put("automaticAdoptionRefused", true)
                }
                report.case("$scenario-new-helper-recovers-original-complete-storage-proof") {
                    val throughRepository = scenario == "stage-replace"
                    val recoveredPids = if (throughRepository) recoverThroughRepository(context, fixture, environment)
                        else recoverThroughBinding(environment, fixture, plan)
                    val actualPid = recoveredPids.first
                    val actualNodePid = recoveredPids.second
                    val previous = proof.getJSONArray("helperPids")
                    check((0 until previous.length()).none { previous.getInt(it) == actualPid }) { "QA_NEW_HELPER_PID_REQUIRED" }
                    val previousNodes = proof.getJSONArray("nodePids")
                    check((0 until previousNodes.length()).none { previousNodes.getInt(it) == actualNodePid }) { "QA_NEW_NODE_PID_REQUIRED" }
                    val state = journal.read()
                    check(!state.has("pending") && !state.has("deferred") && state.getJSONObject("receipt").has("dshStorage"))
                    val receipt = state.getJSONObject("receipt")
                    check(receipt.getString("id") == proof.getString("transactionId") &&
                        receipt.getString("reference") == proof.getString("candidate") && receipt.getBoolean("keyRemoved") == (desired == null))
                    requireNativePair(context, desired, receipt.getString("id"))
                    val finalRead = environment.binding.openColdStorage(environment.factory, plan)
                    val selected = try { finalRead.storage.readStorage(receipt.getString("reference")) }
                    finally { finalRead.close(); awaitIdle(environment.runtime, fixture) }
                    check(selected.getString("effectiveReference") == receipt.getString("reference") &&
                        selected.getString("userReference") == receipt.getString("reference"))
                    check(if (desired == null) selected.isNull("selectedKey") && selected.isNull("candidateKey") && !selected.getBoolean("candidatePresent")
                        else selected.getString("selectedKey") == desired && selected.getString("candidateKey") == desired)
                    assertNativeAdmitted(context)
                    JSONObject().put("actualNewHelperPRootPid", actualPid).put("actualNewVerifiedNodePid", actualNodePid)
                        .put("transactionId", receipt.getString("id"))
                        .put("realStoreRecovery", true).put("actualAndroidKeyStore", true).put("deletionRemainsMissing", desired == null)
                        .put("actualBindingUsesAuthenticatedSavedPlan", true)
                        .put("repositoryPrivateRecoveryViaReflection", throughRepository).put("actualInitializeWorkspaces", false)
                }
                report.case("$scenario-final-normal-dsh-authenticated-rpc-readback") {
                    val service = DshRuntimeManager(context, environment.runtime, environment.installer)
                    val bridge = DshSharedPersistenceBridge(context, environment.runtime, environment.installer)
                    fixture.record("ownedLeaseMayBeActive", true)
                    try {
                        val started = withTimeout(90_000) { service.start(requestedPort = plan.getInt("port")) }
                        check(started.status == DshServiceStatus.RUNNING && started.healthCode == 200)
                        val access = checkNotNull(started.accessUrl)
                        val describe = DshLocalConfigurationRpc().call(access, "settings/describe", JSONObject())
                        val namespaces = describe.getJSONArray("namespaces")
                        val official = (0 until namespaces.length()).map(namespaces::getJSONObject)
                            .filter { it.optString("ns") in setOf("llm-deepseek", "llm-deepseek-api-key") }.single()
                        check(describe.getBoolean("writable") && official.getJSONObject("value").getString("apiKeyEnv") == proof.getString("candidate") &&
                            official.getJSONObject("user").getString("apiKeyEnv") == proof.getString("candidate"))
                        val refs = bridge.readCredentialDocument().getJSONObject("refs")
                        check(refs.getString(proof.getString("oldReference")) == secret(scenario, "before"))
                        check(if (desired == null) !refs.has(proof.getString("candidate")) else refs.getString(proof.getString("candidate")) == desired)
                        requireNativePair(context, desired, proof.getString("transactionId"))
                        JSONObject().put("actualAuthenticatedLoopbackRpc", true).put("actualNormalDshPid", started.pid)
                            .put("oldReferenceRetained", true).put("deletedReferenceNeverRevived", desired == null).put("modelRequests", 0)
                    } finally {
                        withContext(NonCancellable) {
                            service.stop(); service.close(); awaitIdle(environment.runtime, fixture)
                            fixture.record("ownedLeaseMayBeActive", false)
                        }
                    }
                }
                check(environment.installer.current()?.manifest?.digest() == expectedManifest())
            }
        }
    }

    @Test
    fun cleanupOwnedColdFixtures(): Unit = runBlocking {
        requireArgument("official_cold_cleanup", "true")
        val target = target()
        val guard = CommonGuard(target)
        val report = Report(target, runId(), "cleanup")
        var success = false
        try {
            report.case("verified-uuid-only-cleanup") {
                Fixture.open(target, runId(), mustExist = false)?.cleanup()
                guard.verify()
                JSONObject().put("fixtureRemovedOrAbsent", true).put("keystoreAliasesRemoved", 0).put("broadShellDelete", false)
            }
            success = true
        } finally {
            report.document.put("passed", success).put("completed", true).put("cleanupComplete", success)
            report.document.put("finishedAt", System.currentTimeMillis()); report.persist()
        }
    }

    private data class Environment(val runtime: LinuxRuntimeManager, val installer: DshRuntimeInstaller,
        val factory: AndroidOfficialDshLaunchFactory, val binding: AndroidOfficialCredentialBinding)

    private fun environment(fixture: Fixture, context: Context): Environment {
        val runtime = LinuxRuntimeManager(fixture.target)
        val installer = DshRuntimeInstaller(fixture.target, runtime)
        val slot = checkNotNull(installer.current()) { "QA_ALREADY_INSTALLED_FIXED_DSH_REQUIRED" }
        check(slot.manifest.dshVersion == "0.2.1-alpha.1" && slot.manifest.abi == "x86_64" && slot.manifest.digest() == expectedManifest())
        check(runtime.managedProcesses.value.isEmpty() && !runtime.hasProcessLease(fixture.target.filesDir) &&
            !runtime.hasProcessLease(fixture.target.noBackupFilesDir)) { "QA_RUNTIME_BUSY_NO_USER_STOP_ALLOWED" }
        val binding = AndroidOfficialCredentialBinding(context, ModelProviderStore(context), BalanceStore(context),
            DshSharedPersistenceBridge(context, runtime, installer))
        return Environment(runtime, installer, AndroidOfficialDshLaunchFactory(context, runtime, installer), binding)
    }

    private suspend fun initializeIsolatedProfile(fixture: Fixture, context: Context, environment: Environment, plan: JSONObject) {
        val service = DshRuntimeManager(context, environment.runtime, environment.installer)
        fixture.record("ownedLeaseMayBeActive", true)
        try {
            val started = withTimeout(90_000) { service.start(requestedPort = plan.getInt("port")) }
            check(started.status == DshServiceStatus.RUNNING && started.healthCode == 200)
            val response = DshLocalConfigurationRpc().call(checkNotNull(started.accessUrl), "settings/describe", JSONObject())
            check(response.getBoolean("writable"))
        } finally {
            withContext(NonCancellable) {
                service.stop(); service.close(); awaitIdle(environment.runtime, fixture)
                fixture.record("ownedLeaseMayBeActive", false)
            }
        }
    }

    private suspend fun recoverThroughBinding(environment: Environment, fixture: Fixture, plan: JSONObject): Pair<Int, Int> {
        val ignoredNewPlan = JSONObject(plan.toString()).put("port", alternatePort(plan.getInt("port")))
        val savedPlan = checkNotNull(environment.binding.storageLaunchPlanForRecovery())
        check(OfficialCredentialJournalCodec.same(savedPlan, plan))
        val session = environment.binding.openColdStorage(environment.factory, ignoredNewPlan)
        try {
            check(OfficialCredentialJournalCodec.same(session.storage.launchPlan(), savedPlan))
            val tracer = soleHelperPid(environment.runtime)
            val node = checkNotNull(session.verifiedNodePid) { "QA_ACTUAL_NODE_PID_REQUIRED" }
            check(session.helperTracerPid == tracer && node > 0)
            committed(environment.binding.coldCoordinator(session.storage).recover())
            return tracer to node
        } finally { session.close(); awaitIdle(environment.runtime, fixture) }
    }

    /** Uses the actual Repository private recovery route without initializing
     * catalog, notification, push or connection controllers. This proves that
     * route, not an initializeWorkspaces/UI initialization path. Reflection is
     * test-only; it neither bypasses the concrete journal nor supplies tickets. */
    private suspend fun recoverThroughRepository(context: Context, fixture: Fixture, environment: Environment): Pair<Int, Int> = coroutineScope {
        val holder = ViewModelStore()
        val service = DshRuntimeManager(context, environment.runtime, environment.installer)
        val repository = withContext(Dispatchers.Main.immediate) {
            DemoRepository().also { actual ->
                holder.put("official-cold", actual)
                repositoryField(actual, "applicationContext").set(actual, context)
                repositoryField(actual, "modelProviderStore").set(actual, ModelProviderStore(context))
                repositoryField(actual, "deepSeekBalanceStore").set(actual, BalanceStore(context))
                repositoryField(actual, "officialCredentials").set(actual, environment.binding)
                repositoryField(actual, "officialCredentialBridge").set(actual,
                    DshSharedPersistenceBridge(context, environment.runtime, environment.installer))
                repositoryField(actual, "officialCredentialRuntime").set(actual, environment.runtime)
                repositoryField(actual, "officialCredentialStorage").set(actual, environment.factory)
                repositoryField(actual, "dshRuntimeManager").set(actual, service)
            }
        }
        val repositoryJob = withContext(Dispatchers.Main.immediate) { repository.viewModelScope.coroutineContext[Job] }
        val tracerPids = ConcurrentHashMap.newKeySet<Int>()
        val nodePids = ConcurrentHashMap.newKeySet<Int>()
        val actualShared = environment.factory.javaClass.getDeclaredField("shared").apply { isAccessible = true }.get(environment.factory)
        val actualActive = actualShared.javaClass.getDeclaredField("active").apply { isAccessible = true }
        // Read the actual factory-owned Session. Never infer Node identity
        // from PRoot PID or duplicate the production tracee verifier.
        val observer = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            while (isActive) {
                val session = actualActive.get(actualShared) as? AndroidOfficialDshLaunchFactory.Session
                if (session != null) {
                    val tracer = session.helperTracerPid
                    val node = session.verifiedNodePid
                    if (tracer > 0 && node != null && node > 0) { tracerPids.add(tracer); nodePids.add(node) }
                }
                delay(1)
            }
        }
        try {
            val gate = repositoryField(repository, "localEngineGate").get(repository) as Mutex
            gate.withLock { invokeRepositoryRecovery(repository) }
            check(withContext(Dispatchers.Main.immediate) {
                repository.officialCredentialState.nativeReady && !repository.officialCredentialRecoveryPending &&
                    repository.modelRuntime == null && repository.backgroundTasks == null && !repository.isResponding && !repository.sendPending
            })
            for (name in listOf("catalogClient", "notificationMonitor", "pushSettings", "connectPluginHost")) {
                check(repositoryField(repository, name).get(repository) == null) { "QA_UNEXPECTED_INITIALIZED_NETWORK_CONTROLLER" }
            }
            check(repositoryField(repository, "initializationStarted").getBoolean(repository).not())
            check(!AndroidOfficialCredentialJournal(context).read().has("pending"))
        } finally {
            withContext(NonCancellable) {
                observer.cancelAndJoin()
                withContext(Dispatchers.Main.immediate) { holder.clear() }
                withTimeout(20_000) { repositoryJob?.join() }
                check(environment.factory.cleanupPending()) { "QA_REAL_FACTORY_CLEANUP_PENDING" }
                service.stop(); service.close(); awaitIdle(environment.runtime, fixture)
            }
        }
        check(tracerPids.size == 1 && nodePids.size == 1) { "QA_ACTUAL_REPOSITORY_HELPER_PID_WITNESS_REQUIRED" }
        tracerPids.single() to nodePids.single()
    }

    private fun repositoryField(repository: DemoRepository, name: String) =
        repository.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private suspend fun invokeRepositoryRecovery(repository: DemoRepository): Unit = suspendCoroutine { continuation ->
        try {
            val method = repository.javaClass.getDeclaredMethod("recoverOfficialCredentialsLocked", String::class.java,
                OfficialCredentialUiOperation::class.java, Continuation::class.java).apply { isAccessible = true }
            val result = method.invoke(repository, null, OfficialCredentialUiOperation.INITIALIZE, object : Continuation<Unit> {
                override val context = continuation.context
                override fun resumeWith(result: Result<Unit>) = continuation.resumeWith(result)
            })
            if (result !== COROUTINE_SUSPENDED) continuation.resumeWith(Result.success(Unit))
        } catch (failure: InvocationTargetException) {
            continuation.resumeWith(Result.failure(failure.targetException))
        } catch (failure: Throwable) { continuation.resumeWith(Result.failure(failure)) }
    }

    private class LostCoordinatorAcknowledgement(private val real: OfficialDshColdStoragePort, private val operation: String,
        private val cleanupActualSession: suspend () -> Unit) : OfficialDshColdStoragePort by real {
        var injected = false
            private set
        override suspend fun stageStorage(expected: JSONObject): JSONObject = lose("stage", real.stageStorage(expected))
        override suspend fun selectStorage(expected: JSONObject): JSONObject = lose("select", real.selectStorage(expected))
        private suspend fun lose(actualOperation: String, actualResult: JSONObject): JSONObject {
            if (!injected && actualOperation == operation) {
                check(actualResult.getInt("schema") == 1 && actualResult.getString("realm") == "android-dsh-storage-v1")
                injected = true
                cleanupActualSession()
                throw IOException("QA_FIXED_AFTER_WRITE_COORDINATOR_ACK_LOSS")
            }
            return actualResult
        }
    }

    /** Observe only fixed operation names and safe protocol codes before the
     * coordinator intentionally maps arbitrary transport errors to Pending. */
    private class ObservedColdCalls(private val real: OfficialDshColdStoragePort, private val report: JSONObject) :
        OfficialDshColdStoragePort by real {
        private val calls = report.optJSONArray("initialCalls") ?: JSONArray().also { report.put("initialCalls", it) }
        override suspend fun readStorage(candidate: String): JSONObject = observe("read") { real.readStorage(candidate) }
        override suspend fun stageStorage(expected: JSONObject): JSONObject = observe("stage") { real.stageStorage(expected) }
        override suspend fun selectStorage(expected: JSONObject): JSONObject = observe("select") { real.selectStorage(expected) }
        private suspend fun observe(operation: String, action: suspend () -> JSONObject): JSONObject {
            val row = JSONObject().put("operation", operation).put("passed", false)
            calls.put(row)
            return try { action().also { row.put("passed", true) } }
            catch (failure: Throwable) {
                row.put("errorClass", failure.javaClass.simpleName)
                if (failure is AndroidOfficialDshControlledPort.Failure) row.put("safeCode", failure.code)
                row.put("frames", JSONArray(failure.stackTrace.take(8).map {
                    "${it.className}.${it.methodName}:${it.lineNumber}"
                }))
                throw failure
            }
        }
    }

    private suspend fun execute(phase: String, create: Boolean, retain: Boolean,
        action: suspend (Fixture, Report) -> Unit) {
        val target = target()
        val guard = CommonGuard(target)
        val report = Report(target, runId(), phase)
        var fixture: Fixture? = null
        var success = false
        var errorClass: String? = null
        try {
            fixture = if (create) Fixture.create(target, runId()) else checkNotNull(Fixture.open(target, runId(), true))
            action(fixture, report)
            guard.verify()
            success = true
        } catch (failure: Throwable) { errorClass = failure.javaClass.simpleName }
        finally {
            withContext(NonCancellable) {
                try {
                    if (success && retain) report.document.put("retainedForRestart", true).put("cleanupComplete", false)
                    else { fixture?.cleanup(); report.document.put("cleanupComplete", true) }
                    guard.verify()
                    report.document.put("commonProtectedBytesPreserved", true).put("commonKeystoreAliasesPreserved", true)
                } catch (failure: Throwable) {
                    success = false
                    if (errorClass == null) errorClass = failure.javaClass.simpleName
                    report.document.put("cleanupComplete", false).put("cleanupErrorClass", failure.javaClass.simpleName)
                }
                report.document.put("passed", success).put("completed", true).put("errorClass", errorClass ?: JSONObject.NULL)
                    .put("finishedAt", System.currentTimeMillis())
                report.persist()
            }
        }
        if (!success) throw AssertionError("QA_COLD_DSH_FAILED:$phase:${errorClass ?: "unknown"}")
    }

    private class Report(target: Context, run: String, phase: String) {
        private val directory = File(checkNotNull(target.getExternalFilesDir(null)), "qa/official-cold-device-$run")
        private val file = File(directory, "$phase.json")
        private val cases = JSONArray()
        val document = JSONObject().put("schema", 1).put("runId", run).put("phase", phase).put("package", target.packageName)
            .put("pid", Process.myPid()).put("sdk", Build.VERSION.SDK_INT).put("abi", Build.SUPPORTED_ABIS.first())
            .put("expectedDshManifest", expectedManifest())
            .put("startedAt", System.currentTimeMillis()).put("passed", false).put("completed", false).put("results", cases)
            .put("paidModelRequests", 0).put("remoteServiceRequests", 0).put("permissionGrants", 0).put("downloads", 0)
            .put("networkScope", "authenticated local DSH loopback only")
        init {
            check(!Files.isSymbolicLink(directory.toPath()) && (directory.isDirectory || directory.mkdirs()))
            check(!file.exists() && !Files.isSymbolicLink(file.toPath()))
            persist()
        }
        fun persist() { file.writeText(document.toString(2), Charsets.UTF_8) }
        suspend fun case(name: String, action: suspend () -> JSONObject) {
            val row = JSONObject().put("name", name).put("passed", false)
            cases.put(row); persist()
            try { row.put("evidence", withTimeout(180_000) { action() }).put("passed", true) }
            catch (failure: Throwable) {
                row.put("errorClass", failure.javaClass.simpleName)
                if (failure is AndroidOfficialDshControlledPort.Failure) row.put("safeCode", failure.code)
                if (failure is ColdOutcomeFailure) row.put("safeOutcomeCode", failure.code)
                // Frames identify the failed boundary without exporting an
                // arbitrary exception message, environment or credential.
                row.put("frames", JSONArray(failure.stackTrace.take(12).map {
                    "${it.className}.${it.methodName}:${it.lineNumber}"
                }))
                throw failure
            }
            finally { persist() }
        }
    }

    private class CommonGuard(target: Context) {
        private val protected = listOf(
            File(target.noBackupFilesDir, "model-providers.v1.enc"), File(target.noBackupFilesDir, ".deepseek_api_key.v1.bin"),
            File(target.noBackupFilesDir, "official-deepseek-transaction.v1.enc"), File(target.filesDir, "dsh-provider-order/order.json"),
            File(target.filesDir, "dsh-home/.credentials.yaml"), File(target.filesDir, "dsh-home/settings.json"),
            File(target.filesDir, "dsh-home/cordis.patch.yml"), File(target.filesDir, "dsh-home/settings.yaml"),
            File(target.filesDir, "dsh-home/settings.yaml.imported"), File(target.filesDir, "dsh-home/profiles/web/cordis.patch.yml"),
            File(target.filesDir, "dsh-home/profiles/web/cordis.yml"), File(target.filesDir, "dsh-home/profiles/web/package.json"),
            File(target.filesDir, "linux-runtime/active.json"), File(target.filesDir, "dsh-runtime/active.json"), File(target.filesDir, "dsh-runtime/previous.json")
        ).flatMap { listOf(it, File(it.path + ".bak"), File(it.path + ".new")) }
        private val before = protected.associateWith { fingerprint(it) }
        private val aliases = keystore().aliases().toList().toSet()
        fun verify() {
            before.forEach { (file, original) -> check(fingerprint(file) == original) { "QA_COMMON_BYTES_CHANGED" } }
            check(keystore().aliases().toList().toSet().containsAll(aliases)) { "QA_COMMON_KEY_ALIAS_REMOVED" }
        }
    }

    private class Fixture private constructor(val target: Context, val run: String, val files: File, val vault: File, val cache: File) {
        private val markerFile = File(files, "owner.json")
        fun marker(): JSONObject = JSONObject(markerFile.readText()).also { value ->
            check(value.getInt("schema") == 1 && value.getString("suite") == CATEGORY && value.getString("runId") == run &&
                value.getString("package") == target.packageName && value.getString("files") == files.path &&
                value.getString("vault") == vault.path && value.getString("cache") == cache.path)
        }
        fun record(name: String, value: Any) {
            val state = marker().put(name, value)
            markerFile.writeText(state.toString(), Charsets.UTF_8)
        }
        fun context(name: String, reopen: Boolean): Context {
            require(name in SCENARIOS)
            fun child(parent: File): File = File(parent, name).also {
                check(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile == parent.canonicalFile)
                check(if (reopen) it.isDirectory else !it.exists() && it.mkdir())
            }
            val f = child(files); val v = child(vault); val c = child(cache)
            return object : ContextWrapper(target) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = f
                override fun getNoBackupFilesDir(): File = v
                override fun getCacheDir(): File = c
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    require(name.matches(Regex("[A-Za-z0-9_.-]{1,128}")))
                    val scoped = "official-cold-$run-$name-${f.name}"
                    val state = marker()
                    val prefs = state.getJSONArray("preferences")
                    if ((0 until prefs.length()).none { prefs.getString(it) == scoped }) prefs.put(scoped)
                    markerFile.writeText(state.toString(), Charsets.UTF_8)
                    return target.getSharedPreferences(scoped, mode)
                }
            }
        }
        fun cleanup() {
            val proof = marker()
            check(!proof.optBoolean("ownedLeaseMayBeActive")) { "QA_POSSIBLE_OWNED_LEASE_KEEP_FIXTURE" }
            val runtime = LinuxRuntimeManager(target)
            check(!runtime.hasProcessLease(files) && !runtime.hasProcessLease(vault) && !runtime.hasProcessLease(cache)) {
                "QA_ACTUAL_FIXTURE_LEASE_KEEP_FILES"
            }
            val prefs = proof.getJSONArray("preferences")
            for (index in 0 until prefs.length()) {
                val name = prefs.getString(index)
                check(name.startsWith("official-cold-$run-"))
                check(target.deleteSharedPreferences(name))
            }
            for ((root, base) in listOf(vault to target.noBackupFilesDir, cache to target.cacheDir, files to target.filesDir)) {
                val expected = File(base.canonicalFile, "$CATEGORY/$run")
                check(root.absolutePath == expected.absolutePath && root.canonicalPath == expected.absolutePath && !Files.isSymbolicLink(root.toPath()))
                if (root.exists()) Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        check(file.startsWith(root.toPath())); Files.delete(file); return FileVisitResult.CONTINUE
                    }
                    override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                        if (error != null) throw error
                        check(directory.startsWith(root.toPath())); Files.delete(directory); return FileVisitResult.CONTINUE
                    }
                })
            }
        }
        companion object {
            private fun paths(target: Context, run: String) = Fixture(target, run, File(target.filesDir.canonicalFile, "$CATEGORY/$run"),
                File(target.noBackupFilesDir.canonicalFile, "$CATEGORY/$run"), File(target.cacheDir.canonicalFile, "$CATEGORY/$run"))
            fun create(target: Context, run: String): Fixture {
                val fixture = paths(target, run)
                for (path in listOf(fixture.files, fixture.vault, fixture.cache)) check(!path.exists() && path.canonicalPath == path.absolutePath)
                for (path in listOf(fixture.files, fixture.vault, fixture.cache)) check(path.mkdirs())
                fixture.markerFile.writeText(JSONObject().put("schema", 1).put("suite", CATEGORY).put("runId", run).put("package", target.packageName)
                    .put("files", fixture.files.path).put("vault", fixture.vault.path).put("cache", fixture.cache.path)
                    .put("preferences", JSONArray()).put("ownedLeaseMayBeActive", false).toString(), Charsets.UTF_8)
                return fixture
            }
            fun open(target: Context, run: String, mustExist: Boolean): Fixture? {
                val fixture = paths(target, run)
                if (listOf(fixture.files, fixture.vault, fixture.cache).none { it.exists() }) { check(!mustExist); return null }
                check(fixture.markerFile.isFile && !Files.isSymbolicLink(fixture.files.toPath()) && fixture.files.canonicalPath == fixture.files.absolutePath)
                fixture.marker(); return fixture
            }
        }
    }

    private companion object {
        const val CATEGORY = "official-cold-device"
        val SCENARIOS = listOf("stage-replace", "select-replace", "select-delete")
        fun arguments() = InstrumentationRegistry.getArguments()
        fun runId(): String = checkNotNull(arguments().getString("official_cold_run")).also { require(it.matches(Regex("[a-f0-9]{32}"))) }
        fun requireArgument(name: String, value: String) = require(arguments().getString(name) == value) { "QA_EXPLICIT_PHASE_REQUIRED" }
        fun expectedManifest(): String = checkNotNull(arguments().getString("official_dsh_manifest")).also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        fun target(): Context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext.also {
            check(it.packageName == "com.dafeiyu.mobile.dev" && Build.SUPPORTED_ABIS.first() == "x86_64" && Build.HARDWARE in setOf("ranchu", "goldfish"))
            requireArgument("official_qa_avd", "Dafeiyu_QA_API_37")
        }
        fun secret(scenario: String, value: String) = "qa-cold-only-$scenario-$value-${runId()}"
        fun scopeId(context: Context) = sha(
            "android-dsh-storage-v1\n${context.packageName}\n${context.filesDir.canonicalPath}\n${context.noBackupFilesDir.canonicalPath}"
                .toByteArray(Charsets.UTF_8))
        fun committed(value: OfficialCredentialOutcome): OfficialCredentialOutcome.Committed =
            value as? OfficialCredentialOutcome.Committed ?: throw ColdOutcomeFailure(
                if (value is OfficialCredentialOutcome.Pending) value.code else "UNEXPECTED_OUTCOME")
        fun requireNativePair(context: Context, desired: String?, id: String) {
            val provider = ModelProviderStore(context).officialCredentialSnapshot()
            val balance = BalanceStore(context).officialCredentialSnapshot()
            check(provider.key == desired && balance.key == desired && provider.transactionId == id && balance.transactionId == id)
        }
        fun nativeCiphertexts(context: Context): Map<String, String> = listOf("model-providers.v1.enc", ".deepseek_api_key.v1.bin")
            .associateWith { name -> fingerprint(File(context.noBackupFilesDir, name)) }
        fun ciphertextHashes(context: Context): JSONObject = JSONObject().also { result ->
            for (name in listOf("model-providers.v1.enc", ".deepseek_api_key.v1.bin", "official-deepseek-transaction.v1.enc")) {
                val file = File(context.noBackupFilesDir, name)
                check(file.isFile); result.put(name, fingerprint(file))
            }
        }
        suspend fun assertNativeBlocked(context: Context) {
            var admitted = false
            var error: Exception? = null
            try { JournalModelRuntimeRecoveryAccess(AndroidOfficialCredentialJournal(context)).withNativeReady { admitted = true } }
            catch (failure: Exception) { error = failure }
            check(!admitted && error is ModelRuntimeException && error.code == "CREDENTIAL_RECOVERY_REQUIRED")
        }
        suspend fun assertNativeAdmitted(context: Context) {
            var admitted = false
            JournalModelRuntimeRecoveryAccess(AndroidOfficialCredentialJournal(context)).withNativeReady { admitted = true }
            check(admitted)
        }
        fun soleHelperPid(runtime: LinuxRuntimeManager): Int = runtime.managedProcesses.value.single().also { check(it.running) }.pid
        suspend fun awaitIdle(runtime: LinuxRuntimeManager, fixture: Fixture) = withTimeout(20_000) {
            while (runtime.managedProcesses.value.isNotEmpty() || runtime.hasProcessLease(fixture.files) ||
                runtime.hasProcessLease(fixture.vault) || runtime.hasProcessLease(fixture.cache)) delay(50)
        }
        fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }.also { check(it >= 1024) }
        fun alternatePort(port: Int) = if (port == 65535) port - 1 else port + 1
        fun keystore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        fun platformKeyProof() {
            for (alias in listOf("dafeiyu_model_providers_aes_v1", "dafeiyu_deepseek_api_key_aes_v1", "dafeiyu_official_deepseek_transaction_aes_v1")) {
                val key = checkNotNull(keystore().getKey(alias, null))
                check(key.algorithm == "AES" && key.encoded == null)
            }
        }
        fun sha(bytes: ByteArray): String = try { MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
            finally { bytes.fill(0) }
        fun fingerprint(file: File): String = if (Files.isSymbolicLink(file.toPath())) "symlink:" + Files.readSymbolicLink(file.toPath())
            else if (file.isFile) sha(file.readBytes()) else if (file.exists()) "nonfile" else "absent"
    }

    private class ColdOutcomeFailure(val code: String) : AssertionError("QA_EXPECTED_REAL_COLD_COMMIT")
}
