package com.dafeiyu.mobile.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.dafeiyu.mobile.data.DeveloperCenterClient
import com.dafeiyu.mobile.data.FeedbackAttachmentStore
import com.dafeiyu.mobile.data.FeedbackDiagnostics
import com.dafeiyu.mobile.ui.components.FishButton
import com.dafeiyu.mobile.ui.components.FishDivider
import com.dafeiyu.mobile.ui.components.FishGlyph
import com.dafeiyu.mobile.ui.components.GlassMotion
import com.dafeiyu.mobile.ui.components.GlassSurface
import com.dafeiyu.mobile.ui.components.LocalReduceGlassMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Native Android feedback channel; no PC records or administrator credentials are requested. */
@Composable
fun MobileFeedbackContent(notify: (String) -> Unit, developerCenter: DeveloperCenterClient? = null) {
    val context = LocalContext.current.applicationContext
    val client = remember(developerCenter) { developerCenter ?: DeveloperCenterClient() }
    val scope = rememberCoroutineScope()
    val installationId = remember(context) { feedbackIdentity(context, "developer_center_presence", "installation_id") }
    val machineId = remember(context) { feedbackIdentity(context, "developer_center_feedback", "machine_id") }
    val version = remember(context) { com.dafeiyu.mobile.BuildConfig.VERSION_NAME }
    var typeFilter by rememberSaveable { mutableStateOf("all") }
    var statusFilter by rememberSaveable { mutableStateOf("all") }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var previousOffsets by remember { mutableStateOf<List<Int>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var page by remember { mutableStateOf<DeveloperCenterClient.FeedbackPage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var ban by remember { mutableStateOf<DeveloperCenterClient.FeedbackBanStatus?>(null) }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    var mutationIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var mutationErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var supplementItem by remember { mutableStateOf<DeveloperCenterClient.FeedbackItem?>(null) }
    var imageToView by remember { mutableStateOf<DeveloperCenterClient.FeedbackImage?>(null) }

    LaunchedEffect(client, machineId, installationId, offset, typeFilter, statusFilter, refresh) {
        loading = true
        loadError = null
        val result = client.getFeedback(machineId, installationId, offset, DeveloperCenterClient.FEEDBACK_PAGE_SIZE,
            mine = typeFilter == "mine", category = typeFilter.takeIf { it in setOf("Suggestion", "Bug") },
            status = statusFilter.takeUnless { it == "all" })
        result.onSuccess {
            page = it
            ban = it.banStatus ?: ban
            if (it.feedback.isEmpty() && offset > 0 && previousOffsets.isNotEmpty()) {
                offset = previousOffsets.last()
                previousOffsets = previousOffsets.dropLast(1)
            }
        }.onFailure {
            loadError = feedbackFailure(it)
            if (it is DeveloperCenterClient.FeedbackBannedException) ban = it.banStatus
        }
        loading = false
    }
    LaunchedEffect(client, machineId, refresh) {
        client.getFeedbackBanStatus(machineId).onSuccess { ban = it }
    }

    fun resetList() {
        offset = 0
        previousOffsets = emptyList()
        expandedId = null
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        FeedbackCard {
            Text("把你的想法留给 Dafeiyu-GO", style = MaterialTheme.typography.titleMedium)
            FeedbackHint("提交需求建议或漏洞反馈，可添加图片与应用诊断日志。")
            FeedbackAnimated(visible = ban?.banned == true) {
                FeedbackHint(feedbackBanDescription(ban), error = true)
            }
            FeedbackComposer(context, version, submitLabel = "提交反馈", enabled = ban?.banned != true,
                onSubmit = { category, title, body, images, diagnostics ->
                    client.postFeedback(category, title, body, installationId, version, machineId, images, diagnostics)
                        .map { Unit }
                }, onSuccess = {
                    typeFilter = "mine"
                    resetList()
                    refresh++
                    notify("反馈已提交")
                }, onFailure = { if (it is DeveloperCenterClient.FeedbackBannedException) ban = it.banStatus })
        }

        FeedbackCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("反馈与建议", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { refresh++ }, enabled = !loading) {
                    Text("刷新")
                }
            }
            FeedbackFilterRow(listOf("all" to "全部反馈", "Suggestion" to "需求建议", "Bug" to "漏洞反馈", "mine" to "我的提交"),
                typeFilter) { typeFilter = it; resetList() }
            FeedbackFilterRow(listOf("all" to "全部状态", "Submitted" to "已提交", "Processing" to "处理中", "Completed" to "已完成", "Deferred" to "暂不处理"),
                statusFilter) { statusFilter = it; resetList() }
            FishDivider()
            FeedbackAnimated(visible = loading) {
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    FeedbackHint("正在读取反馈…")
                }
            }
            FeedbackAnimated(visible = loadError != null) { FeedbackHint(loadError.orEmpty(), error = true) }
            FeedbackAnimated(visible = !loading && loadError == null && page?.feedback?.isEmpty() == true) {
                FeedbackHint(if (typeFilter == "mine") "你还没有符合筛选条件的提交。" else "暂无符合筛选条件的反馈。")
            }
            FeedbackAnimated(visible = !loading && loadError == null && page?.feedback?.isNotEmpty() == true) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    page?.feedback.orEmpty().forEachIndexed { index, item -> key(item.id) {
                    if (index > 0) FishDivider()
                    val id = item.id.toString()
                    FeedbackRow(item, client, expandedId == id, mutationIds.contains(id),
                        mutationErrors[id], enabled = !loading && ban?.banned != true,
                        onExpand = { expandedId = if (expandedId == id) null else id },
                        onImage = { imageToView = it }, onSupplement = { supplementItem = item },
                        onSupport = {
                            if (id !in mutationIds) {
                                mutationIds = mutationIds + id
                                mutationErrors = mutationErrors - id
                                scope.launch {
                                    try {
                                        val result = if (item.hasUpvoted == true) client.deleteFeedbackUpvote(id, machineId)
                                            else client.postFeedbackUpvote(id, machineId)
                                        result.onSuccess { support ->
                                            page = page?.copy(feedback = page!!.feedback.map { current ->
                                                if (current.id == item.id) current.copy(upvoteCount = support.upvoteCount, hasUpvoted = support.hasUpvoted) else current
                                            })
                                        }.onFailure { error ->
                                            mutationErrors = mutationErrors + (id to feedbackFailure(error))
                                            if (error is DeveloperCenterClient.FeedbackBannedException) ban = error.banStatus
                                            FeedbackDiagnostics.record(context, "feedback support", error)
                                        }
                                    } finally { mutationIds = mutationIds - id }
                                }
                            }
                        })
                    } }
                }
            }
            FishDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = {
                    offset = previousOffsets.last()
                    previousOffsets = previousOffsets.dropLast(1)
                    expandedId = null
                }, enabled = !loading && previousOffsets.isNotEmpty()) { Text("上一页") }
                Text("第 ${previousOffsets.size + 1} 页${page?.totalCount?.let { " · 共 $it 条" }.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    page?.nextOffset?.let { next -> previousOffsets = previousOffsets + offset; offset = next; expandedId = null }
                }, enabled = !loading && page?.nextOffset != null) { Text("下一页") }
            }
        }
    }

    supplementItem?.let { item ->
        val scroll = rememberScrollState()
        AlertDialog(onDismissRequest = { supplementItem = null }, title = { Text("补充反馈 #${item.number ?: ""}") },
            confirmButton = {}, dismissButton = { TextButton(onClick = { supplementItem = null }) { Text("关闭") } },
            text = {
                Column(Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FeedbackHint("仅本人可补充；已完成或暂不处理的反馈会停止接收补充。")
                    key(item.id) {
                        FeedbackComposer(context, version, submitLabel = "提交补充", enabled = item.canAddSupplement && ban?.banned != true,
                            supplement = true, onSubmit = { _, _, body, images, diagnostics ->
                                client.postFeedbackSupplement(item.id.toString(), installationId, machineId, body, images, diagnostics)
                                    .map { updated ->
                                        page = page?.copy(feedback = page!!.feedback.map { if (it.id == updated.id) updated else it })
                                        Unit
                                    }
                            }, onSuccess = { supplementItem = null; refresh++; notify("补充已提交") },
                            onFailure = { if (it is DeveloperCenterClient.FeedbackBannedException) ban = it.banStatus })
                    }
                }
            })
    }
    imageToView?.let { image -> FeedbackImageDialog(client, image) { imageToView = null } }

}

