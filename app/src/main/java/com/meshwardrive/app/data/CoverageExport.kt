package com.meshwardrive.app.data

import java.io.OutputStream
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Exportaciones pensadas para hacer mapas de cobertura: celdas en GeoJSON y KML. Kotlin puro. */
object CoverageExport {

    private fun n(v: Double) = String.format(Locale.US, "%.7f", v)
    private fun f1(v: Float) = String.format(Locale.US, "%.1f", v)
    private fun rgb(color: Int) = String.format("#%06x", color and 0xFFFFFF)

    /** KML usa aabbggrr. */
    private fun kmlColor(color: Int, alpha: Int): String {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return String.format("%02x%02x%02x%02x", alpha, b, g, r)
    }

    private fun iso(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(ts))

    private fun js(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' -> sb.append(String.format("\\u%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }

    private fun xml(s: String): String = buildString {
        for (ch in s) {
            when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch < ' ' && ch != '\n' -> append(' ')
                else -> append(ch)
            }
        }
    }

    private fun ring(c: CoverageGrid.Cell): List<Pair<Double, Double>> = listOf(
        c.lon0 to c.lat0, c.lon1 to c.lat0, c.lon1 to c.lat1, c.lon0 to c.lat1, c.lon0 to c.lat0,
    )

    // ------------------------------------------------------------ GeoJSON de celdas

