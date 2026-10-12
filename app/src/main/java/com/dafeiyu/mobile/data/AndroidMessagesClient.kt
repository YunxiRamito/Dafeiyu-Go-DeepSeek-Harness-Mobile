package com.dafeiyu.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Explicit HTTP client for the Android announcement/notification feed.
 * Markdown and button destinations remain data only: this client never renders
 * markdown, opens URLs, navigates to settings, or runs commands.
 *
 * SSE is an online invalidation hint only. It cannot wake the app or deliver
 * content while offline; pull the feed again on foreground/network recovery.
 */
class AndroidMessagesClient(private val config: Config = Config()) {
    data class Config(
        /** Null makes this client explicitly offline/preview-only. */
        val baseUrl: String? = DEFAULT_BASE_URL,
        val connectTimeoutMs: Int = 5_000,
        val readTimeoutMs: Int = 20_000,
    ) {
        init {
            require(connectTimeoutMs in 1..120_000)
            require(readTimeoutMs in 1..120_000)
        }
    }

    enum class ErrorKind { PREVIEW, INVALID_INPUT, HTTP, NETWORK, MALFORMED_RESPONSE }

    class AndroidMessagesException(
        val kind: ErrorKind,
        override val message: String,
        val httpStatus: Int? = null,
        cause: Throwable? = null,
    ) : IOException(message, cause)

    enum class MessageKind(val wireValue: String) {
        ANNOUNCEMENT("Announcement"), NOTIFICATION("Notification"),
    }
    enum class MetricKind(val wireValue: String) {
        DELIVERED("Delivered"), DISPLAYED("Displayed"), READ("Read"), CLICK("Click"),
    }
    enum class SettingsPage(val wireValue: String) {
        HOME("Home"), ENGINE("Engine"), ONLINE("Online"), GENERAL("General"),
        MODELS("Models"), ENVIRONMENT("Environment"), DATA("Data"), SUPPORT("Support"),
        ABOUT("About"), UPDATES("Updates"),
    }
    enum class PublicationState(val wireValue: String) { PUBLISHED("Published") }
    enum class ButtonAction(val wireValue: String) { URL("Url"), SETTINGS("Settings"), DISMISS("Dismiss") }

    data class MessageButton(
        val position: Int,
        val label: String,
        val action: ButtonAction,
        /** Set only for Url actions; this client never opens it. */
        val url: String? = null,
        /** Set only for Settings actions. */
        val page: SettingsPage? = null,
    )

    data class AndroidMessage(
        val id: UUID,
        val kind: MessageKind,
        val title: String,
        /** Untrusted display text; never interpreted as executable content. */
        val markdown: String,
        val state: PublicationState,
        val createdAt: Instant,
        val updatedAt: Instant,
        val publishedAt: Instant,
        val expiresAt: Instant?,
        val withdrawnAt: Instant?,
        val date: LocalDate,
        val expired: Boolean,
        val read: Boolean,
        val delivered: Boolean,
        val displayed: Boolean,
        val buttons: List<MessageButton>,
    )

    data class FeedPage(
        val announcements: List<AndroidMessage>,
        val notifications: List<AndroidMessage>,
        val announcementTotal: Int,
        val notificationTotal: Int,
        val announcementNextOffset: Int?,
        val notificationNextOffset: Int?,
    )
    data class FeedSnapshot(
        val announcements: List<AndroidMessage>,
        val notifications: List<AndroidMessage>,
        val announcementTotal: Int,
        val notificationTotal: Int,
        val announcementNextOffset: Int?,
        val notificationNextOffset: Int?,
    )

    /** De-duplicates IDs independently for announcements and notifications across pages. */
    class FeedAccumulator {
        private val announcements = LinkedHashMap<UUID, AndroidMessage>()
        private val notifications = LinkedHashMap<UUID, AndroidMessage>()
        fun merge(page: FeedPage) = FeedSnapshot(
            announcements = page.announcements.onEach { announcements[it.id] = it }
                .let { announcements.values.toList() },
            notifications = page.notifications.onEach { notifications[it.id] = it }
                .let { notifications.values.toList() },
            announcementTotal = page.announcementTotal,
            notificationTotal = page.notificationTotal,
            announcementNextOffset = page.announcementNextOffset,
            notificationNextOffset = page.notificationNextOffset,
        )
        fun clear() { announcements.clear(); notifications.clear() }
    }

