package com.dafeiyu.mobile.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/** Touch feedback for a glass panel. Only its narrow, dedicated top grip starts a drag. */
@Stable
class GlassDragState internal constructor() {
    var offset by mutableStateOf(Offset.Zero)
        private set
    var active by mutableStateOf(false)
        private set
    var touchPosition by mutableStateOf(Offset.Zero)
        private set
    private var rawOffset = Offset.Zero
    private var returnJob: Job? = null
    val interactionStrength: Float
        get() = if (active) (0.35f + sqrt(offset.x * offset.x + offset.y * offset.y) / 100f).coerceAtMost(1f) else 0f

    internal fun begin(position: Offset) {
        returnJob?.cancel()
        rawOffset = offset
        touchPosition = position
        active = true
    }

    internal fun move(delta: Offset, position: Offset, maxX: Float, maxY: Float, reduceMotion: Boolean) {
        touchPosition = position
        rawOffset += delta
        // Resistance increases near the edge rather than stopping abruptly at a hard clamp.
        fun resist(value: Float, limit: Float) = limit * value / (abs(value) + limit)
        offset = if (reduceMotion) Offset.Zero else Offset(resist(rawOffset.x, maxX), resist(rawOffset.y, maxY))
    }

    internal fun release(scope: CoroutineScope, reduceMotion: Boolean) {
        active = false
        returnJob?.cancel()
        if (reduceMotion) {
            offset = Offset.Zero
            rawOffset = Offset.Zero
            return
        }
        returnJob = scope.launch {
            Animatable(offset, Offset.VectorConverter).animateTo(
                Offset.Zero,
                animationSpec = tween(440, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)),
            ) { offset = value }
            rawOffset = Offset.Zero
        }
    }
}

@Composable
fun rememberGlassDragState(): GlassDragState = remember { GlassDragState() }

/** Does not consume a tap or a gesture starting in text fields, buttons, sliders, or lists. */
internal fun Modifier.glassPanelDrag(
    enabled: Boolean,
    state: GlassDragState,
    scope: CoroutineScope,
    gripHeight: Float,
    maxX: Float,
    maxY: Float,
    reduceMotion: Boolean,
): Modifier = if (!enabled) this else pointerInput(state, gripHeight, maxX, maxY, reduceMotion) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.isConsumed || down.position.y < 0f || down.position.y > gripHeight) return@awaitEachGesture
        val start = awaitTouchSlopOrCancellation(down.id) { change, overSlop ->
            state.begin(change.position)
            state.move(overSlop, change.position, maxX, maxY, reduceMotion)
            change.consume()
        } ?: return@awaitEachGesture
        try {
            drag(start.id) { change ->
                state.move(change.positionChange(), change.position, maxX, maxY, reduceMotion)
                change.consume()
            }
        } finally {
            state.release(scope, reduceMotion)
        }
    }
}
