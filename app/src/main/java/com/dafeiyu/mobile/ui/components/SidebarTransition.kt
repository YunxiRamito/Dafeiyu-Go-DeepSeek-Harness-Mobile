package com.dafeiyu.mobile.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.dafeiyu.mobile.data.DemoRepository
import com.dafeiyu.mobile.ui.WorkspaceSidebar
import kotlin.math.roundToInt

/** One measured circle and one Bezier progress coordinate the physical surface and all foreground fades. */
@Stable
class WorkspaceSidebarMorphState internal constructor() {
    internal val animation = Animatable(0f)
    internal var anchorBounds by mutableStateOf<Rect?>(null)
    internal var expanded by mutableStateOf(false)
    internal var settling by mutableStateOf(false)
    internal var predictiveBack by mutableStateOf(0f)
    private var dismissalProgress by mutableStateOf<Float?>(null)
    val progress: Float get() = (dismissalProgress ?: animation.value * (1f - predictiveBack)).coerceIn(0f, 1f)

    /** Freeze the currently visible bounds before the caller clears its predictive-back coordinate. */
    fun freezePredictiveDismiss() {
        if (dismissalProgress == null) dismissalProgress = progress
    }

    internal fun updateTarget(open: Boolean, back: Float) {
        if (expanded && !open) freezePredictiveDismiss()
        if (!expanded && open) dismissalProgress = null
        expanded = open
        predictiveBack = back
    }

    internal suspend fun adoptDismissedBounds() {
        dismissalProgress?.let { held ->
            animation.snapTo(held)
            predictiveBack = 0f
            dismissalProgress = null
        }
    }
    val surfaceVisible: Boolean get() = expanded || settling || animation.value > .001f
    val launcherVisible: Boolean get() = !surfaceVisible
    val launcherAlpha: Float get() = if (!surfaceVisible) 1f else (1f - progress / .04f).coerceIn(0f, 1f)
}

/** Pass the same state to the circle launcher and the expanding overlay. No independent slide animation. */
@Composable
fun rememberWorkspaceSidebarMorphState(
    expanded: Boolean,
    predictiveBackProgress: Float = 0f,
    // The page-transition setting can disable the morph independently of global reduce-motion.
    reducedMotion: Boolean = LocalReduceGlassMotion.current,
): WorkspaceSidebarMorphState {
    val state = remember { WorkspaceSidebarMorphState() }
    state.updateTarget(expanded, if (reducedMotion) 0f else predictiveBackProgress.coerceIn(0f, 1f))
    LaunchedEffect(expanded, reducedMotion) {
        if (!expanded) state.adoptDismissedBounds()
        if (!expanded && state.animation.value <= .001f) {
            state.animation.snapTo(0f)
            state.settling = false
            return@LaunchedEffect
        }
        state.settling = true
        try {
            state.animation.animateTo(if (expanded) 1f else 0f,
                GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion))
        } finally {
            state.settling = false
        }
    }
    return state
}

/** Apply on every root toolbar route so its visibility remains shared during route-changing closure. */
fun Modifier.workspaceSidebarMorphAnchor(state: WorkspaceSidebarMorphState): Modifier =
    onGloballyPositioned {
        // Hidden toolbar copies on other routes must not retarget a surface already in flight.
        if (!state.surfaceVisible) state.anchorBounds = it.boundsInRoot()
    }.graphicsLayer { alpha = state.launcherAlpha }

/**
 * Root-sized overlay, with geometry read during layout instead of composition. The expensive workspace
 * content always uses its final dimensions and is never recomposed for individual animation frames.
 * Only the material bounds and foreground layers change. Caller supplies a root-sized modifier.
 * The root toolbar launcher takes ownership continuously during the final 4% of closure.
 * Supply the CURRENT toolbar glyph (menu/back). Material keeps sampling the current shared backdrop;
 * no old page snapshot or independent background is retained across a settings navigation.
 */
