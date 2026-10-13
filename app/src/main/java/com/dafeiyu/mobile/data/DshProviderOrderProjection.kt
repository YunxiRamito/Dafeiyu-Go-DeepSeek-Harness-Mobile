package com.dafeiyu.mobile.data

import android.content.Context
import android.system.Os
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/** Launcher-owned route order, outside DSH Config and the credential vault.
 * Only IDs and a committed revision are exposed to the read-only web adapter. */
internal class DshProviderOrderProjection(context: Context) {
    val directory = File(context.applicationContext.filesDir, "dsh-provider-order")

    fun publish(snapshot: ModelProviderSnapshot): File {
        require(snapshot.revision >= 0 && snapshot.providers.size in 1..128)
        val routes = snapshot.providers.map { if (it.protected) "deepseek-official" else it.dshRoute ?: it.id }.distinct()
        require(routes.all { it.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) && it.length <= 512 })
        val value = JSONObject().put("schema", 1).put("revision", snapshot.revision).put("routes", JSONArray(routes))
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 32 * 1024) { "提供商排序投影超过上限" }
        try {
            require(!LinuxArchiveExtractor.isSymlink(directory) && (directory.isDirectory || directory.mkdirs())) {
                "无法准备 DSH 网页提供商排序"
            }
            val file = File(directory, "order.json")
            require(!LinuxArchiveExtractor.isSymlink(file) && !LinuxArchiveExtractor.isSymlink(File(file.path + ".bak")) &&
                !LinuxArchiveExtractor.isSymlink(File(file.path + ".new"))) { "提供商排序投影路径无效" }
            Os.chmod(directory.path, 0x1c0)
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                output.write(bytes)
                atomic.finishWrite(output)
            } catch (failure: Throwable) { atomic.failWrite(output); throw failure }
            Os.chmod(file.path, 0x180)
        } catch (failure: Exception) {
            throw IOException("提供商配置已保留，但 DSH 网页排序尚未同步；请重试", failure)
        } finally { bytes.fill(0) }
        return directory
    }
}
