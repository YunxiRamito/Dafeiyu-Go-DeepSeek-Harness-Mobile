package com.dafeiyu.mobile.ui.components

import android.util.JsonReader
import android.util.JsonToken
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import com.dafeiyu.mobile.data.AssistantStreamSnapshot
import com.dafeiyu.mobile.data.AssistantStreamEvent
import com.dafeiyu.mobile.data.ChatMessage
import com.dafeiyu.mobile.ui.AnchoredGlassMenu
import com.dafeiyu.mobile.ui.GlassMenuPlacement
import com.dafeiyu.mobile.ui.rememberModelMenuMorphState
import java.io.StringReader
import org.json.JSONObject

internal data class ConversationTaskActivity(
    val id: String,
    val title: String,
    val kind: BackgroundTaskKind,
    val status: String,
    val detail: String,
    val liveTaskId: String? = null,
)

/** Only durable task/subagent facts belong to the hub; ordinary text/reasoning/tools do not. */
internal fun conversationTaskActivities(
    conversationId: String?,
    tasks: List<BackgroundTask>,
    history: List<ChatMessage>,
    activity: AssistantStreamSnapshot? = null,
): List<ConversationTaskActivity> {
    if (conversationId.isNullOrBlank()) return emptyList()
    val entries = linkedMapOf<String, ConversationTaskActivity>()
    tasks.asSequence().filter { it.parentConversationId == conversationId }
        .sortedByDescending { it.createdAtMillis }.forEach { task ->
            entries["${task.kind}:${task.id}"] = ConversationTaskActivity(
                task.id, task.title, task.kind, backgroundTaskStatusLabel(task.status),
                listOf(task.progress, task.result).filter { it.isNotBlank() }.joinToString("\n\n"), task.id,
            )
        }
    val snapshots = listOfNotNull(activity) + history.asReversed().mapNotNull { it.streamSnapshot }
    snapshots.forEach { snapshot ->
        snapshot.subagents.forEach { child ->
            val key = "${BackgroundTaskKind.SUBAGENT}:${child.agentId}"
            entries.putIfAbsent(key, ConversationTaskActivity(
                child.agentId, child.name?.takeIf { it.isNotBlank() } ?: "子 Agent", BackgroundTaskKind.SUBAGENT,
                when (child.status) {
                    AssistantStreamEvent.SubagentStatus.PENDING -> "等待中"
                    AssistantStreamEvent.SubagentStatus.STARTING -> "启动中"
                    AssistantStreamEvent.SubagentStatus.RUNNING -> "运行中"
                    AssistantStreamEvent.SubagentStatus.COMPLETED -> "已完成"
                    AssistantStreamEvent.SubagentStatus.FAILED -> "失败"
                    AssistantStreamEvent.SubagentStatus.CANCELLED -> "已取消"
                    AssistantStreamEvent.SubagentStatus.INCOMPLETE -> "未完成"
                },
                listOfNotNull(child.detail, child.output.takeIf { it.isNotBlank() }, child.error)
                    .filter { it.isNotBlank() }.distinct().joinToString("\n\n"),
            ))
        }
        snapshot.toolCalls.filter { it.name == "create_background_task" }.forEach background@ { call ->
            val response = call.result?.takeIf { it.length <= 65_536 }
                ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@background
            if (response.opt("ok") != true) return@background
            val data = response.optJSONObject("data") ?: return@background
            val taskId = (data.opt("task_id") as? String)?.takeIf {
                it.isNotBlank() && it.length <= 128 && it.none(Char::isISOControl)
            } ?: return@background
            entries.putIfAbsent("${BackgroundTaskKind.BACKGROUND}:$taskId", ConversationTaskActivity(
                taskId, (data.opt("title") as? String)?.takeIf { it.isNotBlank() } ?: "后台任务",
                BackgroundTaskKind.BACKGROUND, "历史记录",
                "此对话保留了任务创建记录。完整任务已不在当前任务存储中，不能据此判断它现在的运行状态。",
            ))
        }
        snapshot.toolCalls.filter {
            it.status == AssistantStreamEvent.ToolStatus.SUCCEEDED && it.name in setOf("bash", "pwsh")
        }.forEach remoteJob@ { call ->
            val prefix = "started background job "
            val receipt = call.result?.takeIf {
                it.length in (prefix.length + 1)..(prefix.length + 128) && it.none(Char::isISOControl)
            }
                ?: return@remoteJob
            if (!receipt.startsWith(prefix)) return@remoteJob
            val jobId = receipt.removePrefix(prefix)
            if (jobId.length !in 1..128 || jobId.any { it.isWhitespace() || it.isISOControl() }) return@remoteJob
            if (!hasExplicitRemoteBackgroundArgument(call.arguments)) return@remoteJob
            entries.putIfAbsent("remote-job:$jobId", ConversationTaskActivity(
                "remote-job:$jobId", "电脑后台任务 · $jobId", BackgroundTaskKind.BACKGROUND, "历史记录",
                "电脑已返回后台任务创建回执；当前任务状态尚未同步，不能据此判断它现在是否运行。\n\n$receipt",
            ))
        }
    }
    return entries.values.toList()
}

