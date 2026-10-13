package com.dafeiyu.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.dafeiyu.mobile.data.AriesConnectionProvider
import com.dafeiyu.mobile.data.DemoRepository
import com.dafeiyu.mobile.ui.components.FishGlyph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject

/** Server reachability and peer presence are deliberately presented as separate facts. */
@Composable
internal fun SettingsConnectionSummary(repository: DemoRepository) {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val provider = remember(context) { AriesConnectionProvider(context) }
    var snapshot by remember(repository.ariesEndpoint, repository.ariesGroupId, repository.ariesDeviceId) {
        mutableStateOf<JSONObject?>(null)
    }
    var reachable by remember(repository.ariesEndpoint) { mutableStateOf(false) }
    LaunchedEffect(provider, lifecycleOwner, repository.ariesEndpoint, repository.ariesGroupId, repository.ariesDeviceId) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (repository.ariesEndpoint.isBlank() || repository.ariesDeviceId.isNullOrBlank()) {
                reachable = false
                return@repeatOnLifecycle
            }
            while (isActive) {
                try {
                    val current = provider.call("get_connection_state")
                    snapshot = current
                    reachable = current.optBoolean("server_connected", false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    reachable = false
                }
                delay(30_000L)
            }
        }
    }
    val peer = snapshot?.optJSONObject("remote_device")
    val peerName = peer?.takeIf { !it.isNull("name") }?.optString("name")?.takeIf { it.isNotBlank() }
        ?: "对方尚未共享设备名称"
    val detail = when {
        peer != null -> "$peerName · ${if (!reachable) "状态未知" else when (peer.optString("online")) {
            "online" -> "在线"
            "offline" -> "离线"
            else -> "状态未知"
        }}"
        repository.ariesGroupId.isNullOrBlank() -> "尚未配对电脑"
        else -> "正在读取已配对设备信息"
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        FishGlyph("link", Modifier.size(22.dp), if (reachable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Aries 服务${if (reachable) "已连接" else "未连接"}", fontSize = 14.sp)
            Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
