package com.dafeiyu.mobile.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.Indication
import androidx.compose.animation.core.VectorConverter
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlin.math.abs
import kotlin.math.sqrt

private fun close(actual: Float, expected: Float) {
    check(abs(actual - expected) < .0001f) { "$actual != $expected" }
}

@OptIn(ExperimentalMaterial3Api::class)
private fun checkRippleScope() {
    val applier = object : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
    val recomposer = Recomposer(Dispatchers.Unconfined)
    val composition = Composition(applier, recomposer)
    val inheritedRipple = RippleConfiguration()
    var outsideBefore: Indication? = null
    var inside: Indication? = null
    var outsideAfter: Indication? = null
    var visited = false
    try {
        composition.setContent {
            CompositionLocalProvider(LocalRippleConfiguration provides inheritedRipple) {
                outsideBefore = LocalIndication.current
                check(LocalRippleConfiguration.current === inheritedRipple)
                GlassInteractionProvider {
                    inside = LocalIndication.current
                    check(LocalRippleConfiguration.current == null)
                    visited = true
                }
                outsideAfter = LocalIndication.current
                check(LocalRippleConfiguration.current === inheritedRipple)
            }
        }
        check(visited)
        check(outsideBefore === outsideAfter)
        check(inside !== outsideBefore)
    } finally {
        composition.dispose()
        recomposer.close()
    }
    println("PASS real Compose provider suppresses both ripple paths locally and restores siblings")
}

