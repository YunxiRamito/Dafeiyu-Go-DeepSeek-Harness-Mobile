package com.dafeiyu.mobile.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.DocumentType
import org.jsoup.parser.Parser

internal data class OfficialWebResponse(
    val status: Int,
    val mediaType: String,
    val body: ByteArray,
    val location: String? = null,
    val charset: String? = null,
)

internal fun interface OfficialWebTransport {
    suspend fun get(url: HttpUrl): OfficialWebResponse
}

/** A read-only browser: no cookies, proxy, credentials, script execution or private network. */
class OfficialWebClient internal constructor(
    private val transport: OfficialWebTransport,
    private val clock: () -> Long,
) : OfficialToolWebProvider {
    constructor() : this(OfficialWebHttpTransport(), System::currentTimeMillis)

    override suspend fun search(query: String, limit: Int): JSONObject = boundedOperation {
        OfficialToolContract.safeText(query, 512)
        require(query.none(Char::isISOControl) && limit in 1..8)
        val attempts = JSONArray()
        for (source in listOf("bing_rss", "duckduckgo_html")) {
            try {
                val url = if (source == "bing_rss") {
                    "https://www.bing.com/search".toHttpUrlOrNull()!!.newBuilder()
                        .addQueryParameter("q", query).addQueryParameter("format", "rss").build()
                } else {
                    "https://html.duckduckgo.com/html/".toHttpUrlOrNull()!!.newBuilder()
                        .addQueryParameter("q", query).build()
                }
                val (finalUrl, response) = fetch(url)
                val document = document(response, finalUrl, xml = source == "bing_rss")
                val results = if (source == "bing_rss") bing(document, limit) else duckDuckGo(document, limit)
                return@boundedOperation JSONObject().put("query", query).put("source", source)
                    .put("source_url", finalUrl.toString()).put("retrieved_at", timestamp())
                    .put("results", results).put("fallback_errors", attempts)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: OfficialWebException) {
                attempts.put(JSONObject().put("source", source).put("error", failed.code))
            }
        }
        throw OfficialWebException("web_search_unavailable", JSONObject().put("attempts", attempts))
    }

    override suspend fun browse(url: String): JSONObject = boundedOperation {
        val (finalUrl, response) = fetch(OfficialWebPolicy.url(url))
        if (response.mediaType == "text/plain") {
            val text = decode(response)
            return@boundedOperation JSONObject().put("url", url).put("final_url", finalUrl.toString())
                .put("title", "").put("text", clip(text, MAX_TEXT)).put("links", JSONArray())
                .put("text_truncated", text.length > MAX_TEXT).put("links_truncated", false)
                .put("retrieved_at", timestamp())
        }
        val document = document(response, finalUrl, xml = response.mediaType in XML_TYPES)
        document.select("script,style,noscript,template,svg,iframe,object,embed,form").remove()
        val content = document.selectFirst("article") ?: document.selectFirst("main") ?: document.body() ?: document
        val text = content.text()
        val links = JSONArray()
        val seen = mutableSetOf<String>()
        var omittedLinks = false
        for (anchor in document.select("a[href]")) {
            val link = publicLink(finalUrl.resolve(anchor.attr("href"))?.toString().orEmpty()) ?: continue
            if (!seen.add(link)) continue
            if (links.length() == MAX_LINKS) { omittedLinks = true; break }
            links.put(JSONObject().put("text", clip(anchor.text(), 160)).put("url", link))
        }
        JSONObject().put("url", url).put("final_url", finalUrl.toString())
            .put("title", clip(document.title(), 256)).put("text", clip(text, MAX_TEXT))
            .put("links", links).put("text_truncated", text.length > MAX_TEXT)
            .put("links_truncated", omittedLinks).put("retrieved_at", timestamp())
    }

    private suspend fun <T : Any> boundedOperation(block: suspend () -> T): T =
        withTimeoutOrNull(25_000) { withContext(Dispatchers.IO) { block() } }
            ?: throw OfficialWebException("web_timeout")

    private suspend fun fetch(initial: HttpUrl): Pair<HttpUrl, OfficialWebResponse> {
        var url = OfficialWebPolicy.url(initial.toString())
        repeat(4) { hop ->
            val response = transport.get(url)
            if (response.status in setOf(301, 302, 303, 307, 308)) {
                if (hop == 3) throw OfficialWebException("web_redirect_limit")
                val location = response.location ?: throw OfficialWebException("web_redirect_invalid")
                if (location.any(Char::isISOControl)) throw OfficialWebException("web_redirect_invalid")
                url = OfficialWebPolicy.url(url.resolve(location)?.toString()
                    ?: throw OfficialWebException("web_redirect_invalid"))
            } else {
                if (response.status !in 200..299) throw OfficialWebException("web_http_${response.status}")
                if (response.body.size > OfficialWebPolicy.MAX_BYTES) {
                    throw OfficialWebException("web_response_too_large")
                }
                if (response.body.isEmpty()) throw OfficialWebException("web_empty_response")
                if (response.mediaType !in HTML_TYPES + XML_TYPES + "text/plain") {
                    throw OfficialWebException("web_content_type")
                }
                return url to response
            }
        }
        throw OfficialWebException("web_redirect_limit")
    }

    private fun document(response: OfficialWebResponse, url: HttpUrl, xml: Boolean): Document {
        if (xml && response.mediaType !in XML_TYPES) throw OfficialWebException("web_search_invalid_response")
        val document = if (xml) {
            Jsoup.parse(decode(response), url.toString(), Parser.xmlParser())
        } else {
            ByteArrayInputStream(response.body).use { Jsoup.parse(it, response.charset, url.toString()) }
        }
        // jsoup never loads external entities; reject DTDs as well instead of returning ambiguous XML.
        if (xml && document.childNodes().any { it is DocumentType }) {
            throw OfficialWebException("web_xml_doctype")
        }
        return document
    }

    private fun bing(document: Document, limit: Int): JSONArray {
        if (document.selectFirst("rss > channel") == null) throw OfficialWebException("web_search_invalid_response")
        val results = JSONArray()
        for (item in document.select("rss > channel > item")) {
            val link = publicLink(item.selectFirst("link")?.text().orEmpty()) ?: continue
            val title = item.selectFirst("title")?.text().orEmpty()
            if (title.isBlank()) continue
            val snippet = Jsoup.parse(item.selectFirst("description")?.text().orEmpty()).text()
            results.put(JSONObject().put("title", clip(title, 256)).put("url", link)
                .put("snippet", clip(snippet, 1024)))
            if (results.length() == limit) break
        }
        if (results.length() == 0) throw OfficialWebException("web_search_no_results")
        return results
    }

    private fun duckDuckGo(document: Document, limit: Int): JSONArray {
        if (document.selectFirst("#challenge-form, #anomaly-form, .anomaly-modal") != null) {
            throw OfficialWebException("web_search_challenge")
        }
        val results = JSONArray()
        for (item in document.select(".result")) {
            val anchor = item.selectFirst("a.result__a") ?: continue
            var link = anchor.absUrl("href")
            val wrapped = link.toHttpUrlOrNull()
            if (wrapped != null && (wrapped.host == "duckduckgo.com" || wrapped.host.endsWith(".duckduckgo.com"))) {
                link = wrapped.queryParameter("uddg") ?: continue
            }
            link = publicLink(link) ?: continue
            val title = anchor.text()
            if (title.isBlank()) continue
            results.put(JSONObject().put("title", clip(title, 256)).put("url", link)
                .put("snippet", clip(item.selectFirst(".result__snippet")?.text().orEmpty(), 1024)))
            if (results.length() == limit) break
        }
        if (results.length() == 0) throw OfficialWebException("web_search_no_results")
        return results
    }

    private fun publicLink(value: String): String? =
        try { OfficialWebPolicy.url(value).toString() } catch (_: OfficialWebException) { null }

    private fun decode(response: OfficialWebResponse): String =
        try {
            val charset = response.charset?.let { java.nio.charset.Charset.forName(it) } ?: Charsets.UTF_8
            charset.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(response.body)).toString()
        } catch (_: Exception) {
            throw OfficialWebException("web_encoding_invalid")
        }

    private fun timestamp(): String = Instant.ofEpochMilli(clock()).toString()

    private fun clip(value: String, maximum: Int): String {
        var end = minOf(value.length, maximum)
        if (end > 0 && end < value.length && value[end - 1].isHighSurrogate()) end--
        return value.take(end).map { if (it.isISOControl() && it !in "\r\n\t") ' ' else it }.joinToString("")
    }

    companion object {
        private const val MAX_TEXT = 12_000
        private const val MAX_LINKS = 20
        private val HTML_TYPES = setOf("text/html", "application/xhtml+xml")
        private val XML_TYPES = setOf("application/xml", "text/xml", "application/rss+xml", "application/atom+xml")
    }
}

