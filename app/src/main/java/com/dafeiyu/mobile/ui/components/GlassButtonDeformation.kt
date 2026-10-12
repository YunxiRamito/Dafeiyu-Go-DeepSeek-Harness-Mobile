/*
 * Directional press/stretch behavior adapted from QWEA0/Liquid-Glass-Android,
 * LiquidGlassView.kt at commit 16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d.
 * Copyright (c) 2025-2026 pandadog. MIT; see third_party/liquid-glass/LICENSE.
 * Compose gesture arbitration and radial drag/Bezier release are implemented here.
 */
package com.dafeiyu.mobile.ui.components

import androidx.compose.animation.core.snap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sqrt

/** Nested helpers see the same UP. Deduplicate that exact pointer event, not successive real taps. */
private object GlassReleaseHaptics {
    private var lastUptime = Long.MIN_VALUE
    private var lastPointer = Long.MIN_VALUE
    fun confirm(haptics: GlassHaptics, uptime: Long, pointer: Long) {
        if (lastUptime == uptime && lastPointer == pointer) return
        lastUptime = uptime
        lastPointer = pointer
        haptics.confirm()
    }
}

/** Shared foreground/material transform; translation uses button-local pixels. */
internal data class GlassButtonVisualFrame(
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val translation: Offset = Offset.Zero,
    val opticalPress: Float = 0f,
)

/** Independent press and directional stretch, adapted from LiquidGlassView's elastic-scale behavior. */
@Stable
class GlassButtonDeformationState internal constructor() {
    var pressed by mutableStateOf(false)
        internal set
    var dragging by mutableStateOf(false)
        internal set
    var delta by mutableStateOf(Offset.Zero)
        internal set
    var touchPosition by mutableStateOf(Offset.Zero)
        internal set
    internal var visualFrame by mutableStateOf(GlassButtonVisualFrame())
    val interactionStrength: Float get() = if (pressed) 1f else 0f

    internal fun reset() {
        pressed = false
        dragging = false
        delta = Offset.Zero
    }
}

/** 「按钮 Q 弹效果」：false 时关闭按压放大与拖动形变，保留点击、命中判定、触感和按压光学高光。 */
val LocalGlassButtonSpring = staticCompositionLocalOf { true }

internal fun glassButtonPressScale(pressed: Boolean, circular: Boolean, reducedMotion: Boolean): Float =
    if (!pressed || reducedMotion) 1f else if (circular) 1.035f else 1.025f

/** Sticky cancellation matches a click detector: returning inside cannot revive a cancelled tap. */
internal class GlassButtonGesture(
    private val width: Float,
    private val height: Float,
    private val circular: Boolean,
    private val slop: Float,
    private val down: Offset,
) {
    var dragging = false
        private set
    var cancelled = !contains(down)
        private set
    val active: Boolean get() = !cancelled && !dragging

    fun contains(position: Offset): Boolean {
        if (!position.x.isFinite() || !position.y.isFinite() || width <= 0f || height <= 0f) return false
        if (position.x < 0f || position.y < 0f || position.x > width || position.y > height) return false
        return !circular || (position - Offset(width / 2f, height / 2f)).getDistance() <= minOf(width, height) / 2f
    }

    fun update(position: Offset, otherPointerPressed: Boolean = false) {
        if ((position - down).getDistance() > slop) dragging = true
        if (!contains(position) || otherPointerPressed) cancelled = true
    }

    fun cancel() { cancelled = true }
    fun acceptsUp(position: Offset): Boolean {
        update(position)
        return !cancelled && !dragging
    }
}

@Composable
fun rememberGlassButtonDeformationState(): GlassButtonDeformationState = remember { GlassButtonDeformationState() }

/**
 * Apply to the whole glass button container, outside its clip. Its child IconButton keeps tap semantics.
 * The parent observes taps but only consumes movement after touch slop, cancelling accidental clicks.
 * A drag deforms this control locally; it never moves a screen, toolbar, or glass panel.
 * Set consumeDrag=false for list rows/thumbnails so the scroll container owns movement and cancels taps.
 * Set drawShadow=false when a combined glass surface owns the shadow.
 */
