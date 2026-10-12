package com.dafeiyu.mobile.data

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private class MemoryStore(var records: List<BackgroundTask> = emptyList()) : BackgroundTaskStore {
    var fail = false
    override suspend fun load() = records
    override suspend fun save(tasks: List<BackgroundTask>) {
        if (fail) error("test_storage_failure")
        records = BackgroundTaskJson.decode(BackgroundTaskJson.encode(tasks))
    }
}

private class FileStore(private val path: Path) : BackgroundTaskStore {
    init { Files.deleteIfExists(path) }
    override suspend fun load(): List<BackgroundTask> =
        if (Files.exists(path)) BackgroundTaskJson.decode(Files.readAllBytes(path)) else emptyList()
    override suspend fun save(tasks: List<BackgroundTask>) {
        val pending = path.resolveSibling(path.fileName.toString() + ".pending")
        Files.write(pending, BackgroundTaskJson.encode(tasks))
        Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}

private class PausedDispatcher : CoroutineDispatcher() {
    private val pending = ConcurrentLinkedQueue<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) { pending.add(block) }
    fun drain() {
        var remaining = 10_000
        while (true) {
            val next = pending.poll() ?: break
            check(remaining-- > 0) { "dispatcher_did_not_settle" }
            next.run()
        }
    }
}

private val ready: (String) -> BackgroundTaskAvailability = { BackgroundTaskAvailability(true) }
private val echo = BackgroundTaskRunner { request, _ -> BackgroundTaskResult(request.prompt) }
private fun record(status: BackgroundTaskStatus = BackgroundTaskStatus.DRAFT) = BackgroundTask(
    UUID.randomUUID().toString(), "Task", "real-model", "Input", status, 10, 10,
)
private suspend fun rejected(action: suspend () -> Unit) {
    var failure: Exception? = null
    try { action() } catch (caught: Exception) { failure = caught }
    check(failure != null) { "expected_rejection" }
}
private suspend fun waitFor(condition: suspend () -> Boolean) = withTimeout(5_000) {
    while (!condition()) delay(5)
}
private suspend fun BackgroundTaskEngine.task(title: String = "Task", prompt: String = "Input") =
    create(title, "real-model", prompt)
private fun BackgroundTaskEngine.status(id: String) = tasks.value.first { it.id == id }.status