internal object OfficialWebPolicy {
    const val MAX_BYTES = 1024 * 1024

    fun url(value: String): HttpUrl {
        if (value.length > 2048 || value.any { it.isWhitespace() || it.isISOControl() } || '\\' in value) {
            throw OfficialWebException("web_url_invalid")
        }
        val raw = try { URI(value) } catch (_: Exception) { throw OfficialWebException("web_url_invalid") }
        if (!raw.scheme.equals("https", ignoreCase = true) || raw.rawUserInfo != null ||
            raw.host.isNullOrBlank() || raw.port !in setOf(-1, 443)) throw OfficialWebException("web_url_blocked")
        val url = value.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()
            ?: throw OfficialWebException("web_url_invalid")
        checkHost(url.host)
        return url
    }

    fun checkHost(host: String) {
        val normalized = host.trimEnd('.').lowercase(Locale.ROOT)
        if (normalized.isBlank() || normalized == "localhost" ||
            listOf(".localhost", ".local", ".internal", ".home", ".lan", ".onion", ".invalid").any(normalized::endsWith)) {
            throw OfficialWebException("web_private_address")
        }
        if (':' in normalized || normalized.all { it.isDigit() || it == '.' }) {
            val address = try { InetAddress.getByName(normalized) }
                catch (_: Exception) { throw OfficialWebException("web_url_invalid") }
            if (!publicAddress(address)) throw OfficialWebException("web_private_address")
        } else if ('.' !in normalized) throw OfficialWebException("web_private_address")
    }

