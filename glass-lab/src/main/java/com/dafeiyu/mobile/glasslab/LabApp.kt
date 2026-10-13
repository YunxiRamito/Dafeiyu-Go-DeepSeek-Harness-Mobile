package com.dafeiyu.mobile.glasslab

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.dafeiyu.mobile.ui.components.GlassMotion
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LabApp(lab: LabState) {
    val dark = lab.document.selectedTheme == "dark"
    val colors = if (dark) darkColorScheme(primary = Color(0xFF94B5FF), background = Color(0xFF111218),
        surface = Color(0xFF1C1E27), surfaceContainer = Color(0xFF242630))
    else lightColorScheme(primary = Color(0xFF386CE4), background = Color(0xFFF4F5F9),
        surface = Color.White, surfaceContainer = Color(0xFFEDEFF5))
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            if (lab.isLoading) Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
                Text("正在恢复上次的参数", Modifier.padding(16.dp))
            } else LabScreen(lab)
        }
    }
}

@Composable
private fun LabScreen(lab: LabState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val doc = lab.document
    val p = doc.current()
    var group by remember { mutableStateOf("阴影") }
    var previewKind by remember { mutableStateOf("single") }
    var focused by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf(false) }
    var playId by remember { mutableIntStateOf(0) }
    var fullscreen by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }
    var showColors by remember { mutableStateOf(false) }
    var showBackgrounds by remember { mutableStateOf(false) }
    var backgroundColorEdit by remember { mutableStateOf(false) }
    var colorKey by remember { mutableStateOf("surface") }
    var resetAll by remember { mutableStateOf(false) }
    var numeric by remember { mutableStateOf<LabParameterSpec?>(null) }
    var exportSnapshot by rememberSaveable { mutableStateOf<String?>(null) }
    var importingPhoto by remember { mutableStateOf(false) }
    var savingTheme by remember { mutableStateOf(false) }
    fun saveAndApplyTheme() {
        if (savingTheme) return
        savingTheme = true
        val snapshot = lab.document.snapshot()
        scope.launch {
            try {
                lab.persistNow()
                lab.awaitPersisted()
                val file = withContext(Dispatchers.IO) {
                    val exports = File(context.cacheDir, "exports").apply { mkdirs() }
                    File(exports, "Dafeiyu-Theme-${UUID.randomUUID()}.json")
                        .also { it.writeText(snapshot.encode(), Charsets.UTF_8) }
                }
                val preferred = if (context.packageName.endsWith(".dev")) "com.dafeiyu.mobile.dev" else "com.dafeiyu.mobile"
                val target = listOf(preferred, "com.dafeiyu.mobile").distinct().firstOrNull { packageName ->
                    runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess
                }
                if (target == null) {
                    lab.notice("主题已保存。请先安装新版 Dafeiyu-GO，或从更多菜单导出主题文件。")
                    showTools = true
                } else {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        component = ComponentName(target, "com.dafeiyu.mobile.MainActivity")
                        setDataAndType(uri, "application/vnd.dafeiyu.theme+json")
                        clipData = ClipData.newRawUri("Dafeiyu theme", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        putExtra("dafeiyu.theme.apply", true)
                    }
                    context.startActivity(intent)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lab.notice("主题已保留，应用失败：${error.message?.take(160)}")
            } finally {
                savingTheme = false
            }
        }
    }
    val referenceAvailable = remember(context) { LabBackgroundImages.hasReference(context) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && !importingPhoto) {
            importingPhoto = true
            scope.launch {
                try {
                    lab.notice("正在准备预览图片")
                    val photo = LabBackgroundImages.importPhoto(context, uri)
                    lab.update(lab.document.copy(previewBackgroundImage = "photo",
                        previewBackgroundImageData = photo.data, previewBackgroundLabel = "相册图片"))
                    lab.notice("预览图片已保存，下次打开会继续使用")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    lab.notice("图片读取失败：${error.message}")
                } finally {
                    importingPhoto = false
                }
            }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = exportSnapshot
        exportSnapshot = null
        if (uri != null && text != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
                    ?: error("无法打开所选文件")
            } }.onSuccess { lab.notice("参数文件已保存，可以发给我") }.onFailure { lab.notice("导出失败：${it.message}") }
        }
        else if (uri != null) lab.notice("导出内容未能恢复，请重新保存参数文件")
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri) ?: error("无法打开文件")
                    input.use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val bytes = ByteArray(8192)
                        while (true) {
                            val read = stream.read(bytes)
                            if (read < 0) break
                            if (output.size() + read > LabDocument.MAX_IMPORT_BYTES) error("文件不可超过 256 KiB")
                            output.write(bytes, 0, read)
                        }
                        output.toString("UTF-8")
                    }
                }
                lab.import(text)
            }.onFailure { lab.notice("导入失败：${it.message}") }
        }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Dafeiyu调色盘", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("拖动即预览 · 自动保存", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            LabToolIcon(if (doc.selectedTheme == "dark") R.drawable.sf_moon else R.drawable.sf_sun, "切换浅色与暗色") {
                lab.update(doc.copy(selectedTheme = if (doc.selectedTheme == "dark") "light" else "dark"))
            }
            LabToolIcon(R.drawable.sf_save, if (savingTheme) "正在保存主题" else "保存并应用到 Dafeiyu-GO") { saveAndApplyTheme() }
            LabToolIcon(R.drawable.sf_more, "方案与导出") { showTools = true }
        }
        ChoiceRow(LabParameters.types, doc.selectedType, LabTypeNames, "类型") { lab.update(doc.copy(selectedType = it)) }
        Box(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            val previewHeight = when {
                doc.selectedType == "panel" -> (p["heightDp"] + 54).coerceIn(240f, 355f)
                else -> 242f
            }
            LabPreview(doc, previewKind, playId, focused, hovered, Modifier.fillMaxWidth().height(previewHeight.dp)
                .testTag("material-preview"))
            TextButton(onClick = { fullscreen = true }, Modifier.align(Alignment.TopEnd).padding(4.dp)) { Text("放大") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showBackgrounds = true }, modifier = Modifier.testTag("preview-backgrounds")) {
                Text("预览背景", fontSize = 12.sp)
            }
            Text(doc.previewBackgroundLabel.ifBlank { "自定义底图" }, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
            TextButton(onClick = { backgroundColorEdit = true; colorKey = "background"; showColors = true },
                modifier = Modifier.testTag("preview-palette")) { Text("调色盘", fontSize = 12.sp) }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Row {
                TextButton(onClick = { playId++ }, Modifier.testTag("play-interaction")) { Text("播放交互") }
                TextButton(onClick = { previewKind = if (previewKind == "pair") "single" else "pair" },
                    Modifier.testTag("toggle-pair")) { Text(if (previewKind == "pair") "单个" else "粘连") }
                TextButton(onClick = { lab.update(doc.copy(compare = !doc.compare)); previewKind = "single" }) {
                    Text(if (doc.compare) "退出对照" else "对照")
                }
            }
            Row {
                TextButton(onClick = { lab.undo() }, enabled = lab.canUndo, modifier = Modifier.testTag("undo")) { Text("撤销") }
                TextButton(onClick = { lab.redo() }, enabled = lab.canRedo, modifier = Modifier.testTag("redo")) { Text("重做") }
            }
        }
        ChoiceRow(listOf("阴影", "玻璃", "反射与高光", "描边与颜色", "尺寸与内容", "按压与拖拽",
            "粘连", "图标与文字效果", "表面纹理", "交互状态", "场景", "颜色", "状态"), group, emptyMap(), "分组") { group = it }
        AnimatedVisibility(lab.message != null, enter = fadeIn(tween(GlassMotion.PressMillis, easing = GlassMotion.EaseOut)),
            exit = fadeOut(tween(GlassMotion.PressMillis, easing = GlassMotion.EaseOut))) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().clickable { lab.notice(null) }.padding(vertical = 4.dp)) {
                Text(lab.message.orEmpty(), Modifier.padding(10.dp), fontSize = 12.sp, maxLines = 3)
            }
        }
        AnimatedContent(group, modifier = Modifier.weight(1f).fillMaxWidth(), label = "labParameterGroup",
            transitionSpec = { fadeIn(tween(GlassMotion.PressMillis, easing = GlassMotion.EaseOut)) togetherWith
                fadeOut(tween(GlassMotion.PressMillis, easing = GlassMotion.EaseOut)) }) { selected ->
            LazyColumn(Modifier.fillMaxSize().testTag("parameter-list"), contentPadding = PaddingValues(top = 6.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(selected, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            var next = p
                            LabParameters.specs.filter { it.group == selected }.forEach { spec ->
                                next = next.withValue(spec.key, LabParameters.defaults(doc.selectedType, doc.selectedTheme == "dark")[spec.key])
                            }
                            lab.parameters(next)
                        }) { Text("重置本组", fontSize = 12.sp) }
                    }
                }
                if (selected == "颜色") {
                    items(LabParameters.colorKeys) { key ->
                        val displayColor = if (doc.previewBackgroundColors.isNotEmpty() && (key == "background" || key == "backgroundAccent"))
                            if (key == "background") doc.previewBackgroundColors.first() else doc.previewBackgroundColors.last()
                            else p.colors.getValue(key)
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                            colorKey = key; backgroundColorEdit = key == "background" || key == "backgroundAccent"; showColors = true
                        }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(28.dp).clip(CircleShape).background(Color(android.graphics.Color.parseColor(displayColor))))
                            Text(ColorNames.getValue(key), Modifier.padding(start = 12.dp).weight(1f))
                            Text(displayColor, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else if (selected == "状态") {
                    item { LabToggle("液态玻璃", p.glassEnabled) { lab.parameters(p.copy(glassEnabled = it)) } }
                    item { LabToggle("动态效果", p.motionEnabled) { lab.parameters(p.copy(motionEnabled = it)) } }
                    item { LabToggle("按钮 Q 弹", p.springEnabled, p.motionEnabled) { lab.parameters(p.copy(springEnabled = it)) } }
                    item { LabToggle("禁用状态预览", p.disabled) { lab.parameters(p.copy(disabled = it)) } }
                    item { LabToggle("聚焦状态预览", focused) { focused = it } }
                    item { LabToggle("悬停状态预览", hovered) { hovered = it } }
                    item { LabToggle("背景缓慢运动", doc.animateBackground, p.motionEnabled) { lab.update(doc.copy(animateBackground = it)) } }
                    item { Text("所有交互只作用于实验台。材质关闭会显示不透明替代样式；动态关闭会停止形变、光学按压和背景动画。",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp)) }
                } else {
                    if (selected == "场景") {
                        item { ChoiceRow(LabDocument.backgroundPresets, doc.backgroundPreset, BackgroundNames, "背景") {
                            lab.update(doc.copy(backgroundPreset = it, previewBackgroundImage = "none",
                                previewBackgroundImageData = "", previewBackgroundLabel =
                                    if (doc.previewBackgroundImage != "none") "" else doc.previewBackgroundLabel)) } }
                        item { ChoiceRow(listOf("single", "page", "pair"), previewKind,
                            mapOf("single" to "单组件", "page" to "页面", "pair" to "双按钮"), "场景模式") { previewKind = it } }
                    }
                    if (selected == "尺寸与内容") item {
                        ChoiceRow(LabParameters.shapes, p.shape, mapOf("rounded" to "圆角", "pill" to "胶囊", "circle" to "正圆", "rectangle" to "直角"), "形状") {
                            lab.parameters(p.copy(shape = it).withValue("roundness", when (it) {
                                "rectangle" -> 0f
                                "circle", "pill" -> 1f
                                else -> LabParameters.defaults(doc.selectedType, doc.selectedTheme == "dark")["roundness"]
                            })) }
                    }
                    if (selected == "图标与文字效果") {
                        item { ChoiceRow(LabParameters.icons, p.icon, IconNames, "图标") { lab.parameters(p.copy(icon = it)) } }
                        item { ChoiceRow(LabParameters.blendModes, p.iconBlend, BlendNames, "混合") { lab.parameters(p.copy(iconBlend = it)) } }
                    }
                    if (selected == "按压与拖拽" || selected == "反射与高光") {
                        item { ChoiceRow(LabParameters.pressEffects, p.pressEffect,
                            mapOf("none" to "无", "darken" to "变深", "glow" to "柔光", "beam" to "追光", "lift" to "抬升"), "按压效果") {
                                lab.parameters(p.copy(pressEffect = it))
                            } }
                    }
                    // Legacy fields remain readable in JSON. Their new controls are roundness and
                    // bridgeReachDp, so there is one effective slider for each visual property.
                    items(LabParameters.specs.filter { it.group == selected &&
                        it.key !in setOf("cornerDp", "fusionDistanceDp") }, key = { it.key }) { spec ->
                        LabSlider(spec, p[spec.key], onChange = { lab.value(spec.key, it) },
                            onBegin = lab::beginGesture, onFinish = lab::endGesture,
                            onPrecise = { numeric = spec }, onReset = {
                                lab.value(spec.key, LabParameters.defaults(doc.selectedType, doc.selectedTheme == "dark")[spec.key])
                            })
                    }
                }
            }
        }
    }
    if (showTools) AlertDialog(onDismissRequest = { showTools = false }, title = { Text("保存你的设计") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("浅色、暗色及四种组件会一起保存。可直接应用到 Dafeiyu-GO，也可分享完整 JSON。" +
                if (doc.previewBackgroundImage == "photo") "参数文件也包含你选中的预览图片。" else "", fontSize = 13.sp)
            TextButton(onClick = { showTools = false; showPresets = true }, Modifier.fillMaxWidth()) { Text("命名方案与预设") }
            TextButton(onClick = { showTools = false; saveAndApplyTheme() }, Modifier.fillMaxWidth()) { Text("保存并应用到 Dafeiyu-GO") }
            TextButton(onClick = { exportSnapshot = lab.document.encode(); showTools = false; exporter.launch("Dafeiyu-Glass-Profile.json") },
                Modifier.fillMaxWidth().testTag("export-json")) { Text("保存参数文件") }
            TextButton(onClick = {
                val snapshot = lab.document.encode()
                showTools = false
                scope.launch { runCatching {
                    val uri = withContext(Dispatchers.IO) {
                        val root = File(context.cacheDir, "exports").apply { mkdirs() }
                        // Each granted URI keeps its original snapshot, even after another share.
                        root.listFiles()?.filter { it.isFile && it.lastModified() < System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000 }
                            ?.forEach { it.delete() }
                        val file = File(root, "Dafeiyu-Glass-Profile-${UUID.randomUUID()}.json").apply { writeText(snapshot, Charsets.UTF_8) }
                        FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
                    }
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"; putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("材质参数", uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "分享材质参数"))
                }.onFailure { lab.notice("分享失败：${it.message}") } }
            }, Modifier.fillMaxWidth().testTag("share-json")) { Text("分享参数文件") }
            TextButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Dafeiyu 材质参数", lab.document.encode()))
                showTools = false; lab.notice("JSON 已复制")
            }, Modifier.fillMaxWidth().testTag("copy-json")) { Text("复制 JSON") }
            TextButton(onClick = { showTools = false; importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                Modifier.fillMaxWidth()) { Text("导入参数文件") }
            TextButton(onClick = { lab.resetCurrent(); showTools = false; lab.notice("已重置当前主题的当前组件；可以撤销") }, Modifier.fillMaxWidth()) { Text("重置当前组件") }
            TextButton(onClick = { showTools = false; resetAll = true }, Modifier.fillMaxWidth()) { Text("重置全部", color = MaterialTheme.colorScheme.error) }
            Text("${LabDocument.VERSION} · ${LabParameters.specs.size} 项滑条 · ${LabParameters.colorKeys.size} 项颜色\n" +
                "当前设备 API ${Build.VERSION.SDK_INT}：${if (Build.VERSION.SDK_INT >= 33) "真实折射 / 色散 / 双按钮融合" else if (Build.VERSION.SDK_INT >= 31) "模糊降级，光学不可用" else "底图采样降级，模糊与光学不可用"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        } }, confirmButton = { TextButton(onClick = { showTools = false }) { Text("关闭") } })
    if (showColors) ColorDialog(lab, colorKey, previewBackground = backgroundColorEdit,
        onDismiss = { lab.endGesture(); showColors = false })
    if (showBackgrounds) LabBackgroundDialog(lab, onDismiss = { showBackgrounds = false },
        referenceAvailable = referenceAvailable, importingPhoto = importingPhoto,
        onPhoto = { showBackgrounds = false; photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onPalette = { showBackgrounds = false; backgroundColorEdit = true; colorKey = "background"; showColors = true })
    if (showPresets) PresetDialog(lab, onDismiss = { showPresets = false })
    if (resetAll) AlertDialog(onDismissRequest = { resetAll = false }, title = { Text("重置全部参数？") },
        text = { Text("两种主题和四种组件恢复默认值，命名方案保留。重置后可以撤销。") },
        confirmButton = { TextButton(onClick = { lab.resetAll(); resetAll = false }) { Text("重置") } },
        dismissButton = { TextButton(onClick = { resetAll = false }) { Text("取消") } })
    numeric?.let { spec -> NumericDialog(spec, p[spec.key], onDismiss = { numeric = null }) {
        lab.value(spec.key, it); numeric = null } }
    if (fullscreen) Dialog(onDismissRequest = { fullscreen = false }) {
        Surface(shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                LabPreview(lab.document, previewKind, playId, focused, hovered,
                    Modifier.fillMaxWidth().height(420.dp))
                Row { TextButton(onClick = { playId++ }) { Text("播放交互") }; TextButton(onClick = { fullscreen = false }) { Text("返回调节") } }
            }
        }
    }
}

@Composable
internal fun ChoiceRow(keys: List<String>, selected: String, labels: Map<String, String>, tag: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        keys.forEach { key -> FilterChip(selected = selected == key, onClick = { onSelect(key) },
            label = { Text(labels[key] ?: key, fontSize = 12.sp) }, modifier = Modifier.testTag("$tag-$key")) }
    }
}

