package com.meshwardrive.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.meshwardrive.app.data.Analysis
import com.meshwardrive.app.data.CoverageGrid
import com.meshwardrive.app.data.PingStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Gráfica de ruido estimado (dBm) a lo largo de uno o varios wardrives, con los pings como puntos.
 * - Tocar un punto abre un recuadro con hora, NF, RSSI y SNR; tocar el recuadro lleva al mapa.
 * - Pellizcar amplía, arrastrar desplaza (con zoom) y doble toque vuelve a la vista completa.
 */
class SignalChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var series: Analysis.Series? = null
        set(value) {
            field = value
            v0 = 0.0
            v1 = 1.0
            selected = null
            invalidate()
        }

    /** Se llama al tocar el recuadro: latitud, longitud y título. */
    var onPick: ((Double, Double, String) -> Unit)? = null
    var emptyText: String = ""
    var mapHint: String = "Map"
    var statusText: (PingStatus) -> String = { it.name }

    private class Hit(
        val x: Float, val y: Float, val lat: Double, val lon: Double,
        val title: String, val lines: List<String>, val event: Boolean,
    )

    private val hits = ArrayList<Hit>()
    private var selected: Hit? = null
    private val tipRect = RectF()

    // Parte visible, como fracción del total (0..1)
    private var v0 = 0.0
    private var v1 = 1.0
    private val zoomed get() = v1 - v0 < 0.999

    private val d = resources.displayMetrics.density
    private val bg = Color.rgb(15, 23, 42)
    private val grid = Paint().apply { color = Color.rgb(40, 52, 78); strokeWidth = 1f * d }
    private val sep = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(120, 130, 160); strokeWidth = 1.2f * d
        pathEffect = DashPathEffect(floatArrayOf(5f * d, 4f * d), 0f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 208, 224); textSize = 11f * d }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = Color.WHITE
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = Color.rgb(255, 213, 79)
    }
    private val tipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(240, 245, 247, 250) }
    private val tipText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 24, 32); textSize = 12f * d }
    private val tipBold = Paint(tipText).apply { isFakeBoldText = true }
    private val tipLink = Paint(tipText).apply { color = Color.rgb(21, 101, 192); textSize = 11.5f * d }
    private val timeFmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())

    private var left = 0f
    private var right = 0f
    private var top = 0f
    private var bottom = 0f

    private fun eventColor(s: PingStatus): Int = when {
        s == PingStatus.ACK_DEST -> Color.rgb(77, 208, 225)
        s.isOk -> CoverageGrid.GOOD
        s.isFail -> Color.rgb(158, 158, 158)
        else -> Color.rgb(255, 213, 79)
    }

    private fun fmt1(v: Float) = String.format(Locale.US, "%.1f", v)

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bg)
        hits.clear()
        val s = series
        left = 44f * d
        right = width - 22f * d
        top = 24f * d
        bottom = height - 24f * d
        if (s == null || (s.noise.isEmpty() && s.events.isEmpty()) || right <= left || bottom <= top) {
            label.textAlign = Paint.Align.CENTER
            canvas.drawText(emptyText, width / 2f, height / 2f, label)
            return
        }
        val span = maxOf(s.end - s.start, 60_000L).toDouble()
        val vs = s.start + v0 * span
        val ve = s.start + v1 * span
        fun px(vt: Long) = (left + (vt - vs) / (ve - vs) * (right - left)).toFloat()
        fun visible(vt: Long) = vt >= vs - 1 && vt <= ve + 1

        // Escala vertical con lo que se ve (o con todo si no hay nada a la vista)
        val visibleNoise = s.noise.filter { visible(it.vt) }.ifEmpty { s.noise }
        var lo = -125f
        var hi = -85f
        if (visibleNoise.isNotEmpty()) {
            lo = floor((visibleNoise.minOf { it.dbm } - 3f) / 5f) * 5f
            hi = ceil((visibleNoise.maxOf { it.dbm } + 3f) / 5f) * 5f
            if (hi - lo < 15f) hi = lo + 15f
        }
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
            val sec = ((v0 + (v1 - v0) * i / 4.0) * span / 1000.0).toInt()
            val txt = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)
            else "%d:%02d".format(sec / 60, sec % 60)
            canvas.drawText(txt, x, height - 8f * d, label)
        }
        label.textAlign = Paint.Align.LEFT
        canvas.drawText("dBm", 6f * d, 14f * d, label)
        if (zoomed) {
            label.textAlign = Paint.Align.RIGHT
            canvas.drawText("×%.1f".format(Locale.US, 1.0 / (v1 - v0)), right, 14f * d, label)
        }

        canvas.save()
        canvas.clipRect(left, top - 8f * d, right, bottom + 1f)

        // Separadores entre wardrives
        for (b in s.boundaries) if (visible(b)) canvas.drawLine(px(b), top, px(b), bottom, sep)

        // Línea de ruido estimado
        if (s.noise.isNotEmpty()) {
            val path = Path()
            val area = Path()
            var started = false
            var lastX = 0f
            for (n in s.noise) {
                val x = px(n.vt)
                val y = py(n.dbm)
                if (!started) {
                    path.moveTo(x, y); area.moveTo(x, bottom); area.lineTo(x, y); started = true
                } else {
                    path.lineTo(x, y); area.lineTo(x, y)
                }
                lastX = x
                if (visible(n.vt)) {
                    val lines = ArrayList<String>()
                    lines.add(timeFmt.format(Date(n.ts)))
                    lines.add("NF ${fmt1(n.dbm)} dBm")
                    lines.add("RSSI ${n.rssi ?: "—"} dBm · SNR ${n.snr?.let { fmt1(it) } ?: "—"} dB")
                    hits.add(Hit(x, y, n.lat, n.lon, "NF ${fmt1(n.dbm)} dBm", lines, false))
                }
            }
            area.lineTo(lastX, bottom)
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
            if (s.noise.size == 1) canvas.drawCircle(px(s.noise[0].vt), py(s.noise[0].dbm), 3f * d, dotStroke)
        }

        // Pings: sobre la línea (interpolando) o en la base si no hay ruido medido
        val r = 6f * d
        for (e in s.events) {
            if (!visible(e.vt)) continue
            val x = px(e.vt)
            val y = interpolate(s.noise, e.vt)?.let { py(it) } ?: (bottom - r)
            dot.color = eventColor(e.status)
            canvas.drawCircle(x, y, r, dot)
            canvas.drawCircle(x, y, r, dotStroke)
            val lines = ArrayList<String>()
            lines.add(timeFmt.format(Date(e.ts)))
            lines.add(statusText(e.status))
            e.dbm?.let { lines.add("NF ${fmt1(it)} dBm") }
            if (e.rssi != null || e.snr != null) {
                lines.add("RSSI ${e.rssi ?: "—"} dBm · SNR ${e.snr?.let { fmt1(it) } ?: "—"} dB")
            }
            hits.add(Hit(x, y, e.lat, e.lon, e.text, lines, true))
        }
        canvas.restore()

        // Punto elegido y su recuadro (se recoloca si cambió el zoom)
        val sel = selected?.let { old ->
            hits.firstOrNull { it.event == old.event && it.lat == old.lat && it.lon == old.lon && it.title == old.title }
        }
        selected = sel
        if (sel != null) {
            canvas.drawCircle(sel.x, sel.y, r + 3f * d, ring)
            drawTip(canvas, sel)
        } else {
            tipRect.setEmpty()
        }
    }

    private fun drawTip(canvas: Canvas, h: Hit) {
        val pad = 8f * d
        val lineH = 16f * d
        val all = listOf(h.title) + h.lines
        var w = tipLink.measureText("▸ $mapHint")
        all.forEachIndexed { i, s -> w = maxOf(w, (if (i == 0) tipBold else tipText).measureText(s)) }
        w += 2 * pad
        val hgt = 2 * pad + lineH * (all.size + 1) - 4f * d
        var x = h.x - w / 2
        x = x.coerceIn(4f * d, width - w - 4f * d)
        var y = h.y - hgt - 12f * d
        if (y < 4f * d) y = h.y + 12f * d
        y = y.coerceAtMost(height - hgt - 4f * d)
        tipRect.set(x, y, x + w, y + hgt)
        canvas.drawRoundRect(tipRect, 6f * d, 6f * d, tipBg)
        var ty = y + pad + 11f * d
        all.forEachIndexed { i, s ->
            canvas.drawText(s, x + pad, ty, if (i == 0) tipBold else tipText)
            ty += lineH
        }
        canvas.drawText("▸ $mapHint", x + pad, ty, tipLink)
    }

    private fun interpolate(noise: List<Analysis.NoisePoint>, vt: Long): Float? {
        if (noise.isEmpty()) return null
        if (vt <= noise.first().vt) return noise.first().dbm
        if (vt >= noise.last().vt) return noise.last().dbm
        for (i in 1 until noise.size) {
            val b = noise[i]
            if (vt <= b.vt) {
                val a = noise[i - 1]
                val f = if (b.vt == a.vt) 0f else (vt - a.vt).toFloat() / (b.vt - a.vt)
                return a.dbm + (b.dbm - a.dbm) * f
            }
        }
        return noise.last().dbm
    }

    // ------------------------------------------------------------------ gestos

    private fun minSpan(): Double {
        val s = series ?: return 1.0
        val total = maxOf(s.end - s.start, 60_000L).toDouble()
        return (30_000.0 / total).coerceIn(0.002, 1.0)
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            val w = (right - left).takeIf { it > 0 } ?: return false
            val f = ((det.focusX - left) / w).toDouble().coerceIn(0.0, 1.0)
            val center = v0 + (v1 - v0) * f
            val newSpan = ((v1 - v0) / det.scaleFactor).coerceIn(minSpan(), 1.0)
            var a = center - newSpan * f
            var b = a + newSpan
            if (a < 0) { b -= a; a = 0.0 }
            if (b > 1) { a -= b - 1; b = 1.0 }
            v0 = a.coerceAtLeast(0.0)
            v1 = b.coerceAtMost(1.0)
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!zoomed) return false
            val w = (right - left).takeIf { it > 0 } ?: return false
            val shift = dx / w * (v1 - v0)
            val spanV = v1 - v0
            v0 = (v0 + shift).coerceIn(0.0, 1.0 - spanV)
            v1 = v0 + spanV
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            handleTap(e.x, e.y)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            v0 = 0.0
            v1 = 1.0
            invalidate()
            return true
        }
    })

    private fun handleTap(x: Float, y: Float) {
        val sel = selected
        if (sel != null && tipRect.contains(x, y)) {
            onPick?.invoke(sel.lat, sel.lon, sel.title)
            performClick()
            return
        }
        val reach = 28f * d
        val ev = hits.filter { it.event }.minByOrNull { hypot(it.x - x, it.y - y) }
            ?.takeIf { hypot(it.x - x, it.y - y) <= reach }
        selected = ev ?: hits.filter { !it.event }.minByOrNull { abs(it.x - x) }?.takeIf { abs(it.x - x) <= reach }
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            // Con zoom, el dedo mueve la gráfica y no la página; con dos dedos, siempre
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(zoomed)
            MotionEvent.ACTION_POINTER_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
        }
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
