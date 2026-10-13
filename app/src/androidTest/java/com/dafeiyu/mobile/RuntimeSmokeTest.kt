package com.dafeiyu.mobile

import android.content.Context
import android.content.ContextWrapper
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dafeiyu.mobile.data.DshRuntimeInstaller
import com.dafeiyu.mobile.data.DshRuntimeManager
import com.dafeiyu.mobile.data.DshRuntimeManifest
import com.dafeiyu.mobile.data.DshLocalConfigurationRpc
import com.dafeiyu.mobile.data.DshSandboxProbe
import com.dafeiyu.mobile.data.DshServiceStatus
import com.dafeiyu.mobile.data.DshSharedConfigurationAdapter
import com.dafeiyu.mobile.data.DshSharedHistoryAdapter
import com.dafeiyu.mobile.data.DshSharedPersistenceBridge
import com.dafeiyu.mobile.data.DshExtensionTransaction
import com.dafeiyu.mobile.data.ChatMessage
import com.dafeiyu.mobile.data.LinuxComponentStatus
import com.dafeiyu.mobile.data.LinuxArchiveExtractor
import com.dafeiyu.mobile.data.LinuxGuestMounts
import com.dafeiyu.mobile.data.LinuxManagedProcess
import com.dafeiyu.mobile.data.LinuxRuntimeManager
import com.dafeiyu.mobile.data.LinuxSandboxContract
import com.dafeiyu.mobile.data.LinuxSandboxVerifier
import com.dafeiyu.mobile.data.ModelProviderConfig
import com.dafeiyu.mobile.data.ModelProviderStore
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Runs only in the separate .dev app on the dedicated x86_64 QA emulator.
 * Inputs are staged by run-as, verified and copied to production download
 * caches; no model, API credential or paid request is used. */
