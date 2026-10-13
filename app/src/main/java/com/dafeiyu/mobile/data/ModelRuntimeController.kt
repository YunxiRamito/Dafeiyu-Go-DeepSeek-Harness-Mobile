package com.dafeiyu.mobile.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class ModelRuntimeStatus { MISSING, READY, INSTALLING, STARTING, RUNNING, STOPPING, ERROR }

data class RuntimeModel(
    val providerId: String,
    val id: String,
    val name: String,
    val api: String,
    val reasoningOptions: List<String>,
    val defaultReasoning: String? = null,
    val input: List<String> = emptyList(),
    val contextWindow: Long? = null,
)

data class RuntimeProvider(
    val id: String,
    val name: String,
    val configured: Boolean,
    val authMethods: List<String>,
    val models: List<RuntimeModel>,
)

data class ModelAuthChoice(val id: String, val label: String, val description: String? = null)
data class ModelAuthPrompt(val id: String, val type: String, val message: String,
    val placeholder: String? = null, val options: List<ModelAuthChoice> = emptyList())
data class ModelAuthLink(val url: String, val label: String)
data class ModelAuthNotice(val message: String, val links: List<ModelAuthLink> = emptyList(), val userCode: String? = null)

/** Contains metadata only. Keys, token responses and credential files never enter this state. */
data class ModelRuntimeState(
    val status: ModelRuntimeStatus = ModelRuntimeStatus.MISSING,
    val detail: String = "其他提供商需要模型运行组件，含 Node.js 与 Linux 基础环境",
    val providers: ModelProviderSnapshot = ModelProviderSnapshot(0, listOf(ModelProviderConfig.DEEPSEEK)),
    val credentialProviders: Set<String> = emptySet(),
    val catalog: List<RuntimeProvider> = emptyList(),
    val builtinCatalog: List<RuntimeProvider> = emptyList(),
    val components: List<LinuxComponentState> = emptyList(),
    val progress: ModelRuntimeProgress? = null,
    val workerRunning: Boolean = false,
    val activeSessions: Int = 0,
    val authenticatingProvider: String? = null,
    val authPrompt: ModelAuthPrompt? = null,
    val authNotices: List<ModelAuthNotice> = emptyList(),
) {
    val busy: Boolean get() = activeSessions > 0 || authenticatingProvider != null ||
        status in setOf(ModelRuntimeStatus.INSTALLING, ModelRuntimeStatus.STARTING, ModelRuntimeStatus.STOPPING)
}

class ModelRuntimeException(val code: String, message: String) : IOException(message)

/** The injected boundary is the actual process, component installer and encrypted vault in production. */
internal interface ModelRuntimeBackend {
    val components: StateFlow<List<LinuxComponentState>>
    val processes: StateFlow<List<LinuxManagedProcessInfo>>
    fun installed(): File?
    fun providers(): ModelProviderSnapshot
    fun hasCredential(providerId: String): Boolean
    fun upsert(config: ModelProviderConfig, credential: JSONObject?): ModelProviderSnapshot
    fun remove(providerId: String): ModelProviderSnapshot
    fun reorder(order: List<String>): ModelProviderSnapshot
    suspend fun refreshComponents()
    suspend fun installLinux(id: String, source: String)
    fun cancelLinuxInstall()
    suspend fun installPackages(source: String, progress: (ModelRuntimeProgress) -> Unit): File
    suspend fun start(directory: File)
    suspend fun configure()
    suspend fun catalog(allBuiltins: Boolean): JSONObject
    suspend fun generate(request: PiAiChatRequest, onEvent: suspend (AssistantStreamEvent) -> Unit): PiAiChatTurn
    suspend fun login(provider: String, authType: String, onPrompt: suspend (JSONObject) -> String,
        onEvent: suspend (JSONObject) -> Unit): JSONObject
    suspend fun logout(provider: String)
    suspend fun stop()
}

/**
 * One application-owned worker. A task lease covers all its model and tool rounds, so a settings
 * edit cannot swap credentials or replace the environment while Android is executing its tools.
 */
