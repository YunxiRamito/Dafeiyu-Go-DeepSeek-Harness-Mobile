package com.dafeiyu.mobile.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.dafeiyu.mobile.data.ModelAuthNotice
import com.dafeiyu.mobile.data.ModelAuthPrompt
import com.dafeiyu.mobile.data.ModelProviderConfig
import com.dafeiyu.mobile.data.ModelProviderCredential
import com.dafeiyu.mobile.data.ModelRuntimeController
import com.dafeiyu.mobile.data.ModelRuntimeException
import com.dafeiyu.mobile.data.ModelRuntimeStatus
import com.dafeiyu.mobile.data.RuntimeProvider
import com.dafeiyu.mobile.ui.components.FishButton
import com.dafeiyu.mobile.ui.components.FishDivider
import com.dafeiyu.mobile.ui.components.FishGlyph
import com.dafeiyu.mobile.ui.components.GlassMotion
import com.dafeiyu.mobile.ui.components.GlassSurface
import com.dafeiyu.mobile.ui.components.LocalReduceGlassMotion
import com.dafeiyu.mobile.ui.components.applyButtonDeformation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** A non-null remoteProviders switches the whole page to PC-owned, selection-only metadata. */
@Composable
fun ModelProvidersContent(
    notify: (String) -> Unit,
    onDeepSeekKey: () -> Unit,
    onSelectModel: (providerId: String, modelId: String, reasoning: String?) -> Unit,
    controller: ModelRuntimeController? = null,
    downloadSource: String = "cdn",
    selectedProviderId: String? = null,
    selectedModelId: String? = null,
    remoteProviders: List<RuntimeProvider>? = null,
    onRefreshRemote: (() -> Unit)? = null,
    nativeDeepSeekProvider: RuntimeProvider? = null,
) {
    val context = LocalContext.current
    val runtime = remember(controller, context.applicationContext) { controller ?: ModelRuntimeController.get(context) }
    val state by runtime.state.collectAsState()
    val scope = rememberCoroutineScope()
    val remote = remoteProviders != null
    val canManage = !remote && !state.busy
    var visible by remember { mutableStateOf(false) }
    var editProvider by remember { mutableStateOf<ModelProviderConfig?>(null) }
    var adding by remember { mutableStateOf(false) }
    var removeProvider by remember { mutableStateOf<ModelProviderConfig?>(null) }
    var installPrompt by remember { mutableStateOf(false) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var order by remember { mutableStateOf<List<ModelProviderConfig>>(emptyList()) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragCenter by remember { mutableStateOf(0f) }
    val bounds = remember { mutableMapOf<String, Rect>() }
    LaunchedEffect(Unit) { visible = true }
    LaunchedEffect(state.providers.revision) { if (dragging == null) order = state.providers.providers }
    val providers = remoteProviders ?: (state.catalog.filterNot { it.id == "deepseek" } + listOfNotNull(nativeDeepSeekProvider))

    fun runOperation(operation: suspend () -> Unit) {
        scope.launch {
            try { operation() } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notify(providerFailure(failure)) }
        }
    }
    fun reorder(ids: List<String>) {
        scope.launch {
            try { runtime.reorder(ids) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { order = state.providers.providers; notify(providerFailure(failure)) }
        }
    }
    fun move(id: String, direction: Int): Boolean {
        if (!canManage) return false
        val index = order.indexOfFirst { it.id == id }
        val target = index + direction
        if (index < 0 || target !in order.indices) return false
        val changed = order.toMutableList().apply { add(target, removeAt(index)) }
        order = changed
        reorder(changed.map { it.id })
        return true
    }

    AnimatedVisibility(visible, enter = fadeIn(GlassMotion.scalar(GlassMotion.MorphMillis, LocalReduceGlassMotion.current)),
        exit = fadeOut(GlassMotion.scalar(reducedMotion = LocalReduceGlassMotion.current))) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ProviderCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (remote) "电脑的模型提供商" else "模型提供商", style = MaterialTheme.typography.titleMedium)
                        ProviderHint(if (remote) "可切换电脑已配置的提供商与模型；新增、编辑、排序和删除由电脑管理。"
                            else "轻引擎与手机 DSH 共用配置和排序。长按左侧把手调整顺序。")
                    }
                    if (!remote) ProviderIconButton("add", "添加提供商", canManage) { adding = true }
                }
                if (!remote) {
                    ProviderHint(state.detail)
                    if (state.progress != null) {
                        val progress = state.progress!!
                        ProviderHint("${progress.completedPackages}/${progress.totalPackages} 个依赖包 · ${progress.receivedBytes / 1024} KiB" +
                            if (progress.threads > 0) " · ${progress.threads} 线程" else "")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        when (state.status) {
                            ModelRuntimeStatus.MISSING, ModelRuntimeStatus.ERROR -> {
                                if (state.workerRunning) TextButton(onClick = { runOperation { runtime.refreshCatalog(includeBuiltins = true) } },
                                    enabled = !state.busy) { Text("重试模型目录") }
                                else FishButton(onClick = { installPrompt = true }, enabled = !state.busy) { Text("安装／修复模型运行组件") }
                            }
                            ModelRuntimeStatus.INSTALLING -> {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                TextButton(onClick = runtime::cancelInstall) { Text("取消安装") }
                            }
                            ModelRuntimeStatus.STARTING, ModelRuntimeStatus.STOPPING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else -> TextButton(onClick = { runOperation { runtime.refreshCatalog(includeBuiltins = true) } },
                                enabled = !state.busy) { Text("刷新模型目录") }
                        }
                        if (state.workerRunning) TextButton(onClick = { runOperation { runtime.shutdown() } },
                            enabled = !state.busy) { Text("停止运行器") }
                    }
                    ProviderHint("DeepSeek 官方无需安装组件。其他提供商按需运行独立 worker，不启动 DSH 网页服务。")
                } else if (onRefreshRemote != null) TextButton(onClick = onRefreshRemote) { Text("刷新电脑目录") }
            }
            if (remote) {
                if (providers.isEmpty()) ProviderCard { ProviderHint("尚未读到电脑的真实模型目录，请检查 Aries 与电脑 DSH 状态。") }
                providers.forEach { provider ->
                    key(provider.id) { ProviderCard {
                        Text(provider.name, style = MaterialTheme.typography.titleSmall)
                        ProviderHint(if (provider.configured) "电脑已配置" else "电脑尚未配置认证")
                        ProviderModels(provider, selectedProviderId, selectedModelId, onSelectModel)
                    } }
                }
            } else {
                val reduced = LocalReduceGlassMotion.current
                LazyColumn(Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 640.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(order, key = { it.id }) { config ->
                        val provider = providers.firstOrNull { it.id == config.id }
                        var more by remember(config.id) { mutableStateOf(false) }
                        ProviderCard(modifier = Modifier.animateItem(
                            fadeInSpec = GlassMotion.scalar(reducedMotion = reduced),
                            placementSpec = tween(if (reduced) 0 else GlassMotion.MorphMillis, easing = GlassMotion.EaseOut),
                            fadeOutSpec = GlassMotion.scalar(reducedMotion = reduced))
                            .onGloballyPositioned { bounds[config.id] = it.boundsInRoot() }) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(44.dp).semantics {
                                    contentDescription = "长按调整 ${config.name} 的顺序"
                                    customActions = listOf(CustomAccessibilityAction("向上移动") { move(config.id, -1) },
                                        CustomAccessibilityAction("向下移动") { move(config.id, 1) })
                                }.pointerInput(config.id, canManage) {
                                    if (canManage) detectDragGesturesAfterLongPress(onDragStart = {
                                        dragging = config.id; dragCenter = bounds[config.id]?.center?.y ?: 0f
                                    }, onDragEnd = {
                                        val ids = order.map { it.id }; dragging = null; reorder(ids)
                                    }, onDragCancel = { dragging = null; order = state.providers.providers }) { change, amount ->
                                        change.consume()
                                        dragCenter += amount.y
                                        val current = order.indexOfFirst { it.id == config.id }
                                        val target = order.indexOfLast { item -> item.id != config.id &&
                                            (bounds[item.id]?.center?.y ?: Float.MAX_VALUE) < dragCenter }
                                        val insert = if (target < 0) 0 else if (target > current) target else target + 1
                                        if (current >= 0 && insert != current && insert in order.indices) order = order.toMutableList().apply {
                                            add(insert, removeAt(current))
                                        }
                                    }
                                }, contentAlignment = Alignment.Center) {
                                    FishGlyph("menu", Modifier.size(20.dp),
                                        if (dragging == config.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable {
                                    expandedId = config.id.takeUnless { expandedId == config.id }
                                }.padding(vertical = 6.dp)) {
                                    Text(config.name, style = MaterialTheme.typography.titleSmall)
                                    ProviderHint(when {
                                        !config.enabled -> "已停用"
                                        config.protected -> "内置官方渠道"
                                        provider == null -> "等待读取真实目录"
                                        !provider.configured -> "尚未配置认证"
                                        else -> "${provider.models.size} 个模型 · 已配置"
                                    })
                                }
                                if (config.protected) TextButton(onClick = onDeepSeekKey, enabled = !state.busy) { Text("API Key") }
                                else {
                                    ProviderIconButton("edit", "编辑 ${config.name}", canManage) { editProvider = config }
                                    Box {
                                        ProviderIconButton("more", "${config.name} 的更多操作", canManage) { more = true }
                                        DropdownMenu(more, onDismissRequest = { more = false }) {
                                            DropdownMenuItem(text = { Text(if (config.enabled) "停用" else "启用") }, onClick = {
                                                more = false; runOperation { runtime.upsert(config.copy(enabled = !config.enabled)) }
                                            })
                                            provider?.authMethods?.forEach { authType ->
                                                DropdownMenuItem(text = { Text(if (authType == "oauth") "登录授权" else "设置认证") }, onClick = {
                                                    more = false; runOperation { runtime.login(config.id, authType) }
                                                })
                                            }
                                            if (config.id in state.credentialProviders) DropdownMenuItem(text = { Text("移除认证") }, onClick = {
                                                more = false; runOperation { runtime.logout(config.id) }
                                            })
                                            DropdownMenuItem(text = { Text("删除提供商", color = MaterialTheme.colorScheme.error) }, onClick = {
                                                more = false; removeProvider = config
                                            })
                                        }
                                    }
                                }
                            }
                            AnimatedVisibility(expandedId == config.id, enter = fadeIn(GlassMotion.scalar(reducedMotion = reduced)),
                                exit = fadeOut(GlassMotion.scalar(reducedMotion = reduced))) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FishDivider()
                                    if (provider == null) {
                                        ProviderHint(if (config.protected) "DeepSeek 目录和推理能力由官方 API 获取，可在聊天页选择。"
                                            else "请安装组件并刷新目录；不会使用虚构模型或推理档位。")
                                    } else ProviderModels(provider, selectedProviderId, selectedModelId, onSelectModel)
                                }
                            }
                        }
                    }
                }
            }
            if (!remote && state.authenticatingProvider != null) ProviderCard {
                Text("提供商认证", style = MaterialTheme.typography.titleSmall)
                state.authNotices.forEach { notice -> ProviderAuthNotice(notice, notify) }
                TextButton(onClick = runtime::cancelAuthentication) { Text("取消认证") }
            }
        }
    }
    if (installPrompt && !remote) AlertDialog(onDismissRequest = { installPrompt = false },
        title = { Text("安装模型运行组件") }, text = {
            Text("将下载并校验 Linux 基础环境、Node.js 和固定版本的模型协议组件。安装期间无法替换正在被 DSH 或模型运行器使用的环境；不会自动启动 DSH。")
        }, confirmButton = { TextButton(onClick = {
            installPrompt = false; runOperation { runtime.install(downloadSource); runtime.refreshCatalog(includeBuiltins = true) }
        }) { Text("安装") } }, dismissButton = { TextButton(onClick = { installPrompt = false }) { Text("取消") } })
    if ((adding || editProvider != null) && !remote) ProviderEditor(editProvider, state.builtinCatalog,
        onDismiss = { adding = false; editProvider = null }, onSave = { config, credential ->
            runtime.upsert(config, credential)
            adding = false; editProvider = null
        }, notify = notify)
    removeProvider?.takeIf { !remote }?.let { config -> AlertDialog(onDismissRequest = { removeProvider = null },
        title = { Text("删除 ${config.name}？") }, text = { Text("将移除该提供商配置和本机保存的认证。已有对话会保留，继续聊天前需选择可用模型。") },
        confirmButton = { TextButton(onClick = { removeProvider = null; runOperation { runtime.remove(config.id) } }) {
            Text("删除", color = MaterialTheme.colorScheme.error)
        } }, dismissButton = { TextButton(onClick = { removeProvider = null }) { Text("取消") } }) }
    state.authPrompt?.takeIf { !remote }?.let { prompt -> ProviderAuthPrompt(prompt,
        onReply = { reply -> if (!runtime.respondToAuthPrompt(prompt.id, reply)) notify("认证步骤已变化，请重新确认") },
        onCancel = runtime::cancelAuthentication) }
}

