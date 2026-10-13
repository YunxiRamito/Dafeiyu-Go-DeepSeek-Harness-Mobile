package com.dafeiyu.mobile.data

import android.content.Context
import android.system.Os
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Exact runtime facts used by both validation and the execution mutex. */
data class LinuxSandboxIdentity(
    val slot: String,
    val abi: String,
    val activeReceiptSha256: String,
    val launcherSha256: String,
    val nativeResourcesSha256: String,
    val nativeLocationSha256: String,
    val kernelBuildSha256: String,
) {
    fun toJson(): JSONObject = JSONObject().put("slot", slot).put("abi", abi)
        .put("active_receipt_sha256", activeReceiptSha256).put("launcher_sha256", launcherSha256)
        .put("native_resources_sha256", nativeResourcesSha256).put("native_location_sha256", nativeLocationSha256)
        .put("kernel_build_sha256", kernelBuildSha256)
}

/** Created only after causal file effects and inherited restrictions succeed.
 * A serialized report is diagnostic data and is never restored as a grant. */
class LinuxSandboxReceipt internal constructor(
    val identity: LinuxSandboxIdentity,
    val verifiedAt: Long,
    private val evidenceJson: String,
) {
    val supportsConstrainedModes: Boolean get() = true
    fun toJson(): JSONObject = JSONObject().put("schema", 1).put("verified_at", verifiedAt)
        .put("identity", identity.toJson()).put("completeFileEffectsVerified", true)
        .put("evidence", JSONObject(evidenceJson))
}

/** Validates the public execute path on this device and active slot. A kernel
 * probe sentence alone cannot enable the native light engine's sandbox menu. */
