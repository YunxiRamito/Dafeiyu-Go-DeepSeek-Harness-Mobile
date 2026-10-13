package com.dafeiyu.mobile.data

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import okio.buffer
import okio.sink
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import okhttp3.OkHttpClient

private data class Reply(val code: Int = 200, val bytes: ByteArray, val type: String = "application/json", val retryAfter: String? = null,
    val sha256: String? = null, val ranged: Boolean = false)
private data class Seen(val method: String, val path: String, val type: String?, val bytes: ByteArray, val range: String? = null)
private val id = UUID.fromString("d1823f30-f52f-4672-bf86-a5190b7a59e2")
private val imageId = UUID.fromString("2c4d9e89-b90f-45a5-9943-e562185927a0")
private val installation = UUID.fromString("3db17528-e80f-4f43-a317-7a3ca7c71425").toString()
private val machine = "android-fixture+device"
private const val prefix = "/api/android/feedback"

private fun banJson() = JSONObject().put("banned", false).put("reason", JSONObject.NULL)
    .put("expiresAt", JSONObject.NULL).put("permanent", false)

private fun imageJson(bytes: Int, width: Int = 2, height: Int = 3) = JSONObject().put("id", imageId.toString())
    .put("url", "$prefix/images/$imageId").put("bytes", bytes).put("width", width).put("height", height)

private fun itemJson(images: JSONArray = JSONArray()) = JSONObject().put("id", id.toString()).put("number", 91)
    .put("category", "Suggestion").put("status", "Submitted").put("body", "Title\n\nA real fixture body")
    .put("launcherVersion", "0.1.25").put("createdAt", "2026-10-12T08:00:00Z").put("updatedAt", "2026-10-12T09:00:00Z")
    .put("reply", JSONObject.NULL).put("mine", true).put("upvoteCount", 2).put("hasUpvoted", false)
    .put("hasLogs", true).put("supplementCount", 0).put("nextSupplementOffset", JSONObject.NULL)
    .put("supplements", JSONArray()).put("images", images)

private fun pageJson(rows: JSONArray = JSONArray().put(itemJson()), next: Int? = null) = JSONObject().put("feedback", rows)
    .put("nextOffset", next ?: JSONObject.NULL).put("totalCount", 21).put("banStatus", banJson())

private fun eventJson(sequence: Long) = JSONObject().put("sequence", sequence).put("feedbackId", id.toString())
    .put("kind", "reply").put("status", JSONObject.NULL).put("title", "反馈有开发者回复")
    .put("detail", "打开反馈查看内容。").put("createdAt", "2026-10-13T01:02:03Z")

private fun eventPage(cursor: Long, events: JSONArray = JSONArray(), more: Boolean = false) =
    JSONObject().put("events", events).put("cursor", cursor).put("hasMore", more)

