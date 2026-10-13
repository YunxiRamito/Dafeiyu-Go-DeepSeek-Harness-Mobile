package com.dafeiyu.mobile.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
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

/** True production .dym/Repo/Keystore/preferences/shared-history roundtrip from an isolated PC archive.
 * Reads only the test fixture and already verified runtime. Never submits a prompt or paid request. */
@RunWith(AndroidJUnit4::class)
class DymCrossPlatformSmoke {
    @Test
    fun pcArchiveAndActualAndroidStores(): Unit = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        check(target.packageName == "com.dafeiyu.mobile.dev" && android.os.Build.SUPPORTED_ABIS.first() == "x86_64")
        val external = checkNotNull(target.getExternalFilesDir(null)).canonicalFile
        val incoming = File(checkNotNull(InstrumentationRegistry.getArguments().getString("pc_archive"))).canonicalFile
        check(incoming.isFile && incoming.parentFile == external && incoming.name == "dym-parity-input.dym")
        val id = UUID.randomUUID().toString()
        val files = File(target.filesDir, "dym-device-smoke/$id").apply { check(mkdirs()) }
        val vault = File(target.noBackupFilesDir, "dym-device-smoke/$id").apply { check(mkdirs()) }
        val cache = File(target.cacheDir, "dym-device-smoke/$id").apply { check(mkdirs()) }
        val names = mutableSetOf<String>()
        val isolated = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getNoBackupFilesDir(): File = vault
            override fun getCacheDir(): File = cache
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val scoped = "dym-device-$id-$name"; names += scoped
                return target.getSharedPreferences(scoped, mode)
            }
        }
        val preferences = isolated.getSharedPreferences("dafeiyu_settings", Context.MODE_PRIVATE)
        check(preferences.edit().putString("engine_mode", "official").putString("connection_mode", "local")
            .putBoolean("notifications_enabled", false).putBoolean("auto_start_dsh", false)
            .putString(SettingsRuntimeStore.KEY_UPDATE_POLICY, "off").commit())
        val runtime = LinuxRuntimeManager(target)
        val installer = DshRuntimeInstaller(target, runtime)
        check(installer.current() != null)
        val bridge = DshSharedPersistenceBridge(isolated, runtime, installer)
        val service = DshRuntimeManager(isolated, runtime, installer)
        val coordinator = DymBackupCoordinator(isolated, runtime, installer, bridge)
        val holder = ViewModelStore()
        val repository = withContext(Dispatchers.Main) { DemoRepository().also { holder.put("dym-test", it) } }
        val reportFile = File(target.filesDir, "dym-cross-platform-smoke-report.json")
        val exportFile = File(external, "dym-parity-device-output.dym")
        val cases = JSONArray()
        val report = JSONObject().put("schema", 1).put("passed", false).put("paidModelRequests", 0)
            .put("checks", cases).put("startedAt", System.currentTimeMillis()).put("output", exportFile.absolutePath)
        fun persist() = reportFile.writeText(report.toString(2))
        suspend fun checkCase(name: String, action: suspend () -> Unit) {
            val row = JSONObject().put("name", name).put("passed", false); cases.put(row); persist()
            try { withTimeout(180_000) { action() }; row.put("passed", true) }
            catch (failure: Throwable) { row.put("error", failure.javaClass.simpleName); throw failure }
            finally { persist() }
        }
        suspend fun waitReady() = withTimeout(120_000) {
            while (!withContext(Dispatchers.Main) { repository.workspaceInitialized && repository.backgroundTasks != null }) delay(100)
        }
        suspend fun switch(engine: String) {
            withContext(Dispatchers.Main) { repository.updateEngineMode(engine) }
            withTimeout(150_000) { while (withContext(Dispatchers.Main) { repository.localEngineSwitchBusy }) delay(100) }
            withContext(Dispatchers.Main) { check(repository.localEngineSwitchError == null && repository.engineMode == engine) }
        }
        var prepared: PreparedDymBackup? = null
        try {
            checkCase("real Android decoder stages PC sevenzip and native compatibility metadata") {
                withContext(Dispatchers.Main) { repository.initializeWorkspaces(isolated, runtime, installer) }; waitReady()
                prepared = coordinator.prepare(Uri.fromFile(incoming))
                check(prepared!!.conversations?.conversations?.single()?.title == "Phone history")
                check(prepared!!.containsCredentials && prepared!!.archive?.sharedCredentials != null)
            }
            checkCase("production restore persists compatible history providers and native Android Keystore") {
                val base = checkNotNull(prepared)
                val phonePreferences = withContext(Dispatchers.Main) { AndroidDymPreferences.capture(repository) }
                phonePreferences.getJSONObject("groups").getJSONObject("appearance").put("themeMode", "invalid-choice")
                phonePreferences.getJSONObject("groups").getJSONObject("official").put("tokenSavingMode", true)
                val plan = AndroidDymPreferences.parse(phonePreferences)
                val restored = withContext(Dispatchers.Main) { coordinator.restore(base.copy(preferences = plan,
                    warnings = base.warnings + plan.warnings), repository) }
                check(restored.warnings.any { it.contains("偏好") })
                withContext(Dispatchers.Main) {
                    check(repository.messages.map { it.text } == listOf("fixture prompt", "fixture answer"))
                    check(repository.tokenSavingMode && repository.themeMode == "system")
                }
                val providers = ModelProviderStore(isolated)
                check(providers.read().providers.any { it.id == "om-fixture" })
                check(providers.workerConfiguration().getJSONObject("credentials").getJSONObject("deepseek").getString("key") == "fixture-launcher-key")
                check(BalanceStore(isolated).readApiKeyForRequest() == "fixture-launcher-key")
                check(!File(vault, "model-providers.v1.enc").readBytes().toString(Charsets.ISO_8859_1).contains("fixture-launcher-key"))
                check(WorkspaceStore(isolated).initialize()!!.messages.values.flatten().map { it.text }.contains("fixture answer"))
            }
            checkCase("actual storage-only helper restores original PC DSH home credentials with idle lease") {
                val credentials = bridge.readCredentialDocument()
                check(credentials.getJSONObject("refs").getString("DEEPSEEK_API_KEY") == "fixture-official-key")
                check(credentials.getJSONObject("refs").getString("FIXTURE_PROVIDER_KEY") == "fixture-other-key")
                check(!credentials.getJSONObject("refs").has("UNRELATED_IDENTITY"))
            }
            checkCase("restored .dym history reaches actual DSH persistence and returns without task replay") {
                switch("dsh")
                val session = withContext(Dispatchers.Main) { checkNotNull(repository.localDshSessionId) }
                val stored = bridge.readArtifact(File(files, "dsh-home/sessions"), session)
                check(DshSharedHistoryAdapter.decode(stored).compatibleMessages.map { it.text } == listOf("fixture prompt", "fixture answer"))
                val events = stored.getJSONArray("events")
                check((0 until events.length()).none { events.getJSONObject(it).getString("type").startsWith("tool/") })
                switch("official")
                check(service.snapshot.value.status == DshServiceStatus.STOPPED)
            }
            checkCase("real persisted preferences survive cold reading and foreign PC preferences remain separate") {
                val captured = withContext(Dispatchers.Main) { AndroidDymPreferences.capture(repository) }
                check(captured.getJSONObject("groups").getJSONObject("official").getBoolean("tokenSavingMode"))
                check(isolated.getSharedPreferences("dafeiyu_settings", Context.MODE_PRIVATE)
                    .getBoolean(SettingsRuntimeStore.KEY_TOKEN_SAVING_MODE, false))
                val retained = DymRetainedResourceStore(files).read()
                check(retained.pcPreferences!!.getJSONObject("groups").getJSONObject("appearance").getString("Theme") == "malformed-theme")
                check(retained.sharedCredentials!!.getJSONObject("refs").getString("FIXTURE_PROVIDER_KEY") == "fixture-other-key")
            }
            checkCase("production ContentResolver export yields PC-readable genuine sevenzip with keys and both platform preferences") {
                val codec = DymArchiveStore(files)
                val canonicalProbe = File(files, "canonical-probe").apply { check(mkdir()) }
                File(canonicalProbe, "SKILL.md").writeText("same private root")
                val canonicalArchive = codec.export(File(files.canonicalFile, "canonical-export.dym"),
                    DymExportInput(directories = mapOf("skills" to canonicalProbe.canonicalFile)))
                val canonicalStaged = codec.stageImport(canonicalArchive, {})
                try { check(canonicalStaged.groupFiles.getValue("skills").single().file.readText() == "same private root") }
                finally { codec.discard(canonicalStaged) }
                val result = withContext(Dispatchers.Main) { coordinator.exportReport(Uri.fromFile(exportFile), repository, DymBackupSelection()) }
                check(result.bytes > 0 && exportFile.isFile)
                val copied = File(files, "verify-export.dym"); exportFile.copyTo(copied, overwrite = true)
                val staged = codec.stageImport(copied, { AndroidBackupStore().import(it) })
                try {
                    check(staged.androidPreferences != null && staged.pcPreferences != null && staged.providerConfiguration != null && staged.sharedCredentials != null)
                    check(staged.providerConfiguration.getJSONObject("credentials").getJSONObject("deepseek").getString("key") == "fixture-launcher-key")
                    check(staged.androidBackup != null)
                } finally { codec.discard(staged) }
            }
            report.put("passed", true).put("finishedAt", System.currentTimeMillis())
        } finally {
            withContext(NonCancellable) {
                prepared?.let { coordinator.discard(it) }
                withContext(Dispatchers.Main) { holder.clear() }
                service.stop(); service.close()
                ModelRuntimeController.get(isolated).shutdown(); ConnectPluginHost.get(isolated).stop()
                withTimeout(15_000) { while (runtime.hasProcessLease(files) || runtime.hasProcessLease(vault)) delay(100) }
                check(files.deleteRecursively() && vault.deleteRecursively() && cache.deleteRecursively())
                names.forEach { check(target.deleteSharedPreferences(it)) }
                // Existing application AES aliases are shared by the platform store;
                // leave them intact rather than invalidating real application keys.
                report.put("testStoresRemoved", true).put("testRuntimeLeasesReleased", true).put("platformKeyAliasesPreserved", true)
                persist()
            }
        }
    }
}
