package com.dafeiyu.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dafeiyu.mobile.ui.components.FishGlyph
import com.dafeiyu.mobile.ui.components.applyButtonDeformation

enum class SandboxMode(val title: String, val glyph: String, val description: String) {
    ReadOnly("只读", "lock", "只查看与分析文件"),
    WorkspaceWrite("工作区内修改", "folderedit", "允许编辑当前工作目录中的文件"),
    FullAccess("完全权限", "unlock", "允许操作工作目录之外的文件"),
}

/** Remote presets are persisted by the PC. Local presets wait for verified executor capabilities. */
@Composable
fun SandboxMenu(
    expanded: Boolean,
    state: ModelMenuMorphState,
    selectedMode: SandboxMode,
    onModeSelected: (SandboxMode) -> Unit,
    onDismiss: () -> Unit,
    reduceTransparency: Boolean = false,
    onOverlayProgress: (Float) -> Unit = {},
    remoteOptions: List<Pair<String, String>>? = null,
    selectedRemoteValue: String? = null,
    onRemoteSelected: (String) -> Unit = {},
    localAvailable: Boolean = false,
    localSupportedModes: Set<SandboxMode> = if (localAvailable) SandboxMode.entries.toSet() else emptySet(),
    localVerificationBusy: Boolean = false,
    localVerificationDetail: String? = null,
    onVerifyLocal: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    AnchoredGlassMenu(expanded, state, onDismiss,
        reduceTransparency = reduceTransparency,
        menuWidth = 300.dp, menuHeight = 400.dp,
        onOverlayProgress = onOverlayProgress,
        sourceContent = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                FishGlyph(if (remoteOptions != null) "lock" else selectedMode.glyph, Modifier.size(19.dp), colors.onSurface)
            }
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("沙箱权限", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)
                    .applyButtonDeformation(circularHit = true, drawShadow = false).clip(CircleShape)
                    .semantics { contentDescription = "关闭沙箱菜单" },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = androidx.compose.ui.graphics.Color.Transparent)) {
                    FishGlyph("close", Modifier.size(16.dp))
                }
            }
            if (remoteOptions != null) {
                remoteOptions.forEach { (value, label) ->
                    Row(Modifier.fillMaxWidth().applyButtonDeformation(consumeDrag = false)
                        .clip(RoundedCornerShape(18.dp))
                        .selectable(value == selectedRemoteValue, role = Role.RadioButton, onClick = { onRemoteSelected(value); onDismiss() })
                        .heightIn(min = 56.dp).padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f), fontSize = 14.sp)
                        RadioButton(value == selectedRemoteValue, onClick = null)
                    }
                }
                Text(if (remoteOptions.isEmpty()) "请先读取电脑的权限目录。" else "修改电脑的全局默认沙箱与审批预设。",
                    fontSize = 10.sp, color = colors.onSurfaceVariant)
            } else SandboxMode.entries.forEach { mode ->
                val selected = mode == selectedMode
                val available = mode in localSupportedModes && !localVerificationBusy
                Row(Modifier.fillMaxWidth().applyButtonDeformation(consumeDrag = false)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (selected) colors.primary.copy(alpha = .10f) else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(selected, enabled = available, role = Role.RadioButton, onClick = { onModeSelected(mode); onDismiss() })
                    .heightIn(min = 62.dp).padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FishGlyph(mode.glyph, Modifier.size(21.dp), if (selected) colors.primary else colors.onSurface)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(mode.title + if (available) "" else "（待验证）", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(mode.description, fontSize = 11.sp, color = colors.onSurfaceVariant)
                        Text(if (mode == SandboxMode.FullAccess) "审批预设：不发起权限升级；仍遵守 Android 权限" else "审批预设：需要扩大权限时询问一次",
                            fontSize = 10.sp, color = colors.onSurfaceVariant)
                    }
                    RadioButton(selected = selected, onClick = null, enabled = available)
                }
            }
            if (remoteOptions == null) {
                Text(localVerificationDetail ?: "正在验证手机执行器的实际约束，档位暂时不可用。", fontSize = 10.sp, color = colors.onSurfaceVariant)
                onVerifyLocal?.let { verify -> TextButton(onClick = verify, enabled = !localVerificationBusy) {
                    Text(if (localVerificationBusy) "正在验证…" else "验证本机执行约束")
                } }
            }
        }
    }
}
