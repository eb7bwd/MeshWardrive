package com.meshwardrive.app.data

import kotlin.math.cos
import kotlin.math.floor

/** Rejilla de celdas cuadradas para agregar mediciones. Kotlin puro: lo usan el mapa y las exportaciones. */
object CoverageGrid {

    const val GOOD = 0xFF2E7D32.toInt()      // verde
    const val MARGINAL = 0xFFF9A825.toInt()  // ámbar
    const val WEAK = 0xFFEF6C00.toInt()      // naranja
    const val FAIL = 0xFFC62828.toInt()      // rojo
    const val PENDING = 0xFF9E9E9E.toInt()   // gris
    const val UNCONFIRMED = 0xFF78909C.toInt() // gris azulado: fallos poco fiables
    const val PASSIVE = 0xFF1565C0.toInt()   // azul
    const val DEST = 0xFF1B5E20.toInt()      // verde oscuro
    const val TREND_NEW = 0xFF00897B.toInt() // turquesa: cobertura nueva
    const val TREND_LOST = 0xFF6D4C41.toInt() // marrón: cobertura perdida

    private const val M_PER_DEG = 111_320.0

    data class Key(val y: Int, val x: Int)

    enum class State { COVERED, NO_COVERAGE, UNCONFIRMED, PENDING }

    /** Cambio respecto a lo medido antes en la misma celda. */
    enum class Trend { NONE, NEW, LOST }

    /** Resumen de un wardrive dentro de una celda. */
    class SessionAgg(val session: String) {
        var ok = 0
        var conclusiveFail = 0
        var weakFail = 0
        var lastTs = 0L
        val conclusive: Boolean get() = ok > 0 || conclusiveFail > 0
    }

    class Cell(val key: Key, val lat0: Double, val lat1: Double, val lon0: Double, val lon1: Double) {
        var count = 0
        var ok = 0
        /** Fallos que cuentan como «sin cobertura» (todos en el criterio clásico). */
        var fail = 0
        /** Fallos poco fiables por las condiciones (solo en el criterio ponderado). */
        var weakFail = 0
        var pending = 0
        var notSent = 0
        var bestSnr: Float? = null
        var worstSnr: Float? = null
        private var snrSum = 0.0
        private var snrN = 0
        var firstTs = Long.MAX_VALUE
        var lastTs = 0L
        var lastOkTs = 0L
        /** Bytes de relay de quien repitió nuestros pings aquí. */
        val relays = LinkedHashSet<Int>()
        /** Nodos que confirmaron directamente (modo directo). */
        val ackFrom = LinkedHashSet<Int>()
        /** Peor fiabilidad entre las mediciones de la celda, para la ficha. */
        val reliabilities = HashMap<Reliability, Int>()
        val sessions = LinkedHashMap<String, SessionAgg>()
        private var altSum = 0.0
        private var altN = 0

        var state = State.PENDING
            private set
        var trend = Trend.NONE
            private set

        fun addSnr(v: Float?) {
            if (v == null) return
            val b = bestSnr
            if (b == null || v > b) bestSnr = v
            val w = worstSnr
            if (w == null || v < w) worstSnr = v
            snrSum += v
            snrN++
        }

        fun addAlt(v: Double?) {
            if (v == null) return
            altSum += v
            altN++
        }

        val avgSnr: Float? get() = if (snrN > 0) (snrSum / snrN).toFloat() else null
        val avgAlt: Double? get() = if (altN > 0) altSum / altN else null
        val successPct: Int? get() = if (ok + fail > 0) 100 * ok / (ok + fail) else null
        val latC: Double get() = (lat0 + lat1) / 2
        val lonC: Double get() = (lon0 + lon1) / 2
        val measured: Boolean get() = ok + fail + weakFail > 0

        /** Calcula el estado y la tendencia una vez sumadas todas las mediciones. */
        fun finish(weighted: Boolean) {
            val ordered = sessions.values.filter { it.conclusive }.sortedBy { it.lastTs }
            val latest = ordered.lastOrNull()
            state = when {
                weighted && latest != null -> if (latest.ok > 0) State.COVERED else State.NO_COVERAGE
                !weighted && ok > 0 -> State.COVERED
                !weighted && fail > 0 -> State.NO_COVERAGE
                weakFail > 0 -> State.UNCONFIRMED
                else -> State.PENDING
            }
            val older = if (ordered.size > 1) ordered.dropLast(1) else emptyList()
            trend = when {
                latest == null || older.isEmpty() -> Trend.NONE
                state == State.COVERED && latest.ok > 0 && older.any { it.ok == 0 && it.conclusiveFail > 0 } -> Trend.NEW
                state == State.NO_COVERAGE && older.any { it.ok > 0 } -> Trend.LOST
                else -> Trend.NONE
            }
        }
    }

