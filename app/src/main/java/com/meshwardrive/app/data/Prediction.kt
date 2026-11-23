package com.meshwardrive.app.data

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Estimación de cobertura en celdas sin medir. Kotlin puro.
 *
 * Es una inferencia prudente, no una simulación de radio:
 * 1. Interpolación por distancia inversa (IDW) de las celdas medidas cercanas: cada celda con
 *    cobertura vale entre 0,6 y 1 según su SNR, cada celda sin cobertura vale 0. Las celdas
 *    «sin confirmar» no cuentan.
 * 2. Solo se estima hasta [radiusM] de alguna celda medida, y la confianza baja con la distancia a
 *    la medida más cercana y sube con el número de vecinas.
 * 3. Si hay relieve (modelo digital de elevaciones), se mira si el terreno corta la línea de visión
 *    entre la celda y los nodos conocidos que te oyeron cerca: con visión despejada dentro de su
 *    alcance observado sube la estimación; si todos quedan tapados por una loma, baja.
 */
object Prediction {

    enum class Terrain { UNKNOWN, CLEAR, BLOCKED, MIXED }

    enum class Level { LIKELY, UNCERTAIN, UNLIKELY }

    class Est(
        val cell: CoverageGrid.Cell,
        /** 0..1: probabilidad aproximada de tener cobertura. */
        val score: Float,
        /** 0..1: confianza de la estimación. */
        val confidence: Float,
        val terrain: Terrain,
        /** Distancia a la celda medida más cercana, en metros. */
        val nearestM: Double,
        val neighbours: Int,
    ) {
        val level: Level
            get() = when {
                score >= 0.6f -> Level.LIKELY
                score >= 0.35f -> Level.UNCERTAIN
                else -> Level.UNLIKELY
            }
    }

    /** Nodo que te oyó, con su posición y la distancia máxima a la que lo hizo. */
    class Node(val num: Int, val lat: Double, val lon: Double, val rangeM: Double)

    const val MIN_CONFIDENCE = 0.3f
    private const val FREQ_GHZ = 0.868
    private const val EARTH_R = 6_371_000.0
    private const val K_FACTOR = 4.0 / 3.0

    fun cellValue(c: CoverageGrid.Cell): Float? = when (c.state) {
        CoverageGrid.State.COVERED -> when (CoverageGrid.snrColor(c.bestSnr)) {
            CoverageGrid.GOOD -> 1f
            CoverageGrid.MARGINAL -> 0.8f
            else -> 0.6f
        }
        CoverageGrid.State.NO_COVERAGE -> 0f
        else -> null
    }

    /**
     * @param measured celdas medidas (de [CoverageGrid.index]).
     * @param elev elevación del terreno en metros, o null si no hay relieve descargado.
     */
    fun estimate(
        measured: Map<CoverageGrid.Key, CoverageGrid.Cell>,
        nodes: List<Node>,
        sizeM: Int,
        elev: ((Double, Double) -> Double?)?,
        radiusM: Double = 2_000.0,
        maxCells: Int = 6_000,
    ): List<Est> {
        val size = sizeM.toDouble()
        val values = HashMap<CoverageGrid.Key, Float>()
        for ((k, c) in measured) cellValue(c)?.let { values[k] = it }
        if (values.isEmpty()) return emptyList()
        val r = ceil(radiusM / size).toInt().coerceIn(1, 40)

        // Candidatas: celdas sin medir cerca de alguna medida, de la más cercana a la más lejana
        val candidates = HashMap<CoverageGrid.Key, Int>()
        for (k in values.keys) {
            for (dy in -r..r) for (dx in -r..r) {
                val n = CoverageGrid.Key(k.y + dy, k.x + dx)
                if (n in values) continue
                val d2 = dy * dy + dx * dx
                if (d2 > r * r) continue
                val prev = candidates[n]
                if (prev == null || d2 < prev) candidates[n] = d2
            }
        }
        val ordered = candidates.entries.sortedBy { it.value }.take(maxCells)

        val out = ArrayList<Est>(ordered.size)
        for ((k, _) in ordered) {
            var sw = 0.0
            var sv = 0.0
            var nearest = Double.MAX_VALUE
            var n = 0
            for (dy in -r..r) for (dx in -r..r) {
                val v = values[CoverageGrid.Key(k.y + dy, k.x + dx)] ?: continue
                val dm = sqrt((dy * dy + dx * dx).toDouble()) * size
                if (dm > radiusM) continue
                val w = 1.0 / (dm * dm + size * size / 4)
                sw += w
                sv += w * v
                n++
                if (dm < nearest) nearest = dm
            }
            if (n == 0) continue
            var score = (sv / sw).toFloat()
            var conf = (exp(-nearest / (radiusM * 0.5)) * min(1.0, n / 4.0)).toFloat()
            val cell = CoverageGrid.newCell(k, size)

            var terrain = Terrain.UNKNOWN
            if (elev != null && nodes.isNotEmpty()) {
                val near = nodes.map { it to Geo.distanceM(cell.latC, cell.lonC, it.lat, it.lon) }
                    .filter { (node, d) -> d <= max(node.rangeM * 1.3, 500.0) }
                    .sortedBy { it.second }
                    .take(3)
                if (near.isNotEmpty()) {
                    val results = near.map { (node, _) -> lineOfSight(cell.latC, cell.lonC, node.lat, node.lon, elev) }
                    terrain = when {
                        results.any { it == null } && results.none { it == true } -> Terrain.UNKNOWN
                        results.any { it == true } && results.any { it == false } -> Terrain.MIXED
                        results.any { it == true } -> Terrain.CLEAR
                        else -> Terrain.BLOCKED
                    }
                    when (terrain) {
                        Terrain.CLEAR -> { score = min(1f, score + 0.2f); conf = min(1f, conf + 0.1f) }
                        Terrain.BLOCKED -> { score *= 0.5f; conf = min(1f, conf + 0.1f) }
                        else -> Unit
                    }
                }
            }
            if (conf < MIN_CONFIDENCE) continue
            out.add(Est(cell, score, conf, terrain, nearest, n))
        }
        return out
    }

