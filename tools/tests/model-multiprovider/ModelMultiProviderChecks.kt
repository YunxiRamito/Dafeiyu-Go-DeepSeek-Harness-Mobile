package com.dafeiyu.mobile.data

import com.dafeiyu.mobile.ui.providerCredential
import com.dafeiyu.mobile.ui.providerEditedConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private var passed = 0
private fun checkCase(name: String, block: () -> Unit) {
    block()
    println("PASS $name")
    passed++
}

private fun completedTurn(native: JSONObject, calls: List<AssistantToolCallSnapshot> = emptyList()): PiAiChatTurn {
    val message = AssistantMessageSnapshot("fixture-message", "assistant", AssistantMessageStatus.COMPLETED,
        listOf(AssistantContentBlockSnapshot.Text("text", "fixture", true)), finishReason = "stop")
    val snapshot = AssistantStreamSnapshot(listOf(message), calls, emptyList(), emptyList(), emptyList(), null,
        null, null, emptyList(), emptyList(), true)
    return PiAiChatTurn(snapshot, native.toString())
}

private fun assistantNative(callId: String? = null): JSONObject {
    val content = JSONArray().put(JSONObject().put("type", "text").put("text", "fixture"))
    if (callId != null) content.put(JSONObject().put("type", "toolCall").put("id", callId).put("name", "write_text_file")
        .put("arguments", JSONObject().put("path", "fixture.txt").put("text", "hello")))
    return JSONObject().put("role", "assistant").put("api", "openai-completions").put("provider", "fixture")
        .put("model", "fixture-model").put("content", content).put("stopReason", if (callId == null) "stop" else "toolUse")
}

private fun catalog(): JSONObject = JSONObject().put("version", "0.87.1").put("apis", JSONArray(ModelProviderConfig.APIS.toList()))
    .put("providers", JSONArray().put(JSONObject().put("id", "fixture").put("name", "Fixture").put("configured", true)
        .put("authMethods", JSONArray().put("api_key").put("oauth"))
        .put("models", JSONArray().put(JSONObject().put("id", "fixture-model").put("name", "Fixture model")
            .put("api", "openai-completions").put("reasoningOptions", JSONArray().put("off").put("low").put("high"))
            .put("default_level", "low")))))

