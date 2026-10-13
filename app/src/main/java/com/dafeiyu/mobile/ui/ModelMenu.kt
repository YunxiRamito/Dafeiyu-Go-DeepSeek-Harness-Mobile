package com.dafeiyu.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.dafeiyu.mobile.data.DemoRepository
import com.dafeiyu.mobile.ui.components.FishGlyph
import com.dafeiyu.mobile.ui.components.LocalReduceGlassTransparency
import com.dafeiyu.mobile.ui.components.applyButtonDeformation
import kotlin.math.roundToInt

@Composable
fun ModelMenu(
    repository: DemoRepository,
    onDismiss: () -> Unit,
    onManage: () -> Unit,
    expanded: Boolean = true,
    onSurfaceVisibilityChanged: (Boolean) -> Unit = {},
    morphState: ModelMenuMorphState = rememberModelMenuMorphState(expanded),
    onOverlayProgress: (Float) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    var manageAfterCollapse by remember { mutableStateOf(false) }
    AnchoredGlassMenu(
        expanded = expanded, state = morphState, onDismiss = onDismiss,
        reduceTransparency = LocalReduceGlassTransparency.current,
        onSurfaceVisibilityChanged = onSurfaceVisibilityChanged,
        onOverlayProgress = onOverlayProgress,
        onClosed = { if (manageAfterCollapse) { manageAfterCollapse = false; onManage() } },
        sourceContent = {
            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(listOfNotNull(repository.displaySelectedModel.name.substringBefore(" ·"),
                    repository.displayReasoningOption?.label).joinToString(" · "),
                    Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(5.dp))
                FishGlyph("chevronDown", Modifier.size(11.dp), colors.onSurfaceVariant)
            }
        },
    ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("模型与推理", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)
                        .applyButtonDeformation(circularHit = true, drawShadow = false).clip(CircleShape)
                        .semantics { contentDescription = "关闭模型菜单" },
                        colors = IconButtonDefaults.iconButtonColors(containerColor = androidx.compose.ui.graphics.Color.Transparent)) {
                        FishGlyph("close", Modifier.size(16.dp))
                    }
                }
                repository.displayModelChoices.filter { !it.isExample }.forEach { model ->
                    Row(Modifier.fillMaxWidth().applyButtonDeformation(consumeDrag = false)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(role = Role.RadioButton) { repository.selectModel(model.id) }
                        .heightIn(min = 56.dp).padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(model.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(model.provider, fontSize = 11.sp, color = colors.onSurfaceVariant)
                        }
                        RadioButton(selected = repository.displaySelectedModel.id == model.id, onClick = null)
                    }
                }
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = .5f))
                val options = repository.displaySelectedModel.reasoningOptions
                if (options.isEmpty()) {
                    Text("思考方式由服务端决定", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 10.dp))
                } else {
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("推理方式", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Text(repository.displayReasoningOption?.label.orEmpty(), color = colors.primary,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    if (options.size == 2) Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.forEachIndexed { index, option ->
                            val chosen = repository.displayReasoningIndex == index
                            Box(Modifier.weight(1f).applyButtonDeformation(consumeDrag = false)
                                .clip(RoundedCornerShape(18.dp))
                                .background(if (chosen) colors.primary.copy(alpha = .14f) else colors.onSurface.copy(alpha = .04f))
                                .selectable(selected = chosen, role = Role.RadioButton, onClick = { repository.setReasoningIndex(index) })
                                .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                                Text(option.label, color = if (chosen) colors.primary else colors.onSurface, fontSize = 13.sp)
                            }
                        }
                    } else if (options.size > 2) Slider(value = repository.displayReasoningIndex.coerceAtLeast(0).toFloat(),
                        onValueChange = { repository.setReasoningIndex(it.roundToInt()) },
                        valueRange = 0f..options.lastIndex.toFloat(), steps = (options.size - 2).coerceAtLeast(0),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${repository.displaySelectedModel.name}推理档位" })
                    if (options.size > 2) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        options.forEach { option -> Text(option.label, fontSize = 11.sp, color = colors.onSurfaceVariant) }
                    }
                }
                TextButton(onClick = { manageAfterCollapse = true; onDismiss() },
                    modifier = Modifier.fillMaxWidth().applyButtonDeformation(consumeDrag = false)
                        .clip(RoundedCornerShape(24.dp))) {
                    FishGlyph("settings", Modifier.size(16.dp)); Text("管理模型供应商", Modifier.padding(start = 8.dp))
                }
                Text(if (repository.displayModelChoices.isEmpty()) "请先在设置中获取模型" else "推理选项来自当前模型的能力声明",
                    fontSize = 10.sp, color = colors.onSurfaceVariant)
            }

    }
}
