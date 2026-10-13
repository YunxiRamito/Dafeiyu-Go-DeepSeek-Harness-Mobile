package com.dafeiyu.mobile.data

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Production consent/dispatcher and real file effects; Linux enforcement is tested on Android separately. */
fun main(args: Array<String>) = runBlocking {
    val root = File(args.single()).apply { check(isDirectory || mkdirs()) }
    var passed = 0
    suspend fun checkCase(name: String, action: suspend () -> Unit) { withTimeout(5000) { action() }; passed++; println("PASS $name") }
    suspend fun pending(bridge: OfficialToolApprovalBridge): OfficialToolApprovalRequest = withTimeout(2000) {
        while (bridge.pending.value == null) delay(5)
        requireNotNull(bridge.pending.value)
    }
    fun call(name: String, arguments: JSONObject, id: String = UUID.randomUUID().toString()) = OfficialToolCall(id, name, arguments.toString())
    fun outputStore() = OfficialToolOutputStore(File(root, UUID.randomUUID().toString()).apply { mkdirs() })
    checkCase("fixed DSH presets retain their true ask/never policy meanings") {
        require(OfficialSandboxPolicy(LinuxSandboxContract.READ_ONLY).approvalPolicy == "ask")
        require(OfficialSandboxPolicy(LinuxSandboxContract.WORKSPACE_WRITE).approvalPolicy == "ask")
        require(OfficialSandboxPolicy(LinuxSandboxContract.FULL_ACCESS).approvalPolicy == "never")
        require(runCatching { OfficialSandboxPolicy("auto") }.isFailure)
    }
    checkCase("one answer authorizes the exact displayed operation and cannot be replayed") {
        val bridge = OfficialToolApprovalBridge(1000)
        val job = async { bridge.request("run_script", "workspace", "exact summary", "echo exact") }
        val request = pending(bridge)
        require(request.content == "echo exact" && request.workspaceId == "workspace")
        require(!bridge.answer(UUID.randomUUID().toString(), true))
        require(bridge.answer(request.id, true) && !bridge.answer(request.id, true))
        require(job.await() && bridge.pending.value == null && !bridge.answer(request.id, true))
    }
    checkCase("denial and consent timeout return false with no persisted grant") {
        val bridge = OfficialToolApprovalBridge(150)
        val job = async { bridge.request("run_script", "workspace", "deny", "echo denied") }
        bridge.answer(pending(bridge).id, false); require(!job.await())
        require(!bridge.request("run_script", "workspace", "timeout", "echo timeout") && bridge.pending.value == null)
    }
    checkCase("cancelling a suspended tool clears its approval and refuses a stale answer") {
        val bridge = OfficialToolApprovalBridge(1000)
        val job = async { bridge.request("run_script", "workspace", "cancel", "echo cancelled") }
        val id = pending(bridge).id
        job.cancelAndJoin(); require(bridge.pending.value == null && !bridge.answer(id, true))
    }
    checkCase("parallel approvals serialize and a previous ID cannot approve the next task") {
        val bridge = OfficialToolApprovalBridge(1000)
        val first = async { bridge.request("run_script", "workspace", "one", "echo first") }
        val one = pending(bridge)
        val second = async { bridge.request("run_script", "workspace", "two", "echo second") }
        require(bridge.pending.value?.id == one.id); bridge.answer(one.id, false); require(!first.await())
        val two = pending(bridge); require(two.id != one.id && two.content == "echo second")
        require(!bridge.answer(one.id, true)); bridge.answer(two.id, true); require(second.await())
    }
    checkCase("readonly file output waits for explicit approval and writes only once in the real store") {
        val bridge = OfficialToolApprovalBridge(1000); val store = outputStore()
        val dispatcher = OfficialToolDispatcher({ null }, store, "conversation", { "fixture" }, Dispatchers.Unconfined,
            mutationPolicy = OfficialNativeMutationPolicy(OfficialSandboxPolicy(LinuxSandboxContract.READ_ONLY), "workspace", bridge))
        val request = call("write_text_file", JSONObject().put("filename", "result.txt").put("text", "one exact file"))
        val job = async { dispatcher.execute(request) }; val displayed = pending(bridge)
        require(displayed.content == "one exact file" && store.list().isEmpty())
        bridge.answer(displayed.id, true)
        val result = job.await(); require(JSONObject(result.content).getBoolean("ok"))
        require(store.list().size == 1 && dispatcher.execute(request) == result && bridge.pending.value == null)
    }
    checkCase("denied readonly file output returns an honest error and leaves no files") {
        val bridge = OfficialToolApprovalBridge(1000); val store = outputStore()
        val dispatcher = OfficialToolDispatcher({ null }, store, "conversation", { "fixture" }, Dispatchers.Unconfined,
            mutationPolicy = OfficialNativeMutationPolicy(OfficialSandboxPolicy(LinuxSandboxContract.READ_ONLY), "workspace", bridge))
        val request = call("write_text_file", JSONObject().put("filename", "result.txt").put("text", "denied"))
        val job = async { dispatcher.execute(request) }; bridge.answer(pending(bridge).id, false)
        val result = job.await(); require(JSONObject(result.content).getString("error") == "approval_denied")
        require(store.list().isEmpty() && dispatcher.execute(request) == result)
    }
    checkCase("cancellation at the approval boundary never commits a file") {
        val bridge = OfficialToolApprovalBridge(1000); val store = outputStore()
        val dispatcher = OfficialToolDispatcher({ null }, store, "conversation", { "fixture" }, Dispatchers.Unconfined,
            mutationPolicy = OfficialNativeMutationPolicy(OfficialSandboxPolicy(LinuxSandboxContract.READ_ONLY), "workspace", bridge))
        val job = async { dispatcher.execute(call("write_text_file", JSONObject().put("filename", "result.txt").put("text", "cancelled"))) }
        val displayed = pending(bridge); job.cancelAndJoin()
        require(store.list().isEmpty() && bridge.pending.value == null && !bridge.answer(displayed.id, true))
    }
    checkCase("invalid output paths fail before an approval is exposed") {
        val bridge = OfficialToolApprovalBridge(1000)
        val policy = OfficialNativeMutationPolicy(OfficialSandboxPolicy(LinuxSandboxContract.READ_ONLY), "workspace", bridge)
        require(runCatching { policy.authorize("write_text_file", JSONObject().put("filename", "../escape.txt").put("text", "no")) }.isFailure)
        require(bridge.pending.value == null)
    }
    checkCase("normal workspace outputs stay within managed storage without unnecessary escalation") {
        val bridge = OfficialToolApprovalBridge(1000); val store = outputStore()
        val dispatcher = OfficialToolDispatcher({ null }, store, "conversation", { "fixture" }, Dispatchers.Unconfined,
            mutationPolicy = OfficialNativeMutationPolicy(OfficialSandboxPolicy(LinuxSandboxContract.WORKSPACE_WRITE), "workspace", bridge))
        require(JSONObject(dispatcher.execute(call("write_text_file", JSONObject().put("filename", "result.txt").put("text", "allowed"))).content).getBoolean("ok"))
        require(bridge.pending.value == null && store.list().size == 1)
    }
    checkCase("script schemas expose escalation only for providers implementing the real interface") {
        val store = outputStore()
        val normal = OfficialToolDispatcher({ null }, store, "conversation", { "fixture" }, Dispatchers.Unconfined,
            scriptProvider = OfficialToolScriptProvider { _, _, _ -> JSONObject() })
        fun parameters(dispatcher: OfficialToolDispatcher): JSONObject {
            val schemas = dispatcher.schemas()
            return (0 until schemas.length()).map { schemas.getJSONObject(it).getJSONObject("function") }
                .single { it.getString("name") == "run_script" }.getJSONObject("parameters").getJSONObject("properties")
        }
        require(!parameters(normal).has("request_full_access"))
        var invoked = 0
        val escalating = object : OfficialToolEscalatingScriptProvider {
            override suspend fun execute(language: String, script: String, timeoutSeconds: Int): JSONObject = error("legacy route")
            override suspend fun execute(language: String, script: String, timeoutSeconds: Int, requestFullAccess: Boolean, justification: String?): JSONObject {
                invoked++; require(requestFullAccess && justification == "outside task requirement")
                return JSONObject().put("exit_code", 0).put("stdout", "real fixture").put("stderr", "")
            }
        }
        val dispatcher = OfficialToolDispatcher({ null }, store, "conversation", { "fixture" }, Dispatchers.Unconfined, scriptProvider = escalating)
        require(parameters(dispatcher).has("request_full_access"))
        val invalid = dispatcher.execute(call("run_script", JSONObject().put("language", "bash").put("script", "echo hi").put("request_full_access", true)))
        require(JSONObject(invalid.content).getString("error") == "invalid_arguments" && invoked == 0)
        val valid = dispatcher.execute(call("run_script", JSONObject().put("language", "bash").put("script", "echo hi")
            .put("request_full_access", true).put("justification", "outside task requirement")))
        require(JSONObject(valid.content).getBoolean("ok") && invoked == 1)
    }
    checkCase("cancelled script escalation stays uncached for a real subsequent retry") {
        var attempts = 0
        val provider = OfficialToolScriptProvider { _, _, _ -> if (++attempts == 1) throw CancellationException("cancel fixture")
            else JSONObject().put("exit_code", 0).put("stdout", "retry").put("stderr", "") }
        val dispatcher = OfficialToolDispatcher({ null }, outputStore(), "conversation", { "fixture" }, Dispatchers.Unconfined, scriptProvider = provider)
        val request = call("run_script", JSONObject().put("language", "node").put("script", "1"))
        require(runCatching { dispatcher.execute(request) }.exceptionOrNull() is CancellationException)
        require(JSONObject(dispatcher.execute(request).content).getBoolean("ok") && attempts == 2)
    }
    println("RESULT $passed/$passed production preset/approval/dispatcher plus real managed file effects; Android/Linux/UI verification separate; paid=0")
}
