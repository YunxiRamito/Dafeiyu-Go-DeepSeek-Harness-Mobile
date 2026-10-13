package com.dafeiyu.mobile.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Durable reminder decisions are separate from task execution and permissions. */
class NotificationEventPolicy(private val journal: RemoteDshJournal) {
    enum class Category(val preference: String, val channel: String) {
        ANNOUNCEMENT("announcement_notifications", NotificationChannelIds.ANNOUNCEMENTS),
        MESSAGE("message_notifications", NotificationChannelIds.GENERAL),
        DEVICE("device_notifications", NotificationChannelIds.DEVICES),
        BACKEND("backend_status_notifications", NotificationChannelIds.BACKEND),
    }
    data class Event(val id: String, val category: Category, val title: String, val detail: String)
    data class FeedbackRead(val owner: String, val after: Long?, val epoch: Long)

    private var document = journal.read()?.also {
        remoteValidateJson(it, 2 * 1024 * 1024)
        require(it.optInt("schema") == 1 && it.getJSONArray("seen").length() <= MAX_SEEN)
        require(it.getLong("sequence") >= 0)
        it.getBoolean("feedInitialized")
        it.getJSONObject("backends")
        val ids = it.getJSONArray("seen")
        for (index in 0 until ids.length()) require(ids.getString(index).length in 1..128)
        if (it.has("feedbackEnabled")) require(it.opt("feedbackEnabled") is Boolean)
        if (it.has("feedbackEpoch")) require(strictLong(it, "feedbackEpoch") >= 0L)
        if (it.has("feedback")) {
            val feedback = it.getJSONObject("feedback")
            require(feedback.getString("owner").matches(Regex("[0-9a-f]{64}")))
            require(feedback.opt("needsBaseline") is Boolean)
            if (!feedback.isNull("cursor")) require(strictLong(feedback, "cursor") >= 0L)
        }
    } ?: JSONObject().put("schema", 1).put("sequence", 0L).put("feedInitialized", false)
        .put("seen", JSONArray()).put("backends", JSONObject())

    /** Persist a mute boundary even if off/on happens entirely between polls. */
    @Synchronized
    fun feedbackPreferencesChanged(enabled: Boolean) {
        if (document.opt("feedbackEnabled") == enabled) return
        val next = JSONObject(document.toString()).put("feedbackEnabled", enabled)
        next.put("feedbackEpoch", Math.addExact(next.optLong("feedbackEpoch", 0L), 1L))
        next.optJSONObject("feedback")?.put("needsBaseline", true)
        commit(next)
    }

    @Synchronized
    fun feedbackRead(identity: String): FeedbackRead {
        require(identity.isNotBlank() && identity.length <= 128 && identity.none(Char::isISOControl))
        val owner = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        if (document.optJSONObject("feedback")?.optString("owner") != owner) {
            val next = JSONObject(document.toString()).put("feedback", JSONObject().put("owner", owner)
                .put("cursor", JSONObject.NULL).put("needsBaseline", true))
            next.put("feedbackEpoch", Math.addExact(next.optLong("feedbackEpoch", 0L), 1L))
            commit(next)
        }
        val feedback = document.getJSONObject("feedback")
        val after = if (!document.optBoolean("feedbackEnabled") || feedback.getBoolean("needsBaseline") ||
            feedback.isNull("cursor")) null else feedback.getLong("cursor")
        return FeedbackRead(owner, after, document.getLong("feedbackEpoch"))
    }

    /** A stale identity/preference response cannot consume a newer cursor. */
    @Synchronized
    fun observeFeedback(read: FeedbackRead, page: DeveloperCenterClient.FeedbackNotificationPage): List<Event> {
        val feedback = document.optJSONObject("feedback") ?: return emptyList()
        if (feedback.optString("owner") != read.owner || document.optLong("feedbackEpoch", -1L) != read.epoch) return emptyList()
        val currentAfter = if (!document.optBoolean("feedbackEnabled") || feedback.getBoolean("needsBaseline") ||
            feedback.isNull("cursor")) null else feedback.getLong("cursor")
        if (currentAfter != read.after) return emptyList()
        require(page.cursor >= (read.after ?: 0L) && page.events.size <= 50)
        require(read.after != null || page.events.isEmpty() && !page.hasMore)
        require(!page.hasMore || page.cursor > (read.after ?: 0L))
        var previous = read.after ?: 0L
        for (item in page.events) {
            require(item.sequence > previous && item.sequence <= page.cursor)
            previous = item.sequence
        }
        val next = JSONObject(document.toString())
        next.getJSONObject("feedback").put("cursor", page.cursor).put("needsBaseline", false)
        val events = if (read.after == null || !next.optBoolean("feedbackEnabled")) emptyList() else page.events.map {
            Event("feedback-${read.owner}-${it.sequence}", Category.MESSAGE, it.title, it.detail)
        }
        commit(next)
        return events
    }

