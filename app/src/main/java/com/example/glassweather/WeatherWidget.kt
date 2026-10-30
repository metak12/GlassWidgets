package com.example.glassweather

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlin.concurrent.thread

class WeatherWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        thread {
            try {
                val prefs = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                val city = prefs.getString("city", "Курчатов") ?: "Курчатов"
                val data = try { WeatherApi.load(city) } catch (e: Exception) { null }

                // данные приложения погоды Xiaomi (если доступны)
                val x = XiaomiWeather.read(context)
                val xTemp = x?.let { XiaomiWeather.number(XiaomiWeather.pick(it, "temperature", "temp", "current_temp")) }
                val xDesc = x?.let { XiaomiWeather.pick(it, "description", "weather_desc", "weather_name") }
                val xCity = x?.let { XiaomiWeather.pick(it, "city_name", "city") }

                for (id in ids) {
                    val views = RemoteViews(context.packageName, R.layout.widget_weather)
                    if (data != null) {
                        views.setTextViewText(R.id.city, xCity ?: data.city)
                        views.setTextViewText(R.id.desc, xDesc ?: data.description)
                        views.setTextViewText(R.id.icon, data.emoji)
                        views.setTextViewText(R.id.temp, "${xTemp ?: data.temp}°")
                        views.setTextViewText(R.id.minmax, "Макс. ${data.max}° Мин. ${data.min}°")
                        views.setTextViewText(R.id.aqi, data.aqiText)
                    } else if (xTemp != null) {
                        views.setTextViewText(R.id.city, xCity ?: city)
                        views.setTextViewText(R.id.desc, xDesc ?: "")
                        views.setTextViewText(R.id.temp, "$xTemp°")
                        views.setTextViewText(R.id.minmax, "")
                        views.setTextViewText(R.id.aqi, "")
                    } else {
                        views.setTextViewText(R.id.desc, "Нет данных")
                    }
                    val intent = Intent(context, WeatherWidget::class.java).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(id))
                    }
                    val pi = PendingIntent.getBroadcast(
                        context, id, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    // тап по иконке — обновить данные
                    views.setOnClickPendingIntent(R.id.icon, pi)
                    // тап по виджету — открыть приложение погоды Xiaomi
                    val launch = context.packageManager.getLaunchIntentForPackage("com.miui.weather2")
                    if (launch != null) {
                        val open = PendingIntent.getActivity(
                            context, id + 100000, launch,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        views.setOnClickPendingIntent(R.id.root, open)
                    } else {
                        views.setOnClickPendingIntent(R.id.root, pi)
                    }
                    manager.updateAppWidget(id, views)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, WeatherWidget::class.java))
            val intent = Intent(context, WeatherWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }
}
