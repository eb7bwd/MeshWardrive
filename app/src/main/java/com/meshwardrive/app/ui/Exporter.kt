package com.meshwardrive.app.ui

import com.meshwardrive.app.data.NodeEntry
import com.meshwardrive.app.data.PingRow
import com.meshwardrive.app.data.RxRow
import com.meshwardrive.app.data.TextSafety
import com.meshwardrive.app.proto.PortNum
import com.meshwardrive.app.proto.RoutingError
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Exporter {

    private fun iso(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(ts))

    private fun hexNode(n: Int?) = n?.let { "!%08x".format(it) } ?: ""
    private fun hexByte(n: Int?) = n?.let { "0x%02x".format(it and 0xFF) } ?: ""
    private fun num(v: Any?) = v?.toString() ?: ""
    private fun f1(v: Float?) = v?.let { String.format(Locale.US, "%.2f", it) } ?: ""
    private fun f7(v: Double?) = v?.let { String.format(Locale.US, "%.7f", it) } ?: ""

    /** Celda CSV: se neutralizan fórmulas (texto que llega por radio) y se entrecomilla si hace falta. */
    private fun csv(raw: String): String {
        val s = TextSafety.csvCell(raw)
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    private fun candidates(relay: Int?, nodes: Map<Int, NodeEntry>, myNum: Int): String {
        if (relay == null) return ""
        return nodes.values.filter { (it.num and 0xFF) == (relay and 0xFF) && it.num != myNum }
            .joinToString(" | ") { "${it.shortName ?: "?"} ${hexNode(it.num)}" }
    }

    fun pingsCsv(out: OutputStream, pings: List<PingRow>, nodes: Map<Int, NodeEntry>, myNum: Int) {
        val w = out.bufferedWriter()
        w.write(
            "id,session,timestamp_utc,lat,lon,alt_m,gps_acc_m,speed_mps,mode,channel,dest,hop_limit,text," +
                "status,error,latency_ms,relay_node,relay_candidates,relay_snr,relay_rssi," +
                "ack_from,ack_from_name,ack_snr,ack_rssi,ack_hops,ack_relay_node,ack_relay_candidates," +
                "ack_lat,ack_lon,ack_moved_m\n"
        )
        for (p in pings) {
            val cols = listOf(
                num(p.rowId), p.session, iso(p.ts), f7(p.lat), f7(p.lon),
                p.alt?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                f1(p.acc), f1(p.speed), p.mode, num(p.channel),
                if (p.dest == -1) "broadcast" else hexNode(p.dest),
                num(p.hopLimit), p.text, p.status.name,
                p.errorReason?.let { RoutingError.name(it) } ?: "",
                num(p.latencyMs),
                hexByte(p.relayNode), candidates(p.relayNode, nodes, myNum),
                f1(p.relaySnr), num(p.relayRssi),
                hexNode(p.ackFrom), p.ackFrom?.let { nodes[it]?.label } ?: "",
                f1(p.ackSnr), num(p.ackRssi), num(p.ackHops),
                hexByte(p.ackRelayNode), candidates(p.ackRelayNode, nodes, myNum),
                f7(p.ackLat), f7(p.ackLon), p.ackDistM?.let { String.format(Locale.US, "%.0f", it) } ?: "",
            )
            w.write(cols.joinToString(",") { csv(it) })
            w.write("\n")
        }
        w.flush()
    }

    fun rxCsv(out: OutputStream, rx: List<RxRow>, nodes: Map<Int, NodeEntry>, myNum: Int) {
        val w = out.bufferedWriter()
        w.write(
            "id,session,timestamp_utc,lat,lon,gps_acc_m,from,from_name,to,channel,port," +
                "snr,rssi,hop_start,hop_limit,hops,relay_node,relay_candidates,packet_id\n"
        )
        for (r in rx) {
            val cols = listOf(
                num(r.rowId), r.session, iso(r.ts), f7(r.lat), f7(r.lon), f1(r.acc),
                hexNode(r.from), nodes[r.from]?.label ?: "",
                if (r.to == -1) "broadcast" else hexNode(r.to),
                num(r.channel), PortNum.name(r.portnum),
                f1(r.snr), num(r.rssi), num(r.hopStart), num(r.hopLimit), num(r.hops),
                hexByte(r.relayNode), candidates(r.relayNode, nodes, myNum),
                "%08x".format(r.packetId),
            )
            w.write(cols.joinToString(",") { csv(it) })
            w.write("\n")
        }
        w.flush()
    }

    fun geoJson(
        out: OutputStream, pings: List<PingRow>, rx: List<RxRow>,
        nodes: Map<Int, NodeEntry>, myNum: Int,
    ) {
        val features = JSONArray()
        fun point(lat: Double, lon: Double) = JSONObject()
            .put("type", "Point")
            .put("coordinates", JSONArray().put(lon).put(lat))

        for (p in pings) {
            val props = JSONObject()
                .put("kind", "ping")
                .put("session", p.session)
                .put("time", iso(p.ts))
                .put("mode", p.mode)
                .put("status", p.status.name)
                .put("ok", p.status.isOk)
                .put("text", p.text)
                .put("hop_limit", p.hopLimit)
            p.acc?.let { props.put("gps_acc_m", it.toDouble()) }
            p.errorReason?.let { props.put("error", RoutingError.name(it)) }
            p.latencyMs?.let { props.put("latency_ms", it) }
            p.relayNode?.let {
                props.put("relay_node", hexByte(it))
                props.put("relay_candidates", candidates(it, nodes, myNum))
            }
            p.relaySnr?.let { props.put("relay_snr", it.toDouble()) }
            p.relayRssi?.let { props.put("relay_rssi", it) }
            p.ackFrom?.let { props.put("ack_from", hexNode(it)) }
            p.ackSnr?.let { props.put("ack_snr", it.toDouble()) }
            p.ackRssi?.let { props.put("ack_rssi", it) }
            p.ackHops?.let { props.put("ack_hops", it) }
            p.ackRelayNode?.let { props.put("ack_relay_node", hexByte(it)) }
            p.ackDistM?.let { props.put("ack_moved_m", it.toDouble()) }
            if (p.movedBeforeAck) props.put("sent_lat", p.lat).put("sent_lon", p.lon)
            features.put(
                JSONObject().put("type", "Feature").put("geometry", point(p.effLat, p.effLon)).put("properties", props)
            )
        }
        for (r in rx) {
            val lat = r.lat ?: continue
            val lon = r.lon ?: continue
            val props = JSONObject()
                .put("kind", "rx")
                .put("session", r.session)
                .put("time", iso(r.ts))
                .put("from", hexNode(r.from))
                .put("from_name", nodes[r.from]?.label ?: "")
                .put("port", PortNum.name(r.portnum))
            r.snr?.let { props.put("snr", it.toDouble()) }
            r.rssi?.let { props.put("rssi", it) }
            r.hops?.let { props.put("hops", it) }
            r.relayNode?.let { props.put("relay_node", hexByte(it)) }
            features.put(
                JSONObject().put("type", "Feature").put("geometry", point(lat, lon)).put("properties", props)
            )
        }
        val doc = JSONObject().put("type", "FeatureCollection").put("features", features)
        val w = out.bufferedWriter()
        w.write(doc.toString())
        w.flush()
    }
}
