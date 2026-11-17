package com.meshwardrive.app.proto

/*
 * Subconjunto de los protobufs de Meshtastic (github.com/meshtastic/protobufs).
 * Números de campo tomados de meshtastic/mesh.proto y meshtastic/channel.proto.
 */

/** Destino broadcast 0xFFFFFFFF. */
const val BROADCAST: Int = -1

object PortNum {
    const val TEXT_MESSAGE = 1
    const val POSITION = 3
    const val NODEINFO = 4
    const val ROUTING = 5
    const val ADMIN = 6
    const val RANGE_TEST = 66
    const val TELEMETRY = 67
    const val TRACEROUTE = 70
    const val NEIGHBORINFO = 71

    fun name(p: Int): String = when (p) {
        -1 -> "ENCRYPTED"
        TEXT_MESSAGE -> "TEXT"
        POSITION -> "POSITION"
        NODEINFO -> "NODEINFO"
        ROUTING -> "ROUTING"
        ADMIN -> "ADMIN"
        RANGE_TEST -> "RANGE_TEST"
        TELEMETRY -> "TELEMETRY"
        TRACEROUTE -> "TRACEROUTE"
        NEIGHBORINFO -> "NEIGHBORINFO"
        else -> "PORT_$p"
    }
}

object RoutingError {
    const val NONE = 0
    const val MAX_RETRANSMIT = 5

    fun name(e: Int): String = when (e) {
        0 -> "NONE"
        1 -> "NO_ROUTE"
        2 -> "GOT_NAK"
        3 -> "TIMEOUT"
        4 -> "NO_INTERFACE"
        5 -> "MAX_RETRANSMIT"
        6 -> "NO_CHANNEL"
        7 -> "TOO_LARGE"
        8 -> "NO_RESPONSE"
        9 -> "DUTY_CYCLE_LIMIT"
        32 -> "BAD_REQUEST"
        33 -> "NOT_AUTHORIZED"
        34 -> "PKI_FAILED"
        35 -> "PKI_UNKNOWN_PUBKEY"
        36 -> "ADMIN_BAD_SESSION_KEY"
        37 -> "ADMIN_PUBLIC_KEY_UNAUTHORIZED"
        38 -> "RATE_LIMIT_EXCEEDED"
        39 -> "PKI_SEND_FAIL_PUBLIC_KEY"
        else -> "ERROR_$e"
    }
}

/** TransportMechanism (mesh.proto). Solo nos interesan los que NO son radio. */
object Transport {
    const val INTERNAL = 0
    const val LORA = 1
    const val MQTT = 5
    const val MULTICAST_UDP = 6
    const val API = 7
}

class DataMsg(
    val portnum: Int,
    val payload: ByteArray = ByteArray(0),
    val wantResponse: Boolean = false,
    val dest: Int = 0,
    val source: Int = 0,
    val requestId: Int = 0,
    val replyId: Int = 0,
)

class MeshPacket(
    val from: Int = 0,
    val to: Int = 0,
    val channel: Int = 0,
    val decoded: DataMsg? = null,
    val encrypted: ByteArray? = null,
    val id: Int = 0,
    val rxTime: Int = 0,
    val rxSnr: Float = 0f,
    val hopLimit: Int = 0,
    val wantAck: Boolean = false,
    val rxRssi: Int = 0,
    val viaMqtt: Boolean = false,
    val hopStart: Int = 0,
    val pkiEncrypted: Boolean = false,
    val nextHop: Int = 0,
    /** Solo el último byte del número de nodo que retransmitió (firmware 2.6+). */
    val relayNode: Int = 0,
    val transportMechanism: Int = 0,
) {
    /** true si tenemos datos de radio reales (SNR/RSSI) del paquete. */
    val hasRadioInfo: Boolean get() = rxSnr != 0f || rxRssi != 0

    /** Saltos consumidos, si el firmware informa hop_start. */
    val hopsTaken: Int? get() = if (hopStart > 0 && hopStart >= hopLimit) hopStart - hopLimit else null
}

class UserMsg(val id: String, val longName: String, val shortName: String)

class PositionMsg(
    val lat: Double?,
    val lon: Double?,
    val alt: Int,
    /** Hora de la posición en segundos Unix, 0 si no la trae. */
    val time: Int = 0,
    /** Precisión en milímetros (gps_accuracy), 0 si no la trae. */
    val accuracyMm: Int = 0,
    val hdop: Int = 0,
)

class NodeInfoMsg(
    val num: Int,
    val user: UserMsg?,
    val position: PositionMsg?,
    val snr: Float,
    val lastHeard: Int,
    val hopsAway: Int?,
    val channelUtil: Float? = null,
)

class ChannelMsg(val index: Int, val name: String, val role: Int) {
    companion object {
        const val ROLE_DISABLED = 0
        const val ROLE_PRIMARY = 1
        const val ROLE_SECONDARY = 2
    }
}

sealed class FromRadio {
    class Packet(val packet: MeshPacket) : FromRadio()
    class MyInfo(val myNodeNum: Int) : FromRadio()
    class NodeInfo(val info: NodeInfoMsg) : FromRadio()
    class Channel(val channel: ChannelMsg) : FromRadio()
    class ConfigComplete(val id: Int) : FromRadio()
    object Rebooted : FromRadio()
    object Other : FromRadio()
}

