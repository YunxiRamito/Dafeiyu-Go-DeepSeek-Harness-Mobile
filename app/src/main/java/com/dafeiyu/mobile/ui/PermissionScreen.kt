package com.dafeiyu.mobile.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.dafeiyu.mobile.ui.components.FishButton
import com.dafeiyu.mobile.ui.components.FishCard
import com.dafeiyu.mobile.ui.components.SectionLabel
import com.dafeiyu.mobile.data.ApplicationPermissionBridge
import kotlinx.coroutines.launch
import java.io.File

/** Android permission status and system-entry surface. It never claims external capabilities are granted. */
@Composable
fun PermissionsScreen(onBack: () -> Unit, notify: (String) -> Unit) {
    val context = LocalContext.current
    val permissionBridge = remember(context) { ApplicationPermissionBridge.get(context) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val collapseRange = with(LocalDensity.current) { 112.dp.toPx() }
    val collapsedTitle = rememberCollapsedTitleState(scroll.value, "权限与系统", collapseRange)
    PublishSettingsNavigation(collapsedTitle.collapsedTitle, collapsedTitle.progress, scroll.value.toFloat())
    val activity = remember(context) { context.permissionActivity() }
    var status by remember(context) { mutableStateOf(readPermissionStatus(context)) }
    val latestNotify by rememberUpdatedState(notify)
    val refresh = { status = readPermissionStatus(context) }
    val refreshOnResume by rememberUpdatedState(refresh)

    DisposableEffect(activity) {
        val lifecycle = (activity as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshOnResume()
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    fun requestPermission(permission: String, purpose: String) {
        scope.launch {
            permissionBridge.requestConsent(permission, purpose)
            refresh()
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        refresh()
        latestNotify(if (uri != null) "系统照片选择器可用；本次未上传照片" else "未选择照片")
    }

    fun openSettings(intent: Intent) {
        val launchIntent = Intent(intent)
        if (launchIntent.resolveActivity(context.packageManager) == null) {
            latestNotify("当前系统未提供这个设置入口，请在系统设置中手动查看")
            return
        }
        try {
            context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            latestNotify("当前系统未提供这个设置入口，请在系统设置中手动查看")
        } catch (_: SecurityException) {
            latestNotify("系统拒绝打开这个设置入口，请在系统设置中手动查看")
        }
    }

    fun appDetails(packageName: String = context.packageName) = openSettings(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
    )

    fun openPhotoPicker() {
        if (!status.photoPickerAvailable) {
            latestNotify("系统没有可用的照片或文件选择器")
            return
        }
        try {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (_: Exception) {
            openSettings(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"))
        }
    }

    fun openAppNotificationSettings() {
        openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        })
    }

    val notificationDeclared = status.isDeclared(Manifest.permission.POST_NOTIFICATIONS)
    val notificationStatus = when {
        Build.VERSION.SDK_INT >= 33 && !notificationDeclared -> "未声明 · 需应用接入"
        !status.notificationPermission -> "未允许"
        !status.notificationsEnabled -> "系统通知已关闭"
        else -> "已允许"
    }
    val cameraStatus = when {
        !status.isDeclared(Manifest.permission.CAMERA) -> "未声明 · 需应用接入"
        status.cameraPermission -> "已允许"
        else -> "未允许"
    }
    val allFilesStatus = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> "系统不需要此权限"
        !status.isDeclared(Manifest.permission.MANAGE_EXTERNAL_STORAGE) -> "未声明 · 需应用接入"
        status.allFilesAccess -> "已允许"
        else -> "未允许 · 需系统设置"
    }
    val overlayStatus = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M -> "系统不需要此权限"
        !status.isDeclared(Manifest.permission.SYSTEM_ALERT_WINDOW) -> "未声明 · 需应用接入"
        status.overlayAllowed -> "已允许"
        else -> "未允许"
    }
    val accessibilityStatus = when {
        !status.accessibilityServiceDeclared -> "未接入 · 需应用服务"
        status.accessibilityEnabled -> "已启用本应用服务"
        else -> "未启用 · 需系统设置"
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        SubpageHeader("权限与系统", "按需开启，由 Android 管理授权", onBack, collapseProgress = collapsedTitle.progress)
        Text(
            "每个条目都显示系统实际可读状态。无法由本应用确认或申请的能力会标为“需系统/外部工具”，打开入口不会自动授予权限。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )

        PermissionGroup("常用", "应用日常功能所需的系统能力") {
            PermissionRow(
                "通知",
                "系统通知许可与应用通知总开关",
                notificationStatus,
                if (Build.VERSION.SDK_INT >= 33 && notificationDeclared && !status.notificationPermission) "允许通知" else "通知设置",
            ) {
                if (Build.VERSION.SDK_INT >= 33 && notificationDeclared && !status.notificationPermission) {
                    requestPermission("notifications", "用于提醒任务运行与完成、设备状态、公告和通知；各类别仍由通知设置中的开关控制。")
                } else {
                    openAppNotificationSettings()
                }
            }
            PermissionDivider()
            PermissionRow(
                "相机",
                "扫码和拍摄功能使用的运行时权限",
                cameraStatus,
                if (status.cameraPermission) "应用设置" else "允许相机",
            ) {
                if (!status.isDeclared(Manifest.permission.CAMERA)) {
                    appDetails()
                } else if (!status.cameraPermission) {
                    requestPermission("camera", "用于扫码配对和拍摄对话附件；画面在本机处理，只有你发送的照片才交给模型。")
                } else {
                    appDetails()
                }
            }
            PermissionDivider()
            PermissionRow(
                "相册",
                "在附件面板读取你授权的相册照片，也可仅允许选中的照片",
                when (permissionBridge.state("media")) { "granted" -> "已允许"; "limited" -> "已允许 · 仅选中照片"; else -> "未允许" },
                if (permissionBridge.state("media") == "denied") "允许相册" else "选择照片",
            ) {
                if (permissionBridge.state("media") == "denied") requestPermission("media", "用于在附件面板展示你授权的照片。照片仅在你主动发送时提供给所选模型。")
                else openPhotoPicker()
            }
            PermissionDivider()
            PermissionRow(
                "所有文件",
                "管理外部存储中的全部文件；仅由系统设置授予",
                allFilesStatus,
                if (status.isDeclared(Manifest.permission.MANAGE_EXTERNAL_STORAGE)) "管理文件权限" else "应用设置",
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && status.isDeclared(Manifest.permission.MANAGE_EXTERNAL_STORAGE)) {
                    requestPermission("all_files", "用于你指定的文件任务访问手机共享存储。请仅在确实需要处理这些目录时授权；模型不能自动授予该权限。")
                } else {
                    appDetails()
                }
            }
        }

        PermissionGroup("后台能力", "影响后台运行、通知常驻和浮层显示") {
            PermissionRow(
                "后台运行白名单",
                "厂商后台策略没有统一授权状态，进入应用电池管理页查看",
                "需系统设置",
                "查看后台设置",
            ) { appDetails() }
            PermissionDivider()
            PermissionRow(
                "电池优化白名单",
                "Android 电池优化豁免状态",
                if (status.batteryExempt) "已加入白名单" else "未加入白名单",
                "电池优化名单",
            ) { openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            PermissionDivider()
            PermissionRow(
                "常驻通知",
                "本应用没有声明常驻前台服务；通知开关不能等同于常驻通知授权",
                "需系统/应用服务",
                "通知设置",
            ) { openAppNotificationSettings() }
            PermissionDivider()
            PermissionRow(
                "悬浮窗",
                "允许应用在其他应用上层显示内容",
                overlayStatus,
                "悬浮窗设置",
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                } else {
                    latestNotify("当前 Android 版本不需要单独的悬浮窗权限")
                }
            }
        }

        PermissionGroup("执行能力", "需要系统服务、开发者工具或设备管理权限") {
            PermissionRow(
                "无障碍",
                if (status.accessibilityServiceDeclared) "系统无障碍服务的启用状态" else "当前应用未声明无障碍服务，不会伪造已启用状态",
                accessibilityStatus,
                "无障碍设置",
            ) { openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            PermissionDivider()
            PermissionRow(
                "Shizuku",
                "仅检测 Shizuku 应用是否安装；授权状态需要 Shizuku SDK 和外部启动",
                when {
                    !status.shizukuInstalled -> "未安装 · 需外部工具"
                    else -> "已安装 · 授权未接入"
                },
                "查看 Shizuku",
            ) {
                if (status.shizukuInstalled) appDetails(SHIZUKU_PACKAGE)
                else latestNotify("未检测到 Shizuku；请通过外部工具安装并启动")
            }
            PermissionDivider()
            PermissionRow(
                "ADB",
                "只读取开发者选项中的 ADB 开关，不代表本应用已获得 ADB 控制权",
                when (status.adbEnabled) {
                    true -> "设备 ADB 已开启 · 应用未接入"
                    false -> "设备 ADB 未开启 · 应用未接入"
                    null -> "不可读取 · 需外部工具"
                },
                "开发者选项",
            ) { openSettings(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            PermissionDivider()
            PermissionRow(
                "Root",
                "只检查常见 su 路径线索，不执行 su，也不把文件存在视为 root 授权",
                if (status.rootCandidate) "发现 su 线索 · 状态未验证" else "未发现线索 · 状态未验证",
                "查看说明",
            ) { latestNotify("本应用不会申请或执行 Root；当前只读取常见 su 路径线索") }
        }

        PermissionGroup("敏感权限", "默认不申请；只有对应功能明确需要时才应由系统授权") {
            PermissionRow(
                "短信读取",
                "当前应用未声明短信读取权限，不会读取短信",
                sensitivePermissionStatus(status, Manifest.permission.READ_SMS),
                "应用设置",
            ) { appDetails() }
            PermissionDivider()
            PermissionRow(
                "联系人读取",
                "当前应用未声明联系人读取权限，不会读取联系人",
                sensitivePermissionStatus(status, Manifest.permission.READ_CONTACTS),
                "应用设置",
            ) { appDetails() }
            PermissionDivider()
            PermissionRow(
                "位置",
                "当前应用未接入位置功能，不会在后台读取位置",
                when {
                    !status.isDeclared(Manifest.permission.ACCESS_COARSE_LOCATION) && !status.isDeclared(Manifest.permission.ACCESS_FINE_LOCATION) -> "未声明 · 未接入"
                    status.locationPermission -> "已允许 · 当前功能未使用"
                    else -> "未允许 · 当前功能未使用"
                },
                "应用设置",
            ) { appDetails() }
            PermissionDivider()
            PermissionRow(
                "传感器",
                "只展示设备是否存在常用传感器；读取权限和业务接入仍未启用",
                when {
                    status.sensorPermission -> "已允许 · 当前功能未使用"
                    !status.sensorHardware -> "未检测到常用传感器 · 未接入"
                    status.isDeclared(Manifest.permission.BODY_SENSORS) -> "设备有传感器 · 未允许"
                    else -> "设备有传感器 · 未声明"
                },
                "应用设置",
            ) { appDetails() }
        }

        FishButton(
            onClick = { refresh(); latestNotify("已重新读取系统权限与能力状态") },
            accent = false,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("刷新状态") }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun PermissionGroup(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(title)
        FishCard {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                Text(
                    subtitle,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                content()
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    status: String,
    action: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = permissionStatusColor(status),
            )
        }
        FishButton(onClick = onClick, accent = false, modifier = Modifier.fillMaxWidth()) { Text(action) }
    }
}

@Composable
private fun PermissionDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
}

@Composable
private fun permissionStatusColor(status: String): Color {
    val palette = MaterialTheme.colorScheme
    return when {
        status.startsWith("已允许") || status.startsWith("已加入") || status.startsWith("已启用") || status == "系统选择器可用" -> palette.primary
        status.contains("未允许") || status.contains("未加入") || status.contains("系统通知已关闭") -> palette.error
        else -> palette.onSurfaceVariant
    }
}

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

private data class PermissionStatus(
    val notificationPermission: Boolean,
    val notificationsEnabled: Boolean,
    val cameraPermission: Boolean,
    val allFilesAccess: Boolean,
    val batteryExempt: Boolean,
    val overlayAllowed: Boolean,
    val accessibilityEnabled: Boolean,
    val accessibilityServiceDeclared: Boolean,
    val shizukuInstalled: Boolean,
    val adbEnabled: Boolean?,
    val rootCandidate: Boolean,
    val smsPermission: Boolean,
    val contactsPermission: Boolean,
    val locationPermission: Boolean,
    val sensorPermission: Boolean,
    val sensorHardware: Boolean,
    val declaredPermissions: Set<String>,
    val photoPickerAvailable: Boolean,
) {
    fun isDeclared(permission: String): Boolean = permission in declaredPermissions
}

private fun readPermissionStatus(context: Context): PermissionStatus {
    fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    val requestedPermissions = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
    }.getOrDefault(emptySet())
    val notificationPermission = Build.VERSION.SDK_INT < 33 || hasPermission(Manifest.permission.POST_NOTIFICATIONS)
    val notificationsEnabled = try {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    } catch (_: RuntimeException) {
        false
    }
    val allFilesAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try { Environment.isExternalStorageManager() } catch (_: RuntimeException) { false }
    } else true
    val batteryExempt = try {
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    } catch (_: RuntimeException) {
        false
    }
    val overlayAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        try { Settings.canDrawOverlays(context) } catch (_: RuntimeException) { false }
    } else true
    val accessibilityEnabled = try {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        enabled.split(':').any { it.equals("${context.packageName}/", ignoreCase = true) || it.startsWith("${context.packageName}/", ignoreCase = true) }
    } catch (_: RuntimeException) {
        false
    }
    val accessibilityServiceDeclared = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SERVICES)
            .services.orEmpty()
            .any { it.permission == Manifest.permission.BIND_ACCESSIBILITY_SERVICE }
    }.getOrDefault(false)
    val shizukuInstalled = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: RuntimeException) {
        false
    }
    val adbEnabled = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED) == 1
    } catch (_: RuntimeException) {
        null
    }
    val rootCandidate = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su")
        .any { path -> try { File(path).exists() } catch (_: SecurityException) { false } }
    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    val sensorHardware = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null ||
        sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
    val locationPermission = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ||
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    val photoPickerAvailable = Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("image/*")
        .resolveActivity(context.packageManager) != null
    return PermissionStatus(
        notificationPermission = notificationPermission,
        notificationsEnabled = notificationsEnabled,
        cameraPermission = hasPermission(Manifest.permission.CAMERA),
        allFilesAccess = allFilesAccess,
        batteryExempt = batteryExempt,
        overlayAllowed = overlayAllowed,
        accessibilityEnabled = accessibilityEnabled,
        accessibilityServiceDeclared = accessibilityServiceDeclared,
        shizukuInstalled = shizukuInstalled,
        adbEnabled = adbEnabled,
        rootCandidate = rootCandidate,
        smsPermission = hasPermission(Manifest.permission.READ_SMS),
        contactsPermission = hasPermission(Manifest.permission.READ_CONTACTS),
        locationPermission = locationPermission,
        sensorPermission = hasPermission(Manifest.permission.BODY_SENSORS),
        sensorHardware = sensorHardware,
        declaredPermissions = requestedPermissions,
        photoPickerAvailable = photoPickerAvailable,
    )
}

private fun sensitivePermissionStatus(status: PermissionStatus, permission: String): String = when {
    !status.isDeclared(permission) -> "未声明 · 未接入"
    status.hasPermission(permission) -> "已允许 · 当前功能未使用"
    else -> "未允许 · 当前功能未使用"
}

private fun PermissionStatus.hasPermission(permission: String): Boolean = when (permission) {
    Manifest.permission.READ_SMS -> smsPermission
    Manifest.permission.READ_CONTACTS -> contactsPermission
    else -> false
}

private fun Context.permissionActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val base = current.baseContext
        if (base === current) return null
        current = base
    }
    return current as? Activity
}
