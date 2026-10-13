package com.dafeiyu.mobile.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.min

/** Subtle contact shadow proportional to the button's short edge, never raw device pixels. */
internal data class GlassButtonShadowGeometry(
    val offsetY: Float,
    val spread: Float,
    val blurSize: Float,
    val opacity: Float = .08f,
    val offsetX: Float = 0f,
) {
    val extent: Float get() = spread + blurSize
}

internal fun glassButtonShadowGeometry(shortEdge: Float, density: Float, tuning: GlassDebugTuning? = null): GlassButtonShadowGeometry {
    val safeDensity = if (density.isFinite()) density.coerceIn(.1f, 16f) else 1f
    if (tuning != null) {
        val safe = tuning.sanitized()
        return GlassButtonShadowGeometry(
            offsetY = safe.shadowOffsetYDp * safeDensity,
            spread = safe.shadowSpreadDp * safeDensity,
            blurSize = safe.shadowBlurDp * safeDensity,
            opacity = safe.shadowOpacity,
            offsetX = safe.shadowOffsetXDp * safeDensity,
        )
    }
    val diameter = if (shortEdge.isFinite()) shortEdge.coerceIn(1f, 48f * safeDensity) else 48f * safeDensity
    return GlassButtonShadowGeometry(
        offsetY = diameter * .025f,
        spread = 0f,
        blurSize = diameter * .12f,
    )
}

/** Identical shifted-distance falloff is uploaded to the shared SDF shader. */
internal fun glassButtonShadowOpacity(field: Float, geometry: GlassButtonShadowGeometry): Float {
    val tail = (field - geometry.spread).coerceAtLeast(0f)
    val blur = geometry.blurSize.coerceAtLeast(.001f)
    val fade = ((tail - blur * .75f) / (blur * .25f)).coerceIn(0f, 1f)
    val edge = 1f - fade * fade * (3f - 2f * fade)
    val normalized = tail / (blur * .50f)
    return geometry.opacity * exp(-.5f * normalized * normalized) * edge
}

internal fun glassButtonShadowMargin(density: Float): Int {
    val safeDensity = if (density.isFinite()) density.coerceIn(.1f, 16f) else 1f
    val geometry = glassButtonShadowGeometry(48f * safeDensity, safeDensity)
    return ceil(geometry.extent + geometry.offsetY + safeDensity).toInt().coerceAtLeast(1)
}

private data class GlassShadowBand(
    val path: Path,
    val alpha: Float,
    val stroke: Stroke? = null,
    val target: Float = 0f,
    val previous: Float = 0f,
)

/**
 * Finite cached contour bands approximate the shared shader's continuous soft shadow on every API.
 * Drawing can overflow the layout, but adds neither padding nor a pointer/semantic target.
 */
internal fun Modifier.glassButtonShadow(
    shape: Shape,
    tuning: GlassDebugTuning? = null,
    pressProgress: () -> Float = { 0f },
): Modifier = drawWithCache {
    val shadowColor = tuning?.sanitized()?.shadowColor ?: Color.Black
    val baseGeometry = glassButtonShadowGeometry(min(size.width, size.height), density, tuning)
    val geometry = if (tuning == null) baseGeometry else baseGeometry.copy(opacity = baseGeometry.opacity * shadowColor.alpha)
    val color = shadowColor.copy(alpha = 1f)
    val outline = shape.createOutline(size, layoutDirection, this)
    val bands = ArrayList<GlassShadowBand>(33)
    var accumulated = 0f
    for (index in 0..32) {
        val expansion = geometry.spread + geometry.blurSize * (1f - index / 32f)
        val target = glassButtonShadowOpacity(expansion, geometry)
        val previous = accumulated
        val alpha = ((target - accumulated) / (1f - accumulated).coerceAtLeast(.001f)).coerceIn(0f, 1f)
        accumulated = target
        if (alpha <= 0f) continue
        val path = Path()
        var stroke: Stroke? = null
        when (outline) {
            is Outline.Rounded -> {
                val round = outline.roundRect
                fun expanded(corner: CornerRadius) = CornerRadius(corner.x + expansion, corner.y + expansion)
                path.addRoundRect(RoundRect(
                    left = round.left - expansion + geometry.offsetX,
                    top = round.top - expansion + geometry.offsetY,
                    right = round.right + expansion + geometry.offsetX,
                    bottom = round.bottom + expansion + geometry.offsetY,
                    topLeftCornerRadius = expanded(round.topLeftCornerRadius),
                    topRightCornerRadius = expanded(round.topRightCornerRadius),
                    bottomRightCornerRadius = expanded(round.bottomRightCornerRadius),
                    bottomLeftCornerRadius = expanded(round.bottomLeftCornerRadius),
                ))
            }
            is Outline.Rectangle -> path.addRect(androidx.compose.ui.geometry.Rect(
                outline.rect.left - expansion + geometry.offsetX, outline.rect.top - expansion + geometry.offsetY,
                outline.rect.right + expansion + geometry.offsetX, outline.rect.bottom + expansion + geometry.offsetY,
            ))
            is Outline.Generic -> {
                path.addPath(outline.path, Offset(geometry.offsetX, geometry.offsetY))
                stroke = Stroke(expansion * 2f)
            }
        }
        bands.add(GlassShadowBand(path, alpha, stroke, target, previous))
    }
    onDrawBehind {
        val press = pressProgress().let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
        val pressure = 1f + ((tuning?.shadowPressScale ?: 1f) - 1f) * press
        for (band in bands) {
            val stroke = band.stroke
            // Bands overlap. Recompute their compositing alpha from the cumulative target so a
            // pressure change adjusts total opacity, rather than multiplying every overlapping coat.
            val alpha = if (pressure == 1f) band.alpha else {
                val target = (band.target * pressure).coerceIn(0f, 1f)
                val previous = (band.previous * pressure).coerceIn(0f, 1f)
                ((target - previous) / (1f - previous).coerceAtLeast(.001f)).coerceIn(0f, 1f)
            }
            if (stroke == null) drawPath(band.path, color, alpha = alpha)
            else drawPath(band.path, color, alpha = alpha, style = stroke)
        }
    }
}