private class RuntimeFixture : ModelRuntimeBackend {
    private val recovery = fixtureModelRecoveryAccess()
    override suspend fun <T> withNativeReady(operation: suspend () -> T): T = recovery.withNativeReady(operation)
    override val components = MutableStateFlow(listOf(LinuxComponentState("ubuntu", "Ubuntu", LinuxComponentStatus.READY),
        LinuxComponentState("node", "Node", LinuxComponentStatus.READY)))
    override val processes = MutableStateFlow<List<LinuxManagedProcessInfo>>(emptyList())
    var directory: File? = File(".")
    var snapshot = ModelProviderSnapshot(0, listOf(ModelProviderConfig.DEEPSEEK, ModelProviderConfig("fixture", "Fixture", builtinProvider = "openai")))
    val credentials = mutableSetOf<String>()
    var starts = 0
    var installs = 0
    var cancelledInstalls = 0
    var generated = 0
    var onStart: suspend () -> Unit = {}
    var onInstall: suspend () -> Unit = {}
    var loginReply: String? = null
    var onLoginCleanup: suspend () -> Unit = {}
    override fun installed() = directory
    override fun providers() = snapshot
    override fun hasCredential(providerId: String) = providerId in credentials
    override fun upsert(config: ModelProviderConfig, credential: JSONObject?): ModelProviderSnapshot {
        val values = snapshot.providers.toMutableList()
        val index = values.indexOfFirst { it.id == config.id }
        if (index >= 0) values[index] = config else values.add(config)
        if (credential != null) credentials.add(config.id)
        snapshot = ModelProviderSnapshot(snapshot.revision + 1, values)
        return snapshot
    }
    override fun remove(providerId: String): ModelProviderSnapshot {
        require(providerId != "deepseek")
        credentials.remove(providerId)
        snapshot = ModelProviderSnapshot(snapshot.revision + 1, snapshot.providers.filterNot { it.id == providerId })
        return snapshot
    }
    override fun reorder(order: List<String>): ModelProviderSnapshot {
        require(order.toSet() == snapshot.providers.map { it.id }.toSet())
        snapshot = ModelProviderSnapshot(snapshot.revision + 1, order.map { id -> snapshot.providers.first { it.id == id } })
        return snapshot
    }
    override suspend fun refreshComponents() = Unit
    override suspend fun installLinux(id: String, source: String) { error("fixture components are ready") }
    override fun cancelLinuxInstall() { cancelledInstalls++ }
    override suspend fun installPackages(source: String, progress: (ModelRuntimeProgress) -> Unit): File {
        installs++
        onInstall()
        directory = File(".")
        return directory!!
    }
    override suspend fun start(directory: File) {
        starts++
        onStart()
        processes.value = listOf(LinuxManagedProcessInfo("model-worker", 123, "fixture", true))
    }
    override suspend fun configure() = Unit
    override suspend fun catalog(allBuiltins: Boolean) = catalog()
    override suspend fun generate(request: PiAiChatRequest, onEvent: suspend (AssistantStreamEvent) -> Unit): PiAiChatTurn {
        generated++
        return completedTurn(assistantNative())
    }
    override suspend fun login(provider: String, authType: String, onPrompt: suspend (JSONObject) -> String,
        onEvent: suspend (JSONObject) -> Unit): JSONObject {
        try {
        onEvent(JSONObject().put("type", "auth_url").put("url", "https://example.test/auth?fixture=1"))
        loginReply = onPrompt(JSONObject().put("type", "secret").put("message", "Fixture secret"))
        credentials.add(provider)
        return JSONObject().put("authenticated", true)
        } finally { onLoginCleanup() }
    }
    override suspend fun logout(provider: String) { credentials.remove(provider) }
    override suspend fun stop() { processes.value = emptyList() }
}

private fun fixtureController(backend: RuntimeFixture): Pair<ModelRuntimeController, CoroutineScope> {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    return ModelRuntimeController(backend, scope) to scope
}