@Composable
private fun FeedbackComposer(
    context: Context, version: String, submitLabel: String, enabled: Boolean,
    supplement: Boolean = false,
    onSubmit: suspend (String, String, String, List<DeveloperCenterClient.FeedbackUploadImage>, ByteArray?) -> Result<Unit>,
    onSuccess: () -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val store = remember(context) { FeedbackAttachmentStore(context) }
    var category by rememberSaveable { mutableStateOf("Suggestion") }
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var images by remember { mutableStateOf<List<FeedbackAttachmentStore.Attachment>>(emptyList()) }
    var includeLogs by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        if (uris.isNotEmpty()) {
            picking = true
            scope.launch {
                try {
                    val remaining = DeveloperCenterClient.FEEDBACK_MAX_IMAGES - images.size
                    if (uris.size > remaining) error = "最多添加 5 张图片，本次仅读取剩余 $remaining 张"
                    uris.take(remaining).forEach { uri ->
                        try { images = images + store.importImage(uri) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Throwable) { error = feedbackFailure(failure) }
                    }
                } finally { picking = false }
            }
        }
    }
    DisposableEffect(store) { onDispose { store.clear() } }
    val bodyLimit = if (supplement) 12000 else 12000 - title.trim().length - 2
    val valid = body.trim().isNotEmpty() && body.trim().length <= bodyLimit && (supplement || title.trim().isNotEmpty())

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!supplement) {
            FeedbackFilterRow(listOf("Suggestion" to "需求建议", "Bug" to "漏洞反馈"), category, enabled && !busy) { category = it }
            OutlinedTextField(title, { if (it.length <= 200) title = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("标题") }, singleLine = true, enabled = enabled && !busy, shape = RoundedCornerShape(16.dp))
        }
        OutlinedTextField(body, { if (it.length <= 12000) body = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp),
            label = { Text(if (supplement) "补充说明" else "你的想法或遇到的问题") },
            supportingText = { Text("${body.trim().length} / ${bodyLimit.coerceAtLeast(0)}") },
            isError = body.trim().length > bodyLimit, enabled = enabled && !busy, minLines = 4, shape = RoundedCornerShape(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = enabled && !busy && !picking && images.size < 5) {
                FishGlyph("photo", Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("添加图片 ${images.size}/5")
            }
            if (picking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        FeedbackAnimated(images.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                images.forEach { image -> key(image.id) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        FeedbackLocalThumbnail(store, image)
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            Text("${image.width} × ${image.height}", style = MaterialTheme.typography.bodySmall)
                            FeedbackHint("${(image.bytes + 1023) / 1024} KiB")
                        }
                        TextButton(onClick = { store.remove(image); images = images - image }, enabled = !busy) {
                            FishGlyph("close", Modifier.size(18.dp)); Text("移除")
                        }
                    }
                } }
            }
        }
        FeedbackHint("支持静态 JPG、PNG、WebP；每张最多 5 MiB、2000 万像素。")
        Row(Modifier.fillMaxWidth().toggleable(value = includeLogs, enabled = enabled && !busy, role = Role.Checkbox) { includeLogs = it },
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(includeLogs, null, enabled = enabled && !busy)
            Column(Modifier.weight(1f)) {
                Text("附上应用诊断日志", style = MaterialTheme.typography.bodyMedium)
                FeedbackHint("只读取本应用的固定日志，先移除凭据与私密路径；仅管理员可查看。")
            }
        }
        FeedbackAnimated(error != null) { FeedbackHint(error.orEmpty(), error = true) }
        FishButton(onClick = { preview = true }, enabled = enabled && valid && !busy && !picking, modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(if (busy) "正在提交…" else "预览并$submitLabel")
        }
    }
    if (preview) {
        AlertDialog(onDismissRequest = { if (!busy) preview = false }, title = { Text("确认$submitLabel") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!supplement) Text(title.trim(), style = MaterialTheme.typography.titleSmall)
                    SelectionContainer { Text(body.trim(), style = MaterialTheme.typography.bodyMedium) }
                    FeedbackHint("${images.size} 张图片${if (includeLogs) " · 附脱敏诊断日志" else ""}")
                    FeedbackHint("反馈正文与图片会公开展示；请先确认没有个人隐私。")
                    FeedbackAnimated(error != null) { FeedbackHint(error.orEmpty(), error = true) }
                }
            }, dismissButton = { TextButton(onClick = { preview = false }, enabled = !busy) { Text("返回编辑") } },
            confirmButton = {
                TextButton(enabled = !busy && enabled, onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val upload = store.upload(images)
                            val diagnostics = if (includeLogs) FeedbackDiagnostics.capture(context, version) else null
                            val result = onSubmit(category, title.trim(), body.trim(), upload, diagnostics)
                            result.onSuccess {
                                store.clear(); images = emptyList(); title = ""; body = ""; includeLogs = false; preview = false
                                onSuccess()
                            }.onFailure { failure ->
                                error = if (failure is DeveloperCenterClient.DeveloperCenterException && failure.kind == DeveloperCenterClient.ErrorKind.NETWORK)
                                    "无法确认提交结果，请先刷新“我的提交”检查记录，确认未提交后再重试。" else feedbackFailure(failure)
                                onFailure(failure)
                                FeedbackDiagnostics.record(context, "feedback submission", failure)
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Throwable) { error = feedbackFailure(failure); onFailure(failure) }
                        finally { busy = false }
                    }
                }) { Text(if (busy) "正在提交…" else submitLabel) }
            })
    }
}

