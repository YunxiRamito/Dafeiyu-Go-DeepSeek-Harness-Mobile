package com.dafeiyu.mobile

import android.animation.ValueAnimator
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.dafeiyu.mobile.data.DeveloperCenterPresenceClient
import com.dafeiyu.mobile.data.ArchiveCleanupScheduler
import com.dafeiyu.mobile.data.DemoRepository
import com.dafeiyu.mobile.data.AriesConnectionProvider
import com.dafeiyu.mobile.data.PowerSavingPolicy
import com.dafeiyu.mobile.data.ApplicationPermissionBridge
import com.dafeiyu.mobile.data.NotificationPublisher
import com.dafeiyu.mobile.data.AndroidUpdateScheduler
import com.dafeiyu.mobile.data.AndroidAppVisibility
import com.dafeiyu.mobile.data.AssistantRunStatus
import com.dafeiyu.mobile.ui.*
import com.dafeiyu.mobile.ui.components.*
import com.dafeiyu.mobile.ui.theme.FishTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DafeiyuApp() }
    }
}

@Composable
fun DafeiyuApp(repository: DemoRepository = viewModel()) {
    var route by rememberSaveable { mutableStateOf("home") }
    var scanReturnToPair by rememberSaveable { mutableStateOf(false) }
    var scannedPairingPayload by rememberSaveable { mutableStateOf<String?>(null) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val savedPages = rememberSaveableStateHolder()
    val context = LocalContext.current
    val permissionBridge = remember(context) { ApplicationPermissionBridge.get(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val rootDensity = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(rootDensity) > 0
    val sidebarBack = remember { Animatable(0f) }
    val settingsTransfer = remember { Animatable(1f) }
    var transferringSettings by remember { mutableStateOf(false) }
    val settingsHome = rememberSettingsPageTransitionState()
    val settingsDetail = rememberSettingsPageTransitionState()
    var navigationJob by remember { mutableStateOf<Job?>(null) }
    var menuButtonBounds by remember { mutableStateOf<Rect?>(null) }
    var homeBackdropCapture by remember { mutableStateOf<PageBackdropCapture?>(null) }
    var chatOverlay by remember { mutableFloatStateOf(0f) }
    var plusOverlay by remember { mutableFloatStateOf(0f) }
    var tasksOverlay by remember { mutableFloatStateOf(0f) }
    var tasksSurfaceVisible by remember { mutableStateOf(false) }
    var selectedTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    val effects = PowerSavingPolicy.resolve(repository.powerSavingMode, repository.glassMaterial,
        repository.pageTransitions, repository.springButtons,
        reduceTransparency = repository.reduceTransparency, reduceMotion = repository.reduceMotion)
    val reducedMotion = effects.reduceMotion || !ValueAnimator.areAnimatorsEnabled()
    // 「页面切换效果」只关闭页面级路由/设置/侧栏转场；按钮按压等微交互仍由 reducedMotion 决定。
    val pageMotionReduced = reducedMotion || !effects.pageTransitions
    val connectionProvider = remember(context) { AriesConnectionProvider(context) }
    val notificationPublisher = remember(context) { NotificationPublisher(context) }
    val dshInstallation = repository.dshInstallation?.state?.collectAsState()?.value
    var awaitingDshHome by remember { mutableStateOf(false) }
    var observedTask by remember { mutableStateOf<Pair<String, Long>?>(null) }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            val foreground = lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            AndroidAppVisibility.setForeground(foreground)
            repository.updateRemoteForeground(foreground)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        AndroidAppVisibility.setForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); AndroidAppVisibility.setForeground(false) }
    }
    LaunchedEffect(repository.displayResponding, repository.notificationsEnabled, repository.liveIslandNotifications, repository.messageNotifications) {
        if (repository.displayResponding) {
            observedTask = repository.activeTaskNotificationIdentity()
            notificationPublisher.postRunning(
                enabled = observedTask != null && repository.notificationsEnabled && repository.liveIslandNotifications,
            )
        } else if (observedTask != null) {
            val state = observedTask?.let(repository::taskNotificationStatus)
            observedTask = null
            val detail = when (state) {
                AssistantRunStatus.COMPLETED -> "任务已完成"
                AssistantRunStatus.FAILED -> "任务执行失败，打开对话查看原因"
                AssistantRunStatus.CANCELLED -> "任务已取消"
                AssistantRunStatus.INCOMPLETE -> "任务未完成，打开对话查看状态"
                else -> null
            }
            notificationPublisher.postCompleted(
                enabled = detail != null && repository.notificationsEnabled &&
                    (repository.messageNotifications || repository.liveIslandNotifications),
                detail = detail.orEmpty(),
            )
        } else {
            notificationPublisher.clearRunning()
        }
    }
    var isPaired by remember(repository.ariesEndpoint, repository.ariesNodeId,
        repository.ariesGroupId, repository.ariesDeviceId) { mutableStateOf(repository.ariesGroupId != null) }
    LaunchedEffect(lifecycleOwner, repository.ariesEndpoint, repository.ariesNodeId,
        repository.ariesGroupId, repository.ariesDeviceId, route) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) {
                if (repository.ariesEndpoint.isNotBlank() && repository.ariesGroupId != null) {
                    isPaired = try {
                        connectionProvider.call("get_connection_state").optString("pairing_status") == "paired"
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        isPaired
                    }
                }
                kotlinx.coroutines.delay(60_000L)
            }
        }
    }
    LaunchedEffect(repository, context) {
        repository.initializeWorkspaces(context)
        ArchiveCleanupScheduler.ensureScheduled(context)
        AndroidUpdateScheduler.reconcile(context, repository.updatePolicy, repository.updateWifiOnly)
    }
    LaunchedEffect(lifecycleOwner, context) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            val presenceClient = DeveloperCenterPresenceClient()
            val launcherVersion = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"
            while (true) {
                presenceClient.heartbeat(context, launcherVersion)
                kotlinx.coroutines.delay(60_000L)
            }
        }
    }
    fun newConversation() {
        val oldKey = "home-${repository.displayConversationId}"
        repository.newConversation()
        if (oldKey != "home-${repository.displayConversationId}") savedPages.removeState(oldKey)
    }
    fun navigate(action: suspend () -> Unit) {
        navigationJob?.cancel()
        navigationJob = scope.launch { action() }
    }
    fun backFromRoute() {
        route = if (route == "scan" && scanReturnToPair) "pair" else "home"
        scanReturnToPair = false
    }
    fun back() {
        when {
            menuOpen -> menuOpen = false
            route != "home" -> backFromRoute()
            settingsDetail.isActive -> navigate { settingsDetail.close(pageMotionReduced) }
            settingsHome.isActive -> navigate { settingsHome.close(pageMotionReduced) }
        }
    }
    fun openSettings(target: String? = null) {
        focus.clearFocus(); keyboard?.hide()
        if (settingsHome.isActive) return
        val origin = menuButtonBounds ?: return
        settingsHome.recordSource("settings", origin, origin, with(rootDensity) { 24.dp.toPx() })
        val fromSidebar = menuOpen
        menuOpen = false
        navigate {
            if (fromSidebar) {
                transferringSettings = true
                try {
                    settingsTransfer.snapTo(0f)
                    settingsHome.open(true)
                    settingsTransfer.animateTo(1f, GlassMotion.scalar(GlassMotion.MorphMillis, pageMotionReduced))
                } finally {
                    transferringSettings = false
                }
            } else settingsHome.open(pageMotionReduced)
            if (target != null) {
                // A model-management shortcut has no settings row origin yet; use the toolbar circle.
                settingsDetail.recordSource(target, origin, origin)
                settingsDetail.open(pageMotionReduced)
            }
        }
    }
    LaunchedEffect(repository.workspaceInitialized, repository.engineMode, repository.connectionMode,
        repository.autoStartDsh, dshInstallation?.installedVersion, repository.runtimeAutostartSuppressed) {
        if (repository.workspaceInitialized && !repository.runtimeAutostartSuppressed && repository.connectionMode == "local" &&
            (repository.engineMode == "dsh" || repository.autoStartDsh)) {
            awaitingDshHome = repository.engineMode == "dsh"
            repository.startRuntime()
        } else awaitingDshHome = false
    }
    LaunchedEffect(repository.runtimeLastError, menuButtonBounds, repository.engineMode, repository.connectionMode) {
        if (awaitingDshHome && repository.engineMode == "dsh" && repository.connectionMode == "local" &&
            repository.runtimeLastError != null && menuButtonBounds != null) openSettings("dsh-engine")
    }
    LaunchedEffect(repository.runtimeAccessUrl, repository.engineMode, repository.connectionMode, repository.localEngineSwitchBusy) {
        if (awaitingDshHome && repository.engineMode == "dsh" && repository.connectionMode == "local" &&
            repository.runtimeAccessUrl != null && !repository.localEngineSwitchBusy && repository.localEngineSwitchError == null) {
            awaitingDshHome = false
            settingsDetail.close(pageMotionReduced)
            settingsHome.close(pageMotionReduced)
            route = "home"
        }
    }
    LaunchedEffect(repository.lastNotice) {
        val notice = repository.lastNotice
        if (notice.isNotBlank()) {
            snackbar.showSnackbar(notice)
            if (repository.lastNotice == notice) repository.updateNotice("")
        }
    }
    FishTheme(
        themeMode = repository.themeMode,
        accentColorKey = repository.accentColorKey,
        fontSizeScale = repository.fontSizeScale,
        fontWeightMode = repository.fontWeight,
        fontFamilyKey = repository.fontFamily,
        interfaceScale = repository.interfaceScale,
    ) {
        ApplicationPermissionHost(permissionBridge)
        OfficialToolApprovalHost()
        val palette = MaterialTheme.colorScheme
        val view = LocalView.current
        val backdrop = rememberGlassBackdropState()
        val floatingBackdrop = rememberGlassBackdropState()
        val haptics = rememberGlassHaptics()
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window
            if (window != null) {
                @Suppress("DEPRECATION")
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                @Suppress("DEPRECATION")
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                window.isNavigationBarContrastEnforced = false
                window.isStatusBarContrastEnforced = false
                WindowCompat.getInsetsController(window, view).apply {
                    val light = route != "scan" && palette.background.luminance() > .5f
                    isAppearanceLightStatusBars = light
                    isAppearanceLightNavigationBars = light
                }
            }
        }
        CompositionLocalProvider(
            LocalGlassBackdrop provides backdrop,
            LocalFloatingGlassBackdrop provides floatingBackdrop,
            // 「液态玻璃材质」关闭时复用减少透明度的实色降级路径（不透明 surface + onSurface 文字）。
            LocalReduceGlassTransparency provides effects.reduceTransparency,
            LocalReduceGlassMotion provides reducedMotion,
            LocalGlassButtonSpring provides effects.springButtons,
            LocalGlassHaptics provides haptics,
        ) {
            val sidebar = rememberWorkspaceSidebarMorphState(menuOpen, if (menuOpen) sidebarBack.value else 0f,
                reducedMotion = pageMotionReduced)
            val pageRoute = updateTransition(route, label = "pageRoute")
            val routeChanging by remember(pageRoute) {
                derivedStateOf { pageRoute.currentState != pageRoute.targetState || pageRoute.isRunning }
            }
            val routeSurfaceVisible by remember(pageRoute) {
                derivedStateOf {
                    pageRoute.currentState != "home" || pageRoute.targetState != "home" || pageRoute.isRunning
                }
            }
            PredictiveBackHandler(enabled = !imeVisible &&
                (menuOpen || route != "home" || settingsDetail.isActive || settingsHome.isActive)) { events ->
                val closing = when {
                    menuOpen || route != "home" -> null
                    settingsDetail.isActive -> settingsDetail
                    else -> settingsHome
                }
                navigationJob?.cancel()
                try {
                    events.collect { event ->
                        if (closing != null) closing.predictiveBack(if (pageMotionReduced) 0f else event.progress)
                        else if (menuOpen) sidebarBack.snapTo(if (pageMotionReduced) 0f else event.progress)
                    }
                    if (closing != null) closing.close(pageMotionReduced)
                    else {
                        if (menuOpen) sidebar.freezePredictiveDismiss()
                        menuOpen = false
                        if (route != "home") backFromRoute()
                        sidebarBack.snapTo(0f)
                    }
                } catch (_: CancellationException) {
                    scope.launch {
                        if (closing != null) closing.cancelPredictiveBack(pageMotionReduced)
                        else sidebarBack.animateTo(0f, GlassMotion.scalar(reducedMotion = pageMotionReduced))
                    }
                }
            }
            Scaffold(containerColor = Color.Transparent, contentColor = palette.onBackground,
                snackbarHost = { SnackbarHost(snackbar, Modifier.navigationBarsPadding().imePadding()) },
                contentWindowInsets = WindowInsets(0, 0, 0, 0)) { innerPadding ->
                Box(Modifier.fillMaxSize().padding(innerPadding)) {
                    GlassBackdrop(backdrop, Modifier.matchParentSize(), baseColor = palette.background)
                    CompositionLocalProvider(LocalSettingsPageTransition provides settingsDetail) {
                        SettingsPageTransition(settingsHome,
                            modifier = Modifier.pageRouteInputBlocked(routeSurfaceVisible),
                            reducedMotion = pageMotionReduced,
                            crossfadeProgress = if (transferringSettings) settingsTransfer.value else null,
                            sourceContent = {
                                val density = LocalDensity.current
                                val blurSteps = remember(density.density, reducedMotion) { (0..4).map { index ->
                                    if (Build.VERSION.SDK_INT >= 31 && !reducedMotion && index > 0)
                                        android.graphics.RenderEffect.createBlurEffect(2.8f * density.density * index / 4f,
                                            2.8f * density.density * index / 4f, android.graphics.Shader.TileMode.CLAMP).asComposeRenderEffect() else null
                                } }
                                Box(Modifier.fillMaxSize().graphicsLayer {
                                    val strength = maxOf(chatOverlay, plusOverlay, tasksOverlay, sidebar.progress, if (transferringSettings) 1f else 0f)
                                    renderEffect = blurSteps[(strength * 4f).toInt().coerceIn(0, 4)]
                                }) {
                                    PageChrome("home", repository, onBack = ::back,
                                        isPaired = isPaired,
                                        onMenu = { focus.clearFocus(); keyboard?.hide(); menuOpen = true },
                                        onNew = ::newConversation,
                                        onScan = { focus.clearFocus(); keyboard?.hide(); route = "scan" },
                                        onPairing = { focus.clearFocus(); keyboard?.hide(); route = "pair" },
                                        onPlusOverlay = { plusOverlay = it },
                                        onTasksOverlay = { tasksOverlay = it },
                                        onTasksVisibility = { tasksSurfaceVisible = it },
                                        onTasksOpening = { focus.clearFocus(); keyboard?.hide() },
                                        onPlusOpening = {
                                            focus.clearFocus(); keyboard?.hide()
                                        },
                                        onTasks = {
                                            selectedTaskId = it
                                            focus.clearFocus(); keyboard?.hide()
                                            route = "tasks"
                                        },
                                        dismissHomeActions = menuOpen || settingsHome.isActive || routeSurfaceVisible,
                                        onMenuBounds = { menuButtonBounds = it }, sidebar = sidebar, floatingBackdrop = floatingBackdrop,
                                        onBackdropCapture = { homeBackdropCapture = it },
                                        modalProgress = { maxOf(chatOverlay, plusOverlay, tasksOverlay, sidebar.progress) }) {
                                        savedPages.SaveableStateProvider("home-${repository.displayConversationId}") {
                                            if (repository.engineMode == "dsh") DshWebContent(repository,
                                                onSettings = { openSettings("dsh-engine") })
                                            else ChatScreen(repository, onModels = { openSettings("models") },
                                                overlayVisible = menuOpen || tasksSurfaceVisible || settingsHome.isActive || routeSurfaceVisible,
                                                onOverlayProgress = { chatOverlay = it },
                                                onAttachmentsOpening = {
                                                    scope.launch { permissionBridge.requestConsent("media",
                                                        "用于在对话中选择和读取你授权的照片；仅在你发送附件时提供给所选模型。", automaticEntry = true) }
                                                })
                                        }
                                    }
                                }
                                Box(Modifier.matchParentSize().graphicsLayer {
                                    alpha = maxOf(chatOverlay, plusOverlay, tasksOverlay, sidebar.progress, if (transferringSettings) 1f else 0f) * .12f
                                }.background(palette.scrim))
                            },
                            destinationContent = {
                                SettingsPageTransition(settingsDetail, reducedMotion = pageMotionReduced,
                                    sourceContent = {
                                        PageChrome("settings", repository, ::back, sidebar = sidebar) {
                                            savedPages.SaveableStateProvider("settings") {
                                                SettingsScreen(repository) { target ->
                                                    if (!settingsDetail.isAnimating && !settingsHome.isAnimating) navigate {
                                                        settingsDetail.open(pageMotionReduced)
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    destinationContent = { target ->
                                        PageChrome(target, repository, ::back, sidebar = sidebar) {
                                            savedPages.SaveableStateProvider(target) {
                                                when (target) {
                                                    "plugins", "extensions" -> ExtensionsScreen(repository, category = "plugin")
                                                    "skills" -> ExtensionsScreen(repository, category = "skill")
                                                    "permissions" -> PermissionsScreen(::back, repository::updateNotice)
                                                    else -> SettingsDetailScreen(target, repository, ::back, repository::updateNotice, onPairing = { focus.clearFocus(); keyboard?.hide(); route = "pair" })
                                                }
                                            }
                                        }
                                    })
                            })
                    }
                    if (sidebar.surfaceVisible) Box(Modifier.matchParentSize().clickable { menuOpen = false })
                    AnimatedWorkspaceSidebar(
                        visible = menuOpen, repo = repository, morphState = sidebar,
                        fusionBackdrop = homeBackdropCapture,
                        launcherGlyph = if (settingsHome.isActive) "back" else "menu",
                        onClose = { menuOpen = false }, onSettings = { openSettings() },
                        onOnlineMetrics = { openSettings("server-metrics") },
                        onConversationChanged = { route = "home" },
                        modifier = Modifier.matchParentSize().navigationBarsPadding()
                            .pageRouteInputBlocked(routeSurfaceVisible))
                    // Home/settings remain mounted below the route surface; camera routes switch immediately.
                    pageRoute.AnimatedContent(
                        modifier = Modifier.matchParentSize().clipToBounds().pageRouteInputBlocked(routeChanging),
                        transitionSpec = {
                            if (reducedMotion || initialState == "scan" || targetState == "scan") {
                                (EnterTransition.None togetherWith ExitTransition.None).using(null)
                            } else if (!repository.pageTransitions) {
                                // 页面切换效果关闭：仅保留短促淡入淡出，不做水平位移。
                                val fade = GlassMotion.scalar(GlassMotion.PressMillis)
                                val enter = if (targetState == "home") EnterTransition.None else fadeIn(fade)
                                val exit = if (initialState == "home") ExitTransition.None else fadeOut(fade)
                                (enter togetherWith exit).using(null)
                            } else {
                                val fade = GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion)
                                val slide = tween<androidx.compose.ui.unit.IntOffset>(
                                    GlassMotion.MorphMillis, easing = GlassMotion.EaseOut)
                                val returning = targetState == "home"
                                val enter = if (returning) EnterTransition.None else
                                    fadeIn(fade) + slideInHorizontally(slide) { it / 12 }
                                val exit = if (initialState == "home") ExitTransition.None else
                                    fadeOut(fade) + slideOutHorizontally(slide) {
                                        if (returning) it / 12 else -it / 24
                                    }
                                (enter togetherWith exit).using(null)
                            }
                        },
                    ) { displayedRoute ->
                        when (displayedRoute) {
                            "scan" -> ScannerScreen(
                                onBack = { if (scanReturnToPair) { scanReturnToPair = false; route = "pair" } else back() },
                                notify = repository::updateNotice,
                            ) { payload ->
                                if (scanReturnToPair) {
                                    scannedPairingPayload = payload
                                    scanReturnToPair = false
                                    route = "pair"
                                } else repository.updateNotice("已读取二维码内容；请从配对页继续验证和申请")
                            }
                            "pair" -> PageChrome("pair", repository, ::back) {
                                PairingScreen(
                                    repository = repository,
                                    onBack = ::back,
                                    notify = repository::updateNotice,
                                    onScan = { scanReturnToPair = true; route = "scan" },
                                    scannedPayload = scannedPairingPayload,
                                    onScannedPayloadConsumed = { scannedPairingPayload = null },
                                )
                            }
                            "tasks" -> {
                                val tasksBackdrop = rememberGlassBackdropState()
                                CompositionLocalProvider(LocalFloatingGlassBackdrop provides tasksBackdrop) {
                                    PageChrome("tasks", repository, ::back, floatingBackdrop = tasksBackdrop) {
                                        BackgroundTasksScreen(
                                            engine = repository.backgroundTasks,
                                            models = repository.modelChoices.filter { !it.isExample }.map {
                                                com.dafeiyu.mobile.data.BackgroundTaskModel(it.id, it.name)
                                            },
                                            notify = repository::updateNotice,
                                            conversationId = repository.selectedConversationId.takeIf { it.isNotBlank() },
                                            initialTaskId = selectedTaskId,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The sampled page covers the window; chrome overlays extend under transparent system bars. */
@Composable
private fun PageChrome(
    page: String,
    repository: DemoRepository,
    onBack: () -> Unit,
    onMenu: () -> Unit = {},
    onNew: () -> Unit = {},
    onScan: () -> Unit = {},
    onPairing: () -> Unit = {},
    isPaired: Boolean = false,
    onPlusOverlay: (Float) -> Unit = {},
    onTasksOverlay: (Float) -> Unit = {},
    onTasksVisibility: (Boolean) -> Unit = {},
    onTasksOpening: () -> Unit = {},
    onPlusOpening: () -> Unit = {},
    onTasks: (String?) -> Unit = {},
    dismissHomeActions: Boolean = false,
    onMenuBounds: (Rect) -> Unit = {},
    sidebar: WorkspaceSidebarMorphState? = null,
    floatingBackdrop: GlassBackdropState? = null,
    modalProgress: () -> Float = { 0f },
    onBackdropCapture: (PageBackdropCapture) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val capture = rememberPageBackdropCapture()
    SideEffect { onBackdropCapture(capture) }
    val navigation = rememberSettingsNavigationState()
    val palette = MaterialTheme.colorScheme
    val button = rememberGlassButtonDeformationState()
    val actionsButton = rememberGlassButtonDeformationState()
    val plusButton = rememberGlassButtonDeformationState()
    var actionsLauncherAlpha by remember { mutableFloatStateOf(1f) }
    var plusLauncherAlpha by remember { mutableFloatStateOf(1f) }
    var taskMenuVisible by remember { mutableStateOf(false) }
    var plusMenuVisible by remember { mutableStateOf(false) }
    var titleTarget by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().capturePageBackdrop(capture).background(palette.background)) {
            CompositionLocalProvider(LocalSettingsNavigationState provides if (page == "home") null else navigation,
                LocalPageHeaderInset provides 64.dp) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(
                    if (page == "home") WindowInsets.systemBars.union(WindowInsets.displayCutout) else WindowInsets.safeDrawing)
                    .then(if (page == "home") Modifier.padding(top = 64.dp) else Modifier)) { content() }
            }
        }
        if (floatingBackdrop != null) FloatingBackdropSource(capture, floatingBackdrop, Modifier.matchParentSize(), modalProgress)
        ScrollingGlassHeader(capture, Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            barHeight = 64.dp, includeSystemBarInsets = true) {
            Row(Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassSurface(buttonDeformation = button,
                    modifier = Modifier.size(48.dp).applyButtonDeformation(state = button, circularHit = true)
                        .onGloballyPositioned { pos ->
                            val origin = pos.positionInWindow()
                            if (page == "home") onMenuBounds(Rect(origin, androidx.compose.ui.geometry.Size(pos.size.width.toFloat(), pos.size.height.toFloat())))
                        }.then(if (sidebar != null) Modifier.workspaceSidebarMorphAnchor(sidebar) else Modifier),
                    cornerRadius = 24.dp, tint = palette.surface.copy(alpha = .18f)) {
                    IconButton(onClick = if (page == "home") onMenu else onBack,
                        modifier = Modifier.fillMaxSize().semantics { contentDescription = if (page == "home") "打开菜单" else "返回" }) {
                        FishGlyph(if (page == "home") "menu" else "back", Modifier.size(21.dp))
                    }
                }
                Box(Modifier.weight(1f).padding(horizontal = 16.dp).onGloballyPositioned {
                    titleTarget = it.positionInWindow() + Offset(0f, it.size.height / 2f)
                }) {
                    val conversationTitle = repository.displayConversations.firstOrNull {
                        it.id == repository.displayConversationId
                    }?.title?.takeUnless { it == "新对话" }
                    val toolbarTitle = when (page) {
                        "home" -> conversationTitle
                        "tasks" -> "任务与子 Agent"
                        else -> null
                    }
                    if (toolbarTitle != null) {
                        Text(toolbarTitle, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else Spacer(Modifier.height(22.dp))
                }
                if (page == "home") {
                    LiquidGlassButtonPair(
                        first = actionsButton, second = plusButton,
                        firstAlpha = { actionsLauncherAlpha }, secondAlpha = { plusLauncherAlpha },
                    ) { drawLauncherMaterial ->
                        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AgentsTaskActions(repository.backgroundTasks, onOpenTasks = onTasks,
                        onOpening = onTasksOpening, dismissRequested = dismissHomeActions || plusMenuVisible,
                        onOverlayProgress = onTasksOverlay,
                        onSurfaceVisibilityChanged = {
                            taskMenuVisible = it
                            onTasksVisibility(it)
                        }, deformation = actionsButton,
                        drawLauncherMaterial = drawLauncherMaterial,
                        onLauncherAlphaChanged = { actionsLauncherAlpha = it })
                            NewSessionActions(onNew, onScan, onPairing = onPairing,
                        isPaired = isPaired, onOverlayProgress = onPlusOverlay,
                        onOpening = onPlusOpening,
                        onSurfaceVisibilityChanged = { plusMenuVisible = it },
                        dismissRequested = dismissHomeActions || taskMenuVisible,
                        deformation = plusButton,
                        drawLauncherMaterial = drawLauncherMaterial,
                        onLauncherAlphaChanged = { plusLauncherAlpha = it })
                        }
                    }
                }
            }
        }
        ScrollingGlassHeader(capture, Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            edge = ScrollGlassEdge.Bottom, barHeight = 0.dp, includeSystemBarInsets = true)
        if (page != "home" && page != "tasks")
            TitleMorphHeader(navigation, titleTarget.x, titleTarget.y, Modifier.fillMaxSize())
    }
}

/** Keep outgoing controls and covered pages inert until the route surface settles. */
private fun Modifier.pageRouteInputBlocked(blocked: Boolean): Modifier = if (!blocked) this else
    clearAndSetSemantics {}.pointerInput(blocked) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
    }

