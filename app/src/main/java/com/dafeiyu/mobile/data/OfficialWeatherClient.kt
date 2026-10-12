package com.dafeiyu.mobile.data

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

class OfficialWeatherException(val code: String) : Exception(code)

/** Public model weather, not observed station measurements. No credentials or user URLs. */
class OfficialWeatherClient internal constructor(
    private val clock: Clock,
    private val connectionFactory: (URL) -> HttpsURLConnection,
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 8_000,
) {
    constructor() : this(Clock.systemUTC(), { it.openConnection() as HttpsURLConnection })

    init {
        require(connectTimeoutMs in 1..15_000 && readTimeoutMs in 1..15_000)
    }

    /**
     * status=choices requires confirmation using a returned location_id and the same city/country.
     * A selected ID is revalidated against geocoding; callers cannot supply coordinates or URLs.
     * Cancellation propagates. Other failures expose only a bounded, stable error code.
     */
    suspend fun getWeather(city: String, countryCode: String? = null, locationId: Long? = null): JSONObject {
        val name = city.trim()
        if (name.length !in 2..100 || name.any { it.isISOControl() } ||
            !validUnicode(name) || countryCode != null && !countryCode.matches(Regex("[A-Za-z]{2}")) ||
            locationId != null && locationId <= 0) throw OfficialWeatherException("INVALID_LOCATION")
        val country = countryCode?.uppercase(java.util.Locale.ROOT)
        return withTimeout(30_000) {
            try {
                val geocode = getJson(GEOCODING + "?name=" + encode(name) +
                    "&count=10&language=zh&format=json" +
                    (country?.let { "&countryCode=$it" } ?: ""), 64 * 1024)
                val results = if (!geocode.has("results")) JSONArray()
                    else geocode.get("results") as? JSONArray ?: malformed()
                if (results.length() > 10) malformed()
                val locations = (0 until results.length()).map { index ->
                    location(results.get(index) as? JSONObject ?: malformed(), country)
                }
                if (locations.map { it.getLong("location_id") }.distinct().size != locations.size) malformed()
                val selected = if (locationId != null) {
                    locations.singleOrNull { it.getLong("location_id") == locationId }
                        ?: throw OfficialWeatherException("LOCATION_NOT_IN_CHOICES")
                } else {
                    locations.singleOrNull()?.takeIf { it.getString("name").equals(name, ignoreCase = true) }
                }
                if (selected == null) {
                    JSONObject().put("ok", true).put("status", if (locations.isEmpty()) "not_found" else "choices")
                        .put("city_query", name).put("choices", JSONArray(locations))
                        .put("source", "Open-Meteo Geocoding / GeoNames")
                } else {
                    val forecast = getJson(FORECAST + "?latitude=${selected.getDouble("latitude")}" +
                        "&longitude=${selected.getDouble("longitude")}" +
                        "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code,wind_speed_10m" +
                        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max" +
                        "&timezone=auto&forecast_days=1&temperature_unit=celsius&wind_speed_unit=kmh&precipitation_unit=mm",
                        128 * 1024)
                    weather(selected, forecast)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: OfficialWeatherException) {
                throw error
            } catch (_: Exception) {
                throw OfficialWeatherException("WEATHER_UNAVAILABLE")
            }
        }
    }

    private fun location(value: JSONObject, country: String?): JSONObject {
        val id = integer(value, "id", 1, Long.MAX_VALUE)
        val code = string(value, "country_code", 2)
        if (!code.matches(Regex("[A-Z]{2}")) || country != null && code != country) malformed()
        val zone = string(value, "timezone", 100)
        ZoneId.of(zone)
        val result = JSONObject().put("location_id", id).put("name", string(value, "name", 200))
            .put("country_code", code).put("country", string(value, "country", 200))
            .put("latitude", number(value, "latitude", -90.0, 90.0))
            .put("longitude", number(value, "longitude", -180.0, 180.0)).put("timezone", zone)
        for (key in listOf("admin1", "admin2", "admin3", "admin4")) {
            if (value.has(key) && !value.isNull(key)) result.put(key, string(value, key, 200))
        }
        return result
    }

    private fun weather(location: JSONObject, value: JSONObject): JSONObject {
        val zone = string(value, "timezone", 100)
        ZoneId.of(zone)
        if (zone != location.getString("timezone")) malformed()
        val offset = ZoneOffset.ofTotalSeconds(integer(value, "utc_offset_seconds", -64_800, 64_800).toInt())
        val now = clock.instant()
        val today = now.atOffset(offset).toLocalDate()
        val current = value.get("current") as? JSONObject ?: malformed()
        val daily = value.get("daily") as? JSONObject ?: malformed()
        val currentUnits = value.get("current_units") as? JSONObject ?: malformed()
        val dailyUnits = value.get("daily_units") as? JSONObject ?: malformed()
        requireUnits(currentUnits, mapOf("time" to "iso8601", "temperature_2m" to "\u00b0C",
            "apparent_temperature" to "\u00b0C", "relative_humidity_2m" to "%",
            "is_day" to "", "precipitation" to "mm", "weather_code" to "wmo code", "wind_speed_10m" to "km/h"))
        requireUnits(dailyUnits, mapOf("time" to "iso8601", "weather_code" to "wmo code",
            "temperature_2m_max" to "\u00b0C", "temperature_2m_min" to "\u00b0C",
            "precipitation_sum" to "mm", "precipitation_probability_max" to "%"))
        val time = LocalDateTime.parse(string(current, "time", 40))
        val age = java.time.Duration.between(time.toInstant(offset), now).seconds
        if (age !in -900L..7_200L || time.toLocalDate() != today) {
            throw OfficialWeatherException("STALE_WEATHER")
        }
        val dailyTime = daily.get("time") as? JSONArray ?: malformed()
        if (dailyTime.length() != 1 || dailyTime.get(0) !is String ||
            LocalDate.parse(dailyTime.getString(0)) != today) throw OfficialWeatherException("STALE_WEATHER")
        val currentResult = JSONObject().put("time", time.toString())
            .put("temperature_2m", number(current, "temperature_2m", -100.0, 70.0))
            .put("apparent_temperature", number(current, "apparent_temperature", -150.0, 100.0))
            .put("relative_humidity_2m", number(current, "relative_humidity_2m", 0.0, 100.0))
            .put("is_day", integer(current, "is_day", 0, 1))
            .put("precipitation", number(current, "precipitation", 0.0, 500.0))
            .put("weather_code", weatherCode(current.get("weather_code")))
            .put("wind_speed_10m", number(current, "wind_speed_10m", 0.0, 500.0))
        val dailyResult = JSONObject().put("date", today.toString())
        for ((key, bounds) in mapOf("temperature_2m_max" to (-100.0 to 70.0),
            "temperature_2m_min" to (-100.0 to 70.0), "precipitation_sum" to (0.0 to 2_000.0),
            "precipitation_probability_max" to (0.0 to 100.0))) {
            val array = daily.get(key) as? JSONArray ?: malformed()
            if (array.length() != 1) malformed()
            dailyResult.put(key, numeric(array.get(0), bounds.first, bounds.second))
        }
        if (dailyResult.getDouble("temperature_2m_min") > dailyResult.getDouble("temperature_2m_max")) malformed()
        val codes = daily.get("weather_code") as? JSONArray ?: malformed()
        if (codes.length() != 1) malformed()
        dailyResult.put("weather_code", weatherCode(codes.get(0)))
        return JSONObject().put("ok", true).put("status", "weather").put("location", location)
            .put("timezone", zone).put("utc_offset_seconds", offset.totalSeconds)
            .put("retrieved_at_utc", now.toString()).put("current", currentResult).put("today", dailyResult)
            .put("current_units", currentUnits).put("daily_units", dailyUnits)
            .put("source", JSONObject().put("name", "Open-Meteo").put("url", FORECAST)
                .put("geocoding_url", GEOCODING).put("kind", "weather_model")
                .put("current_basis", "15-minute model conditions; not station observations"))
    }

    private suspend fun getJson(address: String, limit: Int): JSONObject = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val cancelled = AtomicBoolean(false)
            val connection = AtomicReference<HttpsURLConnection?>()
            var worker: Job? = null
            continuation.invokeOnCancellation {
                cancelled.set(true)
                connection.get()?.disconnect()
                worker?.cancel()
            }
            worker = CoroutineScope(continuation.context).launch {
                try {
                    currentCoroutineContext().ensureActive()
                    val call = connectionFactory(URI(address).toURL())
                    connection.set(call)
                    if (cancelled.get()) throw CancellationException()
                    call.apply {
                        requestMethod = "GET"
                        connectTimeout = connectTimeoutMs
                        readTimeout = readTimeoutMs
                        instanceFollowRedirects = false
                        useCaches = false
                        setRequestProperty("Accept", "application/json")
                    }
                    val status = call.responseCode
                    if (status != 200) throw OfficialWeatherException("WEATHER_HTTP_$status")
                    if (call.contentType?.substringBefore(';')?.trim()?.lowercase() != "application/json") malformed()
                    if (call.contentLengthLong > limit) throw OfficialWeatherException("WEATHER_RESPONSE_LIMIT")
                    val bytes = call.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (output.size() + read > limit) throw OfficialWeatherException("WEATHER_RESPONSE_LIMIT")
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray()
                    }
                    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                    StrictWeatherJson(text).validate()
                    val result = JSONObject(text)
                    if (result.has("error")) throw OfficialWeatherException("WEATHER_PROVIDER_ERROR")
                    continuation.resume(result)
                } catch (error: Throwable) {
                    continuation.resumeWithException(error)
                } finally {
                    connection.get()?.disconnect()
                }
            }
            if (!continuation.isActive) worker.cancel()
        }
    }

    private fun requireUnits(value: JSONObject, expected: Map<String, String>) {
        expected.forEach { (key, unit) -> if (value.get(key) != unit) malformed() }
    }
    private fun string(value: JSONObject, key: String, max: Int): String =
        (value.get(key) as? String)?.takeIf {
            it.isNotBlank() && it.length <= max && it.none(Char::isISOControl) && validUnicode(it)
        } ?: malformed()
    private fun integer(value: JSONObject, key: String, min: Long, max: Long): Long {
        val number = value.get(key)
        if (number !is Int && number !is Long) malformed()
        return (number as Number).toLong().takeIf { it in min..max } ?: malformed()
    }
    private fun number(value: JSONObject, key: String, min: Double, max: Double) =
        numeric(value.get(key), min, max)
    private fun numeric(value: Any, min: Double, max: Double): Double =
        (value as? Number)?.toDouble()?.takeIf { it.isFinite() && it in min..max } ?: malformed()
    private fun weatherCode(value: Any): Int {
        if (value !is Int && value !is Long) malformed()
        val code = (value as Number).toLong()
        if (code !in setOf(0L, 1L, 2L, 3L, 45L, 48L, 51L, 53L, 55L, 56L, 57L,
                61L, 63L, 65L, 66L, 67L, 71L, 73L, 75L, 77L, 80L, 81L, 82L, 85L, 86L, 95L, 96L, 99L)) malformed()
        return code.toInt()
    }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun malformed(): Nothing = throw OfficialWeatherException("WEATHER_PROTOCOL")

    companion object {
        const val GEOCODING = "https://geocoding-api.open-meteo.com/v1/search"
        const val FORECAST = "https://api.open-meteo.com/v1/forecast"
    }
}

