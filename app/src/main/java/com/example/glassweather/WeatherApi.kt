package com.example.glassweather

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

data class WeatherData(
    val city: String,
    val description: String,
    val emoji: String,
    val temp: Int,
    val max: Int,
    val min: Int,
    val aqiText: String
)

object WeatherApi {

    private fun get(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 10000
        return try {
            JSONObject(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }

    /** Ищет город; из нескольких одноимённых предпочитает российский. */
    private fun geocode(query: String): JSONObject {
        val results = get(
            "https://geocoding-api.open-meteo.com/v1/search?name=" +
                URLEncoder.encode(query, "UTF-8") + "&count=10&language=ru"
        ).getJSONArray("results")
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            if (r.optString("country_code") == "RU") return r
        }
        return results.getJSONObject(0)
    }

    /** Подпись найденного места для проверки в приложении. */
    fun findPlace(query: String): String = try {
        val g = geocode(query)
        listOf(g.optString("name"), g.optString("admin1"), g.optString("country"))
            .filter { it.isNotBlank() }
            .joinToString(", ") + " (${g.getDouble("latitude")}, ${g.getDouble("longitude")})"
    } catch (e: Exception) {
        "Город не найден или нет сети"
    }

    fun load(cityQuery: String): WeatherData {
        val geo = geocode(cityQuery)
        val lat = geo.getDouble("latitude")
        val lon = geo.getDouble("longitude")
        val cityName = geo.getString("name")

        val w = get(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code,is_day" +
                "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1"
        )
        val cur = w.getJSONObject("current")
        val daily = w.getJSONObject("daily")
        val code = cur.getInt("weather_code")
        val isDay = cur.getInt("is_day") == 1
        val (desc, emoji) = describe(code, isDay)

        val aqiText = try {
            val a = get(
                "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lat&longitude=$lon&current=us_aqi"
            ).getJSONObject("current").getDouble("us_aqi").roundToInt()
            val label = when {
                a <= 50 -> "Отличное"
                a <= 100 -> "Хорошее"
                a <= 150 -> "Умеренное"
                else -> "Плохое"
            }
            "$label $a"
        } catch (e: Exception) {
            ""
        }

        return WeatherData(
            city = cityName,
            description = desc,
            emoji = emoji,
            temp = cur.getDouble("temperature_2m").roundToInt(),
            max = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
            min = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
            aqiText = aqiText
        )
    }

    private fun describe(code: Int, isDay: Boolean): Pair<String, String> = when (code) {
        0 -> "Ясно" to (if (isDay) "☀️" else "🌙")
        1, 2 -> "Малооблачно" to (if (isDay) "🌤️" else "☁️")
        3 -> "Облачно" to "☁️"
        45, 48 -> "Туман" to "🌫️"
        51, 53, 55, 56, 57 -> "Морось" to "🌦️"
        61, 63, 65, 66, 67, 80, 81, 82 -> "Дождь" to "🌧️"
        71, 73, 75, 77, 85, 86 -> "Снег" to "❄️"
        95, 96, 99 -> "Гроза" to "⛈️"
        else -> "—" to "🌡️"
    }
}
