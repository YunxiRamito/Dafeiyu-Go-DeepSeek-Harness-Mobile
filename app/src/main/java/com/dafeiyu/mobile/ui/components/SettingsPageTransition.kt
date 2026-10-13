package com.dafeiyu.mobile.ui.components

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.ceil
import kotlin.math.roundToInt

@Stable
class SettingsGroupGeometry {
    var boundsInWindow by mutableStateOf<Rect?>(null)
}

val LocalSettingsGroupGeometry = staticCompositionLocalOf<SettingsGroupGeometry?> { null }
val LocalSettingsPageTransition = staticCompositionLocalOf<SettingsPageTransitionState?> { null }

/** Retain one measured origin until close completes, including cancelled predictive-back gestures. */
@Stable
class SettingsPageTransitionState internal constructor() {
    private val animation = Animatable(0f)
    val progress: Float get() = animation.value
    var targetRoute by mutableStateOf<String?>(null)
        private set
    var clickedRowInWindow by mutableStateOf<Rect?>(null)
        private set
    var sourceGroupInWindow by mutableStateOf<Rect?>(null)
        private set
    // Only the clicked row/button drives the shape; the surrounding group is metadata.
    val sourceBoundsInWindow: Rect? get() = clickedRowInWindow
    var sourceCornerRadiusPx by mutableStateOf<Float?>(null)
        private set
    var isActive by mutableStateOf(false)
        private set
    var isAnimating by mutableStateOf(false)
        private set

    fun recordSource(route: String, rowInWindow: Rect, groupInWindow: Rect?, cornerRadiusPx: Float? = null) {
        if (isActive || rowInWindow.width <= 0f || rowInWindow.height <= 0f) return
        targetRoute = route
        clickedRowInWindow = rowInWindow
        sourceGroupInWindow = groupInWindow?.takeIf { it.width > 0f && it.height > 0f } ?: rowInWindow
        sourceCornerRadiusPx = cornerRadiusPx?.takeIf { it.isFinite() && it >= 0f }
    }

    suspend fun open(reducedMotion: Boolean = false) {
        if (targetRoute == null || sourceBoundsInWindow == null || isActive) return
        isActive = true
        isAnimating = true
        try {
            animation.snapTo(0f)
            animation.animateTo(1f, GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion))
        } finally { isAnimating = false }
    }

    suspend fun close(reducedMotion: Boolean = false) {
        if (!isActive) return
        isAnimating = true
        try {
            animation.animateTo(0f, GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion))
            isActive = false
        } finally { isAnimating = false }
    }

    /** Call after cancelling the opening/closing Job; each event retraces the same rectangle path. */
    suspend fun predictiveBack(progress: Float) {
        if (isActive) animation.snapTo(1f - progress.coerceIn(0f, 1f))
    }
    suspend fun cancelPredictiveBack(reducedMotion: Boolean = false) {
        if (isActive) animation.animateTo(1f, GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion))
    }
}

@Composable
fun rememberSettingsPageTransitionState(): SettingsPageTransitionState = remember { SettingsPageTransitionState() }

/**
 * Move and expand the measured clicked row/button into the viewport on the same continuous trajectory.
 * Place inside the root's safe drawing area, above the preserved SettingsScreen and below global overlays.
 * API31+ uses RenderEffect content blur; API30 uses alpha-only. This is navigation blur, not backdrop refraction.
 */
