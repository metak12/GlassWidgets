package com.example.glassweather

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SysSettings
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import kotlin.concurrent.thread

/** Экран настроек виджетов. */
class MainActivity : Activity() {

    private lateinit var settings: Settings
    private val hcContract = PermissionController.createRequestPermissionResultContract()

    private val previewWeather by lazy { findViewById<View>(R.id.preview_weather) }
    private val previewStats by lazy { findViewById<View>(R.id.preview_stats) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        setContentView(R.layout.activity_main)
        applyInsets()

        setupWeather()
        setupActivity()
        setupLook()
        setupWidgets()

        findViewById<TextView>(R.id.version).text = try {
            "Версия " + packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { "" }

        // при первом запуске сразу попросим доступ к датчику шагов
        if (!Steps.sensorGranted(this) && savedInstanceState == null &&
            !getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("asked_sensor", false)
        ) {
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("asked_sensor", true).apply()
            requestSensor()
        }
    }

    override fun onResume() {
        super.onResume()
        // В приложении (на переднем плане) Health Connect всегда доступен — заодно обновим виджет.
        updateActivityStatus()
        reloadSteps()
    }

    // ---------- Погода ----------

    private fun setupWeather() {
        val input = findViewById<EditText>(R.id.city_input)
        val place = findViewById<TextView>(R.id.place)
        input.setText(settings.city)
        input.setSelection(input.text.length)

        val save = {
            val city = input.text.toString().trim()
            if (city.isEmpty()) {
                input.error = "Введите город"
            } else {
                settings.city = city
                hideKeyboard(input)
                place.text = "Ищу «$city»…"
                updateWeatherPreview()
                thread {
                    val found = WeatherApi.findPlace(city)
                    runOnUiThread { place.text = found }
                }
                WeatherWidget.refreshAll(this)
            }
        }
        findViewById<View>(R.id.save_city).setOnClickListener { save() }
        input.setOnEditorActionListener { _, action, ev ->
            if (action == EditorInfo.IME_ACTION_DONE ||
                (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)
            ) { save(); true } else false
        }

        val sw = findViewById<Switch>(R.id.use_xiaomi)
        val installed = isInstalled("com.miui.weather2")
        sw.isChecked = settings.useXiaomi
        if (!installed) findViewById<TextView>(R.id.xiaomi_sub).text = "Приложение «Погода» Xiaomi не найдено"
        sw.setOnCheckedChangeListener { _, on ->
            settings.useXiaomi = on
            WeatherWidget.refreshAll(this)
        }
        updateWeatherPreview()
    }

    private fun updateWeatherPreview() {
        previewWeather.setBackgroundResource(settings.glassRes)
        previewWeather.findViewById<TextView>(R.id.city).text = settings.city
    }

    // ---------- Активность ----------

    private fun setupActivity() {
        findViewById<View>(R.id.hc_btn).setOnClickListener { connectHealth() }
        findViewById<View>(R.id.sensor_btn).setOnClickListener { requestSensor() }

        val goal = findViewById<SeekBar>(R.id.goal)
        val goalValue = findViewById<TextView>(R.id.goal_value)
        goal.progress = ((settings.stepGoal - 2000) / 500).coerceIn(0, goal.max)
        goalValue.text = fmt(settings.stepGoal)
        goal.setOnSeekBarChangeListener(seek { p, done ->
            val v = 2000 + p * 500
            goalValue.text = fmt(v)
            settings.stepGoal = v
            updateStatsPreview()
            if (done) StatsWidget.redraw(this)
        })

        val weight = findViewById<SeekBar>(R.id.weight)
        val weightValue = findViewById<TextView>(R.id.weight_value)
        weight.progress = (settings.weight - 40).coerceIn(0, weight.max)
        weightValue.text = "${settings.weight} кг"
        weight.setOnSeekBarChangeListener(seek { p, done ->
            val v = 40 + p
            weightValue.text = "$v кг"
            settings.weight = v
            updateStatsPreview()
            if (done) StatsWidget.redraw(this)
        })
        updateStatsPreview()
    }

    private fun updateActivityStatus() {
        val hcSub = findViewById<TextView>(R.id.hc_sub)
        val hcBtn = findViewById<TextView>(R.id.hc_btn)
        val sensorSub = findViewById<TextView>(R.id.sensor_sub)
        val sensorBtn = findViewById<TextView>(R.id.sensor_btn)

        when {
            Steps.sensorAvailable(this).not() -> {
                sensorSub.text = "В телефоне нет датчика шагов"; sensorBtn.visibility = View.GONE
            }
            Steps.sensorGranted(this) -> {
                sensorSub.text = "✓ Доступ разрешён — запасной источник"; sensorBtn.visibility = View.GONE
            }
            else -> {
                sensorSub.text = "Нет доступа к физической активности"; sensorBtn.visibility = View.VISIBLE
            }
        }

        if (!Steps.hcAvailable(this)) {
            hcSub.text = if (Steps.hcNeedsInstall(this))
                "Установите Health Connect, чтобы видеть шаги из Xiaomi «Здоровье»"
            else "Недоступен на этом телефоне"
            hcBtn.text = if (Steps.hcNeedsInstall(this)) "Установить" else ""
            hcBtn.visibility = if (Steps.hcNeedsInstall(this)) View.VISIBLE else View.GONE
            return
        }
        hcSub.text = "Проверяю…"
        thread {
            val granted = Steps.hcGranted(this)
            val need = Steps.hcRequestSet(this)
            runOnUiThread {
                val hasSteps = Steps.HC_PERMISSIONS.first() in granted
                val hasBg = Steps.BG_READ !in need || Steps.BG_READ in granted
                hcSub.text = when {
                    !hasSteps -> "Шаги и калории из Xiaomi «Здоровье», Mi Fitness, Google Fit"
                    !hasBg -> "✓ Подключено. Разрешите фоновый доступ, чтобы виджет обновлялся сам"
                    else -> "✓ Подключено — основной источник шагов"
                }
                hcBtn.text = if (hasSteps && hasBg) "Открыть" else "Подключить"
                hcBtn.visibility = View.VISIBLE
            }
        }
    }

    private fun connectHealth() {
        if (Steps.hcNeedsInstall(this)) {
            val uri = Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")
            openSafely(Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending"))
            return
        }
        if (!Steps.hcAvailable(this)) return
        val hcBtn = findViewById<TextView>(R.id.hc_btn)
        if (hcBtn.text == "Открыть") {
            val i = Intent(if (Build.VERSION.SDK_INT >= 34) HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS
                else "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS")
            openSafely(i)
            return
        }
        try {
            settings.useHealthConnect = true
            startActivityForResult(hcContract.createIntent(this, Steps.hcRequestSet(this)), REQ_HC)
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть Health Connect", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestSensor() {
        if (Build.VERSION.SDK_INT < 29 || Steps.sensorGranted(this)) return
        if (shouldShowRequestPermissionRationale(Manifest.permission.ACTIVITY_RECOGNITION) ||
            !getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("sensor_denied", false)
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), REQ_SENSOR)
        } else {
            // пользователь запретил навсегда — открываем настройки приложения
            openSafely(Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == REQ_SENSOR && Build.VERSION.SDK_INT >= 29 && !Steps.sensorGranted(this) &&
            !shouldShowRequestPermissionRationale(Manifest.permission.ACTIVITY_RECOGNITION)
        ) {
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("sensor_denied", true).apply()
        }
        updateActivityStatus()
        reloadSteps()
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_HC) {
            val granted = hcContract.parseResult(resultCode, data)
            if (granted.isEmpty()) Toast.makeText(this, "Доступ к Health Connect не выдан", Toast.LENGTH_SHORT).show()
            updateActivityStatus()
            reloadSteps()
        }
    }

    /** Свежие шаги → предпросмотр, строка «Сегодня» и виджет на рабочем столе. */
    private fun reloadSteps() {
        updateStatsPreview()
        thread {
            val stats = Steps.load(this)
            runOnUiThread {
                updateStatsPreview(stats)
                StatsWidget.redraw(this, stats)
            }
        }
    }

    private fun updateStatsPreview(stats: DayStats = Steps.cached(this)) {
        previewStats.setBackgroundResource(settings.glassRes)
        val img = previewStats.findViewById<ImageView>(R.id.card)
        val d = resources.displayMetrics.density
        val w = (if (previewStats.width > 0) previewStats.width else (170 * d).toInt())
        val h = (if (img.height > 0) img.height else (166 * d).toInt())
        img.setImageBitmap(StatsWidget.card(w, h, stats, settings.stepGoal))
        if (previewStats.width == 0) previewStats.post { updateStatsPreview(stats) }

        findViewById<TextView>(R.id.today_sub).text = buildString {
            append("${fmt(stats.steps)} шагов · ${stats.kcal} ккал · ${stats.minutes} мин")
            append("\nИсточник: ")
            append(stats.source.ifEmpty { "пока нет данных" })
        }
    }

    // ---------- Оформление ----------

    private fun setupLook() {
        val group = findViewById<RadioGroup>(R.id.glass)
        group.check(when (settings.glass) { 0 -> R.id.glass_light; 2 -> R.id.glass_dark; else -> R.id.glass_normal })
        group.setOnCheckedChangeListener { _, id ->
            settings.glass = when (id) { R.id.glass_light -> 0; R.id.glass_dark -> 2; else -> 1 }
            updateWeatherPreview()
            updateStatsPreview()
            StatsWidget.redraw(this)
            WeatherWidget.refreshAll(this)
        }
    }

    // ---------- Виджеты ----------

    private fun setupWidgets() {
        findViewById<View>(R.id.add_weather).setOnClickListener { pin(WeatherWidget::class.java) }
        findViewById<View>(R.id.add_stats).setOnClickListener { pin(StatsWidget::class.java) }
        findViewById<View>(R.id.refresh).setOnClickListener {
            WeatherWidget.refreshAll(this)
            StatsWidget.refresh(this)
            reloadSteps()
            Toast.makeText(this, "Виджеты обновляются", Toast.LENGTH_SHORT).show()
        }
        val dumpText = findViewById<TextView>(R.id.dump_text)
        findViewById<View>(R.id.dump).setOnClickListener {
            if (dumpText.visibility == View.VISIBLE) {
                dumpText.visibility = View.GONE
            } else {
                dumpText.text = XiaomiWeather.dump(this)
                dumpText.visibility = View.VISIBLE
            }
        }
    }

    private fun pin(cls: Class<*>) {
        val mgr = AppWidgetManager.getInstance(this)
        if (mgr.isRequestPinAppWidgetSupported) {
            val ok = mgr.requestPinAppWidget(ComponentName(this, cls), null, null)
            if (ok) return
        }
        Toast.makeText(
            this, "Долгое нажатие на рабочем столе → Виджеты → Glass Виджеты", Toast.LENGTH_LONG
        ).show()
    }

    // ---------- Вспомогательное ----------

    @Suppress("DEPRECATION")
    private fun applyInsets() {
        val content = findViewById<View>(R.id.content)
        val top = content.paddingTop
        val bottom = content.paddingBottom
        content.setOnApplyWindowInsetsListener { v, insets ->
            val (t, b) = if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                i.top to i.bottom
            } else insets.systemWindowInsetTop to insets.systemWindowInsetBottom
            v.setPadding(v.paddingLeft, top + t, v.paddingRight, bottom + b)
            insets
        }
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }

    private fun seek(cb: (Int, Boolean) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) cb(p, false) }
        override fun onStartTrackingTouch(s: SeekBar) {}
        override fun onStopTrackingTouch(s: SeekBar) { cb(s.progress, true) }
    }

    private fun hideKeyboard(v: View) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
    }

    private fun isInstalled(pkg: String) = try {
        packageManager.getPackageInfo(pkg, 0); true
    } catch (e: Exception) { false }

    private fun openSafely(i: Intent) = try {
        startActivity(i)
    } catch (e: Exception) {
        Toast.makeText(this, "Не удалось открыть", Toast.LENGTH_SHORT).show()
    }

    private fun fmt(n: Int) = "%,d".format(n).replace(',', ' ')

    companion object {
        private const val REQ_HC = 10
        private const val REQ_SENSOR = 11
    }
}