class ModelRuntimeController internal constructor(
    private val backend: ModelRuntimeBackend,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val gate = Mutex()
    private val sessions = mutableMapOf<String, Job>()
    @Volatile private var installTask: Deferred<Unit>? = null
    @Volatile private var authTask: Deferred<Unit>? = null
    private var managementBusy = false
    private data class PendingPrompt(val prompt: ModelAuthPrompt, val reply: CompletableDeferred<String>)
    private val pendingPrompt = AtomicReference<PendingPrompt?>()
    private class SessionLease(val owner: ModelRuntimeController, val providerId: String) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<SessionLease>
    }
    private val mutableState = MutableStateFlow(ModelRuntimeState())
    val state: StateFlow<ModelRuntimeState> = mutableState.asStateFlow()

    init {
        scope.launch { backend.components.collect { components -> mutableState.update { it.copy(components = components) } } }
        scope.launch { backend.processes.collect { processes ->
            val running = processes.any { it.id == WORKER_ID && it.running }
            mutableState.update {
                if (!running && it.status == ModelRuntimeStatus.RUNNING) it.copy(status = ModelRuntimeStatus.READY,
                    detail = "模型运行器已退出；下一次请求会重新启动", workerRunning = false)
                else it.copy(workerRunning = running)
            }
        } }
        scope.launch {
            try { refresh() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(status = ModelRuntimeStatus.ERROR,
                detail = "模型配置或组件状态读取失败，请检查组件后重试") } }
        }
    }

    suspend fun refresh() = gate.withLock {
        requireIdle()
        backend.refreshComponents()
        publishProviders()
        val installed = installedDirectory()
        mutableState.update { it.copy(workerRunning = running(), status = when {
            running() -> ModelRuntimeStatus.RUNNING
            installed != null -> ModelRuntimeStatus.READY
            else -> ModelRuntimeStatus.MISSING
        }, detail = if (installed == null) "其他提供商需要安装模型运行组件，DeepSeek 官方可直接使用"
            else if (running()) "模型运行器正在运行" else "模型运行组件已安装，按需启动", progress = null) }
    }

    /** Does not install anything implicitly or start the DSH web service. */
    suspend fun ensureStarted() = gate.withLock {
        if (running() && mutableState.value.catalog.isNotEmpty()) return@withLock
        requireIdle()
        startLocked()
    }

    suspend fun refreshCatalog(includeBuiltins: Boolean = false): List<RuntimeProvider> = gate.withLock {
        requireIdle()
        startLocked()
        loadCatalogLocked(includeBuiltins)
    }

    /** Installation belongs to the application scope; navigating away does not cancel it. */
    suspend fun install(source: String = "cdn") {
        require(source in setOf("official", "cdn", "backend"))
        val task = gate.withLock {
            requireIdle()
            check(!running()) { "请先停止模型运行器，再安装组件" }
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    backend.refreshComponents()
                    for (id in listOf("ubuntu", "node")) {
                        val component = backend.components.value.firstOrNull { it.id == id }
                        if (component?.status == LinuxComponentStatus.UNSUPPORTED) throw ModelRuntimeException(
                            "ABI_UNSUPPORTED", component.detail.ifBlank { "当前设备不支持模型运行组件" })
                        if (component?.status != LinuxComponentStatus.READY) {
                            mutableState.update { it.copy(detail = "正在安装 ${if (id == "ubuntu") "Linux 基础环境" else "Node.js"}") }
                            backend.installLinux(id, source)
                            backend.refreshComponents()
                            if (backend.components.value.firstOrNull { it.id == id }?.status != LinuxComponentStatus.READY)
                                throw ModelRuntimeException("COMPONENT_INSTALL_FAILED", "组件未通过版本检测，请在组件页查看原因")
                        }
                    }
                    backend.installPackages(source) { progress -> mutableState.update {
                        it.copy(progress = progress, detail = progress.detail)
                    } }
                    mutableState.update { it.copy(status = ModelRuntimeStatus.READY, progress = null,
                        detail = "模型运行组件已校验并安装，可刷新模型目录") }
                } catch (cancelled: CancellationException) {
                    backend.cancelLinuxInstall()
                    mutableState.update { it.copy(status = ModelRuntimeStatus.MISSING, progress = null, detail = "安装已取消，未发布候选组件") }
                    throw cancelled
                } catch (failure: Exception) {
                    val error = failure.asRuntimeError("INSTALL_FAILED", "模型运行组件安装失败，请检查组件页、下载源和可用空间")
                    mutableState.update { it.copy(status = ModelRuntimeStatus.ERROR, progress = null, detail = error.message.orEmpty()) }
                    throw error
                }
            }.also {
                installTask = it
                mutableState.update { previous -> previous.copy(status = ModelRuntimeStatus.INSTALLING, progress = null,
                    detail = "正在准备模型运行组件") }
                it.start()
            }
        }
        try { task.await() } finally { if (task.isCompleted) gate.withLock { if (installTask === task) installTask = null } }
    }

    fun cancelInstall() {
        installTask?.takeIf { it.isActive }?.let {
            it.cancel(CancellationException("用户取消安装")); backend.cancelLinuxInstall()
        }
    }

    suspend fun upsert(config: ModelProviderConfig, credential: JSONObject? = null): ModelProviderSnapshot = mutate {
        backend.upsert(config.validated(), credential?.let(ModelProviderCredential::validate))
    }
    suspend fun remove(providerId: String): ModelProviderSnapshot = mutate { backend.remove(providerId) }
    suspend fun reorder(order: List<String>): ModelProviderSnapshot = mutate { backend.reorder(order.toList()) }

    private suspend fun mutate(operation: () -> ModelProviderSnapshot): ModelProviderSnapshot = gate.withLock {
        requireIdle()
        val persisted = withContext(Dispatchers.IO) { operation() }
        publishProviders()
        // The durable edit succeeds even if restarting/refetching its runtime fails afterwards.
        mutableState.update { it.copy(catalog = emptyList(), detail = "提供商配置已保存，请刷新目录") }
        if (running()) try {
            backend.configure()
            loadCatalogLocked(false)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.update { it.copy(status = ModelRuntimeStatus.ERROR,
            detail = "配置已保存，模型目录读取失败；检查认证和接口后重试") } }
        persisted
    }

    suspend fun login(providerId: String, authType: String) {
        require(authType in setOf("api_key", "oauth"))
        val task = gate.withLock {
            requireIdle()
            startLocked()
            val provider = mutableState.value.catalog.firstOrNull { it.id == providerId }
                ?: throw ModelRuntimeException("UNKNOWN_PROVIDER", "请先添加并启用这个提供商")
            require(authType in provider.authMethods) { "这个提供商不支持所选认证方式" }
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    backend.login(providerId, authType, { raw ->
                        val prompt = parsePrompt(raw)
                        val pending = PendingPrompt(prompt, CompletableDeferred())
                        check(pendingPrompt.compareAndSet(null, pending))
                        mutableState.update { it.copy(authPrompt = prompt) }
                        try { pending.reply.await() } finally {
                            pendingPrompt.compareAndSet(pending, null)
                            mutableState.update { if (it.authPrompt?.id == prompt.id) it.copy(authPrompt = null) else it }
                        }
                    }, { raw ->
                        val notice = parseNotice(raw)
                        mutableState.update { it.copy(authNotices = (it.authNotices + notice).takeLast(8)) }
                    })
                    publishProviders()
                    try {
                        loadCatalogLocked(false)
                        mutableState.update { it.copy(detail = "认证已保存到本机加密存储") }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { mutableState.update { it.copy(detail = "认证已保存，模型目录读取失败；请稍后刷新") } }
                } catch (cancelled: CancellationException) {
                    mutableState.update { it.copy(detail = "认证已取消") }
                    throw cancelled
                } catch (_: Exception) {
                    val error = ModelRuntimeException("AUTHENTICATION_FAILED", "提供商认证失败，请检查账户、接口或重新授权")
                    mutableState.update { it.copy(detail = error.message.orEmpty()) }
                    throw error
                } finally {
                    pendingPrompt.getAndSet(null)?.reply?.cancel()
                    mutableState.update { it.copy(authPrompt = null, authenticatingProvider = null) }
                }
            }.also {
                authTask = it
                mutableState.update { previous -> previous.copy(authenticatingProvider = providerId,
                    authPrompt = null, authNotices = emptyList(), detail = "正在认证 ${provider.name}") }
                it.start()
            }
        }
        try { task.await() } finally { if (task.isCompleted) gate.withLock { if (authTask === task) authTask = null } }
    }

    /** A late UI reply cannot answer the next prompt or complete an already-cancelled login. */
    fun respondToAuthPrompt(promptId: String, value: String): Boolean {
        val pending = pendingPrompt.get() ?: return false
        if (pending.prompt.id != promptId || value.length > 65_536 || '\u0000' in value) return false
        if (pending.prompt.type == "select" && pending.prompt.options.none { it.id == value }) return false
        return pending.reply.complete(value)
    }

    fun cancelAuthentication() { authTask?.cancel(CancellationException("用户取消认证")) }

    suspend fun logout(providerId: String) = gate.withLock {
        requireIdle()
        require(providerId != "deepseek") { "DeepSeek 官方的 API Key 使用专用入口管理" }
        startLocked()
        backend.logout(providerId)
        publishProviders()
        loadCatalogLocked(false)
    }

    /** Without stopActive=true a shutdown never interrupts an Android-owned task. */
    suspend fun shutdown(stopActive: Boolean = false) {
        val jobs = gate.withLock {
            check(installTask?.isActive != true) { "请先取消或等待安装完成" }
            if (!stopActive) requireIdle()
            managementBusy = true
            mutableState.update { it.copy(status = ModelRuntimeStatus.STOPPING, detail = "正在停止模型运行器") }
            sessions.values.toList().also { if (stopActive) authTask?.cancel() }
        }
        try {
            if (stopActive) jobs.forEach { it.cancel(CancellationException("用户停止模型运行器")) }
            withContext(NonCancellable) { backend.stop() }
            mutableState.update { it.copy(status = ModelRuntimeStatus.READY, detail = "模型运行器已停止") }
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(status = if (running()) ModelRuntimeStatus.RUNNING else ModelRuntimeStatus.READY,
                detail = if (running()) "模型运行器仍在清理" else "模型运行器已停止") }
            throw cancelled
        } catch (_: Exception) {
            mutableState.update { it.copy(status = ModelRuntimeStatus.ERROR, detail = "模型运行器仍在清理，请稍后重试") }
            throw ModelRuntimeException("WORKER_STOP_FAILED", "模型运行器仍在清理，请稍后重试")
        } finally { withContext(NonCancellable) { gate.withLock { managementBusy = false } } }
    }

    suspend fun <T> withSession(providerId: String, block: suspend () -> T): T {
        currentCoroutineContext()[SessionLease]?.let { lease ->
            if (lease.owner === this && lease.providerId == providerId) return block()
        }
        ensureStarted()
        val id = UUID.randomUUID().toString()
        val job = currentCoroutineContext()[Job] ?: error("任务需要可取消的协程")
        gate.withLock {
            check(!managementBusy && installTask?.isActive != true && authTask?.isActive != true) { "模型运行器正在维护或认证" }
            check(sessions.size < 3) { "模型任务已达到并发上限" }
            val provider = backend.providers().providers.firstOrNull { it.id == providerId && it.enabled }
                ?: throw ModelRuntimeException("PROVIDER_DISABLED", "所选提供商已删除或停用，请重新选择")
            require(!provider.protected) { "DeepSeek 官方使用原生直接接入" }
            sessions[id] = job
            mutableState.update { it.copy(activeSessions = sessions.size) }
        }
        try { return withContext(SessionLease(this, providerId)) { block() } } finally { withContext(NonCancellable) { gate.withLock {
            sessions.remove(id); mutableState.update { it.copy(activeSessions = sessions.size) }
        } } }
    }

    /** Call within withSession for model/tool/model tasks; direct turns remain possible for previews. */
    suspend fun generate(request: PiAiChatRequest, onEvent: suspend (AssistantStreamEvent) -> Unit): PiAiChatTurn = withSession(request.providerId) {
        currentCoroutineContext().ensureActive()
        val model = mutableState.value.catalog.firstOrNull { it.id == request.providerId }?.models?.firstOrNull { it.id == request.modelId }
            ?: throw ModelRuntimeException("UNKNOWN_MODEL", "所选模型不在真实目录中，请刷新模型列表")
        if (request.reasoning != null && request.reasoning !in model.reasoningOptions)
            throw ModelRuntimeException("UNSUPPORTED_REASONING", "所选推理档位已失效，请重新选择")
        backend.generate(request, onEvent)
    }

    fun reasoningFor(providerId: String, modelId: String, previous: String?): String? {
        val model = state.value.catalog.firstOrNull { it.id == providerId }?.models?.firstOrNull { it.id == modelId } ?: return null
        return previous?.takeIf { it in model.reasoningOptions } ?: model.defaultReasoning
    }

    private suspend fun startLocked() {
        val directory = installedDirectory() ?: throw ModelRuntimeException("COMPONENT_REQUIRED",
            "请安装模型运行组件，含 Node.js 与 Linux 基础环境；DeepSeek 官方不需要此组件")
        mutableState.update { it.copy(status = ModelRuntimeStatus.STARTING, detail = "正在启动模型运行器") }
        try {
            backend.start(directory)
            backend.configure()
            loadCatalogLocked(false)
            mutableState.update { it.copy(status = ModelRuntimeStatus.RUNNING, detail = "模型运行器正在运行") }
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(status = if (running()) ModelRuntimeStatus.RUNNING else ModelRuntimeStatus.READY,
                detail = "启动已取消，可重新尝试") }
            throw cancelled
        }
        catch (failure: Exception) {
            val error = failure.asRuntimeError("WORKER_START_FAILED", "模型运行器启动失败，请检查组件状态和 Node.js 版本")
            mutableState.update { it.copy(status = ModelRuntimeStatus.ERROR, detail = error.message.orEmpty()) }
            throw error
        }
    }

    private suspend fun loadCatalogLocked(allBuiltins: Boolean): List<RuntimeProvider> {
        val parsed = parseCatalog(backend.catalog(allBuiltins))
        publishProviders()
        mutableState.update { if (allBuiltins) it.copy(builtinCatalog = parsed) else it.copy(catalog = parsed) }
        return parsed
    }

    private suspend fun publishProviders() = withContext(Dispatchers.IO) {
        val snapshot = backend.providers()
        val credentials = snapshot.providers.map { it.id }.filter { backend.hasCredential(it) }.toSet()
        mutableState.update { it.copy(providers = snapshot, credentialProviders = credentials) }
    }
    private fun requireIdle() { check(!managementBusy && sessions.isEmpty() && installTask?.isActive != true && authTask?.isActive != true) {
        "任务、安装或认证正在使用模型运行器，请先停止或等待完成"
    } }
    private fun running() = backend.processes.value.any { it.id == WORKER_ID && it.running }
    private fun installedDirectory(): File? = try { backend.installed() } catch (_: Exception) {
        throw ModelRuntimeException("COMPONENT_DAMAGED", "模型运行组件校验信息损坏，请在组件页修复")
    }

    companion object {
        private const val WORKER_ID = "model-worker"
        private val instances = ConcurrentHashMap<String, ModelRuntimeController>()
        fun get(context: Context): ModelRuntimeController {
            val app = context.applicationContext
            return instances.computeIfAbsent(app.noBackupFilesDir.canonicalPath) { ModelRuntimeController(AndroidModelRuntimeBackend(app)) }
        }

        internal fun parseCatalog(json: JSONObject): List<RuntimeProvider> {
            require(json.toString().toByteArray().size <= 8 * 1024 * 1024 && json.getString("version") == "0.87.1")
            val providers = json.getJSONArray("providers")
            require(providers.length() <= 128)
            val ids = mutableSetOf<String>()
            return List(providers.length()) { index ->
                val provider = providers.getJSONObject(index)
                val id = provider.safeText("id", 160)
                require(ids.add(id))
                val models = provider.getJSONArray("models")
                require(models.length() <= 2048)
                val modelIds = mutableSetOf<String>()
                val parsed = List(models.length()) { modelIndex ->
                    val model = models.getJSONObject(modelIndex)
                    val modelId = model.safeText("id", 512)
                    require(modelIds.add(modelId))
                    val api = model.safeText("api", 80)
                    require(api in ModelProviderConfig.APIS)
                    val levels = model.optJSONArray("reasoningOptions")
                    require(levels == null || levels.length() <= 32)
                    val reasoning = if (levels == null) emptyList() else List(levels.length()) { level ->
                        levels.getString(level).also { require(it.isNotBlank() && it.length <= 80 && it.none(Char::isISOControl)) }
                    }.distinct()
                    val default = listOf("defaultReasoning", "default_level", "defaultThinkingLevel")
                        .asSequence().mapNotNull { key -> (model.opt(key) as? String) }.firstOrNull()
                        ?.takeIf { it in reasoning }
                    val inputs = model.optJSONArray("input")
                    require(inputs == null || inputs.length() <= 16)
                    RuntimeModel(id, modelId, model.optString("name", modelId).take(512), api, reasoning, default,
                        if (inputs == null) emptyList() else List(inputs.length()) { inputs.getString(it).take(64) },
                        (model.opt("contextWindow") as? Number)?.toLong()?.takeIf { it > 0 })
                }
                val methods = provider.optJSONArray("authMethods")
                RuntimeProvider(id, provider.safeText("name", 512), provider.optBoolean("configured"),
                    if (methods == null) emptyList() else List(methods.length().coerceAtMost(2)) { methods.getString(it) }
                        .filter { it in setOf("api_key", "oauth") }.distinct(), parsed)
            }
        }

        internal fun parsePrompt(json: JSONObject): ModelAuthPrompt {
            require(json.toString().toByteArray().size <= 128 * 1024)
            val type = json.getString("type")
            require(type in setOf("text", "secret", "select", "manual_code"))
            val choices = json.optJSONArray("options")
            require(choices == null || choices.length() in 1..128)
            val ids = mutableSetOf<String>()
            val options = if (choices == null) emptyList() else List(choices.length()) { index ->
                val choice = choices.getJSONObject(index)
                val id = choice.safeText("id", 512)
                require(ids.add(id))
                ModelAuthChoice(id, choice.safeText("label", 2048), (choice.opt("description") as? String)?.take(4096))
            }
            require(type != "select" || options.isNotEmpty())
            return ModelAuthPrompt(UUID.randomUUID().toString(), type, json.safeText("message", 8192),
                (json.opt("placeholder") as? String)?.take(2048), options)
        }

        internal fun parseNotice(json: JSONObject): ModelAuthNotice {
            require(json.toString().toByteArray().size <= 128 * 1024)
            fun link(value: String, label: String) = ModelAuthLink(value, label).also {
                val uri = URI(value)
                require(value.length <= 8192 && uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null)
            }
            return when (json.getString("type")) {
                "auth_url" -> ModelAuthNotice((json.opt("instructions") as? String)?.take(8192) ?: "在系统浏览器完成授权",
                    listOf(link(json.getString("url"), "打开授权页面")))
                "device_code" -> ModelAuthNotice("在提供商页面输入下方设备验证码",
                    listOf(link(json.getString("verificationUri"), "打开设备授权页面")), json.safeText("userCode", 512))
                "info", "progress" -> {
                    val links = json.optJSONArray("links")
                    require(links == null || links.length() <= 8)
                    ModelAuthNotice(json.safeText("message", 8192), if (links == null) emptyList() else List(links.length()) { index ->
                        val item = links.getJSONObject(index)
                        link(item.getString("url"), item.optString("label", "打开页面").take(512))
                    })
                }
                else -> error("未知认证事件")
            }
        }

        private fun JSONObject.safeText(key: String, max: Int) = getString(key).also {
            require(it.isNotBlank() && it.length <= max && it.none { char -> char == '\u0000' })
        }
    }
}

