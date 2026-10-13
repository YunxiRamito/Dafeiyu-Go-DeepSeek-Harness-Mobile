package com.dafeiyu.mobile.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.webkit.WebView
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dafeiyu.mobile.ui.DshWebViewFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Real Repo, Keystore, storage helper, CLI/RPC and production WebView factory.
 * All stores except the verified, installed runtime are test-owned. No prompt
 * endpoint is called, no task is replayed and no production identity is changed. */
@RunWith(AndroidJUnit4::class)
class NativeEngineSwitchSmoke {
    @Test
    fun sharedHistoryAndWebBootstrap(): Unit = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        check(target.packageName == "com.dafeiyu.mobile.dev" && android.os.Build.SUPPORTED_ABIS.first() == "x86_64")
        val id = UUID.randomUUID().toString()
        val files = File(target.filesDir, "native-engine-smoke/$id").apply { check(mkdirs()) }
        val vault = File(target.noBackupFilesDir, "native-engine-smoke/$id").apply { check(mkdirs()) }
        val cache = File(target.cacheDir, "native-engine-smoke/$id").apply { check(mkdirs()) }
        val preferenceNames = mutableSetOf<String>()
        val isolated = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getNoBackupFilesDir(): File = vault
            override fun getCacheDir(): File = cache
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val scoped = "native-engine-$id-$name"
                preferenceNames += scoped
                return target.getSharedPreferences(scoped, mode)
            }
        }
        val settings = isolated.getSharedPreferences("dafeiyu_settings", Context.MODE_PRIVATE)
        check(settings.edit().putString("engine_mode", "official").putString("connection_mode", "local")
            .putBoolean("notifications_enabled", false).putBoolean("auto_start_dsh", false)
            .putString(SettingsRuntimeStore.KEY_UPDATE_POLICY, "off").commit())
        val runtime = LinuxRuntimeManager(target)
        val installer = DshRuntimeInstaller(target, runtime)
        check(installer.current() != null) { "Run the production runtime installation smoke first" }
        val bridge = DshSharedPersistenceBridge(isolated, runtime, installer)
        val service = DshRuntimeManager(isolated, runtime, installer)
        val holder = ViewModelStore()
        val repository = withContext(Dispatchers.Main) { DemoRepository().also { holder.put("smoke", it) } }
        val reportFile = File(target.filesDir, "native-engine-smoke-report.json")
        val cases = JSONArray()
        val report = JSONObject().put("schema", 1).put("passed", false).put("paidModelRequests", 0)
            .put("checks", cases).put("startedAt", System.currentTimeMillis())
        fun persist() { reportFile.writeText(report.toString(2)) }
        suspend fun checkCase(name: String, block: suspend () -> Unit) {
            val item = JSONObject().put("name", name).put("passed", false)
            cases.put(item); persist()
            try { withTimeout(180_000) { block() }; item.put("passed", true) }
            catch (failure: Throwable) { item.put("error", failure.javaClass.simpleName); throw failure }
            finally { persist() }
        }
        suspend fun awaitRepository(predicate: (DemoRepository) -> Boolean) = withTimeout(150_000) {
            while (!withContext(Dispatchers.Main) { predicate(repository) }) delay(100)
        }
        suspend fun switch(targetEngine: String) {
            withContext(Dispatchers.Main) { repository.updateEngineMode(targetEngine) }
            awaitRepository { !it.localEngineSwitchBusy }
            withContext(Dispatchers.Main) {
                check(repository.localEngineSwitchError == null) { repository.localEngineSwitchError.orEmpty() }
                check(repository.engineMode == targetEngine)
            }
        }
        var web: WebView? = null
        try {
            val nativeArtifact = DshSharedHistoryAdapter.fromNative("native-seed-$id", "Native history", "/workspace",
                listOf(ChatMessage(1, "user", "offline native question"), ChatMessage(2, "assistant", "offline native answer")),
                1_700_000_000_000L)
            val nativeMessages = DymHistoryConverter().compatibleWithActivities(DshSharedHistoryAdapter.decode(nativeArtifact))
                .map { ChatMessage(it.id, it.role, it.text, it.streamSnapshot, it.modelInput) }
            WorkspaceStore(isolated).save(WorkspaceSnapshot(listOf(Workspace("default", "Native QA")),
                listOf(Conversation("native-seed", "Native history", "default")),
                mapOf("native-seed" to nativeMessages), "default", "native-seed"))
            checkCase("real Repo restores isolated native history and context") {
                withContext(Dispatchers.Main) { repository.initializeWorkspaces(isolated, runtime, installer) }
                awaitRepository { it.workspaceInitialized && it.backgroundTasks != null }
                withContext(Dispatchers.Main) {
                    check(repository.messages.map { it.text } == listOf("offline native question", "offline native answer"))
                    check(OfficialChatHistory.build(repository.messages).map { it.content } == repository.messages.map { it.text })
                }
            }
            val marker = "SYNTHETIC_OFFLINE_KEY_$id"
            val providers = ModelProviderStore(isolated)
            val balance = BalanceStore(isolated)
            checkCase("native key save seals both stores and invalid save preserves them") {
                withContext(Dispatchers.Main) { repository.saveDeepSeekKey(marker) }
                awaitRepository { !it.deepSeekConfigurationBusy }
                check(balance.readApiKeyForRequest() == marker && providers.hasCredential("deepseek"))
                val sealed = File(vault, "model-providers.v1.enc").readBytes()
                check(!sealed.toString(Charsets.ISO_8859_1).contains(marker))
                withContext(Dispatchers.Main) { repository.saveDeepSeekKey("invalid key with whitespace") }
                awaitRepository { !it.deepSeekConfigurationBusy }
                check(balance.readApiKeyForRequest() == marker)
                check(providers.workerConfiguration().getJSONObject("credentials").getJSONObject("deepseek").getString("key") == marker)
            }
            checkCase("stale provider revision cannot overwrite a newer native edit") {
                val before = providers.exportPortable()
                val current = providers.reorder(providers.read().providers.map { it.id })
                var rejected = false
                try { providers.persistWorkerCredential("deepseek", null, before.getLong("revision")) }
                catch (_: IllegalArgumentException) { rejected = true }
                check(rejected && providers.read().revision == current.revision && providers.hasCredential("deepseek"))
            }
            var selected = ""
            var access = ""
            checkCase("real Repo switch starts DSH with persisted native history and credentials") {
                switch("dsh")
                withContext(Dispatchers.Main) {
                    check(repository.runtimeStatus == RuntimeStatus.Running)
                    selected = checkNotNull(repository.localDshSessionId)
                    access = checkNotNull(repository.runtimeAccessUrl)
                }
                val rpc = DshLocalConfigurationRpc()
                val sessions = rpc.call(access, "session/list", JSONObject().put("_request", JSONObject())).getJSONArray("items")
                val session = (0 until sessions.length()).map { sessions.getJSONObject(it) }.single { it.getString("sessionId") == selected }
                check(!session.getBoolean("running") && session.getString("cwd") == "/dafeiyu-workspaces/default")
                check(bridge.readCredentialDocument().toString().contains(marker))
                val stored = bridge.readArtifact(File(files, "dsh-home/sessions"), selected)
                check(DshSharedHistoryAdapter.decode(stored).compatibleMessages.map { it.text } == nativeMessages.map { it.text })
            }
            checkCase("production WebView bootstraps first-party selected session and loads its bundle") {
                val loadFailure = AtomicReference<JSONObject?>()
                web = withContext(Dispatchers.Main) { DshWebViewFactory.create(target, access, selected,
                    onMainFrameFailureCode = { code, http ->
                        loadFailure.set(JSONObject().put("code", code).put("http", http))
                    }) }
                val state = try { withTimeout(45_000) {
                    while (true) {
                        loadFailure.get()?.let { failure ->
                            report.put("webMainFrameFailure", failure); persist()
                            error("WebView main frame failed: code=${failure.getInt("code")}, http=${failure.getBoolean("http")}")
                        }
                        val result = evaluate(checkNotNull(web), "JSON.stringify((()=>{let selection=null,storage=true;try{selection=localStorage.getItem('dsh.sessions.current')}catch(e){storage=false}return {origin:location.origin,readyState:document.readyState,selection,storage,root:!!document.querySelector('#root')?.childElementCount,js:performance.getEntriesByType('resource').some(x=>x.name.includes('.js'))}})())")
                        val state = runCatching { JSONObject(JSONArray("[$result]").getString(0)) }.getOrNull()
                        state?.let { report.put("webBootstrapState", JSONObject().put("origin", it.optString("origin"))
                            .put("readyState", it.optString("readyState")).put("storage", it.optBoolean("storage"))
                            .put("root", it.optBoolean("root")).put("js", it.optBoolean("js"))) }
                        if (state?.optBoolean("root") == true && state.optBoolean("js")) return@withTimeout state
                        delay(200)
                    }
                    @Suppress("UNREACHABLE_CODE") JSONObject()
                } } finally { persist() }
                val origin = android.net.Uri.parse(access)
                check(state.getString("origin") == "http://127.0.0.1:${origin.port}")
                check(JSONObject(state.getString("selection")).getString("sessionId") == selected)
                withContext(Dispatchers.Main) { checkNotNull(web).stopLoading(); checkNotNull(web).destroy(); web = null }
            }
            checkCase("switch back safely commits shared native history without replay") {
                switch("official")
                check(service.snapshot.value.status == DshServiceStatus.STOPPED)
                withContext(Dispatchers.Main) {
                    check(repository.messages.map { it.text } == nativeMessages.map { it.text })
                    check(repository.localEngineSharingWarnings.isEmpty())
                }
            }
            val dshId = "dsh-source-$id"
            checkCase("real DSH history imports into native context and continues across both switches") {
                val artifact = DshSharedHistoryAdapter.fromNative(dshId, "DSH-origin history", "/dafeiyu-workspaces/default",
                    listOf(ChatMessage(10, "user", "offline DSH question"), ChatMessage(11, "assistant", "offline DSH answer")),
                    1_700_000_010_000L)
                check(bridge.writeCompatibleArtifact(artifact).getBoolean("created"))
                switch("dsh"); switch("official")
                withContext(Dispatchers.Main) {
                    val imported = repository.conversations.single { it.title == "DSH-origin history" }
                    repository.selectConversation(imported.id)
                    check(OfficialChatHistory.build(repository.messages).map { it.content } == listOf("offline DSH question", "offline DSH answer"))
                }
                switch("dsh")
                withContext(Dispatchers.Main) { check(repository.localDshSessionId == dshId) }
                switch("official")
                withContext(Dispatchers.Main) {
                    check(repository.messages.map { it.text } == listOf("offline DSH question", "offline DSH answer"))
                    check(repository.localEngineSharingWarnings.isEmpty())
                }
            }
            checkCase("real Repo permanent deletion survives both engine switches and cold history reconstruction") {
                val removedId = withContext(Dispatchers.Main) {
                    val imported = repository.conversations.single { it.title == "DSH-origin history" }
                    check(repository.archiveConversation(imported.id))
                    check(repository.deleteConversation(imported.id))
                    imported.id
                }
                withTimeout(120_000) {
                    while (withContext(Dispatchers.Main) { repository.archiveOperationBusy }) delay(100)
                }
                withContext(Dispatchers.Main) {
                    check(repository.conversations.none { it.id == removedId }) {
                        "Repository did not confirm permanent deletion: ${repository.localArchiveDeletionDetail}"
                    }
                }
                val committed = checkNotNull(WorkspaceStore(isolated).initialize())
                check(committed.conversations.none { it.id == removedId } && removedId !in committed.messages)
                switch("dsh"); switch("official")
                withContext(Dispatchers.Main) {
                    check(repository.conversations.none { it.id == removedId || it.title == "DSH-origin history" })
                    check(repository.localEngineSharingWarnings.isEmpty())
                }
                val cold = LocalDshHistoryController(isolated, bridge).read(checkNotNull(WorkspaceStore(isolated).initialize()))
                check(cold.snapshot.conversations.none { it.id == removedId || it.title == "DSH-origin history" })
                check(cold.warnings.isEmpty())
            }
            report.put("passed", true).put("finishedAt", System.currentTimeMillis())
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) { web?.stopLoading(); web?.destroy(); web = null; holder.clear() }
                service.stop(); service.close()
                ModelRuntimeController.get(isolated).shutdown()
                ConnectPluginHost.get(isolated).stop()
                withTimeout(15_000) { while (runtime.hasProcessLease(files) || runtime.hasProcessLease(vault)) delay(100) }
                check(files.deleteRecursively() && vault.deleteRecursively() && cache.deleteRecursively())
                preferenceNames.forEach { check(target.deleteSharedPreferences(it)) }
                report.put("testStoresRemoved", true); persist()
            }
        }
    }

    private suspend fun evaluate(view: WebView, script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation -> view.evaluateJavascript(script) { value ->
            if (continuation.isActive) continuation.resume(value)
        } }
    }
}
