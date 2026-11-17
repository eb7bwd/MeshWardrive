package com.meshwardrive.app.data

const val MODE_CHANNEL = "channel"
const val MODE_DIRECT = "direct"
const val MODE_LISTEN = "listen"

enum class PingStatus(val label: String) {
    PENDING("Waiting"),
    ACK_RELAY("Heard by the mesh"),   // ACK implícito: un vecino retransmitió el ping
    ACK_DEST("ACK from target"),       // modo directo: el nodo destino confirmó
    NAK("Rejected / nobody listening"),
    TIMEOUT("No response"),
    SEND_ERROR("Send error");

    val isOk: Boolean get() = this == ACK_RELAY || this == ACK_DEST
    val isFail: Boolean get() = this == NAK || this == TIMEOUT || this == SEND_ERROR
}

data class PingRow(
    var rowId: Long = 0,
    val packetId: Int,
    val session: String,
    val ts: Long,
    val lat: Double,
    val lon: Double,
    val alt: Double?,
    val acc: Float?,
    val speed: Float?,
    val mode: String,
    val channel: Int,
    val dest: Int,
    val hopLimit: Int,
    val text: String,
    var status: PingStatus = PingStatus.PENDING,
    var errorReason: Int? = null,
    var ackTs: Long? = null,
    // Datos del ACK implícito (quién retransmitió nuestro ping y cómo lo oímos)
    var relayNode: Int? = null,
    var relaySnr: Float? = null,
    var relayRssi: Int? = null,
    // Datos del ACK real del destino (modo directo)
    var ackFrom: Int? = null,
    var ackSnr: Float? = null,
    var ackRssi: Int? = null,
    var ackHops: Int? = null,
    var ackRelayNode: Int? = null,
) {
    val latencyMs: Long? get() = ackTs?.let { it - ts }

    /** Mejor SNR disponible para colorear el mapa. */
    val bestSnr: Float? get() = listOfNotNull(relaySnr, ackSnr).maxOrNull()
}

data class RxRow(
    var rowId: Long = 0,
    val session: String,
    val ts: Long,
    val lat: Double?,
    val lon: Double?,
    val acc: Float?,
    val from: Int,
    val to: Int,
    val channel: Int,
    val portnum: Int,          // -1 si venía cifrado con un canal que no tenemos
    val snr: Float?,
    val rssi: Int?,
    val hopStart: Int,
    val hopLimit: Int,
    val relayNode: Int?,
    val packetId: Int,
) {
    val hops: Int? get() = if (hopStart > 0 && hopStart >= hopLimit) hopStart - hopLimit else null
}

data class NodeEntry(
    val num: Int,
    val userId: String? = null,
    val longName: String? = null,
    val shortName: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val lastHeard: Long = 0,
) {
    val label: String
        get() = when {
            !shortName.isNullOrBlank() && !longName.isNullOrBlank() -> "$shortName · $longName"
            !longName.isNullOrBlank() -> longName
            !shortName.isNullOrBlank() -> shortName
            else -> "!%08x".format(num)
        }
}

data class Stats(
    val sent: Int = 0,
    val ackRelay: Int = 0,
    val ackDest: Int = 0,
    val failed: Int = 0,
    val pending: Int = 0,
    val rx: Int = 0,
)

/** Punto de la ruta recorrida durante un wardrive. */
data class TrackPoint(
    val session: String,
    val ts: Long,
    val lat: Double,
    val lon: Double,
    val acc: Float?,
)

/** Datos de un wardrive: nombre que le pone el usuario y, si es importado, quién lo compartió. */
data class SessionMeta(
    val id: String,
    val name: String? = null,
    val importedFrom: String? = null,
    val created: Long = 0,
)
