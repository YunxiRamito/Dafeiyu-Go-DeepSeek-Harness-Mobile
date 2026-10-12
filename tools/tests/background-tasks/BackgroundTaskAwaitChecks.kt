package com.dafeiyu.mobile.data

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private class AwaitStore(private var records: List<BackgroundTask> = emptyList()) : BackgroundTaskStore {
    override suspend fun load() = records
    override suspend fun save(tasks: List<BackgroundTask>) { records = tasks.toList() }
}

private val awaitReady: (String) -> BackgroundTaskAvailability = { BackgroundTaskAvailability(true) }
private fun completedTask(status: BackgroundTaskStatus) = BackgroundTask(
    UUID.randomUUID().toString(), "Completed", "offline", "Input", status, 1, 1,
    result = if (status == BackgroundTaskStatus.SUCCEEDED) "saved child result" else "",
    errorCode = if (status == BackgroundTaskStatus.FAILED) "execution_failed" else null,
    kind = BackgroundTaskKind.SUBAGENT, parentConversationId = "parent-chat",
)
private suspend fun rejectsWith(code: String, action: suspend () -> Unit) {
    check(runCatching { action() }.exceptionOrNull()?.message == code)
}

fun main() = runBlocking {
    var passed = 0
    suspend fun test(name: String, action: suspend () -> Unit) {
        withTimeout(10_000) { action() }
        passed++
        println("PASS $name")
    }
    test("await returns exact already terminal success failure stopped and interrupted snapshots") {
        for (status in listOf(BackgroundTaskStatus.SUCCEEDED, BackgroundTaskStatus.FAILED,
                BackgroundTaskStatus.STOPPED, BackgroundTaskStatus.INTERRUPTED)) {
            val original = completedTask(status)
            BackgroundTaskEngine.open(AwaitStore(listOf(original))).use { engine ->
                check(engine.awaitTerminal(original.id) == original)
            }
        }
    }
    test("await neither enqueues drafts nor treats draft queued running or progress as terminal") {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        BackgroundTaskEngine.open(AwaitStore(), BackgroundTaskRunner { _, emit ->
            calls.incrementAndGet()
            emit("child progress")
            entered.complete(Unit)
            release.await()
            BackgroundTaskResult("child answer")
        }, awaitReady).use { engine ->
            val task = engine.create("Child", "offline", "explicit input", BackgroundTaskKind.SUBAGENT, "parent-chat")
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { engine.awaitTerminal(task.id) }
            check(!waiter.isCompleted && calls.get() == 0)
            engine.enqueue(task.id)
            entered.await()
            check(!waiter.isCompleted && engine.tasks.value.single().progress == "child progress")
            release.complete(Unit)
            val result = waiter.await()
            check(result.status == BackgroundTaskStatus.SUCCEEDED && result.result == "child answer")
            check(result.kind == BackgroundTaskKind.SUBAGENT && result.parentConversationId == "parent-chat")
        }
    }
    test("await returns sanitized failed child without pretending success") {
        BackgroundTaskEngine.open(AwaitStore(), BackgroundTaskRunner { _, _ ->
            error("Authorization: Bearer privatecredential123")
        }, awaitReady).use { engine ->
            val task = engine.create("Failure", "offline", "Input")
            engine.enqueue(task.id)
            val result = engine.awaitTerminal(task.id)
            check(result.status == BackgroundTaskStatus.FAILED && result.errorCode == "execution_failed")
            check(result.result.isEmpty() && result.progress.isEmpty())
        }
    }
    test("await stopping waits for runner cancellation cleanup before returning stopped") {
        val entered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        BackgroundTaskEngine.open(AwaitStore(), BackgroundTaskRunner { _, _ ->
            entered.complete(Unit)
            try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
        }, awaitReady).use { engine ->
            val task = engine.create("Stop", "offline", "Input")
            engine.enqueue(task.id)
            entered.await()
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { engine.awaitTerminal(task.id) }
            engine.stop(task.id)
            check(engine.tasks.value.single().status == BackgroundTaskStatus.STOPPING && !waiter.isCompleted)
            cleanup.complete(Unit)
            check(waiter.await().status == BackgroundTaskStatus.STOPPED)
        }
    }
    test("cancelling a waiter propagates cancellation and leaves its task running") {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        BackgroundTaskEngine.open(AwaitStore(), BackgroundTaskRunner { _, _ ->
            entered.complete(Unit)
            release.await()
            BackgroundTaskResult("still completed")
        }, awaitReady).use { engine ->
            val task = engine.create("Independent", "offline", "Input")
            engine.enqueue(task.id)
            entered.await()
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { engine.awaitTerminal(task.id) }
            waiter.cancelAndJoin()
            check(waiter.isCancelled && engine.tasks.value.single().status == BackgroundTaskStatus.RUNNING)
            release.complete(Unit)
            check(engine.awaitTerminal(task.id).result == "still completed")
        }
    }
    test("unknown or deleted task fails waiting rather than hanging or returning another result") {
        BackgroundTaskEngine.open(AwaitStore()).use { engine ->
            rejectsWith("task_not_found") { engine.awaitTerminal("missing") }
            val task = engine.create("Deleted", "offline", "Input")
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { engine.awaitTerminal(task.id) }.exceptionOrNull()
            }
            engine.delete(task.id)
            check(waiter.await()?.message == "task_not_found")
        }
    }
    test("engine close wakes active waits as interrupted and draft waits as closed") {
        val entered = CompletableDeferred<Unit>()
        val engine = BackgroundTaskEngine.open(AwaitStore(), BackgroundTaskRunner { _, _ ->
            entered.complete(Unit)
            awaitCancellation()
        }, awaitReady)
        try {
            val active = engine.create("Active", "offline", "Input")
            val draft = engine.create("Draft", "offline", "Input")
            engine.enqueue(active.id)
            entered.await()
            val activeWait = async(start = CoroutineStart.UNDISPATCHED) { engine.awaitTerminal(active.id) }
            val draftWait = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { engine.awaitTerminal(draft.id) }.exceptionOrNull()
            }
            engine.close()
            check(activeWait.await().status == BackgroundTaskStatus.INTERRUPTED)
            check(draftWait.await()?.message == "task_engine_closed")
        } finally { engine.close() }
    }
    test("external parent waits do not consume either worker slot and three children finish") {
        val started = AtomicInteger()
        val bothRunning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        BackgroundTaskEngine.open(AwaitStore(), BackgroundTaskRunner { request, _ ->
            check(!request.allowTaskCreation)
            if (started.incrementAndGet() == 2) bothRunning.complete(Unit)
            release.await()
            BackgroundTaskResult(request.prompt)
        }, awaitReady).use { engine ->
            val children = (1..3).map {
                engine.create("Child $it", "offline", "Input $it", BackgroundTaskKind.SUBAGENT, "parent-chat")
            }
            val waiters = children.map { child ->
                async(start = CoroutineStart.UNDISPATCHED) { engine.awaitTerminal(child.id) }
            }
            children.forEach { engine.enqueue(it.id) }
            bothRunning.await()
            check(started.get() == 2 && engine.tasks.value.count { it.status == BackgroundTaskStatus.QUEUED } == 1)
            release.complete(Unit)
            check(waiters.map { it.await().result } == listOf("Input 1", "Input 2", "Input 3"))
            check(started.get() == 3)
        }
    }
    test("two occupied runners reject recursive creation scheduling and waiting without deadlock") {
        val started = AtomicInteger()
        val bothRunning = CompletableDeferred<Unit>()
        val original = completedTask(BackgroundTaskStatus.SUCCEEDED)
        lateinit var engine: BackgroundTaskEngine
        var queuedId = ""
        engine = BackgroundTaskEngine.open(AwaitStore(listOf(original)), BackgroundTaskRunner { request, _ ->
            check(!request.allowTaskCreation)
            if (request.id != queuedId) {
                if (started.incrementAndGet() == 2) bothRunning.complete(Unit)
                bothRunning.await()
                withContext(Dispatchers.Default) {
                    rejectsWith("nested_task_creation_forbidden") {
                        engine.create("Nested", "offline", "Input", BackgroundTaskKind.SUBAGENT)
                    }
                    rejectsWith("nested_task_execution_forbidden") { engine.enqueue(queuedId) }
                    rejectsWith("nested_task_wait_forbidden") { engine.awaitTerminal(queuedId) }
                    rejectsWith("nested_task_wait_forbidden") { engine.awaitTerminal(request.id) }
                    check(engine.awaitTerminal(original.id) == original)
                }
            }
            BackgroundTaskResult("nonrecursive result")
        }, awaitReady)
        engine.use {
            val parents = (1..2).map { engine.create("Parent $it", "offline", "Input") }
            val queued = engine.create("Third", "offline", "Input")
            queuedId = queued.id
            (parents + queued).forEach { engine.enqueue(it.id) }
            val results = (parents + queued).map { engine.awaitTerminal(it.id) }
            check(results.all { it.status == BackgroundTaskStatus.SUCCEEDED })
            check(engine.tasks.value.size == 4 && started.get() == 2)
        }
    }
    println("PASS $passed focused await/recursion checks (injected runners; zero model/network calls)")
}
