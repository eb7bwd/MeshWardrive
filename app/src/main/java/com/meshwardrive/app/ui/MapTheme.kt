package com.meshwardrive.app.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.appcompat.app.AppCompatDelegate
import org.osmdroid.views.MapView

/**
 * Modo nocturno. La interfaz usa el tema DayNight; el mapa se oscurece con un filtro de color
 * sobre las teselas (también las offline), sin descargar otro mapa. Las imágenes exportadas se
 * generan siempre en claro.
 */
object MapTheme {

    fun applyNightMode(theme: String) {
        AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    fun isNight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Invierte el brillo y gira el tono 180°: el agua sigue azulada y los parques verdosos. */
    private val nightFilter: ColorMatrixColorFilter by lazy {
        val invert = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        val hue180 = ColorMatrix(
            floatArrayOf(
                -0.574f, 1.430f, 0.144f, 0f, 0f,
                0.426f, 0.430f, 0.144f, 0f, 0f,
                0.426f, 1.430f, -0.856f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        val dim = ColorMatrix().apply { setScale(0.85f, 0.85f, 0.88f, 1f) }
        val m = ColorMatrix()
        m.postConcat(invert)
        m.postConcat(hue180)
        m.postConcat(dim)
        ColorMatrixColorFilter(m)
    }

    fun applyToMap(map: MapView) {
        map.overlayManager.tilesOverlay.setColorFilter(if (isNight(map.context)) nightFilter else null)
    }

    /** Ejecuta [block] con el mapa en claro (para exportar imágenes) y lo deja como estaba. */
    fun <T> withLightMap(map: MapView, block: () -> T): T {
        val night = isNight(map.context)
        if (night) map.overlayManager.tilesOverlay.setColorFilter(null)
        try {
            return block()
        } finally {
            if (night) map.overlayManager.tilesOverlay.setColorFilter(nightFilter)
        }
    }
}
