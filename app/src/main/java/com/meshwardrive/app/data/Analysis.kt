package com.meshwardrive.app.data

/** Análisis de una sesión: ruido estimado a lo largo de la ruta y nodos que han repetido. Kotlin puro. */
object Analysis {

    class NoisePoint(val ts: Long, val dbm: Float, val lat: Double, val lon: Double)

    class EventPoint(val ts: Long, val status: PingStatus, val lat: Double, val lon: Double, val text: String)

    class Series(
        val session: String,
        val start: Long,
        val end: Long,
        val noise: List<NoisePoint>,
        val events: List<EventPoint>,
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
    fun sessions(pings: List<PingRow>, rx: List<RxRow>): List<String> {
        val last = HashMap<String, Long>()
        for (p in pings) last[p.session] = maxOf(last[p.session] ?: 0L, p.ts)
        for (r in rx) last[r.session] = maxOf(last[r.session] ?: 0L, r.ts)
        return last.entries.sortedByDescending { it.value }.map { it.key }
    }

    fun series(session: String, pings: List<PingRow>, rx: List<RxRow>): Series {
        val noise = ArrayList<NoisePoint>()
        val events = ArrayList<EventPoint>()
        for (r in rx) {
            if (r.session != session) continue
            val lat = r.lat ?: continue
            val lon = r.lon ?: continue
            val n = noiseEstimate(r.rssi, r.snr) ?: continue
            noise.add(NoisePoint(r.ts, n, lat, lon))
        }
        for (p in pings) {
            if (p.session != session) continue
            events.add(EventPoint(p.ts, p.status, p.lat, p.lon, p.text.split(' ').take(2).joinToString(" ")))
            val t = p.ackTs ?: p.ts
            noiseEstimate(p.relayRssi, p.relaySnr)?.let { noise.add(NoisePoint(t, it, p.lat, p.lon)) }
            noiseEstimate(p.ackRssi, p.ackSnr)?.let { noise.add(NoisePoint(t, it, p.lat, p.lon)) }
        }
        noise.sortBy { it.ts }
        events.sortBy { it.ts }
        val all = noise.map { it.ts } + events.map { it.ts }
        val start = all.minOrNull() ?: 0L
        val end = all.maxOrNull() ?: 0L
        return Series(session, start, end, noise, events)
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
