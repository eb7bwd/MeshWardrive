package com.meshwardrive.app.data

/** Análisis de una sesión: ruido estimado a lo largo de la ruta y nodos que han repetido. Kotlin puro. */
object Analysis {

    /**
     * Muestra de la gráfica. [vt] es el tiempo "virtual" en la gráfica: con varios wardrives
     * se colocan uno detrás de otro, así que no coincide con la hora real [ts].
     */
    class NoisePoint(
        val vt: Long,
        val ts: Long,
        val dbm: Float,
        val rssi: Int?,
        val snr: Float?,
        val lat: Double,
        val lon: Double,
    )

    class EventPoint(
        val vt: Long,
        val ts: Long,
        val status: PingStatus,
        val lat: Double,
        val lon: Double,
        val text: String,
        val rssi: Int?,
        val snr: Float?,
    ) {
        /** Ruido estimado con la respuesta del ping, si la hubo. */
        val dbm: Float? get() = noiseEstimate(rssi, snr)
    }

    class Series(
        val sessions: List<String>,
        val start: Long,
        val end: Long,
        val noise: List<NoisePoint>,
        val events: List<EventPoint>,
        /** Tiempos virtuales donde empieza cada wardrive a partir del segundo (para dibujar separadores). */
        val boundaries: List<Long>,
    )

    class RelayStat(val relayByte: Int) {
        var pings = 0            // pings propios que este relay retransmitió
        var rxPackets = 0        // paquetes de otros que nos llegaron a través de él
        var direct = 0           // paquetes recibidos directamente de un nodo con este byte
        var bestSnr: Float? = null
        var lastTs = 0L
        var bestLat: Double? = null
        var bestLon: Double? = null
        var candidates: List<NodeEntry> = emptyList()
        private var snrSum = 0.0
        private var snrN = 0
        val avgSnr: Float? get() = if (snrN > 0) (snrSum / snrN).toFloat() else null

        internal fun add(snr: Float?, ts: Long, lat: Double?, lon: Double?) {
            if (ts > lastTs) lastTs = ts
            if (snr != null) {
                snrSum += snr
                snrN++
                val b = bestSnr
                if (b == null || snr > b) {
                    bestSnr = snr
                    if (lat != null && lon != null) { bestLat = lat; bestLon = lon }
                }
            }
            if (bestLat == null && lat != null && lon != null) { bestLat = lat; bestLon = lon }
        }
    }

    /**
     * Meshtastic no informa del ruido de fondo, así que se estima con cada paquete oído:
     * con SNR positivo, ruido ≈ RSSI − SNR; con SNR negativo la señal queda bajo el ruido y RSSI ≈ ruido.
     * Los paquetes muy fuertes se descartan: el SNR de LoRa se satura hacia +10 dB y la resta
     * daría un ruido falso, mucho más alto que el real.
     */
    fun noiseEstimate(rssi: Int?, snr: Float?): Float? {
        if (rssi == null || snr == null || rssi == 0) return null
        if (snr > 5f) return null
        return rssi - maxOf(snr, 0f)
    }

    /** Sesiones con datos, de la más reciente a la más antigua. */
    fun sessions(pings: List<PingRow>, rx: List<RxRow>, track: List<TrackPoint> = emptyList()): List<String> {
        val last = HashMap<String, Long>()
        for (p in pings) last[p.session] = maxOf(last[p.session] ?: 0L, p.ts)
        for (r in rx) last[r.session] = maxOf(last[r.session] ?: 0L, r.ts)
        for (t in track) last[t.session] = maxOf(last[t.session] ?: 0L, t.ts)
        return last.entries.sortedByDescending { it.value }.map { it.key }
    }

    fun series(session: String, pings: List<PingRow>, rx: List<RxRow>): Series =
        series(listOf(session), pings, rx)

