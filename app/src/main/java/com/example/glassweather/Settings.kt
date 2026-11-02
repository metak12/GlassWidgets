package com.example.glassweather

import android.content.Context

/** Все настройки приложения в одном месте. */
class Settings(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    var city: String
        get() = p.getString("city", null)?.takeIf { it.isNotBlank() } ?: "Курчатов"
        set(v) = p.edit().putString("city", v).apply()

    /** Брать текущую температуру/описание из приложения «Погода» Xiaomi. */
    var useXiaomi: Boolean
        get() = p.getBoolean("use_xiaomi", true)
        set(v) = p.edit().putBoolean("use_xiaomi", v).apply()

    var stepGoal: Int
        get() = p.getInt("step_goal", 8000)
        set(v) = p.edit().putInt("step_goal", v).apply()

    /** Вес в кг — для оценки калорий, если их нет в Health Connect. */
    var weight: Int
        get() = p.getInt("weight", 70)
        set(v) = p.edit().putInt("weight", v).apply()

    var useHealthConnect: Boolean
        get() = p.getBoolean("use_hc", true)
        set(v) = p.edit().putBoolean("use_hc", v).apply()

    /** 0 — светлое стекло, 1 — обычное, 2 — тёмное. */
    var glass: Int
        get() = p.getInt("glass", 1)
        set(v) = p.edit().putInt("glass", v).apply()

    val glassRes: Int
        get() = when (glass) {
            0 -> R.drawable.widget_glass_bg_light
            2 -> R.drawable.widget_glass_bg_dark
            else -> R.drawable.widget_glass_bg
        }
}
