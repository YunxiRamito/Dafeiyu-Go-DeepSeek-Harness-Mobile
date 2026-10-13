package com.dafeiyu.mobile.data

import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import org.json.JSONObject

private class MemoryJournal : RemoteDshJournal {
    @Volatile var document: JSONObject? = null
    var rejectWrites = false
    var writes = 0
    override fun read(): JSONObject? = document?.let { JSONObject(it.toString()) }
    override fun write(value: JSONObject) {
        if (rejectWrites) throw IOException("storage fixture unavailable")
        document = JSONObject(value.toString()); writes++
    }
}

private fun message(id: Int, time: Long, kind: AndroidMessagesClient.MessageKind = AndroidMessagesClient.MessageKind.NOTIFICATION) =
    AndroidMessagesClient.AndroidMessage(UUID(0, id.toLong()), kind, "notice $id", "plain\ntext", 
        AndroidMessagesClient.PublicationState.PUBLISHED, Instant.ofEpochMilli(time), Instant.ofEpochMilli(time),
        Instant.ofEpochMilli(time), null, null, LocalDate.of(2026, 10, 13), false, false, false, false, emptyList())

private fun connection(online: String = "online", connected: Boolean = true, group: String = "group-a") =
    JSONObject().put("node_id", "node-a").put("group_id", group).put("server_connected", connected)
        .put("pairing_status", "paired").put("relationship_confirmed", true)
        .put("local_device", JSONObject().put("id", "phone-a"))
        .put("remote_device", JSONObject().put("id", "pc-a").put("name", "Computer").put("online", online))

private class FakeSource : NativeNotificationMonitor.Source {
    @Volatile var allowed = true
    @Volatile var feedbackAllowed = true
    @Volatile var feedbackOwner: String? = "notification-owner-fixture"
    @Volatile var feedbackFailure: Exception? = null
    @Volatile var feedbackGate: CompletableDeferred<Unit>? = null
    @Volatile var feedbackNonCancellable = false
    @Volatile var aries = true
    @Volatile var feedFailure: Exception? = null
    @Volatile var status = connection()
    val polls = AtomicInteger()
    val ariesPolls = AtomicInteger()
    val listeners = AtomicInteger()
    val cancelled = AtomicInteger()
    val feedbackPolls = AtomicInteger()
    val feedbackHigh = AtomicLong()
    val feedbackRows = CopyOnWriteArrayList<DeveloperCenterClient.FeedbackNotification>()
    val feedbackReads = CopyOnWriteArrayList<Long?>()
    @Volatile var invalidate: (() -> Unit)? = null
    override fun enabled() = allowed
    override fun observeAries() = aries
    override fun feedbackIdentity() = feedbackOwner
    override fun feedbackNotificationsEnabled() = allowed && feedbackAllowed
    override suspend fun fetchFeedback(identity: String, after: Long?): DeveloperCenterClient.FeedbackNotificationPage {
        feedbackPolls.incrementAndGet(); feedbackReads.add(after)
        feedbackGate?.let { gate ->
            if (feedbackNonCancellable) withContext(NonCancellable) { gate.await() } else gate.await()
        }
        feedbackFailure?.let { throw it }
        val high = feedbackHigh.get()
        if (after == null) return DeveloperCenterClient.FeedbackNotificationPage(emptyList(), high, false)
        val all = feedbackRows.filter { it.sequence > after && it.sequence <= high }
        val page = all.take(2)
        val more = all.size > page.size
        return DeveloperCenterClient.FeedbackNotificationPage(page, if (more) page.last().sequence else high, more)
    }
    fun appendFeedback(sequence: Long) {
        feedbackRows += feedbackEvent(sequence)
        feedbackHigh.set(sequence)
    }
    override suspend fun fetchFeed(): List<AndroidMessagesClient.AndroidMessage> {
        polls.incrementAndGet(); feedFailure?.let { throw it }; return emptyList()
    }
    override suspend fun listenOnce(onChanged: () -> Unit): AndroidMessagesClient.SseResult {
        listeners.incrementAndGet(); invalidate = onChanged
        try { awaitCancellation() } finally { cancelled.incrementAndGet(); invalidate = null }
    }
    override fun retry(attempt: Int, serverDelay: Long?, retryAfter: Long?) =
        AndroidMessagesClient.ReconnectSuggestion(false, null, attempt)
    override suspend fun connectionState(): JSONObject { ariesPolls.incrementAndGet(); return JSONObject(status.toString()) }
}

private fun feedbackEvent(sequence: Long) = DeveloperCenterClient.FeedbackNotification(sequence, UUID(0, 500),
    "reply", null, "反馈有开发者回复", "打开反馈查看内容。", Instant.ofEpochMilli(1000 + sequence))

