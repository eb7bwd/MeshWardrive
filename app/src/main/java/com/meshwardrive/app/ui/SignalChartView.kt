package com.meshwardrive.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.meshwardrive.app.data.Analysis
import com.meshwardrive.app.data.CoverageGrid
import com.meshwardrive.app.data.PingStatus
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/** Gráfica de ruido estimado (dBm) frente al tiempo de la sesión, con los pings como puntos pulsables. */
class SignalChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var series: Analysis.Series? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Se llama al tocar un punto: latitud, longitud y texto descriptivo. */
    var onPick: ((Double, Double, String) -> Unit)? = null
    var emptyText: String = ""

    private class Hit(val x: Float, val y: Float, val lat: Double, val lon: Double, val label: String, val event: Boolean)

    private val hits = ArrayList<Hit>()
    private val d = resources.displayMetrics.density
    private val bg = Color.rgb(15, 23, 42)
    private val grid = Paint().apply { color = Color.rgb(40, 52, 78); strokeWidth = 1f * d }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 208, 224); textSize = 11f * d }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = Color.WHITE
    }

    private fun eventColor(s: PingStatus): Int = when {
        s == PingStatus.ACK_DEST -> Color.rgb(77, 208, 225)
        s.isOk -> CoverageGrid.GOOD
        s.isFail -> Color.rgb(158, 158, 158)
        else -> Color.rgb(255, 213, 79)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bg)
        hits.clear()
        val s = series
        val left = 44f * d
        val right = width - 22f * d
        val top = 24f * d
        val bottom = height - 24f * d
        if (s == null || (s.noise.isEmpty() && s.events.isEmpty()) || right <= left || bottom <= top) {
            label.textAlign = Paint.Align.CENTER
            canvas.drawText(emptyText, width / 2f, height / 2f, label)
            return
        }

        var lo = -125f
        var hi = -85f
        if (s.noise.isNotEmpty()) {
            lo = floor((s.noise.minOf { it.dbm } - 3f) / 5f) * 5f
            hi = ceil((s.noise.maxOf { it.dbm } + 3f) / 5f) * 5f
            if (hi - lo < 15f) hi = lo + 15f
        }
        val t0 = s.start
        val span = maxOf(s.end - s.start, 60_000L).toFloat()
        fun px(ts: Long) = left + (ts - t0) / span * (right - left)
        fun py(v: Float) = bottom - (v - lo) / (hi - lo) * (bottom - top)

        // Rejilla y etiquetas
        label.textAlign = Paint.Align.RIGHT
        val step = if (hi - lo > 40f) 10f else 5f
        var v = lo
        while (v <= hi + 0.1f) {
            val y = py(v)
            canvas.drawLine(left, y, right, y, grid)
            canvas.drawText(v.toInt().toString(), left - 6f * d, y + 4f * d, label)
            v += step
        }
        label.textAlign = Paint.Align.CENTER
        for (i in 0..4) {
            val x = left + (right - left) * i / 4f
            canvas.drawLine(x, top, x, bottom, grid)
            val sec = (span * i / 4f / 1000f).toInt()
            canvas.drawText("%d:%02d".format(sec / 60, sec % 60), x, height - 8f * d, label)
        }
        label.textAlign = Paint.Align.LEFT
        canvas.drawText("dBm", 6f * d, 14f * d, label)

        // Línea de ruido estimado
        if (s.noise.isNotEmpty()) {
            val path = Path()
            val area = Path()
            s.noise.forEachIndexed { i, n ->
                val x = px(n.ts)
                val y = py(n.dbm)
                if (i == 0) { path.moveTo(x, y); area.moveTo(x, bottom); area.lineTo(x, y) } else { path.lineTo(x, y); area.lineTo(x, y) }
                hits.add(Hit(x, y, n.lat, n.lon, "%.0f dBm".format(n.dbm), false))
            }
            area.lineTo(px(s.noise.last().ts), bottom)
            area.close()
            fill.shader = LinearGradient(
                0f, top, 0f, bottom, Color.argb(90, 76, 175, 80), Color.argb(25, 76, 175, 80), Shader.TileMode.CLAMP,
            )
            canvas.drawPath(area, fill)
            line.shader = LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(Color.rgb(229, 57, 53), Color.rgb(255, 167, 38), Color.rgb(76, 175, 80), Color.rgb(76, 175, 80)),
                floatArrayOf(0f, 0.3f, 0.6f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawPath(path, line)
            if (s.noise.size == 1) canvas.drawCircle(px(s.noise[0].ts), py(s.noise[0].dbm), 3f * d, dotStroke)
        }

        // Pings: sobre la línea (interpolando) o en la base si no hay ruido medido
        val r = 6f * d
        for (e in s.events) {
            val x = px(e.ts)
            val y = interpolate(s.noise, e.ts)?.let { py(it) } ?: (bottom - r)
            dot.color = eventColor(e.status)
            canvas.drawCircle(x, y, r, dot)
            canvas.drawCircle(x, y, r, dotStroke)
            hits.add(Hit(x, y, e.lat, e.lon, e.text, true))
        }
    }

    private fun interpolate(noise: List<Analysis.NoisePoint>, ts: Long): Float? {
        if (noise.isEmpty()) return null
        if (ts <= noise.first().ts) return noise.first().dbm
        if (ts >= noise.last().ts) return noise.last().dbm
        for (i in 1 until noise.size) {
            val b = noise[i]
            if (ts <= b.ts) {
                val a = noise[i - 1]
                val f = if (b.ts == a.ts) 0f else (ts - a.ts).toFloat() / (b.ts - a.ts)
                return a.dbm + (b.dbm - a.dbm) * f
            }
        }
        return noise.last().dbm
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val reach = 28f * d
                // Primero los pings (por distancia); si no, la muestra de ruido más cercana en horizontal
                val ev = hits.filter { it.event }.minByOrNull { hypot(it.x - event.x, it.y - event.y) }
                    ?.takeIf { hypot(it.x - event.x, it.y - event.y) <= reach }
                val h = ev ?: hits.filter { !it.event }.minByOrNull { abs(it.x - event.x) }
                    ?.takeIf { abs(it.x - event.x) <= reach }
                if (h != null) onPick?.invoke(h.lat, h.lon, h.label)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