    data class ReconnectSuggestion(val shouldReconnect: Boolean, val delayMs: Long?, val failedAttempt: Int)
    data class SseResult(
        val httpStatus: Int,
        val serverRetryMs: Long? = null,
        val retryAfterMs: Long? = null,
        val feedChangedCount: Int = 0,
        val heartbeatCommentCount: Int = 0,
    )

    /** Fetches one independently paged slice of each feed. */
    suspend fun fetchFeed(
        installationId: UUID?,
        announcementOffset: Int = 0,
        notificationOffset: Int = 0,
        limit: Int = DEFAULT_LIMIT,
    ): FeedPage {
        validateInstallationId(installationId, required = false)
        validatePage(announcementOffset, limit, "announcementOffset")
        validatePage(notificationOffset, limit, "notificationOffset")
        val endpoint = buildString {
            append(baseUrl()).append("/api/android/feed?")
            append("announcementOffset=").append(announcementOffset)
            append("&notificationOffset=").append(notificationOffset).append("&limit=").append(limit)
            if (installationId != null) {
                append("&installationId=")
                append(URLEncoder.encode(installationId.toString(), StandardCharsets.UTF_8.name()))
            }
        }
        return ioRequest {
            val response = request("GET", endpoint, accept = "application/json")
            if (response.code != HttpURLConnection.HTTP_OK) throw httpError(response.code)
            parseFeed(response.body ?: malformed("feed response body is missing"))
        }
    }

    /** Sends an idempotent receipt; Click requires a visible button position 0 or 1. */
    suspend fun recordMetric(
        messageId: UUID,
        installationId: UUID,
        kind: MetricKind,
        buttonPosition: Int? = null,
    ) {
        validateInstallationId(installationId, required = true)
        if (messageId == EMPTY_UUID) invalid("messageId must not be the empty UUID")
        when (kind) {
            MetricKind.CLICK -> {
                if (buttonPosition == null || buttonPosition !in 0..1) invalid("Click requires buttonPosition 0 or 1")
            }
            else -> {
                // The server contract accepts explicit -1 for non-click metrics,
                // while omitting the field remains the normal UI call shape.
                if (buttonPosition != null && buttonPosition != -1) {
                    invalid("buttonPosition must be -1 or omitted for non-Click metrics")
                }
            }
        }
        val body = JSONObject().put("installationId", installationId.toString())
            .put("kind", kind.wireValue)
            .apply { if (buttonPosition != null) put("buttonPosition", buttonPosition) }
            .toString()
        ioRequest {
            val response = request(
                "POST", "${baseUrl()}/api/android/messages/$messageId/metrics", body,
                accept = "application/json",
            )
            if (response.code != HttpURLConnection.HTTP_NO_CONTENT) throw httpError(response.code)
        }
    }