/** Admit only a complete JSON object with a boolean true field; coercion and duplicate keys fail closed. */
private fun hasExplicitRemoteBackgroundArgument(arguments: String): Boolean {
    if (arguments.length !in 1..65_536) return false
    return runCatching {
        requireStrictRemoteJobJsonLexemes(arguments)
        @Suppress("DEPRECATION")
        val reader = JsonReader(StringReader(arguments)).apply { isLenient = false }
        reader.use {
            val names = HashSet<String>()
            var background = false
            it.beginObject()
            while (it.hasNext()) {
                val name = it.nextName()
                require(names.add(name))
                if (name == "run_in_background") {
                    require(it.peek() == JsonToken.BOOLEAN)
                    background = it.nextBoolean()
                } else it.skipValue()
            }
            it.endObject()
            require(it.peek() == JsonToken.END_DOCUMENT)
            background
        }
    }.getOrDefault(false)
}

/** Android's JsonReader also accepts uppercase literals and unknown escapes; reject those before parsing. */
private fun requireStrictRemoteJobJsonLexemes(text: String) {
    var at = 0
    while (at < text.length) {
        when (text[at++]) {
            '"' -> {
                var closed = false
                while (at < text.length) {
                    val quoted = text[at++]
                    require(quoted >= ' ')
                    if (quoted == '"') { closed = true; break }
                    if (quoted == '\\') {
                        require(at < text.length)
                        val escape = text[at++]
                        require(escape in "\"\\/bfnrtu")
                        if (escape == 'u') {
                            require(at + 4 <= text.length)
                            require((at until at + 4).all { text[it] in '0'..'9' || text[it] in 'a'..'f' || text[it] in 'A'..'F' })
                            at += 4
                        }
                    }
                }
                require(closed)
            }
            in ",:{}[] \t\r\n" -> Unit
            else -> {
                val start = at - 1
                while (at < text.length && text[at] !in ",:{}[] \t\r\n") at++
                val literal = text.substring(start, at)
                require(literal in setOf("true", "false", "null") || strictRemoteJobNumber.matches(literal))
            }
        }
    }
}

private val strictRemoteJobNumber = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

