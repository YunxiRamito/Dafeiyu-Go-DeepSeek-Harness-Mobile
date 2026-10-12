package com.dafeiyu.mobile.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

fun main() {
    var passed = 0
    fun test(name: String, body: () -> Unit) {
        body()
        passed++
        println("PASS $name")
    }
    test("one continuous first swipe never grants delete even if archive settles before UP") {
        val gate = ConversationSwipeGate()
        check(!gate.begin(ConversationSwipe.Closed, false, false))
        val drag = Any()
        gate.startDrag(drag)
        gate.release(gate.generation, true)
        gate.stopDrag(drag)
        check(!gate.consumeDelete(true))
    }
    test("second swipe requires settled archive, UP and revealed identity") {
        val gate = ConversationSwipeGate()
        check(gate.begin(ConversationSwipe.Archive, false, true))
        check(!gate.consumeDelete(true))
        val drag = Any()
        gate.startDrag(drag)
        gate.release(gate.generation, true)
        check(!gate.consumeDelete(true))
        check(gate.stopDrag(drag) == gate.generation)
        check(!gate.consumeDelete(false))
        check(gate.consumeDelete(true))
        check(!gate.consumeDelete(true))
    }
    test("DOWN during settling does not qualify as gesture two") {
        val gate = ConversationSwipeGate()
        check(!gate.begin(ConversationSwipe.Archive, true, true))
        gate.release(gate.generation, true)
        val drag = Any()
        gate.startDrag(drag)
        gate.stopDrag(drag)
        check(!gate.consumeDelete(true))
    }
    test("cancel after release, pointer cancellation and switching row revoke deletion") {
        for (releaseBeforeCancel in listOf(false, true)) {
            val gate = ConversationSwipeGate()
            check(gate.begin(ConversationSwipe.Archive, false, true))
            val drag = Any()
            gate.startDrag(drag)
            if (releaseBeforeCancel) gate.release(gate.generation, true)
            gate.cancel()
            gate.release(gate.generation, true)
            gate.stopDrag(drag)
            check(!gate.consumeDelete(true))
        }
    }
    test("new gesture never reuses a prior UP permission") {
        val gate = ConversationSwipeGate()
        check(gate.begin(ConversationSwipe.Archive, false, true))
        val oldDrag = Any()
        gate.startDrag(oldDrag)
        gate.release(gate.generation, true)
        gate.stopDrag(oldDrag)
        check(gate.begin(ConversationSwipe.Archive, false, true))
        check(!gate.consumeDelete(true))
        val drag = Any()
        gate.startDrag(drag)
        gate.release(gate.generation, true)
        gate.stopDrag(drag)
        check(gate.consumeDelete(true))
    }
    test("row gates remain independent and consume at most one deletion") {
        val first = ConversationSwipeGate()
        val second = ConversationSwipeGate()
        first.begin(ConversationSwipe.Archive, false, true)
        second.begin(ConversationSwipe.Closed, false, false)
        val firstDrag = Any()
        val secondDrag = Any()
        first.startDrag(firstDrag)
        second.startDrag(secondDrag)
        first.release(first.generation, true)
        second.release(second.generation, true)
        first.stopDrag(firstDrag)
        second.stopDrag(secondDrag)
        check(!second.consumeDelete(true))
        check(first.consumeDelete(true))
        check(!first.consumeDelete(true))
    }
    test("UP below threshold cannot delete even when velocity settles at Delete") {
        val gate = ConversationSwipeGate()
        gate.begin(ConversationSwipe.Archive, false, true)
        val drag = Any()
        gate.startDrag(drag)
        gate.release(gate.generation, false)
        gate.stopDrag(drag)
        check(!gate.consumeDelete(true))
    }
    test("Stop before UP and UP before Stop both require both permissions") {
        for (stopFirst in listOf(false, true)) {
            val gate = ConversationSwipeGate()
            gate.begin(ConversationSwipe.Archive, false, true)
            val drag = Any()
            gate.startDrag(drag)
            if (stopFirst) gate.stopDrag(drag) else gate.release(gate.generation, true)
            check(!gate.consumeDelete(true))
            if (stopFirst) gate.release(gate.generation, true) else gate.stopDrag(drag)
            check(gate.consumeDelete(true))
        }
    }
    test("old Stop Cancel and UP never complete or revoke the next gesture") {
        for (oldCancel in listOf(false, true)) {
            val gate = ConversationSwipeGate()
            gate.begin(ConversationSwipe.Archive, false, true)
            val oldGeneration = gate.generation
            val oldDrag = Any()
            gate.startDrag(oldDrag)
            gate.release(oldGeneration, true)
            gate.begin(ConversationSwipe.Archive, false, true)
            gate.release(oldGeneration, true)
            check(if (oldCancel) gate.cancelDrag(oldDrag) == null else gate.stopDrag(oldDrag) == null)
            check(!gate.consumeDelete(true))
            check(!gate.cancelled)
            val drag = Any()
            gate.startDrag(drag)
            gate.release(gate.generation, true)
            gate.stopDrag(drag)
            check(gate.consumeDelete(true))
        }
    }
    test("old drag token cannot cancel or stop a replacement drag") {
        val gate = ConversationSwipeGate()
        gate.begin(ConversationSwipe.Archive, false, true)
        val oldDrag = Any()
        gate.startDrag(oldDrag)
        gate.begin(ConversationSwipe.Archive, false, true)
        val drag = Any()
        gate.startDrag(drag)
        gate.release(gate.generation, true)
        check(gate.cancelDrag(oldDrag) == null && gate.stopDrag(oldDrag) == null)
        check(!gate.consumeDelete(true))
        gate.stopDrag(drag)
        check(gate.consumeDelete(true))
    }
    test("Cancel wins before settling regardless of Stop UP ordering") {
        for (releaseFirst in listOf(false, true)) {
            val gate = ConversationSwipeGate()
            gate.begin(ConversationSwipe.Archive, false, true)
            val drag = Any()
            gate.startDrag(drag)
            if (releaseFirst) gate.release(gate.generation, true)
            check(gate.cancelDrag(drag) == gate.generation)
            check(gate.cancelled && gate.startedAtArchive)
            gate.release(gate.generation, true)
            check(gate.stopDrag(drag) == null)
            check(!gate.consumeDelete(true))
        }
    }
    test("second DOWN away from archive or on hidden row never qualifies") {
        val gate = ConversationSwipeGate()
        check(!gate.begin(ConversationSwipe.Archive, false, true, atArchive = false))
        check(!gate.begin(ConversationSwipe.Archive, false, false))
        check(!gate.begin(ConversationSwipe.Delete, false, true))
    }
    test("first swipe Cancel cannot expose archive from a late settle") {
        val gate = ConversationSwipeGate()
        gate.begin(ConversationSwipe.Closed, false, false)
        val drag = Any()
        gate.startDrag(drag)
        gate.cancelDrag(drag)
        check(gate.cancelled && !gate.startedAtArchive)
        gate.release(gate.generation, true)
        check(!gate.consumeDelete(true))
    }
    test("all light and dark theme accents remain opaque and readable with white text") {
        val accents = listOf(
            Color(0xFF007AFF), Color(0xFF64B5FF), Color(0xFF6D3BFF), Color(0xFFB69CFF),
            Color(0xFF168A4A), Color(0xFF62D79A), Color(0xFFE26D00), Color(0xFFFFB65C),
            Color(0xFFD81B48), Color(0xFFFF8DA5), Color.White, Color.Black,
        )
        for (accent in accents) {
            val result = workspaceSelectedColor(accent)
            check(result.alpha == 1f)
            check(1.05f / (result.luminance() + .05f) >= 4.5f)
            check(result.red <= accent.red && result.green <= accent.green && result.blue <= accent.blue)
        }
    }
    println("Workspace swipe/color production helper checks: $passed PASS")
    println("Extracted real Kotlin helpers; pointer dispatch, hit testing and rendered pixels need app/device QA.")
}
