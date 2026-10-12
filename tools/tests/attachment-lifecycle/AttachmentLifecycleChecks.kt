package com.dafeiyu.mobile.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

private class Fixture(val root: File, scope: CoroutineScope) {
    val resolver = ContentResolver()
    val context = Context(File(root, "private").also { it.mkdirs() }, resolver)
    val store = ConversationAttachmentStore(context)
    val indexRoot = File(root, "workspace").also { it.mkdirs() }
    val repository = DemoRepository(context, scope, indexRoot)
    fun source(bytes: ByteArray, mime: String = "application/pdf", beforeOpen: () -> Unit = {}): Uri {
        val uri = Uri.parse("content://fixtures/${UUID.randomUUID()}")
        resolver.sources[uri.toString()] = ContentResolver.Source("sample", mime, bytes.size.toLong()) {
            beforeOpen()
            bytes.inputStream()
        }
        return uri
    }
    fun saved(bytes: ByteArray, mime: String = "text/plain"): ConversationAttachment =
        store.importAttachment(source(bytes, mime))
    fun index(ids: List<String> = emptyList(), backup: Boolean = false) {
        val value = JSONObject().put("version", 2).put("messages", JSONObject().put(
            "history", JSONArray().put(JSONObject().put("attachmentIds", JSONArray(ids))),
        ))
        File(indexRoot, if (backup) "workspace-index.json.bak" else "workspace-index.json")
            .writeText(value.toString())
    }
    fun recovery(ids: List<String>, backup: Boolean = false) {
        val snapshot = JSONObject().put("version", 2).put("messages", JSONObject().put(
            "history", JSONArray().put(JSONObject().put("attachmentIds", JSONArray(ids))),
        ))
        val journal = JSONObject().put("schema", 1).put("baseIndexSha256", "")
            .put("snapshot", snapshot)
        File(indexRoot, if (backup) "workspace-recovery.json.bak" else "workspace-recovery.json")
            .writeText(journal.toString())
    }
    suspend fun settled() {
        withTimeout(10_000) {
            while (repository.attachmentImportCount != 0 || repository.cleanupBusy()) delay(5)
        }
    }
    suspend fun select(file: ConversationAttachment) {
        repository.addAttachment(file.id, file.name, file.uriString)
        settled()
    }
}