fun main(args: Array<String>) = runBlocking {
    val directory = Path.of(args.single())
    Files.createDirectories(directory)
    var passed = 0
    suspend fun test(name: String, action: suspend () -> Unit) {
        withTimeout(12_000) { action() }
        println("PASS $name")
        passed++
    }
    test("unconfigured drafts persist but cannot execute") {
        val store = FileStore(directory.resolve("draft.json"))
        BackgroundTaskEngine.open(store).use { engine ->
            val task = engine.task()
            check(engine.status(task.id) == BackgroundTaskStatus.DRAFT)
            rejected { engine.enqueue(task.id) }
            check(store.load().single() == task)
        }
    }
    test("unavailable model cannot call the runner") {
        val calls = AtomicInteger()
        BackgroundTaskEngine.open(MemoryStore(), BackgroundTaskRunner { _, _ ->
            calls.incrementAndGet(); BackgroundTaskResult("unexpected")
        }, { BackgroundTaskAvailability(false, "unavailable") }).use { engine ->
            rejected { engine.enqueue(engine.task().id) }
            check(calls.get() == 0)
        }
    }
    test("injected suspend runner executes and result survives file reopen") {
        val store = FileStore(directory.resolve("result.json"))
        val calls = AtomicInteger()
        var id = ""
        val expected = MessageDigest.getInstance("SHA-256").digest("real input".toByteArray())
            .joinToString("") { "%02x".format(it) }
        BackgroundTaskEngine.open(store, BackgroundTaskRunner { request, emit ->
            calls.incrementAndGet()
            check(request.model == "real-model")
            emit("computing digest")
            BackgroundTaskResult(MessageDigest.getInstance("SHA-256").digest(request.prompt.toByteArray())
                .joinToString("") { "%02x".format(it) })
        }, ready).use { engine ->
            id = engine.task(prompt = "real input").id
            engine.enqueue(id)
            waitFor { engine.status(id) == BackgroundTaskStatus.SUCCEEDED }
            check(engine.tasks.value.single().result == expected && calls.get() == 1)
        }
        BackgroundTaskEngine.open(store).use { engine ->
            check(engine.tasks.value.single().id == id && engine.tasks.value.single().result == expected)
            check(engine.tasks.value.single().progress == "computing digest")
        }
    }
    test("three requests execute with at most two concurrent runners") {
        val active = AtomicInteger()
        val max = AtomicInteger()
        val entered = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        BackgroundTaskEngine.open(MemoryStore(), BackgroundTaskRunner { _, _ ->
            val current = active.incrementAndGet()
            max.updateAndGet { maxOf(it, current) }
            entered.incrementAndGet()
            try { release.await(); BackgroundTaskResult("done") } finally { active.decrementAndGet() }
        }, ready).use { engine ->
            val ids = (1..3).map { engine.task("Task $it").id }
            ids.forEach { engine.enqueue(it) }
            waitFor { entered.get() == 2 }
            check(engine.status(ids.last()) == BackgroundTaskStatus.QUEUED && max.get() == 2)
            release.complete(Unit)
            waitFor { engine.tasks.value.all { it.status == BackgroundTaskStatus.SUCCEEDED } }
            check(entered.get() == 3 && max.get() == 2)
        }
    }
    test("stopping a queued task prevents execution") {
        val entered = AtomicInteger()
        BackgroundTaskEngine.open(MemoryStore(), BackgroundTaskRunner { _, _ ->
            entered.incrementAndGet(); awaitCancellation()
        }, ready).use { engine ->
            val ids = (1..3).map { engine.task("Task $it").id }
            ids.forEach { engine.enqueue(it) }
            waitFor { entered.get() == 2 }
            engine.stop(ids.last())
            check(engine.status(ids.last()) == BackgroundTaskStatus.STOPPED && entered.get() == 2)
        }
    }
    test("stopping running work cancels the actual runner") {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        BackgroundTaskEngine.open(MemoryStore(), BackgroundTaskRunner { _, _ ->
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }, ready).use { engine ->
            val id = engine.task().id
            engine.enqueue(id); entered.await(); engine.stop(id); cancelled.await()
            waitFor { engine.status(id) == BackgroundTaskStatus.STOPPED }
        }
    }
    test("cancellation before dispatch releases reserved slots") {
        val dispatcher = PausedDispatcher()
        BackgroundTaskEngine.open(MemoryStore(), echo, ready, dispatcher).use { engine ->
            val first = engine.task("First").id
            val second = engine.task("Second").id
            engine.enqueue(first); engine.stop(first); dispatcher.drain()
            check(engine.status(first) == BackgroundTaskStatus.STOPPED)
            engine.enqueue(second); dispatcher.drain()
            check(engine.status(second) == BackgroundTaskStatus.SUCCEEDED)
        }
        dispatcher.drain()
    }
    test("deletion before dispatch cannot leave a slot occupied") {
        val dispatcher = PausedDispatcher()
        BackgroundTaskEngine.open(MemoryStore(), echo, ready, dispatcher).use { engine ->
            repeat(4) {
                val id = engine.task().id
                engine.enqueue(id); engine.delete(id); dispatcher.drain()
                check(engine.tasks.value.isEmpty())
            }
            val id = engine.task().id
            engine.enqueue(id); dispatcher.drain()
            check(engine.status(id) == BackgroundTaskStatus.SUCCEEDED)
        }
        dispatcher.drain()
    }
    test("deleting running work cannot resurrect a late result or overbook") {
        val entered = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val store = MemoryStore()
        BackgroundTaskEngine.open(store, BackgroundTaskRunner { _, _ ->
            entered.incrementAndGet()
            withContext(NonCancellable) { release.await() }
            BackgroundTaskResult("late")
        }, ready).use { engine ->
            val ids = (1..3).map { engine.task("Task $it").id }
            ids.forEach { engine.enqueue(it) }
            waitFor { entered.get() == 2 }
            engine.delete(ids.first())
            check(engine.status(ids.last()) == BackgroundTaskStatus.QUEUED && entered.get() == 2)
            release.complete(Unit)
            waitFor { engine.tasks.value.all { it.status == BackgroundTaskStatus.SUCCEEDED } }
            check(engine.tasks.value.none { it.id == ids.first() })
            check(store.load().none { it.id == ids.first() } && entered.get() == 3)
        }
    }
    test("all active states restore interrupted without paid execution") {
        val records = listOf(BackgroundTaskStatus.QUEUED, BackgroundTaskStatus.RUNNING, BackgroundTaskStatus.STOPPING)
            .map(::record)
        val calls = AtomicInteger()
        val runner = BackgroundTaskRunner { _, _ -> calls.incrementAndGet(); BackgroundTaskResult("done") }
        val store = MemoryStore(records)
        BackgroundTaskEngine.open(store, runner, ready).use { engine ->
            check(engine.tasks.value.all { it.status == BackgroundTaskStatus.INTERRUPTED })
            engine.configure(runner, ready)
            delay(40)
            check(calls.get() == 0)
            engine.enqueue(records.first().id)
            waitFor { engine.status(records.first().id) == BackgroundTaskStatus.SUCCEEDED }
            check(calls.get() == 1)
        }
    }
    test("closing the engine interrupts running work and blocks new work") {
        val store = MemoryStore()
        val entered = CompletableDeferred<Unit>()
        val engine = BackgroundTaskEngine.open(store, BackgroundTaskRunner { _, _ ->
            entered.complete(Unit); awaitCancellation()
        }, ready)
        val id = engine.task().id
        engine.enqueue(id); entered.await(); engine.close()
        waitFor { engine.status(id) == BackgroundTaskStatus.INTERRUPTED }
        check(!engine.canRun("real-model").runnable)
        rejected { engine.task() }
    }
    test("failed tasks may explicitly retry") {
        val calls = AtomicInteger()
        BackgroundTaskEngine.open(MemoryStore(), BackgroundTaskRunner { _, _ ->
            if (calls.incrementAndGet() == 1) error("intentional")
            BackgroundTaskResult("second attempt")
        }, ready).use { engine ->
            val id = engine.task().id
            engine.enqueue(id); waitFor { engine.status(id) == BackgroundTaskStatus.FAILED }
            // FAILED is published before the worker releases its slot.
            waitFor {
                runCatching { engine.enqueue(id) }.isSuccess
            }
            waitFor { engine.status(id) == BackgroundTaskStatus.SUCCEEDED }
            check(engine.tasks.value.single().result == "second attempt" && calls.get() == 2)
        }
    }
    test("published terminal status permits immediate retry without a stopping error") {
        for (terminal in listOf(BackgroundTaskStatus.SUCCEEDED, BackgroundTaskStatus.FAILED,
                BackgroundTaskStatus.STOPPED)) {
            val dispatcher = PausedDispatcher()
            val calls = AtomicInteger()
            BackgroundTaskEngine.open(MemoryStore(), BackgroundTaskRunner { _, _ ->
                val attempt = calls.incrementAndGet()
                if (attempt == 1) {
                    when (terminal) {
                        BackgroundTaskStatus.FAILED -> error("first_attempt_failed")
                        BackgroundTaskStatus.STOPPED -> awaitCancellation()
                        else -> Unit
                    }
                }
                BackgroundTaskResult("attempt $attempt")
            }, ready, dispatcher).use { engine ->
                val id = engine.task().id
                engine.enqueue(id)
                val retry = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    engine.tasks.first { tasks -> tasks.any { it.id == id && it.status == terminal } }
                    engine.enqueue(id)
                }
                dispatcher.drain()
                if (terminal == BackgroundTaskStatus.STOPPED) {
                    engine.stop(id)
                    dispatcher.drain()
                }
                retry.await()
                dispatcher.drain()
                check(engine.status(id) == BackgroundTaskStatus.SUCCEEDED && calls.get() == 2) {
                    "terminal_retry_did_not_run"
                }
                check(engine.tasks.value.single().result == "attempt 2")
            }
            dispatcher.drain()
        }
    }
    test("retry clears previous output but preserves kind association and explicit input") {
        for (kind in BackgroundTaskKind.entries) {
            for (status in listOf(BackgroundTaskStatus.SUCCEEDED, BackgroundTaskStatus.FAILED,
                    BackgroundTaskStatus.STOPPED, BackgroundTaskStatus.INTERRUPTED)) {
                val dispatcher = PausedDispatcher()
                val original = record(status).copy(result = "previous result", progress = "previous progress",
                    errorCode = "execution_failed", kind = kind, parentConversationId = "chat-retry")
                val requests = mutableListOf<BackgroundTaskRequest>()
                val store = MemoryStore(listOf(original))
                BackgroundTaskEngine.open(store, BackgroundTaskRunner { request, _ ->
                    requests += request
                    BackgroundTaskResult("new result")
                }, ready, dispatcher).use { engine ->
                    engine.enqueue(original.id)
                    val queued = engine.tasks.value.single()
                    check(queued.status == BackgroundTaskStatus.QUEUED && queued.result.isEmpty() &&
                        queued.progress.isEmpty() && queued.errorCode == null)
                    check(queued.kind == kind && queued.parentConversationId == "chat-retry" &&
                        queued.prompt == original.prompt && queued.model == original.model)
                    check(store.records.single() == queued && requests.isEmpty())
                    dispatcher.drain()
                    check(requests.single() == BackgroundTaskRequest(original.id, original.model,
                        original.prompt, kind, "chat-retry"))
                    check(engine.tasks.value.single().result == "new result" &&
                        engine.status(original.id) == BackgroundTaskStatus.SUCCEEDED)
                }
                dispatcher.drain()
            }
        }
    }
    test("task count is bounded at fifty and deletion frees capacity") {
        BackgroundTaskEngine.open(MemoryStore()).use { engine ->
            repeat(50) { engine.task("Task $it") }
            rejected { engine.task("51") }
            engine.delete(engine.tasks.value.first().id)
            engine.task("replacement")
            check(engine.tasks.value.size == 50)
        }
    }
    test("duplicate enqueue rejects without starting duplicate work") {
        val dispatcher = PausedDispatcher()
        BackgroundTaskEngine.open(MemoryStore(), echo, ready, dispatcher).use { engine ->
            val id = engine.task().id
            engine.enqueue(id); rejected { engine.enqueue(id) }; dispatcher.drain()
            check(engine.status(id) == BackgroundTaskStatus.SUCCEEDED)
        }
        dispatcher.drain()
    }
    test("credential-bearing input fields are not persisted") {
        val store = MemoryStore()
        BackgroundTaskEngine.open(store).use { engine ->
            rejected { engine.create("sk-examplecredential123", "real-model", "Input") }
            rejected { engine.create("Task", "real-model", "api_key=examplecredential123") }
            rejected { engine.create("Task", "Bearer examplecredential123", "Input") }
            check(store.records.isEmpty())
        }
    }
    test("credential-bearing progress fails without persisting the secret") {
        val store = MemoryStore()
        BackgroundTaskEngine.open(store, BackgroundTaskRunner { _, emit ->
            emit("Bearer examplecredential123"); BackgroundTaskResult("unexpected")
        }, ready).use { engine ->
            val id = engine.task().id
            engine.enqueue(id); waitFor { engine.status(id) == BackgroundTaskStatus.FAILED }
            check(!String(BackgroundTaskJson.encode(store.records)).contains("examplecredential123"))
        }
    }
    test("credential-bearing output and exception messages are not persisted") {
        for (throwing in listOf(false, true)) {
            val store = MemoryStore()
            BackgroundTaskEngine.open(store, BackgroundTaskRunner { _, _ ->
                if (throwing) error("Authorization: Bearer examplecredential123")
                BackgroundTaskResult("sk-examplecredential123")
            }, ready).use { engine ->
                val id = engine.task().id
                engine.enqueue(id); waitFor { engine.status(id) == BackgroundTaskStatus.FAILED }
                val saved = String(BackgroundTaskJson.encode(store.records))
                check(!saved.contains("examplecredential123") && saved.contains("execution_failed"))
            }
        }
    }
    test("input, progress, result bounds reject instead of truncating") {
        BackgroundTaskEngine.open(MemoryStore()).use { engine ->
            rejected { engine.task(prompt = "x".repeat(32_769)) }
            rejected { engine.create("x".repeat(81), "real-model", "Input") }
            rejected { engine.create("Task", "x".repeat(129), "Input") }
        }
        for (progress in listOf(false, true)) {
            BackgroundTaskEngine.open(MemoryStore(), BackgroundTaskRunner { _, emit ->
                if (progress) emit("x".repeat(4097))
                BackgroundTaskResult("x".repeat(65_537))
            }, ready).use { engine ->
                val id = engine.task().id
                engine.enqueue(id); waitFor { engine.status(id) == BackgroundTaskStatus.FAILED }
                check(engine.tasks.value.single().result.isEmpty())
            }
        }
    }
    test("storage failure rolls back creation, deletion and queued transition") {
        val store = MemoryStore()
        val dispatcher = PausedDispatcher()
        BackgroundTaskEngine.open(store, echo, ready, dispatcher).use { engine ->
            val task = engine.task()
            store.fail = true
            rejected { engine.task("unsaved") }
            rejected { engine.enqueue(task.id) }
            rejected { engine.delete(task.id) }
            check(engine.tasks.value == listOf(task) && engine.storageIssue.value != null)
            store.fail = false
            engine.enqueue(task.id); dispatcher.drain()
            check(engine.status(task.id) == BackgroundTaskStatus.SUCCEEDED && engine.storageIssue.value == null)
        }
        dispatcher.drain()
    }
    test("configuration changes enable explicit run and publish refresh") {
        BackgroundTaskEngine.open(MemoryStore()).use { engine ->
            val id = engine.task().id
            engine.configure(echo, ready)
            check(engine.configurationVersion.value == 1L && engine.canRun("real-model").runnable)
            check(engine.status(id) == BackgroundTaskStatus.DRAFT)
            engine.enqueue(id); waitFor { engine.status(id) == BackgroundTaskStatus.SUCCEEDED }
            engine.configure(null, ready)
            check(!engine.canRun("real-model").runnable)
        }
    }
    test("availability refresh releases explicit queued work without replacing runner") {
        var enabled = true
        val store = MemoryStore()
        val dispatcher = PausedDispatcher()
        val calls = AtomicInteger()
        BackgroundTaskEngine.open(store, BackgroundTaskRunner { _, _ ->
            calls.incrementAndGet(); BackgroundTaskResult("configured")
        }, { BackgroundTaskAvailability(enabled) }, dispatcher).use { engine ->
            val id = engine.task().id
            engine.enqueue(id)
            enabled = false
            dispatcher.drain()
            check(engine.status(id) == BackgroundTaskStatus.QUEUED && calls.get() == 0)
            enabled = true
            engine.notifyConfigurationChanged()
            check(engine.configurationVersion.value == 1L)
            dispatcher.drain()
            check(engine.status(id) == BackgroundTaskStatus.SUCCEEDED && calls.get() == 1)
        }
        dispatcher.drain()
    }
    test("runner replacement before dispatch cannot execute the reserved old runner") {
        val dispatcher = PausedDispatcher()
        val oldCalls = AtomicInteger()
        val newCalls = AtomicInteger()
        BackgroundTaskEngine.open(MemoryStore(), BackgroundTaskRunner { _, _ ->
            oldCalls.incrementAndGet(); BackgroundTaskResult("old")
        }, ready, dispatcher).use { engine ->
            val id = engine.task().id
            engine.enqueue(id)
            engine.configure(BackgroundTaskRunner { _, _ ->
                newCalls.incrementAndGet(); BackgroundTaskResult("new")
            }, ready)
            dispatcher.drain()
            check(oldCalls.get() == 0 && newCalls.get() == 1) { "reserved_old_runner_executed" }
            check(engine.tasks.value.single().result == "new")
        }
        dispatcher.drain()
    }
    test("close interrupts every queued task and refreshes availability without dispatch") {
        val dispatcher = PausedDispatcher()
        val calls = AtomicInteger()
        val store = MemoryStore()
        val engine = BackgroundTaskEngine.open(store, BackgroundTaskRunner { _, _ ->
            calls.incrementAndGet(); BackgroundTaskResult("unexpected")
        }, ready, dispatcher)
        val ids = (1..3).map { engine.task("Close $it").id }
        ids.forEach { engine.enqueue(it) }
        engine.close()
        dispatcher.drain()
        check(engine.tasks.value.all { it.status == BackgroundTaskStatus.INTERRUPTED }) {
            "close_left_unreserved_queue_active"
        }
        check(store.records == engine.tasks.value && calls.get() == 0)
        check(engine.configurationVersion.value == 1L && !engine.canRun("real-model").runnable)
        engine.close()
        dispatcher.drain()
        check(engine.configurationVersion.value == 1L)
    }
    test("cancelled enqueue stop and delete finish their committed scheduling effects") {
        for (operationName in listOf("enqueue", "stop", "delete")) {
            val dispatcher = PausedDispatcher()
            val calls = AtomicInteger()
            val cancellations = AtomicInteger()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val memory = MemoryStore()
            var blockNext = false
            val store = object : BackgroundTaskStore {
                override suspend fun load() = memory.load()
                override suspend fun save(tasks: List<BackgroundTask>) {
                    if (blockNext) {
                        blockNext = false
                        entered.complete(Unit)
                        release.await()
                    }
                    memory.save(tasks)
                }
            }
            BackgroundTaskEngine.open(store, BackgroundTaskRunner { _, _ ->
                calls.incrementAndGet()
                if (operationName != "enqueue") {
                    try { awaitCancellation() } finally { cancellations.incrementAndGet() }
                }
                BackgroundTaskResult("done")
            }, ready, dispatcher).use { engine ->
                val id = engine.task().id
                if (operationName != "enqueue") {
                    engine.enqueue(id)
                    dispatcher.drain()
                    check(calls.get() == 1 && engine.status(id) == BackgroundTaskStatus.RUNNING)
                }
                blockNext = true
                val operation = launch {
                    when (operationName) {
                        "enqueue" -> engine.enqueue(id)
                        "stop" -> engine.stop(id)
                        else -> engine.delete(id)
                    }
                }
                entered.await()
                operation.cancel()
                release.complete(Unit)
                operation.join()
                dispatcher.drain()
                when (operationName) {
                    "enqueue" -> check(engine.status(id) == BackgroundTaskStatus.SUCCEEDED && calls.get() == 1) {
                        "cancelled_enqueue_left_unpumped_queue"
                    }
                    "stop" -> check(engine.status(id) == BackgroundTaskStatus.STOPPED && cancellations.get() == 1) {
                        "cancelled_stop_did_not_cancel_worker"
                    }
                    else -> check(engine.tasks.value.isEmpty() && cancellations.get() == 1) {
                        "cancelled_delete_did_not_cancel_worker"
                    }
                }
                check(memory.records == engine.tasks.value)
            }
            dispatcher.drain()
        }
    }
    test("task StateFlow publishes associated progress results deletion and configuration refresh") {
        val store = FileStore(directory.resolve("overlay-state.json"))
        val emitNow = CompletableDeferred<Unit>()
        val finishNow = CompletableDeferred<Unit>()
        BackgroundTaskEngine.open(store, BackgroundTaskRunner { request, emit ->
            check(request.parentConversationId == "chat-a")
            emitNow.await()
            emit("visible progress")
            finishNow.await()
            BackgroundTaskResult("visible result")
        }, ready).use { engine ->
            val task = engine.create("Associated background", "real-model", "Independent input",
                BackgroundTaskKind.BACKGROUND, "chat-a")
            engine.create("Other chat", "real-model", "Other input", BackgroundTaskKind.SUBAGENT, "chat-b")
            val progressSnapshot = async(start = CoroutineStart.UNDISPATCHED) {
                engine.tasks.first { tasks -> tasks.any { it.id == task.id && it.progress == "visible progress" } }
            }
            engine.enqueue(task.id)
            emitNow.complete(Unit)
            check(progressSnapshot.await().single { it.parentConversationId == "chat-a" }.status ==
                BackgroundTaskStatus.RUNNING)
            val resultSnapshot = async(start = CoroutineStart.UNDISPATCHED) {
                engine.tasks.first { tasks -> tasks.any { it.id == task.id && it.status == BackgroundTaskStatus.SUCCEEDED } }
            }
            finishNow.complete(Unit)
            check(resultSnapshot.await().first { it.id == task.id }.result == "visible result")
            check(store.load().first { it.id == task.id }.parentConversationId == "chat-a")
            val configurationChanged = async(start = CoroutineStart.UNDISPATCHED) {
                engine.configurationVersion.first { it > 0 }
            }
            engine.configure(null, ready)
            check(configurationChanged.await() == 1L && !engine.canRun(task.model).runnable)
            val deletionSnapshot = async(start = CoroutineStart.UNDISPATCHED) {
                engine.tasks.first { tasks -> tasks.none { it.id == task.id } }
            }
            engine.delete(task.id)
            check(deletionSnapshot.await().single().parentConversationId == "chat-b")
        }
    }
    test("subagent creation is draft, persists association and runs independent explicit input") {
        val store = FileStore(directory.resolve("subagent.json"))
        val requests = ConcurrentLinkedQueue<BackgroundTaskRequest>()
        BackgroundTaskEngine.open(store, BackgroundTaskRunner { request, _ ->
            requests.add(request); BackgroundTaskResult("Result: ${request.prompt}")
        }, ready).use { engine ->
            val first = engine.create("Subagent one", "real-model", "Explicit first input",
                BackgroundTaskKind.SUBAGENT, "conversation-123")
            val second = engine.create("Subagent two", "real-model", "Explicit second input",
                BackgroundTaskKind.SUBAGENT, "conversation-123")
            check(requests.isEmpty() && engine.tasks.value.all { it.status == BackgroundTaskStatus.DRAFT })
            check(store.load().all {
                it.kind == BackgroundTaskKind.SUBAGENT && it.parentConversationId == "conversation-123"
            })
            engine.enqueue(first.id); engine.enqueue(second.id)
            waitFor { engine.tasks.value.all { it.status == BackgroundTaskStatus.SUCCEEDED } }
            check(requests.size == 2 && requests.map { it.id }.toSet() == setOf(first.id, second.id))
            check(requests.all { it.kind == BackgroundTaskKind.SUBAGENT && it.parentConversationId == "conversation-123" })
            check(requests.map { it.prompt }.toSet() == setOf("Explicit first input", "Explicit second input"))
        }
    }
    test("old JSON without kind and association restores background draft") {
        val json = JSONObject(String(BackgroundTaskJson.encode(listOf(record()))))
        json.getJSONArray("tasks").getJSONObject(0).apply {
            remove("kind"); remove("parent_conversation_id")
        }
        val restored = BackgroundTaskJson.decode(json.toString().toByteArray()).single()
        check(restored.kind == BackgroundTaskKind.BACKGROUND && restored.parentConversationId == null)
        BackgroundTaskEngine.open(MemoryStore(listOf(restored))).use { engine ->
            check(engine.tasks.value.single().status == BackgroundTaskStatus.DRAFT)
        }
    }
    test("invalid subagent association cannot enter persistence") {
        BackgroundTaskEngine.open(MemoryStore()).use { engine ->
            rejected { engine.create("Task", "real-model", "Input", BackgroundTaskKind.SUBAGENT, "sk-examplecredential123") }
            rejected { engine.create("Task", "real-model", "Input", BackgroundTaskKind.SUBAGENT, "x".repeat(129)) }
            rejected { engine.create("Task", "real-model", "Input", BackgroundTaskKind.SUBAGENT, "bad\nid") }
            check(engine.tasks.value.isEmpty())
        }
    }
    test("codec roundtrip includes terminal states and results") {
        val records = BackgroundTaskStatus.entries.map { record(it).copy(result = "saved result", progress = "progress") }
        check(BackgroundTaskJson.decode(BackgroundTaskJson.encode(records)) == records)
    }
    test("codec rejects duplicates, malformed UTF8, trailing JSON and bad types") {
        val task = record()
        rejected { BackgroundTaskJson.encode(listOf(task, task)) }
        rejected { BackgroundTaskJson.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
        val encoded = BackgroundTaskJson.encode(listOf(task))
        rejected { BackgroundTaskJson.decode(encoded + "{}".toByteArray()) }
        val json = JSONObject(String(encoded))
        json.getJSONArray("tasks").getJSONObject(0).put("created_at", "10")
        rejected { BackgroundTaskJson.decode(json.toString().toByteArray()) }
        json.getJSONArray("tasks").getJSONObject(0).put("created_at", 1.5)
        rejected { BackgroundTaskJson.decode(json.toString().toByteArray()) }
        json.put("version", "1")
        rejected { BackgroundTaskJson.decode(json.toString().toByteArray()) }
    }
    test("corrupt restored credentials and task IDs fail closed") {
        rejected { BackgroundTaskEngine.open(MemoryStore(listOf(record().copy(id = "-".repeat(36))))) }
        rejected { BackgroundTaskEngine.open(MemoryStore(listOf(record().copy(prompt = "password=examplecredential123")))) }
    }
    test("unknown task operations fail without modifying records") {
        BackgroundTaskEngine.open(MemoryStore(), echo, ready).use { engine ->
            val task = engine.task()
            rejected { engine.delete("unknown") }
            rejected { engine.stop("unknown") }
            rejected { engine.enqueue("unknown") }
            check(engine.tasks.value == listOf(task))
        }
    }
    test("caller cancellation cannot split saved and published snapshots") {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        var persisted: List<BackgroundTask> = emptyList()
        val store = object : BackgroundTaskStore {
            override suspend fun load() = persisted
            override suspend fun save(tasks: List<BackgroundTask>) {
                if (++writes == 2) {
                    entered.complete(Unit)
                    release.await()
                }
                persisted = BackgroundTaskJson.decode(BackgroundTaskJson.encode(tasks))
            }
        }
        BackgroundTaskEngine.open(store).use { engine ->
            val operation = launch { engine.task() }
            entered.await()
            operation.cancel()
            release.complete(Unit)
            operation.join()
            check(persisted.size == 1 && engine.tasks.value == persisted)
        }
    }
    test("worker storage failure cannot repeatedly launch or leave a false running state") {
        for (failOnFinish in listOf(false, true)) {
            val store = MemoryStore()
            val dispatcher = PausedDispatcher()
            val calls = AtomicInteger()
            BackgroundTaskEngine.open(store, BackgroundTaskRunner { _, _ ->
                calls.incrementAndGet()
                if (failOnFinish) store.fail = true
                BackgroundTaskResult("completed")
            }, ready, dispatcher).use { engine ->
                val id = engine.task().id
                engine.enqueue(id)
                if (!failOnFinish) store.fail = true
                dispatcher.drain()
                check(engine.status(id) == BackgroundTaskStatus.INTERRUPTED)
                check(engine.storageIssue.value != null && calls.get() == if (failOnFinish) 1 else 0)
                engine.notifyConfigurationChanged(); dispatcher.drain()
                check(engine.status(id) == BackgroundTaskStatus.INTERRUPTED)
                store.fail = false
                engine.configure(echo, ready)
                engine.enqueue(id); dispatcher.drain()
                check(engine.status(id) == BackgroundTaskStatus.SUCCEEDED && engine.storageIssue.value == null)
            }
            dispatcher.drain()
        }
    }
    test("credential stop cancels clients before writes and attempts all stops after failure") {
        val store = MemoryStore()
        val dispatcher = PausedDispatcher()
        val boundary = BackgroundTaskCredentialBoundary()
        val cancelled = AtomicInteger()
        val stopped = mutableListOf<String>()
        var failNext = false
        val recordingStore = object : BackgroundTaskStore {
            override suspend fun load() = store.load()
            override suspend fun save(tasks: List<BackgroundTask>) {
                val stopping = tasks.filter {
                    it.status in setOf(BackgroundTaskStatus.STOPPED, BackgroundTaskStatus.STOPPING) &&
                        store.records.firstOrNull { old -> old.id == it.id }?.status != it.status
                }
                if (stopping.isNotEmpty()) {
                    check(cancelled.get() > 0 && boundary.blocked)
                    stopped += stopping.map { it.id }
                    if (failNext) {
                        failNext = false
                        error("first_stop_write_failed")
                    }
                }
                store.save(tasks)
            }
        }
        val calls = AtomicInteger()
        BackgroundTaskEngine.open(recordingStore, BackgroundTaskRunner { _, _ ->
            calls.incrementAndGet(); BackgroundTaskResult("unexpected")
        }, { BackgroundTaskAvailability(!boundary.blocked) }, dispatcher).use { engine ->
            val ids = (1..3).map { engine.task("Credential task $it").id }
            ids.forEach { engine.enqueue(it) }
            failNext = true
            rejected { boundary.stop(engine) { cancelled.incrementAndGet() } }
            check(boundary.blocked && ids.all { it in stopped })
            dispatcher.drain()
            // A later successful ordinary save clears storageIssue, but must not reopen admission.
            engine.task("Later draft")
            engine.notifyConfigurationChanged(); dispatcher.drain()
            check(calls.get() == 0 && !engine.canRun("real-model").runnable)
            check(engine.status(ids.first()) == BackgroundTaskStatus.QUEUED)
            boundary.stop(engine) { cancelled.incrementAndGet() }
            check(!boundary.blocked && cancelled.get() == 2)
            engine.notifyConfigurationChanged(); dispatcher.drain()
            check(calls.get() == 0 && ids.all { engine.status(it) == BackgroundTaskStatus.STOPPED })
            engine.enqueue(ids.first()); dispatcher.drain()
            check(calls.get() == 1 && engine.status(ids.first()) == BackgroundTaskStatus.SUCCEEDED)
        }
        dispatcher.drain()
    }
    test("credential stop callback failure still stops every queued task and leaves admission closed") {
        val dispatcher = PausedDispatcher()
        val boundary = BackgroundTaskCredentialBoundary()
        BackgroundTaskEngine.open(MemoryStore(), echo,
            { BackgroundTaskAvailability(!boundary.blocked) }, dispatcher).use { engine ->
            val ids = (1..3).map { engine.task("Task $it").id }
            ids.forEach { engine.enqueue(it) }
            rejected { boundary.stop(engine) { error("client_disconnect_failed") } }
            dispatcher.drain()
            check(boundary.blocked && engine.tasks.value.all { it.status == BackgroundTaskStatus.STOPPED })
            boundary.stop(engine) {}
            check(!boundary.blocked)
        }
        dispatcher.drain()
    }
    test("credential stop cancellation keeps admission closed until explicit recovery") {
        val boundary = BackgroundTaskCredentialBoundary()
        var cancellationPropagated = false
        try {
            boundary.stop(null) { throw kotlinx.coroutines.CancellationException("cancelled") }
        } catch (_: kotlinx.coroutines.CancellationException) {
            cancellationPropagated = true
        }
        check(cancellationPropagated && boundary.blocked)
        boundary.stop(null) {}
        check(!boundary.blocked)
    }
    test("credential change stops both owned running jobs and a queued paid request") {
        val dispatcher = PausedDispatcher()
        val boundary = BackgroundTaskCredentialBoundary()
        val started = AtomicInteger()
        val cancelled = AtomicInteger()
        BackgroundTaskEngine.open(MemoryStore(), BackgroundTaskRunner { _, _ ->
            started.incrementAndGet()
            try { awaitCancellation() } finally { cancelled.incrementAndGet() }
        }, { BackgroundTaskAvailability(!boundary.blocked) }, dispatcher).use { engine ->
            val ids = (1..3).map { engine.task("Task $it").id }
            ids.forEach { engine.enqueue(it) }
            dispatcher.drain()
            check(started.get() == 2 && engine.status(ids.last()) == BackgroundTaskStatus.QUEUED)
            boundary.stop(engine) {}
            dispatcher.drain()
            check(cancelled.get() == 2 && engine.tasks.value.all { it.status == BackgroundTaskStatus.STOPPED })
            engine.notifyConfigurationChanged(); dispatcher.drain()
            check(started.get() == 2)
        }
        dispatcher.drain()
    }
    println("PASS $passed background task checks (real injected test computation; no fake agent/runtime)")
}
