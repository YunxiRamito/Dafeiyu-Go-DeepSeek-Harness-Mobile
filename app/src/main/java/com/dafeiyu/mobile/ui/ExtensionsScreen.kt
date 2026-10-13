package com.dafeiyu.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dafeiyu.mobile.data.ArtifactDownloadProgress
import com.dafeiyu.mobile.data.DemoRepository
import com.dafeiyu.mobile.data.ExtensionPackageCatalog
import com.dafeiyu.mobile.data.ExtensionPackageResult
import com.dafeiyu.mobile.data.ExtensionPackageStore
import com.dafeiyu.mobile.data.ExtensionManagementFailure
import com.dafeiyu.mobile.data.ExtensionRecommendation
import com.dafeiyu.mobile.data.GithubExtensionReference
import com.dafeiyu.mobile.data.GithubExtensionSource
import com.dafeiyu.mobile.data.InstalledExtensionPackage
import com.dafeiyu.mobile.data.InstalledSkill
import com.dafeiyu.mobile.data.LiveExtensionPlugin
import com.dafeiyu.mobile.data.NpmExtensionCatalog
import com.dafeiyu.mobile.data.RecommendationFeed
import com.dafeiyu.mobile.data.SkillCatalog
import com.dafeiyu.mobile.data.SkillInstallStore
import com.dafeiyu.mobile.ui.components.FishButton
import com.dafeiyu.mobile.ui.components.FishCard
import com.dafeiyu.mobile.ui.components.FishGlyph
import com.dafeiyu.mobile.ui.components.GlassMotion
import com.dafeiyu.mobile.ui.components.LocalReduceGlassMotion
import com.dafeiyu.mobile.ui.components.ToggleRow
import com.dafeiyu.mobile.ui.components.applyButtonDeformation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private data class ExtensionUiEntry(val id: String, val name: String, val description: String, val source: String,
    val version: String = "", val skill: InstalledSkill? = null, val plugin: InstalledExtensionPackage? = null,
    val github: GithubExtensionReference? = null, val npm: String? = null, val note: String = "")
