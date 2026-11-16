package com.meshwardrive.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.viewpager.widget.ViewPager
import org.osmdroid.views.MapView

/**
 * Páginas deslizables. Sobre el mapa el gesto horizontal mueve el mapa, no la página:
 * de la pestaña Mapa se sale con la barra inferior (o deslizando sobre la tarjeta de estado).
 */
class PagesViewPager @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : ViewPager(context, attrs) {

    override fun canScroll(v: View, checkV: Boolean, dx: Int, x: Int, y: Int): Boolean {
        if (v is MapView) return true
        return super.canScroll(v, checkV, dx, x, y)
    }
}
