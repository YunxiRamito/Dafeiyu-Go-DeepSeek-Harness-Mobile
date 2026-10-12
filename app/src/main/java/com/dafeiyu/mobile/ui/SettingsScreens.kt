package com.dafeiyu.mobile.ui

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.BorderStroke
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.cert.CertificateFactory
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.dafeiyu.mobile.data.DeveloperCenterPresenceClient
import com.dafeiyu.mobile.data.CacheStore
import com.dafeiyu.mobile.data.AndroidBackupStore
import com.dafeiyu.mobile.data.BalanceStore
import com.dafeiyu.mobile.data.DeepSeekBalanceClient
import com.dafeiyu.mobile.data.AriesClient
import com.dafeiyu.mobile.data.AriesGroupStore
import com.dafeiyu.mobile.data.AriesConnectionProvider
import com.dafeiyu.mobile.data.DeveloperCenterClient
import com.dafeiyu.mobile.data.DemoRepository
import com.dafeiyu.mobile.data.NotificationChannelIds
import com.dafeiyu.mobile.data.NotificationSettingsStore
import com.dafeiyu.mobile.data.NoticeStore
import com.dafeiyu.mobile.data.UsageStore
import com.dafeiyu.mobile.data.SettingsRuntimeStore
import com.dafeiyu.mobile.ui.components.GlassRendererDiagnostics
import com.dafeiyu.mobile.ui.components.FishButton
import com.dafeiyu.mobile.ui.components.FishDivider
import com.dafeiyu.mobile.ui.components.FishGlyph
import com.dafeiyu.mobile.ui.components.SectionLabel
import com.dafeiyu.mobile.ui.components.ToggleRow
import com.dafeiyu.mobile.ui.components.ChatMarkdown
import com.dafeiyu.mobile.ui.components.GlassMotion
import com.dafeiyu.mobile.ui.components.LocalSettingsGroupGeometry
import com.dafeiyu.mobile.ui.components.LocalSettingsPageTransition
import com.dafeiyu.mobile.ui.components.LocalGlassHaptics
import com.dafeiyu.mobile.ui.components.LocalReduceGlassMotion
import com.dafeiyu.mobile.ui.components.SettingsGroupGeometry
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.positionInParent
import androidx.compose.animation.core.tween
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(repository: DemoRepository, onDetail: (String) -> Unit) {
    val scroll = rememberScrollState()
    val sectionAnchors = remember { mutableStateMapOf<String, Int>() }
    val headerRange = with(LocalDensity.current) { 112.dp.toPx() }
    val collapsedTitle = rememberCollapsedTitleState(scroll.value, "设置", headerRange)
    PublishSettingsNavigation(collapsedTitle.collapsedTitle, collapsedTitle.progress, scroll.value.toFloat())
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val noticeMinimumHeight = maxHeight * .5f
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        PageHeader("设置", "工作区、偏好与支持，都在这里", collapseProgress = collapsedTitle.progress)
        SettingsNoticeOverview(onDetail, noticeMinimumHeight)

        SettingsGroup("引擎", anchorKey = "engine", onAnchor = { y -> sectionAnchors["engine"] = y }) {
            HomepageChoiceRow("官方轻引擎", "手机本地的轻量对话入口", repository.engineMode == "official", "model") {
                repository.updateEngineMode("official")
            }
            FishDivider(Modifier.padding(horizontal = 16.dp))
            HomepageChoiceRow("DSH 引擎（未接入）", "使用 DSH 服务作为默认执行引擎", repository.engineMode == "dsh", "terminal", enabled = false, onSelect = {
                if (repository.engineMode != "dsh") repository.updateEngineMode("dsh")
            })
            FishDivider(Modifier.padding(horizontal = 16.dp))
            SettingRow("model", "官方引擎设置", "轻引擎偏好与响应策略", targetRoute = "official-engine", onClick = { onDetail("official-engine") })
            FishDivider(Modifier.padding(horizontal = 16.dp))
            SettingRow("terminal", "DSH 引擎设置", "服务状态、版本与更新策略", targetRoute = "dsh-engine", onClick = { onDetail("dsh-engine") })
            FishDivider(Modifier.padding(horizontal = 16.dp))
            ToggleRow("自动启动 DSH 服务（预留）", "服务尚未接入，暂时无法自动启动", checked = false, enabled = false)
        }

        SettingsGroup("线上", anchorKey = "online", onAnchor = { y -> sectionAnchors["online"] = y }) {
            HomepageChoiceRow("本地", "只使用手机上的本地能力", repository.connectionMode == "local", "folder") {
                repository.updateConnectionMode("local")
            }
            FishDivider(Modifier.padding(horizontal = 16.dp))
            HomepageChoiceRow("在线", "连接已配对的电脑或 Aries 服务", repository.connectionMode == "online", "link") {
                repository.updateConnectionMode("online")
            }
            FishDivider(Modifier.padding(horizontal = 16.dp))
            HomepageChoiceRow("混合", "按能力在本地与在线之间切换", repository.connectionMode == "hybrid", "grid") {
                repository.updateConnectionMode("hybrid")
            }
            FishDivider(Modifier.padding(horizontal = 16.dp))
            SettingRow("link", "在线服务", "设备状态、配对码、Aries 与连接偏好", targetRoute = "online", onClick = { onDetail("online") })
            FishDivider(Modifier.padding(horizontal = 16.dp))
            SettingRow("download", "下载源", "为新组件下载选择线路", targetRoute = "network", onClick = { onDetail("network") })
        }

        SettingsGroup("常规", anchorKey = "general", onAnchor = { y -> sectionAnchors["general"] = y }) {
            SettingRow("bell", "通知", "灵动岛、公告、回复与设备推送", trailing = "预留", targetRoute = "notifications", onClick = { onDetail("notifications") })
            FishDivider(Modifier.padding(horizontal = 16.dp))
            SettingRow("sun", "个性化", "主题、主题色、材质与界面缩放", trailing = themeLabel(repository.themeMode), targetRoute = "appearance", onClick = { onDetail("appearance") })
            FishDivider(Modifier.padding(horizontal = 16.dp))
            SettingRow("settings", "字体", "字体族、字号与字重", targetRoute = "font", onClick = { onDetail("font") })
            FishDivider(Modifier.padding(horizontal = 16.dp))
            SettingRow("settings", "权限与系统", "常用、后台、执行与敏感权限", targetRoute = "permissions", onClick = { onDetail("permissions") })
        }

        SettingsGroup("模型", anchorKey = "models", onAnchor = { y -> sectionAnchors["models"] = y }) {
            SettingRow("model", "模型", "聚合内置引擎与 DSH 模型，排序会同步", targetRoute = "models", onClick = { onDetail("models") })
            FishDivider(Modifier.padding(horizontal = 16.dp))
            SettingRow("grid", "插件与技能", "管理插件、技能目录与导入的扩展", targetRoute = "extensions", onClick = { onDetail("extensions") })
        }

        SettingsGroup("环境", anchorKey = "environment", onAnchor = { y -> sectionAnchors["environment"] = y }) {
            SettingRow("terminal", "Linux 运行环境", "本机环境、版本与安装状态", targetRoute = "virtual-machine", onClick = { onDetail("virtual-machine") })
            FishDivider(Modifier.padding(horizontal = 16.dp))
            SettingRow("grid", "组件", "Ubuntu、Node.js、Git、pnpm 与 Python", targetRoute = "components", onClick = { onDetail("components") })
            FishDivider(Modifier.padding(horizontal = 16.dp))
            SettingRow("terminal", "运行详情", "查看本机 Linux 环境与开发工具状态", targetRoute = "runtime", onClick = { onDetail("runtime") })
        }

        SettingsGroup("数据", anchorKey = "data", onAnchor = { y -> sectionAnchors["data"] = y }) {
            SettingRow("folder", "缓存", "清理应用图片、网页与运行缓存", targetRoute = "cache", onClick = { onDetail("cache") })
            FishDivider(Modifier.padding(horizontal = 16.dp))
            SettingRow("download", "备份", "导出或导入工作区、会话与偏好", targetRoute = "backup", onClick = { onDetail("backup") })
            FishDivider(Modifier.padding(horizontal = 16.dp))
            SettingRow("archive", "对话归档", "恢复已归档对话，或立即永久删除", targetRoute = "archive", onClick = { onDetail("archive") })
            FishDivider(Modifier.padding(horizontal = 16.dp))
            SettingRow("usage", "用量概览", "DeepSeek Token 与账户余额", targetRoute = "usage", onClick = { onDetail("usage") })
        }

        SettingsGroup("支持", anchorKey = "support", onAnchor = { y -> sectionAnchors["support"] = y }) {
            SettingRow("download", "更新", "软件更新与版本策略", targetRoute = "update", onClick = { onDetail("update") })
            FishDivider(Modifier.padding(horizontal = 16.dp))
            SettingRow("chat", "反馈与建议", "告诉我们哪里需要变得更好", targetRoute = "feedback", onClick = { onDetail("feedback") })
            FishDivider(Modifier.padding(horizontal = 16.dp))
            SettingRow("bell", "公告", "查看全部产品公告与接入进展", targetRoute = "notice", onClick = { onDetail("notice") })
            FishDivider(Modifier.padding(horizontal = 16.dp))
            SettingRow("info", "关于", "版本、设计与项目说明", targetRoute = "about", onClick = { onDetail("about") })
        }
        Text("Android 前端 · 仅在对应数据页读取明确接入的本地、DeveloperCenter 或 Aries 状态，不修改 PC / Aries 数据", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
    }
    }
}

private data class NoticeFeedUiState(
    val result: NoticeStore.ReadResult,
    val refreshing: Boolean,
    val refreshError: String?,
    val remoteLoaded: Boolean,
    val onRefresh: () -> Unit = {},
)

@Composable
private fun rememberNoticeFeedState(): NoticeFeedUiState {
    val context = LocalContext.current
    val installationId = remember(context) { readDeveloperCenterInstallationId(context) }
    val store = remember(context) { NoticeStore(context) }
    val client = remember { DeveloperCenterClient() }
    var refreshRevision by remember { mutableIntStateOf(0) }
    var state by remember(context) {
        mutableStateOf(NoticeFeedUiState(NoticeStore.ReadResult(NoticeStore.Status.NO_DATA), true, null, false))
    }

    LaunchedEffect(context, installationId, refreshRevision) {
        val cached = withContext(Dispatchers.IO) { store.read() }
        state = state.copy(result = cached, refreshing = true, refreshError = null, remoteLoaded = false)
        client.getPublicMessages(installationId)
            .onSuccess { notices ->
                val write = withContext(Dispatchers.IO) { store.save(notices) }
                state = if (write.isSuccess) {
                    state.copy(result = withContext(Dispatchers.IO) { store.read() }, refreshing = false, refreshError = null, remoteLoaded = true)
                } else {
                    val error = write.exceptionOrNull()?.message ?: "公告缓存写入失败"
                    state.copy(
                        result = if (notices.isEmpty()) NoticeStore.ReadResult(NoticeStore.Status.NO_DATA) else NoticeStore.ReadResult(NoticeStore.Status.AVAILABLE, notices),
                        refreshing = false,
                        refreshError = "已读取远程公告，但未能保存到本地：$error",
                        remoteLoaded = true,
                    )
                }
            }
            .onFailure { error ->
                state = state.copy(refreshing = false, refreshError = error.message ?: "DeveloperCenter 暂不可用", remoteLoaded = false)
            }
    }
    return state.copy(onRefresh = { refreshRevision++ })
}

@Composable
private fun SettingsNoticeOverview(onDetail: (String) -> Unit, minimumHeight: Dp) {
    val reducedMotion = LocalReduceGlassMotion.current
    val duration = if (reducedMotion) 0 else GlassMotion.MorphMillis
    val noticeFeed = rememberNoticeFeedState()
    val latestNotice = noticeFeed.result.items
        .filter { it.kind == NoticeStore.Kind.ANNOUNCEMENT }
        .maxByOrNull { it.publishedAtEpochMillis }
    val preview = NoticePreviewState(
        latestNotice,
        noticeFeed.result.status,
        noticeFeed.result.errorMessage,
        noticeFeed.refreshing,
        noticeFeed.refreshError,
        noticeFeed.remoteLoaded,
    )
    DetailCard(Modifier.heightIn(min = minimumHeight)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FishGlyph("bell", Modifier.size(26.dp), MaterialTheme.colorScheme.primary)
            Text("公告", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        AnimatedContent(
            targetState = preview,
            modifier = Modifier.fillMaxWidth()
                .heightIn(min = (minimumHeight - 176.dp).coerceAtLeast(104.dp)),
            transitionSpec = {
                (fadeIn(GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion)) togetherWith
                    fadeOut(GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion)))
                    .using(SizeTransform(clip = false) { _, _ -> tween(duration, easing = GlassMotion.EaseOut) })
            },
            contentAlignment = Alignment.TopStart,
            label = "settings-notice-preview",
        ) { shown ->
            NoticePreview(shown)
        }
        FishDivider()
        DashboardActionRow(
            "bell",
            "查看全部公告",
            "已发布公告与通知",
            "notice",
            onDetail,
        )
    }
}

private data class NoticePreviewState(
    val notice: NoticeStore.Notice?,
    val status: NoticeStore.Status,
    val readError: String?,
    val refreshing: Boolean,
    val refreshError: String?,
    val remoteLoaded: Boolean,
)

@Composable
private fun NoticePreview(state: NoticePreviewState) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val notice = state.notice
        when {
            notice != null -> {
                Hint(if (state.refreshing) "最新公告 · 正在更新" else if (state.refreshError != null && !state.remoteLoaded) "最新公告 · 本地缓存" else "最新公告")
                Text(notice.title, style = MaterialTheme.typography.titleMedium)
                Hint(notice.date ?: DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(notice.publishedAtEpochMillis)))
                Text(
                    notice.markdown.ifBlank { "此公告未附正文。" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.refreshError != null) {
                    Hint(if (state.remoteLoaded) "已读取公告，暂时无法保存到本地。" else "暂时无法更新，当前展示已保存的公告。")
                }
            }
            state.refreshing -> {
                Text("正在读取公告…", style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Hint("公告加载后会在这里显示最新内容。")
            }
            state.status == NoticeStore.Status.READ_FAILED || state.refreshError != null -> {
                Text("公告读取失败", style = MaterialTheme.typography.titleMedium)
                Text(
                    state.refreshError ?: state.readError ?: "暂时无法读取公告。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Hint("打开全部公告后可以重试。")
            }
            else -> {
                Text("暂无公告", style = MaterialTheme.typography.titleMedium)
                Hint("当前没有有效的已发布公告。")
            }
        }
    }
}

@Composable
private fun HomepageChoiceRow(title: String, subtitle: String, selected: Boolean, glyph: String, enabled: Boolean = true, onSelect: () -> Unit) {
    val haptics = LocalGlassHaptics.current
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = { if (enabled) { haptics.confirm(); onSelect() } }, enabled = enabled)
            .heightIn(min = 68.dp)
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
            FishGlyph(glyph, Modifier.size(22.dp), if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RadioButton(selected = selected, onClick = null, enabled = enabled)
    }
}

