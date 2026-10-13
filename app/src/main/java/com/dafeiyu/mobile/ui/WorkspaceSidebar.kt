package com.dafeiyu.mobile.ui

import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.dafeiyu.mobile.data.DeveloperCenterPresenceClient
import com.dafeiyu.mobile.data.DemoRepository
import com.dafeiyu.mobile.data.RemoteDshApplicationController
import com.dafeiyu.mobile.data.RemoteDshScope
import com.dafeiyu.mobile.ui.components.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.roundToInt

/** The sidebar panel keeps its glass; row bodies use their own solid theme source for readable text. */
@Composable
fun WorkspaceSidebar(
    repository: DemoRepository,
    onClose: () -> Unit,
    onSettings: () -> Unit,
    onOnlineMetrics: () -> Unit = {},
    onConversationChanged: () -> Unit,
    fusionBackdrop: PageBackdropCapture? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val palette = MaterialTheme.colorScheme
    val taskEngine = repository.backgroundTasks
    val deletionTasks = taskEngine?.tasks?.collectAsStateWithLifecycle()?.value
    val deletionStorageIssue = taskEngine?.storageIssue?.collectAsStateWithLifecycle()?.value
    // Repository checks read StateFlow.value; subscribe here so protection changes invalidate rows.
    val deletableWorkspaceIds = remember(
        deletionTasks, deletionStorageIssue, repository,
    ) {
        derivedStateOf {
            repository.displayWorkspaces.filter { repository.canRemoveWorkspace(it.id) }.mapTo(HashSet()) { it.id }
        }
    }.value
    val presenceClient = remember { DeveloperCenterPresenceClient() }
    var presenceSnapshot by remember {
        mutableStateOf(DeveloperCenterPresenceClient.Snapshot(DeveloperCenterPresenceClient.ServiceState.PREVIEW))
    }
    var presenceLoading by remember { mutableStateOf(true) }
    var showNewWorkspace by remember { mutableStateOf(false) }
    var showRemoteDirectory by remember { mutableStateOf(false) }
    var showRemoteArtifacts by remember { mutableStateOf(false) }
    var workspaceName by remember { mutableStateOf("") }
    var pendingWorkspaceUri by remember { mutableStateOf<Uri?>(null) }
    var revealedRow by remember { mutableStateOf<SidebarSwipeIdentity?>(null) }
    var workspaceToDeleteId by remember { mutableStateOf<String?>(null) }
    var confirmWorkspaceFiles by remember { mutableStateOf(false) }
    var preparingWorkspaceRemoval by remember { mutableStateOf(false) }
    var workspacePreparationError by remember { mutableStateOf<String?>(null) }
    var workspacePreparationAttempt by remember { mutableIntStateOf(0) }
    var conversationToArchiveId by remember { mutableStateOf<String?>(null) }
    var conversationArchiveScope by remember { mutableStateOf<RemoteDshScope?>(null) }
    val remoteScope = repository.remoteWorkspaceScope
    LaunchedEffect(repository.displayWorkspaceId) { revealedRow = null }
    LaunchedEffect(repository.connectionMode, remoteScope) {
        workspaceToDeleteId = null
        conversationToArchiveId = null
        conversationArchiveScope = null
        confirmWorkspaceFiles = false
        repository.clearWorkspaceRemovalConfirmation()
    }
    LaunchedEffect(workspaceToDeleteId, workspacePreparationAttempt) {
        workspacePreparationError = null
        val id = workspaceToDeleteId
        if (id == null) {
            preparingWorkspaceRemoval = false
            repository.clearWorkspaceRemovalConfirmation()
            return@LaunchedEffect
        }
        preparingWorkspaceRemoval = true
        try { repository.prepareWorkspaceRemovalDetails(id) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { workspacePreparationError = RemoteDshApplicationController.remoteFailure(failure) }
        finally { preparingWorkspaceRemoval = false }
    }
    LaunchedEffect(presenceClient, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                presenceSnapshot = presenceClient.probe()
                presenceLoading = false
                delay(5_000)
            }
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) pendingWorkspaceUri = uri
    }
    val performSwipeAction: (SidebarSwipeIdentity) -> Boolean = { identity ->
        val accepted = identity.perform(
            archiveConversation = { id ->
                if (repository.connectionMode == "online" && repository.remoteConversationArchiveRequiresStop(id)) {
                    conversationToArchiveId = id
                    conversationArchiveScope = remoteScope
                    true
                } else repository.archiveConversation(id)
            },
            canDeleteWorkspace = repository::canRemoveWorkspace,
            requestWorkspaceDeletion = { id ->
                workspaceToDeleteId = id
                confirmWorkspaceFiles = false
                true
            },
        )
        if (accepted) {
            if (revealedRow == identity) revealedRow = null
            if (identity.action == SidebarSwipeAction.Archive && conversationToArchiveId == null) onConversationChanged()
        }
        accepted
    }
    val visibleConversations = repository.displayConversations.filter { conversation ->
        conversation.workspaceId == repository.displayWorkspaceId &&
            repository.isConversationVisibleInWorkspace(conversation)
    }
    Column(Modifier.fillMaxHeight().widthIn(max = 296.dp).padding(horizontal = 14.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "工作目录", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                fontSize = 20.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            SidebarIconAction("添加工作目录", "plus", enabled = repository.workspaceInitialized) {
                if (repository.connectionMode == "online") showRemoteDirectory = true else folderPicker.launch(null)
            }
            SidebarIconAction("关闭侧栏", "close", onClick = onClose)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (!repository.workspaceInitialized) {
                item("loading") {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("读取中", style = MaterialTheme.typography.bodyMedium, color = palette.onSurfaceVariant)
                    }
                }
            }
            items(repository.displayWorkspaces, key = { "workspace-${it.id}" }) { workspace ->
                val identity = SidebarSwipeIdentity(workspace.id, SidebarSwipeAction.DeleteEmptyWorkspace)
                SidebarSwipeRow(
                    identity = identity,
                    title = workspace.name,
                    glyph = if (workspace.uri == null) "menu" else "folder",
                    selected = workspace.id == repository.displayWorkspaceId,
                    actionEnabled = workspace.id in deletableWorkspaceIds,
                    revealedRow = revealedRow,
                    onRevealChanged = { row, revealed ->
                        if (revealed) revealedRow = row
                        else if (revealedRow == row) revealedRow = null
                    },
                    onAction = performSwipeAction,
                    onSelect = {
                        revealedRow = null
                        repository.selectWorkspace(workspace.id)
                        onConversationChanged()
                    },
                )
            }
            item("create-internal-workspace") {
                TextButton(
                    onClick = {
                        if (repository.connectionMode == "online") showRemoteDirectory = true
                        else { workspaceName = ""; showNewWorkspace = true }
                    },
                    enabled = repository.workspaceInitialized,
                    modifier = Modifier.fillMaxWidth().applyButtonDeformation(
                        enabled = repository.workspaceInitialized, consumeDrag = false, drawShadow = false,
                    ),
                ) {
                    FishGlyph("plus", Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (repository.connectionMode == "online") "选择电脑目录" else "新建应用内目录", style = MaterialTheme.typography.labelMedium)
                }
            }
            item("conversations-heading") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp, start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "对话", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                        color = palette.onSurfaceVariant,
                    )
                    SidebarIconAction("新建对话", "edit", enabled = repository.workspaceInitialized) {
                        repository.newConversation()
                        onConversationChanged()
                        onClose()
                    }
                }
            }
            if (repository.workspaceInitialized && visibleConversations.isEmpty()) {
                item("conversations-empty") {
                    Text(
                        "暂无对话", Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium, color = palette.onSurfaceVariant,
                    )
                }
            }
            items(visibleConversations, key = { "conversation-${it.id}" }) { conversation ->
                val identity = SidebarSwipeIdentity(conversation.id, SidebarSwipeAction.Archive)
                SidebarSwipeRow(
                    identity = identity,
                    title = conversation.title,
                    glyph = "chat",
                    selected = conversation.id == repository.displayConversationId,
                    actionEnabled = repository.canArchiveConversation(conversation.id),
                    revealedRow = revealedRow,
                    onRevealChanged = { row, revealed ->
                        if (revealed) revealedRow = row
                        else if (revealedRow == row) revealedRow = null
                    },
                    onAction = performSwipeAction,
                    onSelect = {
                        repository.selectConversation(conversation.id)
                        onConversationChanged()
                        // Choosing a conversation is the one sidebar action whose result lives in the main pane.
                        onClose()
                    },
                )
            }
        }
        DeveloperCenterPresenceStatusBar(presenceSnapshot, presenceLoading, onOnlineMetrics)
        if (repository.connectionMode == "online") SidebarItem("电脑工作区文件", "document", false) { showRemoteArtifacts = true }
        SidebarItem("设置", "settings", false) { onSettings() }
        Spacer(Modifier.height(12.dp))
    }
    pendingWorkspaceUri?.let { uri ->
        val directoryName = Uri.decode(uri.lastPathSegment.orEmpty()).substringAfterLast(':').substringAfterLast('/')
        AlertDialog(
            onDismissRequest = { pendingWorkspaceUri = null },
            title = { Text("加入此工作目录？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(directoryName.ifBlank { "所选目录" }, fontWeight = FontWeight.Medium)
                    Text(uri.toString(), style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = repository.workspaceInitialized,
                    onClick = {
                        repository.addExternalWorkspace(context, uri)
                        pendingWorkspaceUri = null
                        revealedRow = null
                        onConversationChanged()
                    },
                ) { Text("加入工作区") }
            },
            dismissButton = { TextButton(onClick = { pendingWorkspaceUri = null }) { Text("取消") } },
        )
    }
    if (showNewWorkspace) {
        AlertDialog(
            onDismissRequest = { showNewWorkspace = false },
            title = { Text("新建工作目录") },
            text = {
                TextField(
                    value = workspaceName,
                    onValueChange = { workspaceName = it.take(80) },
                    label = { Text("目录名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        repository.createWorkspace(workspaceName)
                        showNewWorkspace = false
                        onConversationChanged()
                    },
                    enabled = workspaceName.trim().isNotEmpty() && repository.workspaceInitialized,
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showNewWorkspace = false }) { Text("取消") }
            },
        )
    }

    if (showRemoteDirectory) RemoteDirectoryPicker(repository.remoteDsh,
        onDismiss = { showRemoteDirectory = false }, notify = repository::updateNotice)
    if (showRemoteArtifacts) RemoteArtifactsContent(repository, onDismiss = { showRemoteArtifacts = false })
    val conversationToArchive = repository.displayConversations.firstOrNull { it.id == conversationToArchiveId }
    if (conversationToArchive != null && conversationArchiveScope != null) {
        AlertDialog(
            onDismissRequest = { conversationToArchiveId = null; conversationArchiveScope = null },
            title = { Text("停止任务并归档电脑会话？") },
            text = { Text("“${conversationToArchive.title}”仍有任务运行。电脑确认停止任务后才会归档，会话内容保留。") },
            confirmButton = {
                TextButton(enabled = repository.canArchiveConversation(conversationToArchive.id) &&
                    conversationArchiveScope == remoteScope, onClick = {
                    if (repository.requestArchiveRemoteConversation(conversationToArchive.id, stopRunningTasks = true,
                            expectedScope = conversationArchiveScope)) {
                        conversationToArchiveId = null
                        conversationArchiveScope = null
                        onConversationChanged()
                    }
                }) { Text("停止任务并归档") }
            },
            dismissButton = { TextButton(onClick = {
                conversationToArchiveId = null; conversationArchiveScope = null
            }) { Text("取消") } },
        )
    }
    val workspaceToDelete = repository.displayWorkspaces.firstOrNull { it.id == workspaceToDeleteId }
    if (workspaceToDelete != null) {
        val details = repository.workspaceRemovalDetails(workspaceToDelete.id)
        val canRemove = repository.canRemoveWorkspace(workspaceToDelete.id) && details != null &&
            !preparingWorkspaceRemoval && workspacePreparationError == null
        val computerFiles = repository.connectionMode == "online"
        val runningTasks = details?.hasRunningTasks == true
        val dismissRemoval = { workspaceToDeleteId = null; confirmWorkspaceFiles = false }
        AlertDialog(
            onDismissRequest = dismissRemoval,
            title = { Text(if (confirmWorkspaceFiles) "删除此工作区的文件？" else "移除此工作区？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(workspaceToDelete.name, fontWeight = FontWeight.Medium)
                    Text(if (computerFiles) "电脑工作区中的对话将归档，可在归档页还原。"
                        else "工作区中的对话将归档，保留 30 天，可在归档页还原。")
                    if (runningTasks) Text("此工作区仍有任务运行。继续将先停止任务，确认停止后再移除。")
                    if (details != null) {
                        Text("执行设备：${details.deviceLabel}", style = MaterialTheme.typography.bodySmall,
                            color = palette.onSurfaceVariant)
                        details.directoryPaths.forEach { path ->
                            Text(path, style = MaterialTheme.typography.bodySmall,
                                color = palette.onSurfaceVariant)
                        }
                        details.externalUri?.let { uri ->
                            Text("外部目录保留：$uri", style = MaterialTheme.typography.bodySmall,
                                color = palette.onSurfaceVariant)
                        }
                    }
                    if (confirmWorkspaceFiles) {
                        Text(if (computerFiles) "以上电脑路径中的目录及文件将永久删除，无法撤销。归档消息仍保留，已删除的文件不会随对话还原。"
                            else "以上应用内目录及文件将永久删除，无法撤销。归档消息仍会保留，已删除的文件不会随对话还原。",
                            color = palette.error)
                    } else {
                        Text("磁盘文件默认保留。")
                        TextButton(
                            onClick = { confirmWorkspaceFiles = true },
                            enabled = canRemove && details?.canDeleteOwnedFiles == true,
                        ) { Text(if (computerFiles) "同时删除电脑文件…" else "同时删除应用内文件…", color = palette.error) }
                    }
                    if (preparingWorkspaceRemoval) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("正在核对任务和目录状态…", style = MaterialTheme.typography.bodySmall)
                        }
                    } else if (workspacePreparationError != null) {
                        Text(requireNotNull(workspacePreparationError), color = palette.error)
                        TextButton(onClick = { workspacePreparationAttempt++ },
                            enabled = repository.canRemoveWorkspace(workspaceToDelete.id)) { Text("重新读取") }
                    } else if (!canRemove) Text("工作区状态暂不可确认，请稍后重试。", color = palette.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = canRemove && (!confirmWorkspaceFiles || details?.canDeleteOwnedFiles == true),
                    onClick = {
                        if (repository.requestRemoveWorkspace(
                                workspaceToDelete.id,
                                deleteFiles = confirmWorkspaceFiles,
                                stopRunningTasks = runningTasks,
                            )) {
                            onConversationChanged()
                            dismissRemoval()
                        }
                    },
                ) {
                    Text(if (confirmWorkspaceFiles) {
                        if (runningTasks) "停止任务并删除文件" else "移除并删除文件"
                    } else if (runningTasks) "停止任务并移除" else "保留文件并移除", color = palette.error)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (confirmWorkspaceFiles) confirmWorkspaceFiles = false else dismissRemoval()
                }) { Text(if (confirmWorkspaceFiles) "返回" else "取消") }
            },
        )
    }

}

