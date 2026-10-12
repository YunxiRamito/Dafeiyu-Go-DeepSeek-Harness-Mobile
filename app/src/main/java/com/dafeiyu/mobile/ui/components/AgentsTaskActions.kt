package com.dafeiyu.mobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dafeiyu.mobile.data.BackgroundTask
import com.dafeiyu.mobile.data.BackgroundTaskEngine
import com.dafeiyu.mobile.data.BackgroundTaskKind
import com.dafeiyu.mobile.data.BackgroundTaskStatus
import com.dafeiyu.mobile.ui.AnchoredGlassMenu
import com.dafeiyu.mobile.ui.GlassMenuPlacement
import com.dafeiyu.mobile.ui.rememberModelMenuMorphState

@Composable
fun AgentsTaskActions(
    engine: BackgroundTaskEngine?,
    onOpenTasks: (String?) -> Unit,
    onOpening: () -> Unit = {},
    dismissRequested: Boolean = false,
    onOverlayProgress: (Float) -> Unit = {},
    onSurfaceVisibilityChanged: (Boolean) -> Unit = {},
    deformation: GlassButtonDeformationState = rememberGlassButtonDeformationState(),
    drawLauncherMaterial: Boolean = true,
    onLauncherAlphaChanged: (Float) -> Unit = {},
) {
    val tasks = if (engine == null) emptyList() else engine.tasks.collectAsStateWithLifecycle().value
    val storageIssue = engine?.storageIssue?.collectAsStateWithLifecycle()?.value
    var expanded by remember { mutableStateOf(false) }
    var pendingNavigation by remember { mutableStateOf(false) }
    var selectedTaskId by remember { mutableStateOf<String?>(null) }
    val state = rememberModelMenuMorphState(expanded)
    val latestLauncherAlphaChanged by rememberUpdatedState(onLauncherAlphaChanged)
    DisposableEffect(state) {
        latestLauncherAlphaChanged(state.launcherAlpha)
        // Clear a retained menu fade when this launcher leaves composition.
        onDispose { latestLauncherAlphaChanged(1f) }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.launcherAlpha }.collect { latestLauncherAlphaChanged(it) }
    }
    val palette = MaterialTheme.colorScheme
    // Hold final geometry while task updates arrive, including completion and deletion.
    val menuHeight = remember(expanded) { (300 + tasks.size.coerceAtMost(4) * 52).dp }
    LaunchedEffect(dismissRequested) { if (dismissRequested) expanded = false }
    fun choose(id: String?) {
        selectedTaskId = id
        pendingNavigation = true
        expanded = false
    }
    @Composable
    fun GroupIcon() {
        FishGlyph("groupchat", Modifier.size(23.dp))
    }
    Box {
        GlassSurface(buttonDeformation = deformation, drawMaterial = drawLauncherMaterial,
            modifier = Modifier.size(48.dp)
                .applyButtonDeformation(state = deformation, circularHit = true, drawShadow = drawLauncherMaterial)
                .onGloballyPositioned { state.anchorBounds = it.boundsInWindow() }
                .graphicsLayer { alpha = state.launcherAlpha },
            cornerRadius = 24.dp, tint = palette.surface.copy(alpha = .18f)) {
            IconButton(onClick = {
                if (expanded) expanded = false
                else if (!dismissRequested) {
                    onOpening()
                    expanded = true
                }
            }, modifier = Modifier.fillMaxSize().semantics {
                contentDescription = "子 Agents 与后台任务"
            }) { GroupIcon() }
        }
        AnchoredGlassMenu(
            expanded, state, onDismiss = { expanded = false },
            menuWidth = 336.dp, menuHeight = menuHeight,
            reduceTransparency = LocalReduceGlassTransparency.current,
            placement = GlassMenuPlacement.BelowEnd,
            onOverlayProgress = onOverlayProgress,
            onSurfaceVisibilityChanged = onSurfaceVisibilityChanged,
            onClosed = {
                if (pendingNavigation) {
                    pendingNavigation = false
                    onOpenTasks(selectedTaskId)
                }
            },
            anchorContent = {
                CompositionLocalProvider(LocalGlassMaterialProfile provides GlassMaterialProfile.Button) {
                    GlassSurface(
                        backdrop = LocalFloatingGlassBackdrop.current ?: LocalGlassBackdrop.current,
                        modifier = Modifier.fillMaxSize(), cornerRadius = 24.dp,
                        tint = palette.surface.copy(alpha = .18f)) {
                        IconButton(onClick = { expanded = false },
                            modifier = Modifier.fillMaxSize().semantics {
                                contentDescription = "关闭子 Agents 与后台任务"
                            }) { GroupIcon() }
                    }
                }
            }, sourceContent = {},
        ) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                if (storageIssue != null) Text(
                    storageIssue, color = palette.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
                LazyColumn(Modifier.weight(1f)) {
                    for (kind in listOf(BackgroundTaskKind.SUBAGENT, BackgroundTaskKind.BACKGROUND)) {
                        item(key = "heading-$kind") {
                            Text(if (kind == BackgroundTaskKind.SUBAGENT) "子 Agents" else "后台任务",
                                style = MaterialTheme.typography.titleSmall,
                                color = palette.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp))
                        }
                        val sectionTasks = tasks.filter { it.kind == kind }
                            .sortedByDescending { it.createdAtMillis }
                        if (sectionTasks.isEmpty()) item(key = "empty-$kind") {
                            Text(if (kind == BackgroundTaskKind.SUBAGENT) "暂无子 Agent" else "暂无后台任务",
                                style = MaterialTheme.typography.bodyMedium, color = palette.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp))
                        }
                        items(sectionTasks, key = { it.id }) { task ->
                            TaskSummaryRow(task) { choose(task.id) }
                        }
                        if (kind == BackgroundTaskKind.SUBAGENT) item(key = "divider") {
                            HorizontalDivider(Modifier.padding(vertical = 6.dp),
                                color = palette.outlineVariant.copy(alpha = .5f))
                        }
                    }
                }
                HorizontalDivider(color = palette.outlineVariant.copy(alpha = .5f))
                Row(Modifier.fillMaxWidth().applyButtonDeformation(consumeDrag = false)
                    .clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { choose(null) }
                    .heightIn(min = 48.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("查看全部任务", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    FishGlyph("chevron", Modifier.size(18.dp), palette.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TaskSummaryRow(task: BackgroundTask, onClick: () -> Unit) {
    val palette = MaterialTheme.colorScheme
    val status = when (task.status) {
        BackgroundTaskStatus.DRAFT -> "草稿"
        BackgroundTaskStatus.QUEUED -> "等待中"
        BackgroundTaskStatus.RUNNING -> "运行中"
        BackgroundTaskStatus.STOPPING -> "停止中"
        BackgroundTaskStatus.SUCCEEDED -> "已完成"
        BackgroundTaskStatus.FAILED -> "失败"
        BackgroundTaskStatus.STOPPED -> "已停止"
        BackgroundTaskStatus.INTERRUPTED -> "已中断"
    }
    Row(Modifier.fillMaxWidth().applyButtonDeformation(consumeDrag = false)
        .clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick)
        .heightIn(min = 52.dp).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FishGlyph(if (task.kind == BackgroundTaskKind.SUBAGENT) "groupchat" else "terminal",
            Modifier.size(20.dp), palette.onSurfaceVariant)
        Text(task.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium)
        Text(status, style = MaterialTheme.typography.labelMedium,
            color = if (task.status == BackgroundTaskStatus.FAILED) palette.error else palette.onSurfaceVariant)
    }
}
