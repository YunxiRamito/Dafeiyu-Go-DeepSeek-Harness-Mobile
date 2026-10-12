package com.dafeiyu.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dafeiyu.mobile.data.DemoRepository
import com.dafeiyu.mobile.data.RuntimeStatus
import com.dafeiyu.mobile.ui.components.*

@Composable
fun RunScreen(repository: DemoRepository, onChat: () -> Unit, onDetail: (String) -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val running = repository.runtimeStatus == RuntimeStatus.Running
    val working = repository.runtimeStatus == RuntimeStatus.Starting || repository.runtimeStatus == RuntimeStatus.Stopping
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        PageHeader("本地运行", "DSH 启动、停止与日志的界面预览")
        Row(Modifier.padding(bottom = 13.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("这台手机", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            TinyLabel("启动演示", primary = true)
        }
        FishCard {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp))
                        .background(colors.primary.copy(alpha = .08f)), contentAlignment = Alignment.Center) {
                        FishGlyph("whale", Modifier.size(31.dp), colors.primary)
                    }
                    Column(Modifier.weight(1f).padding(start = 13.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(
                                if (running) Color(0xFF20825D) else colors.onSurfaceVariant.copy(alpha = .5f)))
                            Text(when (repository.runtimeStatus) {
                                RuntimeStatus.Running -> "已就绪"
                                RuntimeStatus.Starting -> "正在准备"
                                RuntimeStatus.Stopping -> "正在停止"
                                RuntimeStatus.Stopped -> "等待启动"
                            }, fontSize = 12.sp, color = colors.onSurfaceVariant)
                        }
                        Text("DeepSeek Harness", fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }, modifier = Modifier.semantics {
                            contentDescription = "服务操作菜单"
                        }) { FishGlyph("more", Modifier.size(20.dp)) }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(text = { Text("运行详情") }, onClick = { showMenu = false; onDetail("runtime") })
                            DropdownMenuItem(text = { Text("重启服务") }, enabled = running,
                                onClick = { showMenu = false; repository.restartRuntime() })
                            DropdownMenuItem(text = { Text("停止服务") }, enabled = running || working,
                                onClick = { showMenu = false; repository.stopRuntime() })
                        }
                    }
                }
                Spacer(Modifier.height(22.dp))
                Text(if (running) "一切准备好了。\n开始一个对话，或者继续你的灵感。" else "你的工作区，就在手边。\nDSH 模式会自动演示启动。",
                    fontSize = 14.sp, lineHeight = 23.sp, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(19.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("当前模型", fontSize = 11.sp, color = colors.onSurfaceVariant)
                        Text("DeepSeek V4", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    TextButton(onClick = { onDetail("models") }, contentPadding = PaddingValues(0.dp)) {
                        Text("管理模型", fontSize = 12.sp)
                        Spacer(Modifier.width(5.dp)); FishGlyph("arrow", Modifier.size(14.dp), colors.primary)
                    }
                }
                Spacer(Modifier.height(16.dp))
                FishButton(onClick = { if (running) onChat() else repository.startRuntime() },
                    enabled = !working, modifier = Modifier.fillMaxWidth().heightIn(min = 49.dp)) {
                    if (working) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else FishGlyph(if (running) "chat" else "play", Modifier.size(18.dp), colors.onPrimary)
                    Spacer(Modifier.width(9.dp))
                    Text(if (running) "进入 DSH 预览" else if (working) repository.runtimeStep else "演示启动流程", fontSize = 14.sp)
                }
                AnimatedVisibility(visible = working) {
                    Column(Modifier.padding(top = 13.dp)) {
                        LinearProgressIndicator(progress = { repository.runtimeProgress / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("${repository.runtimeProgress}% · ${repository.runtimeStep}", fontSize = 11.sp,
                            color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        SectionLabel("最近使用", "查看用量", onAction = { onDetail("usage") })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            FishCard(modifier = Modifier.weight(1f), onClick = { onDetail("usage") }) {
                Column(Modifier.padding(16.dp)) {
                    Text("今日 Token", fontSize = 12.sp, color = colors.onSurfaceVariant)
                    Text("12,840", fontSize = 27.sp, fontWeight = FontWeight.Normal, modifier = Modifier.padding(top = 9.dp))
                    Spacer(Modifier.height(8.dp))
                    UsageBars(Modifier.fillMaxWidth().height(22.dp))
                    Text("示例数据 · 较昨日 +8%", fontSize = 10.sp, color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
            FishCard(modifier = Modifier.weight(1f), onClick = { onDetail("usage") }) {
                Column(Modifier.padding(16.dp)) {
                    Text("账户余额", fontSize = 12.sp, color = colors.onSurfaceVariant)
                    Text("¥ 38.42", fontSize = 27.sp, fontWeight = FontWeight.Normal, modifier = Modifier.padding(top = 9.dp))
                    Text("今天预计花费 ¥ 0.12", fontSize = 11.sp, color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 13.dp))
                    Text("示例数据", fontSize = 10.sp, color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 15.dp))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        SectionLabel("消息")
        FishCard {
            SettingRow("bell", "大肥鱼，来到手机上了", "先体验界面，一起决定下一步。") { onDetail("notice") }
        }
        Text("启动与对话为演示交互，尚未连接真实 DSH。", fontSize = 11.sp, lineHeight = 17.sp,
            color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 20.dp, bottom = 24.dp))
    }
}

@Composable
internal fun UsageBars(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val heights = listOf(.35f, .57f, .44f, .72f, .53f, .84f, .95f, .63f, .78f, .56f, .9f, .69f)
        val gap = 4.dp.toPx()
        val width = (size.width - gap * (heights.size - 1)) / heights.size
        heights.forEachIndexed { index, value ->
            drawRoundRect(primary.copy(alpha = if (index == heights.lastIndex) 1f else .22f + value * .30f),
                topLeft = androidx.compose.ui.geometry.Offset(index * (width + gap), size.height * (1 - value)),
                size = androidx.compose.ui.geometry.Size(width.coerceAtLeast(1f), size.height * value),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
        }
    }
}