    /**
     * Gráfica de uno o varios wardrives. Con varios, se colocan en orden cronológico uno detrás
     * de otro, separados por un hueco pequeño, para que el eje sea "tiempo de wardrive".
     */
    fun series(sessions: List<String>, pings: List<PingRow>, rx: List<RxRow>): Series {
        val wanted = sessions.toSet()
        class Raw(val noise: ArrayList<NoisePoint> = ArrayList(), val events: ArrayList<EventPoint> = ArrayList())
        val raw = HashMap<String, Raw>()
        for (r in rx) {
            if (r.session !in wanted) continue
            val lat = r.lat ?: continue
            val lon = r.lon ?: continue
            val n = noiseEstimate(r.rssi, r.snr) ?: continue
            raw.getOrPut(r.session) { Raw() }.noise.add(NoisePoint(r.ts, r.ts, n, r.rssi, r.snr, lat, lon))
        }
        for (p in pings) {
            if (p.session !in wanted) continue
            val g = raw.getOrPut(p.session) { Raw() }
            val rssi = p.ackRssi ?: p.relayRssi
            val snr = p.ackSnr ?: p.relaySnr
            g.events.add(
                EventPoint(p.ts, p.ts, p.status, p.lat, p.lon, p.text.split(' ').take(2).joinToString(" "), rssi, snr)
            )
            val t = p.ackTs ?: p.ts
            noiseEstimate(p.relayRssi, p.relaySnr)?.let {
                g.noise.add(NoisePoint(t, t, it, p.relayRssi, p.relaySnr, p.lat, p.lon))
            }
            noiseEstimate(p.ackRssi, p.ackSnr)?.let {
                g.noise.add(NoisePoint(t, t, it, p.ackRssi, p.ackSnr, p.lat, p.lon))
            }
        }
        // Orden cronológico de los wardrives y desplazamiento de cada uno
        val ordered = raw.entries.map { (id, g) ->
            val times = g.noise.map { it.ts } + g.events.map { it.ts }
            Triple(id, times.minOrNull() ?: 0L, times.maxOrNull() ?: 0L)
        }.sortedBy { it.second }
        val noise = ArrayList<NoisePoint>()
        val events = ArrayList<EventPoint>()
        val boundaries = ArrayList<Long>()
        var cursor = 0L
        for ((index, t) in ordered.withIndex()) {
            val (id, start, end) = t
            if (index > 0) boundaries.add(cursor)
            val shift = cursor - start
            val g = raw.getValue(id)
            g.noise.forEach { noise.add(NoisePoint(it.ts + shift, it.ts, it.dbm, it.rssi, it.snr, it.lat, it.lon)) }
            g.events.forEach {
                events.add(EventPoint(it.ts + shift, it.ts, it.status, it.lat, it.lon, it.text, it.rssi, it.snr))
            }
            val dur = maxOf(end - start, 1_000L)
            cursor += dur + maxOf(dur / 20, 30_000L)
        }
        noise.sortBy { it.vt }
        events.sortBy { it.vt }
        val all = noise.map { it.vt } + events.map { it.vt }
        return Series(
            sessions = ordered.map { it.first },
            start = all.minOrNull() ?: 0L,
            end = all.maxOrNull() ?: 0L,
            noise = noise,
            events = events,
            boundaries = boundaries,
        )
    }

    // ------------------------------------------------------------------ informe

    class Report(
        val wardrives: Int,
        val distanceM: Double,
        val durationMs: Long,
        val pings: Int,
        val answered: Int,
        val failed: Int,
        val rxPackets: Int,
        val cells: Int,
        val coveredCells: Int,
        val bestSnr: Float?,
        val best: CoverageGrid.Cell?,
        val worst: CoverageGrid.Cell?,
    ) {
        val successPct: Int get() = if (answered + failed > 0) 100 * answered / (answered + failed) else 0
        val coveredPct: Int get() = if (cells > 0) 100 * coveredCells / cells else 0
    }