fun main() {
    val imported = ModelProviderConfig("dsh-pi-deepseek", "Imported", builtinProvider = "deepseek", api = "openai-completions",
        baseUrl = "https://fixture.invalid/v1", modelsJson = "[{\"id\":\"fixture-model\",\"contextWindow\":123456,\"reasoning\":true}]",
        enabled = false, dshRoute = "deepseek")
    checkCase("name-only edit preserves imported builtin overrides, route and enabled state") {
        val edited = providerEditedConfiguration(imported, imported.id, " Renamed ", true, "deepseek",
            "openai-completions", imported.baseUrl.orEmpty(), imported.modelsJson)
        check(edited == imported.copy(name = "Renamed"))
        check(!edited.protected && edited.dshRoute == "deepseek")
    }
    checkCase("hidden catalog fields and route are not cleared by unchanged catalog choice") {
        val edited = providerEditedConfiguration(imported, imported.id, imported.name, true, "deepseek",
            "openai-responses", "", "[]")
        check(edited == imported)
    }
    checkCase("explicit custom fields change without losing imported route or enabled flag") {
        val edited = providerEditedConfiguration(imported, imported.id, "Custom", false, "",
            "openai-responses", "https://changed.invalid/v1", "[{\"id\":\"changed\"}]")
        check(edited == imported.copy(name = "Custom", builtinProvider = null, api = "openai-responses",
            baseUrl = "https://changed.invalid/v1", modelsJson = "[{\"id\":\"changed\"}]"))
    }
    checkCase("unchanged authentication remains null and cannot replace existing OAuth or cloud credentials") {
        check(providerCredential("", " ", "", "", "") == null)
        val cloud = JSONObject().put("type", "api_key").put("env", JSONObject().put("AWS_REGION", "fixture"))
        val edited = providerEditedConfiguration(imported, imported.id, "Renamed", true, "deepseek", "", "", "")
        val saved = JSONObject().put(imported.id, cloud)
        providerCredential("", "", "", "", "")?.let { saved.put(edited.id, it) }
        check(saved.getJSONObject(imported.id).getJSONObject("env").getString("AWS_REGION") == "fixture")
    }
    checkCase("new builtin has no guessed overrides and edit cannot change provider identity") {
        val fresh = providerEditedConfiguration(null, " new-provider ", " New ", true, "openai", "openai-responses", "", "invalid")
        check(fresh == ModelProviderConfig("new-provider", "New", builtinProvider = "openai"))
        check(runCatching { providerEditedConfiguration(imported, "other-id", "Changed", true, "deepseek", "", "", "") }.isFailure)
    }
    checkCase("catalog preserves protocol, levels and default") {
        val provider = ModelRuntimeController.parseCatalog(catalog()).single()
        val model = provider.models.single()
        check(model.api == "openai-completions")
        check(model.reasoningOptions == listOf("off", "low", "high"))
        check(model.defaultReasoning == "low")
        check(provider.authMethods == listOf("api_key", "oauth"))
    }
    checkCase("catalog rejects unknown protocol and duplicate model IDs") {
        val unknownCatalog = JSONObject(catalog().toString())
        unknownCatalog.getJSONArray("providers").getJSONObject(0).getJSONArray("models").getJSONObject(0)
            .put("api", "unknown-protocol")
        check(runCatching { ModelRuntimeController.parseCatalog(unknownCatalog) }.isFailure)
        val duplicateCatalog = JSONObject(catalog().toString())
        val models = duplicateCatalog.getJSONArray("providers").getJSONObject(0).getJSONArray("models")
        models.put(JSONObject(models.getJSONObject(0).toString()))
        check(runCatching { ModelRuntimeController.parseCatalog(duplicateCatalog) }.isFailure)
    }
    checkCase("native IDs map to safe dispatcher IDs without collisions in normal session space") {
        val a = OfficialMultiProviderSession.dispatcherCallId("session-a", "provider:call/1")
        val b = OfficialMultiProviderSession.dispatcherCallId("session-a", "provider:call/2")
        val c = OfficialMultiProviderSession.dispatcherCallId("session-b", "provider:call/1")
        check(a.matches(Regex("pi_[0-9a-f]{64}")) && a != b && a != c)
    }
    checkCase("tool loop executes current calls and never replays imported history") {
        val context = JSONObject().put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "do it")))
        val calls = mutableListOf<String>()
        val outputs = mutableListOf<String>()
        val events = mutableListOf<AssistantStreamEvent>()
        var turns = 0
        val result = runBlocking {
            OfficialMultiProviderSession.generate(
                PiAiChatRequest("fixture", "fixture-model", context), emptyList(),
                generateTurn = { _, _ ->
                    turns++
                    if (turns == 1) completedTurn(assistantNative("native:call"), listOf(
                        AssistantToolCallSnapshot("native:call", "write_text_file", AssistantStreamEvent.ToolKind.TOOL,
                            "{\"path\":\"fixture.txt\",\"text\":\"hello\"}", AssistantStreamEvent.ToolStatus.READY,
                            AssistantStreamEvent.ApprovalRequirement.NONE)))
                    else completedTurn(assistantNative())
                },
                executeTool = { call -> calls += call.id; OfficialToolResult(call.id, call.name, "{\"ok\":true}") },
                awaitSubagent = { it },
                onOutput = { outputs += it.callId },
                onEvent = { events += it },
            )
        }
        check(turns == 2 && calls.size == 1 && calls.single().startsWith("pi_"))
        check(outputs == listOf("native:call"))
        check(result.completed && result.rounds == 2)
        check(events.filterIsInstance<AssistantStreamEvent.ToolResult>().map { it.callId } == listOf("native:call"))
        val historical = JSONObject().put("messages", JSONArray().put(JSONObject().put("role", "assistant").put("content", JSONArray()
            .put(JSONObject().put("type", "toolCall").put("id", "old-call").put("name", "write_text_file")
                .put("arguments", JSONObject().put("path", "old.txt").put("text", "old"))))))
        val rejected = runCatching { runBlocking {
            OfficialMultiProviderSession.generate(PiAiChatRequest("fixture", "fixture-model", historical), emptyList(),
                generateTurn = { _, _ -> completedTurn(assistantNative("old-call"), listOf(
                    AssistantToolCallSnapshot("old-call", "write_text_file", AssistantStreamEvent.ToolKind.TOOL, "{}",
                        AssistantStreamEvent.ToolStatus.READY, AssistantStreamEvent.ApprovalRequirement.NONE))) },
                executeTool = { error("historical call must not execute") }, awaitSubagent = { it }, onOutput = {}, onEvent = {})
        } }.getOrNull()
        check(rejected?.completed == false)
    }
    checkCase("tool result is written before the next model turn") {
        val order = mutableListOf<String>()
        var turn = 0
        runBlocking {
            OfficialMultiProviderSession.generate(PiAiChatRequest("fixture", "fixture-model", JSONObject().put("messages", JSONArray())), emptyList(),
                generateTurn = { _, _ ->
                    turn++
                    check(order == if (turn == 1) emptyList() else listOf("output", "event"))
                    completedTurn(if (turn == 1) assistantNative("call") else assistantNative(), if (turn == 1) listOf(
                        AssistantToolCallSnapshot("call", "write_text_file", AssistantStreamEvent.ToolKind.TOOL, "{}",
                            AssistantStreamEvent.ToolStatus.READY, AssistantStreamEvent.ApprovalRequirement.NONE)) else emptyList())
                },
                executeTool = { OfficialToolResult(it.id, it.name, "{\"ok\":true}") }, awaitSubagent = { it },
                onOutput = { order += "output" }, onEvent = { if (it is AssistantStreamEvent.ToolResult) order += "event" })
        }
        check(order == listOf("output", "event"))
    }
    checkCase("cancellation escapes and does not publish a success transcript") {
        val result = runCatching { runBlocking {
            OfficialMultiProviderSession.generate(PiAiChatRequest("fixture", "fixture-model", JSONObject().put("messages", JSONArray())), emptyList(),
                generateTurn = { _, _ -> throw CancellationException("fixture cancellation") }, executeTool = { error("no tool") },
                awaitSubagent = { it }, onOutput = {}, onEvent = {})
        } }
        check(result.exceptionOrNull() is CancellationException)
    }
    checkCase("usage snapshots add once per round and preserve contiguous sequence numbers") {
        val received = mutableListOf<AssistantStreamEvent>()
        var round = 0
        runBlocking {
            OfficialMultiProviderSession.generate(PiAiChatRequest("fixture", "fixture-model", JSONObject().put("messages", JSONArray()),
                sequenceStart = 17), emptyList(), generateTurn = { request, event ->
                var next = request.sequenceStart
                event(AssistantStreamEvent.MessageStarted(next++, request.requestId))
                event(AssistantStreamEvent.UsageReported(next++, AssistantUsage(inputTokens = 10, outputTokens = 2, totalTokens = 12)))
                event(AssistantStreamEvent.UsageReported(next++, AssistantUsage(outputTokens = 4, totalTokens = 14)))
                event(AssistantStreamEvent.MessageCompleted(next, request.requestId))
                completedTurn(if (round++ == 0) assistantNative("usage-call") else assistantNative())
            }, executeTool = { OfficialToolResult(it.id, it.name, "{\"ok\":true}") }, awaitSubagent = { it }, onOutput = {},
                onEvent = { received += it })
        }
        check(round == 2)
        check(received.map { it.sequenceNumber } == List(received.size) { 17L + it })
        val total = received.filterIsInstance<AssistantStreamEvent.UsageReported>().last().usage
        check(total.inputTokens == 20L && total.outputTokens == 8L && total.totalTokens == 28L)
    }
    checkCase("tool batch limit rejects all calls before any side effect") {
        val native = assistantNative()
        val content = native.getJSONArray("content")
        repeat(OfficialToolContract.MAX_BATCH_CALLS + 1) { index -> content.put(JSONObject().put("type", "toolCall")
            .put("id", "limited-$index").put("name", "write_text_file").put("arguments", JSONObject())) }
        var executed = 0
        val events = mutableListOf<AssistantStreamEvent>()
        val result = runBlocking {
            OfficialMultiProviderSession.generate(PiAiChatRequest("fixture", "fixture-model", JSONObject().put("messages", JSONArray())),
                emptyList(), generateTurn = { _, _ -> completedTurn(native) }, executeTool = {
                    executed++; OfficialToolResult(it.id, it.name, "{\"ok\":true}")
                }, awaitSubagent = { it }, onOutput = {}, onEvent = { events += it })
        }
        check(!result.completed && executed == 0)
        check(events.filterIsInstance<AssistantStreamEvent.ToolError>().size == OfficialToolContract.MAX_BATCH_CALLS + 1)
        check(result.transcript().getJSONArray("messages").length() == OfficialToolContract.MAX_BATCH_CALLS + 2)
    }
    checkCase("opaque signatures remain in transcript and are not invented as public thought") {
        val native = assistantNative().also { it.getJSONArray("content").put(JSONObject().put("type", "thinking")
            .put("thinking", "").put("redacted", true).put("thinkingSignature", "opaque-fixture")) }
        val events = mutableListOf<AssistantStreamEvent>()
        val result = runBlocking {
            OfficialMultiProviderSession.generate(PiAiChatRequest("fixture", "fixture-model", JSONObject().put("messages", JSONArray()),
                allowPublicThinking = false), emptyList(), generateTurn = { _, _ -> completedTurn(native) },
                executeTool = { error("no tools") }, awaitSubagent = { it }, onOutput = {}, onEvent = { events += it })
        }
        check(result.transcriptJson.contains("opaque-fixture"))
        check(events.none { it is AssistantStreamEvent.UserVisibleThinkingDelta })
    }
    checkCase("auth prompt shape is bounded and links remain HTTPS") {
        val prompt = ModelRuntimeController.parsePrompt(JSONObject().put("type", "select").put("message", "Choose")
            .put("options", JSONArray().put(JSONObject().put("id", "one").put("label", "One"))))
        check(prompt.type == "select" && prompt.options.single().id == "one")
        val notice = ModelRuntimeController.parseNotice(JSONObject().put("type", "auth_url").put("url", "https://example.test/auth")
            .put("instructions", "Open the page"))
        check(notice.links.single().url.startsWith("https://"))
        check(runCatching { ModelRuntimeController.parseNotice(JSONObject().put("type", "auth_url").put("url", "http://bad.test")) }.isFailure)
    }
    checkCase("missing component gives install guidance without implicit installation") {
        val backend = RuntimeFixture().apply { directory = null }
        val (runtime, scope) = fixtureController(backend)
        try {
            val failure = runCatching { runBlocking { runtime.ensureStarted() } }.exceptionOrNull()
            check(failure is ModelRuntimeException && failure.code == "COMPONENT_REQUIRED")
            check(backend.starts == 0 && backend.installs == 0)
        } finally { scope.cancel() }
    }
    checkCase("one task lease blocks edits logout replacement and normal shutdown") {
        val backend = RuntimeFixture()
        val (runtime, scope) = fixtureController(backend)
        try { runBlocking {
            runtime.withSession("fixture") {
                check(runtime.state.value.activeSessions == 1)
                check(runCatching { runtime.remove("fixture") }.isFailure)
                check(runCatching { runtime.reorder(listOf("fixture", "deepseek")) }.isFailure)
                check(runCatching { runtime.logout("fixture") }.isFailure)
                check(runCatching { runtime.install() }.isFailure)
                check(runCatching { runtime.shutdown() }.isFailure)
                check(runCatching { runtime.shutdown(stopActive = true) }.isFailure)
                runtime.generate(PiAiChatRequest("fixture", "fixture-model", JSONObject().put("messages", JSONArray()), reasoning = "high")) {}
                check(runtime.state.value.activeSessions == 1 && backend.generated == 1)
                val invalid = runCatching { runtime.generate(PiAiChatRequest("fixture", "fixture-model",
                    JSONObject().put("messages", JSONArray()), reasoning = "future-tier")) {} }.exceptionOrNull()
                check(invalid is ModelRuntimeException && invalid.code == "UNSUPPORTED_REASONING")
                check(backend.generated == 1)
            }
            check(runtime.state.value.activeSessions == 0)
            runtime.reorder(listOf("fixture", "deepseek"))
            check(runtime.state.value.providers.providers.map { it.id } == listOf("fixture", "deepseek"))
            runtime.shutdown()
        } } finally { scope.cancel() }
    }
    checkCase("auth replies target exact step and cancellation releases management lock") {
        val backend = RuntimeFixture()
        val (runtime, scope) = fixtureController(backend)
        try { runBlocking {
            val task = async { runtime.login("fixture", "oauth") }
            val prompt = withTimeout(2000) { runtime.state.first { it.authPrompt != null }.authPrompt!! }
            check(!runtime.respondToAuthPrompt("previous-step", "fixture-secret"))
            check(runtime.respondToAuthPrompt(prompt.id, "fixture-secret"))
            task.await()
            check(backend.loginReply == "fixture-secret" && "fixture" in runtime.state.value.credentialProviders)
            check(runtime.state.value.authPrompt == null && runtime.state.value.authenticatingProvider == null)
            val cancelled = launch { runtime.login("fixture", "oauth") }
            withTimeout(2000) { runtime.state.first { it.authPrompt != null } }
            runtime.cancelAuthentication()
            cancelled.join()
            check(runtime.state.value.authPrompt == null && !runtime.state.value.busy)
            runtime.remove("fixture")
            check(runtime.state.value.providers.providers.none { it.id == "fixture" })
        } } finally { scope.cancel() }
    }
    checkCase("forced shutdown waits for actual session cleanup without holding its management gate") {
        val backend = RuntimeFixture()
        val (runtime, scope) = fixtureController(backend)
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try { runBlocking {
            val task = launch {
                runtime.withSession("fixture") {
                    try { entered.complete(Unit); awaitCancellation() }
                    finally { withContext(NonCancellable) { cleaning.complete(Unit); release.await() } }
                }
            }
            entered.await()
            val stopped = async { runtime.shutdown(stopActive = true) }
            withTimeout(2000) { cleaning.await() }
            check(!stopped.isCompleted && runtime.state.value.status == ModelRuntimeStatus.STOPPING)
            check(runCatching { runtime.reorder(listOf("fixture", "deepseek")) }.isFailure)
            check(runCatching { runtime.shutdown(stopActive = true) }.isFailure)
            release.complete(Unit)
            withTimeout(2000) { stopped.await(); task.join() }
            check(runtime.state.value.activeSessions == 0 && !runtime.state.value.busy)
        } } finally { release.complete(Unit); scope.cancel() }
    }
    checkCase("forced shutdown waits for actual authentication cleanup before allowing credential edits") {
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backend = RuntimeFixture().apply {
            onLoginCleanup = { withContext(NonCancellable) { cleaning.complete(Unit); release.await() } }
        }
        val (runtime, scope) = fixtureController(backend)
        try { runBlocking {
            val login = launch { runtime.login("fixture", "oauth") }
            withTimeout(2000) { runtime.state.first { it.authPrompt != null } }
            val stopped = async { runtime.shutdown(stopActive = true) }
            withTimeout(2000) { cleaning.await() }
            check(!stopped.isCompleted && runtime.state.value.status == ModelRuntimeStatus.STOPPING)
            check(runCatching { runtime.remove("fixture") }.isFailure)
            release.complete(Unit)
            withTimeout(2000) { stopped.await(); login.join() }
            check(runtime.state.value.authPrompt == null && !runtime.state.value.busy)
            runtime.remove("fixture")
        } } finally { release.complete(Unit); scope.cancel() }
    }
    checkCase("cancelled shutdown still rejects edits and sessions until authentication cleanup completes") {
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backend = RuntimeFixture().apply {
            onLoginCleanup = { withContext(NonCancellable) { cleaning.complete(Unit); release.await() } }
        }
        val (runtime, scope) = fixtureController(backend)
        try { runBlocking {
            val login = launch { runtime.login("fixture", "oauth") }
            withTimeout(2000) { runtime.state.first { it.authPrompt != null } }
            val stopped = launch { runtime.shutdown(stopActive = true) }
            withTimeout(2000) { cleaning.await() }
            stopped.cancel(); stopped.join()
            check(!login.isCompleted)
            check(runCatching { runtime.remove("fixture") }.isFailure)
            check(runCatching { runtime.refresh() }.isFailure)
            check(runCatching { runtime.ensureStarted() }.isFailure)
            var admitted = false
            check(runCatching { runtime.withSession("fixture") { admitted = true } }.isFailure && !admitted)
            release.complete(Unit)
            withTimeout(2000) { login.join() }
            runtime.remove("fixture")
        } } finally { release.complete(Unit); scope.cancel() }
    }
    checkCase("cancelled installation retains its admission barrier through actual cleanup") {
        val installing = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backend = RuntimeFixture().apply {
            directory = null
            onInstall = {
                try { installing.complete(Unit); awaitCancellation() }
                finally { withContext(NonCancellable) { cleaning.complete(Unit); release.await() } }
            }
        }
        val (runtime, scope) = fixtureController(backend)
        try { runBlocking {
            val install = launch { runtime.install() }
            installing.await(); runtime.cancelInstall()
            withTimeout(2000) { cleaning.await() }
            check(!install.isCompleted)
            check(runCatching { runtime.remove("fixture") }.isFailure)
            check(runCatching { runtime.shutdown(stopActive = true) }.isFailure)
            check(runCatching { runtime.refresh() }.isFailure)
            release.complete(Unit)
            withTimeout(2000) { install.join() }
            runtime.remove("fixture")
        } } finally { release.complete(Unit); scope.cancel() }
    }
    checkCase("user-stopped foreground callers remain registered until real cleanup has finished") {
        runBlocking {
            val callers = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()
            val entered = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val caller = launchOfficialCredentialCaller(callers) {
                check(callers.contains(requireNotNull(kotlinx.coroutines.currentCoroutineContext()[Job])))
                try { entered.complete(Unit); awaitCancellation() }
                finally { withContext(NonCancellable) { cleaning.complete(Unit); release.await() } }
            }
            try {
                entered.await(); caller.cancel()
                withTimeout(2000) { cleaning.await() }
                check(!caller.isCompleted && caller in callers)
                val stopped = async {
                    awaitOfficialCredentialCallers(null, callers.toList(), {}, {}, timeoutMillis = 2000)
                }
                kotlinx.coroutines.yield()
                check(!stopped.isCompleted)
                release.complete(Unit)
                stopped.await(); caller.join()
                check(callers.isEmpty())
            } finally { release.complete(Unit); caller.cancel(); caller.join() }
        }
    }
    checkCase("cancelled start returns a retryable status") {
        val started = CompletableDeferred<Unit>()
        val backend = RuntimeFixture().apply { onStart = { started.complete(Unit); awaitCancellation() } }
        val (runtime, scope) = fixtureController(backend)
        try { runBlocking {
            val request = launch { runtime.ensureStarted() }
            started.await()
            request.cancel(); request.join()
            check(runtime.state.value.status != ModelRuntimeStatus.STARTING && !runtime.state.value.busy)
            backend.onStart = {}
            runtime.ensureStarted()
            check(runtime.state.value.status == ModelRuntimeStatus.RUNNING)
        } } finally { scope.cancel() }
    }
    checkCase("installation survives page coroutine leaving and exposes explicit cancellation") {
        val installing = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val backend = RuntimeFixture().apply { directory = null; onInstall = { installing.complete(Unit); finish.await() } }
        val (runtime, scope) = fixtureController(backend)
        try { runBlocking {
            val page = launch { runtime.install() }
            installing.await()
            page.cancel(); page.join()
            check(runtime.state.value.status == ModelRuntimeStatus.INSTALLING)
            finish.complete(Unit)
            withTimeout(2000) { runtime.state.first { it.status == ModelRuntimeStatus.READY } }
            check(backend.installs == 1 && backend.directory != null)
        } } finally { scope.cancel() }
    }
    println("Model multi-provider checks: $passed PASS; offline injected worker/dispatcher only, no paid API or device.")
}
