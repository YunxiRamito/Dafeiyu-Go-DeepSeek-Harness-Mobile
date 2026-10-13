package com.dafeiyu.mobile.data

import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
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
    private val terminate: suspend () -> Unit,
) {
    private val stopLock = Mutex()
    private val ended = CompletableDeferred<Int>()
    val input: OutputStream get() = process.outputStream
    val output: InputStream get() = process.inputStream
    val errors: InputStream get() = process.errorStream
    val isAlive: Boolean get() = process.isAlive
    val info: LinuxManagedProcessInfo get() = LinuxManagedProcessInfo(id, pid, runtimeSlot, isAlive)

    suspend fun awaitExit(): Int = ended.await()

    suspend fun stop() = stopLock.withLock {
        if (process.isAlive) terminate()
        check(!process.isAlive) { "常驻运行进程仍在清理，环境租约继续保留" }
        process.outputStream.close()
        process.inputStream.close()
        process.errorStream.close()
    }

    internal fun onExited() { ended.complete(process.exitValue()) }
}
