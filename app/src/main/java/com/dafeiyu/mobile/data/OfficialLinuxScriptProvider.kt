package com.dafeiyu.mobile.data

import android.content.Context
import java.io.File
import org.json.JSONObject

/** Scripts execute in an owned Linux working directory; Android credentials are never bound into the guest. */
class OfficialLinuxScriptProvider(context: Context, private val workspaceId: String,
    private val source: () -> String) : OfficialToolScriptProvider {
    private val app = context.applicationContext
    override suspend fun execute(language: String, script: String, timeoutSeconds: Int): JSONObject {
        require(workspaceId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val workspace = File(app.filesDir, "linux-workspaces/$workspaceId")
        check(workspace.isDirectory || workspace.mkdirs())
        val result = try {
            LinuxRuntimeManager(app).execute(language, script, workspace, timeoutSeconds, source = source())
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            throw OfficialScriptException("linux_runtime_unavailable", JSONObject()
                .put("hint", "请在设置的组件页安装或重新检测 Ubuntu 与所需语言组件")
                .put("reason", failed.message.orEmpty().take(600)))
        }
        return JSONObject().put("exit_code", result.exitCode).put("stdout", result.stdout)
            .put("stderr", result.stderr).put("timed_out", result.timedOut)
            .put("output_truncated", result.outputTruncated).put("working_directory", "/workspace")
    }
}