    /**
     * Metros por grado de longitud para la fila y. No se calcula con la latitud exacta de cada fila:
     * las columnas se cuentan desde Greenwich, y una anchura un poco distinta en cada fila las iría
     * desplazando (miles de columnas × una diferencia mínima = metros). Se usa la latitud media de la
     * franja de 1° en la que cae la fila, así todas las filas de esa franja comparten columnas y
     * quedan perfectamente alineadas. El error de tamaño este-oeste es menor del 1 %.
     */
    private fun mPerDegLon(y: Int, sizeM: Double): Double {
        val latC = (y + 0.5) * sizeM / M_PER_DEG
        val band = floor(latC) + 0.5
        return M_PER_DEG * cos(Math.toRadians(band))
    }

    fun keyOf(lat: Double, lon: Double, sizeM: Double): Key {
        val y = floor(lat * M_PER_DEG / sizeM).toInt()
        return Key(y, floor(lon * mPerDegLon(y, sizeM) / sizeM).toInt())
    }

    fun newCell(k: Key, sizeM: Double): Cell {
        val mPerDegLon = mPerDegLon(k.y, sizeM)
        return Cell(
            key = k,
            lat0 = k.y * sizeM / M_PER_DEG,
            lat1 = (k.y + 1) * sizeM / M_PER_DEG,
            lon0 = k.x * sizeM / mPerDegLon,
            lon1 = (k.x + 1) * sizeM / mPerDegLon,
        )
    }

    /** Celda en la que cuenta un ping: un éxito tardío cuenta donde llegó la respuesta. */
    fun keyOfPing(p: PingRow, sizeM: Double): Key = keyOf(p.effLat, p.effLon, sizeM)

    private fun addPing(c: Cell, p: PingRow, sc: Scoring.Context) {
        c.count++
        val agg = c.sessions.getOrPut(p.session) { SessionAgg(p.session) }
        agg.lastTs = maxOf(agg.lastTs, p.ts)
        c.firstTs = minOf(c.firstTs, p.ts)
        c.lastTs = maxOf(c.lastTs, p.ts)
        val rel = sc.reliability(p.session)
        when {
            p.status.isOk -> {
                c.ok++
                agg.ok++
                c.lastOkTs = maxOf(c.lastOkTs, p.ts)
                c.addSnr(p.bestSnr)
                p.relayNode?.let { c.relays.add(it and 0xFF) }
                p.ackRelayNode?.let { c.relays.add(it and 0xFF) }
                if (p.status == PingStatus.ACK_DEST) p.ackFrom?.let { c.ackFrom.add(it) }
                c.reliabilities[Reliability.HIGH] = (c.reliabilities[Reliability.HIGH] ?: 0) + 1
            }
            p.status.isFail -> {
                if (sc.conclusiveFail(p)) {
                    c.fail++
                    agg.conclusiveFail++
                } else {
                    c.weakFail++
                    agg.weakFail++
                }
                c.reliabilities[rel] = (c.reliabilities[rel] ?: 0) + 1
            }
            p.status.notSent -> c.notSent++
            else -> c.pending++
        }
        c.addAlt(p.alt)
    }

    /** Celdas de cobertura a partir de los pings, ya evaluadas con el criterio elegido. */
    fun pingCells(pings: List<PingRow>, sizeM: Int, sc: Scoring.Context = Scoring.Context.CLASSIC_DEFAULT): List<Cell> =
        index(pings, sizeM, sc).values.toList()

    fun index(pings: List<PingRow>, sizeM: Int, sc: Scoring.Context): Map<Key, Cell> {
        val size = sizeM.toDouble()
        val cells = LinkedHashMap<Key, Cell>()
        for (p in pings) {
            val k = keyOfPing(p, size)
            addPing(cells.getOrPut(k) { newCell(k, size) }, p, sc)
        }
        cells.values.forEach { it.finish(sc.weighted) }
        return cells
    }

    /** Celdas de recepción pasiva (paquetes oídos con posición). */
    fun rxCells(rx: List<RxRow>, sizeM: Int): List<Cell> {
        val size = sizeM.toDouble()
        val cells = LinkedHashMap<Key, Cell>()
        for (r in rx) {
            val lat = r.lat ?: continue
            val lon = r.lon ?: continue
            val k = keyOf(lat, lon, size)
            val c = cells.getOrPut(k) { newCell(k, size) }
            c.count++
            c.addSnr(r.snr)
            c.firstTs = minOf(c.firstTs, r.ts)
            c.lastTs = maxOf(c.lastTs, r.ts)
        }
        return cells.values.toList()
    }