@Composable
private fun FeedbackRow(
    item: DeveloperCenterClient.FeedbackItem, client: DeveloperCenterClient, expanded: Boolean,
    supporting: Boolean, error: String?, enabled: Boolean,
    onExpand: () -> Unit, onSupport: () -> Unit, onSupplement: () -> Unit,
    onImage: (DeveloperCenterClient.FeedbackImage) -> Unit,
) {
    var extra by remember(item.id, item.updatedAt) { mutableStateOf<List<DeveloperCenterClient.FeedbackSupplement>>(emptyList()) }
    var next by remember(item.id, item.updatedAt) { mutableStateOf(item.nextSupplementOffset) }
    var loadingMore by remember { mutableStateOf(false) }
    var supplementError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onExpand).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(item.body.substringBefore('\n').take(200), style = MaterialTheme.typography.titleSmall, maxLines = 2)
                FeedbackHint("#${item.number ?: "—"} · ${if (item.category == "Bug") "漏洞反馈" else "需求建议"}${if (item.mine) " · 我的提交" else ""}")
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(feedbackStatusLabel(item.status), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                FeedbackHint(feedbackDate(item.updatedAt))
            }
            FishGlyph(if (expanded) "chevrondown" else "chevron", Modifier.size(16.dp))
        }
        FeedbackAnimated(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SelectionContainer { Text(item.body, style = MaterialTheme.typography.bodyMedium) }
                FeedbackHint("提交于 ${feedbackDate(item.createdAt)} · v${item.launcherVersion}")
                FeedbackRemoteImages(item.images, onImage)
                if (item.hasLogs) FeedbackHint("含应用诊断日志，仅管理员可查看")
                item.latestDeveloperReply?.let { reply ->
                    Text("最新开发者回复", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    SelectionContainer { Text(reply.body, style = MaterialTheme.typography.bodyMedium) }
                    FeedbackRemoteImages(reply.images, onImage)
                } ?: item.reply?.takeIf { it.isNotBlank() }?.let { reply ->
                    Text("开发者回复", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    SelectionContainer { Text(reply, style = MaterialTheme.typography.bodyMedium) }
                }
                val supplements = (item.supplements + extra).distinctBy { it.id }
                if (supplements.isNotEmpty()) {
                    Text("补充与回复 · ${item.supplementCount}", style = MaterialTheme.typography.titleSmall)
                    supplements.forEach { reply -> key(reply.id) {
                        FishDivider()
                        FeedbackHint("${if (reply.isDeveloperReply) "开发者" else if (item.mine) "我" else "提交者"} · ${feedbackDate(reply.createdAt)}")
                        SelectionContainer { Text(reply.body, style = MaterialTheme.typography.bodyMedium) }
                        FeedbackRemoteImages(reply.images, onImage)
                        if (reply.hasLogs) FeedbackHint("含应用诊断日志，仅管理员可查看")
                    } }
                }
                if (next != null) TextButton(enabled = !loadingMore, onClick = {
                    val requested = next ?: return@TextButton
                    loadingMore = true; supplementError = null
                    scope.launch {
                        try {
                            client.getFeedbackSupplements(item.id.toString(), requested).onSuccess {
                                extra = (extra + it.supplements).distinctBy { row -> row.id }; next = it.nextOffset
                            }.onFailure { supplementError = feedbackFailure(it) }
                        } finally { loadingMore = false }
                    }
                }) { Text(if (loadingMore) "正在读取…" else "读取较早补充") }
                FeedbackAnimated(supplementError != null) { FeedbackHint(supplementError.orEmpty(), error = true) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FishButton(onSupport, accent = item.hasUpvoted == true, enabled = enabled && !supporting, consumeDrag = false) {
                        Text(if (supporting) "处理中…" else "${if (item.hasUpvoted == true) "已支持" else "支持"} · ${item.upvoteCount ?: 0}")
                    }
                    if (item.canAddSupplement) FishButton(onSupplement, accent = false, enabled = enabled) { Text("补充反馈") }
                }
                if (item.mine && !item.canAddSupplement) FeedbackHint("此反馈已关闭，暂不能继续补充。")
                FeedbackAnimated(error != null) { FeedbackHint(error.orEmpty(), error = true) }
            }
        }
    }
}

