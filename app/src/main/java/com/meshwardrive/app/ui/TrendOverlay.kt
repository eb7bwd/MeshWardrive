package com.meshwardrive.app.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import com.meshwardrive.app.data.CoverageGrid
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Marca las celdas donde la cobertura ha cambiado respecto a lo medido antes: ▲ cobertura nueva
 * (antes solo había fallos y ahora hay respuesta) y ▼ cobertura perdida. Los datos antiguos no se
 * borran: se ven al tocar la celda o en la ficha de la pantalla de cobertura.
 */
class TrendOverlay(private val cells: List<CoverageGrid.Cell>, density: Float) : Overlay() {

    private val r = 9f * density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.WHITE
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val pt = Point()
    private val gp = GeoPoint(0.0, 0.0)

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val pj = mapView.projection
        for (c in cells) {
            if (c.trend == CoverageGrid.Trend.NONE) continue
            gp.setCoords(c.latC, c.lonC)
            pj.toPixels(gp, pt)
            if (pt.x < -r || pt.y < -r || pt.x > mapView.width + r || pt.y > mapView.height + r) continue
            val newCov = c.trend == CoverageGrid.Trend.NEW
            bg.color = if (newCov) CoverageGrid.TREND_NEW else CoverageGrid.TREND_LOST
            val x = pt.x.toFloat()
            val y = pt.y.toFloat()
            canvas.drawCircle(x, y, r, bg)
            canvas.drawCircle(x, y, r, ring)
            canvas.drawText(if (newCov) "▲" else "▼", x, y + text.textSize * 0.36f, text)
        }
    }
}
