package com.example.glassweather

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.feature.ExperimentalFeatureAvailabilityApi
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Активность за сегодня. */
data class DayStats(val steps: Int, val kcal: Int, val minutes: Int, val source: String)

/**
 * Источник шагов:
 *  1) Health Connect — туда пишут Xiaomi «Здоровье», Mi Fitness, Google Fit и др.;
 *  2) датчик шагов телефона (считает с момента первого запуска виджета в этот день);
 *  3) последнее сохранённое значение за сегодня.
 *
 * Шаги за день только растут, поэтому показываем максимум из доступных значений —
 * так виджет не «откатывается» к нулю, если в фоне какой-то источник недоступен.
 * Все вызовы блокирующие — выполнять не в главном потоке.
 */
object Steps {
    private const val PREFS = "stats"
    const val BG_READ = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

    val HC_PERMISSIONS = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
    )

    private fun today() = LocalDate.now().toString()

    fun hcAvailable(ctx: Context) =
        HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    /** Нужно ли установить/обновить Health Connect (Android 13 и ниже). */
    fun hcNeedsInstall(ctx: Context) =
        HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED

    /** Разрешения Health Connect, которые стоит запросить (включая фоновое чтение, если поддерживается). */
    @OptIn(ExperimentalFeatureAvailabilityApi::class)
    fun hcRequestSet(ctx: Context): Set<String> {
        if (!hcAvailable(ctx)) return HC_PERMISSIONS
        val client = HealthConnectClient.getOrCreate(ctx)
        val bg = try {
            client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_HEALTH_DATA_BACKGROUND_READ) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        } catch (e: Throwable) { false }
        return if (bg) HC_PERMISSIONS + BG_READ else HC_PERMISSIONS
    }

    fun hcGranted(ctx: Context): Set<String> = if (!hcAvailable(ctx)) emptySet() else try {
        runBlocking {
            withTimeoutOrNull(5000) {
                HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions()
            } ?: emptySet()
        }
    } catch (e: Throwable) { emptySet() }

    fun sensorAvailable(ctx: Context) =
        (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager)
            .getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    fun sensorGranted(ctx: Context) = Build.VERSION.SDK_INT < 29 ||
        ctx.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    /** Последние сохранённые значения — мгновенно, без обращения к источникам. */
    fun cached(ctx: Context): DayStats {
        val p = prefs(ctx)
        val s = Settings(ctx)
        val steps = if (p.getString("day", "") == today()) p.getInt("steps", 0) else 0
        val kcal = if (p.getString("hc_day", "") == today()) p.getInt("hc_kcal", -1) else -1
        return stats(steps, kcal, s.weight, p.getString("src", "") ?: "")
    }

    /** Читает свежие данные из всех доступных источников. */
    fun load(ctx: Context): DayStats {
        val p = prefs(ctx)
        val s = Settings(ctx)
        val hc = if (s.useHealthConnect) readHealthConnect(ctx) else null
        val sensor = readSensor(ctx, p)

        val day = today()
        if (p.getString("hc_day", "") != day) {
            p.edit().putString("hc_day", day).putInt("hc_steps", 0).putInt("hc_kcal", -1).apply()
        }
        if (hc != null) {
            p.edit().putString("hc_day", day)
                .putInt("hc_steps", maxOf(hc.first, p.getInt("hc_steps", 0)))
                .putInt("hc_kcal", if (hc.second >= 0) maxOf(hc.second, p.getInt("hc_kcal", -1)) else p.getInt("hc_kcal", -1))
                .apply()
        }
        val hcSteps = p.getInt("hc_steps", 0)
        val sensorSteps = sensor ?: if (p.getString("day", "") == day) p.getInt("sensor_steps", 0) else 0
        val steps = maxOf(hcSteps, sensorSteps)
        val src = when {
            steps == 0 -> ""
            hcSteps >= sensorSteps -> "Health Connect"
            else -> "Датчик телефона"
        }
        p.edit().putString("day", day).putInt("steps", steps).putString("src", src).apply()
        // калории из Health Connect берём, только если шаги тоже оттуда
        val kcal = if (src == "Health Connect") p.getInt("hc_kcal", -1) else -1
        return stats(steps, kcal, s.weight, src)
    }

    private fun stats(steps: Int, hcKcal: Int, weight: Int, src: String): DayStats {
        // ~0.5 ккал на шаг на кг веса / 1000 (≈0.035 ккал/шаг при 70 кг)
        val kcal = if (hcKcal > 0) hcKcal else (steps * weight * 0.0005).roundToInt()
        val minutes = if (steps > 0) ceil(steps / 110.0).toInt() else 0
        return DayStats(steps, kcal, minutes, src)
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** (шаги, ккал или -1) из Health Connect за сегодня, либо null если недоступно. */
    private fun readHealthConnect(ctx: Context): Pair<Int, Int>? {
        if (!hcAvailable(ctx)) return null
        return try {
            runBlocking {
                withTimeoutOrNull(6000) {
                    val client = HealthConnectClient.getOrCreate(ctx)
                    val granted = client.permissionController.getGrantedPermissions()
                    val stepsPerm = HealthPermission.getReadPermission(StepsRecord::class)
                    if (stepsPerm !in granted) return@withTimeoutOrNull null
                    val metrics = buildSet {
                        add(StepsRecord.COUNT_TOTAL)
                        if (HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class) in granted)
                            add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
                    }
                    val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
                    val r = client.aggregate(
                        AggregateRequest(metrics, TimeRangeFilter.between(start, Instant.now()))
                    )
                    val steps = (r[StepsRecord.COUNT_TOTAL] ?: 0L).toInt()
                    val kcal = r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]
                        ?.inKilocalories?.roundToInt() ?: -1
                    steps to kcal
                }
            }
        } catch (e: Throwable) {
            null // нет фонового доступа, нет сети к сервису и т.п.
        }
    }

    /** Шаги за сегодня по аппаратному счётчику (значение с момента перезагрузки), либо null. */
    private fun readSensor(ctx: Context, p: SharedPreferences): Int? {
        if (!sensorGranted(ctx)) return null
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null

        val thread = HandlerThread("steps").apply { start() }
        val latch = CountDownLatch(1)
        var raw = -1f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (raw < 0f) { raw = e.values[0]; latch.countDown() }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, Handler(thread.looper))
        latch.await(3, TimeUnit.SECONDS)
        sm.unregisterListener(listener)
        thread.quitSafely()
        return if (raw >= 0f) countSensor(p, raw) else null
    }

    /** Переводит показание счётчика «с момента загрузки» в шаги за сегодня. */
    private fun countSensor(p: SharedPreferences, raw: Float): Int {
        val day = today()
        var base = p.getFloat("base", -1f)
        var carry = p.getInt("carry", 0)
        val last = p.getFloat("last", -1f)
        val lastDay = p.getString("sensor_day", null) ?: p.getString("day", "") ?: ""
        when {
            base < 0f || last < 0f -> { base = raw; carry = 0 }           // первый запуск
            lastDay != day -> {                                            // наступил новый день
                // шаги после последнего замера относим к сегодняшнему дню (замеры каждые ~5 мин)
                base = if (raw >= last && lastDay == LocalDate.now().minusDays(1).toString()) last else raw
                carry = 0
                if (raw < last) base = 0f                                  // ещё и перезагрузка
            }
            raw < last -> { carry += (last - base).toInt(); base = 0f }     // перезагрузка телефона
        }
        val steps = (carry + (raw - base)).toInt().coerceAtLeast(0)
        p.edit().putString("sensor_day", day).putFloat("base", base).putInt("carry", carry)
            .putFloat("last", raw).putInt("sensor_steps", steps).apply()
        return steps
    }
}
