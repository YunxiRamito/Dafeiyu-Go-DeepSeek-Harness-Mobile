package com.dafeiyu.mobile.ui

import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.saveable.SaverScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

fun main() {
    var checks = 0
    fun scenario(name: String, block: () -> Unit) {
        block()
        checks++
        println("PASS $name")
    }

    scenario("send completion without a newer intent resumes following") {
        val intent = ChatScrollIntent(false)
        intent.acceptSend(intent.revision)
        check(intent.followingLatest)
    }
    scenario("pointer down beats persistence completion before drag sampling") {
        val intent = ChatScrollIntent()
        val submitted = intent.revision
        intent.beginGesture()
        intent.acceptSend(submitted)
        check(!intent.followingLatest && intent.pointerPressed)
    }
    scenario("stream growth and IME remeasure cannot settle an active drag or fling") {
        val intent = ChatScrollIntent()
        intent.beginGesture()
        intent.settle(isDragging = false, isScrolling = false, awayFromLatest = true)
        check(!intent.followingLatest)
        intent.endGesture()
        intent.settle(isDragging = false, isScrolling = true, awayFromLatest = false)
        check(!intent.followingLatest)
        intent.settle(isDragging = false, isScrolling = false, awayFromLatest = true)
        check(!intent.followingLatest)
    }
    scenario("saved send cannot steal a settled manual history position") {
        val intent = ChatScrollIntent()
        val submitted = intent.revision
        intent.beginGesture()
        intent.endGesture()
        intent.settle(false, false, true)
        intent.acceptSend(submitted)
        check(!intent.followingLatest)
    }
    scenario("manual return to latest only follows once the gesture and fling end") {
        val intent = ChatScrollIntent(false)
        intent.beginGesture()
        intent.settle(true, true, false)
        check(!intent.followingLatest)
        intent.endGesture()
        intent.settle(false, true, false)
        check(!intent.followingLatest)
        intent.settle(false, false, false)
        check(intent.followingLatest)
    }
    scenario("click holds following until animation reaches latest") {
        val intent = ChatScrollIntent(false)
        val request = intent.requestReturn()
        check(!intent.followingLatest && intent.returningLatest)
        intent.finishReturn(request, true)
        intent.finishReturn(request, false)
        check(intent.followingLatest && !intent.returningLatest)
    }
    scenario("touch interrupts animation and stale completion cannot restore following") {
        val intent = ChatScrollIntent(false)
        val request = intent.requestReturn()
        intent.beginGesture()
        intent.finishReturn(request, true)
        intent.finishReturn(request, false)
        check(!intent.followingLatest && !intent.returningLatest)
        intent.endGesture()
        intent.settle(false, false, true)
        check(!intent.followingLatest)
    }
    scenario("cancelled animation keeps the return control available") {
        val intent = ChatScrollIntent(false)
        val request = intent.requestReturn()
        intent.finishReturn(request, false)
        check(!intent.followingLatest && !intent.returningLatest)
    }
    scenario("old animation finally cannot cancel a newer return request") {
        val intent = ChatScrollIntent(false)
        val first = intent.requestReturn()
        val second = intent.requestReturn()
        intent.finishReturn(first, false)
        check(intent.returningLatest && !intent.followingLatest)
        intent.finishReturn(second, true)
        check(intent.followingLatest)
    }
    scenario("save completion while return animation is running cannot preempt it") {
        val intent = ChatScrollIntent(false)
        val submitted = intent.revision
        val request = intent.requestReturn()
        intent.acceptSend(submitted)
        intent.acceptSend(request)
        check(intent.returningLatest && !intent.followingLatest)
    }
    scenario("wheel or accessibility user input invalidates earlier work") {
        val intent = ChatScrollIntent()
        val submitted = intent.revision
        intent.interrupt()
        intent.settle(false, true, true)
        intent.acceptSend(submitted)
        check(!intent.followingLatest)
        intent.settle(false, false, true)
        check(!intent.followingLatest)
    }
    scenario("return completion without reaching latest cannot enable follow") {
        val intent = ChatScrollIntent(false)
        val request = intent.requestReturn()
        intent.finishReturn(request, false)
        check(!intent.followingLatest)
    }
    scenario("new conversation intent is independent from old asynchronous work") {
        val old = ChatScrollIntent()
        val new = ChatScrollIntent()
        old.beginGesture()
        old.acceptSend(0L)
        check(new.followingLatest && new.revision == 0L && !new.pointerPressed)
    }
    scenario("gesture revision remains invalid after returning to latest then leaving again") {
        val intent = ChatScrollIntent()
        val submitted = intent.revision
        intent.beginGesture()
        intent.endGesture()
        intent.settle(false, false, false)
        check(intent.followingLatest)
        intent.beginGesture()
        intent.endGesture()
        intent.settle(false, false, true)
        intent.acceptSend(submitted)
        check(!intent.followingLatest)
    }
    scenario("state saver restores paused follow and discards transient animation") {
        val intent = ChatScrollIntent(false)
        intent.requestReturn()
        val scope = SaverScope { true }
        val saved = with(ChatScrollIntent.StateSaver) { scope.save(intent) }
        check(saved == false)
        val restored = checkNotNull(ChatScrollIntent.StateSaver.restore(checkNotNull(saved)))
        check(!restored.followingLatest && !restored.pointerPressed && !restored.returningLatest)
    }
    scenario("snapshotFlow sees a coalesced DOWN-UP via revision and settles once") {
        runBlocking {
            val intent = ChatScrollIntent()
            var emissions = 0
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                snapshotFlow { intent.revision to intent.pointerPressed }.collect { (_, pressed) ->
                    emissions++
                    intent.settle(pressed, false, false)
                }
            }
            intent.beginGesture()
            intent.endGesture()
            check(!intent.followingLatest)
            Snapshot.sendApplyNotifications()
            yield()
            check(emissions == 2 && intent.followingLatest)
            Snapshot.sendApplyNotifications()
            yield()
            check(emissions == 2)
            collector.cancelAndJoin()
        }
    }
    println("PASS $checks production ChatScrollIntent scenarios")
}
