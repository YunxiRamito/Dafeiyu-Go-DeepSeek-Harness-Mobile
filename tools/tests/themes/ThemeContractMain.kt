package com.dafeiyu.mobile.data

import com.dafeiyu.mobile.glasslab.LabDocument
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/** Real codec/downloader with an isolated local HTTP fixture; no production endpoint or paid call. */
fun main(arguments: Array<String>) {
    val output = File(arguments[0]).apply { mkdirs() }
    val checks = JSONArray()
    fun verify(name: String, block: () -> Unit) {
        try { block(); checks.put(JSONObject().put("name", name).put("passed", true)) }
        catch (failure: Throwable) { checks.put(JSONObject().put("name", name).put("passed", false).put("error", failure.toString())) }
    }
    fun reject(block: () -> Any?) {
        try { block() } catch (_: Exception) { return }
        error("Invalid theme input was accepted")
    }
    val document = LabDocument.defaults()
    val bytes = document.encode().toByteArray(Charsets.UTF_8)
    val digest = ThemeDocuments.sha256(bytes)
    val entry = ThemeMarketEntry("theme-one", "柔光", "真实主题", 1, digest, bytes.size.toLong(),
        "/api/android/themes/theme-one/file", document)
    val page = ThemeMarketPage(listOf(entry), 1, 1)
    val encoded = ThemeMarketCodec.encode(page)
    verify("shared_schema_complete_eight_profiles") {
        check(ThemeDocuments.decode(bytes) == document)
        check(document.light.size == 4 && document.dark.size == 4)
    }
    verify("bounded_file_read_rejects_overflow_and_invalid_utf8") {
        check(ThemeDocuments.read(ByteArrayInputStream(bytes)).contentEquals(bytes))
        reject { ThemeDocuments.read(ByteArrayInputStream(ByteArray(LabDocument.MAX_IMPORT_BYTES + 1))) }
        reject { ThemeDocuments.decode(byteArrayOf(0xC0.toByte(), 0x80.toByte())) }
    }
    verify("webview_context_excludes_preview_and_external_state") {
        val selected = document.copy(previewBackgroundImage = "reference", previewBackgroundLabel = "测试图片",
            previewBackgroundColors = listOf("#FF114477"))
        val web = ThemeDocuments.materialContext(selected)
        check(web.keys().asSequence().toSet() == setOf("schema", "renderer", "light", "dark"))
        check(web.getJSONObject("light").length() == 4 && web.getJSONObject("dark").length() == 4)
        check(!web.toString().contains("previewBackground"))
        check(!web.toString().contains("content://"))
        check(ThemeDocuments.validateMaterialContext(web.toString()).toString() == web.toString())
        reject { ThemeDocuments.validateMaterialContext(JSONObject(web.toString()).put("previewBackgroundImageData", "photo").toString()) }
        reject { ThemeDocuments.validateMaterialContext(JSONObject(web.toString()).put("script", "alert(1)").toString()) }
    }
    verify("webview_context_revalidates_pure_appearance_at_bridge_boundary") {
        val pure = ThemeDocuments.materialContext(document)
        check(ThemeDocuments.validateMaterialContext(pure.toString()).toString() == pure.toString())
        reject { ThemeDocuments.validateMaterialContext(JSONObject(pure.toString()).put("account", "external").toString()) }
        val malformed = JSONObject(pure.toString())
        malformed.getJSONObject("dark").getJSONObject("circle").getJSONObject("values").put("blurDp", -1)
        reject { ThemeDocuments.validateMaterialContext(malformed.toString()) }
        reject { ThemeDocuments.validateMaterialContext(" ".repeat(128 * 1024 + 1)) }
    }
    verify("market_complete_roundtrip_and_profile_validation") {
        check(ThemeMarketCodec.decode(encoded) == page)
        val json = JSONObject(String(encoded, Charsets.UTF_8))
        json.getJSONArray("items").getJSONObject(0).getJSONObject("preview").getJSONObject("light")
            .getJSONObject("circle").getJSONObject("values").put("shadowOpacity", 10)
        reject { ThemeMarketCodec.decode(json.toString().toByteArray()) }
    }
    verify("market_preview_can_omit_unused_types_without_changing_schema") {
        val json = JSONObject(String(encoded, Charsets.UTF_8))
        val preview = json.getJSONArray("items").getJSONObject(0).getJSONObject("preview")
        listOf("light", "dark").forEach { preview.getJSONObject(it).apply { remove("panel"); remove("regular") } }
        val accepted = ThemeMarketCodec.decode(json.toString().toByteArray()).items.single().preview
        check(accepted.light.size == 4 && accepted.dark.size == 4)
        check(accepted.current().values.size == document.current().values.size)
    }
    verify("market_paths_are_owned_and_pagination_is_validated") {
        listOf("https://example.com/theme", "../file", "/api/android/themes/other/file", "/api/android/themes/theme-one/file?token=x").forEach { path ->
            val json = JSONObject(String(encoded, Charsets.UTF_8))
            json.getJSONArray("items").getJSONObject(0).put("downloadPath", path)
            reject { ThemeMarketCodec.decode(json.toString().toByteArray()) }
        }
        listOf("page", "total", "pageSize").forEach { field ->
            val json = JSONObject(String(encoded, Charsets.UTF_8)).put(field, -1)
            reject { ThemeMarketCodec.decode(json.toString().toByteArray()) }
        }
        reject { ThemeDocuments.safeId("../escape") }
        reject { ThemeDocuments.safeId("default") }
        reject { ThemeDocuments.safeTitle("bad\nname") }
    }
    val requests = AtomicInteger()
    var serveBytes = bytes
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/api/android/themes") { exchange ->
        requests.incrementAndGet()
        val content = if (exchange.requestURI.path.endsWith("/file")) serveBytes else encoded
        val range = exchange.requestHeaders.getFirst("Range")?.let { Regex("bytes=(\\d+)-(\\d+)").matchEntire(it) }
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.responseHeaders.add("ETag", "\"theme-fixture-${ThemeDocuments.sha256(content)}\"")
        val payload = if (range != null) {
            val from = range.groupValues[1].toInt(); val to = range.groupValues[2].toInt()
            exchange.responseHeaders.add("Content-Range", "bytes $from-$to/${content.size}")
            content.copyOfRange(from, to + 1)
        } else content
        exchange.sendResponseHeaders(if (range != null) 206 else 200, payload.size.toLong())
        exchange.responseBody.use { it.write(payload) }
        exchange.close()
    }
    server.start()
    val fixtureClient = OkHttpClient()
    try {
        val origin = "http://127.0.0.1:${server.address.port}"
        val client = ThemeMarketClient(output, fixtureClient, origin, true)
        verify("real_local_market_catalog_exchange") { check(runBlocking { client.page(1) } == page) }
        verify("real_artifact_download_hash_and_full_schema") {
            val downloaded = runBlocking { client.download(entry) { } }
            check(downloaded.document == document && downloaded.sha256 == digest)
            check(File(output, "themes/downloads/$digest.json").readBytes().contentEquals(bytes))
        }
        verify("bad_artifact_digest_keeps_existing_download") {
            val badBytes = bytes.copyOf().also { it[it.lastIndex] = ' '.code.toByte() }
            serveBytes = badBytes
            val invalid = entry.copy(sha256 = "0".repeat(64))
            reject { runBlocking { client.download(invalid) { } } }
            check(File(output, "themes/downloads/$digest.json").readBytes().contentEquals(bytes))
            check(!File(output, "themes/downloads/${invalid.sha256}.json").exists())
            serveBytes = bytes
        }
        verify("offline_market_never_contacts_any_server") {
            val before = requests.get()
            val offline = ThemeMarketClient(output, fixtureClient, null, true)
            check(!offline.available)
            reject { runBlocking { offline.page(1) } }
            reject { runBlocking { offline.download(entry) { } } }
            check(before == requests.get())
        }
        verify("foreign_fixture_origin_rejected_before_transport") {
            val before = requests.get()
            reject { runBlocking { ThemeMarketClient(output, fixtureClient, "http://localhost:${server.address.port}", true).page(1) } }
            check(before == requests.get())
        }
    } finally {
        server.stop(0)
        fixtureClient.dispatcher.executorService.shutdownNow()
        fixtureClient.connectionPool.evictAll()
    }
    val failures = (0 until checks.length()).count { !checks.getJSONObject(it).getBoolean("passed") }
    val report = JSONObject().put("checks", checks.length()).put("failures", failures).put("passed", failures == 0)
        .put("scope", "shared theme schema, bounded import, market codec and actual ArtifactDownloader via local HTTP; no Android/UI/AtomicFile/TLS device claim")
        .put("backendRequests", 0).put("paidCalls", 0).put("fixtureRequests", requests.get()).put("results", checks)
    File(output, "report.json").writeText(report.toString(2))
    println(report.toString(2))
    check(failures == 0) { "$failures theme contract checks failed" }
}
