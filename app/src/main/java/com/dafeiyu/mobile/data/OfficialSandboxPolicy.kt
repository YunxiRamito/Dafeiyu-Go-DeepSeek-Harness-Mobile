package com.dafeiyu.mobile.data

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Matches the fixed DSH permission preset table. 'never' denies escalation. */
data class OfficialSandboxPolicy(val mode: String) {
    init { require(mode in LinuxSandboxContract.modes) }
    val approvalPolicy: String get() = if (mode == LinuxSandboxContract.FULL_ACCESS) "never" else "ask"
    val title: String get() = when (mode) {
        LinuxSandboxContract.READ_ONLY -> "只读"
        LinuxSandboxContract.WORKSPACE_WRITE -> "工作区内修改"
        else -> "完全权限"
    }
}

data class OfficialToolApprovalRequest(
    val id: String,
    val tool: String,
    val workspaceId: String,
    val summary: String,
    val content: String,
    internal val answer: CompletableDeferred<Boolean> = CompletableDeferred(),
)

/** One user response authorizes precisely one suspended operation. No stored grant. */
class OfficialToolApprovalBridge internal constructor(private val timeoutMillis: Long = 180_000L) {
    init { require(timeoutMillis in 1..180_000L) }
    private val mutex = Mutex()
    private val current = MutableStateFlow<OfficialToolApprovalRequest?>(null)
    val pending = current.asStateFlow()

    suspend fun request(tool: String, workspaceId: String, summary: String, content: String): Boolean {
        require(tool in setOf("run_script", "write_text_file"))
        require(workspaceId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(summary.isNotBlank() && summary.length <= 1000 && !summary.contains('\u0000'))
        require(content.length <= OfficialToolContract.MAX_TEXT_CHARACTERS && !content.contains('\u0000'))
        return mutex.withLock {
            val request = OfficialToolApprovalRequest(UUID.randomUUID().toString(), tool, workspaceId, summary, content)
            current.value = request
            try { withTimeoutOrNull(timeoutMillis) { request.answer.await() } == true }
            finally { if (current.value?.id == request.id) current.value = null }
        }
    }

    fun answer(id: String, allowedOnce: Boolean): Boolean {
        val request = current.value?.takeIf { it.id == id } ?: return false
        return request.answer.complete(allowedOnce)
    }

    companion object { val instance = OfficialToolApprovalBridge() }
}

class OfficialNativeMutationPolicy(private val policy: OfficialSandboxPolicy, private val workspaceId: String,
    private val bridge: OfficialToolApprovalBridge = OfficialToolApprovalBridge.instance) : OfficialToolMutationPolicy {
    override suspend fun authorize(name: String, arguments: JSONObject): Boolean {
        if (name != "write_text_file" || policy.mode != LinuxSandboxContract.READ_ONLY) return true
        require(arguments.keys().asSequence().all { it in setOf("filename", "text") } && arguments.length() == 2)
        val filename = OfficialToolContract.filename(arguments.getString("filename"))
        val text = OfficialToolContract.safeText(arguments.getString("text"), OfficialToolContract.MAX_TEXT_CHARACTERS, allowEmpty = true)
        return bridge.request(name, workspaceId, "只读档位下创建对话文件 $filename。文件将保存在本机应用的对话产物目录。", text)
    }
}