@Composable
private fun LabToolIcon(icon: Int, label: String, onClick: () -> Unit) {
    IconButton(onClick, Modifier.semantics { contentDescription = label }) {
        Icon(painterResource(icon), label, Modifier.size(23.dp))
    }
}

@Composable
private fun LabToggle(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked, onChange, enabled = enabled, modifier = Modifier.testTag("toggle-$label"))
    }
}

@Composable
private fun LabSlider(spec: LabParameterSpec, value: Float, onChange: (Float) -> Unit, onBegin: () -> Unit,
    onFinish: () -> Unit, onPrecise: () -> Unit, onReset: () -> Unit) {
    var inGesture by remember(spec.key) { mutableStateOf(false) }
    DisposableEffect(spec.key) { onDispose { if (inGesture) onFinish() } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(spec.label, Modifier.weight(1f), fontSize = 13.sp)
            TextButton(onClick = onPrecise, contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.testTag("value-${spec.key}")) { Text(formatValue(value, spec.unit), fontSize = 13.sp) }
            TextButton(onClick = onReset, contentPadding = PaddingValues(4.dp), modifier = Modifier.sizeIn(minWidth = 40.dp)) { Text("复位", fontSize = 11.sp) }
        }
        Slider(value, onValueChange = {
            if (!inGesture) { onBegin(); inGesture = true }
            onChange(it)
        }, valueRange = spec.min..spec.max, onValueChangeFinished = { inGesture = false; onFinish() },
            modifier = Modifier.fillMaxWidth().height(28.dp).testTag("slider-${spec.key}"))
    }
}