@Composable
fun SettingsPageTransition(
    state: SettingsPageTransitionState,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = LocalReduceGlassMotion.current,
    crossfadeProgress: Float? = null,
    directSourceContent: Boolean = false,
    sourceContent: @Composable BoxScope.() -> Unit,
    destinationContent: @Composable BoxScope.(String) -> Unit,
) {
    val density = LocalDensity.current
    val palette = MaterialTheme.colorScheme
    // Record the source subtree only. Transform progress is read by render nodes, never this draw recorder.
    val sourceLayer = rememberGraphicsLayer()
    val sourceTileLayer = rememberGraphicsLayer()
    val sourceRecording = remember { SettingsSourceRecording() }
    val blurEffects = remember(density.density, reducedMotion) {
        List(5) { index -> pageBlurEffect(if (reducedMotion) 0f else index * 2f * density.density) }
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val source = state.sourceBoundsInWindow
    val active = state.isActive && source != null && state.targetRoute != null
    val tileCache = remember(active, state.targetRoute, source) { SettingsTileCache() }
    // Unspecified row/button origins start as a true capsule; explicit toolbar-circle radius stays authoritative.
    val corner = state.sourceCornerRadiusPx ?: source?.let { minOf(it.width, it.height) / 2f } ?: 0f
    // derivedStateOf changes composition only when hit-testing crosses the settled threshold.
    val destinationBlocked by remember(state, active, crossfadeProgress) {
        derivedStateOf {
            active && (state.isAnimating || state.progress < .999f ||
                (crossfadeProgress != null && crossfadeProgress < .999f))
        }
    }
    Box(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        val sourceCutout = remember { Path() }
        Box(Modifier.fillMaxSize().transitionInputBlocked(active).drawWithContent {
            if (active && crossfadeProgress == null) {
                val start = requireNotNull(source).translate(-origin)
                sourceCutout.reset()
                sourceCutout.addRoundRect(RoundRect(start, CornerRadius(corner)))
                // The travelling tile owns this row/circle until the final frame returns ownership.
                // Clip after the source's blur, so it cannot blur the original button back into view.
                clipPath(sourceCutout, ClipOp.Difference) { this@drawWithContent.drawContent() }
            } else {
                drawContent()
            }
        }.graphicsLayer {
            val p = if (active) state.progress else 0f
            val crossfade = if (active) crossfadeProgress?.coerceIn(0f, 1f) else null
            // Keep other settings rows visible beneath the expanding row's clip. At p=1 the
            // opaque full-size destination covers them; only the auxiliary sidebar handoff crossfades.
            alpha = if (active && crossfade != null) 1f - crossfade else 1f
            renderEffect = if (!active) null else blurEffects[blurStage(crossfade?.let { 1f } ?: p)]
        }.drawWithContent {
            sourceLayer.record { this@drawWithContent.drawContent() }
            sourceRecording.revision++
            if (directSourceContent) this@drawWithContent.drawContent() else drawLayer(sourceLayer)
        }, content = sourceContent)
        if (active) {
            val route = requireNotNull(state.targetRoute)
            val measured = requireNotNull(source)
            val shapePath = remember { Path() }
            Box(Modifier.fillMaxSize().graphicsLayer {
                val start = measured.translate(-origin)
                val geometry = settingsMorphGeometry(start, Size(size.width, size.height), corner, state.progress)
                shape = SettingsMorphShape(geometry)
                clip = true
                // Bound descendant AndroidView/RenderEffect output at the owning RenderNode as well.
                compositingStrategy = CompositingStrategy.Offscreen
            }.drawWithContent {
                val p = state.progress.coerceIn(0f, 1f)
                val crossfade = crossfadeProgress?.coerceIn(0f, 1f)
                val start = Rect(measured.left - origin.x, measured.top - origin.y,
                    measured.right - origin.x, measured.bottom - origin.y)
                // The clicked row is the source of truth: all four sides expand from its exact bounds.
                // The tiny center lead is retained only as a shared continuous easing, never as a dock.
                val geometry = settingsMorphGeometry(start, size, corner, p)
                val rect = geometry.rect
                val radius = geometry.radius
                shapePath.reset()
                shapePath.addRoundRect(RoundRect(rect, CornerRadius(radius)))
                clipPath(shapePath) {
                    if (crossfade == null) {
                        drawRoundRect(palette.surface, Offset(rect.left, rect.top), Size(rect.width, rect.height),
                            CornerRadius(radius), alpha = (p / .28f).coerceIn(0f, 1f))
                    }
                    val sourceTileAlpha = if (crossfade != null) 0f else (1f - p / .28f).coerceIn(0f, 1f)
                    if (sourceTileAlpha > .001f && sourceLayer.size.width > 0 && sourceLayer.size.height > 0) {
                        if (tileCache.tileRevision != sourceRecording.revision || tileCache.bounds != start) {
                            val tileSize = IntSize(ceil(start.width).toInt().coerceAtLeast(1),
                                ceil(start.height).toInt().coerceAtLeast(1))
                            sourceTileLayer.record(size = tileSize) {
                                translate(-start.left, -start.top) { drawLayer(sourceLayer) }
                            }
                            // record() retains child layer references, not frozen pixels. Isolate their
                            // drawing to the tile's actual rounded bounds, including nested native views.
                            sourceTileLayer.setRoundRectOutline(cornerRadius = corner)
                            sourceTileLayer.clip = true
                            sourceTileLayer.compositingStrategy = androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen
                            tileCache.tileRevision = sourceRecording.revision
                            tileCache.bounds = start
                        }
                        // No extra per-frame effect allocation or secondary blur on the small row snapshot.
                        sourceTileLayer.alpha = sourceTileAlpha
                        translate(rect.center.x - start.width / 2f, rect.center.y - start.height / 2f) {
                            drawLayer(sourceTileLayer)
                        }
                    }
                    this@drawWithContent.drawContent()
                }
            }) {
                // Both pages retain full-size layout. Only the material clip and render-node properties animate.
                Box(Modifier.fillMaxSize().transitionInputBlocked(destinationBlocked).graphicsLayer {
                    val p = state.progress
                    val crossfade = crossfadeProgress?.coerceIn(0f, 1f)
                    alpha = crossfade ?: ((p - .25f) / .75f).coerceIn(0f, 1f)
                    renderEffect = blurEffects[blurStage(1f - (crossfade ?: p))]
                }.background(palette.background)) { destinationContent(route) }
            }
        }
    }
}

/** Non-snapshot draw cache: changes never invalidate composition or re-layout either page. */
private class SettingsTileCache {
    var tileRevision = -1L
    var bounds: Rect? = null
}

private class SettingsSourceRecording {
    var revision = 0L
}

internal data class SettingsMorphGeometry(val rect: Rect, val radius: Float)

internal fun settingsMorphGeometry(start: Rect, viewport: Size, corner: Float, progress: Float): SettingsMorphGeometry {
    val p = progress.coerceIn(0f, 1f)
    val growth = GlassMotion.sizeProgress(p)
    val centre = start.center + (Offset(viewport.width / 2f, viewport.height / 2f) - start.center) *
        GlassMotion.centerProgress(p)
    val width = start.width + (viewport.width - start.width) * growth
    val height = start.height + (viewport.height - start.height) * growth
    return SettingsMorphGeometry(
        Rect(centre.x - width / 2f, centre.y - height / 2f, centre.x + width / 2f, centre.y + height / 2f),
        minOf(corner * (1f - growth), width / 2f, height / 2f).coerceAtLeast(0f),
    )
}

private data class SettingsMorphShape(val geometry: SettingsMorphGeometry) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(geometry.rect, CornerRadius(geometry.radius)))
}

private fun blurStage(progress: Float): Int = (progress.coerceIn(0f, 1f) * 4f).roundToInt()

private fun pageBlurEffect(radius: Float): androidx.compose.ui.graphics.RenderEffect? =
    if (Build.VERSION.SDK_INT >= 31 && radius > .01f) {
        android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP).asComposeRenderEffect()
    } else null

/** Consume at the ancestor's Initial pass so hidden/full-size descendants cannot be tapped or scrolled. */
private fun Modifier.transitionInputBlocked(blocked: Boolean): Modifier = if (!blocked) this else
    this.clearAndSetSemantics {}.pointerInput(blocked) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            }
        }
    }
