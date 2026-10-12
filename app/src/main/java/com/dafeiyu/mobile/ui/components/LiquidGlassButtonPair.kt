/*
 * Two-shape lens adapted from QWEA0/Liquid-Glass-Android, commit
 * 16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d.
 * Copyright (c) 2025-2026 pandadog. MIT; see third_party/liquid-glass/LICENSE.
 */
package com.dafeiyu.mobile.ui.components

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Explicit contact holds the bridge; only scale/translation differences count as movement. */
internal data class GlassPairMotionFrame(
    val firstScale: Offset,
    val firstTranslation: Offset,
    val secondScale: Offset,
    val secondTranslation: Offset,
    val firstPressed: Boolean = false,
    val secondPressed: Boolean = false,
)

internal fun glassPairMotionStrength(
    previous: GlassPairMotionFrame,
    current: GlassPairMotionFrame,
    elapsedMillis: Long,
    density: Float,
): Float {
    val safeDensity = if (density.isFinite()) density.coerceIn(.1f, 16f) else 1f
    fun displacement(beforeScale: Offset, beforeTranslation: Offset,
        afterScale: Offset, afterTranslation: Offset): Float {
        val radius = 24f * safeDensity
        return (afterTranslation - beforeTranslation).getDistance() + radius *
            maxOf(abs(afterScale.x - beforeScale.x), abs(afterScale.y - beforeScale.y))
    }
    val stepDp = maxOf(displacement(previous.firstScale, previous.firstTranslation,
        current.firstScale, current.firstTranslation), displacement(previous.secondScale,
        previous.secondTranslation, current.secondScale, current.secondTranslation)) / safeDensity
    if (!stepDp.isFinite() || stepDp < .003f) return 0f
    // Cap an idle interval so the first real movement after a long hold is detected immediately.
    val speedDp = stepDp * 1000f / elapsedMillis.coerceIn(8L, 64L)
    val amount = ((speedDp - .40f) / 3.60f).coerceIn(0f, 1f)
    return amount * amount * (3f - 2f * amount)
}

/** Proximity is applied separately by glassPairBlendWidth; optical pressure cannot prolong a tail. */
internal fun glassPairConnectionStrength(
    previous: GlassPairMotionFrame?,
    current: GlassPairMotionFrame,
    elapsedMillis: Long,
    density: Float,
): Float = if (current.firstPressed || current.secondPressed) 1f else previous?.let {
    glassPairMotionStrength(it, current, elapsedMillis, density)
} ?: 0f

/**
 * One optical surface behind the two existing launchers. The expanded drawing has no input or
 * semantics; only the original 48dp circles handle gestures. Unsupported paths retain their material.
 */
