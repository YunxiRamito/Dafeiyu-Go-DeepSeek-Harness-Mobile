package com.dafeiyu.mobile.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** Scripts execute in an owned Linux working directory; Android credentials are never bound into the guest. */
class OfficialLinuxScriptProvider(context: Context, private val workspaceId: String,
    private val policy: OfficialSandboxPolicy = OfficialSandboxPolicy(LinuxSandboxContract.WORKSPACE_WRITE),
    private val source: () -> String) : OfficialToolEscalatingScriptProvider {
    private val app = context.applicationContext
    override suspend fun execute(language: String, script: String, timeoutSeconds: Int): JSONObject =
        execute(language, script, timeoutSeconds, false, null)

    override suspend fun execute(language: String, script: String, timeoutSeconds: Int, requestFullAccess: Boolean,
        justification: String?): JSONObject {
        require(workspaceId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        var mode = policy.mode
        if (requestFullAccess && mode != LinuxSandboxContract.FULL_ACCESS) {
            require(!justification.isNullOrBlank() && justification.length <= 500)
            if (policy.approvalPolicy != "ask" || !OfficialToolApprovalBridge.instance.request("run_script", workspaceId,
                    "${policy.title}档位下申请本次完全权限。语言：$language\n原因：$justification", script))
                throw OfficialScriptException("approval_denied")
            mode = LinuxSandboxContract.FULL_ACCESS
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val workspace = File(app.filesDir, "linux-workspaces/$workspaceId")
        check(workspace.isDirectory || workspace.mkdirs())
        val runtime = LinuxRuntimeManager(app)
        val result = try {
            val identity = if (mode == LinuxSandboxContract.FULL_ACCESS) null else LinuxSandboxVerifier(app, runtime).verify().identity
            runtime.execute(language, script, workspace, timeoutSeconds, source = source(), sandboxMode = mode,
                expectedSandboxIdentity = identity)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            throw OfficialScriptException(if (mode == LinuxSandboxContract.FULL_ACCESS) "linux_runtime_unavailable" else "sandbox_unavailable", JSONObject()
                .put("hint", "请在设置的组件页安装或重新检测环境；无法兑现所选沙箱时不会无约束执行")
                .put("reason", failed.message.orEmpty().take(600)))
        }
        return JSONObject().put("exit_code", result.exitCode).put("stdout", result.stdout)
            .put("stderr", result.stderr).put("timed_out", result.timedOut)
            .put("output_truncated", result.outputTruncated).put("working_directory", "/workspace")
            .put("sandbox_mode", mode).put("sandbox_enforcement", result.sandboxEnforcement)
            .put("approval_policy", policy.approvalPolicy).put("execution_device", "android_local")
    }
}