fun main(args: Array<String>) = runBlocking {
    val fixtureRoot = File(args.single()).apply { mkdirs() }
    val seen = CopyOnWriteArrayList<Seen>()
    val rangedActive = AtomicInteger()
    val rangedMaximum = AtomicInteger()
    val reply = AtomicReference(Reply(bytes = pageJson().toString().toByteArray()))
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "feedback-http-fixture").apply { isDaemon = true } }
    server.executor = executor
    server.createContext("/") { exchange ->
        val request = Seen(exchange.requestMethod, exchange.requestURI.toASCIIString(), exchange.requestHeaders.getFirst("Content-Type"),
            exchange.requestBody.readBytes(), exchange.requestHeaders.getFirst("Range"))
        seen.add(request)
        val current = if (exchange.requestURI.path.startsWith(prefix)) reply.get() else Reply(404, ByteArray(0))
        exchange.responseHeaders.set("Content-Type", current.type)
        current.retryAfter?.let { exchange.responseHeaders.set("Retry-After", it) }
        current.sha256?.let {
            exchange.responseHeaders.set("X-Content-SHA256", it)
            exchange.responseHeaders.set("ETag", "\"sha256-$it\"")
        }
        if (current.ranged) exchange.responseHeaders.set("Accept-Ranges", "bytes")
        if (request.method == "HEAD") {
            exchange.responseHeaders.set("Content-Length", current.bytes.size.toString())
            exchange.sendResponseHeaders(current.code, -1)
        } else if (current.ranged && request.range != null && current.code == 200) {
            val range = Regex("bytes=(\\d+)-(\\d+)").matchEntire(request.range) ?: error("Fixture Range invalid")
            val start = range.groupValues[1].toInt(); val end = range.groupValues[2].toInt()
            check(start <= end && end < current.bytes.size)
            val active = rangedActive.incrementAndGet()
            rangedMaximum.accumulateAndGet(active, ::maxOf)
            try {
                Thread.sleep(75)
                val bytes = current.bytes.copyOfRange(start, end + 1)
                exchange.responseHeaders.set("Content-Range", "bytes $start-$end/${current.bytes.size}")
                exchange.sendResponseHeaders(206, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally { rangedActive.decrementAndGet() }
        } else {
            exchange.sendResponseHeaders(current.code, current.bytes.size.toLong())
            exchange.responseBody.use { it.write(current.bytes) }
        }
        exchange.close()
    }
    server.start()
    val loopback = "http://127.0.0.1:${server.address.port}"
    val client = DeveloperCenterClient(DeveloperCenterClient.Config(), DeveloperCenterClient.FeedbackTransport { request ->
        val connection = URI("$loopback${request.path}").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = request.method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 2000; connection.readTimeout = 2000
        try {
            request.body?.let { body ->
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", body.contentType().toString())
                connection.setFixedLengthStreamingMode(body.contentLength())
                connection.outputStream.sink().buffer().use(body::writeTo)
            }
            val status = connection.responseCode
            val bytes = (if (request.method == "HEAD") null else if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes() } ?: ByteArray(0)
            DeveloperCenterClient.FeedbackHttpResponse(status, bytes, connection.contentType,
                connection.getHeaderField("Retry-After")?.toIntOrNull(), connection.getHeaderField("X-Content-SHA256"),
                connection.getHeaderField("Content-Length")?.toLongOrNull(), connection.getHeaderField("ETag"))
        } finally { connection.disconnect() }
    })
    var passed = 0
    suspend fun checkCase(name: String, block: suspend () -> Unit) {
        block(); passed++; println("PASS $name")
    }
    fun respond(json: JSONObject, code: Int = 200) { reply.set(Reply(code, json.toString().toByteArray(Charsets.UTF_8))) }
    suspend fun page(mine: Boolean = true, category: String? = null, status: String? = null) =
        client.getFeedback(machine, installation, mine = mine, category = category, status = status)
    try {
        checkCase("public Android list uses support sort, default ten and escaped anonymous machine ID") {
            respond(pageJson()); val value = page(false).getOrThrow(); check(value.feedback.single().mine)
            check(seen.last().path == "$prefix?machineId=android-fixture%2Bdevice&offset=0&limit=10&sort=support")
            check(value.banStatus?.banned == false)
        }
        checkCase("mine category/status filters reach server query") {
            page(true, "Bug", "Processing").getOrThrow()
            check(seen.last().path.endsWith("&mine=true&offset=0&limit=10&sort=updated&category=Bug&status=Processing"))
        }
        checkCase("invalid filters, identities and offsets issue no HTTP request") {
            val before = seen.size
            check(page(category = "other").isFailure); check(page(status = "unknown").isFailure)
            check(client.getFeedback(machine, installation, offset = -1).isFailure)
            check(client.getFeedback(machine, "bad-uuid").isFailure)
            check(client.getFeedback(machine, installation, limit = 21).isFailure)
            check(seen.size == before)
        }
        checkCase("page and next-offset bounds reject malformed server responses") {
            respond(pageJson(JSONArray().apply { repeat(11) { put(itemJson()) } })); check(page().isFailure)
            respond(pageJson(next = 0)); check(page().isFailure)
        }
        checkCase("non-JSON and oversized responses remain real failures") {
            reply.set(Reply(bytes = "broken".toByteArray())); check(page().isFailure)
            reply.set(Reply(bytes = "{}".toByteArray(), type = "text/html")); check(page().isFailure)
            reply.set(Reply(bytes = ByteArray(16 * 1024 * 1024 + 1))); check(page().isFailure)
        }
        checkCase("text submission uses Android JSON endpoint and does not leak a title field") {
            respond(itemJson(), 201)
            client.postFeedback("Suggestion", "My title", "My body", installation, "0.1.25", machine).getOrThrow()
            val last = seen.last(); val json = JSONObject(last.bytes.toString(Charsets.UTF_8))
            check(last.path == prefix && last.method == "POST" && last.type?.startsWith("application/json") == true)
            check(json.getString("body") == "My title\n\nMy body" && !json.has("title"))
            check(json.getString("machineId") == machine && json.getString("category") == "Suggestion")
        }
        checkCase("created response validates owner, allowed status and complete body") {
            respond(itemJson().put("mine", false), 201)
            check(client.postFeedback("Suggestion", "Title", "Body", installation, "0.1.25", machine).isFailure)
            respond(itemJson().put("status", "Fabricated"), 201)
            check(client.postFeedback("Suggestion", "Title", "Body", installation, "0.1.25", machine).isFailure)
            respond(itemJson().put("body", "x".repeat(12001)), 201)
            check(client.postFeedback("Suggestion", "Title", "Body", installation, "0.1.25", machine).isFailure)
        }
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "jpeg", it) }.toByteArray()
        val upload = DeveloperCenterClient.FeedbackUploadImage.from("fixture.png", png)
        val logsRoot = File(fixtureRoot, "own-app-logs").apply { mkdirs() }
        File(logsRoot, "launcher-errors.log").writeText("api_key=sk-secretvalue123 Bearer abc.def\n/data/user/0/com.test/files/secret.txt\n")
        val diagnostics = FeedbackDiagnostics.captureOwnedDirectory(logsRoot, "0.1.25", "own app diagnostic summary")
        checkCase("multipart submission matches PC file/log names and exact log MIME") {
            respond(itemJson(), 201)
            client.postFeedback("Bug", "Failure", "Description", installation, "0.1.25", machine, listOf(upload), diagnostics).getOrThrow()
            val last = seen.last(); val text = last.bytes.toString(Charsets.ISO_8859_1)
            check(last.path == "$prefix/with-images" && last.type?.startsWith("multipart/form-data; boundary=") == true)
            check(text.contains("name=\"files\"; filename=\"fixture.png\"") && text.contains("Content-Type: image/png"))
            check(text.contains("name=\"diagnostics\"; filename=\"launcher-diagnostics.json\"\r\nContent-Type: application/json\r\n"))
            check(text.contains("name=\"machineId\"") && text.contains(machine))
        }
        checkCase("logs-only uses multipart and retains Android channel") {
            client.postFeedback("Bug", "Failure", "Description", installation, "0.1.25", machine, diagnostics = diagnostics).getOrThrow()
            check(seen.last().path == "$prefix/with-images" && !seen.last().bytes.toString(Charsets.UTF_8).contains("name=\"files\""))
        }
        checkCase("oversized body, more than five images and malformed log JSON fail before transport") {
            val before = seen.size
            check(client.postFeedback("Bug", "Title", "x".repeat(12000), installation, "0.1.25", machine).isFailure)
            check(client.postFeedback("Bug", "Title", "Body", installation, "0.1.25", machine, List(6) { upload }).isFailure)
            check(client.postFeedback("Bug", "Title", "Body", installation, "0.1.25", machine, diagnostics = "{}".toByteArray()).isFailure)
            check(seen.size == before)
        }
        checkCase("image bytes are immutable after caller mutation") {
            val mutable = png.copyOf(); val image = DeveloperCenterClient.FeedbackUploadImage.from("image.png", mutable)
            mutable[0] = 0; val copy = image.copyBytes(); copy[0] = 0
            check(image.copyBytes().contentEquals(png))
        }
        checkCase("invalid media, extension, dimensions and animated PNG are rejected before decode") {
            check(runCatching { DeveloperCenterClient.FeedbackUploadImage.from("image.gif", png) }.isFailure)
            check(runCatching { DeveloperCenterClient.FeedbackUploadImage.from("bad.png", ByteArray(20)) }.isFailure)
            val huge = png.copyOf(); huge[16] = 0; huge[17] = 0; huge[18] = 50; huge[19] = 0
            check(runCatching { DeveloperCenterClient.FeedbackUploadImage.from("image.png", huge) }.isFailure)
            val animated = png.copyOf(); "acTL".toByteArray().copyInto(animated, 37)
            check(runCatching { DeveloperCenterClient.FeedbackUploadImage.from("image.png", animated) }.isFailure)
        }
        checkCase("public list parses Android-only image metadata and rejects PC URLs") {
            respond(pageJson(JSONArray().put(itemJson(JSONArray().put(imageJson(jpeg.size))))))
            val image = page().getOrThrow().feedback.single().images.single(); check(image.id == imageId)
            respond(pageJson(JSONArray().put(itemJson(JSONArray().put(imageJson(jpeg.size).put("url", "/api/feedback/images/$imageId"))))))
            check(page().isFailure)
        }
        checkCase("external URLs and duplicate image IDs are rejected") {
            respond(pageJson(JSONArray().put(itemJson(JSONArray().put(imageJson(jpeg.size).put("url", "https://evil.test/image.jpg")))))); check(page().isFailure)
            respond(pageJson(JSONArray().put(itemJson(JSONArray().put(imageJson(jpeg.size)).put(imageJson(jpeg.size)))))); check(page().isFailure)
        }
        checkCase("JPEG on-demand read checks MIME, byte length and dimensions") {
            val image = DeveloperCenterClient.FeedbackImage(imageId, "$prefix/images/$imageId", 2, 3, jpeg.size)
            reply.set(Reply(bytes = jpeg, type = "image/jpeg")); check(client.getFeedbackImage(image).getOrThrow().contentEquals(jpeg))
            check(seen.last().path == "$prefix/images/$imageId")
            check(client.getFeedbackImage(image.copy(bytes = jpeg.size + 1)).isFailure)
            check(client.getFeedbackImage(image.copy(width = 3)).isFailure)
            reply.set(Reply(bytes = jpeg, type = "text/plain")); check(client.getFeedbackImage(image).isFailure)
        }
        checkCase("HEAD reads zero image bytes and requires pinned metadata contract") {
            val hash = MessageDigest.getInstance("SHA-256").digest(jpeg).joinToString("") { "%02x".format(it) }
            val image = DeveloperCenterClient.FeedbackImage(imageId, "$prefix/images/$imageId", 2, 3, jpeg.size)
            reply.set(Reply(bytes = jpeg, type = "image/jpeg", sha256 = hash, ranged = true))
            val receipt = client.getFeedbackImageReceipt(image).getOrThrow()
            check(receipt.sha256 == hash && receipt.bytes == jpeg.size.toLong() && receipt.etag == "\"sha256-$hash\"")
            check(seen.last().method == "HEAD")
            reply.set(Reply(bytes = jpeg, type = "image/jpeg"))
            check(client.getFeedbackImageReceipt(image).isFailure)
            reply.set(Reply(bytes = jpeg, type = "image/jpeg", sha256 = "wrong"))
            check(client.getFeedbackImageReceipt(image).isFailure)
        }
        val largeBitmap = BufferedImage(1600, 1600, BufferedImage.TYPE_INT_RGB)
        val random = java.util.Random(91350)
        for (y in 0 until 1600) for (x in 0 until 1600) largeBitmap.setRGB(x, y, random.nextInt(0x1000000))
        val largeJpeg = ByteArrayOutputStream().also { ImageIO.write(largeBitmap, "jpeg", it) }.toByteArray()
        check(largeJpeg.size > 1024 * 1024 && largeJpeg.size <= DeveloperCenterClient.FEEDBACK_MAX_IMAGE_BYTES)
        val largeHash = MessageDigest.getInstance("SHA-256").digest(largeJpeg).joinToString("") { "%02x".format(it) }
        val largeImage = DeveloperCenterClient.FeedbackImage(imageId, "$prefix/images/$imageId", 1600, 1600, largeJpeg.size)
        val downloadsRoot = File(fixtureRoot, "managed-downloads").apply { mkdirs() }
        val downloader = ArtifactDownloader(downloadsRoot, { OkHttpClient.Builder().followRedirects(false).build() })
        checkCase("large feedback JPEG uses real four-way Range download and checksum-before-commit") {
            reply.set(Reply(bytes = largeJpeg, type = "image/jpeg", sha256 = largeHash, ranged = true))
            val threads = mutableSetOf<Int>()
            val target = File(downloadsRoot, "large.jpg")
            val bytes = client.getFeedbackImageForFixture(largeImage, target) { request ->
                check(request.expectedSize == largeJpeg.size.toLong() && request.checksum.value == largeHash)
                downloader.download(request.copy(urls = listOf("$loopback${largeImage.url}"), allowHttpForTests = true)) {
                    synchronized(threads) { threads.add(it.threads) }
                }
            }.getOrThrow()
            check(bytes.contentEquals(largeJpeg) && target.isFile && threads.contains(4))
            check(rangedMaximum.get() >= 4)
            check(client.getFeedbackImage(largeImage).isFailure)
        }
        checkCase("managed image cache remains bound to authenticated hash") {
            val before = seen.size
            val bytes = client.getFeedbackImageForFixture(largeImage, File(downloadsRoot, "large.jpg")) { request ->
                downloader.download(request.copy(urls = listOf("$loopback${largeImage.url}"), allowHttpForTests = true))
            }.getOrThrow()
            check(bytes.contentEquals(largeJpeg) && seen.size == before + 1 && seen.last().method == "HEAD")
        }
        checkCase("image digest mismatch never commits downloaded bytes") {
            val changed = largeJpeg.copyOf().apply { this[lastIndex - 10] = (this[lastIndex - 10].toInt() xor 1).toByte() }
            reply.set(Reply(bytes = changed, type = "image/jpeg", sha256 = largeHash, ranged = true))
            val target = File(downloadsRoot, "wrong-digest.jpg")
            val result = client.getFeedbackImageForFixture(largeImage, target) { request ->
                downloader.download(request.copy(urls = listOf("$loopback${largeImage.url}"), allowHttpForTests = true))
            }
            check(result.isFailure && !target.exists())
        }
        checkCase("missing Range uses bounded single stream with a visible truthful reason") {
            reply.set(Reply(bytes = largeJpeg, type = "image/jpeg", sha256 = largeHash, ranged = false))
            var result: ArtifactDownloadResult? = null
            val target = File(downloadsRoot, "no-range.jpg")
            client.getFeedbackImageForFixture(largeImage, target) { request ->
                downloader.download(request.copy(urls = listOf("$loopback${largeImage.url}"), allowHttpForTests = true)).also { result = it }
            }.getOrThrow()
            check(result?.threads == 1 && result?.singleStreamReason?.contains("Range") == true)
        }
        checkCase("supplement posts use full owned feedback response and real attachment endpoint") {
            respond(itemJson())
            client.postFeedbackSupplement(id.toString(), installation, machine, "More details").getOrThrow()
            check(seen.last().path == "$prefix/$id/supplements")
            client.postFeedbackSupplement(id.toString(), installation, machine, "More details", listOf(upload), diagnostics).getOrThrow()
            check(seen.last().path == "$prefix/$id/supplements/with-images")
            respond(itemJson().put("mine", false)); check(client.postFeedbackSupplement(id.toString(), installation, machine, "Details").isFailure)
        }
        checkCase("supplement paging, developer replies and images parse and validate offsets") {
            val supplement = JSONObject().put("id", UUID.randomUUID().toString()).put("body", "Developer reply")
                .put("createdAt", "2026-10-12T09:00:00Z").put("isDeveloperReply", true).put("hasLogs", true)
                .put("images", JSONArray().put(imageJson(jpeg.size)))
            respond(JSONObject().put("supplements", JSONArray().put(supplement)).put("nextOffset", 60))
            val result = client.getFeedbackSupplements(id.toString(), 10).getOrThrow()
            check(result.supplements.single().isDeveloperReply && result.supplements.single().images.size == 1)
            check(seen.last().path == "$prefix/$id/supplements?offset=10")
            respond(JSONObject().put("supplements", JSONArray()).put("nextOffset", 10))
            check(client.getFeedbackSupplements(id.toString(), 10).isFailure)
        }
        checkCase("support and cancellation reach independent route with idempotent result checks") {
            respond(JSONObject().put("upvoteCount", 3).put("hasUpvoted", true))
            client.postFeedbackUpvote(id.toString(), machine).getOrThrow(); check(seen.last().method == "POST")
            respond(JSONObject().put("upvoteCount", 2).put("hasUpvoted", false))
            client.deleteFeedbackUpvote(id.toString(), machine).getOrThrow()
            check(seen.last().method == "DELETE" && seen.last().path == "$prefix/$id/upvotes?machineId=android-fixture%2Bdevice")
            respond(JSONObject().put("upvoteCount", 0).put("hasUpvoted", true)); check(client.postFeedbackUpvote(id.toString(), machine).isFailure)
        }
        checkCase("ban status and denied mutation retain reason and expiry") {
            val denied = JSONObject().put("banned", true).put("reason", "Fixture policy").put("permanent", false)
                .put("expiresAt", "2026-10-13T09:00:00Z")
            respond(denied); check(client.getFeedbackBanStatus(machine).getOrThrow().reason == "Fixture policy")
            check(seen.last().path == "$prefix/ban-status?machineId=android-fixture%2Bdevice")
            respond(denied, 403)
            val error = client.postFeedbackUpvote(id.toString(), machine).exceptionOrNull()
            check(error is DeveloperCenterClient.FeedbackBannedException && error.banStatus.reason == "Fixture policy")
        }
        checkCase("rate-limit, conflict and missing ownership return truthful HTTP failures without retry") {
            val before = seen.size
            reply.set(Reply(429, ByteArray(0), retryAfter = "30"))
            check(client.postFeedbackUpvote(id.toString(), machine).exceptionOrNull()?.message?.contains("30 秒") == true)
            respond(JSONObject().put("error", "This feedback is closed."), 409)
            check(client.postFeedbackSupplement(id.toString(), installation, machine, "Details").exceptionOrNull()?.message == "This feedback is closed.")
            reply.set(Reply(404, ByteArray(0))); check(client.postFeedbackSupplement(id.toString(), installation, machine, "Details").isFailure)
            check(seen.size == before + 3)
        }
        checkCase("production endpoint pin rules cannot be loosened by fixture configuration") {
            val unsafe = DeveloperCenterClient(DeveloperCenterClient.Config(baseUrl = loopback))
            val error = unsafe.getFeedbackBanStatus(machine).exceptionOrNull()
            check(error is DeveloperCenterClient.DeveloperCenterException && error.kind == DeveloperCenterClient.ErrorKind.ENDPOINT_REJECTED)
        }
        checkCase("diagnostics redact keys, authorization, private paths and never scan arbitrary files") {
            val text = diagnostics.toString(Charsets.UTF_8)
            check(!text.contains("secretvalue123") && !text.contains("abc.def") && !text.contains("/data/user/0"))
            File(logsRoot, "credentials.json").writeText("DO-NOT-COLLECT-APIKEY")
            check(!FeedbackDiagnostics.captureOwnedDirectory(logsRoot, "0.1.25", "Summary").toString(Charsets.UTF_8).contains("DO-NOT-COLLECT"))
            val redact = FeedbackDiagnostics.redact("refresh_token=abcd https://user:pass@example.test/?key=secret sk-key1234567 device_id=id123")
            check(!redact.contains("abcd") && !redact.contains("user:pass") && !redact.contains("=secret") && !redact.contains("id123"))
        }
        checkCase("empty diagnostic store emits only own-app summary with compatible schema") {
            val captured = FeedbackDiagnostics.captureOwnedDirectory(File(fixtureRoot, "empty"), "0.1.25", "Android app summary")
            FeedbackDiagnostics.validate(captured)
            check(JSONObject(captured.toString(Charsets.UTF_8)).getJSONArray("entries").length() == 1)
            check(runCatching { FeedbackDiagnostics.validate(ByteArray(1024 * 1024 + 1)) }.isFailure)
        }
        checkCase("diagnostic duplicate/unknown names and excessive text are rejected") {
            val root = JSONObject(diagnostics.toString(Charsets.UTF_8))
            root.put("entries", JSONArray().put(JSONObject().put("name", "secret.config").put("text", "Secret")))
            check(runCatching { FeedbackDiagnostics.validate(root.toString().toByteArray()) }.isFailure)
            root.put("entries", JSONArray().put(JSONObject().put("name", "launcher.log").put("text", "x".repeat(128 * 1024 + 1))))
            check(runCatching { FeedbackDiagnostics.validate(root.toString().toByteArray()) }.isFailure)
        }
        checkCase("UI ownership policy excludes other authors and closed feedback") {
            respond(pageJson()); val item = page().getOrThrow().feedback.single()
            check(item.canAddSupplement && !item.copy(mine = false).canAddSupplement)
            check(!item.copy(status = "Completed").canAddSupplement && !item.copy(status = "Deferred").canAddSupplement)
        }
        checkCase("feedback notification baseline uses only the Android owner route with no historical events") {
            respond(eventPage(0))
            val baseline = client.getFeedbackNotifications(machine).getOrThrow()
            check(baseline.cursor == 0L && baseline.events.isEmpty() && !baseline.hasMore)
            check(seen.last().path == "$prefix/events?machineId=android-fixture%2Bdevice&limit=50")
            respond(eventPage(8))
            check(client.getFeedbackNotifications(machine).getOrThrow().cursor == 8L)
        }
        checkCase("feedback cursor pages accept ordered gaps and both current status and developer reply types") {
            val status = eventJson(2).put("kind", "status").put("status", "Processing")
            respond(eventPage(5, JSONArray().put(status).put(eventJson(4)), true))
            val result = client.getFeedbackNotifications(machine, 1L, 2).getOrThrow()
            check(result.cursor == 5L && result.hasMore && result.events.map { it.sequence } == listOf(2L, 4L))
            check(result.events.first().status == "Processing" && result.events.last().feedbackId == id)
            check(seen.last().path == "$prefix/events?machineId=android-fixture%2Bdevice&limit=2&after=1")
            respond(eventPage(7, more = true))
            check(client.getFeedbackNotifications(machine, 5).getOrThrow().events.isEmpty())
        }
        checkCase("feedback cursor validation rejects coercions regression replay and stalled pagination") {
            val malformedPages = listOf(eventPage(1).put("cursor", "1"), eventPage(1).put("cursor", 1.5),
                eventPage(-1), eventPage(0), eventPage(1, more = true),
                eventPage(2, JSONArray().put(eventJson(1))), eventPage(2, JSONArray().put(eventJson(3))),
                eventPage(3, JSONArray().put(eventJson(2)).put(eventJson(2))),
                eventPage(4, JSONArray().put(eventJson(3)).put(eventJson(2))),
                eventPage(3, JSONArray().put(eventJson(2))).put("hasMore", "true"))
            for (body in malformedPages) {
                respond(body)
                val error = client.getFeedbackNotifications(machine, 1L).exceptionOrNull()
                check(error is DeveloperCenterClient.DeveloperCenterException && error.kind == DeveloperCenterClient.ErrorKind.MALFORMED_RESPONSE)
            }
            respond(eventPage(1, JSONArray().put(eventJson(1))))
            check(client.getFeedbackNotifications(machine).isFailure)
            respond(eventPage(1, more = true)); check(client.getFeedbackNotifications(machine).isFailure)
        }
        checkCase("feedback event parser rejects invalid identities types dates and oversized public metadata") {
            val invalidEvents = listOf(eventJson(2).put("sequence", "2"), eventJson(2).put("sequence", 2.5),
                eventJson(2).put("feedbackId", "1-1-1-1-1"), eventJson(2).put("kind", "admin"),
                eventJson(2).put("status", "Processing"), eventJson(2).put("kind", "status"),
                eventJson(2).put("kind", "status").put("status", "Unknown"),
                eventJson(2).put("title", "x".repeat(161)), eventJson(2).put("detail", "x".repeat(321)),
                eventJson(2).put("title", "hidden\nline"), eventJson(2).put("createdAt", "yesterday"),
                eventJson(2).apply { remove("status") })
            for (event in invalidEvents) {
                respond(eventPage(2, JSONArray().put(event)))
                check(client.getFeedbackNotifications(machine, 1).isFailure)
            }
            respond(eventPage(3, JSONArray().put(eventJson(2)).put(eventJson(3))))
            check(client.getFeedbackNotifications(machine, 1, 1).isFailure)
        }
        checkCase("feedback event HTTP failures do not silently reset a cursor or fall back to PC") {
            val before = seen.size
            for (status in listOf(404, 409, 503)) {
                reply.set(Reply(status, ByteArray(0)))
                val error = client.getFeedbackNotifications(machine, 7).exceptionOrNull()
                check(error is DeveloperCenterClient.DeveloperCenterException && error.httpStatus == status)
            }
            check(seen.size == before + 3 && seen.takeLast(3).all { it.path.startsWith("$prefix/events?") && it.path.endsWith("&after=7") })
        }
        checkCase("invalid feedback event cursor and bounds are rejected before any network request") {
            val before = seen.size
            check(client.getFeedbackNotifications(machine, -1).isFailure)
            check(client.getFeedbackNotifications(machine, 0, 0).isFailure)
            check(client.getFeedbackNotifications(machine, 0, 51).isFailure)
            check(client.getFeedbackNotifications(" ").isFailure && seen.size == before)
        }
        checkCase("cancelled feedback read cannot return a cursor acknowledgement or retry") {
            val count = AtomicInteger(); val result = AtomicReference<DeveloperCenterClient.FeedbackNotificationPage?>()
            val waiting = DeveloperCenterClient(DeveloperCenterClient.Config(), DeveloperCenterClient.FeedbackTransport {
                count.incrementAndGet(); Thread.sleep(250)
                DeveloperCenterClient.FeedbackHttpResponse(200, eventPage(1, JSONArray().put(eventJson(1))).toString().toByteArray(), "application/json")
            })
            val job = launch { result.set(waiting.getFeedbackNotifications(machine, 0).getOrThrow()) }
            while (count.get() == 0) delay(5)
            job.cancel(); job.join(); delay(300)
            check(job.isCancelled && result.get() == null && count.get() == 1)
        }
        checkCase("cancellation never resubmits a mutation") {
            val count = AtomicInteger()
            val waiting = DeveloperCenterClient(DeveloperCenterClient.Config(), DeveloperCenterClient.FeedbackTransport {
                count.incrementAndGet(); Thread.sleep(250)
                DeveloperCenterClient.FeedbackHttpResponse(201, itemJson().toString().toByteArray(), "application/json")
            })
            val job = launch {
                try { waiting.postFeedback("Bug", "Title", "Body", installation, "0.1.25", machine) }
                catch (_: CancellationException) { }
            }
            while (count.get() == 0) delay(5)
            job.cancel(); job.join(); delay(300)
            check(count.get() == 1)
        }
        println("$passed/$passed PASS Android feedback production client, bounded image headers and own-app diagnostics")
        println("Real loopback HTTP exercises serialization and errors; server sanitization, production TLS and device Compose are not covered.")
    } finally { server.stop(0); executor.shutdownNow() }
}
