package com.meshwardrive.app.ui

import android.graphics.Color
import androidx.core.content.ContextCompat
import com.meshwardrive.app.R
import com.meshwardrive.app.data.CoverageGrid
import com.meshwardrive.app.data.labelRes
import com.meshwardrive.app.data.NodeEntry
import com.meshwardrive.app.data.PingRow
import com.meshwardrive.app.data.PingStatus
import com.meshwardrive.app.data.RxRow
import com.meshwardrive.app.data.WardriveState
import com.meshwardrive.app.proto.PortNum
import com.meshwardrive.app.proto.RoutingError
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.infowindow.InfoWindow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Dibuja la cobertura: celdas cuadradas agregadas + puntos de ping + nodos. */
class CoverageRenderer(private val map: MapView) {

    data class Layers(
        val heat: Boolean, val cells: Boolean, val pings: Boolean, val passive: Boolean, val nodes: Boolean,
    )

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    private var folder: FolderOverlay? = null
    private val ctx = map.context
    private fun snr(v: Float) = String.format(Locale.US, "%.1f", v)
    private val timeFmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())

    fun render(
        pings: List<PingRow>, rx: List<RxRow>, nodes: Collection<NodeEntry>,
        cellSizeM: Int, layers: Layers,
    ) {
        InfoWindow.closeAllInfoWindowsOn(map)
        folder?.let { map.overlays.remove(it) }
        val f = FolderOverlay()

        if (layers.heat) {
            // Primero los fallos y encima los aciertos, para que un acierto no quede tapado
            val pts = pings.filter { it.status.isFail }.map { HeatmapOverlay.HeatPoint(it.lat, it.lon, CoverageGrid.FAIL) } +
                pings.filter { it.status.isOk }.map { HeatmapOverlay.HeatPoint(it.lat, it.lon, CoverageGrid.pingColor(it)) }
            if (pts.isNotEmpty()) {
                f.add(HeatmapOverlay(pts, cellSizeM.toFloat(), ctx.resources.displayMetrics.density))
            }
        }

        if (layers.passive) {
            for (c in CoverageGrid.rxCells(rx, cellSizeM)) {
                val col = CoverageGrid.PASSIVE
                f.add(cellPolygon(c, withAlpha(col, 0x45), withAlpha(col, 0x90)).apply {
                    title = ctx.getString(R.string.passive_rx)
                    snippet = ctx.getString(R.string.rx_packets, c.count) +
                        (c.bestSnr?.let { "\n" + ctx.getString(R.string.best_snr, snr(it)) } ?: "") +
                        (c.avgSnr?.let { "\n" + ctx.getString(R.string.avg_snr, snr(it)) } ?: "")
                })
            }
        }

        if (layers.cells) {
            for (c in CoverageGrid.pingCells(pings, cellSizeM)) {
                val col = CoverageGrid.cellColor(c)
                f.add(cellPolygon(c, withAlpha(col, 0x70), withAlpha(col, 0xC0)).apply {
                    title = ctx.getString(R.string.cell_title)
                    snippet = ctx.getString(R.string.cell_snippet, c.count, c.ok, c.fail, c.successPct ?: 0) +
                        (c.bestSnr?.let { "\n" + ctx.getString(R.string.best_snr, snr(it)) } ?: "") +
                        (c.avgSnr?.let { "\n" + ctx.getString(R.string.avg_snr, snr(it)) } ?: "")
                })
            }
        }

        if (layers.pings) {
            for (p in pings) {
                val color = CoverageGrid.pingColor(p)
                val poly = Polygon(map)
                poly.points = Polygon.pointsAsCircle(GeoPoint(p.lat, p.lon), 12.0)
                poly.fillPaint.color = color
                poly.outlinePaint.color = Color.WHITE
                poly.outlinePaint.strokeWidth = 2f
                poly.title = p.text.split(' ').take(2).joinToString(" ") + " · " + ctx.getString(p.status.labelRes)
                poly.snippet = describePing(p)
                f.add(poly)
            }
        }

        if (layers.nodes) {
            for (n in nodes) {
                val lat = n.lat ?: continue
                val lon = n.lon ?: continue
                val m = Marker(map)
                m.position = GeoPoint(lat, lon)
                ContextCompat.getDrawable(ctx, R.drawable.ic_node)?.let { m.icon = it }
                m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                m.title = n.label
                m.snippet = "!%08x".format(n.num) +
                    (if (n.lastHeard > 0) "\n" + ctx.getString(R.string.seen, timeFmt.format(Date(n.lastHeard))) else "")
                f.add(m)
            }
        }

        // Debajo de la posición propia (que va al final de la lista)
        val idx = map.overlays.size.coerceAtMost(1)
        map.overlays.add(idx, f)
        folder = f
        map.invalidate()
    }

    private fun describePing(p: PingRow): String = buildString {
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
fun portLabel(p: Int) = PortNum.name(p)
