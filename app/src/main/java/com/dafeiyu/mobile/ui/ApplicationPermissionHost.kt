package com.dafeiyu.mobile.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.dafeiyu.mobile.data.ApplicationPermissionBridge

/** Explains every request before handing control to Android; denial leaves the app usable. */
@Composable
fun ApplicationPermissionHost(bridge: ApplicationPermissionBridge) {
    val request by bridge.pending.collectAsState()
    val context = LocalContext.current
    var launchedId by remember { mutableStateOf<String?>(null) }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        launchedId?.let(bridge::finish); launchedId = null
    }
    val systemSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        launchedId?.let(bridge::finish); launchedId = null
    }
    request?.takeIf { it.id != launchedId }?.let { pending ->
        val label = when (pending.permission) {
            "notifications" -> "通知"
            "camera" -> "相机"
            "media" -> "相册"
            else -> "所有文件"
        }
        fun close() { bridge.markExplained(pending.id); bridge.finish(pending.id) }
        AlertDialog(onDismissRequest = ::close, title = { Text("允许访问$label？") },
            text = { Text(pending.purpose + if (pending.permission == "media")
                "\n你可以仅授权选中的照片；拒绝后仍可使用系统选择器。" else "\n授权由你在系统界面决定。") },
            dismissButton = { TextButton(onClick = ::close) { Text("暂不允许") } },
            confirmButton = { TextButton(onClick = {
                bridge.markExplained(pending.id)
                launchedId = pending.id
                try {
                    when (pending.permission) {
                        "notifications" -> if (Build.VERSION.SDK_INT >= 33) runtime.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                            else systemSettings.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        "camera" -> runtime.launch(arrayOf(Manifest.permission.CAMERA))
                        "media" -> runtime.launch(when {
                            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
                            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                        })
                        "all_files" -> systemSettings.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}")))
                    }
                } catch (_: Exception) { bridge.finish(pending.id); launchedId = null }
            }) { Text("前往授权") } })
    }
}