object MeshProto {

    // ---------- Codificación (teléfono -> nodo) ----------

    /** ToRadio { want_config_id = 3 } */
    fun encodeWantConfig(configId: Int): ByteArray =
        ProtoWriter().uint32(3, configId).toByteArray()

    /** ToRadio { packet = 1 } */
    fun encodeToRadioPacket(p: MeshPacket): ByteArray =
        ProtoWriter().message(1, encodeMeshPacket(p)).toByteArray()

    fun encodeMeshPacket(p: MeshPacket): ProtoWriter {
        val w = ProtoWriter()
        w.fixed32(1, p.from)
        w.fixed32(2, p.to)
        w.uint32(3, p.channel)
        p.decoded?.let { w.message(4, encodeData(it)) }
        p.encrypted?.let { w.bytes(5, it) }
        w.fixed32(6, p.id)
        w.uint32(9, p.hopLimit)
        w.bool(10, p.wantAck)
        return w
    }

    fun encodeData(d: DataMsg): ProtoWriter = ProtoWriter()
        .uint32(1, d.portnum)
        .bytes(2, d.payload)
        .bool(3, d.wantResponse)
        .fixed32(4, d.dest)
        .fixed32(5, d.source)
        .fixed32(6, d.requestId)
        .fixed32(7, d.replyId)

    // ---------- Decodificación (nodo -> teléfono) ----------

    fun decodeFromRadio(bytes: ByteArray): FromRadio {
        val r = ProtoReader(bytes)
        var result: FromRadio = FromRadio.Other
        r.forEachField { f, w ->
            when (f) {
                2 -> r.len(w)?.let { result = FromRadio.Packet(decodeMeshPacket(it)) }
                3 -> r.len(w)?.let { result = FromRadio.MyInfo(decodeMyInfo(it)) }
                4 -> r.len(w)?.let { result = FromRadio.NodeInfo(decodeNodeInfo(it)) }
                7 -> result = FromRadio.ConfigComplete(r.vint(w).toInt())
                8 -> { r.vint(w); result = FromRadio.Rebooted }
                10 -> r.len(w)?.let { result = FromRadio.Channel(decodeChannel(it)) }
                else -> r.skip(w)
            }
        }
        return result
    }

    fun decodeMeshPacket(bytes: ByteArray): MeshPacket {
        val r = ProtoReader(bytes)
        var from = 0; var to = 0; var channel = 0; var decoded: DataMsg? = null
        var encrypted: ByteArray? = null; var id = 0; var rxTime = 0; var rxSnr = 0f
        var hopLimit = 0; var wantAck = false; var rxRssi = 0; var viaMqtt = false
        var hopStart = 0; var pki = false; var nextHop = 0; var relayNode = 0; var transport = 0
        r.forEachField { f, w ->
            when (f) {
                1 -> from = r.fx32(w)
                2 -> to = r.fx32(w)
                3 -> channel = r.vint(w).toInt()
                4 -> r.len(w)?.let { decoded = decodeData(it) }
                5 -> encrypted = r.len(w)
                6 -> id = r.fx32(w)
                7 -> rxTime = r.fx32(w)
                8 -> rxSnr = r.float(w)
                9 -> hopLimit = r.vint(w).toInt()
                10 -> wantAck = r.vint(w) != 0L
                12 -> rxRssi = r.vint(w).toInt()
                14 -> viaMqtt = r.vint(w) != 0L
                15 -> hopStart = r.vint(w).toInt()
                17 -> pki = r.vint(w) != 0L
                18 -> nextHop = r.vint(w).toInt()
                19 -> relayNode = r.vint(w).toInt()
                21 -> transport = r.vint(w).toInt()
                else -> r.skip(w)
            }
        }
        return MeshPacket(
            from = from, to = to, channel = channel, decoded = decoded, encrypted = encrypted,
            id = id, rxTime = rxTime, rxSnr = rxSnr, hopLimit = hopLimit, wantAck = wantAck,
            rxRssi = rxRssi, viaMqtt = viaMqtt, hopStart = hopStart, pkiEncrypted = pki,
            nextHop = nextHop, relayNode = relayNode, transportMechanism = transport,
        )
    }

    fun decodeData(bytes: ByteArray): DataMsg {
        val r = ProtoReader(bytes)
        var port = 0; var payload = ByteArray(0); var wantResp = false
        var dest = 0; var source = 0; var requestId = 0; var replyId = 0
        r.forEachField { f, w ->
            when (f) {
                1 -> port = r.vint(w).toInt()
                2 -> payload = r.len(w) ?: ByteArray(0)
                3 -> wantResp = r.vint(w) != 0L
                4 -> dest = r.fx32(w)
                5 -> source = r.fx32(w)
                6 -> requestId = r.fx32(w)
                7 -> replyId = r.fx32(w)
                else -> r.skip(w)
            }
        }
        return DataMsg(port, payload, wantResp, dest, source, requestId, replyId)
    }

