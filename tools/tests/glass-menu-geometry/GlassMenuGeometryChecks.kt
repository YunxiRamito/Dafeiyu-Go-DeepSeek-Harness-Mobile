package com.dafeiyu.mobile.ui

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.dafeiyu.mobile.ui.components.GlassMotion

fun main() {
    val spacing = 12
    fun geometry(window: IntSize, anchor: IntRect, requested: IntSize, insets: IntRect,
        placement: GlassMenuPlacement): GlassMenuGeometry {
        val safe = glassMenuSafeBounds(window, insets, spacing)
        val popup = IntSize(minOf(requested.width, safe.width), minOf(requested.height, safe.height))
        val actual = glassMenuGeometry(anchor, popup, safe, spacing, placement)
        val target = actual.target
        check(actual.origin.x >= safe.left && actual.origin.y >= safe.top)
        check(actual.origin.x + popup.width <= safe.right)
        check(actual.origin.y + popup.height <= safe.bottom)
        check(target.left >= 0 && target.top >= 0 && target.width >= 0 && target.height >= 0)
        check(target.right <= popup.width && target.bottom <= popup.height)
        check(actual.origin.y + target.bottom <= safe.bottom)
        if (placement == GlassMenuPlacement.BelowEnd && target.height > 0) {
            check(actual.origin.y + target.top >= anchor.bottom + spacing)
        }
        if (placement == GlassMenuPlacement.AboveCenter && target.height > 0) {
            check(actual.origin.y + target.bottom <= anchor.top - spacing)
        }
        return actual
    }
    val noInsets = IntRect.Zero
    val regularBelow = geometry(IntSize(400, 900), IntRect(336, 40, 384, 88),
        IntSize(336, 508), noInsets, GlassMenuPlacement.BelowEnd)
    check(regularBelow.origin.y == 40 && regularBelow.target == IntRect(0, 60, 336, 508))
    println("PASS normal BelowEnd retains anchor, gap and requested geometry")

    val shortBelow = geometry(IntSize(400, 360), IntRect(336, 40, 384, 88),
        IntSize(336, 508), IntRect(0, 24, 0, 24), GlassMenuPlacement.BelowEnd)
    check(shortBelow.origin.y == 36 && shortBelow.target == IntRect(0, 64, 336, 288))
    println("PASS short BelowEnd subtracts final origin offset and respects system bars")

    val above = geometry(IntSize(400, 900), IntRect(160, 780, 240, 828),
        IntSize(320, 536), noInsets, GlassMenuPlacement.AboveCenter)
    check(above.origin.y == 292 && above.target == IntRect(0, 0, 320, 476))
    val clampedAbove = geometry(IntSize(400, 360), IntRect(160, 252, 240, 300),
        IntSize(320, 536), IntRect(0, 24, 0, 24), GlassMenuPlacement.AboveCenter)
    check(clampedAbove.origin.y == 36 && clampedAbove.target.bottom == 204)
    println("PASS AboveCenter normal dimensions and clamped top preserve the anchor gap")

    geometry(IntSize(400, 900), IntRect(336, 40, 384, 88), IntSize(336, 508),
        IntRect(0, 24, 0, 500), GlassMenuPlacement.BelowEnd)
    geometry(IntSize(900, 320), IntRect(792, 40, 840, 88), IntSize(336, 508),
        IntRect(44, 24, 44, 24), GlassMenuPlacement.BelowEnd)
    geometry(IntSize(96, 120), IntRect(36, 12, 84, 60), IntSize(336, 508),
        IntRect(0, 0, 0, 0), GlassMenuPlacement.BelowEnd)
    println("PASS IME, landscape cutouts and tiny split window have no forced minimum overflow")

    var cases = 0
    for (width in listOf(96, 320, 400, 900)) for (height in listOf(120, 280, 360, 900)) {
        for (insets in listOf(noInsets, IntRect(8, 24, 8, 24), IntRect(0, 24, 0, height / 2))) {
            val window = IntSize(width, height)
            val safe = glassMenuSafeBounds(window, insets, spacing)
            for (anchorY in listOf(safe.top, (safe.top + safe.bottom) / 2, safe.bottom - 48)) {
                val anchor = IntRect((safe.left + safe.right) / 2 - 24, anchorY,
                    (safe.left + safe.right) / 2 + 24, anchorY + 48)
                for (placement in GlassMenuPlacement.entries) {
                    geometry(window, anchor, IntSize(336, 508), insets, placement)
                    cases++
                }
            }
        }
    }
    val collapsedSafe = glassMenuSafeBounds(IntSize(24, 24), IntRect(24, 24, 24, 24), spacing)
    check(collapsedSafe.width == 0 && collapsedSafe.height == 0)
    check(glassMenuGeometry(IntRect.Zero, IntSize.Zero, collapsedSafe, spacing,
        GlassMenuPlacement.BelowEnd).target.height == 0)
    check(GlassMotion.scalar(GlassMotion.MorphMillis).durationMillis == 380)
    check(GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion = true).durationMillis == 0)
    println("PASS $cases bounded placement cases; exhausted safe area; shared 380ms/reduced 0ms")
}
