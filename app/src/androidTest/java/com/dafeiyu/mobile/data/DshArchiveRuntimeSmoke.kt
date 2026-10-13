package com.dafeiyu.mobile.data

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Real installed Ubuntu/Node/DSH and the APK's exact frozen Connect package.
 * All homes, journals, profiles, fixtures and deletion targets belong to this test.
 * The CLI and its services are never substituted, and no prompt is submitted. */
@RunWith(AndroidJUnit4::class)
class DshArchiveRuntimeSmoke {
    @Test
    fun actualPreloadAndNativeArchiveGuards(): Unit = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        check(target.packageName == "com.dafeiyu.mobile.dev")
        check(android.os.Build.SUPPORTED_ABIS.firstOrNull() == "x86_64")
        val id = UUID.randomUUID().toString()
        val files = File(target.filesDir, "dsh-archive-smoke/$id").apply { check(mkdirs()) }
        val vault = File(target.noBackupFilesDir, "dsh-archive-smoke/$id").apply { check(mkdirs()) }
        val isolated = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getNoBackupFilesDir(): File = vault
        }
        val runtime = LinuxRuntimeManager(target)
        val installer = DshRuntimeInstaller(target, runtime)
        val reportFile = File(target.filesDir, "dsh-archive-smoke-report.json")
        val checks = JSONArray()
        val report = JSONObject().put("schema", 1).put("package", target.packageName)
            .put("runId", id).put("started_at", System.currentTimeMillis()).put("checks", checks)
            .put("paidModelRequests", 0).put("syntheticCli", false).put("syntheticServices", false).put("passed", false)
        fun persist() { reportFile.writeText(report.toString(2), Charsets.UTF_8) }
        suspend fun checkCase(name: String, body: suspend () -> JSONObject) {
            val entry = JSONObject().put("name", name).put("passed", false)
            checks.put(entry); persist()
            try { entry.put("evidence", withTimeout(120_000) { body() }).put("passed", true) }
            catch (failure: Throwable) {
                entry.put("error", failure.javaClass.simpleName).put("detail", failure.message?.take(1200))
                throw failure
            } finally { persist() }
            Log.i(TAG, "PASS ${checks.length()} $name")
        }
        try {
            check(runtime.managedProcesses.value.none { it.running }) { "Stop QA-owned runtime processes before this isolated test" }
            val slot = checkNotNull(installer.current()) { "Run the production runtime installation smoke first" }
            val identity = runtime.nodeIdentity()
            check(identity.platform == "linux" && identity.architecture == slot.manifest.nodeArchitecture &&
                identity.version == "v${slot.manifest.nodeVersion.removePrefix("v")}")
            val home = File(files, "dsh-home").apply { check(mkdir()) }
            val journal = File(home, LinuxDshArchiveEnvironment.ARCHIVE_RELATIVE).apply { check(mkdirs()) }
            val helper = File(files, "helpers").apply { check(mkdir()) }
            val launch = File(files, "launch").apply { check(mkdir()) }
            val workspaces = File(files, "workspaces").apply { check(mkdir()) }
            val order = ModelProviderStore(isolated).prepareDshProviderOrder()
            val manifest = target.assets.open("connection-plugin/manifest.json").use {
                ConnectionPackageValidator.manifest(ConnectionPackageValidator.readBounded(it, ConnectionPackageValidator.MAX_MANIFEST))
            }
            val plugin = ConnectionPluginPackage(isolated).ensureBundled(DshArchiveLaunchContract.CONNECT_VERSION)
            val pluginSlot = DshArchiveLaunchContract.verifyPreparedPackage(manifest, plugin)
            for (name in listOf("android-launch.mjs", "android-sandbox.mjs", "android-persistence.mjs", "android-provider-order.mjs")) {
                target.assets.open("dsh-runtime/$name").use { input -> File(launch, name).outputStream().use { input.copyTo(it) } }
            }
            File(helper, "entry-witness.mjs").writeText(ENTRY_WITNESS)
            File(helper, "wrong-journal-witness.mjs").writeText(
                "import './entry-witness.mjs';\nprocess.env.DAFEIYU_DSH_ARCHIVE_DIRECTORY='/dafeiyu-dsh-home/another-journal';\n")
            File(helper, "ready.mjs").writeText(READINESS)
            val profile = File(home, "profiles/archive-qa").apply { check(mkdirs()) }
            val probe = File(profile, "node_modules/qa-native-archive-probe").apply { check(mkdirs()) }
            File(probe, "index.mjs").writeText(NATIVE_PROBE)
            File(probe, "package.json").writeText(JSONObject().put("name", "qa-native-archive-probe").put("version", "0.0.0")
                .put("type", "module").put("main", "./index.mjs").put("license", "MIT").toString())
            File(profile, "package.json").writeText(JSONObject().put("name", "dsh-profile-archive-qa").put("private", true)
                .put("dependencies", JSONObject().put("qa-native-archive-probe", "0.0.0"))
                .put("dsh", JSONObject().put("profile", JSONObject().put("bundles",
                    JSONArray(listOf("@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app"))))).toString())
            File(profile, "cordis.patch.yml").writeText(PROFILE_PATCH)
            val mounts = mapOf("/dafeiyu-dsh" to slot.directory, "/dafeiyu-dsh-home" to home,
                "/dafeiyu-dsh-launch" to launch, "/dafeiyu-workspaces" to workspaces,
                "/dafeiyu-provider-order" to order, DshArchiveLaunchContract.CONNECT_MOUNT to pluginSlot,
                GUEST_HELPER to helper)
            val environment = DshArchiveLaunchContract.environment()
            checkCase("actual installed ABI and owning DSH metadata plus exact frozen Connect tree") {
                DshArchiveLaunchContract.verifyOwningBundle(slot.directory, slot.manifest.dshVersion)
                LinuxDshArchiveEnvironment.validate(environment, mounts)
                JSONObject().put("dshVersion", slot.manifest.dshVersion).put("nodeVersion", identity.version)
                    .put("connectVersion", manifest.version).put("connectBytes", manifest.sizeBytes).put("connectSha256", manifest.sha256)
            }
            suspend fun execute(phase: String, cli: Boolean = false, preload: Boolean = true, wrongDirectory: Boolean = false): Run {
                File(helper, "run-config.json").writeText(JSONObject().put("phase", phase).toString())
                val observer = if (wrongDirectory) "$GUEST_HELPER/wrong-journal-witness.mjs" else "$GUEST_HELPER/entry-witness.mjs"
                val command = mutableListOf("/usr/local/bin/node", "--import", observer)
                if (preload) command += listOf("--import", DshArchiveLaunchContract.PRELOAD)
                command += if (cli) listOf("/dafeiyu-dsh-launch/android-launch.mjs", "/dafeiyu-dsh",
                    target.applicationInfo.nativeLibraryDir, "--profile", "archive-qa", "--host", "127.0.0.1", "--port", "0", "--no-open")
                    else listOf("$GUEST_HELPER/ready.mjs")
                val process = installer.withCurrentSlot(slot.manifest.digest()) { current ->
                    check(current.directory.canonicalFile == slot.directory.canonicalFile)
                    runtime.startManagedProcess("archive-smoke-$phase-$id", command, mounts,
                        environment = environment, exclusiveRuntimeUse = true, beforeStart = {
                            DshArchiveLaunchContract.verifyOwningBundle(current.directory, current.manifest.dshVersion)
                            DshArchiveLaunchContract.verifyPreparedPackage(manifest, plugin)
                        })
                }
                val result = try {
                    process.input.close()
                    withTimeout(100_000) { coroutineScope {
                        val output = async(Dispatchers.IO) { bounded(process.output) }
                        val errors = async(Dispatchers.IO) { bounded(process.errors) }
                        Run(process.awaitExit(), output.await(), errors.await())
                    } }
                } finally { withContext(NonCancellable) { process.stop() } }
                File(helper, "$phase-stdout.log").writeText(result.output)
                File(helper, "$phase-stderr.log").writeText(result.errors)
                val network = File(helper, "$phase-network.json")
                check(network.isFile && JSONObject(network.readText()).getInt("externalAttempts") == 0) {
                    "The isolated archive process attempted external fetch or did not publish its exit witness"
                }
                return result
            }
            fun entered(phase: String) = File(helper, "$phase-cli-entered.json").exists()
            fun nativeResult(phase: String): JSONObject = JSONObject(File(helper, "$phase-result.json").readText()).also {
                check(it.getBoolean("passed")) { it.toString().take(1600) }
                check(it.getBoolean("actualCliServices") && it.getBoolean("frozenLib") && it.getInt("paidModelRequests") == 0)
            }
            checkCase("real packaged --import readiness is read only and rejects another journal directory") {
                val run = execute("ready")
                check(run.exit == 0 && !entered("ready")) { run.errors.take(1200) }
                JSONObject(File(helper, "ready-result.json").readText()).also {
                    check(it.getBoolean("preloadReady") && it.getBoolean("wrongDirectoryDenied"))
                }
            }
            checkCase("missing --import is refused by the actual frozen readiness export before services or CLI") {
                val run = execute("missing", preload = false)
                check(run.exit != 0 && run.errors.contains("archive_preload_not_ready") && !entered("missing"))
                JSONObject().put("exit", run.exit).put("cliImported", false).put("reason", "archive_preload_not_ready")
            }
            checkCase("actual production Android CLI and first-party services archive and permanently delete isolated history") {
                val run = execute("create-delete", cli = true)
                check(run.exit == 0 && entered("create-delete")) { run.errors.takeLast(1800) }
                nativeResult("create-delete")
            }
            val journalFile = File(journal, "archive-deletion.json")
            check(journalFile.isFile)
            val retainedJournal = journalFile.readBytes()
            checkCase("a separate actual CLI process loads the durable tombstone and refuses reopen recreate and restore") {
                val run = execute("cold-restart", cli = true)
                check(run.exit == 0 && entered("cold-restart")) { run.errors.takeLast(1800) }
                nativeResult("cold-restart")
            }
            suspend fun refusal(phase: String, text: ByteArray, expected: String? = null): JSONObject {
                journalFile.writeBytes(text)
                val before = android.system.Os.lstat(journalFile.path)
                val run = execute(phase, cli = true)
                val after = android.system.Os.lstat(journalFile.path)
                check(run.exit != 0 && !entered(phase) && journalFile.readBytes().contentEquals(text))
                check(before.st_ino == after.st_ino && before.st_mtime == after.st_mtime && before.st_ctime == after.st_ctime)
                if (expected != null) check(run.errors.contains(expected)) { run.errors.take(1200) }
                return JSONObject().put("exit", run.exit).put("cliImported", false).put("journalUnchanged", true)
                    .put("sha256", sha256(text)).put("inode", before.st_ino.toString())
            }
            checkCase("damaged owning journal is refused by the real preloader before importing the actual CLI") {
                refusal("corrupt", "{invalid".toByteArray())
            }
            checkCase("unknown journal schema is refused before CLI without changing the supplied bytes or inode") {
                val unknown = JSONObject(retainedJournal.toString(Charsets.UTF_8)).put("version", 99).toString().toByteArray()
                refusal("unknown", unknown, "archive_journal_invalid")
            }
            journalFile.writeBytes(retainedJournal)
            checkCase("actual packaged preloader refuses a changed home-derived journal before the native entry point") {
                val before = journalFile.readBytes()
                val run = execute("wrong-directory", cli = true, wrongDirectory = true)
                check(run.exit != 0 && run.errors.contains("archive_preload_directory_invalid") && !entered("wrong-directory"))
                check(journalFile.readBytes().contentEquals(before) && !File(home, "another-journal").exists())
                JSONObject().put("exit", run.exit).put("cliImported", false).put("owningJournalUnchanged", true)
                    .put("testBoundary", "test-owned import changes only the isolated process env before the genuine packaged preloader")
            }
            checkCase("all actual tracers exited and released owning runtime DSH and Connect resource leases") {
                check(runtime.managedProcesses.value.none { it.id.startsWith("archive-smoke-") && it.running })
                check(!runtime.hasProcessLease(files) && !runtime.hasProcessLease(vault) && !runtime.hasProcessLease(pluginSlot))
                JSONObject().put("leasesReleased", true).put("paidModelRequests", 0)
            }
            report.put("passed", true)
        } finally {
            report.put("finished_at", System.currentTimeMillis()).put("count", checks.length())
            persist()
            // Retain test-owned profiles, damaged-journal evidence and process
            // logs for run-as export. The runner removes only this exact run
            // after a successful report has been copied to G:\Temp_Android.
            report.put("retainedFiles", files.relativeTo(target.filesDir).invariantSeparatorsPath)
                .put("retainedVault", vault.relativeTo(target.noBackupFilesDir).invariantSeparatorsPath)
            persist()
        }
    }

    private data class Run(val exit: Int, val output: String, val errors: String)
    private fun bounded(input: InputStream): String = input.use {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = it.read(buffer)
            if (read < 0) break
            check(output.size() + read <= 2 * 1024 * 1024) { "Isolated archive process output exceeded its evidence budget" }
            output.write(buffer, 0, read)
        }
        output.toString("UTF-8")
    }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "DafeiyuArchiveSmoke"
        private const val GUEST_HELPER = "/dafeiyu-archive-smoke"
        private val ENTRY_WITNESS = """
            import fs from 'node:fs';
            import {registerHooks} from 'node:module';
            const root='/dafeiyu-archive-smoke', {phase}=JSON.parse(fs.readFileSync(root+'/run-config.json','utf8'));
            let externalAttempts=0;
            const originalFetch=globalThis.fetch;
            globalThis.fetch=(input,init)=>{
              const url=new URL(typeof input==='string'||input instanceof URL?input:input.url);
              if(!['127.0.0.1','localhost','[::1]'].includes(url.hostname)){externalAttempts++;throw new Error('archive_smoke_external_fetch_denied');}
              return originalFetch(input,init);
            };
            globalThis[Symbol.for('dafeiyu-go.archive-qa-network-count')]=()=>externalAttempts;
            registerHooks({resolve(specifier,context,next){const result=next(specifier,context);
              if(result.url==='file:///dafeiyu-dsh/node_modules/@deepseek-ai/dsh/lib/bin.js')
                fs.writeFileSync(root+'/'+phase+'-cli-entered.json',JSON.stringify({actualCli:result.url}));
              return result;}});
            process.once('exit',()=>fs.writeFileSync(root+'/'+phase+'-network.json',JSON.stringify({externalAttempts})));
        """.trimIndent()
        private val READINESS = """
            import assert from 'node:assert/strict';
            import fs from 'node:fs/promises';
            const root='/dafeiyu-archive-smoke', {phase}=JSON.parse(await fs.readFile(root+'/run-config.json','utf8'));
            const {assertFixedDshArchivePreload}=await import('/dafeiyu-connection-slot/package/lib/framework/remote-archive-dsh.js');
            const anchor=process.env.DAFEIYU_DSH_INSTALL_ANCHOR, directory=process.env.DAFEIYU_DSH_ARCHIVE_DIRECTORY;
            await assertFixedDshArchivePreload(anchor,directory);
            await assert.rejects(assertFixedDshArchivePreload(anchor,directory+'/different'),/archive_preload_directory_changed/);
            await fs.writeFile(root+'/'+phase+'-result.json',JSON.stringify({preloadReady:true,wrongDirectoryDenied:true,
              nodeVersion:process.version,platform:process.platform,arch:process.arch,actualPackagedPreloader:true,paidModelRequests:0}));
        """.trimIndent()
        private val PROFILE_PATCH = """
            - id: web-runtime
              config:
                openBrowser: false
                printUrl: false
                surfaceContext: true
            - id: session-query-sqlite
              config:
                path: /dafeiyu-dsh-home/derived.sqlite
                openAt: startup
            - id: hmr
              disabled: true
            - id: session-title-llm
              disabled: true
            - insert:
                - id: qa-native-archive-probe
                  name: qa-native-archive-probe
        """.trimIndent() + "\n"
        private val NATIVE_PROBE = """
            import assert from 'node:assert/strict';
            import path from 'node:path';
            import {createRequire} from 'node:module';
            import {randomUUID} from 'node:crypto';
            import {pathToFileURL} from 'node:url';
            import {readFile,writeFile,stat,lstat,readdir,mkdir} from 'node:fs/promises';
            export const name='qa-native-archive-probe';
            export const inject=['appReady','appExit','loader','sessionPersistence','workspaceRegistry','sessionQuery',
              'sessionProjectionCache','agents','sessions','sessionController','fileUploads'];
            const scope={group:'isolated-native-cli',peer:'isolated-phone'},sessionId='native-cli-archive-session';
            const canonical=value=>value?.[Symbol.for('cordis.original')]??value;
            export function apply(ctx){let task;const cancel=ctx.get('appReady').onReady(()=>{task=verify(ctx);});
              ctx.on('dispose',async()=>{cancel();await task;});}
            async function verify(ctx){
              const root='/dafeiyu-archive-smoke',{phase}=JSON.parse(await readFile(root+'/run-config.json','utf8'));
              const anchor=process.env.DAFEIYU_DSH_INSTALL_ANCHOR,directory=process.env.DAFEIYU_DSH_ARCHIVE_DIRECTORY;
              const require=createRequire(anchor);let lifecycle,code=0;const checks=[];
              const pass=message=>{checks.push(message);console.log('PASS '+checks.length+' '+phase+': '+message);};
              const signal=()=>AbortSignal.timeout(15000);
              try{
                const archive=await import('/dafeiyu-connection-slot/package/lib/framework/remote-archive-dsh.js');
                const {RemoteWorkspaceLifecycle}=await import('/dafeiyu-connection-slot/package/lib/framework/remote-workspaces.js');
                await archive.assertFixedDshArchivePreload(anchor,directory);
                const [{default:Jsonl},{WorkspaceRegistry},{SqliteSessionQueryEngine},{SessionProjectionCache}]=await Promise.all(
                  ['dsh-session-persistence-jsonl','dsh-workspace','dsh-session-query-sqlite','dsh-session-projection-cache']
                    .map(name=>import(pathToFileURL(require.resolve('@deepseek-ai/'+name)).href)));
                const persistence=ctx.get('sessionPersistence'),registry=ctx.get('workspaceRegistry'),query=ctx.get('sessionQuery'),cache=ctx.get('sessionProjectionCache');
                assert.ok(canonical(persistence) instanceof Jsonl);assert.ok(canonical(registry) instanceof WorkspaceRegistry);
                assert.ok(canonical(query) instanceof SqliteSessionQueryEngine);assert.ok(canonical(cache) instanceof SessionProjectionCache);
                assert.ok(new archive.FixedDshArchiveDriver(ctx).available());assert.ok(ctx.get('fileUploads').registerAgentResolver);
                pass('actual CLI Loader services and frozen preloader share the verified first-party realm');
                lifecycle=new RemoteWorkspaceLifecycle(ctx,{directory,archiveDeletion:true});await lifecycle.ready;
                assert.equal(await lifecycle.ensureArchiveDeletion(),true);
                const execute=(operation,payload)=>lifecycle.execute(operation,payload,randomUUID(),signal(),scope);
                const fixtureFile=path.join(root,'session-fixture.json');
                if(phase==='create-delete'){
                  const workPath='/dafeiyu-dsh-home/archive-fixture/workspace';await mkdir(workPath,{recursive:true});
                  const work=await registry.create(workPath,'Android native archive QA');
                  const session=ctx.get('sessions').prepare(sessionId,{meta:{cwd:workPath}});
                  const writer=await persistence.create(session.header);await writer.flush();await writer.close();await work.attachSession(sessionId);
                  const log=await persistence.resolveCurrentLog(sessionId);assert.ok((await stat(log)).isFile());
                  const lock=path.join(path.dirname(log),'session.lock'),lockBefore=await lstat(lock,{bigint:true});
                  assert.ok(lockBefore.isFile()&&!lockBefore.isSymbolicLink());
                  await execute('session.archive',{sessionId});const listed=await execute('session.archived.list',{});
                  assert.equal(listed.archives.length,1);assert.equal(listed.archives[0].remainingDays,30);
                  await query.searchSessions({query:'offline'});const database=canonical(query)._db;
                  assert.equal(database.prepare('SELECT COUNT(*) AS count FROM persisted_sessions WHERE id = ?').get(sessionId).count,1);
                  pass('real JSONL log native archive and SQLite projection exist before confirmation');
                  const prepared=await execute('session.archived.delete.prepare',{sessionId});assert.equal(prepared.status,'prepared');assert.ok((await stat(log)).isFile());
                  await assert.rejects(execute('session.archived.delete',{ticket:prepared.ticket}),/archive_explicit_confirmation_required/);
                  await assert.rejects(lifecycle.execute('session.archived.delete',{ticket:prepared.ticket,confirmDelete:true},randomUUID(),signal(),{...scope,peer:'foreign-phone'}),/archive_confirmation_scope_changed/);
                  pass('prepare retains actual log until exact scope and explicit confirmation');
                  const deleted=await execute('session.archived.delete',{ticket:prepared.ticket,confirmDelete:true});
                  assert.equal(deleted.deleted,true);assert.equal(deleted.status,'deleted');await assert.rejects(stat(log),{code:'ENOENT'});
                  assert.equal(await persistence.stat(sessionId),undefined);assert.ok(!registry.archivedSessionIds.includes(sessionId));
                  assert.ok(!registry.get(work.id).sessionIds.includes(sessionId));
                  assert.equal(database.prepare('SELECT COUNT(*) AS count FROM persisted_sessions WHERE id = ?').get(sessionId).count,0);
                  assert.deepEqual((await execute('session.archived.list',{})).archives,[]);
                  assert.equal((await execute('session.archived.delete',{ticket:prepared.ticket,confirmDelete:true})).deleted,true);
                  const entries=await readdir(path.dirname(log));assert.deepEqual(entries,['session.lock']);
                  const lockAfter=await lstat(lock,{bigint:true});assert.equal(lockAfter.ino,lockBefore.ino);assert.equal(lockAfter.dev,lockBefore.dev);
                  await writeFile(fixtureFile,JSON.stringify({sessionId,log,workspaceId:work.id,ticket:prepared.ticket,
                    lock,lockInode:String(lockBefore.ino),lockDevice:String(lockBefore.dev),directoryEntriesAfterDelete:entries}));
                  pass('frozen lifecycle deletes actual native generations and derived rows idempotently retaining the directory and stable POSIX lock inode');
                }else if(phase==='cold-restart'){
                  const fixture=JSON.parse(await readFile(fixtureFile,'utf8'));await assert.rejects(stat(fixture.log),{code:'ENOENT'});
                  const lock=await lstat(fixture.lock,{bigint:true});assert.equal(String(lock.ino),fixture.lockInode);assert.equal(String(lock.dev),fixture.lockDevice);
                  await assert.rejects(persistence.open(sessionId),/archive_tombstoned/);
                  assert.throws(()=>ctx.get('sessions').prepare(sessionId),/archive_tombstoned/);
                  await assert.rejects(registry.unarchiveSession(sessionId),/archive_tombstoned/);
                  assert.ok(!registry.archivedSessionIds.includes(sessionId));assert.ok(!registry.get(fixture.workspaceId).sessionIds.includes(sessionId));
                  const rows=await persistence.list();assert.ok(rows.every(value=>value.header.id!==sessionId));
                  assert.deepEqual((await execute('session.archived.list',{})).archives,[]);
                  assert.equal((await execute('session.archived.delete',{ticket:fixture.ticket,confirmDelete:true})).deleted,true);
                  pass('a new actual CLI process reads the durable tombstone and refuses reopen recreate restore and listing');
                }else throw new Error('unknown_qa_phase');
                assert.equal(globalThis[Symbol.for('dafeiyu-go.archive-qa-network-count')](),0);
                pass('no prompt model invocation or external fetch');
                await writeFile(root+'/'+phase+'-result.json',JSON.stringify({passed:true,checks,actualCliServices:true,frozenLib:true,paidModelRequests:0}));
              }catch(error){code=1;await writeFile(root+'/'+phase+'-result.json',JSON.stringify({passed:false,checks,name:error?.name,code:error?.code,
                reason:error?.details?.reason,message:String(error?.message).slice(0,8000)}));console.error(error);
              }finally{try{await lifecycle?.dispose();}catch(error){code=1;console.error(error);}
                queueMicrotask(()=>ctx.get('appExit')(code));}
            }
        """.trimIndent()
    }
}