@Composable
fun AnimatedWorkspaceSidebar(
    visible: Boolean,
    repo: DemoRepository,
    onClose: () -> Unit,
    onSettings: () -> Unit,
    onOnlineMetrics: () -> Unit = {},
    onConversationChanged: () -> Unit,
    modifier: Modifier = Modifier,
    morphState: WorkspaceSidebarMorphState = rememberWorkspaceSidebarMorphState(visible),
    launcherGlyph: String = "menu",
    fusionBackdrop: PageBackdropCapture? = null,
) {
    // Retain the native drawing view and initialized workspace content across open/close cycles.
    val accessible by remember(morphState) { derivedStateOf {
        morphState.surfaceVisible && morphState.progress > .2f
    } }
    val hiddenSemantics = if (accessible) Modifier else Modifier.clearAndSetSemantics {}
    val density = LocalDensity.current
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(modifier.fillMaxSize().onGloballyPositioned { overlayOrigin = it.boundsInRoot().topLeft }) {
        val finalWidth = minOf(296.dp, maxWidth - 32.dp).coerceAtLeast(48.dp)
        val anchor = morphState.anchorBounds
        val anchorX = anchor?.let { with(density) { (it.left - overlayOrigin.x).toDp() } } ?: 16.dp
        val anchorY = anchor?.let { with(density) { (it.top - overlayOrigin.y).toDp() } } ?: 8.dp
        val anchorWidth = anchor?.let { with(density) { it.width.toDp() } } ?: 48.dp
        val anchorHeight = anchor?.let { with(density) { it.height.toDp() } } ?: 48.dp
        val targetTop = anchorY.coerceAtLeast(8.dp)
        val finalHeight = (maxHeight - targetTop - 16.dp).coerceAtLeast(48.dp)
        val startWidthPx = with(density) { anchorWidth.toPx() }
        val startHeightPx = with(density) { anchorHeight.toPx() }
        val finalWidthPx = with(density) { finalWidth.toPx() }
        val finalHeightPx = with(density) { finalHeight.toPx() }
        val startCenter = with(density) { Offset(anchorX.toPx() + startWidthPx / 2f, anchorY.toPx() + startHeightPx / 2f) }
        val endCenter = with(density) { Offset(16.dp.toPx() + finalWidthPx / 2f, targetTop.toPx() + finalHeightPx / 2f) }
        CompositionLocalProvider(LocalGlassMaterialProfile provides GlassMaterialProfile.SidebarPanel,
            LocalPageGlassBackdrop provides null) {
        GlassSurface(
            // The independent leaf contains the same page blur/dim as the outside modal backdrop.
            // A missing capture uses the opaque theme fallback, never the decorative texture.
            backdrop = LocalFloatingGlassBackdrop.current,
            modifier = hiddenSemantics.focusProperties { canFocus = accessible }.layout { measurable, _ ->
                if (!morphState.surfaceVisible) {
                    measurable.measure(Constraints.fixed(0, 0))
                    // Do not place even fixed-size children: they cannot draw, hit-test, or take focus.
                    return@layout layout(0, 0) {}
                }
                val p = morphState.progress
                // The center leads the size interpolation by a small amount, without an abrupt two-stage jump.
                val centerProgress = GlassMotion.centerProgress(p)
                val boundsProgress = GlassMotion.sizeProgress(p)
                val currentWidth = (startWidthPx + (finalWidthPx - startWidthPx) * boundsProgress).roundToInt().coerceAtLeast(1)
                val currentHeight = (startHeightPx + (finalHeightPx - startHeightPx) * boundsProgress).roundToInt().coerceAtLeast(1)
                val currentCenter = startCenter + (endCenter - startCenter) * centerProgress
                val placeable = measurable.measure(Constraints.fixed(currentWidth, currentHeight))
                layout(maxWidth.roundToPx(), maxHeight.roundToPx()) {
                    placeable.place((currentCenter.x - currentWidth / 2f).roundToInt(),
                        (currentCenter.y - currentHeight / 2f).roundToInt())
                }
            }.graphicsLayer { alpha = 1f - morphState.launcherAlpha },
            // The rounded-rect shape clamps radius to half the short edge; 48dp naturally becomes a circle.
            cornerRadius = 30.dp,
            tint = MaterialTheme.colorScheme.surface.copy(alpha = .29f),
        ) {
            Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(finalWidth, finalHeight)
                .graphicsLayer { alpha = ((morphState.progress - .2f) / .45f).coerceIn(0f, 1f) }) {
                CompositionLocalProvider(LocalGlassMaterialProfile provides GlassMaterialProfile.Standard) {
                    WorkspaceSidebar(repo, onClose, onSettings, onOnlineMetrics, onConversationChanged,
                        fusionBackdrop = fusionBackdrop)
                }
            }
            Box(Modifier.size(anchorWidth, anchorHeight).graphicsLayer {
                clip = true
                alpha = ((.34f - morphState.progress) / .34f).coerceIn(0f, 1f)
            }, contentAlignment = Alignment.Center) {
                FishGlyph(launcherGlyph, Modifier.size(21.dp), MaterialTheme.colorScheme.onSurface)
            }
        }
        }
    }
}






