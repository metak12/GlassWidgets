package com.example.glassweather

import android.content.Context
import android.net.Uri

object XiaomiWeather {
    private val URI = Uri.parse("content://weather/actualWeatherData/1")

    /** Все поля первой строки провайдера Xiaomi или null, если недоступно. */
    fun read(context: Context): Map<String, String>? = try {
        context.contentResolver.query(URI, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val map = LinkedHashMap<String, String>()
            for (i in 0 until c.columnCount) {
                val v = try { c.getString(i) } catch (e: Exception) { null }
                if (v != null) map[c.getColumnName(i)] = v
            }
            map
        }
    } catch (e: Exception) {
        null
    }

    /** Текст для экрана проверки. */
    fun dump(context: Context): String = try {
        val m = read(context)
        if (m == null) "Провайдер Xiaomi недоступен или пуст"
        else m.entries.joinToString("\n") { "${it.key} = ${it.value}" }
    } catch (e: Exception) {
        "Ошибка: ${e.javaClass.simpleName}: ${e.message}"
    }

    fun pick(map: Map<String, String>, vararg keys: String): String? {
        for (k in keys) {
            val v = map.entries.firstOrNull { it.key.equals(k, true) }?.value
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    fun number(s: String?): Int? =
        s?.let { Regex("-?\\d+").find(it)?.value?.toIntOrNull() }
}