    /**
     * Qué pings dibujar como «superados»: fallos que ya no representan el estado de su celda
     * (la celda tiene cobertura o el fallo no es concluyente). Se siguen viendo, pero apagados.
     */
    fun supersededFails(pings: List<PingRow>, cells: Map<Key, Cell>, sizeM: Int, sc: Scoring.Context): Set<Long> {
        val size = sizeM.toDouble()
        val out = HashSet<Long>()
        for (p in pings) {
            if (!p.status.isFail) continue
            val c = cells[keyOfPing(p, size)] ?: continue
            if (c.state != State.NO_COVERAGE || !sc.conclusiveFail(p)) out.add(p.rowId)
        }
        return out
    }

    // ------------------------------------------------------------------ huecos

    /**
     * Huecos cercanos: celdas sin medir que tocan a una celda medida. Son el borde de lo ya
     * cubierto, que es por donde tiene sentido seguir. Se devuelven las más cercanas a un punto.
     */
    fun gapCells(pings: List<PingRow>, sizeM: Int, nearLat: Double?, nearLon: Double?, max: Int = 300): List<Cell> {
        val size = sizeM.toDouble()
        val measured = HashSet<Key>()
        for (p in pings) if (!p.status.notSent) measured.add(keyOfPing(p, size))
        val gaps = HashSet<Key>()
        for (k in measured) {
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val n = Key(k.y + dy, k.x + dx)
                if (n !in measured) gaps.add(n)
            }
        }
        val cells = gaps.map { newCell(it, size) }
        if (nearLat == null || nearLon == null) return cells.take(max)
        return cells.sortedBy { Geo.distanceM(it.latC, it.lonC, nearLat, nearLon) }.take(max)
    }

    // ------------------------------------------------------------------ comparar

    enum class Change { BETTER, WORSE, SAME, NEW, ONLY_BEFORE }

    class DiffCell(val cell: Cell, val change: Change, val before: Cell?, val after: Cell?)

    /**
     * Compara dos grupos de pings celda a celda. Mejora o empeora si el porcentaje de éxito
     * cambia 15 puntos o más, o, con el mismo éxito, si el mejor SNR cambia 3 dB o más.
     * Con el criterio ponderado, los fallos poco fiables no cuentan.
     */
    fun diffCells(
        before: List<PingRow>, after: List<PingRow>, sizeM: Int,
        sc: Scoring.Context = Scoring.Context.CLASSIC_DEFAULT,
    ): List<DiffCell> {
        val a = index(before, sizeM, sc)
        val b = index(after, sizeM, sc)
        val out = ArrayList<DiffCell>()
        for ((k, cb) in b) {
            val ca = a[k]
            val change = when {
                ca == null || ca.successPct == null -> Change.NEW
                cb.successPct == null -> Change.SAME
                else -> {
                    val dp = cb.successPct!! - ca.successPct!!
                    val ds = (cb.bestSnr ?: -99f) - (ca.bestSnr ?: -99f)
                    when {
                        dp >= 15 -> Change.BETTER
                        dp <= -15 -> Change.WORSE
                        cb.ok > 0 && ca.ok > 0 && ds >= 3f -> Change.BETTER
                        cb.ok > 0 && ca.ok > 0 && ds <= -3f -> Change.WORSE
                        else -> Change.SAME
                    }
                }
            }
            out.add(DiffCell(cb, change, ca, cb))
        }
        for ((k, ca) in a) if (k !in b) out.add(DiffCell(ca, Change.ONLY_BEFORE, ca, null))
        return out
    }

    fun snrColor(snr: Float?): Int = when {
        snr == null -> GOOD
        snr >= -7f -> GOOD
        snr >= -13f -> MARGINAL
        else -> WEAK
    }

    fun cellColor(c: Cell): Int = when (c.state) {
        State.COVERED -> snrColor(c.bestSnr)
        State.NO_COVERAGE -> FAIL
        State.UNCONFIRMED -> UNCONFIRMED
        State.PENDING -> PENDING
    }

    fun pingColor(p: PingRow): Int = when (p.status) {
        PingStatus.ACK_DEST -> DEST
        PingStatus.ACK_RELAY -> snrColor(p.relaySnr)
        PingStatus.PENDING -> PENDING
        PingStatus.NOT_SENT, PingStatus.SEND_ERROR -> PENDING
        else -> FAIL
    }

    fun cellClass(c: Cell): String = when (c.state) {
        State.COVERED -> when (cellColor(c)) {
            GOOD -> "good"
            MARGINAL -> "marginal"
            else -> "weak"
        }
        State.NO_COVERAGE -> "no_coverage"
        State.UNCONFIRMED -> "unconfirmed"
        State.PENDING -> "pending"
    }

    fun trendClass(c: Cell): String = when (c.trend) {
        Trend.NEW -> "new_coverage"
        Trend.LOST -> "lost_coverage"
        Trend.NONE -> ""
    }
}