    /**
     * Opens exactly one SSE connection and calls [onFeedChanged] without event data.
     * Initial `feed-changed`, retry directives, and heartbeat comments are parsed.
     * No automatic/infinite reconnect loop is started; cancellation disconnects the socket.
     * Callback runs on a dedicated daemon I/O thread.
     */
    suspend fun listenOnce(onFeedChanged: () -> Unit): SseResult {
        val endpoint = "${baseUrl()}/api/android/events"
        return suspendCancellableCoroutine { continuation ->
            val connectionRef = AtomicReference<HttpURLConnection?>(null)
            val workerRef = AtomicReference<Thread?>(null)
            continuation.invokeOnCancellation {
                connectionRef.getAndSet(null)?.disconnect()
                workerRef.get()?.interrupt()
            }
            val worker = Thread({
                var activeConnection: HttpsURLConnection? = null
                try {
                    val opened = openHttpsConnection(URI(endpoint), "GET", "text/event-stream")
                    activeConnection = opened
                    connectionRef.set(opened)
                    if (!continuation.isActive) {
                        opened.disconnect()
                        return@Thread
                    }
                    val status = opened.responseCode
                    val result = if (status == HTTP_TOO_MANY_REQUESTS) {
                        SseResult(status, retryAfterMs = parseRetryAfter(opened.getHeaderField("Retry-After")))
                    } else {
                        if (status != HttpURLConnection.HTTP_OK) throw httpError(status)
                        if (!opened.contentType.orEmpty().startsWith("text/event-stream", ignoreCase = true)) {
                            malformed("SSE response content type is invalid")
                        }
                        parseSse(opened.inputStream.bufferedReader(StandardCharsets.UTF_8)) {
                            if (continuation.isActive) onFeedChanged()
                        }
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(asClientException(error))
                } finally {
                    connectionRef.compareAndSet(activeConnection, null)
                    activeConnection?.disconnect()
                }
            }, "android-messages-sse").apply { isDaemon = true }
            workerRef.set(worker)
            if (continuation.isActive) worker.start() else worker.interrupt()
        }
    }

    /** Bounded retry advice only: attempts 1..8 back off exponentially, then stop. */
    fun reconnectSuggestion(
        failedAttempt: Int,
        serverRetryMs: Long? = null,
        retryAfterMs: Long? = null,
    ): ReconnectSuggestion {
        if (failedAttempt !in 1..MAX_RECONNECT_ATTEMPTS) return ReconnectSuggestion(false, null, failedAttempt)
        var exponential = INITIAL_RECONNECT_DELAY_MS
        repeat(failedAttempt - 1) { exponential = minOf(MAX_RECONNECT_DELAY_MS, exponential * 2) }
        val serverHint = maxOf(serverRetryMs ?: 0L, retryAfterMs ?: 0L)
            .coerceAtLeast(0L).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        return ReconnectSuggestion(true, maxOf(exponential, serverHint), failedAttempt)
    }

    /**
     * Pulls both independently paged collections to completion and returns a single
     * ID-deduplicated list. The supplied installation UUID is sent on every page
     * so per-installation read/delivery receipts are included.
     */
    suspend fun fetchAllFeed(installationId: String): Result<List<AndroidMessage>> = try {
        val id = try { UUID.fromString(installationId) } catch (error: Exception) {
            throw AndroidMessagesException(ErrorKind.INVALID_INPUT, "installationId must be a UUID", cause = error)
        }
        if (id == EMPTY_UUID || id.toString() != installationId) invalid("installationId must be a canonical nonempty UUID")

        val accumulator = FeedAccumulator()
        var snapshot: FeedSnapshot? = null
        var announcementOffset = 0
        var notificationOffset = 0
        var pageCount = 0
        while (true) {
            if (++pageCount > MAX_FEED_PAGES) malformed("feed pagination exceeded the supported page count")
            val page = fetchFeed(id, announcementOffset, notificationOffset, MAX_LIMIT)
            snapshot = accumulator.merge(page)
            val nextAnnouncement = page.announcementNextOffset
            val nextNotification = page.notificationNextOffset
            if (nextAnnouncement != null && nextAnnouncement <= announcementOffset) {
                malformed("announcement pagination cursor did not advance")
            }
            if (nextNotification != null && nextNotification <= notificationOffset) {
                malformed("notification pagination cursor did not advance")
            }
            if (nextAnnouncement == null && nextNotification == null) break
            if (nextAnnouncement != null) announcementOffset = nextAnnouncement
            if (nextNotification != null) notificationOffset = nextNotification
        }
        val completed = snapshot ?: malformed("feed returned no page")
        val items = completed.announcements + completed.notifications
        Result.success(items.distinctBy { it.id }.sortedWith(
            compareByDescending<AndroidMessage> { it.kind == MessageKind.ANNOUNCEMENT }
                .thenByDescending { it.publishedAt }
                .thenBy { it.id.toString() },
        ))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(asClientException(error))
    }
    private suspend fun <T> ioRequest(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AndroidMessagesException) {
            throw error
        } catch (error: Exception) {
            throw AndroidMessagesException(ErrorKind.NETWORK, "Android messages request failed", cause = error)
        }
    }