private fun formatValue(value: Float, unit: String): String = when (unit) {
    "ms" -> "${value.toInt()} ms"
    "°" -> "${String.format(Locale.ROOT, "%.1f", value)}°"
    else -> String.format(Locale.ROOT, if (unit == "dp" || unit == "sp") "%.1f %s" else "%.3f %s", value, unit)
}

@Composable
private fun NumericDialog(spec: LabParameterSpec, value: Float, onDismiss: () -> Unit, onApply: (Float) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    val number = text.toFloatOrNull()?.takeIf { it.isFinite() && it in spec.min..spec.max }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(spec.label) }, text = {
        Column { OutlinedTextField(text, { text = it }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), label = { Text("精确数值") },
            modifier = Modifier.testTag("precise-input")); Text("${spec.min} – ${spec.max} ${spec.unit}", fontSize = 12.sp) }
    }, confirmButton = { TextButton(enabled = number != null, onClick = { number?.let(onApply) }) { Text("应用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

internal val ColorNames = mapOf("surface" to "表面底色", "icon" to "图标", "text" to "文字", "highlight" to "光学高光",
    "border" to "玻璃描边", "shadow" to "外阴影", "background" to "预览背景底色", "backgroundAccent" to "预览背景第二色",
    "contentOutline" to "图标／文字描边", "contentHighlight" to "图标／文字高光", "innerShadow" to "内阴影",
    "sheen" to "表面光泽", "focus" to "聚焦描边", "hover" to "悬停底色", "pressed" to "按下底色", "trackingLight" to "追光颜色")
private val BackgroundNames = mapOf("gradient" to "渐变", "solid" to "纯色", "checker" to "棋盘", "stripes" to "条纹", "text" to "文字", "aurora" to "极光")
private val IconNames = mapOf("add" to "加号", "send" to "发送", "settings" to "设置", "folder" to "文件夹", "agent" to "子 Agent", "archive" to "归档", "trash" to "删除", "shield" to "沙箱")
private val BlendNames = mapOf("srcOver" to "正常", "multiply" to "正片叠底", "screen" to "滤色", "overlay" to "叠加", "difference" to "差值", "plus" to "加亮", "srcIn" to "源内")

@Composable
private fun ColorDialog(lab: LabState, initialKey: String, previewBackground: Boolean = false, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf(initialKey) }
    val p = lab.document.current()
    val backgroundColors = lab.document.previewBackgroundColors.ifEmpty { listOf(p.colors.getValue("background"), p.colors.getValue("backgroundAccent")) }
    val colorCode = if (previewBackground) backgroundColors[if (key == "background") 0 else backgroundColors.lastIndex] else p.colors.getValue(key)
    val c = Color(android.graphics.Color.parseColor(colorCode))
    var hex by remember(key, colorCode) { mutableStateOf(colorCode) }
    fun applyColor(value: String) {
        val normalized = p.withColor(key, value).colors.getValue(key)
        if (previewBackground) {
            val colors = (if (key != "background" && backgroundColors.size == 1)
                backgroundColors + backgroundColors else backgroundColors).toMutableList()
            val index = if (key == "background") 0 else colors.lastIndex
            colors[index] = normalized
            lab.update(lab.document.copy(previewBackgroundColors = colors,
                previewBackgroundLabel = "自定义颜色", backgroundPreset = if (colors.size == 1) "solid" else "gradient",
                previewBackgroundImage = "none", previewBackgroundImageData = ""))
        } else lab.color(key, normalized)
    }
    fun channel(index: Int, value: Float) {
        val old = android.graphics.Color.parseColor(colorCode)
        val channels = intArrayOf(android.graphics.Color.alpha(old), android.graphics.Color.red(old),
            android.graphics.Color.green(old), android.graphics.Color.blue(old))
        channels[index] = value.toInt().coerceIn(0, 255)
        applyColor(String.format(Locale.ROOT, "#%02X%02X%02X%02X", *channels.toTypedArray()))
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (previewBackground) "预览背景调色盘" else "颜色与透明度") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            ChoiceRow(if (previewBackground) listOf("background", "backgroundAccent") else LabParameters.colorKeys,
                key, if (previewBackground) mapOf("background" to "起始颜色", "backgroundAccent" to "结束颜色") else ColorNames, "颜色项") { key = it }
            Box(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).background(c))
            LabColorSpectrum(c, onBegin = lab::beginGesture, onEnd = lab::endGesture) { applyColor(it) }
            val channels = listOf("不透明度" to c.alpha * 255f, "红" to c.red * 255f, "绿" to c.green * 255f, "蓝" to c.blue * 255f)
            channels.forEachIndexed { index, (label, value) ->
                Text("$label  ${value.toInt()}", fontSize = 12.sp)
                Slider(value, onValueChange = { lab.beginGesture(); channel(index, it) }, onValueChangeFinished = lab::endGesture,
                    valueRange = 0f..255f, modifier = Modifier.testTag("color-channel-$index"))
            }
            OutlinedTextField(hex, { hex = it }, label = { Text("#AARRGGBB / #RRGGBB") }, singleLine = true)
            TextButton(onClick = { runCatching { applyColor(hex) }.onFailure { lab.notice("颜色无效：${it.message}") } }) { Text("应用颜色代码") }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("#FFFFFFFF", "#FF000000", "#FF3878ED", "#FFFFC247", "#FFFF5B62", "#FF77DDB2").forEach { swatch ->
                    Box(Modifier.size(32.dp).clip(CircleShape).background(Color(android.graphics.Color.parseColor(swatch))).clickable { applyColor(swatch) })
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { lab.endGesture(); onDismiss() }) { Text("完成") } })
}

