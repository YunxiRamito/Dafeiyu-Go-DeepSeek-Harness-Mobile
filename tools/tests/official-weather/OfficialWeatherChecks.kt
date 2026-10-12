package com.dafeiyu.mobile.data

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.security.Principal
import java.security.cert.Certificate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

private val fixedClock = Clock.fixed(Instant.parse("2026-10-11T04:05:00Z"), ZoneOffset.UTC)
private fun place(id: Long = 1, name: String = "\u6cf0\u5b89", admin: String = "\u5c71\u4e1c") =
    JSONObject().put("id", id).put("name", name).put("country_code", "CN").put("country", "\u4e2d\u56fd")
        .put("latitude", 36.2).put("longitude", 117.1).put("timezone", "Asia/Shanghai").put("admin1", admin)
private fun geocode(vararg places: JSONObject) = JSONObject().put("results", JSONArray(places.toList())).toString()
private fun forecast(): JSONObject = JSONObject()
    .put("timezone", "Asia/Shanghai").put("utc_offset_seconds", 28_800)
    .put("current", JSONObject().put("time", "2026-10-11T12:00").put("temperature_2m", 20.5)
        .put("apparent_temperature", 19.5).put("relative_humidity_2m", 60).put("is_day", 1)
        .put("precipitation", 0).put("weather_code", 2).put("wind_speed_10m", 10))
    .put("daily", JSONObject().put("time", JSONArray().put("2026-10-11"))
        .put("temperature_2m_max", JSONArray().put(23.0)).put("temperature_2m_min", JSONArray().put(14.0))
        .put("precipitation_sum", JSONArray().put(0)).put("precipitation_probability_max", JSONArray().put(20))
        .put("weather_code", JSONArray().put(3)))
    .put("current_units", JSONObject().put("time", "iso8601").put("temperature_2m", "\u00b0C")
        .put("apparent_temperature", "\u00b0C").put("relative_humidity_2m", "%").put("is_day", "")
        .put("precipitation", "mm").put("weather_code", "wmo code").put("wind_speed_10m", "km/h"))
    .put("daily_units", JSONObject().put("time", "iso8601").put("temperature_2m_max", "\u00b0C")
        .put("temperature_2m_min", "\u00b0C").put("precipitation_sum", "mm")
        .put("precipitation_probability_max", "%").put("weather_code", "wmo code"))

private class WeatherConnection(url: URL, val body: ByteArray, val status: Int = 200,
    val mime: String = "application/json", val blocked: Boolean = false) : HttpsURLConnection(url) {
    val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    @Volatile var disconnected = false
    override fun connect() {}
    override fun disconnect() { disconnected = true; release.countDown() }
    override fun usingProxy() = false
    override fun getResponseCode() = status
    override fun getContentType() = mime
    override fun getInputStream(): InputStream = if (!blocked) ByteArrayInputStream(body) else object : InputStream() {
        override fun read(): Int {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            throw IOException("private cancellation detail")
        }
    }
    override fun getCipherSuite() = "fixture"
    override fun getLocalCertificates(): Array<Certificate>? = null
    override fun getServerCertificates(): Array<Certificate> = emptyArray()
    override fun getPeerPrincipal(): Principal? = null
    override fun getLocalPrincipal(): Principal? = null
}

private class Fixture(vararg responses: String, private val status: Int = 200,
    private val mime: String = "application/json", private val blocked: Boolean = false,
    private val bytes: ByteArray? = null) {
    val calls = CopyOnWriteArrayList<WeatherConnection>()
    private val bodies = responses.toList()
    val client = OfficialWeatherClient(fixedClock, { url ->
        WeatherConnection(url, bytes ?: bodies[calls.size].toByteArray(), status, mime, blocked)
            .also { calls += it }
    })
}
private suspend fun error(code: String, block: suspend () -> Unit) {
    val failure = try { block(); null } catch (exception: OfficialWeatherException) { exception }
    check(failure?.code == code) { "expected $code, got ${failure?.code}" }
}

