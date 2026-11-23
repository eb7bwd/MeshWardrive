package com.meshwardrive.app.data

import java.security.MessageDigest
import java.util.Locale

/**
 * Formato .mwd para compartir wardrives entre personas (JSON, versión 2; también lee la 1).
 *
 * Un archivo recibido se trata siempre como no fiable: tamaño y número de registros limitados,
 * lectura JSON estricta, campos desconocidos rechazados, rangos comprobados y textos limpiados.
 * Un registro inválido se descarta; si se descartan demasiados, se rechaza el archivo entero.
 * Kotlin puro: se prueba fuera de Android.
 */
object ShareFormat {

    const val FORMAT = "meshwardrive"
    const val VERSION = 2L
    const val EXTENSION = "mwd"
    const val MIME = "application/json"

    const val MAX_BYTES = 50L * 1024 * 1024
    const val MAX_WARDRIVES = 200
    const val MAX_PINGS = 100_000
    const val MAX_RX = 300_000
    const val MAX_TRACK = 300_000
    const val TRIM_M = 1_000.0

    private const val MIN_TS = 1_577_836_800_000L // 2020-01-01
    private const val NODE_MAX = 0xFFFFFFFFL

    class Wardrive(
        val id: String,
        val name: String?,
        val created: Long,
        val pings: List<PingRow>,
        val rx: List<RxRow>,
        val track: List<TrackPoint>,
        val conditions: Conditions? = null,
    )

    class Decoded(
        val sharedBy: String,
        val exported: Long,
        val wardrives: List<Wardrive>,
        val discarded: Int,
        val total: Int,
    )

    class ShareException(msg: String) : Exception(msg)

    // ------------------------------------------------------------------ escribir

    fun encode(sharedBy: String, exportedAt: Long, wardrives: List<Wardrive>): String {
        val w = JsonOut()
        w.open('{')
        w.key("format").str(FORMAT)
        w.key("version").num(VERSION)
        w.key("exported").num(exportedAt)
        w.key("sharedBy").str(TextSafety.clean(sharedBy, 40) ?: "?")
        w.key("wardrives").open('[')
        for (wd in wardrives) {
            w.item().open('{')
            w.key("id").str(wd.id)
            wd.name?.let { w.key("name").str(it) }
            w.key("created").num(wd.created)
            wd.conditions?.let { c0 ->
                val c = Conditions.sanitize(c0)
                w.key("conditions").open('{')
                c.txPowerDbm?.let { w.key("tx").num(it.toLong()) }
                w.key("external").num(if (c.externalAntenna) 1 else 0)
                w.key("gain").dec(c.antennaGainDbi.toDouble(), 1)
                w.key("place").str(c.placement)
                w.key("transport").str(c.transport)
                w.close('}')
            }
            w.key("pings").open('[')
            for (p in wd.pings) {
                w.item().open('{')
                w.key("ts").num(p.ts)
                w.key("lat").coord(p.lat)
                w.key("lon").coord(p.lon)
                p.alt?.let { w.key("alt").dec(it, 1) }
                p.acc?.let { w.key("acc").dec(it.toDouble(), 1) }
                p.speed?.let { w.key("speed").dec(it.toDouble(), 1) }
                w.key("mode").str(p.mode)
                w.key("channel").num(p.channel.toLong())
                w.key("dest").num(p.dest.toLong() and NODE_MAX)
                w.key("hopLimit").num(p.hopLimit.toLong())
                w.key("text").str(p.text)
                w.key("status").str(p.status.name)
                p.errorReason?.let { w.key("error").num(it.toLong()) }
                p.ackTs?.let { w.key("ackTs").num(it) }
                p.relayNode?.let { w.key("relayNode").num((it and 0xFF).toLong()) }
                p.relaySnr?.let { w.key("relaySnr").dec(it.toDouble(), 2) }
                p.relayRssi?.let { w.key("relayRssi").num(it.toLong()) }
                p.ackFrom?.let { w.key("ackFrom").num(it.toLong() and NODE_MAX) }
                p.ackSnr?.let { w.key("ackSnr").dec(it.toDouble(), 2) }
                p.ackRssi?.let { w.key("ackRssi").num(it.toLong()) }
                p.ackHops?.let { w.key("ackHops").num(it.toLong()) }
                p.ackRelayNode?.let { w.key("ackRelayNode").num((it and 0xFF).toLong()) }
                if (p.ackLat != null && p.ackLon != null) {
                    w.key("ackLat").coord(p.ackLat!!)
                    w.key("ackLon").coord(p.ackLon!!)
                    p.ackDistM?.let { w.key("ackDist").dec(it.toDouble(), 1) }
                }
                w.close('}')
            }
            w.close(']')
            w.key("rx").open('[')
            for (r in wd.rx) {
                w.item().open('{')
                w.key("ts").num(r.ts)
                if (r.lat != null && r.lon != null) {
                    w.key("lat").coord(r.lat)
                    w.key("lon").coord(r.lon)
                }
                r.acc?.let { w.key("acc").dec(it.toDouble(), 1) }
                w.key("from").num(r.from.toLong() and NODE_MAX)
                w.key("to").num(r.to.toLong() and NODE_MAX)
                w.key("channel").num(r.channel.toLong())
                w.key("port").num(r.portnum.toLong())
                r.snr?.let { w.key("snr").dec(it.toDouble(), 2) }
                r.rssi?.let { w.key("rssi").num(it.toLong()) }
                w.key("hopStart").num(r.hopStart.toLong())
                w.key("hopLimit").num(r.hopLimit.toLong())
                r.relayNode?.let { w.key("relayNode").num((it and 0xFF).toLong()) }
                w.close('}')
            }
            w.close(']')
            w.key("track").open('[')
            for (t in wd.track) {
                w.item().open('{')
                w.key("ts").num(t.ts)
                w.key("lat").coord(t.lat)
                w.key("lon").coord(t.lon)
                t.acc?.let { w.key("acc").dec(it.toDouble(), 1) }
                t.alt?.let { w.key("alt").dec(it, 1) }
                w.close('}')
            }
            w.close(']')
            w.close('}')
        }
        w.close(']')
        w.close('}')
        return w.toString()
    }

