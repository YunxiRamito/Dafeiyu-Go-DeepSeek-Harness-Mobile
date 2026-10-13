package com.dafeiyu.mobile.ui

import android.animation.ValueAnimator
import android.net.Uri
import android.view.View
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dafeiyu.mobile.data.DemoRepository
import com.dafeiyu.mobile.data.PowerSavingPolicy
import com.dafeiyu.mobile.data.RemoteDshApplicationController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** The WebView can reach only its current authenticated loopback authority. */
@Composable
internal fun DshWebContent(repository: DemoRepository, onSettings: () -> Unit,
    hostActions: DshWebHostActions = DshWebHostActions(), modifier: Modifier = Modifier,
    interactive: Boolean = true, onReadyChanged: (Boolean) -> Unit = {},
    onExit: () -> Unit = onSettings) {
    val online = repository.connectionMode == "online"
    val remoteState = repository.remoteDshState
    val tunnelAvailable = remoteState.connection?.optJSONObject("capabilities")?.optBoolean("remote_web_tunnel") == true
    var tunnelFailure by remember(online, remoteState.projection?.binding) { mutableStateOf<String?>(null) }
    var tunnelRetry by remember(online, remoteState.projection?.binding) { mutableIntStateOf(0) }
    var launchCancelled by remember(online, remoteState.projection?.binding) { mutableStateOf(false) }
    LaunchedEffect(online, tunnelAvailable, remoteState.projection?.binding, remoteState.remoteOnline,
        remoteState.webSuspended, tunnelRetry, launchCancelled) {
        if (online && !launchCancelled && tunnelAvailable && remoteState.remoteOnline && !remoteState.webSuspended) {
            try { repository.remoteDsh?.openWeb(); tunnelFailure = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { tunnelFailure = RemoteDshApplicationController.remoteFailure(failure) }
        }
    }
    DisposableEffect(online) { onDispose { if (online) repository.remoteDsh?.closeWeb() } }
    val access = if (online) remoteState.webAccessUrl else repository.runtimeAccessUrl
        .takeIf { !repository.localEngineSwitchBusy && repository.localEngineSwitchError == null }
    val selectedSession = if (online) null else repository.localDshSessionId
    val startupFailure = if (launchCancelled) "已退出网页加载。返回后可点重试继续，已接收的任务会保留。"
    else if (online) tunnelFailure ?: remoteState.error ?: when {
        !tunnelAvailable && remoteState.connection != null -> "电脑尚未启用网页隧道，请检查互联设置。"
        !remoteState.remoteOnline && remoteState.connection != null -> "电脑当前离线，重新连接后可重试。"
        else -> null
    } else repository.localEngineSwitchError ?: repository.runtimeLastError
    val placeholderTitle = if (online) "等待电脑 DSH 网页通道" else "正在准备本机 DSH"
    val placeholderDetail = if (online) tunnelFailure ?: if (!tunnelAvailable)
        "电脑尚未启用网页隧道（预留）；启用后将通过 Aries 连接。"
    else remoteState.error ?: "正在连接电脑 DSH，请确认电脑服务已启动。"
    else repository.localEngineSwitchError ?: if (repository.localEngineSwitchBusy) "正在同步提供商与兼容历史…"
    else repository.runtimeLastError ?: repository.runtimeStep
    val effects = PowerSavingPolicy.resolve(repository.powerSavingMode, repository.glassMaterial,
        repository.pageTransitions, repository.springButtons, reduceTransparency = repository.reduceTransparency,
        reduceMotion = repository.reduceMotion)
    val customMaterial = LocalAppThemeState.current?.webMaterialTheme
    val motionEnabled = !effects.reduceMotion && ValueAnimator.areAnimatorsEnabled() &&
        effects.pageTransitions && effects.springButtons
    val mobileClient = remember(online, repository.themeMode, effects.glassMaterial, motionEnabled, customMaterial) {
        DshWebClientContext(if (online) "online" else "local", repository.themeMode,
            effects.glassMaterial, motionEnabled, materialTheme = customMaterial)
    }
    DshWebSurface(access, selectedSession, placeholderTitle, placeholderDetail, onSettings,
        mobileClient = mobileClient, hostActions = hostActions, modifier = modifier.testTag("dsh-home-direct"),
        startupFailure = startupFailure, online = online, interactive = interactive,
        presentationCancelled = launchCancelled, onReadyChanged = onReadyChanged,
        onRetry = {
            launchCancelled = false
            if (online) { tunnelFailure = null; tunnelRetry++ }
            else repository.startRuntime()
        }, onCancel = {
            launchCancelled = true
            if (online) repository.remoteDsh?.closeWeb()
            onExit()
        })
}

/** Shared by the real screen and lifecycle QA; no service or model is launched here. */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun DshWebSurface(access: String?, selectedSession: String?, placeholderTitle: String,
    placeholderDetail: String, onSettings: () -> Unit, mobileClient: DshWebClientContext? = null,
    hostActions: DshWebHostActions = DshWebHostActions(), modifier: Modifier = Modifier,
    startupFailure: String? = null, online: Boolean = false, onRetry: () -> Unit = {},
    onCancel: () -> Unit = onSettings, interactive: Boolean = true,
    presentationCancelled: Boolean = false, onReadyChanged: (Boolean) -> Unit = {}) {
    val expected = remember(access) { access?.let(Uri::parse)?.takeIf {
        it.scheme == "http" && it.host == "127.0.0.1" && it.port in 1024..65535 && it.userInfo == null
    } }
    var retryGeneration by remember(access, selectedSession) { mutableIntStateOf(0) }
    var pageReady by remember(access, selectedSession, retryGeneration) { mutableStateOf(false) }
    var failure by remember(access, selectedSession, retryGeneration) { mutableStateOf<String?>(null) }
    var navigationGeneration by remember(access, selectedSession, retryGeneration) { mutableIntStateOf(0) }
    var exited by remember(access, selectedSession) { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    val latestReadyChanged by rememberUpdatedState(onReadyChanged)
    val lifecycleOwner = LocalLifecycleOwner.current
    var foreground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    val cancelled = exited || presentationCancelled
    val reason = startupFailure ?: failure ?: when {
        cancelled -> "已退出网页加载，点重试继续。"
        access != null && expected == null -> "DSH 网页入口无效，请检查服务状态。"
        else -> null
    }
    // Only the versioned webpage handshake can release the native waiting layer.
    // onPageFinished, a listening port and elapsed time cannot declare success.
    val presented = expected != null && pageReady && reason == null && !cancelled
    val awaitingPresentation = expected == null || reason != null || cancelled ||
        (mobileClient != null && !presented)
    val viewInteractive = interactive && !awaitingPresentation
    BackHandler(viewInteractive && canGoBack) { web?.goBack() }
    BackHandler(interactive && awaitingPresentation) {
        exited = true
        onCancel()
    }
    LaunchedEffect(presented) { latestReadyChanged(presented) }
    DisposableEffect(lifecycleOwner, web) {
        val retained = web
        fun updateVisibility() {
            foreground = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (retained != null && web === retained) {
                if (foreground) retained.onResume() else retained.onPause()
            }
        }
        val observer = LifecycleEventObserver { _, _ -> updateVisibility() }
        lifecycleOwner.lifecycle.addObserver(observer)
        updateVisibility()
        // pauseTimers is global and must never be used for an owned webpage.
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(expected, selectedSession, retryGeneration, navigationGeneration, pageReady,
        web, startupFailure, failure, cancelled, foreground, mobileClient != null) {
        val retained = web
        if (expected != null && mobileClient != null && !pageReady && failure == null &&
            startupFailure == null && !cancelled && foreground && retained != null) {
            delay(45_000L)
            if (web === retained && !pageReady && failure == null && startupFailure == null) {
                failure = "DSH 服务可访问，但网页未完成手机界面握手。请重试或检查 DSH 设置。"
            }
        }
    }
    Box(modifier.fillMaxSize().semantics { testTagsAsResourceId = true }
        .dshInputBlocked(!interactive)) {
        if (expected != null && !cancelled) key(access, selectedSession, retryGeneration) {
            AndroidView(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)
                .imePadding().testTag("dsh-web-view")
                .dshInputBlocked(!viewInteractive), factory = { context ->
            var owned: WebView? = null
            DshWebViewFactory.create(context, expected.toString(), selectedSession,
                onViewCreated = { view -> owned = view; web = view; canGoBack = false },
                onPageStarted = { view -> if (web === view) {
                    pageReady = false; failure = null; navigationGeneration++
                    canGoBack = view.canGoBack()
                } },
                onPageFinished = { view -> if (web === view) canGoBack = view.canGoBack() },
                onMainFrameError = { if (owned != null && web === owned) {
                    pageReady = false
                    failure = failure ?: "DSH 网页无法访问，请检查服务状态。"
                } },
                onMainFrameFailureCode = { code, http ->
                    if (owned != null && web === owned) {
                        pageReady = false
                        failure = when (code) {
                            DshWebViewFactory.DISPLAY_CONTEXT_UNAVAILABLE ->
                                "当前 Android System WebView 无法安全启用手机网页与应用入口，请更新后重试。"
                            DshWebViewFactory.BOOTSTRAP_UNAVAILABLE ->
                                "当前 Android System WebView 无法安全续接对话，请更新后重试。"
                            DshWebViewFactory.RENDERER_UNAVAILABLE -> "网页渲染进程已退出，点重试重新打开网页。"
                            401, 403 -> if (http) "DSH 网页授权失效，请检查服务状态后重试。"
                                else "DSH 网页连接失败，请检查服务状态。"
                            else -> "DSH 网页连接失败，请检查服务状态后重试。"
                        }
                    }
                }, mobileClient = mobileClient, hostActions = hostActions,
                onDisplayHostUnavailable = { notice -> if (owned != null && web === owned) {
                    pageReady = false; failure = notice
                } },
                onDisplayReadyChanged = { view, ready -> if (web === view) {
                    pageReady = ready && failure == null
                } },
                onRendererUnavailable = { view, didCrash -> if (web === view) {
                    pageReady = false
                    canGoBack = false
                    failure = if (didCrash) "网页渲染进程意外退出，点重试重新打开网页。"
                        else "系统已回收网页渲染进程，点重试重新打开网页。"
                    web = null
                } })
        }, onRelease = { released ->
            // AndroidView owns this exact instance. An older access/session
            // disposal must never stop the replacement view created by Compose.
            if (web === released) { web = null; canGoBack = false; pageReady = false }
            DshWebViewFactory.release(released)
        }, update = { view ->
            if (web === view) {
                DshWebViewFactory.updateDisplayHost(view, mobileClient, hostActions)
                // Keep the same live surface under native settings; block input
                // without touching a renderer that has already been retired.
                view.isEnabled = viewInteractive
                view.importantForAccessibility = if (viewInteractive) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                if (!viewInteractive) view.clearFocus()
                if (view.tag != access) {
                    view.tag = access
                    if (view.url != access) view.loadUrl(expected.toString())
                }
            }
        }) }
        DshStartupOverlay(visible = awaitingPresentation, title = if (reason != null) "DSH 暂未就绪"
            else if (expected != null) "正在打开 DSH" else placeholderTitle,
            detail = reason ?: if (expected != null) "服务已连接，正在准备网页、对话与工作区。" else placeholderDetail,
            failed = reason != null, online = online, serviceReady = expected != null,
            motionReduced = mobileClient?.motion == false || !ValueAnimator.areAnimatorsEnabled(),
            onRetry = { web = null; pageReady = false; exited = false; retryGeneration++; onRetry() },
            onSettings = onSettings, onExit = { exited = true; onCancel() })
    }
}

/** Covered or waiting web controls must not receive gestures or accessibility. */
private fun Modifier.dshInputBlocked(blocked: Boolean): Modifier = if (!blocked) this else
    clearAndSetSemantics {}.pointerInput(blocked) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