@Composable
private fun DashboardActionRow(glyph: String, title: String, subtitle: String, route: String, onDetail: (String) -> Unit) {
    val navigation = LocalSettingsPageTransition.current
    val haptics = LocalGlassHaptics.current
    val interaction = remember { MutableInteractionSource() }
    var bounds by remember { mutableStateOf<Rect?>(null) }
    Row(
        Modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
            val position = coordinates.positionInWindow()
            bounds = Rect(position.x, position.y, position.x + coordinates.size.width, position.y + coordinates.size.height)
        }.clip(RoundedCornerShape(16.dp)).clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = {
            bounds?.let { navigation?.recordSource(route, it, it, it.height / 2f) }
            haptics.confirm()
            onDetail(route)
        }).heightIn(min = 60.dp).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FishGlyph(glyph, Modifier.size(22.dp), MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FishGlyph("chevron", Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsGroup(
    label: String,
    anchorKey: String? = null,
    onAnchor: ((Int) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.onGloballyPositioned { coordinates ->
            if (anchorKey != null && onAnchor != null) {
                onAnchor(coordinates.positionInParent().y.roundToInt())
            }
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionLabel(label)
        val geometry = remember { SettingsGroupGeometry() }
        Surface(modifier = Modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
            val position = coordinates.positionInWindow()
            geometry.boundsInWindow = Rect(position.x, position.y,
                position.x + coordinates.size.width, position.y + coordinates.size.height)
        }, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))) {
            CompositionLocalProvider(LocalSettingsGroupGeometry provides geometry) {
                Column(Modifier.fillMaxWidth(), content = content)
            }
        }
    }
}

private fun settingsSectionSnapTarget(
    current: Int,
    maximum: Int,
    anchors: Collection<Int>,
    velocity: Float,
): Int? {
    val sorted = anchors.distinct().sorted()
    if (sorted.isEmpty()) return null
    val threshold = 24
    return if (velocity < 0f) {
        sorted.firstOrNull { it > current + threshold }
            ?.coerceIn(0, maximum)
            ?: maximum.takeIf { current < it - threshold }
    } else {
        sorted.lastOrNull { it < current - threshold }
            ?.coerceIn(0, maximum)
            ?: 0.takeIf { current > threshold }
    }
}

