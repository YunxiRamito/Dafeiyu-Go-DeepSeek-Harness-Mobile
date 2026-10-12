/* Scroll-edge masking adapted to Compose layers from QWEA0/Liquid-Glass-Android
 * ScrollEdgeBlurView.kt, commit 16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d (MIT).
 * The capture must wrap only page content; toolbar/edge overlays are separate siblings.
 */
package com.dafeiyu.mobile.ui.components

import android.os.Build
import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import java.util.concurrent.CopyOnWriteArrayList

@Stable
class PageBackdropCapture internal constructor(internal val layer: GraphicsLayer) {
    private var revisionState by mutableIntStateOf(0)
    private val revisionListeners = CopyOnWriteArrayList<() -> Unit>()
    internal var revision: Int
        get() = revisionState
        set(value) {
            revisionState = value
            revisionListeners.forEach { it() }
        }
    internal var origin by mutableStateOf(Offset.Zero)
    internal fun addRevisionListener(listener: () -> Unit) {
        revisionListeners += listener
    }
    internal fun removeRevisionListener(listener: () -> Unit) {
        revisionListeners -= listener
    }
}

@Composable
fun rememberPageBackdropCapture(): PageBackdropCapture {
    val layer = rememberGraphicsLayer()
    return remember(layer) { PageBackdropCapture(layer) }
}

/** Record content once. Call outside the edge/toolbar subtree to prevent self-capture. */
fun Modifier.capturePageBackdrop(capture: PageBackdropCapture): Modifier =
    this.onGloballyPositioned { capture.origin = it.localToRoot(Offset.Zero) }.drawWithContent {
        capture.layer.record { this@drawWithContent.drawContent() }
        drawLayer(capture.layer)
        capture.revision++
    }

enum class ScrollGlassEdge { Top, Bottom }

/**
 * Two cached blur effects with directional feather masks sample actual page pixels.
 * To cover system bars, put this sibling overlay in an edge-to-edge root (outside safeDrawingPadding),
 * set barHeight and includeSystemBarInsets=true, and leave platform status/navigation colors transparent.
 * Without barHeight the caller's size is preserved for existing 88dp header integrations.
 * Bottom uses navigationBars, not IME; keyboard avoidance belongs to the surrounding composition.
 */
@Composable
fun ScrollingGlassHeader(
    capture: PageBackdropCapture,
    modifier: Modifier = Modifier,
    edge: ScrollGlassEdge = ScrollGlassEdge.Top,
    barHeight: Dp? = null,
    fadeExtent: Dp = 28.dp,
    includeSystemBarInsets: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val weak = rememberGraphicsLayer()
    val strong = rememberGraphicsLayer()
    val density = LocalDensity.current
    val systemInsetPx = if (!includeSystemBarInsets) 0 else when (edge) {
        ScrollGlassEdge.Top -> WindowInsets.statusBars.getTop(density)
        ScrollGlassEdge.Bottom -> WindowInsets.navigationBars.getBottom(density)
    }
    val systemInset = with(density) { systemInsetPx.toDp() }
    val weakBlur = remember(density.density) {
        if (Build.VERSION.SDK_INT >= 31) RenderEffect.createBlurEffect(
            4f * density.density, 4f * density.density, Shader.TileMode.CLAMP).asComposeRenderEffect() else null
    }
    val strongBlur = remember(density.density) {
        if (Build.VERSION.SDK_INT >= 31) RenderEffect.createBlurEffect(
            14f * density.density, 14f * density.density, Shader.TileMode.CLAMP).asComposeRenderEffect() else null
    }
    val blurLayers = remember(weak, strong, weakBlur, strongBlur) {
        listOf(weak to weakBlur, strong to strongBlur)
    }
    val palette = MaterialTheme.colorScheme
    val reduced = LocalReduceGlassTransparency.current
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    val sized = if (barHeight == null) modifier else modifier.height(barHeight + systemInset + fadeExtent)
    val canBlur = Build.VERSION.SDK_INT >= 31 && !reduced
    Box(sized.onGloballyPositioned { overlayOrigin = it.localToRoot(Offset.Zero) }) {
        if (canBlur) {
            for ((copy, effect) in blurLayers) {
                Box(Modifier.fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        capture.revision // Draw-only observation; the overlay never changes source content.
                        if (capture.layer.size.width > 0 && capture.layer.size.height > 0) {
                            val margin = ceil(42f * density.density).toInt()
                            // Record just this edge plus blur guard pixels, not a full-screen duplicate.
                            val recordSize = IntSize(ceil(size.width).toInt() + margin * 2,
                                ceil(size.height).toInt() + margin * 2)
                            val sampleOffset = capture.origin - overlayOrigin
                            copy.record(size = recordSize) {
                                translate(sampleOffset.x + margin, sampleOffset.y + margin) {
                                    drawLayer(capture.layer)
                                }
                            }
                            copy.renderEffect = effect
                            translate(-margin.toFloat(), -margin.toFloat()) { drawLayer(copy) }
                            val plateau = ((size.height - fadeExtent.toPx()) / size.height)
                                .coerceIn(.02f, .94f)
                            val fadeColors = if (copy === weak) arrayOf(
                                0f to Color.Black.copy(alpha = .72f),
                                plateau to Color.Black.copy(alpha = .58f),
                                1f to Color.Transparent,
                            ) else arrayOf(
                                0f to Color.Black,
                                plateau to Color.Black.copy(alpha = .88f),
                                1f to Color.Transparent,
                            )
                            drawRect(Brush.verticalGradient(*fadeColors,
                                startY = if (edge == ScrollGlassEdge.Top) 0f else size.height,
                                endY = if (edge == ScrollGlassEdge.Top) size.height else 0f),
                                blendMode = BlendMode.DstIn)
                        }
                    })
            }
        }
        // API30 provides a readability scrim; reduced-transparency deliberately skips optical blur.
        // All variants feather to zero at the inner edge so no solid rectangle intersects the page.
        val outerOpacity = if (reduced) 1f else if (Build.VERSION.SDK_INT < 31) .90f else .22f
        Box(Modifier.fillMaxSize().drawWithContent {
            val plateau = ((size.height - fadeExtent.toPx()) / size.height).coerceIn(.02f, .94f)
            drawRect(Brush.verticalGradient(
                0f to palette.background.copy(alpha = outerOpacity),
                plateau to palette.background.copy(alpha = outerOpacity * .80f),
                1f to Color.Transparent,
                startY = if (edge == ScrollGlassEdge.Top) 0f else size.height,
                endY = if (edge == ScrollGlassEdge.Top) size.height else 0f,
            ))
        })
        CompositionLocalProvider(LocalPageGlassBackdrop provides capture) {
            Box(Modifier.fillMaxSize().padding(
                top = if (edge == ScrollGlassEdge.Top) systemInset else if (barHeight != null) fadeExtent else 0.dp,
                bottom = if (edge == ScrollGlassEdge.Bottom) systemInset else if (barHeight != null) fadeExtent else 0.dp,
            ), content = content)
        }
    }
}
