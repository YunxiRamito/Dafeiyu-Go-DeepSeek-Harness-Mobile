package com.dafeiyu.mobile.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dafeiyu.mobile.data.DemoRepository
import com.dafeiyu.mobile.data.ExtensionItem
import com.dafeiyu.mobile.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.util.Locale
import android.provider.OpenableColumns

private const val EXTENSION_PREFERENCES = "dafeiyu_extensions"
private const val ENABLED_KEY_PREFIX = "enabled:"
private const val LOCAL_EXTENSION_ID_PREFIX = "local-"
private const val MAX_DESCRIPTOR_BYTES = 128 * 1024L
private const val IMPORTED_EXTENSION_DIRECTORY = "extensions"
private const val IMPORTED_SKILL_DIRECTORY = "skills"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(repository: DemoRepository) {
    var category by rememberSaveable { mutableStateOf("plugin") }
    var query by rememberSaveable { mutableStateOf("") }
    var installedOnly by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var showImport by remember { mutableStateOf(false) }
    var importUrl by rememberSaveable { mutableStateOf("") }
    var importMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var importMessageIsError by rememberSaveable { mutableStateOf(false) }
    var scanRevision by remember { mutableIntStateOf(0) }
    var extensionToDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var extensionDeleting by remember { mutableStateOf(false) }
    var scanResult by remember { mutableStateOf(ExtensionScanResult()) }
    var scanInProgress by remember { mutableStateOf(true) }
    var enabledRevision by remember { mutableIntStateOf(0) }
    val connectionPlugin = rememberConnectionPluginUiState(scanRevision)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember(context) {
        context.applicationContext.getSharedPreferences(EXTENSION_PREFERENCES, Context.MODE_PRIVATE)
    }
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    val collapseRange = with(LocalDensity.current) { 112.dp.toPx() }
    val collapse by remember(listState, collapseRange) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / collapseRange.coerceAtLeast(1f)).coerceIn(0f, 1f)
        }
    }
    val localFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val categoryAtLaunch = category
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                importLocalExtension(context.applicationContext, uri, categoryAtLaunch)
            }
            result.onSuccess { name ->
                importMessage = "已导入$name；内容已复制到应用私有目录，不会执行脚本。"
                importMessageIsError = false
                showImport = false
                importUrl = ""
                scanRevision++
                repository.updateNotice("已导入$name · 仅本地前端目录")
            }.onFailure { error ->
                importMessage = "导入失败：${error.message ?: "无法读取所选文件"}"
                importMessageIsError = true
            }
        }
    }

    LaunchedEffect(context, scanRevision) {
        scanInProgress = true
        try {
            scanResult = withContext(Dispatchers.IO) {
                scanPrivateExtensions(context.applicationContext)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            scanResult = ExtensionScanResult(
                readErrors = listOf("目录扫描：${error.message ?: "发生未预期的读取错误"}"),
            )
        }
        scanInProgress = false
    }
    // Publish outside item(0): that header leaves composition after scrolling away.
    PublishSettingsNavigation("插件与技能", collapse,
        if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else null)

    val localPlugins = scanResult.entries.map { it.item }.filter { it.category == "plugin" }
    val localSkills = scanResult.entries.map { it.item }.filter { it.category == "skill" }
    val allItems = buildList {
        addAll(if (localPlugins.isEmpty()) repository.extensions.filter { it.category == "plugin" } else localPlugins)
        addAll(if (localSkills.isEmpty()) repository.extensions.filter { it.category == "skill" } else localSkills)
    }.map { item ->
        // Reading the revision makes preference changes invalidate this derived list.
        enabledRevision
        item.copy(enabled = if (item.installed) {
            preferences.getBoolean(enabledPreferenceKey(item.id), item.enabled)
        } else item.enabled)
    }
    val selected = allItems.firstOrNull { it.id == selectedId }
    val selectedLocalEntry = selected?.let { item -> scanResult.entries.firstOrNull { it.item.id == item.id } }
    val visible = allItems.filter {
        it.category == category && (!installedOnly || it.installed) &&
            (query.isBlank() || (it.name + it.subtitle + it.description).contains(query, ignoreCase = true))
    }
    val showConnectionPlugin = category == "plugin" && connectionPlugin.matches(query) &&
        (!installedOnly || connectionPlugin.currentVersion != null || !connectionPlugin.hasLoaded || connectionPlugin.readFailed)

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 22.dp), state = listState,
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            PageHeader("插件与技能", "为你的助手，添上趁手的工具。", action = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { scanRevision++ },
                        enabled = !scanInProgress,
                        modifier = Modifier.applyButtonDeformation(circularHit = true)
                            .semantics { contentDescription = "刷新插件和技能目录" },
                    ) {
                        if (scanInProgress) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                        else Text("↻", fontSize = 21.sp, color = colors.onSurface)
                    }
                    IconButton(onClick = { showImport = true }, modifier = Modifier.applyButtonDeformation(circularHit = true).semantics { contentDescription = "添加插件或技能" }) {
                        FishGlyph("add")
                    }
                }
            }, collapseProgress = collapse)
            importMessage?.let { message ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = if (importMessageIsError) colors.errorContainer else colors.primary.copy(alpha = .10f),
                    contentColor = if (importMessageIsError) colors.onErrorContainer else colors.onSurface,
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        FishGlyph(if (importMessageIsError) "close" else "check", Modifier.size(18.dp))
                        Text(message, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 18.sp)
                        IconButton(
                            onClick = { importMessage = null },
                            modifier = Modifier.size(30.dp).applyButtonDeformation(circularHit = true),
                        ) { FishGlyph("close", Modifier.size(15.dp)) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.Bottom) {
                listOf("plugin" to "插件", "skill" to "技能").forEach { (key, label) ->
                    Column(Modifier.applyButtonDeformation(consumeDrag = false).clip(RoundedCornerShape(4.dp)).clickable { category = key }.padding(vertical = 6.dp)) {
                        Text(label, fontSize = 26.sp, fontWeight = FontWeight.Normal,
                            color = if (category == key) colors.onSurface else colors.onSurfaceVariant.copy(alpha = .6f))
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.width(27.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
                            .background(if (category == key) colors.primary else colors.background))
                    }
                }
            }
            Spacer(Modifier.height(19.dp))
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                placeholder = { Text(if (category == "plugin") "搜索插件" else "搜索技能", fontSize = 14.sp) },
                leadingIcon = { FishGlyph("search", Modifier.size(18.dp), colors.onSurfaceVariant) },
                trailingIcon = { if (query.isNotBlank()) IconButton(onClick = { query = "" },
                    modifier = Modifier.applyButtonDeformation(circularHit = true).semantics { contentDescription = "清除搜索" }) { FishGlyph("close", Modifier.size(17.dp)) } })
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (installedOnly) "已添加" else "为你精选", fontSize = 13.sp,
                    fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                FilterChip(selected = installedOnly, onClick = { installedOnly = !installedOnly },
                    modifier = Modifier.applyButtonDeformation(consumeDrag = false), label = { Text("只看已添加", fontSize = 11.sp) })
            }
            Spacer(Modifier.height(10.dp))
            ExtensionScanStatusCard(
                result = scanResult,
                scanning = scanInProgress,
                category = category,
                onRefresh = { scanRevision++ },
            )
        }
        if (showConnectionPlugin) item(key = "connection-plugin-package") {
            ConnectionPluginSection(connectionPlugin)
        }
        items(visible, key = { it.id }) { item ->
            FishCard(onClick = { selectedId = item.id }) {
                Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    Box(Modifier.size(43.dp).clip(RoundedCornerShape(4.dp))
                        .background(colors.primary.copy(alpha = .06f)), contentAlignment = Alignment.Center) {
                        FishGlyph(if (item.category == "skill") "book" else when (item.id) {
                            "token-stats" -> "grid"
                            "web-mobile" -> "chat"
                            else -> "plug"
                        }, Modifier.size(23.dp), colors.primary)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(item.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.subtitle, fontSize = 12.sp, lineHeight = 18.sp,
                            color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (item.installed) TinyLabel(if (item.enabled) "已启用" else "已停用", primary = item.enabled)
                    }
                    FishGlyph("chevron", Modifier.size(16.dp), colors.onSurfaceVariant)
                }
            }
        }
        if (visible.isEmpty() && !showConnectionPlugin) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                FishGlyph("search", Modifier.size(30.dp), colors.onSurfaceVariant)
                Text(if (query.isBlank()) "还没有添加${if (category == "plugin") "插件" else "技能"}"
                else "没有找到“$query”", fontSize = 14.sp, modifier = Modifier.padding(top = 15.dp))
                TextButton(onClick = { query = ""; installedOnly = false }, modifier = Modifier.applyButtonDeformation()) { Text("查看精选") }
            }
        }
        item {
            Text("普通插件与技能目录仅扫描应用私有目录中的 metadata/Markdown；空目录显示精选示例，不下载、不执行其中脚本。连接插件包的安装状态单独显示。",
                fontSize = 11.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 7.dp))
        }
    }
    if (selected != null) {
        ModalBottomSheet(onDismissRequest = { selectedId = null }, containerColor = colors.surface) {
            Column(Modifier.fillMaxWidth().verticalScrollCompat().padding(horizontal = 24.dp).padding(bottom = 26.dp)) {
                FishGlyph(if (selected.category == "skill") "book" else "plug", Modifier.size(35.dp), colors.primary)
                Spacer(Modifier.height(16.dp))
                Text(selected.name, fontSize = 25.sp, fontWeight = FontWeight.Normal)
                Text(selected.subtitle, fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(19.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TinyLabel(if (selected.category == "plugin") "插件" else "技能")
                    TinyLabel("v${selected.version}")
                    TinyLabel(if (isLocalExtension(selected)) "应用私有目录 · 只读" else "精选示例")
                }
                Spacer(Modifier.height(24.dp))
                Text("关于这个拓展", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(selected.description, fontSize = 14.sp, lineHeight = 24.sp, modifier = Modifier.padding(top = 10.dp))
                if (selectedLocalEntry != null) {
                    Spacer(Modifier.height(20.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = colors.surfaceVariant.copy(alpha = .48f),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("本地条目校验与来源", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            TinyLabel(
                                selectedLocalEntry.validation.label,
                                primary = selectedLocalEntry.validation == EntryValidationStatus.VALID,
                            )
                            Text("来源：${selectedLocalEntry.source}", fontSize = 12.sp, lineHeight = 18.sp,
                                color = colors.onSurfaceVariant)
                            selectedLocalEntry.issues.forEach { issue ->
                                Text("• $issue", fontSize = 12.sp, lineHeight = 18.sp, color = colors.error)
                            }
                            Text("只读取并校验 JSON/Markdown 清单；目录中的脚本不会执行。",
                                fontSize = 11.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                if (selected.installed) {
                    ToggleRow(
                        title = "启用这个拓展",
                        description = if (isLocalExtension(selected)) "状态保存在应用私有设置中；不会下载或执行内容。" else "状态保存在应用私有设置中；当前只改变目录状态。",
                        checked = selected.enabled,
                        onCheckedChange = {
                            val enabled = !selected.enabled
                            preferences.edit().putBoolean(enabledPreferenceKey(selected.id), enabled).apply()
                            if (!isLocalExtension(selected)) repository.toggleExtension(selected.id)
                            repository.updateNotice("${selected.name}已${if (enabled) "启用" else "停用"}")
                            enabledRevision++
                        })
                    if (selectedLocalEntry?.deletable == true) {
                        FishButton(
                            onClick = {
                                extensionToDeleteId = selected.id
                            },
                            accent = false,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            FishGlyph("trash", Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("删除本地条目")
                        }
                    }
                } else {
                    FishButton(onClick = { repository.installExtension(selected.id); selectedId = null },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        FishGlyph("add", Modifier.size(18.dp), colors.onPrimary); Spacer(Modifier.width(9.dp)); Text("添加到我的拓展")
                    }
                }
            }
        }
    }
    if (showImport) AlertDialog(onDismissRequest = { showImport = false },
        title = { Text("添加${if (category == "plugin") "插件" else "技能"}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Text("从设备选择一个 JSON 清单或 Markdown 说明文件，复制到应用私有目录后再扫描。不会下载、连接远程仓库或执行脚本。", fontSize = 13.sp, lineHeight = 21.sp)
                FishButton(
                    onClick = {
                        showImport = false
                        localFilePicker.launch(arrayOf("application/json", "text/*"))
                    },
                    accent = false,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FishGlyph("folder", Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("从设备选择文件")
                }
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = .55f))
                Text("GitHub 地址目前只做输入校验预览，不会发起网络请求。", fontSize = 12.sp, lineHeight = 18.sp)
                OutlinedTextField(importUrl, { importUrl = it }, label = { Text("仓库地址（可选）") },
                    placeholder = { Text("https://github.com/…", fontSize = 12.sp) }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = {
            if (!importUrl.startsWith("https://github.com/")) {
                importMessage = "请输入以 https://github.com/ 开头的地址，或直接选择本地文件。"
                importMessageIsError = true
            } else {
                repository.updateNotice("已预览导入流程；真实下载将在接入后启用")
                importMessage = "已记录链接预览；当前版本不会下载或执行远程内容。"
                importMessageIsError = false
                showImport = false
                importUrl = ""
            }
        }, enabled = importUrl.isNotBlank(), modifier = Modifier.applyButtonDeformation(enabled = importUrl.isNotBlank())) { Text("预览链接") } },
        dismissButton = { TextButton(onClick = { showImport = false }, modifier = Modifier.applyButtonDeformation()) { Text("取消") } })

    val itemToDelete = allItems.firstOrNull { it.id == extensionToDeleteId }
    if (itemToDelete != null) {
        AlertDialog(
            onDismissRequest = { if (!extensionDeleting) extensionToDeleteId = null },
            title = { Text("永久删除此拓展？") },
            text = { Text("\"${itemToDelete.name}\"将从应用私有目录删除，无法撤销。") },
            confirmButton = {
                TextButton(enabled = !extensionDeleting, onClick = {
                    val item = itemToDelete
                    // Guard against a second tap starting a parallel delete of the same folder.
                    extensionDeleting = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { deleteLocalExtension(context.applicationContext, item) }
                        result.onSuccess {
                            importMessage = "已删除 ${item.name} 的应用私有条目"
                            importMessageIsError = false
                            selectedId = null
                            extensionToDeleteId = null
                            scanRevision++
                        }.onFailure {
                            importMessage = "删除失败：${it.message ?: "无法删除本地条目"}"
                            importMessageIsError = true
                            extensionToDeleteId = null
                        }
                        extensionDeleting = false
                    }
                }) { Text("永久删除", color = colors.error) }
            },
            dismissButton = {
                TextButton(enabled = !extensionDeleting, onClick = { extensionToDeleteId = null }) { Text("取消") }
            },
        )
    }
}

private fun isLocalExtension(item: ExtensionItem): Boolean = item.id.startsWith(LOCAL_EXTENSION_ID_PREFIX)

private fun enabledPreferenceKey(id: String): String = ENABLED_KEY_PREFIX + id

private fun deleteLocalExtension(context: Context, item: ExtensionItem): Result<Unit> = runCatching {
    require(isLocalExtension(item)) { "内置条目不可删除" }
    require(item.category == "plugin" || item.category == "skill") { "条目类型无效" }
    val root = File(context.filesDir, if (item.category == "plugin") IMPORTED_EXTENSION_DIRECTORY else IMPORTED_SKILL_DIRECTORY).canonicalFile
    val folderName = item.id.removePrefix("$LOCAL_EXTENSION_ID_PREFIX${item.category}-")
    require(folderName.isNotBlank() && folderName.none { it == '/' || it == '\\' || it == '.' && folderName == "." }) { "本地条目路径无效" }
    val target = File(root, folderName).canonicalFile
    require(target.parentFile?.canonicalFile == root && target.isDirectory) { "本地条目不在受管理目录内" }
    require(target.deleteRecursively() || !target.exists()) { "系统未删除该目录" }
}

private fun importLocalExtension(context: Context, uri: Uri, category: String): Result<String> = runCatching {
    require(category == "plugin" || category == "skill") { "未知拓展类型" }
    val resolver = context.contentResolver
    val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }.orEmpty().ifBlank { "${category}-descriptor" }
    val bytes = resolver.openInputStream(uri)?.use(::readDescriptorBytes)
        ?: error("无法读取所选文件")
    if (bytes.isEmpty()) error("所选文件为空")

    val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val looksLikeJson = extension == "json" || bytes.toString(Charsets.UTF_8).trimStart().startsWith("{")
    if (looksLikeJson) {
        try {
            JSONObject(bytes.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            throw IllegalArgumentException("JSON 清单格式无效", error)
        }
    } else if (extension !in setOf("md", "markdown", "txt", "")) {
        error("只支持 JSON 或 Markdown 文本文件")
    } else if (bytes.toString(Charsets.UTF_8).isBlank()) {
        error("Markdown 文件不能为空")
    }

    val root = File(context.filesDir, if (category == "plugin") IMPORTED_EXTENSION_DIRECTORY else IMPORTED_SKILL_DIRECTORY)
    if (!root.exists() && !root.mkdirs()) error("无法创建应用私有目录")
    val base = displayName.substringBeforeLast('.', displayName)
        .replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
        .trim('.', '_', '-')
        .ifBlank { category }
        .take(48)
    var folder: File? = null
    try {
        repeat(100) { attempt ->
            val suffix = if (attempt == 0) "" else "-$attempt"
            val candidate = File(root, "$base-${System.currentTimeMillis().toString(36)}$suffix")
            if (!candidate.exists()) {
                folder = candidate
                return@repeat
            }
        }
        val destination = folder ?: error("无法创建唯一的拓展目录")
        if (!destination.mkdirs()) error("无法创建拓展目录")
        val descriptorName = if (looksLikeJson) "metadata.json" else "README.md"
        File(destination, descriptorName).outputStream().use { it.write(bytes) }
        displayName
    } catch (error: Throwable) {
        folder?.takeIf { it.exists() }?.deleteRecursively()
        throw error
    }
}

private fun readDescriptorBytes(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream(minOf(MAX_DESCRIPTOR_BYTES.toInt(), 16 * 1024))
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        if (output.size() > MAX_DESCRIPTOR_BYTES - count) {
            error("文件超过 ${MAX_DESCRIPTOR_BYTES / 1024} KiB 限制")
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private enum class EntryValidationStatus(val label: String) {
    VALID("校验通过"),
    VALID_WITH_WARNINGS("有效 · 有其他清单错误"),
    INVALID("校验未通过"),
    READ_ERROR("读取失败"),
}

private enum class ExtensionScanStatus {
    SUCCESS,
    EMPTY,
    READ_ERROR,
    VALIDATION_ERROR,
    PARTIAL_ERROR,
}

private data class ScannedLocalExtension(
    val item: ExtensionItem,
    val validation: EntryValidationStatus,
    val source: String,
    val issues: List<String> = emptyList(),
    val deletable: Boolean,
)

private data class ExtensionScanResult(
    val entries: List<ScannedLocalExtension> = emptyList(),
    val readErrors: List<String> = emptyList(),
    val validationErrors: List<String> = emptyList(),
) {
    val status: ExtensionScanStatus
        get() = when {
            readErrors.isNotEmpty() && validationErrors.isNotEmpty() -> ExtensionScanStatus.PARTIAL_ERROR
            readErrors.isNotEmpty() -> ExtensionScanStatus.READ_ERROR
            validationErrors.isNotEmpty() -> ExtensionScanStatus.VALIDATION_ERROR
            entries.isEmpty() -> ExtensionScanStatus.EMPTY
            else -> ExtensionScanStatus.SUCCESS
        }
}

private data class MarkdownSummary(val heading: String?, val summary: String?)
private data class DescriptorCandidate(val file: File, val isJson: Boolean)

@Composable
private fun ExtensionScanStatusCard(
    result: ExtensionScanResult,
    scanning: Boolean,
    category: String,
    onRefresh: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val categoryName = if (category == "plugin") "插件" else "技能"
    val categoryEntries = result.entries.count { it.item.category == category }
    val hasErrors = result.readErrors.isNotEmpty() || result.validationErrors.isNotEmpty()
    val statusText = when {
        scanning -> "正在读取应用私有目录…"
        result.status == ExtensionScanStatus.EMPTY -> "扫描成功：插件与技能目录均为空。"
        else -> when (result.status) {
            ExtensionScanStatus.SUCCESS -> "扫描成功：共发现 ${result.entries.size} 个本地条目。"
            ExtensionScanStatus.READ_ERROR -> "目录读取失败；错误不会按空目录处理。"
            ExtensionScanStatus.VALIDATION_ERROR -> "扫描完成，但发现清单校验错误。"
            ExtensionScanStatus.PARTIAL_ERROR -> "扫描部分完成：存在目录读取或清单校验错误。"
            ExtensionScanStatus.EMPTY -> "扫描成功：目录为空。"
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (hasErrors) colors.errorContainer.copy(alpha = .58f) else colors.surfaceVariant.copy(alpha = .48f),
    ) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(statusText, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 18.sp,
                    color = if (hasErrors) colors.onErrorContainer else colors.onSurface)
                TextButton(onClick = onRefresh, enabled = !scanning,
                    modifier = Modifier.applyButtonDeformation(enabled = !scanning)) {
                    Text("刷新扫描", fontSize = 12.sp)
                }
            }
            if (!scanning) {
                Text("当前$categoryName：$categoryEntries 个本地条目；未发现有效本地条目时，下方显示精选示例。",
                    fontSize = 11.sp, lineHeight = 16.sp, color = colors.onSurfaceVariant)
                (result.readErrors + result.validationErrors).take(4).forEach { message ->
                    Text("• $message", fontSize = 11.sp, lineHeight = 16.sp, color = colors.error)
                }
                val omitted = result.readErrors.size + result.validationErrors.size - 4
                if (omitted > 0) Text("另有 $omitted 项错误未展开。", fontSize = 11.sp, color = colors.error)
            }
        }
    }
}

private fun scanPrivateExtensions(context: Context): ExtensionScanResult {
    val entries = mutableListOf<ScannedLocalExtension>()
    val readErrors = mutableListOf<String>()
    val validationErrors = mutableListOf<String>()
    listOf(
        "plugin" to File(context.filesDir, IMPORTED_EXTENSION_DIRECTORY),
        "skill" to File(context.filesDir, IMPORTED_SKILL_DIRECTORY),
    ).forEach { (category, root) ->
        try {
            scanPrivateExtensionRoot(root, category, entries, readErrors, validationErrors)
        } catch (error: SecurityException) {
            readErrors += "${extensionCategoryName(category)}目录：权限不足，${error.message ?: "无法读取"}"
        } catch (error: IOException) {
            readErrors += "${extensionCategoryName(category)}目录：${error.message ?: "I/O 读取失败"}"
        }
    }
    return ExtensionScanResult(entries, readErrors, validationErrors)
}

private fun scanPrivateExtensionRoot(
    root: File,
    category: String,
    entries: MutableList<ScannedLocalExtension>,
    readErrors: MutableList<String>,
    validationErrors: MutableList<String>,
) {
    val parent = root.parentFile ?: throw IOException("无法确定 ${root.name} 的父目录")
    val parentChildren = parent.listFiles() ?: throw IOException("无法列出 ${parent.name} 目录内容")
    val actualRoot = parentChildren.firstOrNull { it.name == root.name }
        ?: return // The managed directory is genuinely absent: this is a successful empty scan.
    if (!actualRoot.isDirectory) throw IOException("${root.name} 不是目录")
    val children = actualRoot.listFiles() ?: throw IOException("无法列出 ${root.name} 目录内容")
    val sortedChildren = children.sortedBy { it.name.lowercase(Locale.ROOT) }
    val directories = sortedChildren.filter { it.isDirectory }
    val descriptors = sortedChildren.filter { it.isFile && isDescriptorFile(it) }

    if (directories.isEmpty() && descriptors.isNotEmpty()) {
        // Preserve support for a descriptor stored directly in the managed root, but never delete that root.
        entries += scanPrivateExtensionEntry(
            root, category, deletable = false, readErrors = readErrors, validationErrors = validationErrors,
        )
        return
    }

    directories.forEach { folder ->
        try {
            entries += scanPrivateExtensionEntry(
                folder, category, deletable = true, readErrors = readErrors, validationErrors = validationErrors,
            )
        } catch (error: SecurityException) {
            val message = "${extensionCategoryName(category)} ${folder.name}：权限不足，${error.message ?: "无法读取"}"
            readErrors += message
            entries += unreadableEntry(folder, category, message)
        } catch (error: IOException) {
            val message = "${extensionCategoryName(category)} ${folder.name}：${error.message ?: "I/O 读取失败"}"
            readErrors += message
            entries += unreadableEntry(folder, category, message)
        }
    }

    if (descriptors.isNotEmpty()) {
        entries += scanPrivateExtensionEntry(
            root, category, deletable = false, readErrors = readErrors, validationErrors = validationErrors,
        )
    }

    if (directories.isEmpty() && descriptors.isEmpty() && sortedChildren.isNotEmpty()) {
        sortedChildren.filter { it.isFile }.forEach { file ->
            validationErrors += "${extensionCategoryName(category)}目录包含未识别文件 ${file.name}（未执行）"
        }
    }
}

private fun scanPrivateExtensionEntry(
    folder: File,
    category: String,
    deletable: Boolean,
    readErrors: MutableList<String>,
    validationErrors: MutableList<String>,
): ScannedLocalExtension {
    val children = folder.listFiles() ?: throw IOException("无法列出条目目录")
    val candidates = mutableListOf<DescriptorCandidate>()
    val metadataNames = listOf("metadata.json", "manifest.json", "extension.json", "plugin.json", "skill.json")
    metadataNames.forEach { name ->
        children.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { file ->
            if (file.isFile) candidates += DescriptorCandidate(file, isJson = true)
        }
    }
    children.firstOrNull { it.isDirectory && it.name.equals("metadata", ignoreCase = true) }?.let { metadataFolder ->
        val nested = metadataFolder.listFiles() ?: throw IOException("无法读取 metadata 子目录")
        nested.firstOrNull { it.name.equals("metadata.json", ignoreCase = true) }?.let { file ->
            if (file.isFile) candidates += DescriptorCandidate(file, isJson = true)
        }
    }
    children.filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
        .sortedWith(compareBy<File> { markdownPriority(it.name) }.thenBy { it.name.lowercase(Locale.ROOT) })
        .forEach { candidates += DescriptorCandidate(it, isJson = false) }

    val validationIssues = mutableListOf<String>()
    val entryReadErrors = mutableListOf<String>()
    var parsedMetadata: JSONObject? = null
    var parsedMarkdown: MarkdownSummary? = null
    var selectedSource: String? = null

    candidates.forEach { candidate ->
        try {
            if (candidate.file.length() > MAX_DESCRIPTOR_BYTES) {
                validationIssues += "${candidate.file.name} 超过 ${MAX_DESCRIPTOR_BYTES / 1024} KiB 限制"
                return@forEach
            }
            val text = readLimitedText(candidate.file)
            if (candidate.isJson) {
                val parsed = try {
                    JSONObject(text)
                } catch (_: Exception) {
                    validationIssues += "${candidate.file.name} 不是有效 JSON 对象"
                    return@forEach
                }
                if (parsedMetadata == null) {
                    parsedMetadata = parsed
                    selectedSource = candidate.file.name
                }
            } else {
                if (text.isBlank()) {
                    validationIssues += "${candidate.file.name} 内容为空"
                    return@forEach
                }
                val summary = parseMarkdownSummary(text)
                if (parsedMarkdown == null) {
                    parsedMarkdown = summary
                    if (selectedSource == null) selectedSource = candidate.file.name
                }
            }
        } catch (error: SecurityException) {
            entryReadErrors += "${candidate.file.name} 权限不足：${error.message ?: "无法读取"}"
        } catch (error: IOException) {
            entryReadErrors += "${candidate.file.name} I/O 读取失败：${error.message ?: "未知错误"}"
        } catch (error: IllegalStateException) {
            validationIssues += "${candidate.file.name} 无法通过大小校验：${error.message ?: "文件内容异常"}"
        }
    }

    val itemName = firstNonBlank(
        parsedMetadata?.optString("name"), parsedMetadata?.optString("displayName"), parsedMetadata?.optString("title"),
        parsedMarkdown?.heading, folder.name,
    ) ?: folder.name
    val description = firstNonBlank(
        parsedMetadata?.optString("description"), parsedMetadata?.optString("summary"), parsedMetadata?.optString("subtitle"),
        parsedMarkdown?.summary, "应用私有目录中的只读条目。",
    ) ?: "应用私有目录中的只读条目。"
    val subtitle = firstNonBlank(parsedMetadata?.optString("subtitle"), parsedMetadata?.optString("summary"), "应用私有目录")
        ?: "应用私有目录"
    val version = firstNonBlank(parsedMetadata?.optString("version"), parsedMetadata?.optString("versionName"), "本地") ?: "本地"
    val safeId = folder.name.ifBlank { category }
    val item = ExtensionItem(
        id = "$LOCAL_EXTENSION_ID_PREFIX$category-$safeId",
        name = itemName,
        subtitle = subtitle,
        description = description,
        version = version,
        category = category,
        installed = true,
        enabled = true,
    )

    val hasValidDescriptor = parsedMetadata != null || parsedMarkdown != null
    if (candidates.isEmpty()) validationIssues += "未找到可读取的 JSON/Markdown 清单"
    val rootName = if (category == "plugin") IMPORTED_EXTENSION_DIRECTORY else IMPORTED_SKILL_DIRECTORY
    val source = buildString {
        append(rootName)
        append('/')
        if (folder.name != rootName) {
            append(folder.name)
            append('/')
        }
        append(selectedSource ?: candidates.firstOrNull()?.file?.name ?: "（无有效清单）")
    }

    validationErrors += validationIssues.map { "${extensionCategoryName(category)} $itemName：$it" }
    readErrors += entryReadErrors.map { "${extensionCategoryName(category)} $itemName：$it" }
    val validation = when {
        !hasValidDescriptor && entryReadErrors.isNotEmpty() -> EntryValidationStatus.READ_ERROR
        !hasValidDescriptor -> EntryValidationStatus.INVALID
        validationIssues.isNotEmpty() || entryReadErrors.isNotEmpty() -> EntryValidationStatus.VALID_WITH_WARNINGS
        else -> EntryValidationStatus.VALID
    }
    return ScannedLocalExtension(item, validation, source, validationIssues + entryReadErrors, deletable)
}

private fun unreadableEntry(folder: File, category: String, message: String): ScannedLocalExtension {
    val item = ExtensionItem(
        id = "$LOCAL_EXTENSION_ID_PREFIX$category-${folder.name.ifBlank { "unreadable" }}",
        name = folder.name.ifBlank { "无法读取的本地条目" },
        subtitle = "应用私有目录 · 读取失败",
        description = message,
        version = "未知",
        category = category,
        installed = true,
        enabled = false,
    )
    val directory = if (category == "plugin") IMPORTED_EXTENSION_DIRECTORY else IMPORTED_SKILL_DIRECTORY
    return ScannedLocalExtension(
        item, EntryValidationStatus.READ_ERROR, "$directory/${folder.name}/（读取失败）", listOf(message), deletable = true,
    )
}

private fun parseMarkdownSummary(text: String): MarkdownSummary {
    val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    val heading = lines.firstOrNull { it.startsWith("#") }
        ?.removePrefix("#")?.trim().takeUnless { it.isNullOrBlank() }
    val summary = lines.firstOrNull { !it.startsWith("#") }
    return MarkdownSummary(heading, summary)
}

private fun markdownPriority(name: String): Int = when (name.lowercase(Locale.ROOT)) {

    "skill.md" -> 0
    "readme.md" -> 1
    "extension.md", "plugin.md" -> 2
    else -> 3
}

private fun isDescriptorFile(file: File): Boolean =
    file.extension.lowercase(Locale.ROOT) == "md" || file.name.lowercase(Locale.ROOT) in setOf(
        "metadata.json", "manifest.json", "extension.json", "plugin.json", "skill.json",
    )

private fun readLimitedText(file: File): String =
    readDescriptorBytes(file.inputStream()).toString(Charsets.UTF_8)

private fun extensionCategoryName(category: String): String = if (category == "plugin") "插件" else "技能"

private fun firstNonBlank(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }?.trim()

@Composable
private fun Modifier.verticalScrollCompat(): Modifier = this.then(
    Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))