internal enum class SidebarSwipe { Closed, Revealed, Expanded }

internal enum class SidebarSwipeAction { Archive, DeleteEmptyWorkspace }

/** A gesture always belongs to this exact row and action, even if selection changes later. */
internal data class SidebarSwipeIdentity(val id: String, val action: SidebarSwipeAction) {
    fun perform(
        archiveConversation: (String) -> Boolean,
        canDeleteWorkspace: (String) -> Boolean,
        requestWorkspaceDeletion: (String) -> Boolean,
    ): Boolean = when (action) {
        SidebarSwipeAction.Archive -> archiveConversation(id)
        SidebarSwipeAction.DeleteEmptyWorkspace -> canDeleteWorkspace(id) && requestWorkspaceDeletion(id)
    }
}

/** Logical drag anchors and visual resistance share a mirrored, bounded coordinate system. */
internal class SidebarSwipeGeometry(rowWidthPx: Float, revealWidthPx: Float, rtl: Boolean) {
    val width = rowWidthPx.coerceAtLeast(2f)
    val revealDistance = revealWidthPx.coerceIn(1f, width - 1f)
    private val sign = if (rtl) 1f else -1f
    val revealOffset = sign * revealDistance
    val expandedOffset = sign * width
    val commitDistance = revealDistance + (width - revealDistance) * .72f

