/*
 * Shared rounded-shape capture/lens adapted from QWEA0/Liquid-Glass-Android, commit
 * 16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d.
 * Copyright (c) 2025-2026 pandadog. MIT; see third_party/liquid-glass/LICENSE.
 */
package com.dafeiyu.mobile.ui.components

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.abs
import kotlin.math.min

/** A complete rounded surface measured in the fusion host's local pixels, before blur margins. */
data class LiquidGlassShape(
    val bounds: Rect,
    val cornerRadius: Float,
    val tint: Color = Color.White.copy(alpha = .12f),
    val alpha: Float = 1f,
    val materialProfile: GlassMaterialProfile? = null,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
)

private fun LiquidGlassShape.valid(): Boolean = bounds.left.isFinite() && bounds.top.isFinite() &&
    bounds.right.isFinite() && bounds.bottom.isFinite() && bounds.width > 0f && bounds.height > 0f &&
    cornerRadius.isFinite() && cornerRadius >= 0f && alpha.isFinite() &&
    scaleX.isFinite() && scaleY.isFinite() && scaleX > 0f && scaleY > 0f

private fun LiquidGlassShape.transformedBounds(): Rect {
    val center = bounds.center
    val halfWidth = bounds.width * scaleX * .5f
    val halfHeight = bounds.height * scaleY * .5f
    return Rect(center.x - halfWidth, center.y - halfHeight, center.x + halfWidth, center.y + halfHeight)
}

private fun LiquidGlassShape.opticalShape(profile: GlassMaterialProfile, darkTheme: Boolean): GlassPairShape {
    val half = Offset(bounds.width * .5f, bounds.height * .5f)
    return GlassPairShape(
        center = bounds.center,
        radius = cornerRadius.coerceIn(0f, min(half.x, half.y)),
        halfSize = half,
        scaleX = scaleX,
        scaleY = scaleY,
        alpha = alpha.coerceIn(0f, 1f),
        tint = (materialProfile ?: profile).materialTint(tint, darkTheme).toArgb(),
    )
}

/**
 * Two complete rounded surfaces share one page sample, union contour, refraction and reflection.
 * The measured geometry determines the neck; connection only limits its availability (0..1).
 *
 * Pass drawOwnMaterial to both foreground GlassSurfaces' drawMaterial parameters. True retains
 * their normal material until measurements/capture are ready, or on unsupported/failed paths.
 * The host installs no gesture detector, hit expansion or semantics in the connecting region.
 * capture must record a separate page sibling, never this host or one of its descendants.
 * opaqueShapeBackdrop instead records the shapes' own solid colours into the same lens. It never
 * reads page pixels, so readable sidebar bodies remain opaque while sharing the complete contour.
 */
