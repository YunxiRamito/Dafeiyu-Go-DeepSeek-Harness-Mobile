package com.dafeiyu.mobile.ui.components

import android.os.Build
import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil

/** Only used by controls outside the recorded page: cannot sample the control itself. */
val LocalPageGlassBackdrop = staticCompositionLocalOf<PageBackdropCapture?> { null }

@Composable
internal fun LivePageGlassMaterial(
    capture: PageBackdropCapture,
    modifier: Modifier,
    radius: Float,
    tint: Color,
    interaction: Float,
    touch: Offset?,
    reducedMotion: Boolean,
    profile: GlassMaterialProfile = GlassMaterialProfile.Button,
    tuning: GlassDebugTuning? = null,
) {
    val copy = rememberGraphicsLayer()
    val density = LocalDensity.current.density
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < .5f
    val opaque = profile.opaqueMaterial(tint, MaterialTheme.colorScheme.surface)
    val buttonStyle = profile.buttonStyle
    val debug = remember(tuning) { tuning?.sanitized() }
    val blurDp = debug?.blurDp ?: profile.blurDp
    val dimAmount = debug?.dimAmount ?: profile.dimAmount
    // Compile once per control, never per size/animation frame.
    val lens = remember(profile.useRefraction) { if (Build.VERSION.SDK_INT >= 33 && profile.useRefraction) runCatching { LensShader() }.getOrNull() else null }
    val blur = remember(density, blurDp) { if (Build.VERSION.SDK_INT >= 31) {
        if (blurDp > 0f) RenderEffect.createBlurEffect(blurDp * density, blurDp * density, Shader.TileMode.CLAMP)
        else RenderEffect.createOffsetEffect(0f, 0f)
    } else null }
    val fallbackFilter = remember(debug?.saturation) {
        if (debug != null && debug.saturation != 1f) {
            ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(debug.saturation) })
        } else null
    }
    val effects = remember(lens, blur) { LiveGlassEffectCache() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val edge = remember(profile, darkTheme, debug) {
        if (debug == null) profile.edgeAppearance(darkTheme) else GlassEdgeAppearance(
            (debug.rimStrength + debug.reflectionStrength * .55f).coerceIn(0f, 1f), debug.rimStrength,
            (debug.rimStrength + debug.reflectionStrength * .35f).coerceIn(0f, 1f), .86f,
        )
    }
    androidx.compose.foundation.layout.Box(modifier.onGloballyPositioned { origin = it.localToRoot(Offset.Zero) }
        .drawWithCache {
            // Size/parameter cached brushes; none are created in an animation frame draw callback.
            val direction = glassDebugLightDirection(debug)
            val start = Offset(size.width * (.5f + direction.x * .5f), size.height * (.5f + direction.y * .5f))
            val end = Offset(size.width - start.x, size.height - start.y)
            val highlight = debug?.highlightColor ?: Color.White
            val front = (.055f * (debug?.highlightStrength ?: 1f) * highlight.alpha).coerceIn(0f, 1f)
            val highlightColors = listOf(highlight.copy(alpha = front), highlight.copy(alpha = front * .35f), highlight.copy(alpha = 0f))
            val edgeColors = listOf(edge.front, edge.middle, edge.back).map { highlight.copy(alpha = it * highlight.alpha) }
            val highlightBrushCache = if (debug == null) Brush.linearGradient(highlightColors)
                else Brush.linearGradient(highlightColors, start, end)
            val rimBrush = if (debug == null) Brush.linearGradient(edgeColors)
                else Brush.linearGradient(edgeColors, start, end)
            val borderColor = debug?.let { it.borderColor.copy(alpha = it.borderColor.alpha * it.borderOpacity) } ?: Color.Transparent
            val borderStroke = androidx.compose.ui.graphics.drawscope.Stroke((debug?.borderWidthDp ?: 0f).dp.toPx())
            val mid = Offset(size.width * .5f, size.height * .5f)
            val trackingRadius = debug?.trackingLightWidthDp?.dp?.toPx()
                ?: maxOf(size.width, size.height).coerceAtLeast(1f) * .65f
            val trackingColor = debug?.trackingLightColor ?: Color.White
            val trackingAlpha = (.18f * (debug?.highlightStrength ?: 1f) * (debug?.trackingLightIntensity ?: 1f) *
                trackingColor.alpha).coerceIn(0f, 1f)
            val glowBrush = Brush.radialGradient(listOf(trackingColor.copy(alpha = trackingAlpha), Color.Transparent),
                mid, trackingRadius.coerceAtLeast(.001f))
            val beamBrush = Brush.linearGradient(listOf(Color.Transparent, trackingColor.copy(alpha = trackingAlpha), Color.Transparent),
                mid - direction * trackingRadius, mid + direction * trackingRadius)
            onDrawWithContent {
                capture.revision
                var opticalActive = false
                val margin = glassSampleMargin(density, blurDp)
                val width = ceil(size.width).toInt().coerceAtLeast(1)
                val height = ceil(size.height).toInt().coerceAtLeast(1)
                val sourceLeft = margin + capture.origin.x - origin.x
                val sourceTop = margin + capture.origin.y - origin.y
                val sampleBounds = glassSampleBounds(width, height, margin, sourceLeft, sourceTop,
                    capture.layer.size.width, capture.layer.size.height)
                if (sampleBounds != null) {
                    copy.record(size = IntSize(width + margin * 2, height + margin * 2)) {
                        translate(sourceLeft, sourceTop) {
                            drawLayer(capture.layer)
                        }
                    }
                    if (Build.VERSION.SDK_INT >= 31 && blur != null) {
                        val key = LiveGlassEffectKey(width, height, margin, radius, tint.toArgb(),
                            reducedMotion, interaction, touch, darkTheme, profile, sampleBounds, debug)
                        if (effects.key != key) {
                            val optical = if (Build.VERSION.SDK_INT >= 33 && !effects.lensFailed) runCatching {
                                lens?.effect(width, height, margin, radius, tint.toArgb(),
                                    density, reducedMotion, interaction, touch?.x ?: -1f, touch?.y ?: -1f,
                                    buttonStyle, darkTheme, profile == GlassMaterialProfile.SidebarPanel,
                                    dimAmount, sampleBounds, debug)
                            }.getOrElse { effects.lensFailed = true; null } else null
                            effects.opticalActive = optical != null
                            effects.effect = runCatching {
                                (if (optical != null) RenderEffect.createChainEffect(optical, blur) else blur)
                                    .asComposeRenderEffect()
                            }.getOrElse {
                                effects.lensFailed = true
                                effects.opticalActive = false
                                blur.asComposeRenderEffect()
                            }
                            effects.key = key
                        }
                        copy.renderEffect = effects.effect
                        opticalActive = effects.opticalActive
                    }
                    copy.colorFilter = if (opticalActive) null else fallbackFilter
                    translate(-margin.toFloat(), -margin.toFloat()) { drawLayer(copy) }
                    if (!opticalActive) {
                        drawRoundRect(tint, cornerRadius = CornerRadius(radius))
                        if (dimAmount > 0f) {
                            drawRoundRect(Color.Black.copy(alpha = dimAmount), cornerRadius = CornerRadius(radius))
                        }
                    }
                    if (profile.useHighlights || (debug != null && !opticalActive)) {
                        drawRoundRect(highlightBrushCache, cornerRadius = CornerRadius(radius))
                    }
                } else {
                    drawRoundRect(opaque, cornerRadius = CornerRadius(radius))
                }
                if (!opticalActive) {
                    val stroke = edge.widthDp.dp.toPx()
                    val inset = stroke / 2f
                    drawRoundRect(rimBrush, topLeft = Offset(inset, inset),
                        size = androidx.compose.ui.geometry.Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f)),
                        cornerRadius = CornerRadius((radius - inset).coerceAtLeast(0f)),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                    if (borderStroke.width > 0f && borderColor.alpha > 0f) {
                        val borderInset = borderStroke.width / 2f
                        drawRoundRect(borderColor, topLeft = Offset(borderInset, borderInset),
                            size = androidx.compose.ui.geometry.Size((size.width - borderStroke.width).coerceAtLeast(0f), (size.height - borderStroke.width).coerceAtLeast(0f)),
                            cornerRadius = CornerRadius((radius - borderInset).coerceAtLeast(0f)), style = borderStroke)
                    }
                    if (!reducedMotion && interaction > 0f) {
                        when (debug?.pressEffect) {
                            "none", "lift" -> Unit
                            "darken" -> drawRoundRect(Color.Black, cornerRadius = CornerRadius(radius),
                                alpha = debug.pressDarken * interaction.coerceIn(0f, 1f))
                            else -> {
                                val center = glassGlowCenter(touch ?: mid, size.width, size.height)
                                translate(center.x - mid.x, center.y - mid.y) {
                                    if (debug?.pressEffect == "beam") drawRect(beamBrush,
                                        topLeft = mid - center, size = size, alpha = interaction.coerceIn(0f, 1f))
                                    else drawRect(glowBrush, topLeft = mid - Offset(trackingRadius, trackingRadius),
                                        size = androidx.compose.ui.geometry.Size(trackingRadius * 2f, trackingRadius * 2f),
                                        alpha = interaction.coerceIn(0f, 1f))
                                }
                            }
                        }
                    }
                }
            }
        })
}

private data class LiveGlassEffectKey(
    val width: Int, val height: Int, val margin: Int, val radius: Float, val tint: Int,
    val reducedMotion: Boolean, val interaction: Float, val touch: Offset?,
    val darkTheme: Boolean, val profile: GlassMaterialProfile, val sampleBounds: Rect,
    val tuning: GlassDebugTuning?,
)

private class LiveGlassEffectCache {
    var key: LiveGlassEffectKey? = null
    var effect: androidx.compose.ui.graphics.RenderEffect? = null
    var opticalActive = false
    var lensFailed = false
}
