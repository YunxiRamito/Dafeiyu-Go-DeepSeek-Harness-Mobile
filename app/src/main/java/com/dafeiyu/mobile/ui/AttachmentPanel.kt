package com.dafeiyu.mobile.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dafeiyu.mobile.data.ApplicationPermissionBridge
import com.dafeiyu.mobile.data.AuthorizedMediaStore
import com.dafeiyu.mobile.data.AttachmentPreview
import com.dafeiyu.mobile.data.ChatMessage
import com.dafeiyu.mobile.data.DemoRepository
import com.dafeiyu.mobile.ui.components.FishGlyph
import com.dafeiyu.mobile.ui.components.FishButton
import com.dafeiyu.mobile.ui.components.GlassSurface
import com.dafeiyu.mobile.ui.components.applyButtonDeformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

private const val CaptureDirectory = "attachment-previews"
private const val MaximumCaptureBytes = 10L * 1024L * 1024L
private val CaptureNamePattern = Regex("capture-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.jpg")

/** Files are imported through explicit system selection into private, bounded storage. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AttachmentTray(repository: DemoRepository, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permissionBridge = remember(context) { ApplicationPermissionBridge.get(context) }
    val permissionRevision by permissionBridge.revision.collectAsState()
    val lifecycle = LocalLifecycleOwner.current
    var albumEpoch by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) albumEpoch++
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val mediaStore = remember(context) { AuthorizedMediaStore(context) }
    val album by produceState<List<AttachmentPreview>>(emptyList(), mediaStore, permissionRevision, albumEpoch) {
        value = try { mediaStore.recentImages() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { emptyList() }
    }
    val recentPhotos = repository.recentImageAttachments()
    val storedFiles = repository.storedAttachmentPreviews().filter { it.mimeType?.startsWith("image/") != true }
    var confirmCleanup by remember { mutableStateOf(false) }
    var pendingCaptureName by rememberSaveable { mutableStateOf<String?>(null) }
    var preparingCamera by remember { mutableStateOf(false) }
    var requestingCameraPermission by remember { mutableStateOf(false) }

    fun addSelected(uri: Uri) {
        if (uri.scheme != "content") {
            repository.updateNotice("无法预览此选择，请从系统选择器重试")
            return
        }
        repository.importAttachment(uri)
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { uris ->
        uris.take(6).forEach { uri -> addSelected(uri) }
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.take(6).forEach { uri -> addSelected(uri) }
        if (uris.size > 6) repository.updateNotice("每次最多添加 6 个附件；只保留前 6 个选择")
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { accepted ->
        val name = pendingCaptureName
        // Result acceptance belongs to the view model lifetime, not the visible tray animation.
        if (name != null) repository.viewModelScope.launch {
            try {
                val file = captureFile(context, name)
                val valid = withContext(Dispatchers.IO) {
                    file != null && accepted && file.isFile && file.length() in 1..MaximumCaptureBytes
                }
                if (valid && file != null) {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", file)
                    addSelected(uri)
                } else {
                    withContext(Dispatchers.IO) { file?.delete() }
                    if (accepted) repository.updateNotice("照片无法预览或超过 10 MiB，请重新选择")
                }
            } finally {
                if (pendingCaptureName == name) pendingCaptureName = null
            }
        }
    }

    fun launchCapture() {
        if (preparingCamera || pendingCaptureName != null) return
        preparingCamera = true
        scope.launch {
            var created: File? = null
            try {
                // Read presentation state on the main thread, before filesystem work.
                val protectedNames = repository.attachmentPreviews.mapNotNull {
                    ownedCaptureName(context, it.uri)
                }.toMutableSet()
                pendingCaptureName?.takeIf { CaptureNamePattern.matches(it) }?.let(protectedNames::add)
                val file = withContext(Dispatchers.IO) { createCapture(context, protectedNames) }
                created = file
                pendingCaptureName = file.name
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", file)
                camera.launch(uri)
            } catch (_: Exception) {
                pendingCaptureName = null
                withContext(Dispatchers.IO) { created?.delete() }
                repository.updateNotice("当前无法打开相机，可以使用相册或附件选择")
            } finally {
                preparingCamera = false
            }
        }
    }

    GlassSurface(modifier = modifier, cornerRadius = 28.dp, tint = MaterialTheme.colorScheme.surface.copy(alpha = .32f)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (album.isNotEmpty()) {
                Text(if (permissionBridge.state("media") == "limited") "已授权的照片" else "最近照片",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(album, key = { it.id }) { photo ->
                        val shape = RoundedCornerShape(12.dp)
                        Box(Modifier.size(68.dp).applyButtonDeformation(consumeDrag = false).clip(shape)
                            .clickable(indication = null,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                role = Role.Button) { photo.uri?.toUri()?.let(::addSelected) }
                            .semantics { contentDescription = "添加照片：${photo.label}" }) {
                            LocalAttachmentThumbnail(photo, Modifier.fillMaxSize())
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("已保存的附件", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    state = rememberTooltipState(), tooltip = {
                        GlassSurface(cornerRadius = 8.dp) {
                            Text("清理未使用附件", Modifier.padding(8.dp), style = MaterialTheme.typography.labelMedium)
                        }
                    }) {
                    IconButton(onClick = { confirmCleanup = true }, enabled = repository.attachmentImportCount == 0,
                        modifier = Modifier.size(48.dp).applyButtonDeformation(circularHit = true)
                            .semantics { contentDescription = "清理未使用附件" }) {
                        FishGlyph("trash", Modifier.size(20.dp))
                    }
                }
            }
            if (recentPhotos.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(recentPhotos, key = { it.id }) { photo ->
                        val isSelected = repository.attachmentPreviews.any { it.id == photo.id }
                        val shape = RoundedCornerShape(12.dp)
                        Box(Modifier.size(68.dp).applyButtonDeformation(consumeDrag = false).clip(shape)
                            .border(if (isSelected) 2.dp else 1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
                            .selectable(selected = isSelected, role = Role.Checkbox, onClick = {
                                if (isSelected) repository.removeAttachment(photo.id)
                                else repository.addAttachment(photo.id, photo.label, photo.uri)
                            })
                            .semantics {
                                contentDescription = photo.label
                                selected = isSelected
                                stateDescription = if (isSelected) "已选中，点击取消" else "未选中，点击添加"
                            }) {
                            LocalAttachmentThumbnail(photo, Modifier.fillMaxSize().padding(2.dp).clip(shape))
                            if (isSelected) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp)
                                .clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center) {
                                FishGlyph("check", Modifier.size(13.dp), MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                }
            } else {
                Text("暂无已保存的照片", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
            }
            if (storedFiles.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(storedFiles, key = { it.id }) { file ->
                    val selected = repository.attachmentPreviews.any { it.id == file.id }
                    FishButton(onClick = {
                        if (selected) repository.removeAttachment(file.id)
                        else repository.addAttachment(file.id, file.label, file.uri)
                    }, accent = selected, modifier = Modifier.widthIn(max = 200.dp)) {
                        FishGlyph(if (selected) "check" else "document", Modifier.size(18.dp))
                        Text(file.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AttachmentAction("拍照", "camera", enabled = !preparingCamera && !requestingCameraPermission && pendingCaptureName == null, modifier = Modifier.weight(1f)) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        launchCapture()
                    } else {
                        requestingCameraPermission = true
                        scope.launch {
                            try {
                                permissionBridge.requestConsent("camera", "用于拍摄你选择发送到当前对话的照片；未发送的照片不会提供给模型。")
                                if (permissionBridge.state("camera") == "granted") launchCapture()
                                else repository.updateNotice("未允许相机权限，可以使用相册或附件选择；也可在权限管理中授权")
                            } finally { requestingCameraPermission = false }
                        }
                    }
                }
                AttachmentAction("相册", "photo", modifier = Modifier.weight(1f)) {
                    try { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    catch (_: Exception) { repository.updateNotice("当前无法打开照片选择器，请使用附件选择") }
                }
                AttachmentAction("附件", "attach", modifier = Modifier.weight(1f)) {
                    try { documentPicker.launch(arrayOf("*/*")) }
                    catch (_: Exception) { repository.updateNotice("当前无法打开文件选择器，请稍后重试") }
                }
            }
            if (repository.attachmentImportCount > 0) Text("正在导入 ${repository.attachmentImportCount} 个附件…",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
    if (confirmCleanup) Dialog(onDismissRequest = { confirmCleanup = false }) {
        GlassSurface(cornerRadius = 8.dp) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("清理未使用附件", style = MaterialTheme.typography.titleLarge)
                Text("删除未被任何对话引用且当前未选中的本地副本？")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FishButton(onClick = { repository.cleanupUnusedAttachments(); confirmCleanup = false }) { Text("清理") }
                    FishButton(onClick = { confirmCleanup = false }, accent = false) { Text("取消") }
                }
            }
        }
    }
}

