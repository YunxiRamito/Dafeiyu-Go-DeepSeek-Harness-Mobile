package com.dafeiyu.mobile.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

private const val PROTOCOL = "dafeiyu-go.model-worker.v1"
private var passed = 0
private suspend fun test(name: String, block: suspend () -> Unit) {
    withTimeout(10_000) { block() }
    passed++
    println("PASS $name")
}

private class ControlledInput : InputStream() {
    private val bytes = LinkedBlockingQueue<Int>()
    fun send(text: String) { text.toByteArray().forEach { bytes.put(it.toInt() and 255) } }
    override fun read(): Int = bytes.take().also { if (it == -2) throw IOException("fixture old pipe failed") }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val first = read()
        if (first < 0) return -1
        buffer[offset] = first.toByte()
        var count = 1
        while (count < length) {
            val value = bytes.poll() ?: break
            if (value < 0) { bytes.offer(value); break }
            buffer[offset + count++] = value.toByte()
        }
        return count
    }
    override fun available(): Int = bytes.size
    override fun close() { bytes.offer(-1) }
    fun fail() { bytes.offer(-2) }
}

private class FixtureProcess : Process() {
    val stdout = ControlledInput()
    val commands = Channel<JSONObject>(Channel.UNLIMITED)
    val writes = mutableListOf<JSONObject>()
    @Volatile var live = true
    val managed: LinuxManagedProcess
    private val input = object : OutputStream() {
        private val buffer = ByteArrayOutputStream()
        override fun write(value: Int) { buffer.write(value) }
        override fun write(value: ByteArray, offset: Int, length: Int) { buffer.write(value, offset, length) }
        override fun flush() {
            val lines = buffer.toString("UTF-8").trim().lines(); buffer.reset()
            for (line in lines) {
                if (line.isBlank()) continue
                val frame = JSONObject(line)
                synchronized(writes) { writes += frame }
                commands.trySend(frame)
                if (frame.getString("type") == "configure") result(frame.getString("id"), JSONObject().put("configured", true))
            }
        }
    }
    init {
        managed = LinuxManagedProcess("fixture-worker", 1234, "fixture-slot", this) { shutdown() }
        emit(JSONObject().put("type", "ready").put("version", "0.87.1"))
    }
    fun emit(frame: JSONObject) { stdout.send(frame.put("protocol", PROTOCOL).toString() + "\n") }
    fun result(id: String, value: JSONObject) = emit(JSONObject().put("type", "result").put("id", id).put("value", value))
    fun event(id: String, sequence: Int, event: JSONObject) = emit(JSONObject().put("type", "event").put("id", id)
        .put("sequence", sequence).put("event", event))
    fun delayedDeath() { live = false }
    fun releaseExit() { managed.onExited() }
    fun shutdown() { live = false; stdout.close(); managed.onExited() }
    suspend fun command(type: String): JSONObject {
        while (true) { val frame = commands.receive(); if (frame.getString("type") == type) return frame }
    }
    override fun getOutputStream(): OutputStream = input
    override fun getInputStream(): InputStream = stdout
    override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
    override fun waitFor(): Int { while (live) Thread.yield(); return 0 }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !live
    override fun exitValue(): Int { check(!live); return 0 }
    override fun isAlive(): Boolean = live
    override fun destroy() { shutdown() }
}

private fun fixtureWorker(processes: Channel<FixtureProcess>, saved: MutableList<String> = mutableListOf()): PiAiNodeWorker = PiAiNodeWorker(
    launchProcess = { processes.receive().managed },
    providerConfiguration = { JSONObject().put("revision", 0).put("providers", JSONArray()).put("credentials", JSONObject()) },
    saveCredential = { id, _, revision -> saved += id; revision + 1 },
)

private fun nativeMessage(content: JSONArray = JSONArray().put(JSONObject().put("type", "text").put("text", "answer")),
    stop: String = "stop"): JSONObject = JSONObject().put("role", "assistant").put("provider", "fixture")
    .put("api", "openai-completions").put("model", "fixture-model").put("content", content).put("stopReason", stop)
    .put("timestamp", 1234).put("usage", JSONObject().put("input", 3).put("output", 2).put("cacheRead", 4)
        .put("cacheWrite", 1).put("totalTokens", 10))