class LinuxSandboxVerifier(
    context: Context,
    private val runtime: LinuxRuntimeManager = LinuxRuntimeManager(context),
) {
    private val app = context.applicationContext
    private val root = File(app.noBackupFilesDir, "linux-sandbox-verifier")
    private val shared = stores.computeIfAbsent(root.canonicalPath) { Shared() }

    suspend fun currentReceipt(): LinuxSandboxReceipt? = withContext(Dispatchers.IO) {
        shared.lock.withLock {
            val receipt = shared.receipt ?: return@withLock null
            try {
                if (runtime.sandboxIdentity() == receipt.identity) receipt
                else { shared.receipt = null; null }
            } catch (failure: kotlinx.coroutines.CancellationException) {
                throw failure
            } catch (_: Exception) { shared.receipt = null; null }
        }
    }

    suspend fun verify(force: Boolean = false): LinuxSandboxReceipt = withContext(Dispatchers.IO) {
        shared.lock.withLock {
            val identity = runtime.sandboxIdentity()
            if (!force) shared.receipt?.takeIf { it.identity == identity }?.let { return@withLock it }
            shared.receipt = null
            val evidence = verifyFileEffects(identity)
            check(runtime.sandboxIdentity() == identity) { "运行环境已变化，沙箱验证未生效" }
            LinuxSandboxReceipt(identity, System.currentTimeMillis(), evidence.toString()).also { shared.receipt = it }
        }
    }

    private suspend fun verifyFileEffects(identity: LinuxSandboxIdentity): JSONObject {
        require(!LinuxArchiveExtractor.isSymlink(root) && (root.isDirectory || root.mkdirs()))
        Os.chmod(root.path, 0x1c0)
        val token = UUID.randomUUID().toString()
        val fixture = File(root, "run-$token").apply { check(mkdir()) }
        val workspace = File(fixture, "workspace").apply { check(mkdir()) }
        Os.chmod(fixture.path, 0x1c0); Os.chmod(workspace.path, 0x1c0)
        val outside = "/root/dafeiyu-sandbox-verifier-$token"
        val paths = JSONObject().put("workspace", "/workspace").put("outside", outside)
        val setup = """
            const fs=require('node:fs');const p=$paths;
            fs.rmSync(p.outside,{recursive:true,force:true});fs.mkdirSync(p.outside);
            for(const f of fs.readdirSync(p.workspace))fs.rmSync(p.workspace+'/'+f,{recursive:true,force:true});
            fs.writeFileSync(p.workspace+'/existing.txt','original');fs.writeFileSync(p.outside+'/existing.txt','original');
            fs.symlinkSync(p.outside+'/existing.txt',p.workspace+'/outside-link');console.log('ready');
        """.trimIndent()
        val proofs = JSONArray()
        var baseline: JSONArray? = null
        var failed: Throwable? = null
        try {
            for (mode in listOf(LinuxSandboxContract.FULL_ACCESS, LinuxSandboxContract.READ_ONLY, LinuxSandboxContract.WORKSPACE_WRITE)) {
                val prepared = execute(setup, workspace, identity, LinuxSandboxContract.FULL_ACCESS)
                check(prepared.stdout.trim() == "ready") { "无法准备独立沙箱文件验证目录" }
                val script = """
                    const fs=require('node:fs');const cp=require('node:child_process');const p=$paths;
                    const mode=${JSONObject.quote(mode)},inside=mode!=='read-only',outside=mode==='danger-full-access';
                    const denied=new Set(['EACCES','EPERM','EROFS','EXDEV']),cases=[];
                    function attempt(name,allowed,fn){let succeeded=false,error=null;try{fn();succeeded=true;}catch(e){error=e.code??e.name;}
                      cases.push({name,expectedAllowed:allowed,succeeded,error,pass:allowed?succeeded:!succeeded&&denied.has(error)});}
                    attempt('read-workspace',true,()=>fs.readFileSync(p.workspace+'/existing.txt'));
                    attempt('read-outside',true,()=>fs.readFileSync(p.outside+'/existing.txt'));
                    attempt('create-workspace',inside,()=>fs.writeFileSync(p.workspace+'/created.txt','inside'));
                    attempt('create-outside',outside,()=>fs.writeFileSync(p.outside+'/created.txt','outside'));
                    attempt('truncate-workspace',inside,()=>fs.truncateSync(p.workspace+'/existing.txt',1));
                    attempt('truncate-outside',outside,()=>fs.truncateSync(p.outside+'/existing.txt',1));
                    attempt('write-through-symlink-outside',outside,()=>fs.writeFileSync(p.workspace+'/outside-link','symlink'));
                    attempt('hardlink-outside-into-workspace',outside,()=>fs.linkSync(p.outside+'/existing.txt',p.workspace+'/hard-link'));
                    const child="const fs=require('node:fs');fs.readFileSync(process.argv[3]);const writes=[];for(const target of process.argv.slice(1,3)){let wrote=false,error=null;try{fs.writeFileSync(target,'nested');wrote=true;}catch(e){error=e.code??e.name;}writes.push({wrote,error});}console.log(JSON.stringify({started:true,readSucceeded:true,writes}));";
                    const nested=cp.spawnSync(process.execPath,['-e',child,p.workspace+'/child-created.txt',p.outside+'/child-created.txt',p.outside+'/existing.txt'],{encoding:'utf8',timeout:5000,maxBuffer:65536,stdio:['ignore','pipe','pipe']});
                    let parsed=null;try{parsed=JSON.parse(nested.stdout?.trim()??'');}catch{}
                    const started=nested.status===0&&parsed?.started===true&&parsed?.readSucceeded===true;
                    for(const [index,allowed,name] of [[0,inside,'child-inherits-workspace-policy'],[1,outside,'child-inherits-outside-policy']]){
                      const target=index===0?p.workspace+'/child-created.txt':p.outside+'/child-created.txt',write=parsed?.writes?.[index];
                      const succeeded=write?.wrote===true&&fs.existsSync(target),blocked=write?.wrote===false&&denied.has(write?.error)&&!fs.existsSync(target);
                      cases.push({name,expectedAllowed:allowed,succeeded,error:write?.error??nested.error?.code??null,childStarted:started,pass:started&&(allowed?succeeded:blocked)});
                    }
                    console.log(JSON.stringify({mode,cases}));
                """.trimIndent()
                val result = execute(script, workspace, identity, mode)
                val proof = JSONObject(result.stdout).put("enforcement", result.sandboxEnforcement)
                check(proof.getString("mode") == mode)
                val cases = proof.getJSONArray("cases")
                check(cases.length() == 10)
                if (mode == LinuxSandboxContract.FULL_ACCESS) {
                    baseline = JSONArray(cases.toString())
                    for (index in 0 until cases.length()) {
                        val item = cases.getJSONObject(index)
                        if (!item.getBoolean("pass")) {
                            check(item.getString("name") == "hardlink-outside-into-workspace" &&
                                item.optString("error") in setOf("EACCES", "EPERM", "EXDEV")) { "完全权限基线不能执行文件验证操作" }
                            item.put("baselineUnavailable", true)
                        }
                    }
                } else for (index in 0 until cases.length()) {
                    val item = cases.getJSONObject(index)
                    check(item.getBoolean("pass")) { "当前设备不能兑现 $mode 的 ${item.getString("name")} 约束" }
                    val base = checkNotNull(baseline).getJSONObject(index)
                    check(base.getString("name") == item.getString("name"))
                    item.put("causalProof", base.getBoolean("pass"))
                    if (!base.getBoolean("pass")) item.put("baselineUnavailable", true)
                }
                val insideAllowed = mode != LinuxSandboxContract.READ_ONLY
                check(File(workspace, "created.txt").exists() == insideAllowed &&
                    File(workspace, "child-created.txt").exists() == insideAllowed &&
                    File(workspace, "existing.txt").readText() == if (insideAllowed) "o" else "original") {
                    "沙箱工作区实际文件效果与验证结果不一致"
                }
                val observe = """
                    const fs=require('node:fs');const p=$paths;
                    console.log(JSON.stringify({created:fs.existsSync(p.outside+'/created.txt'),child:fs.existsSync(p.outside+'/child-created.txt'),
                      existing:fs.readFileSync(p.outside+'/existing.txt','utf8')}));
                """.trimIndent()
                val effects = JSONObject(execute(observe, workspace, identity, LinuxSandboxContract.FULL_ACCESS).stdout)
                val outsideAllowed = mode == LinuxSandboxContract.FULL_ACCESS
                check(effects.getBoolean("created") == outsideAllowed && effects.getBoolean("child") == outsideAllowed &&
                    effects.getString("existing") == if (outsideAllowed) "symlink" else "original") {
                    "沙箱外部目录实际文件效果与验证结果不一致"
                }
                proofs.put(proof.put("observedFileEffects", true))
            }
            return JSONObject().put("modes", proofs).put("baselineUnavailableHardlinksExcluded", true)
                .put("note", "Android 本身拒绝的硬链接操作不计作 Landlock 因果证据")
        } catch (failure: Throwable) {
            failed = failure
            throw failure
        } finally {
            withContext(NonCancellable) {
                try {
                    execute("require('node:fs').rmSync(${JSONObject.quote(outside)},{recursive:true,force:true});", workspace,
                        identity, LinuxSandboxContract.FULL_ACCESS)
                    check(!runtime.hasProcessLease(fixture) && fixture.deleteRecursively()) { "沙箱验证目录仍被进程占用" }
                } catch (cleanup: Throwable) {
                    if (failed != null) failed.addSuppressed(cleanup) else throw cleanup
                }
            }
        }
    }

    private suspend fun execute(script: String, workspace: File, identity: LinuxSandboxIdentity, mode: String): LinuxExecutionResult {
        val result = runtime.execute("node", script, workspace, 20, sandboxMode = mode, expectedSandboxIdentity = identity)
        check(result.exitCode == 0 && !result.timedOut && !result.outputTruncated && result.sandboxMode == mode &&
            result.sandboxEnforcement == if (mode == LinuxSandboxContract.FULL_ACCESS) "android-uid" else "full") {
            "实际沙箱验证脚本未完成；未启用受限档位"
        }
        return result
    }

    private class Shared { val lock = Mutex(); var receipt: LinuxSandboxReceipt? = null }
    private companion object { val stores = ConcurrentHashMap<String, Shared>() }
}
