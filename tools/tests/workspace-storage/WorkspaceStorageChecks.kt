package com.dafeiyu.mobile.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

private var passed = 0

private fun test(name: String, action: () -> Unit) {
    try {
        action()
        passed++
        println("PASS $name")
    } catch (error: Throwable) {
        throw AssertionError(name, error)
    }
}

private fun context(root: File): Context = Context(File(root, "files").apply { mkdirs() })

private fun snapshot(
    workspaces: List<Workspace>,
    conversations: List<Conversation>,
    messages: Map<String, List<ChatMessage>>,
    selectedWorkspaceId: String = workspaces.first().id,
    selectedConversationId: String = conversations.firstOrNull()?.id.orEmpty(),
) = WorkspaceSnapshot(
    workspaces = workspaces,
    conversations = conversations,
    messages = messages,
    selectedWorkspaceId = selectedWorkspaceId,
    selectedConversationId = selectedConversationId,
)

private fun validId(index: Int) = "00000000-0000-4000-8000-%012d".format(index)

private fun indexFile(context: Context): File =
    File(context.filesDir, "Dafeiyu-Works/workspace-index.json")

private fun recoveryFile(context: Context): File =
    File(context.filesDir, "Dafeiyu-Works/workspace-recovery.json")

private fun oneMessage(text: String, modelInput: String? = null, attachments: List<String> = emptyList()) =
    snapshot(listOf(Workspace("default", "Default")),
        listOf(Conversation("c1", "Title", "default")),
        mapOf("c1" to listOf(ChatMessage(1L, "user", text, modelInput = modelInput, attachmentIds = attachments))))

