package com.dafeiyu.mobile.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.AtomicFile
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.DigestInputStream
import java.security.KeyStore
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Real production 7z/coordinator/Repo/vaults, UUID fixture data only.
 * No runtime installation, model request, remote service, grant, or activity launch.
 * Reopening storage and Repository is not claimed to be an Android process restart.
 */
@RunWith(AndroidJUnit4::class)
class DymOfficialCredentialDeviceSmoke {
    @Test
    fun realSevenZipCoordinatorAndNativeCredentials(): Unit = runBlocking {
        val target = target()
        val run = runId()
        val common = CommonStorageGuard(target)
        val commonRuntime = LinuxRuntimeManager(target)
        val report = Report(target, run)
        var owned: OwnedRun? = null
        val sessions = mutableListOf<Session>()
        var workPassed = false
        var failure: Throwable? = null
        suspend fun open(name: String, reopen: Boolean = false): Session {
            val context = checkNotNull(owned).context(name, reopen)
            val session = Session(context, target)
            sessions += session
            session.initialize(reopen)
            return session
        }
        try {
            requireIdle(commonRuntime, target.filesDir, target.noBackupFilesDir)
            owned = OwnedRun.create(target, run)
            val phone = open("roundtrip")
            val current = synthetic(run, "current")
            val stale = synthetic(run, "retained-old")
            val other = synthetic(run, "other")
            report.case("actual-sevenzip-and-production-provider-restore") {
                val archive = phone.archive("positive.dym", DymExportInput(
                    directories = phone.skill("positive"), androidPreferences = officialPreferences(true),
                    pcPreferences = pcPreferences(), providerConfiguration = portable(current, other),
                    sharedCredentials = shared(stale)))
                phone.import(archive) { prepared, restored ->
                    check(!prepared.legacy && prepared.containsCredentials && prepared.archive?.providerConfiguration != null)
                    check(restored.descriptions.any { it == "提供商配置与凭据" })
                    check(restored.warnings.none { it.startsWith("提供商配置") || it.startsWith("提供商配置或官方凭据") })
                }
                requirePair(phone.context, current)
                check(ModelProviderStore(phone.context).read().providers.map { it.id } == listOf(OTHER_ID, "deepseek"))
                requireOther(phone.context, other)
                phone.requirePreferenceAndSkill("positive")
                check(DymRetainedResourceStore(phone.context.filesDir).read().pcPreferences != null)
                phone.requireNoExecution()
                evidence("productionCoordinator", "actualAndroidKeystore", "realSevenZip", "providerOrdering", "preferencesAndResources")
            }
            report.case("production-export-normalizes-retained-official-key-to-current-native-value") {
                phone.verifyExport("positive-export.dym", current, other)
                evidence("actualContentResolverFileStream", "currentKeyAuthoritative", "bothPlatformPreferences", "noDshInstalled")
            }
            report.case("explicit-provider-absence-commits-two-native-tombstones-and-preserves-old-retained-input") {
                val absence = JSONObject(ModelProviderStore(phone.context).exportPortable().toString()).apply {
                    getJSONObject("credentials").remove("deepseek")
                }
                val archive = phone.archive("absence.dym", DymExportInput(providerConfiguration = absence))
                phone.import(archive) { prepared, restored ->
                    check(prepared.archive?.sharedCredentials == null)
                    check(restored.descriptions.any { it == "提供商配置与凭据" })
                    check(restored.warnings.none { it.startsWith("提供商配置") })
                }
                requirePair(phone.context, null)
                requireOther(phone.context, other)
                val journal = AndroidOfficialCredentialJournal(phone.context).read()
                check(journal.getJSONObject("nativeReceipt").getBoolean("keyRemoved") && !journal.has("pending"))
                val retained = checkNotNull(DymRetainedResourceStore(phone.context.filesDir).read().sharedCredentials)
                check(DymSharedCredentials.officialSelection(retained).key == stale)
                evidence("twoNativeTombstones", "retainedOldInputStillPresent", "noImplicitRetainedAuthority")
            }
            report.case("production-export-after-native-clear-never-resurrects-old-retained-key") {
                phone.verifyExport("after-clear.dym", null, other)
                evidence("explicitSelectorAbsence", "noLauncherKey", "noOldOfficialRefValue", "otherProviderPreserved")
            }
            phone.close()
            val reopened = open("roundtrip", reopen = true)
            report.case("real-store-and-repository-reopen-preserves-tombstone-and-produces-safe-export") {
                requirePair(reopened.context, null)
                requireOther(reopened.context, other)
                check(withContext(Dispatchers.Main.immediate) { !reopened.repository.deepSeekConfigured && reopened.repository.tokenSavingMode })
                reopened.verifyExport("reopened-export.dym", null, other)
                reopened.requireNoExecution()
                evidence("realVaultReopen", "realRepositoryReopen", "coldPreferenceRead", "noCredentialResurrection")
                    .put("actualProcessRestart", false)
            }

            val fallback = open("fallback")
            seed(fallback.context, synthetic(run, "fallback-old"), other)
            report.case("bad-provider-json-falls-back-to-known-shared-absence-and-imports-other-valid-groups") {
                val archive = fallback.rawArchive("fallback.dym", mapOf(
                    DymArchiveStore.PROVIDER_CONFIGURATION to "{".toByteArray(),
                    DymArchiveStore.SHARED_CREDENTIALS to shared(null).toString().toByteArray(),
                    DymArchiveStore.ANDROID_PREFERENCES to officialPreferences(true).toString().toByteArray(),
                    ".dsh/skills/fallback/SKILL.md" to SKILL_CONTENT.toByteArray()))
                fallback.import(archive) { prepared, restored ->
                    check(prepared.archive?.providerConfiguration == null && prepared.archive?.sharedCredentials != null)
                    check(prepared.warnings.any { it.startsWith(DymArchiveStore.PROVIDER_CONFIGURATION) })
                    check(restored.descriptions.any { it == "提供商配置与凭据" })
                    check(restored.warnings.isNotEmpty())
                }
                requirePair(fallback.context, null)
                requireOther(fallback.context, other)
                fallback.requirePreferenceAndSkill("fallback")
                fallback.requireNoExecution()
                evidence("actualMetadataParseFailure", "realSharedFallback", "explicitAbsence", "validPreferencesContinued", "validResourcesContinued")
                    .put("partialWarningModelVerified", true).put("yellowWarningVisualVerified", false)
            }

            val legacy = open("unknown-selector")
            val legacyCurrent = synthetic(run, "legacy-live")
            seed(legacy.context, legacyCurrent, other)
            report.case("legacy-shared-metadata-with-unknown-official-selection-does-not-clear-live-native-key") {
                val unknown = shared(null).apply { remove("officialRef") }
                val archive = legacy.rawArchive("unknown.dym", mapOf(
                    DymArchiveStore.PROVIDER_CONFIGURATION to "{".toByteArray(),
                    DymArchiveStore.SHARED_CREDENTIALS to unknown.toString().toByteArray(),
                    DymArchiveStore.ANDROID_PREFERENCES to officialPreferences(true).toString().toByteArray(),
                    ".dsh/skills/unknown/SKILL.md" to SKILL_CONTENT.toByteArray()))
                legacy.import(archive) { prepared, restored ->
                    check(!DymSharedCredentials.officialSelection(checkNotNull(prepared.archive?.sharedCredentials)).known)
                    check(restored.descriptions.none { it == "提供商配置与凭据" } && restored.warnings.isNotEmpty())
                }
                requirePair(legacy.context, legacyCurrent)
                legacy.requirePreferenceAndSkill("unknown")
                legacy.requireNoExecution()
                evidence("unknownIsNotDeletion", "liveNativeValuePreserved", "preferencesAndResourcesContinued")
            }

            val blocked = open("pending")
            val before = synthetic(run, "pending-before")
            val planned = synthetic(run, "pending-planned")
            val unrelated = synthetic(run, "later-own-fixture-write")
            seed(blocked.context, before, other)
            report.case("real-atomic-journal-lost-ack-and-later-fixture-write-produce-durable-native-changed") {
                val lostAck = LostAcknowledgement(AndroidOfficialCredentialJournal(blocked.context))
                val denied = DeniedDshPort()
                val first = transaction(blocked.context, lostAck, denied).beginNative(planned)
                requirePending(first, "STORAGE_OR_TRANSPORT_PENDING")
                check(lostAck.injected && denied.attempts == 0)
                val pending = AndroidOfficialCredentialJournal(blocked.context).read().getJSONObject("pending")
                check(pending.getString("step") == "NATIVE_PROVIDER_CONFIRMED")
                check(ModelProviderStore(blocked.context).officialCredentialSnapshot().key == planned)
                check(BalanceStore(blocked.context).officialCredentialSnapshot().key == before)
                // Deliberate serial mutation of this UUID's real vault, never a common/user vault.
                ModelProviderStore(blocked.context).upsert(ModelProviderConfig.DEEPSEEK, apiKey(unrelated))
                val recovered = binding(blocked.context).coordinator().recover()
                requirePending(recovered, "NATIVE_CHANGED")
                check(AndroidOfficialCredentialJournal(blocked.context).read().has("pending"))
                evidence("realAtomicFile", "durablePending", "noDshCalls", "serialFixtureConflict")
                    .put("injectedFault", "lost-ack-after-durable-native-provider-confirmation")
                    .put("outcome", "Pending").put("code", "NATIVE_CHANGED")
            }
            report.case("production-coordinator-pending-provider-failure-leaves-vaults-and-imports-preferences-and-resources") {
                val protected = encryptedFiles(blocked.context).associateWith { it.readBytes() }
                try {
                    val archive = blocked.archive("blocked.dym", DymExportInput(
                        directories = blocked.skill("pending"), androidPreferences = officialPreferences(true),
                        providerConfiguration = portable(synthetic(run, "must-not-import"), other)))
                    blocked.import(archive) { _, restored ->
                        check(restored.descriptions.none { it == "提供商配置与凭据" })
                        check(restored.warnings.any { it == "提供商配置导入失败，其他有效内容已继续导入" ||
                            it == "提供商配置或官方凭据尚未确认，其他有效内容已继续导入" })
                        check(restored.descriptions.any { it.startsWith("手机偏好") })
                        check(restored.descriptions.any { it.startsWith("已恢复") })
                    }
                    protected.forEach { (file, bytes) -> check(file.readBytes().contentEquals(bytes)) }
                    check(AndroidOfficialCredentialJournal(blocked.context).read().has("pending"))
                    check(ModelProviderStore(blocked.context).officialCredentialSnapshot().key == unrelated)
                    check(BalanceStore(blocked.context).officialCredentialSnapshot().key == before)
                    blocked.requirePreferenceAndSkill("pending")
                    val refused = try { blocked.repository.exportDymProviderConfiguration(); null }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: IllegalStateException) { error }
                    check(refused != null)
                    blocked.requireNoExecution()
                    evidence("nativeCiphertextsUnchanged", "pendingNotDiscarded", "providerFailureReported", "validPreferencesContinued", "validResourcesContinued")
                        .put("partialWarningModelVerified", true).put("yellowWarningVisualVerified", false)
                } finally { protected.values.forEach { it.fill(0) } }
            }
            workPassed = true
        } catch (error: Throwable) {
            failure = error
            report.failure(error)
        } finally {
            withContext(NonCancellable) {
                try {
                    sessions.asReversed().forEach { it.close() }
                    requireIdle(commonRuntime, target.filesDir, target.noBackupFilesDir)
                    owned?.cleanup()
                    report.document.put("cleanupComplete", true)
                } catch (cleanup: Throwable) {
                    report.document.put("cleanupComplete", false).put("cleanupErrorClass", cleanup.javaClass.name)
                    if (failure == null) failure = cleanup else failure?.addSuppressed(cleanup)
                }
                try {
                    common.verify()
                    report.document.put("commonStatePreserved", true).put("commonKeystoreAliasesPreserved", true)
                } catch (guardFailure: Throwable) {
                    report.document.put("commonStatePreserved", false)
                    if (failure == null) failure = guardFailure else failure?.addSuppressed(guardFailure)
                }
                report.document.put("passed", workPassed && failure == null).put("completed", true)
                    .put("finishedAt", System.currentTimeMillis())
                report.persist()
            }
        }
        failure?.let { throw it }
    }

    private class Session(val context: Context, private val target: Context) {
        val runtime = LinuxRuntimeManager(context)
        private val installer = DshRuntimeInstaller(context, runtime)
        private val bridge = DshSharedPersistenceBridge(context, runtime, installer)
        private val coordinator = DymBackupCoordinator(context, runtime, installer, bridge)
        private val codec = DymArchiveStore(context.filesDir)
        private val holder = ViewModelStore()
        lateinit var repository: DemoRepository
            private set
        private var repositoryJob: Job? = null
        private var closed = false

        suspend fun initialize(reopen: Boolean) {
            val prefs = context.getSharedPreferences("dafeiyu_settings", Context.MODE_PRIVATE)
            if (!reopen) check(prefs.edit().putString("engine_mode", "official").putString("connection_mode", "local")
                .putBoolean("notifications_enabled", false).putBoolean("auto_start_dsh", false)
                .putBoolean("keep_running", false).putBoolean("sync_workspace", false).putBoolean("web_broadcast", false)
                .putString(SettingsRuntimeStore.KEY_UPDATE_POLICY, "off").putString("dsh_update_policy", "off").commit())
            check(prefs.getString("engine_mode", null) == "official" && prefs.getString("connection_mode", null) == "local" &&
                !prefs.getBoolean("notifications_enabled", true) && !prefs.getBoolean("auto_start_dsh", true))
            check(installer.current() == null && !File(context.filesDir, "linux-runtime/active.json").exists())
            withContext(Dispatchers.Main.immediate) {
                repository = DemoRepository().also { holder.put("dym-official", it) }
                repository.suppressDshAutostartAfterImport()
                repository.initializeWorkspaces(context, runtime, installer)
                repositoryJob = repository.viewModelScope.coroutineContext[Job]
            }
            withTimeout(30_000) {
                while (!withContext(Dispatchers.Main.immediate) { repository.workspaceInitialized && repository.backgroundTasks != null &&
                        repository.officialCredentialState.nativeReady && !repository.deepSeekConfigurationBusy }) delay(50)
            }
            ModelRuntimeController.get(context).refresh()
            requireNoExecution()
        }

        fun skill(name: String): Map<String, File> {
            require(name.matches(Regex("[a-z-]+")))
            val source = File(context.filesDir, "input-skill-$name").apply { check(mkdir()) }
            val skill = File(source, name).apply { check(mkdir()) }
            File(skill, "SKILL.md").writeText(SKILL_CONTENT, Charsets.UTF_8)
            return mapOf(".dsh/skills" to source)
        }

        suspend fun archive(name: String, input: DymExportInput): File = codec.export(output(name), input).also(::requireSevenZip)

        suspend fun rawArchive(name: String, entries: Map<String, ByteArray>): File = withContext(Dispatchers.IO) {
            val file = output(name)
            SevenZOutputFile(file).use { archive ->
                for ((path, bytes) in entries) {
                    val entry = SevenZArchiveEntry().apply { this.name = path; size = bytes.size.toLong(); isDirectory = false }
                    archive.putArchiveEntry(entry); archive.write(bytes); archive.closeArchiveEntry()
                }
            }
            requireSevenZip(file)
            file
        }

        suspend fun import(file: File, assert: suspend (PreparedDymBackup, DymRestoreReport) -> Unit) {
            val prepared = coordinator.prepare(Uri.fromFile(file))
            try {
                val report = withContext(Dispatchers.Main.immediate) { coordinator.restore(prepared, repository) }
                assert(prepared, report)
            } finally { withContext(NonCancellable) { coordinator.discard(prepared) } }
        }

        suspend fun verifyExport(name: String, official: String?, other: String) {
            val file = output(name)
            val exported = withContext(Dispatchers.Main.immediate) {
                coordinator.exportReport(Uri.fromFile(file), repository, DymBackupSelection(
                    conversations = false, providers = true, resources = true, preferences = true))
            }
            check(exported.bytes > 0 && exported.bytes == file.length())
            requireSevenZip(file)
            val staged = codec.stageImport(file, { AndroidBackupStore().import(it) })
            try {
                val configuration = checkNotNull(staged.providerConfiguration)
                check(OfficialCredentialPortable.key(configuration) == official && !configuration.has("officialCredentialReceipt"))
                check(configuration.getJSONObject("credentials").getJSONObject(OTHER_ID).getString("key") == other)
                val shared = checkNotNull(staged.sharedCredentials)
                val selected = DymSharedCredentials.officialSelection(shared)
                check(selected.known && selected.key == official)
                if (official == null) check(!shared.has("launcherDeepSeekKey") && !shared.getJSONObject("refs").has(checkNotNull(selected.reference)))
                val androidPreferences = checkNotNull(staged.androidPreferences)
                val pcPreferences = checkNotNull(staged.pcPreferences)
                check(androidPreferences.getJSONObject("groups").getJSONObject("official").getBoolean("tokenSavingMode"))
                check(pcPreferences.getJSONObject("groups").getJSONObject("appearance").getString("Theme") == "fixture-pc-theme")
                check(staged.groupFiles["skills"].orEmpty().isNotEmpty())
            } finally { codec.discard(staged) }
        }

        suspend fun requirePreferenceAndSkill(name: String) {
            check(withContext(Dispatchers.Main.immediate) { repository.tokenSavingMode })
            check(context.getSharedPreferences("dafeiyu_settings", Context.MODE_PRIVATE)
                .getBoolean(SettingsRuntimeStore.KEY_TOKEN_SAVING_MODE, false))
            check(File(context.filesDir, "dsh-home/skills/$name/SKILL.md").readText() == SKILL_CONTENT)
        }

        suspend fun requireNoExecution() {
            requireIdle(runtime, context.filesDir, context.noBackupFilesDir, context.cacheDir, target.filesDir, target.noBackupFilesDir)
            check(installer.current() == null)
            check(!ModelRuntimeController.get(context).state.value.workerRunning)
            check(ConnectPluginHost.get(context).snapshot.value.status == ConnectPluginHostStatus.STOPPED)
            check(withContext(Dispatchers.Main.immediate) { repository.connectionMode == "local" && repository.engineMode == "official" &&
                !repository.isResponding && !repository.sendPending && !repository.autoStartDsh &&
                !repository.notificationsEnabled && repository.backgroundTasks?.tasks?.value.orEmpty().isEmpty() })
        }

        suspend fun close() {
            if (closed) return
            withContext(Dispatchers.Main.immediate) { holder.clear() }
            withTimeout(20_000) { repositoryJob?.join() }
            ModelRuntimeController.get(context).shutdown(stopActive = true)
            ConnectPluginHost.get(context).stop()
            requireIdle(runtime, context.filesDir, context.noBackupFilesDir, context.cacheDir)
            closed = true
        }

        private fun output(name: String): File {
            require(name.matches(Regex("[a-z-]+\\.dym")))
            val file = File(context.filesDir, name)
            check(file.canonicalFile.parentFile == context.filesDir.canonicalFile && !file.exists() && !Files.isSymbolicLink(file.toPath()))
            return file
        }
    }

    private class LostAcknowledgement(private val actual: AndroidOfficialCredentialJournal) : OfficialCredentialJournalPort {
        var injected = false
            private set
        override val transactionMutex: Mutex get() = actual.transactionMutex
        override fun read(): JSONObject = actual.read()
        override fun compareAndSet(expectedRevision: Long, replacement: JSONObject): JSONObject {
            val persisted = actual.compareAndSet(expectedRevision, replacement)
            if (!injected && persisted.optJSONObject("pending")?.optString("step") == "NATIVE_PROVIDER_CONFIRMED") {
                injected = true
                throw IOException("QA_DYM_INJECTED_LOST_ACK")
            }
            return persisted
        }
    }

    /** Forbidden-call sentinel only; it cannot satisfy or fake any DSH result. */
    private class DeniedDshPort : OfficialDshCredentialPort {
        var attempts = 0
            private set
        private fun forbidden(): Nothing { attempts++; error("QA_DYM_MUST_NOT_CONTACT_DSH") }
        override suspend fun describe(): JSONObject = forbidden()
        override suspend fun credentials(): JSONObject = forbidden()
        override suspend fun stageReferences(candidates: JSONArray, expectedRevision: String): JSONObject = forbidden()
        override suspend fun mutate(namespace: String, expectedRevision: Long, reference: String): JSONObject = forbidden()
    }

    private class Report(target: Context, run: String) {
        private val directory = File(checkNotNull(target.getExternalFilesDir(null)).canonicalFile, "qa/dym-official-device-$run")
        private val cases = JSONArray()
        val document = JSONObject().put("schema", 1).put("runId", run).put("package", target.packageName)
            .put("pid", Process.myPid()).put("sdk", Build.VERSION.SDK_INT).put("abi", Build.SUPPORTED_ABIS.first())
            .put("passed", false).put("completed", false).put("startedAt", System.currentTimeMillis())
            .put("fixtureOnly", true).put("explicitModelCalls", 0).put("explicitPaidModelCalls", 0)
            .put("explicitRemoteServiceCalls", 0).put("runtimeInstalls", 0).put("permissionGrants", 0)
            .put("activityLaunches", 0).put("actualProcessRestart", false).put("networkTrafficInstrumentation", false)
            .put("results", cases)
        init {
            check(!directory.exists() && !Files.isSymbolicLink(directory.toPath()))
            check(directory.mkdirs() && directory.canonicalPath == directory.absolutePath)
            persist()
        }
        fun persist() {
            val atomic = AtomicFile(File(directory, "report.json"))
            val output = atomic.startWrite()
            try { output.write(document.toString(2).toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
            catch (error: Throwable) { atomic.failWrite(output); throw error }
        }
        suspend fun case(name: String, action: suspend () -> JSONObject) {
            val row = JSONObject().put("name", name).put("passed", false).put("startedAt", System.currentTimeMillis())
            cases.put(row); persist()
            try { row.put("evidence", withTimeout(60_000) { action() }).put("passed", true) }
            catch (error: Throwable) { safeFailure(row, error); throw error }
            finally { row.put("finishedAt", System.currentTimeMillis()); persist() }
        }
        fun failure(error: Throwable) { safeFailure(document, error); persist() }
        private fun safeFailure(row: JSONObject, error: Throwable) {
            row.put("errorClass", error.javaClass.name)
            if (error is SafeOutcomeFailure) row.put("outcome", error.outcome).put("code", error.code)
            row.put("codeLocations", JSONArray().also { frames ->
                error.stackTrace.filter { it.className.startsWith("com.dafeiyu.mobile.data.DymOfficialCredentialDeviceSmoke") }
                    .take(6).forEach { frames.put(JSONObject().put("class", it.className).put("method", it.methodName)
                        .put("file", it.fileName ?: "unknown").put("line", it.lineNumber)) }
            })
        }
    }

    private class CommonStorageGuard(target: Context) {
        private val protected = listOf(
            File(target.noBackupFilesDir, "model-providers.v1.enc"), File(target.noBackupFilesDir, ".deepseek_api_key.v1.bin"),
            File(target.noBackupFilesDir, "official-deepseek-transaction.v1.enc"), File(target.filesDir, "dsh-provider-order/order.json"),
            File(target.filesDir, "dsh-home/.credentials.yaml"), File(target.filesDir, "dsh-home/cordis.patch.yml"),
            File(target.filesDir, "dsh-home/settings.yaml"), File(target.filesDir, "dsh-home/settings.yaml.imported"),
            File(target.filesDir, "dsh-home/profiles/web/cordis.patch.yml"), File(target.filesDir, "dsh-home/profiles/web/cordis.yml"),
            File(target.filesDir, "dsh-home/profiles/web/package.json"), File(target.filesDir, "linux-runtime/active.json"),
            File(target.filesDir, "dsh-runtime/active.json"), File(target.filesDir, "dsh-runtime/previous.json"),
            File(target.filesDir, "dym-retained/active.json"), File(target.applicationInfo.dataDir, "shared_prefs/dafeiyu_settings.xml"),
        ).flatMap { listOf(it, File(it.path + ".bak"), File(it.path + ".new")) }
        // Equality-only guard: never parse or use common data, emit digests or credential values.
        private val before = protected.associateWith { fingerprint(it) }
        private val aliases = keyStore().aliases().toList().toSet()
        fun verify() {
            before.forEach { (file, original) -> check(fingerprint(file) == original) { "QA_DYM_COMMON_STATE_CHANGED" } }
            check(keyStore().aliases().toList().toSet().containsAll(aliases)) { "QA_DYM_COMMON_ALIAS_REMOVED" }
        }
    }

    private class OwnedRun private constructor(private val target: Context, private val run: String,
                                               private val files: File, private val vault: File, private val cache: File) {
        private val marker = File(files, "owner.json")
        private val names = linkedSetOf<String>()
        private val markerLock = Any()
        fun context(name: String, reopen: Boolean): Context {
            require(name.matches(Regex("[a-z-]{1,48}")))
            fun child(parent: File): File = File(parent, name).also {
                check(it.canonicalFile.parentFile == parent.canonicalFile && !Files.isSymbolicLink(it.toPath()))
                check(if (reopen) it.isDirectory else !it.exists() && it.mkdir())
            }
            val childFiles = child(files); val childVault = child(vault); val childCache = child(cache)
            return object : ContextWrapper(target) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = childFiles
                override fun getNoBackupFilesDir(): File = childVault
                override fun getCacheDir(): File = childCache
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    require(name.matches(Regex("[A-Za-z0-9_.-]{1,128}")))
                    val scoped = "dym-official-qa-$run-${childFiles.name}-$name"
                    synchronized(markerLock) { if (names.add(scoped)) writeMarker() }
                    return target.getSharedPreferences(scoped, mode)
                }
            }
        }
        private fun writeMarker() {
            marker.writeText(JSONObject().put("schema", 1).put("suite", CATEGORY).put("runId", run)
                .put("package", target.packageName).put("files", files.absolutePath).put("vault", vault.absolutePath)
                .put("cache", cache.absolutePath).put("preferences", JSONArray(names.toList())).toString(), Charsets.UTF_8)
        }
        fun cleanup() {
            val proof = JSONObject(marker.readText())
            check(proof.getInt("schema") == 1 && proof.getString("suite") == CATEGORY && proof.getString("runId") == run &&
                proof.getString("package") == target.packageName && proof.getString("files") == files.absolutePath &&
                proof.getString("vault") == vault.absolutePath && proof.getString("cache") == cache.absolutePath)
            for (name in names) {
                check(name.startsWith("dym-official-qa-$run-"))
                check(target.deleteSharedPreferences(name))
            }
            for ((base, root) in listOf(target.noBackupFilesDir to vault, target.cacheDir to cache, target.filesDir to files)) {
                val expected = File(base.canonicalFile, "$CATEGORY/$run")
                check(root.absolutePath == expected.absolutePath && root.canonicalPath == expected.absolutePath && !Files.isSymbolicLink(root.toPath()))
                removeOwnedTree(root)
                check(!root.exists())
            }
        }
        companion object {
            fun create(target: Context, run: String): OwnedRun {
                val roots = listOf(target.filesDir, target.noBackupFilesDir, target.cacheDir).map { File(it.canonicalFile, "$CATEGORY/$run") }
                roots.forEach { check(it.canonicalPath == it.absolutePath && !it.exists() && !Files.isSymbolicLink(it.toPath())) }
                val created = mutableListOf<File>()
                try {
                    roots.forEach { check(it.mkdirs()); created += it }
                    return OwnedRun(target, run, roots[0], roots[1], roots[2]).also { it.writeMarker() }
                } catch (error: Throwable) {
                    created.asReversed().forEach(::removeOwnedTree)
                    throw error
                }
            }
        }
    }

    private class SafeOutcomeFailure(val outcome: String, val code: String) : IllegalStateException("QA_DYM_UNEXPECTED_OUTCOME")

    private companion object {
        const val CATEGORY = "dym-official-device"
        const val OTHER_ID = "dym-offline"
        const val SELECTED_REF = "QA_DYM_OFFICIAL_SELECTED"
        const val SKILL_CONTENT = "# UUID fixture skill\nOnly data; no task or script is run.\n"
        fun target(): Context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext.also {
            check(it.packageName == "com.dafeiyu.mobile.dev" && it.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
                Build.SUPPORTED_ABIS.first() == "x86_64" && Build.HARDWARE in setOf("ranchu", "goldfish")) { "QA_DYM_DEBUG_EMULATOR_ONLY" }
            check(InstrumentationRegistry.getArguments().getString("dym_qa_avd") == "Dafeiyu_QA_API_37" &&
                InstrumentationRegistry.getArguments().getString("dym_qa_opt_in") == "true") { "QA_DYM_OPT_IN_REQUIRED" }
        }
        fun runId(): String = checkNotNull(InstrumentationRegistry.getArguments().getString("dym_qa_run")).also {
            require(it.matches(Regex("[a-f0-9]{32}")))
        }
        fun synthetic(run: String, label: String) = "qa-dym-only-$run-$label"
        fun apiKey(value: String) = JSONObject().put("type", "api_key").put("key", value)
        fun otherProvider() = ModelProviderConfig(OTHER_ID, "Offline fixture provider", api = "openai-completions",
            baseUrl = "https://example.invalid/v1", modelsJson = "[{\"id\":\"fixture-model\",\"name\":\"Fixture\"}]").validated()
        fun portable(official: String?, other: String): JSONObject = JSONObject().put("schema", 1).put("revision", 0)
            .put("providers", JSONArray().put(otherProvider().toJson()).put(ModelProviderConfig.DEEPSEEK.toJson()))
            .put("credentials", JSONObject().put(OTHER_ID, apiKey(other)).apply { if (official != null) put("deepseek", apiKey(official)) })
        fun shared(official: String?): JSONObject = JSONObject().put("version", 1).put("officialRef", SELECTED_REF)
            .put("refs", JSONObject().apply { if (official != null) put(SELECTED_REF, official) })
            .put("records", JSONObject()).put("portableFiles", JSONObject())
        fun officialPreferences(save: Boolean) = JSONObject().put("schema", 1).put("groups", JSONObject().put("official", JSONObject()
            .put("streaming", true).put("showReasoning", true).put("expandActivities", false).put("tokenSavingMode", save)))
        fun pcPreferences() = JSONObject().put("schema", 1).put("groups", JSONObject()
            .put("appearance", JSONObject().put("Theme", "fixture-pc-theme")))
        fun evidence(vararg checks: String) = JSONObject().also { document -> checks.forEach { document.put(it, true) } }
        fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        fun binding(context: Context) = AndroidOfficialCredentialBinding(context, ModelProviderStore(context), BalanceStore(context),
            DshSharedPersistenceBridge(context))
        suspend fun seed(context: Context, official: String, other: String) {
            ModelProviderStore(context).upsert(otherProvider(), apiKey(other))
            val committed = binding(context).coordinator().beginNative(official)
            if (committed !is OfficialCredentialOutcome.NativeCommitted) throw SafeOutcomeFailure(committed.javaClass.simpleName,
                (committed as? OfficialCredentialOutcome.Pending)?.code ?: "QA_DYM_NATIVE_COMMIT_EXPECTED")
            requirePair(context, official)
        }
        fun requirePair(context: Context, expected: String?) {
            val provider = ModelProviderStore(context).officialCredentialSnapshot()
            val balance = BalanceStore(context).officialCredentialSnapshot()
            check(provider.key == expected && balance.key == expected && provider.transactionId != null && provider.transactionId == balance.transactionId)
            check(!AndroidOfficialCredentialJournal(context).read().has("pending"))
        }
        fun requireOther(context: Context, expected: String) {
            check(ModelProviderStore(context).workerConfiguration().getJSONObject("credentials").getJSONObject(OTHER_ID).getString("key") == expected)
        }
        fun requirePending(value: OfficialCredentialOutcome, code: String) {
            if (value !is OfficialCredentialOutcome.Pending || value.code != code) throw SafeOutcomeFailure(value.javaClass.simpleName,
                (value as? OfficialCredentialOutcome.Pending)?.code ?: "QA_DYM_PENDING_EXPECTED")
        }
        fun transaction(context: Context, journal: OfficialCredentialJournalPort, dsh: OfficialDshCredentialPort): OfficialDeepSeekCredentialTransaction {
            val providers = ModelProviderStore(context); val balance = BalanceStore(context)
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
        fun encryptedFiles(context: Context) = listOf("model-providers.v1.enc", ".deepseek_api_key.v1.bin", "official-deepseek-transaction.v1.enc")
            .map { File(context.noBackupFilesDir, it).also { file -> check(file.isFile) } }
        suspend fun requireIdle(runtime: LinuxRuntimeManager, vararg roots: File) = withTimeout(20_000) {
            while (runtime.managedProcesses.value.isNotEmpty() || roots.any(runtime::hasProcessLease)) delay(50)
        }
        fun requireSevenZip(file: File) {
            val magic = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
            file.inputStream().use { input -> val actual = ByteArray(6); check(input.read(actual) == 6 && actual.contentEquals(magic)) }
        }
        fun fingerprint(file: File): String {
            if (!file.exists()) return "absent"
            check(file.isFile && file.length() <= 64 * 1024 * 1024 && !Files.isSymbolicLink(file.toPath()))
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(file.inputStream(), digest).use { input ->
                val buffer = ByteArray(8192)
                try { while (input.read(buffer) >= 0) { /* equality-only guard */ } } finally { buffer.fill(0) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        fun removeOwnedTree(root: File) {
            val path = root.toPath()
            check(root.canonicalPath == root.absolutePath && !Files.isSymbolicLink(path))
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
    }
}