    /**
     * Prepara un wardrive para compartirlo: opcionalmente quita todo lo que esté a menos de
     * [TRIM_M] del principio y del final del recorrido (suelen ser casa o trabajo) y las
     * coordenadas que lleva el texto de los pings.
     */
    fun prepare(w: Wardrive, trim: Boolean, stripCoords: Boolean): Wardrive {
        var pings = w.pings
        var rx = w.rx
        var track = w.track
        if (trim) {
            val pos = ArrayList<Triple<Long, Double, Double>>()
            track.forEach { pos.add(Triple(it.ts, it.lat, it.lon)) }
            pings.forEach { pos.add(Triple(it.ts, it.lat, it.lon)) }
            rx.forEach { r -> if (r.lat != null && r.lon != null) pos.add(Triple(r.ts, r.lat, r.lon)) }
            if (pos.isNotEmpty()) {
                pos.sortBy { it.first }
                val a = pos.first()
                val b = pos.last()
                fun far(lat: Double, lon: Double) =
                    Geo.distanceM(lat, lon, a.second, a.third) > TRIM_M &&
                        Geo.distanceM(lat, lon, b.second, b.third) > TRIM_M
                pings = pings.filter { far(it.lat, it.lon) && (it.ackLat == null || far(it.ackLat!!, it.ackLon!!)) }
                rx = rx.filter { it.lat == null || it.lon == null || far(it.lat, it.lon) }
                track = track.filter { far(it.lat, it.lon) }
            }
        }
        if (stripCoords) pings = pings.map { it.copy(text = it.text.split(' ').take(2).joinToString(" ")) }
        return Wardrive(w.id, w.name, w.created, pings, rx, track, w.conditions)
    }

