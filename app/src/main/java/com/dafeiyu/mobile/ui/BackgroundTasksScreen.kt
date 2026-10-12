package com.dafeiyu.mobile.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dafeiyu.mobile.data.BackgroundTaskEngine
import com.dafeiyu.mobile.data.BackgroundTaskKind
import com.dafeiyu.mobile.data.BackgroundTaskModel
import com.dafeiyu.mobile.data.BackgroundTaskStatus
import com.dafeiyu.mobile.ui.components.FishButton
import com.dafeiyu.mobile.ui.components.FishGlyph
import com.dafeiyu.mobile.ui.components.GlassMotion
import com.dafeiyu.mobile.ui.components.GlassSurface
import com.dafeiyu.mobile.ui.components.LocalReduceGlassMotion
import com.dafeiyu.mobile.ui.components.LocalFloatingGlassBackdrop
import com.dafeiyu.mobile.ui.components.LocalGlassBackdrop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun BackgroundTasksScreen(
    engine: BackgroundTaskEngine?,
    models: List<BackgroundTaskModel>,
    notify: (String) -> Unit,
    conversationId: String? = null,
    initialTaskId: String? = null,
) {
    Box(Modifier.fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
        .imePadding()
        .padding(top = LocalPageHeaderInset.current + 12.dp, bottom = 12.dp, start = 16.dp, end = 16.dp)) {
        if (engine == null) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("后台任务", style = MaterialTheme.typography.titleLarge)
                Text("任务存储尚未就绪", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            BackgroundTasksContent(engine, models, notify, conversationId, initialTaskId)
        }
    }
}

