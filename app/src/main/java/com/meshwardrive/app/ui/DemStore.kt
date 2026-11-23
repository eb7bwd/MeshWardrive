package com.meshwardrive.app.ui

import android.content.Context
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Relieve (modelo digital de elevaciones) para la predicción de cobertura.
 *
 * Usa las teselas abiertas «Terrain Tiles» (formato Terrarium, AWS Open Data; datos SRTM y otros)
 * a zoom 12, unos 30 m por píxel en España. Se descargan solo cuando el usuario lo pide, por zonas,
 * y se guardan en la app para funcionar sin conexión. Al descargarlas, el servidor ve qué zona se
 * pide (como con cualquier mapa online); la posición del usuario no se envía nunca.
 */
class DemStore(ctx: Context) {

    companion object {
        const val ZOOM = 12
        const val MAX_TILES = 80
        private const val URL_FMT = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/%d/%d/%d.png"
        private const val CACHE = 24
    }

    private val dir = File(ctx.filesDir, "dem/$ZOOM").apply { mkdirs() }
    private val userAgent = ctx.packageName
    private val decoded = object : LinkedHashMap<Long, IntArray?>(CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, IntArray?>?) = size > CACHE
    }

    private fun n() = (1 shl ZOOM).toDouble()

    fun tileX(lon: Double): Double = (lon + 180.0) / 360.0 * n()

    fun tileY(lat: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * n()
    }

    private fun file(x: Int, y: Int) = File(dir, "${x}_$y.png")

    fun tileCount(): Int = dir.listFiles()?.size ?: 0

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun clear() {
        synchronized(decoded) { decoded.clear() }
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Teselas que cubren una zona (oeste, sur, este, norte). */
    fun tilesFor(west: Double, south: Double, east: Double, north: Double): List<Pair<Int, Int>> {
        val x0 = floor(tileX(west)).toInt()
        val x1 = floor(tileX(east)).toInt()
        val y0 = floor(tileY(north)).toInt()
        val y1 = floor(tileY(south)).toInt()
        val out = ArrayList<Pair<Int, Int>>()
        for (x in x0..x1) for (y in y0..y1) out.add(x to y)
        return out
    }

    fun missing(tiles: List<Pair<Int, Int>>) = tiles.filter { !file(it.first, it.second).exists() }

    /** Descarga las teselas que falten. Se llama fuera del hilo principal. Devuelve las descargadas. */
    fun download(tiles: List<Pair<Int, Int>>, onProgress: (Int, Int) -> Unit): Int {
        val todo = missing(tiles).take(MAX_TILES)
        var done = 0
        for ((i, t) in todo.withIndex()) {
            onProgress(i, todo.size)
            val (x, y) = t
            val conn = URL(String.format(java.util.Locale.US, URL_FMT, ZOOM, x, y)).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                conn.setRequestProperty("User-Agent", userAgent)
                if (conn.responseCode != 200) continue
                val bytes = conn.inputStream.use { it.readBytes() }
                // Solo se guarda si de verdad es una imagen
                if (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) == null) continue
                val tmp = File(dir, "${x}_$y.tmp")
                tmp.writeBytes(bytes)
                tmp.renameTo(file(x, y))
                done++
            } catch (_: Exception) {
            } finally {
                conn.disconnect()
            }
        }
        onProgress(todo.size, todo.size)
        return done
    }

    private fun pixels(x: Int, y: Int): IntArray? {
        val key = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
        synchronized(decoded) {
            if (decoded.containsKey(key)) return decoded[key]
        }
        val f = file(x, y)
        val px = if (!f.exists()) null else runCatching {
            val bmp = BitmapFactory.decodeFile(f.path) ?: return@runCatching null
            val a = IntArray(bmp.width * bmp.height)
            bmp.getPixels(a, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            bmp.recycle()
            a
        }.getOrNull()
        synchronized(decoded) { decoded[key] = px }
        return px
    }

    /** Elevación del terreno en metros, o null si esa zona no está descargada. */
    fun elevation(lat: Double, lon: Double): Double? {
        val fx = tileX(lon)
        val fy = tileY(lat)
        val x = floor(fx).toInt()
        val y = floor(fy).toInt()
        val px = pixels(x, y) ?: return null
        val side = 256
        if (px.size != side * side) return null
        val ix = ((fx - x) * side).toInt().coerceIn(0, side - 1)
        val iy = ((fy - y) * side).toInt().coerceIn(0, side - 1)
        val c = px[iy * side + ix]
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return r * 256.0 + g + b / 256.0 - 32768.0
    }
}