    /**
     * ¿Hay visión despejada entre dos puntos? Observador a 1,5 m del suelo, nodo a 5 m (no se sabe
     * a qué altura está su antena). Se considera tapada si el terreno, con la curvatura terrestre,
     * corta la línea directa más de un pequeño margen proporcional a la zona de Fresnel. No se exige
     * despejar el 60 % de Fresnel: a ras de suelo casi nunca se cumple y LoRa llega igualmente,
     * con algo de pérdida. null si falta relieve en algún punto.
     */
    fun lineOfSight(
        lat1: Double, lon1: Double, lat2: Double, lon2: Double,
        elev: (Double, Double) -> Double?,
        h1: Double = 1.5, h2: Double = 5.0,
    ): Boolean? {
        val dist = Geo.distanceM(lat1, lon1, lat2, lon2)
        if (dist < 50) return true
        val g1 = elev(lat1, lon1) ?: return null
        val g2 = elev(lat2, lon2) ?: return null
        val a = g1 + h1
        val b = g2 + h2
        val steps = (dist / 60.0).toInt().coerceIn(8, 200)
        val dKm = dist / 1000.0
        for (i in 1 until steps) {
            val t = i.toDouble() / steps
            val lat = lat1 + (lat2 - lat1) * t
            val lon = lon1 + (lon2 - lon1) * t
            val g = elev(lat, lon) ?: return null
            val d1 = dist * t
            val d2 = dist - d1
            val line = a + (b - a) * t
            val bulge = d1 * d2 / (2 * K_FACTOR * EARTH_R)
            val fresnel = 17.32 * sqrt((d1 / 1000.0) * (d2 / 1000.0) / (FREQ_GHZ * dKm))
            if (g + bulge > line + 0.25 * fresnel) return false
        }
        return true
    }

    /**
     * Nodos que te oyeron, con posición conocida, y la distancia máxima a la que lo hicieron.
     * Valen los que confirmaron en modo directo y los repetidores cuyo byte solo corresponde a un nodo.
     */
    fun supportingNodes(pings: List<PingRow>, nodes: Collection<NodeEntry>, myNum: Int): List<Node> {
        val byNum = nodes.filter { it.lat != null && it.lon != null && it.num != myNum }.associateBy { it.num }
        val byByte = byNum.values.groupBy { it.num and 0xFF }
        val range = HashMap<Int, Double>()
        fun add(num: Int, p: PingRow) {
            val n = byNum[num] ?: return
            val d = Geo.distanceM(p.effLat, p.effLon, n.lat!!, n.lon!!)
            range[num] = max(range[num] ?: 0.0, d)
        }
        for (p in pings) {
            if (!p.status.isOk) continue
            p.ackFrom?.let { add(it, p) }
            for (b in listOfNotNull(p.relayNode, p.ackRelayNode)) {
                val c = byByte[b and 0xFF] ?: continue
                if (c.size == 1) add(c[0].num, p)
            }
        }
        return range.map { (num, d) -> val n = byNum[num]!!; Node(num, n.lat!!, n.lon!!, d) }
    }
}
