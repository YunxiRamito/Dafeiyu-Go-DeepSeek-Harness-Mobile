package com.dafeiyu.mobile.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

/**
 * One active, explicit official DeepSeek HTTPS request per client. No retries, redirects or logs.
 * generate callbacks run on Dispatchers.IO; UI owners must switch to their own dispatcher.
 * Tool execution belongs to the caller; this transport only advertises a frozen schema.
 */
class DeepSeekChatClient internal constructor(
    private val keyProvider: () -> String?,
    private val config: Config,
    private val connectionFactory: (URL) -> HttpsURLConnection,
) {
    constructor(balanceStore: BalanceStore, config: Config = Config()) : this(
        { balanceStore.readApiKeyForRequest() }, config, { it.openConnection() as HttpsURLConnection },
    )

    data class FunctionCall(val id: String, val name: String, val arguments: String)
    data class FunctionTool(val name: String, val description: String, val parameters: JSONObject)
    data class HistoryMessage(
        val role: String,
        val content: String,
        val reasoningContent: String? = null,
        val toolCalls: List<FunctionCall> = emptyList(),
        val toolCallId: String? = null,
        val images: List<String> = emptyList(),
    )
    data class Request(
        val model: String,
        val history: List<HistoryMessage>,
        val thinking: Boolean? = true,
        val streaming: Boolean = true,
        val tools: List<FunctionTool> = emptyList(),
        val sequenceStart: Long = 0L,
        val reasoningEffort: String? = null,
        val modelCapabilities: Model? = null,
    )
    data class Turn(val snapshot: AssistantStreamSnapshot) {
        val completed: Boolean get() = snapshot.messages.isNotEmpty() &&
            snapshot.messages.all { it.status == AssistantMessageStatus.COMPLETED }
        fun assistantHistory(): HistoryMessage = HistoryMessage(
            role = "assistant",
            content = snapshot.messages.flatMap { it.blocks }
                .filterIsInstance<AssistantContentBlockSnapshot.Text>().joinToString("") { it.text },
            reasoningContent = snapshot.messages.flatMap { it.blocks }
                .filterIsInstance<AssistantContentBlockSnapshot.Reasoning>()
                .filter { it.kind == AssistantReasoningKind.USER_VISIBLE_THINKING }.joinToString("") { it.text },
            toolCalls = snapshot.toolCalls.map {
                FunctionCall(it.callId, it.name.orEmpty(), it.arguments)
            },
        )
    }
    data class Model(
        val id: String,
        val name: String?,
        val ownedBy: String?,
        val contextWindow: Long?,
        val maxOutputTokens: Long?,
        val inputModalities: List<String>,
        val outputModalities: List<String>,
        val supportedEffortLevels: List<String>,
        val defaultEffortLevel: String?,
    )
    data class Config(
        val connectTimeoutMs: Int = 20_000,
        val readTimeoutMs: Int = 660_000,
        val maxRequestBytes: Int = 4 * 1024 * 1024,
        val maxLineBytes: Int = 256 * 1024,
        val maxEventBytes: Int = 1024 * 1024,
        val maxResponseBytes: Int = 16 * 1024 * 1024,
    ) {
        init {
            require(connectTimeoutMs in 1..120_000 && readTimeoutMs in 1..1_800_000)
            require(maxRequestBytes in 1..16 * 1024 * 1024)
            require(maxLineBytes in 1..maxEventBytes && maxEventBytes <= maxResponseBytes)
            require(maxResponseBytes in 1..64 * 1024 * 1024)
        }
    }
    class DeepSeekChatException(
        val code: String,
        override val message: String,
        val httpStatus: Int? = null,
    ) : IOException(message)

    private class Call {
        @Volatile var cancelled = false
        @Volatile var connection: HttpsURLConnection? = null
        fun cancel() {
            cancelled = true
            connection?.disconnect()
        }
        fun checkActive() { if (cancelled) throw CallCancelled() }
    }
    private class CallCancelled : IOException()
    private class CallbackFailure(val original: Throwable) : RuntimeException()
    private val lock = Any()
    private var active: Call? = null

    /** Disconnects the active connection, including a blocked read. Does not cancel future calls. */
    fun cancel() { synchronized(lock) { active }?.cancel() }

    /** Local-only preflight; never reads credentials or opens a connection. */
    fun validateRequest(request: Request): Int = encodeRequest(request).size

    /**
     * Operational failures become MessageFailed; explicit cancel() becomes MessageCancelled.
     * Coroutine cancellation disconnects immediately and propagates CancellationException.
     * Callback failures propagate to the caller and are never mislabeled as provider failures.
     */
    suspend fun generate(request: Request, onEvent: suspend (AssistantStreamEvent) -> Unit): Turn {
        val frozen = request.copy(
            history = request.history.map { it.copy(toolCalls = it.toolCalls.toList(), images = it.images.toList()) },
            tools = request.tools.map { it.copy(parameters = JSONObject(it.parameters.toString())) },
            modelCapabilities = request.modelCapabilities?.let { it.copy(
                inputModalities = it.inputModalities.toList(),
                outputModalities = it.outputModalities.toList(),
                supportedEffortLevels = it.supportedEffortLevels.toList(),
            ) },
        )
        val protocol = DeepSeekChatProtocol(startSequenceNumber = frozen.sequenceStart,
            toolsConnected = frozen.tools.isNotEmpty())
        val accumulator = AssistantStreamReducer(startSequenceNumber = frozen.sequenceStart,
            source = AssistantStreamSource.BACKEND)
        val call = begin()
        try {
            cancellableIo(call) {
                var deliveredSequence = frozen.sequenceStart
                var terminalDelivered = false
                suspend fun emit(events: List<AssistantStreamEvent>, allowCancelled: Boolean = false) {
                    for (event in events) {
                        if (!allowCancelled) call.checkActive()
                        coroutineContext.ensureActive()
                        try { onEvent(event) } catch (error: Throwable) { throw CallbackFailure(error) }
                        accumulator.apply(event)
                        deliveredSequence = event.sequenceNumber + 1L
                        if (event is AssistantStreamEvent.MessageCompleted || event is AssistantStreamEvent.MessageIncomplete ||
                            event is AssistantStreamEvent.MessageFailed || event is AssistantStreamEvent.MessageCancelled) {
                            terminalDelivered = true
                        }
                    }
                }
                try {
                    val body = encodeRequest(frozen)
                    val connection = open(call, "chat/completions", "POST", frozen.streaming)
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.doOutput = true
                    call.checkActive()
                    connection.outputStream.use { it.write(body) }
                    call.checkActive()
                    requireOk(connection)
                    val contentType = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
                    if (contentType != if (frozen.streaming) "text/event-stream" else "application/json") {
                        throw DeepSeekChatException("PROTOCOL", "Unexpected DeepSeek response format")
                    }
                    connection.inputStream.buffered().use { input ->
                        if (frozen.streaming) {
                            val reader = DeepSeekSseReader(input, config.maxLineBytes, config.maxEventBytes,
                                config.maxResponseBytes, call::checkActive)
                            while (true) {
                                val data = reader.nextData() ?: break
                                call.checkActive()
                                if (data.trim() == "[DONE]") break
                                emit(protocol.accept(JSONObject(data)))
                            }
                            call.checkActive()
                            emit(protocol.end())
                        } else {
                            val response = JSONObject(readBody(input, call, config.maxResponseBytes))
                            emit(protocol.accept(response, streaming = false))
                        }
                    }
                } catch (error: CallbackFailure) {
                    throw error.original
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    coroutineContext.ensureActive()
                    if (call.cancelled || error is CallCancelled) {
                        if (!terminalDelivered) emit(protocol.cancelAfterDelivered(deliveredSequence), allowCancelled = true)
                    }
                    else {
                        val safe = safeError(error)
                        emit(protocol.fail(safe.message, safe.code))
                    }
                }
            }
        } finally { end(call) }
        return Turn(accumulator.finish())
    }

    /** Explicit GET https://api.deepseek.com/models. No cached or fabricated model IDs. */
    suspend fun listModels(): List<Model> {
        val call = begin()
        return try {
            cancellableIo(call) {
                try {
                    val connection = open(call, "models", "GET", streaming = false)
                    requireOk(connection)
                    connection.inputStream.buffered().use {
                        parseModels(JSONObject(readBody(it, call, minOf(config.maxResponseBytes, 1024 * 1024))))
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    coroutineContext.ensureActive()
                    if (call.cancelled) throw CancellationException("DeepSeek model listing cancelled")
                    throw safeError(error)
                }
            }
        } finally { end(call) }
    }

    private fun begin(): Call = synchronized(lock) {
        check(active == null) { "A DeepSeek request is already active on this client" }
        Call().also { active = it }
    }
    private fun end(call: Call) {
        call.connection?.disconnect()
        synchronized(lock) { if (active === call) active = null }
    }

    // Cancellation handlers run when cancellation is requested, not after blocking I/O finishes.
    private suspend fun <T> cancellableIo(call: Call, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            var worker: Job? = null
            continuation.invokeOnCancellation { call.cancel(); worker?.cancel() }
            worker = CoroutineScope(continuation.context).launch {
                try { continuation.resume(block()) }
                catch (error: Throwable) { continuation.resumeWithException(error) }
            }
            if (!continuation.isActive) worker.cancel()
        }
    }

    private fun open(call: Call, path: String, method: String, streaming: Boolean): HttpsURLConnection {
        call.checkActive()
        val key = try { keyProvider() }
        catch (_: Exception) { throw DeepSeekChatException("KEY_STORAGE", "Unable to read DeepSeek API Key") }
        if (key.isNullOrBlank()) throw DeepSeekChatException("MISSING_KEY", "DeepSeek API Key is not configured")
        if (key.any { it.isISOControl() }) throw DeepSeekChatException("KEY_STORAGE", "Invalid stored DeepSeek API Key")
        val connection = connectionFactory(URL("https://api.deepseek.com/$path"))
        call.connection = connection
        call.checkActive()
        connection.apply {
            requestMethod = method
            doInput = true
            useCaches = false
            instanceFollowRedirects = false
            connectTimeout = config.connectTimeoutMs
            readTimeout = config.readTimeoutMs
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Accept", if (streaming) "text/event-stream" else "application/json")
            if (method == "POST") setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        return connection
    }

    private fun requireOk(connection: HttpsURLConnection) {
        val status = connection.responseCode
        if (status != 200) {
            val detail = when (status) {
                400, 422 -> "request rejected"
                401, 403 -> "authentication or permission failed"
                402 -> "insufficient balance"
                429 -> "rate limit exceeded"
                in 300..399 -> "redirect refused"
                in 500..599 -> "service unavailable"
                else -> "request failed"
            }
            // Never display an error body: it may echo credentials or prompt contents.
            throw DeepSeekChatException("HTTP_$status", "DeepSeek $detail (HTTP $status)", status)
        }
    }

    private fun encodeRequest(request: Request): ByteArray {
        val reasoning = try {
            DeepSeekThinkingPolicy.validateRequest(request.model, request.thinking, request.reasoningEffort,
                request.modelCapabilities)
        } catch (_: IllegalArgumentException) {
            throw DeepSeekChatException("INVALID_REASONING", "Selected reasoning level is not supported by this model; refresh the model catalog")
        }
        if (request.model.isBlank() || request.model.length > 512 || request.model.any { it.isISOControl() } ||
            request.history.isEmpty() || request.history.size > 4096 ||
            request.history.any { it.role !in setOf("system", "user", "assistant", "tool") } ||
            request.sequenceStart < 0 || request.sequenceStart == Long.MAX_VALUE ||
            request.tools.size > 16 || request.tools.map { it.name }.distinct().size != request.tools.size) {
            throw DeepSeekChatException("INVALID_REQUEST", "Invalid DeepSeek request")
        }
        var characters = request.model.length.toLong()
        request.history.forEach {
            characters += it.content.length.toLong() + (it.reasoningContent?.length ?: 0) +
                it.images.sumOf { image -> image.length.toLong() } +
                it.toolCalls.sumOf { call -> call.arguments.length.toLong() }
        }
        if (characters > config.maxRequestBytes) throw DeepSeekChatException("REQUEST_LIMIT", "DeepSeek request is too large")
        val messages = JSONArray()
        request.history.forEach { message ->
            if ((message.toolCalls.isNotEmpty() || message.reasoningContent != null) && message.role != "assistant" ||
                (message.toolCallId != null) != (message.role == "tool") ||
                message.images.isNotEmpty() && message.role != "user" || message.images.size > 6 ||
                message.toolCalls.size > 16 || message.toolCalls.any {
                    !validIdentifier(it.id) || !validIdentifier(it.name) || it.arguments.length > 320 * 1024
                } || message.toolCallId?.let { !validIdentifier(it) } == true ||
                message.images.any { !validImageData(it) }) {
                throw DeepSeekChatException("INVALID_REQUEST", "Invalid DeepSeek message")
            }
            val item = JSONObject().put("role", message.role)
            if (message.images.isEmpty()) item.put("content", message.content)
            else {
                val parts = JSONArray().put(JSONObject().put("type", "text").put("text", message.content))
                message.images.forEach {
                    parts.put(JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", it)))
                }
                item.put("content", parts)
            }
            message.reasoningContent?.let { item.put("reasoning_content", it) }
            message.toolCallId?.let { item.put("tool_call_id", it) }
            if (message.toolCalls.isNotEmpty()) item.put("tool_calls", JSONArray().also { calls ->
                message.toolCalls.forEach { call ->
                    calls.put(JSONObject().put("id", call.id).put("type", "function")
                        .put("function", JSONObject().put("name", call.name).put("arguments", call.arguments)))
                }
            })
            messages.put(item)
        }
        val root = JSONObject().put("model", request.model).put("messages", messages)
            .put("stream", request.streaming)
        reasoning.thinking?.let {
            root.put("thinking", JSONObject().put("type", if (it) "enabled" else "disabled"))
        }
        reasoning.reasoningEffort?.let { root.put("reasoning_effort", it) }
        if (request.streaming) root.put("stream_options", JSONObject().put("include_usage", true))
        if (request.tools.isNotEmpty()) {
            root.put("tools", JSONArray().also { tools ->
                request.tools.forEach { tool ->
                    if (!validIdentifier(tool.name) || tool.description.length > 4096 ||
                        tool.parameters.optString("type") != "object") {
                        throw DeepSeekChatException("INVALID_REQUEST", "Invalid DeepSeek tool schema")
                    }
                    tools.put(JSONObject().put("type", "function").put("function",
                        JSONObject().put("name", tool.name).put("description", tool.description)
                            .put("parameters", tool.parameters)))
                }
            }).put("tool_choice", "auto")
        }
        val bytes = root.toString().toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > config.maxRequestBytes) throw DeepSeekChatException("REQUEST_LIMIT", "DeepSeek request is too large")
        return bytes
    }

    private fun validIdentifier(value: String): Boolean =
        value.isNotBlank() && value.length <= 512 && value.none { it.isISOControl() }

    private fun validImageData(value: String): Boolean {
        val prefix = Regex("^data:image/(jpeg|png|gif|webp);base64,").find(value)?.value ?: return false
        val body = value.substring(prefix.length)
        if (body.isEmpty() || body.length % 4 != 0) return false
        return runCatching {
            val decoded = java.util.Base64.getDecoder().decode(body)
            decoded.isNotEmpty() && java.util.Base64.getEncoder().encodeToString(decoded) == body
        }.getOrDefault(false)
    }

    private fun readBody(input: InputStream, call: Call, limit: Int): String {
        val output = ByteArrayOutputStream(minOf(limit, 4096))
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            call.checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw DeepSeekChatException("RESPONSE_LIMIT", "DeepSeek response is too large")
            output.write(buffer, 0, count)
        }
        call.checkActive()
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
    }

    private fun parseModels(root: JSONObject): List<Model> = DeepSeekModelCatalog.decode(root)
    private fun safeError(error: Exception): DeepSeekChatException = when (error) {
        is DeepSeekChatException -> error
        is SocketTimeoutException -> DeepSeekChatException("TIMEOUT", "DeepSeek request timed out")
        is SSLException -> DeepSeekChatException("TLS", "DeepSeek TLS verification failed")
        is org.json.JSONException -> DeepSeekChatException("PROTOCOL", "Invalid DeepSeek JSON response")
        is IllegalArgumentException, is IllegalStateException -> DeepSeekChatException("PROTOCOL", "Invalid DeepSeek model capability")
        is IOException -> DeepSeekChatException("NETWORK_OR_PROTOCOL", "DeepSeek connection or response failed")
        else -> DeepSeekChatException("REQUEST_FAILED", "DeepSeek request failed")
    }
}
