package com.dafeiyu.mobile.data

import java.io.InputStream
import java.io.OutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LinuxManagedProcessInfo(val id: String, val pid: Int, val runtimeSlot: String, val running: Boolean)

internal data class LinuxNodeIdentity(val version: String, val architecture: String, val platform: String)

/** Pipes are private to the caller. No raw shell strings or credentials are recorded here. */
class LinuxManagedProcess internal constructor(
    private val id: String,
    val pid: Int,
    val runtimeSlot: String,
    private val process: Process,
    private val retainLeaseUntilPipesComplete: Boolean = false,
    private val releaseRetainedLease: suspend (LinuxManagedProcess) -> Unit = {},
    private val terminate: suspend () -> Unit,
) {
    private val stopLock = Mutex()
    private val pipeLock = Mutex()
    private val ended = CompletableDeferred<Int>()
    private val retainedLease = AtomicBoolean(retainLeaseUntilPipesComplete)
    private val pipeReaders = mutableListOf<Job>()
    private val trackedInput by lazy { ClosedOutput(process.outputStream) }
    private val trackedOutput by lazy { ClosedInput(process.inputStream) }
    private val trackedErrors by lazy { ClosedInput(process.errorStream) }
    val input: OutputStream get() = if (retainLeaseUntilPipesComplete) trackedInput else process.outputStream
    val output: InputStream get() = if (retainLeaseUntilPipesComplete) trackedOutput else process.inputStream
    val errors: InputStream get() = if (retainLeaseUntilPipesComplete) trackedErrors else process.errorStream
    val isAlive: Boolean get() = process.isAlive
    /** Actual native exit alone does not complete retained pipe ownership. */
    internal val holdsRuntimeLease: Boolean get() = isAlive || retainedLease.get()
    internal val retainsPipeLease: Boolean get() = retainLeaseUntilPipesComplete
    /** Identity, not a PID or synthetic idle flag, binds the real runtime lease. */
    internal fun ownsProcess(candidate: Process): Boolean = process === candidate
    val info: LinuxManagedProcessInfo get() = LinuxManagedProcessInfo(id, pid, runtimeSlot, isAlive)

    suspend fun awaitExit(): Int = ended.await()

    /** Register the actual stdio pump Jobs, not idle/cancelled Boolean flags.
     * Registration must occur before protocol IO and cannot be replaced later. */
    internal suspend fun registerPipeReaders(outputReader: Job, errorReader: Job) = pipeLock.withLock {
        check(retainLeaseUntilPipesComplete && retainedLease.get() && pipeReaders.isEmpty() && outputReader !== errorReader) {
            "PIPE_READERS_NOT_REGISTERED"
        }
        pipeReaders += outputReader
        pipeReaders += errorReader
    }

    /** Only a caller that has actually joined both pumps and closed all three
     * private streams may release the real runtime/resource registration. A
     * cancelled but unfinished reader leaves ownership in place. */
    internal suspend fun completePipeCleanup() = pipeLock.withLock {
        check(retainLeaseUntilPipesComplete && retainedLease.get() && ended.isCompleted && !process.isAlive &&
            trackedInput.closed.get() && trackedOutput.closed.get() && trackedErrors.closed.get() &&
            pipeReaders.size == 2 && pipeReaders.all { it.isCompleted }) { "PIPE_CLEANUP_PENDING" }
        releaseRetainedLease(this)
        retainedLease.set(false)
    }

    suspend fun stop() = stopLock.withLock {
        if (process.isAlive) terminate()
        check(!process.isAlive) { "常驻运行进程仍在清理，环境租约继续保留" }
        input.close()
        output.close()
        errors.close()
    }

    internal fun onExited() { ended.complete(process.exitValue()) }

    private class ClosedInput(source: InputStream) : FilterInputStream(source) {
        val closed = AtomicBoolean(false)
        override fun close() { super.close(); closed.set(true) }
    }

    private class ClosedOutput(source: OutputStream) : FilterOutputStream(source) {
        val closed = AtomicBoolean(false)
        override fun close() { super.close(); closed.set(true) }
        // FilterOutputStream's default bulk write calls write(Int) repeatedly.
        // Keep the private authenticated frames on the actual stream bulk path.
        override fun write(value: ByteArray, offset: Int, length: Int) { out.write(value, offset, length) }
    }
}