fun main(args: Array<String>) {
    val root = File(args.first()).apply { mkdirs() }

    test("modelInput and attachmentIds survive save and reload") {
        val ctx = context(File(root, "roundtrip"))
        val store = WorkspaceStore(ctx)
        val workspaces = listOf(Workspace("default", "Default"))
        val conversations = listOf(Conversation("c1", "Conversation", "default"))
        val modelInput = """{"messages":[{"role":"user","content":"quote \" slash \\ newline\n"}]}"""
        val attachments = (0 until 6).map(::validId)
        store.save(snapshot(workspaces, conversations, mapOf(
            "c1" to listOf(ChatMessage(7L, "user", "visible", modelInput = modelInput, attachmentIds = attachments)),
        )))
        val loaded = requireNotNull(WorkspaceStore(ctx).initialize(0L))
        val message = loaded.messages.getValue("c1").single()
        check(message.modelInput == modelInput)
        check(message.attachmentIds == attachments)
    }

    test("JSON escaping preserves titles, visible text and model input content") {
        val ctx = context(File(root, "escaping"))
        val title = "quote \" slash \\ line\n tab\t unicode \u96ea"
        val text = "body {\"key\":\"value\"}\nsecond line\\end"
        val modelInput = """{"content":"a\\b","quoted":"\"value\"","newline":"x
y"}"""
        val store = WorkspaceStore(ctx)
        store.save(snapshot(
            listOf(Workspace("default", title)),
            listOf(Conversation("c1", title, "default")),
            mapOf("c1" to listOf(ChatMessage(1L, "assistant", text, modelInput = modelInput))),
        ))
        val raw = indexFile(ctx).readText(StandardCharsets.UTF_8)
        check(raw.contains("\\\"") && raw.contains("\\\\") && raw.contains("\\n"))
        val message = requireNotNull(WorkspaceStore(ctx).initialize(0L)).messages.getValue("c1").single()
        check(message.text == text && message.modelInput == modelInput)
        check(requireNotNull(WorkspaceStore(ctx).initialize(0L)).conversations.single().title == title)
    }

    test("version 1 index remains readable and keeps modelInput and attachmentIds") {
        val ctx = context(File(root, "v1"))
        val file = indexFile(ctx)
        file.parentFile.mkdirs()
        val oldModelInput = """{"role":"user","content":"legacy \"content\""}"""
        val oldAttachment = validId(42)
        val json = JSONObject()
            .put("version", 1)
            .put("selectedWorkspaceId", "legacy")
            .put("selectedConversationId", "legacy-conversation")
            .put("workspaces", JSONArray().put(JSONObject().put("id", "legacy").put("name", "Legacy")))
            .put("conversations", JSONArray().put(JSONObject().put(
                "id", "legacy-conversation").put("title", "Legacy").put("workspaceId", "legacy")))
            .put("messages", JSONObject().put("legacy-conversation", JSONArray().put(
                JSONObject().put("id", 9).put("role", "user").put("text", "old")
                    .put("modelInput", oldModelInput).put("attachmentIds", JSONArray().put(oldAttachment)))))
        file.writeText(json.toString(), StandardCharsets.UTF_8)
        val loaded = requireNotNull(WorkspaceStore(ctx).initialize(0L))
        val message = loaded.messages.getValue("legacy-conversation").single()
        check(message.modelInput == oldModelInput && message.attachmentIds == listOf(oldAttachment))
        check(loaded.selectedWorkspaceId == "legacy" && loaded.selectedConversationId == "legacy-conversation")
    }

    test("legacy messages without optional fields restore null modelInput and empty attachments") {
        val ctx = context(File(root, "legacy-optional"))
        val store = WorkspaceStore(ctx)
        store.save(snapshot(listOf(Workspace("default", "Default")),
            listOf(Conversation("c1", "Old", "default")),
            mapOf("c1" to listOf(ChatMessage(1L, "user", "legacy")))))
        val file = indexFile(ctx)
        val raw = JSONObject(file.readText()).put("version", 1)
        raw.getJSONArray("conversations").getJSONObject(0).put("archivedAtMillis", 0L)
        file.writeText(raw.toString())
        val loaded = requireNotNull(store.initialize(ARCHIVE_RETENTION_MILLIS + 1))
        val message = loaded.messages.getValue("c1").single()
        check(message.modelInput == null && message.attachmentIds.isEmpty() && message.streamSnapshot == null)
        check(loaded.conversations.single().archivedAtMillis == null)
        store.save(loaded)
        check(JSONObject(file.readText()).getInt("version") == 2)
        check(requireNotNull(store.initialize(0L)).messages.getValue("c1").single() == message)
    }

    test("save keeps newest 1000 messages and first 100 conversations with no orphan messages") {
        val ctx = context(File(root, "save-limits"))
        val store = WorkspaceStore(ctx)
        val conversations = (0 until 101).map { Conversation("c$it", "Title", "default") }
        store.save(snapshot(listOf(Workspace("default", "Default")), conversations, mapOf(
            "c0" to (0 until 1001).map { ChatMessage(it.toLong(), "user", "text") },
            "c100" to listOf(ChatMessage(2L, "user", "discarded")),
            "orphan" to listOf(ChatMessage(3L, "user", "orphan")),
        ), selectedConversationId = "c100"))
        val raw = JSONObject(indexFile(ctx).readText())
        check(raw.getJSONArray("conversations").length() == 100)
        check(!raw.getJSONObject("messages").has("c100") && !raw.getJSONObject("messages").has("orphan"))
        val loaded = requireNotNull(store.initialize(0L))
        check(loaded.messages.getValue("c0").map { it.id } == (1L..1000L).toList())
        check(loaded.selectedConversationId == "c0")
    }

    test("complete source above former character limits survives within serialized byte capacity") {
        val ctx = context(File(root, "string-limits"))
        val store = WorkspaceStore(ctx)
        val atText = "t".repeat(65536)
        val atInput = "i".repeat(600_000)
        store.save(snapshot(listOf(Workspace("default", "n".repeat(81))),
            listOf(Conversation("c1", "c".repeat(101), "default")),
            mapOf("c1" to listOf(
                ChatMessage(1L, "user", atText, modelInput = atInput),
                ChatMessage(2L, "user", atText + "overflow", modelInput = atInput + "overflow"),
                ChatMessage(3L, "user", "blank", modelInput = " \n\t"),
            ))))
        val loaded = requireNotNull(store.initialize(0L))
        check(loaded.workspaces.single().name == "n".repeat(80))
        check(loaded.conversations.single().title == "c".repeat(100))
        check(loaded.messages.getValue("c1")[0].text == atText)
        check(loaded.messages.getValue("c1")[0].modelInput == atInput)
        check(loaded.messages.getValue("c1")[1].text == atText + "overflow")
        check(loaded.messages.getValue("c1")[1].modelInput == atInput + "overflow")
        check(loaded.messages.getValue("c1").last().modelInput == null)
    }

    test("attachment load validates only first six positions without replacing rejected entries") {
        val ctx = context(File(root, "attachment-validation"))
        val store = WorkspaceStore(ctx)
        val ids = listOf(validId(1), "content://external/picker", validId(2), "../unsafe", "", validId(3), validId(4))
        store.save(snapshot(listOf(Workspace("default", "Default")),
            listOf(Conversation("c1", "Title", "default")),
            mapOf("c1" to listOf(ChatMessage(1L, "user", "text", attachmentIds = ids)))))
        check(JSONObject(indexFile(ctx).readText()).getJSONObject("messages")
            .getJSONArray("c1").getJSONObject(0).getJSONArray("attachmentIds").length() == 6)
        check(requireNotNull(store.initialize(0L)).messages.getValue("c1").single().attachmentIds ==
            listOf(validId(1), validId(2), validId(3)))
    }

    test("workspace load examines first 200 positions and creates default if absent") {
        val ctx = context(File(root, "workspace-limit"))
        val store = WorkspaceStore(ctx)
        val workspaces = (0 until 201).map { Workspace("w$it", "Workspace", if (it == 0) "file:///unsafe" else "content://tree/$it") }
        store.save(snapshot(workspaces, emptyList(), emptyMap(), selectedWorkspaceId = "w200"))
        val loaded = requireNotNull(store.initialize(0L))
        check(loaded.workspaces.size == 201 && loaded.workspaces.first().id == "default")
        check(loaded.workspaces.none { it.id == "w200" })
        check(loaded.workspaces.first { it.id == "w0" }.uri == null)
        check(loaded.workspaces.first { it.id == "w1" }.uri == "content://tree/1")
        check(loaded.selectedWorkspaceId == "default")
    }

    test("attachment and collection limits are enforced during load") {
        val ctx = context(File(root, "limits"))
        val file = indexFile(ctx)
        file.parentFile.mkdirs()
        val attachments = JSONArray()
        repeat(8) { attachments.put(if (it == 7) "not-an-attachment" else validId(it)) }
        val conversations = JSONArray()
        repeat(105) { conversations.put(JSONObject().put("id", "c$it").put("title", "t$it").put("workspaceId", "default")) }
        val messages = JSONArray()
        repeat(1005) { messages.put(JSONObject().put("id", it).put("role", "user").put("text", "m$it")) }
        val json = JSONObject().put("version", 2)
            .put("selectedWorkspaceId", "default").put("selectedConversationId", "c0")
            .put("workspaces", JSONArray().put(JSONObject().put("id", "default").put("name", "Default")))
            .put("conversations", conversations)
            .put("messages", JSONObject()
                .put("c0", messages)
                .put("c1", JSONArray().put(JSONObject().put("id", 1).put("role", "user")
                    .put("text", "attachments").put("attachmentIds", attachments))))
        file.writeText(json.toString(), StandardCharsets.UTF_8)
        val loaded = requireNotNull(WorkspaceStore(ctx).initialize(0L))
        check(loaded.conversations.size == 100)
        check(loaded.messages.getValue("c0").size == 1000)
        check(loaded.messages.getValue("c1").single().attachmentIds == (0 until 6).map(::validId))
    }

    test("oversized save is rejected before replacing the prior index") {
        val ctx = context(File(root, "oversize"))
        val store = WorkspaceStore(ctx)
        val base = snapshot(
            listOf(Workspace("default", "Default")),
            listOf(Conversation("c1", "Stable", "default")),
            mapOf("c1" to listOf(ChatMessage(1L, "user", "prior"))),
        )
        store.save(base)
        val file = indexFile(ctx)
        val beforeBytes = file.readBytes()
        val hugeText = "x".repeat(65536)
        val hugeMessages = (0 until 130).map {
            ChatMessage(it.toLong(), "user", hugeText)
        }
        val oversized = snapshot(
            listOf(Workspace("default", "Default")),
            listOf(Conversation("c1", "Oversized", "default")),
            mapOf("c1" to hugeMessages),
        )
        check(runCatching { store.save(oversized) }.exceptionOrNull() is IllegalStateException)
        check(file.readBytes().contentEquals(beforeBytes))
        val loaded = requireNotNull(WorkspaceStore(ctx).initialize(0L))
        check(loaded.conversations.single().title == "Stable")
        check(loaded.messages.getValue("c1").single().text == "prior")
    }

    test("8 MiB limit counts UTF8 bytes after JSON escaping rather than plaintext characters") {
        for ((case, text, count) in listOf(
            Triple("utf8", "\u96ea".repeat(32_000), 90),
            Triple("escaping", "\"".repeat(65536), 65),
        )) {
            val ctx = context(File(root, "byte-limit-$case"))
            val store = WorkspaceStore(ctx)
            val base = snapshot(listOf(Workspace("default", "Default")),
                listOf(Conversation("c1", "Title", "default")),
                mapOf("c1" to listOf(ChatMessage(1L, "user", "prior"))))
            store.save(base)
            val before = indexFile(ctx).readBytes()
            val messages = (0 until count).map { ChatMessage(it.toLong(), "user", text) }
            check(runCatching { store.save(base.copy(messages = mapOf("c1" to messages))) }.exceptionOrNull()
                is IllegalStateException)
            check(indexFile(ctx).readBytes().contentEquals(before))
        }
    }

    test("exact serializer accepts 8MiB and rejects the next byte without changing the index") {
        val ctx = context(File(root, "exact-boundary"))
        val store = WorkspaceStore(ctx)
        val overhead = store.encode(oneMessage("")).size
        val atLimit = oneMessage("a".repeat(WorkspaceStore.MAX_INDEX_BYTES - overhead))
        val encoded = store.encode(atLimit)
        check(encoded.size == WorkspaceStore.MAX_INDEX_BYTES)
        store.save(atLimit)
        check(indexFile(ctx).readBytes().contentEquals(encoded))
        check(runCatching { store.save(oneMessage(atLimit.messages.getValue("c1").single().text + "a")) }.isFailure)
        check(indexFile(ctx).readBytes().contentEquals(encoded))
        check(requireNotNull(store.initialize()).messages.getValue("c1").single().text ==
            atLimit.messages.getValue("c1").single().text)
    }

    test("repeated attachment modelInput counts in candidate precommit and preserves the prior index") {
        val ctx = context(File(root, "input-duplication"))
        val store = WorkspaceStore(ctx)
        val input = "i".repeat(512 * 1024)
        val base = oneMessage("visible").copy(messages = mapOf("c1" to
            (1L..15L).map { ChatMessage(it, "user", "visible", modelInput = input) }))
        store.save(base)
        val before = indexFile(ctx).readBytes()
        val candidate = base.copy(messages = mapOf("c1" to
            (base.messages.getValue("c1") + ChatMessage(16L, "user", "next", modelInput = input))))
        check(store.encode(candidate).size > WorkspaceStore.MAX_INDEX_BYTES)
        check(runCatching { store.save(candidate) }.isFailure)
        check(indexFile(ctx).readBytes().contentEquals(before))
    }

    test("start and finish write failures preserve the previous atomic index") {
        for (phase in listOf("start", "finish")) {
            val ctx = context(File(root, "atomic-$phase"))
            val store = WorkspaceStore(ctx)
            store.save(oneMessage("before"))
            val before = indexFile(ctx).readBytes()
            try {
                if (phase == "start") AtomicFile.failStartFor = "workspace-index.json"
                else AtomicFile.failFinishFor = "workspace-index.json"
                check(runCatching { store.save(oneMessage("after")) }.isFailure)
            } finally {
                AtomicFile.failStartFor = null
                AtomicFile.failFinishFor = null
            }
            check(indexFile(ctx).readBytes().contentEquals(before))
            check(requireNotNull(WorkspaceStore(ctx).initialize()).messages.getValue("c1").single().text == "before")
        }
    }

    test("final stream overflow recovers complete response on restart and retry reclaims the journal") {
        val ctx = context(File(root, "final-recovery"))
        val store = WorkspaceStore(ctx)
        val base = oneMessage("question")
        store.save(base)
        val originalIndex = indexFile(ctx).readBytes()
        val answer = "reply".repeat(900_000)
        val reducer = AssistantStreamReducer(source = AssistantStreamSource.BACKEND)
        reducer.apply(AssistantStreamEvent.MessageStarted(0, "answer"))
        reducer.apply(AssistantStreamEvent.TextDelta(1, "answer", answer))
        reducer.apply(AssistantStreamEvent.ContentBlockDone(2, "answer", "text"))
        reducer.apply(AssistantStreamEvent.MessageCompleted(3, "answer"))
        val final = reducer.finish()
        val overflow = base.copy(messages = mapOf("c1" to
            (base.messages.getValue("c1") + ChatMessage(2L, "assistant", answer, final))))
        val result = store.saveWithRecovery(overflow)
        check(!result.indexCommitted && result.recoveryCommitted)
        check(indexFile(ctx).readBytes().contentEquals(originalIndex))
        check(recoveryFile(ctx).length() <= WorkspaceStore.MAX_RECOVERY_BYTES)
        val restarted = WorkspaceStore(ctx)
        check(restarted.hasRecovery())
        val restored = requireNotNull(restarted.initialize())
        val reply = restored.messages.getValue("c1").last()
        check(reply.text == answer && reply.streamSnapshot?.isFinal == true)
        check(reply.streamSnapshot.messages.single().blocks.filterIsInstance<AssistantContentBlockSnapshot.Text>()
            .single().text == answer)
        val compact = restored.copy(messages = mapOf("c1" to listOf(reply.copy(streamSnapshot = null))))
        check(restarted.saveWithRecovery(compact).indexCommitted)
        check(!restarted.hasRecovery() && !recoveryFile(ctx).exists())
        check(requireNotNull(WorkspaceStore(ctx).initialize()).messages.getValue("c1").single().text == answer)
    }

    test("interrupted stream recovered from write failure remains incomplete and keeps full source") {
        val ctx = context(File(root, "interrupted-recovery"))
        val store = WorkspaceStore(ctx)
        store.save(oneMessage("prior"))
        val text = "x".repeat(70_000)
        val reducer = AssistantStreamReducer(source = AssistantStreamSource.BACKEND)
        reducer.apply(AssistantStreamEvent.MessageStarted(0, "interrupted"))
        reducer.apply(AssistantStreamEvent.TextDelta(1, "interrupted", text))
        try {
            AtomicFile.failFinishFor = "workspace-index.json"
            val snapshot = oneMessage("partial").copy(messages = mapOf("c1" to
                listOf(ChatMessage(2L, "assistant", text, reducer.snapshot()))))
            val result = store.saveWithRecovery(snapshot)
            check(!result.indexCommitted && result.recoveryCommitted)
        } finally {
            AtomicFile.failFinishFor = null
        }
        val reply = requireNotNull(WorkspaceStore(ctx).initialize()).messages.getValue("c1").single()
        check(reply.text == text)
        check(reply.streamSnapshot?.messages?.single()?.status == AssistantMessageStatus.INCOMPLETE)
    }

    test("failed replacement recovery preserves both previous durable files and their attachment references") {
        val ctx = context(File(root, "journal-failure"))
        val store = WorkspaceStore(ctx)
        store.save(oneMessage("prior", attachments = listOf(validId(1))))
        val large = oneMessage("x".repeat(WorkspaceStore.MAX_INDEX_BYTES), attachments = listOf(validId(2)))
        check(store.saveWithRecovery(large).recoveryCommitted)
        val priorIndex = indexFile(ctx).readBytes()
        val priorRecovery = recoveryFile(ctx).readBytes()
        try {
            AtomicFile.failFinishFor = "workspace-recovery.json"
            check(runCatching { store.saveWithRecovery(large.copy(messages = mapOf("c1" to
                listOf(ChatMessage(3L, "user", "y".repeat(WorkspaceStore.MAX_INDEX_BYTES)))))) }.isFailure)
        } finally {
            AtomicFile.failFinishFor = null
        }
        check(indexFile(ctx).readBytes().contentEquals(priorIndex))
        check(recoveryFile(ctx).readBytes().contentEquals(priorRecovery))
        check(store.persistedAttachmentIds() == setOf(validId(1), validId(2)))
        check(requireNotNull(WorkspaceStore(ctx).initialize()).messages.getValue("c1").single().text ==
            large.messages.getValue("c1").single().text)
    }

    test("64MiB recovery cap fails closed without replacing the previous journal") {
        val ctx = context(File(root, "journal-cap"))
        val store = WorkspaceStore(ctx)
        store.save(oneMessage("prior"))
        check(store.saveWithRecovery(oneMessage("x".repeat(WorkspaceStore.MAX_INDEX_BYTES))).recoveryCommitted)
        val before = recoveryFile(ctx).readBytes()
        val error = runCatching {
            store.saveWithRecovery(oneMessage("x".repeat(WorkspaceStore.MAX_RECOVERY_BYTES)))
        }.exceptionOrNull()
        check(error is IllegalStateException && error.message.orEmpty().contains("容量上限"))
        check(recoveryFile(ctx).readBytes().contentEquals(before))
        check(indexFile(ctx).readText().contains("prior"))
    }

    test("stale journal cannot resurrect older content after a newer index commit") {
        val ctx = context(File(root, "stale-journal"))
        val store = WorkspaceStore(ctx)
        store.save(oneMessage("base"))
        check(store.saveWithRecovery(oneMessage("old".repeat(3_000_000))).recoveryCommitted)
        val oldJournal = recoveryFile(ctx).readBytes()
        store.save(oneMessage("new", attachments = listOf(validId(3))))
        recoveryFile(ctx).writeBytes(oldJournal)
        check(!store.hasRecovery())
        check(requireNotNull(WorkspaceStore(ctx).initialize()).messages.getValue("c1").single().text == "new")
        check(store.persistedAttachmentIds() == setOf(validId(3)))
    }

    test("journal reclamation failure reports recovery pending and retry is idempotent") {
        val ctx = context(File(root, "delete-failure"))
        val store = WorkspaceStore(ctx)
        store.save(oneMessage("base"))
        check(store.saveWithRecovery(oneMessage("x".repeat(WorkspaceStore.MAX_INDEX_BYTES))).recoveryCommitted)
        try {
            AtomicFile.failDeleteFor = "workspace-recovery.json"
            check(store.saveWithRecovery(oneMessage("new")).recoveryCommitted)
            check(requireNotNull(WorkspaceStore(ctx).initialize()).messages.getValue("c1").single().text == "new")
        } finally {
            AtomicFile.failDeleteFor = null
        }
        check(store.saveWithRecovery(oneMessage("new")).indexCommitted)
        check(!recoveryFile(ctx).exists())
    }

    test("corrupt durable reference data raises an error rather than permitting attachment deletion") {
        val ctx = context(File(root, "corrupt-references"))
        val store = WorkspaceStore(ctx)
        store.save(oneMessage("base", attachments = listOf(validId(1))))
        indexFile(ctx).writeText("{broken")
        check(runCatching { store.persistedAttachmentIds() }.isFailure)
        check(runCatching { store.initialize() }.isFailure)
    }

    println("Workspace storage checks: $passed PASS")
    println("AtomicFile note: JVM fixture validates JSON/limits and prior-index preservation; it does not prove Android crash durability.")
}

