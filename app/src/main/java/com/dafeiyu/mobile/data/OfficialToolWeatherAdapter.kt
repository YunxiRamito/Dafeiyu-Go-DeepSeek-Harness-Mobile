package com.dafeiyu.mobile.data

import org.json.JSONObject

/** Fixed Open-Meteo transport; the client revalidates selected IDs against geocoding. */
class OfficialToolWeatherAdapter(
    private val client: OfficialWeatherClient = OfficialWeatherClient(),
) : OfficialToolWeatherSelectionProvider {
    override suspend fun weather(city: String, countryCode: String?): JSONObject =
        client.getWeather(city, countryCode)

    override suspend fun weather(city: String, countryCode: String?, locationId: Long): JSONObject =
        client.getWeather(city, countryCode, locationId)
}
