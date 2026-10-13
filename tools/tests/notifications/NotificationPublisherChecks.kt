package com.dafeiyu.mobile.data

import android.content.Context
import java.util.concurrent.CancellationException

private fun Context.settings() = getSharedPreferences("dafeiyu_settings", Context.MODE_PRIVATE)
private fun Context.deliveries() = getSharedPreferences("android_update_notifications", Context.MODE_PRIVATE)
private fun Context.revokeOnNotify() {
    compatManager.beforeNotify = { permissionGranted = false }
}
private fun Context.mute(key: String) { settings().edit().putBoolean(key, false).apply() }
private fun event(category: NotificationEventPolicy.Category) =
    NotificationEventPolicy.Event("event-fixture", category, "fixture title", "fixture detail")

fun main() {
    var passed = 0
    fun checkCase(name: String, block: () -> Unit) {
        block(); passed++; println("PASS $name")
    }
    checkCase("running permission revoked between preflight and notify is skipped without crashing") {
        val context = Context(); val publisher = NotificationPublisher(context)
        context.revokeOnNotify(); publisher.postRunning(true)
        check(context.compatManager.attempts.size == 1 && context.compatManager.accepted.isEmpty())
        check(context.platformManager.channels.keys == NotificationChannelIds.all.toSet())
        context.permissionGranted = true; publisher.postRunning(true)
        check(context.compatManager.accepted.single().notification.ongoing)
        check(context.compatManager.accepted.single().notification.channel == NotificationChannelIds.LIVE_STATUS)
    }
    checkCase("completed permission race still clears the running notification and permits retry") {
        val context = Context(); val publisher = NotificationPublisher(context)
        context.revokeOnNotify(); publisher.postCompleted(true)
        check(context.compatManager.cancelled.size == 1 && context.compatManager.accepted.isEmpty())
        context.permissionGranted = true; publisher.postCompleted(true)
        check(context.compatManager.accepted.single().notification.channel == NotificationChannelIds.TASKS)
    }
    for (category in NotificationEventPolicy.Category.entries) {
        checkCase("${category.name} tagged notification survives revocation and retains its replacement identity") {
            val context = Context(); val publisher = NotificationPublisher(context)
            context.revokeOnNotify(); publisher.postEvent(event(category))
            check(context.compatManager.attempts.size == 1 && context.compatManager.accepted.isEmpty())
            context.permissionGranted = true; publisher.postEvent(event(category))
            val call = context.compatManager.accepted.single()
            check(call.tag == "event-event-fixture" && call.notification.channel == category.channel)
            check(call.id == context.compatManager.attempts.first().id)
        }
    }
    checkCase("rejected available update preserves the prior receipt and successful retry deduplicates") {
        val context = Context(); val publisher = NotificationPublisher(context)
        context.deliveries().edit().putString("last_delivered", "0.1.25:available").apply()
        val originalWrites = context.deliveries().writes
        context.revokeOnNotify(); publisher.postUpdate("0.1.26", false)
        check(context.deliveries().getString("last_delivered", null) == "0.1.25:available")
        check(context.deliveries().writes == originalWrites && context.compatManager.accepted.isEmpty())
        context.permissionGranted = true; publisher.postUpdate("0.1.26", false)
        check(context.deliveries().getString("last_delivered", null) == "0.1.26:available")
        check(context.deliveries().writes == originalWrites + 1)
        publisher.postUpdate("0.1.26", false)
        check(context.compatManager.attempts.size == 2 && context.compatManager.accepted.size == 1)
        check(context.deliveries().writes == originalWrites + 1)
    }
    checkCase("rejected ready update retains available stage and later retries exactly once") {
        val context = Context(); val publisher = NotificationPublisher(context)
        publisher.postUpdate("0.1.26", false)
        context.revokeOnNotify(); publisher.postUpdate("0.1.26", true)
        check(context.deliveries().getString("last_delivered", null) == "0.1.26:available")
        check(context.deliveries().writes == 1 && context.compatManager.accepted.size == 1)
        context.permissionGranted = true; publisher.postUpdate("0.1.26", true)
        check(context.deliveries().getString("last_delivered", null) == "0.1.26:ready")
        publisher.postUpdate("0.1.26", true)
        check(context.compatManager.attempts.size == 3 && context.compatManager.accepted.size == 2)
        check(context.deliveries().writes == 2)
    }
    checkCase("an initially denied permission or blocked app does not attempt posting or consume updates") {
        for (permissionDenied in listOf(true, false)) {
            val context = Context()
            if (permissionDenied) context.permissionGranted = false else context.notificationsEnabled = false
            val publisher = NotificationPublisher(context)
            publisher.postRunning(true); publisher.postCompleted(true); publisher.postUpdate("0.1.26", false)
            NotificationEventPolicy.Category.entries.forEach { publisher.postEvent(event(it)) }
            check(context.compatManager.attempts.isEmpty() && context.deliveries().writes == 0)
        }
    }
    checkCase("master mute gates events and updates while disabled lifecycle calls only clear running") {
        val context = Context(); context.mute("notifications_enabled")
        val publisher = NotificationPublisher(context)
        publisher.postRunning(false); publisher.postCompleted(false); publisher.postUpdate("0.1.26", false)
        NotificationEventPolicy.Category.entries.forEach { publisher.postEvent(event(it)) }
        check(context.compatManager.attempts.isEmpty() && context.deliveries().writes == 0)
        check(context.compatManager.cancelled.size == 2)
    }
    for (category in NotificationEventPolicy.Category.entries) {
        checkCase("${category.preference} only mutes its category and message mute also gates updates") {
            val context = Context(); context.mute(category.preference)
            val publisher = NotificationPublisher(context)
            NotificationEventPolicy.Category.entries.forEach { publisher.postEvent(event(it)) }
            val expectedChannels = NotificationEventPolicy.Category.entries.filter { it != category }.map { it.channel }
            check(context.compatManager.accepted.map { it.notification.channel } == expectedChannels)
            publisher.postUpdate("0.1.26", false)
            val expectUpdate = category != NotificationEventPolicy.Category.MESSAGE
            check((context.deliveries().writes == 1) == expectUpdate)
        }
    }
    checkCase("non-security posting failures propagate unchanged and never consume an update") {
        val calls: List<(NotificationPublisher) -> Unit> = listOf(
            { it.postRunning(true) }, { it.postCompleted(true) },
            { it.postEvent(event(NotificationEventPolicy.Category.MESSAGE)) },
            { it.postUpdate("0.1.26", false) },
        )
        for (failure in listOf(IllegalStateException("posting fixture"), CancellationException("cancel fixture"))) {
            for (call in calls) {
                val context = Context(); context.compatManager.failure = failure
                check(runCatching { call(NotificationPublisher(context)) }.exceptionOrNull() === failure)
                check(context.compatManager.accepted.isEmpty() && context.deliveries().writes == 0)
            }
        }
    }
    println("RESULT $passed/$passed actual production NotificationPublisher with memory-only Android/AndroidX substitutes; no system posting, Live Update promotion, OEM or FCM delivery exercised")
}