    fun distance(offset: Float): Float = if (offset.isFinite())
        (offset * sign).coerceIn(0f, width) else 0f

    fun thresholdReached(offset: Float): Boolean = distance(offset) >= commitDistance

    fun extension(offset: Float): Float {
        val progress = ((distance(offset) - revealDistance) / (commitDistance - revealDistance)).coerceIn(0f, 1f)
        // A restrained start catches up smoothly; the complete capsule is reached at the UP threshold.
        return progress / (1.85f - .85f * progress)
    }

    fun visualOffset(offset: Float): Float {
        val distance = distance(offset)
        return sign * if (distance <= revealDistance) distance
        else revealDistance + (width - revealDistance) * extension(offset)
    }

    fun actionWidth(offset: Float, collapsedWidthPx: Float): Float =
        collapsedWidthPx.coerceAtMost(width) +
            (width - collapsedWidthPx.coerceAtMost(width)) * extension(offset)
}

/** Permission is captured on DOWN, never granted by settling midway through the same gesture. */
internal class SidebarSwipeGate {
    var startedAtReveal = false
        private set
    private var eligible = false
    private var released = false
    private var consumed = false
    private var dragStopped = false
    private var activeDrag: Any? = null
    private var activeDragGeneration = 0
    var generation = 0
        private set
    var cancelled = false
        private set