@Composable
private fun BackgroundTasksContent(
    engine: BackgroundTaskEngine,
    models: List<BackgroundTaskModel>,
    notify: (String) -> Unit,
    conversationId: String?,
    initialTaskId: String?,
) {
    val tasks by engine.tasks.collectAsState()
    val storageIssue by engine.storageIssue.collectAsState()
    val configuration by engine.configurationVersion.collectAsState()
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var creatingPending by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(initialTaskId) { selectedId = initialTaskId }
    var deletingId by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf<BackgroundTaskKind?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val visibleTasks = tasks.filter { filter == null || it.kind == filter }
        .sortedByDescending { it.createdAtMillis }
    val reducedMotion = LocalReduceGlassMotion.current
    val alpha by animateFloatAsState(
        if (tasks.isEmpty()) .8f else 1f,
        animationSpec = GlassMotion.scalar(reducedMotion = reducedMotion),
        label = "backgroundTasks",
    )
    fun submit(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
                message = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Storage or network exception text can contain credentials.
                message = "操作未完成，请检查模型配置、任务内容或存储空间"
                notify("操作未完成，请检查模型配置、任务内容或存储空间")
            }
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("后台任务", Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge)
            TaskIcon("add", "创建任务", { message = null; creating = true }, enabled = tasks.size < 50)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TaskFilter("全部", filter == null) { filter = null }
            TaskFilter("后台", filter == BackgroundTaskKind.BACKGROUND) { filter = BackgroundTaskKind.BACKGROUND }
            TaskFilter("子 Agent", filter == BackgroundTaskKind.SUBAGENT) { filter = BackgroundTaskKind.SUBAGENT }
        }
        if (models.isEmpty()) Text("模型未配置", style = MaterialTheme.typography.bodyMedium)
        (storageIssue ?: message)?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        if (visibleTasks.isEmpty()) Text(when (filter) {
            BackgroundTaskKind.BACKGROUND -> "暂无后台任务"
            BackgroundTaskKind.SUBAGENT -> "暂无子 Agent"
            null -> "暂无任务"
        }, style = MaterialTheme.typography.bodyMedium)
        LazyColumn(Modifier.weight(1f).graphicsLayer { this.alpha = alpha },
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(visibleTasks, key = { it.id }) { task ->
                // Reading this state makes availability refresh after configure().
                val available = remember(engine, configuration, task.model, models) { engine.canRun(task.model) }
                GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 8.dp) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(task.title, style = MaterialTheme.typography.titleMedium)
                        Text("${task.kind.label()} · ${models.firstOrNull { it.id == task.model }?.name ?: task.model} · ${task.status.label()}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        task.parentConversationId?.takeIf { it.isNotBlank() }?.let {
                            Text("关联会话：$it", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (task.progress.isNotBlank() && task.status.active())
                            Text(task.progress, maxLines = 3, style = MaterialTheme.typography.bodySmall)
                        if (!available.runnable && !task.status.active())
                            Text(available.reason.ifBlank { "模型暂不可用" }, style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TaskIcon("document", "查看任务和结果", { message = null; selectedId = task.id })
                            if (task.status.active()) {
                                TaskIcon("stop", "停止任务", { submit { engine.stop(task.id) } },
                                    enabled = task.status != BackgroundTaskStatus.STOPPING)
                            } else {
                                TaskIcon("play", task.status.runLabel(), { submit { engine.enqueue(task.id) } }, available.runnable)
                            }
                            TaskIcon("close", "删除任务", { deletingId = task.id })
                        }
                    }
                }
            }
        }
    }
    if (creating) CreateBackgroundTaskDialog(models, conversationId, filter ?: BackgroundTaskKind.BACKGROUND,
        creatingPending, storageIssue ?: message, onDismiss = { if (!creatingPending) creating = false }) {
        title, model, prompt, kind, parentId ->
        if (!creatingPending) {
            creatingPending = true
            submit {
                try {
                    engine.create(title, model, prompt, kind, parentId)
                    filter = kind
                    creating = false
                } finally {
                    creatingPending = false
                }
            }
        }
    }
    tasks.firstOrNull { it.id == selectedId }?.let { task ->
        TaskDialog(onDismiss = { selectedId = null }) {
            Text(task.title, style = MaterialTheme.typography.titleLarge)
            Text("${task.kind.label()} · ${task.model} · ${task.status.label()}",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            (storageIssue ?: message)?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            SelectionContainer {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(task.prompt)
                    if (task.progress.isNotBlank()) Text(task.progress)
                    if (task.result.isNotBlank()) Text(task.result)
                    if (task.status == BackgroundTaskStatus.FAILED) Text("执行失败")
                    if (task.status == BackgroundTaskStatus.INTERRUPTED) Text("执行已中断")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (task.status.active()) {
                    TaskIcon("stop", "停止任务", { submit { engine.stop(task.id) } },
                        enabled = task.status != BackgroundTaskStatus.STOPPING)
                } else {
                    val available = remember(engine, configuration, task.model, models) { engine.canRun(task.model) }
                    TaskIcon("play", task.status.runLabel(), { submit { engine.enqueue(task.id) } }, available.runnable)
                    if (!available.runnable) Text(available.reason.ifBlank { "模型暂不可用" },
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
                FishButton(onClick = { selectedId = null }, accent = false) { Text("关闭") }
            }
        }
    }
    tasks.firstOrNull { it.id == deletingId }?.let { task ->
        TaskDialog(onDismiss = { deletingId = null }) {
            Text("删除任务", style = MaterialTheme.typography.titleLarge)
            Text("删除「${task.title}」及其结果？")
            (storageIssue ?: message)?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FishButton(onClick = { submit { engine.delete(task.id); deletingId = null } }) { Text("删除") }
                FishButton(onClick = { deletingId = null }, accent = false) { Text("取消") }
            }
        }
    }
}

@Composable
private fun TaskDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val backdrop = LocalFloatingGlassBackdrop.current ?: LocalGlassBackdrop.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(vertical = 16.dp)) {
            val availableHeight = maxHeight
            GlassSurface(backdrop = backdrop, modifier = Modifier.fillMaxWidth(), cornerRadius = 8.dp) {
                Column(Modifier.heightIn(max = availableHeight).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
            }
        }
    }
}

@Composable
private fun RowScope.TaskFilter(label: String, selected: Boolean, onClick: () -> Unit) {
    FishButton(onClick = onClick, accent = selected,
        modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { this.selected = selected }) { Text(label) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskIcon(glyph: String, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        state = rememberTooltipState(),
        tooltip = {
            GlassSurface(cornerRadius = 8.dp) {
                Text(description, Modifier.padding(8.dp), style = MaterialTheme.typography.labelMedium)
            }
        }) {
        FishButton(onClick = onClick, enabled = enabled, accent = false,
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description }) {
            FishGlyph(glyph, Modifier.size(24.dp))
        }
    }
}

