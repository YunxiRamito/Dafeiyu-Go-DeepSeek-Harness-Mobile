package com.dafeiyu.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dafeiyu.mobile.data.Conversation
import com.dafeiyu.mobile.data.ConversationArchiveGroup
import com.dafeiyu.mobile.data.DemoRepository
import com.dafeiyu.mobile.data.RemoteDshScope
import com.dafeiyu.mobile.data.RemoteDshApplicationController
import com.dafeiyu.mobile.data.RemoteWorkspaceStore
import com.dafeiyu.mobile.data.archiveRemainingDays
import com.dafeiyu.mobile.ui.components.FishDivider
import com.dafeiyu.mobile.ui.components.FishGlyph
import com.dafeiyu.mobile.ui.components.GlassMotion
import com.dafeiyu.mobile.ui.components.LocalReduceGlassMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ConversationArchiveContent(repository: DemoRepository, notify: (String) -> Unit) {
    val reducedMotion = LocalReduceGlassMotion.current
    val transitionMillis = if (reducedMotion) 0 else GlassMotion.MorphMillis
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var deleteTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val online = repository.connectionMode == "online"
    val remoteScope = repository.remoteWorkspaceScope
    val scope = rememberCoroutineScope()
    var preparingDelete by remember(online, remoteScope) { mutableStateOf(false) }
    var remoteDeleteTarget by remember(online, remoteScope) { mutableStateOf<RemoteWorkspaceStore.ArchiveDeletion?>(null) }
    var displayedGroups by remember(online, remoteScope) { mutableStateOf<List<ConversationArchiveGroup>>(emptyList()) }
    var fileDeleteTarget by remember { mutableStateOf<RemoteArchivedFilesTarget?>(null) }

    LaunchedEffect(repository, online, remoteScope) {
        deleteTarget = null
        fileDeleteTarget = null
        repository.refreshConversationArchives()
        while (true) {
            delay(60_000L)
            nowMillis = System.currentTimeMillis()
            repository.refreshConversationArchives()
        }
    }

    val archived = repository.displayArchivedConversations
    val groups = repository.displayArchiveGroups
    val activeGroupIds = groups.mapTo(HashSet()) { it.workspaceId }
    val activeConversationIds = archived.mapTo(HashSet()) { it.id }
    LaunchedEffect(groups, transitionMillis) {
        val previous = displayedGroups.associateBy { it.workspaceId }
        displayedGroups = (groups.map { group ->
            val currentIds = group.conversations.mapTo(HashSet()) { it.id }
            group.copy(conversations = (group.conversations + previous[group.workspaceId]?.conversations.orEmpty()
                .filterNot { it.id in currentIds }).sortedBy { it.archiveOrigin?.conversationOrder ?: Int.MAX_VALUE })
        } + displayedGroups.filterNot { it.workspaceId in activeGroupIds }).sortedBy { it.workspaceOrder }
        delay(transitionMillis.toLong())
        displayedGroups = groups
    }
    DetailCard {
        TinyLabel(if (online) "电脑归档" else "归档管理", primary = true)
        Text(if (online) "归档对话保留在电脑" else "归档对话会保留 30 天", style = MaterialTheme.typography.titleMedium)
        Hint(if (online) if (repository.remoteArchiveDeletionAvailable)
            "还原和永久删除由电脑执行。归档保留 30 天；缺少归档日期的旧记录保留至手动删除。"
            else "还原与文件操作由电脑执行。电脑永久删除与到期清理尚未就绪（预留）；旧记录的删除期限保持未知。"
            else "到期后自动永久删除。你也可以随时还原，或立即永久删除。")
        if (online) {
            repository.remoteDshState.error?.let { Hint(it) }
            AnimatedVisibility(preparingDelete,
                enter = fadeIn(GlassMotion.scalar(reducedMotion = reducedMotion)),
                exit = fadeOut(GlassMotion.scalar(reducedMotion = reducedMotion))) {
                Hint("正在读取电脑的删除确认…")
            }
            TextButton(onClick = repository::refreshConversationArchives,
                enabled = repository.remoteWorkspaceLifecycleReady && !repository.archiveOperationBusy) { Text("刷新") }
            if (!repository.remoteWorkspaceLifecycleReady) Hint("请连接支持工作区管理的电脑；缓存记录仍会保留。")
        } else {
            AnimatedVisibility(repository.localArchiveDeletionDetail != null,
                enter = fadeIn(GlassMotion.scalar(reducedMotion = reducedMotion)),
                exit = fadeOut(GlassMotion.scalar(reducedMotion = reducedMotion))) {
                repository.localArchiveDeletionDetail?.let { Hint(it) }
            }
            TextButton(onClick = repository::refreshConversationArchives,
                enabled = !repository.archiveOperationBusy) { Text("刷新") }
        }
    }

    if (groups.isEmpty()) {
        DetailCard {
            FishGlyph("folder", Modifier.size(26.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
            Text("这里暂时没有归档对话", style = MaterialTheme.typography.titleMedium)
            Hint("在工作目录中向对话行左滑，即可将对话归档。")
        }
    }
    displayedGroups.forEach { group ->
        key(group.workspaceId) {
            val visible = remember(group.workspaceId) { MutableTransitionState(false) }
            visible.targetState = group.workspaceId in activeGroupIds
            AnimatedVisibility(
                visibleState = visible,
                enter = fadeIn(GlassMotion.scalar(reducedMotion = reducedMotion)) +
                    expandVertically(tween(transitionMillis, easing = GlassMotion.EaseOut)),
                exit = fadeOut(GlassMotion.scalar(reducedMotion = reducedMotion)) +
                    shrinkVertically(tween(transitionMillis, easing = GlassMotion.EaseOut)),
            ) {
                DetailCard {
                    val removal = if (online) repository.remoteRemovedWorkspace(group.workspaceId) else null
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FishGlyph("folder", Modifier.size(24.dp), MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(group.workspaceName, style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (group.workspaceRemoved && online) "工作区已移除，请先还原电脑工作区"
                                else if (group.workspaceRemoved) "工作区已移除，还原对话时恢复工作区记录"
                                else "${group.conversations.count { it.id in activeConversationIds }} 个归档对话",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (removal != null) TextButton(
                            enabled = repository.remoteWorkspaceLifecycleReady && !repository.archiveOperationBusy,
                            onClick = {
                                if (repository.requestRestoreRemoteWorkspace(removal.ticket, remoteScope))
                                    notify("电脑工作区还原请求已提交")
                            },
                        ) { Text("还原") }
                    }
                    if (removal != null) {
                        Hint(removal.workspace.path)
                        if (removal.filesDeleted) Hint("电脑文件已删除；还原会话前需重新准备原目录。")
                        else TextButton(enabled = repository.remoteWorkspaceLifecycleReady && !repository.archiveOperationBusy &&
                            removal.filesIdentityVerified,
                            onClick = {
                                remoteScope?.let { binding -> fileDeleteTarget = RemoteArchivedFilesTarget(
                                    removal.ticket, removal.workspace.path, group.workspaceName, binding) }
                            }) { Text("删除保留的电脑文件…", color = MaterialTheme.colorScheme.error) }
                    }
                    Column(Modifier.animateContentSize(tween(transitionMillis, easing = GlassMotion.EaseOut))) {
                        group.conversations.forEach { conversation ->
                            key(conversation.id) {
                                val rowVisible = remember(conversation.id) { MutableTransitionState(false) }
                                rowVisible.targetState = conversation.id in activeConversationIds
                                AnimatedVisibility(
                                    visibleState = rowVisible,
                                    enter = fadeIn(GlassMotion.scalar(reducedMotion = reducedMotion)) +
                                        expandVertically(tween(transitionMillis, easing = GlassMotion.EaseOut)),
                                    exit = fadeOut(GlassMotion.scalar(reducedMotion = reducedMotion)) +
                                        shrinkVertically(tween(transitionMillis, easing = GlassMotion.EaseOut)),
                                ) {
                                    Column {
                                        FishDivider()
                                        ArchiveConversationRow(
                                            conversation = conversation,
                                            nowMillis = nowMillis,
                                            restoreEnabled = repository.canRestoreArchivedConversation(conversation.id),
                                            deleteEnabled = !repository.archiveOperationBusy && !preparingDelete &&
                                                (!online || repository.remoteArchiveDeletionAvailable),
                                            deletionReserved = online && !repository.remoteArchiveDeletionAvailable,
                                            retentionLabel = if (online) repository.remoteDshState.projection?.workspace?.archives
                                                ?.firstOrNull { it.sessionId == conversation.id }?.expiresAt?.let { expiry ->
                                                    val days = ((expiry - nowMillis).coerceAtLeast(0L) + 86_399_999L) / 86_400_000L
                                                    if (days == 0L) if (repository.remoteArchiveDeletionAvailable)
                                                        "保留期已到，等待电脑安全清理" else "保留期已到，电脑清理能力待接通"
                                                    else "保留期剩余 $days 天"
                                                } ?: "删除期限未确认" else null,
                                            onRestore = {
                                                if (repository.restoreConversation(conversation.id))
                                                    notify("还原已提交：${conversation.title}")
                                            },
                                            onDelete = {
                                                if (!online) deleteTarget = conversation.id
                                                else if (!preparingDelete) {
                                                    preparingDelete = true
                                                    scope.launch {
                                                        try { remoteDeleteTarget = repository.prepareRemoteArchivedConversationDeletion(conversation.id, remoteScope) }
                                                        catch (cancelled: CancellationException) { throw cancelled }
                                                        catch (failure: Exception) { notify(RemoteDshApplicationController.remoteFailure(failure)) }
                                                        finally { preparingDelete = false }
                                                    }
                                                }
                                            },
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

    val target = archived.firstOrNull { it.id == deleteTarget }
    if (target != null && !online) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("永久删除此对话？") },
            text = { Text("“${target.title}”及其消息、已共享到本机 DSH 的历史版本将永久删除，无法撤销。工作区文件保留。") },
            confirmButton = {
                TextButton(enabled = !repository.archiveOperationBusy, onClick = {
                    if (repository.deleteArchivedConversation(target.id)) {
                        deleteTarget = null
                        notify("永久删除已提交")
                    }
                }) { Text("永久删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }

    remoteDeleteTarget?.takeIf { online && it.scope == remoteScope }?.let { prepared ->
        val conversation = archived.firstOrNull { it.id == prepared.sessionId }
        val currentArchive = repository.remoteDshState.projection?.workspace?.archives?.firstOrNull { it.sessionId == prepared.sessionId }
        val current = repository.remoteArchiveDeletionAvailable && conversation != null && currentArchive != null &&
            (currentArchive.archiveVersion == null || currentArchive.archiveVersion == prepared.archiveVersion)
        AlertDialog(
            onDismissRequest = { remoteDeleteTarget = null },
            title = { Text("永久删除电脑对话？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(conversation?.title ?: "电脑归档状态已改变", fontWeight = FontWeight.Medium)
                    Text("执行设备：已配对电脑")
                    Text(if (current) "此对话及其消息将从电脑永久删除，无法撤销。工作区目录和文件保留。"
                        else "原删除确认已失效，请关闭并重新读取电脑归档。",
                        color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = current && !repository.archiveOperationBusy, onClick = {
                    if (repository.requestDeleteRemoteArchivedConversation(prepared)) {
                        remoteDeleteTarget = null
                        notify("电脑永久删除请求已提交")
                    }
                }) { Text("永久删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { remoteDeleteTarget = null }) { Text("取消") } },
        )
    }

    fileDeleteTarget?.takeIf { online && it.binding == remoteScope }?.let { targetFiles ->
        val removal = repository.remoteDshState.projection?.workspace?.removals?.firstOrNull { it.ticket == targetFiles.ticket }
        AlertDialog(
            onDismissRequest = { fileDeleteTarget = null },
            title = { Text("永久删除电脑文件？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(targetFiles.workspaceName, fontWeight = FontWeight.Medium)
                    Text("执行设备：已配对电脑")
                    Text(targetFiles.path, style = MaterialTheme.typography.bodySmall)
                    Text("此电脑路径中的目录和文件将永久删除，无法撤销。归档会话保留，文件不会随会话还原。",
                        color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = repository.remoteWorkspaceLifecycleReady && !repository.archiveOperationBusy &&
                    removal != null && removal.workspace.path == targetFiles.path && removal.filesIdentityVerified && !removal.filesDeleted,
                    onClick = {
                        if (repository.requestDeleteRemoteWorkspaceFiles(targetFiles.ticket, targetFiles.path, targetFiles.binding)) {
                            fileDeleteTarget = null
                            notify("电脑文件删除请求已提交")
                        }
                    }) { Text("永久删除电脑文件", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { fileDeleteTarget = null }) { Text("取消") } },
        )
    }
}

private data class RemoteArchivedFilesTarget(val ticket: String, val path: String,
    val workspaceName: String, val binding: RemoteDshScope)

@Composable
private fun ArchiveConversationRow(
    conversation: Conversation,
    nowMillis: Long,
    restoreEnabled: Boolean,
    deleteEnabled: Boolean,
    deletionReserved: Boolean,
    retentionLabel: String?,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val archivedAt = conversation.archivedAtMillis
    val remainingDays = archiveRemainingDays(conversation, nowMillis)
    val archivedLabel = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
        .let { formatter -> archivedAt?.let { formatter.format(Date(it)) } }

    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FishGlyph("chat", Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(conversation.title, style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(archivedLabel?.let { "归档于 $it" } ?: "归档时间未知",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(retentionLabel ?: when {
                    remainingDays == null -> "保留至手动删除"
                    remainingDays == 0L -> "保留期已到，待清理"
                    else -> "$remainingDays 天后自动删除"
                }, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onRestore, enabled = restoreEnabled) { Text("还原") }
                TextButton(onClick = onDelete, enabled = deleteEnabled) { Text(if (deletionReserved) "永久删除（预留）" else "永久删除",
                    color = if (deleteEnabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall) }
            }
    }
}
