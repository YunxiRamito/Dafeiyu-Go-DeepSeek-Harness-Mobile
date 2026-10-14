package com.dafeiyu.mobile.data

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Runtime-created opaque token for an actual native process before a verified
 * managed handle exists. No caller/import can construct a portable token.
 * Failed cleanup keeps the process, stop closure and actual cleanup Job alive. */
class LinuxManagedStartupOwnership internal constructor(
    private val process: Process,
    private val stop: suspend () -> Unit,
    private val release: suspend (LinuxManagedStartupOwnership) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var cleanup: Deferred<Unit>? = null
    private val released = AtomicBoolean(false)
    private val transferred = AtomicBoolean(false)

    internal fun owns(candidate: LinuxManagedProcess): Boolean = candidate.ownsProcess(process)
    internal fun owns(candidate: Process): Boolean = candidate === process
    internal fun transferTo(candidate: LinuxManagedProcess) = synchronized(lock) {
        // Native exit between registration and adoption still belongs to this
        // exact managed handle; its readers must consume EOF and close pipes.
        check(owns(candidate) && cleanup == null && transferred.compareAndSet(false, true)) { "HOST_UNAVAILABLE" }
    }

    internal suspend fun retryCleanup(deadlineMillis: Long = 20_000): Boolean = withContext(NonCancellable) {
        require(deadlineMillis in 1..120_000)
        if (released.get()) return@withContext true
        if (transferred.get()) return@withContext false // the real client now owns the registered managed handle
        val task = synchronized(lock) {
            if (transferred.get()) return@synchronized null
            val prior = cleanup
            if (prior != null && (!prior.isCompleted || !prior.isCancelled)) prior
            else scope.async {
                stop()
                check(!process.isAlive) { "HOST_UNAVAILABLE" }
                // Successful close, not runCatching or cancellation, proves all
                // streams. There were no reader jobs before managed adoption.
                process.outputStream.close()
                process.inputStream.close()
                process.errorStream.close()
                release(this@LinuxManagedStartupOwnership)
                released.set(true)
            }.also { cleanup = it }
        } ?: return@withContext false
        try { withTimeoutOrNull(deadlineMillis) { task.await(); true } == true }
        catch (_: Exception) { false }
    }
}
