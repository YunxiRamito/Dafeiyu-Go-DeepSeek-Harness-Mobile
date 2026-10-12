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

/** Capability selector preview only: selecting a row never grants an OS or DSH permission. */
@Composable
fun SandboxMenu(
    expanded: Boolean,
    state: ModelMenuMorphState,
    selectedMode: SandboxMode,
    onModeSelected: (SandboxMode) -> Unit,
    onDismiss: () -> Unit,
    reduceTransparency: Boolean = false,
    onOverlayProgress: (Float) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    AnchoredGlassMenu(expanded, state, onDismiss,
        reduceTransparency = reduceTransparency,
        menuWidth = 300.dp, menuHeight = 400.dp,
        onOverlayProgress = onOverlayProgress,
        sourceContent = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                FishGlyph(selectedMode.glyph, Modifier.size(19.dp), colors.onSurface)
            }
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("沙箱权限", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)
                    .applyButtonDeformation(circularHit = true).clip(CircleShape)
                    .semantics { contentDescription = "关闭沙箱菜单" }) {
                    FishGlyph("close", Modifier.size(16.dp))
                }
            }
            SandboxMode.entries.forEach { mode ->
                val selected = mode == selectedMode
                Row(Modifier.fillMaxWidth().applyButtonDeformation(consumeDrag = false)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (selected) colors.primary.copy(alpha = .10f) else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(selected, role = Role.RadioButton, onClick = { onModeSelected(mode); onDismiss() })
                    .heightIn(min = 62.dp).padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FishGlyph(mode.glyph, Modifier.size(21.dp), if (selected) colors.primary else colors.onSurface)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(mode.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(mode.description, fontSize = 11.sp, color = colors.onSurfaceVariant)
                    }
                    RadioButton(selected = selected, onClick = null)
                }
            }
            Text("权限档位仍为预留。实际脚本在应用内 Linux 工作副本运行；此选择不改变脚本或 Android 权限。", fontSize = 10.sp, color = colors.onSurfaceVariant)
        }
    }
}
