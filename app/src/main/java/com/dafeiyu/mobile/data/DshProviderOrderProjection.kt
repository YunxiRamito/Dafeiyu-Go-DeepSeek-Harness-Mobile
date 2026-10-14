package com.dafeiyu.mobile.data

import android.content.Context
import android.system.Os
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** Launcher-owned route order, outside DSH Config and the credential vault.
 * Only IDs and a committed revision are exposed to the read-only web adapter. */
internal class DshProviderOrderProjection(context: Context) {
    val directory = File(context.applicationContext.filesDir, "dsh-provider-order")
    private val lock = locks.computeIfAbsent(directory.absolutePath) { Any() }

    fun publish(snapshot: ModelProviderSnapshot): File = synchronized(lock) {
        require(snapshot.revision in 0..MAX_REVISION && snapshot.providers.size in 1..128)
        val routes = snapshot.providers.map { if (it.protected) "deepseek-official" else it.dshRoute ?: it.id }.distinct()
        require(routes.all { it.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) && it.length <= 512 })
        val value = JSONObject().put("schema", 1).put("revision", snapshot.revision).put("routes", JSONArray(routes))
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "提供商排序投影超过上限" }
        try {
            require(!LinuxArchiveExtractor.isSymlink(directory) && (directory.isDirectory || directory.mkdirs())) {
                "无法准备 DSH 网页提供商排序"
            }
            val file = File(directory, "order.json")
            require(!LinuxArchiveExtractor.isSymlink(file) && !LinuxArchiveExtractor.isSymlink(File(file.path + ".bak")) &&
                !LinuxArchiveExtractor.isSymlink(File(file.path + ".new"))) { "提供商排序投影路径无效" }
            Os.chmod(directory.path, 0x1c0)
            val atomic = AtomicFile(file)
            if (file.exists() || File(file.path + ".bak").exists()) {
                val previous = readCommitted(atomic)
                require(snapshot.revision >= previous.first) { "提供商排序投影版本不能倒退" }
                if (previous.second == routes) {
                    // Credential and receipt commits advance the vault revision,
                    // but do not change route order or its durable launch input.
                    Os.chmod(file.path, 0x180)
                    return@synchronized directory
                }
                require(snapshot.revision > previous.first) { "同一版本不能改变提供商排序" }
            }
            val output = atomic.startWrite()
            try {
                output.write(bytes)
                atomic.finishWrite(output)
            } catch (failure: Throwable) { atomic.failWrite(output); throw failure }
            Os.chmod(file.path, 0x180)
        } catch (failure: Exception) {
            throw IOException("提供商配置已保留，但 DSH 网页排序尚未同步；请重试", failure)
        } finally { bytes.fill(0) }
        directory
    }

    private fun readCommitted(atomic: AtomicFile): Pair<Long, List<String>> {
        val bytes = atomic.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (output.size() <= MAX_BYTES) {
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.toByteArray().also { require(it.size in 1..MAX_BYTES) { "提供商排序投影大小无效" } }
        }
        return try {
            val value = JSONObject(bytes.toString(Charsets.UTF_8))
            require(value.keys().asSequence().toSet() == setOf("schema", "revision", "routes") && value.get("schema") == 1)
            val revisionValue = value.get("revision")
            require(revisionValue is Int || revisionValue is Long)
            val revision = (revisionValue as Number).toLong()
            require(revision in 0..MAX_REVISION)
            val array = value.getJSONArray("routes")
            require(array.length() in 1..128)
            val routes = List(array.length()) { index ->
                val route = array.get(index)
                require(route is String && route.length <= 512 && route.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")))
                route
            }
            require(routes.distinct().size == routes.size)
            revision to routes
        } finally { bytes.fill(0) }
    }

    private companion object {
        const val MAX_BYTES = 32 * 1024
        const val MAX_REVISION = 9_007_199_254_740_991L
        val locks = ConcurrentHashMap<String, Any>()
    }
}
