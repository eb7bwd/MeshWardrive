package com.meshwardrive.app.ui

import android.graphics.Color
import android.graphics.DashPathEffect
import androidx.core.content.ContextCompat
import com.meshwardrive.app.R
import com.meshwardrive.app.data.CoverageGrid
import com.meshwardrive.app.data.NodeEntry
import com.meshwardrive.app.data.PingRow
import com.meshwardrive.app.data.PingStatus
import com.meshwardrive.app.data.TextSafety
import com.meshwardrive.app.data.TrackPoint
import com.meshwardrive.app.data.WardriveState
import com.meshwardrive.app.data.labelRes
import com.meshwardrive.app.proto.RoutingError
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.infowindow.InfoWindow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dibuja el mapa: mapa de calor, celdas, puntos de ping, ruta, huecos, nodos y comparaciones.
 * Los cálculos pesados (celdas, huecos, diferencias) llegan ya hechos en [MapData].
 * Todo texto que acaba en un globo del mapa se escapa, porque osmdroid lo interpreta como HTML.
 */
class CoverageRenderer(
    private val map: MapView,
    private val onPingTap: (PingRow) -> Unit,
) {

    data class Layers(
        val heat: Boolean,
        val cells: Boolean,
        val pings: Boolean,
        val passive: Boolean,
        val nodes: Boolean,
        val route: Boolean,
        val gaps: Boolean,
    )

    class MapData(
        val cellSizeM: Int,
        val pings: List<PingRow>,
        val nodes: Collection<NodeEntry>,
        val track: List<TrackPoint>,
        val pingCells: List<CoverageGrid.Cell>,
        val rxCells: List<CoverageGrid.Cell>,
        val gaps: List<CoverageGrid.Cell>,
        /** Si no es null, el mapa muestra la comparación de dos grupos de wardrives. */
        val diff: List<CoverageGrid.DiffCell>?,
        /** Nodo destacado (cobertura de un repetidor). */
        val highlight: NodeEntry?,
    )

    companion object {
        const val ROUTE = 0xFF6A1B9A.toInt()   // morado
        const val GAP = 0xFF00838F.toInt()     // turquesa
        const val BETTER = 0xFF2E7D32.toInt()
        const val WORSE = 0xFFC62828.toInt()
        const val SAME = 0xFF9E9E9E.toInt()
        const val NEW = 0xFF1565C0.toInt()
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    private var folder: FolderOverlay? = null
    private val ctx = map.context
    private val density = ctx.resources.displayMetrics.density
    private val timeFmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
    private fun snr(v: Float) = String.format(Locale.US, "%.1f", v)

    fun render(d: MapData, layers: Layers) {
        InfoWindow.closeAllInfoWindowsOn(map)
        folder?.let { map.overlays.remove(it) }
        val f = FolderOverlay()

        val diff = d.diff
        if (diff != null) {
            for (dc in diff) f.add(diffPolygon(dc))
        } else {
            if (layers.passive) {
                for (c in d.rxCells) {
                    val col = CoverageGrid.PASSIVE
                    f.add(cellPolygon(c, withAlpha(col, 0x45), withAlpha(col, 0x90)).apply {
                        title = ctx.getString(R.string.passive_rx)
                        snippet = TextSafety.htmlText(
                            ctx.getString(R.string.rx_packets, c.count) +
                                (c.bestSnr?.let { "\n" + ctx.getString(R.string.best_snr, snr(it)) } ?: "") +
                                (c.avgSnr?.let { "\n" + ctx.getString(R.string.avg_snr, snr(it)) } ?: "")
                        )
                    })
                }
            }
            if (layers.heat) {
                // Primero los fallos y encima los aciertos, para que un acierto no quede tapado
                val pts = d.pings.filter { it.status.isFail }
                    .map { HeatmapOverlay.HeatPoint(it.lat, it.lon, CoverageGrid.FAIL) } +
                    d.pings.filter { it.status.isOk }
                        .map { HeatmapOverlay.HeatPoint(it.lat, it.lon, CoverageGrid.pingColor(it)) }
                if (pts.isNotEmpty()) f.add(HeatmapOverlay(pts, d.cellSizeM.toFloat(), density))
            }
            if (layers.cells) {
                for (c in d.pingCells) {
                    val col = CoverageGrid.cellColor(c)
                    f.add(cellPolygon(c, withAlpha(col, 0x70), withAlpha(col, 0xC0)).apply {
                        title = ctx.getString(R.string.cell_title)
                        snippet = TextSafety.htmlText(
                            ctx.getString(R.string.cell_snippet, c.count, c.ok, c.fail, c.successPct ?: 0) +
                                (c.bestSnr?.let { "\n" + ctx.getString(R.string.best_snr, snr(it)) } ?: "") +
                                (c.avgSnr?.let { "\n" + ctx.getString(R.string.avg_snr, snr(it)) } ?: "")
                        )
                    })
                }
            }
            if (layers.gaps) {
                val dash = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
                for (c in d.gaps) {
                    f.add(cellPolygon(c, withAlpha(GAP, 0x18), withAlpha(GAP, 0xB0)).apply {
                        outlinePaint.pathEffect = dash
                        infoWindow = null
                    })
                }
            }
        }

        if (layers.route) {
            for ((_, pts) in d.track.groupBy { it.session }) {
                if (pts.size < 2) continue
                val line = Polyline(map)
                line.setPoints(pts.map { GeoPoint(it.lat, it.lon) })
                line.outlinePaint.color = withAlpha(ROUTE, 0xC0)
                line.outlinePaint.strokeWidth = 3f * density
                line.infoWindow = null
                f.add(line)
            }
        }

        if (diff == null && layers.pings && d.pings.isNotEmpty()) {
            f.add(PingDotsOverlay(d.pings, density, onPingTap))
        }

        if (layers.nodes || d.highlight != null) {
            val nodes = if (layers.nodes) d.nodes else listOfNotNull(d.highlight)
            for (n in nodes) {
                val lat = n.lat ?: continue
                val lon = n.lon ?: continue
                val m = Marker(map)
                m.position = GeoPoint(lat, lon)
                val focus = d.highlight?.num == n.num
                ContextCompat.getDrawable(ctx, if (focus) R.drawable.ic_focus else R.drawable.ic_node)?.let { m.icon = it }
                m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                m.title = n.label
                m.snippet = TextSafety.htmlText(
                    "!%08x".format(n.num) +
                        (if (n.lastHeard > 0) "\n" + ctx.getString(R.string.seen, timeFmt.format(Date(n.lastHeard))) else "")
                )
                f.add(m)
            }
        }

        // Debajo de la posición propia (que va al final de la lista)
        val idx = map.overlays.size.coerceAtMost(1)
        map.overlays.add(idx, f)
        folder = f
        map.invalidate()
    }

    private fun diffPolygon(dc: CoverageGrid.DiffCell): Polygon {
        val (col, fillA) = when (dc.change) {
            CoverageGrid.Change.BETTER -> BETTER to 0x80
            CoverageGrid.Change.WORSE -> WORSE to 0x80
            CoverageGrid.Change.SAME -> SAME to 0x50
            CoverageGrid.Change.NEW -> NEW to 0x80
            CoverageGrid.Change.ONLY_BEFORE -> SAME to 0x00
        }
        val poly = cellPolygon(dc.cell, withAlpha(col, fillA), withAlpha(col, 0xC0))
        if (dc.change == CoverageGrid.Change.ONLY_BEFORE) {
            poly.outlinePaint.pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
        }
        poly.title = ctx.getString(
            when (dc.change) {
                CoverageGrid.Change.BETTER -> R.string.cmp_better
                CoverageGrid.Change.WORSE -> R.string.cmp_worse
                CoverageGrid.Change.SAME -> R.string.cmp_same
                CoverageGrid.Change.NEW -> R.string.cmp_new
                CoverageGrid.Change.ONLY_BEFORE -> R.string.cmp_only_before
            }
        )
        fun line(label: Int, c: CoverageGrid.Cell?): String {
            if (c == null) return ctx.getString(label) + ": —"
            return ctx.getString(label) + ": " + (c.successPct?.let { "$it %" } ?: "—") +
                (c.bestSnr?.let { " · SNR ${snr(it)} dB" } ?: "") + " · ${c.count} pings"
        }
        poly.snippet = TextSafety.htmlText(line(R.string.cmp_before, dc.before) + "\n" + line(R.string.cmp_after, dc.after))
        return poly
    }

    /** Texto plano con el detalle de un ping, para el cuadro que se abre al tocarlo. */
    fun describePing(p: PingRow): String = buildString {
        append(timeFmt.format(Date(p.ts)))
        p.acc?.let { append(" · GPS ±%.0f m".format(it)) }
        val mode = if (p.mode == "direct") ctx.getString(R.string.st_direct_to, WardriveState.nodeLabel(p.dest))
        else ctx.getString(R.string.word_channel) + " #${p.channel}"
        append("\n").append(ctx.getString(R.string.mode_lbl, mode)).append(" · hop limit ${p.hopLimit}")
        if (p.relayNode != null || p.relaySnr != null) {
            append("\nRelay: ").append(WardriveState.relayLabel(p.relayNode))
            p.relaySnr?.let { append(" · SNR ${snr(it)} dB") }
            p.relayRssi?.let { append(" · RSSI $it dBm") }
        }
        if (p.status == PingStatus.ACK_DEST) {
            append("\nACK: ").append(WardriveState.nodeLabel(p.ackFrom ?: 0))
            p.ackSnr?.let { append(" · SNR ${snr(it)} dB") }
            p.ackRssi?.let { append(" · RSSI $it dBm") }
            p.ackHops?.let { append(" · ").append(ctx.getString(R.string.hops, it)) }
            p.ackRelayNode?.let { append("\n").append(ctx.getString(R.string.last_hop, WardriveState.relayLabel(it))) }
        }
        p.errorReason?.let { append("\nError: ").append(RoutingError.name(it)) }
        p.latencyMs?.let { append("\n").append(ctx.getString(R.string.reply_in, String.format(Locale.US, "%.1f", it / 1000.0))) }
    }

    fun pingTitle(p: PingRow): String =
        p.text.split(' ').take(2).joinToString(" ") + " · " + ctx.getString(p.status.labelRes)

    private fun cellPolygon(c: CoverageGrid.Cell, fill: Int, stroke: Int): Polygon {
        val poly = Polygon(map)
        poly.points = listOf(
            GeoPoint(c.lat0, c.lon0), GeoPoint(c.lat0, c.lon1), GeoPoint(c.lat1, c.lon1),
            GeoPoint(c.lat1, c.lon0), GeoPoint(c.lat0, c.lon0),
        )
        poly.fillPaint.color = fill
        poly.outlinePaint.color = stroke
        poly.outlinePaint.strokeWidth = 1.5f
        return poly
    }
}

/** Nombre corto del puerto para exportaciones. */
fun portLabel(p: Int) = com.meshwardrive.app.proto.PortNum.name(p)
