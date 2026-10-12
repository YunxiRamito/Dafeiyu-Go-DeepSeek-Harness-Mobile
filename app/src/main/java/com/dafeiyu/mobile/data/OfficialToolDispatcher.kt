package com.dafeiyu.mobile.data

import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * One dispatcher per user turn. Its ledger prevents replayed call IDs from creating duplicate paid work.
 * Background runners do not receive this dispatcher, so subagents cannot recursively spawn tasks.
 */
class OfficialToolDispatcher(
    private val engineProvider: () -> BackgroundTaskEngine?,
    private val outputStore: OfficialToolOutputStore,
    conversationId: String,
    private val defaultModel: () -> String?,
    private val taskDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val weatherProvider: OfficialToolWeatherProvider? = null,
    private val subagentWaitMillis: Long = 15 * 60_000L,
    private val webProvider: OfficialToolWebProvider? = null,
    private val scriptProvider: OfficialToolScriptProvider? = null,
) {
    init { require(subagentWaitMillis in 1..30 * 60_000L) }
    private val conversationId = OfficialToolContract.conversationId(conversationId)
    private val mutex = Mutex()
    private data class Cached(val fingerprint: String, val result: OfficialToolResult)
    private data class OwnedSubagent(val taskId: String, val engine: BackgroundTaskEngine)
    private val ledger = linkedMapOf<String, Cached>()
    private val subagents = linkedMapOf<String, OwnedSubagent>()
    private var createdTasks = 0
    private var createdFiles = 0

    fun schemas(): JSONArray = OfficialToolContract.schemas(includeWeather = weatherProvider != null,
        includeWeatherSelection = weatherProvider is OfficialToolWeatherSelectionProvider,
        includeWeb = webProvider != null, includeScripts = scriptProvider != null)

    suspend fun executeBatch(calls: List<OfficialToolCall>): List<OfficialToolResult> {
        require(calls.size in 1..OfficialToolContract.MAX_BATCH_CALLS)
        require(calls.map { it.id }.distinct().size == calls.size)
        return calls.map { execute(it) }
    }

    /** Wait outside the side-effect ledger and its mutex; sibling tasks are already admitted. */
    suspend fun awaitSubagent(result: OfficialToolResult): OfficialToolResult {
        if (result.name != "create_subagent") return result
        return try {
            currentCoroutineContext().ensureActive()
            val cached = mutex.withLock { ledger[result.callId] }
            if (cached?.result != result) return unavailableSubagent(result)
            val response = JSONObject(result.content)
            if (!response.getBoolean("ok")) return result
            val data = response.getJSONObject("data")
            val owned = mutex.withLock { subagents[result.callId] }
                ?: return unavailableSubagent(result)
            val taskId = data.getString("task_id")
            if (taskId != owned.taskId || engineProvider() !== owned.engine) return unavailableSubagent(result)
            fun belongs(task: BackgroundTask?): Boolean = task != null && task.id == owned.taskId &&
                task.parentConversationId == conversationId && task.kind == BackgroundTaskKind.SUBAGENT
            val before = owned.engine.tasks.value.firstOrNull { it.id == taskId }
            if (!belongs(before)) return unavailableSubagent(result)
            if (!data.getBoolean("run_requested") || !data.isNull("start_error")) return result
            val terminal = withTimeoutOrNull(subagentWaitMillis) { owned.engine.awaitTerminal(taskId) }
            // The task may have been deleted, or the selected engine replaced, while suspended.
            val after = owned.engine.tasks.value.firstOrNull { it.id == taskId }
            val stillOwned = mutex.withLock {
                ledger[result.callId] == cached && subagents[result.callId] == owned
            }
            if (!stillOwned || engineProvider() !== owned.engine || !belongs(after) ||
                terminal != null && !belongs(terminal)) return unavailableSubagent(result)
            val current = terminal ?: checkNotNull(after)
            val text = if (current.status == BackgroundTaskStatus.SUCCEEDED) current.result else ""
            result.copy(content = JSONObject().put("ok", true).put("data", summary(current)
                .put("run_requested", true).put("wait_timed_out", terminal == null)
                .put("result", text.take(OfficialToolContract.MAX_RESULT_CHARACTERS))
                .put("result_truncated", text.length > OfficialToolContract.MAX_RESULT_CHARACTERS)).toString())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            unavailableSubagent(result)
        }
    }

    private fun unavailableSubagent(result: OfficialToolResult): OfficialToolResult =
        result.copy(content = JSONObject().put("ok", false).put("error", "subagent_result_unavailable").toString(),
            outputs = emptyList())

    suspend fun execute(call: OfficialToolCall): OfficialToolResult = mutex.withLock {
        currentCoroutineContext().ensureActive()
        if (runCatching { OfficialToolContract.checkId(call.id) }.isFailure) return@withLock error(call, "invalid_call_id")
        if (call.name !in OfficialToolContract.names) return@withLock error(call, "unsupported_tool")
        val arguments = try {
            OfficialToolContract.arguments(call.arguments)
        } catch (_: Exception) {
            return@withLock error(call, "invalid_arguments")
        }
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(OfficialToolContract.utf8(call.name + "\n" + call.arguments))
            .joinToString("") { "%02x".format(it) }
        ledger[call.id]?.let {
            return@withLock if (it.fingerprint == fingerprint) it.result else error(call, "call_id_reused")
        }
        if (ledger.size >= OfficialToolContract.MAX_SESSION_CALLS) return@withLock error(call, "tool_call_limit")
        val result = try {
            // Once admitted, complete and remember bounded side effects even if the caller leaves.
            // These operations only enqueue work or commit a small local file, never wait for model I/O.
            if (call.name in setOf("get_weather", "web_search", "browser", "run_script")) {
                when (call.name) {
                    "get_weather" -> weather(call, arguments)
                    "run_script" -> script(call, arguments)
                    else -> web(call, arguments)
                }.also { ledger[call.id] = Cached(fingerprint, it) }
            } else withContext(NonCancellable) {
                when (call.name) {
                    "create_subagent", "create_background_task" -> create(call, arguments)
                    "write_text_file" -> write(call, arguments)
                    "list_tasks" -> list(call, arguments)
                    "get_task" -> get(call, arguments)
                    else -> error(call, "unsupported_tool")
                }.also { ledger[call.id] = Cached(fingerprint, it) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: OfficialWebException) {
            error(call, failed.code, failed.details).also { ledger[call.id] = Cached(fingerprint, it) }
        } catch (failed: OfficialScriptException) {
            error(call, failed.code, failed.details).also { ledger[call.id] = Cached(fingerprint, it) }
        } catch (_: IllegalArgumentException) {
            error(call, "invalid_arguments").also { ledger[call.id] = Cached(fingerprint, it) }
        } catch (_: Exception) {
            error(call, "operation_failed").also { ledger[call.id] = Cached(fingerprint, it) }
        }
        result
    }

    private suspend fun script(call: OfficialToolCall, args: JSONObject): OfficialToolResult {
        val provider = scriptProvider ?: return error(call, "linux_runtime_unavailable")
        fields(args, setOf("language", "script", "timeout_seconds"), setOf("language", "script"))
        val language = string(args, "language", 16)
        require(language in setOf("bash", "node", "python"))
        val body = string(args, "script", OfficialToolContract.MAX_TEXT_CHARACTERS)
        val timeout = if (args.has("timeout_seconds")) {
            val value = args.get("timeout_seconds")
            require(value is Int || value is Long)
            (value as Number).toLong().also { require(it in 1..120) }.toInt()
        } else 30
        val data = provider.execute(language, body, timeout)
        var truncated = data.optBoolean("output_truncated", false)
        for (field in listOf("stdout", "stderr")) {
            val output = data.optString(field, "")
            if (output.length > OfficialToolContract.MAX_RESULT_CHARACTERS / 2) {
                var end = OfficialToolContract.MAX_RESULT_CHARACTERS / 2
                if (output[end - 1].isHighSurrogate()) end--
                data.put(field, output.substring(0, end))
                truncated = true
            }
        }
        data.put("output_truncated", truncated)
        return success(call, data)
    }

    private fun fields(args: JSONObject, allowed: Set<String>, required: Set<String> = emptySet()) {
        val keys = args.keys().asSequence().toSet()
        require(keys.all { it in allowed } && keys.containsAll(required))
    }

    private fun string(args: JSONObject, field: String, max: Int, allowEmpty: Boolean = false): String =
        OfficialToolContract.safeText(args.get(field) as? String ?: throw IllegalArgumentException(), max, allowEmpty)

    private suspend fun create(call: OfficialToolCall, args: JSONObject): OfficialToolResult {
        fields(args, setOf("title", "prompt", "model", "run"), setOf("title", "prompt"))
        val title = string(args, "title", 80)
        val prompt = string(args, "prompt", BackgroundTaskLimits.MAX_INPUT)
        val run = if (args.has("run")) args.get("run") as? Boolean ?: throw IllegalArgumentException() else false
        if (createdTasks >= OfficialToolContract.MAX_SESSION_TASKS) return error(call, "task_creation_limit")
        return withContext(taskDispatcher) {
            val model = if (args.has("model")) string(args, "model", 128)
                else defaultModel()?.let { OfficialToolContract.safeText(it, 128) }
                    ?: return@withContext error(call, "model_required")
            val engine = engineProvider() ?: return@withContext error(call, "task_engine_unavailable")
            if (run && !engine.canRun(model).runnable) return@withContext error(call, "model_unavailable")
            if (engine.tasks.value.size >= BackgroundTaskLimits.MAX_TASKS) return@withContext error(call, "task_limit")
            val kind = if (call.name == "create_subagent") BackgroundTaskKind.SUBAGENT else BackgroundTaskKind.BACKGROUND
            val task = engine.create(title, model, prompt, kind, conversationId)
            createdTasks++
            var startError: String? = null
            if (run) {
                try {
                    engine.enqueue(task.id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    startError = "task_not_started"
                }
            }
            val current = engine.tasks.value.first { it.id == task.id }
            success(call, summary(current).put("run_requested", run)
                .put("start_error", startError ?: JSONObject.NULL)).also {
                if (kind == BackgroundTaskKind.SUBAGENT) subagents[call.id] = OwnedSubagent(task.id, engine)
            }
        }
    }

    private suspend fun write(call: OfficialToolCall, args: JSONObject): OfficialToolResult {
        fields(args, setOf("filename", "text"), setOf("filename", "text"))
        val filename = OfficialToolContract.filename(string(args, "filename", 96))
        val text = string(args, "text", OfficialToolContract.MAX_TEXT_CHARACTERS, allowEmpty = true)
        if (createdFiles >= OfficialToolContract.MAX_SESSION_FILES) return error(call, "file_creation_limit")
        val output = withContext(Dispatchers.IO) { outputStore.write(filename, text, conversationId) }
        createdFiles++
        return success(call, JSONObject().put("output_id", output.id).put("filename", output.filename)
            .put("mime_type", output.mimeType).put("size_bytes", output.sizeBytes)
            .put("storage", "managed_app").put("export_action_required", false), listOf(output))
    }

    private suspend fun list(call: OfficialToolCall, args: JSONObject): OfficialToolResult {
        fields(args, setOf("limit", "kind"))
        val limit = if (args.has("limit")) {
            val number = args.get("limit")
            require(number is Int || number is Long)
            (number as Number).toLong().also { require(it in 1..50) }.toInt()
        } else 20
        val kind = if (args.has("kind")) BackgroundTaskKind.valueOf(string(args, "kind", 16)) else null
        return withContext(taskDispatcher) {
            val engine = engineProvider() ?: return@withContext error(call, "task_engine_unavailable")
            val tasks = engine.tasks.value.filter {
                it.parentConversationId == conversationId && (kind == null || it.kind == kind)
            }
            success(call, JSONObject().put("tasks", JSONArray(tasks.take(limit).map(::summary)))
                .put("total", tasks.size).put("truncated", tasks.size > limit))
        }
    }

    private suspend fun get(call: OfficialToolCall, args: JSONObject): OfficialToolResult {
        fields(args, setOf("task_id"), setOf("task_id"))
        val id = string(args, "task_id", 36)
        require(OfficialToolContract.uuidPattern.matches(id))
        return withContext(taskDispatcher) {
            val engine = engineProvider() ?: return@withContext error(call, "task_engine_unavailable")
            val task = engine.tasks.value.firstOrNull { it.id == id && it.parentConversationId == conversationId }
                ?: return@withContext error(call, "task_not_found")
            val result = if (task.status == BackgroundTaskStatus.SUCCEEDED) task.result else ""
            success(call, summary(task).put("result", result.take(OfficialToolContract.MAX_RESULT_CHARACTERS))
                .put("result_truncated", result.length > OfficialToolContract.MAX_RESULT_CHARACTERS))
        }
    }

    private suspend fun weather(call: OfficialToolCall, args: JSONObject): OfficialToolResult {
        fields(args, setOf("city", "country", "location_id"), setOf("city"))
        val city = string(args, "city", 80).trim()
        require(city.length >= 2 && city.none(Char::isISOControl) &&
            city.none { it in "/\\:@?#" } && !city.contains("://"))
        val country = if (args.has("country")) string(args, "country", 2).also {
            require(it.matches(Regex("[A-Za-z]{2}")))
        }.uppercase(Locale.ROOT) else null
        val provider = weatherProvider ?: return error(call, "weather_unavailable")
        val locationId = if (args.has("location_id")) {
            val number = args.get("location_id")
            require(number is Int || number is Long)
            (number as Number).toLong().also { require(it > 0) }
        } else null
        if (locationId != null && provider !is OfficialToolWeatherSelectionProvider) {
            return error(call, "weather_selection_unavailable")
        }
        val data = withTimeoutOrNull(OfficialToolContract.WEATHER_TIMEOUT_MILLIS) {
            withContext(Dispatchers.IO) {
                if (locationId == null) provider.weather(city, country)
                else (provider as OfficialToolWeatherSelectionProvider).weather(city, country, locationId)
            }
        } ?: return error(call, "weather_timeout")
        if (runCatching { checkWeatherData(data) }.isFailure) return error(call, "weather_invalid_response")
        val serialized = data.toString()
        OfficialToolContract.safeText(serialized, 32_768)
        require(OfficialToolContract.utf8(serialized).size <= 65_536)
        return success(call, data)
    }

    private suspend fun web(call: OfficialToolCall, args: JSONObject): OfficialToolResult {
        val provider = webProvider ?: return error(call, "web_unavailable")
        val data = withTimeoutOrNull(OfficialToolContract.WEB_TIMEOUT_MILLIS) {
            if (call.name == "web_search") {
                fields(args, setOf("query", "limit"), setOf("query"))
                val query = string(args, "query", 512).trim()
                require(query.none(Char::isISOControl))
                val limit = if (args.has("limit")) {
                    val value = args.get("limit")
                    require(value is Int || value is Long)
                    (value as Number).toLong().also { require(it in 1..8) }.toInt()
                } else 5
                provider.search(query, limit)
            } else {
                fields(args, setOf("url"), setOf("url"))
                val url = string(args, "url", 2048)
                require(url.none(Char::isISOControl))
                provider.browse(url)
            }
        } ?: return error(call, "web_timeout")
        return success(call, data)
    }

    private fun checkWeatherData(data: JSONObject) {
        var nodes = 0
        val sensitive = Regex("(?i)(?:api[_-]?key|access[_-]?token|authorization|password|private[_-]?key)")
        fun check(value: Any, depth: Int) {
            require(depth <= 16 && ++nodes <= 4096)
            when (value) {
                is JSONObject -> value.keys().forEach { key ->
                    OfficialToolContract.safeText(key, 128)
                    require(!sensitive.matches(key))
                    check(value.get(key), depth + 1)
                }
                is JSONArray -> {
                    require(value.length() <= 4096)
                    for (index in 0 until value.length()) check(value.get(index), depth + 1)
                }
                is String -> OfficialToolContract.safeText(value, 32_768, allowEmpty = true)
                is Number -> require(value.toDouble().isFinite())
                is Boolean -> Unit
                else -> require(value === JSONObject.NULL)
            }
        }
        check(data, 0)
    }

    private fun summary(task: BackgroundTask): JSONObject = JSONObject().put("task_id", task.id)
        .put("title", task.title).put("model", task.model).put("kind", task.kind.name)
        .put("status", task.status.name).put("error_code", task.errorCode ?: JSONObject.NULL)

    private fun success(
        call: OfficialToolCall,
        data: JSONObject,
        outputs: List<OfficialToolOutput> = emptyList(),
    ): OfficialToolResult {
        val text = JSONObject().put("ok", true).put("data", data).toString()
        require(OfficialToolContract.utf8(text).size <= OfficialToolContract.MAX_REPLY_BYTES)
        return OfficialToolResult(call.id, call.name, text, outputs)
    }

    private fun error(call: OfficialToolCall, code: String, details: JSONObject? = null): OfficialToolResult =
        OfficialToolResult(call.id, call.name, JSONObject().put("ok", false).put("error", code).apply {
            if (details != null) put("details", details)
        }.toString())
}