    fun publicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address
        if (bytes.size == 4) {
            val value = bytes.fold(0L) { acc, byte -> (acc shl 8) or (byte.toInt() and 255).toLong() }
            return listOf(
                0x00000000L..0x00ffffffL, 0x0a000000L..0x0affffffL, 0x64400000L..0x647fffffL,
                0x7f000000L..0x7fffffffL, 0xa9fe0000L..0xa9feffffL, 0xac100000L..0xac1fffffL,
                0xc0000000L..0xc00000ffL, 0xc0000200L..0xc00002ffL, 0xc0586300L..0xc05863ffL,
                0xc0a80000L..0xc0a8ffffL, 0xc6120000L..0xc613ffffL, 0xc6336400L..0xc63364ffL,
                0xcb007100L..0xcb0071ffL, 0xe0000000L..0xffffffffL,
            ).none { value in it }
        }
        // Only global unicast IPv6; block transition ranges that can embed private IPv4.
        if (bytes.size != 16 || (bytes[0].toInt() and 0xe0) != 0x20) return false
        val first = ((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255)
        if (first == 0x2002 || first == 0x3fff) return false
        if (first == 0x2001) {
            val second = ((bytes[2].toInt() and 255) shl 8) or (bytes[3].toInt() and 255)
            if (second <= 0x01ff || second == 0x0db8) return false
        }
        return true
    }
}

/** Checked answers are the exact addresses OkHttp connects to, with normal TLS hostname verification. */
internal class OfficialWebDns(private val resolve: (String) -> List<InetAddress> = {
    Dns.SYSTEM.lookup(it)
}) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        OfficialWebPolicy.checkHost(hostname)
        val answers = resolve(hostname)
        if (answers.isEmpty() || answers.any { !OfficialWebPolicy.publicAddress(it) }) {
            throw OfficialWebException("web_private_address")
        }
        return answers
    }
}

internal class OfficialWebHttpTransport(
    private val client: OkHttpClient = sharedClient,
) : OfficialWebTransport {
    companion object {
        // Reuse bounded idle connections across turns; cancelling a call never closes other turns' client.
        private val sharedClient by lazy {
            val executor = Executors.newCachedThreadPool { task ->
                Thread(task, "official-web-http").apply { isDaemon = true }
            }
            OkHttpClient.Builder().dns(OfficialWebDns()).proxy(Proxy.NO_PROXY)
                .dispatcher(okhttp3.Dispatcher(executor))
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(12, TimeUnit.SECONDS).build()
        }
    }

    override suspend fun get(url: HttpUrl): OfficialWebResponse = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(OfficialWebPolicy.url(url.toString()))
            .header("Accept", "text/html,application/rss+xml,application/xml,text/plain;q=0.8")
            .header("User-Agent", "DafeiyuGo/0.1 (read-only public web tools)")
            .build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, exception: IOException) {
                if (continuation.isActive) continuation.resumeWithException(classify(exception))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (it.code !in 200..299) {
                            return@use OfficialWebResponse(it.code, "", byteArrayOf(), it.header("Location"))
                        }
                        val body = it.body ?: throw OfficialWebException("web_empty_response")
                        val type = body.contentType()
                        val mediaType = type?.let { media -> "${media.type}/${media.subtype}" }.orEmpty()
                        if (mediaType !in setOf("text/html", "application/xhtml+xml", "application/xml",
                                "text/xml", "application/rss+xml", "application/atom+xml", "text/plain")) {
                            throw OfficialWebException("web_content_type")
                        }
                        if (body.contentLength() > OfficialWebPolicy.MAX_BYTES) {
                            throw OfficialWebException("web_response_too_large")
                        }
                        val output = ByteArrayOutputStream()
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > OfficialWebPolicy.MAX_BYTES) {
                                    throw OfficialWebException("web_response_too_large")
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                        OfficialWebResponse(it.code, mediaType, output.toByteArray(),
                            charset = type?.charset()?.name())
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (failed: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(classify(failed))
                }
            }
        })
    }

    private fun classify(exception: Exception): OfficialWebException {
        generateSequence<Throwable>(exception) { it.cause }.filterIsInstance<OfficialWebException>()
            .firstOrNull()?.let { return it }
        return OfficialWebException(if (exception is java.io.InterruptedIOException) "web_timeout"
            else "web_network_error")
    }
}
