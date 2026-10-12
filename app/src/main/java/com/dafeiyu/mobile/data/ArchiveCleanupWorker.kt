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

/** Persistent safety net: expired archived conversations are removed even when the UI stays closed. */
class ArchiveCleanupWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching {
            WorkspaceStore(applicationContext).purgeExpiredArchives(System.currentTimeMillis())
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