private data class ExtensionConfirmation(val title: String, val message: String, val label: String, val operation: suspend () -> String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(repository: DemoRepository, category: String = "plugin") {
    val isSkill = category == "skill"
    val title = if (isSkill) "技能" else "插件"
    val context = LocalContext.current
    val skills = remember(context) { SkillInstallStore(context) }
    val packages = remember(context) { ExtensionPackageStore(context) }
    val dshService by packages.serviceSnapshot.collectAsState()
    val github = remember(context) { GithubExtensionSource(context) }
    val npmCatalog = remember { NpmExtensionCatalog() }
    val feed = remember(context) { RecommendationFeed(context) }
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val reduced = LocalReduceGlassMotion.current
    val connection = rememberConnectionPluginUiState(0)
    var pluginScope by rememberSaveable { mutableStateOf("dafeiyu") }
    var page by rememberSaveable { mutableStateOf("installed") }
    var query by rememberSaveable { mutableStateOf("") }
    var searchSource by rememberSaveable { mutableStateOf("github") }
    var revision by remember { mutableIntStateOf(0) }
    var skillCatalog by remember { mutableStateOf(SkillCatalog(emptyList(), emptyList())) }
    var pluginCatalog by remember { mutableStateOf(ExtensionPackageCatalog(emptyList(), emptyList(), false)) }
    var recommendations by remember { mutableStateOf<List<ExtensionRecommendation>>(emptyList()) }
    var searchResults by remember { mutableStateOf<List<ExtensionUiEntry>>(emptyList()) }
    var feedWarning by remember { mutableStateOf<String?>(null) }
    var scanWarning by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var livePlugins by remember { mutableStateOf<List<LiveExtensionPlugin>>(emptyList()) }
    var liveWarning by remember { mutableStateOf<String?>(null) }
    var operation by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val progressState = remember { MutableStateFlow<ArtifactDownloadProgress?>(null) }
    val progress by progressState.collectAsState()
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var failed by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var skillBody by remember { mutableStateOf<String?>(null) }
    var showImport by remember { mutableStateOf(false) }
    var importValue by rememberSaveable { mutableStateOf("") }
    var importSource by rememberSaveable { mutableStateOf("github") }
    var replaceSkill by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<ExtensionConfirmation?>(null) }
    val busy = operation != null
    val managedPage = isSkill || pluginScope == "dsh"
    val listState = rememberLazyListState()
    val collapseRange = with(LocalDensity.current) { 112.dp.toPx() }
    val collapse by remember(listState, collapseRange) { derivedStateOf {
        if (listState.firstVisibleItemIndex > 0) 1f else (listState.firstVisibleItemScrollOffset / collapseRange).coerceIn(0f, 1f)
    } }

    fun resultText(result: ExtensionPackageResult): String = buildString {
        append("插件配置已保存")
        if (result.restartRequired) append("，启动或重启 DSH 后生效")
        if (result.warnings.isNotEmpty()) append("\n" + result.warnings.joinToString("\n"))
    }
    fun perform(label: String, action: suspend () -> String) {
        if (operation != null) return
        operation = label; message = null; progressState.value = null
        job = scope.launch {
            try { message = action(); failed = false; revision++ }
            catch (cancelled: CancellationException) { message = "操作已取消，请刷新当前状态。"; failed = false; throw cancelled }
            catch (error: ExtensionManagementFailure) {
                message = error.message; failed = true
                if (error.incompatible.isNotEmpty()) confirmation = ExtensionConfirmation("允许此版本继续安装？",
                    error.incompatible.joinToString("\n") { issue -> "${issue.name} ${issue.version} · DSH ${issue.runtimeVersion}" } +
                        "\n\n这些精确版本未满足插件声明的兼容范围，继续可能导致崩溃或数据损坏。授权仅对列出的插件与 DSH 版本有效，可在已安装页撤销。", "接受风险并继续") {
                    resultText(packages.retryWithVersionConsent(error, repository.downloadSource, acceptRisk = true) { progressState.value = it })
                }
                else if (error.builds.isNotEmpty()) confirmation = ExtensionConfirmation("运行插件构建脚本？",
                    extensionBuildConsentText(error.builds), "授权并继续") {
                    resultText(packages.retryWithBuildConsent(error, repository.downloadSource, acceptRisk = true) { progressState.value = it })
                }
            }
            catch (error: Exception) { message = error.message ?: "操作未完成，请刷新后重试"; failed = true }
            finally { operation = null; progressState.value = null }
        }
    }
    val reportProgress: (ArtifactDownloadProgress) -> Unit = { progressState.value = it }
    val localPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) perform("正在导入$title") {
            if (isSkill) "已安装 ${skills.import(uri, replaceSkill).size} 个技能；轻引擎与 DSH 共用。"
            else resultText(packages.import(uri, repository.downloadSource, progress = reportProgress))
        }
    }
    LaunchedEffect(revision, isSkill, pluginScope) {
        scanning = true; scanWarning = null
        try {
            if (isSkill) skillCatalog = skills.scan()
            else if (managedPage) pluginCatalog = packages.scan()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { scanWarning = error.message ?: "目录读取失败，未把错误当作空目录" }
        finally { scanning = false }
    }
    LaunchedEffect(revision, isSkill, pluginScope, dshService) {
        livePlugins = emptyList(); liveWarning = null
        if (!isSkill && managedPage) {
            try { livePlugins = packages.liveEntries() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { liveWarning = error.message ?: "运行中的插件目录暂不可读" }
        }
    }
    LaunchedEffect(page, isSkill, pluginScope) {
        if (page == "recommended" && managedPage) {
            try { val result = feed.load(isSkill, repository.downloadSource); recommendations = result.items; feedWarning = result.warning }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { feedWarning = error.message }
        }
    }
    val installed = if (isSkill) skillCatalog.items.map { item -> ExtensionUiEntry("skill:${item.metadata.name}", item.metadata.name,
        item.metadata.description, item.source, item.commit?.take(12).orEmpty(), skill = item) }
    else pluginCatalog.items.map { item -> ExtensionUiEntry("plugin:${item.name}", item.name, item.description, item.source,
        item.version, plugin = item) }
    val recommended = recommendations.map { item -> ExtensionUiEntry("recommended:${item.id}", item.name, item.description,
        item.github?.url ?: item.npm.orEmpty(), github = item.github, npm = item.npm, note = item.note) }
    val selected = (installed + recommended + searchResults).firstOrNull { it.id == selectedId }
    val visible = (when (page) { "recommended" -> recommended; "search" -> searchResults; else -> installed })
        .filter { page == "search" || query.isBlank() || (it.name + it.description + it.source).contains(query, true) }
    LaunchedEffect(selected?.id, revision) {
        skillBody = null
        selected?.skill?.let { item ->
            try { skillBody = skills.inspect(item.metadata.name).body }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { skillBody = error.message }
        }
    }
    PublishSettingsNavigation(title, collapse,
        if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else null)

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 22.dp), state = listState,
        contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            PageHeader(title, if (isSkill) "为轻引擎与 DSH 添加可读取的技能。" else "管理应用插件与 DSH 扩展。", action = {
                Row {
                    IconButton(onClick = { revision++; connection.refresh() }, enabled = !scanning && !busy,
                        modifier = Modifier.applyButtonDeformation(circularHit = true, drawShadow = false)
                            .semantics { contentDescription = "刷新$title" }) {
                        if (scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else FishGlyph("refresh", Modifier.size(21.dp))
                    }
                    IconButton(onClick = { showImport = true }, enabled = managedPage && !busy,
                        modifier = Modifier.applyButtonDeformation(circularHit = true, drawShadow = false)
                            .semantics { contentDescription = "安装$title" }) { FishGlyph("add", Modifier.size(21.dp)) }
                }
            }, collapseProgress = collapse)
            if (!isSkill) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("dafeiyu" to "Dafeiyu 插件", "dsh" to "DSH 插件").forEach { (key, label) ->
                    FilterChip(pluginScope == key, onClick = { pluginScope = key; page = "installed"; selectedId = null; query = "" },
                        label = { Text(label) }, modifier = Modifier.weight(1f))
                }
            }
            AnimatedVisibility(message != null || busy, enter = fadeIn(GlassMotion.scalar(reducedMotion = reduced)),
                exit = fadeOut(GlassMotion.scalar(reducedMotion = reduced))) {
                FishCard(modifier = Modifier.padding(top = 12.dp).animateContentSize(tween(if (reduced) 0 else GlassMotion.MorphMillis, easing = GlassMotion.EaseOut))) {
                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(operation ?: message.orEmpty(), Modifier.weight(1f), fontSize = 13.sp, lineHeight = 19.sp,
                                color = if (failed && !busy) colors.error else colors.onSurface)
                            if (busy) TextButton(onClick = { job?.cancel() }) { Text("取消") }
                            else IconButton(onClick = { message = null }, modifier = Modifier.size(32.dp)
                                .applyButtonDeformation(circularHit = true, drawShadow = false)) { FishGlyph("close", Modifier.size(16.dp)) }
                        }
                        progress?.let { current ->
                            val total = current.totalBytes
                            if (total != null && total > 0) LinearProgressIndicator(progress = { (current.receivedBytes.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(formatExtensionBytes(current.receivedBytes) + (total?.let { " / ${formatExtensionBytes(it)}" } ?: "") +
                                (if (current.threads > 1) " · ${current.threads} 线程" else ""), fontSize = 11.sp, color = colors.onSurfaceVariant)
                            current.reason?.let { Text(it, fontSize = 11.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant) }
                        }
                    }
                }
            }
            if (managedPage) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("installed" to "已安装", "recommended" to "官方推荐", "search" to "在线搜索").forEach { (key, label) ->
                        FilterChip(page == key, onClick = { page = key; selectedId = null }, label = { Text(label, fontSize = 12.sp) }, modifier = Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text(if (page == "search") "输入关键词搜索" else "搜索$title") }, leadingIcon = { FishGlyph("search", Modifier.size(18.dp)) })
                if (page == "search") Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (!isSkill) {
                        FilterChip(searchSource == "github", onClick = { searchSource = "github" }, label = { Text("GitHub") })
                        Spacer(Modifier.width(7.dp)); FilterChip(searchSource == "npm", onClick = { searchSource = "npm" }, label = { Text("npm") })
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { perform("正在搜索$title") {
                        searchResults = if (isSkill || searchSource == "github") github.search(query.trim(), isSkill).map { value ->
                            ExtensionUiEntry("github:${value.reference.owner}/${value.reference.repository}", "${value.reference.owner}/${value.reference.repository}",
                                value.description, value.reference.url, github = value.reference)
                        } else npmCatalog.search(query.trim()).map { value -> ExtensionUiEntry("npm:${value.name}", value.name,
                            value.description, value.url, value.version, npm = "${value.name}@${value.version}") }
                        "找到 ${searchResults.size} 项；安装前会校验完整包。"
                    } }, enabled = query.isNotBlank() && !busy) { Text("搜索") }
                }
                if (page == "recommended") TextButton(onClick = { perform("正在刷新推荐清单") {
                    val value = feed.load(isSkill, repository.downloadSource, refresh = true)
                    recommendations = value.items; feedWarning = value.warning; value.warning ?: "推荐清单已刷新"
                } }, enabled = !busy) { Text("刷新推荐") }
                val warnings = when (page) { "recommended" -> listOfNotNull(feedWarning)
                    "installed" -> listOfNotNull(scanWarning, liveWarning) + if (isSkill) skillCatalog.warnings else pluginCatalog.warnings
                    else -> emptyList() }
                warnings.take(5).forEach { Text(it, Modifier.padding(top = 8.dp), fontSize = 12.sp, lineHeight = 18.sp, color = colors.error) }
                if (!isSkill && packages.recoveryRequired()) TextButton(onClick = {
                    confirmation = ExtensionConfirmation("恢复插件配置？", "恢复上次插件操作前的配置。请先停止本机服务；恢复后可重新安装或修复依赖。", "恢复") {
                        resultText(packages.recover())
                    }
                }, enabled = !busy) { Text("恢复上次中断的插件配置") }
                if (!isSkill && page == "installed" && pluginCatalog.exemptions.isNotEmpty()) {
                    Text("已允许的兼容版本", Modifier.padding(top = 12.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    pluginCatalog.exemptions.forEach { exemption ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${exemption.packageVersion}\nDSH ${exemption.runtimeVersion}", Modifier.weight(1f),
                                fontSize = 11.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant)
                            TextButton(onClick = { confirmation = ExtensionConfirmation("撤销兼容授权？",
                                "${exemption.packageVersion} 将重新接受 DSH ${exemption.runtimeVersion} 的兼容检查。", "撤销") {
                                resultText(packages.setVersionExemption(exemption, false))
                            } }, enabled = !busy) { Text("撤销") }
                        }
                    }
                }
                if (!isSkill && page == "installed" && pluginCatalog.builds.isNotEmpty()) {
                    Text("尚未运行的构建脚本", Modifier.padding(top = 12.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("这些依赖已安装，构建脚本仍需要你的许可。", fontSize = 11.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant)
                    pluginCatalog.builds.forEach { candidate ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(candidate.key, Modifier.weight(1f), fontSize = 11.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant)
                            TextButton(onClick = { confirmation = ExtensionConfirmation("运行此依赖的构建脚本？",
                                extensionBuildConsentText(listOf(candidate)), "授权并构建") {
                                resultText(packages.buildDependencies(listOf(candidate), repository.downloadSource,
                                    acceptRisk = true, progress = reportProgress))
                            } }, enabled = !busy) { Text("授权构建") }
                        }
                    }
                }
                if (repository.connectionMode != "local") Text("这里管理手机上的$title。在线任务使用电脑的配置，这些修改不会改变电脑。",
                    Modifier.padding(top = 10.dp), fontSize = 12.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant)
            }
        }
        if (!managedPage) {
            item("connect") { ConnectionPluginSection(connection) }
            item("native-files") { FishCard {
                Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    FishGlyph("folder", Modifier.size(26.dp), colors.primary)
                    Column { Text("文件与权限", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("由应用提供文件选择与权限申请，随应用启用。", fontSize = 12.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant) }
                }
            } }
        } else {
            if (isSkill && page == "installed") items(skillCatalog.damaged, key = { "damaged-skill:${it.id}" }) { item ->
                FishCard(modifier = Modifier.animateItem(fadeInSpec = GlassMotion.scalar(reducedMotion = reduced),
                    placementSpec = tween(if (reduced) 0 else GlassMotion.MorphMillis, easing = GlassMotion.EaseOut),
                    fadeOutSpec = GlassMotion.scalar(reducedMotion = reduced))) {
                    Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(item.id, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("需要修复 · ${item.error}", fontSize = 12.sp, lineHeight = 18.sp, color = colors.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                if (item.canRepair) confirmation = ExtensionConfirmation("修复技能？",
                                    "重新取得原来固定的技能版本，当前损坏文件会保留。", "修复") {
                                    skills.repairDamaged(item.id, repository.downloadSource, reportProgress); "技能已修复"
                                } else { replaceSkill = true; showImport = true }
                            }, enabled = !busy) { Text(if (item.canRepair) "修复" else "重新导入修复") }
                            TextButton(onClick = { confirmation = ExtensionConfirmation("移除损坏的技能？",
                                "从技能目录移除此项，原始文件仍会在应用内部保留。", "移除") {
                                skills.removeDamaged(item.id); "技能已移除"
                            } }, enabled = !busy) { Text("移除", color = colors.error) }
                        }
                    }
                }
            }
            if (!isSkill && page == "installed" && livePlugins.isNotEmpty()) item("live-plugin-entries") {
                FishCard {
                    Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                        Text("运行中的插件入口", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("读取当前 DSH 服务的真实入口；修改会由引擎应用并保存。", fontSize = 11.sp,
                            lineHeight = 17.sp, color = colors.onSurfaceVariant)
                        livePlugins.filter { query.isBlank() || it.moduleName.contains(query, true) }.forEach { entry ->
                            ToggleRow(entry.moduleName, if (entry.readOnlyReason != null) "由引擎管理，不能在此修改"
                                else when (entry.phase) { "active" -> "正在运行"; "loading", "pending" -> "正在启动"
                                    "failed" -> "加载失败"; "unloading" -> "正在停止"; else -> "未运行" },
                                entry.enabled, enabled = !busy && entry.readOnlyReason == null) { enabled ->
                                perform("正在保存插件入口状态") { resultText(packages.setPluginEntryEnabled(entry, enabled)) }
                            }
                        }
                    }
                }
            }
            items(visible, key = { it.id }) { entry -> FishCard(onClick = { selectedId = entry.id }, modifier = Modifier.animateItem(
                fadeInSpec = GlassMotion.scalar(reducedMotion = reduced), placementSpec = tween(if (reduced) 0 else GlassMotion.MorphMillis, easing = GlassMotion.EaseOut),
                fadeOutSpec = GlassMotion.scalar(reducedMotion = reduced))) {
                Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    ExtensionPageIcon(category, Modifier.size(27.dp), colors.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(entry.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(entry.description.ifBlank { entry.source }, fontSize = 12.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val enabled = entry.skill?.enabled ?: entry.plugin?.enabled
                        if (enabled != null) Text(if (entry.plugin?.error != null) "需要修复 · ${entry.plugin.error}" else if (enabled) "已启用" else "已停用",
                            fontSize = 11.sp, color = if (entry.plugin?.error != null) colors.error else colors.onSurfaceVariant)
                    }
                    FishGlyph("chevron", Modifier.size(15.dp), colors.onSurfaceVariant)
                }
            } }
            if (visible.isEmpty() && !scanning) item("empty") { Text(when (page) {
                "recommended" -> "手机推荐清单暂无条目，可在线搜索或从本机安装。"
                "search" -> "输入关键词并搜索，或使用右上角加号填写 GitHub / npm 来源。"
                else -> "没有匹配的已安装$title。"
            }, Modifier.padding(vertical = 28.dp), fontSize = 13.sp, lineHeight = 20.sp, color = colors.onSurfaceVariant) }
        }
        item { Text(if (isSkill) "技能说明会在使用时重新读取。启停同步到本机 DSH；安装包中的脚本由任务工具按权限执行。"
            else if (managedPage) "安装和移除插件需要先停止本机 DSH 与模型运行器；运行中的入口可单独启停。"
            else "Dafeiyu 插件扩展应用能力，DSH 插件管理运行引擎的扩展。", fontSize = 11.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant) }
    }

    if (selected != null) ModalBottomSheet(onDismissRequest = { selectedId = null }, containerColor = colors.surface) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 23.dp).padding(bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ExtensionPageIcon(category, Modifier.size(33.dp), colors.primary)
            Text(selected.name, fontSize = 24.sp, fontWeight = FontWeight.Medium)
            if (selected.version.isNotBlank()) Text(selected.version, fontSize = 12.sp, color = colors.onSurfaceVariant)
            Text(selected.description, fontSize = 14.sp, lineHeight = 22.sp)
            Text("来源：${selected.source}", fontSize = 12.sp, lineHeight = 19.sp, color = colors.onSurfaceVariant)
            if (selected.note.isNotBlank()) Text(selected.note, fontSize = 12.sp, lineHeight = 19.sp)
            selected.skill?.let { item ->
                ToggleRow("启用技能", "两种本机引擎共用此状态。", item.enabled, enabled = !busy && item.userOwned) { enabled ->
                    perform("正在保存技能状态") { skills.setEnabled(item.metadata.name, enabled); "技能状态已保存" }
                }
                Text(if (item.metadata.disableModelInvocation) "不进入模型自动目录。" else "允许模型发现与读取此技能。", fontSize = 12.sp, color = colors.onSurfaceVariant)
                if (!item.metadata.userInvocable) Text("此技能不接受用户命令调用。", fontSize = 12.sp, color = colors.onSurfaceVariant)
                if (item.canUpdate) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { confirmation = ExtensionConfirmation("更新技能？", "替换为来源最新版本。旧文件会保留在应用内部。", "更新") {
                        skills.update(item.metadata.name, repository.downloadSource, reportProgress); "技能已更新" } }, enabled = !busy) { Text("更新") }
                    TextButton(onClick = { confirmation = ExtensionConfirmation("修复技能？", "重新下载已安装的固定版本，替换当前文件。旧文件会保留。", "修复") {
                        skills.repair(item.metadata.name, repository.downloadSource, reportProgress); "技能已修复" } }, enabled = !busy) { Text("修复") }
                } else TextButton(onClick = { replaceSkill = true; showImport = true }, enabled = !busy) { Text("重新导入修复") }
                TextButton(onClick = { confirmation = ExtensionConfirmation("移除技能？", "从两种本机引擎的技能目录移除此项。原文件会移入应用内部保留目录。", "移除") {
                    skills.remove(item.metadata.name); selectedId = null; "技能已移除" } }, enabled = !busy && item.userOwned) { Text("移除技能", color = colors.error) }
                skillBody?.let { Text(it, fontSize = 13.sp, lineHeight = 22.sp) }
            }
            selected.plugin?.let { item ->
                ToggleRow("启用插件", "保存后在下一次启动 DSH 时生效。", item.enabled, enabled = !busy && !item.builtIn) { enabled ->
                    perform("正在保存插件状态") { resultText(packages.setEnabled(item.name, enabled)) }
                }
                item.error?.let { Text("插件校验：$it", fontSize = 12.sp, color = colors.error) }
                if (item.canUpdate) TextButton(onClick = { confirmation = ExtensionConfirmation("更新插件？", "校验新包并保留启用状态。请先停止本机服务。", "更新") {
                    resultText(packages.update(item.name, repository.downloadSource, reportProgress)) } }, enabled = !busy) { Text("检查并更新") }
                if (!item.builtIn) TextButton(onClick = { confirmation = ExtensionConfirmation("修复插件？", "按已安装来源和版本重装依赖。请先停止本机服务。", "修复") {
                    resultText(packages.repair(repository.downloadSource, item.name, reportProgress)) } }, enabled = !busy) { Text("修复") }
                if (item.removable) TextButton(onClick = { confirmation = ExtensionConfirmation("移除插件？", "从本机 DSH 移除此插件。请先停止服务，插件源文件仍会保留。", "移除") {
                    val value = packages.remove(item.name, repository.downloadSource, reportProgress); selectedId = null; resultText(value) } }, enabled = !busy) { Text("移除插件", color = colors.error) }
                if (item.builtIn) Text("此 bundle 为引擎管理所必需，不能停用或移除。", fontSize = 12.sp, color = colors.onSurfaceVariant)
                if (item.rows.isNotEmpty()) Text("包含 ${item.rows.size} 个插件入口", fontSize = 12.sp, color = colors.onSurfaceVariant)
            }
            if (selected.skill == null && selected.plugin == null) FishButton(onClick = {
                if (selected.github != null) { importValue = selected.github.url; importSource = "github" }
                else { importValue = selected.npm.orEmpty(); importSource = "npm" }
                replaceSkill = false; showImport = true
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("安装") }
        }
    }
    if (showImport) AlertDialog(onDismissRequest = { if (!busy) showImport = false }, title = { Text("安装$title") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!isSkill) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(importSource == "github", onClick = { importSource = "github" }, label = { Text("GitHub") })
                FilterChip(importSource == "npm", onClick = { importSource = "npm" }, label = { Text("npm") })
            }
            OutlinedTextField(importValue, { importValue = it }, singleLine = true,
                label = { Text(if (importSource == "npm" && !isSkill) "npm 包名或包名@版本" else "GitHub 仓库或技能子目录") })
            if (isSkill) ToggleRow("替换同名技能", "替换保留此前状态，原文件会保留。", replaceSkill) { replaceSkill = it }
            Text("在线文件先校验固定来源和摘要。本地可选择 ZIP / tar.gz / tgz" + (if (isSkill) " 或 SKILL.md。" else " 插件包。"), fontSize = 12.sp, lineHeight = 19.sp)
            TextButton(onClick = { showImport = false; localPicker.launch(arrayOf("*/*")) }, enabled = !busy) {
                FishGlyph("folder", Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("从本机选择文件")
            }
        }
    }, confirmButton = { TextButton(onClick = {
        val source = importValue; val npm = importSource == "npm" && !isSkill; showImport = false
        perform("正在安装$title") {
            if (isSkill) "已安装 ${skills.install(GithubExtensionReference.parse(source), repository.downloadSource, replaceSkill, reportProgress).size} 个技能"
            else if (npm) resultText(packages.installNpm(source.trim(), repository.downloadSource, progress = reportProgress))
            else resultText(packages.installGithub(GithubExtensionReference.parse(source), repository.downloadSource, progress = reportProgress))
        }
    }, enabled = importValue.isNotBlank() && !busy) { Text("安装") } }, dismissButton = { TextButton(onClick = { showImport = false }, enabled = !busy) { Text("取消") } })
    confirmation?.let { value -> AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(value.title) }, text = {
        Text(value.message, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { confirmation = null; perform(value.title, value.operation) }, enabled = !busy) { Text(value.label) } },
        dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } }) }
}

private fun extensionBuildConsentText(candidates: List<com.dafeiyu.mobile.data.ExtensionBuildCandidate>): String =
    candidates.joinToString("\n\n") { candidate -> candidate.key + "\n" + candidate.scripts.entries.joinToString("\n") { "${it.key}: ${it.value}" } } +
        "\n\n这些脚本可修改应用内的运行环境与文件。只运行本次确认的精确版本；来源或脚本变化后需要重新确认。"

private fun formatExtensionBytes(bytes: Long): String = if (bytes >= 1024 * 1024) "%.1f MiB".format(bytes / (1024.0 * 1024))
    else "%.0f KiB".format(bytes / 1024.0)
