package com.dafeiyu.mobile.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject

enum class RuntimeStatus { Stopped, Starting, Running, Stopping }
enum class WorkspaceMode(val title: String) { Lite("轻量对话"), Dsh("DSH 工作区") }

/** An adapter-owned, discrete choice; its id is not a universal numeric effort. */
data class ReasoningOption(val id: String, val label: String)

/** Remote metadata or explicitly identified local preview metadata. */
data class ModelChoice(
    val id: String,
    val provider: String,
    val name: String,
    val reasoningOptions: List<ReasoningOption>,
    val defaultReasoningIndex: Int,
    val isExample: Boolean = true,
    val inputModalities: List<String> = emptyList(),
    val officialCapabilities: DeepSeekChatClient.Model? = null,
    val runtimeModel: RuntimeModel? = null,
)

/** Imported files use private copies; validated text is prepared away from the UI thread. */
data class AttachmentPreview(
    val id: String,
    val label: String,
    val uri: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long = 0,
    val textContent: String? = null,
    val textSupported: Boolean = false,
)

data class ExtensionItem(
    val id: String,
    val name: String,
    val subtitle: String,
    val description: String,
    val version: String,
    val category: String,
    val installed: Boolean = false,
    val enabled: Boolean = true,
)

/** Local workspaces, native model tasks, and the application-owned DSH service. */
class DemoRepository : ViewModel() {
    var workspaceMode by mutableStateOf(WorkspaceMode.Lite)
        private set
    var composerGeneration by mutableIntStateOf(0)
        private set
    val workspaces = mutableStateListOf(Workspace("default", "默认工作区"))
    var selectedWorkspaceId by mutableStateOf("default")
        private set
    val selectedWorkspace get() = workspaces.firstOrNull { it.id == selectedWorkspaceId } ?: workspaces.first()
    val conversations = mutableStateListOf(Conversation("initial", "新对话", "default"))
    val archivedConversations: List<Conversation>
        get() = conversations.filter { it.archivedAtMillis != null }
    var selectedConversationId by mutableStateOf("initial")
        private set
    private val conversationMessages = mutableStateMapOf<String, SnapshotStateList<ChatMessage>>()
    var workspaceInitialized by mutableStateOf(false)
        private set
    var sidebarMetadataBusy by mutableStateOf(false)
        private set
    var workspaceRootPath by mutableStateOf("")
        private set
    private var workspaceStore: WorkspaceStore? = null
    private var applicationContext: Context? = null
    private var attachmentStore: ConversationAttachmentStore? = null
    private var officialOutputStore: OfficialToolOutputStore? = null
    private val officialOutputs = mutableStateMapOf<String, OfficialToolOutput>()
    private val importedAttachments = mutableStateMapOf<String, AttachmentPreview>()
    private val deletingAttachmentIds = mutableSetOf<String>()
    private val pendingAttachmentSelections = mutableMapOf<String, Any>()
    private var attachmentCleanupBusy = false
    private var attachmentSelectionGeneration = 0L
    private var deletionProtectionLoadError by mutableStateOf<String?>(null)
    private var workspaceDeletionBusy by mutableStateOf(false)
    private var composerDraftText by mutableStateOf<String?>(null)
    var attachmentImportCount by mutableIntStateOf(0)
        private set
    private var settingsPreferences: android.content.SharedPreferences? = null
    private var settingsRuntimeStore: SettingsRuntimeStore? = null
    private var deepSeekClient: DeepSeekChatClient? = null
    var deepSeekConfigured by mutableStateOf(false)
        private set
    var deepSeekConfigurationBusy by mutableStateOf(false)
        private set
    var deepSeekModelError by mutableStateOf<String?>(null)
        private set
    private var deepSeekBalanceStore: BalanceStore? = null
    private var modelProviderStore: ModelProviderStore? = null
    private var nativeTranscriptStore: NativeTranscriptStore? = null
    private var notificationMonitor: NativeNotificationMonitor? = null
    private var pushSettings: PushSettingsController? = null
    var pushSettingsState by mutableStateOf(PushSettingsController.State())
        private set
    private var connectPluginHost: ConnectPluginHost? = null
    private var connectVisibilityJob: Job? = null
    var modelRuntime by mutableStateOf<ModelRuntimeController?>(null)
        private set
    private var modelRuntimeSnapshotJob: Job? = null
    private var catalogClient: DeepSeekChatClient? = null
    var backgroundTasks by mutableStateOf<BackgroundTaskEngine?>(null)
        private set
    private val taskClients = mutableSetOf<DeepSeekChatClient>()
    private val taskCredentialBoundary = BackgroundTaskCredentialBoundary()
    @Volatile private var credentialGeneration = 0L
    private var activeStreamMessageId: String? = null
    private var activeRunMetrics: ChatRunMetricsCollector? = null
    private var initializationStarted = false
    private var saveJob: Job? = null
    private val persistenceMutex = Mutex()
    private var persistenceRevision = 0L
    var sendPending by mutableStateOf(false)
        private set
    var workspaceSaveError by mutableStateOf<String?>(null)
        private set
    var runtimeStatus by mutableStateOf(RuntimeStatus.Stopped)
        private set
    var runtimeProgress by mutableIntStateOf(0)
        private set
    var runtimeStep by mutableStateOf("DSH 服务已停止")
        private set
    var runtimePort by mutableStateOf<Int?>(null)
        private set
    var runtimeAccessUrl by mutableStateOf<String?>(null)
        private set
    var runtimeVersion by mutableStateOf<String?>(null)
        private set
    var runtimeLastError by mutableStateOf<String?>(null)
        private set
    var runtimeAutostartSuppressed by mutableStateOf(false)
        private set
    var themeMode by mutableStateOf("system")
    var accentColorKey by mutableStateOf("blue")
    var reduceTransparency by mutableStateOf(false)
    var reduceMotion by mutableStateOf(false)
    var notificationsEnabled by mutableStateOf(true)
    var keepRunning by mutableStateOf(true)
    var downloadSource by mutableStateOf("cdn")
    var engineMode by mutableStateOf("official")
    var developerMode by mutableStateOf(false)
        private set
    var localEngineSwitchBusy by mutableStateOf(false)
        private set
    var localEngineSwitchError by mutableStateOf<String?>(null)
        private set
    var localDshSessionId by mutableStateOf<String?>(null)
        private set
    var localEngineSharingWarnings by mutableStateOf<List<String>>(emptyList())
        private set
    var dshSharedConfigurationError by mutableStateOf<String?>(null)
        private set
    var localSandboxMode by mutableStateOf(LinuxSandboxContract.WORKSPACE_WRITE)
        private set
    var localSandboxVerified by mutableStateOf(false)
        private set
    var localSandboxVerifying by mutableStateOf(false)
        private set
    var localSandboxDetail by mutableStateOf("请验证本机执行器的实际文件约束")
        private set
    var connectionMode by mutableStateOf("local")
    var autoStartDsh by mutableStateOf(false)
    var syncWorkspace by mutableStateOf(false)
    var webBroadcast by mutableStateOf(false)
    var liveIslandNotifications by mutableStateOf(true)
    var announcementNotifications by mutableStateOf(true)
    var deviceNotifications by mutableStateOf(true)
    var messageNotifications by mutableStateOf(true)
        private set
    var backendStatusNotifications by mutableStateOf(true)
        private set
    var tokenSavingMode by mutableStateOf(false)
        private set
    var powerSavingMode by mutableStateOf(false)
        private set
    var glassMaterial by mutableStateOf(true)
    var pageTransitions by mutableStateOf(true)
    var springButtons by mutableStateOf(true)
    var fontFamily by mutableStateOf("系统")
    var fontSizeScale by mutableFloatStateOf(1f)
    var fontWeight by mutableStateOf("标准")
    var interfaceScale by mutableFloatStateOf(1f)
    var updatePolicy by mutableStateOf("remind")
    var updateWifiOnly by mutableStateOf(true)
    var autoCleanupCache by mutableStateOf(false)
    var officialEngineStrategy by mutableStateOf("balanced")
    var officialEngineStreaming by mutableStateOf(true)
    var officialEngineShowReasoning by mutableStateOf(true)
        private set
    var officialEngineExpandActivities by mutableStateOf(false)
        private set
    var dshVersionChannel by mutableStateOf("stable")
    var dshUpdatePolicy by mutableStateOf("remind")
    /** Stable provider ids are persisted separately from display labels and model metadata. */
    var modelProviderOrder by mutableStateOf(SettingsRuntimeStore.DEFAULT_MODEL_PROVIDER_ORDER)
        private set
    var modelProviderEnabled by mutableStateOf(
        SettingsRuntimeStore.DEFAULT_MODEL_PROVIDER_ORDER.associateWith { true },
    )
        private set
    val enabledModelProviderIds: Set<String>
        get() = modelProviderOrder.filter { modelProviderEnabled[it] == true }.toSet()
    var virtualMachineProfile by mutableStateOf(SettingsRuntimeStore.DEFAULT_VM_PROFILE)
        private set
    var virtualMachineHardwareAcceleration by mutableStateOf(
        SettingsRuntimeStore.DEFAULT_VM_HARDWARE_ACCELERATION,
    )
        private set
    var virtualMachineBackgroundRunning by mutableStateOf(
        SettingsRuntimeStore.DEFAULT_VM_BACKGROUND_RUNNING,
    )
        private set
    /** Persisted Aries endpoint and last observed connection metadata. */
    var ariesEndpoint by mutableStateOf("")
    var ariesNodeId by mutableStateOf<String?>(null)
    var ariesServerCode by mutableStateOf<String?>(null)
    var ariesDeviceId by mutableStateOf<String?>(null)
    var ariesGroupId by mutableStateOf<String?>(null)
    var ariesConnectionState by mutableStateOf(OnlineConnectionState.UNCONFIGURED)
    var ariesLastCheckedAtEpochMillis by mutableStateOf<Long?>(null)
    var isResponding by mutableStateOf(false)
        private set
    var lastNotice by mutableStateOf("")
        private set

    private val unconfiguredModel = ModelChoice("unconfigured", "DeepSeek", "选择模型", emptyList(), -1, false)
    val modelChoices = mutableStateListOf<ModelChoice>()
    var selectedModel by mutableStateOf(unconfiguredModel)
        private set
    var selectedReasoningIndex by mutableIntStateOf(selectedModel.defaultReasoningIndex)
        private set
    val selectedReasoningOption get() = selectedModel.reasoningOptions.getOrNull(selectedReasoningIndex)

    var remoteDsh by mutableStateOf<RemoteDshApplicationController?>(null)
        private set
    var remoteDshState by mutableStateOf(RemoteDshApplicationController.State())
        private set
    var remoteArtifactDownloads by mutableStateOf<RemoteArtifactDownloadController?>(null)
        private set
    private var remoteAttachmentComposer: RemoteAttachmentComposer? = null
    var remoteAttachmentProgress by mutableStateOf(RemoteAttachmentComposer.State())
        private set
    private var remoteWorkspaceRemovalConfirmation by mutableStateOf<RemoteWorkspaceRemovalConfirmation?>(null)
    val remoteWorkspaceScope: RemoteDshScope?
        get() = remoteDshState.projection?.workspace?.scope?.takeIf { binding ->
            remoteDshState.connection?.let { runCatching { binding.requireCurrent(it) }.isSuccess } == true
        }
    val remoteWorkspaceLifecycleReady: Boolean
        get() = connectionMode == "online" && remoteWorkspaceLifecycleAvailable(
            remoteDshState.connection, remoteDshState.projection?.workspace)
    val displayArchiveGroups: List<ConversationArchiveGroup>
        get() = if (connectionMode == "online") {
            remoteDshState.projection?.let { projection ->
                remoteConversationArchiveGroups(projection.workspace, projection.sessions.mapValues { (_, view) ->
                    view.messages.firstOrNull { it.role == "user" }?.text?.replace('\n', ' ')?.take(60).orEmpty()
                })
            }.orEmpty()
        } else conversationArchiveGroups(workspaces.toList(), conversations.toList())
    val displayArchivedConversations: List<Conversation>
        get() = displayArchiveGroups.flatMap { it.conversations }
    val remoteArchiveDeletionAvailable: Boolean get() = remoteWorkspaceLifecycleReady &&
        remoteDshState.connection?.optJSONObject("capabilities")?.optBoolean("remote_archive_delete") == true
    val archiveOperationBusy: Boolean get() = workspaceDeletionBusy || remoteDshState.busy
    var localArchiveDeletionDetail by mutableStateOf<String?>(null)
        private set
    private val localArchivePendingIds = mutableStateListOf<String>()
    private var localArchiveRecoveryFailed = false
    val displayWorkspaces: List<Workspace> get() = if (connectionMode == "online")
        remoteDshState.projection?.workspace?.workspaces.orEmpty().map { Workspace(it.id, it.title, it.path) } else workspaces
    val displayWorkspaceId: String get() = if (connectionMode == "online") remoteDshState.selectedWorkspaceId.orEmpty() else selectedWorkspaceId
    val displayConversationId: String get() = if (connectionMode == "online") remoteDshState.selectedSessionId.orEmpty() else selectedConversationId
    val displayConversations: List<Conversation> get() = if (connectionMode == "online") {
        val remote = remoteDshState.projection
        remote?.workspace?.workspaces.orEmpty().flatMap { workspace -> workspace.sessionIds.map { id ->
            val title = remote?.sessions?.get(id)?.messages?.firstOrNull { it.role == "user" }?.text
                ?.replace('\n', ' ')?.take(30)?.takeIf { it.isNotBlank() } ?: "电脑会话"
            Conversation(id, title, workspace.id)
        } }
    } else conversations
    val displayMessages: List<ChatMessage> get() = if (connectionMode == "online") remoteDshState.selectedView?.messages.orEmpty() else messages
    val displayResponding: Boolean get() = if (connectionMode == "online") remoteDshState.running else isResponding
    val displayModelChoices: List<ModelChoice> get() = if (connectionMode == "online")
        remoteDshState.projection?.workspace?.catalog?.providers.orEmpty().flatMap { provider -> provider.models.map { model ->
            ModelChoice("${provider.id}::${model.id}", provider.name, model.name,
                model.efforts.map { ReasoningOption(it.id, it.name) },
                model.efforts.indexOfFirst { it.id == model.defaultEffort }, false)
        } } else modelChoices
    val displaySelectedModel: ModelChoice get() {
        if (connectionMode != "online") return selectedModel
        val metadata = remoteDshState.projection?.workspace
        val selection = metadata?.settings?.selection ?: metadata?.catalog?.selection
        return displayModelChoices.firstOrNull { it.id == "${selection?.provider}::${selection?.model}" }
            ?: ModelChoice("remote-unconfigured", "电脑", "选择电脑模型", emptyList(), -1, false)
    }
    val displayReasoningIndex: Int get() {
        if (connectionMode != "online") return selectedReasoningIndex
        val metadata = remoteDshState.projection?.workspace
        val effort = (metadata?.settings?.selection ?: metadata?.catalog?.selection)?.reasoningEffort
        return displaySelectedModel.reasoningOptions.indexOfFirst { it.id == effort }
    }
    val displayReasoningOption: ReasoningOption? get() = displaySelectedModel.reasoningOptions.getOrNull(displayReasoningIndex)
    val remoteProviderCatalog: List<RuntimeProvider> get() = remoteDshState.projection?.workspace?.catalog?.let { catalog ->
        catalog.providers.map { provider -> RuntimeProvider(provider.id, provider.name, provider.id in catalog.routable,
            emptyList(), provider.models.map { model -> RuntimeModel(provider.id, model.id, model.name, "电脑 DSH",
                model.efforts.map { it.id }, model.defaultEffort) }) }
    }.orEmpty()

    val attachmentChoices = listOf(
        AttachmentPreview("preview-image", "示例图片.png"),
        AttachmentPreview("preview-document", "示例文档.pdf"),
        AttachmentPreview("preview-code", "示例代码.kt"),
    )
    private val liteAttachments = mutableStateListOf<AttachmentPreview>()
    private val dshAttachments = mutableStateListOf<AttachmentPreview>()
    private val activeAttachments get() = if (workspaceMode == WorkspaceMode.Lite) liteAttachments else dshAttachments
    val attachmentPreviews: List<AttachmentPreview> get() = activeAttachments.toList()

    val messages: SnapshotStateList<ChatMessage>
        get() = conversationMessages.getOrPut(selectedConversationId) { mutableStateListOf() }
    val extensions = mutableStateListOf(
        ExtensionItem(
            id = "mobile-files",
            name = "文件助手",
            subtitle = "把创作带到手机上",
            description = "导入照片、文本和代码附件；文本随对话发送，图片保存在本地。可将助手正文保存到系统文件位置。",
            version = "0.1.0",
            category = "plugin",
            installed = true,
        ),
        ExtensionItem(
            id = "task-notifications",
            name = "任务通知",
            subtitle = "完成时，轻轻提醒你",
            description = "演示任务完成提醒的开关；当前不会发送系统通知或申请权限。",
            version = "0.1.0",
            category = "plugin",
            installed = true,
        ),
        ExtensionItem(
            id = "project-preview",
            name = "作品预览",
            subtitle = "从想法到可见的作品",
            description = "演示作品预览插件的安装状态；当前没有下载、浏览器服务或运行环境。",
            version = "0.1.0",
            category = "plugin",
        ),
        ExtensionItem(
            id = "writing-partner",
            name = "写作搭档",
            subtitle = "梳理思路，打磨表达",
            description = "演示一份写作技能说明，用于整理提纲、润色和改写；当前不调用模型。",
            version = "1.0",
            category = "skill",
            installed = true,
        ),
        ExtensionItem(
            id = "code-review",
            name = "代码审阅",
            subtitle = "看清问题，也看清下一步",
            description = "演示检查代码结构、潜在问题和修改建议的技能；当前不会访问项目文件。",
            version = "1.0",
            category = "skill",
        ),
        ExtensionItem(
            id = "daily-planning",
            name = "日程规划",
            subtitle = "给今天留一点秩序",
            description = "演示把目标拆成行动清单的技能；当前不会连接日历或建立提醒。",
            version = "1.0",
            category = "skill",
        ),
    )

    private var runtimeJob: Job? = null
    private var dshRuntimeManager: DshRuntimeManager? = null
    private var localDshHistory: LocalDshHistoryController? = null
    private var sharedDshConfiguration: DshSharedConfigurationAdapter? = null
    private val localEngineGate = Mutex()
    private var dshProviderSyncJob: Job? = null
    private var lastDshProviderRevision = -1L
    var dshInstallation by mutableStateOf<DshInstallationController?>(null)
        private set
    private var dshInstallJob: Job? = null
    private var dshCheckJob: Job? = null
    private var dshSnapshotJob: Job? = null
    private var responseJob: Job? = null
    private var responseGeneration = 0L
    private var nextMessageId = 1L
    private var activeResponseId: Long? = null
    private var activeResponseConversationId: String? = null
    private var activeResponseReducer: AssistantStreamReducer? = null

    private fun loadSettings(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences("dafeiyu_settings", Context.MODE_PRIVATE)
        settingsPreferences = prefs
        developerMode = prefs.getBoolean("developer_mode", false)
        localSandboxMode = prefs.getString("local_sandbox_mode", LinuxSandboxContract.WORKSPACE_WRITE)
            ?.takeIf { it in LinuxSandboxContract.modes } ?: LinuxSandboxContract.WORKSPACE_WRITE
        val runtimeStore = SettingsRuntimeStore(prefs)
        settingsRuntimeStore = runtimeStore
        val runtimeSnapshot = runtimeStore.read()
        tokenSavingMode = runtimeSnapshot.tokenSavingMode
        powerSavingMode = runtimeSnapshot.powerSavingMode
        modelProviderOrder = runtimeSnapshot.modelProviders.order
        modelProviderEnabled = runtimeSnapshot.modelProviders.enabledById
        virtualMachineProfile = runtimeSnapshot.virtualMachine.profile
        virtualMachineHardwareAcceleration = runtimeSnapshot.virtualMachine.hardwareAcceleration
        virtualMachineBackgroundRunning = runtimeSnapshot.virtualMachine.backgroundRunning
        themeMode = SettingsContract.themeMode(prefs.getString("theme_mode", themeMode) ?: themeMode)
        accentColorKey = prefs.getString("accent_color", accentColorKey) ?: accentColorKey
        reduceTransparency = prefs.getBoolean("reduce_transparency", reduceTransparency)
        reduceMotion = prefs.getBoolean("reduce_motion", reduceMotion)
        notificationsEnabled = prefs.getBoolean("notifications_enabled", notificationsEnabled)
        keepRunning = prefs.getBoolean("keep_running", keepRunning)
        downloadSource = runtimeSnapshot.downloadSource
        engineMode = SettingsContract.engineMode(prefs.getString("engine_mode", engineMode) ?: engineMode)
        connectionMode = SettingsContract.connectionMode(prefs.getString("connection_mode", connectionMode) ?: connectionMode)
        autoStartDsh = prefs.getBoolean("auto_start_dsh", autoStartDsh)
        syncWorkspace = prefs.getBoolean("sync_workspace", syncWorkspace)
        webBroadcast = prefs.getBoolean("web_broadcast", webBroadcast)
        liveIslandNotifications = prefs.getBoolean("live_island_notifications", liveIslandNotifications)
        announcementNotifications = prefs.getBoolean("announcement_notifications", announcementNotifications)
        deviceNotifications = prefs.getBoolean("device_notifications", deviceNotifications)
        messageNotifications = prefs.getBoolean("message_notifications", true)
        backendStatusNotifications = prefs.getBoolean("backend_status_notifications", true)
        glassMaterial = prefs.getBoolean("glass_material", glassMaterial)
        pageTransitions = prefs.getBoolean("page_transitions", pageTransitions)
        springButtons = prefs.getBoolean("spring_buttons", springButtons)
        fontFamily = prefs.getString("font_family", fontFamily) ?: fontFamily
        fontSizeScale = prefs.getFloat("font_size_scale", fontSizeScale)
        fontWeight = prefs.getString("font_weight", fontWeight) ?: fontWeight
        interfaceScale = prefs.getFloat("interface_scale", interfaceScale)
        updatePolicy = runtimeSnapshot.updatePolicy
        updateWifiOnly = prefs.getBoolean("update_wifi_only", updateWifiOnly)
        autoCleanupCache = prefs.getBoolean("auto_cleanup_cache", autoCleanupCache)
        officialEngineStrategy = prefs.getString("official_engine_strategy", officialEngineStrategy) ?: officialEngineStrategy
        officialEngineStreaming = prefs.getBoolean("official_engine_streaming", officialEngineStreaming)
        officialEngineShowReasoning = prefs.getBoolean("official_engine_show_reasoning", true)
        officialEngineExpandActivities = runtimeSnapshot.officialEngineExpandActivities
        dshVersionChannel = SettingsContract.dshVersionChannel(prefs.getString("dsh_version_channel", dshVersionChannel) ?: dshVersionChannel)
        dshUpdatePolicy = runtimeSnapshot.dshUpdatePolicy
        ariesEndpoint = SettingsContract.normalizeAriesEndpoint(prefs.getString("aries_endpoint", "").orEmpty()).orEmpty()
        ariesNodeId = prefs.getString("aries_node_id", null)?.takeIf { it.isNotBlank() }
        ariesServerCode = prefs.getString("aries_server_code", null)?.takeIf { it.isNotBlank() }
        ariesDeviceId = prefs.getString("aries_device_id", null)?.takeIf { it.matches(Regex("dev_[a-f0-9]{32}")) }
        ariesGroupId = prefs.getString("aries_group_id", null)?.takeIf { it.isNotBlank() }
        ariesConnectionState = OnlineConnectionState.fromId(prefs.getString("aries_connection_state", "unconfigured").orEmpty())
        ariesLastCheckedAtEpochMillis = prefs.getLong("aries_last_checked_at", 0L).takeIf { it > 0L }
    }

