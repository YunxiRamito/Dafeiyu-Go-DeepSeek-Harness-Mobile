package com.dafeiyu.mobile.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Retention authorizes the same real deletion transaction as the foreground;
 * service/process ownership failures preserve history and retry later. */
class ArchiveCleanupWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching {
            val store = WorkspaceStore(applicationContext)
            val history = LocalDshHistoryController(applicationContext)
            for (pending in history.pendingPermanentDeletions().filter { it.phase in setOf("deleting", "native_deleted") }) {
                val receipt = history.deletePermanentHistory(pending)
                store.commitPermanentDeletion(receipt)
                history.acknowledgePermanentDeletion(receipt)
            }
            val now = System.currentTimeMillis()
            for (candidate in store.expiredArchiveCandidates(now)) {
                val intent = history.preparePermanentDeletion(candidate)
                val receipt = history.deletePermanentHistory(intent)
                store.commitPermanentDeletion(receipt)
                history.acknowledgePermanentDeletion(receipt)
            }
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                Result.retry()
            },
        )
    }
}

object ArchiveCleanupScheduler {
    private const val UNIQUE_WORK_NAME = "conversation-archive-expiry-cleanup"
    private val REPEAT_INTERVAL_HOURS = 12L

    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<ArchiveCleanupWorker>(
            REPEAT_INTERVAL_HOURS,
            TimeUnit.HOURS,
        ).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