    private fun request(method: String, url: String, body: String? = null, accept: String): HttpResult {
        val connection = openHttpsConnection(URI(url), method, accept)
        return try {
            if (body != null) {
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            val responseBody = if (code in 200..299 && code != HttpURLConnection.HTTP_NO_CONTENT) {
                val length = connection.getHeaderFieldLong("Content-Length", -1L)
                if (length > MAX_RESPONSE_BYTES) malformed("response body exceeds size limit")
                connection.inputStream.use { String(readLimited(it, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8) }
            } else null
            HttpResult(code, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun openHttpsConnection(uri: URI, method: String, accept: String): HttpsURLConnection {
        if (!uri.scheme.equals("https", ignoreCase = true)) invalid("HTTPS is required")
        val connection = uri.toURL().openConnection() as? HttpsURLConnection
            ?: invalid("HTTPS connection could not be created")
        connection.requestMethod = method
        connection.connectTimeout = config.connectTimeoutMs
        connection.readTimeout = config.readTimeoutMs
        connection.useCaches = false
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("Cache-Control", "no-cache")
        if (isPinnedEndpoint(uri)) {
            connection.sslSocketFactory = pinnedSslSocketFactory()
            // Keep Android's normal hostname/SAN verifier; the SPKI pin is additive.
            connection.hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        }
        return connection
    }

    private fun baseUrl(): String {
        val value = config.baseUrl?.trim()?.trimEnd('/')
            ?: throw AndroidMessagesException(ErrorKind.PREVIEW, "Android messages server is not configured")
        if (value.isBlank()) invalid("baseUrl must not be blank")
        val uri = try { URI(value) } catch (error: Exception) {
            throw AndroidMessagesException(ErrorKind.INVALID_INPUT, "baseUrl is invalid", cause = error)
        }
        if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null || (!uri.path.isNullOrEmpty() && uri.path != "/")) {
            invalid("baseUrl must be a fixed HTTPS origin without credentials or path")
        }
        return value
    }

    private fun parseFeed(raw: String): FeedPage = try {
        val root = JSONObject(raw)
        val keys = setOf(
            "announcements", "notifications", "announcementTotal", "notificationTotal",
            "announcementNextOffset", "notificationNextOffset",
        )
        requireKeys(root, keys, keys, "feed")
        val announcements = parseMessages(root.get("announcements"), MessageKind.ANNOUNCEMENT)
            .distinctBy { it.id }
        val notifications = parseMessages(root.get("notifications"), MessageKind.NOTIFICATION)
            .distinctBy { it.id }
        val announcementTotal = requiredInt(root, "announcementTotal")
        val notificationTotal = requiredInt(root, "notificationTotal")
        if (announcementTotal < announcements.size || notificationTotal < notifications.size ||
            announcementTotal < 0 || notificationTotal < 0) malformed("feed totals are invalid")
        FeedPage(
            announcements, notifications, announcementTotal, notificationTotal,
            nullableOffset(root, "announcementNextOffset"),
            nullableOffset(root, "notificationNextOffset"),
        )
    } catch (error: AndroidMessagesException) {
        throw error
    } catch (error: Exception) {
        throw malformed("feed response is malformed", error)
    }

    private fun parseMessages(value: Any?, expectedKind: MessageKind): List<AndroidMessage> {
        val array = value as? JSONArray ?: malformed("feed message collection is not an array")
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.opt(index) as? JSONObject
                    ?: malformed("feed message at index $index is not an object")
                add(parseMessage(item, expectedKind))
            }
        }
    }

    private fun parseMessage(json: JSONObject, expectedKind: MessageKind): AndroidMessage {
        val keys = setOf(
            "id", "kind", "title", "markdown", "state", "createdAt", "updatedAt", "publishedAt",
            "expiresAt", "withdrawnAt", "date", "expired", "read", "delivered", "displayed", "buttons",
        )
        requireKeys(json, keys, keys, "message")
        val kind = MessageKind.entries.firstOrNull { it.wireValue == requiredString(json, "kind") }
            ?: malformed("message kind is unsupported")
        if (kind != expectedKind) malformed("message is in the wrong feed collection")
        val state = PublicationState.entries.firstOrNull { it.wireValue == requiredString(json, "state") }
            ?: malformed("message state is unsupported")
        val title = requiredString(json, "title")
        if (title.length !in 1..MAX_TITLE_LENGTH || title.any(Char::isISOControl)) malformed("message title is invalid")
        val markdown = requiredString(json, "markdown")
        if (markdown.isBlank() || markdown.length > MAX_MARKDOWN_LENGTH || '\u0000' in markdown) {
            malformed("message markdown is invalid")
        }
        val buttons = parseButtons(json.get("buttons"))
        if (kind == MessageKind.ANNOUNCEMENT && buttons.isNotEmpty()) malformed("announcements cannot contain buttons")
        if (buttons.size > MAX_BUTTONS || buttons.withIndex().any { it.index != it.value.position }) {
            malformed("message button positions are invalid")
        }
        val withdrawnAt = nullableInstant(json, "withdrawnAt")
        if (state == PublicationState.PUBLISHED && withdrawnAt != null) {
            malformed("published feed message cannot have withdrawnAt")
        }
        return AndroidMessage(
            id = requiredUuid(json, "id"), kind = kind, title = title, markdown = markdown, state = state,
            createdAt = requiredInstant(json, "createdAt"), updatedAt = requiredInstant(json, "updatedAt"),
            publishedAt = requiredInstant(json, "publishedAt"), expiresAt = nullableInstant(json, "expiresAt"),
            withdrawnAt = withdrawnAt, date = requiredDate(json, "date"), expired = requiredBoolean(json, "expired"),
            read = requiredBoolean(json, "read"), delivered = requiredBoolean(json, "delivered"),
            displayed = requiredBoolean(json, "displayed"), buttons = buttons,
        )
    }

    private fun parseButtons(value: Any?): List<MessageButton> {
        val array = value as? JSONArray ?: malformed("message buttons are not an array")
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val button = array.opt(index) as? JSONObject
                    ?: malformed("message button at index $index is not an object")
                // Feed views include nullable url/page on every action; all other keys are rejected.
                val keys = setOf("position", "label", "action", "url", "page")
                requireKeys(button, keys, keys, "button")
                val action = ButtonAction.entries.firstOrNull { it.wireValue == requiredString(button, "action") }
                    ?: malformed("button action is unsupported")
                val position = requiredInt(button, "position")
                if (position !in 0..1) malformed("button position is invalid")
                val label = requiredString(button, "label")
                if (label.length !in 1..MAX_BUTTON_LABEL_LENGTH || label.any(Char::isISOControl)) {
                    malformed("button label is invalid")
                }
                val url = nullableString(button, "url")
                val pageName = nullableString(button, "page")
                val page = when (action) {
                    ButtonAction.URL -> {
                        if (pageName != null) malformed("Url button cannot contain page")
                        validateHttpsUrl(url ?: malformed("Url button is missing url"))
                        null
                    }
                    ButtonAction.SETTINGS -> {
                        if (url != null) malformed("Settings button cannot contain url")
                        SettingsPage.entries.firstOrNull { it.wireValue == pageName }
                            ?: malformed("Settings button page is unsupported")
                    }
                    ButtonAction.DISMISS -> {
                        if (url != null || pageName != null) malformed("Dismiss button cannot contain a target")
                        null
                    }
                }
                add(MessageButton(position, label, action, url, page))
            }
        }
    }

