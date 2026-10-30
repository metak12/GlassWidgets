package com.example.glassweather

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Укажите город для виджета, затем добавьте виджет «Glass Погода» на рабочий стол."
            textSize = 16f
        })
        root.addView(TextView(this).apply {
            text = "Для виджета «Статистика» разрешите доступ к физической активности."
            textSize = 13f
            setPadding(0, pad / 2, 0, 0)
        })
        val input = EditText(this).apply {
            hint = "Город"
            setText(prefs.getString("city", "Курчатов"))
            setSingleLine()
        }
        root.addView(input)

        val result = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
        }

        root.addView(Button(this).apply {
            text = "Сохранить и проверить город"
            setOnClickListener {
                val city = input.text.toString().trim()
                prefs.edit().putString("city", city).apply()
                WeatherWidget.refreshAll(this@MainActivity)
                result.text = "Ищу…"
                thread {
                    val place = WeatherApi.findPlace(city)
                    runOnUiThread { result.text = "Найдено: $place" }
                }
                Toast.makeText(this@MainActivity, "Сохранено", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(Button(this).apply {
            text = "Проверить Xiaomi"
            setOnClickListener { result.text = XiaomiWeather.dump(this@MainActivity) }
        })
        root.addView(ScrollView(this).apply { addView(result) })
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 1)
        } else {
            StatsWidget.refresh(this)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        StatsWidget.refresh(this)
    }
}
