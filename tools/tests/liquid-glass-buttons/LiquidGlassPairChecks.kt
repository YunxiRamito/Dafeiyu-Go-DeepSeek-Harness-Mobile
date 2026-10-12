package com.dafeiyu.mobile.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs

private fun pairClose(actual: Float, expected: Float) {
    check(abs(actual - expected) < .001f) { "$actual != $expected" }
}

private fun pairOffset(actual: Offset, expected: Offset) {
    pairClose(actual.x, expected.x)
    pairClose(actual.y, expected.y)
}

/** Execute verbatim production CPU spans, not a Kotlin imitation of the AGSL field. */
internal fun checkLiquidGlassPairCpuBoundaries() {
    val state = GlassButtonDeformationState()
    check(state.visualFrame == GlassButtonVisualFrame())
    val visual = GlassButtonVisualFrame(1.2f, .8f, Offset(4f, -3f), .4f)
    state.visualFrame = visual
    // Gesture reset must leave the animated release frame available to the optical surface.
    state.pressed = true
    state.delta = Offset(30f, 20f)
    state.reset()
    check(state.visualFrame == visual && state.interactionStrength == 0f)

    var transformCases = 0
    for (density in listOf(.75f, 1f, 2f, 3f)) {
        val center = Offset(40f * density, 40f * density)
        for (touch in listOf(Offset(24f, 24f), Offset(48f, 0f), Offset(-8f, 60f))) {
            state.touchPosition = touch * density
            val mapped = CurrentPairCpuHelpers.map(density, center, state, state.visualFrame, .25f)
            pairOffset(mapped.center, center + Offset(4f, -3f))
            val landmark = when (touch) {
                Offset(24f, 24f) -> Offset(40f, 40f)
                Offset(48f, 0f) -> Offset(68.8f, 20.8f)
                else -> Offset(11.2f, 59.2f)
            }
            pairOffset(mapped.touch, landmark * density + Offset(4f, -3f))
            pairClose(mapped.radius, 24f * density)
            pairClose(mapped.scaleX, visual.scaleX)
            pairClose(mapped.scaleY, visual.scaleY)
            pairClose(mapped.press, .4f)
            pairClose(mapped.alpha, .25f)
            // Relocating the pair host moves both material and touch, preserving local optics.
            val moved = CurrentPairCpuHelpers.map(density, center + Offset(117f, -41f), state, visual, .25f)
            pairOffset(moved.center - mapped.center, Offset(117f, -41f))
            pairOffset(moved.touch - mapped.touch, Offset(117f, -41f))
            transformCases++
        }
        state.touchPosition = Offset(Float.NaN, Float.POSITIVE_INFINITY)
        val fallbackTouch = CurrentPairCpuHelpers.map(density, center, state, visual, 1f)
        pairOffset(fallbackTouch.touch, fallbackTouch.center)
        // Shared visual enlargement cannot expand the original circular hit target into the gap.
        val originalHit = GlassButtonGesture(48f * density, 48f * density, true,
            8f * density, Offset(24f, 24f) * density)
        check(originalHit.contains(Offset(48f, 24f) * density))
        check(!originalHit.contains(Offset(48.01f, 24f) * density))
        check(!originalHit.contains(Offset(48f, 0f) * density))
    }
    println("PASS $transformCases verbatim pair transform cases: density, anisotropic landmarks, release, host translation and original hit bounds")

    val valid = GlassPairShape(Offset(40f, 40f), 24f, 1.2f, .8f, .4f, Offset(68.8f, 20.8f), .25f)
    check(CurrentPairCpuHelpers.sanitize(valid, 1024f) == valid)
    var alphaCases = 0
    for ((input, expected) in listOf(-1f to 0f, 0f to 0f, .001f to .001f, .25f to .25f,
        .5f to .5f, 1f to 1f, 2f to 1f, Float.NaN to 0f,
        Float.POSITIVE_INFINITY to 0f, Float.NEGATIVE_INFINITY to 0f)) {
        val sanitized = CurrentPairCpuHelpers.sanitize(valid.copy(alpha = input), 1024f)
        pairClose(sanitized.alpha, expected)
        check(sanitized.copy(alpha = valid.alpha) == valid)
        alphaCases++
    }
    for (invalid in listOf(valid.copy(center = Offset(Float.NaN, 40f)),
        valid.copy(radius = 0f), valid.copy(radius = -1f), valid.copy(radius = Float.POSITIVE_INFINITY),
        valid.copy(scaleX = 0f), valid.copy(scaleY = -1f), valid.copy(scaleX = Float.NaN))) {
        val hidden = CurrentPairCpuHelpers.sanitize(invalid, 1024f)
        check(hidden.alpha == 0f && hidden.center == Offset.Zero && hidden.radius > 0f)
        check(hidden.scaleX > 0f && hidden.scaleY > 0f && hidden.press == 0f)
    }
    for (press in listOf(-1f, 0f, .4f, 1f, 2f, Float.NaN, Float.POSITIVE_INFINITY)) {
        val sanitized = CurrentPairCpuHelpers.sanitize(valid.copy(press = press), 1024f)
        check(sanitized.press in 0f..1f)
        if (!press.isFinite() || press < 0f) check(sanitized.press == 0f)
        if (press > 1f && press.isFinite()) check(sanitized.press == 1f)
    }
    val noTouch = CurrentPairCpuHelpers.sanitize(valid.copy(touch = Offset(Float.NaN, 3f)), 1024f)
    check(noTouch.press == 0f && noTouch.touch == valid.center && noTouch.alpha == valid.alpha)
    val extreme = CurrentPairCpuHelpers.sanitize(valid.copy(center = Offset(Float.MAX_VALUE, -Float.MAX_VALUE),
        radius = Float.MAX_VALUE, scaleX = .001f, scaleY = Float.MAX_VALUE,
        touch = Offset(-Float.MAX_VALUE, Float.MAX_VALUE)), 1024f)
    check(extreme.center == Offset(1024f, -1024f) && extreme.touch == Offset(-1024f, 1024f))
    check(extreme.radius == 1024f && extreme.scaleX == .05f && extreme.scaleY == 8f)
    println("PASS $alphaCases verbatim alpha sanitation boundaries; invalid shapes disappear and invalid touches disable optical press")

    // Optics use the transformed short edge, invariant to translation, alpha and axis exchange.
    val optics = CurrentPairCpuHelpers.optics(valid, 1f)
    check(CurrentPairCpuHelpers.optics(valid.copy(scaleX = valid.scaleY, scaleY = valid.scaleX), 1f) == optics)
    check(CurrentPairCpuHelpers.optics(valid.copy(center = Offset(500f, 300f), alpha = 0f), 1f) == optics)
    check(optics.bevel > 0f && optics.refraction > 0f && optics.hairWidth > 0f)
    check(CurrentPairCpuHelpers.optics(valid.copy(scaleX = .5f, scaleY = .5f), 1f).refraction < optics.refraction)

    // Capture intersections are in recording coordinates: margin is applied exactly once.
    check(glassSampleBounds(136, 80, 10, 10f, 10f, 500, 500) == Rect(11f, 11f, 145f, 89f))
    check(glassSampleBounds(136, 80, 10, 25f, -20f, 100, 70) == Rect(25.5f, 11f, 124.5f, 49.5f))
    check(glassSampleBounds(136, 80, 10, 300f, 10f, 100, 70) == null)
    check(glassSampleBounds(136, 80, 10, 10f, -100f, 100, 70) == null)
    check(glassSampleBounds(1, 1, 10, 10f, 10f, 100, 100) == Rect(10.5f, 10.5f, 10.75f, 10.75f))
    check(glassSampleBounds(1, 1, 10, 10f, 10f, 1, 1) == Rect(10.5f, 10.5f, 10.5f, 10.5f))
    println("PASS real short-edge optics invariants and six recording-coordinate sample intersections; AGSL rendering remains device-only")
}