private fun feedbackPage(cursor: Long, vararg sequences: Long, more: Boolean = false) =
    DeveloperCenterClient.FeedbackNotificationPage(sequences.map(::feedbackEvent), cursor, more)

fun main() = runBlocking {
    var passed = 0
    suspend fun checkCase(name: String, block: suspend () -> Unit) {
        block(); passed++; println("PASS $name")
    }
    checkCase("initial feed establishes a durable baseline and does not alert historical pages") {
        val journal = MemoryJournal(); val policy = NotificationEventPolicy(journal)
        check(policy.observeFeed(listOf(message(1, 10)), 100).isEmpty())
        check(NotificationEventPolicy(journal).observeFeed(listOf(message(2, 20)), 200).isEmpty())
    }
    checkCase("new announcements and messages use separate categories and survive restart deduplication") {
        val journal = MemoryJournal(); val policy = NotificationEventPolicy(journal)
        policy.observeFeed(emptyList(), 100)
        val items = listOf(message(3, 101, AndroidMessagesClient.MessageKind.ANNOUNCEMENT), message(4, 102))
        val events = policy.observeFeed(items, 200)
        check(events.map { it.category } == listOf(NotificationEventPolicy.Category.ANNOUNCEMENT, NotificationEventPolicy.Category.MESSAGE))
        check(events.all { it.detail == "plain text" })
        check(NotificationEventPolicy(journal).observeFeed(items, 300).isEmpty())
    }
    checkCase("read expired withdrawn and future content never posts prematurely") {
        val policy = NotificationEventPolicy(MemoryJournal()); policy.observeFeed(emptyList(), 100)
        val ignored = listOf(message(5, 101).copy(read = true), message(6, 101).copy(expired = true),
            message(7, 101).copy(withdrawnAt = Instant.ofEpochMilli(102)),
            message(8, 101).copy(expiresAt = Instant.ofEpochMilli(150)), message(9, 400))
        check(policy.observeFeed(ignored, 200).isEmpty())
        check(policy.observeFeed(ignored, 400).single().id == "notice-${UUID(0, 9)}")
    }
    checkCase("durable storage failure does not consume an event or invent successful delivery") {
        val journal = MemoryJournal(); val policy = NotificationEventPolicy(journal); policy.observeFeed(emptyList(), 100)
        journal.rejectWrites = true
        check(runCatching { policy.observeFeed(listOf(message(10, 110)), 200) }.isFailure)
        journal.rejectWrites = false
        check(policy.observeFeed(listOf(message(10, 110)), 200).size == 1)
    }
    checkCase("unknown presence and disconnected transport keep confirmed peer status") {
        val policy = NotificationEventPolicy(MemoryJournal())
        check(policy.observeDevice(connection()).isEmpty())
        check(policy.observeDevice(connection("unknown", false)).isEmpty())
        check(policy.observeDevice(connection("offline", false)).isEmpty())
        check(policy.observeDevice(connection()).isEmpty())
        check(policy.observeDevice(connection("offline")).single().title == "电脑已离线")
    }
    checkCase("only exact local-device group and terminal status can announce revoked pairing") {
        val journal = MemoryJournal(); val policy = NotificationEventPolicy(journal); policy.observeDevice(connection())
        fun terminal(group: String, local: String, status: String) = connection().put("relationship_confirmed", false)
            .put("pairing_status", "revoked").put("remote_device", JSONObject.NULL)
            .put("terminal_receipt", JSONObject().put("group_id", group).put("device_id", local).put("status", status))
        check(policy.observeDevice(terminal("wrong", "phone-a", "unpaired")).isEmpty())
        check(policy.observeDevice(terminal("group-a", "wrong", "unpaired")).isEmpty())
        check(policy.observeDevice(terminal("group-a", "phone-a", "unknown")).isEmpty())
        val valid = terminal("group-a", "phone-a", "group_closed")
        check(policy.observeDevice(valid).single().title == "配对关系已解除")
        check(NotificationEventPolicy(journal).observeDevice(valid).isEmpty())
    }
    checkCase("changing relationship scope establishes peer baseline without crossing status") {
        val policy = NotificationEventPolicy(MemoryJournal()); policy.observeDevice(connection())
        check(policy.observeDevice(connection("offline", group = "group-b")).isEmpty())
        check(policy.observeDevice(connection("online", group = "group-b")).single().title == "电脑已上线")
    }
    checkCase("backend transitions persist before publication and unchanged polls avoid disk rewrites") {
        val journal = MemoryJournal(); val policy = NotificationEventPolicy(journal)
        check(policy.observeBackend("aries", true).isEmpty()); val writes = journal.writes
        check(policy.observeBackend("aries", true).isEmpty() && journal.writes == writes)
        check(policy.observeBackend("aries", false).single().id == "transition-1")
        check(NotificationEventPolicy(journal).observeBackend("aries", true).single().id == "transition-2")
    }
    checkCase("corrupt journal is rejected and original bytes remain owned by recovery") {
        val journal = MemoryJournal().apply { document = JSONObject().put("schema", 1).put("sequence", -1) }
        check(runCatching { NotificationEventPolicy(journal) }.isFailure && journal.writes == 0)
    }
    checkCase("notification category preferences and Android channel IDs cover all event types") {
        check(NotificationEventPolicy.Category.entries.map { it.preference }.toSet() == setOf(
            "announcement_notifications", "message_notifications", "device_notifications", "backend_status_notifications"))
        check(NotificationEventPolicy.Category.entries.map { it.channel }.distinct().size == 4)
    }
    checkCase("feedback baseline and cold restart retain the cursor without replaying prior replies") {
        val journal = MemoryJournal(); val policy = NotificationEventPolicy(journal)
        policy.feedbackPreferencesChanged(true)
        val first = policy.feedbackRead("owner-a")
        check(first.after == null && policy.observeFeedback(first, feedbackPage(8)).isEmpty())
        check(policy.observeFeedback(policy.feedbackRead("owner-a"), feedbackPage(9, 9)).single().category == NotificationEventPolicy.Category.MESSAGE)
        val cold = NotificationEventPolicy(journal)
        check(cold.feedbackRead("owner-a").after == 9L)
        check(cold.observeFeedback(cold.feedbackRead("owner-a"), feedbackPage(9)).isEmpty())
        check(cold.observeFeedback(cold.feedbackRead("owner-a"), feedbackPage(10, 10)).size == 1)
    }
    checkCase("failed feedback persistence leaves the previous durable cursor available for retry") {
        val journal = MemoryJournal(); val policy = NotificationEventPolicy(journal)
        policy.feedbackPreferencesChanged(true); policy.observeFeedback(policy.feedbackRead("owner-a"), feedbackPage(0))
        journal.rejectWrites = true
        check(runCatching { policy.observeFeedback(policy.feedbackRead("owner-a"), feedbackPage(1, 1)) }.isFailure)
        check(policy.feedbackRead("owner-a").after == 0L)
        journal.rejectWrites = false
        check(policy.observeFeedback(policy.feedbackRead("owner-a"), feedbackPage(1, 1)).size == 1)
    }
    checkCase("feedback owner and fast preference changes invalidate stale in-flight responses") {
        val policy = NotificationEventPolicy(MemoryJournal()); policy.feedbackPreferencesChanged(true)
        policy.observeFeedback(policy.feedbackRead("owner-a"), feedbackPage(0))
        val beforeMute = policy.feedbackRead("owner-a")
        policy.feedbackPreferencesChanged(false); policy.feedbackPreferencesChanged(true)
        check(policy.observeFeedback(beforeMute, feedbackPage(1, 1)).isEmpty())
        check(policy.feedbackRead("owner-a").after == null)
        policy.observeFeedback(policy.feedbackRead("owner-a"), feedbackPage(1))
        val previousOwner = policy.feedbackRead("owner-a")
        check(policy.feedbackRead("owner-b").after == null)
        check(policy.observeFeedback(previousOwner, feedbackPage(2, 2)).isEmpty())
        check(policy.feedbackRead("owner-b").after == null)
    }
    checkCase("muted feedback tracks high-water progress and reopening establishes a fresh baseline") {
        val journal = MemoryJournal(); val policy = NotificationEventPolicy(journal)
        policy.feedbackPreferencesChanged(false)
        policy.observeFeedback(policy.feedbackRead("owner-a"), feedbackPage(10))
        policy.observeFeedback(policy.feedbackRead("owner-a"), feedbackPage(11))
        check(journal.document!!.getJSONObject("feedback").getLong("cursor") == 11L)
        val cold = NotificationEventPolicy(journal); cold.feedbackPreferencesChanged(true)
        check(cold.feedbackRead("owner-a").after == null)
        cold.observeFeedback(cold.feedbackRead("owner-a"), feedbackPage(12))
        check(cold.observeFeedback(cold.feedbackRead("owner-a"), feedbackPage(13, 13)).size == 1)
    }
    checkCase("filtered or deleted feedback advances scanned pages without inventing a notification") {
        val policy = NotificationEventPolicy(MemoryJournal()); policy.feedbackPreferencesChanged(true)
        policy.observeFeedback(policy.feedbackRead("owner-a"), feedbackPage(0))
        check(policy.observeFeedback(policy.feedbackRead("owner-a"), feedbackPage(2, more = true)).isEmpty())
        check(policy.feedbackRead("owner-a").after == 2L)
        check(policy.observeFeedback(policy.feedbackRead("owner-a"), feedbackPage(4, 4)).size == 1)
        val before = policy.feedbackRead("owner-a")
        check(runCatching { policy.observeFeedback(before, feedbackPage(3)) }.isFailure)
        check(policy.feedbackRead("owner-a").after == 4L)
    }
    suspend fun until(predicate: () -> Boolean) = withTimeout(3000) { while (!predicate()) delay(5) }
    checkCase("master off cancels actual suspended SSE and stops all observation jobs") {
        val source = FakeSource(); val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val monitor = NativeNotificationMonitor(parent, source, NotificationEventPolicy(MemoryJournal()), {}, 25, 25)
        try {
            monitor.reconcile(true, false); until { source.polls.get() > 0 && source.listeners.get() == 1 }
            monitor.reconcile(true, false); check(source.listeners.get() == 1)
            source.allowed = false; monitor.reconcile(true, false); until { source.cancelled.get() == 1 }
            delay(50); val polls = source.polls.get(); val devicePolls = source.ariesPolls.get(); delay(90)
            check(source.polls.get() == polls && source.ariesPolls.get() == devicePolls)
        } finally { monitor.close(); parent.cancel() }
    }
    checkCase("power-saving pauses only in background and resumes feed and device observations") {
        val source = FakeSource(); val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val monitor = NativeNotificationMonitor(parent, source, NotificationEventPolicy(MemoryJournal()), {}, 25, 25)
        try {
            monitor.reconcile(true, true); until { source.listeners.get() == 1 }
            monitor.reconcile(false, true); until { source.cancelled.get() == 1 }
            monitor.reconcile(true, true); until { source.listeners.get() == 2 && source.polls.get() >= 2 }
        } finally { monitor.close(); parent.cancel() }
    }
    checkCase("SSE invalidation actually refreshes the Android feed before periodic polling") {
        val source = FakeSource(); val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val monitor = NativeNotificationMonitor(parent, source, NotificationEventPolicy(MemoryJournal()), {}, 60_000, 60_000)
        try {
            monitor.reconcile(true, false); until { source.polls.get() == 1 && source.invalidate != null }
            source.invalidate?.invoke(); until { source.polls.get() == 2 }
        } finally { monitor.close(); parent.cancel() }
    }
    checkCase("network failure requires two checks and recovers once without duplicate status alerts") {
        val source = FakeSource(); val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val events = CopyOnWriteArrayList<NotificationEventPolicy.Event>()
        val monitor = NativeNotificationMonitor(parent, source, NotificationEventPolicy(MemoryJournal()), events::add, 30, 60_000)
        try {
            monitor.reconcile(true, false); until { source.polls.get() >= 1 }
            source.feedFailure = AndroidMessagesClient.AndroidMessagesException(AndroidMessagesClient.ErrorKind.NETWORK, "offline fixture")
            until { events.any { it.title == "后端暂时不可用" } }
            check(events.count { it.title == "后端暂时不可用" } == 1)
            source.feedFailure = null; until { events.any { it.title == "后端连接已恢复" } }
            delay(70); check(events.count { it.title == "后端连接已恢复" } == 1)
        } finally { monitor.close(); parent.cancel() }
    }
    checkCase("feedback monitor waits for an existing identity, drains ordered pages and preserves failures") {
        val source = FakeSource().apply { feedbackOwner = null }
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val journal = MemoryJournal(); val events = CopyOnWriteArrayList<NotificationEventPolicy.Event>()
        val monitor = NativeNotificationMonitor(parent, source, NotificationEventPolicy(journal), events::add, 25, 60_000)
        try {
            monitor.reconcile(true, false); until { source.listeners.get() == 1 }; delay(70)
            check(source.feedbackPolls.get() == 0)
            source.feedbackOwner = "owner-live"
            until { journal.document?.optJSONObject("feedback")?.opt("cursor") == 0 }
            for (sequence in 1L..6L) source.appendFeedback(sequence)
            until { events.count { it.id.startsWith("feedback-") } == 6 }
            check(journal.document!!.getJSONObject("feedback").getLong("cursor") == 6L)
            source.feedbackFailure = DeveloperCenterClient.DeveloperCenterException(DeveloperCenterClient.ErrorKind.HTTP, "503 fixture", 503)
            source.appendFeedback(7); val before = source.feedbackPolls.get()
            until { source.feedbackPolls.get() >= before + 2 }
            check(journal.document!!.getJSONObject("feedback").getLong("cursor") == 6L)
            source.feedbackFailure = null
            until { events.count { it.id.startsWith("feedback-") } == 7 }
        } finally { monitor.close(); parent.cancel() }
    }
    checkCase("message mute continues feedback progress and does not replay muted events on reopening") {
        val source = FakeSource(); val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val journal = MemoryJournal(); val events = CopyOnWriteArrayList<NotificationEventPolicy.Event>()
        val monitor = NativeNotificationMonitor(parent, source, NotificationEventPolicy(journal), events::add, 25, 60_000)
        try {
            monitor.reconcile(true, false); until { source.feedbackReads.size > 1 }
            source.feedbackAllowed = false; monitor.onNotificationPreferencesChanged(); source.appendFeedback(1)
            until { journal.document?.optJSONObject("feedback")?.optLong("cursor") == 1L }
            source.appendFeedback(2); source.feedbackAllowed = true; monitor.onNotificationPreferencesChanged()
            until { journal.document?.optJSONObject("feedback")?.optLong("cursor") == 2L }
            check(events.none { it.id.startsWith("feedback-") })
            source.appendFeedback(3); until { events.any { it.id.endsWith("-3") && it.id.startsWith("feedback-") } }
            check(events.count { it.id.startsWith("feedback-") } == 1)
        } finally { monitor.close(); parent.cancel() }
    }
    checkCase("master mute cancels feedback observation and reopening skips the disabled interval") {
        val source = FakeSource(); val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val journal = MemoryJournal(); val events = CopyOnWriteArrayList<NotificationEventPolicy.Event>()
        val monitor = NativeNotificationMonitor(parent, source, NotificationEventPolicy(journal), events::add, 25, 60_000)
        try {
            monitor.reconcile(true, false); until { source.feedbackReads.size > 1 }
            source.allowed = false; monitor.reconcile(true, false); until { source.cancelled.get() == 1 }
            val before = source.feedbackPolls.get(); source.appendFeedback(1); delay(80)
            check(source.feedbackPolls.get() == before)
            source.allowed = true; monitor.reconcile(true, false)
            until { journal.document?.optJSONObject("feedback")?.optLong("cursor") == 1L }
            check(events.none { it.id.startsWith("feedback-") })
            source.appendFeedback(2); until { events.any { it.id.endsWith("-2") && it.id.startsWith("feedback-") } }
        } finally { monitor.close(); parent.cancel() }
    }
    checkCase("power background pause retains enabled feedback cursor and resumes missed updates") {
        val source = FakeSource(); val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val journal = MemoryJournal(); val events = CopyOnWriteArrayList<NotificationEventPolicy.Event>()
        val monitor = NativeNotificationMonitor(parent, source, NotificationEventPolicy(journal), events::add, 25, 60_000)
        try {
            monitor.reconcile(true, true); until { source.feedbackReads.size > 1 }
            monitor.reconcile(false, true); until { source.cancelled.get() == 1 }
            val before = source.feedbackPolls.get(); source.appendFeedback(1); delay(80)
            check(source.feedbackPolls.get() == before)
            monitor.reconcile(true, true)
            until { events.any { it.id.startsWith("feedback-") } }
            check(events.count { it.id.startsWith("feedback-") } == 1)
        } finally { monitor.close(); parent.cancel() }
    }
    checkCase("a cancelled noncooperative feedback transport cannot commit its returned page") {
        val source = FakeSource(); val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val journal = MemoryJournal(); val events = CopyOnWriteArrayList<NotificationEventPolicy.Event>()
        val monitor = NativeNotificationMonitor(parent, source, NotificationEventPolicy(journal), events::add, 25, 60_000)
        try {
            monitor.reconcile(true, true); until { source.feedbackReads.size > 1 }
            val gate = CompletableDeferred<Unit>(); source.feedbackNonCancellable = true; source.feedbackGate = gate
            source.appendFeedback(1); val before = source.feedbackPolls.get()
            until { source.feedbackPolls.get() > before }
            monitor.reconcile(false, true); gate.complete(Unit); until { source.cancelled.get() == 1 }; delay(80)
            check(journal.document!!.getJSONObject("feedback").getLong("cursor") == 0L)
            check(events.none { it.id.startsWith("feedback-") })
        } finally { source.feedbackGate?.complete(Unit); monitor.close(); parent.cancel() }
    }
    println("RESULT $passed/$passed production notification policy and coroutine lifecycle checks; Android posting, live promotion and OEM/FCM delivery are not exercised")
}