@RunWith(AndroidJUnit4::class)
class RuntimeSmokeTest {
    @Test
    fun cachedRuntimeAndDsh(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        check(context.packageName == "com.dafeiyu.mobile.dev") { "Runtime smoke is restricted to the separate debug application" }
        check(android.os.Build.SUPPORTED_ABIS.firstOrNull() == "x86_64") { "Runtime smoke requires native x86_64 QA" }
        val input = File(context.cacheDir, "runtime-smoke-input")
        val reportFile = File(context.filesDir, "runtime-smoke-report.json")
        val results = JSONArray()
        val report = JSONObject().put("schema", 1).put("package", context.packageName)
            .put("abi", android.os.Build.SUPPORTED_ABIS.first()).put("started_at", System.currentTimeMillis())
            .put("paidModelRequests", 0).put("checks", results).put("passed", false)
        val runtime = LinuxRuntimeManager(context)
        val dshInstaller = DshRuntimeInstaller(context, runtime)
        val dsh = DshRuntimeManager(context, runtime, dshInstaller)
        fun persist() { reportFile.writeText(report.toString(2), Charsets.UTF_8) }
        suspend fun checkCase(name: String, block: suspend () -> JSONObject) {
            val entry = JSONObject().put("name", name).put("passed", false)
            results.put(entry)
            persist()
            try {
                entry.put("evidence", withTimeout(240_000) { block() }).put("passed", true)
            } catch (failure: Throwable) {
                entry.put("error", failure.javaClass.simpleName).put("detail", failure.message?.take(1000))
                throw failure
            } finally { persist() }
        }
        try {
            checkCase("bounded repair of legacy empty mount targets refuses data and links") {
                verifyMountTargets(context)
            }
            val guest = context.assets.open("linux-runtime/x86_64-release.json").bufferedReader().use { JSONObject(it.readText()) }
            checkCase("stage verified guest archive into production cache") {
                val target = File(context.filesDir, "linux-runtime/downloads/${guest.getString("runtimeId")}.tar.gz")
                copyVerified(File(input, "guest.tar.gz"), target, guest.getLong("archiveSize"), guest.getString("archiveSha256"))
                JSONObject().put("size", target.length()).put("sha256", guest.getString("archiveSha256"))
            }
            checkCase("install actual x86_64 Ubuntu and all five components") {
                runtime.install("ubuntu", "official")
                runtime.refresh()
                check(runtime.snapshot.value.size == 5 && runtime.snapshot.value.all { it.status == LinuxComponentStatus.READY }) {
                    runtime.snapshot.value.joinToString { "${it.id}: ${it.status}: ${it.detail}" }
                }
                JSONObject().put("components", JSONArray(runtime.snapshot.value.map { state ->
                    JSONObject().put("id", state.id).put("status", state.status.name).put("version", state.version)
                }))
            }
            val workspace = File(context.filesDir, "linux-workspaces/runtime-smoke").apply { check(isDirectory || mkdirs()) }
            checkCase("actual Python SSL SQLite readline and script output") {
                val result = runtime.execute("python", "import ssl,sqlite3,readline\nprint(2+2)\n", workspace, 10)
                check(result.exitCode == 0 && !result.timedOut && result.stdout.trim() == "4") { result.toString() }
                JSONObject().put("exitCode", result.exitCode).put("stdout", result.stdout)
            }
            checkCase("actual Node ABI and script output") {
                val result = runtime.execute("node", "console.log(process.platform + '/' + process.arch);console.log(2+2);", workspace, 10)
                check(result.exitCode == 0 && !result.timedOut && result.stdout.trim() == "linux/x64\n4") { result.toString() }
                JSONObject().put("exitCode", result.exitCode).put("stdout", result.stdout)
            }
            checkCase("upgrade after actual PRoot bindings retains guest and workspace data") {
                val marker = "mount-upgrade-${UUID.randomUUID()}"
                val local = File(workspace, "mount-upgrade-marker").apply { writeText(marker) }
                val before = runtime.execute("bash", "printf '%s' '$marker' > /root/mount-upgrade-marker\n", workspace, 10)
                check(before.exitCode == 0 && !before.timedOut)
                runtime.install("ubuntu", "official")
                val after = runtime.execute("bash", "cat /root/mount-upgrade-marker\n", workspace, 10)
                check(after.exitCode == 0 && !after.timedOut && after.stdout == marker && local.readText() == marker) {
                    "Runtime upgrade failed to preserve guest or mounted workspace data"
                }
                JSONObject().put("upgradeAfterBindings", true).put("guestDataRetained", true).put("workspaceDataRetained", true)
            }
            checkCase("actual shell output and nonzero status") {
                val result = runtime.execute("bash", "printf 'smoke-ok\\n'\nexit 17\n", workspace, 10)
                check(result.exitCode == 17 && !result.timedOut && result.stdout.trim() == "smoke-ok") { result.toString() }
                JSONObject().put("exitCode", result.exitCode).put("stdout", result.stdout)
            }
            checkCase("actual process timeout cleans tracer and permits another command") {
                val result = runtime.execute("node", "setInterval(()=>{},1000);", workspace, 1)
                check(result.timedOut && result.exitCode == 124) { result.toString() }
                val after = runtime.execute("node", "console.log('after-timeout');", workspace, 10)
                check(after.exitCode == 0 && after.stdout.trim() == "after-timeout") { after.toString() }
                JSONObject().put("timeoutExit", result.exitCode).put("nextExit", after.exitCode)
            }
            checkCase("actual process cancellation cleans tracer") {
                val running = async { runtime.execute("node", "setInterval(()=>{},1000);", workspace, 30) }
                delay(1500)
                running.cancelAndJoin()
                check(running.isCancelled)
                val after = runtime.execute("node", "console.log('after-cancel');", workspace, 10)
                check(after.exitCode == 0 && after.stdout.trim() == "after-cancel") { after.toString() }
                JSONObject().put("cancelled", true).put("nextExit", after.exitCode)
            }
            checkCase("actual independent process leases reject conflicting launches until exit") {
                verifyManagedLeases(runtime)
            }
            checkCase("actual light script file modes and inherited restrictions") {
                val proof = verifyLightSandbox(context, runtime, workspace)
                report.put("lightSandboxVerified", true)
                proof
            }
            val manifest = DshRuntimeManifest.fromJson(JSONObject(File(input, "dsh-manifest.json").readText(Charsets.UTF_8)))
            check(manifest.abi == "x86_64")
            checkCase("stage verified complete DSH dependency archive") {
                val target = File(context.filesDir, "dsh-runtime/downloads/${manifest.runtimeId}-${manifest.archiveSha256.lowercase()}.tar.gz")
                copyVerified(File(input, "dsh.tar.gz"), target, manifest.archiveSize, manifest.archiveSha256)
                JSONObject().put("size", target.length()).put("sha256", manifest.archiveSha256)
            }
            checkCase("install DSH candidate using actual version and authenticated webpage probes") {
                val slot = dshInstaller.install(manifest, "official")
                check(slot.manifest.digest() == manifest.digest())
                JSONObject().put("dshVersion", slot.manifest.dshVersion).put("manifestSha256", slot.manifest.digest())
            }
            checkCase("start and authenticate actual DSH service") {
                val state = dsh.start(workspace)
                check(state.status == DshServiceStatus.RUNNING && state.healthCode == 200 && state.port != null)
                check(dsh.health()?.code == 200)
                JSONObject().put("status", state.status.name).put("port", state.port).put("healthCode", state.healthCode)
            }
            checkCase("stop actual DSH service") {
                dsh.stop()
                check(dsh.snapshot.value.status == DshServiceStatus.STOPPED)
                withTimeout(10_000) { while (runtime.managedProcesses.value.isNotEmpty()) delay(50) }
                JSONObject().put("status", dsh.snapshot.value.status.name)
            }
            checkCase("real Android Keystore and first-party configuration and history round trips") {
                verifySharedState(context, runtime, dshInstaller) { phase ->
                    report.put("sharedStatePhase", phase)
                    persist()
                }
            }
            checkCase("actual first-party sandbox file effects with controlled PRoot loader") {
                val sandbox = DshSandboxProbe(context, runtime, dshInstaller).run()
                check(sandbox.getString("architecture") == "x64" && sandbox.getString("platform") == "linux")
                report.put("sandboxVerified", sandbox.optBoolean("completeSandboxVerified"))
                report.put("requiresVmDecision", sandbox.optBoolean("requiresFurtherUserDecision"))
                check(sandbox.optBoolean("completeSandboxVerified") && !sandbox.optBoolean("requiresFurtherUserDecision")) {
                    "Actual first-party file-effect proof failed; do not advertise constrained modes on this device"
                }
                sandbox
            }
            report.put("passed", true)
        } catch (cancelled: CancellationException) {
            report.put("failure", "cancelled")
            throw cancelled
        } catch (failure: Throwable) {
            report.put("failure", failure.javaClass.simpleName).put("detail", failure.message?.take(1000))
                .put("dshState", dsh.snapshot.value.toString())
            throw failure
        } finally {
            try {
                withContext(NonCancellable) { dsh.stop() }
            } finally {
                dsh.close()
                report.put("finished_at", System.currentTimeMillis())
                persist()
            }
        }
    }