    /**
     * Polígonos de celda con estadísticas. Capa "coverage" = pings; capa "rx" = recepción pasiva.
     * Incluye propiedades de estilo (fill, stroke…) que entienden geojson.io, uMap y GitHub.
     */
    fun cellsGeoJson(
        out: OutputStream, pings: List<PingRow>, rx: List<RxRow>, sizeM: Int,
        sc: Scoring.Context = Scoring.Context.CLASSIC_DEFAULT,
    ) {
        val w = out.bufferedWriter()
        w.write("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true
        fun feature(c: CoverageGrid.Cell, props: String) {
            if (!first) w.write(",")
            first = false
            w.write("\n{\"type\":\"Feature\",\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[")
            w.write(ring(c).joinToString(",") { "[${n(it.first)},${n(it.second)}]" })
            w.write("]]},\"properties\":{")
            w.write(props)
            w.write("}}")
        }
        for (c in CoverageGrid.pingCells(pings, sizeM, sc)) {
            val color = rgb(CoverageGrid.cellColor(c))
            feature(c, buildString {
                append("\"layer\":\"coverage\",\"cell_size_m\":$sizeM")
                append(",\"class\":").append(js(CoverageGrid.cellClass(c)))
                append(",\"pings\":${c.count},\"ok\":${c.ok},\"fail\":${c.fail},\"unconfirmed_fail\":${c.weakFail},\"pending\":${c.pending}")
                append(",\"trend\":").append(js(CoverageGrid.trendClass(c)))
                append(",\"first\":").append(if (c.firstTs != Long.MAX_VALUE) js(iso(c.firstTs)) else "null")
                append(",\"last\":").append(if (c.lastTs > 0) js(iso(c.lastTs)) else "null")
                append(",\"success_pct\":").append(c.successPct?.toString() ?: "null")
                append(",\"best_snr\":").append(c.bestSnr?.let { f1(it) } ?: "null")
                append(",\"avg_snr\":").append(c.avgSnr?.let { f1(it) } ?: "null")
                append(",\"fill\":").append(js(color)).append(",\"fill-opacity\":0.5")
                append(",\"stroke\":").append(js(color)).append(",\"stroke-width\":1")
            })
        }
        val passive = rgb(CoverageGrid.PASSIVE)
        for (c in CoverageGrid.rxCells(rx, sizeM)) {
            feature(c, buildString {
                append("\"layer\":\"rx\",\"cell_size_m\":$sizeM,\"packets\":${c.count}")
                append(",\"best_snr\":").append(c.bestSnr?.let { f1(it) } ?: "null")
                append(",\"avg_snr\":").append(c.avgSnr?.let { f1(it) } ?: "null")
                append(",\"fill\":").append(js(passive)).append(",\"fill-opacity\":0.3")
                append(",\"stroke\":").append(js(passive)).append(",\"stroke-width\":1")
            })
        }
        w.write("\n]}\n")
        w.flush()
    }

    // ------------------------------------------------------------ KML

    private fun cellPlacemark(w: Writer, c: CoverageGrid.Cell, name: String, desc: String, color: Int, alpha: Int) {
        w.write("<Placemark><name>${xml(name)}</name><description>${xml(desc)}</description>")
        w.write("<Style><LineStyle><color>${kmlColor(color, 0xCC)}</color><width>1</width></LineStyle>")
        w.write("<PolyStyle><color>${kmlColor(color, alpha)}</color></PolyStyle></Style>")
        w.write("<Polygon><outerBoundaryIs><LinearRing><coordinates>")
        w.write(ring(c).joinToString(" ") { "${n(it.first)},${n(it.second)},0" })
        w.write("</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>\n")
    }

    private fun pointPlacemark(w: Writer, lat: Double, lon: Double, name: String, desc: String, color: Int, time: Long?) {
        w.write("<Placemark><name>${xml(name)}</name><description>${xml(desc)}</description>")
        if (time != null) w.write("<TimeStamp><when>${iso(time)}</when></TimeStamp>")
        w.write("<Style><IconStyle><color>${kmlColor(color, 0xFF)}</color><scale>0.6</scale>")
        w.write("<Icon><href>http://maps.google.com/mapfiles/kml/shapes/shaded_dot.png</href></Icon></IconStyle>")
        w.write("<LabelStyle><scale>0</scale></LabelStyle></Style>")
        w.write("<Point><coordinates>${n(lon)},${n(lat)},0</coordinates></Point></Placemark>\n")
    }

    /** KML para Google Earth, OsmAnd, QGIS…: celdas, pings, recepción pasiva y nodos en carpetas. */
    fun kml(
        out: OutputStream, pings: List<PingRow>, rx: List<RxRow>, nodes: Map<Int, NodeEntry>, sizeM: Int,
        sc: Scoring.Context = Scoring.Context.CLASSIC_DEFAULT,
    ) {
        val w = out.bufferedWriter()
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        w.write("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>\n")
        w.write("<name>MeshWardrive · Meshtastic coverage</name>\n")

        w.write("<Folder><name>Coverage cells ($sizeM m)</name>\n")
        for (c in CoverageGrid.pingCells(pings, sizeM, sc)) {
            val desc = buildString {
                append("Pings: ${c.count}\nAnswered: ${c.ok}\nNo response: ${c.fail}")
                if (c.weakFail > 0) append("\nUnconfirmed (poor conditions): ${c.weakFail}")
                when (c.trend) {
                    CoverageGrid.Trend.NEW -> append("\nNew coverage")
                    CoverageGrid.Trend.LOST -> append("\nCoverage lost")
                    else -> Unit
                }
                c.successPct?.let { append("\nSuccess: $it %") }
                c.bestSnr?.let { append("\nBest SNR: ${f1(it)} dB") }
                c.avgSnr?.let { append("\nAverage SNR: ${f1(it)} dB") }
            }
            val name = c.successPct?.let { "$it %" } ?: "pending"
            cellPlacemark(w, c, name, desc, CoverageGrid.cellColor(c), 0x80)
        }
        w.write("</Folder>\n")

        w.write("<Folder><name>Pings</name><visibility>0</visibility>\n")
        for (p in pings) {
            val desc = buildString {
                append(p.status.label)
                append("\nMode: ").append(p.mode).append(" · hop limit ").append(p.hopLimit)
                p.relayNode?.let { append("\nRelay: 0x%02x".format(it and 0xFF)) }
                p.relaySnr?.let { append("\nSNR relay: ${f1(it)} dB") }
                p.relayRssi?.let { append("\nRSSI relay: $it dBm") }
                p.ackFrom?.let { append("\nACK from: ").append(nodes[it]?.label ?: "!%08x".format(it)) }
                p.ackSnr?.let { append("\nSNR ACK: ${f1(it)} dB") }
                p.ackRssi?.let { append("\nRSSI ACK: $it dBm") }
                p.ackHops?.let { append("\nHops: $it") }
                p.latencyMs?.let { append("\nLatency: $it ms") }
            }
            pointPlacemark(w, p.effLat, p.effLon, p.text.split(' ').take(2).joinToString(" "), desc,
                CoverageGrid.pingColor(p), p.ts)
        }
        w.write("</Folder>\n")

        w.write("<Folder><name>Passive reception ($sizeM m)</name><visibility>0</visibility>\n")
        for (c in CoverageGrid.rxCells(rx, sizeM)) {
            val desc = buildString {
                append("Packets heard: ${c.count}")
                c.bestSnr?.let { append("\nBest SNR: ${f1(it)} dB") }
                c.avgSnr?.let { append("\nAverage SNR: ${f1(it)} dB") }
            }
            cellPlacemark(w, c, "${c.count} packets", desc, CoverageGrid.PASSIVE, 0x55)
        }
        w.write("</Folder>\n")

        w.write("<Folder><name>Nodes</name>\n")
        for (nd in nodes.values) {
            val lat = nd.lat ?: continue
            val lon = nd.lon ?: continue
            w.write("<Placemark><name>${xml(nd.label)}</name><description>${xml("!%08x".format(nd.num))}</description>")
            w.write("<Point><coordinates>${n(lon)},${n(lat)},0</coordinates></Point></Placemark>\n")
        }
        w.write("</Folder>\n</Document></kml>\n")
        w.flush()
    }
}
