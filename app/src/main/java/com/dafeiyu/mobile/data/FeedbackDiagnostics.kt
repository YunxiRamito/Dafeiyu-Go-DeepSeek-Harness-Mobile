package com.dafeiyu.mobile.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.time.Instant

/** Diagnostic JSON matches the PC contract, and reads only fixed files owned by this app. */
object FeedbackDiagnostics {
    const val MAX_BYTES = 1024 * 1024
    private const val MAX_TEXT_BYTES = 128 * 1024
    private val names = listOf("launcher.log", "launcher-boot.log", "launcher-errors.log", "settings-preview.log")

    suspend fun capture(context: Context, version: String): ByteArray = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val summary = "Android ${Build.VERSION.SDK_INT}; app=${app.packageName}; version=$version\n" +
            "Only app-owned diagnostic logs are included. Conversations, configuration and device identifiers are excluded."
        captureOwnedDirectory(File(app.filesDir, "feedback-diagnostics"), version, summary)
    }

    /** Callers record app lifecycle/failures here; credentials are removed before the file is written. */
    suspend fun record(context: Context, operation: String, failure: Throwable? = null) = withContext(Dispatchers.IO) {
        try {
            val root = safeDirectory(File(context.applicationContext.filesDir, "feedback-diagnostics"))
            val file = File(root, if (failure == null) "launcher.log" else "launcher-errors.log")
            requireSafeFile(root, file)
            val message = redact("${Instant.now()} ${operation.take(300)} ${failure?.javaClass?.simpleName.orEmpty()}: ${failure?.message.orEmpty().take(4000)}") + "\n"
            synchronized(FeedbackDiagnostics) {
                if (file.length() > 512 * 1024) file.writeText(tail(file))
                file.appendText(message)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* A diagnostic write must not turn the original failure into an application crash. */ }
    }

    internal fun captureOwnedDirectory(directory: File, version: String, summary: String): ByteArray {
        require(version.matches(Regex("[0-9A-Za-z.+_-]{1,64}"))) { "诊断版本号无效" }
        val root = safeDirectory(directory)
        val entries = JSONArray()
        names.forEach { name ->
            val file = File(root, name)
            requireSafeFile(root, file)
            if (file.isFile) {
                val text = boundedRedactedText(tail(file))
                if (text.isNotBlank()) entries.put(JSONObject().put("name", name).put("text", text))
            }
        }
        if (entries.length() == 0) {
            entries.put(JSONObject().put("name", "launcher-boot.log").put("text", boundedRedactedText(summary)))
        }
        val bytes = JSONObject().put("schemaVersion", 1).put("launcherVersion", version)
            .put("capturedAt", Instant.now().toString()).put("entries", entries).toString().toByteArray(Charsets.UTF_8)
        validate(bytes)
        return bytes
    }

    internal fun validate(bytes: ByteArray) {
        require(bytes.size in 1..MAX_BYTES) { "诊断日志最大 1 MiB" }
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.keys().asSequence().toSet() == setOf("schemaVersion", "launcherVersion", "capturedAt", "entries")) { "诊断日志字段无效" }
        require(root.getInt("schemaVersion") == 1 && root.getString("launcherVersion").matches(Regex("[0-9A-Za-z.+_-]{1,64}"))) { "诊断日志版本无效" }
        Instant.parse(root.getString("capturedAt"))
        val rows = root.getJSONArray("entries")
        require(rows.length() in 1..4) { "诊断日志条目无效" }
        val seen = mutableSetOf<String>()
        repeat(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            require(row.keys().asSequence().toSet() == setOf("name", "text")) { "诊断日志条目字段无效" }
            val text = row.getString("text")
            require(row.getString("name") in names && seen.add(row.getString("name")) && text.isNotEmpty() &&
                text.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES && text.none { it.isISOControl() && it !in "\r\n\t" }) { "诊断日志条目无效" }
        }
    }

    internal fun redact(value: String): String {
        var text = value.filter { !it.isISOControl() || it in "\r\n\t" }
        fun replace(pattern: String, replacement: String) {
            text = Regex(pattern, RegexOption.IGNORE_CASE).replace(text, replacement)
        }
        replace("-----BEGIN [^-\\r\\n]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [^-\\r\\n]*PRIVATE KEY-----|$)", "[REDACTED PRIVATE KEY]")
        replace("\\bBearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [REDACTED]")
        replace("(\\b(?:[A-Za-z0-9]+[_-])*(?:api[_-]?key|access[_-]?token|refresh[_-]?token|admin[_-]?token(?:protected)?|token|password|passwd|secret|authorization)\\b[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;]+)", "$1[REDACTED]")
        replace("\\bsk-[A-Za-z0-9_-]{8,}", "[REDACTED]")
        replace("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+", "[REDACTED]")
        replace("([?&](?:key|api[_-]?key|token|password|secret|signature|sig)=)[^\\s&#]+", "$1[REDACTED]")
        replace("(https?://)[^/\\s:@]+:[^/\\s@]+@", "$1[REDACTED]@")
        replace("\\b[A-Z]:[\\\\/]Users[\\\\/][^\\\\/\\r\\n\\\"']+", "%USERPROFILE%")
        replace("(?<![A-Za-z0-9])/(?:home|Users)/[^/\\s]+", "~/[USER]")
        replace("/(?:data/(?:user/\\d+|data)/[^/\\s]+|storage/emulated/\\d+|sdcard)(?:/[^\\s\\\"']*)?", "[APP_OR_MEDIA_PATH]")
        replace("\\b(?:user(?:name)?|machine(?:name)?|computername|device[_-]?id|installation[_-]?id|android[_-]?id|serial)\\s*[:=]\\s*[^\\s,;]+", "identity=[REDACTED]")
        return text
    }

    private fun boundedRedactedText(text: String): String {
        var value = redact(text)
        while (value.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) {
            val newline = value.indexOf('\n')
            value = if (newline >= 0) value.substring(newline + 1) else "[long diagnostic entry omitted]"
        }
        return value
    }

    private fun tail(file: File): String = RandomAccessFile(file, "r").use { input ->
        val start = (input.length() - MAX_TEXT_BYTES).coerceAtLeast(0L)
        input.seek(start)
        val bytes = ByteArray((input.length() - start).toInt())
        input.readFully(bytes)
        val text = bytes.toString(Charsets.UTF_8)
        if (start > 0) text.substringAfter('\n', "") else text
    }

    private fun safeDirectory(directory: File): File {
        require(!Files.isSymbolicLink(directory.toPath())) { "诊断目录不能是链接" }
        require(directory.isDirectory || directory.mkdirs()) { "无法准备应用诊断目录" }
        return directory.canonicalFile
    }

    private fun requireSafeFile(root: File, file: File) {
        require(!Files.isSymbolicLink(file.toPath()) && file.canonicalFile.parentFile == root) { "诊断日志必须是应用内普通文件" }
    }
}