    private fun verifyMountTargets(context: Context): JSONObject {
        val root = File(context.noBackupFilesDir, "mount-layout-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            val legacy = File(root, "dafeiyu-dsh").apply { check(mkdir()); Os.chmod(path, 0) }
            LinuxGuestMounts.repairLegacy(root)
            check((Os.lstat(legacy.path).st_mode and 0x1ff) == 0x1c0 && legacy.listFiles()?.isEmpty() == true)
            LinuxGuestMounts.prepare(root, listOf("/dev", "/proc", "/sys", "/system", "/apex",
                context.applicationInfo.nativeLibraryDir), listOf("/dafeiyu-script"))
            check((Os.lstat(File(root, "dafeiyu-script").path).st_mode and 0x1ff) == 0x180)

            val data = File(root, "dafeiyu-model-host").apply { check(mkdir()) }
            File(data, "marker").writeText("preserve")
            Os.chmod(data.path, 0)
            var rejectedData = false
            try { LinuxGuestMounts.repairLegacy(root) } catch (_: IllegalArgumentException) { rejectedData = true }
            check(rejectedData && (Os.lstat(data.path).st_mode and 0x1ff) == 0)
            Os.chmod(data.path, 0x1c0)
            check(File(data, "marker").readText() == "preserve")

            val link = File(root, "dafeiyu-model-component")
            Os.symlink("dafeiyu-model-host", link.path)
            var rejectedLink = false
            try { LinuxGuestMounts.repairLegacy(root) } catch (_: IllegalArgumentException) { rejectedLink = true }
            check(rejectedLink && LinuxArchiveExtractor.isSymlink(link) && File(data, "marker").readText() == "preserve")
            check(link.delete())

            val unknown = File(root, "custom-data").apply { check(mkdir()); Os.chmod(path, 0) }
            LinuxGuestMounts.repairLegacy(root)
            var rejectedUnknown = false
            try { LinuxGuestMounts.prepare(root, listOf("/custom-data")) }
            catch (_: IllegalArgumentException) { rejectedUnknown = true }
            check(rejectedUnknown && (Os.lstat(unknown.path).st_mode and 0x1ff) == 0)
            Os.chmod(unknown.path, 0x1c0)
            return JSONObject().put("legacyEmptyRepaired", true).put("nonemptyRefusedAndModeRetained", true)
                .put("symlinkRefusedAndDataRetained", true).put("unknownModesRetained", true)
                .put("newDirectoryAndFileTargetsPrepared", true)
        } finally {
            // Only these freshly created, application-owned fixtures are touched.
            for (name in listOf("dafeiyu-model-host", "custom-data")) File(root, name).takeIf {
                it.isDirectory && !LinuxArchiveExtractor.isSymlink(it)
            }?.let { Os.chmod(it.path, 0x1c0) }
            LinuxArchiveExtractor.removeTree(root)
        }
    }

