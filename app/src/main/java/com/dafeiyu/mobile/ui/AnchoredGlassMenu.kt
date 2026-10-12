package com.dafeiyu.mobile.ui

import androidx.compose.animation.core.Animatable
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.dafeiyu.mobile.ui.components.GlassSurface
import com.dafeiyu.mobile.ui.components.GlassMaterialProfile
import com.dafeiyu.mobile.ui.components.LocalGlassMaterialProfile
import com.dafeiyu.mobile.ui.components.LocalPageGlassBackdrop
import com.dafeiyu.mobile.ui.components.LocalFloatingGlassBackdrop
import com.dafeiyu.mobile.ui.components.LocalGlassBackdrop
import com.dafeiyu.mobile.ui.components.LocalReduceGlassMotion
import com.dafeiyu.mobile.ui.components.GlassMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class GlassMenuPlacement { AboveCenter, BelowEnd }

internal data class GlassMenuGeometry(val origin: IntOffset, val target: IntRect)

internal fun glassMenuSafeBounds(window: IntSize, insets: IntRect, spacing: Int): IntRect {
    val left = (insets.left + spacing).coerceIn(0, window.width)
    val top = (insets.top + spacing).coerceIn(0, window.height)
    return IntRect(left, top,
        (window.width - insets.right - spacing).coerceIn(left, window.width),
        (window.height - insets.bottom - spacing).coerceIn(top, window.height))
}

internal fun glassMenuGeometry(
    anchor: IntRect,
    popup: IntSize,
    safe: IntRect,
    spacing: Int,
    placement: GlassMenuPlacement,
): GlassMenuGeometry {
    val x = (if (placement == GlassMenuPlacement.BelowEnd) anchor.right - popup.width
        else anchor.center.x - popup.width / 2)
        .coerceIn(safe.left, (safe.right - popup.width).coerceAtLeast(safe.left))
    val y = (if (placement == GlassMenuPlacement.BelowEnd) anchor.top else anchor.bottom - popup.height)
        .coerceIn(safe.top, (safe.bottom - popup.height).coerceAtLeast(safe.top))
    val bottom = minOf(popup.height, safe.bottom - y,
        if (placement == GlassMenuPlacement.AboveCenter) anchor.top - y - spacing else popup.height)
        .coerceAtLeast(0)
    val top = (if (placement == GlassMenuPlacement.BelowEnd) anchor.bottom - y + spacing else 0)
        .coerceIn(0, bottom)
    // Position clamping can move the popup above its anchor. Reserve that offset too.
    val height = minOf((popup.height - anchor.height - spacing).coerceAtLeast(0), bottom - top)
    return GlassMenuGeometry(IntOffset(x, y),
        IntRect(0, top, minOf(popup.width, safe.right - x).coerceAtLeast(0), top + height))
}

/** Shared launcher/menu ownership. No async callback controls the launcher's alpha. */
@Stable
class ModelMenuMorphState internal constructor() {
    internal val entrance = Animatable(0f)
    internal val predictive = Animatable(0f)
    internal var expanded by mutableStateOf(false)
    internal var retained by mutableStateOf(false)
    internal var anchorBounds by mutableStateOf<Rect?>(null)
    internal var openedAnchorBounds: Rect? = null
    val surfaceVisible get() = expanded || retained
    val progress get() = (entrance.value * (1f - predictive.value)).coerceIn(0f, 1f)
    // The last 8% returns the matching label to its original Compose node, not a new label.
    val launcherAlpha get() = if (!surfaceVisible) 1f else 1f - (progress / .08f).coerceIn(0f, 1f)
}

@Composable
fun rememberModelMenuMorphState(expanded: Boolean): ModelMenuMorphState {
    val state = remember { ModelMenuMorphState() }
    if (expanded && !state.expanded) state.openedAnchorBounds = state.anchorBounds
    state.expanded = expanded
    return state
}

