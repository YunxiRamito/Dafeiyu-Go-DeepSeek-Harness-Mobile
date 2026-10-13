package com.dafeiyu.mobile.data

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.json.JSONObject
import org.json.JSONArray

fun main(args: Array<String>) = runBlocking {
    require(args.size == 5)
    val root = File(args[0]).apply { mkdirs() }
    val firstPartyFixtures = File(args[1])
    val store = DymArchiveStore(root)
    val nativeStore = AndroidBackupStore()
    var passed = 0
    suspend fun test(name: String, block: suspend () -> Unit) { block(); passed++; println("PASS $name") }
    fun nativeBackup(message: AndroidBackupStore.BackupMessage = AndroidBackupStore.BackupMessage(1L, "user", "retained")): ByteArray = nativeStore.export(AndroidBackupStore.BackupInput(
        workspaces = listOf(AndroidBackupStore.BackupWorkspace("second", "Second")),
        conversations = listOf(AndroidBackupStore.BackupConversation("a", "A", "first", System.currentTimeMillis(),
            AndroidBackupStore.BackupArchiveOrigin("first", "First", 0, 0))),
        messages = mapOf("a" to listOf(message)),
        selectedWorkspaceId = "second", selectedConversationId = "", settings = AndroidBackupStore.BackupSettings(),
    ))
    fun fixture(name: String, contents: Map<String, ByteArray>): File {
        val file = File(root, name)
        SevenZOutputFile(file).use { archive ->
            contents.forEach { (path, bytes) ->
                archive.putArchiveEntry(SevenZArchiveEntry().apply { this.name = path; size = bytes.size.toLong() })
                archive.write(bytes); archive.closeArchiveEntry()
            }
        }
        return file
    }
    suspend fun import(file: File) = store.stageImport(file, { nativeStore.import(it) })
    fun pcArtifact(id: String = "pc-id", interrupted: Boolean = true): JSONObject {
        // Synthetic display/provenance fixture only. Its lifecycle is intentionally
        // not offered as valid DSH; exact first-party physical formats are tested below.
        val events = JSONArray()
        fun event(type: String, data: JSONObject, surface: Boolean = false) {
            events.put(JSONObject().put("type", type).put("seq", events.length()).put("time", 1000L)
                .put("data", data).also { if (surface) it.put("surfaceOp", "append") })
        }
        fun message(id: String, role: String, text: String): JSONObject = JSONObject().put("id", id).put("role", role)
            .put("source", JSONObject().put("kind", if (role == "user") "user" else "model")
                .put("provider", "deepseek").put("model", "fixture"))
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        event("session/title", JSONObject().put("title", "PC original session"))
        event("system/message", JSONObject().put("instruction", "do not replay this old system prompt"), surface = true)
        event("user/message", message("u1", "user", "original user request"), surface = true)
        event("turn/start", JSONObject().put("turn", 1))
        val assistant = message("a1", "assistant", "compatible answer")
        assistant.getJSONArray("content").put(JSONObject().put("type", "reasoning").put("text", "public thinking")
            .put("signature", "DO-NOT-EXPORT-SIGNATURE"))
        event("assistant/message", JSONObject().put("turn", 1).put("step", 0).put("message", assistant).put("stream", JSONArray()), surface = true)
        event("tool/call", JSONObject().put("callId", "native.pc/call:1").put("name", "pc_shell")
            .put("arguments", "{\"command\":\"echo PC\"}"))
        if (!interrupted) {
            val result = message("t1", "tool", "original tool result").put("toolCallId", "native.pc/call:1")
            event("tool/result", JSONObject().put("turn", 1).put("step", 0).put("message", result), surface = true)
            event("turn/end", JSONObject().put("turn", 1))
        }
        return JSONObject().put("header", JSONObject().put("version", 4).put("id", id).put("createdAt", 1000L)
            .put("cwd", "C:\\Projects\\Example")).put("events", events)
    }
    fun physicalArtifact(artifact: JSONObject): ByteArray = buildString {
        append(JSONObject(artifact.getJSONObject("header").toString()).put("type", "session")); append('\n')
        val events = artifact.getJSONArray("events")
        repeat(events.length()) { append(events.getJSONObject(it)); append('\n') }
    }.toByteArray()
    var readerCalls = 0
    fun readFirstParty(resource: DymStagedFile): JSONObject {
        val output = File(root, "firstparty-${readerCalls++}.json")
        val errors = File(root, "firstparty-errors.log")
        val builder = ProcessBuilder(args[2], args[4], args[3]).directory(root)
            .redirectOutput(output).redirectError(errors)
        builder.environment()["TMP"] = root.absolutePath
        builder.environment()["TEMP"] = root.absolutePath
        val process = builder.start()
        try {
            process.outputStream.use { it.write(JSONObject().put("operation", "portable")
                .put("file", resource.file.absolutePath).toString().toByteArray()) }
            check(process.waitFor(30, TimeUnit.SECONDS)) { "Pinned storage-only reader timed out" }
            check(output.length() <= 24 * 1024 * 1024)
            val response = JSONObject(output.readText())
            check(process.exitValue() == 0 && response.optBoolean("ok")) { "Pinned reader rejected the physical Session" }
            return response.getJSONObject("value")
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
    fun realSession(compression: String): File {
        val directory = File(firstPartyFixtures, compression)
        return directory.walkTopDown().single { it.isFile && it.name ==
            if (compression == "none") "session.v4.jsonl" else "session.v4.jsonl.zstd" }
    }
    test("Android JSON schema4 roundtrip retains removed-workspace archives and message contents") {
        val validated = nativeStore.import(nativeBackup())
        check(validated.schemaVersion == 4 && validated.workspaces.single().id == "second")
        check(validated.conversations.single().archiveOrigin?.workspaceName == "First")
        check(validated.messages["a"]?.single()?.text == "retained")
    }
    test("native backup rejects an active orphan with copied source provenance") {
        val json = JSONObject(String(nativeBackup(), Charsets.UTF_8))
        json.getJSONArray("conversations").getJSONObject(0).put("archivedAtMillis", JSONObject.NULL)
        check(runCatching { nativeStore.import(json.toString().toByteArray()) }.isFailure)
    }
    test("native backup still imports schemas1 to3 with their original fields") {
        for (version in listOf(1, 2, 3)) {
            val json = JSONObject(String(nativeBackup(), Charsets.UTF_8)).put("schemaVersion", version)
            val conversation = json.getJSONArray("conversations").getJSONObject(0)
            conversation.remove("archiveOrigin"); conversation.put("workspaceId", "second")
            if (version == 1) conversation.remove("archivedAtMillis")
            check(nativeStore.import(json.toString().toByteArray()).schemaVersion == version)
        }
    }
    test("public thinking and native tool provenance survive while pending imported work is interrupted") {
        val reducer = AssistantStreamReducer(source = AssistantStreamSource.BACKEND)
        reducer.apply(AssistantStreamEvent.MessageStarted(0, "pc-message"))
        reducer.apply(AssistantStreamEvent.UserVisibleThinkingDelta(1, "pc-message", "thinking", "public thought"))
        reducer.apply(AssistantStreamEvent.TextDelta(2, "pc-message", "partial answer"))
        reducer.apply(AssistantStreamEvent.ToolCallStarted(3, "pc-call", "pc.run_script", initialArguments = "{\"script\":\"echo fixture\"}"))
        reducer.apply(AssistantStreamEvent.ToolArgumentsDone(4, "pc-call"))
        reducer.apply(AssistantStreamEvent.ToolApprovalRequired(5, "pc-call"))
        reducer.apply(AssistantStreamEvent.SubagentStarted(6, "pc-agent", "PC researcher", "pc-call"))
        val snapshot = reducer.snapshot().copy(runStatus = AssistantRunStatus.RUNNING)
        val message = AndroidBackupStore.BackupMessage(2, "assistant", "partial answer", snapshot, "compatible context")
        val imported = nativeStore.import(nativeBackup(message)).messages.getValue("a").single()
        check(imported.modelInput == "compatible context")
        val display = requireNotNull(imported.streamSnapshot)
        check(display.isFinal && display.runStatus == AssistantRunStatus.INCOMPLETE && display.pendingSequenceNumbers.isEmpty())
        check(display.messages.single().status == AssistantMessageStatus.INCOMPLETE)
        check((display.messages.single().blocks.first() as AssistantContentBlockSnapshot.Reasoning).text == "public thought")
        check(display.toolCalls.single().name == "pc.run_script" && display.toolCalls.single().arguments.contains("echo fixture"))
        check(display.toolCalls.single().status == AssistantStreamEvent.ToolStatus.INCOMPLETE)
        check(display.subagents.single().status == AssistantStreamEvent.SubagentStatus.INCOMPLETE)
    }
    test("completed activity retains tool results and provider source on a native roundtrip") {
        val reducer = AssistantStreamReducer(source = AssistantStreamSource.ANTHROPIC)
        reducer.apply(AssistantStreamEvent.MessageStarted(0, "done"))
        reducer.apply(AssistantStreamEvent.ToolCallStarted(1, "call", "original.tool"))
        reducer.apply(AssistantStreamEvent.ToolResult(2, "call", "original result"))
        reducer.apply(AssistantStreamEvent.MessageCompleted(3, "done", "stop"))
        val snapshot = reducer.finish().copy(runStatus = AssistantRunStatus.COMPLETED)
        val imported = nativeStore.import(nativeBackup(AndroidBackupStore.BackupMessage(3, "assistant", "done", snapshot)))
        val restored = requireNotNull(imported.messages.getValue("a").single().streamSnapshot)
        check(restored.runStatus == AssistantRunStatus.COMPLETED && restored.source == AssistantStreamSource.ANTHROPIC)
        check(restored.toolCalls.single().result == "original result" && restored.toolCalls.single().status == AssistantStreamEvent.ToolStatus.SUCCEEDED)
    }
    test("untrusted activity rejects hidden payloads, unknown fields and coerced scalar types") {
        val reducer = AssistantStreamReducer()
        reducer.apply(AssistantStreamEvent.MessageStarted(0, "msg"))
        reducer.apply(AssistantStreamEvent.TextDelta(1, "msg", "text"))
        val bytes = nativeBackup(AndroidBackupStore.BackupMessage(4, "assistant", "text", reducer.finish()))
        for (mutation in listOf<(JSONObject) -> Unit>(
            { it.put("hiddenReasoning", "not public") },
            { it.put("isFinal", "true") },
            { it.put("lastAppliedSequenceNumber", 1.5) },
        )) {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            mutation(json.getJSONObject("messages").getJSONArray("a").getJSONObject(0).getJSONObject("streamSnapshot"))
            check(runCatching { nativeStore.import(json.toString().toByteArray()) }.isFailure)
        }
    }
    test("export produces genuine 7z magic with PC-readable DSH group prefixes") {
        val profiles = File(root, "data/profiles/default").apply { mkdirs() }
        File(profiles, "settings.json").writeText("{\"model\":\"deepseek\",\"apiKey\":\"fixture-key\"}")
        val skills = File(root, "data/skills/example").apply { mkdirs() }
        File(skills, "SKILL.md").writeText("---\nname: example\n---\nOffline skill")
        val output = store.export(File(root, "export.dym"), DymExportInput(
            directories = mapOf(".dsh/profiles" to requireNotNull(profiles.parentFile), "skills" to requireNotNull(skills.parentFile)),
            androidBackup = nativeBackup(), androidPreferences = JSONObject().put("theme", "dark"),
            pcPreferences = JSONObject().put("theme", "light"),
            providerConfiguration = JSONObject().put("deepseek", JSONObject().put("apiKey", "fixture-key")),
        ))
        check(output.inputStream().use { it.readNBytes(6) }.contentEquals(byteArrayOf(0x37, 0x7a, 0xbc.toByte(), 0xaf.toByte(), 0x27, 0x1c)))
        val names = SevenZFile.builder().setFile(output).get().use { archive -> archive.entries.map { it.name } }
        check(".dsh/profiles/default/settings.json" in names && "skills/example/SKILL.md" in names)
        val restored = import(output)
        check(restored.availableGroups == setOf("profiles", "skills"))
        check(restored.androidPreferences?.getString("theme") == "dark" && restored.pcPreferences?.getString("theme") == "light")
        check(restored.providerConfiguration?.getJSONObject("deepseek")?.getString("apiKey") == "fixture-key")
        check(restored.androidBackup != null && !restored.partial)
        store.discard(restored); check(!restored.stagingRoot.exists())
    }
    test("legacy PC prefixes are all recognized without altering source files") {
        val archive = fixture("legacy.dym", mapOf("profiles/default/config.json" to "{}".toByteArray(),
            ".dsh/skills/example/SKILL.md" to "skill".toByteArray(),
            ".dsh/plugin-profiles/default/sample/package.json" to "{}".toByteArray(),
            "storages/session.jsonl" to "{\"version\":4}".toByteArray()))
        val imported = import(archive)
        check(imported.availableGroups == setOf("profiles", "skills", "plugins", "sessions"))
        check(imported.groupFiles.getValue("sessions").single().file.readText() == "{\"version\":4}")
        store.discard(imported)
    }
    test("invalid Android preferences warn but valid groups and foreign preferences survive") {
        val archive = fixture("partial.dym", mapOf("skills/example/SKILL.md" to "skill".toByteArray(),
            DymArchiveStore.ANDROID_PREFERENCES to "not-json".toByteArray(),
            DymArchiveStore.PC_PREFERENCES to "{\"theme\":\"dark\"}".toByteArray()))
        val imported = import(archive)
        check(imported.partial && imported.androidPreferences == null && imported.availableGroups == setOf("skills"))
        check(imported.pcPreferences?.getString("theme") == "dark")
        store.discard(imported)
    }
    test("invalid native conversation group does not block DSH session files") {
        val archive = fixture("partial-native.dym", mapOf(".dsh/storages/session.jsonl" to "{}".toByteArray(),
            DymArchiveStore.ANDROID_BACKUP to "broken".toByteArray()))
        val imported = import(archive)
        check(imported.androidBackup == null && imported.partial && "sessions" in imported.availableGroups)
        store.discard(imported)
    }
    test("device identities are excluded while API keys remain portable") {
        val profiles = File(root, "identity/profiles").apply { mkdirs() }
        File(profiles, "identity.json").writeText("private")
        File(profiles, "settings.json").writeText("{\"apiKey\":\"fixture\"}")
        val archive = store.export(File(root, "identity.dym"), DymExportInput(directories = mapOf("profiles" to profiles)))
        val imported = import(archive)
        check(imported.groupFiles.getValue("profiles").single().file.readText().contains("apiKey"))
        check(runCatching { store.export(File(root, "identity-bad.dym"), DymExportInput(
            androidPreferences = JSONObject().put("device_id", "nonportable"))) }.isFailure)
        store.discard(imported)
    }
    test("canonical private-root acceptance still rejects outside and nested-link export paths") {
        val actual = File(root, "canonical-boundary").canonicalFile.apply { check(mkdirs()) }
        val outside = File(root, "canonical-outside").apply { check(mkdirs()) }
        File(outside, "SKILL.md").writeText("outside must not be exported")
        val boundary = DymArchiveStore(actual)
        check(runCatching { boundary.export(File(outside, "output.dym"), DymExportInput()) }.isFailure)
        check(runCatching { boundary.export(File(actual, "output.dym"), DymExportInput(
            directories = mapOf("skills" to outside.canonicalFile))) }.isFailure)
        val nested = File(actual, "nested-link")
        Files.createSymbolicLink(nested.toPath(), outside.toPath())
        try {
            check(runCatching { boundary.export(File(nested, "output.dym"), DymExportInput()) }.isFailure)
            check(runCatching { boundary.export(File(actual, "linked.dym"), DymExportInput(
                directories = mapOf("skills" to nested))) }.isFailure)
            check(!File(outside, "output.dym").exists())
        } finally { Files.delete(nested.toPath()) }
    }
    test("traversal and absolute paths are rejected before staging writes") {
        for ((index, path) in listOf("../escape", "/absolute", "C:/escape", "skills/../../escape").withIndex()) {
            val archive = fixture("bad-$index.dym", mapOf(path to "bad".toByteArray()))
            check(runCatching { import(archive) }.isFailure)
        }
        check(!File(root.parentFile, "escape").exists())
    }
    test("case-folded duplicate paths are rejected") {
        val archive = fixture("duplicate.dym", mapOf("skills/a/SKILL.md" to "first".toByteArray(),
            "skills/a/skill.md" to "second".toByteArray()))
        check(runCatching { import(archive) }.isFailure)
    }
    test("runtime dependency folders and export symlinks are omitted") {
        val folder = File(root, "dependencies/plugins/test").apply { mkdirs() }
        File(folder, "package.json").writeText("{}")
        File(folder, "node_modules/sample").mkdirs()
        File(folder, "node_modules/sample/large.bin").writeText("excluded")
        val outside = File(root, "outside.txt").apply { writeText("outside") }
        runCatching { Files.createSymbolicLink(File(folder, "link.txt").toPath(), outside.toPath()) }
        val archive = store.export(File(root, "dependencies.dym"), DymExportInput(directories = mapOf("plugins" to requireNotNull(folder.parentFile))))
        val imported = import(archive)
        check(imported.groupFiles.getValue("plugins").map { it.archivePath } == listOf("plugins/test/package.json"))
        store.discard(imported)
    }
    test("portable custom profile plugins retain nested dependencies but omit DSH runtime modules") {
        val portable = File(root, "portable-plugin/web").apply { mkdirs() }
        File(portable, "package.json").writeText("{\"dependencies\":{\"@fixture/portable\":\"file:./node_modules/@fixture/portable\"}}")
        val module = File(portable, "node_modules/@fixture/portable/node_modules/transitive").apply { mkdirs() }
        File(module, "index.js").writeText("nested dependency")
        File(portable, "node_modules/@fixture/portable/package.json").writeText("{\"name\":\"@fixture/portable\"}")
        val runtime = File(portable, "node_modules/@deepseek-ai/dsh-base").apply { mkdirs() }
        File(runtime, "index.js").writeText("runtime is separately installed")
        val archive = store.export(File(root, "portable.dym"), DymExportInput(
            directories = mapOf(".dsh/plugin-profiles" to requireNotNull(portable.parentFile))))
        val imported = import(archive)
        val paths = imported.groupFiles.getValue("plugins").map { it.archivePath }
        check(".dsh/plugin-profiles/web/node_modules/@fixture/portable/node_modules/transitive/index.js" in paths)
        check(paths.none { it.contains("@deepseek-ai/dsh-base") })
        store.discard(imported)
    }
    test("unknown archived time is never invented by reading raw DSH history") {
        val archive = fixture("unknown-time.dym", mapOf("sessions/history.jsonl" to
            "{\"version\":4,\"title\":\"A\",\"archived\":true}".toByteArray()))
        val imported = import(archive)
        check(imported.groupFiles.getValue("sessions").single().file.readText() ==
            "{\"version\":4,\"title\":\"A\",\"archived\":true}")
        store.discard(imported)
    }
    test("raw v4 compatibility fallback warns and excludes old instructions and opaque signatures") {
        val artifact = pcArtifact()
        val archive = fixture("pc-current.dym", mapOf(".dsh/sessions/project/session/session.v4.jsonl" to physicalArtifact(artifact)))
        val staged = import(archive)
        val conversion = DymHistoryConverter().convert(staged)
        check(conversion.convertedSessions == 1 && conversion.warnings.single().contains("未完成 DSH 格式校验"))
        val native = requireNotNull(conversion.conversations)
        check(native.workspaces.single().name == "Example" && native.conversations.single().title == "PC original session")
        val messages = native.messages.values.single()
        check(messages.map { it.role } == listOf("user", "assistant"))
        check(messages.first().text == "original user request" && messages.last().modelInput == "compatible answer")
        val stream = requireNotNull(messages.last().streamSnapshot)
        check(stream.runStatus == AssistantRunStatus.INCOMPLETE && stream.toolCalls.single().status == AssistantStreamEvent.ToolStatus.INCOMPLETE)
        check(stream.toolCalls.single().name == "pc_shell" && stream.toolCalls.single().callId == "native.pc/call:1")
        check((stream.messages.single().blocks.first() as AssistantContentBlockSnapshot.Reasoning).text == "public thinking")
        val encoded = nativeStore.export(AndroidBackupStore.BackupInput(native.workspaces, native.conversations, native.messages,
            native.selectedWorkspaceId, native.selectedConversationId, native.settings)).toString(Charsets.UTF_8)
        check(!encoded.contains("DO-NOT-EXPORT-SIGNATURE") && !encoded.contains("do not replay this old system prompt"))
        check(staged.groupFiles.getValue("sessions").single().file.readText().contains("DO-NOT-EXPORT-SIGNATURE"))
        store.discard(staged)
    }
    test("PC conversion preserves native sessions and skips malformed or oversized groups independently") {
        val archive = fixture("pc-partial-history.dym", mapOf(
            "sessions/project/good/session.v4.jsonl" to physicalArtifact(pcArtifact(interrupted = false)),
            "sessions/project/bad/session.v4.jsonl" to "bad-header\n".toByteArray()))
        val staged = import(archive)
        val conversion = DymHistoryConverter().convert(staged, nativeStore.import(nativeBackup()))
        check(conversion.convertedSessions == 1 && conversion.warnings.size == 2)
        val native = requireNotNull(conversion.conversations)
        check(native.conversations.size == 2 && native.messages.getValue("a").single().text == "retained")
        val converted = native.messages.values.first { it.any { message -> message.streamSnapshot != null } }.last()
        check(converted.streamSnapshot?.toolCalls?.single()?.result == "original tool result")
        check(converted.streamSnapshot?.runStatus == AssistantRunStatus.COMPLETED)
        store.discard(staged)
    }
    test("newest physical generation uses the injected reader boundary and missing components preserve opaque history") {
        val archive = fixture("pc-compressed-boundary.dym", mapOf(
            "sessions/project/same/session.v3.jsonl" to "legacy".toByteArray(),
            "sessions/project/same/session.v4.jsonl.zstd" to "compressed-boundary-fixture".toByteArray()))
        val staged = import(archive)
        var called = 0
        val decoded = DymHistoryConverter().convert(staged, readPhysicalArtifact = { resource ->
            called++; check(resource.file.name == "session.v4.jsonl.zstd"); pcArtifact(interrupted = false)
        })
        check(called == 1 && decoded.convertedSessions == 1)
        val missing = DymHistoryConverter().convert(staged)
        check(missing.conversations == null && missing.warnings.single().contains("原始记录已保留"))
        check(staged.groupFiles.getValue("sessions").size == 2)
        store.discard(staged)
    }
    for (compression in listOf("none", "zstd")) test("actual pinned $compression physical session in 7z is read by first-party Node and converted without execution") {
        val source = realSession(compression)
        val originalBytes = source.readBytes()
        val path = ".dsh/sessions/" + source.relativeTo(File(firstPartyFixtures, compression)).invariantSeparatorsPath
        val archive = fixture("first-party-$compression.dym", mapOf(path to originalBytes))
        val staged = import(archive)
        val conversion = DymHistoryConverter().convert(staged, readPhysicalArtifact = { readFirstParty(it) })
        check(conversion.convertedSessions == 1 && conversion.warnings.isEmpty())
        val history = requireNotNull(conversion.conversations).messages.values.single()
        check(history.map { it.role } == listOf("user", "assistant"))
        check(history.map { it.text } == listOf("OFFLINE QUESTION", "OFFLINE ANSWER"))
        check(history.last().streamSnapshot?.runStatus == AssistantRunStatus.COMPLETED)
        check(source.readBytes().contentEquals(originalBytes))
        check(staged.groupFiles.getValue("sessions").single().file.readBytes().contentEquals(originalBytes))
        store.discard(staged)
    }
    test("a rejecting first-party reader never falls back to the raw compatible parser") {
        val source = realSession("none")
        val rows = source.readLines().filter { it.isNotBlank() }.map(::JSONObject)
        rows[3].put("seq", 999)
        val path = ".dsh/sessions/" + source.relativeTo(File(firstPartyFixtures, "none")).invariantSeparatorsPath
        val bytes = rows.joinToString("\n", postfix = "\n") { it.toString() }.toByteArray()
        val archive = fixture("first-party-reject.dym", mapOf(path to bytes))
        val staged = import(archive)
        val conversion = DymHistoryConverter().convert(staged, readPhysicalArtifact = { readFirstParty(it) })
        check(conversion.convertedSessions == 0 && conversion.conversations == null)
        check(conversion.warnings.single().contains("原始记录已保留"))
        check(staged.groupFiles.getValue("sessions").single().file.readBytes().contentEquals(bytes))
        store.discard(staged)
    }
    test("cancelling a history reader stops conversion and preserves original staged bytes") {
        val source = realSession("none")
        val path = ".dsh/sessions/" + source.relativeTo(File(firstPartyFixtures, "none")).invariantSeparatorsPath
        val archive = fixture("first-party-cancel.dym", mapOf(path to source.readBytes()))
        val staged = import(archive)
        val failure = runCatching { DymHistoryConverter().convert(staged,
            readPhysicalArtifact = { throw CancellationException("fixture cancellation") }) }.exceptionOrNull()
        check(failure is CancellationException)
        check(staged.groupFiles.getValue("sessions").single().file.readBytes().contentEquals(source.readBytes()))
        store.discard(staged)
    }
    test("actual skill and portable plugin roots are restored without running any file or leaving synthetic directories") {
        val installedRoot = File(root, "installed-real").apply { mkdirs() }
        val installer = DymRuntimeResourceStore(installedRoot)
        val localArchiveStore = DymArchiveStore(installedRoot)
        val archive = File(installedRoot, "input.dym")
        SevenZOutputFile(archive).use { zip ->
            for ((path, content) in mapOf(
                ".dsh/skills/fixture/SKILL.md" to "---\nname: fixture\n---\nDo not execute on import",
                ".dsh/plugin-profiles/web/package.json" to "{\"dependencies\":{\"@fixture/portable\":\"file:C:/old-PC/plugin\"},\"dsh\":{\"profile\":{\"bundles\":[\"@fixture/portable\"]}}}",
                ".dsh/plugin-profiles/web/node_modules/@fixture/portable/package.json" to "{\"name\":\"@fixture/portable\"}",
                ".dsh/plugin-profiles/web/node_modules/@fixture/portable/index.js" to "throw new Error('This resource must not be executed by import')",
                ".dsh/profiles/web/settings.yml" to "fixture: true",
            )) {
                val bytes = content.toByteArray()
                zip.putArchiveEntry(SevenZArchiveEntry().apply { name = path; size = bytes.size.toLong() })
                zip.write(bytes); zip.closeArchiveEntry()
            }
        }
        val staged = localArchiveStore.stageImport(archive, {})
        var guarded = 0
        val report = installer.restore(staged) { guarded++ }
        check(report.restoredFiles == 5 && report.warnings.isEmpty() && guarded >= 3)
        check(File(installedRoot, "dsh-home/skills/fixture/SKILL.md").readText().contains("Do not execute"))
        check(File(installedRoot, "dsh-home/profiles/web/node_modules/@fixture/portable/index.js").isFile)
        check(!File(installedRoot, "dsh-home/plugin-profiles").exists())
        val manifest = JSONObject(File(installedRoot, "dsh-home/profiles/web/package.json").readText())
        check(manifest.getJSONObject("dependencies").getString("@fixture/portable") == "file:./node_modules/@fixture/portable")
        localArchiveStore.discard(staged)
    }
    test("installed-resource conflicts keep existing bytes and preserve disabled plugins and DSH runtime defaults") {
        val installedRoot = File(root, "installed-conflicts").apply { mkdirs() }
        val skill = File(installedRoot, "dsh-home/skills/fixture").apply { mkdirs() }
        File(skill, "SKILL.md").writeText("existing skill")
        val profile = File(installedRoot, "dsh-home/profiles/web").apply { mkdirs() }
        File(profile, "package.json").writeText("{\"dependencies\":{\"@deepseek-ai/dsh-base\":\"0.2.1\",\"old-plugin\":\"1.0\"},\"dsh\":{\"profile\":{\"bundles\":[\"@deepseek-ai/dsh-base\"]}}}")
        val localStore = DymArchiveStore(installedRoot)
        val incoming = File(installedRoot, "incoming/skills/fixture").apply { mkdirs() }
        File(incoming, "SKILL.md").writeText("incoming skill")
        val incomingProfile = File(installedRoot, "incoming/plugin-profile/web").apply { mkdirs() }
        File(incomingProfile, "package.json").writeText("{\"dependencies\":{\"@deepseek-ai/dsh-base\":\"9.9\",\"old-plugin\":\"2.0\",\"new-plugin\":\"file:./node_modules/new-plugin\"},\"dsh\":{\"profile\":{\"bundles\":[\"old-plugin\",\"new-plugin\"]}}}")
        File(incomingProfile, "node_modules/new-plugin").mkdirs()
        File(incomingProfile, "node_modules/new-plugin/index.js").writeText("new plugin")
        val archive = localStore.export(File(installedRoot, "conflict.dym"), DymExportInput(directories = mapOf(
            "skills" to requireNotNull(incoming.parentFile), ".dsh/plugin-profiles" to requireNotNull(incomingProfile.parentFile))))
        val staged = localStore.stageImport(archive, {})
        val report = DymRuntimeResourceStore(installedRoot).restore(staged) {}
        check(report.preservedFiles == 1 && report.warnings.size == 2)
        check(File(skill, "SKILL.md").readText() == "existing skill")
        val manifest = JSONObject(File(profile, "package.json").readText())
        check(manifest.getJSONObject("dependencies").getString("@deepseek-ai/dsh-base") == "0.2.1")
        check(manifest.getJSONObject("dependencies").getString("old-plugin") == "1.0")
        val bundles = manifest.getJSONObject("dsh").getJSONObject("profile").getJSONArray("bundles")
        check((0 until bundles.length()).map { bundles.getString(it) } == listOf("@deepseek-ai/dsh-base", "new-plugin"))
        check(staged.groupFiles.getValue("skills").single().file.readText() == "incoming skill")
        localStore.discard(staged)
    }
    test("resource publication failure rolls back already moved units and leaves existing manifests intact") {
        val installedRoot = File(root, "installed-rollback").apply { mkdirs() }
        val localStore = DymArchiveStore(installedRoot)
        val data = File(installedRoot, "incoming/skills").apply { mkdirs() }
        for (name in listOf("a", "b")) { File(data, name).mkdirs(); File(data, "$name/SKILL.md").writeText(name) }
        val archive = localStore.export(File(installedRoot, "rollback.dym"), DymExportInput(directories = mapOf("skills" to data)))
        val staged = localStore.stageImport(archive, {})
        var checkpoints = 0
        check(runCatching { DymRuntimeResourceStore(installedRoot).restore(staged) {
            checkpoints++; check(checkpoints < 4) { "injected process acquisition" }
        } }.isFailure)
        check(!File(installedRoot, "dsh-home/skills/a").exists() && !File(installedRoot, "dsh-home/skills/b").exists())
        check(File(installedRoot, "dym-resource-operations").listFiles().orEmpty().isEmpty())
        localStore.discard(staged)
    }
    test("export includes actual installed skill roots and materializes custom plugin bytes from real profiles") {
        val installedRoot = File(root, "installed-export").apply { mkdirs() }
        val skill = File(installedRoot, "dsh-home/skills/live").apply { mkdirs() }
        File(skill, "SKILL.md").writeText("installed after earlier backup")
        val profile = File(installedRoot, "dsh-home/profiles/web").apply { mkdirs() }
        File(profile, "package.json").writeText("{\"dependencies\":{\"custom\":\"file:/old-path\",\"@deepseek-ai/dsh-base\":\"0.2.1\"}}")
        File(profile, "node_modules/custom").mkdirs()
        File(profile, "node_modules/custom/package.json").writeText("{\"name\":\"custom\"}")
        File(profile, "node_modules/custom/index.js").writeText("actual installed content")
        File(profile, "node_modules/@deepseek-ai/dsh-base").mkdirs()
        File(profile, "node_modules/@deepseek-ai/dsh-base/index.js").writeText("runtime cache")
        val installer = DymRuntimeResourceStore(installedRoot)
        val portable = installer.portablePlugins(File(installedRoot, "portable-output"))
        val localStore = DymArchiveStore(installedRoot)
        val archive = localStore.export(File(installedRoot, "installed.dym"), DymExportInput(
            directories = installer.installedDirectories(includeProfiles = true) + (".dsh/plugin-profiles" to portable)))
        val staged = localStore.stageImport(archive, {})
        check(staged.groupFiles.getValue("skills").single().file.readText() == "installed after earlier backup")
        check(staged.groupFiles.getValue("plugins").any { it.archivePath.endsWith("node_modules/custom/index.js") &&
            it.file.readText() == "actual installed content" })
        check(staged.groupFiles.getValue("plugins").none { it.archivePath.contains("@deepseek-ai/dsh-base") })
        localStore.discard(staged)
    }
    test("native protocol history uses its own namespace and imported unfinished context cannot resume") {
        val installedRoot = File(root, "native-protocol").apply { mkdirs() }
        val nativeDirectory = File(installedRoot, "native-model-history").apply { mkdirs() }
        val fileName = MessageDigest.getInstance("SHA-256").digest("a".toByteArray())
            .joinToString("") { "%02x".format(it) } + ".json"
        File(nativeDirectory, fileName).writeText(JSONObject().put("schema", 1).put("provider", "fixture")
            .put("model", "fixture-model").put("through", 1).put("completed", true)
            .put("context", JSONObject().put("messages", JSONArray().put(JSONObject().put("role", "assistant")
                .put("opaqueSignature", "private-protocol-signature")))).toString())
        val protocol = DymNativeHistoryStore(installedRoot)
        val output = File(installedRoot, "native-snapshot")
        val snap = protocol.snapshot(output, setOf("a"))
        check(snap.files == 1 && snap.warnings.isEmpty())
        val localStore = DymArchiveStore(installedRoot)
        val archive = localStore.export(File(installedRoot, "native.dym"), DymExportInput(
            directories = mapOf(DymNativeHistoryStore.PREFIX to output), androidBackup = nativeBackup()))
        val staged = localStore.stageImport(archive, { nativeStore.import(it) })
        check(staged.availableGroups == setOf("native-history"))
        val restored = protocol.restore(staged, nativeStore.import(requireNotNull(staged.androidBackup)))
        check(restored.files == 1 && restored.warnings.single().contains("兼容正文"))
        val record = JSONObject(File(nativeDirectory, fileName).readText())
        check(!record.getBoolean("completed") && record.getJSONObject("context").toString().contains("private-protocol-signature"))
        check(staged.androidBackup?.toString(Charsets.UTF_8)?.contains("private-protocol-signature") == false)
        localStore.discard(staged)
    }
    test("corrupt and unmatched native protocol records warn without blocking valid conversation restoration") {
        val installedRoot = File(root, "native-protocol-partial").apply { mkdirs() }
        val localStore = DymArchiveStore(installedRoot)
        val output = File(installedRoot, "incoming-native").apply { mkdirs() }
        File(output, "0".repeat(64) + ".json").writeText("invalid")
        File(output, "1".repeat(64) + ".json").writeText(JSONObject().put("schema", 1).put("provider", "fixture")
            .put("model", "model").put("through", 1).put("completed", false)
            .put("context", JSONObject().put("messages", JSONArray())).toString())
        val archive = localStore.export(File(installedRoot, "native-partial.dym"), DymExportInput(
            directories = mapOf(DymNativeHistoryStore.PREFIX to output), androidBackup = nativeBackup()))
        val staged = localStore.stageImport(archive, { nativeStore.import(it) })
        val restored = DymNativeHistoryStore(installedRoot).restore(staged, nativeStore.import(requireNotNull(staged.androidBackup)))
        check(restored.files == 0 && restored.warnings.size == 2)
        check(nativeStore.import(requireNotNull(staged.androidBackup)).messages.getValue("a").single().text == "retained")
        localStore.discard(staged)
    }
    test("wrong appearance boolean types skip only their group and retain usable official/download preferences") {
        val repo = DemoRepository()
        val preferences = AndroidDymPreferences.capture(repo)
        preferences.getJSONObject("groups").getJSONObject("appearance").put("reduceTransparency", "false")
        val parsed = AndroidDymPreferences.parse(preferences)
        check("appearance" !in parsed.groups && parsed.warnings.single().contains("外观"))
        check("official" in parsed.groups && "downloads" in parsed.groups)
        check(AndroidDymPreferences.apply(repo, parsed).isEmpty())
        check(!repo.officialEngineExpandActivities && !repo.tokenSavingMode && repo.downloadSource == "cdn")
    }
    test("malformed notify group cannot prevent independent Android engine and PC preferences roundtrip") {
        val repo = DemoRepository()
        val preferences = AndroidDymPreferences.capture(repo)
        preferences.getJSONObject("groups").getJSONObject("notifications").put("enabled", 1)
        preferences.getJSONObject("groups").getJSONObject("engine").put("engineMode", "dsh")
        val parsed = AndroidDymPreferences.parse(preferences)
        check("notifications" !in parsed.groups && "engine" in parsed.groups)
        check(AndroidDymPreferences.apply(repo, parsed).isEmpty() && repo.engineMode == "dsh")
        val archive = store.export(File(root, "independent-prefs.dym"), DymExportInput(
            androidPreferences = preferences, pcPreferences = JSONObject().put("theme", "pc-only")))
        val imported = import(archive)
        check(imported.pcPreferences?.getString("theme") == "pc-only")
        check(AndroidDymPreferences.parse(requireNotNull(imported.androidPreferences)).warnings.size == 1)
        store.discard(imported)
    }
    test("each local sandbox preference roundtrips as a choice without execution grants") {
        for (mode in LinuxSandboxContract.modes) {
            val source = DemoRepository().apply { localSandboxMode = mode }
            val preferences = AndroidDymPreferences.capture(source)
            val official = preferences.getJSONObject("groups").getJSONObject("official")
            check(official.getString("sandboxMode") == mode)
            check(official.keys().asSequence().toSet() == setOf(
                "streaming", "showReasoning", "expandActivities", "tokenSavingMode", "sandboxMode"))
            val plan = AndroidDymPreferences.parse(preferences)
            val target = DemoRepository()
            check(plan.warnings.isEmpty() && AndroidDymPreferences.apply(target, plan).isEmpty())
            check(target.localSandboxMode == mode)
        }
    }
    test("old official preferences without a sandbox choice preserve the target device setting") {
        val preferences = AndroidDymPreferences.capture(DemoRepository())
        preferences.getJSONObject("groups").getJSONObject("official").remove("sandboxMode")
        val plan = AndroidDymPreferences.parse(preferences)
        val target = DemoRepository().apply { localSandboxMode = LinuxSandboxContract.READ_ONLY }
        check(plan.warnings.isEmpty() && "official" in plan.groups)
        check(AndroidDymPreferences.apply(target, plan).isEmpty())
        check(target.localSandboxMode == LinuxSandboxContract.READ_ONLY)
    }
    test("invalid sandbox values skip only the official group and never change its execution preference") {
        for (invalid in listOf<Any>("unconfined", true, 1, JSONObject.NULL)) {
            val preferences = AndroidDymPreferences.capture(DemoRepository())
            preferences.getJSONObject("groups").getJSONObject("official").put("sandboxMode", invalid)
            val target = DemoRepository().apply { localSandboxMode = LinuxSandboxContract.READ_ONLY }
            val plan = AndroidDymPreferences.parse(preferences)
            check("official" !in plan.groups && "downloads" in plan.groups)
            check(plan.warnings.single().contains("轻引擎"))
            check(AndroidDymPreferences.apply(target, plan).isEmpty())
            check(target.localSandboxMode == LinuxSandboxContract.READ_ONLY)
        }
    }
    test("serialized verifier receipts and permission grants cannot enter official preference imports") {
        for (field in listOf("sandboxReceipt", "permissionGrants")) {
            val preferences = AndroidDymPreferences.capture(DemoRepository())
            preferences.getJSONObject("groups").getJSONObject("official").put(field, JSONObject().put("approved", true))
            val plan = AndroidDymPreferences.parse(preferences)
            check("official" !in plan.groups && "engine" in plan.groups)
            check(plan.warnings.single().contains("轻引擎"))
        }
        val plan = AndroidDymPreferences.parse(AndroidDymPreferences.capture(DemoRepository()))
        plan.groups.getValue("official").put("sandboxMode", "unconfined")
        val target = DemoRepository().apply { localSandboxMode = LinuxSandboxContract.READ_ONLY }
        check(AndroidDymPreferences.apply(target, plan).single().contains("轻引擎"))
        check(target.localSandboxMode == LinuxSandboxContract.READ_ONLY)
    }
    test("sandbox preferences restore for DSH online choices and report a real rejected persistence") {
        val source = DemoRepository().apply {
            localSandboxMode = LinuxSandboxContract.READ_ONLY
            engineMode = "dsh"
            connectionMode = "online"
        }
        val plan = AndroidDymPreferences.parse(AndroidDymPreferences.capture(source))
        val restored = DemoRepository()
        check(AndroidDymPreferences.apply(restored, plan).isEmpty())
        check(restored.engineMode == "dsh" && restored.connectionMode == "online")
        check(restored.localSandboxMode == LinuxSandboxContract.READ_ONLY)
        val refused = DemoRepository().apply { rejectSandboxRestore = true }
        val failures = AndroidDymPreferences.apply(refused, plan)
        check(failures.single().contains("轻引擎") && refused.localSandboxMode == LinuxSandboxContract.WORKSPACE_WRITE)
        check(refused.engineMode == "dsh" && refused.connectionMode == "online" && refused.downloadSource == "cdn")
    }
    test("earlier conflicting resource snapshots remain accessible and export the exact original bytes") {
        val installedRoot = File(root, "retained-conflicts").apply { mkdirs() }
        val localArchiveStore = DymArchiveStore(installedRoot)
        val retained = DymRetainedResourceStore(installedRoot)
        for ((name, content) in listOf("first" to "original skill version", "second" to "later skill version")) {
            val data = File(installedRoot, "incoming-$name/fixture").apply { mkdirs() }
            File(data, "SKILL.md").writeText(content)
            val archive = localArchiveStore.export(File(installedRoot, "$name.dym"), DymExportInput(
                directories = mapOf(".dsh/skills" to requireNotNull(data.parentFile)),
                pcPreferences = JSONObject().put("theme", name)))
            val staged = localArchiveStore.stageImport(archive, {})
            check(retained.retain(staged) == 1)
            localArchiveStore.discard(staged)
        }
        val snapshots = retained.snapshots()
        check(snapshots.size == 2 && snapshots.all { it.files == 2 && it.bytes > 0 })
        val originals = snapshots.map { retained.readSnapshot(it.id) }
        check(originals.map { it.pcPreferences?.getString("theme") }.toSet() == setOf("first", "second"))
        val first = originals.single { it.pcPreferences?.getString("theme") == "first" }
        val exported = localArchiveStore.export(File(installedRoot, "original-export.dym"), DymExportInput(
            directories = first.directories, pcPreferences = first.pcPreferences))
        val staged = localArchiveStore.stageImport(exported, {})
        check(staged.groupFiles.getValue("skills").single().file.readText() == "original skill version")
        check(staged.pcPreferences?.getString("theme") == "first")
        localArchiveStore.discard(staged)
    }
    test("retained-resource access rejects an unowned snapshot identifier") {
        val retained = DymRetainedResourceStore(File(root, "retained-boundary").apply { mkdirs() })
        check(runCatching { retained.readSnapshot("../../outside") }.isFailure)
        check(runCatching { retained.readSnapshot("snapshot-" + "0".repeat(36)) }.isFailure)
        check(retained.snapshots().isEmpty())
    }
    println("RESULT $passed/$passed real 7z, native compatibility, actual first-party none/zstd and resource files; Android process lease/UI/DSH task execution not exercised")
}
