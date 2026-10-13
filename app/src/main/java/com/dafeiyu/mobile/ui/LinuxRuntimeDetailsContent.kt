package com.dafeiyu.mobile.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dafeiyu.mobile.data.DemoRepository
import com.dafeiyu.mobile.data.LinuxComponentStatus
import com.dafeiyu.mobile.data.LinuxRuntimeManager
import com.dafeiyu.mobile.ui.components.FishButton
import com.dafeiyu.mobile.ui.components.FishDivider
import com.dafeiyu.mobile.ui.components.FishGlyph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class RuntimeHostDiagnostics(val processId: Int, val privateMemoryKiB: Int?, val availableMemory: Long?)

/** Read-only diagnostics. PRoot is labelled accurately; no synthetic VM counters or logs. */
@Composable
internal fun LinuxRuntimeDetailsContent(repository: DemoRepository) {
    val context = LocalContext.current.applicationContext
    val manager = remember(context) { LinuxRuntimeManager(context) }
    val components by manager.snapshot.collectAsState()
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var diagnostics by remember { mutableStateOf<RuntimeHostDiagnostics?>(null) }

    suspend fun refresh() {
        reading = true
        error = null
        try {
            manager.refresh()
            diagnostics = withContext(Dispatchers.IO) {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val hostMemory = activityManager?.let { service -> ActivityManager.MemoryInfo().also(service::getMemoryInfo) }
                val processMemory = activityManager?.getProcessMemoryInfo(intArrayOf(Process.myPid()))?.firstOrNull()
                RuntimeHostDiagnostics(Process.myPid(), processMemory?.totalPss, hostMemory?.availMem)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "运行状态读取失败"
        } finally {
            reading = false
        }
    }
    LaunchedEffect(manager) { refresh() }
    DetailCard {
        Text("运行环境", style = MaterialTheme.typography.titleLarge)
        Text("Ubuntu 用户空间 · PRoot", style = MaterialTheme.typography.titleMedium)
        Hint("使用 Android 内核。完整虚拟机和独立沙箱尚待验证；组件安装在「组件」页面管理。")
        val ubuntu = components.firstOrNull { it.id == "ubuntu" }
        Text(when (ubuntu?.status) {
            LinuxComponentStatus.READY -> "环境已安装 · ${ubuntu.version}"
            LinuxComponentStatus.INSTALLING -> "环境安装中"
            LinuxComponentStatus.UNSUPPORTED -> "当前设备不支持此环境"
            LinuxComponentStatus.ERROR -> "环境检测失败"
            else -> if (reading) "正在检测环境…" else "尚未安装环境"
        }, style = MaterialTheme.typography.bodyLarge)
        ubuntu?.detail?.takeIf { it.isNotBlank() }?.let { Hint(it) }
        FishButton(onClick = { scope.launch { refresh() } }, enabled = !reading,
            accent = false, modifier = Modifier.fillMaxWidth()) {
            FishGlyph("search", Modifier.size(18.dp))
            Text(if (reading) "读取中…" else "刷新状态")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    DetailCard {
        Text("主机与资源", style = MaterialTheme.typography.titleMedium)
        Hint("Android ${Build.VERSION.RELEASE} · ${Build.SUPPORTED_ABIS.joinToString()}")
        diagnostics?.let {
            Text("应用进程 PID ${it.processId}", style = MaterialTheme.typography.bodyMedium)
            Text("应用内存 ${it.privateMemoryKiB?.let { value -> "${value / 1024} MiB" } ?: "不可用"}", style = MaterialTheme.typography.bodyMedium)
            Text("系统可用内存 ${it.availableMemory?.let { value -> "${value / (1024 * 1024)} MiB" } ?: "不可用"}", style = MaterialTheme.typography.bodyMedium)
        }
        Hint("下载线路：${componentDownloadSourceLabel(repository.downloadSource)}")
    }
    DetailCard {
        Text("组件诊断", style = MaterialTheme.typography.titleMedium)
        components.forEachIndexed { index, component ->
            if (index > 0) FishDivider()
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${component.title} · ${when (component.status) {
                        LinuxComponentStatus.READY -> component.version.ifBlank { "已安装" }
                        LinuxComponentStatus.INSTALLING -> "安装中"
                        LinuxComponentStatus.MISSING -> "未安装"
                        LinuxComponentStatus.ERROR -> "错误"
                        LinuxComponentStatus.UNSUPPORTED -> "不支持"
                    }}", style = MaterialTheme.typography.bodyMedium)
                    Hint(component.detail)
                }
            }
        }
        if (components.isEmpty()) Hint("尚未读取到组件信息。")
    }
}