fun main() {
    close(glassButtonPressScale(true, true, false), 1.035f)
    close(glassButtonPressScale(true, false, false), 1.025f)
    for (circular in listOf(false, true)) {
        close(glassButtonPressScale(true, circular, true), 1f)
        close(glassButtonPressScale(false, circular, false), 1f)
    }
    check(GlassMotion.scalar(reducedMotion = true).durationMillis == 0)
    println("PASS moderate circular enlargement, rectangular scale, rest and reduced motion")

    fun gesture(down: Offset = Offset(24f, 24f), circular: Boolean = true, slop: Float = 8f) =
        GlassButtonGesture(48f, 48f, circular, slop, down)
    check(gesture().acceptsUp(Offset(24f, 24f)))
    check(gesture().acceptsUp(Offset(32f, 24f)))
    check(!gesture().acceptsUp(Offset(32.01f, 24f)))
    check(!gesture().acceptsUp(Offset(30f, 30f)))
    check(!gesture(Offset(3f, 12f)).acceptsUp(Offset(1f, 10f)))
    check(!gesture(Offset(1f, 1f)).acceptsUp(Offset(1f, 1f)))
    check(gesture(Offset(0f, 24f)).acceptsUp(Offset(0f, 24f)))
    check(!gesture(Offset(0f, 24f)).acceptsUp(Offset(-.01f, 24f)))
    check(!gesture(Offset(24f, 24f)).acceptsUp(Offset(Float.NaN, 24f)))
    val exitReturn = gesture(Offset(2f, 24f))
    exitReturn.update(Offset(-1f, 24f))
    check(!exitReturn.acceptsUp(Offset(2f, 24f)))
    val dragReturn = gesture()
    dragReturn.update(Offset(35f, 24f))
    check(!dragReturn.acceptsUp(Offset(24f, 24f)))
    val parentCancelled = gesture()
    parentCancelled.cancel()
    check(!parentCancelled.acceptsUp(Offset(24f, 24f)))
    val multitouch = gesture()
    multitouch.update(Offset(24f, 24f), otherPointerPressed = true)
    check(!multitouch.active)
    check(!multitouch.acceptsUp(Offset(24f, 24f)))
    check(gesture().active)
    check(!dragReturn.active && !exitReturn.active && !parentCancelled.active)
    check(!GlassButtonGesture(0f, 48f, false, 8f, Offset.Zero).acceptsUp(Offset.Zero))
    var boundaryCases = 0
    for (x in -2..50) for (y in -2..50) {
        val point = Offset(x.toFloat(), y.toFloat())
        for (circular in listOf(false, true)) {
            val insideRect = x in 0..48 && y in 0..48
            val distance = sqrt(((x - 24) * (x - 24) + (y - 24) * (y - 24)).toFloat())
            check(gesture(point, circular).acceptsUp(point) == (insideRect && (!circular || distance <= 24f)))
            boundaryCases++
        }
    }
    println("PASS $boundaryCases hit-boundary cases; exact UP, last-UP slop, diagonal drag, sticky cancel")
    println("PASS drag, exit, parent consumption and second pointer immediately end optical press")

    val state = GlassButtonDeformationState()
    state.pressed = true
    state.dragging = true
    state.delta = Offset(10f, 8f)
    state.touchPosition = Offset(30f, 24f)
    state.reset()
    check(!state.pressed && !state.dragging && state.delta == Offset.Zero && state.interactionStrength == 0f)
    println("PASS state cleanup preserves release glow position and clears deformation")

    close(glassGlowCenter(Offset(-8f, 60f), 48f, 48f).x, 0f)
    close(glassGlowCenter(Offset(-8f, 60f), 48f, 48f).y, 48f)
    check(glassGlowCenter(Offset(Float.NaN, Float.POSITIVE_INFINITY), 48f, 48f) == Offset(24f, 24f))
    check(glassGlowCenter(Offset(12f, 18f), 48f, 48f) == Offset(12f, 18f))
    println("PASS pointer glow follows finite in-bounds positions and clamps dragged positions")

    var sampleCases = 0
    for (width in listOf(0, 1, 47, 48, 119, 120, 121, 1440, 4096, Int.MAX_VALUE)) {
        for (height in listOf(0, 1, 48, 120, 121, 3200, Int.MAX_VALUE)) {
            for (refraction in listOf(false, true)) {
                val limit = if (refraction) 120 else 48
                val sample = glassFallbackSampleSize(width, height, refraction)
                check(sample.width in 1..limit && sample.height in 1..limit)
                check(sample.width.toLong() * sample.height * 4 <= limit.toLong() * limit * 4)
                sampleCases++
            }
        }
    }
    println("PASS $sampleCases real fallback sizing cases, including integer extremes and explicit allocation cap")

    for (profile in listOf(GlassMaterialProfile.Button, GlassMaterialProfile.MediaButton)) {
        val dark = profile.edgeAppearance(true)
        val light = profile.edgeAppearance(false)
        check(dark.front > light.front && dark.middle > light.middle && dark.back > light.back)
        check(dark.widthDp >= light.widthDp)
        check(profile.materialTint(Color.White, true).alpha <= profile.darkTintAlpha + .005f)
    }
    close(GlassMaterialProfile.SidebarPanel.edgeAppearance(true).front, .14f)
    close(GlassMaterialProfile.MediaButton.dimAmount, .16f)
    println("PASS bright dark rims keep bounded transmission, quiet sidebar and media readability")

    fun bridge(width: Float = 12f, height: Float = 48f, gap: Float = 12f, strength: Float = 1f,
        reduced: Boolean = false) = liquidBridgeGeometry(width, height, gap, strength, reduced)
    for (strength in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) check(bridge(strength = strength) == null)
    check(bridge(gap = 0f) == null && bridge(height = 0f) == null && bridge(width = 0f) == null)
    check(bridge(gap = Float.NaN) == null && bridge(height = Float.POSITIVE_INFINITY) == null)
    var bridgeCases = 0
    for (width in listOf(1f, 8f, 12f, 80f)) for (height in listOf(1f, 24f, 48f, 96f)) {
        for (gap in listOf(1f, 12f, 100f)) for (strength in listOf(.01f, .25f, .5f, 1f, 2f)) {
            val value = requireNotNull(bridge(width, height, gap, strength))
            check(value.left >= 0f && value.right <= width && value.left < value.right)
            close((value.left + value.right) / 2f, width / 2f)
            check(value.waistHalfHeight > 0f && value.waistHalfHeight < value.endHalfHeight)
            check(value.centerY - value.endHalfHeight > 0f && value.centerY + value.endHalfHeight < height)
            check(value.strength in 0f..1f)
            bridgeCases++
        }
    }
    check(bridge(strength = .01f, reduced = true) == bridge(strength = 1f, reduced = true))
    check(bridge(strength = 0f, reduced = true) == null)
    close(requireNotNull(bridge(height = 96f)).endHalfHeight,
        requireNotNull(bridge(height = 48f)).endHalfHeight * 2f)
    println("PASS $bridgeCases concave bridge geometries, caller height, gap constraints, zero and reduced motion")
    close(liquidBridgeTarget(Float.NaN), 0f)
    close(liquidBridgeTarget(Float.POSITIVE_INFINITY), 0f)
    close(liquidBridgeTarget(-1f), 0f)
    close(liquidBridgeTarget(2f), 1f)
    check(liquidBridgeMotion(1f, false).durationMillis == GlassMotion.PressMillis)
    check(liquidBridgeMotion(0f, false).durationMillis == GlassMotion.ReleaseMillis)
    check(liquidBridgeMotion(1f, true).durationMillis == 0)
    check(liquidBridgeMotion(0f, true).durationMillis == 0)
    for (target in listOf(0f, 1f)) {
        val animation = androidx.compose.animation.core.TargetBasedAnimation(
            liquidBridgeMotion(target, false), Float.VectorConverter,
            if (target == 1f) 0f else 1f, target,
        )
        var previous = if (target == 1f) 0f else 1f
        for (step in 0..100) {
            val value = animation.getValueFromNanos(animation.durationNanos * step / 100)
            check(value in 0f..1f)
            check(if (target == 1f) value >= previous else value <= previous)
            if (value > 0f) {
                val geometry = requireNotNull(bridge(strength = value))
                check(geometry.endHalfHeight <= 48f * .44f * value + .0001f)
            }
            previous = value
        }
        close(previous, target)
    }
    println("PASS real Bezier bridge entrance/release samples; bounded shape collapses even with opaque fill")
    checkLiquidGlassPairCpuBoundaries()
    checkRippleScope()
}
