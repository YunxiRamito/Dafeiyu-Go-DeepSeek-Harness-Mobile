package com.dafeiyu.mobile.data

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Live process observations. Platform push is separately registered and may
 * wake a killed application; SSE and polling make no such promise. */
class NativeNotificationMonitor internal constructor(
    parentScope: CoroutineScope,
    private val source: Source,
    private val policy: NotificationEventPolicy,
    private val publish: (NotificationEventPolicy.Event) -> Unit,
    private val feedIntervalMillis: Long = 60_000,
    private val deviceIntervalMillis: Long = 30_000,
) : AutoCloseable {
    constructor(context: Context, parentScope: CoroutineScope) : this(parentScope,
        AndroidSource(context.applicationContext),
        NotificationEventPolicy(RemoteDshSessionCoordinator.diskJournal(
            File(context.noBackupFilesDir, "notification-events/state.json"))),
        NotificationPublisher(context.applicationContext)::postEvent)

    internal interface Source {
        fun enabled(): Boolean
        fun observeAries(): Boolean
        fun feedbackIdentity(): String?
        fun feedbackNotificationsEnabled(): Boolean
        suspend fun fetchFeedback(identity: String, after: Long?): DeveloperCenterClient.FeedbackNotificationPage
        suspend fun fetchFeed(): List<AndroidMessagesClient.AndroidMessage>
        suspend fun listenOnce(onChanged: () -> Unit): AndroidMessagesClient.SseResult
        fun retry(attempt: Int, serverDelay: Long?, retryAfter: Long?): AndroidMessagesClient.ReconnectSuggestion
        suspend fun connectionState(): org.json.JSONObject
    }

    private class AndroidSource(private val app: Context) : Source {
        private val settings = app.getSharedPreferences("dafeiyu_settings", Context.MODE_PRIVATE)
        private val messages = AndroidMessagesClient()
        private val feedback = DeveloperCenterClient()
        private val aries = AriesConnectionProvider(app)
        override fun enabled() = settings.getBoolean("notifications_enabled", true)
        override fun observeAries() = settings.getString("aries_group_id", null) != null &&
            (settings.getBoolean("device_notifications", true) || settings.getBoolean("backend_status_notifications", true))
        override fun feedbackIdentity(): String? = app.getSharedPreferences("developer_center_feedback", Context.MODE_PRIVATE)
            .getString("machine_id", null)?.takeIf { it.isNotBlank() && it.length <= 128 && it.none(Char::isISOControl) }
        override fun feedbackNotificationsEnabled() = enabled() && settings.getBoolean("message_notifications", true)
        override suspend fun fetchFeedback(identity: String, after: Long?) =
            feedback.getFeedbackNotifications(identity, after).getOrThrow()
        override suspend fun fetchFeed(): List<AndroidMessagesClient.AndroidMessage> {
            val installation = app.getSharedPreferences("developer_center_presence", Context.MODE_PRIVATE)
                .getString("installation_id", null)?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() }
            val feed = messages.fetchFeed(installation, limit = 50)
            return feed.announcements + feed.notifications
        }
        override suspend fun listenOnce(onChanged: () -> Unit) = messages.listenOnce(onChanged)
        override fun retry(attempt: Int, serverDelay: Long?, retryAfter: Long?) =
            messages.reconnectSuggestion(attempt, serverDelay, retryAfter)
        override suspend fun connectionState() = aries.call("get_connection_state")
    }
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO)
    private var observation: Job? = null
    private val feedbackInvalidations = Channel<Unit>(Channel.CONFLATED)
    private var lastForeground = false
    private var lastPowerSaving = false

    @Synchronized
    fun onNotificationPreferencesChanged() {
        reconcile(lastForeground, lastPowerSaving)
        feedbackInvalidations.trySend(Unit)
    }

    @Synchronized
    fun reconcile(foreground: Boolean, powerSaving: Boolean) {
        lastForeground = foreground
        lastPowerSaving = powerSaving
        try { policy.feedbackPreferencesChanged(source.feedbackNotificationsEnabled()) }
        catch (_: Exception) {
            // Failed durable preference state cannot acknowledge an in-flight page.
            observation?.cancel(); observation = null
            return
        }
        if (!source.enabled() || (!foreground && powerSaving)) {
            observation?.cancel(); observation = null
            return
        }
        if (observation?.isActive == true) return
        observation = scope.launch {
            coroutineScope {
                launch { observeMessages() }
                launch { observeDevices() }
                launch { observeFeedback() }
            }
        }
    }

    private suspend fun observeFeedback() = coroutineScope {
        launch {
            while (isActive) { feedbackInvalidations.trySend(Unit); delay(feedIntervalMillis) }
        }
        for (ignored in feedbackInvalidations) {
            try {
                val identity = source.feedbackIdentity() ?: continue
                // Bound each observation; a large backlog never monopolizes the process.
                var remainingPages = 16
                while (remainingPages-- > 0) {
                    policy.feedbackPreferencesChanged(source.feedbackNotificationsEnabled())
                    val read = policy.feedbackRead(identity)
                    val page = source.fetchFeedback(identity, read.after)
                    currentCoroutineContext().ensureActive()
                    if (source.feedbackIdentity() != identity) break
                    policy.feedbackPreferencesChanged(source.feedbackNotificationsEnabled())
                    val events = policy.observeFeedback(read, page)
                    currentCoroutineContext().ensureActive()
                    if (source.feedbackNotificationsEnabled()) events.forEach(publish)
                    if (!page.hasMore || read.after == null) break
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Missing endpoint, rejected page or storage failure preserves the committed cursor. */ }
        }
    }

    private suspend fun observeMessages() = coroutineScope {
        val invalidations = Channel<Unit>(Channel.CONFLATED)
        launch {
            while (isActive) { invalidations.trySend(Unit); delay(feedIntervalMillis) }
        }
        launch {
            var attempt = 0
            while (isActive) {
                try {
                    val result = source.listenOnce { invalidations.trySend(Unit) }
                    attempt = if (result.httpStatus == 200) 1 else attempt + 1
                    val retry = source.retry(attempt, result.serverRetryMs, result.retryAfterMs)
                    if (!retry.shouldReconnect) break
                    delay(checkNotNull(retry.delayMs))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    val retry = source.retry(++attempt, null, null)
                    if (!retry.shouldReconnect) break
                    delay(checkNotNull(retry.delayMs))
                }
            }
        }
        var failures = 0
        for (ignored in invalidations) {
            try {
                val feed = source.fetchFeed()
                failures = 0
                policy.observeBackend("developer-center", true).forEach(publish)
                policy.observeFeed(feed, System.currentTimeMillis()).forEach(publish)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: AndroidMessagesClient.AndroidMessagesException) {
                if (failure.kind == AndroidMessagesClient.ErrorKind.NETWORK ||
                    failure.kind == AndroidMessagesClient.ErrorKind.HTTP && (failure.httpStatus ?: 0) >= 500) {
                    if (++failures >= 2) policy.observeBackend("developer-center", false).forEach(publish)
                } else failures = 0
            } catch (_: Exception) { /* A corrupt cache or rejected permission never resets the reminder journal. */ }
        }
    }

    private suspend fun observeDevices() {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            if (source.observeAries()) {
                try {
                    val state = source.connectionState()
                    (state.opt("server_connected") as? Boolean)?.let { connected ->
                        policy.observeBackend("aries", connected).forEach(publish)
                    }
                    policy.observeDevice(state).forEach(publish)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Unknown status does not mean offline or unpaired. */ }
            }
            delay(deviceIntervalMillis)
        }
    }

    override fun close() { scope.cancel(); observation = null }
}
