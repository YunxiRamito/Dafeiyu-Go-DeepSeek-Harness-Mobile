package com.dafeiyu.mobile.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*

private var passed = 0
private fun msg(text: String = "payload") = ChatMessage(1L, "user", text)
private fun snapshot(workspace: String = "other") = WorkspaceSnapshot(
    listOf(Workspace(workspace, "Workspace")),
    listOf(Conversation("c1", "Title", workspace)),
    mapOf("c1" to listOf(msg())),
    "missing", "missing",
)

fun main(args: Array<String>) {
    val root = File(args.single()).apply { mkdirs() }
    try {
        runBlocking(fixtureMain) {
            suspend fun test(name: String, body: suspend (WorkspaceStore, WorkspaceDeleteSubject) -> Unit) {
                val ctx = Context(File(root, "case-${passed + 1}").apply { mkdirs() })
                val store = WorkspaceStore(ctx)
                val subject = WorkspaceDeleteSubject(store)
                subject.updateComposerDraft("")
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
            test("script files prevent empty workspace deletion and survive the request") { store, subject ->
                store.initialize()
                val directory = File(store.root.parentFile, "linux-workspaces/default").apply { mkdirs() }
                val scriptOutput = File(directory, "result.txt").apply { writeText("script-result") }
                check(!store.canDeleteInternalWorkspaceFolder(Workspace("default", "Default")))
                check(subject.deleteEmptyWorkspace("default"))
                subject.settle()
                check(subject.workspaces.any { it.id == "default" } && scriptOutput.readText() == "script-result")
            }
            test("SAF reference deletion preserves a nonempty Linux working copy") { store, _ ->
                val directory = File(store.root.parentFile, "linux-workspaces/external").apply { mkdirs() }
                File(directory, "result.txt").writeText("keep")
                val workspace = Workspace("external", "External", "content://fixture/tree")
                check(!store.canDeleteInternalWorkspaceFolder(workspace))
                check(!store.deleteInternalWorkspaceFolderIfEmpty(workspace))
            }
            test("an empty Linux working copy still permits empty workspace deletion") { store, _ ->
                store.initialize()
                File(store.root.parentFile, "linux-workspaces/default").mkdirs()
                check(store.canDeleteInternalWorkspaceFolder(Workspace("default", "Default")))
            }
            test("query reads memory without touching an unavailable filesystem or notice") { store, subject ->
                store.root.writeText("not a directory")
                subject.notice = "unchanged"
                check(subject.canDeleteWorkspace("default"))
                check(subject.notice == "unchanged")
                check(!subject.canDeleteWorkspace("unknown"))
                check(subject.deleteEmptyWorkspace("default"))
                subject.settle()
                check(subject.workspaces.size == 2)
                check(subject.notice.contains("\u4fdd\u7559"))
            }
            test("last empty workspace deletes and persists a fresh identity and selection") { store, subject ->
                subject.workspaces.removeAll { it.id == "other" }
                store.initialize()
                check(subject.canDeleteWorkspace("default"))
                check(subject.deleteEmptyWorkspace("default"))
                subject.settle()
                val fresh = subject.workspaces.single()
                check(fresh.id != "default" && fresh.uri == null)
                check(File(store.root, fresh.id).isDirectory)
                check(subject.selectedWorkspaceId == fresh.id)
                check(subject.conversations.single().id == subject.selectedConversationId)
                val restored = requireNotNull(store.initialize())
                check(restored.workspaces.single() == fresh)
                check(restored.selectedWorkspaceId == fresh.id)
                check(restored.selectedConversationId == subject.selectedConversationId)
                check(subject.notice.contains("\u81ea\u52a8\u65b0\u5efa"))
            }
            test("each payload fact blocks empty-workspace deletion independently") { _, subject ->
                val c = subject.conversations.single()
                for (facts in listOf(
                    ConversationDeleteFacts(hasMessages = true),
                    ConversationDeleteFacts(hasDraft = true),
                    ConversationDeleteFacts(hasAttachments = true),
                    ConversationDeleteFacts(hasTasks = true),
                    ConversationDeleteFacts(hasOutputs = true),
                )) {
                    check(!canDeleteEmptyWorkspace("default", 2, listOf(c), mapOf(c.id to facts)))
                }
                check(canDeleteEmptyWorkspace("default", 2, listOf(c), emptyMap()))
                check(!canDeleteEmptyWorkspace("default", 2, listOf(c.copy(title = "named")), emptyMap()))
                check(!canDeleteEmptyWorkspace("default", 2, listOf(c.copy(archivedAtMillis = 1L)), emptyMap()))
                check(canDeleteEmptyWorkspace("default", 1, listOf(c), emptyMap()))
                check(!canDeleteEmptyWorkspace("default", 0, emptyList(), emptyMap()))
                subject.conversationMessages.getValue(c.id).add(msg(""))
                check(!subject.canDeleteWorkspace("default"))
            }
            test("unknown draft, whitespace, both modes and in-flight attachments protect selection") { _, subject ->
                subject.composerDraftText = null
                check(!subject.canDeleteWorkspace("default"))
                subject.updateComposerDraft(" ")
                check(!subject.canDeleteWorkspace("default"))
                subject.updateComposerDraft("")
                subject.dshAttachments.add("hidden-mode")
                check(!subject.canDeleteWorkspace("default"))
                subject.dshAttachments.clear()
                subject.liteAttachments.add("attachment")
                check(!subject.canDeleteWorkspace("default"))
                subject.liteAttachments.clear()
                subject.attachmentImportCount = 1
                check(!subject.canDeleteWorkspace("default"))
                subject.attachmentImportCount = 0
                subject.pendingAttachmentSelections["a"] = Any()
                check(!subject.canDeleteWorkspace("default"))
            }
            test("task/output load issues fail closed and linked or orphaned payloads protect workspace") { _, subject ->
                subject.deletionProtectionLoadError = "load failed"
                check(!subject.canDeleteWorkspace("other"))
                subject.deletionProtectionLoadError = null
                subject.backgroundTasks = null
                check(!subject.canDeleteWorkspace("other"))
                subject.backgroundTasks = FixtureTaskEngine()
                subject.backgroundTasks!!.tasks.value = listOf(FixtureTask("initial"))
                check(!subject.canDeleteWorkspace("default"))
                check(subject.canDeleteWorkspace("other"))
                subject.backgroundTasks!!.tasks.value = listOf(FixtureTask(null))
                check(!subject.canDeleteWorkspace("other"))
                subject.backgroundTasks!!.tasks.value = emptyList()
                subject.officialOutputs["o"] = FixtureOutput("initial")
                check(!subject.canDeleteWorkspace("default"))
                subject.officialOutputs["o"] = FixtureOutput("orphan")
                check(!subject.canDeleteWorkspace("other"))
                subject.officialOutputs.clear()
                subject.backgroundTasks!!.storageIssue.value = "failed"
                check(!subject.canDeleteWorkspace("other"))
            }
            test("selected empty default deletes, creates replacement and never resurrects default") { store, subject ->
                store.initialize()
                val defaultFolder = File(store.root, "\u9ed8\u8ba4\u5de5\u4f5c\u533a")
                check(defaultFolder.isDirectory)
                check(subject.deleteEmptyWorkspace("default"))
                check(!subject.deleteEmptyWorkspace("other"))
                subject.settle()
                check(subject.workspaces.single().id == "other")
                check(subject.selectedWorkspaceId == "other")
                check(subject.conversations.single().id == subject.selectedConversationId)
                check(!defaultFolder.exists())
                val restored = requireNotNull(store.initialize())
                check(restored.workspaces.single().id == "other")
                check(restored.selectedWorkspaceId == "other")
                check(!defaultFolder.exists())
            }
            test("unselected deletion preserves selected conversation, attachments and response") { store, subject ->
                store.createInternalWorkspace("other")
                subject.liteAttachments.add("keep")
                subject.activeResponseConversationId = "initial"
                subject.isResponding = true
                check(subject.deleteEmptyWorkspace("other"))
                subject.settle()
                check(subject.selectedConversationId == "initial")
                check(subject.liteAttachments == listOf("keep"))
                check(subject.cancelledResponses == 0 && subject.clearedAttachments == 0)
                check(subject.workspaces.single().id == "default")
            }
            test("nonempty internal folder rejects before index mutation") { store, subject ->
                store.createInternalWorkspace("other")
                File(store.root, "other/keep.txt").writeText("keep")
                check(subject.canDeleteWorkspace("other"))
                check(subject.deleteEmptyWorkspace("other"))
                subject.settle()
                check(subject.workspaces.size == 2)
                check(File(store.root, "other/keep.txt").readText() == "keep")
            }
            test("IO recheck protects new messages and sends while the request is waiting") { _, subject ->
                subject.persistenceMutex.lock()
                check(subject.deleteEmptyWorkspace("other"))
                subject.conversations.add(Conversation("new", "\u65b0\u5bf9\u8bdd", "other"))
                subject.conversationMessages["new"] = mutableListOf(msg())
                subject.persistenceMutex.unlock()
                subject.settle()
                check(subject.workspaces.size == 2)
                check(subject.conversationMessages.getValue("new").single().text == "payload")
                subject.sendPending = true
                check(!subject.deleteConversation("initial"))
            }
            test("IO recheck protects late drafts, attachments, tasks, outputs, sends and workspace identity") { _, subject ->
                val protections: List<() -> Unit> = listOf(
                    { subject.updateComposerDraft("late draft") },
                    { subject.liteAttachments.add("late attachment") },
                    { subject.attachmentImportCount = 1 },
                    { subject.backgroundTasks!!.tasks.value = listOf(FixtureTask("initial")) },
                    { subject.officialOutputs["late"] = FixtureOutput("initial") },
                    { subject.sendPending = true },
                    { subject.workspaces[0] = Workspace("default", "Changed identity", "content://changed") },
                )
                for (protect in protections) {
                    subject.persistenceMutex.lock()
                    check(subject.deleteEmptyWorkspace("default"))
                    protect()
                    subject.persistenceMutex.unlock()
                    subject.settle()
                    check(subject.workspaces.size == 2 && subject.conversations.any { it.id == "initial" })
                    subject.updateComposerDraft("")
                    subject.liteAttachments.clear()
                    subject.attachmentImportCount = 0
                    subject.backgroundTasks!!.tasks.value = emptyList()
                    subject.officialOutputs.clear()
                    subject.sendPending = false
                    subject.workspaces[0] = Workspace("default", "Default")
                }
            }
            test("last-workspace IO recheck protects late draft before reserving a replacement") { store, subject ->
                subject.workspaces.removeAll { it.id == "other" }
                // Blocking the store lock keeps the initial folder probe pending.
                subject.persistenceMutex.lock()
                check(subject.deleteEmptyWorkspace("default"))
                subject.updateComposerDraft("arrived before removal")
                subject.persistenceMutex.unlock()
                subject.settle()
                check(subject.workspaces.single().id == "default")
                check(subject.composerDraftText == "arrived before removal")
                check(!store.root.exists())
            }
            test("SAF deletion forgets reference without touching same-name internal folder") { store, subject ->
                subject.workspaces[1] = Workspace("other", "External", "content://external/tree")
                store.createInternalWorkspace("other")
                File(store.root, "other/keep.txt").writeText("untouched")
                check(subject.deleteEmptyWorkspace("other"))
                subject.settle()
                check(subject.workspaces.none { it.id == "other" })
                check(File(store.root, "other/keep.txt").readText() == "untouched")
            }
            test("selected workspace switches to first remaining workspace and its first active conversation") { _, subject ->
                subject.workspaces.add(0, Workspace("first", "First"))
                subject.conversations.add(0, Conversation("other-c", "Title", "other"))
                subject.conversations.add(Conversation("first-archive", "Title", "first", 1L))
                subject.conversations.add(Conversation("first-c", "Title", "first"))
                check(subject.deleteEmptyWorkspace("default"))
                subject.settle()
                check(subject.selectedWorkspaceId == "first" && subject.selectedConversationId == "first-c")
            }
            test("conversation deletion removes history and cancels only its own response") { store, subject ->
                subject.conversationMessages.getValue("initial").add(msg())
                subject.conversations.add(0, Conversation("orphan", "Title", "missing"))
                subject.conversations.add(Conversation("archived", "Title", "other", 1L))
                subject.conversations.add(Conversation("next", "Title", "other"))
                subject.officialOutputs["file"] = FixtureOutput("initial")
                subject.backgroundTasks!!.tasks.value = listOf(FixtureTask("initial"))
                subject.activeResponseConversationId = "initial"
                subject.isResponding = true
                check(subject.deleteConversation("initial"))
                subject.settle()
                check(subject.cancelledResponses == 1)
                check(subject.selectedWorkspaceId == "other" && subject.selectedConversationId == "next")
                check("initial" !in subject.conversationMessages)
                check(subject.officialOutputs.size == 1 && subject.backgroundTasks!!.tasks.value.size == 1)
                val loaded = requireNotNull(store.initialize())
                check(loaded.conversations.none { it.id == "initial" } && "initial" !in loaded.messages)
            }
            test("unselected conversation deletion leaves other running response and composer intact") { _, subject ->
                subject.conversations.add(Conversation("delete", "Title", "other"))
                subject.conversationMessages["delete"] = mutableListOf(msg())
                subject.activeResponseConversationId = "initial"
                subject.liteAttachments.add("keep")
                check(subject.deleteConversation("delete"))
                subject.settle()
                check(subject.activeResponseConversationId == "initial" && subject.cancelledResponses == 0)
                check(subject.selectedConversationId == "initial" && subject.liteAttachments.single() == "keep")
            }
            test("deleting only conversation creates a valid fresh selection after freeing a slot") { _, subject ->
                check(subject.deleteConversation("initial"))
                subject.settle()
                check(subject.conversations.single().workspaceId == "default")
                check(subject.selectedConversationId == subject.conversations.single().id)
                check(!subject.deleteConversation("initial"))
            }
            test("archived deletion keeps its contract while deleting full messages") { _, subject ->
                check(!subject.deleteArchivedConversation("initial"))
                subject.conversations.add(Conversation("archive", "Title", "other", System.currentTimeMillis()))
                subject.conversationMessages["archive"] = mutableListOf(msg())
                check(subject.deleteArchivedConversation("archive"))
                subject.settle()
                check("archive" !in subject.conversationMessages)
            }
            test("failed index plus recovery write reports error and retains internal folder") { store, subject ->
                store.createInternalWorkspace("other")
                store.save(WorkspaceSnapshot(subject.workspaces, subject.conversations,
                    subject.conversationMessages, "default", "initial"))
                val originalIndex = File(store.root, "workspace-index.json").readBytes()
                AtomicFile.failStartFor = "workspace-index.json"
                AtomicFile.failFinishFor = "workspace-recovery.json"
                check(subject.deleteEmptyWorkspace("other"))
                subject.settle()
                check(subject.workspaceSaveError != null && subject.notice.contains("\u672a\u4fdd\u5b58"))
                check(File(store.root, "other").isDirectory)
                check(File(store.root, "workspace-index.json").readBytes().contentEquals(originalIndex))
                check(requireNotNull(store.initialize()).workspaces.any { it.id == "other" })
            }
            test("index write failure persists deletion in recovery without claiming final success") { store, subject ->
                store.createInternalWorkspace("other")
                AtomicFile.failStartFor = "workspace-index.json"
                check(subject.deleteEmptyWorkspace("other"))
                subject.settle()
                check(subject.workspaceSaveError != null && store.hasRecovery())
                check(File(store.root, "other").isDirectory)
                check(requireNotNull(store.initialize()).workspaces.none { it.id == "other" })
                AtomicFile.failStartFor = null
                check(subject.retryWorkspaceSave())
                check(!store.hasRecovery())
            }
            test("deleted workspace reload chooses first valid workspace without default fallback") { store, _ ->
                store.save(snapshot())
                val restored = requireNotNull(store.initialize())
                check(restored.workspaces.single().id == "other")
                check(restored.selectedWorkspaceId == "other" && restored.selectedConversationId == "c1")
                check(!File(store.root, "\u9ed8\u8ba4\u5de5\u4f5c\u533a").exists())
            }
            test("bounded legacy workspace load preserves 200 valid entries without adding default") { store, _ ->
                store.save(snapshot().copy(
                    workspaces = (0..200).map { Workspace("w$it", "Workspace") },
                    conversations = emptyList(), messages = emptyMap(), selectedWorkspaceId = "w200",
                ))
                val loaded = requireNotNull(store.initialize())
                check(loaded.workspaces.size == 200 && loaded.workspaces.none { it.id == "default" })
                check(loaded.selectedWorkspaceId == "w0")
            }
            test("unsafe paths, occupied paths and links cannot be removed recursively") { store, _ ->
                store.root.mkdirs()
                check(runCatching { store.deleteInternalWorkspaceFolderIfEmpty(Workspace("../escape", "Unsafe")) }.isFailure)
                val external = File(root, "external-${passed}").apply { mkdirs() }
                File(external, "keep").writeText("keep")
                val link = File(store.root, "link")
                val linked = runCatching { Files.createSymbolicLink(link.toPath(), external.toPath()) }.isSuccess
                if (linked) {
                    check(runCatching { store.deleteInternalWorkspaceFolderIfEmpty(Workspace("link", "Link")) }.isFailure)
                    check(File(external, "keep").readText() == "keep")
                } else println("NOTE symbolic-link runtime probe unavailable; containment and nonrecursive tests ran")
                File(store.root, "file").writeText("keep")
                check(!store.deleteInternalWorkspaceFolderIfEmpty(Workspace("file", "File")))
                store.createInternalWorkspace("occupied")
                File(store.root, "occupied/child").mkdir()
                check(!store.deleteInternalWorkspaceFolderIfEmpty(Workspace("occupied", "Occupied")))
                check(File(store.root, "occupied/child").isDirectory)
            }
            test("durable reference or corrupt index prevents folder cleanup") { store, _ ->
                store.createInternalWorkspace("other")
                store.save(snapshot())
                check(!store.deleteUnreferencedInternalWorkspaceFolder(Workspace("other", "Other")))
                File(store.root, "workspace-index.json").writeText("{broken")
                check(runCatching { store.deleteUnreferencedInternalWorkspaceFolder(Workspace("other", "Other")) }.isFailure)
                check(File(store.root, "other").isDirectory)
            }
            test("streamed cleanup skips message text and preserves any recovery reference") { store, _ ->
                store.createInternalWorkspace("removed")
                val payload = "\\\"workspaces\\\":[{\\\"id\\\":\\\"removed\\\"}] ".repeat(1024)
                store.save(snapshot().copy(messages = mapOf("c1" to listOf(msg(payload)))))
                check(store.deleteUnreferencedInternalWorkspaceFolder(Workspace("removed", "Removed")))
                check(!File(store.root, "removed").exists())
                store.createInternalWorkspace("removed")
                AtomicFile.failStartFor = "workspace-index.json"
                val result = store.saveWithRecovery(snapshot("removed"))
                check(result.recoveryCommitted)
                check(!store.deleteUnreferencedInternalWorkspaceFolder(Workspace("removed", "Removed")))
                check(File(store.root, "removed").isDirectory)
            }
            test("duplicate, incomplete and trailing metadata cannot authorize folder cleanup") { store, _ ->
                store.createInternalWorkspace("removed")
                val index = File(store.root, "workspace-index.json")
                for (invalid in listOf(
                    """{"version":2,"version":2,"workspaces":[]}""",
                    """{"version":2,"workspaces":[],"workspaces":[]}""",
                    """{"version":2,"workspaces":[{"name":"Missing id"}]}""",
                    """{"version":2,"workspaces":[{"id":"other","id":"other"}]}""",
                    """{"version":2,"workspaces":[]}{}""",
                )) {
                    index.writeText(invalid)
                    check(runCatching {
                        store.deleteUnreferencedInternalWorkspaceFolder(Workspace("removed", "Removed"))
                    }.isFailure)
                    check(File(store.root, "removed").isDirectory)
                }
                index.writeText("""{"version":2,"workspaces":[]}""")
                val recovery = File(store.root, "workspace-recovery.json")
                for (invalid in listOf(
                    """{"schema":1,"schema":1,"snapshot":{"version":2,"workspaces":[]}}""",
                    """{"schema":1,"snapshot":{"version":2,"workspaces":[{"id":"removed"}]},"snapshot":{"version":2,"workspaces":[]}}""",
                )) {
                    recovery.writeText(invalid)
                    check(runCatching {
                        store.deleteUnreferencedInternalWorkspaceFolder(Workspace("removed", "Removed"))
                    }.isFailure)
                    check(File(store.root, "removed").isDirectory)
                }
            }
            println("Workspace delete checks: $passed PASS")
            println("Fixture runs extracted production deletion/persistence functions. UI, task records, cancelResponse and Android AtomicFile are JVM substitutes.")
        }
    } finally {
        fixtureMain.close()
    }
}
