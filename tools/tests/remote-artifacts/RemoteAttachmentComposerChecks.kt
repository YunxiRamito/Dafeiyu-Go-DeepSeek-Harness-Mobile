package com.dafeiyu.mobile.data

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

fun main(args: Array<String>) = runBlocking {
    val root = File(args[0]).apply { mkdirs() }
    val binding = RemoteDshScope("node-a", "group-a", "phone-a", "pc-a")
    var checks = 0
    suspend fun test(name: String, action: suspend (File) -> Unit) {
        val files = File(root, "case-${++checks}").apply { mkdirs() }
        action(files)
        println("PASS $checks $name")
    }
    class Fixture(private val files: File) : RemoteAttachmentComposer.Transport {
        var currentBinding = binding
        var selected: Pair<String?, String?> = "workspace-a" to "session-a"
        var pending = false
        var failUpload = false
        var afterUpload: (() -> Unit)? = null
        var uploadWait: CompletableDeferred<Unit>? = null
        val started = CompletableDeferred<Unit>()
        val actions = mutableListOf<String>()
        val calls = mutableListOf<String>()
        var failCompletionWrite = false
        val journal = object : RemoteDshJournal {
            val file = File(files, "pending.json")
            override fun read(): JSONObject? = file.takeIf { it.isFile }?.let { JSONObject(it.readText()) }
            override fun write(value: JSONObject) {
                if (failCompletionWrite && value.optBoolean("completed")) error("synthetic disk failure after durable PC admission")
                file.writeText(value.toString())
            }
        }
        fun composer() = RemoteAttachmentComposer({ journal }, this)
        fun source(id: String = "source-a") = File(files, "$id.txt").apply { writeText("actual selected file") }.let {
            RemoteAttachmentComposer.Source(id, it.name, it.length(), it)
        }
        override suspend fun state(): JSONObject = JSONObject().put("node_id", currentBinding.nodeId)
            .put("group_id", currentBinding.groupId).put("local_device", JSONObject().put("id", currentBinding.localDeviceId))
            .put("remote_device", JSONObject().put("id", currentBinding.remoteDeviceId).put("online", "online"))
            .put("pairing_status", "paired").put("server_connected", true)
        override suspend fun prepareSession(binding: RemoteDshScope): String { calls += "prepare"; return "session-a" }
        override suspend fun upload(binding: RemoteDshScope, action: String, session: String, source: RemoteAttachmentComposer.Source,
            progress: (RemoteAttachmentTransfer.Progress) -> Unit): JSONObject {
            calls += "upload"; actions += action; started.complete(Unit)
            uploadWait?.await()
            check(source.file.isFile && source.file.length() == source.size)
            if (failUpload) error("synthetic transport interruption")
            progress(RemoteAttachmentTransfer.Progress("upload-a", source.size, source.size))
            afterUpload?.invoke()
            return JSONObject().put("type", "file").put("uploadId", "upload-${source.id}")
        }
        override suspend fun send(binding: RemoteDshScope, workspace: String, session: String, text: String, parts: List<JSONObject>): Boolean {
            check(parts.isNotEmpty() && parts.all { it.getString("type") == "file" })
            check(selected == (workspace to session))
            calls += "prompt"
            return true
        }
        override fun pendingPrompt(): Boolean = pending
        override fun selection(): Pair<String?, String?> = selected
    }
    test("all selected uploads complete before the single prompt admission") { files ->
        val f = Fixture(files); val composer = f.composer()
        check(composer.send(binding, "workspace-a", "read selected files", listOf(f.source(), f.source("source-b"))) {})
        check(f.calls == listOf("prepare", "upload", "upload", "prompt"))
        check(f.journal.read()!!.getBoolean("completed"))
        check(!composer.state.value.busy && composer.state.value.receivedBytes == composer.state.value.totalBytes)
    }
    test("transport failure and a fresh composer reuse the durable upload action") { files ->
        val f = Fixture(files); val source = f.source(); f.failUpload = true
        check(runCatching { f.composer().send(binding, "workspace-a", "same draft", listOf(source)) {} }.isFailure)
        val first = f.actions.single(); check(!f.journal.read()!!.getBoolean("completed"))
        f.failUpload = false
        check(f.composer().send(binding, "workspace-a", "same draft", listOf(source)) {})
        check(f.actions == listOf(first, first) && f.calls.count { it == "prompt" } == 1)
    }
    test("a post-admission cache write error never reports an unaccepted prompt") { files ->
        val f = Fixture(files); f.failCompletionWrite = true
        check(f.composer().send(binding, "workspace-a", "accepted", listOf(f.source())) {})
        check(f.calls.count { it == "prompt" } == 1)
    }
    test("scope replacement during upload cannot submit a prompt to either computer") { files ->
        val f = Fixture(files); val composer = f.composer()
        f.afterUpload = { f.currentBinding = RemoteDshScope("node-a", "new-group", "phone-a", "other-pc") }
        check(runCatching { composer.send(binding, "workspace-a", "draft", listOf(f.source())) {} }.isFailure)
        check("prompt" !in f.calls && !composer.state.value.busy)
    }
    test("a selected conversation change refuses prompt admission after upload") { files ->
        val f = Fixture(files); f.afterUpload = { f.selected = "workspace-a" to "another-session" }
        check(runCatching { f.composer().send(binding, "workspace-a", "draft", listOf(f.source())) {} }.isFailure)
        check("prompt" !in f.calls)
    }
    test("cancel interrupts the active transfer and preserves the resumable composer action") { files ->
        coroutineScope {
            val f = Fixture(files); f.uploadWait = CompletableDeferred()
            val composer = f.composer()
            val result = async { composer.send(binding, "workspace-a", "draft", listOf(f.source())) {} }
            f.started.await(); composer.cancel()
            check(runCatching { result.await() }.isFailure)
            check("prompt" !in f.calls && !composer.state.value.busy && !f.journal.read()!!.getBoolean("completed"))
        }
    }
    test("a changed draft cannot replace an uncertain durable prompt") { files ->
        val f = Fixture(files); val source = f.source(); f.failUpload = true
        check(runCatching { f.composer().send(binding, "workspace-a", "old draft", listOf(source)) {} }.isFailure)
        val previous = f.journal.read()!!.getString("action")
        f.failUpload = false; f.pending = true
        check(runCatching { f.composer().send(binding, "workspace-a", "changed draft", listOf(source)) {} }.isFailure)
        check(f.journal.read()!!.getString("action") == previous && f.actions.size == 1 && "prompt" !in f.calls)
    }
    test("a separate explicit message gets a new action even with identical content") { files ->
        val f = Fixture(files); val source = f.source(); val composer = f.composer()
        check(composer.send(binding, "workspace-a", "draft", listOf(source)) {})
        check(composer.send(binding, "workspace-a", "draft", listOf(source)) {})
        check(f.actions.distinct().size == 2 && f.calls.count { it == "prompt" } == 2)
    }
    test("duplicate selected sources fail before starting any upload or prompt") { files ->
        val f = Fixture(files); val source = f.source()
        check(runCatching { f.composer().send(binding, "workspace-a", "draft", listOf(source, source)) {} }.isFailure)
        check(f.calls.isEmpty())
    }
    println("$checks PASS; production composer with durable real-file journal and synthetic transport; no Aries, Android or model calls.")
}
