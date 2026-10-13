package com.dafeiyu.mobile.data

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production installers/manager in an already installed real Ubuntu+DSH slot.
 * Only isolated, test-owned app data is touched; no pairing, permission grants or paid model calls. */
@RunWith(AndroidJUnit4::class)
class ExtensionManagementSmokeTest {
    @Test fun actualSkillAndPluginManagementUsesSharedFilesAndPinnedManager() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = LinuxRuntimeManager(context)
        check(runtime.managedProcesses.value.none { it.running }) { "Stop QA-owned runtime processes before this isolated test" }
        val installer = DshRuntimeInstaller(context, runtime)
        check(installer.current() != null) { "Run the production runtime installation smoke first" }
        val id = UUID.randomUUID().toString()
        val data = File(context.filesDir, "extension-smoke/$id").apply { check(mkdirs()) }
        val vault = File(context.noBackupFilesDir, "extension-smoke/$id").apply { check(mkdirs()) }
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = data
            override fun getNoBackupFilesDir(): File = vault
        }
        val report = JSONObject().put("modelCalls", 0).put("scope", "isolated real Android installers and first-party manager")
        val cases = JSONArray()
        val reportFile = File(context.filesDir, "extension-smoke-report.json")
        fun pass(label: String) {
            cases.put(JSONObject().put("name", label).put("status", "pass"))
            Log.i(TAG, "PASS ${cases.length()} $label")
        }
        val bridge = DshExtensionBridge(isolated, runtime, installer)
        val packages = ExtensionPackageStore(isolated, bridge)
        val skills = SkillInstallStore(isolated, runtime)
        fun archive(name: String, entries: Map<String, String>): File = File(data, name).also { file ->
            ZipOutputStream(file.outputStream()).use { output -> entries.forEach { (path, text) ->
                output.putNextEntry(ZipEntry(path)); output.write(text.toByteArray()); output.closeEntry()
            } }
        }
        try {
            val skillArchive = archive("offline-skill.zip", mapOf(
                "bundle/SKILL.md" to "---\nname: android-smoke-skill\ndescription: Offline Android skill fixture.\n---\nRead the test reference.\n",
                "bundle/reference.md" to "Offline shared reference.", "LICENSE" to "MIT fixture, owned by this test.",
            ))
            skills.import(Uri.fromFile(skillArchive))
            val skillDirectory = File(data, "dsh-home/skills/android-smoke-skill")
            check(skills.catalogForModel().items.single().metadata.name == "android-smoke-skill")
            check(File(skillDirectory, "reference.md").readText() == "Offline shared reference.")
            pass("actual Android local archive installs the complete skill into the shared DSH root")
            check(skills.load("android-smoke-skill").body.contains("test reference"))
            skills.setEnabled("android-smoke-skill", false)
            check(skills.catalogForModel().items.isEmpty() && !File(skillDirectory, "SKILL.md").exists())
            pass("production skill enablement physically controls model and DSH discovery")
            skills.setEnabled("android-smoke-skill", true)
            val pinned = readPinnedSkillCatalog(runtime, installer, File(data, "dsh-home"))
            check(pinned.getJSONArray("names").toString().contains("android-smoke-skill"))
            check(pinned.getString("body").contains("test reference"))
            pass("actual pinned first-party DSH skill provider discovers and reads the same installed bytes")
            val listing = packages.scan()
            check(listing.available && listing.items.any { it.name == "@deepseek-ai/dsh-base" && it.builtIn })
            check(!bridge.recoveryRequired())
            pass("actual pinned plugin manager lists the protected core after journaled profile initialization")
            val pluginArchive = archive("offline-plugin.zip", mapOf(
                "bundle/package.json" to JSONObject().put("name", "dafeiyu-android-smoke").put("version", "1.0.0")
                    .put("license", "MIT").put("description", "Offline Android manager fixture")
                    .put("dsh", JSONObject().put("bundle", JSONObject().put("patch", "cordis.patch.yml"))).toString(),
                "bundle/cordis.patch.yml" to "- insert:\n    - id: never-execute\n      name: ./never-execute.mjs\n",
                "bundle/never-execute.mjs" to "throw new Error('Installation must not execute fixture plugin code');",
                "bundle/LICENSE" to "MIT fixture, owned by this test.",
            ))
            packages.import(Uri.fromFile(pluginArchive), "official")
            val installed = packages.scan().items.single { it.name == "dafeiyu-android-smoke" }
            check(installed.enabled && installed.installed && installed.version == "1.0.0")
            check(!bridge.recoveryRequired())
            pass("production Android PRoot, pnpm and first-party manager install a real local bundle")
            packages.setEnabled(installed.name, false)
            check(!packages.scan().items.single { it.name == installed.name }.enabled)
            pass("first-party plugin enablement updates the actual persisted bundle selection")
            packages.repair("official", installed.name)
            check(packages.scan().items.single { it.name == installed.name }.let { it.installed && !it.enabled })
            pass("real fixed-source plugin repair preserves the prior disabled state")
            val protectedBefore = File(data, "dsh-home/profiles/web/package.json").readText()
            val rejected = try { packages.setEnabled("@deepseek-ai/dsh-base", false); false } catch (_: Exception) { true }
            check(rejected && File(data, "dsh-home/profiles/web/package.json").readText() == protectedBefore && !bridge.recoveryRequired())
            pass("protected core mutation is truly rejected and the journal restores exact configuration")
            packages.remove(installed.name, "official")
            check(packages.scan().items.none { it.name == installed.name })
            check(runtime.managedProcesses.value.none { it.id.startsWith("dsh-extension-") && it.running })
            pass("real plugin removal clears the profile dependency and releases process leases")
            val prepareArchive = archive("offline-prepare-plugin.zip", mapOf(
                "bundle/package.json" to JSONObject().put("name", "dafeiyu-android-prepare-smoke").put("version", "1.0.0")
                    .put("license", "MIT").put("description", "Offline Android exact source build fixture")
                    .put("scripts", JSONObject().put("prepare", "node build.cjs"))
                    .put("dsh", JSONObject().put("bundle", JSONObject().put("patch", "generated/patch.yml"))).toString(),
                "bundle/build.cjs" to "require('fs').mkdirSync('generated',{recursive:true});require('fs').writeFileSync('generated/patch.yml','- insert: []\\n');require('fs').writeFileSync('build-marker','executed');",
                "bundle/LICENSE" to "MIT fixture, owned by this test.",
            ))
            val awaitingConsent = try { packages.import(Uri.fromFile(prepareArchive), "official"); error("Unconfirmed prepare was allowed") }
                catch (failure: ExtensionManagementFailure) { failure }
            check(awaitingConsent.builds.single().key == "dafeiyu-android-prepare-smoke@1.0.0")
            check(awaitingConsent.builds.single().scripts["prepare"] == "node build.cjs")
            check(!bridge.recoveryRequired() && packages.scan().items.none { it.name == "dafeiyu-android-prepare-smoke" })
            pass("actual Android missing patch yields exact script consent and restores the original profile")
            val missingConsent = try { packages.retryWithBuildConsent(awaitingConsent, "official", acceptRisk = false); false }
                catch (_: IllegalArgumentException) { true }
            check(missingConsent)
            pass("application layer refuses source build without explicit risk acknowledgement")
            packages.retryWithBuildConsent(awaitingConsent, "official", acceptRisk = true)
            val built = packages.scan().items.single { it.name == "dafeiyu-android-prepare-smoke" }
            check(built.installed && built.enabled && built.version == "1.0.0" && !bridge.recoveryRequired())
            pass("real Android pnpm10 runs the approved offline prepare then the pinned manager registers its generated bundle")
            packages.remove(built.name, "official")
            check(packages.scan().items.none { it.name == built.name })
            pass("the Android source-built bundle is actually removed by the pinned manager")
            val liveService = DshRuntimeManager(isolated, runtime, installer)
            try {
                val running = liveService.start()
                check(running.status == DshServiceStatus.RUNNING && running.healthCode == 200)
                val entries = packages.liveEntries()
                check(entries.isNotEmpty() && entries.all { it.entryId.isNotBlank() && it.moduleName.isNotBlank() })
                check(entries.any { it.readOnlyReason == "management-required" })
                pass("actual Android authenticated plugin RPC reads the running first-party loader inventory")
                val entry = checkNotNull(entries.firstOrNull { it.enabled && it.readOnlyReason == null }) {
                    "The actual first-party profile exposes no manageable active entry for this smoke"
                }
                packages.setPluginEntryEnabled(entry, entry.enabled)
                check(packages.liveEntries().single { it.entryId == entry.entryId }.enabled == entry.enabled)
                pass("actual Android authenticated setPluginEnabled RPC accepts a same-state request and retains the loader selection")
            } finally {
                withContext(NonCancellable) {
                    check(liveService.stop().status == DshServiceStatus.STOPPED)
                }
            }
            val workspace = File(data, "workspace").apply { check(mkdirs()) }
            val outside = File(data, "outside").apply { check(mkdirs()) }
            File(outside, "skills/outside-skill").apply { check(mkdirs()) }.let { directory ->
                File(directory, "SKILL.md").writeText("---\nname: outside-skill\ndescription: Must not be discovered through a link.\n---\noutside\n")
            }
            Files.createSymbolicLink(File(workspace, ".dsh").toPath(), outside.toPath())
            val guarded = skills.scan(workspace)
            check(guarded.items.none { it.metadata.name == "outside-skill" } && guarded.warnings.isNotEmpty())
            pass("actual Android ancestor symlink cannot escape the selected workspace skill root")
            skills.remove("android-smoke-skill")
            check(skills.scan().items.isEmpty())
            pass("actual skill removal retains original content outside model discovery")
            report.put("status", "passed")
        } catch (failure: Throwable) {
            report.put("status", "failed").put("failure", failure.javaClass.simpleName + ": " + failure.message)
            throw failure
        } finally {
            report.put("cases", cases).put("count", cases.length())
            reportFile.writeText(report.toString(2))
            withContext(NonCancellable) {
                if (!runtime.hasProcessLease(data) && !runtime.hasProcessLease(vault) && !bridge.recoveryRequired()) {
                    ExtensionFiles.deleteOwned(File(context.filesDir, "extension-smoke"), data)
                    ExtensionFiles.deleteOwned(File(context.noBackupFilesDir, "extension-smoke"), vault)
                }
            }
        }
        Log.i(TAG, "Android extension smoke: ${cases.length()} PASS, paid model calls=0")
        Unit
    }

    private suspend fun readPinnedSkillCatalog(runtime: LinuxRuntimeManager, installer: DshRuntimeInstaller, home: File): JSONObject {
        val slot = checkNotNull(installer.current())
        val script = """
            const {createRequire} = require('node:module');
            const root = createRequire('/dafeiyu-dsh/package.json');
            const {FileSystemSkillProvider} = root('@deepseek-ai/dsh-skill-filesystem');
            const {Context} = root('@deepseek-ai/cordis');
            const ctx = new Context();
            const abort = new AbortController();
            const provider = new FileSystemSkillProvider(ctx, {signal:abort.signal,invalidate(){}},
              {includeDefaultRoots:false,customSkillDirs:['/dafeiyu-dsh-home/skills'],watch:false});
            (async()=>{try{const result=await provider.list({});const entries=Array.isArray(result)?result:result.candidates;
              const item=entries.find(value=>value.name==='android-smoke-skill');
              const full=item?await provider.get(item,{signal:abort.signal}):null;
              console.log(JSON.stringify({names:entries.map(value=>value.name),body:full?.content??''}));
            }finally{await provider.dispose();await ctx.fiber.dispose();}})().catch(error=>{console.error(error.message);process.exitCode=1;});
        """.trimIndent()
        val process = installer.withCurrentSlot(slot.manifest.digest()) { current ->
            runtime.startManagedProcess("extension-skill-smoke-${UUID.randomUUID()}", listOf("/usr/local/bin/node", "-e", script),
                mapOf("/dafeiyu-dsh" to current.directory, "/dafeiyu-dsh-home" to home), exclusiveRuntimeUse = true)
        }
        try {
            process.input.close()
            return withTimeout(30_000) {
                val output = process.output.use { ExtensionFiles.bytes(it, 64 * 1024L) }
                val errors = process.errors.use { ExtensionFiles.bytes(it, 64 * 1024L) }
                check(process.awaitExit() == 0) { ExtensionFiles.utf8(errors) }
                JSONObject(ExtensionFiles.utf8(output))
            }
        } finally { withContext(NonCancellable) { process.stop() } }
    }
    companion object { const val TAG = "DafeiyuExtensionSmoke" }
}