fun main() = runBlocking {
    test("late old reader frames and exit cannot complete or close a new request with the same ID") {
        val processes = Channel<FixtureProcess>(2)
        val old = FixtureProcess(); val next = FixtureProcess()
        processes.send(old); processes.send(next)
        val worker = fixtureWorker(processes)
        try {
            worker.start(File("unused-fixture"))
            val oldCall = async { runCatching { worker.stream("fixture", "model", JSONObject().put("messages", JSONArray()), requestId = "same-id") {} } }
            old.command("stream")
            old.delayedDeath()
            worker.start(File("unused-fixture"))
            val newEvents = mutableListOf<String>()
            val newCall = async { worker.stream("fixture", "model", JSONObject().put("messages", JSONArray()), requestId = "same-id") {
                newEvents += it.getString("type")
            } }
            next.command("stream")
            old.event("same-id", 0, JSONObject().put("type", "text_delta").put("contentIndex", 0).put("delta", "stale"))
            old.releaseExit()
            delay(100)
            check(!newCall.isCompleted && newEvents.isEmpty())
            next.event("same-id", 0, JSONObject().put("type", "text_delta").put("contentIndex", 0).put("delta", "current"))
            next.result("same-id", JSONObject().put("finished", true))
            check(newCall.await().getBoolean("finished") && newEvents == listOf("text_delta"))
            check(oldCall.await().isFailure)
        } finally { worker.stop(); old.shutdown(); next.shutdown() }
    }
    test("old pipe failure only retires its generation while the new worker remains usable") {
        val processes = Channel<FixtureProcess>(2)
        val old = FixtureProcess(); val next = FixtureProcess()
        processes.send(old); processes.send(next)
        val worker = fixtureWorker(processes)
        try {
            worker.start(File("unused")); old.delayedDeath(); worker.start(File("unused"))
            val call = async { worker.catalog() }; val request = next.command("catalog")
            old.stdout.fail(); old.releaseExit(); delay(100)
            check(!call.isCompleted)
            next.result(request.getString("id"), JSONObject().put("generation", "new"))
            check(call.await().getString("generation") == "new")
        } finally { worker.stop(); old.shutdown(); next.shutdown() }
    }
    test("old suspended OAuth prompt cannot reply or cancel to a new process") {
        val processes = Channel<FixtureProcess>(2)
        val old = FixtureProcess(); val next = FixtureProcess()
        processes.send(old); processes.send(next)
        val worker = fixtureWorker(processes)
        val entered = CompletableDeferred<Unit>(); val answer = CompletableDeferred<String>()
        try {
            worker.start(File("unused"))
            val login = async { runCatching { worker.login("fixture", "oauth", onPrompt = { entered.complete(Unit); answer.await() }) } }
            val request = old.command("login")
            old.emit(JSONObject().put("type", "auth_prompt").put("id", request.getString("id"))
                .put("promptId", "old-prompt").put("prompt", JSONObject().put("type", "text").put("message", "fixture")))
            entered.await(); old.delayedDeath(); worker.start(File("unused")); old.releaseExit()
            answer.complete("old secret")
            check(login.await().isFailure)
            delay(50)
            check(synchronized(next.writes) { next.writes.none { it.optString("type") in setOf("auth_reply", "cancel") } })
        } finally { worker.stop(); old.shutdown(); next.shutdown() }
    }
    test("OAuth callback cancels a suspended manual prompt and login consumes its success") {
        val processes = Channel<FixtureProcess>(1)
        val process = FixtureProcess()
        processes.send(process)
        val worker = fixtureWorker(processes)
        val entered = CompletableDeferred<Unit>()
        val promptClosed = CompletableDeferred<Unit>()
        try {
            worker.start(File("unused"))
            val login = async { worker.login("fixture", "oauth", onPrompt = {
                entered.complete(Unit)
                try { CompletableDeferred<String>().await() }
                finally { promptClosed.complete(Unit) }
            }) }
            val request = process.command("login")
            val id = request.getString("id")
            process.emit(JSONObject().put("type", "auth_prompt").put("id", id)
                .put("promptId", "manual-code").put("prompt", JSONObject().put("type", "text").put("message", "fixture")))
            entered.await()
            process.emit(JSONObject().put("type", "auth_prompt_cancel").put("id", id).put("promptId", "manual-code"))
            process.result(id, JSONObject().put("authenticated", true))
            check(login.await().getBoolean("authenticated"))
            promptClosed.await()
            check(synchronized(process.writes) { process.writes.none { it.optString("type") in setOf("auth_reply", "cancel") } })
        } finally { worker.stop(); process.shutdown() }
    }
    test("OAuth manual response is written while terminal result closes any outstanding prompt") {
        val processes = Channel<FixtureProcess>(1)
        val process = FixtureProcess()
        processes.send(process)
        val worker = fixtureWorker(processes)
        try {
            worker.start(File("unused"))
            val login = async { worker.login("fixture", "oauth", onPrompt = { "OFFLINE_FIXTURE_CODE" }) }
            val request = process.command("login")
            process.emit(JSONObject().put("type", "auth_prompt").put("id", request.getString("id"))
                .put("promptId", "manual-response").put("prompt", JSONObject().put("type", "text").put("message", "fixture")))
            val reply = process.command("auth_reply")
            check(reply.getString("promptId") == "manual-response" && reply.getString("value") == "OFFLINE_FIXTURE_CODE")
            process.result(request.getString("id"), JSONObject().put("authenticated", true))
            check(login.await().getBoolean("authenticated"))
        } finally { worker.stop(); process.shutdown() }
    }
    test("old credential receipt never writes the current vault or acknowledges to the new worker") {
        val processes = Channel<FixtureProcess>(2)
        val old = FixtureProcess(); val next = FixtureProcess(); val saved = mutableListOf<String>()
        processes.send(old); processes.send(next)
        val worker = fixtureWorker(processes, saved)
        try {
            worker.start(File("unused")); old.delayedDeath(); worker.start(File("unused"))
            old.emit(JSONObject().put("type", "credential_write").put("receiptId", "late-old")
                .put("provider", "fixture").put("credential", JSONObject().put("type", "api_key").put("key", "FIXTURE")))
            old.releaseExit(); delay(100)
            check(saved.isEmpty())
            check(synchronized(next.writes) { next.writes.none { it.optString("type") == "credential_ack" } })
        } finally { worker.stop(); old.shutdown(); next.shutdown() }
    }
    test("worker restart reconfigures independently despite unchanged provider revision") {
        val processes = Channel<FixtureProcess>(2)
        val old = FixtureProcess(); val next = FixtureProcess()
        processes.send(old); processes.send(next)
        val worker = fixtureWorker(processes)
        try {
            worker.start(File("unused")); worker.configure(); old.command("configure")
            old.delayedDeath(); worker.start(File("unused")); worker.configure(); next.command("configure")
            check(synchronized(next.writes) { next.writes.count { it.getString("type") == "configure" } == 1 })
        } finally { worker.stop(); old.shutdown(); next.shutdown() }
    }
    test("native text thinking tools and cache usage produce contiguous real reducer events") {
        val adapter = PiAiStreamAdapter("native-round", sequenceStart = 5)
        val reducer = AssistantStreamReducer(5)
        val emitted = mutableListOf<AssistantStreamEvent>()
        fun accept(event: JSONObject) { val output = adapter.accept(event); emitted += output; output.forEach { reducer.apply(it) } }
        accept(JSONObject().put("type", "start").put("partial", JSONObject().put("provider", "fixture")))
        accept(JSONObject().put("type", "text_delta").put("contentIndex", 0).put("delta", "draft"))
        accept(JSONObject().put("type", "thinking_delta").put("contentIndex", 1).put("delta", "public thought"))
        val call = JSONObject().put("type", "toolCall").put("id", "call-1").put("name", "host_only").put("arguments", JSONObject().put("a", 1))
        accept(JSONObject().put("type", "toolcall_end").put("contentIndex", 2).put("toolCall", call))
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", "authoritative"))
            .put(JSONObject().put("type", "thinking").put("thinking", "public thought").put("thinkingSignature", "SECRET_SIGNATURE"))
            .put(call)
        accept(JSONObject().put("type", "done").put("reason", "toolUse").put("message", nativeMessage(content, "toolUse")))
        check(emitted.map { it.sequenceNumber } == List(emitted.size) { it.toLong() + 5 })
        val snapshot = reducer.snapshot()
        check(snapshot.messages.single().status == AssistantMessageStatus.COMPLETED)
        check(snapshot.messages.single().blocks.filterIsInstance<AssistantContentBlockSnapshot.Text>().single().text == "authoritative")
        check(snapshot.toolCalls.single().status == AssistantStreamEvent.ToolStatus.READY)
        check(snapshot.toolCalls.single().approvalRequirement == AssistantStreamEvent.ApprovalRequirement.POLICY_DEPENDENT)
        check(snapshot.usage?.inputTokens == 8L && snapshot.usage?.cachedInputTokens == 4L)
        check(!snapshot.toString().contains("SECRET_SIGNATURE"))
        val turn = adapter.turn(snapshot)
        check(turn.toolCalls().single().arguments().getInt("a") == 1)
        check(turn.assistantHistory()?.toString()?.contains("SECRET_SIGNATURE") == true)
    }
    test("thinking display disabled and redacted thinking never render signature content") {
        val hidden = PiAiStreamAdapter("hidden", allowPublicThinking = false)
        val hiddenEvents = hidden.accept(JSONObject().put("type", "thinking_delta").put("delta", "public text"))
        check(hiddenEvents.none { it is AssistantStreamEvent.UserVisibleThinkingDelta })
        val redacted = PiAiStreamAdapter("redacted")
        val output = redacted.accept(JSONObject().put("type", "done").put("message", nativeMessage(JSONArray()
            .put(JSONObject().put("type", "thinking").put("thinking", "HIDDEN_PAYLOAD")
                .put("redacted", true).put("thinkingSignature", "SIGNATURE")))))
        check(output.none { it is AssistantStreamEvent.UserVisibleThinkingDelta || it is AssistantStreamEvent.ContentBlockDone })
        check(!output.toString().contains("HIDDEN_PAYLOAD") && !output.toString().contains("SIGNATURE"))
    }
    test("transcript conversion removes diagnostics and engine switching cannot replay old tools") {
        val raw = nativeMessage().put("diagnostics", JSONArray().put("SECRET_PROVIDER_ERROR")).put("errorMessage", "SECRET_ERROR")
        val normalized = PiAiTranscriptAdapter.assistant(raw)
        check(!normalized.has("diagnostics") && !normalized.has("errorMessage"))
        val context = PiAiTranscriptAdapter.compatibleContext(listOf(ChatMessage(1, "user", "question"), ChatMessage(2, "assistant", "answer")),
            "fixture", "fixture-model", "openai-completions", "system")
        check(context.getJSONArray("messages").length() == 2)
        check(context.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("type") == "text")
        check(!context.toString().contains("toolCall"))
    }
    test("native tool bridge freezes arguments and requires caller supplied truthful error state") {
        val call = PiAiToolCall("call", "host_only", "{\"x\":1}")
        val arguments = call.arguments(); arguments.put("x", 2)
        check(call.arguments().getInt("x") == 1)
        val result = PiAiTranscriptAdapter.toolResult(call, "permission denied", true)
        check(result.getBoolean("isError") && result.getString("toolCallId") == "call")
        check(result.getJSONArray("content").getJSONObject(0).getString("text") == "permission denied")
    }
    test("native malformed tools duplicate IDs and unsupported APIs fail before dispatch") {
        val content = JSONArray().put(JSONObject().put("type", "toolCall").put("id", "duplicate").put("name", "tool").put("arguments", JSONObject()))
            .put(JSONObject().put("type", "toolCall").put("id", "duplicate").put("name", "tool").put("arguments", JSONObject()))
        check(runCatching { PiAiTranscriptAdapter.assistant(nativeMessage(content)) }.isFailure)
        check(runCatching { PiAiTranscriptAdapter.assistant(nativeMessage().put("api", "invented")) }.isFailure)
    }
    println("Node generation/native adapters: $passed PASS. Production Kotlin host + controlled Process pipes and real reducer; no Android launch or paid API.")
}