    private suspend fun verifyManagedLeases(runtime: LinuxRuntimeManager): JSONObject {
        val command = listOf("/usr/local/bin/node", "-e", "process.stdin.resume();")
        val prefix = "smoke-lease-${UUID.randomUUID()}"
        var ordinary: LinuxManagedProcess? = null
        var exclusive: LinuxManagedProcess? = null
        var recovered: LinuxManagedProcess? = null
        suspend fun expectRejection(id: String, exclusiveUse: Boolean, message: String) {
            val unexpected = try { runtime.startManagedProcess(id, command, exclusiveRuntimeUse = exclusiveUse) }
            catch (failure: IllegalStateException) {
                check(failure.message?.contains(message) == true) { "Launch failed for a different reason from the lease gate" }
                return
            }
            withContext(NonCancellable) { unexpected.stop() }
            error("Conflicting process started while a real runtime lease was held")
        }
        try {
            ordinary = runtime.startManagedProcess("$prefix-ordinary", command)
            check(ordinary.isAlive)
            expectRejection("$prefix-rejected-exclusive", true, "运行时仍有进程占用")
            check(ordinary.isAlive)
            ordinary.stop()
            check(!ordinary.isAlive)
            exclusive = runtime.startManagedProcess("$prefix-exclusive", command, exclusiveRuntimeUse = true)
            check(exclusive.isAlive)
            expectRejection("$prefix-rejected-ordinary", false, "正在写入共享记录")
            check(exclusive.isAlive)
            exclusive.stop()
            check(!exclusive.isAlive)
            recovered = runtime.startManagedProcess("$prefix-recovered", command)
            check(recovered.isAlive)
            recovered.stop()
            check(!recovered.isAlive)
            withTimeout(10_000) { while (runtime.managedProcesses.value.any { it.id.startsWith(prefix) }) delay(20) }
            return JSONObject().put("ordinaryBlocksExclusive", true).put("exclusiveBlocksOrdinary", true)
                .put("actualExitReleasesLease", true)
        } finally {
            withContext(NonCancellable) {
                recovered?.stop()
                exclusive?.stop()
                ordinary?.stop()
            }
        }
    }

    /** Calls the same production verifier used to admit real light tasks. */
    private suspend fun verifyLightSandbox(context: Context, runtime: LinuxRuntimeManager, parent: File): JSONObject {
        val verifier = LinuxSandboxVerifier(context, runtime)
        val receipt = verifier.verify(force = true)
        check(receipt.supportsConstrainedModes && verifier.currentReceipt()?.identity == receipt.identity)
        val workspace = File(parent, "identity-check-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            val marker = File(workspace, "must-not-execute")
            var rejected = false
            try {
                runtime.execute("node", "require('node:fs').writeFileSync('/workspace/must-not-execute','invalid');", workspace,
                    sandboxMode = LinuxSandboxContract.WORKSPACE_WRITE,
                    expectedSandboxIdentity = receipt.identity.copy(activeReceiptSha256 = "0".repeat(64)))
            } catch (failure: IllegalStateException) {
                check(failure.message?.contains("运行环境已变化") == true)
                rejected = true
            }
            check(rejected && !marker.exists()) { "Stale receipt was not refused before script execution" }
            return receipt.toJson().put("staleIdentityRefusedBeforeExecution", true)
        } finally {
            check(!runtime.hasProcessLease(workspace) && workspace.deleteRecursively())
        }
    }

