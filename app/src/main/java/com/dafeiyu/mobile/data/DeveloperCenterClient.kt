package com.dafeiyu.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Explicit-call data layer for the public DeveloperCenter API.
 *
 * This class does not start polling, persist credentials, create an installation
 * id, or send device information. Callers decide when to invoke each method.
 * The feedback endpoint has no title field, so postFeedback() sends the title
 * as the first plain-text line of the server's required body field.
 */
class DeveloperCenterClient(
    private val config: Config = Config(),
) {
    data class Config(
        /** Null keeps the client explicitly offline/preview-only. */
        val baseUrl: String? = DEFAULT_BASE_URL,
        val connectTimeoutMs: Int = 1_500,
        val readTimeoutMs: Int = 3_000,
    )

    enum class ErrorKind {
        PREVIEW,
        INVALID_INPUT,
        ENDPOINT_REJECTED,
        NETWORK,
        HTTP,
        MALFORMED_RESPONSE,
    }

    class DeveloperCenterException(
        val kind: ErrorKind,
        override val message: String,
        val httpStatus: Int? = null,
        val serverMessage: String? = null,
        cause: Throwable? = null,
    ) : IOException(message, cause)

    data class DshBundleManifest(
        val version: String,
        val platform: String,
        val nodeMajor: Int,
        val sha256: String,
        val sizeBytes: Long,
        val fileName: String,
    )

    data class ServerMetrics(
        val current: ServerMetricsSnapshot?,
        val recentSamples: List<ServerMetricsSnapshot>,
        val windowMinutes: Int?,
    )

    data class ServerMetricsSnapshot(
        val observedAt: String?,
        val cpuPercent: Double?,
        val processor: String?,
        val logicalProcessors: Int?,
        val memoryUsedBytes: Long?,
        val memoryTotalBytes: Long?,
        val memoryPercent: Double?,
        val networkReceiveBytesPerSecond: Double?,
        val networkSendBytesPerSecond: Double?,
        val diskUsedBytes: Long?,
        val diskTotalBytes: Long?,
        val diskPercent: Double?,
    )

    data class PresenceHistory(
        val onlineCount: Int,
        val onlineInstallations: Int?,
        val onlineWindowSeconds: Int?,
        val launcherVersions: List<LauncherVersionCount>,
        val history: List<PresenceHistoryPoint>,
        val windowHours: Int?,
        val sampleIntervalSeconds: Int?,
    )

    data class LauncherVersionCount(
        val version: String,
        val count: Int,
    )

    data class PresenceHistoryPoint(
        val observedAt: String,
        val onlineCount: Int,
    )

    data class Acknowledgement(
        val id: UUID,
        val name: String,
        val category: String,
        val link: String?,
        val role: String?,
        val contribution: String?,
        val sortOrder: Int,
        val createdAt: String,
        val updatedAt: String,
        val avatarUrl: String?,
        val avatarWidth: Int?,
        val avatarHeight: Int?,
        val avatarBytes: Int?,
    )

    /** The public feedback view returned with HTTP 201. */
    data class FeedbackSubmission(
        val id: UUID,
        val title: String,
        val number: Long?,
        val category: String,
        val status: String?,
        val body: String,
        val launcherVersion: String,
        val createdAt: String?,
        val updatedAt: String?,
        val reply: String?,
        val mine: Boolean?,
        val upvoteCount: Int?,
        val hasUpvoted: Boolean?,
        val supplementCount: Int?,
    )

    /** Result returned by POST/DELETE /api/feedback/{id}/upvotes. */
    enum class MessageMetricKind(val wireValue: String) {
        READ("Read"),
        CLICK("Click"),
        DELIVERED("Delivered"),
        DISPLAYED("Displayed"),
        DEFERRED("Deferred"),
    }

    data class FeedbackUpvoteResult(
        val upvoteCount: Int,
        val hasUpvoted: Boolean,
    )

    /** Current anonymous-machine feedback ban state from /api/feedback/ban-status. */
    data class FeedbackBanStatus(
        val banned: Boolean,
        val reason: String?,
        val expiresAt: String?,
        val permanent: Boolean,
    )

    data class FeedbackSupplement(
        val id: UUID,
        val body: String,
        val createdAt: String,
        val isDeveloperReply: Boolean,
        val hasLogs: Boolean,
    )

    data class FeedbackItem(
        val id: UUID,
        val number: Long?,
        val upvoteCount: Int?,
        val hasUpvoted: Boolean?,
        val category: String,
        val status: String,
        val body: String,
        val launcherVersion: String,
        val createdAt: String,
        val updatedAt: String,
        val reply: String?,
        val latestDeveloperReply: FeedbackSupplement?,
        val hasLogs: Boolean,
        val mine: Boolean,
        val supplementCount: Int,
        val nextSupplementOffset: Int?,
        val supplements: List<FeedbackSupplement>,
    )

    data class FeedbackPage(
        val feedback: List<FeedbackItem>,
        val nextOffset: Int?,
        val totalCount: Int?,
    )

    data class FeedbackSupplementsPage(
        val supplements: List<FeedbackSupplement>,
        val nextOffset: Int?,
    )

    /**
     * Reads the 24-hour online history and current version distribution.
     * This method performs one request only when the caller explicitly invokes it.
     */
    suspend fun getPresenceHistory(): Result<PresenceHistory> = withContext(Dispatchers.IO) {
        execute {
            val json = getJson("/api/presence/history")
            parsePresenceHistory(json)
        }
    }

    /**
     * Reads the public server resource snapshot and its optional recent samples.
     * Missing or malformed metric fields are represented as null so one partial
     * server response does not discard the rest of the usable data.
     */
    suspend fun getServerMetrics(): Result<ServerMetrics> = withContext(Dispatchers.IO) {
        execute {
            val json = getJson("/api/server/metrics")
            parseServerMetrics(json)
        }
    }

    /** Reads a verified Windows DSH bundle manifest; this endpoint never describes Android APKs. */
    suspend fun getDshBundleManifest(version: String): Result<DshBundleManifest> = withContext(Dispatchers.IO) {
        execute {
            val normalized = version.trim()
            require(normalized.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "版本号格式无效" }
            val pathVersion = URLEncoder.encode(normalized, Charsets.UTF_8.name()).replace("+", "%20")
            val json = getJson("/api/dsh/bundles/$pathVersion/manifest")
            val manifest = DshBundleManifest(
                version = requiredString(json, "version"),
                platform = requiredString(json, "platform"),
                nodeMajor = requiredInt(json, "nodeMajor"),
                sha256 = requiredString(json, "sha256").lowercase(),
                sizeBytes = requiredLong(json, "sizeBytes"),
                fileName = requiredString(json, "fileName"),
            )
            require(manifest.version == normalized) { "服务器返回的版本与请求不一致" }
            require(manifest.platform == "win-x64" && manifest.nodeMajor == 22) { "该 bundle 不是受支持的 Windows x64 Node 22 包" }
            require(manifest.sha256.matches(Regex("[0-9a-f]{64}"))) { "服务器返回的 SHA-256 无效" }
            require(manifest.sizeBytes > 0L && manifest.fileName == "dsh-${manifest.version}-win-x64-node22.zip") { "服务器返回的 bundle 元数据无效" }
            manifest
        }
    }

    /** Reads the published public message feed; cache persistence is left to the caller. */
    suspend fun getPublicMessages(installationId: String): Result<List<NoticeStore.Notice>> = withContext(Dispatchers.IO) {
        execute {
            val installation = parseInstallationId(installationId)
            val json = getJson("/api/messages?installationId=$installation")
            try {
                NoticeStore.parseRemoteFeed(json)
            } catch (error: Exception) {
                throw malformed("message feed is invalid", error)
            }
        }
    }

    /** Sends one idempotent per-installation message display/read/click metric. */
    suspend fun postMessageMetric(
        messageId: String,
        installationId: String,
        kind: MessageMetricKind,
        buttonPosition: Int? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        execute {
            val id = parseFeedbackUuid(messageId, "messageId")
            val installation = parseInstallationId(installationId)
            if (kind == MessageMetricKind.CLICK) {
                if (buttonPosition == null || buttonPosition !in 0..1) {
                    throw invalidInput("buttonPosition is required for click metrics")
                }
            } else if (buttonPosition != null) {
                throw invalidInput("buttonPosition is only valid for click metrics")
            }
            val payload = JSONObject()
                .put("installationId", installation.toString())
                .put("kind", kind.wireValue)
            buttonPosition?.let { payload.put("buttonPosition", it) }
            val response = request("POST", "/api/messages/$id/metrics", payload.toString())
            requireSuccess(response)
            Unit
        }
    }

    /** Reads the public acknowledgement list. */
    suspend fun getAcknowledgements(): Result<List<Acknowledgement>> = withContext(Dispatchers.IO) {
        execute {
            val json = getJson("/api/acknowledgements")
            parseAcknowledgements(json)
        }
    }

    /**
     * Reads the current installation's public feedback once when explicitly called.
     * The list endpoint identifies ownership with machineId; installationId is
     * validated here for the caller's identity contract and is intentionally not
     * sent because the public GET endpoint does not accept it.
     */
    suspend fun getFeedback(
        machineId: String,
        installationId: String,
        offset: Int = 0,
        limit: Int = MAX_FEEDBACK_LIST_LIMIT,
    ): Result<FeedbackPage> = withContext(Dispatchers.IO) {
        execute {
            val machine = validateMachineId(machineId)
            parseInstallationId(installationId)
            if (offset < 0) throw invalidInput("feedback offset must be non-negative")
            if (limit !in 1..MAX_FEEDBACK_LIST_LIMIT) {
                throw invalidInput("feedback limit must be between 1 and $MAX_FEEDBACK_LIST_LIMIT")
            }
            val query = buildString {
                append("/api/feedback?machineId=")
                append(encodeQuery(machine))
                append("&mine=true&offset=")
                append(offset)
                append("&limit=")
                append(limit)
                append("&sort=updated")
            }
            parseFeedbackPage(getJson(query))
        }
    }

    /** Reads older public conversation supplements for one feedback item. */
    suspend fun getFeedbackSupplements(
        feedbackId: String,
        offset: Int = 0,
    ): Result<FeedbackSupplementsPage> = withContext(Dispatchers.IO) {
        execute {
            val id = parseFeedbackUuid(feedbackId, "feedbackId")
            if (offset < 0) throw invalidInput("supplement offset must be non-negative")
            parseFeedbackSupplements(getJson("/api/feedback/$id/supplements?offset=$offset"))
        }
    }

    /**
     * Adds this anonymous machine's idempotent +1 support to a feedback item.
     * HTTP 200 response shape: {"upvoteCount": number, "hasUpvoted": boolean}.
     */
    suspend fun postFeedbackUpvote(
        feedbackId: String,
        machineId: String,
    ): Result<FeedbackUpvoteResult> = withContext(Dispatchers.IO) {
        execute {
            val id = parseFeedbackUuidInput(feedbackId, "feedbackId")
            val machine = validateMachineId(machineId)
            val requestBody = JSONObject()
                .put("machineId", machine)
                .toString()
            val response = request("POST", "/api/feedback/$id/upvotes", requestBody)
            requireSuccess(response)
            parseFeedbackUpvoteResult(
                response.body ?: throw malformed("feedback upvote response body is empty"),
            )
        }
    }

    /**
     * Idempotently removes this anonymous machine's support from a feedback item.
     * HTTP 200 response shape: {"upvoteCount": number, "hasUpvoted": boolean}.
     */
    suspend fun deleteFeedbackUpvote(
        feedbackId: String,
        machineId: String,
    ): Result<FeedbackUpvoteResult> = withContext(Dispatchers.IO) {
        execute {
            val id = parseFeedbackUuidInput(feedbackId, "feedbackId")
            val machine = validateMachineId(machineId)
            val query = encodeQuery(machine)
            val response = request("DELETE", "/api/feedback/$id/upvotes?machineId=$query")
            requireSuccess(response)
            parseFeedbackUpvoteResult(
                response.body ?: throw malformed("feedback upvote response body is empty"),
            )
        }
    }

    /** Reads the current feedback ban state for a caller-supplied anonymous machine ID. */
    suspend fun getFeedbackBanStatus(machineId: String): Result<FeedbackBanStatus> =
        withContext(Dispatchers.IO) {
            execute {
                val machine = validateMachineId(machineId)
                val query = encodeQuery(machine)
                parseFeedbackBanStatus(getJson("/api/feedback/ban-status?machineId=$query"))
            }
        }

    /**
     * Adds a text-only supplement to a feedback item owned by the same anonymous machine.
     * HTTP 200 response shape is the full feedback view parsed as [FeedbackItem]:
     * `{id, number, upvoteCount, hasUpvoted, category, status, body, launcherVersion,
     * createdAt, updatedAt, reply, latestDeveloperReply, hasLogs, mine, supplementCount,
     * nextSupplementOffset, supplements}`. It is not a standalone FeedbackSupplement.
     */
    suspend fun postFeedbackSupplement(
        feedbackId: String,
        installationId: String,
        machineId: String,
        body: String,
    ): Result<FeedbackItem> = withContext(Dispatchers.IO) {
        execute {
            val id = parseFeedbackUuidInput(feedbackId, "feedbackId")
            val installationUuid = parseInstallationId(installationId)
            val machine = validateMachineId(machineId)
            val normalizedBody = validateBody(body)
            val requestBody = JSONObject()
                .put("installationId", installationUuid.toString())
                .put("machineId", machine)
                .put("body", normalizedBody)
                .toString()
            val response = request("POST", "/api/feedback/$id/supplements", requestBody)
            requireSuccess(response)
            parseFeedbackItemResponse(
                response.body ?: throw malformed("feedback supplement response body is empty"),
            )
        }
    }

    /**
     * Submits anonymous feedback using only caller-supplied identifiers.
     *
     * DeveloperCenter requires both installationId and machineId. This API
     * accepts a separate random machineId so feedback ownership can be
     * rotated independently from the heartbeat installation identity; no
     * Android/device identifier is read.
     */
    suspend fun postFeedback(
        category: String,
        title: String,
        body: String,
        installationId: String,
        launcherVersion: String,
        machineId: String = installationId,
    ): Result<FeedbackSubmission> = withContext(Dispatchers.IO) {
        execute {
            val normalizedCategory = normalizeCategory(category)
            val normalizedTitle = validateTitle(title)
            val normalizedBody = validateBody(body)
            val installationUuid = parseInstallationId(installationId)
            val machine = validateMachineId(machineId)
            val normalizedVersion = validateLauncherVersion(launcherVersion)
            val combinedBody = "$normalizedTitle\n\n$normalizedBody"
            if (combinedBody.length > MAX_FEEDBACK_BODY_LENGTH) {
                throw invalidInput("title and body together must be at most 12000 characters")
            }

            val requestBody = JSONObject()
                .put("installationId", installationUuid.toString())
                .put("machineId", machine)
                .put("category", normalizedCategory)
                .put("body", combinedBody)
                .put("launcherVersion", normalizedVersion)
                .toString()
            val response = request("POST", "/api/feedback", requestBody)
            requireSuccess(response)
            parseFeedbackSubmission(response.body ?: throw malformed("feedback response body is empty"), normalizedTitle)
        }
    }

    private fun <T> execute(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(asClientException(error))
    }

    private fun getJson(path: String): JSONObject {
        val response = request("GET", path)
        requireSuccess(response)
        val body = response.body ?: throw malformed("response body is empty")
        return try {
            JSONObject(body)
        } catch (error: Exception) {
            throw malformed("response is not valid JSON", error)
        }
    }

    private fun request(method: String, path: String, body: String? = null): HttpResult {
        val baseUrl = normalizeBaseUrl(config.baseUrl)
        val uri = URI("$baseUrl$path")
        val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = config.connectTimeoutMs.coerceAtLeast(1)
            readTimeout = config.readTimeoutMs.coerceAtLeast(1)
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setFixedLengthStreamingMode(body.toByteArray(Charsets.UTF_8).size)
            }
        }
        if (connection !is HttpsURLConnection || !isPinnedEndpoint(uri)) {
            connection.disconnect()
            throw DeveloperCenterException(
                ErrorKind.ENDPOINT_REJECTED,
                "DeveloperCenter endpoint must be the pinned HTTPS server",
            )
        }
        connection.sslSocketFactory = pinnedSslSocketFactory()
        // The platform verifier performs strict IP/DNS SAN hostname validation.
        connection.hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()

        return try {
            if (body != null) {
                connection.outputStream.use { output ->
                    output.write(body.toByteArray(Charsets.UTF_8))
                }
            }
            val status = connection.responseCode
            HttpResult(status, readBody(connection, status))
        } catch (error: DeveloperCenterException) {
            throw error
        } catch (error: Exception) {
            throw DeveloperCenterException(ErrorKind.NETWORK, "DeveloperCenter request failed", cause = error)
        } finally {
            connection.disconnect()
        }
    }

    private fun requireSuccess(response: HttpResult) {
        if (response.code !in 200..299) {
            val serverMessage = response.body?.let(::extractServerMessage)
            throw DeveloperCenterException(
                kind = ErrorKind.HTTP,
                message = serverMessage ?: "DeveloperCenter returned HTTP ${response.code}",
                httpStatus = response.code,
                serverMessage = serverMessage,
            )
        }
    }

    private fun readBody(connection: HttpURLConnection, code: Int): String? {
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            ?: return null
        return stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_RESPONSE_BYTES) {
                    throw malformed("response exceeds the allowed size")
                }
                output.write(buffer, 0, read)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    private fun parsePresenceHistory(json: JSONObject): PresenceHistory {
        val onlineCount = requiredNonNegativeInt(json, "onlineCount")
        val onlineInstallations = optionalNonNegativeInt(json, "onlineInstallations")
        val onlineWindowSeconds = optionalPositiveInt(json, "onlineWindowSeconds")
        val launcherVersions = parseArray(json.optJSONArray("launcherVersions")) { item ->
            LauncherVersionCount(
                version = requiredString(item, "version"),
                count = requiredNonNegativeInt(item, "count"),
            )
        }
        val history = parseArray(json.optJSONArray("history")) { item ->
            PresenceHistoryPoint(
                observedAt = requiredString(item, "observedAt"),
                onlineCount = requiredNonNegativeInt(item, "onlineCount"),
            )
        }
        return PresenceHistory(
            onlineCount = onlineCount,
            onlineInstallations = onlineInstallations,
            onlineWindowSeconds = onlineWindowSeconds,
            launcherVersions = launcherVersions,
            history = history,
            windowHours = optionalPositiveInt(json, "windowHours"),
            sampleIntervalSeconds = optionalPositiveInt(json, "sampleIntervalSeconds"),
        )
    }

    private fun parseServerMetrics(json: JSONObject): ServerMetrics {
        val current = json.optJSONObject("current")?.let(::parseServerMetricsSnapshot)
        val recentSamples = parseTolerantArray(json.optJSONArray("history"), ::parseServerMetricsSnapshot)
        return ServerMetrics(
            current = current,
            recentSamples = recentSamples,
            windowMinutes = tolerantNonNegativeInt(json, "windowMinutes"),
        )
    }

    private fun parseServerMetricsSnapshot(json: JSONObject): ServerMetricsSnapshot {
        val hardware = json.optJSONObject("hardware")
        val memory = json.optJSONObject("memory")
        val network = json.optJSONObject("network")
        val disk = json.optJSONObject("disk")
        return ServerMetricsSnapshot(
            observedAt = tolerantString(json, "observedAt"),
            cpuPercent = tolerantDouble(json, "cpuPercent"),
            processor = hardware?.let { tolerantString(it, "processor") },
            logicalProcessors = hardware?.let { tolerantPositiveInt(it, "logicalProcessors") },
            memoryUsedBytes = tolerantNonNegativeLong(memory, "usedBytes"),
            memoryTotalBytes = tolerantNonNegativeLong(memory, "totalBytes")
                ?: hardware?.let { tolerantNonNegativeLong(it, "memoryTotalBytes") },
            memoryPercent = tolerantDouble(memory, "percent"),
            networkReceiveBytesPerSecond = tolerantNonNegativeDouble(network, "receiveBytesPerSecond"),
            networkSendBytesPerSecond = tolerantNonNegativeDouble(network, "sendBytesPerSecond"),
            diskUsedBytes = tolerantNonNegativeLong(disk, "usedBytes"),
            diskTotalBytes = tolerantNonNegativeLong(disk, "totalBytes"),
            diskPercent = tolerantDouble(disk, "percent"),
        )
    }

    private fun <T> parseTolerantArray(array: JSONArray?, parser: (JSONObject) -> T): List<T> {
        if (array == null) return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                runCatching { parser(item) }.getOrNull()?.let(::add)
            }
        }
    }

    private fun tolerantString(json: JSONObject?, key: String): String? {
        if (json == null || !json.has(key) || json.isNull(key)) return null
        return json.optString(key).takeIf { it.isNotBlank() }
    }

    private fun tolerantDouble(json: JSONObject?, key: String): Double? {
        if (json == null || !json.has(key) || json.isNull(key)) return null
        val value = json.opt(key)
        return when (value) {
            is Number -> value.toDouble().takeIf { it.isFinite() }
            is String -> value.toDoubleOrNull()?.takeIf { it.isFinite() }
            else -> null
        }
    }

    private fun tolerantNonNegativeDouble(json: JSONObject?, key: String): Double? {
        return tolerantDouble(json, key)?.takeIf { it >= 0.0 }
    }

    private fun tolerantNonNegativeLong(json: JSONObject?, key: String): Long? {
        if (json == null || !json.has(key) || json.isNull(key)) return null
        val value = json.opt(key)
        return when (value) {
            is Number -> value.toLong().takeIf { it >= 0L }
            is String -> value.toLongOrNull()?.takeIf { it >= 0L }
            else -> null
        }
    }

    private fun tolerantNonNegativeInt(json: JSONObject, key: String): Int? {
        return tolerantNonNegativeLong(json, key)?.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    private fun tolerantPositiveInt(json: JSONObject, key: String): Int? {
        return tolerantNonNegativeInt(json, key)?.takeIf { it > 0 }
    }

    private fun parseAcknowledgements(json: JSONObject): List<Acknowledgement> {
        val rows = json.optJSONArray("acknowledgements")
            ?: throw malformed("acknowledgements is missing")
        return parseArray(rows) { item ->
            val id = try {
                UUID.fromString(requiredString(item, "id"))
            } catch (error: Exception) {
                throw malformed("acknowledgement id is invalid", error)
            }
            Acknowledgement(
                id = id,
                name = requiredString(item, "name"),
                category = requiredString(item, "category"),
                link = optionalString(item, "link"),
                role = optionalString(item, "role"),
                contribution = optionalString(item, "contribution"),
                sortOrder = requiredInt(item, "sortOrder"),
                createdAt = requiredString(item, "createdAt"),
                updatedAt = requiredString(item, "updatedAt"),
                avatarUrl = optionalString(item, "avatarUrl"),
                avatarWidth = optionalInt(item, "avatarWidth"),
                avatarHeight = optionalInt(item, "avatarHeight"),
                avatarBytes = optionalInt(item, "avatarBytes"),
            )
        }
    }

    private fun parseFeedbackSubmission(raw: String, title: String): FeedbackSubmission {
        val json = try {
            JSONObject(raw)
        } catch (error: Exception) {
            throw malformed("feedback response is not valid JSON", error)
        }
        val id = parseFeedbackUuid(requiredString(json, "id"), "feedback response id")
        return FeedbackSubmission(
            id = id,
            title = title,
            number = optionalLong(json, "number"),
            category = requiredString(json, "category"),
            status = optionalString(json, "status"),
            body = requiredString(json, "body"),
            launcherVersion = requiredString(json, "launcherVersion"),
            createdAt = optionalString(json, "createdAt"),
            updatedAt = optionalString(json, "updatedAt"),
            reply = optionalString(json, "reply"),
            mine = optionalBoolean(json, "mine"),
            upvoteCount = optionalNonNegativeInt(json, "upvoteCount"),
            hasUpvoted = optionalBoolean(json, "hasUpvoted"),
            supplementCount = optionalNonNegativeInt(json, "supplementCount"),
        )
    }

    private fun parseFeedbackItemResponse(raw: String): FeedbackItem {
        val json = try {
            JSONObject(raw)
        } catch (error: Exception) {
            throw malformed("feedback supplement response is not valid JSON", error)
        }
        return parseFeedbackItem(json)
    }

    private fun parseFeedbackUpvoteResult(raw: String): FeedbackUpvoteResult {
        val json = try {
            JSONObject(raw)
        } catch (error: Exception) {
            throw malformed("feedback upvote response is not valid JSON", error)
        }
        return FeedbackUpvoteResult(
            upvoteCount = requiredNonNegativeInt(json, "upvoteCount"),
            hasUpvoted = requiredBoolean(json, "hasUpvoted"),
        )
    }

    private fun parseFeedbackBanStatus(json: JSONObject): FeedbackBanStatus {
        val reason = optionalString(json, "reason")
        if (reason != null && reason.length > MAX_FEEDBACK_BAN_REASON_LENGTH) {
            throw malformed("feedback ban reason exceeds the allowed length")
        }
        val expiresAt = optionalString(json, "expiresAt")
        if (expiresAt != null && expiresAt.length > MAX_FEEDBACK_TIMESTAMP_LENGTH) {
            throw malformed("feedback ban expiry timestamp is invalid")
        }
        val banned = requiredBoolean(json, "banned")
        val permanent = requiredBoolean(json, "permanent")
        if (!banned && (reason != null || expiresAt != null || permanent)) {
            throw malformed("an unbanned feedback machine cannot include ban details")
        }
        if (banned && reason.isNullOrBlank()) {
            throw malformed("a banned feedback machine must include a reason")
        }
        if (banned && (permanent == (expiresAt != null))) {
            throw malformed("feedback ban expiry does not match its permanence")
        }
        return FeedbackBanStatus(
            banned = banned,
            reason = reason,
            expiresAt = expiresAt,
            permanent = permanent,
        )
    }

    private fun parseFeedbackPage(json: JSONObject): FeedbackPage {
        val rows = json.optJSONArray("feedback")
            ?: throw malformed("feedback is missing")
        return FeedbackPage(
            feedback = parseArray(rows, ::parseFeedbackItem),
            nextOffset = optionalNonNegativeInt(json, "nextOffset"),
            totalCount = optionalNonNegativeInt(json, "totalCount"),
        )
    }

    private fun parseFeedbackSupplements(json: JSONObject): FeedbackSupplementsPage {
        val rows = json.optJSONArray("supplements")
            ?: throw malformed("supplements is missing")
        return FeedbackSupplementsPage(
            supplements = parseArray(rows, ::parseFeedbackSupplement),
            nextOffset = optionalNonNegativeInt(json, "nextOffset"),
        )
    }

    private fun parseFeedbackItem(json: JSONObject): FeedbackItem {
        val supplementCount = requiredNonNegativeInt(json, "supplementCount")
        val supplements = parseArray(json.optJSONArray("supplements"), ::parseFeedbackSupplement)
        if (supplements.size > supplementCount) {
            throw malformed("feedback supplements exceed supplementCount")
        }
        return FeedbackItem(
            id = parseFeedbackUuid(requiredString(json, "id"), "feedback id"),
            number = optionalLong(json, "number"),
            upvoteCount = optionalNonNegativeInt(json, "upvoteCount"),
            hasUpvoted = optionalBoolean(json, "hasUpvoted"),
            category = requiredString(json, "category"),
            status = requiredString(json, "status"),
            body = requiredString(json, "body"),
            launcherVersion = requiredString(json, "launcherVersion"),
            createdAt = requiredString(json, "createdAt"),
            updatedAt = requiredString(json, "updatedAt"),
            reply = optionalString(json, "reply"),
            latestDeveloperReply = json.optJSONObject("latestDeveloperReply")?.let(::parseFeedbackSupplement),
            hasLogs = optionalBoolean(json, "hasLogs") ?: false,
            mine = optionalBoolean(json, "mine") ?: false,
            supplementCount = supplementCount,
            nextSupplementOffset = optionalNonNegativeInt(json, "nextSupplementOffset"),
            supplements = supplements,
        )
    }

    private fun parseFeedbackSupplement(json: JSONObject): FeedbackSupplement {
        return FeedbackSupplement(
            id = parseFeedbackUuid(requiredString(json, "id"), "supplement id"),
            body = requiredString(json, "body"),
            createdAt = requiredString(json, "createdAt"),
            isDeveloperReply = requiredBoolean(json, "isDeveloperReply"),
            hasLogs = optionalBoolean(json, "hasLogs") ?: false,
        )
    }

    private fun normalizeBaseUrl(raw: String?): String {
        if (raw == null) {
            throw DeveloperCenterException(ErrorKind.PREVIEW, "DeveloperCenter client is in preview mode")
        }
        val value = raw.trim().trimEnd('/')
        val uri = runCatching { URI(value) }.getOrElse {
            throw DeveloperCenterException(ErrorKind.ENDPOINT_REJECTED, "DeveloperCenter base URL is invalid", cause = it)
        }
        val valid = uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(PINNED_HOST, ignoreCase = true) &&
            uri.port == PINNED_PORT &&
            uri.path.isNullOrEmpty() &&
            uri.rawUserInfo == null &&
            uri.query == null &&
            uri.fragment == null
        if (!valid) {
            throw DeveloperCenterException(
                ErrorKind.ENDPOINT_REJECTED,
                "DeveloperCenter base URL must be https://$PINNED_HOST:$PINNED_PORT",
            )
        }
        return DEFAULT_BASE_URL
    }

    private fun normalizeCategory(raw: String): String {
        return when (raw.trim().lowercase()) {
            "suggestion", "建议", "需求" -> "Suggestion"
            "bug", "漏洞", "问题" -> "Bug"
            else -> throw invalidInput("category must be Suggestion or Bug")
        }
    }

    private fun validateMachineId(raw: String): String {
        if (raw.isEmpty() || raw.length > MAX_FEEDBACK_MACHINE_ID_LENGTH ||
            raw.any { it.isWhitespace() || it.isISOControl() }
        ) {
            throw invalidInput("machineId must contain 1-256 non-whitespace, non-control characters")
        }
        return raw
    }

    private fun encodeQuery(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun validateTitle(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty() || value.length > MAX_FEEDBACK_TITLE_LENGTH || value.any(Char::isISOControl)) {
            throw invalidInput("title must contain 1-200 non-control characters")
        }
        return value
    }

    private fun validateBody(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty() || value.length > MAX_FEEDBACK_BODY_LENGTH || hasDisallowedControl(value)) {
            throw invalidInput("body must contain 1-12000 characters")
        }
        return value
    }

    private fun validateLauncherVersion(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty() || value.length > MAX_LAUNCHER_VERSION_LENGTH || value.any(Char::isISOControl)) {
            throw invalidInput("launcherVersion must contain 1-64 non-control characters")
        }
        return value
    }

    private fun parseInstallationId(raw: String): UUID {
        return runCatching { UUID.fromString(raw.trim()) }.getOrElse {
            throw invalidInput("installationId must be a valid UUID")
        }
    }

    private fun parseFeedbackUuidInput(raw: String, label: String): UUID {
        return runCatching { UUID.fromString(raw.trim()) }.getOrElse {
            throw invalidInput("$label must be a valid UUID")
        }
    }

    private fun parseFeedbackUuid(raw: String, label: String): UUID {
        return runCatching { UUID.fromString(raw.trim()) }.getOrElse {
            throw malformed("$label is invalid", it)
        }
    }

    private fun optionalLong(json: JSONObject, key: String): Long? {
        if (!json.has(key) || json.isNull(key)) return null
        val value = json.opt(key)
        return when (value) {
            is Number -> value.toLong().takeIf { it >= 0L }
            is String -> value.toLongOrNull()?.takeIf { it >= 0L }
            else -> throw malformed("response field '$key' is invalid")
        }
    }

    private fun requiredBoolean(json: JSONObject, key: String): Boolean {
        return optionalBoolean(json, key)
            ?: throw malformed("response field '$key' is missing")
    }

    private fun hasDisallowedControl(value: String): Boolean = value.any { character ->
        character.isISOControl() && character != '\n' && character != '\r' && character != '\t'
    }

    private fun optionalString(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        return json.optString(key).takeIf { it.isNotBlank() }
    }

    private fun requiredString(json: JSONObject, key: String): String {
        return json.optString(key).takeIf { it.isNotBlank() }
            ?: throw malformed("response field '$key' is missing")
    }

    private fun requiredLong(json: JSONObject, key: String): Long {
        val value = json.opt(key)
        require(value is Number && value.toLong() > 0L && value.toDouble() == value.toLong().toDouble()) {
            "response field '$key' is missing or invalid"
        }
        return value.toLong()
    }

    private fun requiredInt(json: JSONObject, key: String): Int {
        if (!json.has(key) || json.isNull(key)) throw malformed("response field '$key' is missing")
        return json.optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
            ?: throw malformed("response field '$key' is invalid")
    }

    private fun requiredNonNegativeInt(json: JSONObject, key: String): Int {
        return requiredInt(json, key).takeIf { it >= 0 }
            ?: throw malformed("response field '$key' is negative")
    }

    private fun optionalInt(json: JSONObject, key: String): Int? {
        if (!json.has(key) || json.isNull(key)) return null
        return json.optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
            ?: throw malformed("response field '$key' is invalid")
    }

    private fun optionalNonNegativeInt(json: JSONObject, key: String): Int? {
        return optionalInt(json, key)?.takeIf { it >= 0 }
            ?: if (!json.has(key) || json.isNull(key)) null else throw malformed("response field '$key' is negative")
    }

    private fun optionalPositiveInt(json: JSONObject, key: String): Int? {
        return optionalInt(json, key)?.takeIf { it > 0 }
            ?: if (!json.has(key) || json.isNull(key)) null else throw malformed("response field '$key' must be positive")
    }

    private fun optionalBoolean(json: JSONObject, key: String): Boolean? {
        if (!json.has(key) || json.isNull(key)) return null
        val value = json.opt(key)
        return value as? Boolean ?: throw malformed("response field '$key' is invalid")
    }

    private fun <T> parseArray(array: JSONArray?, parser: (JSONObject) -> T): List<T> {
        if (array == null) return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                    ?: throw malformed("response array item at index $index is invalid")
                add(parser(item))
            }
        }
    }

    private fun extractServerMessage(body: String): String? = runCatching {
        JSONObject(body).optString("error").trim().takeIf { it.isNotEmpty() }
    }.getOrNull()?.take(MAX_ERROR_MESSAGE_LENGTH)

    private fun asClientException(error: Throwable): DeveloperCenterException {
        if (error is DeveloperCenterException) return error
        return DeveloperCenterException(ErrorKind.NETWORK, "DeveloperCenter request failed", cause = error)
    }

    private fun invalidInput(message: String): DeveloperCenterException =
        DeveloperCenterException(ErrorKind.INVALID_INPUT, message)

    private fun malformed(message: String, cause: Throwable? = null): DeveloperCenterException =
        DeveloperCenterException(ErrorKind.MALFORMED_RESPONSE, message, cause = cause)

    private fun isPinnedEndpoint(uri: URI): Boolean =
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(PINNED_HOST, ignoreCase = true) &&
            uri.port == PINNED_PORT

    private fun pinnedSslSocketFactory(): SSLSocketFactory {
        val defaults = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm(),
        ).apply { init(null as java.security.KeyStore?) }
        val defaultTrust = defaults.trustManagers.filterIsInstance<X509TrustManager>().single()
        val pinned = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = defaultTrust.acceptedIssuers

            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                defaultTrust.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                require(chain.isNotEmpty()) { "empty server certificate chain" }
                val leaf = chain.first()
                leaf.checkValidity()
                val actual = MessageDigest.getInstance("SHA-256")
                    .digest(leaf.publicKey.encoded)
                    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                check(actual.equals(PINNED_SPKI_SHA256, ignoreCase = true)) {
                    "untrusted pinned server certificate"
                }
            }
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(pinned), null)
        }.socketFactory
    }

    private data class HttpResult(val code: Int, val body: String?)

    companion object {
        const val DEFAULT_BASE_URL = "https://202.189.21.218:8787"

        private const val PINNED_HOST = "202.189.21.218"
        private const val PINNED_PORT = 8787
        private const val PINNED_SPKI_SHA256 =
            "2c72728755267c607124b2ac86e431743c34af5c6069aa22bbcc401e0f626f0b"
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MAX_ERROR_MESSAGE_LENGTH = 512
        private const val MAX_FEEDBACK_TITLE_LENGTH = 200
        private const val MAX_FEEDBACK_BODY_LENGTH = 12_000
        private const val MAX_FEEDBACK_MACHINE_ID_LENGTH = 256
        private const val MAX_FEEDBACK_BAN_REASON_LENGTH = 500
        private const val MAX_FEEDBACK_TIMESTAMP_LENGTH = 64
        private const val MAX_FEEDBACK_LIST_LIMIT = 20
        private const val MAX_LAUNCHER_VERSION_LENGTH = 64
    }
}




