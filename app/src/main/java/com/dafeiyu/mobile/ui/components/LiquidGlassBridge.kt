package com.dafeiyu.mobile.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

internal data class LiquidBridgeGeometry(
    val left: Float, val right: Float, val centerY: Float,
    val endHalfHeight: Float, val waistHalfHeight: Float, val strength: Float,
)

internal fun liquidBridgeTarget(strength: Float): Float =
    if (strength.isFinite()) strength.coerceIn(0f, 1f) else 0f

internal fun liquidBridgeMotion(strength: Float, reducedMotion: Boolean) =
    GlassMotion.scalar(if (strength > 0f) GlassMotion.PressMillis else GlassMotion.ReleaseMillis, reducedMotion)

internal fun liquidBridgeGeometry(width: Float, height: Float, gap: Float, strength: Float,
    reducedMotion: Boolean): LiquidBridgeGeometry? {
    if (!width.isFinite() || !height.isFinite() || !gap.isFinite() || !strength.isFinite() ||
        width <= 0f || height <= 0f || gap <= 0f || strength <= 0f) return null
    val active = if (reducedMotion) 1f else strength.coerceIn(0f, 1f)
    val span = min(width, gap)
    val end = height * (.25f + .19f * active) * active
    val waist = end * (.18f + .30f * active)
    return LiquidBridgeGeometry((width - span) / 2f, (width + span) / 2f, height / 2f, end, waist, active)
}

/**
 * Legacy fill-only connector retained for old callers. New interactions use
 * LiquidGlassFusionSurface with both complete measured shapes, so shoulders and light share a field.
 */
@Composable
fun LiquidGlassBridge(
    modifier: Modifier,
    gap: Dp,
    strength: Float,
    tint: Color,
) {
    val reducedMotion = LocalReduceGlassMotion.current
    val reducedTransparency = LocalReduceGlassTransparency.current
    val target = liquidBridgeTarget(strength)
    val animatedStrength by animateFloatAsState(
        targetValue = target,
        animationSpec = liquidBridgeMotion(target, reducedMotion),
        label = "liquidGlassBridge",
    )
    // Accessibility changes take effect immediately, even in the middle of an animation.
    val visibleStrength = if (reducedMotion) target else animatedStrength
    val safeGap = if (gap.value.isFinite()) gap.coerceAtLeast(0.dp) else 0.dp
    val upper = remember { Path() }
    val lower = remember { Path() }
    val body = remember { Path() }
    val bodyBrush = remember(tint) { Brush.verticalGradient(listOf(
        tint.copy(alpha = tint.alpha.coerceAtLeast(.22f)),
        tint.copy(alpha = tint.alpha.coerceAtLeast(.10f)),
    )) }
    val edgeBrush = remember { Brush.linearGradient(listOf(
        Color.White.copy(alpha = .80f), Color.White.copy(alpha = .32f),
        Color.White.copy(alpha = .65f),
    )) }
    Canvas(modifier.width(safeGap)) {
        val geometry = liquidBridgeGeometry(size.width, size.height, safeGap.toPx(), visibleStrength, reducedMotion)
            ?: return@Canvas
        val (left, right, cy, end, waist, active) = geometry
        val mid = (left + right) / 2f
        val shoulder = (right - left) * .22f
        upper.apply {
            reset()
            moveTo(left, cy - end)
            cubicTo(left + shoulder, cy - waist, mid - shoulder, cy - waist, mid, cy - waist)
            cubicTo(mid + shoulder, cy - waist, right - shoulder, cy - waist, right, cy - end)
        }
        lower.apply {
            reset()
            moveTo(right, cy + end)
            cubicTo(right - shoulder, cy + waist, mid + shoulder, cy + waist, mid, cy + waist)
            cubicTo(mid - shoulder, cy + waist, left + shoulder, cy + waist, left, cy + end)
        }
        body.apply {
            reset()
            addPath(upper)
            lineTo(right, cy + end)
            cubicTo(right - shoulder, cy + waist, mid + shoulder, cy + waist, mid, cy + waist)
            cubicTo(mid - shoulder, cy + waist, left + shoulder, cy + waist, left, cy + end)
            close()
        }
        if (reducedTransparency) {
            drawPath(body, tint.copy(alpha = 1f))
        } else {
            drawPath(body, bodyBrush, alpha = active)
        }
        val stroke = min(.9f.dp.toPx(), min(end - waist, (right - left) * .1f))
        val rim = Stroke(stroke)
        drawPath(upper, edgeBrush, alpha = active, style = rim)
        drawPath(lower, edgeBrush, alpha = active, style = rim)
    }
}
