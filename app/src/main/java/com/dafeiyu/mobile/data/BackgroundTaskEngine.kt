package com.dafeiyu.mobile.data

import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Own one engine for the application lifetime; the screen does not own its jobs. */
class BackgroundTaskEngine private constructor(
    private val store: BackgroundTaskStore,
    runner: BackgroundTaskRunner?,
    availability: (String) -> BackgroundTaskAvailability,
    dispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    private val jobs = mutableMapOf<String, Job>()
    private val values = MutableStateFlow<List<BackgroundTask>>(emptyList())
    private val issue = MutableStateFlow<String?>(null)
    private val configuration = MutableStateFlow(0L)
    private class RunnerExecution : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<RunnerExecution>
    }
    private data class ExecutionConfig(
        val runner: BackgroundTaskRunner?,
        val availability: (String) -> BackgroundTaskAvailability,
    )
    @Volatile private var executionConfig = ExecutionConfig(runner, availability)
    @Volatile private var closed = false
    val tasks: StateFlow<List<BackgroundTask>> = values.asStateFlow()
    val storageIssue: StateFlow<String?> = issue.asStateFlow()
    val configurationVersion: StateFlow<Long> = configuration.asStateFlow()

    /**
     * Wait for a terminal snapshot without starting, stopping or retrying the task.
     * Missing/deleted tasks fail with task_not_found; cancelling the waiter leaves work running.
     * A runner may read a terminal result, but cannot hold a worker slot awaiting unfinished work.
     */
    suspend fun awaitTerminal(taskId: String): BackgroundTask {
        currentCoroutineContext().ensureActive()
        val insideRunner = currentCoroutineContext()[RunnerExecution] != null
        return combine(values, configuration) { snapshot, _ ->
            snapshot.firstOrNull { it.id == taskId }
                ?: throw IllegalArgumentException("task_not_found")
        }.first { task ->
            if (task.status in TERMINAL) true
            else {
                check(!insideRunner) { "nested_task_wait_forbidden" }
                check(!closed || task.status != BackgroundTaskStatus.DRAFT) { "task_engine_closed" }
                false
            }
        }
    }

    fun canRun(model: String): BackgroundTaskAvailability {
        val config = executionConfig
        return when {
            closed -> BackgroundTaskAvailability(false, "任务引擎已关闭")
            config.runner == null -> BackgroundTaskAvailability(false, "请先配置可用模型和 API Key")
            else -> runCatching { config.availability(model) }
                .getOrDefault(BackgroundTaskAvailability(false, "模型暂不可用"))
        }
    }

    suspend fun configure(
        runner: BackgroundTaskRunner?,
        availability: (String) -> BackgroundTaskAvailability,
    ) = mutex.withLock {
        check(!closed)
        executionConfig = ExecutionConfig(runner, availability)
        configuration.value += 1
        pump()
    }

    suspend fun notifyConfigurationChanged() = mutex.withLock {
        check(!closed)
        configuration.value += 1
        pump()
    }

    suspend fun create(
        title: String,
        model: String,
        prompt: String,
        kind: BackgroundTaskKind = BackgroundTaskKind.BACKGROUND,
        parentId: String? = null,
    ): BackgroundTask = mutex.withLock {
        check(currentCoroutineContext()[RunnerExecution] == null) { "nested_task_creation_forbidden" }
        check(!closed)
        require(values.value.size < BackgroundTaskLimits.MAX_TASKS) { "最多保留 50 个任务，请先删除旧任务" }
        val now = clock()
        val task = BackgroundTask(UUID.randomUUID().toString(), title.trim(), model.trim(), prompt.trim(),
            BackgroundTaskStatus.DRAFT, now, now, kind = kind, parentConversationId = parentId)
        BackgroundTaskLimits.validate(task)
        commit(values.value + task)
        task
    }

    suspend fun enqueue(id: String) = mutex.withLock {
        withContext(NonCancellable) {
            check(currentCoroutineContext()[RunnerExecution] == null) { "nested_task_execution_forbidden" }
            check(!closed)
            val task = find(id)
            require(task.status !in ACTIVE) { "任务已经排队或正在执行" }
            require(id !in jobs) { "任务正在停止，请稍后重试" }
            val available = canRun(task.model)
            require(available.runnable) { available.reason.ifBlank { "模型暂不可用" } }
            // Finish scheduling even if the caller disappears after the durable queue write.
            commit(values.value.map {
                if (it.id == id) it.copy(status = BackgroundTaskStatus.QUEUED, updatedAtMillis = clock(),
                    result = "", progress = "", errorCode = null) else it
            })
            pump()
        }
    }

    suspend fun stop(id: String) = mutex.withLock {
        withContext(NonCancellable) {
            check(!closed)
            val task = find(id)
            if (task.status in ACTIVE) {
                val running = id in jobs
                commit(values.value.map {
                    if (it.id == id) it.copy(
                        status = if (running) BackgroundTaskStatus.STOPPING else BackgroundTaskStatus.STOPPED,
                        updatedAtMillis = clock(), errorCode = null,
                    ) else it
                })
                jobs[id]?.cancel()
            }
        }
    }

    suspend fun delete(id: String) = mutex.withLock {
        withContext(NonCancellable) {
            check(!closed)
            find(id)
            commit(values.value.filterNot { it.id == id })
            jobs[id]?.cancel()
        }
    }

    private fun find(id: String): BackgroundTask =
        values.value.firstOrNull { it.id == id } ?: throw IllegalArgumentException("任务不存在")

    private suspend fun commit(next: List<BackgroundTask>) = withContext(NonCancellable) {
        // A screen cancellation must not separate the durable write from its published snapshot.
        try {
            store.save(next)
            values.value = next.toList()
            issue.value = null
        } catch (failure: Exception) {
            if (failure !is CancellationException) issue.value = "任务保存失败，请检查存储空间"
            throw failure
        }
    }

    /** Reserve slots before launch so competing enqueue calls cannot overbook them. */
    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private fun pump() {
        if (closed || issue.value != null) return
        for (task in values.value) {
            if (jobs.size >= BackgroundTaskLimits.MAX_CONCURRENT) break
            if (task.status != BackgroundTaskStatus.QUEUED || task.id in jobs || !canRun(task.model).runnable) continue
            // ATOMIC reaches execute's finally even if cancelled before acquiring the mutex.
            val job = scope.launch(start = CoroutineStart.ATOMIC) { execute(task.id) }
            jobs[task.id] = job
        }
    }

    private suspend fun execute(id: String) {
        var terminalStatus: BackgroundTaskStatus? = null
        var terminalText = ""
        var terminalError: String? = null
        try {
            val (task, selectedRunner) = mutex.withLock {
                currentCoroutineContext().ensureActive()
                val queued = values.value.firstOrNull { it.id == id } ?: return
                if (queued.status != BackgroundTaskStatus.QUEUED || !canRun(queued.model).runnable) return
                // Reservations may outlive configure(); choose the runner at admission.
                val runner = executionConfig.runner ?: return
                val running = queued.copy(status = BackgroundTaskStatus.RUNNING, updatedAtMillis = clock())
                commit(values.value.map { if (it.id == id) running else it })
                running to runner
            }
            currentCoroutineContext().ensureActive()
            val result = withContext(RunnerExecution()) {
                selectedRunner.run(BackgroundTaskRequest(
                    task.id, task.model, task.prompt, task.kind, task.parentConversationId,
                    allowTaskCreation = false,
                )) { progress ->
                    currentCoroutineContext().ensureActive()
                    require(progress.length <= BackgroundTaskLimits.MAX_PROGRESS && BackgroundTaskLimits.safe(progress)) {
                        "invalid_progress"
                    }
                    mutex.withLock {
                        values.value = values.value.map {
                            if (it.id == id && it.status == BackgroundTaskStatus.RUNNING) it.copy(progress = progress) else it
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            require(result.text.length <= BackgroundTaskLimits.MAX_RESULT && BackgroundTaskLimits.safe(result.text)) {
                "invalid_result"
            }
            terminalStatus = BackgroundTaskStatus.SUCCEEDED
            terminalText = result.text
        } catch (_: CancellationException) {
            terminalStatus = BackgroundTaskStatus.STOPPED
        } catch (_: Exception) {
            // Runner exception messages can contain HTTP headers or secrets.
            terminalStatus = BackgroundTaskStatus.FAILED
            terminalError = "execution_failed"
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    val task = values.value.firstOrNull { it.id == id }
                    val status = terminalStatus
                    if (task != null && status != null) {
                        val finalStatus = when {
                            closed -> BackgroundTaskStatus.INTERRUPTED
                            task.status == BackgroundTaskStatus.STOPPING -> BackgroundTaskStatus.STOPPED
                            else -> status
                        }
                        runCatching {
                            commit(values.value.map {
                                if (it.id == id) it.copy(status = finalStatus,
                                    result = if (finalStatus == BackgroundTaskStatus.SUCCEEDED) terminalText else "",
                                    errorCode = if (closed) "interrupted" else terminalError,
                                    updatedAtMillis = clock()) else it
                            })
                        }
                    }
                    // A collector may immediately retry a terminal task; release its slot
                    // before unlocking so it cannot observe an old worker reservation.
                    jobs.remove(id)
                    if (values.value.any { it.id == id && it.status == BackgroundTaskStatus.STOPPING }) {
                        runCatching {
                            commit(values.value.map {
                                if (it.id == id) it.copy(status = BackgroundTaskStatus.STOPPED, updatedAtMillis = clock()) else it
                            })
                        }
                    }
                    if (issue.value != null) {
                        // The durable active state will recover as interrupted on reopen.
                        // Reflect the ended worker now and suspend scheduling until storage recovers.
                        values.value = values.value.map {
                            if (it.id == id && it.status in ACTIVE) it.copy(
                                status = BackgroundTaskStatus.INTERRUPTED, updatedAtMillis = clock(),
                                errorCode = "storage_failed",
                            ) else it
                        }
                    }
                    pump()
                }
            }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        // Unreserved queued tasks have no worker finally; finish them on the engine dispatcher.
        scope.launch(NonCancellable) {
            mutex.withLock {
                configuration.value += 1
                val interrupted = values.value.map {
                    if (it.status in ACTIVE) it.copy(status = BackgroundTaskStatus.INTERRUPTED,
                        updatedAtMillis = clock(), errorCode = "interrupted") else it
                }
                if (interrupted != values.value) {
                    try {
                        commit(interrupted)
                    } catch (_: Exception) {
                        values.value = interrupted
                    }
                }
            }
        }
    }

    companion object {
        private val ACTIVE = setOf(BackgroundTaskStatus.QUEUED, BackgroundTaskStatus.RUNNING, BackgroundTaskStatus.STOPPING)
        private val TERMINAL = setOf(BackgroundTaskStatus.SUCCEEDED, BackgroundTaskStatus.FAILED,
            BackgroundTaskStatus.STOPPED, BackgroundTaskStatus.INTERRUPTED)

        suspend fun open(
            store: BackgroundTaskStore,
            runner: BackgroundTaskRunner? = null,
            availability: (String) -> BackgroundTaskAvailability = { BackgroundTaskAvailability(false, "请先配置模型") },
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
            clock: () -> Long = System::currentTimeMillis,
        ): BackgroundTaskEngine {
            val engine = BackgroundTaskEngine(store, runner, availability, dispatcher, clock)
            try {
                val loaded = store.load()
                require(loaded.size <= BackgroundTaskLimits.MAX_TASKS && loaded.map { it.id }.distinct().size == loaded.size)
                loaded.forEach(BackgroundTaskLimits::validate)
                val restored = loaded.map {
                    if (it.status in ACTIVE) {
                        it.copy(status = BackgroundTaskStatus.INTERRUPTED, updatedAtMillis = clock(), errorCode = "interrupted")
                    } else it
                }
                engine.mutex.withLock {
                    engine.commit(restored)
                    engine.pump()
                }
                return engine
            } catch (failure: Exception) {
                engine.close()
                throw failure
            }
        }
    }
}