@Composable
fun SelectedAttachmentStrip(repository: DemoRepository, modifier: Modifier = Modifier) {
    val selected = repository.attachmentPreviews
    if (selected.isEmpty()) return
    LazyRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 5.dp)) {
        items(selected, key = { it.id }) { attachment ->
            Row(Modifier.widthIn(max = 220.dp).clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f)).padding(start = 7.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LocalAttachmentThumbnail(attachment, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))
                Text(attachment.label, modifier = Modifier.widthIn(max = 104.dp), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = {
                    repository.removeAttachment(attachment.id)
                }, modifier = Modifier.size(48.dp).applyButtonDeformation(circularHit = true).clip(CircleShape)
                    .semantics { contentDescription = "移除${attachment.label}" }) {
                    FishGlyph("close", Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
fun MessageAttachmentStrip(repository: DemoRepository, message: ChatMessage, modifier: Modifier = Modifier) {
    val attachments = repository.messageAttachments(message)
    if (attachments.isEmpty()) return
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 6.dp)) {
        items(attachments, key = { it.id }) { attachment ->
            GlassSurface(modifier = Modifier.widthIn(max = 230.dp).heightIn(min = 56.dp), cornerRadius = 8.dp,
                tint = MaterialTheme.colorScheme.surface.copy(alpha = .30f)) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LocalAttachmentThumbnail(attachment, Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)))
                Column(Modifier.widthIn(max = 154.dp)) {
                    Text(attachment.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall)
                    Text("${(attachment.sizeBytes + 1023) / 1024} KiB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            }
        }
    }
}

@Composable
private fun AttachmentAction(label: String, glyph: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    FishButton(onClick = onClick, enabled = enabled, accent = false, modifier = modifier.heightIn(min = 64.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FishGlyph(glyph, Modifier.size(22.dp), MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else .4f))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun LocalAttachmentThumbnail(attachment: AttachmentPreview, modifier: Modifier) {
    val context = LocalContext.current
    val preview by produceState<ImageBitmap?>(null, attachment.uri) {
        value = withContext(Dispatchers.IO) { decodePreview(context, attachment.uri) }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (preview != null) Image(bitmap = preview!!, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else FishGlyph("document", Modifier.size(22.dp), MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun decodePreview(context: Context, value: String?): ImageBitmap? {
    if (value == null) return null
    return try {
        val uri = value.toUri()
        val source = if (uri.scheme == "file") {
            val file = File(uri.path ?: return null).canonicalFile
            val root = File(context.noBackupFilesDir, "conversation-attachments").canonicalFile
            if (!file.toPath().startsWith(root.toPath()) || !file.isFile) return null
            ImageDecoder.createSource(file)
        } else {
            if (uri.scheme != "content") return null
            if (context.contentResolver.getType(uri)?.startsWith("image/") != true) return null
            ImageDecoder.createSource(context.contentResolver, uri)
        }
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            require(width > 0 && height > 0 && width.toLong() * height <= 100_000_000L)
            val ratio = 256f / max(width, height).coerceAtLeast(256)
            decoder.setTargetSize((width * ratio).roundToInt().coerceAtLeast(1), (height * ratio).roundToInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }.asImageBitmap()
    } catch (_: Exception) { null }
}

private fun captureFile(context: Context, name: String): File? {
    if (!CaptureNamePattern.matches(name)) return null
    return File(File(context.cacheDir, CaptureDirectory), name)
}

private fun ownedCaptureName(context: Context, value: String?): String? {
    if (value == null) return null
    val uri = value.toUri()
    if (uri.scheme != "content" || uri.authority != "${context.packageName}.attachments") return null
    val segments = uri.pathSegments
    if (segments.size != 2 || segments.first() != "attachment_previews") return null
    return segments.last().takeIf { CaptureNamePattern.matches(it) }
}

private fun createCapture(context: Context, protectedNames: Set<String>): File {
    val directory = File(context.cacheDir, CaptureDirectory)
    check(directory.isDirectory || directory.mkdirs())
    // Selected and pending app-owned captures survive cleanup; only stale unused files are bounded.
    val files = directory.listFiles()?.filter {
        it.isFile && CaptureNamePattern.matches(it.name) && it.name !in protectedNames
    }?.sortedByDescending { it.lastModified() }.orEmpty()
    files.forEachIndexed { index, file ->
        if (index >= 11 || System.currentTimeMillis() - file.lastModified() > 86_400_000L) file.delete()
    }
    return File(directory, "capture-${UUID.randomUUID()}.jpg").also { check(it.createNewFile()) }
}






