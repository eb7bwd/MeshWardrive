package com.meshwardrive.app.data

/**
 * Condiciones en que se hizo un wardrive: cómo iba el nodo. Sirven para estimar lo difícil que era
 * que te oyeran y, con eso, cuánto se puede fiar uno de un ping sin respuesta. Kotlin puro.
 *
 * Los dB son aproximaciones razonables, no medidas: se explican en «Cómo se calcula cada valor».
 */
data class Conditions(
    /** Potencia de emisión en dBm; null = no se sabe (se toma la de referencia). */
    val txPowerDbm: Int? = null,
    /** true = antena exterior; false = la de serie. */
    val externalAntenna: Boolean = false,
    /** Ganancia de la antena en dBi. La de serie se toma como 2 dBi. */
    val antennaGainDbi: Float = STOCK_GAIN,
    val placement: String = PLACE_HAND,
    val transport: String = TRANSPORT_WALK,
) {
    companion object {
        const val STOCK_GAIN = 2f
        const val REF_TX = 22

        const val PLACE_POCKET = "pocket"
        const val PLACE_BAG = "bag"
        const val PLACE_HAND = "hand"
        const val PLACE_CAR_INSIDE = "car_inside"
        const val PLACE_DASHBOARD = "dashboard"
        const val PLACE_ROOF = "roof"
        const val PLACE_MAST = "mast"
        val PLACES = listOf(
            PLACE_POCKET, PLACE_BAG, PLACE_HAND, PLACE_CAR_INSIDE, PLACE_DASHBOARD, PLACE_ROOF, PLACE_MAST,
        )

        const val TRANSPORT_WALK = "walk"
        const val TRANSPORT_BIKE = "bike"
        const val TRANSPORT_CAR = "car"
        val TRANSPORTS = listOf(TRANSPORT_WALK, TRANSPORT_BIKE, TRANSPORT_CAR)

        /** Pérdida (o ganancia) aproximada según dónde va el nodo, en dB. */
        fun placementDb(p: String): Float = when (p) {
            PLACE_POCKET -> -15f       // pegado al cuerpo, que absorbe mucho a 868 MHz
            PLACE_BAG -> -10f
            PLACE_HAND -> -3f
            PLACE_CAR_INSIDE -> -8f    // la carrocería hace de jaula
            PLACE_DASHBOARD -> -5f     // tras el parabrisas
            PLACE_ROOF -> 0f           // referencia: antena fuera del vehículo
            PLACE_MAST -> 3f           // más altura
            else -> 0f
        }

        fun sanitize(c: Conditions): Conditions = c.copy(
            txPowerDbm = c.txPowerDbm?.coerceIn(0, 33),
            antennaGainDbi = if (c.antennaGainDbi.isNaN()) STOCK_GAIN else c.antennaGainDbi.coerceIn(-10f, 15f),
            placement = if (c.placement in PLACES) c.placement else PLACE_HAND,
            transport = if (c.transport in TRANSPORTS) c.transport else TRANSPORT_WALK,
        )
    }

    /**
     * Diferencia en dB respecto a un equipo de referencia (antena de serie de 2 dBi fuera del
     * vehículo, 22 dBm). Negativo = más difícil que te oigan.
     */
    val offsetDb: Float
        get() {
            val gain = (if (externalAntenna) antennaGainDbi else STOCK_GAIN) - STOCK_GAIN
            val tx = txPowerDbm?.let { (it - REF_TX).toFloat() } ?: 0f
            return placementDb(placement) + gain + tx
        }

    val difficulty: Difficulty get() = Difficulty.of(offsetDb)
}

enum class Difficulty {
    EASY, NORMAL, HARD;

    companion object {
        fun of(offsetDb: Float): Difficulty = when {
            offsetDb >= -3f -> EASY
            offsetDb >= -10f -> NORMAL
            else -> HARD
        }
    }
}

/** Cuánto se puede fiar uno de una medición. */
enum class Reliability {
    HIGH, MEDIUM, LOW;

    companion object {
        /** Sin condiciones conocidas se toma como media: se comporta como en versiones anteriores. */
        fun ofOffset(offsetDb: Float?): Reliability = when {
            offsetDb == null -> MEDIUM
            offsetDb >= -3f -> HIGH
            offsetDb >= -10f -> MEDIUM
            else -> LOW
        }
    }
}

/**
 * Criterio para dar un ping por bueno o malo en todos los análisis.
 *
 * - Ponderado: un ping con respuesta siempre prueba cobertura. Uno sin respuesta solo cuenta como
 *   «sin cobertura» si se hizo con un equipo razonable (fiabilidad media o alta); si no, queda como
 *   «sin confirmar». Una celda toma el estado de su wardrive más reciente con datos concluyentes.
 * - Clásico: cada ping cuenta igual, como en las versiones anteriores.
 */
object Scoring {
    const val WEIGHTED = "weighted"
    const val CLASSIC = "classic"

    class Context(
        val mode: String,
        /** Desfase en dB de cada wardrive; los que faltan no tienen condiciones conocidas. */
        val offsetBySession: Map<String, Float> = emptyMap(),
    ) {
        val weighted: Boolean get() = mode == WEIGHTED

        fun offset(session: String): Float? = offsetBySession[session]

        fun reliability(session: String): Reliability =
            if (!weighted) Reliability.HIGH else Reliability.ofOffset(offset(session))

        /** ¿Este fallo demuestra que no hay cobertura? */
        fun conclusiveFail(p: PingRow): Boolean = p.status.isFail && reliability(p.session) != Reliability.LOW

        /** SNR corregido por las condiciones (el que se habría medido con el equipo de referencia). */
        fun correctedSnr(p: PingRow): Float? {
            val s = p.bestSnr ?: return null
            val o = offset(p.session) ?: return s
            return s - o
        }

        companion object {
            val CLASSIC_DEFAULT = Context(CLASSIC)
        }
    }

    fun context(mode: String, meta: Map<String, SessionMeta>): Context =
        Context(
            if (mode == CLASSIC) CLASSIC else WEIGHTED,
            meta.mapNotNull { (id, m) -> m.conditions?.let { id to it.offsetDb } }.toMap(),
        )
}
