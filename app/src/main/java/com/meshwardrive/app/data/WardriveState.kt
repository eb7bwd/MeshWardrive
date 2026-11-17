package com.meshwardrive.app.data

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Estado compartido entre el servicio y la interfaz. */
object WardriveState {

    enum class Conn { DISCONNECTED, CONNECTING, CONFIGURING, READY }

    data class ConnInfo(
        val conn: Conn = Conn.DISCONNECTED,
        val deviceName: String? = null,
        val address: String? = null,
        val myNodeNum: Int = 0,
        val myName: String? = null,
        val channelIndex: Int? = null,
        val channelName: String? = null,
        val message: String? = null,
    )

    val conn = MutableStateFlow(ConnInfo())
    val running = MutableStateFlow(false)

    /** Identificador del wardrive en curso (o del último). */
    val session = MutableStateFlow("")
    val location = MutableStateFlow<Location?>(null)
    val lastPing = MutableStateFlow<PingRow?>(null)

    /** Motivo por el que ahora mismo no sale un ping (sin GPS, sin moverse…), o null. */
    val blocker = MutableStateFlow<String?>(null)

    /** Ocupación del canal (%) que informa el nodo, o null si aún no ha llegado. */
    val channelUtil = MutableStateFlow<Float?>(null)
    val stats = MutableStateFlow(Stats())
    val nodes = MutableStateFlow<Map<Int, NodeEntry>>(emptyMap())

    /** Se incrementa cada vez que cambian los datos guardados (para repintar el mapa). */
    val dataVersion = MutableStateFlow(0L)

    val log = MutableStateFlow<List<String>>(emptyList())

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun log(msg: String) {
        val line = "${timeFmt.format(Date())}  $msg"
        log.update { (it + line).takeLast(300) }
    }

    fun bumpData() = dataVersion.update { it + 1 }

    fun nodeLabel(num: Int): String = nodes.value[num]?.label ?: "!%08x".format(num)

    /** Nodos cuyo número acaba en el byte relay_node (el firmware solo envía el último byte). */
    fun relayCandidates(relayByte: Int?): List<NodeEntry> {
        if (relayByte == null) return emptyList()
        val my = conn.value.myNodeNum
        return nodes.value.values.filter { (it.num and 0xFF) == (relayByte and 0xFF) && it.num != my }
    }

    fun relayLabel(relayByte: Int?): String {
        if (relayByte == null) return "?"
        val c = relayCandidates(relayByte)
        val hex = "0x%02x".format(relayByte and 0xFF)
        return when {
            c.isEmpty() -> hex
            c.size == 1 -> "${c[0].label} ($hex)"
            else -> "$hex: " + c.joinToString(" / ") { it.shortName ?: it.label }
        }
    }
}