    private fun parseSse(reader: BufferedReader, onFeedChanged: () -> Unit): SseResult {
        var eventName = ""
        var retryMs: Long? = null
        var changed = 0
        var heartbeats = 0
        try {
            reader.use { input ->
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) {
                        if (eventName == "feed-changed") {
                            changed++
                            onFeedChanged() // Invalidation only; deliberately discard data payload.
                        }
                        eventName = ""
                        continue
                    }
                    if (line.startsWith(':')) {
                        if (line.substring(1).trimStart().equals("heartbeat", ignoreCase = true)) heartbeats++
                        continue
                    }
                    val separator = line.indexOf(':')
                    val field = if (separator < 0) line else line.substring(0, separator)
                    var value = if (separator < 0) "" else line.substring(separator + 1)
                    if (value.startsWith(' ')) value = value.substring(1)
                    when (field) {
                        "event" -> eventName = value
                        "retry" -> if (value.isNotEmpty() && value.all(Char::isDigit)) {
                            value.toLongOrNull()?.let { retryMs = it.coerceAtMost(MAX_SERVER_RETRY_MS) }
                        }
                        // Never retain or expose SSE data/id fields.
                    }
                }
            }
        } catch (error: AndroidMessagesException) {
            throw error
        } catch (error: Exception) {
            throw AndroidMessagesException(ErrorKind.NETWORK, "Android messages event stream failed", cause = error)
        }
        return SseResult(HttpURLConnection.HTTP_OK, retryMs, feedChangedCount = changed, heartbeatCommentCount = heartbeats)
    }

    private fun validateHttpsUrl(raw: String) {
        if (raw.length !in 1..MAX_BUTTON_URL_LENGTH || raw.any(Char::isISOControl)) malformed("button URL is invalid")
        val uri = try { URI(raw) } catch (error: Exception) {
            throw malformed("button URL is invalid", error)
        }
        if (!uri.isAbsolute || !uri.scheme.equals("https", true) || uri.host.isNullOrBlank() || uri.rawUserInfo != null) {
            malformed("button URL must be an absolute HTTPS URL without credentials")
        }
    }

    private fun validatePage(offset: Int, limit: Int, field: String) {
        if (offset !in 0..MAX_OFFSET) invalid("$field must be between 0 and $MAX_OFFSET")
        if (limit !in 1..MAX_LIMIT) invalid("limit must be between 1 and $MAX_LIMIT")
    }

    private fun validateInstallationId(value: UUID?, required: Boolean) {
        if (required && value == null) invalid("installationId is required")
        if (value == EMPTY_UUID) invalid("installationId must not be the empty UUID")
    }

    private fun requireKeys(json: JSONObject, allowed: Set<String>, required: Set<String>, context: String) {
        val keys = json.keys().asSequence().toSet()
        if ((keys - allowed).isNotEmpty()) {
            // Reject unknown fields too, including command/PowerShell payloads.
            malformed("$context contains unsupported fields")
        }
        if (!keys.containsAll(required)) malformed("$context is missing required fields")
    }

    private fun requiredString(json: JSONObject, key: String): String =
        json.opt(key) as? String ?: malformed("field '$key' must be a string")

    private fun nullableString(json: JSONObject, key: String): String? {
        if (!json.has(key)) malformed("field '$key' is missing")
        val value = json.opt(key)
        return if (value == null || value === JSONObject.NULL) null
        else value as? String ?: malformed("field '$key' must be a string or null")
    }

    private fun requiredInt(json: JSONObject, key: String): Int {
        val number = json.opt(key) as? Number ?: malformed("field '$key' must be an integer")
        val long = number.toLong()
        if (number.toDouble() != long.toDouble() || long !in Int.MIN_VALUE..Int.MAX_VALUE) {
            malformed("field '$key' must be an integer")
        }
        return long.toInt()
    }

    private fun nullableOffset(json: JSONObject, key: String): Int? {
        if (!json.has(key)) malformed("field '$key' is missing")
        if (json.isNull(key)) return null
        return requiredInt(json, key).also {
            if (it !in 0..MAX_OFFSET) malformed("field '$key' is outside the supported range")
        }
    }

    private fun requiredBoolean(json: JSONObject, key: String): Boolean =
        json.opt(key) as? Boolean ?: malformed("field '$key' must be a boolean")

    private fun requiredUuid(json: JSONObject, key: String): UUID {
        val raw = requiredString(json, key)
        val id = try { UUID.fromString(raw) } catch (error: Exception) {
            throw malformed("field '$key' is not a UUID", error)
        }
        if (raw != id.toString() || id == EMPTY_UUID) malformed("field '$key' is not a canonical nonempty UUID")
        return id
    }

    private fun requiredInstant(json: JSONObject, key: String): Instant {
        val raw = requiredString(json, key)
        return try { OffsetDateTime.parse(raw).toInstant() } catch (error: Exception) {
            throw malformed("field '$key' is not an ISO-8601 timestamp", error)
        }
    }

    private fun nullableInstant(json: JSONObject, key: String): Instant? {
        if (!json.has(key)) malformed("field '$key' is missing")
        return if (json.isNull(key)) null else requiredInstant(json, key)
    }

    private fun requiredDate(json: JSONObject, key: String): LocalDate {
        val raw = requiredString(json, key)
        return try { LocalDate.parse(raw) } catch (error: Exception) {
            throw malformed("field '$key' is not a valid yyyy-MM-dd date", error)
        }
    }

    private fun readLimited(input: InputStream, maximumBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maximumBytes, 8 * 1024))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maximumBytes) malformed("response body exceeds size limit")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun parseRetryAfter(raw: String?): Long? {
        val seconds = raw?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        return if (seconds >= MAX_SERVER_RETRY_MS / 1_000L) MAX_SERVER_RETRY_MS else seconds * 1_000L
    }

    private fun httpError(status: Int) = AndroidMessagesException(
        ErrorKind.HTTP, "Android messages server returned HTTP $status", httpStatus = status,
    )
    private fun asClientException(error: Throwable): AndroidMessagesException = when (error) {
        is AndroidMessagesException -> error
        else -> AndroidMessagesException(ErrorKind.NETWORK, "Android messages request failed", cause = error)
    }
    private fun invalid(message: String): Nothing = throw AndroidMessagesException(ErrorKind.INVALID_INPUT, message)
    private fun malformed(message: String, cause: Throwable? = null): Nothing =
        throw AndroidMessagesException(ErrorKind.MALFORMED_RESPONSE, message, cause = cause)

    private fun isPinnedEndpoint(uri: URI): Boolean = uri.scheme.equals("https", true) &&
        uri.host.equals(PINNED_HOST, true) && uri.port == PINNED_PORT

    private fun pinnedSslSocketFactory(): SSLSocketFactory {
        val defaults = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as java.security.KeyStore?) }
        val systemTrust = defaults.trustManagers.filterIsInstance<X509TrustManager>().single()
        val pinned = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = systemTrust.acceptedIssuers
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                systemTrust.checkClientTrusted(chain, authType)
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                require(chain.isNotEmpty()) { "empty server certificate chain" }
                val leaf = chain.first()
                leaf.checkValidity()
                val actual = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)
                    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                check(actual.equals(PINNED_SPKI_SHA256, ignoreCase = true)) { "untrusted pinned server certificate" }
            }
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(pinned), null) }.socketFactory
    }

    private data class HttpResult(val code: Int, val body: String?)

    companion object {
        /** Same public DeveloperCenter endpoint as [DeveloperCenterPresenceClient]. */
        const val DEFAULT_BASE_URL = DeveloperCenterPresenceClient.DEFAULT_BASE_URL
        private const val PINNED_HOST = "202.189.21.218"
        private const val PINNED_PORT = 8787
        private const val PINNED_SPKI_SHA256 = "2c72728755267c607124b2ac86e431743c34af5c6069aa22bbcc401e0f626f0b"
        private val EMPTY_UUID = UUID(0L, 0L)
        private const val DEFAULT_LIMIT = 20
        private const val MAX_LIMIT = 100
        private const val MAX_OFFSET = 1_000_000
        private const val MAX_TITLE_LENGTH = 200
        private const val MAX_MARKDOWN_LENGTH = 32_000
        private const val MAX_BUTTONS = 2
        private const val MAX_BUTTON_LABEL_LENGTH = 80
        private const val MAX_BUTTON_URL_LENGTH = 2_048
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MAX_RECONNECT_ATTEMPTS = 8
        private const val MAX_FEED_PAGES = 10_001
        private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
        private const val MAX_SERVER_RETRY_MS = 300_000L
        private const val HTTP_TOO_MANY_REQUESTS = 429
    }
}

