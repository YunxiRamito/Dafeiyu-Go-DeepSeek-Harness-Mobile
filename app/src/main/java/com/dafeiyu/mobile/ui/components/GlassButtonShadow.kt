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
) {
    val extent: Float get() = spread + blurSize
}

internal fun glassButtonShadowGeometry(shortEdge: Float, density: Float): GlassButtonShadowGeometry {
    val safeDensity = if (density.isFinite()) density.coerceIn(.1f, 16f) else 1f
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

private data class GlassShadowBand(val path: Path, val alpha: Float, val stroke: Stroke? = null)

/**
 * Finite cached contour bands approximate the shared shader's continuous soft shadow on every API.
 * Drawing can overflow the layout, but adds neither padding nor a pointer/semantic target.
 */
internal fun Modifier.glassButtonShadow(shape: Shape): Modifier = drawWithCache {
    val geometry = glassButtonShadowGeometry(min(size.width, size.height), density)
    val outline = shape.createOutline(size, layoutDirection, this)
    val bands = ArrayList<GlassShadowBand>(33)
    var accumulated = 0f
    for (index in 0..32) {
        val expansion = geometry.spread + geometry.blurSize * (1f - index / 32f)
        val target = glassButtonShadowOpacity(expansion, geometry)
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
                    left = round.left - expansion,
                    top = round.top - expansion + geometry.offsetY,
                    right = round.right + expansion,
                    bottom = round.bottom + expansion + geometry.offsetY,
                    topLeftCornerRadius = expanded(round.topLeftCornerRadius),
                    topRightCornerRadius = expanded(round.topRightCornerRadius),
                    bottomRightCornerRadius = expanded(round.bottomRightCornerRadius),
                    bottomLeftCornerRadius = expanded(round.bottomLeftCornerRadius),
                ))
            }
            is Outline.Rectangle -> path.addRect(androidx.compose.ui.geometry.Rect(
                outline.rect.left - expansion, outline.rect.top - expansion + geometry.offsetY,
                outline.rect.right + expansion, outline.rect.bottom + expansion + geometry.offsetY,
            ))
            is Outline.Generic -> {
                path.addPath(outline.path, Offset(0f, geometry.offsetY))
                stroke = Stroke(expansion * 2f)
            }
        }
        bands.add(GlassShadowBand(path, alpha, stroke))
    }
    onDrawBehind {
        for (band in bands) {
            val stroke = band.stroke
            if (stroke == null) drawPath(band.path, Color.Black, alpha = band.alpha)
            else drawPath(band.path, Color.Black, alpha = band.alpha, style = stroke)
        }
    }
}
