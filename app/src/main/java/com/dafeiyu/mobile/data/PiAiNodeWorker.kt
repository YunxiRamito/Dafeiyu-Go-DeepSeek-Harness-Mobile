package com.dafeiyu.mobile.data

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** A single controlled Node process multiplexes up to four requests. Tool calls are returned as data. */
class PiAiNodeWorker internal constructor(
    private val launchProcess: suspend (File) -> LinuxManagedProcess,
    private val providerConfiguration: suspend () -> JSONObject,
    private val saveCredential: suspend (String, JSONObject?, Long) -> Long,
) {
    constructor(
        context: Context,
        providers: ModelProviderStore,
        runtime: LinuxRuntimeManager = LinuxRuntimeManager(context),
    ) : this(
        launchProcess = { directory -> launchAndroid(context.applicationContext, runtime, directory) },
        providerConfiguration = { withContext(Dispatchers.IO) { providers.workerConfiguration() } },
        saveCredential = { provider, credential, revision ->
            withContext(Dispatchers.IO) { providers.persistWorkerCredential(provider, credential, revision) }
        },
    )

    /** All mutable protocol state belongs to one exact process lifetime, including request IDs. */
    private class Generation(val process: LinuxManagedProcess) {
        val ready = CompletableDeferred<Unit>()
        val pending = ConcurrentHashMap<String, Channel<JSONObject>>()
        val writes = Mutex()
        val credentials = Mutex()
        val configuration = Mutex()
        @Volatile var revision = -1L
        @Volatile var configuredRevision = -1L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycle = Mutex()
    private val current = AtomicReference<Generation?>()

    suspend fun start(componentDirectory: File) = lifecycle.withLock {
        val previous = current.get()
        if (previous?.process?.isAlive == true) return@withLock
        if (previous != null) retire(previous, IOException("模型运行器已退出"))
        val launched = launchProcess(componentDirectory)
        val generation = Generation(launched)
        current.set(generation)
        scope.launch { read(generation) }
        // Never persist provider stderr: SDK errors may echo authentication material.
        scope.launch { runCatching { launched.errors.use { errors -> val buffer = ByteArray(4096); while (errors.read(buffer) >= 0) buffer.fill(0) } } }
        scope.launch {
            val exit = launched.awaitExit()
            retire(generation, IOException("模型运行进程退出（$exit）；请重试或修复组件"))
        }
        try { withTimeout(15_000) { generation.ready.await() }; ensureCurrent(generation) }
        catch (failure: Throwable) {
            withContext(NonCancellable) { runCatching { launched.stop() } }
            retire(generation, failure)
            throw failure
        }
    }

    suspend fun configure() { configuredGeneration() }

    private suspend fun configuredGeneration(): Generation {
        val generation = current.get() ?: throw IOException("模型运行组件未启动")
        generation.configuration.withLock {
            ensureCurrent(generation)
            val configuration = providerConfiguration()
            ensureCurrent(generation)
            val revision = configuration.getLong("revision")
            if (generation.configuredRevision != revision) {
                generation.revision = revision
                call(generation, "configure", configuration) {}
                ensureCurrent(generation)
                generation.configuredRevision = revision
            }
        }
        return generation
    }

    suspend fun catalog(allBuiltins: Boolean = false): JSONObject {
        val generation = configuredGeneration()
        return call(generation, "catalog", JSONObject().put("allBuiltins", allBuiltins)) {}
    }

    suspend fun stream(
        providerId: String,
        modelId: String,
        context: JSONObject,
        reasoning: String? = null,
        requestId: String = UUID.randomUUID().toString(),
        onEvent: suspend (JSONObject) -> Unit,
    ): JSONObject {
        val generation = configuredGeneration()
        return call(generation, "stream", JSONObject().put("provider", providerId).put("model", modelId).put("context", context)
            .also { if (reasoning != null) it.put("reasoning", reasoning) }, requestId, onEvent)
    }

    suspend fun login(
        providerId: String,
        authType: String,
        onPrompt: suspend (JSONObject) -> String,
        onEvent: suspend (JSONObject) -> Unit = {},
    ): JSONObject = coroutineScope {
        val generation = configuredGeneration()
        val prompts = ConcurrentHashMap<String, Job>()
        try {
            call(generation, "login", JSONObject().put("provider", providerId).put("authType", authType)) { frame ->
                when (frame.optString("type")) {
                    "auth_prompt" -> {
                        val promptId = frame.getString("promptId")
                        require(promptId.length <= 120 && prompts.size < 8)
                        val job = launch(start = CoroutineStart.LAZY) {
                            val response = onPrompt(frame.getJSONObject("prompt"))
                            write(generation, JSONObject().put("type", "auth_reply").put("id", UUID.randomUUID().toString())
                                .put("promptId", promptId).put("value", response))
                        }
                        check(prompts.putIfAbsent(promptId, job) == null) { "认证提示 ID 重复" }
                        job.invokeOnCompletion { prompts.remove(promptId, job) }
                        job.start()
                    }
                    "auth_prompt_cancel" -> prompts.remove(frame.getString("promptId"))?.cancelAndJoin()
                    "auth_event" -> onEvent(frame.getJSONObject("event"))
                }
            }
        } finally {
            withContext(NonCancellable) { prompts.values.toList().forEach { it.cancelAndJoin() } }
        }
    }

    suspend fun logout(providerId: String): JSONObject {
        val generation = configuredGeneration()
        return call(generation, "logout", JSONObject().put("provider", providerId)) {}
    }

    suspend fun cancel(requestId: String) {
        val generation = current.get() ?: return
        if (generation.pending.containsKey(requestId)) cancel(generation, requestId)
    }

    private suspend fun cancel(generation: Generation, requestId: String) = write(generation,
        JSONObject().put("type", "cancel").put("id", UUID.randomUUID().toString()).put("target", requestId))

    suspend fun stop() = lifecycle.withLock {
        val generation = current.get() ?: return@withLock
        withContext(NonCancellable + Dispatchers.IO) { generation.process.stop() }
        retire(generation, CancellationException("模型运行器已停止"))
    }

    private suspend fun call(
        generation: Generation,
        type: String,
        arguments: JSONObject,
        onEvent: suspend (JSONObject) -> Unit,
    ): JSONObject = call(generation, type, arguments, UUID.randomUUID().toString(), onEvent)

    private suspend fun call(generation: Generation, type: String, arguments: JSONObject, id: String, onEvent: suspend (JSONObject) -> Unit): JSONObject {
        require(id.isNotBlank() && id.length <= 120)
        ensureCurrent(generation)
        check(generation.pending.size < 4)
        val frames = Channel<JSONObject>(64)
        check(generation.pending.putIfAbsent(id, frames) == null) { "模型请求 ID 重复" }
        var terminal = false
        try {
            write(generation, JSONObject(arguments.toString()).put("type", type).put("id", id))
            return withTimeout(if (type == "login") 600_000L else 720_000L) {
                var expected = 0L
                for (frame in frames) {
                    when (frame.getString("type")) {
                        "result" -> { terminal = true; return@withTimeout frame.optJSONObject("value") ?: JSONObject() }
                        "error" -> {
                            terminal = true
                            throw IOException("${frame.optJSONObject("error")?.optString("code", "PROVIDER_FAILED")}: 模型请求失败，请检查提供商配置或认证")
                        }
                        "event" -> {
                            require(frame.getLong("sequence") == expected++) { "模型流事件顺序无效" }
                            onEvent(frame.getJSONObject("event"))
                        }
                        "auth_prompt", "auth_prompt_cancel", "auth_event" -> onEvent(frame)
                    }
                }
                throw IOException("模型运行器中断，响应未完成")
            }
        } finally {
            generation.pending.remove(id, frames)
            frames.close()
            if (!terminal && current.get() === generation && generation.process.isAlive) withContext(NonCancellable) {
                runCatching { withTimeout(1000) { cancel(generation, id) } }
            }
        }
    }

    private suspend fun write(generation: Generation, frame: JSONObject) = generation.writes.withLock {
        ensureCurrent(generation)
        val active = generation.process
        frame.put("protocol", PROTOCOL)
        val bytes = (frame.toString() + "\n").toByteArray()
        require(bytes.size <= MAX_FRAME)
        val writeTask = scope.async(Dispatchers.IO) {
            try { active.input.write(bytes); active.input.flush() }
            finally { bytes.fill(0) }
        }
        try { withTimeout(10_000) { writeTask.await() } }
        catch (failure: Throwable) {
            withContext(NonCancellable) { runCatching { active.stop() } }
            writeTask.cancel()
            retire(generation, failure)
            throw failure
        }
    }

    private suspend fun read(generation: Generation) {
        val active = generation.process
        try {
            val input = active.output.buffered()
            while (active.isAlive || input.available() > 0) {
                val bytes = ByteArrayOutputStream()
                while (true) {
                    val value = input.read()
                    if (value < 0) { if (bytes.size() == 0) return else throw IOException("模型消息未完成") }
                    if (value == '\n'.code) break
                    require(bytes.size() < MAX_FRAME) { "模型消息超过上限" }
                    bytes.write(value)
                }
                val frame = JSONObject(bytes.toString("UTF-8"))
                require(frame.getString("protocol") == PROTOCOL)
                when (frame.getString("type")) {
                    "ready" -> {
                        require(frame.getString("version") == "0.87.1")
                        generation.ready.complete(Unit)
                    }
                    "fatal" -> throw IOException("模型运行组件无法载入或协议无效")
                    "credential_write" -> persistCredential(generation, frame)
                    else -> frame.optString("id").takeIf { it.isNotBlank() }?.let { id ->
                        val target = generation.pending[id]
                        if (target != null && target.trySend(frame).isFailure) throw IOException("模型事件过多，已停止运行器以保护内存")
                    }
                }
            }
        } catch (failure: Throwable) {
            retire(generation, failure)
            withContext(NonCancellable) { runCatching { active.stop() } }
        }
    }

    private suspend fun persistCredential(generation: Generation, frame: JSONObject) = lifecycle.withLock {
        // Serialize a durable write with process replacement. A stale receipt never
        // writes current credentials or acknowledges to a different process.
        if (current.get() !== generation) return@withLock
        generation.credentials.withLock {
        val success = runCatching {
            val credential = if (frame.isNull("credential")) null else frame.getJSONObject("credential")
            generation.revision = saveCredential(frame.getString("provider"), credential, generation.revision)
            generation.configuredRevision = generation.revision
        }.isSuccess
        write(generation, JSONObject().put("type", "credential_ack").put("id", UUID.randomUUID().toString())
            .put("receiptId", frame.getString("receiptId")).put("ok", success))
        }
    }

    private fun ensureCurrent(generation: Generation) {
        if (current.get() !== generation || !generation.process.isAlive) throw IOException("模型运行器已退出或切换到新进程")
    }

    private fun retire(generation: Generation, failure: Throwable) {
        current.compareAndSet(generation, null)
        generation.ready.completeExceptionally(failure)
        generation.pending.values.forEach { it.close(failure) }
    }

    companion object {
        const val PROTOCOL = "dafeiyu-go.model-worker.v1"
        private const val MAX_FRAME = 4 * 1024 * 1024

        internal suspend fun launchAndroid(app: Context, runtime: LinuxRuntimeManager, componentDirectory: File): LinuxManagedProcess {
            require(componentDirectory.isDirectory && !LinuxArchiveExtractor.isSymlink(componentDirectory)) { "模型运行组件尚未安装" }
            val privateRoot = File(app.filesDir, "model-runtime")
            require(componentDirectory.canonicalPath.startsWith(privateRoot.canonicalPath + File.separator)) { "模型运行组件路径无效" }
            val workerRoot = File(app.noBackupFilesDir, "model-worker").apply { require(isDirectory || mkdirs()) }
            require(!LinuxArchiveExtractor.isSymlink(workerRoot))
            val script = File(workerRoot, "worker.mjs")
            require(!LinuxArchiveExtractor.isSymlink(script))
            app.assets.open("model-worker/worker.mjs").use { source -> script.outputStream().use { output -> source.copyTo(output) } }
            return runtime.startManagedProcess("model-worker", listOf("/usr/local/bin/node", "/dafeiyu-model-host/worker.mjs", "/dafeiyu-model-component"),
                mapOf("/dafeiyu-model-host" to workerRoot, "/dafeiyu-model-component" to componentDirectory), workerRoot)
        }
    }
}