@Composable
private fun ProviderModels(provider: RuntimeProvider, selectedProviderId: String?, selectedModelId: String?,
    onSelect: (String, String, String?) -> Unit) {
    if (provider.models.isEmpty()) ProviderHint("这个提供商尚未返回可用模型")
    provider.models.forEach { model ->
        key(model.id) {
            var reasoningMenu by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                onSelect(provider.id, model.id, model.defaultReasoning)
            }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(model.name, style = MaterialTheme.typography.bodyMedium)
                    ProviderHint(model.api + (model.contextWindow?.let { " · ${it / 1024}k 上下文" } ?: ""))
                }
                RadioButton(selectedProviderId == provider.id && selectedModelId == model.id, onClick = null)
                if (model.reasoningOptions.isNotEmpty()) Box {
                    TextButton(onClick = { reasoningMenu = true }) { Text("推理") }
                    DropdownMenu(reasoningMenu, onDismissRequest = { reasoningMenu = false }) {
                        DropdownMenuItem(text = { Text("由提供商决定") }, onClick = {
                            reasoningMenu = false; onSelect(provider.id, model.id, model.defaultReasoning)
                        })
                        model.reasoningOptions.forEach { level -> DropdownMenuItem(text = { Text(reasoningLabel(level)) }, onClick = {
                            reasoningMenu = false; onSelect(provider.id, model.id, level)
                        }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderEditor(existing: ModelProviderConfig?, catalog: List<RuntimeProvider>, onDismiss: () -> Unit,
    onSave: suspend (ModelProviderConfig, JSONObject?) -> Unit, notify: (String) -> Unit) {
    var id by remember(existing?.id) { mutableStateOf(existing?.id.orEmpty()) }
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var builtin by remember(existing?.id) { mutableStateOf(existing?.builtinProvider != null || existing == null) }
    var builtinId by remember(existing?.id) { mutableStateOf(existing?.builtinProvider.orEmpty()) }
    var api by remember(existing?.id) { mutableStateOf(existing?.api ?: ModelProviderConfig.APIS.first()) }
    var url by remember(existing?.id) { mutableStateOf(existing?.baseUrl.orEmpty()) }
    var models by remember(existing?.id) { mutableStateOf(existing?.modelsJson ?: "[]") }
    var apiKey by remember(existing?.id) { mutableStateOf("") }
    var env by remember(existing?.id) { mutableStateOf("") }
    var googleAdc by remember(existing?.id) { mutableStateOf("") }
    var awsCredentials by remember(existing?.id) { mutableStateOf("") }
    var awsConfig by remember(existing?.id) { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (existing == null) "添加提供商" else "编辑提供商") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ProviderHint("仅 DeepSeek 官方受保护。凭据留空会保留原值；填写认证字段会整体替换该提供商的认证。")
            OutlinedTextField(id, { id = it }, label = { Text("配置 ID") }, enabled = !busy && existing == null, singleLine = true)
            OutlinedTextField(name, { name = it }, label = { Text("显示名称") }, enabled = !busy, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(builtin, { builtin = it }, enabled = !busy)
                Text("使用已固定的提供商目录")
            }
            if (builtin) {
                OutlinedTextField(builtinId, { builtinId = it }, label = { Text("目录提供商 ID") }, enabled = !busy, singleLine = true)
                if (catalog.isEmpty()) ProviderHint("安装模型运行组件并刷新后，可读取目录中的真实提供商。")
                else ProviderDropdown("选择目录提供商", builtinId, catalog.filter { it.id != "deepseek" }.associate { it.id to it.name }) { chosen ->
                    builtinId = chosen
                    if (existing == null && id.isBlank()) id = chosen
                    if (name.isBlank()) name = catalog.first { it.id == chosen }.name
                }
            } else {
                ProviderDropdown("接口协议", api, ModelProviderConfig.APIS.associateWith { it }) { api = it }
                OutlinedTextField(url, { url = it }, label = { Text("HTTPS 接口地址") }, enabled = !busy, singleLine = true)
                OutlinedTextField(models, { models = it }, label = { Text("模型目录 JSON") }, enabled = !busy, minLines = 3,
                    supportingText = { Text("填写模型 id、name、reasoning、input 等真实元数据，不填入 Key 或 headers。") })
            }
            OutlinedTextField(apiKey, { apiKey = it }, label = { Text("新的 API Key（留空保留）") }, enabled = !busy,
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
            TextButton(onClick = { advanced = !advanced }, enabled = !busy) { Text(if (advanced) "收起云认证" else "Azure／AWS／Vertex 云认证") }
            AnimatedVisibility(advanced, enter = fadeIn(GlassMotion.scalar(reducedMotion = LocalReduceGlassMotion.current)),
                exit = fadeOut(GlassMotion.scalar(reducedMotion = LocalReduceGlassMotion.current))) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProviderHint("认证环境 JSON 可填写 Azure 资源／部署／版本、AWS 区域及签名凭据、Vertex 项目与区域。凭据文件填内容，不填手机路径。")
                    OutlinedTextField(env, { env = it }, label = { Text("认证环境 JSON") }, enabled = !busy, minLines = 3)
                    OutlinedTextField(googleAdc, { googleAdc = it }, label = { Text("Google ADC JSON") }, enabled = !busy, minLines = 3,
                        visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(awsCredentials, { awsCredentials = it }, label = { Text("AWS credentials 文件内容") }, enabled = !busy,
                        minLines = 3, visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(awsConfig, { awsConfig = it }, label = { Text("AWS config 文件内容") }, enabled = !busy, minLines = 2)
                    ProviderHint("OAuth 在保存配置后，从右侧更多菜单选择“登录授权”，按提供商要求在系统浏览器登录。")
                }
            }
            failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled = !busy, onClick = {
            scope.launch {
                busy = true; failure = null
                try {
                    val config = ModelProviderConfig(id.trim(), name.trim(), builtinProvider = builtinId.trim().takeIf { builtin },
                        api = api.takeUnless { builtin }, baseUrl = url.trim().takeUnless { builtin },
                        modelsJson = if (builtin) "[]" else models, enabled = existing?.enabled ?: true).validated()
                    require(!config.protected) { "DeepSeek 官方使用专用 API Key 入口" }
                    val credential = providerCredential(apiKey, env, googleAdc, awsCredentials, awsConfig)
                    onSave(config, credential)
                    apiKey = ""; env = ""; googleAdc = ""; awsCredentials = ""; awsConfig = ""
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { failure = if (error is IllegalArgumentException) "配置无效，请检查 ID、协议、HTTPS 地址、模型目录和认证字段"
                    else providerFailure(error); notify(failure.orEmpty()) }
                finally { busy = false }
            }
        }) { Text(if (busy) "保存中" else "保存") } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } })
}

internal fun providerCredential(key: String, env: String, googleAdc: String, awsCredentials: String, awsConfig: String): JSONObject? {
    if (listOf(key, env, googleAdc, awsCredentials, awsConfig).all { it.isBlank() }) return null
    val result = JSONObject().put("type", "api_key")
    if (key.isNotBlank()) result.put("key", key)
    if (env.isNotBlank()) result.put("env", JSONObject(env))
    val files = JSONObject()
    if (googleAdc.isNotBlank()) files.put("google_adc", googleAdc)
    if (awsCredentials.isNotBlank()) files.put("aws_credentials", awsCredentials)
    if (awsConfig.isNotBlank()) files.put("aws_config", awsConfig)
    if (files.length() > 0) result.put("files", files)
    return ModelProviderCredential.validate(result)
}

@Composable
private fun ProviderAuthPrompt(prompt: ModelAuthPrompt, onReply: (String) -> Unit, onCancel: () -> Unit) {
    // Authentication responses deliberately do not use rememberSaveable or durable UI state.
    var response by remember(prompt.id) { mutableStateOf("") }
    AlertDialog(onDismissRequest = onCancel, title = { Text("认证确认") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(prompt.message)
            if (prompt.type == "select") prompt.options.forEach { choice ->
                Row(Modifier.fillMaxWidth().clickable { response = choice.id }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(response == choice.id, onClick = null)
                    Column { Text(choice.label); choice.description?.let { ProviderHint(it) } }
                }
            } else OutlinedTextField(response, { response = it }, placeholder = { Text(prompt.placeholder.orEmpty()) },
                singleLine = true, visualTransformation = if (prompt.type == "secret") PasswordVisualTransformation() else VisualTransformation.None)
        }
    }, confirmButton = { TextButton(enabled = response.isNotBlank(), onClick = { onReply(response); response = "" }) { Text("继续") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消认证") } })
}

@Composable
private fun ProviderAuthNotice(notice: ModelAuthNotice, notify: (String) -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ProviderHint(notice.message)
        notice.userCode?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.titleLarge) } }
        notice.links.forEach { link -> TextButton(onClick = {
            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: Exception) { notify("没有可打开授权页面的浏览器") }
        }) { Text(link.label) } }
    }
}