    /** An isolated context routes only test-owned stores/homes into production
     * adapters, preserving the main dev app's keys, profiles and sessions. */
    private suspend fun verifySharedState(context: Context, runtime: LinuxRuntimeManager, installer: DshRuntimeInstaller,
        checkpoint: (String) -> Unit): JSONObject {
        val id = UUID.randomUUID().toString()
        val data = File(context.filesDir, "runtime-smoke-state/$id").apply { check(mkdirs()) }
        val vault = File(context.noBackupFilesDir, "runtime-smoke-state/$id").apply { check(mkdirs()) }
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = data
            override fun getNoBackupFilesDir(): File = vault
        }
        val workspace = File(data, "linux-workspaces/shared-state").apply { check(mkdirs()) }
        val service = DshRuntimeManager(isolated, runtime, installer)
        val bridge = DshSharedPersistenceBridge(isolated, runtime, installer)
        val store = ModelProviderStore(isolated)
        val adapter = DshSharedConfigurationAdapter(isolated, store, bridge)
        val providerId = "runtime-smoke-$id"
        val marker = "SYNTHETIC_OFFLINE_KEY_$id"
        try {
            checkpoint("start-isolated-dsh")
            val state = service.start(workspace)
            check(state.status == DshServiceStatus.RUNNING && state.healthCode == 200)
            val access = checkNotNull(state.accessUrl)
            store.upsert(ModelProviderConfig(providerId, "Offline runtime fixture", api = "openai-completions",
                baseUrl = "https://example.invalid/v1", modelsJson = "[{\"id\":\"offline-runtime-model\",\"reasoning\":false}]"),
                JSONObject().put("type", "api_key").put("key", marker))
            store.reorder(listOf(providerId, "deepseek"))
            checkpoint("publish-native-provider-and-key")
            adapter.writeToDsh(access)
            val rpc = DshLocalConfigurationRpc()
            suspend fun verifyLiveOrder(expected: List<String>): List<String> = withTimeout(20_000) {
                while (true) {
                    val catalog = rpc.call(access, "session/modelCatalog", JSONObject())
                    val groups = catalog.getJSONArray("groups")
                    val order = List(groups.length()) { groups.getJSONObject(it).getString("id") }
                    if (order.take(expected.size) == expected) {
                        check(catalog.getJSONArray("routableProviders").toString() == JSONArray(order).toString())
                        val models = groups.getJSONObject(order.indexOf(providerId)).getJSONArray("models")
                        check(models.length() == 1 && models.getJSONObject(0).getString("id") == "offline-runtime-model")
                        return@withTimeout order
                    }
                    delay(150)
                }
                @Suppress("UNREACHABLE_CODE") emptyList()
            }
            checkpoint("live-model-order-first")
            val firstOrder = verifyLiveOrder(listOf(providerId, "deepseek-official"))
            store.reorder(listOf("deepseek", providerId))
            checkpoint("live-model-order-second")
            val secondOrder = verifyLiveOrder(listOf("deepseek-official", providerId))
            checkpoint("first-party-key-read-and-keystore-reopen")
            check(bridge.readCredentialDocument().toString().contains(marker)) { "Synthetic credential did not reach the first-party store" }
            val sealed = File(vault, "model-providers.v1.enc")
            check(sealed.isFile && !sealed.readBytes().toString(Charsets.ISO_8859_1).contains(marker)) { "Provider credential was not sealed" }
            check(ModelProviderStore(isolated).hasCredential(providerId)) { "Android Keystore did not reopen the synthetic credential" }
            store.remove(providerId)
            checkpoint("read-published-provider-back")
            adapter.readFromDsh(access)
            check(store.read().providers.any { it.id == providerId && it.dshRoute == providerId && it.api == "openai-completions" })
            check(store.workerConfiguration().getJSONObject("credentials").getJSONObject(providerId).getString("key") == marker)
            store.remove(providerId)
            checkpoint("publish-owned-provider-removal")
            adapter.writeToDsh(access)
            checkpoint("read-after-owned-provider-removal")
            adapter.readFromDsh(access)
            check(store.read().providers.none { it.id == providerId } && !store.hasCredential(providerId))
            check(!bridge.readCredentialDocument().toString().contains(marker)) { "Removed owned credentials were not deleted" }
            service.stop()
            check(service.snapshot.value.status == DshServiceStatus.STOPPED)
            val artifact = DshSharedHistoryAdapter.fromNative("android-smoke-$id", "Offline shared history",
                "/dafeiyu-workspaces/shared-state", listOf(ChatMessage(1, "user", "offline history user"),
                    ChatMessage(2, "assistant", "offline history assistant")), 1_700_000_000_000L)
            checkpoint("write-first-party-compatible-history")
            val written = bridge.writeCompatibleArtifact(artifact)
            check(written.getBoolean("created"))
            checkpoint("retry-first-party-compatible-history")
            check(!bridge.writeCompatibleArtifact(artifact).getBoolean("created"))
            val sessions = File(data, "dsh-home/sessions")
            checkpoint("list-first-party-compatible-history")
            val listing = bridge.listArtifacts(sessions)
            val listed = listing.getJSONArray("items")
            check((0 until listed.length()).any { listed.getJSONObject(it).getJSONObject("header").getString("id") == artifact.getJSONObject("header").getString("id") })
            checkpoint("read-first-party-compatible-history")
            val restored = DshSharedHistoryAdapter.decode(bridge.readArtifact(sessions, artifact.getJSONObject("header").getString("id")))
            check(restored.cwd == "/dafeiyu-workspaces/shared-state" && !restored.interrupted)
            check(restored.compatibleMessages.map { it.text } == listOf("offline history user", "offline history assistant"))
            checkpoint("restart-and-read-live-history-projections")
            val restarted = service.start(workspace)
            check(restarted.healthCode == 200)
            val rpcAccess = checkNotNull(restarted.accessUrl)
            val controllerItems = rpc.call(rpcAccess, "session/list", JSONObject().put("_request", JSONObject())).getJSONArray("items")
            val restoredId = artifact.getJSONObject("header").getString("id")
            val summary = (0 until controllerItems.length()).map { controllerItems.getJSONObject(it) }
                .single { it.getString("sessionId") == restoredId }
            check(!summary.getBoolean("running") && summary.getString("cwd") == restored.cwd)
            val projections = rpc.call(rpcAccess, "session/projections", JSONObject().put("request", JSONObject().put("sessionId", restoredId)))
            check(projections.getLong("asOfSeq") >= 0 && projections.getJSONObject("values").length() > 0)
            service.stop()
            checkpoint("reject-incomplete-extension-journal")
            val journal = File(data, "dsh-home/${DshExtensionTransaction.DIRECTORY}").apply { check(mkdir()) }
            File(journal, "incomplete-test-journal").writeText("invalid fixture must still block")
            suspend fun rejectsJournal(block: suspend () -> Unit) {
                var rejected = false
                try { block() } catch (failure: IllegalStateException) {
                    check(failure.message?.contains("插件变更尚未恢复") == true)
                    rejected = true
                }
                check(rejected) { "Incomplete extension journal was ignored" }
            }
            rejectsJournal { service.start(workspace) }
            rejectsJournal { adapter.writeToDsh(rpcAccess) }
            rejectsJournal { bridge.convertProviderConfiguration(JSONObject().put("operation", "to-dsh").put("native", store.exportPortable())) }
            withTimeout(10_000) { while (runtime.managedProcesses.value.isNotEmpty()) delay(20) }
            check(service.snapshot.value.status == DshServiceStatus.ERROR)
            checkpoint("complete")
            return JSONObject().put("androidKeystoreReopen", true).put("credentialRoundTrip", true)
                .put("ownedCredentialDeletion", true).put("firstPartyHistoryWriteReadList", true)
                .put("retryIdempotent", true).put("historyCwdStable", true).put("readOnlySessionRpc", true).put("noPromptReplay", true)
                .put("extensionJournalBlocksLaunchAndConfig", true).put("sessionProjections", projections)
                .put("liveWebCatalogOrder", JSONArray(listOf(JSONArray(firstOrder), JSONArray(secondOrder))))
                .put("vaultBytes", sealed.length()).put("paidModelRequests", 0)
        } finally {
            try { withContext(NonCancellable) { service.stop() } }
            finally {
                service.close()
                if (!runtime.hasProcessLease(data) && !runtime.hasProcessLease(vault)) {
                    check(data.deleteRecursively() && vault.deleteRecursively())
                }
            }
        }
    }

    private fun copyVerified(source: File, destination: File, size: Long, digest: String) {
        check(source.isFile && source.length() == size) { "Staged fixture is missing or has incorrect size: ${source.name}" }
        val checksum = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; checksum.update(buffer, 0, count) }
        }
        check(checksum.digest().joinToString("") { "%02x".format(it) } == digest.lowercase()) { "Staged fixture SHA-256 mismatch: ${source.name}" }
        check(destination.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        source.copyTo(destination, overwrite = true)
    }
}
