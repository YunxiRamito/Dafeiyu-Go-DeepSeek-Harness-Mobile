package com.dafeiyu.mobile.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

private const val ID = "10000000-0000-4000-8000-000000000001"
private const val OTHER_ID = "10000000-0000-4000-8000-000000000002"
private const val TOKEN = "fixture-admin-token-never-production"
private data class Recorded(val method: String, val url: String, val authorization: String?, val body: String)

/** Real cancellable OkHttp/loopback IO. No public writes, paid models or real credentials. */
fun main() = runBlocking {
    var passed = 0
    suspend fun check(name: String, action: suspend () -> Unit) {
        action(); passed++; println("PASS $name")
    }
    suspend fun rejects(action: suspend () -> Unit) { require(runCatching { action() }.isFailure) }
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val executor = Executors.newCachedThreadPool()
    server.executor = executor
    val handler = AtomicReference<(HttpExchange) -> Unit>()
    val count = AtomicInteger()
    val recorded = AtomicReference<Recorded>()
    server.createContext("/") { exchange -> count.incrementAndGet(); try { handler.get()(exchange) } finally { exchange.close() } }
    server.start()
    val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).addInterceptor { chain ->
        val request = chain.request()
        val buffer = okio.Buffer(); request.body?.writeTo(buffer)
        recorded.set(Recorded(request.method, request.url.toString(), request.header("Authorization"), buffer.readUtf8()))
        val mapped = request.url.newBuilder().scheme("http").host("127.0.0.1").port(server.address.port).build()
        chain.proceed(request.newBuilder().url(mapped).build())
    }.build()
    fun response(text: String, code: Int = 200) { handler.set { exchange ->
        val bytes = text.toByteArray(); exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(code, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
    } }
    fun page(field: String, total: String, rows: JSONArray, next: Int? = null, size: Int = rows.length()) =
        JSONObject().put(field, rows).put(total, size).put("nextOffset", next ?: JSONObject.NULL).toString()
    fun draft() = JSONObject().put("kind", "Announcement").put("title", "手机消息")
        .put("markdown", "完整正文").put("date", "2026-10-13").put("buttons", JSONArray())
    val admin = DeveloperAdminClient(http, fixture = true)
    try {
        check("Android management routes remain isolated and use transient Bearer authentication") {
            response(page("messages", "total", JSONArray().put(JSONObject().put("id", ID)), 1, 2))
            val result = admin.messages(TOKEN, 0)
            require(result.nextOffset == 1 && result.items.single().getString("id") == ID)
            require(recorded.get().let { it.method == "GET" && it.url == ComponentDownloadSources.BACKEND + "/api/admin/android/messages?offset=0&limit=10" && it.authorization == "Bearer $TOKEN" })
        }
        check("invalid token and path identifiers fail before any network request") {
            val before = count.get()
            rejects { admin.messages("bad token", 0) }; rejects { admin.publishMessage(TOKEN, "../messages") }
            rejects { admin.messages(TOKEN, -1) }; require(count.get() == before)
        }
        check("draft save serializes the real contract and checks exact updated message identity") {
            response(JSONObject().put("id", ID).put("state", "Draft").toString())
            admin.saveMessage(TOKEN, ID, draft())
            val request = recorded.get(); require(request.method == "PUT" && request.url.endsWith("/android/messages/$ID"))
            require(JSONObject(request.body).getString("markdown") == "完整正文")
            response(JSONObject().put("id", OTHER_ID).put("state", "Draft").toString())
            rejects { admin.saveMessage(TOKEN, ID, draft()) }
        }
        check("published state cannot succeed on a stale or different message receipt") {
            response(JSONObject().put("id", ID).put("state", "Draft").toString())
            rejects { admin.publishMessage(TOKEN, ID) }
            response(JSONObject().put("id", OTHER_ID).put("state", "Published").toString())
            rejects { admin.publishMessage(TOKEN, ID) }
        }
        check("message validation rejects incompatible button targets, scripts and invalid dates") {
            val notification = draft().put("kind", "Notification")
            fun button(value: JSONObject) = notification.toString().let { JSONObject(it).put("buttons", JSONArray().put(value)) }
            rejects { DeveloperAdminClient.validateMessage(button(JSONObject().put("label", "查看").put("action", "Url").put("url", "https://example.com").put("page", "Home"))) }
            rejects { DeveloperAdminClient.validateMessage(button(JSONObject().put("label", "关闭").put("action", "Dismiss").put("url", "https://example.com"))) }
            rejects { DeveloperAdminClient.validateMessage(button(JSONObject().put("label", "执行").put("action", "Url").put("url", "https://example.com").put("command", "run"))) }
            rejects { DeveloperAdminClient.validateMessage(draft().put("date", "2026-02-30")) }
            rejects { DeveloperAdminClient.validateMessage(draft().put("expiresAt", "2000-01-01T00:00:00Z")) }
            DeveloperAdminClient.validateMessage(button(JSONObject().put("label", "设置").put("action", "Settings").put("page", "Models")))
        }
        check("pagination refuses repeated cursors and accepts exact ten-row server bounds") {
            val rows = JSONArray().put(JSONObject().put("id", ID))
            response(page("messages", "total", rows, 0, 2)); rejects { admin.messages(TOKEN, 0) }
            response(page("messages", "total", rows, 2, 4)); rejects { admin.messages(TOKEN, 0) }
            response(page("messages", "total", JSONArray(List(11) { JSONObject().put("id", ID) }), null, 11))
            rejects { admin.messages(TOKEN, 0) }
        }
        check("long feedback retains complete body within the real server budget") {
            val body = "x".repeat(1100000)
            response(page("feedback", "totalCount", JSONArray().put(JSONObject().put("id", ID).put("body", body))))
            val result = admin.feedback(TOKEN, 0)
            require(result.items.single().getString("body") == body)
            require(recorded.get().url.contains("/api/admin/android/feedback?"))
        }
        check("feedback mutations require the exact status/category persistence receipt") {
            response(JSONObject().put("id", ID).put("status", "Completed").put("category", "Bug").toString())
            admin.updateFeedback(TOKEN, ID, "Completed", "Bug")
            require(recorded.get().method == "PATCH" && JSONObject(recorded.get().body).getString("status") == "Completed")
            response(JSONObject().put("id", ID).put("status", "Submitted").put("category", "Bug").toString())
            rejects { admin.updateFeedback(TOKEN, ID, "Completed", "Bug") }
        }
        check("supplement pages never forward the admin token to public attachment endpoints") {
            response(JSONObject().put("supplements", JSONArray()).put("nextOffset", JSONObject.NULL).toString())
            admin.feedbackSupplements(TOKEN, ID, 10)
            require(recorded.get().authorization == null && recorded.get().url.contains("/api/android/feedback/$ID/supplements?offset=10"))
        }
        check("ban records use independent validated pagination and full server duration range") {
            response(page("bans", "totalCount", JSONArray().put(JSONObject().put("id", ID))))
            require(admin.bans(TOKEN, 0).items.size == 1)
            response(JSONObject().put("id", OTHER_ID).toString())
            admin.ban(TOKEN, ID, "重复提交", 876000, false)
            require(JSONObject(recorded.get().body).getInt("durationHours") == 876000)
        }
        check("diagnostic logs enforce one MiB and malformed UTF8 is rejected") {
            response("x".repeat(1024 * 1024 + 1)); rejects { admin.readLog(TOKEN, ID, OTHER_ID) }
            rejects { DeveloperAdminClient.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
        }
        check("redirects are rejected without following a credential-bearing request") {
            handler.set { exchange -> exchange.responseHeaders.set("Location", "http://127.0.0.1:${server.address.port}/forbidden"); exchange.sendResponseHeaders(302, -1) }
            val before = count.get(); rejects { admin.messages(TOKEN, 0) }; require(count.get() == before + 1)
        }
        check("server error bodies are not exposed as credential-containing UI failures") {
            response("secret-upstream-token=$TOKEN", 401)
            val failure = runCatching { admin.messages(TOKEN, 0) }.exceptionOrNull()
            require(failure is IOException && !failure.message.orEmpty().contains(TOKEN) && failure.message.orEmpty().contains("身份"))
        }
        check("cancellation closes the real pending OkHttp call and allows a later request") {
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            handler.set { exchange -> entered.countDown(); if (release.await(5, TimeUnit.SECONDS)) exchange.sendResponseHeaders(204, -1) }
            val job = async { admin.messages(TOKEN, 0) }
            try {
                require(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
                withTimeout(1500) { job.cancel(); job.join() }; require(job.isCancelled)
            } finally { release.countDown() }
            response(page("messages", "total", JSONArray())); require(admin.messages(TOKEN, 0).total == 0)
        }
        val recommendation = MobileRecommendationAdmin(http)
        val feed = JSONObject().put("schemaVersion", 1).put("platform", "android").put("items", JSONArray()).toString()
        fun sha(text: String): String {
            val bytes = text.toByteArray()
            return MessageDigest.getInstance("SHA-1").digest("blob ${bytes.size}\u0000".toByteArray() + bytes).joinToString("") { "%02x".format(it) }
        }
        fun github(text: String, digest: String = sha(text)) = JSONObject().put("type", "file").put("encoding", "base64")
            .put("size", text.toByteArray().size).put("sha", digest).put("content", Base64.getEncoder().encodeToString(text.toByteArray())).toString()
        check("mobile GitHub reads pin main and verify actual Git blob size/hash") {
            response(github(feed)); val result = recommendation.read(true, TOKEN)
            require(result.sha == sha(feed) && result.text == feed)
            require(recorded.get().url == "https://api.github.com/repos/YunxiRamito/Dafeiyu-Go-DeepSeek-Harness-Mobile/contents/recommendations/mobile-skills.json?ref=main")
            response(github(feed, "0".repeat(40))); rejects { recommendation.read(true, TOKEN) }
        }
        check("recommendation writes preserve old blob CAS and verify the returned new hash") {
            val before = MobileRecommendationDocument(false, sha(feed), feed)
            val next = JSONObject(feed).toString(2)
            response(JSONObject().put("content", JSONObject().put("sha", sha(next))).toString())
            require(recommendation.save(before, next, TOKEN) == sha(next))
            val payload = JSONObject(recorded.get().body)
            require(payload.getString("sha") == before.sha && payload.getString("branch") == "main")
            require(String(Base64.getDecoder().decode(payload.getString("content"))) == next)
            require(recorded.get().url.endsWith("/recommendations/mobile-dsh-plugins.json"))
            response(JSONObject().put("content", JSONObject().put("sha", "0".repeat(40))).toString())
            rejects { recommendation.save(before, next, TOKEN) }
        }
        check("invalid channel schemas and changed GitHub documents fail without fake publication") {
            val before = MobileRecommendationDocument(true, sha(feed), feed)
            val countBefore = count.get()
            rejects { recommendation.save(before, JSONObject(feed).put("platform", "windows").toString(), TOKEN) }
            require(count.get() == countBefore)
            response("{\"message\":\"old version\"}", 409)
            rejects { recommendation.save(before, feed, TOKEN) }
        }
        println("RESULT $passed/$passed production admin/GitHub clients + real loopback OkHttp; public writes=0 paid=0; TLS, live admin identity and Android UI pending")
    } finally {
        http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll(); server.stop(0); executor.shutdownNow()
    }
}
