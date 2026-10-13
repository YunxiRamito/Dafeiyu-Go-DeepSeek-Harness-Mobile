package com.dafeiyu.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dafeiyu.mobile.data.OfficialToolApprovalBridge

@Composable
internal fun OfficialToolApprovalHost(bridge: OfficialToolApprovalBridge = OfficialToolApprovalBridge.instance) {
    val request by bridge.pending.collectAsState()
    request?.let { pending -> AlertDialog(
        onDismissRequest = { bridge.answer(pending.id, false) },
        title = { Text("允许这一次操作？") },
        text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("执行设备：本机 Android\n工作区：${pending.workspaceId}\n${pending.summary}")
            Text(pending.content, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("仅授权上方内容本次执行。取消、超时或离开任务后不会继续执行。", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = { bridge.answer(pending.id, true) }) { Text("允许一次") } },
        dismissButton = { TextButton(onClick = { bridge.answer(pending.id, false) }) { Text("拒绝") } },
    ) }
}