@Composable
private fun ProviderDropdown(label: String, value: String, options: Map<String, String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text("$label：${options[value] ?: value}") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(id) }) }
        }
    }
}

@Composable
private fun ProviderIconButton(glyph: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    Box(Modifier.size(44.dp).applyButtonDeformation(enabled = enabled, circularHit = true, drawShadow = false)
        .clip(CircleShape).clickable(enabled = enabled, interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        FishGlyph(glyph, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .4f))
    }
}

@Composable
private fun ProviderCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val reduced = LocalReduceGlassMotion.current
    GlassSurface(modifier = modifier.fillMaxWidth().animateContentSize(tween(if (reduced) 0 else GlassMotion.MorphMillis,
        easing = GlassMotion.EaseOut)), cornerRadius = 24.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun ProviderHint(message: String) { Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
private fun providerFailure(failure: Exception): String = when (failure) {
    is ModelRuntimeException -> failure.message ?: "模型操作失败"
    is IllegalStateException -> "任务、安装或认证正在使用模型运行器，请先停止或等待完成"
    else -> "模型配置操作失败，请检查组件、认证和接口后重试"
}
private fun reasoningLabel(level: String): String = when (level) {
    "off" -> "关闭推理"; "minimal" -> "最少"; "low" -> "低"; "medium" -> "中"; "high" -> "高"; "xhigh" -> "极高"; else -> level
}
