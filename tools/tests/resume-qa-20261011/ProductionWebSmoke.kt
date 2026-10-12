package com.dafeiyu.mobile.data

import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import kotlin.system.exitProcess

private fun digest(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun main(args: Array<String>) {
    require(args.size == 1 && args.single() in setOf("--offline", "--live-host"))
    var passed = 0
    val live = args.single() == "--live-host"
    runBlocking {
        suspend fun attempt(name: String, action: suspend () -> JSONObject) {
            val result = JSONObject().put("name", name).put("started_at", Instant.now().toString())
            try {
                result.put("result", action()).put("status", "PASS")
                passed++
            } catch (failed: Exception) {
                result.put("status", "FAIL").put("error",
                    if (failed is OfficialWebException) failed.code else failed.javaClass.simpleName)
                    .put("message", failed.message.orEmpty().take(300))
            }
            println("QA_PRODUCTION_WEB $result")
        }
        attempt("original_one_MiB_policy") {
            check(OfficialWebPolicy.MAX_BYTES == 1024 * 1024)
            var called = 0
            val client = OfficialWebClient(OfficialWebTransport {
                called++
                OfficialWebResponse(200, "text/plain", ByteArray(OfficialWebPolicy.MAX_BYTES + 1))
            }, System::currentTimeMillis)
            val failure = runCatching { client.browse("https://www.rfc-editor.org/rfc/rfc768.html") }.exceptionOrNull()
            check(failure is OfficialWebException && failure.code == "web_response_too_large")
            check(called == 1)
            JSONObject().put("maximum_bytes", OfficialWebPolicy.MAX_BYTES)
        }
        attempt("synthetic_static_body_extraction") {
            val body = "<html><head><title>QA official static</title></head><body><main>" +
                "<p>QA_BODY_019 actual paragraph</p></main><script>QA_SCRIPT_EXCLUDED</script></body></html>"
            val client = OfficialWebClient(OfficialWebTransport {
                OfficialWebResponse(200, "text/html", body.toByteArray())
            }, System::currentTimeMillis)
            val page = client.browse("https://www.rfc-editor.org/rfc/rfc768.html")
            check(page.getString("text") == "QA_BODY_019 actual paragraph")
            check(!page.getBoolean("text_truncated"))
            JSONObject().put("body", page.getString("text"))
        }
        if (live) {
            val transport = OfficialWebHttpTransport()
            val sizes = mutableMapOf<String, Int>()
            val client = OfficialWebClient(OfficialWebTransport { url ->
                transport.get(url).also { sizes[url.toString()] = it.body.size }
            }, System::currentTimeMillis)
            attempt("browser_RFC768_small_official_body") {
                val page = client.browse("https://www.rfc-editor.org/rfc/rfc768.html")
                check(page.getString("final_url") == "https://www.rfc-editor.org/rfc/rfc768.html")
                // The official RFC768 HTML is a preformatted fragment without a title element.
                check(page.getString("title").isEmpty())
                check(page.getString("text").contains("User Datagram Protocol"))
                check(page.getString("text").contains("RFC 768"))
                check(!page.getBoolean("text_truncated"))
                check(sizes.values.all { it in 1..32768 })
                Instant.parse(page.getString("retrieved_at"))
                JSONObject().put("page", page).put("response_bytes", sizes.values.sum())
                    .put("text_sha256", digest(page.getString("text").toByteArray()))
            }
            attempt("search_transport_only") {
                val page = client.search("Kotlin official documentation", 3)
                check(page.getJSONArray("results").length() in 1..3)
                Instant.parse(page.getString("retrieved_at"))
                page.put("assertion_scope", "transport/schema only; relevance requires review")
            }
        }
    }
    val total = if (live) 4 else 2
    println("QA_PRODUCTION_SUMMARY " + JSONObject().put("passed", passed).put("total", total)
        .put("environment", "host JVM independently compiled production sources")
        .put("apk_uid", false).put("model_called", false))
    exitProcess(if (passed == total) 0 else 2)
}