@Composable
internal fun LiquidGlassButtonPair(
    first: GlassButtonDeformationState,
    second: GlassButtonDeformationState,
    firstAlpha: () -> Float,
    secondAlpha: () -> Float,
    content: @Composable BoxScope.(drawLauncherMaterial: Boolean) -> Unit,
) {
    val capture = LocalPageGlassBackdrop.current
    val reducedTransparency = LocalReduceGlassTransparency.current
    val reducedMotion = LocalReduceGlassMotion.current
    val springEnabled = LocalGlassButtonSpring.current
    val density = LocalDensity.current.density
    val rightToLeft = LocalLayoutDirection.current == LayoutDirection.Rtl
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < .5f
    val profile = GlassMaterialProfile.Button
    val tint = profile.materialTint(MaterialTheme.colorScheme.surface.copy(alpha = .18f), darkTheme)
    val supported = Build.VERSION.SDK_INT >= 33 && capture != null && !reducedTransparency && !reducedMotion
    val lens = remember(supported) {
        if (supported) runCatching { GlassPairLensShader() }.onFailure {
            Log.w("GlassPair", "Shared lens unavailable; retaining individual button materials", it)
        }.getOrNull() else null
    }
    val copy = rememberGraphicsLayer()
    val blur = remember(supported, density) {
        if (supported) runCatching {
            RenderEffect.createBlurEffect(profile.blurDp * density, profile.blurDp * density, Shader.TileMode.CLAMP)
        }.onFailure { Log.w("GlassPair", "Shared blur unavailable; retaining individual materials", it) }
            .getOrNull() else null
    }
    val cache = remember(lens, blur) { GlassPairEffectCache() }
    var ready by remember(lens, capture) { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val currentFirstAlpha by rememberUpdatedState(firstAlpha)
    val currentSecondAlpha by rememberUpdatedState(secondAlpha)
    val connection = remember(first, second) { Animatable(0f) }
    LaunchedEffect(first, second, supported, springEnabled, density) {
        connection.snapTo(0f)
        if (!supported || !springEnabled) return@LaunchedEffect
        var previous: GlassPairMotionFrame? = null
        var lastChange = SystemClock.uptimeMillis()
        snapshotFlow {
            val a = first.visualFrame
            val b = second.visualFrame
            GlassPairMotionFrame(Offset(a.scaleX, a.scaleY), a.translation,
                Offset(b.scaleX, b.scaleY), b.translation, first.pressed, second.pressed)
        }.collectLatest { geometry ->
            val now = SystemClock.uptimeMillis()
            val strength = glassPairConnectionStrength(previous, geometry, now - lastChange, density)
            previous = geometry
            lastChange = now
            if (strength > 0f) {
                connection.animateTo(maxOf(connection.value, strength), GlassMotion.scalar(40))
                // A stable hold needs no frame loop or timer. Release emits a new snapshot.
                if (geometry.firstPressed || geometry.secondPressed) return@collectLatest
                delay(50L)
            }
            // A finite tail includes release/rebound frames; no perpetual idle frame loop.
            connection.animateTo(0f, GlassMotion.scalar(GlassMotion.PressMillis))
        }
    }
    val ownsMaterial = supported && lens != null && ready
    Box(Modifier.width(104.dp).height(48.dp)) {
        if (supported && lens != null && blur != null) {
            Box(Modifier.align(Alignment.Center).requiredSize(136.dp, 80.dp)
                .onGloballyPositioned { origin = it.localToRoot(Offset.Zero) }
                .drawWithContent {
                    capture.revision
                    val width = ceil(size.width).toInt().coerceAtLeast(1)
                    val height = ceil(size.height).toInt().coerceAtLeast(1)
                    val margin = maxOf(glassSampleMargin(density, profile.blurDp), glassButtonShadowMargin(density))
                    val sourceLeft = margin + capture.origin.x - origin.x
                    val sourceTop = margin + capture.origin.y - origin.y
                    val bounds = glassSampleBounds(width, height, margin, sourceLeft, sourceTop,
                        capture.layer.size.width, capture.layer.size.height)
                    if (bounds == null || cache.failed || !drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
                        if (ready) ready = false
                        return@drawWithContent
                    }
                    val firstVisual = first.visualFrame
                    val secondVisual = second.visualFrame
                    val firstCenter = Offset((if (rightToLeft) 96f else 40f) * density, 40f * density)
                    val secondCenter = Offset((if (rightToLeft) 40f else 96f) * density, 40f * density)
                    fun shape(center: Offset, state: GlassButtonDeformationState,
                        visual: GlassButtonVisualFrame, alpha: Float): GlassPairShape {
                        val transformedCenter = center + visual.translation
                        val localTouch = glassGlowCenter(state.touchPosition, 48f * density, 48f * density) -
                            Offset(24f * density, 24f * density)
                        return GlassPairShape(
                            center = transformedCenter,
                            radius = 24f * density,
                            scaleX = visual.scaleX, scaleY = visual.scaleY,
                            press = visual.opticalPress,
                            touch = transformedCenter + Offset(localTouch.x * visual.scaleX, localTouch.y * visual.scaleY),
                            alpha = alpha.coerceIn(0f, 1f),
                        )
                    }
                    val firstShape = shape(firstCenter, first, firstVisual, currentFirstAlpha())
                    val secondShape = shape(secondCenter, second, secondVisual, currentSecondAlpha())
                    val frame = GlassPairFrame(firstShape, secondShape,
                        glassPairBlendWidth(firstShape, secondShape, connection.value))
                    val key = GlassPairEffectKey(width, height, margin, tint.toArgb(), density,
                        darkTheme, frame, bounds)
                    if (cache.key != key) {
                        val effect = runCatching {
                            RenderEffect.createChainEffect(
                                lens.effect(width, height, margin, tint.toArgb(), density, darkTheme, frame, bounds),
                                blur,
                            ).asComposeRenderEffect()
                        }.getOrElse {
                            cache.failed = true
                            Log.w("GlassPair", "Shared lens effect failed; restoring individual button materials", it)
                            if (ready) ready = false
                            return@drawWithContent
                        }
                        cache.key = key
                        cache.effect = effect
                    }
                    // Hand ownership to the shared material on the next composition. Do not draw
                    // it on top of still-visible individual material on this first readiness frame.
                    if (!ready) {
                        ready = true
                        return@drawWithContent
                    }
                    if (!ownsMaterial) return@drawWithContent
                    copy.record(size = IntSize(width + margin * 2, height + margin * 2)) {
                        translate(sourceLeft, sourceTop) { drawLayer(capture.layer) }
                    }
                    copy.renderEffect = cache.effect
                    translate(-margin.toFloat(), -margin.toFloat()) { drawLayer(copy) }
                    if (!cache.reportedActive) {
                        cache.reportedActive = true
                        Log.i("GlassPair", "Shared optical surface active: ${width}x${height}, margin=$margin")
                    }
                })
        }
        content(!ownsMaterial)
    }
}

private data class GlassPairEffectKey(
    val width: Int,
    val height: Int,
    val margin: Int,
    val tint: Int,
    val density: Float,
    val darkTheme: Boolean,
    val frame: GlassPairFrame,
    val sampleBounds: Rect,
)

private class GlassPairEffectCache {
    var key: GlassPairEffectKey? = null
    var effect: androidx.compose.ui.graphics.RenderEffect? = null
    var failed = false
    var reportedActive = false
}
