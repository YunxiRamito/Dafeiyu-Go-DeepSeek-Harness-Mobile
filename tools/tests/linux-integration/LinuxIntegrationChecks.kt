package com.dafeiyu.mobile.data

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject

fun main(args: Array<String>) = runBlocking {
    val root = File(args.single()).apply { mkdirs() }
    var passed = 0
    suspend fun test(name: String, block: suspend () -> Unit) {
        withTimeout(5_000) { block() }
        println("PASS $name")
        passed++
    }
    fun result(response: OfficialToolResult) = JSONObject(response.content)
    fun call(id: String, body: JSONObject) = OfficialToolCall(id, "run_script", body.toString())
    fun body() = JSONObject().put("language", "python").put("script", "print(42)")
    fun dispatcher(provider: OfficialToolScriptProvider? = null) = OfficialToolDispatcher(
        { null }, OfficialToolOutputStore(File(root, "outputs")), "conversation-test", { "model" },
        Dispatchers.Unconfined, scriptProvider = provider)
    test("script schema is absent without a provider and present with a provider") {
        check(!dispatcher().schemas().toString().contains("run_script"))
        check(dispatcher(OfficialToolScriptProvider { _, _, _ -> JSONObject() }).schemas().toString().contains("run_script"))
        check(result(dispatcher().execute(call("missing", body()))).getString("error") == "linux_runtime_unavailable")
    }
    test("actual provider result preserves nonzero exit, timeout and output") {
        val d = dispatcher(OfficialToolScriptProvider { language, script, timeout ->
            check(language == "python" && script == "print(42)" && timeout == 30)
            JSONObject().put("exit_code", 17).put("stdout", "42\n").put("stderr", "failure").put("timed_out", true)
        })
        val data = result(d.execute(call("result", body()))).getJSONObject("data")
        check(data.getInt("exit_code") == 17 && data.getBoolean("timed_out") && data.getString("stdout") == "42\n")
    }
    test("replayed call ID executes script once and changed arguments are rejected") {
        var executions = 0
        val d = dispatcher(OfficialToolScriptProvider { _, _, _ -> executions++; JSONObject().put("exit_code", 0) })
        val original = call("replay", body())
        check(d.execute(original) == d.execute(original) && executions == 1)
        check(result(d.execute(call("replay", body().put("script", "print(43)")))).getString("error") == "call_id_reused")
    }
    test("large output stays within the reply limit and flags truncation") {
        val d = dispatcher(OfficialToolScriptProvider { _, _, _ ->
            JSONObject().put("exit_code", 0).put("stdout", "中".repeat(100_000))
                .put("stderr", "error".repeat(50_000)).put("output_truncated", false)
        })
        val reply = d.execute(call("large-output", body()))
        val data = result(reply).getJSONObject("data")
        check(data.getBoolean("output_truncated"))
        check(reply.content.toByteArray().size < OfficialToolContract.MAX_REPLY_BYTES)
        check(data.getString("stdout").length == 8192 && data.getString("stderr").length == 8192)
    }
    test("runtime failure preserves an actionable structured failure") {
        val d = dispatcher(OfficialToolScriptProvider { _, _, _ ->
            throw OfficialScriptException("linux_runtime_unavailable", JSONObject().put("hint", "Install Ubuntu"))
        })
        val reply = result(d.execute(call("runtime-error", body())))
        check(!reply.getBoolean("ok") && reply.getString("error") == "linux_runtime_unavailable")
        check(reply.getJSONObject("details").getString("hint") == "Install Ubuntu")
    }
    test("malformed language, extra fields and fractional or out-of-range timeout never execute") {
        var executions = 0
        val d = dispatcher(OfficialToolScriptProvider { _, _, _ -> executions++; JSONObject() })
        val invalid = listOf(body().put("language", "powershell"), body().put("extra", true),
            body().put("timeout_seconds", 1.5), body().put("timeout_seconds", 0), body().put("timeout_seconds", 121))
        invalid.forEachIndexed { i, b ->
            check(result(d.execute(call("invalid-$i", b))).getString("error") == "invalid_arguments")
        }
        check(executions == 0)
    }
    test("cancelled script remains cancellable and is not replay-cached") {
        val entered = CompletableDeferred<Unit>()
        var executions = 0
        val d = dispatcher(OfficialToolScriptProvider { _, _, _ ->
            executions++
            if (executions == 1) { entered.complete(Unit); awaitCancellation() }
            JSONObject().put("exit_code", 0)
        })
        val job = launch { d.execute(call("cancel", body())) }
        entered.await()
        job.cancelAndJoin()
        check(result(d.execute(call("cancel", body()))).getBoolean("ok") && executions == 2)
    }
    test("source policy keeps authenticated GitHub API direct and backend separate") {
        val url = "https://github.com/example/project/releases/download/v1/a.tgz"
        val cdn = ComponentDownloadSources.candidates(url, "cdn")
        check(cdn.size == 4 && cdn.last() == url && cdn.none { it.startsWith(ComponentDownloadSources.BACKEND) })
        check(ComponentDownloadSources.candidates(url, "official") == listOf(url))
        check(ComponentDownloadSources.candidates(url, "backend").single().startsWith(ComponentDownloadSources.BACKEND + "/api/download?url="))
        check(ComponentDownloadSources.candidates("https://api.github.com/repos/a/b", "backend") == listOf("https://api.github.com/repos/a/b"))
        check(ComponentDownloadSources.npmRegistry("cdn") == "https://registry.npmmirror.com")
        check(runCatching { ComponentDownloadSources.candidates("http://github.com/a", "cdn") }.isFailure)
        check(runCatching { ComponentDownloadSources.candidates("https://user:pass@github.com/a", "cdn") }.isFailure)
    }
    val payload = "verified-component-content".toByteArray()
    fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    val expected = hash(payload)
    val context = Context(File(root, "files").apply { mkdirs() })
    val url = "https://github.com/example/project/releases/download/v1/a.tgz"
    fun client(block: (String) -> ByteArray) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(block(chain.request().url.toString()).toResponseBody()).build()
    }.build()
    test("a corrupt mirror falls through to a verified official response") {
        val visited = mutableListOf<String>()
        val download = ComponentDownloadClient(context, client { candidate ->
            visited += candidate
            if (candidate == url) payload else "tampered".toByteArray()
        })
        val target = File(context.filesDir, "fallback.tgz")
        download.download(url, expected, target, "cdn")
        check(target.readBytes().contentEquals(payload) && visited.size == 4)
        check(!File(target.path + ".partial").exists())
    }
    test("verified cache is reused without transport") {
        var calls = 0
        val target = File(context.filesDir, "cache.tgz").apply { writeBytes(payload) }
        ComponentDownloadClient(context, client { calls++; payload }).download(url, expected, target, "official")
        check(calls == 0)
    }
    test("all corrupt responses preserve the previous file and remove the partial") {
        val target = File(context.filesDir, "preserved.tgz").apply { writeText("old-content") }
        check(runCatching {
            ComponentDownloadClient(context, client { "tampered".toByteArray() }).download(url, expected, target, "cdn")
        }.isFailure)
        check(target.readText() == "old-content" && !File(target.path + ".partial").exists())
    }
    test("cancel during streaming cleans the partial and preserves the old file") {
        val target = File(context.filesDir, "cancelled.tgz").apply { writeText("old-content") }
        val job = launch {
            try {
                ComponentDownloadClient(context, client { payload }).download(url, expected, target, "official") { _, _ ->
                    throw CancellationException("fixture cancellation")
                }
                error("Cancellation was swallowed")
            } catch (_: CancellationException) { }
        }
        job.join()
        check(target.readText() == "old-content" && !File(target.path + ".partial").exists())
    }
    test("destination outside private storage is rejected before transport") {
        var calls = 0
        check(runCatching {
            ComponentDownloadClient(context, client { calls++; payload }).download(url, expected, File(root, "outside"), "official")
        }.isFailure)
        check(calls == 0 && !File(root, "outside").exists())
    }
    println("RESULT $passed/$passed PASS; synthetic transport/provider only, no TLS/Android runtime/model calls")
}