/** Converts the strict Android wire model to the existing local notice shape. */
fun AndroidMessagesClient.AndroidMessage.toNotice(): NoticeStore.Notice = NoticeStore.Notice(
    id = id.toString(),
    kind = when (kind) {
        AndroidMessagesClient.MessageKind.ANNOUNCEMENT -> NoticeStore.Kind.ANNOUNCEMENT
        AndroidMessagesClient.MessageKind.NOTIFICATION -> NoticeStore.Kind.NOTIFICATION
    },
    title = title,
    markdown = markdown,
    date = date.toString(),
    publishedAtEpochMillis = publishedAt.toEpochMilli(),
    expiresAtEpochMillis = expiresAt?.toEpochMilli(),
    buttons = buttons.map { button ->
        NoticeStore.Button(
            label = button.label,
            action = when (button.action) {
                AndroidMessagesClient.ButtonAction.URL -> NoticeStore.Action.URL
                AndroidMessagesClient.ButtonAction.SETTINGS -> NoticeStore.Action.SETTINGS
                AndroidMessagesClient.ButtonAction.DISMISS -> NoticeStore.Action.DISMISS
            },
            target = when (button.action) {
                AndroidMessagesClient.ButtonAction.URL -> button.url
                AndroidMessagesClient.ButtonAction.SETTINGS -> button.page?.wireValue
                AndroidMessagesClient.ButtonAction.DISMISS -> null
            },
        )
    },
)

fun List<AndroidMessagesClient.AndroidMessage>.toNotices(): List<NoticeStore.Notice> = map { it.toNotice() }








