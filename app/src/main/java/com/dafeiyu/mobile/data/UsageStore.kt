package com.dafeiyu.mobile.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Reads the usage log produced by the local DSH token statistics plugin.
 *
 * The store is read-only. It never creates the DSH directory, writes usage
 * records, estimates prices, or replaces missing values with demo numbers.
 */
class UsageStore(
    context: Context,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val filesRoot = context.applicationContext.filesDir

    enum class Status {
        AVAILABLE,
        NO_DATA,
        READ_FAILED,
        DEMO,
    }

    data class ReadResult(
        val status: Status,
        val snapshot: Snapshot? = null,
        val sourcePath: String? = null,
        val errorMessage: String? = null,
    ) {
        val hasData: Boolean get() = status == Status.AVAILABLE && snapshot != null

        companion object {
            fun demo(reason: String = "当前页面使用演示状态，未读取真实用量。") = ReadResult(
                status = Status.DEMO,
                errorMessage = reason,
            )
        }
    }

    data class Snapshot(
        val records: Int,
        val invalidRecords: Int,
        val todayTokens: Long,
        val weekTokens: Long,
        val monthTokens: Long,
        val todayRequests: Int,
        val monthRequests: Int,
        val monthInputTokens: Long,
        val monthOutputTokens: Long,
        val monthCacheReadTokens: Long,
        val lastCallAtEpochMillis: Long?,
        val topModel: String?,
        val topModelTokens: Long,
        val daily: List<DailyUsage>,
    )

    data class DailyUsage(
        val day: LocalDate,
        val tokens: Long,
        val requests: Int,
    )

    /** Reads the default private usage path, or a legacy equivalent if present. */
    fun read(): ReadResult {
        val file = resolveExistingFile()
        if (!file.exists()) return ReadResult(Status.NO_DATA, sourcePath = file.path)
        return read(file)
    }

    /** Reads a caller-provided file under the app-private directory only. */
    fun read(relativePath: String): ReadResult {
        val file = resolvePrivateFile(relativePath) ?: return ReadResult(
            status = Status.READ_FAILED,
            errorMessage = "用量文件路径无效。",
        )
        return if (!file.exists()) ReadResult(Status.NO_DATA, sourcePath = file.path) else read(file)
    }

    private fun read(file: File): ReadResult {
        val path = file.path
        if (!file.isFile) return ReadResult(Status.READ_FAILED, sourcePath = path, errorMessage = "用量路径不是文件。")
        if (file.length() > MAX_FILE_BYTES) {
            return ReadResult(Status.READ_FAILED, sourcePath = path, errorMessage = "用量文件超过大小限制。")
        }

        val now = Instant.now(clock)
        val zone = clock.zone
        val nowLocal = now.atZone(zone)
        val today = nowLocal.toLocalDate()
        val weekStart = today.minusDays(6)
        val monthStart = today.minusDays(29)
        val perDay = linkedMapOf<LocalDate, MutableDay>()
        val modelTokens = mutableMapOf<String, Long>()
        var records = 0
        var invalidRecords = 0
        var todayTokens = 0L
        var weekTokens = 0L
        var monthTokens = 0L
        var todayRequests = 0
        var monthRequests = 0
        var monthInput = 0L
        var monthOutput = 0L
        var monthCacheRead = 0L
        var lastCall: Instant? = null

        try {
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    val item = runCatching { JSONObject(line) }.getOrNull()
                    val timestampMillis = item?.let(::readLong)?.let { value ->
                        if (value <= 0L) null else value
                    }
                    val stamp = timestampMillis?.let { runCatching { Instant.ofEpochMilli(it) }.getOrNull() }
                    if (item == null || stamp == null || stamp.isAfter(now)) {
                        invalidRecords++
                        return@forEach
                    }

                    val input = nonNegative(readLong(item, "inputTokens"))
                    val output = nonNegative(readLong(item, "outputTokens"))
                    val cacheRead = nonNegative(readLong(item, "cacheReadTokens"))
                    val cacheWrite = nonNegative(readLong(item, "cacheWriteTokens"))
                    val total = addAll(input, output, cacheRead, cacheWrite)
                    val day = stamp.atZone(zone).toLocalDate()
                    records++
                    if (lastCall == null || stamp.isAfter(lastCall)) lastCall = stamp
                    if (!day.isBefore(today)) {
                        todayTokens = safeAdd(todayTokens, total)
                        todayRequests++
                    }
                    if (!day.isBefore(weekStart)) weekTokens = safeAdd(weekTokens, total)
                    if (!day.isBefore(monthStart)) {
                        monthTokens = safeAdd(monthTokens, total)
                        monthRequests++
                        monthInput = safeAdd(monthInput, input)
                        monthOutput = safeAdd(monthOutput, output)
                        monthCacheRead = safeAdd(monthCacheRead, cacheRead)
                        val model = readString(item, "model")?.trim().orEmpty()
                        if (model.isNotEmpty()) modelTokens[model] = safeAdd(modelTokens[model] ?: 0L, total)
                        val dayData = perDay.getOrPut(day) { MutableDay() }
                        dayData.tokens = safeAdd(dayData.tokens, total)
                        dayData.requests++
                    }
                }
            }
        } catch (error: IOException) {
            return ReadResult(
                status = Status.READ_FAILED,
                sourcePath = path,
                errorMessage = error.message ?: "无法读取用量文件。",
            )
        } catch (error: SecurityException) {
            return ReadResult(
                status = Status.READ_FAILED,
                sourcePath = path,
                errorMessage = error.message ?: "没有读取用量文件的权限。",
            )
        }

        if (records == 0) {
            return ReadResult(
                status = Status.NO_DATA,
                sourcePath = path,
                errorMessage = if (invalidRecords > 0) "文件中没有可用的用量记录。" else null,
            )
        }

        val top = modelTokens.maxByOrNull { it.value }
        val daily = (29 downTo 0).map { offset ->
            val day = today.minusDays(offset.toLong())
            val value = perDay[day]
            DailyUsage(day, value?.tokens ?: 0L, value?.requests ?: 0)
        }
        return ReadResult(
            status = Status.AVAILABLE,
            sourcePath = path,
            snapshot = Snapshot(
                records = records,
                invalidRecords = invalidRecords,
                todayTokens = todayTokens,
                weekTokens = weekTokens,
                monthTokens = monthTokens,
                todayRequests = todayRequests,
                monthRequests = monthRequests,
                monthInputTokens = monthInput,
                monthOutputTokens = monthOutput,
                monthCacheReadTokens = monthCacheRead,
                lastCallAtEpochMillis = lastCall?.toEpochMilli(),
                topModel = top?.key,
                topModelTokens = top?.value ?: 0L,
                daily = daily,
            ),
        )
    }

    private fun resolveExistingFile(): File {
        val primary = File(filesRoot, DEFAULT_RELATIVE_PATH)
        if (primary.exists()) return primary
        val legacy = File(filesRoot, LEGACY_RELATIVE_PATH)
        return if (legacy.exists()) legacy else primary
    }

    private fun resolvePrivateFile(relativePath: String): File? {
        val normalized = relativePath.trim().replace('\\', '/')
        if (normalized.isBlank() || normalized.startsWith('/') || normalized.contains("../") || normalized == "..") return null
        val root = runCatching { filesRoot.canonicalFile }.getOrNull() ?: return null
        val file = runCatching { File(root, normalized).canonicalFile }.getOrNull() ?: return null
        return file.takeIf { it.path == root.path || it.path.startsWith(root.path + File.separator) }
    }

    private fun readLong(json: JSONObject, key: String? = null): Long? {
        val value = if (key == null) json.opt("ts") else json.opt(key)
        return when (value) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }
    }

    private fun readString(json: JSONObject, key: String): String? = json.opt(key).let { value ->
        (value as? String)?.takeIf { it.isNotEmpty() }
    }

    private fun nonNegative(value: Long?): Long = value?.takeIf { it > 0L } ?: 0L

    private fun addAll(vararg values: Long): Long = values.fold(0L, ::safeAdd)

    private fun safeAdd(first: Long, second: Long): Long =
        if (second > 0L && first > Long.MAX_VALUE - second) Long.MAX_VALUE else first + second

    private class MutableDay(var tokens: Long = 0L, var requests: Int = 0)

    companion object {
        const val DEFAULT_RELATIVE_PATH = "dsh/storages/token-stats/usage.jsonl"
        const val LEGACY_RELATIVE_PATH = ".dsh/storages/token-stats/usage.jsonl"
        private const val MAX_FILE_BYTES = 8L * 1024L * 1024L
    }
}
