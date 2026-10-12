/*
 * Size adaptation from QWEA0/Liquid-Glass-Android, commit
 * 16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d.
 * Copyright (c) 2025-2026 pandadog. MIT; see third_party/liquid-glass/LICENSE.
 */
package com.dafeiyu.mobile.ui.components

import androidx.compose.ui.geometry.Rect
import kotlin.math.ceil
import kotlin.math.min

internal data class GlassLensGeometry(val bevel: Float, val refraction: Float, val hairWidth: Float)

internal fun glassLensGeometry(width: Int, height: Int, density: Float): GlassLensGeometry {
    val shortEdge = min(width, height).toFloat().coerceAtLeast(1f)
    return GlassLensGeometry(
        bevel = min(16f * density, shortEdge * .30f).coerceAtLeast(2f),
        refraction = min(40f * density, .70f * shortEdge * min(1f, shortEdge / (110f * density))),
        hairWidth = min(1.2f * density, shortEdge * .05f).coerceAtLeast(1f),
    )
}

/** Inward refraction stays inside the output clip; only the blur needs expanded real pixels. */
internal fun glassSampleMargin(density: Float, blurDp: Float): Int =
    ceil(blurDp * density * 3f + 1f).toInt().coerceAtLeast(1)

/** Intersection with the real recording, never with invented padding or an unrelated backdrop. */
internal fun glassSampleBounds(
    width: Int, height: Int, margin: Int, sourceLeft: Float, sourceTop: Float,
    sourceWidth: Int, sourceHeight: Int,
): Rect? {
    val insetX = min(1f, width * .25f)
    val insetY = min(1f, height * .25f)
    val left = maxOf(margin + insetX, sourceLeft + .5f)
    val top = maxOf(margin + insetY, sourceTop + .5f)
    val right = minOf(margin + width - insetX, sourceLeft + sourceWidth - .5f)
    val bottom = minOf(margin + height - insetY, sourceTop + sourceHeight - .5f)
    return if (left <= right && top <= bottom) Rect(left, top, right, bottom) else null
}
