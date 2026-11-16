package com.meshwardrive.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Mapa de calor: cada medición es una mancha difuminada de su color. Se pinta en un bitmap
 * a un cuarto de resolución y se amplía con filtrado, lo que funde las manchas vecinas.
 */
class HeatmapOverlay(
    private val points: List<HeatPoint>,
    private val radiusM: Float,
    private val density: Float,
) : Overlay() {

    class HeatPoint(val lat: Double, val lon: Double, val color: Int)

    private val scale = 4
    private var bmp: Bitmap? = null
    private var bmpCanvas: Canvas? = null
    private val shaders = HashMap<Int, Paint>()
    private val out = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { alpha = 215 }
    private val pt = Point()
    private val gp = GeoPoint(0.0, 0.0)

    private fun paintFor(color: Int): Paint = shaders.getOrPut(color) {
        val c0 = (color and 0x00FFFFFF) or (0xD0 shl 24)
        val c1 = (color and 0x00FFFFFF) or (0x70 shl 24)
        val c2 = color and 0x00FFFFFF
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                0f, 0f, 1f, intArrayOf(c0, c1, c2), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
            )
        }
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || points.isEmpty()) return
        val w = mapView.width / scale
        val h = mapView.height / scale
        if (w <= 0 || h <= 0) return
        var b = bmp
        if (b == null || b.width != w || b.height != h) {
            b?.recycle()
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp = b
            bmpCanvas = Canvas(b)
        }
        val c = bmpCanvas ?: return
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val pj = mapView.projection
        val minPx = 22f * density
        val r = maxOf(pj.metersToPixels(radiusM) * 1.2f, minPx) / scale
        val margin = (r * scale).toInt()
        val sw = mapView.width
        val sh = mapView.height
        for (p in points) {
            gp.setCoords(p.lat, p.lon)
            pj.toPixels(gp, pt)
            if (pt.x < -margin || pt.y < -margin || pt.x > sw + margin || pt.y > sh + margin) continue
            c.save()
            c.translate(pt.x.toFloat() / scale, pt.y.toFloat() / scale)
            c.scale(r, r)
            c.drawCircle(0f, 0f, 1f, paintFor(p.color))
            c.restore()
        }
        // El lienzo del mapa puede venir girado o escalado: se deshace para pintar en píxeles de pantalla
        pj.save(canvas, false, false)
        canvas.drawBitmap(b, null, Rect(0, 0, sw, sh), out)
        pj.restore(canvas, false)
    }

    override fun onDetach(mapView: MapView?) {
        bmp?.recycle()
        bmp = null
        bmpCanvas = null
    }
}
