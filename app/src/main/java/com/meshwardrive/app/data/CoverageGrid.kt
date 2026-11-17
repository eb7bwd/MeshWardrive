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
    const val PASSIVE = 0xFF1565C0.toInt()   // azul
    const val DEST = 0xFF1B5E20.toInt()      // verde oscuro

    private const val M_PER_DEG = 111_320.0

    data class Key(val y: Int, val x: Int)

    class Cell(val lat0: Double, val lat1: Double, val lon0: Double, val lon1: Double) {
        var count = 0
        var ok = 0
        var fail = 0
        var pending = 0
        var bestSnr: Float? = null
        private var snrSum = 0.0
        private var snrN = 0

        fun addSnr(v: Float?) {
            if (v == null) return
            val b = bestSnr
            if (b == null || v > b) bestSnr = v
            snrSum += v
            snrN++
        }

        val avgSnr: Float? get() = if (snrN > 0) (snrSum / snrN).toFloat() else null
        val successPct: Int? get() = if (ok + fail > 0) 100 * ok / (ok + fail) else null
        val latC: Double get() = (lat0 + lat1) / 2
        val lonC: Double get() = (lon0 + lon1) / 2
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

    private fun newCell(k: Key, sizeM: Double): Cell {
        val mPerDegLon = mPerDegLon(k.y, sizeM)
        return Cell(
            lat0 = k.y * sizeM / M_PER_DEG,
            lat1 = (k.y + 1) * sizeM / M_PER_DEG,
            lon0 = k.x * sizeM / mPerDegLon,
            lon1 = (k.x + 1) * sizeM / mPerDegLon,
        )
    }

    /** Celdas de cobertura a partir de los pings. */
    fun pingCells(pings: List<PingRow>, sizeM: Int): List<Cell> {
        val size = sizeM.toDouble()
        val cells = LinkedHashMap<Key, Cell>()
        for (p in pings) {
            val k = keyOf(p.lat, p.lon, size)
            val c = cells.getOrPut(k) { newCell(k, size) }
            c.count++
            when {
                p.status.isOk -> { c.ok++; c.addSnr(p.bestSnr) }
                p.status.isFail -> c.fail++
                else -> c.pending++
            }
        }
        return cells.values.toList()
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
        }
        return cells.values.toList()
    }

    // ------------------------------------------------------------------ huecos

    /**
     * Huecos cercanos: celdas sin medir que tocan a una celda medida. Son el borde de lo ya
     * cubierto, que es por donde tiene sentido seguir. Se devuelven las más cercanas a un punto.
     */
    fun gapCells(pings: List<PingRow>, sizeM: Int, nearLat: Double?, nearLon: Double?, max: Int = 300): List<Cell> {
        val size = sizeM.toDouble()
        val measured = HashSet<Key>()
        for (p in pings) measured.add(keyOf(p.lat, p.lon, size))
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
     */
    fun diffCells(before: List<PingRow>, after: List<PingRow>, sizeM: Int): List<DiffCell> {
        val size = sizeM.toDouble()
        fun index(list: List<PingRow>): Map<Key, Cell> {
            val m = LinkedHashMap<Key, Cell>()
            for (p in list) {
                val k = keyOf(p.lat, p.lon, size)
                val c = m.getOrPut(k) { newCell(k, size) }
                c.count++
                when {
                    p.status.isOk -> { c.ok++; c.addSnr(p.bestSnr) }
                    p.status.isFail -> c.fail++
                    else -> c.pending++
                }
            }
            return m
        }
        val a = index(before)
        val b = index(after)
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

    fun cellColor(c: Cell): Int = when {
        c.ok > 0 -> snrColor(c.bestSnr)
        c.fail > 0 -> FAIL
        else -> PENDING
    }

    fun pingColor(p: PingRow): Int = when (p.status) {
        PingStatus.ACK_DEST -> DEST
        PingStatus.ACK_RELAY -> snrColor(p.relaySnr)
        PingStatus.PENDING -> PENDING
        else -> FAIL
    }

    fun cellClass(c: Cell): String = when (cellColor(c)) {
        GOOD -> "good"
        MARGINAL -> "marginal"
        WEAK -> "weak"
        FAIL -> "no_coverage"
        else -> "pending"
    }
}