fun main(args: Array<String>) = runBlocking {
    if (args.firstOrNull() == "--public-smoke") {
        val result = OfficialWeatherClient().getWeather("\u6cf0\u5b89", "CN")
        check(result.getString("status") in setOf("weather", "choices", "not_found"))
        val evidence = JSONObject().put("retrieved_at_utc", Instant.now().toString())
            .put("query", "\u6cf0\u5b89").put("country_code", "CN").put("result", result)
        val text = evidence.toString(2)
        check(text.toByteArray().size <= 64 * 1024)
        Files.writeString(Path.of(args[1]).resolve("taian-public-get.json"), text)
        println("PASS public fixed HTTPS GET: ${result.getString("status")}; bounded evidence saved")
        // Explicit QA selection of the geocoded Shandong city, never an application default.
        val latinCity = "Tai\u2019an"
        val choices = OfficialWeatherClient().getWeather(latinCity, "CN")
        check(choices.getString("status") == "choices")
        val candidates = choices.getJSONArray("choices")
        val shandong = (0 until candidates.length()).map { candidates.getJSONObject(it) }
            .single { it.optString("admin1") == "\u5c71\u4e1c" && it.optString("admin2") == "\u6cf0\u5b89\u5e02" }
        val weather = OfficialWeatherClient().getWeather(latinCity, "CN", shandong.getLong("location_id"))
        check(weather.getString("status") == "weather")
        val weatherEvidence = JSONObject().put("query", latinCity).put("confirmed_location", shandong)
            .put("result", weather).toString(2)
        check(weatherEvidence.toByteArray().size <= 64 * 1024)
        Files.writeString(Path.of(args[1]).resolve("shandong-taian-weather-public-get.json"), weatherEvidence)
        println("PASS confirmed Shandong Tai'an public forecast: " +
            weather.getJSONObject("current").getString("time") + " " + weather.getString("timezone"))
        return@runBlocking
    }
    var passed = 0
    suspend fun test(name: String, block: suspend () -> Unit) {
        withTimeout(8_000) { block() }
        passed++
        println("PASS $name")
    }
    test("fixed hosts, escaped city, country, no credentials, current plus local today") {
        val fixture = Fixture(geocode(place()), forecast().toString())
        val result = fixture.client.getWeather("\u6cf0\u5b89", "cn")
        check(result.getString("status") == "weather")
        check(result.getJSONObject("today").getString("date") == "2026-10-11")
        check(result.getJSONObject("current").getDouble("temperature_2m") == 20.5)
        check(result.getString("timezone") == "Asia/Shanghai")
        check(result.getJSONObject("source").getString("kind") == "weather_model")
        check(fixture.calls.size == 2 && fixture.calls.all {
            it.url.protocol == "https" && it.requestMethod == "GET" && !it.instanceFollowRedirects &&
                it.connectTimeout == 8_000 && it.readTimeout == 8_000 && it.disconnected &&
                it.getRequestProperty("Authorization") == null
        })
        check(fixture.calls[0].url.host == "geocoding-api.open-meteo.com")
        check(fixture.calls[0].url.query.contains("countryCode=CN"))
        check(fixture.calls[1].url.host == "api.open-meteo.com")
        check(fixture.calls[1].url.query.contains("forecast_days=1"))
    }
    test("ambiguous city returns administrative choices without fetching weather") {
        val fixture = Fixture(geocode(place(1, admin = "Jiangsu"), place(2, admin = "Sichuan")))
        val result = fixture.client.getWeather("\u6cf0\u5b89", "CN")
        check(result.getString("status") == "choices" && result.getJSONArray("choices").length() == 2)
        check(fixture.calls.size == 1)
    }
    test("explicit candidate selection is revalidated and resolves ambiguity") {
        val fixture = Fixture(geocode(place(1), place(2)), forecast().toString())
        check(fixture.client.getWeather("\u6cf0\u5b89", "CN", 2).getJSONObject("location").getLong("location_id") == 2L)
        val rejected = Fixture(geocode(place(1)))
        error("LOCATION_NOT_IN_CHOICES") { rejected.client.getWeather("\u6cf0\u5b89", "CN", 3) }
        check(rejected.calls.size == 1)
    }
    test("single fuzzy result still requires confirmation") {
        val fixture = Fixture(geocode(place(name = "Taian Southeast")))
        check(fixture.client.getWeather("Taian").getString("status") == "choices")
        check(fixture.calls.size == 1)
    }
    test("absent results and empty results return not_found") {
        for (body in listOf("{}", geocode())) {
            check(Fixture(body).client.getWeather("missing").getString("status") == "not_found")
        }
    }
    test("invalid city country selection and unicode are rejected before transport") {
        val fixture = Fixture()
        for (name in listOf("", "x", "x\nx", "x".repeat(101), "\uD800x")) {
            error("INVALID_LOCATION") { fixture.client.getWeather(name) }
        }
        error("INVALID_LOCATION") { fixture.client.getWeather("city", "C&x") }
        error("INVALID_LOCATION") { fixture.client.getWeather("city", locationId = 0) }
        check(fixture.calls.isEmpty())
    }
    test("redirect HTML and provider error cannot become weather") {
        error("WEATHER_HTTP_302") { Fixture("{}", status = 302).client.getWeather("city") }
        error("WEATHER_PROTOCOL") { Fixture("{}", mime = "text/html").client.getWeather("city") }
        error("WEATHER_PROVIDER_ERROR") { Fixture("{\"error\":true,\"reason\":\"PRIVATE\"}").client.getWeather("city") }
    }
    test("strict JSON duplicate escaped keys trailing commas and deep structures rejected") {
        val invalid = listOf("{'results':[]}", "{results:[]}", "{\"results\":[],}",
            "{\"results\":[],\"res\\u0075lts\":[]}", "{\"results\":[]} trailing",
            "{\"x\":" + "[".repeat(18) + "0" + "]".repeat(18) + "}",
            "{\"results\":null}", "{\"results\":{}}", "{\"x\":\"\\uD800\"}")
        for (body in invalid) {
            val fixture = Fixture(body)
            check(runCatching { fixture.client.getWeather("city") }.exceptionOrNull() is OfficialWeatherException)
            check(fixture.calls.single().disconnected)
        }
    }
    test("response bytes are bounded and invalid UTF8 is rejected") {
        error("WEATHER_RESPONSE_LIMIT") { Fixture(" ".repeat(64 * 1024 + 1)).client.getWeather("city") }
        error("WEATHER_UNAVAILABLE") { Fixture(bytes = byteArrayOf(0xC3.toByte(), 0x28)).client.getWeather("city") }
    }
    test("country mismatch invalid coordinates timezone and duplicate candidates rejected") {
        for (bad in listOf(place().put("country_code", "US"), place().put("latitude", 100),
            place().put("timezone", "private/invalid"), place().put("id", "1"))) {
            check(runCatching { Fixture(geocode(bad)).client.getWeather("\u6cf0\u5b89", "CN") }
                .exceptionOrNull() is OfficialWeatherException)
        }
        error("WEATHER_PROTOCOL") { Fixture(geocode(place(), place())).client.getWeather("city") }
    }
    test("stale current or wrong local today cannot be labeled current weather") {
        for (bad in listOf(forecast().apply { getJSONObject("current").put("time", "2026-10-10T12:00") },
            forecast().apply { getJSONObject("daily").put("time", JSONArray().put("2026-10-12")) })) {
            error("STALE_WEATHER") { Fixture(geocode(place()), bad.toString()).client.getWeather("\u6cf0\u5b89") }
        }
    }
    test("unit mismatch null weather coercive numbers and unknown WMO fail closed") {
        for (bad in listOf(
            forecast().apply { getJSONObject("current_units").put("temperature_2m", "F") },
            forecast().apply { getJSONObject("current").put("temperature_2m", JSONObject.NULL) },
            forecast().apply { getJSONObject("current").put("temperature_2m", "20") },
            forecast().apply { getJSONObject("current").put("weather_code", 999) },
            forecast().apply { getJSONObject("daily").put("temperature_2m_max", JSONArray().put(10)) },
        )) error("WEATHER_PROTOCOL") {
            Fixture(geocode(place()), bad.toString()).client.getWeather("\u6cf0\u5b89")
        }
    }
    test("blocked input cancellation disconnects immediately and propagates cancellation") {
        val fixture = Fixture("{}", blocked = true)
        var caught = false
        val job = launch(Dispatchers.Default) {
            try { fixture.client.getWeather("city") }
            catch (cancelled: CancellationException) { caught = true; throw cancelled }
        }
        withContext(Dispatchers.IO) {
            while (fixture.calls.isEmpty()) Thread.yield()
            check(fixture.calls.single().entered.await(3, TimeUnit.SECONDS))
        }
        job.cancel()
        job.join()
        check(caught && fixture.calls.single().disconnected)
    }
    println("PASS $passed official weather fixtures (no network, credentials or paid calls)")
}
