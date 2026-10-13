package com.dafeiyu.mobile.glasslab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

internal data class LabBackgroundChoice(val id: String, val label: String, val colors: List<String>)

internal val LabSolidBackgrounds = listOf(
    LabBackgroundChoice("white", "纯白", listOf("#FFFFFFFF")),
    LabBackgroundChoice("cloud", "云灰", listOf("#FFF2F2F7")),
    LabBackgroundChoice("silver", "银灰", listOf("#FFE5E7EB")),
    LabBackgroundChoice("ivory", "米白", listOf("#FFFAF6ED")),
    LabBackgroundChoice("blue", "淡蓝", listOf("#FFE8F0FE")),
    LabBackgroundChoice("mint", "薄荷", listOf("#FFE5F5ED")),
    LabBackgroundChoice("violet", "淡紫", listOf("#FFF0EAFB")),
    LabBackgroundChoice("rose", "淡粉", listOf("#FFFBEAF1")),
    LabBackgroundChoice("sand", "暖沙", listOf("#FFF1E6D8")),
    LabBackgroundChoice("navy", "深蓝", listOf("#FF101827")),
    LabBackgroundChoice("charcoal", "深灰", listOf("#FF1C1C1E")),
    LabBackgroundChoice("black", "纯黑", listOf("#FF000000")),
)

internal val LabGradientBackgrounds = listOf(
    LabBackgroundChoice("sky", "晴空", listOf("#FFEAF3FF", "#FF8FBDF8")),
    LabBackgroundChoice("sea", "海盐", listOf("#FFD8F4F3", "#FF94BDF0")),
    LabBackgroundChoice("lavender", "薰衣草", listOf("#FFE7DEFF", "#FFF3CFE1")),
    LabBackgroundChoice("peach", "蜜桃", listOf("#FFFFE1D3", "#FFF9AFC4")),
    LabBackgroundChoice("lemon", "柠檬", listOf("#FFFFF4B8", "#FFD8F1CE")),
    LabBackgroundChoice("mint", "青柠", listOf("#FFCFF3E2", "#FF93D9CD")),
    LabBackgroundChoice("sand", "日光", listOf("#FFFAEFDE", "#FFE3BC93")),
    LabBackgroundChoice("aurora", "极光", listOf("#FFADC7FF", "#FFC6ACEE", "#FFF6C1DE")),
    LabBackgroundChoice("dusk", "暮色", listOf("#FF322B57", "#FF885C92", "#FFDD927B")),
    LabBackgroundChoice("midnight", "午夜", listOf("#FF101525", "#FF294679")),
)