private fun validUnicode(value: String): Boolean {
    var at = 0
    while (at < value.length) {
        val char = value[at++]
        if (char.isHighSurrogate()) {
            if (at == value.length || !value[at++].isLowSurrogate()) return false
        } else if (char.isLowSurrogate()) return false
    }
    return true
}

/** Reject lenient org.json extensions, duplicate keys and excessive nesting before decoding. */
private class StrictWeatherJson(private val text: String) {
    private var at = 0
    private var nodes = 0
    fun validate() {
        whitespace()
        require(at < text.length && text[at] == '{')
        value(0)
        whitespace()
        require(at == text.length)
    }
    private fun whitespace() { while (at < text.length && text[at] in " \t\r\n") at++ }
    private fun take(char: Char): Boolean {
        whitespace()
        if (at < text.length && text[at] == char) { at++; return true }
        return false
    }
    private fun quoted(): String {
        whitespace()
        val start = at
        require(at < text.length && text[at++] == '"')
        while (at < text.length) {
            when (val char = text[at++]) {
                '"' -> {
                    val result = JSONTokener(text.substring(start, at)).nextValue() as String
                    require(validUnicode(result))
                    return result
                }
                '\\' -> {
                    require(at < text.length)
                    when (text[at++]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> repeat(4) { require(at < text.length && text[at++] in "0123456789abcdefABCDEF") }
                        else -> error("invalid JSON escape")
                    }
                }
                else -> require(char >= ' ')
            }
        }
        error("unterminated JSON")
    }
    private fun value(depth: Int) {
        require(depth <= 16 && ++nodes <= 8_192)
        whitespace()
        require(at < text.length)
        when (text[at]) {
            '{' -> {
                at++
                val keys = mutableSetOf<String>()
                if (!take('}')) {
                    do {
                        require(keys.add(quoted()))
                        require(take(':'))
                        value(depth + 1)
                    } while (take(','))
                    require(take('}'))
                }
            }
            '[' -> {
                at++
                if (!take(']')) {
                    do { value(depth + 1) } while (take(','))
                    require(take(']'))
                }
            }
            '"' -> quoted()
            else -> {
                val start = at
                while (at < text.length && text[at] !in " \t\r\n,]}") at++
                val token = text.substring(start, at)
                require(token in setOf("true", "false", "null") ||
                    token.matches(Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")))
            }
        }
    }
}