@Composable
private fun PresetDialog(lab: LabState, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var presets by remember { mutableStateOf(lab.store.listPresets()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("你的方案") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(name, { name = it }, label = { Text("方案名称") }, singleLine = true)
            TextButton(enabled = name.isNotBlank(), onClick = { scope.launch {
                runCatching { withContext(Dispatchers.IO) { lab.store.savePreset(name, lab.document.snapshot()) } }
                    .onSuccess { presets = lab.store.listPresets(); name = ""; lab.notice("方案已保存") }
                    .onFailure { lab.notice("保存失败：${it.message}") }
            } }) { Text("保存当前全部参数") }
            Text("快速起点", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            ChoiceRow(listOf("default", "clear", "soft", "frosted", "quiet"), "", mapOf("default" to "默认", "clear" to "清透",
                "soft" to "柔光", "frosted" to "磨砂", "quiet" to "安静"), "预设") { preset ->
                var p = LabParameters.defaults(lab.document.selectedType, lab.document.selectedTheme == "dark")
                p = when (preset) {
                    "clear" -> p.withValue("blurDp", 0f).withValue("tintAlpha", .05f).withValue("shadowOpacity", .035f)
                    "soft" -> p.withValue("blurDp", 5f).withValue("reflectionStrength", .4f).withValue("shadowBlurDp", 4f).withValue("shadowOpacity", .045f)
                    "frosted" -> p.withValue("blurDp", 20f).withValue("tintAlpha", .24f).withValue("refractionScale", .2f).withValue("grainAlpha", .025f)
                    "quiet" -> p.withValue("reflectionStrength", .15f).withValue("rimStrength", .02f).withValue("shadowOpacity", .025f).withValue("pressScale", 1f)
                    else -> p
                }
                lab.parameters(p); onDismiss()
            }
            if (presets.isEmpty()) Text("尚未保存命名方案", fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp))
            presets.forEach { preset ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { lab.store.loadPreset(preset.id)?.let { lab.update(it); lab.notice("已载入 ${preset.name}"); onDismiss() }
                        ?: lab.notice(lab.store.lastError ?: "方案不存在") }, modifier = Modifier.weight(1f)) { Text(preset.name) }
                    TextButton(onClick = { runCatching { lab.store.deletePreset(preset.id); presets = lab.store.listPresets() }
                        .onFailure { lab.notice("删除失败：${it.message}") } }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}