@Composable
private fun FeedbackRemoteImages(images: List<DeveloperCenterClient.FeedbackImage>, onImage: (DeveloperCenterClient.FeedbackImage) -> Unit) {
    if (images.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        images.forEachIndexed { index, image ->
            TextButton(onClick = { onImage(image) }) {
                FishGlyph("photo", Modifier.size(18.dp)); Text("图片 ${index + 1} · ${(image.bytes + 1023) / 1024} KiB")
            }
        }
    }
}

@Composable
private fun FeedbackLocalThumbnail(store: FeedbackAttachmentStore, attachment: FeedbackAttachmentStore.Attachment) {
    var bitmap by remember(attachment.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(store, attachment.id) {
        try { bitmap = store.thumbnail(attachment) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Throwable) { /* Import already checked decode; use the attachment icon if staging is removed. */ }
    }
    DisposableEffect(attachment.id) { onDispose { bitmap?.recycle() } }
    Box(Modifier.size(60.dp).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), "所选反馈图片", Modifier.size(60.dp), contentScale = ContentScale.Crop) }
            ?: FishGlyph("photo", Modifier.size(25.dp))
    }
}

@Composable
internal fun FeedbackImageDialog(client: DeveloperCenterClient, image: DeveloperCenterClient.FeedbackImage, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    var bitmap by remember(image.id) { mutableStateOf<Bitmap?>(null) }
    var error by remember(image.id) { mutableStateOf<String?>(null) }
    var loading by remember(image.id) { mutableStateOf(true) }
    var revision by remember(image.id) { mutableIntStateOf(0) }
    var downloadNote by remember(image.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(client, image.id, revision) {
        loading = true; error = null
        client.getFeedbackImage(image, context) { progress ->
            downloadNote = if (progress.source == "cache") "已读取校验后的缓存" else
                "${if (progress.threads > 1) "${progress.threads} 线程 · " else ""}${progress.receivedBytes / 1024} / ${image.bytes / 1024} KiB${progress.reason?.let { "\n$it" }.orEmpty()}"
        }.onSuccess { bytes ->
            try {
                val decoded = withContext(Dispatchers.IO) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.setTargetSampleSize((maxOf(image.width, image.height) / 1600).coerceAtLeast(1))
                        decoder.setOnPartialImageListener { false }
                    }
                }
                bitmap?.recycle(); bitmap = decoded
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { error = "图片无法解码" }
        }.onFailure { error = feedbackFailure(it) }
        loading = false
    }
    DisposableEffect(image.id) { onDispose { bitmap?.recycle() } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("反馈图片") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (loading) CircularProgressIndicator(Modifier.size(24.dp))
                downloadNote?.let { FeedbackHint(it) }
                bitmap?.let { Image(it.asImageBitmap(), "反馈图片", Modifier.fillMaxWidth().heightIn(max = 430.dp), contentScale = ContentScale.Fit) }
                error?.let { FeedbackHint(it, error = true); TextButton(onClick = { revision++ }) { Text("重试") } }
            }
        })
}

