package com.meshwardrive.app.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.view.MotionEvent
import com.meshwardrive.app.data.CoverageGrid
import com.meshwardrive.app.data.PingRow
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Puntos de ping dibujados directamente en el lienzo: mucho más ligero que un polígono por
 * ping cuando hay miles. Al tocar uno se llama a [onTap] con el ping más cercano.
 * Los fallos «superados» ([faded]: su celda tiene cobertura o el fallo no es concluyente) se
 * dibujan huecos, para no perder el historial sin pintar de rojo donde sí hay cobertura.
 */
class PingDotsOverlay(
    private val pings: List<PingRow>,
    private val density: Float,
    private val faded: Set<Long> = emptySet(),
    private val onTap: (PingRow) -> Unit,
) : Overlay() {

    private val radius = 5.5f * density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.WHITE
    }
    private val hollow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val pt = Point()
    private val gp = GeoPoint(0.0, 0.0)

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val pj = mapView.projection
        val w = mapView.width + radius
        val h = mapView.height + radius
        for (p in pings) {
            gp.setCoords(p.effLat, p.effLon)
            pj.toPixels(gp, pt)
            if (pt.x < -radius || pt.y < -radius || pt.x > w || pt.y > h) continue
            if (p.rowId in faded) {
                hollow.color = (CoverageGrid.pingColor(p) and 0x00FFFFFF) or (0x99 shl 24)
                canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), radius * 0.8f, hollow)
                continue
            }
            fill.color = CoverageGrid.pingColor(p)
            canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), radius, fill)
            canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), radius, stroke)
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val pj = mapView.projection
        val reach = 18f * density
        var best: PingRow? = null
        var bestD = reach * reach
        for (p in pings) {
            gp.setCoords(p.effLat, p.effLon)
            pj.toPixels(gp, pt)
            val dx = pt.x - e.x
            val dy = pt.y - e.y
            val d = dx * dx + dy * dy
            if (d <= bestD) {
                bestD = d
                best = p
            }
        }
        val hit = best ?: return false
        onTap(hit)
        return true
    }
}