    fun updateThemeMode(value: String) { themeMode = value; settingsPreferences?.edit()?.putString("theme_mode", value)?.apply() }
    fun updateDeveloperMode(enabled: Boolean) {
        val saved = settingsPreferences?.edit()?.putBoolean("developer_mode", enabled)?.commit() == true
        if (saved) { developerMode = enabled; updateNotice(if (enabled) "开发者页面已开启" else "开发者页面已关闭") }
        else updateNotice("开发者偏好保存失败，请重试")
    }
    fun updateLocalSandboxMode(value: String) {
        require(value in LinuxSandboxContract.modes)
        if (connectionMode != "local" || engineMode != "official") { updateNotice("请在本机轻引擎选择沙箱档位"); return }
        if (hasNativeRunningTasks()) { updateNotice("请等待本机任务停止后再更改执行档位"); return }
        if (!restoreLocalSandboxPreference(value)) updateNotice("沙箱偏好保存失败，请重试")
    }
    /** Restore a preference for later local use; never restore executor evidence or approval grants. */
    internal fun restoreLocalSandboxPreference(value: String): Boolean {
        require(value in LinuxSandboxContract.modes)
        if (localEngineSwitchBusy || localSandboxVerifying || hasNativeRunningTasks()) return false
        val saved = settingsPreferences?.edit()?.putString("local_sandbox_mode", value)?.commit() == true
        if (saved) localSandboxMode = value
        return saved
    }
    fun verifyLocalSandbox() {
        if (localSandboxVerifying || connectionMode != "local" || engineMode != "official") return
        if (hasNativeRunningTasks()) { updateNotice("请等待本机任务结束后验证执行约束"); return }
        val app = applicationContext ?: return
        localSandboxVerifying = true; localSandboxVerified = false; localSandboxDetail = "正在验证真实文件效果…"
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { LinuxSandboxVerifier(app, LinuxRuntimeManager(app)).verify(force = true) }
                localSandboxVerified = true; localSandboxDetail = "本机只读与工作区内修改约束已验证；受限任务按此执行"
            } catch (cancelled: CancellationException) { localSandboxDetail = "验证已取消"; throw cancelled }
            catch (failure: Exception) { localSandboxDetail = "验证未通过：${failure.message.orEmpty().take(400)}" }
            finally { localSandboxVerifying = false }
        }
    }
    fun updateAccentColor(value: String) {
        accentColorKey = value
        settingsPreferences?.edit()?.putString("accent_color", value)?.apply()
    }
    fun updateReduceTransparency(value: Boolean) { reduceTransparency = value; settingsPreferences?.edit()?.putBoolean("reduce_transparency", value)?.apply() }
    fun updateReduceMotion(value: Boolean) { reduceMotion = value; settingsPreferences?.edit()?.putBoolean("reduce_motion", value)?.apply() }
    fun updateNotificationsEnabled(value: Boolean) {
        notificationsEnabled = value
        settingsPreferences?.edit()?.putBoolean("notifications_enabled", value)?.apply()
        notificationMonitor?.reconcile(AndroidAppVisibility.isForeground, powerSavingMode)
        synchronizePushNotificationPreferences()
        if (!value) applicationContext?.let { NotificationPublisher(it).clearRunning() }
    }
    fun updateKeepRunning(value: Boolean) { keepRunning = value; settingsPreferences?.edit()?.putBoolean("keep_running", value)?.apply() }
    fun updateDownloadSource(value: String) {
        downloadSource = SettingsContract.downloadSource(value)
        settingsRuntimeStore?.setDownloadSource(downloadSource)
            ?: settingsPreferences?.edit()?.putString(SettingsRuntimeStore.KEY_DOWNLOAD_SOURCE, downloadSource)?.apply()
    }
    fun updateEngineMode(value: String) {
        val target = SettingsContract.engineMode(value)
        if (target == engineMode) return
        if (workspaceDeletionBusy || connectionMode == "local" &&
            (localArchiveRecoveryFailed || localArchivePendingIds.isNotEmpty())) {
            updateNotice("归档永久删除尚未完成，请先完成或重试删除")
            return
        }
        if (localEngineSwitchBusy) { updateNotice("正在同步引擎配置与历史，请稍候"); return }
        if (connectionMode == "local" && hasNativeRunningTasks()) {
            updateNotice("请先停止本机任务并等待结束，再切换引擎")
            return
        }
        runtimeAutostartSuppressed = false
        if (connectionMode == "online") {
            engineMode = target
            settingsPreferences?.edit()?.putString("engine_mode", target)?.apply()
            return
        }
        localEngineSwitchBusy = true
        localEngineSwitchError = null
        localEngineSharingWarnings = emptyList()
        runtimeJob = viewModelScope.launch {
            try {
                localEngineGate.withLock {
                    if (target == "official") synchronizeFromLocalDsh()
                    else startLocalDsh(sharedHistory = true)
                    engineMode = target
                    settingsPreferences?.edit()?.putString("engine_mode", target)?.apply()
                    composerGeneration++
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                localEngineSwitchError = failure.message ?: "引擎切换未完成，原历史保留"
                updateNotice(requireNotNull(localEngineSwitchError))
            } finally { localEngineSwitchBusy = false }
        }
    }
    fun updateConnectionMode(value: String) {
        if (localEngineSwitchBusy) { updateNotice("正在同步引擎配置与历史，请稍候"); return }
        if (workspaceDeletionBusy) { updateNotice("正在提交工作区或归档操作，请稍候"); return }
        if (connectionMode != SettingsContract.connectionMode(value) && connectionMode == "local") cancelResponse()
        remoteWorkspaceRemovalConfirmation = null
        connectionMode = SettingsContract.connectionMode(value)
        settingsPreferences?.edit()?.putString("connection_mode", connectionMode)?.apply()
        remoteDsh?.setActive(connectionMode == "online")
        if (connectionMode == "online") remoteOperation {
            remoteDsh?.connect()
            remoteDsh?.refresh()
            val receipt = remoteDsh?.resolveWorkspace(selectedWorkspace.name)
            if (receipt?.optString("status") == "needs_directory") updateNotice("请在电脑目录中选择此工作区的实际位置")
            else if (receipt?.optString("status") == "choose_workspace") updateNotice("电脑有多个同名工作区，请在工作目录中选择")
        }
    }
    fun updateAutoStartDsh(value: Boolean) { autoStartDsh = value; settingsPreferences?.edit()?.putBoolean("auto_start_dsh", value)?.apply() }
    fun updateSyncWorkspace(value: Boolean) { syncWorkspace = value; settingsPreferences?.edit()?.putBoolean("sync_workspace", value)?.apply() }
    fun updateWebBroadcast(value: Boolean) { webBroadcast = value; settingsPreferences?.edit()?.putBoolean("web_broadcast", value)?.apply() }
    fun updateLiveIslandNotifications(value: Boolean) { liveIslandNotifications = value; settingsPreferences?.edit()?.putBoolean("live_island_notifications", value)?.apply(); synchronizePushNotificationPreferences() }
    fun updateAnnouncementNotifications(value: Boolean) { announcementNotifications = value; settingsPreferences?.edit()?.putBoolean("announcement_notifications", value)?.apply(); synchronizePushNotificationPreferences() }
    fun updateDeviceNotifications(value: Boolean) { deviceNotifications = value; settingsPreferences?.edit()?.putBoolean("device_notifications", value)?.apply(); synchronizePushNotificationPreferences() }
    fun updateMessageNotifications(value: Boolean) {
        messageNotifications = value
        settingsPreferences?.edit()?.putBoolean("message_notifications", value)?.apply()
        notificationMonitor?.onNotificationPreferencesChanged()
        synchronizePushNotificationPreferences()
    }
    fun updateBackendStatusNotifications(value: Boolean) { backendStatusNotifications = value; settingsPreferences?.edit()?.putBoolean("backend_status_notifications", value)?.apply(); synchronizePushNotificationPreferences() }
    private fun pushNotificationPreferences() = PushPreferences(notificationsEnabled, liveIslandNotifications,
        deviceNotifications, announcementNotifications, messageNotifications, backendStatusNotifications)
    private fun synchronizePushNotificationPreferences() { pushSettings?.updatePreferences(pushNotificationPreferences()) }
    fun refreshPushNotificationStatus() { pushSettings?.refresh(pushNotificationPreferences()) }
    fun updateTokenSavingMode(value: Boolean) {
        tokenSavingMode = value
        settingsRuntimeStore?.setTokenSavingMode(value)
    }
    fun updatePowerSavingMode(value: Boolean) {
        powerSavingMode = value
        settingsRuntimeStore?.setPowerSavingMode(value)
        updateRemoteForeground(AndroidAppVisibility.isForeground)
    }
    fun updateGlassMaterial(value: Boolean) { glassMaterial = value; settingsPreferences?.edit()?.putBoolean("glass_material", value)?.apply() }
    fun updatePageTransitions(value: Boolean) { pageTransitions = value; settingsPreferences?.edit()?.putBoolean("page_transitions", value)?.apply() }
    fun updateSpringButtons(value: Boolean) { springButtons = value; settingsPreferences?.edit()?.putBoolean("spring_buttons", value)?.apply() }
    fun updateFontFamily(value: String) { fontFamily = value; settingsPreferences?.edit()?.putString("font_family", value)?.apply() }
    fun updateFontSizeScale(value: Float) {
        fontSizeScale = value.coerceIn(0.85f, 1.2f)
        settingsPreferences?.edit()?.putFloat("font_size_scale", fontSizeScale)?.apply()
    }
    fun updateFontWeight(value: String) { fontWeight = value; settingsPreferences?.edit()?.putString("font_weight", value)?.apply() }
    fun updateInterfaceScale(value: Float) {
        interfaceScale = value.coerceIn(0.85f, 1.15f)
        settingsPreferences?.edit()?.putFloat("interface_scale", interfaceScale)?.apply()
    }
    fun updateUpdatePolicy(value: String) {
        updatePolicy = SettingsContract.updatePolicy(value)
        settingsRuntimeStore?.setUpdatePolicy(updatePolicy)
            ?: settingsPreferences?.edit()?.putString(SettingsRuntimeStore.KEY_UPDATE_POLICY, updatePolicy)?.apply()
        applicationContext?.let { AndroidUpdateScheduler.reconcile(it, updatePolicy, updateWifiOnly) }
    }
    fun updateUpdateWifiOnly(value: Boolean) {
        updateWifiOnly = value
        settingsPreferences?.edit()?.putBoolean("update_wifi_only", value)?.apply()
        applicationContext?.let { AndroidUpdateScheduler.reconcile(it, updatePolicy, updateWifiOnly) }
    }
    fun updateAutoCleanupCache(value: Boolean) { autoCleanupCache = value; settingsPreferences?.edit()?.putBoolean("auto_cleanup_cache", value)?.apply() }

    /** Persist a complete provider ordering while retaining each provider's enabled state. */
    fun updateModelProviderOrder(order: List<String>) {
        val current = ModelProviderSettings(modelProviderOrder, modelProviderEnabled)
        val normalized = SettingsRuntimeStore.normalizeModelProviderSettings(order, current.enabledById)
        val persisted = settingsRuntimeStore?.setModelProviderSettings(
            normalized.order,
            normalized.enabledById,
        ) ?: normalized
        applyModelProviderSettings(persisted)
        notifyTaskConfiguration()
    }

    /** Add unknown provider ids to the order so a future provider can be enabled before its models load. */
    fun updateModelProviderEnabled(providerId: String, enabled: Boolean) {
        val persisted = settingsRuntimeStore?.setModelProviderEnabled(providerId, enabled)
            ?: run {
                val normalizedId = SettingsRuntimeStore.normalizeProviderId(providerId)
                if (normalizedId == null) {
                    ModelProviderSettings(modelProviderOrder, modelProviderEnabled)
                } else {
                    val order = if (normalizedId in modelProviderOrder) {
                        modelProviderOrder
                    } else {
                        modelProviderOrder + normalizedId
                    }
                    SettingsRuntimeStore.normalizeModelProviderSettings(
                        order,
                        modelProviderEnabled + (normalizedId to enabled),
                    )
                }
            }
        applyModelProviderSettings(persisted)
        notifyTaskConfiguration()
    }

    fun isModelProviderEnabled(providerId: String): Boolean =
        modelProviderEnabled[providerId] == true

    /** Persist all virtual-machine resource policy values as one coherent snapshot. */
    fun updateVirtualMachineStrategy(
        profile: String = virtualMachineProfile,
        hardwareAcceleration: Boolean = virtualMachineHardwareAcceleration,
        backgroundRunning: Boolean = virtualMachineBackgroundRunning,
    ) {
        val normalized = settingsRuntimeStore?.setVirtualMachineStrategy(
            profile,
            hardwareAcceleration,
            backgroundRunning,
        ) ?: VirtualMachineSettings(
            profile = SettingsRuntimeStore.normalizeVirtualMachineProfile(profile),
            hardwareAcceleration = hardwareAcceleration,
            backgroundRunning = backgroundRunning,
        )
        virtualMachineProfile = normalized.profile
        virtualMachineHardwareAcceleration = normalized.hardwareAcceleration
        virtualMachineBackgroundRunning = normalized.backgroundRunning
    }

    fun updateVirtualMachineProfile(value: String) {
        updateVirtualMachineStrategy(profile = value)
    }

    fun updateVirtualMachineHardwareAcceleration(value: Boolean) {
        updateVirtualMachineStrategy(hardwareAcceleration = value)
    }

    fun updateVirtualMachineBackgroundRunning(value: Boolean) {
        updateVirtualMachineStrategy(backgroundRunning = value)
    }
    fun updateOfficialEngineStrategy(value: String) { officialEngineStrategy = value; settingsPreferences?.edit()?.putString("official_engine_strategy", value)?.apply() }
    fun updateOfficialEngineStreaming(value: Boolean) { officialEngineStreaming = value; settingsPreferences?.edit()?.putBoolean("official_engine_streaming", value)?.apply() }
    fun updateOfficialEngineShowReasoning(value: Boolean) {
        officialEngineShowReasoning = value
        settingsPreferences?.edit()?.putBoolean("official_engine_show_reasoning", value)?.apply()
    }
    fun updateOfficialEngineExpandActivities(value: Boolean) {
        officialEngineExpandActivities = value
        settingsPreferences?.edit()?.putBoolean("official_engine_expand_activities", value)?.apply()
    }
    fun updateDshVersionChannel(value: String) {
        dshVersionChannel = SettingsContract.dshVersionChannel(value)
        settingsPreferences?.edit()?.putString("dsh_version_channel", dshVersionChannel)?.apply()
    }
    fun updateDshUpdatePolicy(value: String) {
        dshUpdatePolicy = SettingsContract.updatePolicy(value)
        settingsRuntimeStore?.setDshUpdatePolicy(dshUpdatePolicy)
            ?: settingsPreferences?.edit()?.putString(SettingsRuntimeStore.KEY_DSH_UPDATE_POLICY, dshUpdatePolicy)?.apply()
    }

    private fun applyModelProviderSettings(settings: ModelProviderSettings) {
        modelProviderOrder = settings.order
        modelProviderEnabled = settings.enabledById
    }

    fun updateAriesGroupId(value: String?) {
        ariesGroupId = value?.trim()?.takeIf { it.isNotEmpty() && it.length <= 256 && it.none(Char::isISOControl) }
        settingsPreferences?.edit()?.apply {
            if (ariesGroupId.isNullOrBlank()) remove("aries_group_id") else putString("aries_group_id", ariesGroupId)
        }?.apply()
    }

    fun updateAriesEndpoint(value: String) {
        val endpoint = SettingsContract.normalizeAriesEndpoint(value).orEmpty()
        val changed = endpoint != ariesEndpoint
        ariesEndpoint = endpoint
        if (changed || ariesEndpoint.isBlank()) {
            ariesNodeId = null
            ariesServerCode = null
            ariesDeviceId = null
            ariesGroupId = null
            ariesLastCheckedAtEpochMillis = null
            ariesConnectionState = OnlineConnectionState.UNCONFIGURED
        }
        settingsPreferences?.edit()?.apply {
            putString("aries_endpoint", ariesEndpoint)
            putString("aries_connection_state", ariesConnectionState.id)
            if (changed || ariesEndpoint.isBlank()) {
                remove("aries_node_id")
                remove("aries_server_code")
                remove("aries_device_id")
                remove("aries_group_id")
                remove("aries_last_checked_at")
            }
        }?.apply()
    }

    fun recordAriesConnection(snapshot: AriesClient.ConnectionSnapshot) {
        val endpoint = SettingsContract.normalizeAriesEndpoint(snapshot.endpoint).orEmpty()
        val changed = endpoint != ariesEndpoint ||
            (snapshot.node != null && ariesNodeId != null && ariesNodeId != snapshot.node.nodeId) ||
            (snapshot.deviceId != null && ariesDeviceId != null && ariesDeviceId != snapshot.deviceId)
        if (changed) ariesGroupId = null
        ariesEndpoint = endpoint
        ariesConnectionState = when (snapshot.state) {
            AriesClient.ConnectionState.PREVIEW -> OnlineConnectionState.UNCONFIGURED
            AriesClient.ConnectionState.OFFLINE -> OnlineConnectionState.OFFLINE
            AriesClient.ConnectionState.HEALTHY -> OnlineConnectionState.HEALTHY
            AriesClient.ConnectionState.UNREGISTERED -> OnlineConnectionState.UNREGISTERED
            AriesClient.ConnectionState.REGISTERED -> OnlineConnectionState.REGISTERED
        }
        // A failed observation has no new identity; retain the confirmed relationship offline.
        if (changed) { ariesNodeId = null; ariesServerCode = null; ariesDeviceId = null }
        snapshot.node?.let { ariesNodeId = it.nodeId; ariesServerCode = it.serverCode }
        snapshot.deviceId?.let { ariesDeviceId = it }
        ariesLastCheckedAtEpochMillis = snapshot.checkedAtEpochMillis
        settingsPreferences?.edit()?.apply {
            if (changed) remove("aries_group_id")
            putString("aries_endpoint", ariesEndpoint)
            putString("aries_connection_state", ariesConnectionState.id)
            putLong("aries_last_checked_at", ariesLastCheckedAtEpochMillis ?: 0L)
            if (ariesNodeId.isNullOrBlank()) remove("aries_node_id") else putString("aries_node_id", ariesNodeId)
            if (ariesServerCode.isNullOrBlank()) remove("aries_server_code") else putString("aries_server_code", ariesServerCode)
            if (ariesDeviceId.isNullOrBlank()) remove("aries_device_id") else putString("aries_device_id", ariesDeviceId)
        }?.apply()
    }

    fun clearAriesConnection() {
        ariesEndpoint = ""
        ariesNodeId = null
        ariesServerCode = null
        ariesDeviceId = null
        ariesGroupId = null
        ariesConnectionState = OnlineConnectionState.UNCONFIGURED
        ariesLastCheckedAtEpochMillis = null
        settingsPreferences?.edit()?.apply {
            remove("aries_endpoint")
            remove("aries_node_id")
            remove("aries_server_code")
            remove("aries_device_id")
            remove("aries_group_id")
            remove("aries_connection_state")
            remove("aries_last_checked_at")
        }?.apply()
    }

    fun settingsSnapshot(): SettingsSnapshot = SettingsSnapshot(
        themeMode = SettingsContract.themeMode(themeMode),
        reduceTransparency = reduceTransparency,
        reduceMotion = reduceMotion,
        notificationsEnabled = notificationsEnabled,
        keepRunning = keepRunning,
        downloadSource = SettingsContract.downloadSource(downloadSource),
        updatePolicy = SettingsContract.updatePolicy(updatePolicy),
        updateWifiOnly = updateWifiOnly,
        dshVersionChannel = SettingsContract.dshVersionChannel(dshVersionChannel),
        dshUpdatePolicy = SettingsContract.updatePolicy(dshUpdatePolicy),
        tokenSavingMode = tokenSavingMode,
        powerSavingMode = powerSavingMode,
        officialEngineExpandActivities = officialEngineExpandActivities,
        aries = AriesConnectionSettings(
            endpoint = ariesEndpoint,
            nodeId = ariesNodeId,
            serverCode = ariesServerCode,
            deviceId = ariesDeviceId,
            state = ariesConnectionState,
            lastCheckedAtEpochMillis = ariesLastCheckedAtEpochMillis,
        ),
    )
    fun initializeWorkspaces(context: Context) = initializeWorkspaces(context, LinuxRuntimeManager(context))

    /** Shared runtime ownership also permits isolated profiles to exercise the real lifecycle. */
    internal fun initializeWorkspaces(context: Context, runtime: LinuxRuntimeManager,
        installer: DshRuntimeInstaller = DshRuntimeInstaller(context, runtime)) {
        if (initializationStarted) return
        loadSettings(context)
        applicationContext = context.applicationContext
        attachmentStore = ConversationAttachmentStore(context.applicationContext)
        officialOutputStore = OfficialToolOutputStore(context.noBackupFilesDir)
        val balanceStore = BalanceStore(context.applicationContext)
        deepSeekBalanceStore = balanceStore
        modelProviderStore = ModelProviderStore(context.applicationContext)
        val sharedBridge = DshSharedPersistenceBridge(context.applicationContext, runtime, installer)
        sharedDshConfiguration = DshSharedConfigurationAdapter(context.applicationContext, requireNotNull(modelProviderStore), sharedBridge)
        localDshHistory = LocalDshHistoryController(context.applicationContext, sharedBridge)
        nativeTranscriptStore = NativeTranscriptStore(context.applicationContext)
        modelRuntime = ModelRuntimeController.get(context.applicationContext)
        notificationMonitor = runCatching { NativeNotificationMonitor(context.applicationContext, viewModelScope) }
            .onFailure { updateNotice("通知记录无法读取，已保留原文件，请在设置中检查") }.getOrNull()
        notificationMonitor?.reconcile(AndroidAppVisibility.isForeground, powerSavingMode)
        connectPluginHost = ConnectPluginHost.get(context.applicationContext)
        remoteDsh = RemoteDshApplicationController(context.applicationContext, requireNotNull(connectPluginHost))
        remoteArtifactDownloads = RemoteArtifactDownloadController(context.applicationContext, requireNotNull(remoteDsh))
        remoteAttachmentComposer = RemoteAttachmentComposer(context.applicationContext, requireNotNull(remoteDsh), requireNotNull(connectPluginHost))
        viewModelScope.launch { remoteAttachmentComposer?.state?.collect { remoteAttachmentProgress = it } }
        viewModelScope.launch {
            remoteDsh?.state?.collect { state ->
                remoteDshState = state
                remoteArtifactDownloads?.onRelationship(state.projection?.binding?.takeIf { binding ->
                    state.connection?.let { runCatching { binding.requireCurrent(it) }.isSuccess } == true
                })
                remoteAttachmentComposer?.onRelationship(state.projection?.binding?.takeIf { binding ->
                    state.connection?.let { runCatching { binding.requireCurrent(it) }.isSuccess } == true
                })
                remoteWorkspaceRemovalConfirmation?.let { confirmation ->
                    val connection = state.connection
                    if (connection == null || state.projection?.workspace?.scope != confirmation.binding ||
                        runCatching { confirmation.binding.requireCurrent(connection) }.isFailure)
                        remoteWorkspaceRemovalConfirmation = null
                }
            }
        }
        remoteDsh?.setActive(connectionMode == "online")
        modelRuntimeSnapshotJob = viewModelScope.launch {
            modelRuntime?.state?.collect { state ->
                if (connectionMode == "local") {
                    modelProviderOrder = state.providers.providers.map { it.id }
                    modelProviderEnabled = state.providers.providers.associate { it.id to it.enabled }
                    val previous = selectedModel
                    modelChoices.removeAll { it.runtimeModel != null }
                    val providers = state.catalog.associateBy { it.id }
                    state.providers.providers.filter { it.enabled && !it.protected }.forEach { config ->
                        providers[config.id]?.models?.forEach { model ->
                            modelChoices += ModelChoice("${config.id}::${model.id}", config.name, model.name,
                                model.reasoningOptions.map { ReasoningOption(it, it) },
                                model.reasoningOptions.indexOf(model.defaultReasoning), isExample = false,
                                inputModalities = model.input, runtimeModel = model)
                        }
                    }
                    if (previous.runtimeModel != null) {
                        val current = modelChoices.firstOrNull { it.id == previous.id }
                        if (current != null) {
                            val reasoning = selectedReasoningOption?.id
                            selectedModel = current
                            selectedReasoningIndex = current.reasoningOptions.indexOfFirst { it.id == reasoning }
                                .takeIf { it >= 0 } ?: current.defaultReasoningIndex
                        } else if (!isResponding) {
                            selectedModel = unconfiguredModel
                            selectedReasoningIndex = -1
                        }
                    } else if (previous.id == "unconfigured") {
                        val remembered = settingsPreferences?.getString("selected_model", null)
                        modelChoices.firstOrNull { it.id == remembered && it.runtimeModel != null }?.let { selectModel(it.id) }
                    }
                    scheduleLocalProviderSynchronization(state.providers.revision)
                }
            }
        }
        deepSeekClient = DeepSeekChatClient(balanceStore)
        catalogClient = DeepSeekChatClient(balanceStore)
        pushSettings = PushSettingsController(context.applicationContext)
        viewModelScope.launch { pushSettings?.state?.collect { pushSettingsState = it } }
        dshRuntimeManager = DshRuntimeManager(context.applicationContext, runtime, installer)
        dshInstallation = DshInstallationController(context.applicationContext)
        dshSnapshotJob = viewModelScope.launch {
            dshRuntimeManager?.snapshot?.collect { snapshot ->
                runtimeStatus = when (snapshot.status) {
                    DshServiceStatus.STARTING -> RuntimeStatus.Starting
                    DshServiceStatus.RUNNING -> RuntimeStatus.Running
                    DshServiceStatus.STOPPING -> RuntimeStatus.Stopping
                    DshServiceStatus.STOPPED, DshServiceStatus.ERROR -> RuntimeStatus.Stopped
                }
                runtimeProgress = when (snapshot.status) {
                    DshServiceStatus.RUNNING -> 100
                    DshServiceStatus.STARTING -> 0
                    DshServiceStatus.STOPPING, DshServiceStatus.STOPPED, DshServiceStatus.ERROR -> 0
                }
                runtimeStep = snapshot.detail
                runtimePort = snapshot.port
                runtimeAccessUrl = snapshot.accessUrl
                runtimeVersion = snapshot.version
                runtimeLastError = snapshot.lastError
            }
        }
        viewModelScope.launch { runCatching { dshRuntimeManager?.refresh() } }
        initializationStarted = true
        val store = WorkspaceStore(context.applicationContext)
        workspaceStore = store
        workspaceRootPath = store.root.absolutePath
        viewModelScope.launch {
            try {
                backgroundTasks = BackgroundTaskEngine.open(
                    BackgroundTaskAndroidStore(context.applicationContext),
                    runner = BackgroundTaskRunner { request, emit ->
                        val selected = modelChoices.firstOrNull { it.id == request.model && !it.isExample }
                            ?: error("model_unavailable")
                        if (selected.runtimeModel != null) return@BackgroundTaskRunner runMultiProviderBackgroundTask(request, selected, emit)
                        val generation = credentialGeneration
                        check(!taskCredentialBoundary.blocked && !deepSeekConfigurationBusy && deepSeekConfigured) { "configuration_changed" }
                        val client = DeepSeekChatClient(balanceStore)
                        val reducer = AssistantStreamReducer(source = AssistantStreamSource.BACKEND)
                        var lastProgressAt = 0L
                        try {
                            withContext(Dispatchers.Main.immediate) {
                                check(generation == credentialGeneration && !taskCredentialBoundary.blocked &&
                                    !deepSeekConfigurationBusy && deepSeekConfigured) { "configuration_changed" }
                                taskClients.add(client)
                            }
                            val taskModel = modelChoices.first { it.id == request.model && !it.isExample }
                            val taskReasoning = officialReasoning(taskModel,
                                if (selectedModel.id == taskModel.id) selectedReasoningOption else null)
                            val dispatcher = delegatedTaskDispatcher(request, request.model)
                            OfficialToolSession.generate(client, DeepSeekChatClient.Request(
                                request.model, listOf(
                                    delegatedTaskSystemMessage(),
                                    DeepSeekChatClient.HistoryMessage("user", request.prompt)),
                                thinking = taskReasoning.thinking, streaming = officialEngineStreaming,
                                reasoningEffort = taskReasoning.reasoningEffort,
                                modelCapabilities = taskModel.officialCapabilities,
                            ), dispatcher, onOutput = {}, onEvent = { event ->
                                check(generation == credentialGeneration) { "configuration_changed" }
                                val snapshot = reducer.apply(event).snapshot
                                val now = android.os.SystemClock.elapsedRealtime()
                                if (now - lastProgressAt >= 100L) {
                                    lastProgressAt = now
                                    val progress = snapshot.messages.flatMap { it.blocks }
                                        .filterIsInstance<AssistantContentBlockSnapshot.Text>()
                                        .joinToString("") { it.text }.takeLast(4096)
                                    withContext(Dispatchers.Main.immediate) {
                                        check(generation == credentialGeneration) { "configuration_changed" }
                                        emit(progress)
                                    }
                                }
                            })
                            val snapshot = reducer.finish()
                            check(generation == credentialGeneration) { "configuration_changed" }
                            check(snapshot.messages.isNotEmpty() &&
                                snapshot.messages.all { it.status == AssistantMessageStatus.COMPLETED }) { "task_incomplete" }
                            val result = snapshot.messages.flatMap { it.blocks }
                                .filterIsInstance<AssistantContentBlockSnapshot.Text>().joinToString("") { it.text }
                            check(result.isNotBlank()) { "task_empty" }
                            BackgroundTaskResult(result)
                        } finally {
                            try {
                                client.cancel()
                            } finally {
                                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) { taskClients.remove(client) }
                            }
                        }
                    },
                    availability = { model ->
                        val choice = modelChoices.firstOrNull { it.id == model && !it.isExample }
                        val providerId = choice?.runtimeModel?.providerId
                        when {
                            connectionMode == "online" -> BackgroundTaskAvailability(false, "在线任务由电脑 DSH 调度，请在电脑会话中创建")
                            choice == null -> BackgroundTaskAvailability(false, "请先获取可用模型")
                            providerId != null && !isModelProviderEnabled(providerId) -> BackgroundTaskAvailability(false, "请启用该模型提供商")
                            providerId != null && modelRuntime?.state?.value?.catalog?.firstOrNull { it.id == providerId }?.configured != true ->
                                BackgroundTaskAvailability(false, "请完成该提供商的认证")
                            providerId != null -> BackgroundTaskAvailability(true)
                            taskCredentialBoundary.blocked -> BackgroundTaskAvailability(false, "任务停止未完成，请重新保存或清除 API Key")
                            !deepSeekConfigured || deepSeekConfigurationBusy -> BackgroundTaskAvailability(false, "请先配置 DeepSeek API Key")
                            !isModelProviderEnabled("deepseek") -> BackgroundTaskAvailability(false, "请启用 DeepSeek 提供商")
                            modelChoices.none { it.id == model && !it.isExample } -> BackgroundTaskAvailability(false, "请先获取可用模型")
                            else -> BackgroundTaskAvailability(true)
                        }
                    },
                    dispatcher = Dispatchers.Main.immediate,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                deletionProtectionLoadError = "后台任务存储读取失败，无法确认工作区是否为空"
                updateNotice("后台任务存储读取失败，已保留原文件")
            }
            val restored = try {
                withContext(Dispatchers.IO) { persistenceMutex.withLock { store.initialize() } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Preserve an unreadable existing index instead of silently overwriting history.
                workspaceStore = null
                updateNotice("本地工作目录读取失败，本次暂不保存：${error.message ?: "请重试"}")
                null
            }
            if (workspaceStore != null && withContext(Dispatchers.IO) { store.hasRecovery() }) {
                workspaceSaveError = "已恢复本地恢复文件；请释放索引容量后重试保存，暂不发送新消息"
                updateNotice(requireNotNull(workspaceSaveError))
            }
            deepSeekConfigured = withContext(Dispatchers.IO) {
                balanceStore.hasKey()
            }
            if (deepSeekConfigured) restoreModelCatalog()
            notifyTaskConfiguration()
            val loadedAttachments = withContext(Dispatchers.IO) {
                runCatching { checkNotNull(attachmentStore).list().map { previewAttachment(it, includeText = false) } }
            }
            loadedAttachments.onSuccess { importedAttachments.putAll(it.associateBy { it.id }) }
                .onFailure {
                    deletionProtectionLoadError = "附件存储读取失败，无法确认工作区是否为空，已保留原文件"
                }
            val loadedOutputs = withContext(Dispatchers.IO) {
                runCatching { checkNotNull(officialOutputStore).list() }
            }
            loadedOutputs.onSuccess { officialOutputs.putAll(it.associateBy { it.id }) }
                .onFailure {
                    deletionProtectionLoadError = "输出存储读取失败，无法确认工作区是否为空，已保留原文件"
                }
            if (restored != null) {
                workspaces.clear()
                workspaces.addAll(restored.workspaces)
                conversations.clear()
                conversations.addAll(restored.conversations)
                conversationMessages.clear()
                restored.messages.forEach { (id, values) ->
                    conversationMessages[id] = mutableStateListOf<ChatMessage>().apply { addAll(values) }
                }
                selectedWorkspaceId = restored.selectedWorkspaceId.takeIf { id -> workspaces.any { it.id == id } }
                    ?: workspaces.firstOrNull()?.id.orEmpty()
                selectedConversationId = restored.selectedConversationId.takeIf { id ->
                    conversations.any {
                        it.id == id && it.workspaceId == selectedWorkspaceId && it.archivedAtMillis == null
                    }
                } ?: conversations.firstOrNull {
                    it.workspaceId == selectedWorkspaceId && it.archivedAtMillis == null
                }?.id.orEmpty()
                nextMessageId = (restored.messages.values.flatten().maxOfOrNull { it.id } ?: 0L) + 1L
            }
            try {
                val pending = withContext(Dispatchers.IO) { localDshHistory?.pendingPermanentDeletions().orEmpty() }
                localArchivePendingIds.clear()
                localArchivePendingIds.addAll(pending.filter { it.phase in setOf("deleting", "native_deleted") }
                    .map { it.conversationId }.distinct())
                localArchiveRecoveryFailed = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                localArchiveRecoveryFailed = true
                localArchiveDeletionDetail = "永久删除记录读取失败；已保留原历史，请在归档页刷新后重试"
            }
            workspaceInitialized = true
            if (selectedConversationId.isBlank() || conversations.none {
                    it.id == selectedConversationId && it.archivedAtMillis == null
                }) {
                if (createEmptyConversation(selectedWorkspaceId, enforceActiveLimit = false) == null) {
                    updateNotice("没有可用的未归档对话，且本地会话已达到存储上限")
                }
            }
            persistWorkspaces()
            deletionProtectionLoadError?.let { updateNotice(it) }
            purgeExpiredArchives()
        }
    }

    /**
     * Atomically applies a validated Android backup to the in-memory repository.
     *
     * This API deliberately accepts only [AndroidBackupStore.ValidatedBackup]. It
     * never restores external workspace URI references, attachments, credentials,
     * or device identifiers because none of those values exist in the backup model.
     * The repository must already be initialized so the existing workspace and
     * settings persistence paths are available.
     */
    fun restoreBackup(backup: AndroidBackupStore.ValidatedBackup): Result<Unit> {
        val store = workspaceStore
        val prefs = settingsPreferences
        if (!workspaceInitialized || store == null || prefs == null || workspaceDeletionBusy) {
            return Result.failure(IllegalStateException("本地工作区尚未初始化，暂时无法恢复备份"))
        }

        val prepared = try {
            validateBackupForRestore(backup)
            val restoredWorkspaces = backup.workspaces.map { workspace ->
                // BackupWorkspace intentionally has no URI field: restored workspaces
                // are application-owned entries and never reattach an external tree.
                Workspace(workspace.id, workspace.name)
            }
            val restoredConversations = backup.conversations.map { conversation ->
                Conversation(
                    conversation.id,
                    conversation.title,
                    conversation.workspaceId,
                    conversation.archivedAtMillis,
                    conversation.archiveOrigin?.let { origin ->
                        val restoredOrigin = Workspace(origin.workspaceId, origin.workspaceName)
                        ConversationArchiveOrigin(restoredOrigin, origin.workspaceOrder, origin.conversationOrder,
                            store.workspaceDirectoryPaths(restoredOrigin))
                    },
                )
            }
            val restoredMessages = backup.messages.mapValues { (_, messages) ->
                messages.map { message -> ChatMessage(message.id, message.role, message.text, message.streamSnapshot, message.modelInput) }
            }
            val nextId = backup.messages.values.asSequence()
                .flatMap { it.asSequence() }
                .maxOfOrNull { it.id }
                ?.let { maxId ->
                    if (maxId == Long.MAX_VALUE) {
                        throw IllegalArgumentException("备份消息 id 已达到上限")
                    }
                    maxId + 1L
                }
                ?: 1L
            PreparedBackupRestore(
                workspaces = restoredWorkspaces,
                conversations = restoredConversations,
                messages = restoredMessages,
                nextMessageId = nextId,
            )
        } catch (error: Exception) {
            return Result.failure(error)
        }

        // Everything below has been prepared and checked before any current state
        // is touched. Keep a memory snapshot so an unexpected local mutation error
        // cannot leave a partially restored repository behind.
        val previousWorkspaces = workspaces.toList()
        val previousConversations = conversations.toList()
        val previousMessages = conversationMessages.mapValues { it.value.toList() }
        val previousSelectedWorkspaceId = selectedWorkspaceId
        val previousSelectedConversationId = selectedConversationId
        val previousThemeMode = themeMode
        val previousReduceTransparency = reduceTransparency
        val previousReduceMotion = reduceMotion
        val previousNotificationsEnabled = notificationsEnabled
        val previousKeepRunning = keepRunning
        val previousDownloadSource = downloadSource
        val previousNextMessageId = nextMessageId
        val previousComposerGeneration = composerGeneration
        val previousLiteAttachments = liteAttachments.toList()
        val previousDshAttachments = dshAttachments.toList()

        return try {
            cancelResponse()
            liteAttachments.clear()
            dshAttachments.clear()
            workspaces.clear()
            workspaces.addAll(prepared.workspaces)
            conversations.clear()
            conversations.addAll(prepared.conversations)
            conversationMessages.clear()
            prepared.messages.forEach { (conversationId, messages) ->
                conversationMessages[conversationId] = mutableStateListOf<ChatMessage>().apply {
                    addAll(messages)
                }
            }
            selectedWorkspaceId = backup.selectedWorkspaceId
            selectedConversationId = backup.selectedConversationId.takeIf { id ->
                prepared.conversations.any {
                    it.id == id && it.workspaceId == selectedWorkspaceId && it.archivedAtMillis == null
                }
            } ?: prepared.conversations.firstOrNull {
                it.workspaceId == selectedWorkspaceId && it.archivedAtMillis == null
            }?.id.orEmpty()
            if (selectedConversationId.isBlank() &&
                createEmptyConversation(selectedWorkspaceId, enforceActiveLimit = false) == null) {
                throw IllegalStateException("无法为恢复的备份创建当前对话")
            }
            themeMode = backup.settings.themeMode
            reduceTransparency = backup.settings.reduceTransparency
            reduceMotion = backup.settings.reduceMotion
            notificationsEnabled = backup.settings.notificationsEnabled
            keepRunning = backup.settings.keepRunning
            downloadSource = backup.settings.downloadSource
            prefs.edit()
                .putString("theme_mode", themeMode)
                .putBoolean("reduce_transparency", reduceTransparency)
                .putBoolean("reduce_motion", reduceMotion)
                .putBoolean("notifications_enabled", notificationsEnabled)
                .putBoolean("keep_running", keepRunning)
                .putString("download_source", downloadSource)
                .apply()
            nextMessageId = prepared.nextMessageId
            composerGeneration++
            persistWorkspaces()
            updateNotice("已恢复 Android 备份")
            Result.success(Unit)
        } catch (error: Exception) {
            workspaces.clear()
            workspaces.addAll(previousWorkspaces)
            conversations.clear()
            conversations.addAll(previousConversations)
            conversationMessages.clear()
            previousMessages.forEach { (conversationId, messages) ->
                conversationMessages[conversationId] = mutableStateListOf<ChatMessage>().apply {
                    addAll(messages)
                }
            }
            selectedWorkspaceId = previousSelectedWorkspaceId
            selectedConversationId = previousSelectedConversationId
            themeMode = previousThemeMode
            reduceTransparency = previousReduceTransparency
            reduceMotion = previousReduceMotion
            notificationsEnabled = previousNotificationsEnabled
            keepRunning = previousKeepRunning
            downloadSource = previousDownloadSource
            nextMessageId = previousNextMessageId
            composerGeneration = previousComposerGeneration
            liteAttachments.clear()
            liteAttachments.addAll(previousLiteAttachments)
            dshAttachments.clear()
            dshAttachments.addAll(previousDshAttachments)
            prefs.edit()
                .putString("theme_mode", previousThemeMode)
                .putBoolean("reduce_transparency", previousReduceTransparency)
                .putBoolean("reduce_motion", previousReduceMotion)
                .putBoolean("notifications_enabled", previousNotificationsEnabled)
                .putBoolean("keep_running", previousKeepRunning)
                .putString("download_source", previousDownloadSource)
                .apply()
            Result.failure(error)
        }
    }

    private data class PreparedBackupRestore(
        val workspaces: List<Workspace>,
        val conversations: List<Conversation>,
        val messages: Map<String, List<ChatMessage>>,
        val nextMessageId: Long,
    )

    private fun validateBackupForRestore(backup: AndroidBackupStore.ValidatedBackup) {
        check(backup.schema == AndroidBackupStore.SCHEMA) { "不支持的备份类型" }
        check(backup.schemaVersion in 1..AndroidBackupStore.SCHEMA_VERSION) { "不支持的备份版本" }
        check(backup.workspaces.isNotEmpty()) { "备份至少需要一个工作区" }

        val workspaceIds = backup.workspaces.map { it.id }
        check(workspaceIds.size == workspaceIds.toSet().size) { "备份工作区 id 重复" }
        val workspaceIdSet = workspaceIds.toSet()
        check(backup.selectedWorkspaceId in workspaceIdSet) { "备份选中的工作区不存在" }

        val conversationIds = backup.conversations.map { it.id }
        check(conversationIds.size == conversationIds.toSet().size) { "备份会话 id 重复" }
        backup.conversations.forEach { conversation ->
            check(conversation.workspaceId in workspaceIdSet || (conversation.archivedAtMillis != null &&
                conversation.archiveOrigin?.workspaceId == conversation.workspaceId)) { "备份会话引用了不存在的工作区" }
            check(conversation.archivedAtMillis == null || conversation.archivedAtMillis >= 0L) {
                "备份归档时间无效"
            }
        }
        val conversationIdSet = conversationIds.toSet()
        if (backup.selectedConversationId.isNotEmpty()) {
            val selected = backup.conversations.firstOrNull { it.id == backup.selectedConversationId }
                ?: throw IllegalArgumentException("备份选中的会话不存在")
            check(selected.workspaceId == backup.selectedWorkspaceId) {
                "备份选中的会话不属于选中的工作区"
            }
        }
        check(backup.messages.keys == conversationIdSet) { "备份消息与会话不匹配" }

        val messageIds = HashSet<Long>()
        backup.messages.values.flatten().forEach { message ->
            check(message.id >= 0L) { "备份消息 id 无效" }
            check(message.role == "user" || message.role == "assistant") { "备份消息角色无效" }
            check(messageIds.add(message.id)) { "备份消息 id 重复" }
        }
        check(backup.settings.themeMode in setOf("system", "light", "dark")) { "备份主题模式无效" }
        check(backup.settings.downloadSource in setOf("official", "cdn", "backend")) {
            "备份下载源无效"
        }
    }
    fun selectWorkspace(id: String) {
        if (connectionMode == "online") {
            clearAttachments()
            remoteOperation { remoteDsh?.selectWorkspace(id); composerGeneration++ }
            return
        }
        if (!workspaceInitialized || workspaceDeletionBusy || workspaces.none { it.id == id } || id == selectedWorkspaceId) return
        val conversation = conversations.firstOrNull { it.workspaceId == id && it.archivedAtMillis == null }
        if (conversation == null && conversations.size >= MAX_WORKSPACE_CONVERSATIONS) {
            updateNotice("本地会话（含归档）已达到上限，暂时无法打开空工作目录")
            return
        }
        cancelResponse()
        clearAttachments()
        selectedWorkspaceId = id
        if (conversation == null) {
            if (createEmptyConversation(id) != null) {
                updateNotice("已在${selectedWorkspace.name}新建对话")
                persistWorkspaces()
            }
        } else {
            selectedConversationId = conversation.id
            composerGeneration++
            persistWorkspaces()
        }
    }

    /** UI should publish its initial draft and every edit/clear, including whitespace-only text. */
    fun updateComposerDraft(text: String) {
        composerDraftText = text
    }

    /** Compose query: reads memory only, with no disk access and no notice mutation. */
    fun canDeleteWorkspace(id: String): Boolean {
        return !workspaceDeletionBusy && canDeleteWorkspaceState(id)
    }

    fun canRemoveWorkspace(id: String): Boolean {
        if (connectionMode == "online") return remoteWorkspaceLifecycleReady && !workspaceDeletionBusy &&
            !remoteDshState.busy && !sendPending && attachmentImportCount == 0 &&
            remoteDshState.projection?.workspace?.workspaces?.any { it.id == id } == true
        return canRemoveWorkspaceRecord(
            id, workspaces, workspaceInitialized, workspaceDeletionBusy || sendPending || attachmentImportCount > 0,
            workspaceStore != null && workspaceSaveError == null && deletionProtectionLoadError == null &&
                backgroundTasks != null && backgroundTasks?.storageIssue?.value == null,
        )
    }

    private fun activeWorkspaceTasks(id: String): List<BackgroundTask> {
        val conversationIds = conversations.filter { it.workspaceId == id }.mapTo(HashSet()) { it.id }
        return backgroundTasks?.tasks?.value.orEmpty().filter {
            it.parentConversationId in conversationIds && it.status in setOf(
                BackgroundTaskStatus.QUEUED, BackgroundTaskStatus.RUNNING, BackgroundTaskStatus.STOPPING,
            )
        }
    }

    private fun hasWorkspaceResponse(id: String): Boolean = responseJob?.isCompleted == false &&
        conversations.any { it.workspaceId == id && it.id == activeResponseConversationId }

    fun workspaceRemovalDetails(id: String): WorkspaceRemovalDetails? {
        if (connectionMode == "online") {
            val confirmation = remoteWorkspaceRemovalConfirmation ?: return null
            val connection = remoteDshState.connection ?: return null
            val snapshot = remoteDshState.projection?.workspace ?: return null
            if (runCatching { confirmation.requireCurrent(connection, snapshot, id) }.isFailure) return null
            return confirmation.details(remoteExecutionDeviceLabel())
        }
        val workspace = workspaces.firstOrNull { it.id == id } ?: return null
        val store = workspaceStore ?: return null
        return WorkspaceRemovalDetails(workspace, "本机 · ${android.os.Build.MODEL}",
            store.workspaceDirectoryPaths(workspace), hasRunningTasks = hasWorkspaceResponse(id) ||
                activeWorkspaceTasks(id).isNotEmpty(), canDeleteOwnedFiles = connectionMode == "local")
    }

    /** Fetch task state and the computer's file identity before enabling a destructive confirmation. */
    suspend fun prepareWorkspaceRemovalDetails(id: String): WorkspaceRemovalDetails? {
        remoteWorkspaceRemovalConfirmation = null
        if (connectionMode != "online") return workspaceRemovalDetails(id)
        check(canRemoveWorkspace(id)) { "电脑工作区尚未就绪，请稍后重试" }
        val binding = remoteWorkspaceScope ?: error("电脑配对关系尚未确认")
        val removal = requireNotNull(remoteDsh).prepareWorkspaceRemoval(id, expectedScope = binding)
        check(connectionMode == "online") { "执行模式已改变，请重新确认" }
        val confirmation = RemoteWorkspaceRemovalConfirmation(binding, removal)
        confirmation.requireCurrent(requireNotNull(remoteDshState.connection),
            requireNotNull(remoteDshState.projection?.workspace), id)
        remoteWorkspaceRemovalConfirmation = confirmation
        return confirmation.details(remoteExecutionDeviceLabel())
    }

    fun clearWorkspaceRemovalConfirmation() { remoteWorkspaceRemovalConfirmation = null }

    private fun remoteExecutionDeviceLabel(): String {
        val device = remoteDshState.connection?.optJSONObject("remote_device")
        val name = device?.optString("name")?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            ?: device?.optString("device_name")?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        return name?.let { "电脑 · $it" } ?: "电脑 · 对方尚未共享设备名称"
    }

    /** Acceptance is asynchronous; only a committed archive snapshot permits file removal. */
    fun requestRemoveWorkspace(id: String, deleteFiles: Boolean = false, stopRunningTasks: Boolean = false): Boolean {
        if (!canRemoveWorkspace(id)) {
            updateNotice("工作区正在保存或执行设备尚未就绪，请稍后重试")
            return false
        }
        if (connectionMode == "online") {
            val confirmation = remoteWorkspaceRemovalConfirmation ?: return false.also {
                updateNotice("请先重新读取电脑工作区状态")
            }
            val details = workspaceRemovalDetails(id) ?: return false.also {
                updateNotice("电脑工作区或配对关系已改变，请重新确认")
            }
            if (details.hasRunningTasks && !stopRunningTasks) {
                updateNotice("此电脑工作区有运行任务，请先确认停止任务")
                return false
            }
            if (deleteFiles && !details.canDeleteOwnedFiles) {
                updateNotice("电脑未确认目录身份，磁盘文件保留")
                return false
            }
            return requestRemoteLifecycleMutation(confirmation.binding) { controller, binding ->
                val removed = controller.removeWorkspace(confirmation.removal.ticket,
                    stopRunning = stopRunningTasks, expectedScope = binding)
                check(removed.workspace.path == confirmation.removal.workspace.path && removed.phase in REMOVED_PHASES) {
                    "电脑未返回匹配的工作区移除回执"
                }
                remoteWorkspaceRemovalConfirmation = null
                if (deleteFiles) {
                    val deleted = controller.deleteWorkspaceFiles(removed.ticket, confirmation.removal.workspace.path,
                        expectedScope = binding)
                    check(deleted.filesDeleted && deleted.phase == "files_deleted") { "电脑文件删除尚未确认" }
                    updateNotice("已移除电脑工作区并归档对话，已删除确认路径中的电脑文件")
                } else updateNotice("已移除电脑工作区并归档对话，电脑文件保留")
            }
        }
        val workspace = workspaces.first { it.id == id }
        val store = requireNotNull(workspaceStore)
        val active = activeWorkspaceTasks(id)
        if ((hasWorkspaceResponse(id) || active.isNotEmpty()) && !stopRunningTasks) {
            updateNotice("此工作区有运行任务，请先确认停止任务")
            return false
        }
        workspaceDeletionBusy = true
        viewModelScope.launch {
            try {
                updateNotice("正在停止任务并归档：${workspace.name}")
                withTimeout(30_000L) {
                    if (hasWorkspaceResponse(id)) {
                        val runningJob = responseJob
                        cancelResponse()
                        runningJob?.join()
                    }
                    val engine = requireNotNull(backgroundTasks)
                    active.forEach { engine.stop(it.id) }
                    active.forEach { engine.awaitTerminal(it.id) }
                }
                check(!hasWorkspaceResponse(id) && activeWorkspaceTasks(id).isEmpty()) { "任务未完全停止，文件已保留" }
                check(backgroundTasks?.storageIssue?.value == null) { "任务状态保存失败，文件已保留" }
                saveJob?.cancelAndJoin()
                saveJob = null
                check(workspaces.firstOrNull { it.id == id } == workspace) { "工作区已改变，请重新确认" }
                var prepared = prepareWorkspaceRemovalSnapshot(workspaceSnapshot(), id,
                    store.workspaceDirectoryPaths(workspace),
                    if (workspaces.size == 1) Workspace(UUID.randomUUID().toString(), "默认工作区") else null)
                if (prepared.selectedConversationId.isBlank()) {
                    check(prepared.conversations.size < MAX_WORKSPACE_CONVERSATIONS) { "对话存储已满，请先清理归档" }
                    val replacement = Conversation(UUID.randomUUID().toString(), "新对话", prepared.selectedWorkspaceId)
                    prepared = prepared.copy(conversations = prepared.conversations + replacement,
                        messages = prepared.messages + (replacement.id to emptyList()), selectedConversationId = replacement.id)
                }
                if (!commitWorkspace(prepared)) return@launch
                val removedWasSelected = selectedWorkspaceId == id
                workspaces.clear(); workspaces.addAll(prepared.workspaces)
                conversations.clear(); conversations.addAll(prepared.conversations)
                selectedWorkspaceId = prepared.selectedWorkspaceId
                selectedConversationId = prepared.selectedConversationId
                if (removedWasSelected) { clearAttachments(); composerDraftText = ""; composerGeneration++ }
                persistenceRevision++
                if (deleteFiles) {
                    check(activeWorkspaceTasks(id).isEmpty()) { "出现新运行任务，文件已保留" }
                    val result = withContext(Dispatchers.IO) {
                        persistenceMutex.withLock { store.deleteRemovedWorkspaceFiles(workspace) }
                    }
                    updateNotice(if (result.completed) "已移除工作区并归档对话；已删除应用内文件" +
                        if (result.preservedExternalUri != null) "，所选外部目录保留" else ""
                    else "工作区已归档；${result.error}")
                } else updateNotice("已移除工作区并归档对话，磁盘文件保留")
            } catch (cancelled: CancellationException) {
                if (cancelled is kotlinx.coroutines.TimeoutCancellationException) updateNotice("任务停止超时，未删除工作区或文件")
                else throw cancelled
            } catch (error: Exception) { updateNotice(error.message ?: "工作区移除失败，文件已保留") }
            finally { workspaceDeletionBusy = false; persistWorkspaces() }
        }
        return true
    }

    private fun canDeleteWorkspaceState(id: String): Boolean {
        if (!workspaceInitialized || workspaceStore == null || workspaces.isEmpty() ||
            sendPending || deletionProtectionLoadError != null) return false
        if (workspaces.none { it.id == id }) return false
        // Missing parent records prevent attributing surviving tasks/outputs to a workspace.
        val knownIds = conversations.mapTo(HashSet()) { it.id }
        if (backgroundTasks == null || backgroundTasks?.storageIssue?.value != null ||
            backgroundTasks?.tasks?.value?.any {
                it.parentConversationId == null || it.parentConversationId !in knownIds
            } == true || officialOutputs.values.any { it.conversationId !in knownIds }) return false
        if (selectedWorkspaceId == id && (composerDraftText == null || composerDraftText?.isNotEmpty() == true ||
                liteAttachments.isNotEmpty() || dshAttachments.isNotEmpty() ||
                attachmentImportCount > 0 || pendingAttachmentSelections.isNotEmpty())) return false
        // Inspect existing metadata lazily; do not load output bodies or copy their catalog.
        return conversations.asSequence().filter { it.workspaceId == id }
            .all { isAutoCreatedEmptyConversation(it, conversationDeleteFacts(it.id)) }
    }

    /**
     * True means the asynchronous request was accepted. State and notice report its outcome.
     * Rechecks memory after IO, then uses the existing revision-aware persistence writer.
     */
    fun deleteEmptyWorkspace(id: String): Boolean {
        if (workspaceDeletionBusy) {
            updateNotice("正在复核工作区删除，请稍候")
            return false
        }
        if (!canDeleteWorkspace(id)) {
            updateNotice(deletionProtectionLoadError ?: "工作区包含内容或保护状态尚未确认，未删除")
            return false
        }
        val workspace = workspaces.firstOrNull { it.id == id } ?: return false
        val store = workspaceStore ?: return false
        workspaceDeletionBusy = true
        updateNotice("正在复核空工作区：${workspace.name}")
        viewModelScope.launch {
            var freshWorkspace: Workspace? = null
            try {
                val folderEmpty = withContext(Dispatchers.IO) {
                    persistenceMutex.withLock { store.canDeleteInternalWorkspaceFolder(workspace) }
                }
                if (!folderEmpty || !canDeleteWorkspaceState(id) || workspaces.firstOrNull { it.id == id } != workspace) {
                    updateNotice("工作区内容已变更或内部目录非空，未删除")
                    return@launch
                }
                if (workspaces.size == 1) {
                    val fresh = Workspace(UUID.randomUUID().toString(), "默认工作区")
                    freshWorkspace = fresh
                    withContext(Dispatchers.IO) {
                        persistenceMutex.withLock { store.createInternalWorkspace(fresh.id) }
                    }
                    // Directory creation suspends: a draft/send/task may have arrived meanwhile.
                    if (!canDeleteWorkspaceState(id) || workspaces.firstOrNull { it.id == id } != workspace) {
                        updateNotice("工作区内容已变更，未删除")
                        return@launch
                    }
                }
                val wasSelected = workspaces.size == 1 || selectedWorkspaceId == id ||
                    conversations.any { it.id == selectedConversationId && it.workspaceId == id }
                val remainingWorkspaces = workspaces.filterNot { it.id == id }.ifEmpty {
                    listOf(requireNotNull(freshWorkspace))
                }
                val remainingConversations = conversations.filterNot { it.workspaceId == id }
                val replacementWorkspaceId = remainingWorkspaces.first().id
                val replacement = remainingConversations.firstOrNull {
                    it.workspaceId == replacementWorkspaceId && it.archivedAtMillis == null
                }
                if (wasSelected && replacement == null && remainingConversations.size >= MAX_WORKSPACE_CONVERSATIONS) {
                    updateNotice("本地会话已达到上限，无法创建替代对话，未删除工作区")
                    return@launch
                }
                if (conversations.any { it.id == activeResponseConversationId && it.workspaceId == id }) cancelResponse()
                val removedIds = conversations.filter { it.workspaceId == id }.map { it.id }.toSet()
                conversations.removeAll { it.id in removedIds }
                removedIds.forEach { conversationMessages.remove(it) }
                workspaces.removeAll { it.id == id }
                if (workspaces.isEmpty()) workspaces.addAll(remainingWorkspaces)
                if (wasSelected) {
                    clearAttachments()
                    val next = replacement ?: createEmptyConversation(replacementWorkspaceId, enforceActiveLimit = false)
                    if (next != null) activateConversation(next)
                }
                persistWorkspaces()
                updateNotice("空工作区已移除，正在保存：${workspace.name}")
                saveJob?.join()
                if (workspaceSaveError != null || sendPending || workspaces.any { it.id == id }) {
                    updateNotice(workspaceSaveError ?: "工作区状态再次变更，已保留内部目录")
                    return@launch
                }
                // Verify the durable selection after the writer drains; failure retains the folder.
                val cleaned = withContext(Dispatchers.IO) {
                    persistenceMutex.withLock {
                        store.deleteUnreferencedInternalWorkspaceFolder(workspace)
                    }
                }
                updateNotice(if (cleaned && workspaces.any { it.id == freshWorkspace?.id })
                    "已删除空工作区：${workspace.name}，已自动新建默认工作区"
                    else if (cleaned) "已删除空工作区：${workspace.name}"
                    else "工作区引用已移除，但内部目录尚未确认可清理，已保留文件")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                updateNotice("工作区删除未完成，已保留内部文件：${error.message ?: "请重试"}")
            } finally {
                freshWorkspace?.takeIf { fresh -> workspaces.none { it.id == fresh.id } }?.let { unused ->
                    withContext(NonCancellable + Dispatchers.IO) {
                        // This UUID directory was reserved by this request and never adopted.
                        runCatching { persistenceMutex.withLock { store.deleteInternalWorkspaceFolderIfEmpty(unused) } }
                    }
                }
                workspaceDeletionBusy = false
            }
        }
        return true
    }

    fun selectConversation(id: String) {
        if (connectionMode == "online") {
            clearAttachments()
            remoteOperation { remoteDsh?.selectSession(id); composerGeneration++ }
            return
        }
        if (!workspaceInitialized || workspaceDeletionBusy || id == selectedConversationId) return
        val conversation = conversations.firstOrNull { it.id == id && it.archivedAtMillis == null } ?: return
        cancelResponse()
        clearAttachments()
        selectedWorkspaceId = conversation.workspaceId
        selectedConversationId = id
        composerGeneration++
        persistWorkspaces()
    }

    private fun canEditSidebarMetadata(): Boolean = connectionMode == "local" && workspaceInitialized &&
        workspaceStore != null && workspaceSaveError == null && !workspaceDeletionBusy && !sendPending &&
        !isResponding && !localEngineSwitchBusy && !sidebarMetadataBusy

    fun canRenameWorkspace(id: String): Boolean = canEditSidebarMetadata() && workspaces.any { it.id == id }

    fun canRenameConversation(id: String): Boolean = canEditSidebarMetadata() &&
        conversations.any { it.id == id && it.archivedAtMillis == null }

    fun canPinConversation(id: String): Boolean = canRenameConversation(id)

    fun isConversationPinned(id: String): Boolean = connectionMode == "local" &&
        conversations.any { it.id == id && it.pinned }

    /** Accept display metadata only; names cannot rename a directory or change a workspace identity. */
    suspend fun renameWorkspace(id: String, name: String): Boolean {
        val normalized = name.trim()
        if (normalized.isBlank() || normalized.length > 80 || normalized.any(Char::isISOControl)) {
            updateNotice("工作区名称需为 1–80 个字符，不能包含控制字符")
            return false
        }
        if (!canRenameWorkspace(id)) return false
        return saveSidebarMetadata("工作区名称已保存") {
            val index = workspaces.indexOfFirst { it.id == id }
            if (index < 0) false else {
                workspaces[index] = workspaces[index].copy(name = normalized)
                true
            }
        }
    }

    suspend fun renameConversation(id: String, title: String): Boolean {
        val normalized = title.trim()
        if (normalized.isBlank() || normalized.length > 100 || normalized.any(Char::isISOControl)) {
            updateNotice("对话名称需为 1–100 个字符，不能包含控制字符")
            return false
        }
        if (!canRenameConversation(id)) return false
        return saveSidebarMetadata("对话名称已保存") {
            val index = conversations.indexOfFirst { it.id == id && it.archivedAtMillis == null }
            if (index < 0) false else {
                conversations[index] = conversations[index].copy(title = normalized, titleManuallySet = true)
                true
            }
        }
    }

    suspend fun setConversationPinned(id: String, pinned: Boolean): Boolean {
        if (!canPinConversation(id)) return false
        return saveSidebarMetadata(if (pinned) "对话已置顶" else "已取消置顶") {
            val index = conversations.indexOfFirst { it.id == id && it.archivedAtMillis == null }
            if (index < 0) false else {
                conversations[index] = conversations[index].copy(pinned = pinned)
                true
            }
        }
    }

    /** Use the real workspace writer, and report success only after the index commits. */
    private suspend fun saveSidebarMetadata(success: String, apply: () -> Boolean): Boolean =
        withContext(Dispatchers.Main.immediate) {
            if (!canEditSidebarMetadata()) return@withContext false
            sidebarMetadataBusy = true
            sendPending = true
            try {
                saveJob?.cancelAndJoin()
                saveJob = null
                if (!apply()) return@withContext false
                persistenceRevision++
                val committed = commitWorkspace(workspaceSnapshot())
                if (committed) updateNotice(success)
                committed
            } finally {
                sendPending = false
                sidebarMetadataBusy = false
                // Retain unsaved metadata in memory and use the existing recovery/error path.
                persistWorkspaces()
            }
        }

    /** Permanent deletion is confirmed separately from archive and commits only
     * after every linked DSH history has an actual durable deletion receipt. */
    fun deleteConversation(id: String): Boolean {
        if (connectionMode != "local" || !workspaceInitialized || workspaceStore == null ||
            localDshHistory == null || sendPending || workspaceDeletionBusy || localEngineSwitchBusy ||
            hasNativeRunningTasks()) {
            updateNotice("请等待本机任务和引擎操作结束后再永久删除归档")
            return false
        }
        if (conversations.none { it.id == id && it.archivedAtMillis != null }) {
            updateNotice("请先归档此对话，再确认永久删除")
            return false
        }
        return requestLocalArchiveDeletion(setOf(id), includePending = false)
    }

    /** Archive a conversation without dropping its messages. The active session is switched first. */
    fun archiveConversation(id: String): Boolean {
        if (connectionMode == "online") return requestArchiveRemoteConversation(id)
        if (!workspaceInitialized || workspaceDeletionBusy || sendPending) return false
        var index = conversations.indexOfFirst { it.id == id }
        if (index < 0) return false
        var conversation = conversations[index]
        if (conversation.archivedAtMillis != null) return false

        val isSelected = selectedConversationId == id
        var replacement = if (isSelected) {
            findUnarchivedConversation(excludingId = id, preferredWorkspaceId = conversation.workspaceId)
        } else null
        if (isSelected && replacement == null && conversations.size >= MAX_WORKSPACE_CONVERSATIONS) {
            // Expired archives may free the slot needed for a fresh selected session.
            purgeExpiredArchives()
            index = conversations.indexOfFirst { it.id == id }
            if (index < 0) return false
            conversation = conversations[index]
            replacement = findUnarchivedConversation(excludingId = id, preferredWorkspaceId = conversation.workspaceId)
            if (replacement == null && conversations.size >= MAX_WORKSPACE_CONVERSATIONS) {
                updateNotice("本地会话已达到存储上限，无法为当前对话创建替代会话")
                return false
            }
        }
        if (isSelected) cancelResponse()
        val sourceWorkspace = workspaces.first { it.id == conversation.workspaceId }
        conversations[index] = conversation.copy(archivedAtMillis = System.currentTimeMillis(),
            archiveOrigin = ConversationArchiveOrigin(sourceWorkspace, workspaces.indexOf(sourceWorkspace), index,
                workspaceStore?.workspaceDirectoryPaths(sourceWorkspace).orEmpty()))
        if (isSelected) {
            val next = replacement ?: createEmptyConversation(conversation.workspaceId)
            if (next == null) {
                // Keep selection and data coherent if the bounded store cannot add a replacement.
                conversations[index] = conversation
                persistWorkspaces()
                updateNotice("无法创建替代对话，未归档当前会话")
                return false
            }
            activateConversation(next)
        }
        persistWorkspaces()
        updateNotice("已归档对话：${conversation.title}")
        return true
    }

    fun canArchiveConversation(id: String): Boolean {
        if (connectionMode != "online") return workspaceInitialized && !workspaceDeletionBusy && !sendPending &&
            conversations.any { it.id == id && it.archivedAtMillis == null }
        return remoteWorkspaceLifecycleReady && !workspaceDeletionBusy && !remoteDshState.busy &&
            !remoteDshState.pendingPrompt && displayConversations.any { it.id == id }
    }

    fun remoteConversationArchiveRequiresStop(id: String): Boolean {
        if (connectionMode != "online") return false
        val projection = remoteDshState.projection ?: return true
        return projection.workspace.sessions.any { it.id == id && it.running } ||
            projection.sessions[id]?.activity?.runStatus == AssistantRunStatus.RUNNING ||
            projection.sessions[id]?.activity?.subagents?.any { it.status in setOf(
                AssistantStreamEvent.SubagentStatus.PENDING, AssistantStreamEvent.SubagentStatus.STARTING,
                AssistantStreamEvent.SubagentStatus.RUNNING) } == true
    }

    fun requestArchiveRemoteConversation(id: String, stopRunningTasks: Boolean = false,
        expectedScope: RemoteDshScope? = remoteWorkspaceScope): Boolean {
        if (!canArchiveConversation(id) || connectionMode != "online" || expectedScope == null ||
            expectedScope != remoteWorkspaceScope) return false
        if (remoteConversationArchiveRequiresStop(id) && !stopRunningTasks) {
            updateNotice("此电脑会话仍有运行任务，请先确认停止任务后归档")
            return false
        }
        return requestRemoteLifecycleMutation(expectedScope) { controller, binding ->
            controller.archiveSession(id, stopRunning = stopRunningTasks, expectedScope = binding)
            updateNotice("电脑会话已归档")
        }
    }

    fun remoteRemovedWorkspace(id: String): RemoteWorkspaceStore.Removal? =
        remoteDshState.projection?.workspace?.removals?.firstOrNull { it.workspace.id == id && it.phase in REMOVED_PHASES }

    fun canRestoreArchivedConversation(id: String): Boolean {
        if (connectionMode != "online") return workspaceInitialized && !workspaceDeletionBusy && !sendPending &&
            id !in localArchivePendingIds && archivedConversations.any { it.id == id }
        if (!remoteWorkspaceLifecycleReady || workspaceDeletionBusy || remoteDshState.busy) return false
        val snapshot = remoteDshState.projection?.workspace ?: return false
        val archive = snapshot.archives.firstOrNull { it.sessionId == id } ?: return false
        return archive.workspace == null || snapshot.workspaces.any { it.id == archive.workspace.id }
    }

    fun requestRestoreRemoteWorkspace(ticket: String, expectedScope: RemoteDshScope?): Boolean {
        if (expectedScope == null || expectedScope != remoteWorkspaceScope || !remoteWorkspaceLifecycleReady ||
            remoteDshState.projection?.workspace?.removals?.none { it.ticket == ticket && it.phase in REMOVED_PHASES } != false)
            return false
        return requestRemoteLifecycleMutation(expectedScope) { controller, binding ->
            val result = controller.restoreRemovedWorkspace(ticket, expectedScope = binding)
            updateNotice(if (result.optString("status") == "needs_directory")
                "原电脑目录已不存在，请先在电脑准备原路径，再还原工作区；已删除的文件不会自动恢复"
            else {
                check(result.optString("status") == "restored") { "电脑工作区还原尚未确认" }
                "已还原电脑工作区及其会话"
            })
        }
    }

    fun requestDeleteRemoteWorkspaceFiles(ticket: String, confirmedPath: String, expectedScope: RemoteDshScope?): Boolean {
        if (expectedScope == null || expectedScope != remoteWorkspaceScope || !remoteWorkspaceLifecycleReady) return false
        val removal = remoteDshState.projection?.workspace?.removals?.firstOrNull { it.ticket == ticket } ?: return false
        if (removal.workspace.path != confirmedPath || !removal.filesIdentityVerified || removal.filesDeleted ||
            removal.phase !in setOf("removed", "files_moving", "files_deleting")) return false
        return requestRemoteLifecycleMutation(expectedScope) { controller, binding ->
            val result = controller.deleteWorkspaceFiles(ticket, confirmedPath, expectedScope = binding)
            check(result.filesDeleted && result.phase == "files_deleted") { "电脑文件删除尚未确认" }
            updateNotice("已删除确认路径中的电脑文件，归档会话仍保留")
        }
    }

    /** Restore an archived conversation in place; its messages and workspace are preserved. */
    fun restoreConversation(id: String): Boolean {
        if (connectionMode == "online") {
            if (!canRestoreArchivedConversation(id)) {
                updateNotice("请先还原所属电脑工作区，或等待电脑连接恢复")
                return false
            }
            val binding = remoteWorkspaceScope ?: return false
            return requestRemoteLifecycleMutation(binding) { controller, expected ->
                controller.restoreArchivedSession(id, expectedScope = expected)
                updateNotice("电脑会话已还原")
            }
        }
        if (!workspaceInitialized || workspaceDeletionBusy || sendPending || id in localArchivePendingIds) return false
        val index = conversations.indexOfFirst { it.id == id }
        if (index < 0) return false
        val conversation = conversations[index]
        if (conversation.archivedAtMillis == null) return false
        val restored = runCatching { prepareConversationRestoreSnapshot(workspaceSnapshot(), id) }.getOrElse {
            updateNotice(it.message ?: "缺少原工作区信息，无法还原")
            return false
        }
        workspaces.clear(); workspaces.addAll(restored.workspaces)
        conversations.clear(); conversations.addAll(restored.conversations)
        persistWorkspaces()
        updateNotice("已还原对话：${conversation.title}")
        return true
    }

    /** Permanently remove only an archived conversation and its associated messages. */
    fun deleteArchivedConversation(id: String): Boolean {
        if (connectionMode == "online") {
            updateNotice("请先读取电脑的删除确认，再确认永久删除")
            return false
        }
        if (conversations.none { it.id == id && it.archivedAtMillis != null }) return false
        return deleteConversation(id)
    }

    /** Read a durable computer ticket before showing the independent destructive confirmation. */
    suspend fun prepareRemoteArchivedConversationDeletion(id: String, expectedScope: RemoteDshScope?): RemoteWorkspaceStore.ArchiveDeletion {
        require(expectedScope != null && remoteWorkspaceScope == expectedScope && remoteArchiveDeletionAvailable &&
            !archiveOperationBusy && displayArchivedConversations.any { it.id == id }) { "remote_archive_confirmation_scope_changed" }
        val controller = remoteDsh ?: error("电脑连接尚未准备好")
        workspaceDeletionBusy = true
        return try {
            val prepared = controller.prepareArchivedSessionDeletion(id, expectedScope = expectedScope)
            require(connectionMode == "online" && remoteWorkspaceScope == expectedScope && prepared.scope == expectedScope &&
                prepared.sessionId == id && prepared.status == "prepared" && !prepared.deleted &&
                prepared.executionDevice == "remote_computer") { "remote_archive_confirmation_changed" }
            prepared
        } finally { workspaceDeletionBusy = false }
    }

    fun requestDeleteRemoteArchivedConversation(prepared: RemoteWorkspaceStore.ArchiveDeletion): Boolean {
        if (!remoteArchiveDeletionAvailable || remoteWorkspaceScope != prepared.scope ||
            prepared.status != "prepared" || prepared.deleted || prepared.executionDevice != "remote_computer") return false
        val archive = remoteDshState.projection?.workspace?.archives?.firstOrNull { it.sessionId == prepared.sessionId }
            ?: return false
        if (archive.archiveVersion != null && archive.archiveVersion != prepared.archiveVersion) return false
        return requestRemoteLifecycleMutation(prepared.scope) { controller, expected ->
            val committed = controller.deleteArchivedSession(prepared, expectedScope = expected)
            check(committed.scope == expected && committed.ticket == prepared.ticket && committed.sessionId == prepared.sessionId &&
                committed.archiveVersion == prepared.archiveVersion && committed.status == "deleted" && committed.deleted &&
                committed.executionDevice == "remote_computer" && committed.deletedAt != null) { "remote_archive_delete_not_confirmed" }
            updateNotice("电脑归档对话已永久删除；工作区文件保留")
        }
    }

    /** Queue expired archives for the same physical deletion transaction. The
     * return value stays zero until a durable receipt is actually committed. */
    fun purgeExpiredArchives(nowMillis: Long = System.currentTimeMillis()): Int {
        if (connectionMode == "online") return 0
        if (!workspaceInitialized || workspaceDeletionBusy || sendPending || localEngineSwitchBusy || hasNativeRunningTasks()) return 0
        val expiredIds = conversations.filter { isArchiveExpired(it, nowMillis) }.mapTo(HashSet()) { it.id }
        requestLocalArchiveDeletion(expiredIds, includePending = true)
        return 0
    }

    private fun requestLocalArchiveDeletion(ids: Set<String>, includePending: Boolean): Boolean {
        if (connectionMode != "local" || workspaceDeletionBusy || localEngineSwitchBusy || hasNativeRunningTasks()) return false
        val history = localDshHistory ?: return false
        val store = workspaceStore ?: return false
        if (ids.isEmpty() && (!includePending || localArchivePendingIds.isEmpty() && !localArchiveRecoveryFailed)) return false
        workspaceDeletionBusy = true
        localArchiveDeletionDetail = "正在核对本机归档与共享历史…"
        viewModelScope.launch {
            val failures = mutableListOf<String>()
            var completed = 0
            try {
                localEngineGate.withLock {
                    saveJob?.cancelAndJoin()
                    saveJob = null
                    check(commitWorkspace(workspaceSnapshot())) { "归档索引尚未保存，未开始永久删除" }
                    val pending = history.pendingPermanentDeletions()
                    localArchivePendingIds.clear()
                    localArchivePendingIds.addAll(pending.filter { it.phase in setOf("deleting", "native_deleted") }
                        .map { it.conversationId }.distinct())
                    localArchiveRecoveryFailed = false
                    val requested = ids + if (includePending) pending.filter {
                        it.phase in setOf("deleting", "native_deleted")
                    }.map { it.conversationId } else emptyList()
                    var runtimeStopped = false
                    for (id in requested) {
                        try {
                            val conversation = conversations.firstOrNull { it.id == id }
                            val previous = pending.firstOrNull { it.conversationId == id }
                            val intent = if (previous != null) previous else {
                                requireNotNull(conversation) { "本机归档已变化，请刷新" }
                                check(conversation.archivedAtMillis != null) { "对话已还原，未永久删除" }
                                history.preparePermanentDeletion(conversation)
                            }
                            if (conversation != null) check(intent.conversationSha256 ==
                                LocalDshHistoryController.deletionFingerprint(conversation, conversationMessages[id].orEmpty())) {
                                "归档内容已变化，原删除确认不能继续"
                            }
                            if (id !in localArchivePendingIds) localArchivePendingIds += id
                            if (intent.requiresDsh && !runtimeStopped) {
                                // Stop only an idle service. Running tasks retain
                                // their history until the user stops them.
                                dshRuntimeManager?.snapshot?.value?.accessUrl?.let { requireLocalDshIdle(it) }
                                dshRuntimeManager?.stop()
                                modelRuntime?.shutdown()
                                connectPluginHost?.stop()
                                runtimeStopped = true
                            }
                            localArchiveDeletionDetail = if (intent.requiresDsh)
                                "正在永久删除本机与 DSH 中的共享对话…" else "正在永久删除本机归档…"
                            val receipt = history.deletePermanentHistory(intent)
                            val committed = withContext(NonCancellable + Dispatchers.IO) {
                                persistenceMutex.withLock { store.commitPermanentDeletion(receipt) }
                            }
                            // The durable fence protects this snapshot from any
                            // delayed save or a reconstructed repository writer.
                            workspaces.clear(); workspaces.addAll(committed.workspaces)
                            conversations.clear(); conversations.addAll(committed.conversations)
                            conversationMessages.clear()
                            committed.messages.forEach { (key, values) ->
                                conversationMessages[key] = mutableStateListOf<ChatMessage>().apply { addAll(values) }
                            }
                            selectedWorkspaceId = committed.selectedWorkspaceId
                            selectedConversationId = committed.selectedConversationId
                            nextMessageId = (committed.messages.values.flatten().maxOfOrNull { it.id } ?: 0L) + 1L
                            persistenceRevision++
                            history.acknowledgePermanentDeletion(receipt)
                            localArchivePendingIds.remove(id)
                            completed++
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            failures += failure.message?.take(400) ?: "共享历史删除未确认，请重试"
                        }
                    }
                }
                localArchiveDeletionDetail = if (failures.isEmpty()) {
                    if (completed == 0) null else "已永久删除 $completed 个归档；工作区文件保留"
                } else "部分删除步骤尚未完成，请刷新后重试；已完成的删除无法撤销。${failures.first()}"
                localArchiveDeletionDetail?.let(::updateNotice)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                localArchiveDeletionDetail = failure.message?.take(400) ?: "永久删除未完成，请重试"
                updateNotice(requireNotNull(localArchiveDeletionDetail))
            } finally {
                workspaceDeletionBusy = false
                persistWorkspaces()
            }
        }
        return true
    }

    /** Sidebar visibility excludes drafts and archived conversations. */
    fun isConversationVisibleInWorkspace(conversation: Conversation): Boolean {
        if (connectionMode == "online") return conversation.archivedAtMillis == null
        val current = conversations.firstOrNull { it.id == conversation.id } ?: return false
        if (current.workspaceId != conversation.workspaceId || current.archivedAtMillis != null) return false
        if (current.pinned || current.titleManuallySet) return true
        return conversationMessages[current.id]?.any {
            it.role in setOf("user", "assistant") && it.text.isNotBlank()
        } == true
    }

    private fun findUnarchivedConversation(
        excludingId: String? = null,
        preferredWorkspaceId: String = selectedWorkspaceId,
    ): Conversation? = conversations.firstOrNull {
        it.id != excludingId && it.archivedAtMillis == null && it.workspaceId == preferredWorkspaceId
    } ?: conversations.firstOrNull { it.id != excludingId && it.archivedAtMillis == null }

    private fun createEmptyConversation(
        workspaceId: String,
        enforceActiveLimit: Boolean = true,
    ): Conversation? {
        if (workspaces.none { it.id == workspaceId } || conversations.size >= MAX_WORKSPACE_CONVERSATIONS) return null
        if (enforceActiveLimit && conversations.count { it.archivedAtMillis == null } >= MAX_WORKSPACE_CONVERSATIONS) return null
        val conversation = Conversation(UUID.randomUUID().toString(), "新对话", workspaceId)
        conversations.add(0, conversation)
        conversationMessages[conversation.id] = mutableStateListOf()
        activateConversation(conversation)
        return conversation
    }

    private fun activateConversation(conversation: Conversation) {
        selectedWorkspaceId = conversation.workspaceId
        selectedConversationId = conversation.id
        composerGeneration++
    }

    /** Remove only inactive, unarchived drafts with no messages; never touch the selected session. */
    private fun pruneUnusedEmptyDrafts(): Int {
        val unusedIds = conversations.asSequence()
            .filter { it.archivedAtMillis == null && it.id != selectedConversationId }
            .filterNot { it.pinned || it.titleManuallySet }
            .filter { isAutoCreatedEmptyConversation(it, conversationDeleteFacts(it.id)) }
            .map { it.id }
            .toSet()
        if (unusedIds.isEmpty()) return 0
        conversations.removeAll { it.id in unusedIds }
        unusedIds.forEach { conversationMessages.remove(it) }
        return unusedIds.size
    }

    private fun conversationDeleteFacts(id: String): ConversationDeleteFacts {
        val messages = conversationMessages[id].orEmpty()
        return ConversationDeleteFacts(
            hasMessages = messages.isNotEmpty(),
            hasDraft = selectedConversationId == id &&
                (composerDraftText == null || composerDraftText?.isNotEmpty() == true),
            hasAttachments = messages.any { it.attachmentIds.isNotEmpty() } ||
                (selectedConversationId == id && (liteAttachments.isNotEmpty() || dshAttachments.isNotEmpty() ||
                    attachmentImportCount > 0 || pendingAttachmentSelections.isNotEmpty())),
            hasTasks = backgroundTasks?.tasks?.value?.any { it.parentConversationId == id } == true,
            hasOutputs = officialOutputs.values.any { it.conversationId == id } || activeResponseConversationId == id,
        )
    }

    fun createWorkspace(name: String) {
        if (!workspaceInitialized || workspaceDeletionBusy) return
        val title = name.trim().take(80)
        if (title.isEmpty()) return
        if (workspaces.size >= 200) {
            updateNotice("最多保留 200 个工作目录")
            return
        }
        val id = UUID.randomUUID().toString()
        val store = workspaceStore ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { persistenceMutex.withLock { store.createInternalWorkspace(id) } }
                check(!workspaceDeletionBusy) { "工作区正在移除，请完成后重新创建" }
                workspaces.add(Workspace(id, title))
                selectWorkspace(id)
                updateNotice("已新建本地工作目录：$title")
                persistWorkspaces()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                updateNotice("无法新建工作目录：${error.message ?: "请重试"}")
            }
        }
    }

    fun addExternalWorkspace(context: Context, treeUri: Uri) {
        if (!workspaceInitialized || workspaceDeletionBusy) return
        val existing = workspaces.firstOrNull { it.uri == treeUri.toString() }
        if (existing != null) {
            selectWorkspace(existing.id)
            return
        }
        if (workspaces.size >= 200) {
            updateNotice("最多保留 200 个工作目录")
            return
        }
        try {
            context.contentResolver.takePersistableUriPermission(treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val leaf = Uri.decode(treeUri.lastPathSegment.orEmpty()).substringAfterLast(':').substringAfterLast('/')
            val name = leaf.takeIf { it.isNotBlank() }?.take(80) ?: "所选工作目录"
            val workspace = Workspace(UUID.randomUUID().toString(), name, treeUri.toString())
            workspaces.add(workspace)
            selectWorkspace(workspace.id)
            updateNotice("已引用工作目录：$name · 对话保存在应用内，未写入所选目录")
            persistWorkspaces()
        } catch (error: Exception) {
            updateNotice("无法保留目录授权：${error.message ?: "请重新选择"}")
        }
    }

    private fun workspaceSnapshot(): WorkspaceSnapshot =
        WorkspaceSnapshot(workspaces.toList(), conversations.toList(),
            conversationMessages.mapValues { it.value.toList() }, selectedWorkspaceId, selectedConversationId)

    /** IO only; failures must prevent attachment deletion rather than imply an empty keep set. */
    suspend fun persistedAttachmentIds(): Set<String> = withContext(Dispatchers.IO) {
        persistenceMutex.withLock {
            checkNotNull(workspaceStore) { "本地索引不可读取，暂不能清理附件" }.persistedAttachmentIds()
        }
    }

    private fun acceptSaveResult(result: WorkspaceSaveResult) {
        val previousError = workspaceSaveError
        workspaceSaveError = if (result.indexCommitted) null else
            "索引保存失败，内容已写入本地恢复文件；请释放容量后重试保存：${result.error}"
        if (workspaceSaveError != null) updateNotice(requireNotNull(workspaceSaveError))
        else if (previousError != null) updateNotice("本地对话已保存，可以继续发送")
    }

    private suspend fun commitWorkspace(snapshot: WorkspaceSnapshot): Boolean = withContext(NonCancellable) {
        val store = workspaceStore ?: run {
            workspaceSaveError = "本地索引不可用，草稿已保留；请重新启动后重试"
            updateNotice(requireNotNull(workspaceSaveError))
            return@withContext false
        }
        try {
            val result = withContext(NonCancellable + Dispatchers.IO) {
                persistenceMutex.withLock { store.saveWithRecovery(snapshot) }
            }
            acceptSaveResult(result)
            result.indexCommitted
        } catch (error: Exception) {
            workspaceSaveError = "索引及恢复文件未保存，内容仍在内存中；请导出回复并重试保存：${error.message ?: "写入失败"}"
            updateNotice(requireNotNull(workspaceSaveError))
            false
        }
    }

    suspend fun retryWorkspaceSave(): Boolean = withContext(NonCancellable + Dispatchers.Main.immediate) {
        if (sendPending || isResponding || workspaceDeletionBusy) {
            updateNotice("请等待当前发送或回复完成后重试保存")
            return@withContext false
        }
        sendPending = true
        var revision = persistenceRevision
        try {
            saveJob?.cancelAndJoin()
            saveJob = null
            revision = persistenceRevision
            commitWorkspace(workspaceSnapshot())
        } finally {
            sendPending = false
            if (revision != persistenceRevision) persistWorkspaces()
        }
    }

    private fun persistWorkspaces() {
        persistenceRevision++
        if (workspaceStore == null) return
        if (!workspaceInitialized) return
        if (sendPending || workspaceDeletionBusy) return
        // Keep one writer; replacing a cancelled noncancellable commit could leave an
        // older IO task untracked and allow it to overwrite the precommitted turn.
        if (saveJob?.isCompleted == false) return
        saveJob = viewModelScope.launch {
            do {
                delay(180)
                val revision = persistenceRevision
                commitWorkspace(workspaceSnapshot())
            } while (revision != persistenceRevision && !sendPending &&
                currentCoroutineContext()[Job]?.isActive != false)
        }
    }

    fun selectModel(id: String) {
        if (connectionMode == "online") {
            val provider = remoteDshState.projection?.workspace?.catalog?.providers.orEmpty()
                .firstOrNull { it.models.any { model -> "${it.id}::${model.id}" == id } }
            val model = provider?.models?.firstOrNull { "${provider.id}::${it.id}" == id }
            if (provider != null && model != null) selectProviderModel(provider.id, model.id, model.defaultEffort)
            else updateNotice("请先刷新电脑的模型目录")
            return
        }
        val model = modelChoices.firstOrNull { it.id == id }
        if (model == null) {
            updateNotice("找不到该模型")
            return
        }
        if (model == selectedModel) return
        val previousReasoning = selectedReasoningOption?.id
        selectedModel = model
        val rememberedReasoning = settingsPreferences?.getString("deepseek_reasoning_${model.id}", null)
        val validId = model.officialCapabilities?.let {
            DeepSeekThinkingPolicy.normalizeOptionId(it, rememberedReasoning ?: previousReasoning)
        } ?: (rememberedReasoning ?: previousReasoning)?.takeIf { id -> model.reasoningOptions.any { it.id == id } }
        selectedReasoningIndex = model.reasoningOptions.indexOfFirst { it.id == validId }
            .takeIf { it >= 0 } ?: model.defaultReasoningIndex
        settingsPreferences?.edit()?.putString("deepseek_selected_model", if (model.isExample) "" else model.id)?.apply()
        settingsPreferences?.edit()?.putString("selected_model", if (model.isExample) "" else model.id)?.apply()
        updateNotice("已选择${model.name}${if (model.isExample) " · 仅本地预览" else ""}")
    }

    fun selectProviderModel(providerId: String, modelId: String, reasoning: String?) {
        if (connectionMode == "online") {
            remoteOperation { remoteDsh?.selectModel(providerId, modelId, reasoning) }
            return
        }
        val choice = if (providerId == "deepseek") modelChoices.firstOrNull { it.id == modelId && it.runtimeModel == null }
            else modelChoices.firstOrNull { it.runtimeModel?.providerId == providerId && it.runtimeModel.id == modelId }
        if (choice == null) { updateNotice("请先刷新该提供商的真实模型目录"); return }
        selectModel(choice.id)
        setReasoningId(reasoning)
    }

    fun saveDeepSeekKey(value: String) {
        if (deepSeekBalanceStore == null || modelProviderStore == null) return
        if (connectionMode != "local" || deepSeekConfigurationBusy || isResponding || localEngineSwitchBusy) return
        val generation = ++credentialGeneration
        deepSeekConfigurationBusy = true
        deepSeekModelError = null
        viewModelScope.launch {
            try {
                stopTasksForCredentialChange()
                persistOfficialCredential(value)
                if (generation == credentialGeneration) {
                    deepSeekConfigured = true
                    invalidateRemoteModels()
                    updateNotice("DeepSeek API Key 已安全保存")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { deepSeekModelError = "无法安全保存 API Key，请重试" }
            finally { deepSeekConfigurationBusy = false; notifyTaskConfiguration() }
        }
    }

    fun clearDeepSeekKey() {
        if (deepSeekBalanceStore == null || modelProviderStore == null) return
        if (connectionMode != "local" || deepSeekConfigurationBusy || localEngineSwitchBusy) return
        val runningResponse = responseJob
        cancelResponse()
        ++credentialGeneration
        catalogClient?.cancel()
        deepSeekConfigurationBusy = true
        viewModelScope.launch {
            try {
                withTimeout(30_000L) { runningResponse?.join() }
                stopTasksForCredentialChange()
                persistOfficialCredential(null)
                deepSeekConfigured = false
                invalidateRemoteModels()
                deepSeekModelError = null
                updateNotice("DeepSeek API Key 已清除")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { deepSeekModelError = "无法清除 API Key，请重试" }
            finally { deepSeekConfigurationBusy = false; notifyTaskConfiguration() }
        }
    }

    /** A failed native write rolls back only our revision, never another provider edit. */
    private suspend fun persistOfficialCredential(value: String?) = localEngineGate.withLock {
        dshRuntimeManager?.snapshot?.value?.accessUrl?.let { requireLocalDshIdle(it) }
        try {
            withContext(NonCancellable + Dispatchers.IO) {
                val providers = requireNotNull(modelProviderStore)
                val balance = requireNotNull(deepSeekBalanceStore)
                val key = value?.trim()?.also {
                    require(it.isNotEmpty() && it.length <= 512 && it.none { c -> c.isWhitespace() || c.isISOControl() })
                }
                val credential = key?.let { JSONObject().put("type", "api_key").put("key", it) }
                val before = providers.workerConfiguration()
                val previousCredential = before.getJSONObject("credentials").optJSONObject("deepseek")
                val previousKey = balance.readApiKeyForRequest()
                val committed = providers.persistWorkerCredential("deepseek", credential, before.getLong("revision"))
                try {
                    if (key == null) balance.clearApiKey() else balance.setApiKey(key)
                } catch (failure: Exception) {
                    try {
                        val current = balance.readApiKeyForRequest()
                        check(current == key || current == previousKey) { "官方凭据在保存期间已变化" }
                        if (current != previousKey) {
                            if (previousKey == null) balance.clearApiKey() else balance.setApiKey(previousKey)
                        }
                        providers.persistWorkerCredential("deepseek", previousCredential, committed)
                    } catch (rollback: Exception) {
                        throw IllegalStateException("官方凭据回退未完成，请在模型页重新保存 API Key", rollback)
                    }
                    throw failure
                }
            }
        } finally {
            if (modelRuntime?.state?.value?.busy != true) modelRuntime?.refresh()
        }
    }

    /** Explicit .dym export only. The UI explains that this object contains portable credentials. */
    fun exportDymProviderConfiguration(): JSONObject {
        val portable = requireNotNull(modelProviderStore) { "提供商存储尚未就绪" }.exportPortable()
        deepSeekBalanceStore?.readApiKeyForRequest()?.let { key ->
            portable.getJSONObject("credentials").put("deepseek", JSONObject().put("type", "api_key").put("key", key))
        }
        return portable
    }

    /** Pause real execution before replacing credentials; keep both vaults consistent on a write failure. */
    suspend fun restoreDymProviderConfiguration(configuration: JSONObject): Result<Unit> = withContext(Dispatchers.Main.immediate) {
        if (deepSeekConfigurationBusy || workspaceDeletionBusy || localEngineSwitchBusy || connectionMode != "local") {
            return@withContext Result.failure(IllegalStateException("请在本地模式等待当前配置保存后重试"))
        }
        val providers = modelProviderStore ?: return@withContext Result.failure(IllegalStateException("提供商存储尚未就绪"))
        val balance = deepSeekBalanceStore ?: return@withContext Result.failure(IllegalStateException("凭据存储尚未就绪"))
        deepSeekConfigurationBusy = true
        ++credentialGeneration
        val runningJob = responseJob
        cancelResponse()
        catalogClient?.cancel()
        try {
            withTimeout(30_000L) { runningJob?.join(); stopTasksForCredentialChange() }
            localEngineGate.withLock { withContext(NonCancellable + Dispatchers.IO) {
                val incoming = JSONObject(configuration.toString())
                val deepseek = incoming.getJSONObject("credentials").optJSONObject("deepseek")
                val key = deepseek?.let {
                    require(it.getString("type") == "api_key") { "DeepSeek 官方仅接受 API Key" }
                    it.getString("key").trim().also { value ->
                        require(value.isNotEmpty() && value.length <= 512 && value.none { c -> c.isWhitespace() || c.isISOControl() }) {
                            "备份中的 DeepSeek API Key 无效"
                        }
                    }
                }
                val previous = providers.exportPortable()
                val previousKey = balance.readApiKeyForRequest()
                var writtenRevision: Long? = null
                try {
                    val imported = providers.importPortableIfRevision(incoming, previous.getLong("revision"))
                    writtenRevision = imported.revision
                    if (key == null) balance.clearApiKey() else balance.setApiKey(key)
                    withContext(Dispatchers.Main.immediate) {
                        modelProviderOrder = imported.providers.map { it.id }
                        modelProviderEnabled = imported.providers.associate { it.id to it.enabled }
                    }
                } catch (error: Exception) {
                    try {
                        writtenRevision?.let { revision ->
                            providers.importPortableIfRevision(previous, revision)
                            val current = balance.readApiKeyForRequest()
                            check(current == key || current == previousKey) { "官方凭据已变化，不能回退覆盖" }
                            if (current != previousKey) {
                                if (previousKey == null) balance.clearApiKey() else balance.setApiKey(previousKey)
                            }
                        }
                    } catch (rollback: Exception) {
                        throw IllegalStateException("凭据恢复失败且回退未完成，请重新检查提供商配置", rollback)
                    }
                    throw error
                }
            } }
            deepSeekConfigured = withContext(Dispatchers.IO) { balance.hasKey() }
            invalidateRemoteModels()
            Result.success(Unit)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
        finally { deepSeekConfigurationBusy = false; notifyTaskConfiguration() }
    }

    private suspend fun stopTasksForCredentialChange() = withContext(Dispatchers.Main.immediate) {
        taskCredentialBoundary.stop(backgroundTasks) {
            var failure: Exception? = null
            taskClients.toList().forEach { client ->
                try {
                    client.cancel()
                } catch (error: Exception) {
                    if (failure == null) failure = error
                }
            }
            failure?.let { throw it }
        }
    }

    private fun notifyTaskConfiguration() {
        val engine = backgroundTasks ?: return
        viewModelScope.launch {
            try {
                engine.notifyConfigurationChanged()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                updateNotice("任务配置更新失败，请重新打开任务页面")
            }
        }
    }

    private fun invalidateRemoteModels() {
        modelChoices.removeAll { !it.isExample && it.runtimeModel == null }
        settingsPreferences?.edit()?.remove("deepseek_model_catalog")?.apply()
        if (!selectedModel.isExample && selectedModel.runtimeModel == null) {
            selectedModel = unconfiguredModel
            selectedReasoningIndex = -1
        }
    }

    private fun restoreModelCatalog() {
        val cached = settingsPreferences?.getString("deepseek_model_catalog", null) ?: return
        val models = runCatching { DeepSeekModelCatalog.decodeCache(cached).map(::officialModelChoice) }
            .getOrNull() ?: return
        modelChoices.addAll(0, models)
        val remembered = settingsPreferences?.getString("selected_model", null)
            ?: settingsPreferences?.getString("deepseek_selected_model", null)
        if (remembered?.contains("::") != true) {
            models.firstOrNull { it.id == remembered }?.let { selectModel(it.id) }
                ?: models.firstOrNull()?.let { selectModel(it.id) }
        }
    }

    private fun officialModelChoice(model: DeepSeekChatClient.Model): ModelChoice {
        val options = DeepSeekThinkingPolicy.options(model).map { ReasoningOption(it.id, it.label) }
        val defaultId = DeepSeekThinkingPolicy.defaultOptionId(model)
        return ModelChoice(model.id, "DeepSeek", model.name ?: model.id, options,
            options.indexOfFirst { it.id == defaultId }, false, model.inputModalities, model)
    }

    private fun officialReasoning(model: ModelChoice, option: ReasoningOption?): DeepSeekThinkingPolicy.Selection {
        return model.officialCapabilities?.let { DeepSeekThinkingPolicy.selection(it, option?.id) }
            ?: DeepSeekThinkingPolicy.Selection(null, null)
    }

    private fun supportsImages(model: ModelChoice): Boolean = !model.isExample &&
        (model.id in setOf("deepseek-flash", "deepseek-v4-flash-vision-exp") ||
            model.inputModalities.any { it == "image" })

    fun refreshDeepSeekModels() {
        val client = catalogClient ?: return
        if (deepSeekConfigurationBusy) return
        if (!deepSeekConfigured) { updateNotice("请先保存 DeepSeek API Key"); return }
        val generation = credentialGeneration
        deepSeekConfigurationBusy = true
        deepSeekModelError = null
        viewModelScope.launch {
            try {
                val fetched = client.listModels()
                if (generation != credentialGeneration) return@launch
                val remembered = settingsPreferences?.getString("deepseek_selected_model", null)
                val oldId = selectedModel.id.takeUnless { selectedModel.isExample || it == "unconfigured" } ?: remembered
                val previousReasoning = selectedReasoningOption?.id
                val models = fetched.map(::officialModelChoice)
                invalidateRemoteModels()
                modelChoices.addAll(0, models)
                settingsPreferences?.edit()?.putString("deepseek_model_catalog", DeepSeekModelCatalog.encodeCache(fetched))?.apply()
                if (selectedModel.runtimeModel == null) {
                    models.firstOrNull { it.id == oldId }?.let { selectModel(it.id) }
                        ?: models.firstOrNull()?.let { selectModel(it.id) }
                }
                selectedModel.officialCapabilities?.let { capabilities ->
                    val valid = DeepSeekThinkingPolicy.normalizeOptionId(capabilities, previousReasoning)
                    selectedReasoningIndex = selectedModel.reasoningOptions.indexOfFirst { it.id == valid }
                }
                if (models.isEmpty()) deepSeekModelError = "账户没有返回可用模型"
                else updateNotice("已获取 ${models.size} 个 DeepSeek 模型")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { deepSeekModelError = error.message ?: "模型目录读取失败" }
            finally { deepSeekConfigurationBusy = false; notifyTaskConfiguration() }
        }
    }

    fun setReasoningIndex(index: Int) {
        if (connectionMode == "online") {
            val selected = remoteDshState.projection?.workspace?.settings?.selection
                ?: remoteDshState.projection?.workspace?.catalog?.selection ?: return
            val option = displaySelectedModel.reasoningOptions.getOrNull(index) ?: return
            remoteOperation { remoteDsh?.selectModel(selected.provider, selected.model, option.id) }
            return
        }
        if (index !in selectedModel.reasoningOptions.indices) return
        selectedReasoningIndex = index
        settingsPreferences?.edit()?.putString("deepseek_reasoning_${selectedModel.id}", selectedReasoningOption?.id)?.apply()
    }

    fun setReasoningId(id: String?) {
        val index = id?.let { value -> selectedModel.reasoningOptions.indexOfFirst { it.id == value } } ?: -1
        if (index >= 0) setReasoningIndex(index)
    }

    fun toggleAttachment(id: String) {
        val attachment = attachmentChoices.firstOrNull { it.id == id } ?: return
        val index = activeAttachments.indexOfFirst { it.id == id }
        if (index >= 0) removeAttachment(id) else addAttachment(attachment.id, attachment.label, attachment.uri)
    }

    fun addAttachment(id: String, label: String, uri: String? = null) {
        if (id in deletingAttachmentIds) {
            updateNotice("此附件正在清理，请重新选择文件")
            return
        }
        if (id.isBlank() || label.isBlank() || activeAttachments.any { it.id == id } ||
            id in pendingAttachmentSelections) return
        if (activeAttachments.size + attachmentImportCount >= 6) {
            updateNotice("最多添加 6 个附件")
            return
        }
        val managed = importedAttachments[id]
        if (managed != null) {
            selectStoredAttachment(id)
            return
        }
        if (attachmentChoices.none { it.id == id }) {
            updateNotice("附件已不存在，请重新选择文件")
            return
        }
        activeAttachments.add(AttachmentPreview(id, label, uri))
    }

    private fun selectStoredAttachment(id: String) {
        val store = attachmentStore ?: return
        if (id in deletingAttachmentIds || id in pendingAttachmentSelections ||
            activeAttachments.any { it.id == id }) return
        if (activeAttachments.size + attachmentImportCount >= 6) {
            updateNotice("最多添加 6 个附件")
            return
        }
        val targetMode = workspaceMode
        val targetConversation = selectedConversationId
        val targetComposer = composerGeneration
        val selectionToken = Any()
        pendingAttachmentSelections[id] = selectionToken
        attachmentImportCount++
        viewModelScope.launch {
            try {
                val preview = withContext(Dispatchers.IO) {
                    previewAttachment(store.load(id) ?: error("附件已不存在"))
                }
                if (targetMode != workspaceMode || targetConversation != selectedConversationId ||
                    targetComposer != composerGeneration || pendingAttachmentSelections[id] !== selectionToken ||
                    id in deletingAttachmentIds || activeAttachments.any { it.id == id }) return@launch
                commitAttachmentSelection(preview)
                notifyLocalOnlyAttachment(preview)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                updateNotice("附件读取失败：${error.message ?: "请重新选择"}")
            } finally {
                if (pendingAttachmentSelections[id] === selectionToken) pendingAttachmentSelections.remove(id)
                attachmentImportCount--
            }
        }
    }

    fun importAttachment(uri: Uri) {
        val store = attachmentStore ?: run {
            updateNotice("正在准备附件存储，请稍候")
            return
        }
        if (!workspaceInitialized) {
            updateNotice("正在读取本地工作目录，请稍候")
            return
        }
        if (activeAttachments.size + attachmentImportCount >= 6) {
            updateNotice("最多添加 6 个附件")
            return
        }
        val targetMode = workspaceMode
        val targetConversation = selectedConversationId
        val targetComposer = composerGeneration
        val targetSelection = attachmentSelectionGeneration
        attachmentImportCount++
        viewModelScope.launch {
            var imported: ConversationAttachment? = null
            try {
                val preview = withContext(Dispatchers.IO) {
                    val file = store.importAttachment(uri)
                    imported = file
                    previewAttachment(file)
                }
                if (workspaceMode != targetMode || selectedConversationId != targetConversation ||
                    composerGeneration != targetComposer || attachmentSelectionGeneration != targetSelection) {
                    withContext(Dispatchers.IO) { imported?.let { store.delete(it.id) } }
                    return@launch
                }
                commitAttachmentSelection(preview)
                notifyLocalOnlyAttachment(preview)
            } catch (cancelled: CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { imported?.let { store.delete(it.id) } }
                throw cancelled
            } catch (error: Exception) {
                withContext(Dispatchers.IO) { imported?.let { store.delete(it.id) } }
                updateNotice("附件导入失败：${error.message ?: "请重新选择文件"}")
            } finally {
                attachmentImportCount--
            }
        }
    }

    private fun previewAttachment(file: ConversationAttachment, includeText: Boolean = true): AttachmentPreview {
        val store = checkNotNull(attachmentStore)
        return AttachmentPreview(
            file.id, file.name, Uri.fromFile(store.resolveFile(file.id)).toString(),
            file.mimeType, file.sizeBytes,
            if (includeText && file.textSupported && file.sizeBytes <= ConversationAttachmentStore.MAX_TEXT_BYTES)
                store.readText(file.id) else null,
            file.textSupported,
        )
    }

    private fun commitAttachmentSelection(preview: AttachmentPreview) {
        check(preview.id !in deletingAttachmentIds) { "此附件正在清理，请重新选择文件" }
        require(activeAttachments.none { it.id == preview.id }) { "附件已选中" }
        require(activeAttachments.size < 6) { "最多添加 6 个附件" }
        require(activeAttachments.sumOf { it.sizeBytes } + preview.sizeBytes <=
            ConversationAttachmentStore.MAX_TOTAL_BYTES) { "本次附件合计不能超过 25 MiB" }
        val textBytes = (activeAttachments + preview).sumOf {
            it.textContent?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
        }
        require(textBytes <= ConversationAttachmentStore.MAX_TOTAL_TEXT_BYTES) {
            "本次附件文本合计不能超过 512 KiB"
        }
        // History/catalog entries carry metadata; only the two bounded drafts retain text.
        importedAttachments[preview.id] = preview.copy(textContent = null)
        activeAttachments.add(preview)
    }

    private fun notifyLocalOnlyAttachment(preview: AttachmentPreview) {
        if (preview.textContent != null) return
        if (preview.mimeType in setOf("image/jpeg", "image/png", "image/gif", "image/webp")) {
            updateNotice("已保存${preview.label}；图片发送需使用支持视觉输入的模型")
            return
        }
        val reason = if (preview.textSupported &&
            preview.sizeBytes > ConversationAttachmentStore.MAX_TEXT_BYTES) {
            "文本超过 256 KiB，仅保存在本地，不会注入模型"
        } else {
            "二进制文件仅保存在本地，不会注入模型"
        }
        updateNotice("已保存${preview.label}；$reason，请在发送前移除此附件")
    }

    fun messageAttachments(message: ChatMessage): List<AttachmentPreview> =
        message.attachmentIds.mapNotNull { importedAttachments[it] }

    fun recentImageAttachments(): List<AttachmentPreview> =
        importedAttachments.values.filter { it.mimeType?.startsWith("image/") == true }.takeLast(12)

    fun storedAttachmentPreviews(): List<AttachmentPreview> = importedAttachments.values.toList()

    fun cleanupUnusedAttachments() {
        val store = attachmentStore ?: return
        val workspace = workspaceStore
        if (!workspaceInitialized || workspace == null) {
            updateNotice("本地索引尚不可读，已保留附件")
            return
        }
        if (attachmentCleanupBusy) return
        if (attachmentImportCount > 0) {
            updateNotice("附件导入中，请稍候再清理")
            return
        }
        val referenced = conversationMessages.values.flatten().flatMap { it.attachmentIds }.toSet() +
            liteAttachments.map { it.id } + dshAttachments.map { it.id }
        val candidates = importedAttachments.keys.filterNot { it in referenced || it in deletingAttachmentIds }
        attachmentCleanupBusy = true
        deletingAttachmentIds.addAll(candidates)
        viewModelScope.launch {
            val deletedIds = mutableSetOf<String>()
            try {
                val currentReferences = conversationMessages.values.flatten().flatMap { it.attachmentIds }.toSet() +
                    liteAttachments.map { it.id } + dshAttachments.map { it.id }
                withContext(Dispatchers.IO) {
                    // Keep the durable read and deletion in one lock interval: a save cannot
                    // introduce new committed references between the check and the deletion.
                    synchronized(WorkspaceStore.INDEX_LOCK) {
                        val durableReferences = readDurableAttachmentReferences(workspace.root)
                        for (id in candidates) {
                            if (id in currentReferences || id in durableReferences) continue
                            if (store.delete(id)) deletedIds.add(id)
                        }
                    }
                }
                updateNotice("已清理 ${deletedIds.size} 个未使用附件")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                updateNotice("索引读取或附件清理失败，已保留其余文件")
            } finally {
                deletedIds.forEach { importedAttachments.remove(it) }
                deletingAttachmentIds.removeAll(candidates.toSet())
                attachmentCleanupBusy = false
            }
        }
    }

    private fun readDurableAttachmentReferences(root: java.io.File): Set<String> {
        val files = listOf(
            "workspace-index.json", "workspace-index.json.bak",
            "workspace-recovery.json", "workspace-recovery.json.bak",
        ).map { java.io.File(root, it) }.filter { it.exists() }
        check(files.isNotEmpty()) { "工作区索引不可读" }
        // Read directly: AtomicFile.openRead() may restore a backup. Conservatively
        // retain references even in a superseded recovery until its owner removes it.
        return buildSet {
            for (file in files) {
                val isRecovery = file.name.startsWith("workspace-recovery.")
                val limit = if (isRecovery) 64L * 1024 * 1024 else 8L * 1024 * 1024
                check(file.isFile && file.length() <= limit) { "工作区索引不可读" }
                val bytes = file.inputStream().use { stream ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val count = stream.read(chunk)
                        if (count < 0) break
                        check(buffer.size().toLong() + count <= limit) { "工作区索引过大" }
                        buffer.write(chunk, 0, count)
                    }
                    buffer.toByteArray()
                }
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                val encoded = JSONObject(decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString())
                val json = if (isRecovery) {
                    check(encoded.getInt("schema") == 1) { "工作区恢复版本无效" }
                    encoded.getJSONObject("snapshot")
                } else encoded
                check(json.getInt("version") in 1..2) { "工作区索引版本无效" }
                val messages = json.getJSONObject("messages")
                val keys = messages.keys()
                while (keys.hasNext()) {
                    val items = messages.getJSONArray(keys.next())
                    for (i in 0 until items.length()) {
                        val message = items.getJSONObject(i)
                        if (!message.has("attachmentIds")) continue
                        val ids = message.getJSONArray("attachmentIds")
                        for (j in 0 until ids.length()) add(ids.getString(j))
                    }
                }
            }
        }
    }

    fun exportResponse(message: ChatMessage, target: Uri) {
        val context = applicationContext ?: return
        if (target.scheme != "content" || message.role != "assistant" || message.text.isBlank()) return
        val snapshot = message.streamSnapshot ?: return
        if (!snapshot.isFinal || snapshot.runStatus?.let { it != AssistantRunStatus.COMPLETED } == true ||
            snapshot.messages.any { it.status != AssistantMessageStatus.COMPLETED }) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(target, "wt")?.use {
                        it.write(message.text.toByteArray(Charsets.UTF_8))
                    } ?: error("无法打开保存位置")
                }
                updateNotice("已保存助手回复")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                updateNotice("保存失败，请重新选择保存位置")
            }
        }
    }

    fun exportCode(code: String, target: Uri) {
        val context = applicationContext ?: return
        if (target.scheme != "content" || code.length > 65_536) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(target, "wt")?.use {
                        it.write(code.toByteArray(Charsets.UTF_8))
                    } ?: error("destination_unavailable")
                }
                updateNotice("已保存代码")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                updateNotice("保存失败，请重新选择保存位置")
            }
        }
    }

    fun removeAttachment(id: String) {
        pendingAttachmentSelections.remove(id)
        activeAttachments.removeAll { it.id == id }
    }

    fun clearAttachments() {
        attachmentSelectionGeneration++
        pendingAttachmentSelections.clear()
        activeAttachments.clear()
    }

    fun selectMode(mode: WorkspaceMode) {
        if (mode == workspaceMode) return
        cancelResponse()
        workspaceMode = mode
        if (mode == WorkspaceMode.Dsh && connectionMode == "local") startRuntime()
    }

    fun startRuntime() {
        if (workspaceDeletionBusy || localArchiveRecoveryFailed || localArchivePendingIds.isNotEmpty()) {
            updateNotice("请先完成本机共享历史的永久删除，再启动 DSH")
            return
        }
        runtimeAutostartSuppressed = false
        if (dshInstallation?.state?.value?.installing == true) {
            updateNotice("DSH 运行包正在安装，请等待校验完成")
            return
        }
        if (localEngineSwitchBusy || runtimeStatus == RuntimeStatus.Running || runtimeStatus == RuntimeStatus.Starting) return
        if (runtimeStatus == RuntimeStatus.Stopping) {
            updateNotice("DSH 服务正在停止，请稍后再启动")
            return
        }
        val manager = dshRuntimeManager
        if (manager == null) {
            updateNotice("DSH 运行服务尚未初始化，请稍后重试")
            return
        }
        val previous = runtimeJob
        previous?.cancel()
        runtimeJob = viewModelScope.launch {
            localEngineSwitchBusy = engineMode == "dsh"
            localEngineSwitchError = null
            try {
                previous?.join()
                localEngineGate.withLock { startLocalDsh(sharedHistory = engineMode == "dsh") }
                updateNotice("DSH 服务已启动")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                runtimeStep = error.message ?: "DSH 服务启动失败"
                runtimeLastError = runtimeStep
                localEngineSwitchError = runtimeStep
                updateNotice("DSH 服务启动失败：${runtimeStep}")
            } finally { localEngineSwitchBusy = false }
        }
    }

    private fun hasNativeRunningTasks(): Boolean = isResponding || sendPending || workspaceDeletionBusy ||
        deepSeekConfigurationBusy || modelRuntime?.state?.value?.busy == true ||
        backgroundTasks?.tasks?.value.orEmpty().any {
            it.status in setOf(BackgroundTaskStatus.QUEUED, BackgroundTaskStatus.RUNNING, BackgroundTaskStatus.STOPPING)
        }

    private suspend fun startLocalDsh(sharedHistory: Boolean) {
        check(!localArchiveRecoveryFailed && localArchivePendingIds.isEmpty()) {
            "共享历史的永久删除尚未完成，请在归档页刷新后重试"
        }
        val manager = requireNotNull(dshRuntimeManager) { "DSH 服务管理器尚未初始化" }
        val baseline = workspaceSnapshot()
        val directory = localDshWorkspace(baseline.selectedWorkspaceId)
        if (sharedHistory) {
            check(!hasNativeRunningTasks()) { "本机任务尚未停止，不能共享运行中的历史" }
            val access = manager.snapshot.value.accessUrl
            if (access != null) { requireLocalDshIdle(access); manager.stop() }
            modelRuntime?.shutdown()
            connectPluginHost?.stop()
            synchronizeOfficialCredentialToVault()
            val published = requireNotNull(localDshHistory).publish(baseline)
            localDshSessionId = published.selectedSessionId
            localEngineSharingWarnings = published.warnings
            published.warnings.firstOrNull()?.let(::updateNotice)
            check(workspaceSnapshot() == baseline) { "工作区或对话已变化，历史保留，请重新切换" }
        }
        val started = manager.start(directory)
        if (sharedHistory) {
            val access = requireNotNull(started.accessUrl) { "DSH 未提供已认证网页入口" }
            try {
                lastDshProviderRevision = requireNotNull(sharedDshConfiguration).writeToDsh(access).revision
                dshSharedConfigurationError = null
            }
            catch (failure: Throwable) {
                withContext(NonCancellable) { manager.stop() }
                throw failure
            }
        }
    }

    private suspend fun synchronizeOfficialCredentialToVault() = withContext(Dispatchers.IO) {
        val store = requireNotNull(modelProviderStore)
        val portable = store.exportPortable()
        val secrets = portable.getJSONObject("credentials")
        val old = secrets.optJSONObject("deepseek")
        val key = requireNotNull(deepSeekBalanceStore).readApiKeyForRequest()
        val value = key?.let { JSONObject().put("type", "api_key").put("key", it) }
        if (old?.toString() != value?.toString()) {
            if (value == null) secrets.remove("deepseek") else secrets.put("deepseek", value)
            store.importPortableIfRevision(portable, portable.getLong("revision"))
        }
    }

    private suspend fun requireLocalDshIdle(access: String) {
        val rpc = DshLocalConfigurationRpc()
        val state = rpc.call(access, "session/list", JSONObject().put("_request", JSONObject()))
        val items = state.getJSONArray("items")
        for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            check(item.opt("running") is Boolean) { "无法确认 DSH 任务状态，请先在 DSH 中检查" }
            check(!item.getBoolean("running")) { "DSH 仍有任务在运行，请先停止并等待结束" }
            val projections = rpc.call(access, "session/projections",
                JSONObject().put("request", JSONObject().put("sessionId", item.getString("sessionId"))))
            val inbox = requireNotNull(projections.optJSONObject("values")?.optJSONObject("inbox")) {
                "无法确认 DSH 待办队列，请先在 DSH 中检查"
            }
            check(inbox.getJSONArray("next-turn").length() == 0 && inbox.getJSONArray("next-step").length() == 0) {
                "DSH 仍有待办消息，请先处理或清除后再切换引擎"
            }
        }
    }

    private suspend fun synchronizeFromLocalDsh() {
        val manager = requireNotNull(dshRuntimeManager)
        if (dshInstallation?.state?.value?.installedVersion == null && manager.snapshot.value.accessUrl == null) {
            updateNotice("DSH 运行组件未安装，继续使用现有本机历史")
            return
        }
        val access = manager.snapshot.value.accessUrl
        if (access != null) {
            requireLocalDshIdle(access)
            val imported = requireNotNull(sharedDshConfiguration).readFromDsh(access)
            var keyChanged = false
            try {
                withContext(Dispatchers.IO) {
                    val secret = requireNotNull(modelProviderStore).exportPortable().getJSONObject("credentials").optJSONObject("deepseek")
                    val balance = requireNotNull(deepSeekBalanceStore)
                    val before = balance.readApiKeyForRequest()
                    val after = secret?.let { require(it.getString("type") == "api_key"); it.getString("key") }
                    keyChanged = before != after
                    if (keyChanged) {
                        if (after == null) balance.clearApiKey() else balance.setApiKey(after)
                    }
                    deepSeekConfigured = balance.hasKey()
                }
            } catch (failure: Exception) {
                deepSeekConfigured = false
                invalidateRemoteModels()
                throw IllegalStateException("DSH 设置已读取，但官方凭据保存未完成，请在模型页重新保存 API Key", failure)
            }
            modelProviderOrder = imported.providers.map { it.id }
            modelProviderEnabled = imported.providers.associate { it.id to it.enabled }
            if (keyChanged) invalidateRemoteModels()
            manager.stop()
        }
        val baseline = workspaceSnapshot()
        val result = requireNotNull(localDshHistory).read(baseline)
        check(workspaceSnapshot() == baseline) { "对话已变化，历史保留，请重新切换" }
        check(commitWorkspace(result.snapshot)) { "共享历史尚未安全保存，不能切换引擎" }
        workspaces.clear(); workspaces.addAll(result.snapshot.workspaces)
        conversations.clear(); conversations.addAll(result.snapshot.conversations)
        conversationMessages.clear()
        result.snapshot.messages.forEach { (id, values) ->
            conversationMessages[id] = mutableStateListOf<ChatMessage>().apply { addAll(values) }
        }
        nextMessageId = (result.snapshot.messages.values.flatten().maxOfOrNull { it.id } ?: 0L) + 1L
        persistenceRevision++
        requireNotNull(localDshHistory).acknowledge(result)
        modelRuntime?.refresh()
        val modelState = modelRuntime?.state?.value
        if (modelState != null && modelState.status != ModelRuntimeStatus.MISSING &&
            modelState.providers.providers.any { it.enabled && !it.protected }) modelRuntime?.refreshCatalog()
        localEngineSharingWarnings = result.warnings
        result.warnings.firstOrNull()?.let(::updateNotice)
        notifyTaskConfiguration()
    }

    private fun scheduleLocalProviderSynchronization(revision: Long) {
        if (engineMode != "dsh" || connectionMode != "local" || localEngineSwitchBusy ||
            revision == lastDshProviderRevision || runtimeStatus != RuntimeStatus.Running ||
            hasNativeRunningTasks() || dshProviderSyncJob?.isActive == true) return
        dshProviderSyncJob = viewModelScope.launch {
            try {
                localEngineGate.withLock {
                    repeat(4) {
                        if (engineMode != "dsh" || connectionMode != "local" || localEngineSwitchBusy) return@withLock
                        val current = requireNotNull(modelProviderStore).read().revision
                        if (current == lastDshProviderRevision) return@withLock
                        val access = requireNotNull(dshRuntimeManager?.snapshot?.value?.accessUrl) { "DSH 服务已停止" }
                        lastDshProviderRevision = requireNotNull(sharedDshConfiguration).writeToDsh(access).revision
                        dshSharedConfigurationError = null
                        modelRuntime?.refresh()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                dshSharedConfigurationError = failure.message ?: "提供商已在手机保存，DSH 同步未完成，请重试"
                updateNotice(requireNotNull(dshSharedConfigurationError))
            }
        }
    }

    fun retryLocalProviderSynchronization() {
        if (dshProviderSyncJob?.isActive == true) return
        lastDshProviderRevision = -1L
        modelProviderStore?.read()?.revision?.let(::scheduleLocalProviderSynchronization)
    }

    fun stopRuntime() {
        if (workspaceMode == WorkspaceMode.Dsh) cancelResponse()
        if (runtimeStatus == RuntimeStatus.Stopped || runtimeStatus == RuntimeStatus.Stopping) return
        val manager = dshRuntimeManager ?: return
        val previous = runtimeJob
        previous?.cancel()
        runtimeJob = viewModelScope.launch {
            try {
                previous?.join()
                manager.stop()
                updateNotice("DSH 服务已停止")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                updateNotice("DSH 服务停止失败：${error.message ?: "请重试"}")
            }
        }
    }

    fun restartRuntime() {
        if (workspaceDeletionBusy || localArchiveRecoveryFailed || localArchivePendingIds.isNotEmpty()) {
            updateNotice("请先完成本机共享历史的永久删除，再重启 DSH")
            return
        }
        if (localEngineSwitchBusy || runtimeStatus == RuntimeStatus.Stopping || dshInstallation?.state?.value?.installing == true) {
            updateNotice("DSH 正在停止或安装，请稍后再试")
            return
        }
        val manager = dshRuntimeManager ?: return
        if (workspaceMode == WorkspaceMode.Dsh) cancelResponse()
        val previous = runtimeJob
        previous?.cancel()
        runtimeJob = viewModelScope.launch {
            localEngineSwitchBusy = engineMode == "dsh"
            localEngineSwitchError = null
            try {
                // Await cleanup of the previous launch before creating the next generation.
                previous?.join()
                localEngineGate.withLock {
                    if (engineMode == "dsh") {
                        manager.snapshot.value.accessUrl?.let { requireLocalDshIdle(it) }
                        manager.stop()
                        startLocalDsh(sharedHistory = true)
                    } else manager.restart(localDshWorkspace())
                }
                updateNotice("DSH 服务已重启")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                runtimeStep = error.message ?: "DSH 服务重启失败"
                runtimeLastError = runtimeStep
                localEngineSwitchError = runtimeStep
                updateNotice("DSH 服务重启失败：${runtimeStep}")
            } finally { localEngineSwitchBusy = false }
        }
    }

    private suspend fun localDshWorkspace(id: String = selectedWorkspaceId): java.io.File = withContext(Dispatchers.IO) {
        val app = requireNotNull(applicationContext) { "工作区尚未初始化" }
        require(id.matches(Regex("[a-zA-Z0-9-]+"))) { "工作区身份无效" }
        val root = java.io.File(app.filesDir, "linux-workspaces").canonicalFile
        val directory = java.io.File(root, id).canonicalFile
        require(directory.parentFile == root && (directory.mkdirs() || directory.isDirectory)) {
            "无法准备当前工作区的 Linux 工作目录"
        }
        directory
    }

    fun checkDshRelease() {
        val controller = dshInstallation ?: return
        if (dshInstallJob?.isActive == true) return
        dshCheckJob?.cancel()
        dshCheckJob = viewModelScope.launch { controller.check(dshVersionChannel) }
    }

    /** A running web service may own tasks which are not represented by native chat. */
    fun dshUpdateNeedsInterruption(): Boolean = runtimeStatus != RuntimeStatus.Stopped || isResponding ||
        backgroundTasks?.tasks?.value.orEmpty().any {
            it.status in setOf(BackgroundTaskStatus.QUEUED, BackgroundTaskStatus.RUNNING, BackgroundTaskStatus.STOPPING)
        }

    fun installDshRelease(interruptConfirmed: Boolean = false) {
        val controller = dshInstallation ?: return
        if (dshInstallJob?.isActive == true || controller.state.value.available == null) return
        if (dshUpdateNeedsInterruption() && !interruptConfirmed) {
            updateNotice("DSH 或任务仍在运行，请先选择立即中断或稍后更新")
            return
        }
        dshInstallJob = viewModelScope.launch {
            try {
                if (interruptConfirmed) {
                    val response = responseJob
                    cancelResponse()
                    response?.join()
                    val tasks = backgroundTasks?.tasks?.value.orEmpty().filter {
                        it.status in setOf(BackgroundTaskStatus.QUEUED, BackgroundTaskStatus.RUNNING, BackgroundTaskStatus.STOPPING)
                    }
                    tasks.forEach { backgroundTasks?.stop(it.id) }
                    tasks.forEach { backgroundTasks?.awaitTerminal(it.id) }
                    runtimeJob?.cancelAndJoin()
                    dshRuntimeManager?.stop()
                }
                dshCheckJob?.join()
                modelRuntime?.shutdown()
                controller.install(downloadSource)
                dshRuntimeManager?.refresh()
                updateNotice("DSH 已安装并通过网页验证")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                updateNotice(failure.message ?: "DSH 运行包安装失败，原版本已保留")
            }
        }
    }

    fun cancelDshInstall() { dshInstallJob?.cancel() }

    suspend fun sendMessage(text: String): Boolean {
        if (connectionMode == "online") {
            if ((text.isBlank() && activeAttachments.isEmpty()) || sendPending || attachmentImportCount > 0) return false
            val selected = attachmentPreviews
            val binding = remoteWorkspaceScope ?: run { updateNotice("请先连接电脑并选择工作区"); return false }
            val workspace = displayWorkspaceId
            val generation = composerGeneration
            val selection = attachmentSelectionGeneration
            sendPending = true
            return try {
                val accepted = if (selected.isEmpty()) requireNotNull(remoteDsh).send(text, expectedScope = binding) else {
                    if (remoteDshState.connection?.optJSONObject("capabilities")?.optBoolean("remote_attachments") != true) {
                        updateNotice("电脑尚未启用附件传输，附件和草稿已保留（预留）")
                        return false
                    }
                    val store = requireNotNull(attachmentStore)
                    val sources = withContext(Dispatchers.IO) { selected.map { preview ->
                        val actual = requireNotNull(store.load(preview.id)) { "附件未保存在手机，请重新选择" }
                        RemoteAttachmentComposer.Source(actual.id, actual.name, actual.sizeBytes, store.resolveFile(actual.id))
                    } }
                    requireNotNull(remoteAttachmentComposer).send(binding, workspace, text, sources) {
                        withContext(Dispatchers.Main.immediate) {
                            require(connectionMode == "online" && displayWorkspaceId == workspace &&
                                composerGeneration == generation && attachmentSelectionGeneration == selection &&
                                attachmentPreviews == selected) { "工作区或附件选择已变化，草稿已保留" }
                        }
                    }
                }
                if (accepted && composerGeneration == generation && attachmentSelectionGeneration == selection && attachmentPreviews == selected)
                    clearAttachments()
                accepted
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { updateNotice(RemoteDshApplicationController.remoteFailure(failure)); false }
            finally { sendPending = false }
        }
        val caller = currentCoroutineContext()[Job]
        return withContext(NonCancellable + Dispatchers.Main.immediate) { sendMessageCommitted(text, caller) }
    }

    private suspend fun sendMessageCommitted(text: String, caller: Job?): Boolean {
        if (sendPending || workspaceDeletionBusy || localEngineSwitchBusy) return false
        dshRuntimeManager?.snapshot?.value?.accessUrl?.let { access ->
            try { requireLocalDshIdle(access) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                updateNotice(failure.message ?: "无法确认 DSH 任务状态，草稿已保留")
                return false
            }
        }
        if (workspaceSaveError != null) {
            updateNotice("还有未合并到索引的本地内容；请先重试保存，草稿已保留")
            return false
        }
        val store = workspaceStore ?: run {
            updateNotice("本地索引不可用，草稿已保留；请重新启动后重试")
            return false
        }
        if (!workspaceInitialized && initializationStarted) {
            updateNotice("正在读取本地工作目录，请稍候")
            return false
        }
        val input = text.trim()
        if (input.isEmpty() && activeAttachments.isEmpty()) return false
        if (attachmentImportCount > 0) {
            updateNotice("附件正在导入，请稍候")
            return false
        }
        if (isResponding) {
            updateNotice("回复进行中，可以先停止生成")
            return false
        }
        if (workspaceMode == WorkspaceMode.Lite) {
            if (engineMode != "official") {
                updateNotice("DSH 模型服务尚未接入，请选择官方轻引擎")
                return false
            }
            if (selectedModel.isExample || selectedModel.id == "unconfigured" ||
                (selectedModel.runtimeModel == null && !deepSeekConfigured)) {
                updateNotice("请在设置—模型中配置提供商、获取并选择真实模型")
                return false
            }
            if (!isModelProviderEnabled(selectedModel.runtimeModel?.providerId ?: "deepseek")) {
                updateNotice("请先启用所选模型提供商")
                return false
            }
            if (activeAttachments.any { it.textContent == null && !isImageAttachment(it) }) {
                updateNotice("当前仅支持文本、代码及 JPEG/PNG/GIF/WebP 图片，草稿已保留")
                return false
            }
            if (activeAttachments.any(::isImageAttachment) && !supportsImages(selectedModel)) {
                updateNotice("此模型未确认支持图片，请选择支持图片的模型或移除图片；草稿已保留")
                return false
            }
            if (deepSeekConfigurationBusy) {
                updateNotice("模型配置更新中，请稍候")
                return false
            }
        }
        if (workspaceMode == WorkspaceMode.Dsh && runtimeStatus != RuntimeStatus.Running) {
            updateNotice("DSH 界面预览尚未就绪，请等待启动演示或在菜单中重试")
            return false
        }
        val sentAttachments = attachmentPreviews
        val sentModel = selectedModel
        val sentReasoning = selectedReasoningOption
        val textBytes = sentAttachments.sumOf { it.textContent?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L }
        if (textBytes > 512L * 1024) {
            updateNotice("本次附件文本合计不能超过 512 KiB，请移除部分文件")
            return false
        }
        val modelInput = buildString {
            append(input.ifBlank { "请阅读并分析附带的文件或图片。" })
            sentAttachments.forEach { attachment ->
                attachment.textContent?.let { content ->
                    append("\n\n<attachment name=\"")
                    append(attachment.label.replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;"))
                    append("\">\n")
                    append(content)
                    append("\n</attachment>")
                }
            }
        }
        val attachmentText = if (sentAttachments.isEmpty()) "" else {
            if (workspaceMode == WorkspaceMode.Dsh) "[预览附件：${sentAttachments.joinToString("、") { it.label }}]"
            else ""
        }
        val displayedInput = listOf(input, attachmentText).filter { it.isNotEmpty() }.joinToString("\n\n")
        val conversationId = selectedConversationId
        val candidate = ChatMessage(
            nextMessageId, "user", displayedInput.ifBlank { "请阅读附带的文件。" },
            modelInput = if (workspaceMode == WorkspaceMode.Lite && sentAttachments.isNotEmpty()) modelInput else null,
            attachmentIds = sentAttachments.map { it.id }.filter { it in importedAttachments },
        )
        val targetMode = workspaceMode
        val targetComposer = composerGeneration
        val targetCredential = credentialGeneration
        sendPending = true
        var deferredSave = false
        var preparedHistory: List<DeepSeekChatClient.HistoryMessage> = emptyList()
        try {
            if (targetMode == WorkspaceMode.Lite) {
                try {
                    val source = conversationMessages[conversationId].orEmpty().toList() + candidate
                    val attachmentCatalog = importedAttachments.mapValues { (_, attachment) ->
                        isImageAttachment(attachment)
                    }
                    val possibleImages = source.flatMap { it.attachmentIds }.distinct().filter { attachmentId ->
                        attachmentCatalog[attachmentId] == true
                    }.associateWith { it }
                    val requiredImages = OfficialChatHistory.build(source, true, possibleImages, attachmentCatalog)
                        .flatMap { it.images }.distinct()
                    if (requiredImages.isNotEmpty() && !supportsImages(sentModel)) {
                        updateNotice("此对话包含图片，请选择支持图片的模型；草稿已保留")
                        return false
                    }
                    val imageData = withContext(Dispatchers.IO) {
                        check(requiredImages.sumOf { importedAttachments[it]?.sizeBytes ?: 0L } <= 2_800_000L) {
                            "图片合计过大"
                        }
                        attachmentStore?.readImageDataUrls(requiredImages).orEmpty()
                    }
                    check(imageData.size == requiredImages.size) { "图片读取失败" }
                    preparedHistory = listOf(OfficialTokenSavingPolicy.applyToSystemMessage(
                        OfficialToolSession.systemMessage(), tokenSavingMode)) +
                        OfficialChatHistory.build(source, true, imageData, attachmentCatalog)
                    val toolSchemas = OfficialToolContract.schemas(
                        includeWeather = true, includeWeatherSelection = true, includeWeb = true,
                        includeScripts = true, includeScriptEscalation = true, includePermissions = true, includeSkills = true,
                    )
                    val tools = List(toolSchemas.length()) { index ->
                        val function = toolSchemas.getJSONObject(index).getJSONObject("function")
                        DeepSeekChatClient.FunctionTool(function.getString("name"),
                            function.getString("description"), function.getJSONObject("parameters"))
                    }
                    if (sentModel.runtimeModel == null) withContext(Dispatchers.IO) {
                        val reasoning = officialReasoning(sentModel, sentReasoning)
                        checkNotNull(deepSeekClient).validateRequest(DeepSeekChatClient.Request(
                            sentModel.id, preparedHistory, reasoning.thinking,
                            officialEngineStreaming, tools,
                            reasoningEffort = reasoning.reasoningEffort, modelCapabilities = sentModel.officialCapabilities))
                    }
                } catch (_: Exception) {
                    updateNotice("图片读取失败或完整上下文超过 4 MiB，请减少附件或新建对话；草稿已保留")
                    return false
                }
            }
            saveJob?.cancelAndJoin()
            saveJob = null
            if (workspaceSaveError != null) {
                updateNotice("本地保存尚未完成；请先重试保存，草稿已保留")
                return false
            }
            val revision = persistenceRevision
            val base = workspaceSnapshot()
            // A cancelled debounce may have held the final reply. Persist it before testing a new turn.
            if (!commitWorkspace(base)) return false
            val proposed = base.copy(messages = base.messages + (conversationId to
                (base.messages[conversationId].orEmpty() + candidate)))
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    persistenceMutex.withLock { store.save(proposed) }
                }
            } catch (error: Exception) {
                // The candidate is not accepted; keep the live draft and do not call the provider.
                updateNotice("发送前本地保存失败，草稿已保留；请释放容量或修复存储后重试发送：${error.message ?: "写入失败"}")
                deferredSave = persistenceRevision != revision
                return false
            }
            val targetMessages = conversationMessages[conversationId]
            if (targetMessages == null || conversations.none { it.id == conversationId && it.archivedAtMillis == null }) {
                deferredSave = true
                updateNotice("对话已变更，未调用模型；草稿已保留")
                return false
            }
            targetMessages.add(candidate)
            nextMessageId = maxOf(nextMessageId, candidate.id + 1)
            deferredSave = persistenceRevision != revision
            if (targetMode != workspaceMode || conversationId != selectedConversationId ||
                targetComposer != composerGeneration || targetCredential != credentialGeneration ||
                sentModel != selectedModel || deepSeekConfigurationBusy ||
                sentReasoning != selectedReasoningOption || sentAttachments != attachmentPreviews ||
                persistenceRevision != revision || caller?.isActive == false) {
                deferredSave = true
                updateNotice("消息已本地保存，但对话、附件或模型配置已变更；未调用模型，草稿已保留")
                return false
            }
            clearAttachments()
        } finally {
            sendPending = false
            if (deferredSave) persistWorkspaces()
        }
        if (workspaceMode == WorkspaceMode.Lite && !sentModel.isExample) {
            if (sentModel.runtimeModel != null) sendPiAiMessage(conversationId,
                if (sentAttachments.isEmpty()) input else modelInput, sentModel, sentReasoning, preparedHistory)
            else sendDeepSeekMessage(conversationId, if (sentAttachments.isEmpty()) input else modelInput,
                    sentModel, sentReasoning, preparedHistory)
            return true
        }
        val id = nextMessageId++
        val generation = ++responseGeneration
        activeResponseId = id
        activeResponseConversationId = conversationId
        val reducer = AssistantStreamReducer(source = AssistantStreamSource.DEMO)
        activeResponseReducer = reducer
        val streamMessageId = "demo-$id"
        activeStreamMessageId = streamMessageId
        var sequence = 0L
        fun emit(event: AssistantStreamEvent) {
            val snapshot = reducer.apply(event).snapshot
            replaceStreamMessage(conversationId, id, snapshot)
        }
        messages += ChatMessage(id, "assistant", "演示回复准备中…", reducer.snapshot())
        isResponding = true
        responseJob = viewModelScope.launch {
            try {
                delay(650)
                ensureActive()
                if (generation != responseGeneration) return@launch
                emit(AssistantStreamEvent.MessageStarted(sequence++, streamMessageId))
                val conversationIndex = conversations.indexOfFirst { it.id == conversationId }
                if (conversationIndex >= 0 && !conversations[conversationIndex].titleManuallySet &&
                    conversations[conversationIndex].title == "新对话") {
                    conversations[conversationIndex] = conversations[conversationIndex].copy(
                        title = input.replace('\n', ' ').trim().take(30).ifBlank { "附件对话" },
                    )
                    persistWorkspaces()
                }
                val quoted = input.take(72).let { if (input.length > 72) "$it…" else it }
                val previewConfiguration = "本次预览：${sentModel.name}" +
                    (sentReasoning?.let { " · ${it.label}" } ?: " · 无推理档位")
                val receivedText = if (quoted.isEmpty()) "你添加了预览附件。" else "我收到了：\"$quoted\""
                val attachmentNote = if (sentAttachments.isEmpty()) "" else {
                    "\n对话只记录附件名称，未向模型发送文件内容，也未上传附件。"
                }
                val answer = "这是一段本地演示回复，未连接模型，也没有运行真实 DSH。\n\n" +
                    "$previewConfiguration\n\n$receivedText$attachmentNote\n\n" +
                    "真正接入后，你可以在这里讨论想法、整理材料、编写代码，并查看任务进度。" +
                    "当前可以体验消息流式呈现、停止生成、新建对话和运行状态切换。"
                for (part in answer.chunked(7)) {
                    ensureActive()
                    if (generation != responseGeneration) return@launch
                    emit(AssistantStreamEvent.TextDelta(sequence++, streamMessageId, part))
                    delay(65)
                }
                emit(AssistantStreamEvent.ContentBlockDone(sequence++, streamMessageId, "text"))
                emit(AssistantStreamEvent.MessageCompleted(sequence++, streamMessageId, "demo_end"))
                replaceStreamMessage(conversationId, id, reducer.finish())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                emit(AssistantStreamEvent.MessageFailed(sequence++, streamMessageId, error.message ?: "demo_failed"))
                replaceStreamMessage(conversationId, id, reducer.finish())
            } finally {
                if (generation == responseGeneration) {
                    withContext(NonCancellable) {
                        saveJob?.cancelAndJoin()
                        saveJob = null
                        commitWorkspace(workspaceSnapshot())
                    }
                }
                if (generation == responseGeneration) {
                    isResponding = false
                    activeResponseId = null
                    activeResponseConversationId = null
                    activeResponseReducer = null
                    activeStreamMessageId = null
                    responseJob = null
                }
            }
        }
        return true
    }

    /** True when [message] is the latest failed official reply in the selected conversation. */
    fun canRetryResponse(message: ChatMessage): Boolean {
        if (connectionMode == "online") return false
        val snapshot = message.streamSnapshot ?: return false
        val list = conversationMessages[selectedConversationId] ?: return false
        return message.role == "assistant" && snapshot.source == AssistantStreamSource.BACKEND &&
            snapshot.isFinal && snapshot.messages.lastOrNull()?.status == AssistantMessageStatus.FAILED &&
            list.lastOrNull()?.id == message.id &&
            list.getOrNull(list.size - 2)?.role == "user"
    }

    /**
     * Resend the user turn that produced a failed official reply. The failed reply is removed so
     * [OfficialChatHistory] pairs the user turn with the new reply; the user message is unchanged.
     */
    suspend fun retryFailedResponse(assistantMessageId: Long): Boolean =
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            val conversationId = selectedConversationId
            val failed = conversationMessages[conversationId]?.lastOrNull()
            if (failed == null || failed.id != assistantMessageId || !canRetryResponse(failed)) {
                updateNotice("只能重试当前对话中最后一条失败的回复")
                return@withContext false
            }
            if (sendPending || isResponding) {
                updateNotice("回复进行中，请稍后重试")
                return@withContext false
            }
            if (workspaceSaveError != null) {
                updateNotice("还有未合并到索引的本地内容；请先重试保存")
                return@withContext false
            }
            if (workspaceMode != WorkspaceMode.Lite || engineMode != "official") {
                updateNotice("仅官方轻引擎支持重试回复")
                return@withContext false
            }
            val model = selectedModel
            val reasoning = selectedReasoningOption
            if (model.isExample || model.id == "unconfigured" || !deepSeekConfigured ||
                !isModelProviderEnabled("deepseek") || deepSeekConfigurationBusy || deepSeekClient == null) {
                updateNotice("请在设置—模型中确认 DeepSeek 已配置并启用后重试")
                return@withContext false
            }
            val composer = composerGeneration
            val credential = credentialGeneration
            sendPending = true
            var history: List<DeepSeekChatClient.HistoryMessage>
            var user: ChatMessage
            try {
                val list = conversationMessages[conversationId].orEmpty().toList()
                // Same history rules as a fresh send: every turn before and including the user turn.
                val source = list.dropLast(1)
                user = source.last()
                val catalog = importedAttachments.mapValues { (_, attachment) -> isImageAttachment(attachment) }
                val possibleImages = source.flatMap { it.attachmentIds }.distinct()
                    .filter { catalog[it] == true }.associateWith { it }
                try {
                    val requiredImages = OfficialChatHistory.build(source, true, possibleImages, catalog)
                        .flatMap { it.images }.distinct()
                    if (requiredImages.isNotEmpty() && !supportsImages(model)) {
                        updateNotice("此对话包含图片，请选择支持图片的模型后重试")
                        return@withContext false
                    }
                    val imageData = withContext(Dispatchers.IO) {
                        check(requiredImages.sumOf { importedAttachments[it]?.sizeBytes ?: 0L } <= 2_800_000L) {
                            "图片合计过大"
                        }
                        attachmentStore?.readImageDataUrls(requiredImages).orEmpty()
                    }
                    check(imageData.size == requiredImages.size) { "图片读取失败" }
                    history = listOf(OfficialTokenSavingPolicy.applyToSystemMessage(
                        OfficialToolSession.systemMessage(), tokenSavingMode)) +
                        OfficialChatHistory.build(source, true, imageData, catalog)
                } catch (error: Exception) {
                    updateNotice("重试准备失败：${error.message ?: "图片或上下文不可用"}")
                    return@withContext false
                }
                // Abort if anything changed while images were read.
                val current = conversationMessages[conversationId]
                if (current == null || current.lastOrNull()?.id != assistantMessageId ||
                    conversationId != selectedConversationId || composer != composerGeneration ||
                    credential != credentialGeneration || model != selectedModel ||
                    reasoning != selectedReasoningOption || isResponding) {
                    updateNotice("对话或模型配置已变更，未重试")
                    return@withContext false
                }
                current.removeAt(current.lastIndex)
            } finally {
                sendPending = false
            }
            persistWorkspaces()
            // Match the original send: attachments are folded into modelInput, not text.
            sendDeepSeekMessage(conversationId, user.modelInput ?: user.text, model, reasoning, history)
            true
        }

    fun responseFiles(message: ChatMessage): List<OfficialToolOutput> {
        val ids = message.streamSnapshot?.toolCalls.orEmpty().mapNotNull { call ->
            if (call.name != "write_text_file" || call.status != AssistantStreamEvent.ToolStatus.SUCCEEDED) null
            else runCatching { JSONObject(call.result.orEmpty()).getJSONObject("data").getString("output_id") }.getOrNull()
        }.toSet()
        return ids.mapNotNull { officialOutputs[it] }
    }

    fun responseRunMetrics(message: ChatMessage): ChatRunMetrics? {
        val snapshot = message.streamSnapshot ?: return null
        if (snapshot.source != AssistantStreamSource.BACKEND) return null
        if (connectionMode == "local" && activeResponseId == message.id) {
            activeRunMetrics?.let { return it.snapshot(android.os.SystemClock.elapsedRealtime()) }
        }
        return chatRunMetricsFromSnapshot(message.id, displayConversationId, snapshot,
            snapshot.runElapsedMillis, snapshot.runRounds, snapshot.runSteps)
    }

    fun activeTaskNotificationIdentity(): Pair<String, Long>? {
        if (connectionMode == "online") return remoteDshState.selectedView?.messages
            ?.lastOrNull { it.role == "assistant" }?.let { displayConversationId to it.id }
        if (activeRunMetrics == null) return null
        val conversationId = activeResponseConversationId ?: return null
        val responseId = activeResponseId ?: return null
        return conversationId to responseId
    }

    fun taskNotificationStatus(identity: Pair<String, Long>): AssistantRunStatus? =
        if (connectionMode == "online") remoteDshState.projection?.sessions?.get(identity.first)?.activity?.runStatus
        else conversationMessages[identity.first]?.firstOrNull { it.id == identity.second }?.streamSnapshot
            ?.takeIf { it.source == AssistantStreamSource.BACKEND }?.runStatus

    private fun measuredSnapshot(snapshot: AssistantStreamSnapshot): AssistantStreamSnapshot {
        val metrics = activeRunMetrics?.snapshot(android.os.SystemClock.elapsedRealtime()) ?: return snapshot
        return snapshot.copy(runElapsedMillis = metrics.elapsedMillis,
            runRounds = metrics.rounds, runSteps = metrics.steps,
            runStatus = metrics.status.toAssistantRunStatus())
    }

    fun exportGeneratedFile(outputId: String, destination: Uri) {
        val store = officialOutputStore ?: return
        val output = officialOutputs[outputId] ?: run {
            updateNotice("文件不可用，请重新生成")
            return
        }
        val context = applicationContext ?: return
        if (destination.scheme != "content") {
            updateNotice("请选择系统文件保存位置")
            return
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(destination, "wt")?.use {
                        store.copyTo(output.id, it)
                    } ?: error("destination_unavailable")
                }
                updateNotice("已保存 ${output.filename}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                updateNotice("文件保存失败，请检查目标位置后重试")
            }
        }
    }

    private fun isImageAttachment(attachment: AttachmentPreview): Boolean =
        attachment.mimeType in setOf("image/jpeg", "image/png", "image/gif", "image/webp") &&
            attachment.textContent == null && attachment.id in importedAttachments

    private fun sendDeepSeekMessage(
        conversationId: String, input: String, model: ModelChoice, option: ReasoningOption?,
        history: List<DeepSeekChatClient.HistoryMessage>,
    ) {
        val client = deepSeekClient ?: return
        val modelId = model.id
        val reasoning = officialReasoning(model, option)
        val id = nextMessageId++
        val generation = ++responseGeneration
        activeResponseId = id
        activeResponseConversationId = conversationId
        val reducer = AssistantStreamReducer(source = AssistantStreamSource.BACKEND)
        activeResponseReducer = reducer
        val runMetrics = ChatRunMetricsCollector(id, conversationId, android.os.SystemClock.elapsedRealtime())
        activeRunMetrics = runMetrics
        activeStreamMessageId = null
        val outputStore = checkNotNull(officialOutputStore)
        val requestCredential = credentialGeneration
        val dispatcher = OfficialToolDispatcher({ backgroundTasks }, outputStore, conversationId, { modelId },
            scriptProvider = applicationContext?.let { context ->
                val workspaceId = conversations.firstOrNull { it.id == conversationId }?.workspaceId
                workspaceId?.let { OfficialLinuxScriptProvider(context, it, OfficialSandboxPolicy(localSandboxMode)) { downloadSource } }
            },
            weatherProvider = object : OfficialToolWeatherSelectionProvider {
                private val weather = OfficialWeatherClient()
                override suspend fun weather(city: String, countryCode: String?): JSONObject =
                    weather.getWeather(city, countryCode)
                override suspend fun weather(city: String, countryCode: String?, locationId: Long): JSONObject =
                    weather.getWeather(city, countryCode, locationId)
            },
            webProvider = OfficialWebClient(),
            permissionProvider = applicationContext?.let(ApplicationPermissionBridge::get),
            skillProvider = officialSkills(conversationId),
            mutationPolicy = officialMutationPolicy(conversationId),
        )
        val streaming = officialEngineStreaming
        messages += ChatMessage(id, "assistant", "正在连接 DeepSeek…", reducer.snapshot())
        isResponding = true
        responseJob = viewModelScope.launch {
            try {
                OfficialToolSession.generate(
                    client,
                    DeepSeekChatClient.Request(
                        model = modelId,
                        history = history,
                        thinking = reasoning.thinking,
                        streaming = streaming,
                        reasoningEffort = reasoning.reasoningEffort,
                        modelCapabilities = model.officialCapabilities,
                    ),
                    dispatcher,
                    onOutput = { result ->
                        withContext(Dispatchers.Main.immediate) {
                            ensureActive()
                            check(generation == responseGeneration && requestCredential == credentialGeneration)
                            result.outputs.forEach { officialOutputs[it.id] = it }
                        }
                    },
                    onEvent = { event ->
                        withContext(Dispatchers.Main.immediate) {
                            ensureActive()
                            if (generation == responseGeneration) {
                                val snapshot = reducer.apply(event).snapshot
                                runMetrics.onEvent(event, android.os.SystemClock.elapsedRealtime())
                                snapshot.messages.lastOrNull()?.let { activeStreamMessageId = it.messageId }
                                replaceStreamMessage(conversationId, id, measuredSnapshot(snapshot))
                                if (event is AssistantStreamEvent.MessageStarted) {
                                    val index = conversations.indexOfFirst { it.id == conversationId }
                                    if (index >= 0 && !conversations[index].titleManuallySet &&
                                        conversations[index].title == "新对话") {
                                        conversations[index] = conversations[index].copy(
                                            title = input.replace('\n', ' ').trim().take(30).ifBlank { "新对话" },
                                        )
                                        persistWorkspaces()
                                    }
                                }
                            }
                        }
                    },
                )
                ensureActive()
                if (generation == responseGeneration) {
                    runMetrics.finish(ChatRunStatus.Completed, android.os.SystemClock.elapsedRealtime())
                    replaceStreamMessage(conversationId, id, measuredSnapshot(reducer.finish()))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ensureActive()
                if (generation != responseGeneration) return@launch
                val sequence = reducer.snapshot().nextExpectedSequenceNumber ?: 0L
                reducer.apply(
                    AssistantStreamEvent.MessageFailed(
                        sequence, "deepseek-request-error-$id", "DeepSeek 请求失败，请重试", "REQUEST_FAILED",
                    ),
                )
                runMetrics.finish(ChatRunStatus.Failed, android.os.SystemClock.elapsedRealtime())
                replaceStreamMessage(conversationId, id, measuredSnapshot(reducer.finish()))
            } finally {
                if (generation == responseGeneration) {
                    withContext(NonCancellable) {
                        saveJob?.cancelAndJoin()
                        saveJob = null
                        commitWorkspace(workspaceSnapshot())
                    }
                }
                if (generation == responseGeneration) {
                    isResponding = false
                    activeResponseId = null
                    activeResponseConversationId = null
                    activeResponseReducer = null
                    activeStreamMessageId = null
                    activeRunMetrics = null
                    responseJob = null
                }
            }
        }
    }

    private fun sendPiAiMessage(conversationId: String, input: String, model: ModelChoice,
        option: ReasoningOption?, history: List<DeepSeekChatClient.HistoryMessage>) {
        val runtime = modelRuntime ?: return
        val nativeModel = requireNotNull(model.runtimeModel)
        val precedingMessageId = conversationMessages[conversationId]?.dropLast(1)?.lastOrNull()?.id
        val id = nextMessageId++
        val generation = ++responseGeneration
        activeResponseId = id
        activeResponseConversationId = conversationId
        val reducer = AssistantStreamReducer(source = AssistantStreamSource.BACKEND)
        activeResponseReducer = reducer
        val metrics = ChatRunMetricsCollector(id, conversationId, android.os.SystemClock.elapsedRealtime())
        activeRunMetrics = metrics
        activeStreamMessageId = null
        val dispatcher = OfficialToolDispatcher({ backgroundTasks }, requireNotNull(officialOutputStore),
            conversationId, { model.id }, scriptProvider = applicationContext?.let { app ->
                conversations.firstOrNull { it.id == conversationId }?.workspaceId?.let { workspace ->
                    OfficialLinuxScriptProvider(app, workspace, OfficialSandboxPolicy(localSandboxMode)) { downloadSource }
                }
            }, weatherProvider = object : OfficialToolWeatherSelectionProvider {
                private val weather = OfficialWeatherClient()
                override suspend fun weather(city: String, countryCode: String?): JSONObject = weather.getWeather(city, countryCode)
                override suspend fun weather(city: String, countryCode: String?, locationId: Long): JSONObject =
                    weather.getWeather(city, countryCode, locationId)
            }, webProvider = OfficialWebClient(), permissionProvider = applicationContext?.let(ApplicationPermissionBridge::get),
            skillProvider = officialSkills(conversationId), mutationPolicy = officialMutationPolicy(conversationId))
        val streaming = officialEngineStreaming
        conversationMessages.getValue(conversationId).add(ChatMessage(id, "assistant", "正在连接 ${model.provider}…", reducer.snapshot()))
        isResponding = true
        responseJob = viewModelScope.launch {
            try {
                val context = withContext(Dispatchers.IO) {
                    nativeTranscriptStore?.prepare(conversationId, nativeModel, precedingMessageId, history)
                        ?: piContextFromHistory(history, nativeModel)
                }
                val result = OfficialMultiProviderSession.generate(runtime,
                    PiAiChatRequest(nativeModel.providerId, nativeModel.id, context,
                        option?.id, requestId = "native-$id-${UUID.randomUUID()}", allowPublicThinking = true),
                    dispatcher,
                    onOutput = { output -> withContext(Dispatchers.Main.immediate) {
                        ensureActive()
                        check(generation == responseGeneration)
                        output.outputs.forEach { officialOutputs[it.id] = it }
                    } },
                    onEvent = { event -> withContext(Dispatchers.Main.immediate) {
                        ensureActive()
                        if (generation == responseGeneration) {
                            val snapshot = reducer.apply(event).snapshot
                            metrics.onEvent(event, android.os.SystemClock.elapsedRealtime())
                            snapshot.messages.lastOrNull()?.let { activeStreamMessageId = it.messageId }
                            if (streaming || event is AssistantStreamEvent.MessageCompleted || event is AssistantStreamEvent.MessageFailed ||
                                event is AssistantStreamEvent.MessageIncomplete)
                                replaceStreamMessage(conversationId, id, measuredSnapshot(snapshot))
                            if (event is AssistantStreamEvent.MessageStarted) {
                                val index = conversations.indexOfFirst { it.id == conversationId }
                                if (index >= 0 && !conversations[index].titleManuallySet &&
                                    conversations[index].title == "新对话") {
                                    conversations[index] = conversations[index].copy(title = input.replace('\n', ' ').take(30))
                                }
                            }
                        }
                    } }, onTranscript = { transcript -> withContext(Dispatchers.IO) {
                        nativeTranscriptStore?.save(conversationId, nativeModel, id, transcript, completed = false)
                    } })
                ensureActive()
                if (generation == responseGeneration) {
                    withContext(Dispatchers.IO) {
                        nativeTranscriptStore?.save(conversationId, nativeModel, id, result.transcript(), completed = result.completed)
                    }
                    metrics.finish(if (result.completed) ChatRunStatus.Completed else ChatRunStatus.Failed,
                        android.os.SystemClock.elapsedRealtime())
                    replaceStreamMessage(conversationId, id, measuredSnapshot(reducer.finish()))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                ensureActive()
                if (generation == responseGeneration) {
                    val reason = if (failure is ModelRuntimeException) failure.message.orEmpty() else "模型请求失败，请检查提供商认证与运行组件"
                    reducer.apply(AssistantStreamEvent.MessageFailed(reducer.snapshot().nextExpectedSequenceNumber ?: 0L,
                        "native-model-error-$id", reason, (failure as? ModelRuntimeException)?.code ?: "REQUEST_FAILED"))
                    metrics.finish(ChatRunStatus.Failed, android.os.SystemClock.elapsedRealtime())
                    replaceStreamMessage(conversationId, id, measuredSnapshot(reducer.finish()))
                }
            } finally {
                if (generation == responseGeneration) {
                    withContext(NonCancellable) { saveJob?.cancelAndJoin(); saveJob = null; commitWorkspace(workspaceSnapshot()) }
                    isResponding = false
                    activeResponseId = null
                    activeResponseConversationId = null
                    activeResponseReducer = null
                    activeStreamMessageId = null
                    activeRunMetrics = null
                    responseJob = null
                }
            }
        }
    }

    private suspend fun runMultiProviderBackgroundTask(request: BackgroundTaskRequest, model: ModelChoice,
        emit: suspend (String) -> Unit): BackgroundTaskResult {
        val runtime = requireNotNull(modelRuntime)
        val native = requireNotNull(model.runtimeModel)
        val reducer = AssistantStreamReducer(source = AssistantStreamSource.BACKEND)
        val system = delegatedTaskSystemMessage()
        val dispatcher = delegatedTaskDispatcher(request, model.id)
        val reasoning = if (selectedModel.id == model.id) selectedReasoningOption?.id else native.defaultReasoning
        var lastProgressAt = 0L
        runtime.withSession(native.providerId) {
            val result = OfficialMultiProviderSession.generate(runtime, PiAiChatRequest(native.providerId, native.id,
                piContextFromHistory(listOf(system, DeepSeekChatClient.HistoryMessage("user", request.prompt)), native),
                reasoning = reasoning, requestId = "task-${request.id}", allowPublicThinking = true),
                dispatcher, onOutput = {}, onEvent = { event ->
                val snapshot = reducer.apply(event).snapshot
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastProgressAt >= 100L) {
                    lastProgressAt = now
                    emit(snapshot.messages.flatMap { it.blocks }.filterIsInstance<AssistantContentBlockSnapshot.Text>()
                        .joinToString("") { it.text }.takeLast(4096))
                }
            })
            currentCoroutineContext().ensureActive()
            check(result.completed) { "task_incomplete" }
        }
        val finished = reducer.finish()
        val text = finished.messages.flatMap { it.blocks }.filterIsInstance<AssistantContentBlockSnapshot.Text>()
            .joinToString("") { it.text }
        check(text.isNotBlank()) { "task_empty" }
        return BackgroundTaskResult(text)
    }

    private fun delegatedTaskSystemMessage(): DeepSeekChatClient.HistoryMessage {
        val system = OfficialToolSession.systemMessage()
        return OfficialTokenSavingPolicy.applyToSystemMessage(
        system.copy(content = system.content +
            "\nThis is an independent delegated task. Use only the supplied tools, including installed skills and scripts. " +
            "Never create subagents or background tasks. Execution device is this Android phone; report actual tool outcomes."),
        tokenSavingMode)
    }

    private fun delegatedTaskDispatcher(request: BackgroundTaskRequest, modelId: String): OfficialToolDispatcher {
        check(!request.allowTaskCreation) { "递归任务创建不可用" }
        val app = requireNotNull(applicationContext)
        val workspace = conversations.firstOrNull { it.id == request.parentConversationId }?.workspaceId ?: selectedWorkspaceId
        return OfficialToolDispatcher({ null }, requireNotNull(officialOutputStore),
            request.parentConversationId ?: request.id, { modelId },
            scriptProvider = OfficialLinuxScriptProvider(app, workspace, OfficialSandboxPolicy(localSandboxMode)) { downloadSource },
            webProvider = OfficialWebClient(), permissionProvider = ApplicationPermissionBridge.get(app),
            skillProvider = OfficialSkillAdapter(app, workspace), allowTaskCreation = false, allowFileOutputs = false)
    }

    private fun officialMutationPolicy(conversationId: String): OfficialToolMutationPolicy {
        val workspace = conversations.firstOrNull { it.id == conversationId }?.workspaceId
            ?: return OfficialToolMutationPolicy { _, _ -> false }
        return OfficialNativeMutationPolicy(OfficialSandboxPolicy(localSandboxMode), workspace)
    }

    fun cancelResponse() {
        if (connectionMode == "online") {
            if (remoteAttachmentProgress.busy) { remoteAttachmentComposer?.cancel(); return }
            remoteOperation { remoteDsh?.cancelTask() }
            return
        }
        if (!isResponding && responseJob == null) return
        ++responseGeneration
        deepSeekClient?.cancel()
        responseJob?.cancel()
        responseJob = null
        activeRunMetrics?.finish(ChatRunStatus.Cancelled, android.os.SystemClock.elapsedRealtime())
        activeResponseId?.let { id ->
            val conversationId = activeResponseConversationId ?: selectedConversationId
            val targetMessages = conversationMessages[conversationId]
            val message = targetMessages?.firstOrNull { it.id == id }
            if (message != null) {
                val real = message.streamSnapshot?.source == AssistantStreamSource.BACKEND
                val text = if (real) {
                    message.streamSnapshot?.messages.orEmpty().flatMap { it.blocks }
                        .filterIsInstance<AssistantContentBlockSnapshot.Text>().joinToString("") { it.text }
                } else if (message.text == "演示回复准备中…") {
                    "演示回复已取消 · 未调用模型"
                } else {
                    "${message.text}\n\n[演示生成已停止]"
                }
                val reducer = activeResponseReducer
                val snapshot = reducer?.let {
                    val beforeCancel = it.snapshot()
                    val writableMessageId = activeStreamMessageId?.takeIf { messageId ->
                        beforeCancel.messages.any { streamMessage ->
                            streamMessage.messageId == messageId &&
                                streamMessage.status in setOf(
                                    AssistantMessageStatus.CREATED, AssistantMessageStatus.IN_PROGRESS,
                                )
                        }
                    }
                    // A completed model round cannot accept cancellation while its tools are waiting.
                    val cancellationId = writableMessageId ?: if (real) {
                        "official-session-cancel-$id"
                    } else "demo-session-cancel-$id"
                    it.apply(AssistantStreamEvent.MessageCancelled(
                        beforeCancel.nextExpectedSequenceNumber ?: 0L,
                        cancellationId,
                        "user_cancelled",
                    ))
                    measuredSnapshot(it.finish())
                }
                val index = targetMessages.indexOfFirst { it.id == id }
                if (index >= 0) targetMessages[index] = message.copy(text = text, streamSnapshot = snapshot)
            }
        }
        activeResponseId = null
        activeResponseConversationId = null
        activeResponseReducer = null
        activeStreamMessageId = null
        activeRunMetrics = null
        isResponding = false
        updateNotice("已停止生成")
        persistWorkspaces()
    }

    fun newConversation() {
        if (connectionMode == "online") {
            clearAttachments()
            remoteOperation { remoteDsh?.newSession(); composerGeneration++ }
            return
        }
        if (workspaceDeletionBusy) return
        if (initializationStarted && !workspaceInitialized) return
        val selectedEmptyConversation = conversations.firstOrNull {
            it.id == selectedConversationId && it.workspaceId == selectedWorkspaceId && it.archivedAtMillis == null
        }?.takeIf { conversationMessages[it.id].isNullOrEmpty() }
        if (selectedEmptyConversation != null) {
            val removedDrafts = pruneUnusedEmptyDrafts()
            clearAttachments()
            composerGeneration++
            updateNotice("当前已是${selectedWorkspace.name}的空白对话")
            if (removedDrafts > 0) persistWorkspaces()
            return
        }

        // Older versions could leave many hidden empty drafts. Remove only unselected drafts
        // that contain zero messages before applying the active-session limit.
        val removedDrafts = pruneUnusedEmptyDrafts()
        if (conversations.size >= MAX_WORKSPACE_CONVERSATIONS) {
            if (removedDrafts > 0) persistWorkspaces()
            updateNotice("最多保留 100 个会话（含归档）；请删除部分归档对话")
            return
        }
        cancelResponse()
        clearAttachments()
        if (createEmptyConversation(selectedWorkspaceId) == null) {
            if (removedDrafts > 0) persistWorkspaces()
            return
        }
        updateNotice("已在${selectedWorkspace.name}新建对话")
        persistWorkspaces()
    }

    fun installExtension(id: String) {
        val index = extensions.indexOfFirst { it.id == id }
        if (index < 0) {
            updateNotice("找不到该演示扩展")
            return
        }
        val extension = extensions[index]
        if (extension.installed) {
            updateNotice("${extension.name}已添加到演示环境")
            return
        }
        extensions[index] = extension.copy(installed = true, enabled = true)
        updateNotice("已添加${extension.name} · 演示操作，没有下载文件")
    }

    fun toggleExtension(id: String) {
        val index = extensions.indexOfFirst { it.id == id }
        if (index < 0) {
            updateNotice("找不到该演示扩展")
            return
        }
        val extension = extensions[index]
        if (!extension.installed) {
            updateNotice("请先添加${extension.name}")
            return
        }
        extensions[index] = extension.copy(enabled = !extension.enabled)
        updateNotice("${extension.name}已${if (extension.enabled) "停用" else "启用"} · 仅演示状态")
    }

    fun updateNotice(text: String) {
        lastNotice = text
    }

    fun refreshRemoteDsh() = remoteOperation { remoteDsh?.refresh() }
    fun refreshConversationArchives() {
        if (connectionMode != "online") { purgeExpiredArchives(); return }
        val binding = remoteWorkspaceScope ?: return
        remoteOperation { remoteDsh?.refreshArchive(expectedScope = binding) }
    }

    private fun requestRemoteLifecycleMutation(binding: RemoteDshScope,
        action: suspend (RemoteDshApplicationController, RemoteDshScope) -> Unit): Boolean {
        if (connectionMode != "online" || workspaceDeletionBusy || remoteDshState.busy ||
            !remoteWorkspaceLifecycleReady || remoteWorkspaceScope != binding) return false
        val controller = remoteDsh ?: return false
        val previousSelection = remoteDshState.selectedWorkspaceId to remoteDshState.selectedSessionId
        workspaceDeletionBusy = true
        updateNotice("正在等待电脑提交操作…")
        viewModelScope.launch {
            try {
                binding.requireCurrent(requireNotNull(remoteDshState.connection), requireOnline = true)
                action(controller, binding)
                check(connectionMode == "online" && remoteWorkspaceScope == binding) { "电脑配对关系已改变，请重新读取状态" }
                if ((remoteDshState.selectedWorkspaceId to remoteDshState.selectedSessionId) != previousSelection) {
                    clearAttachments()
                    composerDraftText = ""
                    composerGeneration++
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { updateNotice(RemoteDshApplicationController.remoteFailure(failure)) }
            finally { workspaceDeletionBusy = false }
        }
        return true
    }
    fun updateRemoteForeground(foreground: Boolean) {
        remoteDsh?.setForeground(foreground, powerSavingMode)
        remoteArtifactDownloads?.onPowerState(foreground, powerSavingMode)
        remoteAttachmentComposer?.onPowerState(foreground, powerSavingMode)
        notificationMonitor?.reconcile(foreground, powerSavingMode)
        connectVisibilityJob?.cancel()
        connectVisibilityJob = viewModelScope.launch {
            try { connectPluginHost?.setBackgroundPaused(!foreground && powerSavingMode) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { updateNotice("连接插件暂时无法恢复，请在插件页重试") }
        }
    }

    private fun officialSkills(conversationId: String): OfficialToolSkillProvider? {
        val app = applicationContext ?: return null
        val workspace = conversations.firstOrNull { it.id == conversationId }?.workspaceId ?: return null
        return OfficialSkillAdapter(app, workspace)
    }
    fun selectRemoteSandbox(value: String) = remoteOperation { remoteDsh?.selectSandbox(value) }
    fun suppressDshAutostartAfterImport() { runtimeAutostartSuppressed = true }
    private fun remoteOperation(action: suspend () -> Unit) {
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { updateNotice(RemoteDshApplicationController.remoteFailure(failure)) }
        }
    }

    override fun onCleared() {
        dshProviderSyncJob?.cancel()
        notificationMonitor?.close()
        pushSettings?.close()
        remoteArtifactDownloads?.close()
        remoteAttachmentComposer?.cancel()
        remoteDsh?.close()
        dshSnapshotJob?.cancel()
        dshRuntimeManager?.close()
        backgroundTasks?.close()
        taskClients.toList().forEach { it.cancel() }
        deepSeekClient?.cancel()
        catalogClient?.cancel()
        super.onCleared()
    }

    private fun replaceStreamMessage(conversationId: String, id: Long, snapshot: AssistantStreamSnapshot) {
        val targetMessages = conversationMessages[conversationId] ?: return
        val index = targetMessages.indexOfFirst { it.id == id }
        if (index < 0) return
        val responseParts = if (snapshot.isFinal && snapshot.messages.lastOrNull()?.status ==
            AssistantMessageStatus.COMPLETED) snapshot.messages.takeLast(1) else snapshot.messages
        val text = responseParts.flatMap { it.blocks }
            .filterIsInstance<AssistantContentBlockSnapshot.Text>().joinToString("") { it.text }
        val old = targetMessages[index]
        targetMessages[index] = old.copy(
            text = if (snapshot.source == AssistantStreamSource.BACKEND) text else text.ifEmpty { old.text },
            streamSnapshot = snapshot,
        )
        persistWorkspaces()
    }

}