/** Only changes the backdrop under the preview controls, never a button's surface color. */
@Composable
internal fun LabBackgroundDialog(lab: LabState, onDismiss: () -> Unit, onPalette: () -> Unit,
    referenceAvailable: Boolean, importingPhoto: Boolean, onPhoto: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("按钮预览背景") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("切换按钮下面的底色，观察透明度、阴影和高光。", fontSize = 12.sp)
            Text("图片背景", fontSize = 14.sp)
            TextButton(onClick = {
                lab.update(lab.document.copy(previewBackgroundImage = "reference",
                    previewBackgroundImageData = "", previewBackgroundLabel = "参考图片"))
            }, enabled = referenceAvailable, modifier = Modifier.fillMaxWidth().testTag("background-image-reference")) {
                Text(if (referenceAvailable) "使用这张参考图片" else "参考图片（当前安装包未包含）")
            }
            TextButton(onClick = onPhoto, enabled = !importingPhoto,
                modifier = Modifier.fillMaxWidth().testTag("background-image-photo")) {
                Text(if (importingPhoto) "正在准备图片" else "从相册选择背景")
            }
            Text("相册图片只用于按钮预览，随参数文件一起保存与分享。", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("常用纯色", fontSize = 14.sp)
            BackgroundGrid(LabSolidBackgrounds, "solid") { choice ->
                lab.update(lab.document.copy(previewBackgroundColors = choice.colors,
                    previewBackgroundLabel = choice.label, backgroundPreset = "solid",
                    previewBackgroundImage = "none", previewBackgroundImageData = ""))
            }
            Text("渐变预设", fontSize = 14.sp)
            BackgroundGrid(LabGradientBackgrounds, "gradient") { choice ->
                lab.update(lab.document.copy(previewBackgroundColors = choice.colors,
                    previewBackgroundLabel = choice.label, backgroundPreset = "gradient",
                    previewBackgroundImage = "none", previewBackgroundImageData = ""))
            }
            TextButton(onClick = onPalette, modifier = Modifier.fillMaxWidth()) { Text("打开调色盘") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}

@Composable
private fun BackgroundGrid(choices: List<LabBackgroundChoice>, kind: String, onSelect: (LabBackgroundChoice) -> Unit) {
    choices.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { choice ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .clickable { onSelect(choice) }.padding(3.dp).testTag("background-$kind-${choice.id}")) {
                    Box(Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(9.dp))
                        .background(Brush.horizontalGradient(choice.colors.let {
                            val colors = it.map { code -> Color(android.graphics.Color.parseColor(code)) }
                            if (colors.size == 1) colors + colors else colors
                        })))
                    Text(choice.label, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
                }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** Standard HSV color picker. Alpha is retained and has its own precise RGBA slider. */
@Composable
internal fun LabColorSpectrum(color: Color, onBegin: () -> Unit, onEnd: () -> Unit, onColor: (String) -> Unit) {
    val hsv = remember(color) { FloatArray(3).also { android.graphics.Color.colorToHSV(color.toArgb(), it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    LaunchedEffect(color) { if (hsv[1] > .0001f && hsv[2] > .0001f) hue = hsv[0] }
    val currentColor by rememberUpdatedState(color)
    val currentHsv by rememberUpdatedState(hsv)
    val currentHue by rememberUpdatedState(hue)
    val currentBegin by rememberUpdatedState(onBegin)
    val currentEnd by rememberUpdatedState(onEnd)
    val currentApply by rememberUpdatedState(onColor)
    fun emit(h: Float, s: Float, v: Float) {
        val argb = android.graphics.Color.HSVToColor((currentColor.alpha * 255).toInt().coerceIn(0, 255),
            floatArrayOf(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)))
        currentApply(String.format(Locale.ROOT, "#%08X", argb))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        Box(Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(10.dp))
            .testTag("color-spectrum").semantics { contentDescription = "饱和度与明度调色盘" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    currentBegin()
                    fun change(position: Offset) = emit(currentHue,
                        position.x / size.width.coerceAtLeast(1), 1f - position.y / size.height.coerceAtLeast(1))
                    try {
                        change(down.position); down.consume()
                        do {
                            val event = awaitPointerEvent()
                            val point = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (point.pressed) change(point.position)
                            point.consume()
                        } while (event.changes.any { it.pressed })
                    } finally { currentEnd() }
                }
            }.drawWithCache {
                val hueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
                val saturation = Brush.horizontalGradient(listOf(Color.White, Color.Transparent))
                val value = Brush.verticalGradient(listOf(Color.Transparent, Color.Black))
                val thumb = Offset(hsv[1] * size.width, (1f - hsv[2]) * size.height)
                onDrawBehind {
                    drawRect(hueColor); drawRect(saturation); drawRect(value)
                    drawCircle(Color.Black, 6.dp.toPx(), thumb, style = Stroke(3.dp.toPx()))
                    drawCircle(Color.White, 6.dp.toPx(), thumb, style = Stroke(1.5.dp.toPx()))
                }
            })
        Box(Modifier.fillMaxWidth().height(25.dp).clip(RoundedCornerShape(9.dp))
            .semantics { contentDescription = "色相调色盘" }.testTag("color-hue")
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    currentBegin()
                    fun change(position: Offset) {
                        hue = (position.x / size.width.coerceAtLeast(1) * 360f).coerceIn(0f, 360f)
                        emit(hue, currentHsv[1], currentHsv[2])
                    }
                    try {
                        change(down.position); down.consume()
                        do {
                            val event = awaitPointerEvent()
                            val point = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (point.pressed) change(point.position)
                            point.consume()
                        } while (event.changes.any { it.pressed })
                    } finally { currentEnd() }
                }
            }.drawWithCache {
                val rainbow = Brush.horizontalGradient(listOf(Color.Red, Color.Yellow, Color.Green,
                    Color.Cyan, Color.Blue, Color.Magenta, Color.Red))
                val x = hue / 360f * size.width
                onDrawBehind {
                    drawRect(rainbow)
                    drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3.dp.toPx())
                }
            })
    }
}