    fun begin(
        stage: SidebarSwipe, animating: Boolean, revealed: Boolean,
        atReveal: Boolean = true, actionEnabled: Boolean = true,
    ): Boolean {
        generation++
        startedAtReveal = stage == SidebarSwipe.Revealed && !animating && revealed && atReveal && actionEnabled
        eligible = startedAtReveal
        released = false
        consumed = false
        dragStopped = false
        cancelled = false
        return eligible
    }

    fun release(gestureGeneration: Int, thresholdReached: Boolean) {
        if (gestureGeneration == generation) released = eligible && thresholdReached
    }
    fun startDrag(token: Any) {
        activeDrag = token
        activeDragGeneration = generation
    }
    fun stopDrag(token: Any): Int? {
        if (token !== activeDrag) return null
        activeDrag = null
        if (activeDragGeneration != generation) return null
        dragStopped = true
        return generation
    }
    fun cancelDrag(token: Any): Int? {
        if (token !== activeDrag) return null
        activeDrag = null
        if (activeDragGeneration != generation) return null
        cancel()
        return generation
    }
    fun cancel() { eligible = false; released = false; dragStopped = false; cancelled = true }
    fun consumeCommit(revealed: Boolean, actionEnabled: Boolean = true): Boolean {
        if (!eligible || !released || !dragStopped || consumed || !revealed || !actionEnabled) return false
        consumed = true
        return true
    }
}

/** Light themes need enough contrast for white labels. Dark callers keep their primary unchanged. */
internal fun workspaceSelectedColor(accent: Color): Color {
    val opaque = accent.copy(alpha = 1f)
    if ((1.05f / (opaque.luminance() + .05f)) >= 4.5f) return opaque
    var low = 0f
    var high = 1f
    repeat(16) {
        val amount = (low + high) / 2f
        val candidate = Color(opaque.red * amount, opaque.green * amount, opaque.blue * amount)
        if (1.05f / (candidate.luminance() + .05f) >= 4.5f) low = amount else high = amount
    }
    return Color(opaque.red * low, opaque.green * low, opaque.blue * low)
}

/** Operation colours are independent of the user's theme; yellow keeps a dark readable label. */
internal fun sidebarActionColor(action: SidebarSwipeAction): Color = when (action) {
    SidebarSwipeAction.Archive -> Color(0xFFFFCC00)
    SidebarSwipeAction.DeleteEmptyWorkspace -> Color(0xFFD92D23)
}

internal fun sidebarActionForeground(action: SidebarSwipeAction): Color = when (action) {
    SidebarSwipeAction.Archive -> Color(0xFF241C00)
    SidebarSwipeAction.DeleteEmptyWorkspace -> Color.White
}

/** The same centre transform as the foreground layer; no geometry is inferred from reveal width. */
internal fun sidebarTransformedBounds(bounds: Rect, scaleX: Float, scaleY: Float, translation: Offset): Rect {
    if (bounds.isEmpty || !scaleX.isFinite() || !scaleY.isFinite() || scaleX <= 0f || scaleY <= 0f ||
        !translation.x.isFinite() || !translation.y.isFinite()) return Rect.Zero
    val center = bounds.center + translation
    val halfWidth = bounds.width * scaleX * .5f
    val halfHeight = bounds.height * scaleY * .5f
    return Rect(center.x - halfWidth, center.y - halfHeight, center.x + halfWidth, center.y + halfHeight)
}

/** Match the visible translated body; its viewport clips only the body, never the action capsule. */
internal fun sidebarVisibleBodyBounds(body: Rect, viewport: Rect): Rect {
    if (body.isEmpty || viewport.isEmpty) return Rect.Zero
    val left = maxOf(body.left, viewport.left)
    val top = maxOf(body.top, viewport.top)
    val right = minOf(body.right, viewport.right)
    val bottom = minOf(body.bottom, viewport.bottom)
    return if (right > left && bottom > top) Rect(left, top, right, bottom) else Rect.Zero
}

@Stable
private class SidebarFusionLayoutState {
    private var host: LayoutCoordinates? = null
    private var body: LayoutCoordinates? = null
    private var action: LayoutCoordinates? = null
    var viewportBounds by mutableStateOf(Rect.Zero)
        private set
    var bodyBounds by mutableStateOf(Rect.Zero)
        private set
    var actionBounds by mutableStateOf(Rect.Zero)
        private set