private fun Exception.asRuntimeError(code: String, message: String) = this as? ModelRuntimeException ?: ModelRuntimeException(code, message)

private class AndroidModelRuntimeBackend(context: Context) : ModelRuntimeBackend {
    private val store = ModelProviderStore(context)
    private val runtime = LinuxRuntimeManager(context)
    private val installer = PiAiRuntimeInstaller(context, runtime)
    private val worker = PiAiNodeWorker(context, store, runtime)
    override val components = runtime.snapshot
    override val processes = runtime.managedProcesses
    override fun installed() = installer.current()
    override fun providers() = store.read()
    override fun hasCredential(providerId: String) = store.hasCredential(providerId)
    override fun upsert(config: ModelProviderConfig, credential: JSONObject?) = store.upsert(config, credential)
    override fun remove(providerId: String) = store.remove(providerId)
    override fun reorder(order: List<String>) = store.reorder(order)
    override suspend fun refreshComponents() = runtime.refresh()
    override suspend fun installLinux(id: String, source: String) = runtime.install(id, source)
    override fun cancelLinuxInstall() = runtime.cancelInstall()
    override suspend fun installPackages(source: String, progress: (ModelRuntimeProgress) -> Unit) = installer.install(source, progress)
    override suspend fun start(directory: File) = worker.start(directory)
    override suspend fun configure() = worker.configure()
    override suspend fun catalog(allBuiltins: Boolean) = worker.catalog(allBuiltins)
    override suspend fun generate(request: PiAiChatRequest, onEvent: suspend (AssistantStreamEvent) -> Unit) = worker.generate(request, onEvent)
    override suspend fun login(provider: String, authType: String, onPrompt: suspend (JSONObject) -> String,
        onEvent: suspend (JSONObject) -> Unit) = worker.login(provider, authType, onPrompt, onEvent)
    override suspend fun logout(provider: String) { worker.logout(provider) }
    override suspend fun stop() = worker.stop()
}
