package com.dafeiyu.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dafeiyu.mobile.data.ChatMessage
import com.dafeiyu.mobile.data.DemoRepository
import com.dafeiyu.mobile.data.OfficialToolOutput
import com.dafeiyu.mobile.ui.components.FishButton
import com.dafeiyu.mobile.ui.components.FishGlyph
import com.dafeiyu.mobile.ui.components.GlassMotion
import com.dafeiyu.mobile.ui.components.LocalReduceGlassMotion
import com.dafeiyu.mobile.ui.components.LocalReduceGlassTransparency
import com.dafeiyu.mobile.ui.components.applyButtonDeformation
import com.dafeiyu.mobile.ui.components.rememberGlassButtonDeformationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun GeneratedFileStrip(
    repository: DemoRepository,
    message: ChatMessage,
    onReferenceText: ((filename: String, text: String) -> Unit)? = null,
) {
    val outputs = repository.responseFiles(message)
    if (outputs.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        outputs.forEach { file -> key(file.id) { GeneratedFilePreview(repository, file, onReferenceText) } }
    }
}

@Composable
private fun GeneratedFilePreview(
    repository: DemoRepository,
    file: OfficialToolOutput,
    onReferenceText: ((filename: String, text: String) -> Unit)?,
) {
    val context = LocalContext.current.applicationContext
    val reducedMotion = LocalReduceGlassMotion.current
    var expanded by rememberSaveable(file.id) { mutableStateOf(true) }
    var readAttempt by remember(file.id) { mutableIntStateOf(0) }
    val preview by produceState<GeneratedTextPreview>(GeneratedTextPreview.Loading, context, file, readAttempt) {
        value = GeneratedTextPreview.Loading
        value = try {
            GeneratedTextPreview.Ready(withContext(Dispatchers.IO) {
                readGeneratedFilePreview(context.noBackupFilesDir, file)
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            GeneratedTextPreview.Failed
        }
    }
    val disclosureRotation by animateFloatAsState(
        if (expanded) 180f else 0f,
        GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion),
        label = "generatedFileDisclosure",
    )
    var choosingLocation by rememberSaveable(file.id) { mutableStateOf(false) }
    val latestFile by rememberUpdatedState(file)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        choosingLocation = false
        if (uri != null) repository.exportGeneratedFile(latestFile.id, uri)
    }
    val export = {
        choosingLocation = true
        try { launcher.launch(file.filename) }
        catch (_: Exception) {
            choosingLocation = false
            repository.updateNotice("无法打开保存选择器，请重试")
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FishButton(onClick = { expanded = !expanded }, accent = false, consumeDrag = false,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                .semantics { contentDescription = "${if (expanded) "收起" else "预览"} ${file.filename}" }) {
            FishGlyph("document", Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(file.filename, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text("${file.sizeBytes} B · TXT", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            FishGlyph("chevrondown", Modifier.size(20.dp).rotate(disclosureRotation))
        }
        GeneratedFileMoreActions(!choosingLocation, export)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(if (reducedMotion) 0 else GlassMotion.MorphMillis,
                easing = GlassMotion.EaseOut)) + fadeIn(GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion)),
            exit = shrinkVertically(tween(if (reducedMotion) 0 else GlassMotion.MorphMillis,
                easing = GlassMotion.EaseOut)) + fadeOut(GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion)),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (val state = preview) {
                    GeneratedTextPreview.Loading -> Text("正在读取文本…",
                        modifier = Modifier.padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    GeneratedTextPreview.Failed -> {
                        Text("文本读取失败，文件可能已移除或损坏。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                        FishButton(onClick = { readAttempt++ }, accent = false, consumeDrag = false) { Text("重新读取") }
                    }
                    is GeneratedTextPreview.Ready -> {
                        if (state.text.isEmpty()) Text("空文本文件",
                            modifier = Modifier.padding(vertical = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else MessageSelectionContainer(
                            onReferenceText = onReferenceText?.let { reference ->
                                { selected -> reference(file.filename, selected) }
                            },
                            onExport = export, exportEnabled = !choosingLocation) {
                            Text(state.text, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GeneratedFileMoreActions(enabled: Boolean, onExport: () -> Unit) {
    val deformation = rememberGlassButtonDeformationState()
    var expanded by remember { mutableStateOf(false) }
    var exportAfterCollapse by remember { mutableStateOf(false) }
    val state = rememberModelMenuMorphState(expanded)
    Box(Modifier.size(48.dp)) {
        IconButton(onClick = { expanded = true }, enabled = !state.surfaceVisible,
            modifier = Modifier.fillMaxSize().applyButtonDeformation(state = deformation,
                circularHit = true, consumeDrag = false, drawShadow = false)
                .onGloballyPositioned { state.anchorBounds = it.boundsInWindow() }
                .graphicsLayer { alpha = state.launcherAlpha }
                .semantics { contentDescription = "文件更多操作" }) {
            FishGlyph("more", Modifier.size(19.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnchoredGlassMenu(expanded, state, onDismiss = { expanded = false },
            menuWidth = 248.dp, menuHeight = 152.dp,
            reduceTransparency = LocalReduceGlassTransparency.current,
            onClosed = { if (exportAfterCollapse) { exportAfterCollapse = false; onExport() } },
            sourceContent = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                FishGlyph("more", Modifier.size(19.dp))
            } }) {
            FishButton(onClick = { exportAfterCollapse = true; expanded = false }, enabled = enabled,
                accent = false, consumeDrag = false,
                modifier = Modifier.fillMaxWidth().padding(10.dp)
                    .semantics { contentDescription = "导出文本文件" }) {
                FishGlyph("download", Modifier.size(18.dp))
                Text("导出文本文件", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