private fun backgroundTaskStatusLabel(status: BackgroundTaskStatus): String = when (status) {
    BackgroundTaskStatus.DRAFT -> "草稿"
    BackgroundTaskStatus.QUEUED -> "等待中"
    BackgroundTaskStatus.RUNNING -> "运行中"
    BackgroundTaskStatus.STOPPING -> "停止中"
    BackgroundTaskStatus.SUCCEEDED -> "已完成"
    BackgroundTaskStatus.FAILED -> "失败"
    BackgroundTaskStatus.STOPPED -> "已停止"
    BackgroundTaskStatus.INTERRUPTED -> "已中断"
}

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
    conversationId: String? = null,
    history: List<ChatMessage> = emptyList(),
    activity: AssistantStreamSnapshot? = null,
) {
    val tasks = if (engine == null) emptyList() else engine.tasks.collectAsStateWithLifecycle().value
    val storageIssue = engine?.storageIssue?.collectAsStateWithLifecycle()?.value
    val activities = conversationTaskActivities(conversationId, tasks, history, activity)
    var expanded by remember(conversationId) { mutableStateOf(false) }
    var pendingNavigation by remember(conversationId) { mutableStateOf(false) }
    var selectedTaskId by remember(conversationId) { mutableStateOf<String?>(null) }
    var pendingHistory by remember(conversationId) { mutableStateOf<ConversationTaskActivity?>(null) }
    var selectedHistory by remember(conversationId) { mutableStateOf<ConversationTaskActivity?>(null) }
    val state = rememberModelMenuMorphState(expanded)
    val latestLauncherAlphaChanged by rememberUpdatedState(onLauncherAlphaChanged)
    val latestOverlayProgress by rememberUpdatedState(onOverlayProgress)
    val latestSurfaceVisibilityChanged by rememberUpdatedState(onSurfaceVisibilityChanged)
    DisposableEffect(state) {
        latestLauncherAlphaChanged(state.launcherAlpha)
        // Clear a retained menu fade when this launcher leaves composition.
        onDispose {
            latestLauncherAlphaChanged(1f)
            latestOverlayProgress(0f)
            latestSurfaceVisibilityChanged(false)
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.launcherAlpha }.collect { latestLauncherAlphaChanged(it) }
    }
    val palette = MaterialTheme.colorScheme
    // Hold final geometry while task updates arrive, including completion and deletion.
    val menuHeight = remember(expanded) { (224 + activities.size.coerceAtMost(6) * 52).dp }
    LaunchedEffect(dismissRequested) { if (dismissRequested) expanded = false }
    LaunchedEffect(activities.isEmpty()) {
        if (activities.isEmpty()) { expanded = false; pendingNavigation = false; pendingHistory = null }
    }
    fun choose(id: String?) {
        selectedTaskId = id
        pendingNavigation = true
        expanded = false
    }
    fun chooseHistory(item: ConversationTaskActivity) {
        pendingHistory = item
        expanded = false
    }
    @Composable
    fun GroupIcon() {
        FishGlyph("groupchat", Modifier.size(23.dp))
    }
    // Main retains this disabled launcher only for AnimatedVisibility's finite exit, then removes it.
    Box {
        GlassSurface(buttonDeformation = deformation, drawMaterial = drawLauncherMaterial,
            modifier = Modifier.size(48.dp)
                .applyButtonDeformation(state = deformation, circularHit = true, drawShadow = drawLauncherMaterial)
                .onGloballyPositioned { state.anchorBounds = it.boundsInWindow() }
                .graphicsLayer { alpha = state.launcherAlpha },
            cornerRadius = 24.dp, tint = palette.surface.copy(alpha = .18f)) {
            IconButton(enabled = activities.isNotEmpty(), onClick = {
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
                pendingHistory?.let {
                    selectedHistory = it
                    pendingHistory = null
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
                        val sectionActivities = activities.filter { it.kind == kind }
                        if (sectionActivities.isEmpty()) item(key = "empty-$kind") {
                            Text(if (kind == BackgroundTaskKind.SUBAGENT) "暂无子 Agent" else "暂无后台任务",
                                style = MaterialTheme.typography.bodyMedium, color = palette.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp))
                        }
                        items(sectionActivities, key = { "${it.kind}:${it.id}" }) { item ->
                            TaskSummaryRow(item) {
                                val taskId = item.liveTaskId
                                if (taskId != null) choose(taskId) else chooseHistory(item)
                            }
                        }
                        if (kind == BackgroundTaskKind.SUBAGENT) item(key = "divider") {
                            HorizontalDivider(Modifier.padding(vertical = 6.dp),
                                color = palette.outlineVariant.copy(alpha = .5f))
                        }
                    }
                }
                Text("仅显示当前对话的任务与子 Agent", style = MaterialTheme.typography.labelMedium,
                    color = palette.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp))
            }
        }
    }
    selectedHistory?.let { item ->
        AlertDialog(
            onDismissRequest = { selectedHistory = null },
            title = { Text(item.title) },
            text = {
                SelectionContainer {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(item.status, style = MaterialTheme.typography.labelLarge)
                        Text(item.detail.ifBlank { "此历史活动没有保存更多详情。" },
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedHistory = null }) { Text("完成") } },
        )
    }
}

@Composable
private fun TaskSummaryRow(task: ConversationTaskActivity, onClick: () -> Unit) {
    val palette = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().applyButtonDeformation(consumeDrag = false)
        .clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick)
        .heightIn(min = 52.dp).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FishGlyph(if (task.kind == BackgroundTaskKind.SUBAGENT) "groupchat" else "terminal",
            Modifier.size(20.dp), palette.onSurfaceVariant)
        Text(task.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium)
        Text(task.status, style = MaterialTheme.typography.labelMedium,
            color = if (task.status == "失败") palette.error else palette.onSurfaceVariant)
    }
}