@Composable
fun AnchoredGlassMenu(
    expanded: Boolean,
    state: ModelMenuMorphState,
    onDismiss: () -> Unit,
    reduceTransparency: Boolean = false,
    menuWidth: Dp = 320.dp,
    menuHeight: Dp = 536.dp,
    placement: GlassMenuPlacement = GlassMenuPlacement.AboveCenter,
    onSurfaceVisibilityChanged: (Boolean) -> Unit = {},
    onOverlayProgress: (Float) -> Unit = {},
    onClosed: () -> Unit = {},
    anchorContent: (@Composable BoxScope.() -> Unit)? = null,
    sourceContent: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    // A Popup has a different root/window. The native backdrop bridge measures both views on
    // screen; inheriting a toolbar's Compose page capture would sample in the wrong coordinates.
    val floatingBackdrop = LocalFloatingGlassBackdrop.current ?: LocalGlassBackdrop.current
    val latestOverlay by rememberUpdatedState(onOverlayProgress)
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val screen = LocalConfiguration.current
    val layoutDirection = LocalLayoutDirection.current
    val spacing = with(density) { 12.dp.roundToPx() }
    val safeInsets = WindowInsets.safeDrawing.let {
        IntRect(it.getLeft(density, layoutDirection), it.getTop(density),
            it.getRight(density, layoutDirection), it.getBottom(density))
    }
    var measuredWindow by remember(screen.screenWidthDp, screen.screenHeightDp) {
        mutableStateOf<IntSize?>(null)
    }
    val window = measuredWindow ?: with(density) {
        IntSize(screen.screenWidthDp.dp.roundToPx(), screen.screenHeightDp.dp.roundToPx())
    }
    val safeBounds = glassMenuSafeBounds(window, safeInsets, spacing)
    val popupWidth = minOf(menuWidth, with(density) { safeBounds.width.toDp() })
    val popupHeight = minOf(menuHeight, with(density) { safeBounds.height.toDp() })
    var anchorGeometry by remember { mutableStateOf(IntRect.Zero) }
    var popupOrigin by remember { mutableStateOf(IntOffset.Zero) }
    var menuGeometry by remember { mutableStateOf<GlassMenuGeometry?>(null) }
    val position = remember(spacing, state, placement, safeInsets) { object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
            layoutDirection: androidx.compose.ui.unit.LayoutDirection, popupContentSize: IntSize): IntOffset {
            val measured = state.openedAnchorBounds ?: state.anchorBounds
            val actual = measured?.let { IntRect(it.left.roundToInt(), it.top.roundToInt(),
                it.right.roundToInt(), it.bottom.roundToInt()) } ?: anchorBounds
            val geometry = glassMenuGeometry(actual, popupContentSize,
                glassMenuSafeBounds(windowSize, safeInsets, spacing), spacing, placement)
            measuredWindow = windowSize
            anchorGeometry = actual
            menuGeometry = geometry
            popupOrigin = geometry.origin
            return popupOrigin
        }
    } }
    val entrance = state.entrance
    val predictive = state.predictive
    val reducedMotion = LocalReduceGlassMotion.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(expanded, reducedMotion) {
        if (!expanded && !state.retained && entrance.value <= .001f) return@LaunchedEffect
        state.retained = true
        if (expanded) {
            predictive.snapTo(0f)
            entrance.animateTo(1f, GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion))
        } else {
            // Freeze predictive geometry before resetting its channel; never jump back open.
            val effective = state.progress
            entrance.snapTo(effective)
            predictive.snapTo(0f)
            entrance.animateTo(0f, GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion))
            // Keep one transparent hand-off frame so the launcher already owns its label.
            withFrameNanos { }
            state.retained = false
            onClosed()
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.progress }.collect { latestOverlay(it) }
    }
    val surfaceVisible = state.surfaceVisible
    LaunchedEffect(surfaceVisible) { onSurfaceVisibilityChanged(surfaceVisible) }
    DisposableEffect(Unit) { onDispose { latestOverlay(0f); onSurfaceVisibilityChanged(false) } }
    if (!surfaceVisible) return
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = false)) {
        PredictiveBackHandler(enabled = expanded) {
            try {
                it.collect { event -> predictive.snapTo(if (reducedMotion) 0f else event.progress) }
                onDismiss()
            } catch (_: CancellationException) {
                scope.launch { predictive.animateTo(0f, GlassMotion.scalar(180, reducedMotion)) }
            }
        }
        // Anchor/final dimensions are stable for this Popup lifetime. Read animation only
        // during placement and foreground-layer drawing, never while composing the menu body.
        val sourceWidth = with(density) { anchorGeometry.width.coerceAtLeast(1).toDp() }
            .takeIf { anchorGeometry.width > 0 } ?: 190.dp
        val sourceHeight = with(density) { anchorGeometry.height.coerceAtLeast(1).toDp() }
            .takeIf { anchorGeometry.height > 0 } ?: 44.dp
        val targetHeight = menuGeometry?.target?.height?.let { with(density) { it.toDp() } }
            ?: (popupHeight - sourceHeight - 12.dp).coerceAtLeast(0.dp)
        val sourceCenterX = if (anchorGeometry.width > 0)
            with(density) { (anchorGeometry.center.x - popupOrigin.x).toDp() } else popupWidth / 2
        val sourceCenterY = if (anchorGeometry.height > 0)
            with(density) { (anchorGeometry.center.y - popupOrigin.y).toDp() } else popupHeight - sourceHeight / 2
        val sourceWidthPx = with(density) { sourceWidth.toPx() }
        val sourceHeightPx = with(density) { sourceHeight.toPx() }
        val finalWidthPx = with(density) { popupWidth.toPx() }
        val finalHeightPx = with(density) { targetHeight.toPx() }
        val popupHeightPx = with(density) { popupHeight.toPx() }
        val sourceCenterXPx = with(density) { sourceCenterX.toPx() }
        val sourceCenterYPx = with(density) { sourceCenterY.toPx() }
        val targetTopPx = menuGeometry?.target?.top?.toFloat() ?: 0f
        CompositionLocalProvider(
            LocalPageGlassBackdrop provides null,
            LocalGlassMaterialProfile provides GlassMaterialProfile.FloatingMenu,
        ) {
        Box(Modifier.size(popupWidth, popupHeight)) {
        GlassSurface(
            backdrop = floatingBackdrop,
            modifier = Modifier.layout { measurable, _ ->
                val p = state.progress
                val center = GlassMotion.centerProgress(p)
                val bounds = GlassMotion.sizeProgress(p)
                val width = (sourceWidthPx + (finalWidthPx - sourceWidthPx) * bounds)
                    .roundToInt().coerceIn(0, finalWidthPx.roundToInt())
                val height = (sourceHeightPx + (finalHeightPx - sourceHeightPx) * bounds)
                    .roundToInt().coerceIn(0, popupHeightPx.roundToInt())
                val x = sourceCenterXPx + (finalWidthPx / 2f - sourceCenterXPx) * center
                val y = sourceCenterYPx + (targetTopPx + finalHeightPx / 2f - sourceCenterYPx) * center
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(finalWidthPx.roundToInt(), popupHeightPx.roundToInt()) {
                    placeable.place(
                        (x - width / 2f).roundToInt().coerceIn(0, finalWidthPx.roundToInt() - width),
                        (y - height / 2f).roundToInt().coerceIn(0, popupHeightPx.roundToInt() - height))
                }
            }.graphicsLayer {
                // Same continuous final hand-off; alpha doesn't recreate the material configuration.
                alpha = if (anchorContent != null) ((state.progress - .08f) / .18f).coerceIn(0f, 1f)
                    else (state.progress / .08f).coerceIn(0f, 1f)
            },
            // At a short initial bound the renderer naturally clamps this radius to half-height.
            // Keeping radius/tint stable prevents per-frame AGSL/effect reconstruction.
            cornerRadius = 28.dp,
            tint = colors.surface.copy(alpha = .55f),
            reduceTransparency = reduceTransparency,
        ) {
            Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                .requiredSize(popupWidth, targetHeight).graphicsLayer {
                    val growth = GlassMotion.sizeProgress(state.progress)
                    alpha = ((growth - .20f) / .55f).coerceIn(0f, 1f)
                }) {
                content()
            }
            if (anchorContent == null) Box(Modifier.size(sourceWidth, sourceHeight).graphicsLayer {
                val growth = GlassMotion.sizeProgress(state.progress)
                alpha = (1f - growth * 4f).coerceIn(0f, 1f)
            }, content = sourceContent)
        }
        if (anchorContent != null) Box(
            Modifier.size(sourceWidth, sourceHeight).offset {
                IntOffset((sourceCenterXPx - sourceWidthPx / 2).roundToInt(),
                    (sourceCenterYPx - sourceHeightPx / 2).roundToInt())
            }.graphicsLayer { alpha = (state.progress / .08f).coerceIn(0f, 1f) },
            content = anchorContent,
        )
        }
        }
    }
}
