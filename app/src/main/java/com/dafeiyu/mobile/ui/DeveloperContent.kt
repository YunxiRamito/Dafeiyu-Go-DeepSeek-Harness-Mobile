package com.dafeiyu.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dafeiyu.mobile.data.DemoRepository
import com.dafeiyu.mobile.data.DeveloperAdminClient
import com.dafeiyu.mobile.data.DeveloperAdminPage
import com.dafeiyu.mobile.data.DeveloperCenterClient
import com.dafeiyu.mobile.data.FeedbackAttachmentStore
import com.dafeiyu.mobile.data.MobileRecommendationAdmin
import com.dafeiyu.mobile.data.MobileRecommendationDocument
import com.dafeiyu.mobile.ui.components.FishButton
import com.dafeiyu.mobile.ui.components.GlassMotion
import com.dafeiyu.mobile.ui.components.LocalReduceGlassMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/** Credentials live only in this composition and are not saveable, backed up or logged. */
@Composable
internal fun DeveloperContent(repository: DemoRepository) {
    if (!repository.developerMode) { Hint("请在关于页面连续点击版本号五次开启。开启入口后仍需管理身份验证。"); return }
    val context = LocalContext.current.applicationContext
    val client = remember(context) { DeveloperAdminClient(context) }
    val publicClient = remember { DeveloperCenterClient() }
    val recommendationClient = remember { MobileRecommendationAdmin() }
    val attachments = remember(context) { FeedbackAttachmentStore(context) }
    val scope = rememberCoroutineScope()
    val reduced = LocalReduceGlassMotion.current
    val modelRuntimeDetail = repository.modelRuntime?.state?.collectAsState()?.value?.detail.orEmpty()
    var token by remember { mutableStateOf("") }
    var githubToken by remember { mutableStateOf("") }
    var section by remember { mutableStateOf("messages") }
    var busy by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf<DeveloperAdminPage?>(null) }
    var offset by remember { mutableIntStateOf(0) }
    var previousOffsets by remember { mutableStateOf<List<Int>>(emptyList()) }
    var acknowledgements by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, suspend () -> Unit>?>(null) }
    var formKind by remember { mutableStateOf("Announcement") }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf("") }
    var buttons by remember { mutableStateOf("[]") }
    var status by remember { mutableStateOf("Submitted") }
    var category by remember { mutableStateOf("Suggestion") }
    var reply by remember { mutableStateOf("") }
    var banReason by remember { mutableStateOf("") }
    var banHours by remember { mutableStateOf("24") }
    var role by remember { mutableStateOf("") }
    var contribution by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var sortOrder by remember { mutableStateOf("") }
    var skills by remember { mutableStateOf(true) }
    var recommendation by remember { mutableStateOf<MobileRecommendationDocument?>(null) }
    var recommendationText by remember { mutableStateOf("") }
    var detailText by remember { mutableStateOf<String?>(null) }
    var logs by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var logNext by remember { mutableStateOf<Int?>(0) }
    var supplements by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var supplementNext by remember { mutableStateOf<Int?>(null) }
    var imageToView by remember { mutableStateOf<DeveloperCenterClient.FeedbackImage?>(null) }

    fun execute(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null; note = null
        operation = scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { note = "操作已取消；已发送的修改请刷新确认"; throw cancelled }
            catch (failure: Exception) { error = developerFailure(failure) }
            finally { busy = false }
        }
    }
    fun clearForm() {
        selected = null; title = ""; body = ""; date = ""; expiry = ""; buttons = "[]"; formKind = "Announcement"
        status = "Submitted"; category = if (section == "acknowledgements") "Person" else "Suggestion"
        reply = ""; banReason = ""; banHours = "24"; role = ""; contribution = ""; link = ""; sortOrder = ""
        logs = emptyList(); logNext = 0; supplements = emptyList(); supplementNext = null; imageToView = null
    }
    suspend fun refresh() {
        when (section) {
            "messages" -> page = client.messages(token, offset)
            "feedback" -> page = client.feedback(token, offset)
            "acknowledgements" -> acknowledgements = client.acknowledgements(token)
            "bans" -> page = client.bans(token, offset)
        }
        clearForm()
    }
    fun choose(item: JSONObject) {
        selected = item; detailText = null; logs = emptyList(); logNext = 0
        title = item.optString(if (section == "acknowledgements") "name" else "title")
        body = item.optString("markdown")
        formKind = if (item.optString("kind").equals("Notification", true)) "Notification" else "Announcement"
        date = item.optString("date").takeIf { it != "null" }.orEmpty()
        expiry = item.optString("expiresAt").takeIf { it != "null" }.orEmpty()
        buttons = item.optJSONArray("buttons")?.toString() ?: "[]"
        status = item.optString("status", "Submitted"); category = item.optString("category", "Suggestion")
        role = item.optString("role").takeIf { it != "null" }.orEmpty()
        contribution = item.optString("contribution").takeIf { it != "null" }.orEmpty()
        link = item.optString("link").takeIf { it != "null" }.orEmpty()
        sortOrder = item.optString("sortOrder")
        supplements = item.optJSONArray("supplements")?.let { values -> List(values.length()) { values.getJSONObject(it) } }.orEmpty()
        supplementNext = if (item.isNull("nextSupplementOffset")) null else item.getInt("nextSupplementOffset")
        reply = ""; banReason = ""; banHours = "24"
    }
    DisposableEffect(Unit) { onDispose { operation?.cancel(); attachments.clear() } }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val id = selected?.optString("id")
        if (uri != null && section == "acknowledgements" && id != null) execute {
            val image = attachments.importImage(uri)
            try { client.uploadAvatar(token, id, attachments.upload(listOf(image)).single()); note = "头像已更新"; refresh() }
            finally { attachments.remove(image) }
        }
    }

    DetailCard {
        Text("管理身份", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(token, { token = it }, label = { Text("DeveloperCenter 管理令牌") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        Hint("令牌仅在本页面使用，离开后释放。手机公告和反馈使用独立通道。")
        FishButton(onClick = { token = ""; githubToken = ""; page = null; acknowledgements = emptyList(); clearForm(); recommendation = null; recommendationText = ""; detailText = null },
            accent = false, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("退出管理身份") }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((key, label) in listOf("messages" to "公告与通知", "feedback" to "反馈管理", "bans" to "限制记录",
            "recommendations" to "手机推荐", "acknowledgements" to "公开致谢", "tools" to "工具")) {
            FilterChip(section == key, enabled = !busy, onClick = {
                section = key; page = null; clearForm(); offset = 0; previousOffsets = emptyList(); detailText = null; note = null; error = null
            }, label = { Text(label) })
        }
    }
    AnimatedVisibility(busy || error != null || note != null,
        enter = fadeIn(GlassMotion.scalar(GlassMotion.MorphMillis, reduced)), exit = fadeOut(GlassMotion.scalar(GlassMotion.MorphMillis, reduced))) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = { operation?.cancel() }) { Text("取消操作") } }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            note?.let { Hint(it) }
        }
    }
    AnimatedContent(section, transitionSpec = {
        fadeIn(GlassMotion.scalar(GlassMotion.MorphMillis, reduced)) togetherWith fadeOut(GlassMotion.scalar(GlassMotion.MorphMillis, reduced))
    }, label = "developer-section") { current ->
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (current in setOf("messages", "feedback", "acknowledgements", "bans")) {
                FishButton(onClick = { execute { refresh() } }, enabled = !busy && token.isNotBlank(), accent = false,
                    modifier = Modifier.fillMaxWidth()) { Text("刷新") }
                val rows = when (current) { "acknowledgements" -> acknowledgements; else -> page?.items.orEmpty() }
                rows.forEachIndexed { index, item ->
                    DetailCard {
                        Text(item.optString(if (current == "acknowledgements") "name" else if (current == "bans") "reason" else "title")
                            .ifBlank { item.optString("body").lineSequence().firstOrNull().orEmpty().take(100) }, style = MaterialTheme.typography.titleMedium)
                        Hint(if (current == "messages") "${item.optString("kind")} · ${item.optString("state")}" else
                            if (current == "feedback") "${developerStatus(item.optString("status"))} · ${if (item.optString("category") == "Bug") "漏洞反馈" else "需求建议"}" else
                            if (current == "bans") "${item.optString("machineCode").take(16)} · ${item.optString("expiresAt").takeIf { it != "null" } ?: "永久限制"}" else item.optString("role").takeIf { it != "null" }.orEmpty())
                        if (current == "bans") FishButton(onClick = {
                            confirm = "解除这条提交限制？" to { client.unban(token, item.getString("id")); refresh() }
                        }, enabled = !busy, accent = false, modifier = Modifier.fillMaxWidth()) { Text("解除限制") }
                        else FishButton(onClick = { choose(item) }, enabled = !busy, accent = false, modifier = Modifier.fillMaxWidth()) { Text("查看与管理") }
                        if (current == "acknowledgements") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            fun move(to: Int) = execute {
                                val reordered = acknowledgements.toMutableList().also { it.add(to, it.removeAt(index)) }
                                client.reorderAcknowledgements(token, reordered.map { it.getString("id") }); refresh(); note = "显示顺序已保存"
                            }
                            TextButton(onClick = { move(index - 1) }, enabled = !busy && index > 0) { Text("上移") }
                            TextButton(onClick = { move(index + 1) }, enabled = !busy && index < rows.lastIndex) { Text("下移") }
                        }
                    }
                }
                if (rows.isEmpty()) Hint(if (page == null && current !in setOf("acknowledgements", "bans")) "刷新后读取管理内容" else "当前没有可显示的内容")
                if (current in setOf("messages", "feedback", "bans")) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FishButton(onClick = { execute { offset = previousOffsets.last(); previousOffsets = previousOffsets.dropLast(1); refresh() } },
                        enabled = !busy && previousOffsets.isNotEmpty(), accent = false, modifier = Modifier.weight(1f)) { Text("上一页") }
                    FishButton(onClick = { val next = page?.nextOffset; if (next != null) execute { previousOffsets += offset; offset = next; refresh() } },
                        enabled = !busy && page?.nextOffset != null, accent = false, modifier = Modifier.weight(1f)) { Text("下一页") }
                }
            }
            when (current) {
                "messages" -> DetailCard {
                    Text(if (selected == null) "新建草稿" else "消息详情", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((key, label) in listOf("Announcement" to "公告", "Notification" to "通知"))
                            FilterChip(formKind == key, { formKind = key }, label = { Text(label) }, enabled = !busy && selected?.optString("state")?.equals("draft", true) != false)
                    }
                    val editable = !busy && selected?.optString("state")?.equals("draft", true) != false
                    OutlinedTextField(title, { title = it }, label = { Text("标题") }, modifier = Modifier.fillMaxWidth(), enabled = editable)
                    OutlinedTextField(body, { body = it }, label = { Text("正文，支持 Markdown") }, modifier = Modifier.fillMaxWidth(), minLines = 4, enabled = editable)
                    OutlinedTextField(date, { date = it }, label = { Text("显示日期，例如 2026-10-13") }, modifier = Modifier.fillMaxWidth(), enabled = editable)
                    OutlinedTextField(expiry, { expiry = it }, label = { Text("到期时间，可留空，例如 2026-12-01T00:00:00Z") }, modifier = Modifier.fillMaxWidth(), enabled = editable)
                    if (formKind == "Notification") OutlinedTextField(buttons, { buttons = it }, label = { Text("操作按钮 JSON，最多两个") },
                        supportingText = { Text("例如 [{\"label\":\"查看\",\"action\":\"Url\",\"url\":\"https://example.com\"}]") }, modifier = Modifier.fillMaxWidth(), enabled = editable)
                    FishButton(onClick = { execute {
                        val draft = JSONObject().put("kind", formKind).put("title", title).put("markdown", body)
                            .put("date", date.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                            .put("expiresAt", expiry.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                            .put("buttons", if (formKind == "Notification") JSONArray(buttons) else JSONArray())
                        choose(client.saveMessage(token, selected?.getString("id"), draft)); note = "草稿已保存"
                    } }, enabled = !busy && token.isNotBlank() && selected?.optString("state")?.equals("draft", true) != false,
                        modifier = Modifier.fillMaxWidth()) { Text("保存草稿") }
                    selected?.let { item ->
                        val state = item.getString("state").lowercase()
                        if (state == "draft") FishButton(onClick = { confirm = "发布这条手机${if (formKind == "Announcement") "公告" else "通知"}？" to {
                            client.publishMessage(token, item.getString("id")); refresh(); note = "已发布"
                        } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("发布") }
                        if (state == "published") FishButton(onClick = { confirm = "撤回这条已发布消息？" to {
                            client.withdrawMessage(token, item.getString("id")); refresh(); note = "已撤回"
                        } }, enabled = !busy, accent = false, modifier = Modifier.fillMaxWidth()) { Text("撤回") }
                        TextButton(onClick = { execute { detailText = client.messageMetrics(token, item.getString("id")).toString(2) } }, enabled = !busy) { Text("查看投递与阅读指标") }
                        if (state == "draft") TextButton(onClick = { confirm = "永久删除此草稿？" to { client.removeDraft(token, item.getString("id")); refresh() } }, enabled = !busy) { Text("删除草稿") }
                        TextButton(onClick = { clearForm() }, enabled = !busy) { Text("新建另一条") }
                    }
                }
                "feedback" -> selected?.let { item -> DetailCard {
                    Text(item.optString("body"), style = MaterialTheme.typography.bodyMedium)
                    DeveloperFeedbackImages(item.optJSONArray("images")) { imageToView = it }
                    supplements.forEach { supplement ->
                        Hint(if (supplement.optBoolean("isDeveloperReply")) "开发者回复" else "用户补充")
                        Text(supplement.optString("body"))
                        DeveloperFeedbackImages(supplement.optJSONArray("images")) { imageToView = it }
                    }
                    supplementNext?.let { next -> TextButton(onClick = { execute {
                        val result = client.feedbackSupplements(token, item.getString("id"), next)
                        val values = result.getJSONArray("supplements")
                        supplements += List(values.length()) { values.getJSONObject(it) }
                        supplementNext = if (result.isNull("nextOffset")) null else result.getInt("nextOffset")
                    } }, enabled = !busy) { Text("更多补充与回复") } }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DeveloperAdminClient.STATUSES.forEach { value -> FilterChip(status == value, { status = value }, label = { Text(developerStatus(value)) }, enabled = !busy) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((value, label) in listOf("Suggestion" to "需求建议", "Bug" to "漏洞反馈")) FilterChip(category == value, { category = value }, label = { Text(label) }, enabled = !busy)
                    }
                    FishButton(onClick = { execute { client.updateFeedback(token, item.getString("id"), status, category); refresh(); note = "反馈状态已保存" } },
                        enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("保存状态与分类") }
                    OutlinedTextField(reply, { reply = it }, label = { Text("开发者回复") }, modifier = Modifier.fillMaxWidth(), minLines = 3, enabled = !busy)
                    FishButton(onClick = { confirm = "发送这条开发者回复？" to { client.reply(token, item.getString("id"), reply); reply = ""; refresh(); note = "回复已保存" } },
                        enabled = !busy && reply.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("发送回复") }
                    FishButton(onClick = { execute {
                        val next = logNext ?: 0
                        val result = client.feedbackLogs(token, item.getString("id"), next)
                        val values = result.getJSONArray("logs"); logs += List(values.length()) { values.getJSONObject(it) }
                        logNext = if (result.isNull("nextOffset")) null else result.getInt("nextOffset")
                    } }, enabled = !busy && logNext != null, accent = false, modifier = Modifier.fillMaxWidth()) { Text("读取诊断日志列表") }
                    logs.forEach { log -> TextButton(onClick = { execute { detailText = com.dafeiyu.mobile.data.DeveloperAdminClient.decode(
                        client.readLog(token, item.getString("id"), log.getString("id"))) } }, enabled = !busy) { Text("查看 ${log.optString("createdAt")} · ${log.optInt("bytes") / 1024} KiB") } }
                    OutlinedTextField(banReason, { banReason = it }, label = { Text("限制原因") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                    OutlinedTextField(banHours, { banHours = it }, label = { Text("限制小时数，留空为永久") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                    TextButton(onClick = { confirm = "限制此反馈来源继续提交？" to {
                        val hours = banHours.takeIf { it.isNotBlank() }?.toInt(); client.ban(token, item.getString("id"), banReason, hours, false); refresh()
                    } }, enabled = !busy && banReason.isNotBlank()) { Text("限制提交") }
                    TextButton(onClick = { confirm = "永久删除此手机反馈及附件？" to { client.removeFeedback(token, item.getString("id")); refresh() } }, enabled = !busy) { Text("删除反馈") }
                } }
                "recommendations" -> DetailCard {
                    Text("手机独立推荐清单", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(githubToken, { githubToken = it }, label = { Text("GitHub 仓库写入令牌") }, visualTransformation = PasswordVisualTransformation(),
                        singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(skills, { skills = true; recommendation = null; recommendationText = "" }, label = { Text("技能") }, enabled = !busy)
                        FilterChip(!skills, { skills = false; recommendation = null; recommendationText = "" }, label = { Text("DSH 插件") }, enabled = !busy)
                    }
                    FishButton(onClick = { execute { recommendation = recommendationClient.read(skills, githubToken); recommendationText = requireNotNull(recommendation).text } },
                        enabled = !busy && githubToken.isNotBlank(), accent = false, modifier = Modifier.fillMaxWidth()) { Text("从 GitHub 读取") }
                    OutlinedTextField(recommendationText, { recommendationText = it }, label = { Text("推荐清单") }, minLines = 8, modifier = Modifier.fillMaxWidth(), enabled = !busy && recommendation != null)
                    FishButton(onClick = { val before = recommendation; if (before != null) confirm = "保存并发布手机推荐清单到 GitHub main？" to {
                        val sha = recommendationClient.save(before, recommendationText, githubToken)
                        recommendation = before.copy(sha = sha, text = recommendationText); note = "推荐清单已保存"
                    } }, enabled = !busy && recommendation != null && githubToken.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("保存到 GitHub") }
                }
                "acknowledgements" -> DetailCard {
                    Text(if (selected == null) "新增致谢" else "编辑致谢", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(title, { title = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(category == "Person", { category = "Person" }, label = { Text("个人") }, enabled = !busy)
                        FilterChip(category == "Organization", { category = "Organization" }, label = { Text("组织") }, enabled = !busy)
                    }
                    OutlinedTextField(role, { role = it }, label = { Text("身份") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                    OutlinedTextField(contribution, { contribution = it }, label = { Text("贡献") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                    OutlinedTextField(link, { link = it }, label = { Text("HTTPS 链接，可留空") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                    OutlinedTextField(sortOrder, { sortOrder = it }, label = { Text("显示顺序，可留空") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                    FishButton(onClick = { execute {
                        val value = JSONObject().put("name", title).put("role", role).put("contribution", contribution)
                            .put("category", category).put("link", link).put("sortOrder", sortOrder.takeIf { it.isNotBlank() }?.toInt() ?: JSONObject.NULL)
                        client.saveAcknowledgement(token, selected?.getString("id"), value); refresh(); note = "致谢已保存"
                    } }, enabled = !busy && token.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("保存致谢") }
                    selected?.let { item ->
                        TextButton(onClick = { avatarPicker.launch("image/*") }, enabled = !busy) { Text("更换头像") }
                        TextButton(onClick = { execute { client.removeAvatar(token, item.getString("id")); refresh() } }, enabled = !busy) { Text("移除头像") }
                        TextButton(onClick = { confirm = "删除此公开致谢？" to { client.removeAcknowledgement(token, item.getString("id")); refresh() } }, enabled = !busy) { Text("删除致谢") }
                        TextButton(onClick = { clearForm() }, enabled = !busy) { Text("新增另一条") }
                    }
                }
                "tools" -> DetailCard {
                    Text("运行诊断", style = MaterialTheme.typography.titleMedium)
                    Hint("${repository.runtimeStep}\n$modelRuntimeDetail\nAries：${repository.ariesConnectionState}")
                    FishButton(onClick = repository::refreshRemoteDsh, enabled = !busy && repository.connectionMode == "online", accent = false, modifier = Modifier.fillMaxWidth()) { Text("重新读取电脑状态") }
                    FishButton(onClick = { repository.updateDeveloperMode(false) }, enabled = !busy, accent = false, modifier = Modifier.fillMaxWidth()) { Text("关闭开发者入口") }
                }
            }
            detailText?.let { value -> DetailCard { Text(value.take(128 * 1024), style = MaterialTheme.typography.bodySmall); TextButton(onClick = { detailText = null }) { Text("收起详情") } } }
        }
    }
    confirm?.let { (message, action) -> AlertDialog(onDismissRequest = { if (!busy) confirm = null }, title = { Text("确认操作") },
        text = { Text(message) }, confirmButton = { TextButton(onClick = { confirm = null; execute(action) }, enabled = !busy) { Text("确认") } },
        dismissButton = { TextButton(onClick = { confirm = null }, enabled = !busy) { Text("取消") } }) }
    imageToView?.let { image -> FeedbackImageDialog(publicClient, image) { imageToView = null } }
}

@Composable
private fun DeveloperFeedbackImages(values: JSONArray?, onImage: (DeveloperCenterClient.FeedbackImage) -> Unit) {
    val images = remember(values?.toString()) { runCatching {
        if (values == null) emptyList() else List(values.length()) { index ->
            val image = values.getJSONObject(index)
            DeveloperCenterClient.FeedbackImage(UUID.fromString(image.getString("id")), image.getString("url"),
                image.getInt("width"), image.getInt("height"), image.getInt("bytes"))
        }
    }.getOrDefault(emptyList()) }
    images.forEachIndexed { index, image -> TextButton(onClick = { onImage(image) }) { Text("查看图片 ${index + 1} · ${image.width} × ${image.height}") } }
}

private fun developerFailure(failure: Exception): String {
    val value = failure.message.orEmpty()
    return if (failure is IOException && (value.startsWith("管理") || value.startsWith("GitHub 未确认"))) value.take(300)
        else "输入内容或服务回执无效，请检查填写内容并刷新确认结果"
}

private fun developerStatus(value: String): String = when (value) {
    "Submitted" -> "已提交"; "Processing" -> "处理中"; "Completed" -> "已完成"; "Deferred" -> "暂不处理"; else -> value
}