fun Modifier.applyButtonDeformation(
    enabled: Boolean = true,
    state: GlassButtonDeformationState? = null,
    circularHit: Boolean = false,
    consumeDrag: Boolean = true,
    drawShadow: Boolean = true,
): Modifier = composed {
    val deformation = state ?: rememberGlassButtonDeformationState()
    DisposableEffect(deformation, enabled) {
        if (!enabled) deformation.visualFrame = GlassButtonVisualFrame()
        onDispose { deformation.visualFrame = GlassButtonVisualFrame() }
    }
    if (!enabled) return@composed this.then(if (drawShadow && (circularHit || state != null))
        Modifier.glassButtonShadow(if (circularHit) CircleShape else RoundedCornerShape(24.dp)) else Modifier)
    val reduceMotion = LocalReduceGlassMotion.current
    // Geometry (scale/stretch/offset) is off when motion is reduced or the spring setting is off.
    val noSpring = reduceMotion || !LocalGlassButtonSpring.current
    val density = LocalDensity.current
    val haptics by rememberUpdatedState(LocalGlassHaptics.current)
    val translationLimit = with(density) { 6.dp.toPx() }
    val delta = deformation.delta
    val distance = sqrt(delta.x * delta.x + delta.y * delta.y)
    val intensity = (distance / with(density) { 52.dp.toPx() }).coerceIn(0f, 1f)
    val directionX = if (distance > .01f) abs(delta.x) / distance else 0f
    val directionY = if (distance > .01f) abs(delta.y) / distance else 0f
    val pressProgress = animateFloatAsState(
        if (deformation.pressed && !reduceMotion) 1f else 0f,
        GlassMotion.scalar(if (deformation.pressed) GlassMotion.PressMillis else GlassMotion.ReleaseMillis, reduceMotion),
        label = "glassButtonPress",
    )
    val releaseSpec: FiniteAnimationSpec<Float> = if (noSpring || deformation.dragging) snap()
        else GlassMotion.scalar()
    val stretchX = animateFloatAsState(
        if (noSpring) 1f else 1f + directionX * intensity * .045f - directionY * intensity * .018f,
        releaseSpec, label = "glassButtonStretchX",
    )
    val stretchY = animateFloatAsState(
        if (noSpring) 1f else 1f + directionY * intensity * .045f - directionX * intensity * .018f,
        releaseSpec, label = "glassButtonStretchY",
    )
    val resisted = GlassMotion.radialResistance(delta, translationLimit)
    val offsetX = animateFloatAsState(if (noSpring) 0f else resisted.x, releaseSpec, label = "glassButtonOffsetX")
    val offsetY = animateFloatAsState(if (noSpring) 0f else resisted.y, releaseSpec, label = "glassButtonOffsetY")
    LaunchedEffect(deformation, reduceMotion, noSpring, circularHit, pressProgress, stretchX, stretchY, offsetX, offsetY) {
        // Observe only animation inputs, never the published frame, so publication cannot feed back.
        snapshotFlow {
            if (reduceMotion) {
                GlassButtonVisualFrame()
            } else {
                val press = pressProgress.value
                val scale = if (noSpring) 1f else 1f + (glassButtonPressScale(true, circularHit, false) - 1f) * press
                GlassButtonVisualFrame(
                    scaleX = scale * stretchX.value,
                    scaleY = scale * stretchY.value,
                    translation = Offset(offsetX.value, offsetY.value),
                    opticalPress = press,
                )
            }
        }.collect { deformation.visualFrame = it }
    }
    this
        .pointerInput(deformation, circularHit, consumeDrag) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val gesture = GlassButtonGesture(size.width.toFloat(), size.height.toFloat(),
                    circularHit, viewConfiguration.touchSlop, down.position)
                if (!gesture.contains(down.position)) {
                    // Reject bounding-box corners before the child's click detector sees the event.
                    down.consume()
                    do {
                        val rejected = awaitPointerEvent(PointerEventPass.Initial)
                        rejected.changes.forEach { it.consume() }
                    } while (rejected.changes.any { it.pressed })
                    return@awaitEachGesture
                }
                gesture.update(down.position, currentEvent.changes.any { it.id != down.id && it.pressed })
                deformation.pressed = gesture.active
                deformation.touchPosition = down.position
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        gesture.update(change.position, event.changes.any { it.id != down.id && it.pressed })
                        deformation.pressed = gesture.active
                        deformation.dragging = gesture.dragging
                        deformation.touchPosition = change.position
                        if (!change.pressed) {
                            val accepted = change.previousPressed && !change.isConsumed && gesture.acceptsUp(change.position)
                            if (!accepted) change.consume()
                            if (accepted) {
                                GlassReleaseHaptics.confirm(haptics, change.uptimeMillis, change.id.value)
                            }
                            break
                        }
                        val travel = change.position - down.position
                        if (deformation.dragging) {
                            deformation.delta = travel
                            if (consumeDrag) change.consume()
                        }
                        // A parent scroll may consume in Main after our Initial observation.
                        val finalChange = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id }
                        if (finalChange == null || (finalChange.isConsumed && !(consumeDrag && gesture.dragging))) {
                            gesture.cancel()
                            deformation.pressed = false
                        }
                    }
                } finally {
                    deformation.reset()
                }
            }
        }
        // Keep gesture coordinates outside the visual layer, including during Bezier release.
        .graphicsLayer {
            val frame = deformation.visualFrame
            scaleX = frame.scaleX
            scaleY = frame.scaleY
            translationX = frame.translation.x
            translationY = frame.translation.y
        }
        .then(if (drawShadow && (circularHit || state != null))
            Modifier.glassButtonShadow(if (circularHit) CircleShape else RoundedCornerShape(24.dp)) else Modifier)
}