    /**
     * Resumen de los wardrives elegidos. La distancia sale de la ruta guardada; si un wardrive
     * no tiene ruta (versiones anteriores), de las posiciones de sus pings y recepciones.
     */
    fun report(pings: List<PingRow>, rx: List<RxRow>, track: List<TrackPoint>, cellSizeM: Int): Report {
        val bySession = HashMap<String, ArrayList<Triple<Long, Double, Double>>>()
        val withTrack = track.map { it.session }.toSet()
        track.forEach { bySession.getOrPut(it.session) { ArrayList() }.add(Triple(it.ts, it.lat, it.lon)) }
        for (p in pings) {
            if (p.session !in withTrack) bySession.getOrPut(p.session) { ArrayList() }.add(Triple(p.ts, p.lat, p.lon))
        }
        for (r in rx) {
            val lat = r.lat ?: continue
            val lon = r.lon ?: continue
            if (r.session !in withTrack) bySession.getOrPut(r.session) { ArrayList() }.add(Triple(r.ts, lat, lon))
        }
        var dist = 0.0
        var dur = 0L
        for (pts in bySession.values) {
            pts.sortBy { it.first }
            for (k in 1 until pts.size) {
                val a = pts[k - 1]
                val b = pts[k]
                val d = Geo.distanceM(a.second, a.third, b.second, b.third)
                // Un salto enorme en poco tiempo es un error de posición, no recorrido
                val dt = (b.first - a.first).coerceAtLeast(1L) / 1000.0
                if (d / dt < 70.0) dist += d
            }
            if (pts.size > 1) dur += pts.last().first - pts.first().first
        }
        // Las sesiones sin posiciones cuentan su duración por los tiempos de sus registros
        val sessions = sessions(pings, rx, track)
        val cells = CoverageGrid.pingCells(pings, cellSizeM)
        val answered = cells.filter { it.ok > 0 }
        return Report(
            wardrives = sessions.size,
            distanceM = dist,
            durationMs = dur,
            pings = pings.size,
            answered = pings.count { it.status.isOk },
            failed = pings.count { it.status.isFail },
            rxPackets = rx.size,
            cells = cells.count { it.ok + it.fail > 0 },
            coveredCells = answered.size,
            bestSnr = answered.mapNotNull { it.bestSnr }.maxOrNull(),
            best = answered.filter { it.bestSnr != null }.maxByOrNull { it.bestSnr!! } ?: answered.firstOrNull(),
            worst = cells.filter { it.ok == 0 && it.fail > 0 }.maxByOrNull { it.fail }
                ?: answered.filter { it.bestSnr != null }.minByOrNull { it.bestSnr!! },
        )
    }

    /**
     * Nodos que probablemente han repetido. Se agrupa por el byte relay_node, que es lo único que da
     * el firmware. Entran: los relays de nuestros pings (si el firmware los informa), los relays de
     * paquetes ajenos y los nodos oídos directamente, que son los que están al alcance.
     */
    fun relays(pings: List<PingRow>, rx: List<RxRow>, nodes: Collection<NodeEntry>, myNum: Int): List<RelayStat> {
        val map = LinkedHashMap<Int, RelayStat>()
        val directNodes = HashMap<Int, HashSet<Int>>()
        fun stat(b: Int) = map.getOrPut(b and 0xFF) { RelayStat(b and 0xFF) }
        for (p in pings) {
            val t = p.ackTs ?: p.ts
            p.relayNode?.let { stat(it).apply { this.pings++; add(p.relaySnr, t, p.lat, p.lon) } }
            val ar = p.ackRelayNode
            if (ar != null) {
                val s = stat(ar)
                if (p.relayNode == null || (p.relayNode!! and 0xFF) != (ar and 0xFF)) s.pings++
                s.add(p.ackSnr, t, p.lat, p.lon)
            }
        }
        for (r in rx) {
            val hops = r.hops ?: continue
            if (hops == 0) {
                if (r.from == myNum || r.from == 0) continue
                val s = stat(r.from)
                s.direct++
                s.add(r.snr, r.ts, r.lat, r.lon)
                directNodes.getOrPut(r.from and 0xFF) { HashSet() }.add(r.from)
            } else {
                val b = r.relayNode ?: continue
                val s = stat(b)
                s.rxPackets++
                s.add(r.snr, r.ts, r.lat, r.lon)
            }
        }
        val byNum = nodes.associateBy { it.num }
        for (s in map.values) {
            // Un repetidor tiene que estar al alcance: si hemos oído directamente a algún nodo con
            // ese byte, los candidatos son solo esos
            val heard = directNodes[s.relayByte]
            s.candidates = if (!heard.isNullOrEmpty()) {
                heard.map { byNum[it] ?: NodeEntry(it) }
            } else {
                nodes.filter { (it.num and 0xFF) == s.relayByte && it.num != myNum }
            }.sortedByDescending { it.lastHeard }
        }
        return map.values.sortedWith(
            compareByDescending<RelayStat> { it.pings }.thenByDescending { it.rxPackets + it.direct }
        )
    }

}