@Composable
private fun FeedbackFilterRow(options: List<Pair<String, String>>, selected: String, enabled: Boolean = true, onSelected: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        options.forEach { (value, label) -> FilterChip(selected == value, onClick = { onSelected(value) }, enabled = enabled,
            label = { Text(label) }) }
    }
}

@Composable
private fun FeedbackCard(content: @Composable ColumnScope.() -> Unit) {
    val reduced = LocalReduceGlassMotion.current
    GlassSurface(modifier = Modifier.fillMaxWidth().animateContentSize(tween(if (reduced) 0 else GlassMotion.MorphMillis, easing = GlassMotion.EaseOut)),
        cornerRadius = 24.dp) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun FeedbackAnimated(visible: Boolean, content: @Composable () -> Unit) {
    val reduced = LocalReduceGlassMotion.current
    val duration = if (reduced) 0 else GlassMotion.MorphMillis
    AnimatedVisibility(visible, enter = fadeIn(GlassMotion.scalar(duration)) + expandVertically(tween(duration, easing = GlassMotion.EaseOut)),
        exit = fadeOut(GlassMotion.scalar(duration)) + shrinkVertically(tween(duration, easing = GlassMotion.EaseOut))) { content() }
}

@Composable
private fun FeedbackHint(text: String, error: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun feedbackFailure(error: Throwable): String = when {
    error is DeveloperCenterClient.FeedbackBannedException -> feedbackBanDescription(error.banStatus)
    error is DeveloperCenterClient.DeveloperCenterException && error.kind == DeveloperCenterClient.ErrorKind.NETWORK -> "连接反馈服务失败，请检查网络后重试"
    else -> error.message?.take(600) ?: "操作失败，请稍后重试"
}

private fun feedbackStatusLabel(status: String): String = when (status) {
    "Submitted" -> "已提交"; "Processing" -> "处理中"; "Completed" -> "已完成"; "Deferred" -> "暂不处理"; else -> status
}

private fun feedbackBanDescription(ban: DeveloperCenterClient.FeedbackBanStatus?): String {
    if (ban?.banned != true) return ""
    return "反馈功能已被限制 · ${if (ban.permanent) "永久" else "至 ${ban.expiresAt?.let(::feedbackDate).orEmpty()}"}\n原因：${ban.reason.orEmpty()}"
}

private fun feedbackDate(value: String): String = runCatching {
    OffsetDateTime.parse(value).atZoneSameInstant(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}.getOrElse { value.take(32) }

private fun feedbackIdentity(context: Context, group: String, key: String): String {
    val preferences = context.getSharedPreferences(group, Context.MODE_PRIVATE)
    preferences.getString(key, null)?.let { value -> runCatching { UUID.fromString(value) }.getOrNull()?.let { return it.toString() } }
    val id = UUID.randomUUID().toString()
    check(preferences.edit().putString(key, id).commit()) { "无法保存反馈身份" }
    return id
}
