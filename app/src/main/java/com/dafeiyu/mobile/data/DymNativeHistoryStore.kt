package com.dafeiyu.mobile.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class DymNativeHistoryResult(val files: Int, val warnings: List<String>)

/** Opaque protocol context is private portable data, never a thinking card or executable request. */
class DymNativeHistoryStore(private val privateRoot: File) {
    private val installed = File(privateRoot, "native-model-history")

    suspend fun snapshot(destination: File, conversationIds: Set<String>): DymNativeHistoryResult = withContext(Dispatchers.IO) {
        val folder = owned(destination)
        check(!folder.exists() && folder.mkdirs())
        val warnings = mutableListOf<String>()
        var files = 0
        for (id in conversationIds) {
            currentCoroutineContext().ensureActive()
            val name = fileName(id)
            val source = owned(File(installed, name))
            if (!source.exists()) continue
            try {
                val record = readRecord(source)
                owned(File(folder, name)).writeText(record.toString())
                files++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { warnings += "一条原始模型历史无效，已仅导出兼容正文" }
        }
        DymNativeHistoryResult(files, warnings.distinct())
    }

    suspend fun restore(result: DymImportResult, backup: AndroidBackupStore.ValidatedBackup?): DymNativeHistoryResult =
        withContext(Dispatchers.IO) {
            val allowed = backup?.conversations?.associateBy { fileName(it.id) }.orEmpty()
            val warnings = mutableListOf<String>()
            var count = 0
            for (resource in result.groupFiles["native-history"].orEmpty()) {
                currentCoroutineContext().ensureActive()
                try {
                    require(resource.archivePath.startsWith(PREFIX + "/") &&
                        resource.archivePath.removePrefix(PREFIX + "/").matches(filePattern))
                    val expected = File(result.stagingRoot, resource.archivePath)
                    require(expected.absoluteFile == resource.file.absoluteFile)
                    val record = readRecord(resource.file)
                    val name = resource.archivePath.substringAfterLast('/')
                    val conversation = allowed[name]
                    if (conversation == null || backup == null) {
                        warnings += "原始模型历史缺少对应会话，已独立保留原文件"
                        continue
                    }
                    val through = record.getLong("through")
                    val completedAssistant = backup.messages[conversation.id].orEmpty().any { message ->
                        message.id == through && message.role == "assistant" &&
                            message.streamSnapshot?.let { it.isFinal && it.runStatus == AssistantRunStatus.COMPLETED } == true
                    }
                    // There is no independently trusted native provider/model index in
                    // a foreign archive. Preserve protocol bytes but use compatible
                    // context until the app can prove the exact engine identity.
                    if (record.getBoolean("completed") || !completedAssistant)
                        warnings += "原始模型历史已保留；这次导入仅使用兼容正文续聊"
                    record.put("completed", false)
                    val target = owned(File(installed, name))
                    check(installed.isDirectory || installed.mkdirs())
                    val candidate = owned(File(installed, ".dym-history-${UUID.randomUUID()}"))
                    try {
                        candidate.writeText(record.toString())
                        currentCoroutineContext().ensureActive()
                        owned(target)
                        Files.move(candidate.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } finally { candidate.delete() }
                    count++
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { warnings += "一条原始模型历史恢复失败，兼容正文和其他有效内容已继续导入" }
            }
            DymNativeHistoryResult(count, warnings.distinct())
        }

    private fun readRecord(file: File): JSONObject {
        owned(file)
        require(file.isFile && file.length() in 1..MAX_BYTES)
        val value = JSONObject(file.readText())
        require(value.keys().asSequence().toSet() == setOf("schema", "provider", "model", "through", "completed", "context"))
        require(value.get("schema") is Int && value.getInt("schema") == 1)
        for (key in listOf("provider", "model")) require(value.get(key) is String && value.getString(key).length in 1..512 &&
            value.getString(key).none { it.code < 32 })
        val through = value.get("through")
        require(through is Number && through.toDouble().isFinite() && through.toDouble() == through.toLong().toDouble() && through.toLong() >= 0)
        require(value.get("completed") is Boolean && value.get("context") is JSONObject)
        val context = value.getJSONObject("context")
        require(context.optJSONArray("messages") != null && context.getJSONArray("messages").length() <= 20_000)
        require(context.toString().toByteArray().size <= 4 * 1024 * 1024)
        return value
    }

    private fun owned(file: File): File {
        val base = privateRoot.absoluteFile.toPath().normalize()
        val path = file.absoluteFile.toPath().normalize()
        require(path.startsWith(base) && path != base)
        var current = base
        for (part in base.relativize(path)) { current = current.resolve(part); require(!Files.isSymbolicLink(current)) }
        require(path.toFile().canonicalPath.startsWith(privateRoot.canonicalPath + File.separator))
        return path.toFile()
    }

    private fun fileName(id: String) = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) } + ".json"

    companion object {
        const val PREFIX = ".dafeiyu/android/model-history"
        private const val MAX_BYTES = 4L * 1024 * 1024 + 4096
        private val filePattern = Regex("[a-f0-9]{64}\\.json")
    }
}
