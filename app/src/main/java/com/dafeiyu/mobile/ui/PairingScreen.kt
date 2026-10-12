@file:Suppress("SuspiciousIndentation")

package com.dafeiyu.mobile.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.dafeiyu.mobile.data.AriesClient
import com.dafeiyu.mobile.data.AriesConnectionProvider
import com.dafeiyu.mobile.data.AriesGroupStore
import com.dafeiyu.mobile.data.AriesMembershipSnapshot
import com.dafeiyu.mobile.data.AriesMembershipStatus
import com.dafeiyu.mobile.data.DemoRepository
import com.dafeiyu.mobile.data.SettingsContract
import com.dafeiyu.mobile.ui.components.FishButton
import com.dafeiyu.mobile.ui.components.FishGlyph
import com.dafeiyu.mobile.ui.components.GlassMotion
import com.dafeiyu.mobile.ui.components.LocalReduceGlassMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONObject

private enum class PairingStage {
    IDLE,
    VERIFYING,
    VERIFIED,
    REGISTERING,
    APPLYING,
    APPLIED,
    ERROR,
}

private data class PairingCandidate(
    val endpoint: String,
    val code: String,
    val payload: AriesClient.PairingPayload?,
)

private data class PairingApplicationScope(val endpoint: String, val nodeId: String, val groupId: String) {
    val configured: Boolean get() = endpoint.isNotBlank() && nodeId.isNotBlank() && groupId.isNotBlank()
}

private fun pairingApplicationScope(repository: DemoRepository) = PairingApplicationScope(
    SettingsContract.normalizeAriesEndpoint(repository.ariesEndpoint).orEmpty(),
    repository.ariesNodeId.orEmpty(),
    repository.ariesGroupId.orEmpty(),
)

private data class PairingKeyDelivery(
    val scope: PairingApplicationScope,
    val applicationId: String,
    val deliveryId: String,
    val senderId: String,
    val fingerprint: String,
)

private data class PairingUnsavedApplication(
    val scope: PairingApplicationScope,
    val applicationId: String,
    val status: String,
)

// Names are scoped presentation data and never participate in key confirmation.
private data class PairingNameSync(
    val scope: PairingApplicationScope,
    val deviceId: String,
    val deviceName: String?,
    val status: String,
)

private data class PairingRemoteDevice(
    val scope: PairingApplicationScope,
    val deviceId: String,
    val deviceName: String?,
)

private fun pairingKeyDelivery(json: JSONObject): PairingKeyDelivery {
    fun required(name: String): String = (json.opt(name) as? String)?.also {
        require(it.isNotBlank() && it.length <= 2048 && it.none(Char::isISOControl))
    } ?: error("密钥投递信息不完整")
    val endpoint = required("endpoint")
    require(SettingsContract.normalizeAriesEndpoint(endpoint) == endpoint)
    val fingerprint = required("fingerprint")
    require(fingerprint.matches(Regex("[0-9A-Fa-f]{64}")))
    return PairingKeyDelivery(
        PairingApplicationScope(endpoint, required("node_id"), required("group_id")),
        required("application_id"), required("delivery_id"), required("sender_id"), fingerprint,
    )
}

private fun pairingApplicationStatus(status: String): String = when (status) {
    "pending_approval" -> "等待成员审核"
    "awaiting_key" -> "等待对端发送密钥"
    "approved" -> "申请已批准"
    "rejected" -> "申请已拒绝"
    "revoked" -> "申请已撤销"
    "cancelled", "canceled" -> "申请已取消"
    "expired" -> "申请已过期"
    else -> "申请状态待核对"
}

private fun pairingCandidate(client: AriesClient, raw: String, fallbackEndpoint: String): PairingCandidate {
    val text = raw.trim()
    require(text.isNotEmpty()) { "请输入配对码，或粘贴完整的 Aries 二维码内容" }
    require(text.length <= 4096) { "配对内容过长" }

    val looksLikePayload = text.startsWith("{") ||
        text.startsWith("aries:", ignoreCase = true) ||
        text.startsWith("https://", ignoreCase = true)
    if (!looksLikePayload) {
        require(text.length in 1..128 && text.none(Char::isISOControl)) { "配对码无效" }
        return PairingCandidate(
            endpoint = fallbackEndpoint,
            code = text,
            payload = null,
        )
    }

    val payload = client.parsePairingPayload(text)
    val code = payload.code?.trim().orEmpty()
    require(code.isNotEmpty()) { "二维码中没有配对码" }
    require(code.length <= 128 && code.none(Char::isISOControl)) { "二维码中的配对码无效" }
    return PairingCandidate(payload.endpoint, code, payload)
}

private fun Throwable.userMessage(fallback: String): String =
    message?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

