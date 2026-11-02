package com.example.glassweather

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.sin

class StatsWidget : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TICK -> refresh(context)
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                schedule(context); refresh(context)
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, manager, id, newOptions)
        // размер изменился — перерисовать по последним данным, без повторного чтения датчиков
        manager.updateAppWidget(id, buildViews(context, manager, id, Steps.cached(context)))
    }

    override fun onEnabled(context: Context) = schedule(context)

    override fun onDisabled(context: Context) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(tickIntent(context))
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        schedule(context)
        // сразу показываем последнее значение, затем обновляем свежими данными
        val cached = Steps.cached(context)
        ids.forEach { manager.updateAppWidget(it, buildViews(context, manager, it, cached)) }
        val pending = goAsync()
        thread {
            try {
                val stats = Steps.load(context)
                ids.forEach { manager.updateAppWidget(it, buildViews(context, manager, it, stats)) }
            } catch (_: Throwable) {
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_TICK = "com.example.glassweather.STATS_TICK"
        private const val INTERVAL = 15 * 60 * 1000L

        private fun tickIntent(ctx: Context) = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, StatsWidget::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun schedule(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setInexactRepeating(
                AlarmManager.RTC, System.currentTimeMillis() + INTERVAL, INTERVAL, tickIntent(ctx)
            )
        }

        fun ids(ctx: Context): IntArray = AppWidgetManager.getInstance(ctx)
            .getAppWidgetIds(ComponentName(ctx, StatsWidget::class.java))

        fun refresh(ctx: Context) {
            val ids = ids(ctx)
            if (ids.isEmpty()) return
            ctx.sendBroadcast(
                Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .setClass(ctx, StatsWidget::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            )
        }

        /** Мгновенно перерисовать виджеты по уже известным данным (например, после смены оформления). */
        fun redraw(ctx: Context, stats: DayStats = Steps.cached(ctx)) {
            val mgr = AppWidgetManager.getInstance(ctx)
            ids(ctx).forEach { mgr.updateAppWidget(it, buildViews(ctx, mgr, it, stats)) }
        }

        /** Размер стекла виджета на экране в пикселях. */
        private fun widgetSize(ctx: Context, mgr: AppWidgetManager, id: Int): Pair<Int, Int> {
            val o = mgr.getAppWidgetOptions(id)
            val d = ctx.resources.displayMetrics.density
            var wdp = 0f
            var hdp = 0f
            if (Build.VERSION.SDK_INT >= 31) {
                // Android 12+: точные размеры для текущей ориентации
                @Suppress("DEPRECATION")
                val sizes = o.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                if (!sizes.isNullOrEmpty()) {
                    val land = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    val s = if (land) sizes.maxBy { it.width } else sizes.maxBy { it.height }
                    wdp = s.width; hdp = s.height
                }
            }
            if (wdp <= 0f || hdp <= 0f) {
                val land = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                wdp = o.getInt(if (land) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
                    else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).toFloat()
                hdp = o.getInt(if (land) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
                    else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).toFloat()
            }
            if (wdp <= 0f) wdp = 160f
            if (hdp <= 0f) hdp = wdp
            val inset = ctx.resources.getDimension(R.dimen.glass_inset_v) * 2
            val w = (wdp * d).toInt().coerceIn(120, 1200)
            val h = (hdp * d - inset).toInt().coerceIn(120, 1200)
            return w to h
        }

        private fun buildViews(ctx: Context, mgr: AppWidgetManager, id: Int, stats: DayStats): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_stats)
            val s = Settings(ctx)
            val (w, h) = widgetSize(ctx, mgr, id)
            rv.setInt(R.id.root, "setBackgroundResource", s.glassRes)
            rv.setImageViewBitmap(R.id.card, card(w, h, stats, s.stepGoal))
            rv.setOnClickPendingIntent(R.id.root, openHealth(ctx))
            return rv
        }

        // Открывает встроенное приложение Xiaomi «Здоровье».
        private fun openHealth(ctx: Context): PendingIntent {
            val pm = ctx.packageManager
            val intent = listOf("com.mi.health", "com.xiaomi.wearable", "com.google.android.apps.fitness")
                .firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
                ?: Intent(ctx, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(
                ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * Кольцо и цифры — картинка ровно по размеру стекла, без растягивания.
         * Квадратный виджет: кольцо с цифрами внутри. Широкий: кольцо слева, цифры справа.
         */
        fun card(w: Int, h: Int, stats: DayStats, goal: Int): Bitmap {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val values = listOf(fmt(stats.steps), stats.kcal.toString(), stats.minutes.toString())
            val f = (stats.steps.toFloat() / goal.coerceAtLeast(1)).coerceIn(0f, 1f)

            if (w >= h * 1.6f) {
                // широкий вариант
                val k = h / 480f
                c.scale(k, k)
                ring(c, 240f, 240f, 190f, f)
                percent(c, 240f, f)
                val left = 480f + 10f
                labelsRows(c, left, (w / k) - left - 40f, values)
            } else {
                val k = minOf(w, h) / 480f
                c.translate((w - 480f * k) / 2f, (h - 480f * k) / 2f)
                c.scale(k, k)
                ring(c, 240f, 240f, 190f, f)
                rows(c, 240f, values, true)
            }
            return bmp
        }

        private fun fmt(n: Int) = if (n >= 10000) "%,d".format(n).replace(',', ' ') else n.toString()

        private fun ring(c: Canvas, cx: Float, cy: Float, r: Float, f: Float) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val accent = Color.parseColor("#FF6A3D")
            val oval = RectF(cx - r, cy - r, cx + r, cy + r)
            p.style = Paint.Style.STROKE; p.strokeWidth = 32f; p.strokeCap = Paint.Cap.ROUND
            p.color = Color.parseColor("#55FF6A3D")
            c.drawOval(oval, p)
            if (f > 0.005f) { p.color = accent; c.drawArc(oval, -90f, 360f * f, false, p) }
            val ang = Math.toRadians(-90.0 + 360.0 * f)
            p.style = Paint.Style.FILL; p.color = accent
            c.drawCircle(cx + r * cos(ang).toFloat(), cy + r * sin(ang).toFloat(), 19f, p)
        }

        /** Процент выполнения цели в центре кольца. */
        private fun percent(c: Canvas, cx: Float, f: Float) {
            c.save(); c.translate(cx, 175f); c.scale(1.6f, 1.6f); feet(c); c.restore()
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 96f; textAlign = Paint.Align.CENTER
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
            c.drawText("${(f * 100).toInt()}%", cx, 320f, tp)
        }

        /** Три строки «значок + число» внутри кольца. */
        private fun rows(c: Canvas, cx: Float, values: List<String>, numbers: Boolean) {
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 56f; typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            val iconW = 46f; val gap = 14f; val step = 82f
            // ширина текста ограничена кольцом
            val maxText = 250f
            val widest = values.maxOf { tp.measureText(it) }
            if (widest > maxText) tp.textSize *= maxText / widest
            val block = iconW + if (numbers) gap + values.maxOf { tp.measureText(it) } else 0f
            val left = cx - block / 2f
            for (i in 0..2) {
                val cy = 240f + (i - 1) * step
                c.save(); c.translate(left + iconW / 2f, cy)
                icon(c, i)
                c.restore()
                if (numbers) c.drawText(values[i], left + iconW + gap, cy + 20f, tp)
            }
        }

        /** Для широкого виджета: подписи и крупные числа справа от кольца. */
        private fun labelsRows(c: Canvas, left: Float, width: Float, values: List<String>) {
            val labels = listOf("шагов", "ккал", "мин")
            val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 92f; typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
            val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#B3FFFFFF"); textSize = 48f
            }
            val step = 130f
            for (i in 0..2) {
                val cy = 240f + (i - 1) * step
                val need = 90f + big.measureText(values[i]) + 16f + small.measureText(labels[i])
                val k = if (need > width) width / need else 1f
                c.save(); c.translate(left, cy); c.scale(k, k)
                c.save(); c.translate(26f, 0f); c.scale(1.2f, 1.2f); icon(c, i); c.restore()
                c.drawText(values[i], 70f, 32f, big)
                c.drawText(labels[i], 70f + big.measureText(values[i]) + 16f, 32f, small)
                c.restore()
            }
        }

        private fun icon(c: Canvas, i: Int) = when (i) { 0 -> feet(c); 1 -> flame(c); else -> clock(c) }

        private fun feet(c: Canvas) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF8A3D") }
            for ((dx, dy, rot) in listOf(Triple(-10f, 5f, -12f), Triple(10f, -5f, 12f))) {
                c.save(); c.translate(dx, dy); c.rotate(rot)
                c.drawOval(RectF(-7f, -2f, 7f, 18f), p)
                c.drawOval(RectF(-6f, -14f, 6f, -4f), p)
                c.restore()
            }
        }

        private fun flame(c: Canvas) {
            val path = Path().apply {
                moveTo(0f, -21f)
                cubicTo(5f, -11f, 17f, -4f, 16f, 8f)
                cubicTo(15f, 17f, 8f, 22f, 0f, 22f)
                cubicTo(-8f, 22f, -16f, 16f, -16f, 7f)
                cubicTo(-16f, -2f, -9f, -7f, -7f, -15f)
                cubicTo(-3f, -11f, -1f, -10f, 0f, -21f)
                close()
            }
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFC21A") }
            c.drawPath(path, p)
        }

        private fun clock(c: Canvas) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#34C759") }
            c.drawCircle(0f, 0f, 21f, p)
            p.color = Color.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = 3.6f
            p.strokeCap = Paint.Cap.ROUND
            c.drawLine(0f, 0f, 0f, -11f, p)
            c.drawLine(0f, 0f, 8f, 5f, p)
        }
    }
}