@Composable
fun LiquidGlassFusionSurface(
    modifier: Modifier = Modifier,
    first: () -> LiquidGlassShape?,
    second: () -> LiquidGlassShape?,
    profile: GlassMaterialProfile = GlassMaterialProfile.Standard,
    connection: () -> Float = { 1f },
    capture: PageBackdropCapture? = LocalPageGlassBackdrop.current,
    opaqueShapeBackdrop: Boolean = false,
    content: @Composable BoxScope.(drawOwnMaterial: Boolean) -> Unit,
) {
    val reducedTransparency = LocalReduceGlassTransparency.current
    val reducedMotion = LocalReduceGlassMotion.current
    val density = LocalDensity.current.density
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < .5f
    val currentFirst by rememberUpdatedState(first)
    val currentSecond by rememberUpdatedState(second)
    val currentConnection by rememberUpdatedState(connection)
    val pageCapture = capture
    val supported = Build.VERSION.SDK_INT >= 33 && (capture != null || opaqueShapeBackdrop) && profile.useRefraction &&
        !reducedTransparency && !reducedMotion
    val lens = remember(supported) {
        if (supported) runCatching { GlassPairLensShader() }.onFailure {
            Log.w("GlassFusion", "Shared lens unavailable; retaining individual materials", it)
        }.getOrNull() else null
    }
    val blur = remember(supported, profile, density) {
        if (supported) runCatching {
            RenderEffect.createBlurEffect(profile.blurDp * density, profile.blurDp * density, Shader.TileMode.CLAMP)
        }.onFailure { Log.w("GlassFusion", "Blur unavailable; retaining individual materials", it) }
            .getOrNull() else null
    }
    val copy = rememberGraphicsLayer()
    val cache = remember(lens, blur) { GlassFusionEffectCache() }
    var ready by remember(lens, blur, capture, opaqueShapeBackdrop) { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val geometryReady = currentFirst()?.valid() == true && currentSecond()?.valid() == true
    val ownsMaterial = supported && lens != null && blur != null && ready && geometryReady
    Box(modifier.onGloballyPositioned { origin = it.localToRoot(Offset.Zero) }) {
        if (supported && lens != null && blur != null) {
            Box(Modifier.matchParentSize().drawWithContent {
                val page = if (opaqueShapeBackdrop) null else pageCapture ?: return@drawWithContent
                page?.revision
                val a = currentFirst()?.takeIf { it.valid() }
                val b = currentSecond()?.takeIf { it.valid() }
                if (a == null || b == null || cache.failed || !drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
                    if (ready) ready = false
                    return@drawWithContent
                }
                val width = ceil(size.width).toInt().coerceAtLeast(1)
                val height = ceil(size.height).toInt().coerceAtLeast(1)
                val margin = maxOf(glassSampleMargin(density, profile.blurDp), glassButtonShadowMargin(density))
                val sourceLeft = if (page == null) 0f else margin + page.origin.x - origin.x
                val sourceTop = if (page == null) 0f else margin + page.origin.y - origin.y
                val bounds = if (opaqueShapeBackdrop) {
                    Rect(.5f, .5f, width + margin * 2f - .5f, height + margin * 2f - .5f)
                } else glassSampleBounds(width, height, margin, sourceLeft, sourceTop,
                    requireNotNull(page).layer.size.width, page.layer.size.height)
                if (bounds == null) {
                    if (ready) ready = false
                    return@drawWithContent
                }
                val firstShape = a.opticalShape(profile, darkTheme)
                val secondShape = b.opticalShape(profile, darkTheme)
                val frame = GlassPairFrame(firstShape, secondShape,
                    glassPairBlendWidth(firstShape, secondShape, currentConnection()))
                val key = GlassFusionEffectKey(width, height, margin, density, darkTheme, profile, frame, bounds)
                if (opaqueShapeBackdrop) {
                    val sourceKey = GlassFusionSolidKey(a.transformedBounds(), b.transformedBounds(),
                        a.tint.copy(alpha = 1f), b.tint.copy(alpha = 1f), margin)
                    if (cache.solidKey != sourceKey) {
                        cache.solidBrush = opaqueFusionBrush(sourceKey)
                        cache.solidKey = sourceKey
                    }
                }
                if (cache.key != key) {
                    val effect = runCatching {
                        RenderEffect.createChainEffect(lens.effect(width, height, margin,
                            Color.Transparent.toArgb(), density, darkTheme, frame, bounds, profile), blur)
                            .asComposeRenderEffect()
                    }.getOrElse {
                        cache.failed = true
                        Log.w("GlassFusion", "Shared effect failed; restoring individual materials", it)
                        if (ready) ready = false
                        return@drawWithContent
                    }
                    cache.key = key
                    cache.effect = effect
                }
                // A one-frame handshake prevents shared optics from doubling the fallback surfaces.
                if (!ready) { ready = true; return@drawWithContent }
                if (!ownsMaterial) return@drawWithContent
                copy.record(size = IntSize(width + margin * 2, height + margin * 2)) {
                    if (opaqueShapeBackdrop) drawRect(requireNotNull(cache.solidBrush))
                    else translate(sourceLeft, sourceTop) { drawLayer(requireNotNull(page).layer) }
                }
                copy.renderEffect = cache.effect
                translate(-margin.toFloat(), -margin.toFloat()) { drawLayer(copy) }
                if (!cache.reportedActive) {
                    cache.reportedActive = true
                    Log.i("GlassFusion", "Shared rounded optical surface active: ${width}x${height}, margin=$margin")
                }
            })
        }
        content(!ownsMaterial)
    }
}

private data class GlassFusionSolidKey(
    val firstBounds: Rect,
    val secondBounds: Rect,
    val firstColor: Color,
    val secondColor: Color,
    val margin: Int,
)

/** A bounded cached colour source; blur softens the seam between the closest measured faces. */
private fun opaqueFusionBrush(key: GlassFusionSolidKey): Brush {
    val a = key.firstBounds
    val b = key.secondBounds
    val delta = b.center - a.center
    val colors = listOf(key.firstColor, key.secondColor)
    return if (abs(delta.x) >= abs(delta.y)) {
        val sign = if (delta.x >= 0f) 1f else -1f
        val seam = (if (sign > 0f) a.right + b.left else a.left + b.right) * .5f + key.margin
        Brush.linearGradient(colors, Offset(seam - sign, 0f), Offset(seam + sign, 0f))
    } else {
        val sign = if (delta.y >= 0f) 1f else -1f
        val seam = (if (sign > 0f) a.bottom + b.top else a.top + b.bottom) * .5f + key.margin
        Brush.linearGradient(colors, Offset(0f, seam - sign), Offset(0f, seam + sign))
    }
}

private data class GlassFusionEffectKey(
    val width: Int,
    val height: Int,
    val margin: Int,
    val density: Float,
    val darkTheme: Boolean,
    val profile: GlassMaterialProfile,
    val frame: GlassPairFrame,
    val sampleBounds: Rect,
)

private class GlassFusionEffectCache {
    var key: GlassFusionEffectKey? = null
    var effect: androidx.compose.ui.graphics.RenderEffect? = null
    var failed = false
    var reportedActive = false
    var solidKey: GlassFusionSolidKey? = null
    var solidBrush: Brush? = null
}