@Composable
private fun CreateBackgroundTaskDialog(
    models: List<BackgroundTaskModel>,
    conversationId: String?,
    initialKind: BackgroundTaskKind,
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onCreate: (String, String, String, BackgroundTaskKind, String?) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var modelId by remember { mutableStateOf(models.firstOrNull()?.id.orEmpty()) }
    var choosingModel by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf(initialKind) }
    val validModel = models.firstOrNull { it.id == modelId }
    val backdrop = LocalFloatingGlassBackdrop.current ?: LocalGlassBackdrop.current
    TaskDialog(onDismiss = onDismiss) {
        Text("创建任务", style = MaterialTheme.typography.titleLarge)
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FishButton(onClick = { kind = BackgroundTaskKind.BACKGROUND }, accent = kind == BackgroundTaskKind.BACKGROUND,
                enabled = !saving, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("后台") }
            FishButton(onClick = { kind = BackgroundTaskKind.SUBAGENT }, accent = kind == BackgroundTaskKind.SUBAGENT,
                enabled = !saving, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("子 Agent") }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = title, onValueChange = { if (it.length <= 80) title = it },
                label = { Text("任务名称") }, enabled = !saving, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (models.isEmpty()) OutlinedTextField(
                value = modelId, onValueChange = { if (it.length <= 128) modelId = it },
                label = { Text("模型 ID（未配置）") }, enabled = !saving,
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            ) else Box {
                FishButton(onClick = { choosingModel = true }, enabled = !saving && models.isNotEmpty(), accent = false) {
                    FishGlyph("model")
                    Text(validModel?.name ?: "选择模型", Modifier.weight(1f))
                    FishGlyph("chevrondown")
                }
                if (choosingModel) Popup(alignment = Alignment.TopStart, onDismissRequest = { choosingModel = false },
                    properties = PopupProperties(focusable = true)) {
                    GlassSurface(backdrop = backdrop, modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(), cornerRadius = 8.dp) {
                        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            models.forEach { model ->
                                FishButton(onClick = {
                                    modelId = model.id
                                    choosingModel = false
                                }, enabled = !saving, accent = modelId == model.id,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                    Text(model.name)
                                }
                            }
                        }
                    }
                }
            }
            OutlinedTextField(value = prompt, onValueChange = { if (it.length <= 32_768) prompt = it },
                label = { Text("任务内容") }, enabled = !saving,
                minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth())
        }
        FishButton(onClick = {
            onCreate(title, modelId, prompt, kind, if (kind == BackgroundTaskKind.SUBAGENT) conversationId else null)
        },
            enabled = !saving && title.isNotBlank() && prompt.isNotBlank() &&
                modelId.isNotBlank() && (models.isEmpty() || validModel != null)) { Text(if (saving) "保存中" else "创建") }
        FishButton(onClick = onDismiss, enabled = !saving, accent = false) { Text("取消") }
    }
}

private fun BackgroundTaskStatus.active(): Boolean =
    this == BackgroundTaskStatus.QUEUED || this == BackgroundTaskStatus.RUNNING || this == BackgroundTaskStatus.STOPPING

private fun BackgroundTaskStatus.runLabel(): String = when (this) {
    BackgroundTaskStatus.DRAFT -> "运行任务"
    BackgroundTaskStatus.SUCCEEDED -> "再次运行任务"
    else -> "重试任务"
}

private fun BackgroundTaskStatus.label(): String = when (this) {
    BackgroundTaskStatus.DRAFT -> "草稿"
    BackgroundTaskStatus.QUEUED -> "排队中"
    BackgroundTaskStatus.RUNNING -> "执行中"
    BackgroundTaskStatus.STOPPING -> "停止中"
    BackgroundTaskStatus.SUCCEEDED -> "已完成"
    BackgroundTaskStatus.FAILED -> "失败"
    BackgroundTaskStatus.STOPPED -> "已停止"
    BackgroundTaskStatus.INTERRUPTED -> "已中断"
}

private fun BackgroundTaskKind.label(): String = when (this) {
    BackgroundTaskKind.BACKGROUND -> "后台"
    BackgroundTaskKind.SUBAGENT -> "子 Agent"
}
