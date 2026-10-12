package com.dafeiyu.mobile.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import com.dafeiyu.mobile.data.ChatRunMetrics
import com.dafeiyu.mobile.ui.components.FishGlyph
import com.dafeiyu.mobile.ui.components.GlassMaterialProfile
import com.dafeiyu.mobile.ui.components.GlassMotion
import com.dafeiyu.mobile.ui.components.GlassSurface
import com.dafeiyu.mobile.ui.components.LocalReduceGlassMotion
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.roundToLong

/** The dock owns visibility and separation; this capsule only renders measured real values. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatRunInfoBar(
    metrics: ChatRunMetrics?,
    modifier: Modifier = Modifier,
    drawMaterial: Boolean = true,
    revealProgress: () -> Float = { 1f },
    onMeasuredSize: (IntSize) -> Unit = {},
) {
    val shown = metrics ?: return
    val reducedMotion = LocalReduceGlassMotion.current
    val colors = MaterialTheme.colorScheme
    val duration = if (reducedMotion) 0 else GlassMotion.MorphMillis
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    val capsuleRadius = with(LocalDensity.current) {
        if (surfaceSize.height > 0) (minOf(surfaceSize.width, surfaceSize.height) / 2f).toDp() else 24.dp
    }
    val items = remember(shown) { chatRunInfoItems(shown) }
    val description = remember(shown) { chatRunInfoDescription(shown) }
    GlassSurface(
        modifier = modifier.fillMaxWidth()
            .onSizeChanged { onMeasuredSize(it) }
            .animateContentSize(tween(duration, easing = GlassMotion.EaseOut))
            .onSizeChanged { surfaceSize = it }
            .clearAndSetSemantics { contentDescription = description },
        cornerRadius = capsuleRadius,
        tint = colors.surface.copy(alpha = .26f),
        materialProfile = GlassMaterialProfile.ReadablePanel,
        drawMaterial = drawMaterial,
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().heightIn(min = 34.dp)
                .padding(horizontal = 14.dp, vertical = 7.dp)
                .graphicsLayer { alpha = ((revealProgress() - .15f) / .70f).coerceIn(0f, 1f) },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MetricGroup(items[0], colors.onSurface) {
                FishGlyph("stopwatch", Modifier.size(16.dp), colors.onSurface)
            }
            MetricGroup(items[1], colors.onSurface) {
                FishGlyph("archive", Modifier.size(16.dp), colors.onSurface)
            }
            MetricGroup(items[2], colors.onSurface) {
                // The protocol supplies no task total. Never turn rounds/tokens/status into progress.
                Canvas(Modifier.size(16.dp)) {
                    val stroke = 1.75.dp.toPx()
                    drawCircle(colors.onSurface.copy(alpha = .45f),
                        radius = (size.minDimension - stroke) / 2f, style = Stroke(stroke))
                }
            }
        }
    }
}

@Composable
private fun MetricGroup(text: String, color: Color, glyph: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        glyph()
        Text(
            text = text,
            modifier = Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = color,
        )
    }
}

internal fun chatRunInfoItems(metrics: ChatRunMetrics): List<String> = listOf(
    "${metrics.rounds ?: "未知"}轮 · " +
        "${metrics.tokensPerSecond?.roundToLong() ?: "未知"} t/s",
    "${metrics.totalTokens?.let(::compactChatRunTokens) ?: "未知"} tok · " +
        "中${metrics.cacheHitPercent?.let { compactDecimal(it) + "%" } ?: "未知"}",
    "未知",
)

internal fun chatRunInfoDescription(metrics: ChatRunMetrics): String = listOf(
    "模型轮数 ${metrics.rounds ?: "未知"}",
    "全会话平均输出速度 ${metrics.tokensPerSecond?.let { decimal(it) + " token/秒" } ?: "未知"}，包含模型及工具等待",
    "累计用量 ${metrics.totalTokens ?: "未知"} token",
    "输入缓存命中率 ${metrics.cacheHitPercent?.let { decimal(it) + "%" } ?: "未知"}",
    "耗时 ${metrics.elapsedMillis?.let { decimal(it / 1000.0) + " 秒" } ?: "未知"}",
    "任务进度未知，未提供任务总量",
).joinToString("，")

/** Decimal SI units; promote rounded 1000k/1000m and preserve Long arithmetic. */
internal fun compactChatRunTokens(tokens: Long): String {
    require(tokens >= 0)
    if (tokens < 1000) return tokens.toString()
    var unit = ChatRunTokenDivisors.indexOfLast { tokens >= it }
    fun rounded() = BigDecimal.valueOf(tokens)
        .divide(BigDecimal.valueOf(ChatRunTokenDivisors[unit]), 1, RoundingMode.HALF_UP)
    if (unit < ChatRunTokenDivisors.lastIndex && rounded() >= BigDecimal.valueOf(1000)) unit++
    return rounded().stripTrailingZeros().toPlainString() + ChatRunTokenSuffixes[unit]
}

private val ChatRunTokenDivisors = longArrayOf(1000L, 1_000_000L, 1_000_000_000L)
private val ChatRunTokenSuffixes = arrayOf("k", "m", "b")
private fun compactDecimal(value: Double): String = decimal(value).removeSuffix(".0")
private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)
