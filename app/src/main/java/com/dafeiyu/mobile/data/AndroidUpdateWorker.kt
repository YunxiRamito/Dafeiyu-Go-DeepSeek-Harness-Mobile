package com.dafeiyu.mobile.data

import android.content.Context
import android.os.Build
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/** Downloads and verifies an APK in the background; it never starts installation. */
class AndroidUpdateWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val settings = applicationContext.getSharedPreferences("dafeiyu_settings", Context.MODE_PRIVATE)
        val policy = settings.getString(SettingsRuntimeStore.KEY_UPDATE_POLICY, "remind")
        if (policy == "off") return Result.success()
        val installedCode = runCatching {
            val info = applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        }.getOrElse { return Result.failure() }
        val client = AndroidReleaseClient(applicationContext)
        val manifest = client.getCurrent("stable").getOrElse { return retryOrFailure() }
        if (manifest.versionCode <= installedCode) return Result.success()
        if (policy != "auto") {
            NotificationPublisher(applicationContext).postUpdate(manifest.version, downloaded = false)
            return Result.success()
        }
        if (!mayDownload()) return Result.retry()
        val result = try {
            client.download(manifest, source = settings.getString(SettingsRuntimeStore.KEY_DOWNLOAD_SOURCE, "cdn") ?: "cdn") { progress ->
                if (!mayDownload()) throw DownloadDeferred()
                setProgressAsync(workDataOf(
                    "receivedBytes" to progress.receivedBytes,
                    "totalBytes" to (progress.totalBytes ?: -1L),
                    "threads" to progress.threads,
                ))
            }
        } catch (_: DownloadDeferred) { return Result.retry()
        } catch (cancelled: CancellationException) { throw cancelled }
        if (result.isSuccess) {
            NotificationPublisher(applicationContext).postUpdate(manifest.version, downloaded = true)
            return Result.success()
        }
        return retryOrFailure()
    }

    private fun mayDownload(): Boolean {
        val settings = applicationContext.getSharedPreferences("dafeiyu_settings", Context.MODE_PRIVATE)
        if (settings.getString(SettingsRuntimeStore.KEY_UPDATE_POLICY, "remind") != "auto") return false
        if (settings.getBoolean(SettingsRuntimeStore.KEY_POWER_SAVING_MODE, false) && !AndroidAppVisibility.isForeground) return false
        if (!settings.getBoolean("update_wifi_only", true)) return true
        val connectivity = applicationContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private class DownloadDeferred : CancellationException("自动下载条件已改变，稍后继续")

    private fun retryOrFailure(): Result = if (runAttemptCount < 3) Result.retry() else Result.failure()
}

object AndroidUpdateScheduler {
    private const val WORK_NAME = "dafeiyu.android.update.check"

    fun reconcile(context: Context, policy: String, wifiOnly: Boolean) {
        val manager = WorkManager.getInstance(context.applicationContext)
        if (policy == "off") {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<AndroidUpdateWorker>(12, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setInputData(workDataOf("policy" to policy, "wifiOnly" to wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

/** Process memory only: a killed app is never mistaken for a visible activity. */
object AndroidAppVisibility {
    private val foreground = AtomicBoolean(false)
    val isForeground: Boolean get() = foreground.get()
    fun setForeground(value: Boolean) { foreground.set(value) }
}

