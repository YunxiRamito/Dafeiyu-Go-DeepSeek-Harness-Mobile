package com.dafeiyu.mobile.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

private var passed = 0

fun main(args: Array<String>) {
    val fixtureRoot = File(args.single()).apply { mkdirs() }
    val now = System.currentTimeMillis()
    fun test(name: String, body: (WorkspaceStore) -> Unit) {
        val context = Context(File(fixtureRoot, "case-${passed + 1}").apply { mkdirs() })
        val store = WorkspaceStore(context)
        try {
            body(store)
            passed++
            println("PASS $name")
        } finally {
            AtomicFile.beforeStartWrite = null
            AtomicFile.failStartFor = null
            AtomicFile.failFinishFor = null
            AtomicFile.failDeleteFor = null
        }
    }
    fun snapshot(workspaces: List<Workspace> = listOf(Workspace("first", "First"), Workspace("second", "Second")),
        conversations: List<Conversation> = listOf(Conversation("a", "First conversation", "first"),
            Conversation("b", "Second conversation", "second")),
        selectedWorkspace: String = "first", selectedConversation: String = "a") = WorkspaceSnapshot(
            workspaces, conversations,
            conversations.associate { it.id to listOf(ChatMessage(1L, "user", "retained ${it.id}"),
                ChatMessage(2L, "assistant", "response ${it.id}", modelInput = "original ${it.id}")) },
            selectedWorkspace, selectedConversation,
        )
    test("nonempty workspace removal archives messages and immutable source context") { store ->
        val before = snapshot()
        val removed = prepareWorkspaceRemovalSnapshot(before, "first", store.workspaceDirectoryPaths(before.workspaces[0]), nowMillis = now)
        check(removed.workspaces.map { it.id } == listOf("second"))
        val archived = removed.conversations.first()
        check(archived.archivedAtMillis == now && archived.archiveOrigin?.workspace?.name == "First")
        check(removed.messages == before.messages)
        check(removed.selectedWorkspaceId == "second" && removed.selectedConversationId == "b")
        check(before.conversations.first().archivedAtMillis == null)
    }
    test("existing archive retention is not reset when workspace is removed") { store ->
        val before = snapshot(conversations = listOf(Conversation("a", "Earlier", "first", now - 1000),
            Conversation("b", "Second", "second")))
        val removed = prepareWorkspaceRemovalSnapshot(before, "first", store.workspaceDirectoryPaths(before.workspaces.first()), nowMillis = now)
        check(removed.conversations.first().archivedAtMillis == now - 1000)
    }
    test("unselected removal preserves valid selection") { store ->
        val before = snapshot()
        val removed = prepareWorkspaceRemovalSnapshot(before, "second", store.workspaceDirectoryPaths(before.workspaces[1]), nowMillis = now)
        check(removed.selectedWorkspaceId == "first" && removed.selectedConversationId == "a")
    }
    test("last workspace requires a different prepared replacement identity") { store ->
        val before = snapshot(workspaces = listOf(Workspace("first", "First")),
            conversations = listOf(Conversation("a", "A", "first")))
        check(runCatching { prepareWorkspaceRemovalSnapshot(before, "first", emptyList(), nowMillis = now) }.isFailure)
        check(runCatching { prepareWorkspaceRemovalSnapshot(before, "first", emptyList(), Workspace("first", "Same"), now) }.isFailure)
        val removed = prepareWorkspaceRemovalSnapshot(before, "first", store.workspaceDirectoryPaths(before.workspaces.first()),
            Workspace("replacement", "Default"), now)
        check(removed.workspaces.single().id == "replacement" && removed.selectedConversationId.isEmpty())
        check(removed.conversations.single().archivedAtMillis == now)
    }
    test("action eligibility is independent of contents but rejects unknown storage and busy requests") { _ ->
        val workspaces = snapshot().workspaces
        check(canRemoveWorkspaceRecord("first", workspaces, true, false, true))
        check(!canRemoveWorkspaceRecord("missing", workspaces, true, false, true))
        check(!canRemoveWorkspaceRecord("first", workspaces, false, false, true))
        check(!canRemoveWorkspaceRecord("first", workspaces, true, true, true))
        check(!canRemoveWorkspaceRecord("first", workspaces, true, false, false))
    }
    test("schema3 roundtrip keeps removed workspace archives and original attachment IDs") { store ->
        val before = snapshot().let { it.copy(messages = it.messages + ("a" to listOf(ChatMessage(
            1L, "user", "attachment", attachmentIds = listOf("11111111-1111-1111-1111-111111111111"))))) }
        val removed = prepareWorkspaceRemovalSnapshot(before, "first", store.workspaceDirectoryPaths(before.workspaces.first()), nowMillis = now)
        store.save(removed)
        val loaded = requireNotNull(store.initialize(now))
        check(loaded.workspaces == removed.workspaces && loaded.conversations == removed.conversations)
        check(loaded.messages == removed.messages)
        check(store.persistedAttachmentIds() == setOf("11111111-1111-1111-1111-111111111111"))
    }
    test("schema1 and schema2 indexes still load active and archived conversations") { store ->
        for (version in listOf(1, 2)) {
            val json = JSONObject(String(store.encode(snapshot()), Charsets.UTF_8)).put("version", version)
            if (version == 2) json.getJSONArray("conversations").getJSONObject(0).put("archivedAtMillis", now)
            store.root.mkdirs()
            File(store.root, "workspace-index.json").writeText(json.toString())
            val loaded = requireNotNull(store.initialize(now))
            check(loaded.conversations.size == 2)
            check(loaded.conversations.first().archivedAtMillis == if (version == 2) now else null)
        }
    }
    test("malformed source workspace cannot retain orphaned conversation") { store ->
        val before = snapshot()
        val removed = prepareWorkspaceRemovalSnapshot(before, "first", emptyList(), nowMillis = now)
        val json = JSONObject(String(store.encode(removed), Charsets.UTF_8))
        json.getJSONArray("conversations").getJSONObject(0).getJSONObject("archiveOrigin")
            .getJSONObject("workspace").put("id", "other")
        store.root.mkdirs()
        File(store.root, "workspace-index.json").writeText(json.toString())
        check(requireNotNull(store.initialize(now)).conversations.map { it.id } == listOf("b"))
    }
    test("stored path text cannot authorize external file removal") { store ->
        val before = snapshot()
        val removed = prepareWorkspaceRemovalSnapshot(before, "first", listOf("G:/not-owned"), nowMillis = now)
        store.save(removed)
        check(requireNotNull(store.initialize(now)).conversations.first().archiveOrigin?.directoryPaths?.isEmpty() == true)
    }
    test("active orphan cannot be saved even when it has a source snapshot") { store ->
        val before = snapshot()
        val removed = prepareWorkspaceRemovalSnapshot(before, "first", emptyList(), nowMillis = now)
        val invalid = removed.copy(conversations = removed.conversations.map { if (it.id == "a") it.copy(archivedAtMillis = null) else it })
        check(runCatching { store.save(invalid) }.isFailure)
    }
    test("restore recreates removed workspace at original order without replaying messages") { store ->
        val before = snapshot()
        val removed = prepareWorkspaceRemovalSnapshot(before, "first", store.workspaceDirectoryPaths(before.workspaces.first()), nowMillis = now)
        val restored = prepareConversationRestoreSnapshot(removed, "a")
        check(restored.workspaces == before.workspaces)
        check(restored.conversations.first().archivedAtMillis == null && restored.conversations.first().archiveOrigin == null)
        check(restored.messages == before.messages)
        store.save(restored)
        check(requireNotNull(store.initialize(now)).messages == before.messages)
    }
    test("restore does not duplicate live workspace or alter another active selection") { _ ->
        val before = snapshot(conversations = listOf(Conversation("a", "Archived", "first", now),
            Conversation("b", "Active", "second")), selectedWorkspace = "second", selectedConversation = "b")
        val restored = prepareConversationRestoreSnapshot(before, "a")
        check(restored.workspaces == before.workspaces && restored.selectedConversationId == "b")
    }
    test("grouping follows workspace and original conversation order") { store ->
        val before = snapshot(conversations = listOf(Conversation("a", "A", "first"),
            Conversation("b", "B", "second", now), Conversation("c", "C", "first")))
        val removed = prepareWorkspaceRemovalSnapshot(before, "first", store.workspaceDirectoryPaths(before.workspaces.first()), nowMillis = now)
        val groups = conversationArchiveGroups(removed.workspaces, removed.conversations)
        check(groups.map { it.workspaceId } == listOf("first", "second"))
        check(groups.first().conversations.map { it.id } == listOf("a", "c"))
        check(groups.first().workspaceRemoved && !groups[1].workspaceRemoved)
    }
    test("successive removals and out-of-order restores retain original workspace order") { store ->
        val before = snapshot(workspaces = listOf(Workspace("first", "First"), Workspace("second", "Second"),
            Workspace("third", "Third")), conversations = listOf(Conversation("a", "A", "first"),
                Conversation("b", "B", "second"), Conversation("c", "C", "third", now)))
        val firstRemoved = prepareWorkspaceRemovalSnapshot(before, "first",
            store.workspaceDirectoryPaths(before.workspaces.first()), nowMillis = now)
        val bothRemoved = prepareWorkspaceRemovalSnapshot(firstRemoved, "second",
            store.workspaceDirectoryPaths(before.workspaces[1]), nowMillis = now)
        check(bothRemoved.conversations[1].archiveOrigin?.workspaceOrder == 1)
        check(conversationArchiveGroups(bothRemoved.workspaces, bothRemoved.conversations)
            .map { it.workspaceId } == listOf("first", "second", "third"))
        val secondRestored = prepareConversationRestoreSnapshot(bothRemoved, "b")
        check(secondRestored.workspaces.map { it.id } == listOf("second", "third"))
        val allRestored = prepareConversationRestoreSnapshot(secondRestored, "a")
        check(allRestored.workspaces == before.workspaces)
    }
    test("remaining days rounds up and expiration never reports an extra day") { _ ->
        val archived = Conversation("a", "A", "first", now)
        check(archiveRemainingDays(archived, now) == 30L)
        check(archiveRemainingDays(archived, now + ARCHIVE_RETENTION_MILLIS - 1) == 1L)
        check(archiveRemainingDays(archived, now + ARCHIVE_RETENTION_MILLIS) == 0L)
        check(isArchiveExpired(archived, now + ARCHIVE_RETENTION_MILLIS))
    }
    test("expired removed archives are purged with their messages") { store ->
        val before = snapshot()
        val removed = prepareWorkspaceRemovalSnapshot(before, "first", emptyList(), nowMillis = now)
        store.save(removed)
        check(store.purgeExpiredArchives(now + ARCHIVE_RETENTION_MILLIS) == 1)
        val loaded = requireNotNull(store.initialize(now + ARCHIVE_RETENTION_MILLIS))
        check(loaded.conversations.map { it.id } == listOf("b") && "a" !in loaded.messages)
    }
    test("file deletion cannot precede durable record removal") { store ->
        val before = snapshot()
        store.save(before)
        val target = File(store.root, "first").apply { mkdirs() }
        File(target, "keep.txt").writeText("keep")
        check(runCatching { store.deleteRemovedWorkspaceFiles(before.workspaces.first()) }.isFailure)
        check(File(target, "keep.txt").readText() == "keep")
    }
    test("retaining files never deletes owned contents during record removal") { store ->
        val before = snapshot()
        val target = File(store.root, "first").apply { mkdirs() }
        File(target, "keep.txt").writeText("keep")
        store.save(prepareWorkspaceRemovalSnapshot(before, "first", store.workspaceDirectoryPaths(before.workspaces.first()), nowMillis = now))
        check(File(target, "keep.txt").readText() == "keep")
    }
    test("explicit deletion removes only both owned working copies") { store ->
        val before = snapshot()
        val owned = workspaceOwnedDirectories(store.root, before.workspaces.first())
        owned.forEach { folder -> File(folder, "nested").mkdirs(); File(folder, "nested/result.txt").writeText("result") }
        val other = File(store.root, "second").apply { mkdirs() }
        File(other, "keep.txt").writeText("other")
        store.save(prepareWorkspaceRemovalSnapshot(before, "first", store.workspaceDirectoryPaths(before.workspaces.first()), nowMillis = now))
        val result = store.deleteRemovedWorkspaceFiles(before.workspaces.first())
        check(result.completed && result.deletedPaths.size == 2 && owned.none { it.exists() })
        check(File(other, "keep.txt").readText() == "other")
        check(requireNotNull(store.initialize(now)).messages["a"] == before.messages["a"])
    }
    test("SAF deletion removes private Linux copy and preserves external directory reference") { store ->
        val workspace = Workspace("external", "External", "content://fixture/tree")
        val before = snapshot(workspaces = listOf(workspace, Workspace("second", "Second")),
            conversations = listOf(Conversation("a", "A", "external"), Conversation("b", "B", "second")),
            selectedWorkspace = "external")
        val linuxCopy = workspaceOwnedDirectories(store.root, workspace).single().apply { mkdirs() }
        File(linuxCopy, "result.txt").writeText("delete private")
        val unrelated = File(store.root, "external").apply { mkdirs() }
        File(unrelated, "keep.txt").writeText("keep")
        store.save(prepareWorkspaceRemovalSnapshot(before, "external", store.workspaceDirectoryPaths(workspace), nowMillis = now))
        val result = store.deleteRemovedWorkspaceFiles(workspace)
        check(result.completed && result.preservedExternalUri == workspace.uri && !linuxCopy.exists())
        check(File(unrelated, "keep.txt").readText() == "keep")
    }
    test("recovery journal prevents deletion even after a new index commit") { store ->
        val before = snapshot()
        store.save(before)
        AtomicFile.failStartFor = "workspace-index.json"
        val removal = prepareWorkspaceRemovalSnapshot(before, "first", emptyList(), nowMillis = now)
        check(store.saveWithRecovery(removal).recoveryCommitted)
        check(runCatching { store.deleteRemovedWorkspaceFiles(before.workspaces.first()) }.isFailure)
    }
    test("traversal identities cannot select a directory for deletion") { store ->
        check(runCatching { store.workspaceDirectoryPaths(Workspace("../escape", "Bad")) }.isFailure)
        check(runCatching { deleteOwnedWorkspaceDirectories(store.root, Workspace("../escape", "Bad")) }.getOrThrow().error != null)
    }
    test("symbolic child is unlinked without touching its outside target when host supports links") { store ->
        val workspace = Workspace("first", "First")
        val owned = workspaceOwnedDirectories(store.root, workspace).first().apply { mkdirs() }
        val outside = File(store.root.parentFile, "outside").apply { mkdirs() }
        File(outside, "keep.txt").writeText("outside")
        val linked = runCatching { Files.createSymbolicLink(File(owned, "link").toPath(), outside.toPath()) }.isSuccess
        if (!linked) {
            println("SKIP child symlink OS operation: host does not permit symlink creation")
        } else {
            check(deleteOwnedWorkspaceDirectories(store.root, workspace).completed)
            check(File(outside, "keep.txt").readText() == "outside")
        }
    }
    println("RESULT $passed/$passed production helper and persistence checks; Android Context/AtomicFile are JVM fixtures")
}
