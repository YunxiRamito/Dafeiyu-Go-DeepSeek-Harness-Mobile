package com.dafeiyu.mobile.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import java.text.BreakIterator
import java.util.Locale

/**
 * Keep the received text in layout/selection; only color spans change during reveal.
 * The saved frontier advances across deltas, rather than replaying the message.
 */
@Composable
internal fun StreamRevealText(
    text: String,
    streaming: Boolean,
    contentKey: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val frontier = rememberStreamRevealFrontier(text, streaming, contentKey)
    val boundaries = remember(text) { streamCharacterBoundaries(text) }
    val revealedText = streamRevealAnnotatedText(text, frontier, color, boundaries)
    Text(revealedText, modifier = modifier, style = style, color = color)
}

internal fun initialStreamRevealFrontier(
    text: String,
    streaming: Boolean,
    reducedMotion: Boolean,
): Float = if (streaming && !reducedMotion) 0f else text.length.toFloat()

@Composable
internal fun rememberStreamRevealFrontier(
    text: String,
    streaming: Boolean,
    contentKey: String,
): Float {
    val reducedMotion = LocalReduceGlassMotion.current
    var frontier by rememberSaveable(contentKey) {
        mutableFloatStateOf(initialStreamRevealFrontier(text, streaming, reducedMotion))
    }
    var observedText by remember(contentKey) { mutableStateOf(text) }
    val latestText by rememberUpdatedState(text)
    val replacement = !text.startsWith(observedText)
    val immediate = reducedMotion || !streaming || replacement
    val displayFrontier = streamRevealFrontierAfterAppend(observedText, text, frontier)

    // Token updates do not cancel the frame loop. It reads the newest target each frame.
    LaunchedEffect(contentKey, streaming, reducedMotion) {
        if (!streaming || reducedMotion) {
            snapshotFlow { latestText }.collect {
                observedText = it
                frontier = it.length.toFloat()
            }
        } else {
            snapshotFlow { latestText }.collect {
                var previousFrame = withFrameNanos { it }
                var speed = 20f
                do {
                    val current = latestText
                    frontier = streamRevealFrontierAfterAppend(observedText, current, frontier)
                    observedText = current
                    if (frontier >= current.length) break
                    val frame = withFrameNanos { it }
                    val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, .05f)
                    previousFrame = frame
                    val remaining = latestText.length - frontier
                    speed = maxOf(speed, remaining / (GlassMotion.MorphMillis / 1_000f))
                    frontier = minOf(latestText.length.toFloat(), frontier + speed * seconds)
                } while (true)
            }
        }
    }

    return if (immediate) text.length.toFloat() else displayFrontier
}

/** A combining suffix must not dim a character that was already fully visible. */
internal fun streamRevealFrontierAfterAppend(previous: String, text: String, frontier: Float): Float {
    if (text == previous) return frontier
    if (!text.startsWith(previous)) return text.length.toFloat()
    if (previous.isEmpty()) return frontier
    val oldBoundaries = streamCharacterBoundaries(previous)
    val oldIndex = oldBoundaries.binarySearch(frontier.toInt().coerceIn(0, previous.length))
    val settled = oldBoundaries[if (oldIndex >= 0) oldIndex else (-oldIndex - 2).coerceAtLeast(0)]
    val boundaries = streamCharacterBoundaries(text)
    val index = boundaries.binarySearch(settled)
    return if (index >= 0) frontier else maxOf(frontier, boundaries[-index - 1].toFloat())
}

internal fun streamRevealAnnotatedText(
    text: String,
    frontier: Float,
    color: Color,
    boundaries: IntArray = streamCharacterBoundaries(text),
) = buildAnnotatedString {
    append(text)
    if (frontier < text.length) {
        val index = boundaries.binarySearch(frontier.toInt())
        val first = if (index >= 0) index else (-index - 2).coerceAtLeast(0)
        if (first < boundaries.lastIndex) {
            val start = boundaries[first]
            val end = boundaries[first + 1]
            val alpha = ((frontier - start) / (end - start)).coerceIn(0f, 1f)
            addStyle(SpanStyle(color = color.copy(alpha = color.alpha * alpha)), start, end)
            if (end < text.length) addStyle(SpanStyle(color = Color.Transparent), end, text.length)
        }
    }
}

/** Character boundaries prevent transparency spans from splitting surrogate/combining sequences. */
internal fun streamCharacterBoundaries(text: String): IntArray {
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(text)
    val boundaries = mutableListOf<Int>()
    var boundary = iterator.first()
    while (boundary != BreakIterator.DONE) {
        boundaries += boundary
        boundary = iterator.next()
    }
    return boundaries.toIntArray()
}