    fun decodeMyInfo(bytes: ByteArray): Int {
        val r = ProtoReader(bytes)
        var num = 0
        r.forEachField { f, w -> if (f == 1) num = r.vint(w).toInt() else r.skip(w) }
        return num
    }

    fun decodeUser(bytes: ByteArray): UserMsg {
        val r = ProtoReader(bytes)
        var id = ""; var longName = ""; var shortName = ""
        r.forEachField { f, w ->
            when (f) {
                1 -> id = r.str(w).orEmpty()
                2 -> longName = r.str(w).orEmpty()
                3 -> shortName = r.str(w).orEmpty()
                else -> r.skip(w)
            }
        }
        return UserMsg(id, longName, shortName)
    }

    fun decodePosition(bytes: ByteArray): PositionMsg {
        val r = ProtoReader(bytes)
        var latI: Int? = null; var lonI: Int? = null; var alt = 0
        var time = 0; var accMm = 0; var hdop = 0
        r.forEachField { f, w ->
            when (f) {
                1 -> latI = r.fx32(w)          // sfixed32 latitude_i
                2 -> lonI = r.fx32(w)          // sfixed32 longitude_i
                3 -> alt = r.vint(w).toInt()   // int32 altitude
                4 -> time = r.fx32(w)          // fixed32 time
                12 -> hdop = r.vint(w).toInt() // uint32 HDOP (en centésimas)
                14 -> accMm = r.vint(w).toInt() // uint32 gps_accuracy (mm)
                else -> r.skip(w)
            }
        }
        val lat = latI?.takeIf { it != 0 }?.let { it * 1e-7 }
        val lon = lonI?.takeIf { it != 0 }?.let { it * 1e-7 }
        return PositionMsg(lat, lon, alt, time, accMm, hdop)
    }

    /**
     * Ocupación del canal (%) de un paquete Telemetry: DeviceMetrics (variante 2, campo 3)
     * o LocalStats (variante 6, campo 2). Null si el paquete no la trae.
     */
    fun decodeChannelUtil(bytes: ByteArray): Float? {
        val r = ProtoReader(bytes)
        var util: Float? = null
        r.forEachField { f, w ->
            when (f) {
                2 -> r.len(w)?.let { util = floatField(it, 3) ?: util }
                6 -> r.len(w)?.let { util = floatField(it, 2) ?: util }
                else -> r.skip(w)
            }
        }
        return util?.takeIf { !it.isNaN() && it >= 0f && it <= 100f }
    }

    private fun floatField(bytes: ByteArray, field: Int): Float? {
        val r = ProtoReader(bytes)
        var v: Float? = null
        r.forEachField { f, w -> if (f == field && w == WIRE_FIXED32) v = r.float(w) else r.skip(w) }
        return v
    }

    fun decodeNodeInfo(bytes: ByteArray): NodeInfoMsg {
        val r = ProtoReader(bytes)
        var num = 0; var user: UserMsg? = null; var pos: PositionMsg? = null
        var snr = 0f; var lastHeard = 0; var hops: Int? = null; var chUtil: Float? = null
        r.forEachField { f, w ->
            when (f) {
                1 -> num = r.vint(w).toInt()
                2 -> r.len(w)?.let { user = decodeUser(it) }
                3 -> r.len(w)?.let { pos = decodePosition(it) }
                4 -> snr = r.float(w)
                5 -> lastHeard = r.fx32(w)
                6 -> r.len(w)?.let { chUtil = floatField(it, 3) }
                9 -> hops = r.vint(w).toInt()
                else -> r.skip(w)
            }
        }
        return NodeInfoMsg(num, user, pos, snr, lastHeard, hops, chUtil)
    }

    fun decodeChannel(bytes: ByteArray): ChannelMsg {
        val r = ProtoReader(bytes)
        var index = 0; var name = ""; var role = 0
        r.forEachField { f, w ->
            when (f) {
                1 -> index = r.vint(w).toInt()
                2 -> r.len(w)?.let { name = decodeChannelSettingsName(it) }
                3 -> role = r.vint(w).toInt()
                else -> r.skip(w)
            }
        }
        return ChannelMsg(index, name, role)
    }

    private fun decodeChannelSettingsName(bytes: ByteArray): String {
        val r = ProtoReader(bytes)
        var name = ""
        r.forEachField { f, w -> if (f == 3) name = r.str(w).orEmpty() else r.skip(w) }
        return name
    }

    /**
     * Routing { route_request=1, route_reply=2, error_reason=3 }.
     * Devuelve el código de error (0 = ACK), o null si es un route_request/reply.
     */
    fun decodeRoutingError(bytes: ByteArray): Int? {
        val r = ProtoReader(bytes)
        var err: Int? = null
        var isRoute = false
        r.forEachField { f, w ->
            when (f) {
                3 -> err = r.vint(w).toInt()
                1, 2 -> { r.skip(w); isRoute = true }
                else -> r.skip(w)
            }
        }
        return err ?: if (isRoute) null else RoutingError.NONE
    }
}

fun nodeIdStr(num: Int): String = "!%08x".format(num)
fun relayStr(b: Int): String = "0x%02x".format(b and 0xFF)
