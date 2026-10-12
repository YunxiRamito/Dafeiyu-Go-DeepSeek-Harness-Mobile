package com.dafeiyu.mobile.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** No credentials are part of the task schema; use one engine per application. */
class BackgroundTaskAndroidStore(context: Context) : BackgroundTaskStore {
    private val index = AtomicFile(File(context.applicationContext.noBackupFilesDir, "background-tasks/tasks.json"))

    override suspend fun load(): List<BackgroundTask> = withContext(Dispatchers.IO) {
        val source = try {
            index.openRead()
        } catch (failure: FileNotFoundException) {
            if (!index.baseFile.exists() && !File(index.baseFile.path + ".bak").exists()) return@withContext emptyList()
            throw failure
        }
        val bytes = source.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= BackgroundTaskLimits.MAX_STORE_BYTES)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        BackgroundTaskJson.decode(bytes)
    }

    override suspend fun save(tasks: List<BackgroundTask>) = withContext(Dispatchers.IO) {
        val bytes = BackgroundTaskJson.encode(tasks)
        check(index.baseFile.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "task_storage_unavailable" }
        val output = index.startWrite()
        try {
            output.write(bytes)
            index.finishWrite(output)
        } catch (failure: Exception) {
            index.failWrite(output)
            throw failure
        }
    }
}
