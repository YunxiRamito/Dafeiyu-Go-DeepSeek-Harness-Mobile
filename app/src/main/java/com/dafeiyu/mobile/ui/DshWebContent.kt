package com.dafeiyu.mobile.ui

import android.net.Uri
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.dafeiyu.mobile.data.DemoRepository
import com.dafeiyu.mobile.data.RemoteDshApplicationController
import com.dafeiyu.mobile.ui.components.FishButton
import kotlinx.coroutines.CancellationException

/** The WebView can reach only its current authenticated loopback authority. */
@Composable
internal fun DshWebContent(repository: DemoRepository, onSettings: () -> Unit) {
    val online = repository.connectionMode == "online"
    val remoteState = repository.remoteDshState
    val tunnelAvailable = remoteState.connection?.optJSONObject("capabilities")?.optBoolean("remote_web_tunnel") == true
    var tunnelFailure by remember(online, remoteState.projection?.binding) { mutableStateOf<String?>(null) }
    LaunchedEffect(online, tunnelAvailable, remoteState.projection?.binding, remoteState.remoteOnline, remoteState.webSuspended) {
        if (online && tunnelAvailable && remoteState.remoteOnline && !remoteState.webSuspended) {
            try { repository.remoteDsh?.openWeb(); tunnelFailure = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { tunnelFailure = RemoteDshApplicationController.remoteFailure(failure) }
        }
    }
    DisposableEffect(online) { onDispose { if (online) repository.remoteDsh?.closeWeb() } }
    val access = if (online) remoteState.webAccessUrl else repository.runtimeAccessUrl
        .takeIf { !repository.localEngineSwitchBusy && repository.localEngineSwitchError == null }
    val selectedSession = if (online) null else repository.localDshSessionId
    val expected = remember(access) { access?.let(Uri::parse)?.takeIf {
        it.scheme == "http" && it.host == "127.0.0.1" && it.port in 1024..65535 && it.userInfo == null
    } }
    var loading by remember(access) { mutableStateOf(true) }
    var failure by remember(access) { mutableStateOf<String?>(null) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    BackHandler(canGoBack) { web?.goBack() }
    DisposableEffect(access) {
        onDispose { web?.stopLoading(); web?.destroy(); web = null }
    }
    if (expected == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text(if (repository.connectionMode == "online") "等待电脑 DSH 网页通道" else "正在准备本机 DSH",
                style = MaterialTheme.typography.titleLarge)
            Hint(if (online) tunnelFailure ?: if (!tunnelAvailable)
                "电脑尚未启用网页隧道（预留）；启用后将通过 Aries 连接。"
            else remoteState.error ?: "正在连接电脑 DSH，请确认电脑服务已启动。"
            else repository.localEngineSwitchError ?: if (repository.localEngineSwitchBusy) "正在同步提供商与兼容历史…"
            else repository.runtimeLastError ?: repository.runtimeStep)
            FishButton(onClick = onSettings, accent = false) { Text("打开 DSH 设置") }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        failure?.let { reason ->
            Text(reason, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            FishButton(onClick = onSettings, accent = false, modifier = Modifier.padding(horizontal = 16.dp)) { Text("打开 DSH 设置") }
        }
        key(access, selectedSession) { AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { context ->
            DshWebViewFactory.create(context, expected.toString(), selectedSession,
                onPageStarted = { view -> loading = true; failure = null; canGoBack = view.canGoBack() },
                onPageFinished = { view -> loading = false; canGoBack = view.canGoBack() },
                onMainFrameError = { loading = false; failure = "DSH 网页无法访问，请检查服务状态" },
                onMainFrameFailureCode = { code, _ ->
                    if (code == DshWebViewFactory.BOOTSTRAP_UNAVAILABLE) {
                        loading = false
                        failure = "请更新 Android System WebView 后重试，以便安全续接当前对话"
                    }
                })
                .also { web = it }
        }, update = { view ->
            if (view.tag != access) {
                view.tag = access
                if (view.url != access) view.loadUrl(expected.toString())
            }
        }) }
    }
}