@Composable
fun PairingScreen(
    repository: DemoRepository,
    onBack: () -> Unit,
    notify: (String) -> Unit,
    onScan: () -> Unit = {},
    scannedPayload: String? = null,
    onScannedPayloadConsumed: () -> Unit = {},
    onMembershipChanged: (AriesMembershipSnapshot) -> Unit = {},
) {
    val context = LocalContext.current
    val ariesClient = remember(context) { AriesClient(context) }
    val groupStore = remember(context) { AriesGroupStore(context) }
    val connectionProvider = remember(context) { AriesConnectionProvider(context) }
    val clientVersion = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                ?.takeIf(String::isNotBlank)?.let { "android/$it" }
        }.getOrNull()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val publishMembership by rememberUpdatedState(onMembershipChanged)
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val range = with(androidx.compose.ui.platform.LocalDensity.current) { 112.dp.toPx() }
    val collapse = (scroll.value / range).coerceIn(0f, 1f)

    var input by rememberSaveable { mutableStateOf("") }
    var endpoint by rememberSaveable {
        mutableStateOf(repository.ariesEndpoint.takeIf { it.isNotBlank() } ?: AriesClient.Config().baseUrl)
    }
    var serverCode by rememberSaveable { mutableStateOf<String?>(null) }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var groupId by rememberSaveable { mutableStateOf<String?>(null) }
    var deviceId by rememberSaveable { mutableStateOf<String?>(null) }
    var registrationStatus by rememberSaveable { mutableStateOf("读取中") }
    var applicationStatus by rememberSaveable { mutableStateOf<String?>(null) }
    var applicationId by remember { mutableStateOf<String?>(null) }
    var applicationScope by remember { mutableStateOf<PairingApplicationScope?>(null) }
    var applicationCheckError by remember { mutableStateOf<String?>(null) }
    var applicationDeviceName by remember { mutableStateOf<String?>(null) }
    var nameSync by remember { mutableStateOf<PairingNameSync?>(null) }
    var remoteDevice by remember { mutableStateOf<PairingRemoteDevice?>(null) }
    var membership by remember { mutableStateOf<AriesMembershipSnapshot?>(null) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var stage by remember { mutableStateOf(PairingStage.IDLE) }
    var candidate by remember { mutableStateOf<PairingCandidate?>(null) }
    var invite by remember { mutableStateOf<AriesClient.InviteInfo?>(null) }
    var verifiedNode by remember { mutableStateOf<AriesClient.Node?>(null) }
    var editingInvite by remember { mutableStateOf(false) }
    var pendingDelivery by remember { mutableStateOf<PairingKeyDelivery?>(null) }
    var reviewedDelivery by remember { mutableStateOf<PairingKeyDelivery?>(null) }
    var fingerprintChecked by remember { mutableStateOf(false) }
    var deliveryError by remember { mutableStateOf<String?>(null) }
    var confirmationError by remember { mutableStateOf<String?>(null) }
    var confirmationBusy by remember { mutableStateOf(false) }
    var confirmationJob by remember { mutableStateOf<Job?>(null) }
    var confirmationGeneration by remember { mutableIntStateOf(0) }
    var pairingGeneration by remember { mutableIntStateOf(0) }
    var pollRevision by remember { mutableIntStateOf(0) }
    var securePairingStatus by remember { mutableStateOf("待核对") }
    var unsavedApplication by remember { mutableStateOf<PairingUnsavedApplication?>(null) }
    val selectedScope = pairingApplicationScope(repository)
    var lastHeartbeatAttemptAt by remember { mutableStateOf<Long?>(null) }
    var observedScope by remember { mutableStateOf(selectedScope) }
    val busy = stage == PairingStage.VERIFYING || stage == PairingStage.REGISTERING ||
        stage == PairingStage.APPLYING || confirmationBusy

    fun clearDelivery(cancelConfirmation: Boolean = true) {
        confirmationGeneration++
        if (cancelConfirmation) confirmationJob?.cancel()
        confirmationJob = null
        confirmationBusy = false
        pendingDelivery = null
        reviewedDelivery = null
        fingerprintChecked = false
        confirmationError = null
    }

    fun clearDeviceNames() {
        applicationDeviceName = null
        nameSync = null
        remoteDevice = null
    }

    fun remoteNameFor(delivery: PairingKeyDelivery): String =
        remoteDevice?.takeIf { it.scope == delivery.scope && it.deviceId == delivery.senderId }
            ?.deviceName ?: "对方尚未共享设备名称"

    fun isCurrentScope(expected: PairingApplicationScope): Boolean =
        !editingInvite && pairingApplicationScope(repository) == expected &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    fun canReview(delivery: PairingKeyDelivery): Boolean =
        isCurrentScope(delivery.scope) && pendingDelivery == delivery &&
            applicationScope == delivery.scope && applicationId == delivery.applicationId &&
            membership?.isActiveMember == true &&
            applicationStatus in setOf("awaiting_key", "approved")

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_DESTROY) {
                clearDelivery()
                clearDeviceNames()
                lastHeartbeatAttemptAt = null
                if (stage == PairingStage.VERIFYING || stage == PairingStage.REGISTERING) {
                    pairingGeneration++
                    stage = PairingStage.ERROR
                    errorMessage = "配对验证已中断，请重新验证配对信息"
                }
                securePairingStatus = "待核对"
                notice = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            clearDelivery()
        }
    }

    LaunchedEffect(selectedScope, editingInvite, pollRevision, lifecycleOwner) {
        if (observedScope != selectedScope) {
            clearDeviceNames()
            lastHeartbeatAttemptAt = null
            observedScope = selectedScope
            if (stage != PairingStage.APPLYING && stage != PairingStage.REGISTERING) {
                pairingGeneration++
                candidate = null
                invite = null
                verifiedNode = null
                unsavedApplication = null
                stage = PairingStage.IDLE
                editingInvite = false
                errorMessage = null
                notice = null
            }
        }
        clearDelivery()
        notice = null
        securePairingStatus = "待核对"
        deliveryError = null
        membership = null
        applicationId = null
        applicationScope = null
        applicationStatus = null
        applicationCheckError = null
        applicationDeviceName = null
        remoteDevice = null
        if (editingInvite || nameSync?.scope != selectedScope) nameSync = null
        if (editingInvite || selectedScope.endpoint.isBlank()) return@LaunchedEffect
        endpoint = selectedScope.endpoint
        groupId = selectedScope.groupId.takeIf(String::isNotBlank)
        group = null
        val config = AriesClient.Config(baseUrl = selectedScope.endpoint)
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                if (confirmationBusy) {
                    delay(1_000)
                    continue
                }
                val pollGeneration = confirmationGeneration
                val restored = if (selectedScope.configured) runCatching {
                    connectionProvider.savedApplication()
                } else Result.success(null)
                currentCoroutineContext().ensureActive()
                if (!isCurrentScope(selectedScope)) return@repeatOnLifecycle
                if (confirmationBusy || pollGeneration != confirmationGeneration) continue
                applicationId = restored.getOrNull()
                applicationScope = selectedScope.takeIf { applicationId != null }
                applicationCheckError = if (restored.isFailure) "无法读取已保存的入组申请，请重试" else null
                val checked = groupStore.inspectMembership(
                    selectedScope.groupId.takeIf(String::isNotBlank), config,
                    selectedScope.nodeId.takeIf(String::isNotBlank),
                )
                currentCoroutineContext().ensureActive()
                if (!isCurrentScope(selectedScope)) return@repeatOnLifecycle
                if (confirmationBusy || pollGeneration != confirmationGeneration) continue
                membership = checked
                publishMembership(checked)
                val registeredDevice = checked.scope.deviceId
                if (selectedScope.configured && checked.scope.endpoint == selectedScope.endpoint &&
                    checked.scope.nodeId == selectedScope.nodeId && !registeredDevice.isNullOrBlank() &&
                    checked.status in setOf(AriesMembershipStatus.ACTIVE_MEMBER, AriesMembershipStatus.NOT_MEMBER)) {
                    val now = SystemClock.elapsedRealtime()
                    val lastAttempt = lastHeartbeatAttemptAt
                    if (lastAttempt == null || now - lastAttempt >= 30_000L) {
                        lastHeartbeatAttemptAt = now
                        nameSync = PairingNameSync(selectedScope, registeredDevice, null, "正在同步设备名称")
                        val heartbeat = ariesClient.heartbeat(
                            config, clientVersion = clientVersion,
                            expectedNodeId = selectedScope.nodeId,
                        ).mapCatching {
                            require(it.deviceId == registeredDevice) { "心跳返回的设备 ID 与本机不一致" }
                            it
                        }
                        currentCoroutineContext().ensureActive()
                        if (!isCurrentScope(selectedScope)) return@repeatOnLifecycle
                        if (confirmationBusy || pollGeneration != confirmationGeneration) {
                            nameSync = null
                            continue
                        }
                        nameSync = heartbeat.fold(
                            onSuccess = { result ->
                                PairingNameSync(selectedScope, registeredDevice, result.deviceName,
                                    if (result.deviceName != null) "设备名称已同步" else "心跳已完成，节点未返回设备名称")
                            },
                            onFailure = { failure ->
                                PairingNameSync(selectedScope, registeredDevice, null,
                                    "设备名称同步失败：${failure.userMessage("请重试")}")
                            },
                        )
                    }
                } else {
                    nameSync = null
                }
                val savedApplication = applicationId
                var applicationVerified = false
                if (savedApplication != null && checked.scope.nodeId == selectedScope.nodeId &&
                    checked.status != AriesMembershipStatus.ERROR) {
                    val application = groupStore.getApplication(selectedScope.groupId, savedApplication, config)
                    currentCoroutineContext().ensureActive()
                    if (!isCurrentScope(selectedScope)) return@repeatOnLifecycle
                    if (confirmationBusy || pollGeneration != confirmationGeneration) continue
                    val detail = application.getOrNull()
                    if (detail != null && detail.applicationId == savedApplication &&
                        detail.deviceId == checked.scope.deviceId &&
                        detail.publicKey == checked.scope.publicKey) {
                        applicationVerified = true
                        applicationStatus = detail.status
                        applicationDeviceName = detail.deviceName
                        applicationCheckError = null
                    } else {
                        applicationStatus = null
                        applicationDeviceName = null
                        clearDelivery()
                        applicationCheckError = if (application.isFailure) "申请状态查询失败，请重试"
                            else "申请身份与本机不一致，无法确认密钥"
                    }
                } else {
                    applicationStatus = null
                    applicationDeviceName = null
                }
                if (applicationStatus !in setOf("awaiting_key", "approved")) clearDelivery()
                if (!checked.isActiveMember) {
                    remoteDevice = null
                    clearDelivery()
                    securePairingStatus = when (checked.status) {
                        AriesMembershipStatus.NOT_MEMBER -> "尚未取得有效成员资格"
                        AriesMembershipStatus.ERROR -> "安全状态核对失败"
                        else -> "等待成员资格核对"
                    }
                    notice = null
                    deliveryError = null
                } else {
                    val stateGeneration = confirmationGeneration
                    val state = runCatching { connectionProvider.call("get_connection_state") }
                    currentCoroutineContext().ensureActive()
                    if (!isCurrentScope(selectedScope)) return@repeatOnLifecycle
                    if (confirmationBusy || stateGeneration != confirmationGeneration) continue
                    val json = state.getOrNull()
                    val verifiedState = json?.let { response ->
                        val local = response.optJSONObject("local_device") ?: return@let false
                        response.optBoolean("server_connected") &&
                            response.optString("group_id") == selectedScope.groupId &&
                            local.optString("id") == checked.scope.deviceId &&
                            local.optString("public_key") == checked.scope.publicKey
                    } ?: false
                    remoteDevice = if (verifiedState) {
                        json?.optJSONObject("remote_device")?.let { remote ->
                            val remoteId = remote.opt("id") as? String
                            if (remoteId.isNullOrBlank() || remoteId == checked.scope.deviceId) null
                            else PairingRemoteDevice(selectedScope, remoteId,
                                (remote.opt("name") as? String)?.trim()?.takeIf(String::isNotEmpty))
                        }
                    } else null
                    val terminalApplication = applicationStatus in setOf("rejected", "revoked", "cancelled", "canceled", "expired")
                    val paired = !terminalApplication && verifiedState && json?.optString("pairing_status") == "paired"
                    if (!paired) notice = null
                    securePairingStatus = when {
                        terminalApplication -> "入组申请已失效"
                        paired -> "已安全配对"
                        else -> "等待密钥确认"
                    }
                    if (!verifiedState || paired || !applicationVerified ||
                        applicationStatus !in setOf("awaiting_key", "approved")) {
                        clearDelivery()
                        deliveryError = if (!verifiedState) "安全连接状态核对失败，请重试" else null
                        if (!verifiedState) securePairingStatus = "安全状态核对失败"
                    } else {
                        val deliveryGeneration = confirmationGeneration
                        val delivery = runCatching {
                            connectionProvider.pendingDelivery()?.let(::pairingKeyDelivery)?.also {
                                require(it.scope == selectedScope && it.applicationId == savedApplication)
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        if (!isCurrentScope(selectedScope)) return@repeatOnLifecycle
                        if (confirmationBusy || deliveryGeneration != confirmationGeneration) continue
                        val next = delivery.getOrNull()
                        if (next != pendingDelivery) {
                            clearDelivery()
                            pendingDelivery = next
                        }
                        deliveryError = if (delivery.isFailure) "密钥投递核对失败，请重试" else null
                        if (next != null) securePairingStatus = "等待核对对端指纹"
                    }
                }
                delay(15_000)
            }
        }
    }

    LaunchedEffect(repository.ariesEndpoint) {
        if (stage == PairingStage.IDLE && candidate == null && repository.ariesEndpoint.isNotBlank()) {
            endpoint = repository.ariesEndpoint
        }
    }

    LaunchedEffect(ariesClient) {
        val identity = runCatching { ariesClient.localIdentity() }.getOrNull()
        deviceId = identity?.deviceId
        registrationStatus = when {
            identity == null -> "不可用：本地身份无法读取"
            identity.deviceId.isNullOrBlank() -> "未注册"
            else -> "本地已注册（设备 ID ${identity.deviceId}）"
        }
    }

    LaunchedEffect(scannedPayload) {
        val scanned = scannedPayload?.trim().orEmpty()
        if (scanned.isNotEmpty()) {
            pairingGeneration++
            clearDelivery()
            clearDeviceNames()
            editingInvite = true
            securePairingStatus = "待核对"
            input = scanned
            candidate = null
            invite = null
            verifiedNode = null
            stage = PairingStage.IDLE
            applicationStatus = null
            applicationId = null
            applicationScope = null
            unsavedApplication = null
            errorMessage = null
            notice = "已填入扫描内容，请点击「验证配对信息」"
            onScannedPayloadConsumed()
        }
    }

    PublishSettingsNavigation("配对码", collapse, scroll.value.toFloat())
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(scroll).heightIn(min = maxHeight + 112.dp)
                .padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SubpageHeader("配对码", "先验证节点与群组，再由你确认设备注册和入组申请", onBack, collapse)

            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("输入配对信息", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = input,
                        onValueChange = {
                            pairingGeneration++
                            clearDelivery()
                            clearDeviceNames()
                            editingInvite = true
                            unsavedApplication = null
                            applicationId = null
                            applicationScope = null
                            securePairingStatus = "待核对"
                            input = it.take(4096)
                            candidate = null
                            invite = null
                            verifiedNode = null
                            stage = PairingStage.IDLE
                            applicationStatus = null
                            errorMessage = null
                            notice = null
                        },
                        singleLine = false,
                        minLines = 1,
                        maxLines = 4,
                        enabled = !busy,
                        label = { Text("配对码或二维码内容") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FishButton(
                            onClick = {
                                clearDelivery()
                                onScan()
                            },
                            accent = false,
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                        ) {
                            FishGlyph("viewfinder")
                            Text("扫描二维码")
                        }
                        FishButton(
                            onClick = {
                                scope.launch {
                                    clearDelivery()
                                    clearDeviceNames()
                                    editingInvite = true
                                    unsavedApplication = null
                                    val verificationGeneration = ++pairingGeneration
                                    stage = PairingStage.VERIFYING
                                    errorMessage = null
                                    notice = null
                                    applicationStatus = null
                                    applicationId = null
                                    applicationScope = null
                                    invite = null
                                    verifiedNode = null
                                    val fallbackEndpoint = repository.ariesEndpoint.takeIf { it.isNotBlank() }
                                        ?: AriesClient.Config().baseUrl
                                    val resolved = runCatching { pairingCandidate(ariesClient, input, fallbackEndpoint) }
                                        .getOrElse { failure ->
                                            stage = PairingStage.ERROR
                                            errorMessage = failure.userMessage("配对内容无效")
                                            return@launch
                                        }
                                    candidate = resolved
                                    endpoint = resolved.endpoint
                                    val config = AriesClient.Config(baseUrl = resolved.endpoint)
                                    val nodeResult = runCatching { ariesClient.node(config).getOrThrow() }
                                    currentCoroutineContext().ensureActive()
                                    if (verificationGeneration != pairingGeneration) return@launch
                                    val inviteResult = runCatching {
                                        ariesClient.lookupInvite(resolved.code, config).getOrThrow()
                                    }
                                    currentCoroutineContext().ensureActive()
                                    if (verificationGeneration != pairingGeneration) return@launch
                                    val returnedNode = nodeResult.getOrNull()
                                    val qrNodeId = resolved.payload?.nodeId?.trim()?.takeIf { it.isNotEmpty() }
                                    val nodeMismatch = qrNodeId?.let { expected ->
                                        if (expected != returnedNode?.nodeId?.trim()) {
                                            "二维码中的节点标识与节点返回值不一致"
                                        } else {
                                            null
                                        }
                                    }
                                    nodeResult.onSuccess { node ->
                                        serverCode = node.serverCode
                                    }.onFailure {
                                        serverCode = null
                                    }
                                    inviteResult.onSuccess { info ->
                                        invite = info
                                        groupId = info.groupId
                                        group = info.groupName ?: resolved.payload?.group
                                    }
                                    val failures = buildList {
                                        nodeResult.exceptionOrNull()?.let { add("节点信息：${it.userMessage("获取失败")}") }
                                        nodeMismatch?.let { add(it) }
                                        val authorityNode = inviteResult.getOrNull()?.authorityNode
                                        if (authorityNode.isNullOrBlank() || authorityNode != returnedNode?.nodeId) {
                                            add("邀请码所属节点与节点返回值不一致")
                                        }
                                        inviteResult.exceptionOrNull()?.let { add("配对码：${it.userMessage("验证失败")}") }
                                    }
                                    if (failures.isNotEmpty()) {
                                        verifiedNode = null
                                        stage = PairingStage.ERROR
                                        errorMessage = failures.joinToString("；")
                                    } else {
                                        verifiedNode = returnedNode
                                        stage = PairingStage.VERIFIED
                                        notice = "验证通过，请确认设备注册和入组申请"
                                        notify("Aries 配对信息验证通过")
                                    }
                                }
                            },
                            accent = true,
                            enabled = input.isNotBlank() && !busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (stage == PairingStage.VERIFYING) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else FishGlyph("check")
                            Text(if (stage == PairingStage.VERIFYING) "验证中" else "验证配对信息")
                        }
                    }
                    Text(
                        "请使用对端提供的配对码或二维码内容。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (editingInvite && selectedScope.configured && !busy && unsavedApplication == null) {
                        FishButton(
                            onClick = {
                                clearDelivery()
                                clearDeviceNames()
                                lastHeartbeatAttemptAt = null
                                candidate = null
                                invite = null
                                verifiedNode = null
                                stage = PairingStage.IDLE
                                editingInvite = false
                                errorMessage = null
                                notice = null
                            },
                            accent = false,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            FishGlyph("back")
                            Text("返回已保存的配对")
                        }
                    }
                }
            }

            if (candidate != null || stage == PairingStage.ERROR || membership != null) {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("配对状态", style = MaterialTheme.typography.titleMedium)
                        PairingValueRow("服务器地址", endpoint)
                        PairingValueRow("服务器码", serverCode ?: "未返回")
                        PairingValueRow("群组", group ?: groupId ?: membership?.scope?.groupId ?: "未返回")
                        PairingValueRow("设备注册", registrationStatus)
                        PairingValueRow("设备 ID", deviceId ?: "未注册")
                        val displayedScope = if (editingInvite) candidate?.let { resolved ->
                            verifiedNode?.nodeId?.let { nodeId ->
                                PairingApplicationScope(resolved.endpoint, nodeId, invite?.groupId.orEmpty())
                            }
                        } else selectedScope
                        val visibleNameSync = nameSync?.takeIf { it.scope == displayedScope && it.deviceId == deviceId }
                        val verifiedApplicationName = applicationDeviceName.takeIf {
                            applicationScope == selectedScope && !editingInvite && membership?.scope?.deviceId == deviceId
                        }
                        PairingAnimatedValueRow("本机共享名称", visibleNameSync?.deviceName ?: verifiedApplicationName ?: "本机尚未共享设备名称")
                        PairingAnimatedValueRow("名称同步", visibleNameSync?.status ?: "等待节点核对")
                        candidate?.payload?.nodeId?.let { PairingValueRow("节点标识", it) }
                        applicationStatus?.let { PairingValueRow("入组申请", pairingApplicationStatus(it)) }
                        applicationId?.let { PairingValueRow("申请标识", it) }
                        PairingValueRow("安全配对", if (selectedScope == applicationScope || !editingInvite) securePairingStatus else "待核对")
                        membership?.let { checked ->
                            PairingValueRow(
                                "成员资格",
                                when (checked.status) {
                                    AriesMembershipStatus.ACTIVE_MEMBER -> "已确认本机为有效成员"
                                    AriesMembershipStatus.NOT_MEMBER -> "当前尚非有效成员"
                                    AriesMembershipStatus.UNREGISTERED -> "未注册"
                                    AriesMembershipStatus.UNCONFIGURED -> "尚无群组"
                                    AriesMembershipStatus.CHECKING -> "核对中"
                                    AriesMembershipStatus.UNKNOWN -> "未核对"
                                    AriesMembershipStatus.ERROR -> "核对失败"
                                },
                            )
                            if (checked.isActiveMember) PairingValueRow("其他有效成员", checked.activePeerCount.toString())
                            if (checked.status == AriesMembershipStatus.ERROR) {
                                Text(
                                    listOfNotNull(checked.errorMessage, checked.errorCode, checked.requestId).joinToString(" · "),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        val delivery = pendingDelivery?.takeIf { canReview(it) }
                        val visibleRemote = if (delivery != null) {
                            PairingRemoteDevice(delivery.scope, delivery.senderId, remoteNameFor(delivery))
                        } else remoteDevice?.takeIf {
                            !editingInvite && it.scope == selectedScope && membership?.isActiveMember == true
                        }
                        PairingAnimatedRemoteRows(visibleRemote)
                        applicationCheckError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        deliveryError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        if (delivery != null) {
                            FishButton(
                                onClick = {
                                    if (canReview(delivery)) {
                                        reviewedDelivery = delivery
                                        fingerprintChecked = false
                                        confirmationError = null
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                FishGlyph("check")
                                Text("核对对端密钥指纹")
                            }
                        }
                        if (!editingInvite && selectedScope.configured && !confirmationBusy) {
                            FishButton(
                                onClick = { pollRevision++ },
                                accent = false,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                FishGlyph("arrow")
                                Text("重新核对配对状态")
                            }
                        }
                    }
                }
            }

            errorMessage?.let { message ->
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Text(
                        message,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            notice?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }

            if (stage == PairingStage.VERIFIED) {
                val resolved = candidate
                val verifiedInvite = invite
                if (resolved != null && verifiedInvite != null) {
                    FishButton(
                        onClick = {
                            scope.launch {
                                val originalScope = pairingApplicationScope(repository)
                                val node = verifiedNode
                                val nodeId = node?.nodeId?.takeIf(String::isNotBlank)
                                if (nodeId == null) {
                                    errorMessage = "缺少已验证的节点标识，请重新验证配对信息"
                                    stage = PairingStage.ERROR
                                    return@launch
                                }
                                clearDelivery()
                                val applyGeneration = ++pairingGeneration
                                errorMessage = null
                                notice = null
                                val config = AriesClient.Config(baseUrl = resolved.endpoint)
                                if (deviceId.isNullOrBlank()) {
                                    stage = PairingStage.REGISTERING
                                    registrationStatus = "注册中"
                                    val enrollment = runCatching {
                                        val challenge = ariesClient.requestChallenge(config, deviceId = null).getOrThrow()
                                        ariesClient.enroll(challenge, config).getOrThrow()
                                    }
                                    currentCoroutineContext().ensureActive()
                                    if (applyGeneration != pairingGeneration) return@launch
                                    if (pairingApplicationScope(repository) != originalScope) {
                                        clearDeviceNames()
                                        stage = PairingStage.ERROR
                                        errorMessage = "配对信息已改变，请重新验证"
                                        return@launch
                                    }
                                    if (enrollment.isFailure) {
                                        stage = PairingStage.ERROR
                                        registrationStatus = "未注册"
                                        errorMessage = enrollment.exceptionOrNull()?.userMessage("设备注册失败")
                                        return@launch
                                    }
                                    val enrolledDeviceId = enrollment.getOrThrow().deviceId
                                    deviceId = enrolledDeviceId
                                    registrationStatus = "已注册（设备 ID $enrolledDeviceId）"
                                }

                                stage = PairingStage.APPLYING
                                val nameScope = PairingApplicationScope(resolved.endpoint, nodeId, verifiedInvite.groupId)
                                val registeredDevice = deviceId
                                if (registeredDevice.isNullOrBlank()) {
                                    stage = PairingStage.ERROR
                                    errorMessage = "本机设备身份不可用，请重新验证"
                                    return@launch
                                }
                                val nameGeneration = confirmationGeneration
                                nameSync = PairingNameSync(nameScope, registeredDevice, null, "正在同步设备名称")
                                val heartbeat = ariesClient.heartbeat(
                                    config, clientVersion = clientVersion, expectedNodeId = nodeId,
                                ).mapCatching {
                                    require(it.deviceId == registeredDevice) { "心跳返回的设备 ID 与本机不一致" }
                                    it
                                }
                                currentCoroutineContext().ensureActive()
                                if (applyGeneration != pairingGeneration) return@launch
                                if (pairingApplicationScope(repository) != originalScope ||
                                    nameGeneration != confirmationGeneration ||
                                    !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                    clearDeviceNames()
                                    stage = PairingStage.ERROR
                                    errorMessage = "配对信息已改变或操作已中断，请重新验证"
                                    return@launch
                                }
                                if (heartbeat.isFailure) {
                                    stage = PairingStage.ERROR
                                    val failure = heartbeat.exceptionOrNull()?.userMessage("请重试")
                                    nameSync = PairingNameSync(nameScope, registeredDevice, null, "设备名称同步失败：$failure")
                                    errorMessage = "心跳核对失败，尚未提交入组申请：$failure"
                                    return@launch
                                }
                                val sharedName = heartbeat.getOrThrow().deviceName
                                nameSync = PairingNameSync(nameScope, registeredDevice, sharedName,
                                    if (sharedName != null) "设备名称已同步" else "心跳已完成，节点未返回设备名称")
                                val application = runCatching {
                                    ariesClient.applyInvite(resolved.code, config).getOrThrow()
                                }
                                currentCoroutineContext().ensureActive()
                                if (applyGeneration != pairingGeneration) return@launch
                                if (pairingApplicationScope(repository) != originalScope) {
                                    clearDeviceNames()
                                    stage = PairingStage.ERROR
                                    errorMessage = "配对信息已改变，无法保存本次申请，请重新核对"
                                    return@launch
                                }
                                val returnedGroup = application.getOrNull()?.groupId
                                if (returnedGroup != null && returnedGroup != verifiedInvite.groupId) {
                                    stage = PairingStage.ERROR
                                    errorMessage = "入组申请返回的群组与已验证邀请码不一致"
                                    return@launch
                                }
                                val result = application.getOrNull()
                                if (result != null) {
                                    val intendedScope = PairingApplicationScope(
                                        SettingsContract.normalizeAriesEndpoint(resolved.endpoint).orEmpty(),
                                        nodeId, result.groupId,
                                    )
                                    repository.recordAriesConnection(
                                        AriesClient.ConnectionSnapshot(
                                            endpoint = resolved.endpoint,
                                            state = AriesClient.ConnectionState.HEALTHY,
                                            node = node,
                                            deviceId = deviceId,
                                        ),
                                    )
                                    groupId = result.groupId
                                    repository.updateAriesGroupId(result.groupId)
                                    unsavedApplication = PairingUnsavedApplication(
                                        intendedScope, result.applicationId, result.status,
                                    )
                                    val saved = runCatching {
                                        connectionProvider.saveApplication(
                                            intendedScope.endpoint, intendedScope.nodeId,
                                            intendedScope.groupId, result.applicationId,
                                        )
                                    }
                                    currentCoroutineContext().ensureActive()
                                    if (pairingApplicationScope(repository) != intendedScope) {
                                        unsavedApplication = null
                                        stage = PairingStage.ERROR
                                        errorMessage = "端点或群组已改变，请重新核对申请"
                                        return@launch
                                    }
                                    if (saved.isFailure) {
                                        stage = PairingStage.ERROR
                                        errorMessage = "节点已收到入组申请，但本机保存失败，请重试保存"
                                    } else {
                                        unsavedApplication = null
                                        applicationId = result.applicationId
                                        applicationScope = intendedScope
                                        applicationStatus = result.status
                                        stage = PairingStage.APPLIED
                                        editingInvite = false
                                        pollRevision++
                                        notice = "入组申请已保存，等待成员审核和对端密钥投递"
                                        notify("入组申请已提交并保存")
                                    }
                                } else {
                                    stage = PairingStage.ERROR
                                    applicationStatus = null
                                    errorMessage = application.exceptionOrNull()?.userMessage("入组申请失败")
                                }
                            }
                        },
                        accent = true,
                        enabled = stage == PairingStage.VERIFIED,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        when (stage) {
                            PairingStage.REGISTERING -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            PairingStage.APPLYING -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else -> FishGlyph("pair")
                        }
                        Text(
                            when (stage) {
                                PairingStage.REGISTERING -> "注册设备"
                                PairingStage.APPLYING -> "提交申请"
                                else -> if (deviceId.isNullOrBlank()) "注册设备并申请加入" else "申请加入群组"
                            },
                        )
                    }
                }
            }

            val unsaved = unsavedApplication
            if (unsaved != null && pairingApplicationScope(repository) == unsaved.scope) {
                FishButton(
                    onClick = {
                        scope.launch {
                            stage = PairingStage.APPLYING
                            errorMessage = null
                            val saved = runCatching {
                                connectionProvider.saveApplication(
                                    unsaved.scope.endpoint, unsaved.scope.nodeId,
                                    unsaved.scope.groupId, unsaved.applicationId,
                                )
                            }
                            currentCoroutineContext().ensureActive()
                            if (pairingApplicationScope(repository) != unsaved.scope ||
                                unsavedApplication != unsaved) {
                                stage = PairingStage.ERROR
                                unsavedApplication = null
                                errorMessage = "端点或群组已改变，无法保存旧申请"
                                return@launch
                            }
                            if (saved.isSuccess) {
                                unsavedApplication = null
                                applicationId = unsaved.applicationId
                                applicationScope = unsaved.scope
                                applicationStatus = unsaved.status
                                editingInvite = false
                                stage = PairingStage.APPLIED
                                pollRevision++
                                notice = "入组申请已保存，等待成员审核和对端密钥投递"
                                notify("入组申请已保存")
                            } else {
                                stage = PairingStage.ERROR
                                errorMessage = "入组申请仍未保存，请重试"
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (stage == PairingStage.APPLYING) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else FishGlyph("check")
                    Text("重试保存入组申请")
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    val review = reviewedDelivery
    if (review != null && canReview(review)) {
        AlertDialog(
            onDismissRequest = { clearDelivery(); pollRevision++ },
            properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = !confirmationBusy),
            title = { Text("核对对端密钥指纹") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PairingAnimatedValueRow("对端设备名称", remoteNameFor(review))
                    PairingValueRow("对端设备 ID", review.senderId)
                    PairingValueRow("服务器地址", review.scope.endpoint)
                    PairingValueRow("群组", review.scope.groupId)
                    Text("对端签名公钥指纹", style = MaterialTheme.typography.labelMedium)
                    Text("签名算法：Ed25519", style = MaterialTheme.typography.bodySmall)
                    SelectionContainer {
                        Text(
                            review.fingerprint.chunked(8).chunked(2).joinToString("\n") { it.joinToString(" ") },
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Text(
                        "请与对端设备显示的指纹逐项核对。指纹不一致或无法核实时，请取消。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row {
                        Checkbox(
                            checked = fingerprintChecked,
                            onCheckedChange = { fingerprintChecked = it; confirmationError = null },
                            enabled = !confirmationBusy,
                        )
                        Text("我已核对，指纹与对端一致", modifier = Modifier.weight(1f).padding(top = 12.dp))
                    }
                    if (confirmationBusy) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("正在核对并确认密钥", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    confirmationError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = fingerprintChecked && !confirmationBusy,
                    onClick = {
                        if (!canReview(review)) {
                            clearDelivery()
                            deliveryError = "配对状态已改变，请重新核对"
                            pollRevision++
                        } else {
                            confirmationBusy = true
                            confirmationError = null
                            val generation = confirmationGeneration
                            confirmationJob = scope.launch {
                                try {
                                    val latest = runCatching {
                                        require(isCurrentScope(review.scope))
                                        require(connectionProvider.savedApplication() == review.applicationId)
                                        val fresh = connectionProvider.pendingDelivery()?.let(::pairingKeyDelivery)
                                        currentCoroutineContext().ensureActive()
                                        require(fresh == review && canReview(review))
                                        connectionProvider.confirmDelivery(
                                            review.scope.endpoint, review.scope.nodeId, review.scope.groupId,
                                            review.applicationId, review.deliveryId, review.fingerprint,
                                        )
                                    }
                                    currentCoroutineContext().ensureActive()
                                    if (generation != confirmationGeneration || !isCurrentScope(review.scope)) return@launch
                                    latest.fold(
                                        onSuccess = { state ->
                                            clearDelivery(cancelConfirmation = false)
                                            val checked = membership
                                            val paired = state.optJSONObject("local_device")?.let { local ->
                                                checked != null && checked.isActiveMember &&
                                                    state.optBoolean("server_connected") &&
                                                    state.optString("group_id") == review.scope.groupId &&
                                                    local.optString("id") == checked.scope.deviceId &&
                                                    local.optString("public_key") == checked.scope.publicKey &&
                                                    state.optString("pairing_status") == "paired"
                                            } ?: false
                                            securePairingStatus = if (paired) "已安全配对" else "等待安全状态核对"
                                            notice = if (paired) "已核对指纹并完成安全配对" else "指纹已确认，等待安全状态核对"
                                            notify(notice.orEmpty())
                                            pollRevision++
                                        },
                                        onFailure = {
                                            fingerprintChecked = false
                                            confirmationError = "密钥确认失败或配对状态已改变。请重新核对后重试。"
                                        },
                                    )
                                } finally {
                                    if (generation == confirmationGeneration) {
                                        confirmationBusy = false
                                        confirmationJob = null
                                    }
                                }
                            }
                        }
                    },
                ) { Text("确认指纹并接收密钥") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val wasConfirming = confirmationBusy
                    clearDelivery()
                    pollRevision++
                    notice = if (wasConfirming) "已关闭确认，请重新核对配对状态" else "已取消本次指纹确认"
                }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun PairingValueRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PairingAnimatedValueRow(label: String, value: String?) {
    val reducedMotion = LocalReduceGlassMotion.current
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            (fadeIn(GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion)) togetherWith
                fadeOut(GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion)))
                .using(SizeTransform(clip = false) { _, _ ->
                    tween(if (reducedMotion) 0 else GlassMotion.MorphMillis, easing = GlassMotion.EaseOut)
                })
        },
        label = "pairing-$label",
    ) { shown ->
        if (shown != null) PairingValueRow(label, shown)
    }
}

@Composable
private fun PairingAnimatedRemoteRows(device: PairingRemoteDevice?) {
    val reducedMotion = LocalReduceGlassMotion.current
    AnimatedContent(
        targetState = device,
        transitionSpec = {
            (fadeIn(GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion)) togetherWith
                fadeOut(GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion)))
                .using(SizeTransform(clip = false) { _, _ ->
                    tween(if (reducedMotion) 0 else GlassMotion.MorphMillis, easing = GlassMotion.EaseOut)
                })
        },
        label = "pairing-remote-device",
    ) { shown ->
        if (shown != null) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PairingValueRow("对端设备名称", shown.deviceName ?: "对方尚未共享设备名称")
                PairingValueRow("对端设备 ID", shown.deviceId)
            }
        }
    }
}


