package com.dafeiyu.mobile.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import kotlin.math.sqrt

/** Shared timing and radial resistance for every interactive glass control and expanding surface. */
object GlassMotion {
    val EaseOut = CubicBezierEasing(.16f, 1f, .3f, 1f)
    const val PressMillis = 150
    const val ReleaseMillis = 360
    const val MorphMillis = 380
    fun scalar(duration: Int = ReleaseMillis, reducedMotion: Boolean = false): TweenSpec<Float> =
        tween(if (reducedMotion) 0 else duration, easing = EaseOut)

    /** Center movement and bounds expansion start together on one continuous normalized coordinate. */
    fun centerProgress(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        return p + .12f * p * (1f - p)
    }

    fun sizeProgress(progress: Float): Float = progress.coerceIn(0f, 1f)

    /** Circular displacement limit: diagonal movement has the same maximum radius as axial movement. */
    fun radialResistance(delta: Offset, radius: Float): Offset {
        val distance = sqrt(delta.x * delta.x + delta.y * delta.y)
        if (distance < .001f || radius <= 0f) return Offset.Zero
        val resistedDistance = radius * distance / (distance + radius)
        return delta * (resistedDistance / distance)
    }
}

