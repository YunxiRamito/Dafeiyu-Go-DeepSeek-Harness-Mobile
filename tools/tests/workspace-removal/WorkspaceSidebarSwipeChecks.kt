package com.dafeiyu.mobile.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs

private var passed = 0

private fun scenario(name: String, body: () -> Unit) {
    try {
        body()
        passed++
        println("PASS $name")
    } catch (failure: Throwable) {
        throw AssertionError("FAIL $name: ${failure.message}", failure)
    }
}

private fun near(expected: Float, actual: Float, tolerance: Float = .002f) {
    check(abs(expected - actual) <= tolerance) { "Expected $expected, got $actual" }
}

private fun eligibleGate(): SidebarSwipeGate = SidebarSwipeGate().also {
    check(it.begin(SidebarSwipe.Revealed, animating = false, revealed = true))
}

private fun SidebarSwipeGate.finish(threshold: Boolean = true): Any {
    val token = Any()
    startDrag(token)
    release(generation, threshold)
    check(stopDrag(token) == generation)
    return token
}

fun main() {
    scenario("first long gesture cannot commit, even if it settles at Revealed before UP") {
        val gate = SidebarSwipeGate()
        check(!gate.begin(SidebarSwipe.Closed, animating = false, revealed = false))
        gate.finish(threshold = true)
        check(!gate.consumeCommit(revealed = true))
    }
    scenario("two separate gestures are required, and only second release commits once") {
        val gate = SidebarSwipeGate()
        check(!gate.begin(SidebarSwipe.Closed, false, false))
        gate.finish()
        check(!gate.consumeCommit(true))
        check(gate.begin(SidebarSwipe.Revealed, false, true))
        gate.finish()
        check(gate.consumeCommit(true))
        check(!gate.consumeCommit(true))
    }
    scenario("UP threshold alone without matching Drag Stop cannot commit") {
        val gate = eligibleGate()
        gate.startDrag(Any())
        gate.release(gate.generation, true)
        check(!gate.consumeCommit(true))
    }
    scenario("Drag Stop alone without a real threshold UP cannot commit") {
        val gate = eligibleGate()
        val token = Any()
        gate.startDrag(token)
        check(gate.stopDrag(token) == gate.generation)
        check(!gate.consumeCommit(true))
    }
    scenario("UP before Stop commits only after matching stop arrives") {
        val gate = eligibleGate()
        val token = Any()
        gate.startDrag(token)
        gate.release(gate.generation, true)
        check(!gate.consumeCommit(true))
        gate.stopDrag(token)
        check(gate.consumeCommit(true))
    }
    scenario("Stop before Final-pass UP also commits once") {
        val gate = eligibleGate()
        val token = Any()
        gate.startDrag(token)
        gate.stopDrag(token)
        check(!gate.consumeCommit(true))
        gate.release(gate.generation, true)
        check(gate.consumeCommit(true))
    }
    scenario("fling crosses visual threshold after false physical UP without permission") {
        val gate = eligibleGate()
        gate.finish(threshold = false)
        check(!gate.consumeCommit(true))
    }
    scenario("drag above threshold then retreat before UP does not commit") {
        val geometry = SidebarSwipeGeometry(268f, 84f, false)
        check(geometry.thresholdReached(-260f))
        val gate = eligibleGate()
        gate.finish(threshold = geometry.thresholdReached(-100f))
        check(!gate.consumeCommit(true))
    }
    scenario("DOWN during settling has no second-stage permission") {
        val gate = SidebarSwipeGate()
        check(!gate.begin(SidebarSwipe.Revealed, animating = true, revealed = true))
        gate.finish()
        check(!gate.consumeCommit(true))
    }
    scenario("DOWN at an unsettled physical reveal offset has no permission") {
        val gate = SidebarSwipeGate()
        check(!gate.begin(SidebarSwipe.Revealed, false, true, atReveal = false))
        gate.finish()
        check(!gate.consumeCommit(true))
    }
    scenario("unrevealed identity cannot acquire or later revive permission") {
        val gate = SidebarSwipeGate()
        check(!gate.begin(SidebarSwipe.Revealed, false, revealed = false))
        gate.finish()
        check(!gate.consumeCommit(true))
    }
    scenario("another row becoming revealed revokes pending commit") {
        val gate = eligibleGate()
        gate.finish()
        check(!gate.consumeCommit(revealed = false))
    }
    scenario("multitouch cancellation remains sticky even after extra pointer lifts") {
        val gate = eligibleGate()
        val token = Any()
        gate.startDrag(token)
        gate.cancel()
        gate.release(gate.generation, true)
        gate.stopDrag(token)
        check(gate.cancelled)
        check(!gate.consumeCommit(true))
    }
    scenario("Drag Cancel after a threshold UP cannot commit") {
        val gate = eligibleGate()
        val token = Any()
        gate.startDrag(token)
        gate.release(gate.generation, true)
        check(gate.cancelDrag(token) == gate.generation)
        check(!gate.consumeCommit(true))
    }
    scenario("unknown token cannot stop or cancel a real drag") {
        val gate = eligibleGate()
        val token = Any()
        gate.startDrag(token)
        gate.release(gate.generation, true)
        check(gate.stopDrag(Any()) == null)
        check(gate.cancelDrag(Any()) == null)
        check(!gate.consumeCommit(true))
        gate.stopDrag(token)
        check(gate.consumeCommit(true))
    }
    scenario("old generation UP and Stop cannot apply to a new DOWN") {
        val gate = eligibleGate()
        val previousGeneration = gate.generation
        val oldToken = Any()
        gate.startDrag(oldToken)
        check(gate.begin(SidebarSwipe.Revealed, false, true))
        gate.release(previousGeneration, true)
        check(gate.stopDrag(oldToken) == null)
        check(!gate.consumeCommit(true))
        gate.finish()
        check(gate.consumeCommit(true))
    }
    scenario("old Stop after a new Start never completes the new drag") {
        val gate = eligibleGate()
        val oldToken = Any()
        gate.startDrag(oldToken)
        check(gate.begin(SidebarSwipe.Revealed, false, true))
        val newToken = Any()
        gate.startDrag(newToken)
        gate.release(gate.generation, true)
        check(gate.stopDrag(oldToken) == null)
        check(!gate.consumeCommit(true))
        gate.stopDrag(newToken)
        check(gate.consumeCommit(true))
    }
    scenario("protection disabled on DOWN blocks entire gesture") {
        val gate = SidebarSwipeGate()
        check(!gate.begin(SidebarSwipe.Revealed, false, true, actionEnabled = false))
        gate.finish()
        check(!gate.consumeCommit(true, actionEnabled = true))
    }
    scenario("protection changing before dispatch prevents commit") {
        val gate = eligibleGate()
        gate.finish()
        check(!gate.consumeCommit(true, actionEnabled = false))
    }
    scenario("protection cancellation cannot be revived by storage becoming ready again") {
        val gate = eligibleGate()
        gate.cancel()
        gate.finish()
        check(!gate.consumeCommit(true, actionEnabled = true))
    }
    scenario("Archive dispatch uses exact captured ID and calls archive only") {
        val identity = SidebarSwipeIdentity("conversation-42", SidebarSwipeAction.Archive)
        val archives = mutableListOf<String>()
        check(identity.perform({ archives.add(it); true },
            { error("Archive must not query workspace delete") },
            { error("Archive must not request deletion") }))
        check(archives == listOf("conversation-42"))
    }
    scenario("rejected archive returns false for a safe rebound") {
        check(!SidebarSwipeIdentity("c", SidebarSwipeAction.Archive).perform({ false },
            { error("Wrong action") }, { error("Wrong action") }))
    }
    scenario("workspace action only requests confirmation with exact captured ID") {
        val identity = SidebarSwipeIdentity("workspace-original", SidebarSwipeAction.DeleteEmptyWorkspace)
        val protected = mutableListOf<String>()
        val confirmations = mutableListOf<String>()
        check(identity.perform({ error("Workspace must not archive") },
            { protected.add(it); true }, { confirmations.add(it); true }))
        check(protected == listOf("workspace-original"))
        check(confirmations == listOf("workspace-original"))
    }
    scenario("unknown workspace state cannot reach removal confirmation") {
        check(!SidebarSwipeIdentity("storage-unknown", SidebarSwipeAction.DeleteEmptyWorkspace).perform(
            { error("Wrong action") }, { false }, { error("Protected workspace must not reach confirmation") }))
    }
    scenario("late protection check blocks a previously eligible gate from opening confirmation") {
        val gate = eligibleGate()
        gate.finish()
        check(gate.consumeCommit(true))
        check(!SidebarSwipeIdentity("became-unavailable", SidebarSwipeAction.DeleteEmptyWorkspace).perform(
            { error("Wrong action") }, { false }, { error("Protection must be rechecked") }))
    }
    scenario("same String ID for distinct row kinds never confuses action identity") {
        check(SidebarSwipeIdentity("shared", SidebarSwipeAction.Archive) !=
            SidebarSwipeIdentity("shared", SidebarSwipeAction.DeleteEmptyWorkspace))
    }
    scenario("LTR first reveal is bounded and exposes a complete 76px capsule plus safe inset") {
        val geometry = SidebarSwipeGeometry(268f, 84f, false)
        near(-84f, geometry.revealOffset)
        near(76f, geometry.actionWidth(geometry.revealOffset, 76f))
        near(0f, geometry.extension(geometry.revealOffset))
        near(-84f, geometry.visualOffset(geometry.revealOffset))
        check(!geometry.thresholdReached(geometry.revealOffset))
        val bodyRight = geometry.width + geometry.visualOffset(geometry.revealOffset)
        val capsuleLeft = geometry.width - geometry.actionWidth(geometry.revealOffset, 76f) - 4f
        check(capsuleLeft - bodyRight >= 4f)
    }
    scenario("physical threshold yields full-width capsule and hides original body in the same frame") {
        val geometry = SidebarSwipeGeometry(268f, 84f, false)
        val physical = -geometry.commitDistance
        check(geometry.thresholdReached(physical))
        near(1f, geometry.extension(physical))
        near(geometry.width, geometry.actionWidth(physical, 76f))
        near(-geometry.width, geometry.visualOffset(physical))
    }
    scenario("below threshold cannot become a full capsule and uses resistance") {
        val geometry = SidebarSwipeGeometry(268f, 84f, false)
        val physical = -(geometry.revealDistance + (geometry.commitDistance - geometry.revealDistance) * .5f)
        check(!geometry.thresholdReached(physical))
        check(geometry.extension(physical) < .5f)
        check(geometry.actionWidth(physical, 76f) < geometry.width)
        check(abs(geometry.visualOffset(physical)) < geometry.width)
    }
    scenario("RTL mirrors drag, full capsule, and physical threshold exactly") {
        val ltr = SidebarSwipeGeometry(268f, 84f, false)
        val rtl = SidebarSwipeGeometry(268f, 84f, true)
        for (distance in listOf(0f, 40f, 84f, 130f, 220f, 268f)) {
            near(ltr.distance(-distance), rtl.distance(distance))
            near(-ltr.visualOffset(-distance), rtl.visualOffset(distance))
            near(ltr.actionWidth(-distance, 76f), rtl.actionWidth(distance, 76f))
            check(ltr.thresholdReached(-distance) == rtl.thresholdReached(distance))
        }
        check(!rtl.thresholdReached(-268f))
        near(0f, rtl.distance(-268f))
    }
    scenario("real narrow measured width never becomes a 240px fallback") {
        val geometry = SidebarSwipeGeometry(180f, 84f, false)
        near(180f, geometry.width)
        near(180f, geometry.actionWidth(-geometry.commitDistance, 76f))
        near(-180f, geometry.visualOffset(-geometry.commitDistance))
    }
    scenario("extremely narrow row keeps all geometry within measured width") {
        val geometry = SidebarSwipeGeometry(64f, 84f, false)
        near(64f, geometry.width)
        near(63f, geometry.revealDistance)
        check(geometry.actionWidth(geometry.revealOffset, 76f) <= geometry.width)
    }
    scenario("non-finite or opposite drag does not grant threshold permission") {
        val geometry = SidebarSwipeGeometry(268f, 84f, false)
        for (offset in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 50f)) {
            check(!geometry.thresholdReached(offset))
            near(0f, geometry.distance(offset))
        }
    }
    scenario("pixel density changes preserve normalized geometry and complete capsule") {
        val one = SidebarSwipeGeometry(268f, 84f, false)
        val scaled = SidebarSwipeGeometry(670f, 210f, false)
        for (distance in listOf(84f, 120f, 160f, one.commitDistance, 268f)) {
            near(one.extension(-distance), scaled.extension(-distance * 2.5f))
            near(one.actionWidth(-distance, 76f), scaled.actionWidth(-distance * 2.5f, 190f) / 2.5f)
        }
    }
    scenario("light selected color keeps hue proportions, opacity and white text contrast") {
        val accent = Color(.3f, .68f, 1f, .3f)
        val actual = workspaceSelectedColor(accent)
        near(1f, actual.alpha)
        check(1.05f / (actual.luminance() + .05f) >= 4.499f)
        near(accent.red / accent.blue, actual.red / actual.blue)
        near(accent.green / accent.blue, actual.green / actual.blue)
    }
    println("RESULT $passed/$passed production-helper scenarios passed")
}