    fun updateHost(coordinates: LayoutCoordinates) { host = coordinates; updateBounds() }
    fun updateBody(coordinates: LayoutCoordinates) { body = coordinates; updateBounds() }
    fun updateAction(coordinates: LayoutCoordinates) { action = coordinates; updateBounds() }

    private fun updateBounds() {
        val parent = host?.takeIf { it.isAttached } ?: return
        viewportBounds = Rect(0f, 0f, parent.size.width.toFloat(), parent.size.height.toFloat())
        bodyBounds = body?.takeIf { it.isAttached }?.let {
            sidebarVisibleBodyBounds(parent.localBoundingBoxOf(it, clipBounds = false), viewportBounds)
        } ?: Rect.Zero
        actionBounds = action?.takeIf { it.isAttached }?.let {
            parent.localBoundingBoxOf(it, clipBounds = false)
        } ?: Rect.Zero
    }
}

@Composable
private fun SidebarSwipeRow(
    identity: SidebarSwipeIdentity,
    title: String,
    glyph: String,
    selected: Boolean,
    actionEnabled: Boolean,
    revealedRow: SidebarSwipeIdentity?,
    onRevealChanged: (identity: SidebarSwipeIdentity, isRevealed: Boolean) -> Unit,
    onAction: (identity: SidebarSwipeIdentity) -> Boolean,
    onSelect: () -> Unit,
) {
    val palette = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val reducedMotion = LocalReduceGlassMotion.current
    val reducedTransparency = LocalReduceGlassTransparency.current
    val springEnabled = LocalGlassButtonSpring.current
    val fusionLayout = remember(identity) { SidebarFusionLayoutState() }
    val actionDeformation = rememberGlassButtonDeformationState()
    val connection = remember(identity) { Animatable(0f) }
    val revealWidth = 84.dp
    var rowWidthPx by remember { mutableFloatStateOf(0f) }
    val actualGeometry = remember(rowWidthPx, density, direction) {
        SidebarSwipeGeometry(if (rowWidthPx > 0f) rowWidthPx else with(density) { 240.dp.toPx() },
            with(density) { revealWidth.toPx() }, direction == LayoutDirection.Rtl)
    }
    val currentGeometry by rememberUpdatedState(actualGeometry)
    val swipeState = remember(identity, density, direction) {
        AnchoredDraggableState(
            initialValue = if (revealedRow == identity) SidebarSwipe.Revealed else SidebarSwipe.Closed,
            anchors = DraggableAnchors {
                SidebarSwipe.Closed at 0f
                SidebarSwipe.Revealed at actualGeometry.revealOffset
            },
        )
    }
    val currentRevealChanged by rememberUpdatedState(onRevealChanged)
    val currentAction by rememberUpdatedState(onAction)
    val currentRevealedRow by rememberUpdatedState(revealedRow)
    val currentActionEnabled by rememberUpdatedState(actionEnabled)
    val gate = remember(swipeState) { SidebarSwipeGate() }
    var pointerPressed by remember(swipeState) { mutableStateOf(false) }
    var fusionPressed by remember(swipeState) { mutableStateOf(false) }
    var dragInProgress by remember(swipeState) { mutableStateOf(false) }
    var dragCompletion by remember(swipeState) { mutableIntStateOf(0) }
    var reboundJob by remember(swipeState) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val animation = GlassMotion.scalar(reducedMotion = reducedMotion)
    fun rebound(target: SidebarSwipe, gestureGeneration: Int = gate.generation) {
        reboundJob?.cancel()
        reboundJob = scope.launch {
            if (gestureGeneration == gate.generation && !pointerPressed) {
                swipeState.animateTo(target, animation)
            }
        }
    }
    val currentCancelRebound by rememberUpdatedState {
        rebound(if (gate.startedAtReveal && currentRevealedRow == identity && currentActionEnabled)
            SidebarSwipe.Revealed else SidebarSwipe.Closed)
    }
    val dragInteractions = remember(swipeState, gate) {
        val source = MutableInteractionSource()
        // Record at emission: collecting Start later can attach an old drag to a new DOWN.
        object : MutableInteractionSource by source {
            private fun record(interaction: Interaction) {
                when (interaction) {
                    is DragInteraction.Start -> {
                        gate.startDrag(interaction)
                        dragInProgress = true
                    }
                    is DragInteraction.Stop -> gate.stopDrag(interaction.start)?.let {
                        dragCompletion = it
                        dragInProgress = false
                    }
                    is DragInteraction.Cancel -> gate.cancelDrag(interaction.start)?.let {
                        dragCompletion = it
                        dragInProgress = false
                        fusionPressed = false
                        currentCancelRebound()
                    }
                }
            }
            override suspend fun emit(interaction: Interaction) {
                record(interaction)
                source.emit(interaction)
            }
            override fun tryEmit(interaction: Interaction): Boolean {
                record(interaction)
                return source.tryEmit(interaction)
            }
        }
    }
    LaunchedEffect(swipeState, gate) {
        snapshotFlow { Triple(swipeState.settledValue, pointerPressed, dragCompletion) }.collect { (stage, pressed, _) ->
            if (pressed) return@collect
            if (gate.cancelled) {
                val target = if (gate.startedAtReveal && currentRevealedRow == identity && currentActionEnabled)
                    SidebarSwipe.Revealed else SidebarSwipe.Closed
                val targetOffset = if (target == SidebarSwipe.Revealed) currentGeometry.revealOffset else 0f
                if (stage != target || swipeState.targetValue != target || abs(swipeState.requireOffset() - targetOffset) > .5f) {
                    rebound(target)
                    return@collect
                }
            }
            // Release commits from the physical UP sample, even if the fling would settle elsewhere.
            if (dragCompletion == gate.generation &&
                gate.consumeCommit(currentRevealedRow == identity, currentActionEnabled)) {
                val accepted = currentAction(identity)
                gate.cancel()
                if (accepted) currentRevealChanged(identity, false)
                rebound(if (accepted) SidebarSwipe.Closed else SidebarSwipe.Revealed)
            } else if (stage == SidebarSwipe.Expanded) {
                // A fling may animate past the threshold, but it cannot grant release permission.
                if (dragCompletion == gate.generation) currentCancelRebound()
            } else {
                currentRevealChanged(identity, stage != SidebarSwipe.Closed)
            }
        }
    }
    LaunchedEffect(revealedRow, actionEnabled, swipeState) {
        val isRevealed = revealedRow == identity
        if (!isRevealed || !actionEnabled) {
            gate.cancel()
            if (abs(swipeState.requireOffset()) > .5f) rebound(SidebarSwipe.Closed)
        }
    }
    LaunchedEffect(actualGeometry, swipeState) {
        gate.cancel()
        swipeState.updateAnchors(DraggableAnchors {
            SidebarSwipe.Closed at 0f
            if (currentActionEnabled) SidebarSwipe.Revealed at actualGeometry.revealOffset
        }, newTarget = if (currentRevealedRow == identity && currentActionEnabled)
            SidebarSwipe.Revealed else SidebarSwipe.Closed)
    }
    val distancePx = actualGeometry.distance(swipeState.requireOffset())
    val progress = (distancePx / actualGeometry.revealDistance).coerceIn(0f, 1f)
    val expansion = actualGeometry.extension(swipeState.requireOffset())
    val actionTint = sidebarActionColor(identity.action)
    val actionForeground = sidebarActionForeground(identity.action)
    val selectedColor = if (palette.background.luminance() < .5f) palette.primary else workspaceSelectedColor(palette.primary)
    val bodyColor by animateColorAsState(
        if (selected) selectedColor else palette.surface,
        animationSpec = tween(if (reducedMotion) 0 else GlassMotion.ReleaseMillis, easing = GlassMotion.EaseOut),
        label = "sidebarFusionBodyColor",
    )
    val actionIcon = if (identity.action == SidebarSwipeAction.DeleteEmptyWorkspace) "trash" else "archive"
    val actionLabel = if (identity.action == SidebarSwipeAction.DeleteEmptyWorkspace) "删除" else "归档"
    val revealScale = if (reducedMotion) 1f else .92f + .08f * progress
    fun measuredActionBounds(): Rect {
        val frame = actionDeformation.visualFrame
        val geometry = currentGeometry
        val currentProgress = (geometry.distance(swipeState.requireOffset()) / geometry.revealDistance).coerceIn(0f, 1f)
        val scale = if (reducedMotion) 1f else .92f + .08f * currentProgress
        return sidebarTransformedBounds(fusionLayout.actionBounds,
            scale * frame.scaleX, scale * frame.scaleY, frame.translation * scale)
    }
    LaunchedEffect(fusionLayout, swipeState, actionDeformation, reducedMotion, reducedTransparency,
        springEnabled, actionEnabled, density) {
        connection.snapTo(0f)
        if (reducedMotion || reducedTransparency || !springEnabled || !actionEnabled) return@LaunchedEffect
        var previous: GlassPairMotionFrame? = null
        var lastChange = SystemClock.uptimeMillis()
        snapshotFlow {
            val body = fusionLayout.bodyBounds
            val action = measuredActionBounds()
            val baseline = with(density) { 52.dp.toPx() }.coerceAtLeast(1f)
            GlassPairMotionFrame(
                Offset(body.width / baseline, body.height / baseline), body.center,
                Offset(action.width / baseline, action.height / baseline), action.center,
                fusionPressed, actionDeformation.pressed,
            )
        }.collectLatest { frame ->
            val now = SystemClock.uptimeMillis()
            val strength = glassPairConnectionStrength(previous, frame, now - lastChange, density.density)
            previous = frame
            lastChange = now
            if (strength > 0f) {
                connection.animateTo(maxOf(connection.value, strength), GlassMotion.scalar(GlassMotion.PressMillis))
                if (frame.firstPressed || frame.secondPressed) return@collectLatest
            }
            connection.animateTo(0f, GlassMotion.scalar(GlassMotion.PressMillis))
        }
    }
    Box(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).height(52.dp)
            .onSizeChanged { rowWidthPx = it.width.toFloat() }
                .pointerInput(swipeState, gate) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        reboundJob?.cancel()
                        pointerPressed = true
                        fusionPressed = true
                        dragInProgress = false
                        val gestureGeometry = currentGeometry
                        val allowCommit = gate.begin(
                            swipeState.settledValue, swipeState.isAnimationRunning, currentRevealedRow == identity,
                            atReveal = abs(swipeState.requireOffset() - gestureGeometry.revealOffset) < .5f &&
                                swipeState.targetValue == SidebarSwipe.Revealed,
                            actionEnabled = currentActionEnabled,
                        )
                        val gestureGeneration = gate.generation
                        if (currentEvent.changes.count { it.pressed } > 1) {
                            gate.cancel()
                            fusionPressed = false
                        }
                        swipeState.updateAnchors(DraggableAnchors {
                            SidebarSwipe.Closed at 0f
                            if (currentActionEnabled) SidebarSwipe.Revealed at gestureGeometry.revealOffset
                            if (allowCommit) SidebarSwipe.Expanded at gestureGeometry.expandedOffset
                        }, newTarget = if (currentRevealedRow == identity && currentActionEnabled)
                            SidebarSwipe.Revealed else SidebarSwipe.Closed)
                        try {
                            var anyPressed: Boolean
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.count { it.pressed } > 1 || !currentActionEnabled ||
                                    (gate.startedAtReveal && currentRevealedRow != identity)) {
                                    gate.cancel()
                                    fusionPressed = false
                                }
                                anyPressed = event.changes.any { it.pressed }
                                if (!anyPressed) {
                                    // Initial precedes Main's fling; momentum cannot invent an UP crossing.
                                    val thresholdReached = gestureGeometry.thresholdReached(swipeState.requireOffset())
                                    awaitPointerEvent(PointerEventPass.Final)
                                    gate.release(gestureGeneration, thresholdReached)
                                }
                            } while (anyPressed)
                        } catch (cancelled: CancellationException) {
                            gate.cancel()
                            dragCompletion = gestureGeneration
                            throw cancelled
                        } finally {
                            pointerPressed = false
                            fusionPressed = false
                            if (gate.cancelled) currentCancelRebound()
                        }
                    }
                }
                .anchoredDraggable(
                    state = swipeState,
                    reverseDirection = false,
                    orientation = Orientation.Horizontal,
                    enabled = actionEnabled,
                    interactionSource = dragInteractions,
                    flingBehavior = AnchoredDraggableDefaults.flingBehavior(
                        state = swipeState,
                        positionalThreshold = { it * .72f },
                        animationSpec = animation,
                    ),
                )
                .semantics(mergeDescendants = true) {
                    this.selected = selected
                    val rowType = if (identity.action == SidebarSwipeAction.Archive) "对话" else "工作区"
                    contentDescription = "$rowType：$title${if (selected) "，当前$rowType" else ""}"
                    customActions = buildList {
                        if (actionEnabled) add(CustomAccessibilityAction("$actionLabel$rowType") {
                            gate.cancel()
                            val accepted = currentAction(identity)
                            if (accepted) rebound(SidebarSwipe.Closed)
                            accepted
                        })
                        if (revealedRow == identity) {
                            add(CustomAccessibilityAction("关闭${actionLabel}操作") {
                                gate.cancel()
                                currentRevealChanged(identity, false)
                                rebound(SidebarSwipe.Closed)
                                true
                            })
                        }
                    }
                },
    ) {
        LiquidGlassFusionSurface(
            modifier = Modifier.fillMaxSize().onGloballyPositioned(fusionLayout::updateHost),
            first = {
                fusionLayout.bodyBounds.takeUnless { it.isEmpty }?.let {
                    LiquidGlassShape(it, minOf(it.width, it.height) * .5f,
                        bodyColor.copy(alpha = .78f), materialProfile = GlassMaterialProfile.AccentButton)
                }
            },
            second = {
                if (!actionEnabled || progress <= 0f) null
                else fusionLayout.actionBounds.takeUnless { it.isEmpty }?.let {
                    val frame = actionDeformation.visualFrame
                    val translation = frame.translation * revealScale
                    val bounds = Rect(it.left + translation.x, it.top + translation.y,
                        it.right + translation.x, it.bottom + translation.y)
                    LiquidGlassShape(bounds, minOf(it.width, it.height) * .5f,
                        actionTint.copy(alpha = .78f), alpha = progress,
                        materialProfile = GlassMaterialProfile.AccentButton,
                        scaleX = revealScale * frame.scaleX, scaleY = revealScale * frame.scaleY)
                }
            },
            profile = GlassMaterialProfile.AccentButton,
            connection = { connection.value },
            capture = null,
            opaqueShapeBackdrop = true,
        ) { drawOwnMaterial ->
            // Only the body is clipped to its viewport. The action keeps its complete round ends.
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(26.dp))) {
                SidebarItem(
                    title = title, glyph = glyph, selected = selected, opaquePill = true,
                    opaqueColor = bodyColor, drawOwnMaterial = drawOwnMaterial,
                    modifier = Modifier.absoluteOffset {
                        IntOffset(actualGeometry.visualOffset(swipeState.requireOffset()).roundToInt(), 0)
                    }.onGloballyPositioned(fusionLayout::updateBody),
                    onClick = {
                        if (abs(swipeState.requireOffset()) > .5f) {
                            gate.cancel()
                            currentRevealChanged(identity, false)
                            rebound(SidebarSwipe.Closed)
                        } else onSelect()
                    },
                )
            }
            if (progress > 0f && actionEnabled) {
                val capsuleWidthPx = actualGeometry.actionWidth(swipeState.requireOffset(), with(density) { 76.dp.toPx() })
                val insetPx = with(density) { 4.dp.toPx() } * (1f - expansion)
                val capsuleWidth = with(density) { capsuleWidthPx.toDp() }
                val canTap = !dragInProgress && swipeState.settledValue == SidebarSwipe.Revealed &&
                    !swipeState.isAnimationRunning && abs(swipeState.requireOffset() - actualGeometry.revealOffset) < .5f
                // Measure an untransformed layout sibling; visual transforms live on its child.
                Box(Modifier.align(Alignment.CenterEnd).width(capsuleWidth).fillMaxHeight()
                    .offset(x = with(density) { -insetPx.toDp() })
                    .onGloballyPositioned(fusionLayout::updateAction)) {
                    OpaqueSidebarCapsule(
                        color = actionTint,
                        modifier = Modifier.fillMaxSize()
                            .graphicsLayer { alpha = progress; scaleX = revealScale; scaleY = revealScale }
                            .applyButtonDeformation(enabled = canTap, state = actionDeformation,
                                consumeDrag = false, drawShadow = drawOwnMaterial),
                        drawOwnMaterial = drawOwnMaterial,
                        buttonDeformation = actionDeformation,
                    ) {
                        Row(
                            Modifier.fillMaxSize().clip(RoundedCornerShape(26.dp))
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                                    enabled = canTap, role = Role.Button, onClick = {
                                        gate.cancel()
                                        val accepted = currentAction(identity)
                                        if (accepted) currentRevealChanged(identity, false)
                                        rebound(if (accepted) SidebarSwipe.Closed else SidebarSwipe.Revealed)
                                    })
                                .semantics { contentDescription = "$actionLabel：$title" },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            FishGlyph(actionIcon, Modifier.size((22f + if (reducedMotion) 0f else 6f * expansion).dp), actionForeground)
                            if (expansion > 0f) {
                                Spacer(Modifier.width(10.dp * expansion))
                                Box(Modifier.width(54.dp * expansion).graphicsLayer { alpha = expansion }) {
                                    Text(actionLabel, color = actionForeground, maxLines = 1, softWrap = false,
                                        fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OpaqueSidebarCapsule(
    color: Color,
    modifier: Modifier = Modifier,
    drawShadow: Boolean = true,
    drawOwnMaterial: Boolean = true,
    buttonDeformation: GlassButtonDeformationState? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = rememberGlassBackdropState()
    val shadowlessFrame = rememberGlassButtonDeformationState()
    val shape = RoundedCornerShape(26.dp)
    Box(modifier) {
        // The native source is deliberately solid and has the same bounds as this capsule.
        // It stays invisible: only its pixels are sampled, never the page or its background logo.
        if (drawOwnMaterial && !LocalReduceGlassTransparency.current && !LocalReduceGlassMotion.current) {
            GlassBackdrop(backdrop, Modifier.matchParentSize().graphicsLayer { alpha = 0f }, color.copy(alpha = 1f))
        }
        if (drawOwnMaterial) Box(Modifier.matchParentSize().background(color.copy(alpha = 1f), shape))
        CompositionLocalProvider(LocalPageGlassBackdrop provides null) {
            GlassSurface(
                backdrop = backdrop, modifier = Modifier.matchParentSize(), cornerRadius = 26.dp,
                tint = color.copy(alpha = .78f), materialProfile = GlassMaterialProfile.AccentButton,
                // The translated body has no outer shadow to cut at the viewport. The action keeps it.
                buttonDeformation = buttonDeformation ?: if (drawShadow) null else shadowlessFrame,
                drawMaterial = drawOwnMaterial,
                reduceTransparency = LocalReduceGlassTransparency.current || LocalReduceGlassMotion.current,
                content = content,
            )
        }
    }
}

@Composable
private fun SidebarItem(
    title: String,
    glyph: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    opaquePill: Boolean = false,
    opaqueColor: Color? = null,
    drawOwnMaterial: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = MaterialTheme.colorScheme
    val reducedMotion = LocalReduceGlassMotion.current
    val foreground by animateColorAsState(
        if (selected) Color.White else palette.onSurface,
        animationSpec = tween(if (reducedMotion) 0 else GlassMotion.ReleaseMillis, easing = GlassMotion.EaseOut),
        label = "conversationForeground",
    )
    val selectedColor = if (palette.background.luminance() < .5f) palette.primary else workspaceSelectedColor(palette.primary)
    val bodyColor by animateColorAsState(
        if (selected) selectedColor else palette.surface,
        animationSpec = tween(if (reducedMotion) 0 else GlassMotion.ReleaseMillis, easing = GlassMotion.EaseOut),
        label = "sidebarOpaqueBody",
    )
    val bodyModifier = modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                role = Role.Button, onClick = onClick)
            .height(52.dp)
    val body: @Composable BoxScope.() -> Unit = {
        Row(Modifier.fillMaxWidth().height(52.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FishGlyph(glyph, Modifier.size(21.dp), foreground)
        Text(
            title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = foreground, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        }
    }
    if (opaquePill) OpaqueSidebarCapsule(opaqueColor ?: bodyColor, bodyModifier,
        drawShadow = false, drawOwnMaterial = drawOwnMaterial, content = body)
    else Box(bodyModifier, content = body)
}

@Composable
private fun DeveloperCenterPresenceStatusBar(
    snapshot: DeveloperCenterPresenceClient.Snapshot,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val palette = MaterialTheme.colorScheme
    val available = snapshot.serviceState == DeveloperCenterPresenceClient.ServiceState.ONLINE
    val dotColor = when {
        loading -> palette.onSurfaceVariant
        available && snapshot.onlineDeviceCount != null -> Color(0xFF35C759)
        snapshot.serviceState == DeveloperCenterPresenceClient.ServiceState.PREVIEW -> palette.onSurfaceVariant
        else -> palette.error
    }
    val countText = when {
        loading -> "读取中"
        available && snapshot.onlineDeviceCount != null -> "${snapshot.onlineDeviceCount} 人"
        available -> "总数未知"
        snapshot.serviceState == DeveloperCenterPresenceClient.ServiceState.PREVIEW -> "未配置"
        else -> "暂不可用"
    }
    val platformText = if (available) {
        "PC ${snapshot.pcOnlineCount ?: "未知"} · 手机 ${snapshot.androidOnlineCount ?: "未知"}"
    } else null
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "在线设备：$countText${platformText?.let { "，$it" }.orEmpty()}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(percent = 50)).background(dotColor))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "在线设备", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                    color = palette.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    countText, style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold, color = palette.onSurface,
                )
            }
            platformText?.let {
                Text(
                    it, style = MaterialTheme.typography.labelSmall,
                    color = palette.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SidebarIconAction(
    title: String,
    glyph: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(title) } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(48.dp).applyButtonDeformation(enabled = enabled, circularHit = true, drawShadow = false)
                .semantics { contentDescription = title },
            colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent),
        ) { FishGlyph(glyph, Modifier.size(21.dp)) }
    }
}