    /** Identificador local de un wardrive importado: estable, para no importarlo dos veces. */
    fun importedId(sharedBy: String, originalId: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val h = md.digest((sharedBy + "\u0000" + originalId).toByteArray(Charsets.UTF_8))
        return "i-" + h.take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    // ------------------------------------------------------------------ leer

    /**
     * Lee un archivo con un límite de tamaño: deja de leer en cuanto lo supera, sin cargarlo entero.
     * Exige UTF-8 válido.
     */
    fun readText(input: java.io.InputStream, maxBytes: Long = MAX_BYTES): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw ShareException("file too large")
            out.write(buf, 0, n)
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw ShareException("the file is not UTF-8 text")
        }
    }

    private class Bad(msg: String) : Exception(msg)

    private class Rec(private val m: Map<String, Any?>, allowed: Set<String>) {
        init {
            for (k in m.keys) if (k !in allowed) throw Bad("unexpected field '$k'")
        }

        fun has(k: String) = m[k] != null

        fun long(k: String, min: Long, max: Long): Long =
            longOpt(k, min, max) ?: throw Bad("missing '$k'")

        fun longOpt(k: String, min: Long, max: Long): Long? {
            val v = m[k] ?: return null
            if (v !is Long) throw Bad("'$k' must be an integer")
            if (v < min || v > max) throw Bad("'$k' out of range")
            return v
        }

        fun dbl(k: String, min: Double, max: Double): Double =
            dblOpt(k, min, max) ?: throw Bad("missing '$k'")

        fun dblOpt(k: String, min: Double, max: Double): Double? {
            val v = when (val x = m[k]) {
                null -> return null
                is Long -> x.toDouble()
                is Double -> x
                else -> throw Bad("'$k' must be a number")
            }
            if (v.isNaN() || v < min || v > max) throw Bad("'$k' out of range")
            return v
        }

        fun str(k: String): String = strOpt(k) ?: throw Bad("missing '$k'")

        fun strOpt(k: String): String? {
            val v = m[k] ?: return null
            if (v !is String) throw Bad("'$k' must be text")
            return v
        }

        fun map(k: String): Map<*, *>? {
            val v = m[k] ?: return null
            if (v !is Map<*, *>) throw Bad("'$k' must be an object")
            return v
        }

        fun list(k: String): List<*> {
            val v = m[k] ?: return emptyList<Any?>()
            if (v !is List<*>) throw Bad("'$k' must be a list")
            return v
        }
    }

    private val TOP_KEYS = setOf("format", "version", "exported", "sharedBy", "app", "wardrives")
    private val WD_KEYS = setOf("id", "name", "created", "pings", "rx", "track", "conditions")
    private val COND_KEYS = setOf("tx", "external", "gain", "place", "transport")
    private val PING_KEYS = setOf(
        "ts", "lat", "lon", "alt", "acc", "speed", "mode", "channel", "dest", "hopLimit", "text", "status",
        "error", "ackTs", "relayNode", "relaySnr", "relayRssi", "ackFrom", "ackSnr", "ackRssi", "ackHops",
        "ackRelayNode", "ackLat", "ackLon", "ackDist",
    )
    private val RX_KEYS = setOf(
        "ts", "lat", "lon", "acc", "from", "to", "channel", "port", "snr", "rssi", "hopStart", "hopLimit",
        "relayNode",
    )
    private val TRACK_KEYS = setOf("ts", "lat", "lon", "acc", "alt")
    private val MODES = setOf(MODE_CHANNEL, MODE_DIRECT, MODE_LISTEN)

    /** Lee y valida un archivo .mwd ya cargado en memoria. Lanza [ShareException] si no es aceptable. */
    fun decode(text: String, nowMs: Long): Decoded {
        if (text.length > MAX_BYTES) throw ShareException("file too large")
        val root = try {
            MiniJson.parse(text)
        } catch (e: MiniJson.JsonException) {
            throw ShareException("not valid JSON: ${e.message}")
        }
        if (root !is Map<*, *>) throw ShareException("not a MeshWardrive file")
        @Suppress("UNCHECKED_CAST")
        val top = try {
            Rec(root as Map<String, Any?>, TOP_KEYS)
        } catch (e: Bad) {
            throw ShareException(e.message ?: "invalid file")
        }
        val maxTs = nowMs + 86_400_000L
        val sharedBy: String
        val exported: Long
        val list: List<*>
        try {
            if (top.str("format") != FORMAT) throw Bad("not a MeshWardrive file")
            if (top.long("version", 1, Long.MAX_VALUE) > VERSION) throw Bad("unsupported version")
            exported = top.long("exported", MIN_TS, maxTs)
            sharedBy = TextSafety.clean(top.str("sharedBy"), 40) ?: "?"
            top.strOpt("app")
            list = top.list("wardrives")
        } catch (e: Bad) {
            throw ShareException(e.message ?: "invalid file")
        }
        if (list.isEmpty()) throw ShareException("the file contains no wardrives")
        if (list.size > MAX_WARDRIVES) throw ShareException("too many wardrives")

        var total = 0
        var discarded = 0
        var nPings = 0
        var nRx = 0
        var nTrack = 0
        val out = ArrayList<Wardrive>()
        val seen = HashSet<String>()
        for (item in list) {
            if (item !is Map<*, *>) throw ShareException("invalid wardrive")
            @Suppress("UNCHECKED_CAST")
            val wd = try {
                Rec(item as Map<String, Any?>, WD_KEYS)
            } catch (e: Bad) {
                throw ShareException(e.message ?: "invalid wardrive")
            }
            val origId: String
            val name: String?
            val created: Long
            val pl: List<*>
            val rl: List<*>
            val tl: List<*>
            val cond: Conditions?
            try {
                origId = wd.str("id")
                if (origId.isEmpty() || origId.length > 64) throw Bad("invalid wardrive id")
                name = TextSafety.clean(wd.strOpt("name"), 64)
                created = wd.long("created", MIN_TS, maxTs)
                pl = wd.list("pings")
                rl = wd.list("rx")
                tl = wd.list("track")
                cond = parseConditions(wd.map("conditions"))
            } catch (e: Bad) {
                throw ShareException(e.message ?: "invalid wardrive")
            }
            nPings += pl.size
            nRx += rl.size
            nTrack += tl.size
            if (nPings > MAX_PINGS || nRx > MAX_RX || nTrack > MAX_TRACK) throw ShareException("too many records")

            val id = importedId(sharedBy, origId)
            if (!seen.add(id)) throw ShareException("repeated wardrive")
            val pings = ArrayList<PingRow>(pl.size)
            val rx = ArrayList<RxRow>(rl.size)
            val track = ArrayList<TrackPoint>(tl.size)
            for (x in pl) {
                total++
                val p = runCatching { parsePing(x, id, maxTs) }.getOrNull()
                if (p == null) discarded++ else pings.add(p)
            }
            for (x in rl) {
                total++
                val r = runCatching { parseRx(x, id, maxTs) }.getOrNull()
                if (r == null) discarded++ else rx.add(r)
            }
            for (x in tl) {
                total++
                val t = runCatching { parseTrack(x, id, maxTs) }.getOrNull()
                if (t == null) discarded++ else track.add(t)
            }
            out.add(Wardrive(id, name, created, pings, rx, track, cond))
        }
        // Algún registro raro se tolera; muchos indican un archivo manipulado o corrupto
        if (discarded > 0 && discarded * 10 > total) {
            throw ShareException("too many invalid records ($discarded of $total)")
        }
        return Decoded(sharedBy, exported, out, discarded, total)
    }

    private fun map(x: Any?, keys: Set<String>): Rec {
        if (x !is Map<*, *>) throw Bad("record must be an object")
        @Suppress("UNCHECKED_CAST")
        return Rec(x as Map<String, Any?>, keys)
    }

    private fun parseConditions(x: Map<*, *>?): Conditions? {
        if (x == null) return null
        val r = map(x, COND_KEYS)
        val place = r.str("place")
        val transport = r.str("transport")
        if (place !in Conditions.PLACES || transport !in Conditions.TRANSPORTS) throw Bad("bad conditions")
        return Conditions.sanitize(
            Conditions(
                txPowerDbm = r.longOpt("tx", 0, 33)?.toInt(),
                externalAntenna = r.long("external", 0, 1) == 1L,
                antennaGainDbi = r.dbl("gain", -10.0, 15.0).toFloat(),
                placement = place,
                transport = transport,
            )
        )
    }

    private fun parsePing(x: Any?, session: String, maxTs: Long): PingRow {
        val r = map(x, PING_KEYS)
        val ts = r.long("ts", MIN_TS, maxTs)
        val mode = r.str("mode")
        if (mode !in MODES) throw Bad("bad mode")
        val status = runCatching { PingStatus.valueOf(r.str("status")) }.getOrNull() ?: throw Bad("bad status")
        val ackLat = r.dblOpt("ackLat", -90.0, 90.0)
        val ackLon = r.dblOpt("ackLon", -180.0, 180.0)
        if ((ackLat == null) != (ackLon == null)) throw Bad("incomplete position")
        return PingRow(
            packetId = 0,
            session = session,
            ts = ts,
            lat = r.dbl("lat", -90.0, 90.0),
            lon = r.dbl("lon", -180.0, 180.0),
            alt = r.dblOpt("alt", -1_000.0, 20_000.0),
            acc = r.dblOpt("acc", 0.0, 100_000.0)?.toFloat(),
            speed = r.dblOpt("speed", 0.0, 500.0)?.toFloat(),
            mode = mode,
            channel = r.long("channel", 0, 7).toInt(),
            dest = r.long("dest", 0, NODE_MAX).toInt(),
            hopLimit = r.long("hopLimit", 0, 7).toInt(),
            text = TextSafety.clean(r.str("text"), 200).orEmpty(),
            // Un ping que estaba esperando ya no va a recibir respuesta
            status = if (status == PingStatus.PENDING) PingStatus.TIMEOUT else status,
            errorReason = r.longOpt("error", 0, 255)?.toInt(),
            ackTs = r.longOpt("ackTs", ts, ts + 3_600_000L),
            relayNode = r.longOpt("relayNode", 0, 255)?.toInt(),
            relaySnr = r.dblOpt("relaySnr", -40.0, 40.0)?.toFloat(),
            relayRssi = r.longOpt("relayRssi", -200, 0)?.toInt(),
            ackFrom = r.longOpt("ackFrom", 0, NODE_MAX)?.toInt(),
            ackSnr = r.dblOpt("ackSnr", -40.0, 40.0)?.toFloat(),
            ackRssi = r.longOpt("ackRssi", -200, 0)?.toInt(),
            ackHops = r.longOpt("ackHops", 0, 7)?.toInt(),
            ackRelayNode = r.longOpt("ackRelayNode", 0, 255)?.toInt(),
            ackLat = ackLat,
            ackLon = ackLon,
            ackDistM = if (ackLat != null) r.dblOpt("ackDist", 0.0, 1_000_000.0)?.toFloat() else null,
        )
    }

    private fun parseRx(x: Any?, session: String, maxTs: Long): RxRow {
        val r = map(x, RX_KEYS)
        val lat = r.dblOpt("lat", -90.0, 90.0)
        val lon = r.dblOpt("lon", -180.0, 180.0)
        if ((lat == null) != (lon == null)) throw Bad("incomplete position")
        return RxRow(
            session = session,
            ts = r.long("ts", MIN_TS, maxTs),
            lat = lat,
            lon = lon,
            acc = r.dblOpt("acc", 0.0, 100_000.0)?.toFloat(),
            from = r.long("from", 0, NODE_MAX).toInt(),
            to = r.long("to", 0, NODE_MAX).toInt(),
            channel = r.long("channel", 0, 7).toInt(),
            portnum = r.long("port", -1, 1_023).toInt(),
            snr = r.dblOpt("snr", -40.0, 40.0)?.toFloat(),
            rssi = r.longOpt("rssi", -200, 0)?.toInt(),
            hopStart = r.long("hopStart", 0, 7).toInt(),
            hopLimit = r.long("hopLimit", 0, 7).toInt(),
            relayNode = r.longOpt("relayNode", 0, 255)?.toInt(),
            packetId = 0,
        )
    }

    private fun parseTrack(x: Any?, session: String, maxTs: Long): TrackPoint {
        val r = map(x, TRACK_KEYS)
        return TrackPoint(
            session = session,
            ts = r.long("ts", MIN_TS, maxTs),
            lat = r.dbl("lat", -90.0, 90.0),
            lon = r.dbl("lon", -180.0, 180.0),
            acc = r.dblOpt("acc", 0.0, 100_000.0)?.toFloat(),
            alt = r.dblOpt("alt", -1_000.0, 20_000.0),
        )
    }

    // ------------------------------------------------------------------ escritor JSON mínimo

    private class JsonOut {
        private val sb = StringBuilder(1 shl 16)
        private val first = ArrayList<Boolean>()

        fun open(c: Char): JsonOut { sb.append(c); first.add(true); return this }
        fun close(c: Char): JsonOut { sb.append(c); first.removeAt(first.size - 1); return this }

        private fun sep() {
            val i = first.size - 1
            if (first[i]) first[i] = false else sb.append(',')
        }

        fun item(): JsonOut { sep(); return this }

        fun key(k: String): JsonOut { sep(); quote(k); sb.append(':'); return this }

        fun str(v: String): JsonOut { quote(v); return this }
        fun num(v: Long): JsonOut { sb.append(v); return this }
        fun coord(v: Double): JsonOut = dec(v, 7)
        fun dec(v: Double, digits: Int): JsonOut {
            require(!v.isNaN() && !v.isInfinite())
            sb.append(String.format(Locale.US, "%.${digits}f", v))
            return this
        }

        private fun quote(s: String) {
            sb.append('"')
            for (c in s) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> sb.append("\\r")
                    c == '\t' -> sb.append("\\t")
                    c.code < 0x20 || c == ' ' || c == ' ' ->
                        sb.append(String.format(Locale.US, "\\u%04x", c.code))
                    else -> sb.append(c)
                }
            }
            sb.append('"')
        }

        override fun toString() = sb.toString()
    }
}
