package com.dafeiyu.mobile.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*

private var passed = 0

fun main(args: Array<String>) {
    val root = File(args.single()).apply { mkdirs() }
    try {
        runBlocking(fixtureMain) {
            suspend fun test(name: String, body: suspend (WorkspaceStore, SendPersistenceSubject) -> Unit) {
                val ctx = Context(File(root, "case-${passed + 1}").apply { mkdirs() })
                val store = WorkspaceStore(ctx)
                val subject = SendPersistenceSubject(store)
                try {
                    body(store, subject)
                    passed++
                    println("PASS $name")
                } finally {
                    AtomicFile.beforeStartWrite = null
                    AtomicFile.failStartFor = null
                    AtomicFile.failFinishFor = null
                    AtomicFile.failDeleteFor = null
                    subject.shutdown()
                }
            }
            test("preflight failure retains draft and does not cancel the old writer or touch the index") { store, subject ->
                val writes = AtomicInteger()
                val priorWriter = Job()
                subject.saveJob = priorWriter
                subject.deepSeekClient.onValidate = { error("offline preflight failure") }
                AtomicFile.beforeStartWrite = { writes.incrementAndGet() }
                try {
                    check(!subject.sendMessage("keep draft"))
                    check(priorWriter.isActive && subject.saveJob === priorWriter)
                    check(writes.get() == 0 && !subject.sendPending)
                    check(subject.paidCalls == 0 && subject.clearedAttachments == 0)
                    check(subject.conversationMessages.getValue("c1").isEmpty())
                    check(subject.notice.contains("草稿已保留"))
                    check(store.initialize() == null)
                } finally {
                    priorWriter.cancel()
                }
            }
            test("pending preflight rejects duplicate send before any candidate write") { _, subject ->
                val entered = CompletableDeferred<Unit>()
                val release = CountDownLatch(1)
                val validations = AtomicInteger()
                val writes = AtomicInteger()
                subject.deepSeekClient.onValidate = {
                    validations.incrementAndGet()
                    entered.complete(Unit)
                    check(release.await(10, TimeUnit.SECONDS))
                }
                AtomicFile.beforeStartWrite = { writes.incrementAndGet() }
                coroutineScope {
                    val first = async { subject.sendMessage("first") }
                    try {
                        withTimeout(10_000) { entered.await() }
                        check(subject.sendPending && !subject.sendMessage("duplicate"))
                        check(writes.get() == 0 && subject.paidCalls == 0 && validations.get() == 1)
                    } finally {
                        release.countDown()
                    }
                    check(first.await() && subject.paidCalls == 1 && !subject.sendPending)
                }
            }
            test("candidate is durably readable before the paid callback and accepted once") { store, subject ->
                subject.onPaid = {
                    val saved = requireNotNull(store.initialize()).messages.getValue("c1").single()
                    check(saved.text == "hello" && saved.role == "user")
                }
                check(subject.sendMessage("hello"))
                check(subject.paidCalls == 1 && subject.clearedAttachments == 1)
                check(!subject.sendMessage("duplicate"))
                check(subject.paidCalls == 1)
            }
            test("candidate index overflow retains draft and old index with zero paid calls") { store, subject ->
                val input = "i".repeat(512 * 1024)
                subject.conversationMessages.getValue("c1").addAll((1L..15L).map {
                    ChatMessage(it, "user", "prior", modelInput = input)
                })
                subject.nextMessageId = 16L
                subject.attachmentPreviews = listOf(AttachmentPreview("a", "text.txt", input))
                val before = store.encode(WorkspaceSnapshot(subject.workspaces, subject.conversations,
                    subject.conversationMessages.mapValues { it.value.toList() }, "default", "c1"))
                val draft = "keep me"
                check(!subject.sendMessage(draft))
                check(draft == "keep me" && subject.paidCalls == 0 && subject.clearedAttachments == 0)
                check(subject.conversationMessages.getValue("c1").size == 15)
                check(File(store.root, "workspace-index.json").readBytes().contentEquals(before))
                check(subject.notice.contains("草稿已保留") && subject.workspaceSaveError == null)
            }
            test("candidate atomic write failure does not append or call the provider") { store, subject ->
                val writes = AtomicInteger()
                AtomicFile.beforeStartWrite = { file ->
                    if (file.name == "workspace-index.json" && writes.incrementAndGet() == 2)
                        AtomicFile.failFinishFor = file.name
                }
                check(!subject.sendMessage("keep"))
                check(subject.paidCalls == 0 && subject.conversationMessages.getValue("c1").isEmpty())
                check(requireNotNull(store.initialize()).messages.getValue("c1").isEmpty())
                check(!subject.sendPending && subject.notice.contains("重试发送"))
            }
            test("unsaved base blocks paid calls until truthful save retry succeeds") { store, subject ->
                subject.conversationMessages.getValue("c1") += ChatMessage(1L, "assistant", "final answer")
                AtomicFile.failFinishFor = "workspace-index.json"
                check(!subject.sendMessage("next"))
                check(subject.workspaceSaveError != null && subject.paidCalls == 0)
                check(store.hasRecovery())
                check(!subject.sendMessage("still blocked"))
                AtomicFile.failFinishFor = null
                check(subject.retryWorkspaceSave())
                check(subject.workspaceSaveError == null && !store.hasRecovery())
                check(requireNotNull(store.initialize()).messages.getValue("c1").single().text == "final answer")
                check(subject.sendMessage("next"))
                check(subject.paidCalls == 1)
            }
            test("pending precommit rejects a concurrent send while all writes run off the UI dispatcher") { _, subject ->
                val entered = CompletableDeferred<Unit>()
                val release = CountDownLatch(1)
                val writes = AtomicInteger()
                AtomicFile.beforeStartWrite = { file ->
                    if (file.name == "workspace-index.json") {
                        check(!Thread.currentThread().name.startsWith("pool-"))
                        if (writes.incrementAndGet() == 2) {
                            entered.complete(Unit)
                            check(release.await(10, TimeUnit.SECONDS))
                        }
                    }
                }
                coroutineScope {
                    val first = async { subject.sendMessage("first") }
                    try {
                        withTimeout(10_000) { entered.await() }
                        check(subject.sendPending && !subject.sendMessage("second"))
                        check(subject.paidCalls == 0)
                    } finally {
                        release.countDown()
                    }
                    check(first.await())
                    check(subject.paidCalls == 1)
                }
            }
            test("precommit cancelAndJoin waits for the prior noncancellable writer before committing newer state") { store, subject ->
                val entered = CompletableDeferred<Unit>()
                val release = CountDownLatch(1)
                val writes = AtomicInteger()
                AtomicFile.beforeStartWrite = { file ->
                    if (file.name == "workspace-index.json" && writes.incrementAndGet() == 1) {
                        entered.complete(Unit)
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                }
                subject.mutate("old")
                withTimeout(10_000) { entered.await() }
                subject.conversationMessages.getValue("c1") += ChatMessage(2L, "assistant", "new")
                coroutineScope {
                    val send = async { subject.sendMessage("next") }
                    try {
                        yield()
                        check(!send.isCompleted && subject.paidCalls == 0)
                    } finally {
                        release.countDown()
                    }
                    check(send.await())
                }
                check(requireNotNull(store.initialize()).messages.getValue("c1").map { it.text } ==
                    listOf("old", "new", "next"))
            }
            test("conversation boundary change during precommit keeps locally accepted message without paid call") { store, subject ->
                val entered = CompletableDeferred<Unit>()
                val release = CountDownLatch(1)
                val writes = AtomicInteger()
                AtomicFile.beforeStartWrite = { file ->
                    if (file.name == "workspace-index.json" && writes.incrementAndGet() == 2) {
                        entered.complete(Unit)
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                }
                coroutineScope {
                    val send = async { subject.sendMessage("kept") }
                    try {
                        withTimeout(10_000) { entered.await() }
                        subject.composerGeneration++
                    } finally {
                        release.countDown()
                    }
                    check(!send.await())
                }
                check(subject.paidCalls == 0 && subject.clearedAttachments == 0)
                check(requireNotNull(store.initialize()).messages.getValue("c1").single().text == "kept")
                check(subject.notice.contains("未调用模型"))
            }
            test("debounced mutations retain one tracked writer and eventually persist the newest snapshot") { store, subject ->
                val entered = CompletableDeferred<Unit>()
                val release = CountDownLatch(1)
                val writes = AtomicInteger()
                AtomicFile.beforeStartWrite = { file ->
                    if (file.name == "workspace-index.json" && writes.incrementAndGet() == 1) {
                        entered.complete(Unit)
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                }
                subject.mutate("old")
                withTimeout(10_000) { entered.await() }
                val writer = requireNotNull(subject.saveJob)
                try {
                    subject.mutate("new")
                    subject.mutate("newest")
                    check(subject.saveJob === writer && writer.isActive)
                } finally {
                    release.countDown()
                }
                withTimeout(10_000) { writer.join() }
                check(requireNotNull(store.initialize()).messages.getValue("c1").map { it.text } ==
                    listOf("old", "new", "newest"))
                check(writes.get() == 2)
            }
            test("failed index and journal keep memory intact and block sends until retry") { store, subject ->
                subject.conversationMessages.getValue("c1") += ChatMessage(1L, "assistant", "keep answer")
                AtomicFile.failStartFor = "workspace-index.json"
                AtomicFile.failFinishFor = "workspace-recovery.json"
                check(!subject.sendMessage("next"))
                check(subject.workspaceSaveError?.contains("内容仍在内存中") == true)
                check(subject.paidCalls == 0 && subject.conversationMessages.getValue("c1").single().text == "keep answer")
                check(!subject.sendMessage("blocked"))
                AtomicFile.failStartFor = null
                AtomicFile.failFinishFor = null
                check(subject.retryWorkspaceSave())
                check(requireNotNull(store.initialize()).messages.getValue("c1").single().text == "keep answer")
            }
            test("retry saves a mutation that arrives while its IO commit is pending") { store, subject ->
                val entered = CompletableDeferred<Unit>()
                val release = CountDownLatch(1)
                val writes = AtomicInteger()
                AtomicFile.beforeStartWrite = { file ->
                    if (file.name == "workspace-index.json" && writes.incrementAndGet() == 1) {
                        entered.complete(Unit)
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                }
                coroutineScope {
                    val retry = async { subject.retryWorkspaceSave() }
                    try {
                        withTimeout(10_000) { entered.await() }
                        subject.mutate("during retry")
                    } finally {
                        release.countDown()
                    }
                    check(retry.await())
                }
                withTimeout(10_000) { subject.saveJob?.join() }
                check(requireNotNull(store.initialize()).messages.getValue("c1").single().text == "during retry")
            }
            test("caller cancellation during atomic candidate save suppresses the paid request") { store, subject ->
                val entered = CompletableDeferred<Unit>()
                val release = CountDownLatch(1)
                val writes = AtomicInteger()
                AtomicFile.beforeStartWrite = { file ->
                    if (file.name == "workspace-index.json" && writes.incrementAndGet() == 2) {
                        entered.complete(Unit)
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                }
                coroutineScope {
                    val send = launch { subject.sendMessage("durable") }
                    try {
                        withTimeout(10_000) { entered.await() }
                        send.cancel()
                    } finally {
                        release.countDown()
                    }
                    send.join()
                }
                check(subject.paidCalls == 0 && subject.clearedAttachments == 0 && !subject.sendPending)
                check(requireNotNull(store.initialize()).messages.getValue("c1").single().text == "durable")
            }
        }
        println("Production send/persistence checks: $passed PASS")
        println("Boundary: JVM Main and production IO/Mutex/precommit; history/image/schema/validation fixtures, provider counter, no tools/API.")
    } finally {
        fixtureMain.close()
    }
}