    @Synchronized
    fun observeFeed(items: List<AndroidMessagesClient.AndroidMessage>, now: Long): List<Event> {
        require(items.size <= 1000 && now >= 0)
        val next = JSONObject(document.toString())
        val seenArray = next.getJSONArray("seen")
        val seen = (0 until seenArray.length()).map { seenArray.getString(it) }.toMutableSet()
        val events = mutableListOf<Event>()
        if (!next.has("feedEstablishedAt")) next.put("feedEstablishedAt", now)
        val baseline = next.getLong("feedEstablishedAt")
        for (item in items) {
            if (item.publishedAt.toEpochMilli() > now) continue
            val identity = item.id.toString()
            if (!seen.add(identity)) continue
            seenArray.put(identity)
            if (!next.optBoolean("feedInitialized") || item.publishedAt.toEpochMilli() <= baseline ||
                item.read || item.expired || item.withdrawnAt != null ||
                item.expiresAt?.toEpochMilli()?.let { it <= now } == true) continue
            val category = if (item.kind == AndroidMessagesClient.MessageKind.ANNOUNCEMENT) Category.ANNOUNCEMENT else Category.MESSAGE
            events += Event("notice-$identity", category, item.title,
                item.markdown.replace('\n', ' ').take(320))
        }
        next.put("feedInitialized", true)
        if (seenArray.length() > MAX_SEEN) next.put("seen", JSONArray().also { bounded ->
            for (index in seenArray.length() - MAX_SEEN until seenArray.length()) bounded.put(seenArray.getString(index))
        })
        commit(next)
        return events
    }

    /** Unknown/offline transport never invents a terminal pairing transition. */
    @Synchronized
    fun observeDevice(state: JSONObject): List<Event> {
        val node = state.remoteOptionalString("node_id") ?: return emptyList()
        val group = state.remoteOptionalString("group_id") ?: return emptyList()
        val local = state.optJSONObject("local_device")?.remoteOptionalString("id") ?: return emptyList()
        val next = JSONObject(document.toString())
        val previous = next.optJSONObject("device")
        val peer = state.optJSONObject("remote_device")
        val peerId = peer?.remoteOptionalString("id") ?: previous?.remoteOptionalString("peer")
            ?.takeIf { previous.optString("node") == node && previous.optString("group") == group }
            ?: return emptyList()
        val same = previous?.optString("node") == node && previous.optString("group") == group &&
            previous.optString("local") == local && previous.optString("peer") == peerId
        val current = if (same) JSONObject(checkNotNull(previous).toString()) else JSONObject()
            .put("node", node).put("group", group).put("local", local).put("peer", peerId).put("confirmed", false)
        val confirmed = state.optBoolean("relationship_confirmed") || state.optString("pairing_status") == "paired"
        val events = mutableListOf<Event>()
        val terminal = state.optJSONObject("terminal_receipt")
        if (same && current.optBoolean("confirmed") && terminal != null &&
            terminal.optString("group_id") == group && terminal.optString("device_id") == local &&
            terminal.optString("status") in setOf("unpaired", "left", "revoked", "group_closed") &&
            state.optString("pairing_status") in setOf("unpaired", "revoked")) {
            events += transition(next, Category.DEVICE, "配对关系已解除", "电脑与手机的旧配对权限已撤销。")
            current.put("confirmed", false)
        } else if (confirmed) {
            val online = if (state.opt("server_connected") == true)
                peer?.remoteOptionalString("online")?.takeIf { it == "online" || it == "offline" } else null
            val previousOnline = current.remoteOptionalString("online")
            if (same && online != null && previousOnline != null && online != previousOnline) {
                val name = peer?.remoteOptionalString("name")?.takeIf { it.isNotBlank() } ?: "已配对电脑"
                events += transition(next, Category.DEVICE,
                    if (online == "online") "电脑已上线" else "电脑已离线",
                    if (online == "online") "$name 可以接收任务。" else "$name 暂时无法接收任务；配对关系保留。")
            }
            current.put("confirmed", true)
            if (online != null) current.put("online", online)
        }
        next.put("device", current)
        commit(next)
        return events
    }

    @Synchronized
    fun observeBackend(id: String, available: Boolean): List<Event> {
        require(id in setOf("aries", "developer-center"))
        val next = JSONObject(document.toString())
        val backends = next.getJSONObject("backends")
        val previous = backends.opt(id) as? Boolean
        backends.put(id, available)
        val events = if (previous != null && previous != available) listOf(transition(next, Category.BACKEND,
            if (available) "后端连接已恢复" else "后端暂时不可用",
            (if (id == "aries") "Aries" else "产品服务") + if (available) " 已恢复连接。" else " 无法连接，回到应用后可查看当前状态。")) else emptyList()
        commit(next)
        return events
    }

    private fun transition(next: JSONObject, category: Category, title: String, detail: String): Event {
        val sequence = Math.addExact(next.getLong("sequence"), 1L)
        next.put("sequence", sequence)
        return Event("transition-$sequence", category, title, detail)
    }
    private fun commit(next: JSONObject) {
        remoteValidateJson(next, 2 * 1024 * 1024)
        if (next.toString() == document.toString()) return
        journal.write(next)
        document = next
    }

    companion object {
        private const val MAX_SEEN = 4096
        private fun strictLong(value: JSONObject, key: String): Long = when (val number = value.opt(key)) {
            is Int -> number.toLong()
            is Long -> number
            else -> error("Invalid persisted notification integer.")
        }
    }
}
