package com.dafeiyu.mobile.ui.components

import android.os.Build
import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
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
) {
    val copy = rememberGraphicsLayer()
    val density = LocalDensity.current.density
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < .5f
    val opaque = profile.opaqueMaterial(tint, MaterialTheme.colorScheme.surface)
    val buttonStyle = profile.buttonStyle
    // Compile once per control, never per size/animation frame.
    val lens = remember(profile.useRefraction) { if (Build.VERSION.SDK_INT >= 33 && profile.useRefraction) runCatching { LensShader() }.getOrNull() else null }
    val blur = remember(density, profile) { if (Build.VERSION.SDK_INT >= 31)
        RenderEffect.createBlurEffect(profile.blurDp * density, profile.blurDp * density, Shader.TileMode.CLAMP) else null }
    val effects = remember(lens, blur) { LiveGlassEffectCache() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    // Static highlight brush; linearGradient without offsets spans the draw size, so no size key.
    val highlightBrushCache = remember {
        val front = .055f
        Brush.linearGradient(listOf(
            Color.White.copy(alpha = front), Color.White.copy(alpha = front * .35f), Color.Transparent,
        ))
    }
    // One rim brush per (profile, theme); recreated only when either changes, never per frame.
    val edge = remember(profile, darkTheme) { profile.edgeAppearance(darkTheme) }
    val rimBrush = remember(edge) {
        Brush.linearGradient(listOf(edge.front, edge.middle, edge.back).map { Color.White.copy(alpha = it) })
    }
    androidx.compose.foundation.layout.Box(modifier.onGloballyPositioned { origin = it.localToRoot(Offset.Zero) }
        .drawWithContent {
            capture.revision
            var opticalActive = false
            val margin = glassSampleMargin(density, profile.blurDp)
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
                        reducedMotion, interaction, touch, darkTheme, profile, sampleBounds)
                    if (effects.key != key) {
                        val optical = if (Build.VERSION.SDK_INT >= 33 && !effects.lensFailed) runCatching {
                            lens?.effect(width, height, margin, radius, tint.toArgb(),
                                density, reducedMotion, interaction, touch?.x ?: -1f, touch?.y ?: -1f,
                                buttonStyle, darkTheme, profile == GlassMaterialProfile.SidebarPanel,
                                profile.dimAmount, sampleBounds)
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
                translate(-margin.toFloat(), -margin.toFloat()) { drawLayer(copy) }
                if (!opticalActive) {
                    drawRoundRect(tint, cornerRadius = CornerRadius(radius))
                    if (profile.dimAmount > 0f) {
                        drawRoundRect(Color.Black.copy(alpha = profile.dimAmount), cornerRadius = CornerRadius(radius))
                    }
                }
                if (profile.useHighlights) {
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
            }
        })
}

private data class LiveGlassEffectKey(
    val width: Int, val height: Int, val margin: Int, val radius: Float, val tint: Int,
    val reducedMotion: Boolean, val interaction: Float, val touch: Offset?,
    val darkTheme: Boolean, val profile: GlassMaterialProfile, val sampleBounds: Rect,
)

private class LiveGlassEffectCache {
    var key: LiveGlassEffectKey? = null
    var effect: androidx.compose.ui.graphics.RenderEffect? = null
    var opticalActive = false
    var lensFailed = false
}