@Composable
fun SettingsDetailScreen(route: String, repository: DemoRepository, onBack: () -> Unit, notify: (String) -> Unit, onPairing: () -> Unit = {}) {
    val heading = when (route) {
        "official-engine" -> "官方引擎设置" to "调整手机本地引擎的响应方式"
        "dsh-engine" -> "DSH 引擎设置" to "管理 DSH 服务的前端偏好"
        "virtual-machine" -> "Linux 运行环境" to "查看本机环境与工具版本"
        "components" -> "组件" to "安装并管理本机开发工具"
        "cache" -> "缓存" to "整理应用产生的临时内容"
        "update" -> "更新" to "选择软件更新的提醒方式"
        "feedback" -> "反馈与建议" to "把你的想法留给大肥鱼"
        // Titles must equal the source row titles on the settings home (title morph contract).
        "models" -> "模型" to "给每一种想法，选择合适的模型"
        "appearance" -> "个性化" to "一套界面，两种光线"
        "font" -> "字体" to "字体族、字号和字重"
        "notifications" -> "通知" to "有需要的时候，再提醒你"
        "backup" -> "备份" to "把值得保留的内容，带在身边"
        "archive" -> "对话归档" to "整理对话，保留或彻底删除"
        "network" -> "下载源" to "选择适合你的线路"
        "online" -> "在线服务" to "连接手机、电脑与 Aries 服务"
        "server-metrics" -> "服务器" to "实时数据 · 每2秒更新"
        "about" -> "关于" to "大肥鱼 Go · 随身的创作伙伴"
        "runtime" -> "运行详情" to "手机上的本地工作空间"
        "usage" -> "用量概览" to "看看最近的使用节奏"
        "notice" -> "公告" to "来自大肥鱼的一封短信"
        else -> "详情" to "大肥鱼 Go"
    }
    val scroll = rememberScrollState()
    val headerRange = with(LocalDensity.current) { 112.dp.toPx() }
    val collapsedTitle = rememberCollapsedTitleState(scroll.value, heading.first, headerRange)
    PublishSettingsNavigation(collapsedTitle.collapsedTitle, collapsedTitle.progress, scroll.value.toFloat())
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        // Reserve exactly the title's 112dp travel even when the page is shorter than its viewport.
        val minimumContentHeight = maxHeight + 112.dp
        Column(
            Modifier.fillMaxWidth().verticalScroll(scroll)
                .heightIn(min = minimumContentHeight).padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            SubpageHeader(heading.first, heading.second, onBack, collapseProgress = collapsedTitle.progress)
            when (route) {
                "official-engine" -> OfficialEngineContent(repository, notify)
                "dsh-engine" -> DshEngineContent(repository, notify)
                "virtual-machine" -> LinuxComponentsContent(repository)
                "components" -> LinuxComponentsContent(repository)
                "cache" -> CacheContent(repository, notify)
                "update" -> UpdateContent(repository, notify)
                "feedback" -> FeedbackContent(notify)
                "models" -> ModelsContent(repository, notify)
                "appearance" -> AppearanceContent(repository)
                "font" -> FontSettingsScreen(repository, onBack)
                "notifications" -> NotificationsContent(repository, notify)
                "backup" -> BackupContent(repository, notify)
                "archive" -> ConversationArchiveContent(repository, notify)
                "network" -> NetworkContent(repository, notify)
                "online" -> OnlineContent(repository, notify, onPairing)
                "server-metrics" -> ServerMetricsContent(notify)
                "about" -> AboutContent()
                "runtime" -> RuntimeContent(repository)
                "usage" -> UsageContent(repository)
                "notice" -> NoticeContent()
                else -> DetailCard { Text("此页面将在后续版本完善。", style = MaterialTheme.typography.bodyLarge) }
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}

@Composable
internal fun DetailCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable
internal fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ModelsContent(repository: DemoRepository, notify: (String) -> Unit) {
    val haptics = LocalGlassHaptics.current
    val providerNames = mapOf(
        "deepseek" to "DeepSeek",
        "dsh" to "DSH 引擎",
        "lite" to "本地轻引擎",
    )
    val providers = repository.modelProviderOrder
    var showForm by remember { mutableStateOf(false) }
    var providerName by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var officialKey by remember { mutableStateOf("") }
    var showClearKeyConfirm by remember { mutableStateOf(false) }

    fun clearForm() {
        providerName = ""
        endpoint = ""
        apiKey = ""
        showForm = false
    }

    fun moveProvider(from: Int, to: Int) {
        if (from !in providers.indices || to !in providers.indices) return
        val reordered = providers.toMutableList().apply {
            add(to, removeAt(from))
        }
        repository.updateModelProviderOrder(reordered)
        haptics.confirm()
    }

    DetailCard {
        Text("DeepSeek", style = MaterialTheme.typography.titleLarge)
        if (!repository.deepSeekConfigured) {
            OutlinedTextField(
                value = officialKey, onValueChange = { officialKey = it.take(512) },
                label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            FishButton(onClick = { repository.saveDeepSeekKey(officialKey); officialKey = "" },
                enabled = officialKey.isNotBlank() && !repository.deepSeekConfigurationBusy && !repository.isResponding,
                modifier = Modifier.fillMaxWidth()) { Text("安全保存") }
        } else {
            OnlineValueRow("API Key", "已加密保存")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FishButton(onClick = repository::refreshDeepSeekModels,
                    enabled = !repository.deepSeekConfigurationBusy,
                    modifier = Modifier.weight(1f)) {
                    FishGlyph("refresh", Modifier.size(18.dp))
                    Text(if (repository.deepSeekConfigurationBusy) "读取中…" else "获取模型")
                }
                TextButton(onClick = { showClearKeyConfirm = true },
                    enabled = !repository.deepSeekConfigurationBusy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("清除 Key") }
            }
            repository.modelChoices.filter { !it.isExample }.forEach { model ->
                SelectionRow(model.name, model.id, repository.selectedModel.id == model.id, "model") {
                    repository.selectModel(model.id)
                }
            }
        }
        repository.deepSeekModelError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    DetailCard {
        TinyLabel("模型目录", primary = true)
        Text("模型提供商顺序", style = MaterialTheme.typography.titleLarge)
        Hint("DeepSeek 目录由官方接口读取；提供商顺序和启用状态保存在本机。")
    }

    providers.forEachIndexed { index, providerId ->
        val provider = providerNames[providerId] ?: providerId
        val connected = providerId == "deepseek"
        val enabled = connected && repository.isModelProviderEnabled(providerId)
        DetailCard {
            ToggleRow(
                title = "${index + 1}. $provider${if (connected) "" else "（预留）"}",
                description = when (providerId) {
                    "deepseek" -> "官方文本对话"
                    "dsh" -> "DSH 聚合入口尚未接入，暂不可启用"
                    "lite" -> "手机本地运行器尚未接入，暂不可启用"
                    else -> "提供商运行器尚未接入，暂不可启用"
                },
                checked = enabled,
                enabled = connected,
                onCheckedChange = if (connected) {
                    { value -> repository.updateModelProviderEnabled(providerId, value) }
                } else null,
            )
            FishDivider()
            // DeepSeek refresh lives in the card above ("获取模型"); other providers have no remote check yet.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    onClick = { moveProvider(index, index - 1) },
                    enabled = index > 0,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("上移") }
                TextButton(
                    onClick = { moveProvider(index, index + 1) },
                    enabled = index < providers.lastIndex,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("下移") }
            }
        }
    }

    FishButton(onClick = { showForm = true }, modifier = Modifier.fillMaxWidth()) {
        FishGlyph("add", Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("添加提供商")
    }
    Hint("新增提供商只保存名称、排序和启用状态；endpoint 与密钥不会写入设置，也不会发送。")

    if (showForm) {
        AlertDialog(
            onDismissRequest = { clearForm() },
            title = { Text("添加提供商") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Hint("这里只建立本地目录条目，不验证或保存真实 API Key。")
                    OutlinedTextField(value = providerName, onValueChange = { providerName = it.take(60) }, label = { Text("提供商名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = endpoint, onValueChange = { endpoint = it.take(200) }, label = { Text("示例接口地址") }, placeholder = { Text("https://example.com/v1") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = apiKey, onValueChange = { apiKey = it.take(200) }, label = { Text("演示 API Key") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.confirm()
                        val id = SettingsRuntimeStore.normalizeProviderId(providerName)
                        if (id != null) {
                            repository.updateModelProviderEnabled(id, true)
                            clearForm()
                            notify("已添加 $providerName · 只保存本地目录状态")
                        }
                    },
                    enabled = SettingsRuntimeStore.normalizeProviderId(providerName) != null,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { haptics.confirm(); clearForm() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } },
        )
    }

    if (showClearKeyConfirm) {
        AlertDialog(
            onDismissRequest = { showClearKeyConfirm = false },
            title = { Text("清除 API Key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("确定要清除已保存的 API Key 吗？", style = MaterialTheme.typography.bodyLarge)
                    Text("此操作会中止当前回复、中止所有子任务，并移除已缓存的模型列表。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        repository.clearDeepSeekKey()
                        showClearKeyConfirm = false
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("继续清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showClearKeyConfirm = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } },
        )
    }
}

@Composable
private fun AppearanceContent(repository: DemoRepository) {
    DetailCard {
        Text("主题", style = MaterialTheme.typography.titleMedium)
        Column(Modifier.selectableGroup()) {
            SelectionRow("跟随系统", "随设备的浅色或深色模式变化", repository.themeMode == "system", "sun") { repository.updateThemeMode("system") }
            FishDivider()
            SelectionRow("浅色", "中性浅灰，清晰的阅读空间", repository.themeMode == "light", "sun") { repository.updateThemeMode("light") }
            FishDivider()
            SelectionRow("深色", "减轻夜间的明亮感", repository.themeMode == "dark", "moon") { repository.updateThemeMode("dark") }
        }
    }
    DetailCard {
        Text("主题色", style = MaterialTheme.typography.titleMedium)
        Hint("用于按钮、选中状态和页面高光；选择会立即应用并保存")
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            listOf(
                Triple("blue", "蓝", Color(0xFF007AFF)),
                Triple("purple", "紫", Color(0xFF7C4DFF)),
                Triple("green", "绿", Color(0xFF18A558)),
                Triple("orange", "橙", Color(0xFFFF9500)),
                Triple("pink", "粉", Color(0xFFFF2D55)),
            ).forEach { (key, label, color) ->
                val selected = repository.accentColorKey == key
                Column(
                    Modifier.weight(1f).clickable { repository.updateAccentColor(key) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        Modifier.size(34.dp)
                            .background(color, RoundedCornerShape(17.dp))
                            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(17.dp)) else Modifier),
                    )
                    Text(label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    DetailCard {
        Text("材质与动效", style = MaterialTheme.typography.titleMedium)
        ToggleRow("液态玻璃材质", "使用实时模糊、折射和边缘高光", repository.glassMaterial) { repository.updateGlassMaterial(it) }
        FishDivider()
        ToggleRow("页面切换效果", "设置详情从来源行展开并返回", repository.pageTransitions) { repository.updatePageTransitions(it) }
        FishDivider()
        ToggleRow("按钮 Q 弹效果", "保留按压缩小与缓出反馈", repository.springButtons) { repository.updateSpringButtons(it) }
        FishDivider()
        ToggleRow("减少透明度", "使用更实的表面，提高内容可读性", repository.reduceTransparency) { repository.updateReduceTransparency(it) }
        FishDivider()
        ToggleRow("减少动态效果", "减少页面和玻璃面板的移动", repository.reduceMotion) { repository.updateReduceMotion(it) }
    }
    DetailCard {
        Text("字体", style = MaterialTheme.typography.titleMedium)
        Hint("快速调整；完整预览在设置首页「常规 · 字体」")
        Column(Modifier.selectableGroup()) {
            com.dafeiyu.mobile.ui.theme.APP_FONT_FAMILIES.forEachIndexed { index, option ->
                if (index > 0) FishDivider()
                SelectionRow(option.title, option.description, repository.fontFamily == option.key,
                    if (index == 0) "settings" else "terminal") { repository.updateFontFamily(option.key) }
            }
        }
        Slider(value = repository.fontSizeScale, onValueChange = { repository.updateFontSizeScale(it) }, valueRange = .85f..1.2f, steps = 6)
        OnlineValueRow("字体大小", "${(repository.fontSizeScale * 100).toInt()}%")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("轻", "标准", "粗").forEach { weight ->
                FishButton(onClick = { repository.updateFontWeight(weight) }, accent = repository.fontWeight == weight, modifier = Modifier.weight(1f)) { Text(weight) }
            }
        }
    }
    DetailCard {
        Text("界面缩放", style = MaterialTheme.typography.titleMedium)
        Hint("调整设置、工作区和详情页的整体密度")
        Slider(value = repository.interfaceScale, onValueChange = { repository.updateInterfaceScale(it) }, valueRange = .85f..1.15f, steps = 5)
        OnlineValueRow("当前缩放", "${(repository.interfaceScale * 100).toInt()}%")
    }
    DetailCard {
        TinyLabel("即时预览", primary = true)
        Text("让想法，轻轻展开。", style = MaterialTheme.typography.headlineSmall)
        Hint("材质：${if (repository.glassMaterial) "液态玻璃" else "简化表面"} · 页面切换：${if (repository.pageTransitions) "开启" else "关闭"} · 字重：${repository.fontWeight}")
    }
}

@Composable
private fun SelectionRow(title: String, subtitle: String, selected: Boolean, glyph: String, onSelect: () -> Unit) {
    val haptics = LocalGlassHaptics.current
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = { haptics.confirm(); onSelect() }).heightIn(min = 72.dp).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FishGlyph(glyph, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Hint(subtitle)
        }
        RadioButton(selected = selected, onClick = null)
    }
}

private data class NotificationSystemState(
    val permissionDeclared: Boolean,
    val permissionGranted: Boolean,
    val appNotificationsEnabled: Boolean,
) {
    val canRequestRuntimePermission: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            permissionDeclared && !permissionGranted
}

private fun readNotificationSystemState(context: Context): NotificationSystemState {
    val requestedPermissions = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).requestedPermissions.orEmpty().toSet()
    }.getOrDefault(emptySet())
    val declared = Manifest.permission.POST_NOTIFICATIONS in requestedPermissions
    val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val enabled = runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }.getOrDefault(false)
    return NotificationSystemState(declared, granted, enabled)
}

private fun notificationSystemStatus(state: NotificationSystemState): String = when {
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && !state.appNotificationsEnabled -> "应用通知已在系统设置中关闭"
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> "系统通知已开启（当前 Android 版本无需运行时权限）"
    !state.permissionDeclared -> "应用未声明 Android 13 通知权限"
    !state.permissionGranted -> "系统尚未授予通知权限"
    !state.appNotificationsEnabled -> "应用通知已在系统设置中关闭"
    else -> "通知权限和应用通知开关均已开启"
}

private fun notificationSettingsIntent(context: Context): Intent? {
    val appSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    if (appSettings.resolveActivity(context.packageManager) != null) return appSettings
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:${context.packageName}"))
    return details.takeIf { it.resolveActivity(context.packageManager) != null }
}

@Composable
private fun NotificationsContent(repository: DemoRepository, notify: (String) -> Unit) {
    val context = LocalContext.current
    var statusRevision by remember { mutableIntStateOf(0) }
    val systemState = remember(context, statusRevision) { readNotificationSystemState(context) }
    val notificationStore = remember(context) { NotificationSettingsStore(context) }
    val channelState = remember(context, statusRevision) { notificationStore.readState(ensureChannels = true) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { statusRevision++ }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { statusRevision++ }
    val settingsIntent = remember(context) { notificationSettingsIntent(context) }

    DetailCard {
        TinyLabel("Android 系统", primary = systemState.appNotificationsEnabled && systemState.permissionGranted)
        Text("通知权限与系统开关", style = MaterialTheme.typography.titleMedium)
        Text(notificationSystemStatus(systemState), style = MaterialTheme.typography.bodyLarge)
        OnlineValueRow("运行时权限", when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> "当前系统无需单独授权"
            !systemState.permissionDeclared -> "应用未声明"
            systemState.permissionGranted -> "已授予"
            else -> "未授予"
        })
        OnlineValueRow("应用通知开关", if (systemState.appNotificationsEnabled) "已开启" else "已关闭")
        OnlineValueRow(
            "任务通知渠道",
            channelState.channels.firstOrNull { it.id == NotificationChannelIds.TASKS }?.let {
                when {
                    !it.exists -> "未创建"
                    it.blockedByUser -> "已被系统关闭"
                    else -> "可用"
                }
            } ?: "当前系统不支持渠道",
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (systemState.canRequestRuntimePermission) {
                FishButton(
                    onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    modifier = Modifier.weight(1f),
                ) {
                    FishGlyph("bell", Modifier.size(18.dp))
                    Text("请求权限")
                }
            }
            FishButton(
                onClick = {
                    val intent = settingsIntent
                    if (intent == null) {
                        notify("当前设备没有可用的通知设置页面")
                    } else {
                        settingsLauncher.launch(intent)
                    }
                },
                enabled = settingsIntent != null,
                accent = false,
                modifier = Modifier.weight(1f),
            ) {
                FishGlyph("settings", Modifier.size(18.dp))
                Text("系统通知设置")
            }
        }
        Hint("这里读取的是 Android 当前系统状态；下面的开关只保存大肥鱼的功能偏好。")
    }
    DetailCard {
        Text("通知偏好", style = MaterialTheme.typography.titleMedium)
        ToggleRow("灵动岛通知（预留）", "进行中状态通知尚未接入", checked = false, enabled = false)
        FishDivider()
        ToggleRow("公告推送（预留）", "公告推送服务尚未接入", checked = false, enabled = false)
        FishDivider()
        ToggleRow("通知推送（预留）", "回复与任务通知尚未接入", checked = false, enabled = false)
        FishDivider()
        ToggleRow("连接的设备推送（预留）", "设备状态推送尚未接入", checked = false, enabled = false)
    }
}

@Composable
private fun BackupContent(repository: DemoRepository, notify: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backupStore = remember { AndroidBackupStore() }
    var conversations by rememberSaveable { mutableStateOf(true) }
    var skills by rememberSaveable { mutableStateOf(true) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var backupMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var backupMessageIsError by rememberSaveable { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            runCatching {
                val bytes = withContext(Dispatchers.IO) { backupStore.export(backupStore.capture(repository, context)) }
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri) ?: error("无法打开备份目标")
                    output.use { it.write(bytes) }
                }
                backupMessageIsError = false
                backupMessage = "已导出真实 Android 备份：${bytes.size / 1024} KB"
                notify(backupMessage!!)
            }.onFailure {
                backupMessageIsError = true
                backupMessage = "备份导出失败：${it.message ?: "无法写入文件"}"
                notify(backupMessage!!)
            }
            busy = false
        }
    }
    // Step 1: read + validate only. Nothing is restored until the user confirms the preview.
    var pendingImport by remember { mutableStateOf<AndroidBackupStore.ValidatedBackup?>(null) }
    var showOverwriteConfirm by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            pendingImport = null
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { backupStore.import(it) }
                        ?: error("无法读取备份文件")
                }
            }.onSuccess {
                pendingImport = it
                backupMessage = null
            }.onFailure {
                backupMessageIsError = true
                backupMessage = "备份校验失败：${it.message ?: "格式无效"}"
                notify(backupMessage!!)
            }
            busy = false
        }
    }
    // Step 2: overwrite only after the red confirmation.
    fun restorePending() {
        val validated = pendingImport ?: return
        busy = true
        repository.restoreBackup(validated)
            .onSuccess {
                backupMessageIsError = false
                backupMessage = "备份已恢复：${validated.workspaces.size} 个工作区、${validated.conversations.size} 个会话"
                pendingImport = null
            }
            .onFailure {
                backupMessageIsError = true
                backupMessage = "备份导入失败：${it.message ?: "无法恢复"}"
            }
        notify(backupMessage!!)
        busy = false
    }
    DetailCard {
        Text("选择备份范围", style = MaterialTheme.typography.titleMedium)
        ToggleRow("对话记录", "导出工作区、会话和消息", conversations) { conversations = it }
        FishDivider()
        ToggleRow("技能与拓展清单", "导出非敏感清单选择", skills) { skills = it }
        FishDivider()
        ToggleRow("偏好设置", "导出主题、动效和下载源", settings) { settings = it }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FishButton(onClick = { exportLauncher.launch("dafeiyu-backup.json") }, enabled = !busy && (conversations || skills || settings), modifier = Modifier.weight(1f)) {
            FishGlyph("download", Modifier.size(18.dp)); Text(if (busy) "处理中…" else "导出")
        }
        FishButton(onClick = { importLauncher.launch(arrayOf("application/json")) }, enabled = !busy, accent = false, modifier = Modifier.weight(1f)) {
            FishGlyph("folder", Modifier.size(18.dp)); Text("选择备份导入")
        }
    }
    pendingImport?.let { preview ->
        val messageCount = preview.messages.values.sumOf { it.size }
        val archivedCount = preview.conversations.count { it.archivedAtMillis != null }
        DetailCard {
            TinyLabel("导入预览 · 已校验", primary = true)
            Text("即将恢复的备份", style = MaterialTheme.typography.titleMedium)
            OnlineValueRow("导出时间", formatInstalledTime(preview.exportedAtEpochMillis))
            FishDivider()
            OnlineValueRow("工作区", "${preview.workspaces.size} 个")
            FishDivider()
            OnlineValueRow("会话", if (archivedCount > 0) "${preview.conversations.size} 个（归档 $archivedCount）" else "${preview.conversations.size} 个")
            FishDivider()
            OnlineValueRow("消息", "$messageCount 条")
            FishDivider()
            OnlineValueRow("格式版本", "${preview.schema} v${preview.schemaVersion}")
            Hint("导入会覆盖当前全部工作区、会话和备份内的偏好；API Key 不受影响。")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = { pendingImport = null }, enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("取消") }
                TextButton(onClick = { showOverwriteConfirm = true }, enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("导入并覆盖", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (showOverwriteConfirm && pendingImport != null) {
        val preview = pendingImport!!
        AlertDialog(
            onDismissRequest = { showOverwriteConfirm = false },
            title = { Text("覆盖当前数据？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("将用备份中的 ${preview.workspaces.size} 个工作区、${preview.conversations.size} 个会话替换当前内容。", style = MaterialTheme.typography.bodyLarge)
                    Text("当前未备份的对话会丢失，且无法撤销。建议先导出一份当前备份。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = { showOverwriteConfirm = false; restorePending() },
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("导入并覆盖", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showOverwriteConfirm = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
            },
        )
    }
    if (backupMessage != null) {
        DetailCard {
            TinyLabel(if (backupMessageIsError) "备份错误" else "备份状态", primary = !backupMessageIsError)
            Text(
                backupMessage!!,
                style = MaterialTheme.typography.bodyMedium,
                color = if (backupMessageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    Hint("备份使用版本化 Android JSON；不包含 API Key、附件 URI、设备标识或外部 SAF 文件。导入先校验，不直接覆盖现有数据。")
}


@Composable
private fun NetworkContent(repository: DemoRepository, notify: (String) -> Unit) {
    val choices = listOf(
        Triple("official", "官方来源", "直接使用项目的官方下载地址"),
        Triple("cdn", "CDN 加速", "优先使用加速线路，失败时尝试官方来源"),
        Triple("backend", "大肥鱼后端", "通过大肥鱼下载服务获取组件"),
    )
    DetailCard {
        Column(Modifier.selectableGroup()) {
            choices.forEachIndexed { index, (value, title, subtitle) ->
                SelectionRow(title, subtitle, repository.downloadSource == value, "download") {
                    repository.updateDownloadSource(value)
                    notify("已选择$title · 新组件下载立即生效")
                }
                if (index < choices.lastIndex) FishDivider()
            }
        }
    }
    Hint("下载源用于 Ubuntu 和开发工具组件；新下载使用当前选择，已开始的传输沿用原线路。CDN 线路可回退到官方来源，大肥鱼后端使用独立下载服务。")
}

@Composable
private fun OnlineContent(repository: DemoRepository, notify: (String) -> Unit, onPairing: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ariesClient = remember(context) { AriesClient(context) }
    val groupStore = remember(context) { AriesGroupStore(context) }
    val connectionProvider = remember(context) { AriesConnectionProvider(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val savedGroupId = repository.ariesGroupId
    var groupPresence by remember { mutableStateOf<List<AriesGroupStore.Presence>?>(null) }
    var groupCache by remember { mutableStateOf<AriesGroupStore.FileTransferCacheStats?>(null) }
    var groupReadError by remember { mutableStateOf<String?>(null) }
    var groupRefresh by remember { mutableIntStateOf(0) }
    val savedAriesEndpoint = repository.ariesEndpoint.trim().takeIf { it.isNotBlank() }
    val ariesEndpoint = savedAriesEndpoint ?: AriesClient.Config().baseUrl
    val ariesConfig = remember(ariesEndpoint) { AriesClient.Config(baseUrl = ariesEndpoint) }
    var providerState by remember(ariesEndpoint, repository.ariesNodeId, savedGroupId, repository.ariesDeviceId) {
        mutableStateOf<org.json.JSONObject?>(null)
    }
    var localDevice by remember(context) { mutableStateOf(readLocalDevicePreview(context)) }
    var showPairingDialog by rememberSaveable { mutableStateOf(false) }
    var checkedAriesStatus by rememberSaveable(ariesEndpoint, repository.ariesNodeId) { mutableStateOf<String?>(null) }
    var checkedAriesNode by rememberSaveable(ariesEndpoint, repository.ariesNodeId) { mutableStateOf<String?>(null) }
    val persistedAriesStatus = when (repository.ariesConnectionState) {
        com.dafeiyu.mobile.data.OnlineConnectionState.UNCONFIGURED ->
            if (savedAriesEndpoint == null) "未配置" else "未检查"
        com.dafeiyu.mobile.data.OnlineConnectionState.CHECKING -> "检查中…"
        com.dafeiyu.mobile.data.OnlineConnectionState.OFFLINE -> "不可用"
        com.dafeiyu.mobile.data.OnlineConnectionState.HEALTHY,
        com.dafeiyu.mobile.data.OnlineConnectionState.UNREGISTERED,
        com.dafeiyu.mobile.data.OnlineConnectionState.REGISTERED,
        -> "已响应"
    }
    val ariesStatus = checkedAriesStatus ?: persistedAriesStatus
    val ariesNode = checkedAriesNode
        ?: repository.ariesServerCode
        ?: repository.ariesNodeId
        ?: "未检查"

    LaunchedEffect(context, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (isActive) {
                localDevice = withContext(Dispatchers.IO) { readLocalDevicePreview(context) }
                delay(3_000L)
            }
        }
    }
    LaunchedEffect(connectionProvider, lifecycleOwner, ariesEndpoint, repository.ariesNodeId,
        savedGroupId, repository.ariesDeviceId, groupRefresh) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            if (savedAriesEndpoint == null || savedGroupId.isNullOrBlank()) return@repeatOnLifecycle
            while (isActive) {
                providerState = try {
                    connectionProvider.call("get_connection_state")
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                delay(30_000L)
            }
        }
    }
    LaunchedEffect(savedGroupId, ariesEndpoint, repository.ariesDeviceId, groupRefresh) {
        groupPresence = null
        groupCache = null
        groupReadError = null
        if (savedGroupId.isNullOrBlank() || repository.ariesDeviceId.isNullOrBlank()) return@LaunchedEffect
        val config = AriesClient.Config(baseUrl = ariesEndpoint)
        val presence = groupStore.getPresence(savedGroupId, config)
        val cache = groupStore.getFileTransferCacheStats(savedGroupId, config)
        presence.onSuccess { groupPresence = it }.onFailure { groupReadError = it.message ?: "群组状态读取失败" }
        cache.onSuccess { groupCache = it }.onFailure { if (groupReadError == null) groupReadError = it.message ?: "缓存统计读取失败" }
    }

    val checkAries: () -> Unit = {
        scope.launch {
            checkedAriesStatus = "检查中…"
            checkedAriesNode = "检查中…"
            ariesClient.healthz(ariesConfig).fold(
                onSuccess = { health ->
                    checkedAriesStatus = health.status?.takeIf { it.isNotBlank() } ?: "已响应"
                    ariesClient.node(ariesConfig).fold(
                        onSuccess = { node ->
                            checkedAriesNode = node.serverCode ?: node.nodeId ?: "不可用"
                            repository.recordAriesConnection(
                                AriesClient.ConnectionSnapshot(
                                    endpoint = ariesEndpoint,
                                    state = AriesClient.ConnectionState.HEALTHY,
                                    health = health,
                                    node = node,
                                    deviceId = repository.ariesDeviceId,
                                ),
                            )
                            notify("Aries healthz/node 已响应 · 需要注册时请打开完整配对页")
                        },
                        onFailure = {
                            checkedAriesNode = "不可用"
                            notify("Aries node 暂不可用 · healthz 已响应")
                        },
                    )
                },
                onFailure = { error ->
                    checkedAriesStatus = "不可用"
                    checkedAriesNode = "不可用"
                    notify("Aries 暂不可用 · ${error.message ?: "连接失败"}")
                },
            )
        }
    }

    val connectionTitle = when (ariesStatus) {
        "未配置" -> "未配置"
        "未检查" -> "未检查"
        "检查中…" -> "检查中…"
        "不可用" -> "不可用"
        else -> "Aries 已响应"
    }
    val connectionDetail = when (ariesStatus) {
        "未配置" -> "尚未保存连接节点"
        "未检查" -> "尚未检查当前 Aries endpoint"
        "检查中…" -> "正在检查当前 Aries endpoint 的 healthz/node"
        "不可用" -> "当前 Aries endpoint 无法读取健康状态"
        else -> "healthz 已响应；node 状态见下方"
    }

    DetailCard {
        TinyLabel("连接状态", primary = true)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FishGlyph("link", Modifier.size(34.dp), MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(connectionTitle, style = MaterialTheme.typography.headlineSmall)
                Hint(connectionDetail)
            }
            TinyLabel(if (ariesStatus == "不可用") "不可用" else "只读")
        }
        FishButton(
            onClick = checkAries,
            modifier = Modifier.fillMaxWidth(),
        ) {
            FishGlyph("link", Modifier.size(18.dp))
            Text("检查连接")
        }
    }

    DetailCard {
        TinyLabel("设备", primary = true)
        Text("双设备连接", style = MaterialTheme.typography.titleLarge)
        OnlineDeviceRow(
            glyph = "camera",
            title = localDevice.name,
            subtitle = "手机 · ${localDevice.system}",
            status = "本机",
        ) {
            DeviceMetric("CPU", localDevice.cpu)
            DeviceMetric("内存", localDevice.memory)
            DeviceMetric("GPU", localDevice.gpu)
            DeviceMetric("网络", localDevice.network)
            DeviceMetric("存储", localDevice.storage)
        }
        FishDivider()
        val remote = providerState?.optJSONObject("remote_device")
        val pairingLabel = when (providerState?.optString("pairing_status")) {
            "paired" -> "已安全配对"
            "awaiting_approval" -> "等待配对确认"
            "revoked" -> "成员资格已撤销"
            "unpaired" -> "待配对"
            else -> "状态未知"
        }
        if (remote == null) {
            OnlineValueRow("对端设备", if (savedGroupId.isNullOrBlank()) "尚未配对" else "暂不可用")
            OnlineValueRow("安全连接", pairingLabel)
        } else {
            OnlineDeviceRow(
                glyph = "terminal",
                title = if (remote.isNull("name")) "对方尚未共享设备名称"
                    else remote.optString("name").ifBlank { "对方尚未共享设备名称" },
                subtitle = "群组设备 · $pairingLabel",
                status = when (remote.optString("online")) {
                    "online" -> "在线"
                    "offline" -> "离线"
                    else -> "在线状态未知"
                },
            ) {
                DeviceMetric("设备 ID", remote.getString("id"))
                DeviceMetric("角色", remote.optString("role").ifBlank { "未知" })
                DeviceMetric("最近心跳", if (remote.isNull("last_seen_at")) "未知" else remote.getString("last_seen_at"))
            }
        }
    }

    DetailCard {
        TinyLabel("设备配对", primary = true)
        Text("用配对码连接另一台设备", style = MaterialTheme.typography.titleMedium)
        Hint("这里保留快速预览；打开完整配对页后可以验证 Aries 节点与邀请码，并由你确认设备注册和入组申请。")
        FishButton(
            onClick = { showPairingDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            FishGlyph("viewfinder", Modifier.size(18.dp))
            Text("配对 / 端口")
        }
    }

    DetailCard {
        TinyLabel("Aries", primary = true)
        Text("Aries 服务器", style = MaterialTheme.typography.titleMedium)
        OnlineValueRow("当前 endpoint", ariesEndpoint)
        FishDivider()
        OnlineValueRow("健康状态", ariesStatus)
        FishDivider()
        OnlineValueRow("节点短码", ariesNode)
        FishDivider()
        OnlineValueRow("传输协议", "HTTPS · 接入后使用")
        Hint("仅使用私有 CA 和严格 HTTPS；当前检查只读取 healthz/node，不注册设备、不发送签名。")
        FishButton(
            onClick = checkAries,
            accent = false,
            modifier = Modifier.fillMaxWidth(),
        ) {
            FishGlyph("link", Modifier.size(18.dp))
            Text("检查连接")
        }
    }

    DetailCard {
        TinyLabel("Aries 组状态", primary = true)
        Text("群组 presence 与传输缓存", style = MaterialTheme.typography.titleMedium)
        if (savedGroupId.isNullOrBlank()) {
            Hint("完成配对并读取邀请码后，这里才会读取当前群组的只读状态。")
        } else if (repository.ariesDeviceId.isNullOrBlank()) {
            Hint("已保存群组 ${savedGroupId}，但本机尚未完成 Aries 设备注册。")
        } else {
            OnlineValueRow("群组", savedGroupId)
            FishDivider()
            OnlineValueRow("在线成员", groupPresence?.count { it.online }?.toString() ?: "读取中…")
            FishDivider()
            OnlineValueRow(
                "缓存状态",
                groupCache?.let { "可用 ${it.available.count} · 待清理 ${it.cleanupPending.count} · ${formatBytes(it.physicalBytes)}" }
                    ?: groupReadError ?: "读取中…",
            )
            groupPresence?.firstOrNull { it.deviceId == repository.ariesDeviceId }?.let { local ->
                FishDivider()
                OnlineValueRow("本机心跳", if (local.online) "在线" else "已过期")
            }
        }
        FishButton(
            onClick = { if (savedGroupId.isNullOrBlank()) notify("请先完成 Aries 配对") else { groupRefresh++; notify("正在读取群组状态") } },
            accent = false,
            modifier = Modifier.fillMaxWidth(),
        ) {
            FishGlyph("download", Modifier.size(18.dp))
            Text("读取群组状态")
        }
    }

    DetailCard {
        TinyLabel("连接偏好", primary = true)
        ToggleRow(
            "同步工作区（预留）",
            "设备间工作区同步尚未接入",
            checked = false,
            enabled = false,
        )
        FishDivider()
        ToggleRow(
            "DSH 网页广播（预留）",
            "网页广播服务尚未接入",
            checked = false,
            enabled = false,
        )
    }

    DetailCard {
        TinyLabel("下载源", primary = true)
        val choices = listOf(
            Triple("official", "官方来源", "直接使用项目的官方下载地址"),
            Triple("cdn", "CDN 加速", "优先使用加速线路，失败时尝试官方来源"),
            Triple("backend", "大肥鱼后端", "通过大肥鱼下载服务获取组件"),
        )
        Column(Modifier.selectableGroup()) {
            choices.forEachIndexed { index, (value, title, subtitle) ->
                SelectionRow(title, subtitle, repository.downloadSource == value, "download") {
                    repository.updateDownloadSource(value)
                    notify("已选择$title · 新组件下载立即生效")
                }
                if (index < choices.lastIndex) FishDivider()
            }
        }
        Hint("下载源用于新组件下载，已开始的传输沿用原线路。CDN 可回退到官方来源；大肥鱼后端使用独立下载服务。")
    }

    if (showPairingDialog) {
        PairingPreviewDialog(onDismiss = { showPairingDialog = false }, onOpenPairing = { showPairingDialog = false; onPairing() })
    }
}


@Composable
private fun PlatformPresenceCard(
    modifier: Modifier,
    title: String,
    snapshot: DeveloperCenterPresenceClient.PlatformSnapshot?,
    unavailableHint: String,
) {
    DetailCard(modifier) {
        TinyLabel(title, primary = true)
        Text(snapshot?.onlineCount?.toString() ?: "—", style = MaterialTheme.typography.headlineMedium)
        Hint(if (snapshot == null) unavailableHint else "在线安装实例")
        FishDivider()
        if (snapshot == null) {
            Text("版本分布不可用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (snapshot.versions.isEmpty()) {
            Text("暂无已知版本", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            snapshot.versions.take(4).forEach { version ->
                val label = buildString {
                    append(version.version)
                    version.versionCode?.let { append(" · #").append(it) }
                }
                Text("$label  ·  ${version.count} 人", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            if (snapshot.otherVersionCount > 0) {
                Hint("另有 ${snapshot.otherVersionCount} 个版本未展开")
            }
        }
    }
}

@Composable
private fun ServerMetricsContent(notify: (String) -> Unit) {
    val presenceClient = remember { DeveloperCenterPresenceClient() }
    val developerCenter = remember { DeveloperCenterClient() }
    var presence by remember {
        mutableStateOf(DeveloperCenterPresenceClient.Snapshot(DeveloperCenterPresenceClient.ServiceState.PREVIEW))
    }
    var history by remember { mutableStateOf<DeveloperCenterClient.PresenceHistory?>(null) }
    var metrics by remember { mutableStateOf<DeveloperCenterClient.ServerMetrics?>(null) }
    var metricsError by remember { mutableStateOf(false) }

    LaunchedEffect(presenceClient, developerCenter) {
        while (isActive) {
            presence = presenceClient.probe()
            developerCenter.getPresenceHistory().onSuccess { history = it }
            developerCenter.getServerMetrics()
                .onSuccess {
                    metrics = it
                    metricsError = false
                }
                .onFailure {
                    metrics = null
                    metricsError = true
                }
            delay(5_000L)
        }
    }

    val server = metrics?.current
    val serverMetricsAvailable = !metricsError && server != null
    val statusTitle = when (presence.serviceState) {
        DeveloperCenterPresenceClient.ServiceState.ONLINE -> presence.onlineDeviceCount?.let { "$it 人在线" }
            ?: presence.pcOnlineCount?.let { "PC端 $it 人在线" }
            ?: "服务在线"
        DeveloperCenterPresenceClient.ServiceState.OFFLINE -> "服务暂不可用"
        DeveloperCenterPresenceClient.ServiceState.PREVIEW -> "前端预览"
    }
    val statusDetail = when (presence.serviceState) {
        DeveloperCenterPresenceClient.ServiceState.ONLINE -> {
            val window = presence.onlineWindowSeconds?.let { "最近 ${it / 60} 分钟" } ?: "当前窗口"
            if (presence.pcOnlineCount != null && presence.androidOnlineCount != null) {
                "PC端（Windows 归入此类）${presence.pcOnlineCount} · Android ${presence.androidOnlineCount} · $window"
            } else if (presence.pcOnlineCount != null) {
                "旧版服务仅返回 PC 统计（Windows 归入 PC端）· $window"
            } else "已连接 Developer Center · $window"
        }
        DeveloperCenterPresenceClient.ServiceState.OFFLINE -> "无法读取当前在线快照，未推断在线人数"
        DeveloperCenterPresenceClient.ServiceState.PREVIEW -> "在线人数读取已预留，服务端尚未接入"
    }
    val memoryFraction = server?.memoryPercent?.takeIf { it >= 0.0 }?.div(100.0)?.toFloat()
    val diskFraction = server?.diskPercent?.takeIf { it >= 0.0 }?.div(100.0)?.toFloat()
    val memoryValue = if (server != null) {
        "${formatMetricBytes(server.memoryUsedBytes)} / ${formatMetricBytes(server.memoryTotalBytes)}"
    } else "不可用"
    val diskValue = if (server != null) {
        "${formatMetricBytes(server.diskUsedBytes)} / ${formatMetricBytes(server.diskTotalBytes)}"
    } else "不可用"
    val networkValue = if (server != null && (server.networkSendBytesPerSecond != null || server.networkReceiveBytesPerSecond != null)) {
        "↑ ${formatMetricRate(server.networkSendBytesPerSecond)} · ↓ ${formatMetricRate(server.networkReceiveBytesPerSecond)}"
    } else "不可用"
    val recentSamples = metrics?.recentSamples.orEmpty()
    val uploadSamples = recentSamples.mapNotNull { it.networkSendBytesPerSecond }

    DetailCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .16f)) {
                FishGlyph("terminal", Modifier.padding(12.dp).size(28.dp), MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TinyLabel("服务状态", primary = true)
                Text(statusTitle, style = MaterialTheme.typography.headlineSmall)
                Hint(statusDetail)
            }
            TinyLabel(if (presence.serviceState == DeveloperCenterPresenceClient.ServiceState.ONLINE) "在线" else "只读")
        }
        FishDivider()
        Hint(
            if (serverMetricsAvailable) {
                "服务器资源每 5 秒读取一次；历史采样按服务端约 5 分钟窗口返回。"
            } else {
                "服务器资源指标暂不可用，不以 0% 或 0 B 代替失败结果。"
            },
        )
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ServerResourceCard(
            Modifier.weight(1f),
            "model",
            "CPU",
            server?.cpuPercent?.let(::formatMetricPercent) ?: "不可用",
            server?.let { metric ->
                listOfNotNull(metric.processor, metric.logicalProcessors?.let { "$it 逻辑处理器" }).joinToString(" · ").ifBlank { "服务器未公开" }
            } ?: "服务器指标不可用",
            server?.cpuPercent?.takeIf { it >= 0.0 }?.div(100.0)?.toFloat(),
        )
        ServerResourceCard(
            Modifier.weight(1f),
            "usage",
            "内存",
            memoryValue,
            server?.memoryPercent?.let { "${formatMetricPercent(it)} 已用" } ?: "服务器指标不可用",
            memoryFraction,
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ServerResourceCard(
            Modifier.weight(1f),
            "link",
            "网络",
            networkValue,
            if (server != null) "发送 / 接收速率" else "服务器指标不可用",
            null,
        )
        ServerResourceCard(
            Modifier.weight(1f),
            "download",
            "硬盘",
            diskValue,
            server?.diskPercent?.let { "${formatMetricPercent(it)} 已用" } ?: "服务器指标不可用",
            diskFraction,
        )
    }

    DetailCard {
        TinyLabel("PC端在线人数 · 近24小时", primary = true)
        Text(presence.pcOnlineCount?.let { "$it 人在线" } ?: "暂无当前 PC 在线数据", style = MaterialTheme.typography.titleLarge)
        Hint(if (history == null) "历史采样暂不可用，不绘制虚假曲线。" else "来自 DeveloperCenter 的真实分钟采样；空历史不会补造数据。")
        ServerHistoryPlaceholder(history?.history.orEmpty(), presence.pcOnlineCount)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("24 小时前", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("现在", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val pc = presence.platform("pc")
        val android = presence.platform("android")
        PlatformPresenceCard(
            modifier = Modifier.weight(1f),
            title = "PC端 · 含 Windows",
            snapshot = pc,
            unavailableHint = if (presence.serviceState == DeveloperCenterPresenceClient.ServiceState.ONLINE)
                "此旧版服务只提供 PC 在线快照" else "服务端尚未返回 PC 分平台数据",
        )
        PlatformPresenceCard(
            modifier = Modifier.weight(1f),
            title = "Android端",
            snapshot = android,
            unavailableHint = if (presence.serviceState == DeveloperCenterPresenceClient.ServiceState.ONLINE)
                "旧版服务尚未提供 Android 分平台统计" else "服务端尚未返回 Android 分平台数据",
        )
    }

    DetailCard {
        TinyLabel("网络上传带宽", primary = true)
        if (uploadSamples.isEmpty()) {
            Text("不可用", style = MaterialTheme.typography.titleLarge)
            Hint("服务器带宽采样暂不可用，不绘制虚假折线。")
            ServerEmptyChart("等待服务器指标")
        } else {
            val latest = uploadSamples.last()
            Text(formatMetricRate(latest), style = MaterialTheme.typography.titleLarge)
            Hint("发送速率 · 服务端最近 ${metrics?.windowMinutes ?: 5} 分钟采样")
            ServerMetricChart(uploadSamples)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("约 5 分钟前", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("现在", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    DetailCard {
        TinyLabel("硬件信息", primary = true)
        OnlineValueRow("处理器", server?.processor ?: "不可用")
        FishDivider()
        OnlineValueRow("逻辑处理器", server?.logicalProcessors?.toString() ?: "不可用")
        OnlineValueRow("内存", memoryValue)
        OnlineValueRow("存储", diskValue)
        Hint(if (serverMetricsAvailable) "信息来自 DeveloperCenter 公开服务器指标接口。" else "服务器指标暂不可用，不以本机信息替代服务器数据。")
    }
}

@Composable
private fun ServerResourceCard(modifier: Modifier, glyph: String, title: String, value: String, detail: String, fraction: Float?) {
    DetailCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FishGlyph(glyph, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 2)
        Box(Modifier.fillMaxWidth().height(5.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f), RoundedCornerShape(5.dp))) {
            if (fraction != null) {
                Box(Modifier.fillMaxWidth(fraction.coerceIn(.04f, 1f)).fillMaxHeight().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp)))
            }
        }
        Hint(detail)
    }
}

@Composable
private fun ServerHistoryPlaceholder(points: List<DeveloperCenterClient.PresenceHistoryPoint>, currentCount: Int?) {
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)
    val accent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(142.dp)) {
        repeat(4) { row ->
            val y = size.height * row / 3f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
        for (index in 0 until 12) {
            val x = size.width * index / 11f
            drawLine(grid.copy(alpha = .22f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
        }
        if (points.size >= 2) {
            val maxValue = points.maxOf { it.onlineCount }.coerceAtLeast(1)
            val minValue = points.minOf { it.onlineCount }
            val span = (maxValue - minValue).coerceAtLeast(1)
            val path = androidx.compose.ui.graphics.Path()
            points.forEachIndexed { index, point ->
                val x = size.width * index / (points.lastIndex.coerceAtLeast(1)).toFloat()
                val normalized = (point.onlineCount - minValue).toFloat() / span
                val y = size.height - normalized * (size.height - 8.dp.toPx()) - 4.dp.toPx()
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, accent, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
            drawCircle(accent, radius = 5.dp.toPx(), center = Offset(size.width - 3.dp.toPx(), size.height - ((points.last().onlineCount - minValue).toFloat() / span) * (size.height - 8.dp.toPx()) - 4.dp.toPx()))
        } else if (currentCount != null) {
            val x = size.width - 3.dp.toPx()
            val y = size.height / 2f
            drawCircle(accent, radius = 5.dp.toPx(), center = Offset(x, y))
            drawLine(accent.copy(alpha = .45f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
        } else {
            val segment = 12.dp.toPx()
            var x = 0f
            while (x < size.width) {
                drawLine(accent.copy(alpha = .28f), Offset(x, size.height / 2f), Offset((x + segment * .55f).coerceAtMost(size.width), size.height / 2f), strokeWidth = 2.dp.toPx())
                x += segment
            }
        }
    }
}


@Composable
private fun ServerMetricChart(values: List<Double>) {
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)
    val accent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(126.dp)) {
        repeat(4) { row ->
            val y = size.height * row / 3f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
        val maxValue = values.maxOfOrNull { it.coerceAtLeast(0.0) } ?: return@Canvas
        val minValue = values.minOfOrNull { it.coerceAtLeast(0.0) } ?: 0.0
        val span = (maxValue - minValue).coerceAtLeast(1.0)
        val path = androidx.compose.ui.graphics.Path()
        values.forEachIndexed { index, value ->
            val x = size.width * index / (values.lastIndex.coerceAtLeast(1)).toFloat()
            val normalized = (value.coerceAtLeast(0.0) - minValue) / span
            val y = size.height - normalized.toFloat() * (size.height - 8.dp.toPx()) - 4.dp.toPx()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, accent, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
        val last = values.last().coerceAtLeast(0.0)
        val lastY = size.height - ((last - minValue) / span).toFloat() * (size.height - 8.dp.toPx()) - 4.dp.toPx()
        drawCircle(accent, radius = 5.dp.toPx(), center = Offset(size.width - 3.dp.toPx(), lastY))
    }
}

@Composable
private fun ServerEmptyChart(label: String) {
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)
    val accent = MaterialTheme.colorScheme.primary.copy(alpha = .28f)
    Canvas(Modifier.fillMaxWidth().height(126.dp)) {
        repeat(4) { row ->
            val y = size.height * row / 3f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
        val centerY = size.height / 2f
        drawLine(accent, Offset(0f, centerY), Offset(size.width, centerY), strokeWidth = 2.dp.toPx())
    }
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun OnlineDeviceRow(
    glyph: String,
    title: String,
    subtitle: String,
    status: String,
    metrics: @Composable ColumnScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FishGlyph(glyph, Modifier.size(26.dp), MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Hint(subtitle)
                }
                TinyLabel(status, primary = status == "本机")
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp), content = metrics)
        }
    }
}

@Composable
private fun DeviceMetric(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        // Long values (device IDs, timestamps) stay on one line and truncate instead of wrapping.
        Text(value, Modifier.weight(1f, fill = false).padding(start = 12.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

/** iOS-style value row: label left, value right-aligned on one line and ellipsized (endpoint, SHA-256, paths). */
@Composable
private fun OnlineValueRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, Modifier.weight(1f, fill = false).padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun PairingPreviewDialog(onDismiss: () -> Unit, onOpenPairing: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设备配对预览") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(190.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                        .padding(18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FishGlyph("viewfinder", Modifier.size(58.dp), MaterialTheme.colorScheme.primary)
                        Text("二维码占位", style = MaterialTheme.typography.labelLarge)
                    }
                }
                OnlineValueRow("服务器码", "O4T5-834840")
                Hint("此卡片只展示旧入口的布局；完整 Aries 配对请打开配对页。")
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭") }
                TextButton(onClick = onOpenPairing, modifier = Modifier.heightIn(min = 48.dp)) { Text("打开完整配对页") }
            }
        },
    )
}

private data class LocalDevicePreview(
    val name: String,
    val system: String,
    val cpu: String,
    val memory: String,
    val gpu: String,
    val network: String,
    val storage: String,
    val memoryUsed: String = "系统未公开",
    val storageUsed: String = "系统未公开",
    val memoryUsageFraction: Float? = null,
    val storageUsageFraction: Float? = null,
)

private fun readLocalDevicePreview(context: android.content.Context): LocalDevicePreview {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val memoryInfo = ActivityManager.MemoryInfo().also { info -> activityManager?.getMemoryInfo(info) }
    val statFs = StatFs(Environment.getDataDirectory().path)
    val totalStorage = statFs.totalBytes
    val availableStorage = statFs.availableBytes
    val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "未知 ABI" }
    val name = Build.MODEL.ifBlank { Build.DEVICE }.ifBlank { "Android 手机" }
    val system = "Android ${Build.VERSION.RELEASE ?: "未知"} · API ${Build.VERSION.SDK_INT}"
    val memory = if (memoryInfo.totalMem > 0L) {
        "${formatBytes(memoryInfo.availMem)} 可用 / ${formatBytes(memoryInfo.totalMem)}"
    } else {
        "系统未公开"
    }
    val memoryUsed = if (memoryInfo.totalMem > 0L) {
        "${formatBytes((memoryInfo.totalMem - memoryInfo.availMem).coerceAtLeast(0L))} / ${formatBytes(memoryInfo.totalMem)}"
    } else {
        "系统未公开"
    }
    val storage = if (totalStorage > 0L) {
        "${formatBytes(availableStorage)} 可用 / ${formatBytes(totalStorage)}"
    } else {
        "系统未公开"
    }
    val storageUsed = if (totalStorage > 0L) {
        "${formatBytes((totalStorage - availableStorage).coerceAtLeast(0L))} / ${formatBytes(totalStorage)}"
    } else {
        "系统未公开"
    }
    return LocalDevicePreview(
        name = name,
        system = system,
        cpu = abi,
        memory = memory,
        gpu = "系统未公开",
        network = "未连接",
        storage = storage,
        memoryUsed = memoryUsed,
        storageUsed = storageUsed,
        memoryUsageFraction = if (memoryInfo.totalMem > 0L) ((memoryInfo.totalMem - memoryInfo.availMem).toFloat() / memoryInfo.totalMem).coerceIn(0f, 1f) else null,
        storageUsageFraction = if (totalStorage > 0L) ((totalStorage - availableStorage).toFloat() / totalStorage).coerceIn(0f, 1f) else null,
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val gigabyte = 1024L * 1024L * 1024L
    val megabyte = 1024L * 1024L
    return when {
        bytes >= gigabyte -> "${bytes / gigabyte} GB"
        bytes >= megabyte -> "${bytes / megabyte} MB"
        else -> "${bytes / 1024L} KB"
    }
}


@Composable
private fun OfficialEngineContent(repository: DemoRepository, notify: (String) -> Unit) {
    val strategy = repository.officialEngineStrategy
    DetailCard {
        TinyLabel("官方轻引擎", primary = true)
        Text("手机本地的响应方式", style = MaterialTheme.typography.titleLarge)
        Hint("响应策略暂保存为偏好；实际思考模式由会话中的模型菜单控制。")
        Column(Modifier.selectableGroup()) {
            listOf(
                Triple("quick", "快速", "优先缩短等待时间，适合简单问答"),
                Triple("balanced", "平衡", "在响应速度与回答长度之间取平衡"),
                Triple("deep", "深思", "为复杂问题预留更多思考空间"),
            ).forEachIndexed { index, (value, title, subtitle) ->
                SelectionRow(title, subtitle, strategy == value, "model") { repository.updateOfficialEngineStrategy(value) }
                if (index < 2) FishDivider()
            }
        }
    }
    DetailCard {
        ToggleRow("流式输出", "逐段显示回复；关闭后等待完整回复", repository.officialEngineStreaming) { repository.updateOfficialEngineStreaming(it) }
        FishDivider()
        ToggleRow("显示思考", "显示供应商公开的思考或摘要", repository.officialEngineShowReasoning) { repository.updateOfficialEngineShowReasoning(it) }
        FishDivider()
        ToggleRow("默认全部展开", "思考、命令、子 Agent 与搜索活动", repository.officialEngineExpandActivities) { repository.updateOfficialEngineExpandActivities(it) }
    }
    Hint("开关会立即保存，下次回复生效。")
}

@Composable
private fun DshEngineContent(repository: DemoRepository, notify: (String) -> Unit) {
    val version = repository.dshVersionChannel
    val updatePolicy = repository.dshUpdatePolicy
    DetailCard {
        TinyLabel("服务状态", primary = true)
        Text("尚未连接", style = MaterialTheme.typography.headlineSmall)
        Hint("DSH 引擎尚未接入 · 不会启动、停止或探测真实 DSH 服务。")
    }
    DetailCard {
        Text("DSH 版本", style = MaterialTheme.typography.titleMedium)
        Column(Modifier.selectableGroup()) {
            SelectionRow("稳定版", "适合日常使用的预览选项", version == "stable", "download") { repository.updateDshVersionChannel("stable") }
            FishDivider()
            SelectionRow("测试版", "体验新功能的预览选项", version == "preview", "model") { repository.updateDshVersionChannel("preview") }
        }
    }
    DetailCard {
        Text("自动更新", style = MaterialTheme.typography.titleMedium)
        Column(Modifier.selectableGroup()) {
            SelectionRow("自动下载并更新", "后端接入后自动完成更新", updatePolicy == "auto", "download") { repository.updateDshUpdatePolicy("auto") }
            FishDivider()
            SelectionRow("仅提醒", "有新版本时显示提醒", updatePolicy == "remind", "bell") { repository.updateDshUpdatePolicy("remind") }
            FishDivider()
            SelectionRow("关闭", "不主动检查新版本", updatePolicy == "off", "close") { repository.updateDshUpdatePolicy("off") }
        }
        FishDivider()
        ToggleRow("自动启动 DSH 服务（预留）", "服务尚未接入，暂时无法自动启动", checked = false, enabled = false)
    }
}

private enum class ComponentAvailability(val label: String) {
    AVAILABLE("可用"),
    NEEDS_PERMISSION("需权限"),
    NOT_CONNECTED("未接入"),
}

private data class ComponentCapability(
    val key: String,
    val title: String,
    val subtitle: String,
    val glyph: String,
    val recommended: Boolean,
    val availability: ComponentAvailability,
    val detail: String,
)

private fun readComponentCapabilities(context: Context): List<ComponentCapability> {
    val packageManager = context.packageManager
    val requestedPermissions = runCatching {
        packageManager.getPackageInfo(context.packageName, 0).requestedPermissions.orEmpty().toSet()
    }.getOrDefault(emptySet())
    val cameraDeclared = Manifest.permission.CAMERA in requestedPermissions
    val cameraPresent = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    val cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    val cameraAvailability = when {
        !cameraDeclared || !cameraPresent -> ComponentAvailability.NOT_CONNECTED
        !cameraGranted -> ComponentAvailability.NEEDS_PERMISSION
        else -> ComponentAvailability.AVAILABLE
    }
    val cameraDetail = when {
        !cameraDeclared -> "应用未声明相机权限"
        !cameraPresent -> "设备没有可用相机硬件"
        !cameraGranted -> "已声明相机权限，但系统尚未授权"
        else -> "已声明、设备可用且系统已授权"
    }

    fun resolves(intent: Intent): Boolean = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
    val photoPickerIntent = if (Build.VERSION.SDK_INT >= 33) {
        Intent("android.provider.action.PICK_IMAGES")
    } else {
        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
    }
    val photoPickerAvailable = resolves(photoPickerIntent)
    val filePickerAvailable = resolves(
        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
    )
    val notificationsDeclared = Manifest.permission.POST_NOTIFICATIONS in requestedPermissions
    val notificationsGranted = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val notificationAvailability = if (notificationsDeclared && notificationsGranted && notificationsEnabled) {
        ComponentAvailability.AVAILABLE
    } else {
        ComponentAvailability.NEEDS_PERMISSION
    }
    val notificationDetail = when {
        !notificationsDeclared -> "应用未声明通知权限"
        !notificationsGranted -> "系统尚未授予通知权限"
        !notificationsEnabled -> "应用通知已在系统设置中关闭"
        else -> "通知权限和应用通知开关均已开启"
    }
    val developerCenterAvailable = Manifest.permission.INTERNET in requestedPermissions
    val ariesCaAvailable = runCatching {
        val resourceId = context.resources.getIdentifier("dafeiyu_download_ca", "raw", context.packageName)
        if (resourceId == 0) return@runCatching false
        context.resources.openRawResource(resourceId).use { input ->
            CertificateFactory.getInstance("X.509").generateCertificate(input)
        }
        true
    }.getOrDefault(false)
    val workspaceRoot = File(context.filesDir, "Dafeiyu-Works")
    val workspaceAvailable = (workspaceRoot.isDirectory && workspaceRoot.canRead() && workspaceRoot.canWrite()) ||
        (!workspaceRoot.exists() && context.filesDir.isDirectory && context.filesDir.canWrite())

    return listOf(
        ComponentCapability(
            key = "local-chat",
            title = "本地对话核心",
            subtitle = "手机端工作区与对话界面",
            glyph = "model",
            recommended = true,
            availability = ComponentAvailability.AVAILABLE,
            detail = "应用内核心页面已随应用提供",
        ),
        ComponentCapability(
            key = "camera",
            title = "相机与二维码",
            subtitle = "扫码配对和拍摄附件",
            glyph = "scan",
            recommended = true,
            availability = cameraAvailability,
            detail = cameraDetail,
        ),
        ComponentCapability(
            key = "photo-picker",
            title = "系统照片选择器",
            subtitle = "由 Android 选择图片，不读取整库",
            glyph = "photo",
            recommended = true,
            availability = if (photoPickerAvailable) ComponentAvailability.AVAILABLE else ComponentAvailability.NOT_CONNECTED,
            detail = if (photoPickerAvailable) "系统已提供可调用的照片选择器" else "系统没有返回可用的照片选择器",
        ),
        ComponentCapability(
            key = "file-picker",
            title = "文件选择器",
            subtitle = "通过 Storage Access Framework 选择文件",
            glyph = "folder",
            recommended = true,
            availability = if (filePickerAvailable) ComponentAvailability.AVAILABLE else ComponentAvailability.NOT_CONNECTED,
            detail = if (filePickerAvailable) "系统已提供 ACTION_OPEN_DOCUMENT" else "系统没有返回可用的文件选择器",
        ),
        ComponentCapability(
            key = "notifications",
            title = "系统通知",
            subtitle = "公告、任务和设备连接提醒",
            glyph = "bell",
            recommended = true,
            availability = notificationAvailability,
            detail = notificationDetail,
        ),
        ComponentCapability(
            key = "developer-center",
            title = "DeveloperCenter 在线模块",
            subtitle = "在线人数、服务器指标和反馈接口",
            glyph = "link",
            recommended = false,
            availability = if (developerCenterAvailable) ComponentAvailability.AVAILABLE else ComponentAvailability.NEEDS_PERMISSION,
            detail = if (developerCenterAvailable) "客户端已集成，网络连接按在线页面按需检测" else "应用未声明网络权限",
        ),
        ComponentCapability(
            key = "aries-ca",
            title = "Aries CA 资源",
            subtitle = "10240 私有 CA 信任资源",
            glyph = "lock",
            recommended = false,
            availability = if (ariesCaAvailable) ComponentAvailability.AVAILABLE else ComponentAvailability.NOT_CONNECTED,
            detail = if (ariesCaAvailable) "应用内 CA 证书可读取且可解析" else "未找到可解析的 Aries CA 资源",
        ),
        ComponentCapability(
            key = "workspace-storage",
            title = "工作区存储",
            subtitle = "应用私有 Dafeiyu-Works 数据目录",
            glyph = "folder",
            recommended = true,
            availability = if (workspaceAvailable) ComponentAvailability.AVAILABLE else ComponentAvailability.NOT_CONNECTED,
            detail = if (workspaceAvailable) "应用私有目录可读写，按需创建工作区索引" else "应用私有工作区目录不可读写",
        ),
    )
}

@Composable
private fun ComponentsContent(notify: (String) -> Unit) {
    val context = LocalContext.current
    var componentScope by rememberSaveable { mutableStateOf("recommended") }
    var showLocalChat by rememberSaveable { mutableStateOf(true) }
    var showScanner by rememberSaveable { mutableStateOf(true) }
    var showFiles by rememberSaveable { mutableStateOf(true) }
    var capabilities by remember(context) { mutableStateOf(readComponentCapabilities(context)) }
    val visibleCapabilities = capabilities
        .filter { componentScope == "full" || it.recommended }
        .filter { capability ->
            when (capability.key) {
                "local-chat" -> showLocalChat
                "camera" -> showScanner
                "photo-picker", "file-picker", "workspace-storage" -> showFiles
                else -> true
            }
        }
    val availableCount = capabilities.count { it.availability == ComponentAvailability.AVAILABLE }

    DetailCard {
        TinyLabel("工作区组件", primary = true)
        Text("按需要显示能力", style = MaterialTheme.typography.titleLarge)
        Hint("这里显示应用当前能证明的能力。开关只控制页面展示，不会伪造安装或授权状态。")
        Column(Modifier.selectableGroup()) {
            SelectionRow("推荐组件", "显示适合手机端的基础能力", componentScope == "recommended", "grid") { componentScope = "recommended" }
            FishDivider()
            SelectionRow("完整组件", "显示全部已检测的能力入口", componentScope == "full", "model") { componentScope = "full" }
        }
        FishDivider()
        ToggleRow("显示本地对话核心", "只控制本页是否显示该能力", showLocalChat) { showLocalChat = it }
        FishDivider()
        ToggleRow("显示扫码能力", "只控制本页是否显示相机与二维码", showScanner) { showScanner = it }
        FishDivider()
        ToggleRow("显示文件能力", "只控制本页是否显示照片、文件和工作区存储", showFiles) { showFiles = it }
    }
    DetailCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("能力检测", style = MaterialTheme.typography.titleMedium)
                Hint("${availableCount}/${capabilities.size} 项当前可用 · 本地读取")
            }
            Text("${capabilities.size} 项", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        FishDivider()
        visibleCapabilities.forEachIndexed { index, capability ->
            ComponentCapabilityRow(capability)
            if (index < visibleCapabilities.lastIndex) FishDivider()
        }
        if (visibleCapabilities.isEmpty()) {
            Hint("当前显示范围为空，请打开一个展示开关。")
        }
        FishButton(
            onClick = {
                capabilities = readComponentCapabilities(context)
                notify("组件状态已重新读取")
            },
            accent = false,
            modifier = Modifier.fillMaxWidth(),
        ) {
            FishGlyph("download", Modifier.size(18.dp))
            Text("刷新组件状态")
        }
    }
}

@Composable
private fun ComponentCapabilityRow(capability: ComponentCapability) {
    val stateColor = when (capability.availability) {
        ComponentAvailability.AVAILABLE -> MaterialTheme.colorScheme.primary
        ComponentAvailability.NEEDS_PERMISSION -> MaterialTheme.colorScheme.tertiary
        ComponentAvailability.NOT_CONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FishGlyph(capability.glyph, Modifier.size(22.dp), stateColor)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(capability.title, style = MaterialTheme.typography.titleSmall)
            Text(capability.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(capability.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(capability.availability.label, style = MaterialTheme.typography.labelMedium, color = stateColor)
    }
}

@Composable
private fun CacheContent(repository: DemoRepository, notify: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val autoCleanup = repository.autoCleanupCache
    var busy by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf<CacheStore.Stats?>(null) }
    var scanFailureDetail by remember { mutableStateOf<String?>(null) }
    var failureDetail by remember { mutableStateOf<String?>(null) }
    val protectedPaths = repository.attachmentPreviews.mapNotNull { it.uri }

    fun applyCleanupResult(result: CacheStore.CleanupResult, automatic: Boolean) {
        stats = result.remaining
        scanFailureDetail = result.remaining.failures.takeIf { it.isNotEmpty() }?.let { failures ->
            "${failures.size} 项无法读取：${failures.first().reason}"
        }
        failureDetail = result.failures.takeIf { it.isNotEmpty() }?.let { failures ->
            "${failures.size} 项未能清理：${failures.first().reason}"
        }
        val prefix = if (automatic) "自动清理完成" else "已清理应用临时缓存"
        val failureSuffix = result.failures.takeIf { it.isNotEmpty() }?.let { "，失败 ${it.size} 项" }.orEmpty()
        notify("$prefix ${formatBytes(result.deletedBytes)}$failureSuffix")
    }

    LaunchedEffect(protectedPaths) {
        try {
            val snapshot = CacheStore.stats(context, protectedPaths)
            stats = snapshot
            scanFailureDetail = snapshot.failures.takeIf { it.isNotEmpty() }?.let { failures ->
                "${failures.size} 项无法读取：${failures.first().reason}"
            }
        } catch (error: Exception) {
            stats = null
            scanFailureDetail = "缓存扫描失败：${error.message ?: "无法读取应用缓存目录"}"
        }
    }
    LaunchedEffect(autoCleanup) {
        if (!autoCleanup) return@LaunchedEffect
        busy = true
        try {
            applyCleanupResult(CacheStore.cleanupExpired(context, protectedPaths), automatic = true)
        } finally {
            busy = false
        }
    }

    val currentStats = stats
    val totalFormatted = currentStats?.let { formatBytes(it.totalBytes) } ?: "读取中…"
    val attachmentFormatted = currentStats?.byDirectory?.get("attachment-previews")?.bytes?.let(::formatBytes) ?: "读取中…"
    val previewFormatted = currentStats?.let {
        formatBytes(
            listOf("web-previews", "qr-previews").sumOf { name -> it.byDirectory[name]?.bytes ?: 0L },
        )
    } ?: "读取中…"
    val temporaryFormatted = currentStats?.let {
        formatBytes(
            listOf("network-downloads", "runtime-temp").sumOf { name -> it.byDirectory[name]?.bytes ?: 0L },
        )
    } ?: "读取中…"

    DetailCard {
        TinyLabel("本地缓存", primary = true)
        Text("临时内容整理", style = MaterialTheme.typography.titleLarge)
        Hint("只管理应用自有临时目录；工作区索引、会话和外部 SAF 文件始终不会被清理。")
        OnlineValueRow("图片与附件预览", attachmentFormatted)
        FishDivider()
        OnlineValueRow("网页与二维码预览", previewFormatted)
        FishDivider()
        OnlineValueRow("下载与运行临时文件", temporaryFormatted)
        FishDivider()
        OnlineValueRow("可清理总量", totalFormatted)
        FishDivider()
        OnlineValueRow("临时文件数", currentStats?.totalFiles?.toString() ?: "读取中…")
        if (scanFailureDetail != null) {
            FishDivider()
            Text("缓存读取：$scanFailureDetail", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (failureDetail != null) {
            FishDivider()
            Text("上次清理：$failureDetail", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
    DetailCard {
        ToggleRow("自动清理临时缓存", "进入此页面时清理超过 24 小时或超出每目录 12 个的未引用文件", repository.autoCleanupCache) { repository.updateAutoCleanupCache(it) }
        FishDivider()
        FishButton(
            onClick = {
                if (!busy) {
                    scope.launch {
                        busy = true
                        try {
                            applyCleanupResult(CacheStore.clear(context, protectedPaths), automatic = false)
                        } finally {
                            busy = false
                        }
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            FishGlyph("close", Modifier.size(18.dp))
            Text(if (busy) "正在整理…" else "清理未引用临时缓存")
        }
    }
}

private data class InstalledPackageSnapshot(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val error: String? = null,
)

private fun readInstalledPackageSnapshot(context: Context): InstalledPackageSnapshot {
    return runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        InstalledPackageSnapshot(
            packageName = info.packageName,
            versionName = info.versionName ?: "未设置",
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong(),
            firstInstallTime = info.firstInstallTime,
            lastUpdateTime = info.lastUpdateTime,
        )
    }.getOrElse { error ->
        InstalledPackageSnapshot(
            packageName = context.packageName,
            versionName = "未知",
            versionCode = -1L,
            firstInstallTime = 0L,
            lastUpdateTime = 0L,
            error = error.message ?: "无法读取当前安装包",
        )
    }
}

private fun formatInstalledTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return "不可用"
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(Date(epochMillis))
}

@Composable
private fun UpdateContent(repository: DemoRepository, notify: (String) -> Unit) {
    val context = LocalContext.current
    val installedPackage = remember(context) { readInstalledPackageSnapshot(context) }
    val policy = repository.updatePolicy
    val developerCenter = remember { DeveloperCenterClient() }
    val scope = rememberCoroutineScope()
    var bundleVersion by rememberSaveable { mutableStateOf(if (repository.dshVersionChannel == "preview") "0.2.1-alpha.1" else "0.2.0-rc.2") }
    var bundleManifest by remember { mutableStateOf<DeveloperCenterClient.DshBundleManifest?>(null) }
    var bundleError by remember { mutableStateOf<String?>(null) }
    var bundleBusy by remember { mutableStateOf(false) }
    val localReadStatus = installedPackage.error?.let { "读取失败：$it" } ?: "已读取当前安装包信息"

    DetailCard {
        TinyLabel("软件更新", primary = true)
        Text("保持大肥鱼 Go 最新", style = MaterialTheme.typography.titleLarge)
        Hint("更新策略保存在当前页面；Android 安装仍由系统包管理器确认，应用不能静默替换自身。")
        Column(Modifier.selectableGroup()) {
            SelectionRow("自动下载并更新", "有可用版本时自动处理下载", policy == "auto", "download") { repository.updateUpdatePolicy("auto") }
            FishDivider()
            SelectionRow("仅提醒", "由你决定何时下载和安装", policy == "remind", "bell") { repository.updateUpdatePolicy("remind") }
            FishDivider()
            SelectionRow("关闭", "不主动检查更新", policy == "off", "close") { repository.updateUpdatePolicy("off") }
        }
        FishDivider()
        ToggleRow("仅在 Wi-Fi 下下载（预留）", "应用更新下载服务尚未接入", checked = false, enabled = false)
    }
    DetailCard {
        TinyLabel("当前安装包", primary = installedPackage.error == null)
        Text(localReadStatus, style = MaterialTheme.typography.titleMedium,
            color = if (installedPackage.error == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
        OnlineValueRow("包名", installedPackage.packageName)
        OnlineValueRow("版本", "${installedPackage.versionName} · versionCode ${if (installedPackage.versionCode >= 0) installedPackage.versionCode else "未知"}")
        OnlineValueRow("首次安装", formatInstalledTime(installedPackage.firstInstallTime))
        OnlineValueRow("最近更新", formatInstalledTime(installedPackage.lastUpdateTime))
        if (installedPackage.error != null) {
            Hint("无法读取完整安装包信息；请稍后重试。")
        }
    }
    Hint("Android 更新服务尚未接入：当前不发起网络请求、不下载文件，也不触发安装。")
    DetailCard {
        TinyLabel("Windows DSH bundle", primary = true)
        Text("读取已发布的 Windows bundle manifest", style = MaterialTheme.typography.titleMedium)
        Hint("此接口只描述 Windows x64 / Node 22 的 DSH bundle，不是 Android APK，也不会自动下载或安装。")
        OutlinedTextField(
            value = bundleVersion,
            onValueChange = { bundleVersion = it.take(80); bundleError = null },
            label = { Text("精确版本") },
            singleLine = true,
            enabled = !bundleBusy,
            modifier = Modifier.fillMaxWidth(),
        )
        FishButton(
            onClick = {
                scope.launch {
                    bundleBusy = true
                    bundleError = null
                    bundleManifest = null
                    developerCenter.getDshBundleManifest(bundleVersion)
                        .onSuccess { bundleManifest = it }
                        .onFailure { bundleError = it.message ?: "暂时无法读取 bundle manifest" }
                    bundleBusy = false
                }
            },
            enabled = !bundleBusy && bundleVersion.isNotBlank(),
            accent = false,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (bundleBusy) "读取中…" else "读取 bundle manifest") }
        bundleManifest?.let { manifest ->
            FishDivider()
            OnlineValueRow("平台", "${manifest.platform} · Node ${manifest.nodeMajor}")
            OnlineValueRow("文件", manifest.fileName)
            OnlineValueRow("大小", formatBytes(manifest.sizeBytes))
            OnlineValueRow("SHA-256", manifest.sha256)
        }
        bundleError?.let { Hint("读取失败：$it") }
    }
}

@Composable
private fun FeedbackContent(notify: (String) -> Unit) {
    val context = LocalContext.current
    val developerCenter = remember { DeveloperCenterClient() }
    val scope = rememberCoroutineScope()
    val maxLength = 1000
    val installationId = remember(context) { readDeveloperCenterInstallationId(context) }
    val machineId = remember(context) { readDeveloperCenterMachineId(context) }
    var category by rememberSaveable { mutableStateOf("suggestion") }
    var feedbackText by rememberSaveable { mutableStateOf("") }
    var showPreview by rememberSaveable { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    var myFeedback by remember { mutableStateOf<List<DeveloperCenterClient.FeedbackItem>?>(null) }
    var feedbackLoaded by remember { mutableStateOf(false) }
    var feedbackLoadError by remember { mutableStateOf<String?>(null) }
    var expandedFeedbackId by rememberSaveable { mutableStateOf<String?>(null) }
    var supplementCache by remember { mutableStateOf<Map<String, List<DeveloperCenterClient.FeedbackSupplement>>>(emptyMap()) }
    var supplementNextOffset by remember { mutableStateOf<Map<String, Int?>>(emptyMap()) }
    var supplementLoading by remember { mutableStateOf<Set<String>>(emptySet()) }
    var supplementDrafts by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var supplementPosting by remember { mutableStateOf<Set<String>>(emptySet()) }
    var upvoteUpdating by remember { mutableStateOf<Set<String>>(emptySet()) }
    var feedbackActionErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var feedbackBanStatus by remember { mutableStateOf<DeveloperCenterClient.FeedbackBanStatus?>(null) }
    var feedbackBanCheckError by remember { mutableStateOf<String?>(null) }
    val categoryLabel = when (category) {
        "issue" -> "问题反馈"
        "experience" -> "体验想法"
        else -> "功能建议"
    }

    LaunchedEffect(developerCenter, machineId) {
        developerCenter.getFeedbackBanStatus(machineId)
            .onSuccess { feedbackBanStatus = it; feedbackBanCheckError = null }
            .onFailure { feedbackBanCheckError = it.message ?: "无法检查反馈权限" }
    }

    LaunchedEffect(developerCenter, machineId, installationId) {
        developerCenter.getFeedback(machineId = machineId, installationId = installationId)
            .onSuccess { page ->
                myFeedback = page.feedback
                feedbackLoaded = true
                feedbackLoadError = null
                supplementCache = page.feedback.associate { item -> item.id.toString() to item.supplements }
                supplementNextOffset = page.feedback.associate { item -> item.id.toString() to item.nextSupplementOffset }
            }
            .onFailure {
                myFeedback = emptyList()
                feedbackLoaded = true
                feedbackLoadError = it.message ?: "暂时无法读取反馈"
                supplementCache = emptyMap()
            }
    }

    fun toggleFeedbackUpvote(item: DeveloperCenterClient.FeedbackItem) {
        val id = item.id.toString()
        if (id in upvoteUpdating) return
        upvoteUpdating = upvoteUpdating + id
        feedbackActionErrors = feedbackActionErrors - id
        scope.launch {
            val shouldUpvote = item.hasUpvoted != true
            val result = if (shouldUpvote) {
                developerCenter.postFeedbackUpvote(id, machineId)
            } else {
                developerCenter.deleteFeedbackUpvote(id, machineId)
            }
            result.onSuccess { vote ->
                myFeedback = myFeedback.orEmpty().map { current ->
                    if (current.id == item.id) current.copy(upvoteCount = vote.upvoteCount, hasUpvoted = vote.hasUpvoted) else current
                }
            }.onFailure { error ->
                feedbackActionErrors = feedbackActionErrors + (id to (error.message ?: "支持状态更新失败"))
            }
            upvoteUpdating = upvoteUpdating - id
        }
    }

    fun postSupplement(item: DeveloperCenterClient.FeedbackItem) {
        val id = item.id.toString()
        val body = supplementDrafts[id].orEmpty().trim()
        if (body.isEmpty() || id in supplementPosting) return
        supplementPosting = supplementPosting + id
        feedbackActionErrors = feedbackActionErrors - id
        scope.launch {
            developerCenter.postFeedbackSupplement(id, installationId, machineId, body)
                .onSuccess { updated ->
                    myFeedback = myFeedback.orEmpty().map { current -> if (current.id == updated.id) updated else current }
                    supplementCache = supplementCache + (id to updated.supplements)
                    supplementNextOffset = supplementNextOffset + (id to updated.nextSupplementOffset)
                    supplementDrafts = supplementDrafts - id
                }
                .onFailure { error ->
                    feedbackActionErrors = feedbackActionErrors + (id to (error.message ?: "补充反馈失败"))
                }
            supplementPosting = supplementPosting - id
        }
    }

    fun loadOlderSupplements(item: DeveloperCenterClient.FeedbackItem) {
        val id = item.id.toString()
        val offset = supplementNextOffset[id] ?: item.nextSupplementOffset ?: return
        if (id in supplementLoading) return
        supplementLoading = supplementLoading + id
        scope.launch {
            developerCenter.getFeedbackSupplements(id, offset)
                .onSuccess { page ->
                    val merged = ((supplementCache[id].orEmpty() + page.supplements)
                        .distinctBy { supplement -> supplement.id })
                    supplementCache = supplementCache + (id to merged)
                    supplementNextOffset = supplementNextOffset + (id to page.nextOffset)
                }
                .onFailure { notify("暂时无法读取更多补充") }
            supplementLoading = supplementLoading - id
        }
    }

    DetailCard {
        TinyLabel("反馈与建议", primary = true)
        Text("让下一版更贴近你的工作方式", style = MaterialTheme.typography.titleLarge)
        Hint("提交时只使用随机安装 UUID、应用版本和你填写的正文；诊断摘要不会上传。")
        feedbackBanStatus?.takeIf { it.banned }?.let { ban ->
            Text(
                "此匿名安装暂时无法提交反馈：${ban.reason ?: "服务端限制"}${if (ban.permanent) "（永久）" else ban.expiresAt?.let { "（至 $it）" }.orEmpty()}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        feedbackBanCheckError?.let { Hint("尚未确认服务端反馈权限：$it") }
        Column(Modifier.selectableGroup()) {
            SelectionRow("功能建议", "告诉我们你希望增加什么", category == "suggestion", "model") { category = "suggestion"; showPreview = false; submitted = false }
            FishDivider()
            SelectionRow("问题反馈", "告诉我们哪里没有按预期工作", category == "issue", "bell") { category = "issue"; showPreview = false; submitted = false }
            FishDivider()
            SelectionRow("体验想法", "分享界面、动效或交互感受", category == "experience", "chat") { category = "experience"; showPreview = false; submitted = false }
        }
        OutlinedTextField(
            value = feedbackText,
            onValueChange = {
                feedbackText = it.take(maxLength)
                showPreview = false
                submitted = false
            },
            label = { Text("反馈正文") },
            placeholder = { Text("写下你希望保留、修复或改进的内容") },
            supportingText = { Text("${feedbackText.length}/$maxLength") },
            minLines = 5,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
        Hint("正文只保留在当前页面状态；关闭页面后会清空。")
    }
    DetailCard {
        TinyLabel("匿名提交", primary = true)
        Hint("不附带姓名、联系人或设备名称；服务端只接收随机安装 UUID。")
        FishDivider()
        ToggleRow("附带设备诊断（预留）", "设备诊断提交尚未接入", checked = false, enabled = false)
        FishDivider()
        FishButton(
            onClick = {
                showPreview = true
                submitted = false
                notify("已生成反馈提交预览 · 等待确认")
            },
            enabled = feedbackText.isNotBlank() && feedbackBanStatus?.banned != true,
            modifier = Modifier.fillMaxWidth(),
        ) {
            FishGlyph("send", Modifier.size(18.dp))
            Text("生成提交预览")
        }
        Hint(if (feedbackText.isBlank()) "填写正文后才能生成预览。" else "预览会展示反馈类型和正文。")
    }
    if (showPreview) {
        DetailCard {
            TinyLabel("提交预览", primary = true)
            Text("$categoryLabel · 匿名提交", style = MaterialTheme.typography.titleMedium)
            Text(feedbackText.trim(), style = MaterialTheme.typography.bodyLarge)
            Hint("不附带设备诊断")
            FishDivider()
            if (submitted) {
                Hint("反馈已提交；正文仍只保留在当前页面状态。")
            } else {
                Hint("确认后将使用现有匿名安装 UUID 和当前应用版本提交；不会上传诊断。")
                FishButton(
                    onClick = {
                        if (!submitting && feedbackText.isNotBlank()) {
                            submitting = true
                            val apiCategory = if (category == "issue") "Bug" else "Suggestion"
                            val title = categoryLabel
                            val body = feedbackText.trim()
                            val launcherVersion = readLauncherVersion(context)
                            scope.launch {
                                developerCenter.postFeedback(
                                    category = apiCategory,
                                    title = title,
                                    body = body,
                                    installationId = installationId,
                                    launcherVersion = launcherVersion,
                                    machineId = machineId,
                                ).onSuccess {
                                    submitted = true
                                    notify("反馈已提交 · 感谢你的建议")
                                    developerCenter.getFeedback(machineId, installationId)
                                        .onSuccess { page ->
                                            myFeedback = page.feedback
                                            feedbackLoaded = true
                                            supplementCache = page.feedback.associate { item -> item.id.toString() to item.supplements }
                                        }
                                }.onFailure {
                                    notify("反馈提交失败：${it.message?.take(80) ?: "服务暂不可用"}")
                                }
                                submitting = false
                            }
                        }
                    },
                    enabled = !submitting,
                    accent = false,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (submitting) "正在提交…" else "确认提交")
                }
            }
        }
    }
    DetailCard {
        TinyLabel("我的反馈", primary = true)
        when {
            !feedbackLoaded -> Hint("正在读取你的反馈…")
            myFeedback.isNullOrEmpty() -> Hint("暂无反馈记录；网络不可用或你还没有提交反馈。")
            else -> {
                myFeedback.orEmpty().forEachIndexed { index, item ->
                    if (index > 0) FishDivider()
                    val id = item.id.toString()
                    val expanded = expandedFeedbackId == id
                    val supplements = supplementCache[id].orEmpty()
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { expandedFeedbackId = if (expanded) null else id }
                            .padding(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("#${item.number ?: "—"} · ${feedbackCategoryLabel(item.category)}", style = MaterialTheme.typography.titleSmall)
                                Text(item.body.replace('\n', ' ').trim(), maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                            }
                            Text(if (expanded) "⌃" else "⌄", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FeedbackStatusLabel(item.status)
                            Text(formatFeedbackTime(item.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (expanded) {
                            FishDivider()
                            Text("提交时间", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatFeedbackTime(item.createdAt), style = MaterialTheme.typography.bodySmall)
                            item.reply?.takeIf { it.isNotBlank() }?.let { reply ->
                                Text("开发者回复", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(reply, style = MaterialTheme.typography.bodyMedium)
                            }
                            item.latestDeveloperReply?.let { reply ->
                                Text("最新回复 · ${formatFeedbackTime(reply.createdAt)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(reply.body, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (supplements.isNotEmpty()) {
                                Text("补充与回复", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                supplements.asReversed().forEach { supplement ->
                                    Text(
                                        "${if (supplement.isDeveloperReply) "开发者" else "我"} · ${formatFeedbackTime(supplement.createdAt)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (supplement.isDeveloperReply) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(supplement.body, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (supplementNextOffset[id] != null) {
                                FishButton(
                                    onClick = { loadOlderSupplements(item) },
                                    enabled = id !in supplementLoading,
                                    modifier = Modifier.fillMaxWidth(),
                                    accent = false,
                                ) {
                                    Text(if (id in supplementLoading) "正在读取补充…" else "读取更早的补充")
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FishButton(
                                    onClick = {
                                        val actionId = item.id.toString()
                                        if (actionId !in upvoteUpdating) {
                                            upvoteUpdating = upvoteUpdating + actionId
                                            feedbackActionErrors = feedbackActionErrors - actionId
                                            scope.launch {
                                                val result = if (item.hasUpvoted == true) {
                                                    developerCenter.deleteFeedbackUpvote(actionId, machineId)
                                                } else {
                                                    developerCenter.postFeedbackUpvote(actionId, machineId)
                                                }
                                                result.onSuccess { vote ->
                                                    myFeedback = myFeedback.orEmpty().map { current ->
                                                        if (current.id == item.id) current.copy(upvoteCount = vote.upvoteCount, hasUpvoted = vote.hasUpvoted) else current
                                                    }
                                                }.onFailure { error ->
                                                    feedbackActionErrors = feedbackActionErrors + (actionId to (error.message ?: "支持状态更新失败"))
                                                }
                                                upvoteUpdating = upvoteUpdating - actionId
                                            }
                                        }
                                    },
                                    enabled = id !in upvoteUpdating,
                                    modifier = Modifier.weight(1f),
                                    accent = item.hasUpvoted == true,
                                ) {
                                    Text(when {
                                        id in upvoteUpdating -> "更新中…"
                                        item.hasUpvoted == true -> "已支持 ${item.upvoteCount ?: 0}"
                                        else -> "支持 ${item.upvoteCount ?: 0}"
                                    })
                                }
                            }
                            val canSupplement = item.status != "Completed" && item.status != "Deferred"
                            if (canSupplement) {
                                OutlinedTextField(
                                    value = supplementDrafts[id].orEmpty(),
                                    onValueChange = { value -> supplementDrafts = supplementDrafts + (id to value.take(4_000)) },
                                    label = { Text("补充说明") },
                                    supportingText = { Text("${supplementDrafts[id].orEmpty().length}/4000") },
                                    minLines = 2,
                                    maxLines = 5,
                                    enabled = id !in supplementPosting,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                FishButton(
                                    onClick = {
                                        val body = supplementDrafts[id].orEmpty().trim()
                                        if (body.isNotEmpty() && id !in supplementPosting) {
                                            supplementPosting = supplementPosting + id
                                            feedbackActionErrors = feedbackActionErrors - id
                                            scope.launch {
                                                developerCenter.postFeedbackSupplement(id, installationId, machineId, body)
                                                    .onSuccess { updated ->
                                                        myFeedback = myFeedback.orEmpty().map { current -> if (current.id == updated.id) updated else current }
                                                        supplementCache = supplementCache + (id to updated.supplements)
                                                        supplementNextOffset = supplementNextOffset + (id to updated.nextSupplementOffset)
                                                        supplementDrafts = supplementDrafts - id
                                                    }
                                                    .onFailure { error ->
                                                        feedbackActionErrors = feedbackActionErrors + (id to (error.message ?: "补充反馈失败"))
                                                    }
                                                supplementPosting = supplementPosting - id
                                            }
                                        }
                                    },
                                    enabled = supplementDrafts[id].orEmpty().isNotBlank() && id !in supplementPosting && feedbackBanStatus?.banned != true,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(if (id in supplementPosting) "正在提交补充…" else "提交补充") }
                            } else {
                                Hint("此反馈已结束，服务端不再接受补充。")
                            }
                            feedbackActionErrors[id]?.let { error ->
                                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun feedbackCategoryLabel(category: String): String = when (category) {
    "Bug" -> "问题反馈"
    "Suggestion" -> "功能建议"
    else -> category
}

@Composable
private fun FeedbackStatusLabel(status: String) {
    val label = when (status) {
        "Submitted" -> "已提交"
        "Processing" -> "处理中"
        "Completed" -> "已完成"
        "Deferred" -> "已延期"
        else -> status
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

private fun formatFeedbackTime(raw: String): String {
    return raw.replace('T', ' ').removeSuffix("Z").substringBefore('.')
}

@Composable
private fun AboutContent() {
    val context = LocalContext.current
    val packageInfo = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    }
    val versionName = packageInfo?.versionName ?: "未知版本"
    val versionCode = packageInfo?.let {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else it.versionCode.toLong()
    } ?: 0L
    val developerCenter = remember { DeveloperCenterClient() }
    var acknowledgements by remember { mutableStateOf<List<DeveloperCenterClient.Acknowledgement>?>(null) }
    var acknowledgementsLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(developerCenter) {
        developerCenter.getAcknowledgements()
            .onSuccess {
                acknowledgements = it.sortedWith(compareBy<DeveloperCenterClient.Acknowledgement> { item -> item.sortOrder }.thenBy { item -> item.name })
                acknowledgementsLoaded = true
            }
            .onFailure {
                acknowledgements = null
                acknowledgementsLoaded = true
            }
    }

    DetailCard {
        FishGlyph("app-icon", Modifier.size(64.dp))
        Text("大肥鱼 Go", style = MaterialTheme.typography.headlineMedium)
        Text("把想法，带到更远的地方。", style = MaterialTheme.typography.bodyLarge)
        TinyLabel("$versionName · versionCode $versionCode", primary = true)
        Hint("版本信息来自 Android PackageManager；当前材质：${GlassRendererDiagnostics.status}")
    }
    DetailCard {
        Text("为手机重新设计", style = MaterialTheme.typography.titleMedium)
        Hint("导航与浮动操作采用通透材料，设置列表与详情采用清晰的分组表面。点开设置项时从原圆角组展开，返回时收回原位置。")
        Hint("按钮使用弹性按压反馈，支持拖动的面板有独立握柄，松手回弹；真实运动与材质效果以当前 Android 设备呈现为准。")
        FishDivider()
        Text("接下来", style = MaterialTheme.typography.titleMedium)
        Hint("对话界面提供独立的手机工作空间；真实 DSH 接入将继续讨论。优先验证手机本地运行，电脑连接预留 Dafeiyu-GO-Aries。")
    }
    DetailCard {
        TinyLabel("公开致谢", primary = true)
        when {
            !acknowledgementsLoaded -> Hint("正在读取公开致谢…")
            acknowledgements.isNullOrEmpty() -> Hint("暂时没有可显示的公开致谢；网络不可用时保留本地许可证说明。")
            else -> acknowledgements.orEmpty().forEachIndexed { index, item ->
                if (index > 0) FishDivider()
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                    val role = listOfNotNull(item.category.takeIf { it.isNotBlank() }, item.role?.takeIf { it.isNotBlank() }).joinToString(" · ")
                    if (role.isNotBlank()) Hint(role)
                    item.contribution?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    item.link?.takeIf { it.isNotBlank() }?.let { Hint(it) }
                }
            }
        }
    }
    DetailCard {
        Text("许可证与第三方来源", style = MaterialTheme.typography.titleMedium)
        Hint("本应用自身与随应用分发的第三方依赖分别遵循各自许可证；Android、Jetpack Compose 与 Material 组件的许可信息以对应版本声明为准。")
        FishDivider()
        Text("第三方服务边界", style = MaterialTheme.typography.titleSmall)
        Hint("DeveloperCenter、Aries、DSH 及其公开服务只有在明确接入相应页面后才会使用；关于页面不会把服务状态伪装成本地能力。")
    }
    DetailCard {
        Text("隐私边界", style = MaterialTheme.typography.titleMedium)
        Hint("关于页面不会读取或上传 API Key、短信、联系人、位置或设备序列号。在线人数、反馈和设备配对只会在对应功能启用并完成服务接入后发送必要字段。")
        Hint("临时预览内容只保留在当前页面状态；关闭页面后释放，不写入工作区备份。")
    }
}


private fun readDeveloperCenterInstallationId(context: android.content.Context): String {
    val preferences = context.applicationContext.getSharedPreferences("developer_center_presence", android.content.Context.MODE_PRIVATE)
    val existing = preferences.getString("installation_id", null)
        ?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() }
    if (existing != null) return existing.toString()
    val generated = UUID.randomUUID()
    preferences.edit().putString("installation_id", generated.toString()).commit()
    return generated.toString()
}

private fun readDeveloperCenterMachineId(context: android.content.Context): String {
    val preferences = context.applicationContext.getSharedPreferences("developer_center_feedback", android.content.Context.MODE_PRIVATE)
    val existing = preferences.getString("machine_id", null)
        ?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() }
    if (existing != null) return existing.toString()
    val generated = UUID.randomUUID()
    preferences.edit().putString("machine_id", generated.toString()).commit()
    return generated.toString()
}

private fun readLauncherVersion(context: android.content.Context): String =
    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"

private fun formatMetricPercent(value: Double): String =
    String.format(Locale.US, "%.1f%%", value.coerceAtLeast(0.0))

private fun formatMetricBytes(value: Long?): String {
    if (value == null || value < 0L) return "不可用"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var amount = value.toDouble()
    var unit = 0
    while (amount >= 1024.0 && unit < units.lastIndex) {
        amount /= 1024.0
        unit++
    }
    return if (unit == 0) "$value B" else String.format(Locale.US, "%.1f %s", amount, units[unit])
}

private fun formatMetricRate(value: Double?): String {
    if (value == null || value < 0.0 || !value.isFinite()) return "不可用"
    return "${formatMetricBytes(value.toLong())}/s"
}

@Composable
private fun RuntimeContent(repository: DemoRepository) {
    LinuxComponentsContent(repository)
}

@Composable
private fun UsageContent(repository: DemoRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val usageResult by produceState<UsageStore.ReadResult?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) { UsageStore(context).read() }
    }
    val snapshot = usageResult?.snapshot
    val balanceStore = remember(context) { BalanceStore(context) }
    val balanceClient = remember(context) { DeepSeekBalanceClient(balanceStore) }
    val balanceHasKey = repository.deepSeekConfigured
    var apiKeyInput by remember { mutableStateOf("") }
    var balanceResult by remember { mutableStateOf<DeepSeekBalanceClient.BalanceResult?>(null) }
    var balanceError by remember { mutableStateOf<String?>(null) }
    var balanceBusy by remember { mutableStateOf(false) }
    val statusLabel = when (usageResult?.status) {
        UsageStore.Status.AVAILABLE -> "已读取本地 DSH 用量"
        UsageStore.Status.NO_DATA -> "暂无本地用量记录"
        UsageStore.Status.READ_FAILED -> "读取本地用量失败"
        UsageStore.Status.DEMO -> "演示状态"
        null -> "正在读取本地用量…"
    }
    DetailCard {
        TinyLabel("账户余额", primary = balanceResult != null)
        Text("DeepSeek 余额", style = MaterialTheme.typography.titleMedium)
        Hint("余额查询只在你主动点击后进行；API Key 使用 Android Keystore 加密保存，不写入普通设置或备份。")
        if (!balanceHasKey) {
            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { apiKeyInput = it.take(512); balanceError = null },
                label = { Text("DeepSeek API Key") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            FishButton(
                onClick = {
                    repository.saveDeepSeekKey(apiKeyInput)
                    apiKeyInput = ""
                },
                enabled = apiKeyInput.isNotBlank() && !balanceBusy && !repository.deepSeekConfigurationBusy && !repository.isResponding,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("安全保存 Key") }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FishButton(
                    onClick = {
                        scope.launch {
                            balanceBusy = true
                            balanceError = null
                            balanceClient.fetchBalance().onSuccess { balanceResult = it }.onFailure { balanceError = it.message ?: "余额查询失败" }
                            balanceBusy = false
                        }
                    },
                    enabled = !balanceBusy && !repository.deepSeekConfigurationBusy,
                    modifier = Modifier.weight(1f),
                ) { Text(if (balanceBusy) "查询中…" else "查询余额") }
                FishButton(
                    onClick = {
                        repository.clearDeepSeekKey()
                        balanceResult = null
                        apiKeyInput = ""
                        balanceError = null
                    },
                    enabled = !balanceBusy && !repository.deepSeekConfigurationBusy,
                    accent = false,
                    modifier = Modifier.weight(1f),
                ) { Text("清除 Key") }
            }
            balanceResult?.selected?.let { selected -> OnlineValueRow("当前余额", "${selected.totalBalance.toPlainString()} ${selected.currency}") }
                ?: Hint("已安全保存 Key，尚未查询余额。")
        }
        balanceError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        repository.deepSeekModelError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    DetailCard {
        TinyLabel(statusLabel, primary = usageResult?.status == UsageStore.Status.AVAILABLE)
        Text("本地用量", style = MaterialTheme.typography.titleMedium)
        if (snapshot == null) {
            Text(statusLabel, style = MaterialTheme.typography.headlineSmall)
            Hint(usageResult?.errorMessage ?: "应用私有目录中没有可用的 usage.jsonl；不会用示例数字代替真实记录。")
        } else {
            Text("近 7 天", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.weight(1f)) { Text(formatTokenCount(snapshot.weekTokens), style = MaterialTheme.typography.headlineSmall); Hint("Token") }
                Column(Modifier.weight(1f)) { Text(snapshot.todayRequests.toString(), style = MaterialTheme.typography.headlineSmall); Hint("今日请求") }
            }
            FishDivider()
            OnlineValueRow("近 30 天输入", formatTokenCount(snapshot.monthInputTokens))
            OnlineValueRow("近 30 天输出", formatTokenCount(snapshot.monthOutputTokens))
            OnlineValueRow("缓存读取", formatTokenCount(snapshot.monthCacheReadTokens))
            OnlineValueRow("记录数", "${snapshot.records}（无效 ${snapshot.invalidRecords}）")
            snapshot.topModel?.let { OnlineValueRow("使用最多的模型", "$it · ${formatTokenCount(snapshot.topModelTokens)}") }
            snapshot.lastCallAtEpochMillis?.let { OnlineValueRow("最近调用", formatInstalledTime(it)) }
            val values = snapshot.daily.takeLast(7).map { it.tokens.toFloat() }
            if (values.any { it > 0f }) {
                val maxValue = values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
                val usageAccent = MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                    val step = size.width / values.size.coerceAtLeast(1)
                    values.forEachIndexed { index, value ->
                        val barWidth = step * .46f
                        val barHeight = (size.height - 8.dp.toPx()) * (value / maxValue)
                        drawRoundRect(
                            usageAccent.copy(alpha = if (index == values.lastIndex) 1f else .38f),
                            Offset(index * step + (step - barWidth) / 2f, size.height - barHeight),
                            Size(barWidth, barHeight), CornerRadius(4.dp.toPx()),
                        )
                    }
                }
            }
        }
    }
    Hint("用量只读取应用私有 DSH 统计文件；余额、价格和费用不会从 Token 数量推算。")
}

private fun formatTokenCount(value: Long): String = when {
    value >= 1_000_000L -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000L -> String.format(Locale.US, "%.1fk", value / 1_000.0)
    else -> value.toString()
}

@Composable
private fun NoticeContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reducedMotion = LocalReduceGlassMotion.current
    val duration = if (reducedMotion) 0 else GlassMotion.MorphMillis
    val feed = rememberNoticeFeedState()
    var locallyDismissed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val notices = feed.result.items
        .sortedWith(compareByDescending<NoticeStore.Notice> { it.kind == NoticeStore.Kind.ANNOUNCEMENT }
            .thenByDescending { it.publishedAtEpochMillis })
        .distinctBy { it.id }
        .filter { it.id !in locallyDismissed }
    val firstNotice = notices.firstOrNull()
    val installationId = remember(context) { readDeveloperCenterInstallationId(context) }
    val developerCenter = remember { DeveloperCenterClient() }

    LaunchedEffect(firstNotice?.id, feed.remoteLoaded) {
        val item = firstNotice ?: return@LaunchedEffect
        if (!feed.remoteLoaded) return@LaunchedEffect
        developerCenter.postMessageMetric(item.id, installationId, DeveloperCenterClient.MessageMetricKind.DISPLAYED)
    }

    DetailCard(Modifier.animateContentSize(tween(duration, easing = GlassMotion.EaseOut))) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                TinyLabel("全部公告与通知", primary = notices.isNotEmpty())
                Text(
                    when {
                        feed.refreshing -> "正在刷新公告…"
                        notices.isNotEmpty() -> "共 ${notices.size} 条已发布内容"
                        feed.refreshError != null || feed.result.status == NoticeStore.Status.READ_FAILED -> "公告读取失败"
                        else -> "暂无公告"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            FishButton(onClick = feed.onRefresh, enabled = !feed.refreshing, accent = false, consumeDrag = false) {
                Text(if (feed.refreshing) "刷新中…" else "刷新")
            }
        }
        if (feed.refreshError != null) {
            Text(
                "公告更新失败：${feed.refreshError}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        when {
            notices.isNotEmpty() -> Hint("点击公告标题可展开或收起完整内容。")
            feed.refreshing -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            feed.result.status == NoticeStore.Status.READ_FAILED -> {
                Hint(feed.result.errorMessage ?: "公告缓存格式或路径无效。")
            }
            feed.refreshError == null -> {
                Text("暂无已发布公告", style = MaterialTheme.typography.titleLarge)
                Hint("当前没有有效的已发布内容。")
            }
            else -> Hint("检查网络连接后，点击刷新重试。")
        }
    }

    AnimatedContent(
        targetState = notices,
        transitionSpec = {
            (fadeIn(GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion)) togetherWith
                fadeOut(GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion)))
                .using(SizeTransform(clip = false) { _, _ -> tween(duration, easing = GlassMotion.EaseOut) })
        },
        contentAlignment = Alignment.TopStart,
        label = "notice-message-list",
    ) { shownNotices ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            shownNotices.forEachIndexed { index, notice ->
                key(notice.id) {
                    NoticeMessageCard(notice, initiallyExpanded = index == 0) { buttonIndex, button ->
                        val actionError = when (button.action) {
                            NoticeStore.Action.URL -> runCatching {
                                val uri = Uri.parse(button.target)
                                require(uri.scheme.equals("https", true))
                                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                            }.exceptionOrNull()?.let { "无法打开此链接：${it.message ?: "没有可用的浏览器"}" }
                            NoticeStore.Action.DISMISS -> {
                                locallyDismissed = (locallyDismissed + notice.id).distinct()
                                null
                            }
                            NoticeStore.Action.SETTINGS, NoticeStore.Action.POWERSHELL -> "此动作暂不支持。"
                        }
                        scope.launch {
                            developerCenter.postMessageMetric(notice.id, installationId, DeveloperCenterClient.MessageMetricKind.CLICK, buttonIndex)
                        }
                        actionError
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeMessageCard(
    notice: NoticeStore.Notice,
    initiallyExpanded: Boolean,
    onAction: (Int, NoticeStore.Button) -> String?,
) {
    val reducedMotion = LocalReduceGlassMotion.current
    val haptics = LocalGlassHaptics.current
    val interaction = remember { MutableInteractionSource() }
    val duration = if (reducedMotion) 0 else GlassMotion.MorphMillis
    var expanded by rememberSaveable(notice.id) { mutableStateOf(initiallyExpanded) }
    var actionError by remember(notice.id) { mutableStateOf<String?>(null) }
    val chevronRotation by animateFloatAsState(
        if (expanded) 90f else 0f,
        GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion),
        label = "notice-disclosure",
    )
    DetailCard(Modifier.animateContentSize(tween(duration, easing = GlassMotion.EaseOut))) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = {
                    haptics.confirm()
                    expanded = !expanded
                }).heightIn(min = 56.dp).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TinyLabel(if (notice.kind == NoticeStore.Kind.ANNOUNCEMENT) "公告" else "通知", primary = true)
                Text(notice.title, style = MaterialTheme.typography.titleMedium)
                Hint(notice.date ?: DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(notice.publishedAtEpochMillis)))
            }
            FishGlyph("chevron", Modifier.size(18.dp).graphicsLayer { rotationZ = chevronRotation }, MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion)) +
                expandVertically(tween(duration, easing = GlassMotion.EaseOut)),
            exit = fadeOut(GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion)) +
                shrinkVertically(tween(duration, easing = GlassMotion.EaseOut)),
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FishDivider()
                if (notice.markdown.isBlank()) {
                    Hint("此公告未附正文。")
                } else {
                    SelectionContainer {
                        ChatMarkdown(notice.markdown, streaming = false, contentKey = "notice-${notice.id}")
                    }
                }
                notice.buttons.forEachIndexed { index, button ->
                    val supported = button.action == NoticeStore.Action.URL || button.action == NoticeStore.Action.DISMISS
                    FishButton(
                        onClick = { actionError = onAction(index, button) },
                        modifier = Modifier.fillMaxWidth(),
                        accent = index == 0,
                        enabled = supported,
                        consumeDrag = false,
                    ) { Text(button.label + if (supported) "" else "（预留）") }
                    if (button.action == NoticeStore.Action.POWERSHELL) Hint("此动作仅支持 Windows。")
                    if (button.action == NoticeStore.Action.SETTINGS) Hint("此设置动作尚未接入。")
                }
                actionError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Hint("来源：DeveloperCenter 已发布消息")
            }
        }
    }
}

private fun themeLabel(mode: String) = when (mode) { "light" -> "浅色"; "dark" -> "深色"; else -> "跟随系统" }