fun main(args: Array<String>) {
    val root = File(args.single()).also { it.mkdirs() }
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    var passed = 0
    try {
        runBlocking(dispatcher) {
            suspend fun case(name: String, body: suspend Fixture.() -> Unit) {
                val scope = CoroutineScope(SupervisorJob() + dispatcher)
                try {
                    Fixture(File(root, name).also { it.mkdirs() }, scope).body()
                    passed++
                    println("PASS $name")
                } finally {
                    scope.cancel()
                }
            }
            case("metadata-and-two-mode-text") {
                val file = saved(ByteArray(1024) { 'a'.code.toByte() })
                repository.catalog()
                check(!repository.metadataHasText())
                select(file)
                repository.workspaceMode = WorkspaceMode.Dsh
                select(file)
                repository.clearAttachments()
                repository.workspaceMode = WorkspaceMode.Lite
                check(repository.attachmentPreviews.single().textContent?.length == 1024)
                check(!repository.metadataHasText())
                repository.removeAttachment(file.id)
                check(store.load(file.id) != null)
                select(file)
                check(repository.attachmentPreviews.single().textContent != null)
            }
            case("oversized-text-is-selectable-local-only") {
                val bytes = ByteArray(256 * 1024 + 1) { 'a'.code.toByte() }
                repository.importAttachment(source(bytes, "text/plain"))
                settled()
                val preview = repository.attachmentPreviews.single()
                check(preview.textSupported && preview.textContent == null)
                check(repository.notices.last().contains("256 KiB"))
                repository.removeAttachment(preview.id)
                repository.addAttachment(preview.id, preview.label, preview.uri)
                settled()
                check(repository.attachmentPreviews.single().textContent == null)
                check(store.load(preview.id) != null)
            }
            case("sixth-oversized-text-import-does-not-double-reserve") {
                repeat(5) { repository.importAttachment(source(byteArrayOf(1))) }
                settled()
                repository.importAttachment(source(ByteArray(256 * 1024 + 1) { 97 }, "text/plain"))
                settled()
                check(repository.attachmentPreviews.size == 6)
                check(repository.attachmentPreviews.count { it.textSupported && it.textContent == null } == 1)
                check(store.list().size == 6)
            }
            case("aggregate-text-cache-limit") {
                repeat(3) { repository.importAttachment(source(ByteArray(256 * 1024) { 97 }, "text/plain")) }
                settled()
                check(repository.attachmentPreviews.size == 2)
                check(repository.selectedTextBytes() == 512L * 1024)
                check(store.list().size == 2)
                check(!repository.metadataHasText())
            }
            case("selection-25mib-and-store-independent") {
                repository.importAttachment(source(ByteArray(10 * 1024 * 1024)))
                repository.importAttachment(source(ByteArray(10 * 1024 * 1024)))
                repository.importAttachment(source(ByteArray(6 * 1024 * 1024)))
                settled()
                check(repository.attachmentPreviews.size == 2)
                check(repository.selectedBytes() == 20L * 1024 * 1024)
                repository.clearAttachments()
                repository.importAttachment(source(ByteArray(10 * 1024 * 1024)))
                settled()
                check(store.list().sumOf { it.sizeBytes } == 30L * 1024 * 1024)
                check(repository.selectedBytes() == 10L * 1024 * 1024)
            }
            case("pending-slots-block-cached-selections") {
                val files = (1..6).map { saved(byteArrayOf(1), "application/pdf") }
                repository.catalog()
                val opened = CountDownLatch(1)
                val release = CountDownLatch(1)
                try {
                    repository.importAttachment(source(byteArrayOf(2)) {
                        opened.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    })
                    withTimeout(5_000) { while (opened.count != 0L) delay(5) }
                    files.forEach { repository.addAttachment(it.id, it.name, it.uriString) }
                    check(repository.attachmentImportCount == 6)
                } finally {
                    release.countDown()
                }
                settled()
                check(repository.attachmentPreviews.size == 6)
            }
            case("deselect-and-reselect-pending-token") {
                val file = saved(byteArrayOf(97))
                repository.catalog()
                val opened = CountDownLatch(1)
                val release = CountDownLatch(1)
                try {
                    repository.importAttachment(source(byteArrayOf(2)) {
                        opened.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    })
                    withTimeout(5_000) { while (opened.count != 0L) delay(5) }
                    repository.addAttachment(file.id, file.name)
                    repository.addAttachment(file.id, file.name)
                    check(repository.attachmentImportCount == 2)
                    repository.removeAttachment(file.id)
                    repository.addAttachment(file.id, file.name)
                    check(repository.attachmentImportCount == 3)
                } finally {
                    release.countDown()
                }
                settled()
                check(repository.attachmentPreviews.count { it.id == file.id } == 1)
            }
            case("cleanup-protects-both-drafts-live-and-durable") {
                val files = (1..5).map { saved(byteArrayOf(97)) }
                repository.catalog()
                select(files[0])
                repository.workspaceMode = WorkspaceMode.Dsh
                select(files[1])
                repository.history(listOf(files[2].id))
                index(listOf(files[3].id))
                repository.cleanupUnusedAttachments()
                repository.addAttachment(files[4].id, files[4].name)
                settled()
                check(files.take(4).all { store.load(it.id) != null })
                check(store.load(files[4].id) == null)
                check(repository.attachmentPreviews.none { it.id == files[4].id })
                repository.addAttachment(files[4].id, files[4].name)
                check(repository.attachmentPreviews.none { it.id == files[4].id })
            }
            case("cleanup-fails-closed-malformed-index") {
                val file = saved(byteArrayOf(97))
                repository.catalog()
                File(indexRoot, "workspace-index.json").writeText("{broken")
                repository.cleanupUnusedAttachments()
                settled()
                check(store.load(file.id) != null)
                index()
                repository.cleanupUnusedAttachments()
                settled()
                check(store.load(file.id) == null)
            }
            case("cleanup-fails-closed-missing-index") {
                val file = saved(byteArrayOf(97))
                repository.catalog()
                repository.cleanupUnusedAttachments()
                settled()
                check(store.load(file.id) != null)
            }
            case("cleanup-protects-atomic-backup") {
                val file = saved(byteArrayOf(97))
                repository.catalog()
                index()
                index(listOf(file.id), backup = true)
                val backup = File(indexRoot, "workspace-index.json.bak")
                val before = backup.readBytes()
                repository.cleanupUnusedAttachments()
                settled()
                check(store.load(file.id) != null)
                check(backup.readBytes().contentEquals(before))
            }
            case("cleanup-fails-closed-invalid-reference-shape") {
                val file = saved(byteArrayOf(97))
                repository.catalog()
                File(indexRoot, "workspace-index.json").writeText(
                    """{"version":2,"messages":{"history":[{"attachmentIds":"bad"}]}}""",
                )
                repository.cleanupUnusedAttachments()
                settled()
                check(store.load(file.id) != null)
            }
            case("cleanup-fails-closed-invalid-utf8-index") {
                val file = saved(byteArrayOf(97))
                repository.catalog()
                File(indexRoot, "workspace-index.json").writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
                repository.cleanupUnusedAttachments()
                settled()
                check(store.load(file.id) != null)
            }
            case("cleanup-protects-index-recovery-and-both-backups") {
                val files = (1..5).map { saved(byteArrayOf(97)) }
                repository.catalog()
                index(listOf(files[0].id))
                index(listOf(files[1].id), backup = true)
                recovery(listOf(files[2].id))
                recovery(listOf(files[3].id), backup = true)
                val durable = indexRoot.listFiles()!!.associateWith { it.readBytes() }
                repository.cleanupUnusedAttachments()
                settled()
                check(files.take(4).all { store.load(it.id) != null })
                check(store.load(files[4].id) == null)
                check(durable.all { (file, bytes) -> file.readBytes().contentEquals(bytes) })
            }
            case("cleanup-protects-recovery-without-base-index") {
                val files = (1..2).map { saved(byteArrayOf(97)) }
                repository.catalog()
                recovery(listOf(files[0].id))
                repository.cleanupUnusedAttachments()
                settled()
                check(store.load(files[0].id) != null)
                check(store.load(files[1].id) == null)
            }
            case("cleanup-fails-closed-malformed-recovery") {
                val file = saved(byteArrayOf(97))
                repository.catalog()
                index()
                File(indexRoot, "workspace-recovery.json").writeText(
                    """{"schema":1,"snapshot":{"version":2,"messages":{"history":[{"attachmentIds":null}]}}}""",
                )
                repository.cleanupUnusedAttachments()
                settled()
                check(store.load(file.id) != null)
            }
            case("cleanup-fails-closed-unsupported-recovery-schema") {
                val file = saved(byteArrayOf(97))
                repository.catalog()
                index()
                File(indexRoot, "workspace-recovery.json").writeText("""{"schema":2,"snapshot":{}}""")
                repository.cleanupUnusedAttachments()
                settled()
                check(store.load(file.id) != null)
            }
            case("stale-composer-import-rolls-back") {
                val opened = CountDownLatch(1)
                val release = CountDownLatch(1)
                try {
                    repository.importAttachment(source(byteArrayOf(97), "text/plain") {
                        opened.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    })
                    withTimeout(5_000) { while (opened.count != 0L) delay(5) }
                    repository.selectedConversationId = "new-conversation"
                    repository.composerGeneration++
                } finally {
                    release.countDown()
                }
                settled()
                check(repository.attachmentPreviews.isEmpty())
                check(store.list().isEmpty())
            }
            case("clear-during-import-does-not-refill-draft") {
                val opened = CountDownLatch(1)
                val release = CountDownLatch(1)
                try {
                    repository.importAttachment(source(byteArrayOf(97), "text/plain") {
                        opened.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    })
                    withTimeout(5_000) { while (opened.count != 0L) delay(5) }
                    repository.clearAttachments()
                } finally {
                    release.countDown()
                }
                settled()
                check(repository.attachmentPreviews.isEmpty())
                check(store.list().isEmpty())
            }
            case("stale-composer-reselection-retains-original-copy") {
                val file = saved(byteArrayOf(97))
                repository.catalog()
                repository.addAttachment(file.id, file.name)
                repository.selectedConversationId = "new-conversation"
                repository.composerGeneration++
                settled()
                check(repository.attachmentPreviews.isEmpty())
                check(store.load(file.id) != null)
            }
            case("import-rejected-before-initialization") {
                repository.workspaceInitialized = false
                repository.importAttachment(source(byteArrayOf(97), "text/plain"))
                check(repository.attachmentImportCount == 0)
                check(store.list().isEmpty())
            }
        }
    } finally {
        dispatcher.close()
    }
    println("PASS $passed attachment lifecycle scenarios")
}
