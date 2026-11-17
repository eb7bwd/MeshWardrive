package com.meshwardrive.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.meshwardrive.app.R
import com.meshwardrive.app.ble.MeshBle
import com.meshwardrive.app.data.MODE_CHANNEL
import com.meshwardrive.app.data.MODE_DIRECT
import com.meshwardrive.app.data.MODE_LISTEN
import com.meshwardrive.app.data.CoverageGrid
import com.meshwardrive.app.data.TextSafety
import com.meshwardrive.app.data.TrackPoint
import com.meshwardrive.app.data.NodeEntry
import com.meshwardrive.app.data.PingRow
import com.meshwardrive.app.data.PingStatus
import com.meshwardrive.app.data.RxRow
import com.meshwardrive.app.data.Settings
import com.meshwardrive.app.data.WardriveDb
import com.meshwardrive.app.data.WardriveState
import com.meshwardrive.app.data.labelRes
import com.meshwardrive.app.data.WardriveState.Conn
import com.meshwardrive.app.proto.BROADCAST
import com.meshwardrive.app.proto.ChannelMsg
import com.meshwardrive.app.proto.DataMsg
import com.meshwardrive.app.proto.FromRadio
import com.meshwardrive.app.proto.MeshPacket
import com.meshwardrive.app.proto.MeshProto
import com.meshwardrive.app.proto.PortNum
import com.meshwardrive.app.proto.PositionMsg
import com.meshwardrive.app.proto.RoutingError
import com.meshwardrive.app.proto.Transport
import com.meshwardrive.app.proto.UserMsg
import com.meshwardrive.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlin.random.Random

class WardriveService : Service(), MeshBle.Listener {

    companion object {
        const val ACTION_CONNECT = "com.meshwardrive.CONNECT"
        const val ACTION_DISCONNECT = "com.meshwardrive.DISCONNECT"
        const val ACTION_START = "com.meshwardrive.START"
        const val ACTION_STOP = "com.meshwardrive.STOP"
        const val ACTION_PING = "com.meshwardrive.PING"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_NAME = "name"

        private const val NOTIF_CHANNEL = "wardrive"
        private const val NOTIF_ID = 1
        private const val ALERT_CHANNEL = "alerts"
        private const val ALERT_ID = 2
        private const val STORAGE_ID = 3

        fun send(ctx: Context, action: String, address: String? = null, name: String? = null) {
            val i = Intent(ctx, WardriveService::class.java).setAction(action)
            address?.let { i.putExtra(EXTRA_ADDRESS, it) }
            name?.let { i.putExtra(EXTRA_NAME, it) }
            if (action == ACTION_CONNECT) ContextCompat.startForegroundService(ctx, i)
            else ctx.startService(i)
        }
    }

    /** Toda la lógica corre en un único hilo: no hacen falta locks. */
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())

    private lateinit var ble: MeshBle
    private lateinit var db: WardriveDb
    private lateinit var settings: Settings
    private lateinit var locationManager: LocationManager

    private var wantConnected = false
    private var address: String? = null
    private var deviceName: String? = null
    private var reconnectJob: Job? = null

    private var myNodeNum = 0
    private var configId = 0
    private val channels = mutableMapOf<Int, ChannelMsg>()
    private val nodes = mutableMapOf<Int, NodeEntry>()

    private var session = newSessionId()
    private var seq = 0
    private var tickerJob: Job? = null
    private var pending: PendingPing? = null
    private var lastPingAt = 0L
    private var lastPingLoc: Location? = null
    private var locationActive = false
    private var gpsOffLogged = false
    private var disconnectedSince = 0L
    private var nodeFix: Location? = null
    private var lastTrackLoc: Location? = null
    private var lastTrackAt = 0L
    private val cellCounts = HashMap<CoverageGrid.Key, Int>()
    private var cellCountsSize = 0
    private var wakeLock: PowerManager.WakeLock? = null

    private class PendingPing(val row: PingRow) {
        var timeoutJob: Job? = null
    }

    // ------------------------------------------------------------ ciclo de vida

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        db = WardriveDb.get(this)
        settings = Settings(this)
        ble = MeshBle(this, this)
        locationManager = getSystemService(LocationManager::class.java)
        createNotificationChannel()
        WardriveState.session.value = session
        scope.launch {
            db.closeStalePings()
            db.allNodes().forEach { nodes[it.num] = it }
            WardriveState.nodes.value = nodes.toMap()
            WardriveState.stats.value = db.stats()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                goForeground(getString(R.string.n_connecting))
                val addr = intent.getStringExtra(EXTRA_ADDRESS)
                if (addr == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startLocation()
                address = addr
                deviceName = intent.getStringExtra(EXTRA_NAME)
                wantConnected = true
                disconnectedSince = 0L
                reconnectJob?.cancel()
                WardriveState.conn.value = WardriveState.ConnInfo(
                    conn = Conn.CONNECTING, deviceName = deviceName, address = addr,
                )
                ble.connect(addr)
            }
            ACTION_DISCONNECT -> scope.launch {
                wantConnected = false
                reconnectJob?.cancel()
                stopWardrive()
                ble.disconnect()
                WardriveState.conn.value = WardriveState.ConnInfo()
                WardriveState.log(getString(R.string.n_disconnected))
                stopLocation()
                ServiceCompat.stopForeground(this@WardriveService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_START -> scope.launch { startWardrive() }
            ACTION_STOP -> scope.launch { stopWardrive() }
            ACTION_PING -> scope.launch { doPing(manual = true) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        WardriveState.running.value = false
        releaseWakeLock()
        stopLocation()
        ble.shutdown()
        scope.cancel()
        executor.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------ BLE

    override fun onBleConnecting() {
        scope.launch {
            WardriveState.conn.value = WardriveState.conn.value.copy(conn = Conn.CONNECTING)
        }
    }

    override fun onBleStep(msg: String) {
        scope.launch {
            WardriveState.log(msg)
            WardriveState.conn.value = WardriveState.conn.value.copy(message = msg)
        }
    }

    override fun onBleReady() {
        scope.launch {
            channels.clear()
            configId = Random.nextInt(1, Int.MAX_VALUE)
            WardriveState.conn.value = WardriveState.conn.value.copy(conn = Conn.CONFIGURING)
            WardriveState.log(getString(R.string.st_configuring))
            try {
                ble.send(MeshProto.encodeWantConfig(configId))
            } catch (e: Exception) {
                WardriveState.log(getString(R.string.error_generic, e.message ?: "?"))
            }
        }
    }

    override fun onBleDisconnected(reason: String?) {
        scope.launch {
            WardriveState.log(getString(R.string.log_bt_lost, reason ?: "?"))
            if (!wantConnected) {
                WardriveState.conn.value = WardriveState.ConnInfo()
                return@launch
            }
            val now = System.currentTimeMillis()
            if (disconnectedSince == 0L) disconnectedSince = now
            val giveUpMin = settings.reconnectGiveUpMin
            if (giveUpMin > 0 && now - disconnectedSince >= giveUpMin * 60_000L) {
                giveUpReconnect(giveUpMin)
                return@launch
            }
            WardriveState.conn.value = WardriveState.conn.value.copy(
                conn = Conn.CONNECTING, message = getString(R.string.fail_retry, reason ?: "?"),
            )
            updateNotification()
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(5_000)
                val addr = address
                if (wantConnected && addr != null && !ble.isActive) ble.connect(addr)
            }
        }
    }

    /** El nodo lleva demasiado tiempo sin volver: se para todo y se avisa. */
    private fun giveUpReconnect(minutes: Int) {
        val msg = getString(R.string.gave_up_msg, minutes)
        wantConnected = false
        reconnectJob?.cancel()
        stopWardrive()
        ble.disconnect()
        WardriveState.conn.value = WardriveState.ConnInfo(message = msg)
        WardriveState.log(msg)
        stopLocation()
        notifyAlert(getString(R.string.gave_up_title), msg)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onFromRadio(bytes: ByteArray) {
        scope.launch { handleFromRadio(bytes) }
    }

    private fun handleFromRadio(bytes: ByteArray) {
        val msg = try {
            MeshProto.decodeFromRadio(bytes)
        } catch (e: Exception) {
            WardriveState.log("FromRadio decode error: ${e.message}")
            return
        }
        when (msg) {
            is FromRadio.MyInfo -> {
                myNodeNum = msg.myNodeNum
                WardriveState.conn.value = WardriveState.conn.value.copy(myNodeNum = myNodeNum)
            }
            is FromRadio.NodeInfo -> {
                val i = msg.info
                mergeNode(i.num, i.user, i.position, i.lastHeard.toLong() * 1000L, publish = false)
                if (i.num == myNodeNum && myNodeNum != 0) i.channelUtil?.let { WardriveState.channelUtil.value = it }
            }
            is FromRadio.Channel -> channels[msg.channel.index] = msg.channel
            is FromRadio.ConfigComplete -> if (msg.id == configId) onConfigComplete()
            is FromRadio.Packet -> handlePacket(msg.packet)
            FromRadio.Rebooted -> WardriveState.log(getString(R.string.log_rebooted))
            FromRadio.Other -> Unit
        }
    }

    private fun onConfigComplete() {
        disconnectedSince = 0L
        WardriveState.nodes.value = nodes.toMap()
        val ch = resolveChannel()
        val myName = nodes[myNodeNum]?.label
        WardriveState.conn.value = WardriveState.conn.value.copy(
            conn = Conn.READY,
            myNodeNum = myNodeNum,
            myName = myName,
            channelIndex = ch?.index,
            channelName = ch?.name ?: settings.channelName,
            message = null,
        )
        WardriveState.log(getString(R.string.log_ready, myName ?: "!%08x".format(myNodeNum), nodes.size))
        if (ch == null) {
            WardriveState.log(getString(R.string.log_no_channel, settings.channelName))
        } else {
            WardriveState.log(getString(R.string.st_channel, ch.name, ch.index))
        }
        updateNotification()
    }

    private fun resolveChannel(): ChannelMsg? {
        val wanted = settings.channelName
        return channels.values.firstOrNull {
            it.role != ChannelMsg.ROLE_DISABLED && it.name.equals(wanted, ignoreCase = true)
        }
    }

    // ------------------------------------------------------------ paquetes

    private fun handlePacket(p: MeshPacket) {
        val d = p.decoded

        // ¿Es la respuesta (ACK/NAK) al ping pendiente?
        if (d != null && d.portnum == PortNum.ROUTING && d.requestId != 0) {
            val pp = pending
            if (pp != null && pp.row.packetId == d.requestId) handleRouting(pp, p, d)
        }

        // Telemetría y posición de nuestro propio nodo
        if (d != null && p.from == myNodeNum && myNodeNum != 0) {
            when (d.portnum) {
                PortNum.TELEMETRY -> runCatching { MeshProto.decodeChannelUtil(d.payload) }.getOrNull()
                    ?.let { WardriveState.channelUtil.value = it }
                PortNum.POSITION -> runCatching { MeshProto.decodePosition(d.payload) }.getOrNull()
                    ?.let { onNodePosition(it) }
            }
        }

        if (p.from == myNodeNum || p.from == 0) return

        val now = System.currentTimeMillis()
        if (d != null) {
            when (d.portnum) {
                PortNum.NODEINFO -> runCatching { MeshProto.decodeUser(d.payload) }.getOrNull()
                    ?.let { mergeNode(p.from, it, null, now) }
                PortNum.POSITION -> runCatching { MeshProto.decodePosition(d.payload) }.getOrNull()
                    ?.let { mergeNode(p.from, null, it, now) }
                else -> mergeNode(p.from, null, null, now)
            }
        } else {
            mergeNode(p.from, null, null, now)
        }

        // Registro pasivo: todo lo que oímos por radio con nuestra posición
        val viaRadio = !p.viaMqtt &&
            p.transportMechanism != Transport.MQTT &&
            p.transportMechanism != Transport.MULTICAST_UDP &&
            p.transportMechanism != Transport.API &&
            p.hasRadioInfo
        // Con el registro pasivo desactivado solo se guarda lo dirigido a nosotros (ACK y respuestas),
        // salvo en modo solo escucha, que no tendría sentido sin él.
        val keep = settings.recordPassive || settings.mode == MODE_LISTEN || p.to == myNodeNum
        if (viaRadio && keep) logRx(p, now)
    }

    private var rxSinceCheck = 0

    /** Avisa cuando la recepción pasiva se acerca al límite, antes de que se borre lo más antiguo. */
    private fun checkStorage() {
        val n = runCatching { db.rxCount() }.getOrNull() ?: return
        val level = WardriveDb.storageLevel(n)
        val prev = settings.storageNotified
        if (level < prev) settings.storageNotified = level
        if (level <= prev) return
        settings.storageNotified = level
        val msg = getString(R.string.storage_msg, WardriveDb.storagePct(n))
        WardriveState.log(msg)
        notifyAlert(getString(R.string.storage_title), msg, STORAGE_ID)
    }

    private fun logRx(p: MeshPacket, now: Long) {
        val loc = currentFix(15_000)
        db.insertRx(
            RxRow(
                session = session,
                ts = now,
                lat = loc?.latitude,
                lon = loc?.longitude,
                acc = loc?.takeIf { it.hasAccuracy() }?.accuracy,
                from = p.from,
                to = p.to,
                channel = p.channel,
                portnum = p.decoded?.portnum ?: -1,
                snr = p.rxSnr,
                rssi = p.rxRssi.takeIf { it != 0 },
                hopStart = p.hopStart,
                hopLimit = p.hopLimit,
                relayNode = p.relayNode.takeIf { it != 0 },
                packetId = p.id,
            )
        )
        WardriveState.stats.value = WardriveState.stats.value.let { it.copy(rx = it.rx + 1) }
        WardriveState.bumpData()
        if (++rxSinceCheck >= 250) {
            rxSinceCheck = 0
            checkStorage()
        }
    }

    private fun handleRouting(pp: PendingPing, p: MeshPacket, d: DataMsg) {
        val row = pp.row
        val err = MeshProto.decodeRoutingError(d.payload) ?: return
        val now = System.currentTimeMillis()
        val fromDest = row.mode == MODE_DIRECT && p.from == row.dest && p.from != myNodeNum

        if (err == RoutingError.NONE) {
            if (fromDest) {
                // ACK real del nodo destino: SNR/RSSI/saltos del camino de vuelta
                row.status = PingStatus.ACK_DEST
                row.ackTs = now
                row.ackFrom = p.from
                row.ackSnr = if (p.hasRadioInfo) p.rxSnr else null
                row.ackRssi = p.rxRssi.takeIf { it != 0 }
                row.ackHops = p.hopsTaken
                row.ackRelayNode = p.relayNode.takeIf { it != 0 }
                finalizePing(pp)
            } else {
                // ACK implícito: nuestro nodo oyó a un vecino retransmitir el ping.
                // En firmware reciente el ACK trae relay_node + SNR/RSSI de esa retransmisión.
                if (row.status == PingStatus.PENDING) {
                    row.status = PingStatus.ACK_RELAY
                    row.ackTs = now
                    row.relayNode = p.relayNode.takeIf { it != 0 }
                    row.relaySnr = if (p.hasRadioInfo) p.rxSnr else null
                    row.relayRssi = p.rxRssi.takeIf { it != 0 }
                }
                if (row.mode == MODE_CHANNEL) finalizePing(pp) else savePing(row)
            }
        } else {
            row.errorReason = err
            if (row.status == PingStatus.PENDING) row.status = PingStatus.NAK
            finalizePing(pp)
        }
    }

    private fun mergeNode(num: Int, user: UserMsg?, pos: PositionMsg?, heardAt: Long, publish: Boolean = true) {
        if (num == 0) return
        val isNew = !nodes.containsKey(num)
        val old = nodes[num] ?: NodeEntry(num)
        val n = old.copy(
            // Los nombres llegan por radio y los pone quien quiere: se limpian y se acortan
            userId = TextSafety.clean(user?.id, 16) ?: old.userId,
            longName = TextSafety.clean(user?.longName, 40) ?: old.longName,
            shortName = TextSafety.clean(user?.shortName, 8) ?: old.shortName,
            lat = pos?.lat ?: old.lat,
            lon = pos?.lon ?: old.lon,
            lastHeard = maxOf(old.lastHeard, heardAt),
        )
        if (!isNew && n == old) return
        nodes[num] = n
        // A disco solo si es nuevo, cambia algo más que la hora, o la hora avanza > 10 min
        if (isNew || n.copy(lastHeard = 0) != old.copy(lastHeard = 0) ||
            n.lastHeard - old.lastHeard > 600_000L
        ) db.upsertNode(n)
        if (publish) WardriveState.nodes.value = nodes.toMap()
    }

    // ------------------------------------------------------------ wardriving

    private fun startWardrive() {
        if (WardriveState.conn.value.conn != Conn.READY) {
            WardriveState.log(getString(R.string.log_connect_first))
            return
        }
        if (WardriveState.running.value) return
        session = newSessionId()
        WardriveState.session.value = session
        db.ensureSession(session, System.currentTimeMillis())
        seq = 0
        lastPingAt = 0
        lastPingLoc = null
        lastTrackLoc = null
        lastTrackAt = 0L
        loadCellCounts()
        if (settings.mode == MODE_LISTEN) WardriveState.log(getString(R.string.blk_listen))
        else if (!settings.recordPassive) WardriveState.log(getString(R.string.log_passive_off))
        scope.launch { checkStorage() }
        WardriveState.running.value = true
        // Mantiene la CPU despierta con la pantalla apagada para que los pings salgan a su hora
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MeshWardrive:session")
                .apply { setReferenceCounted(false) }
        }
        runCatching { wakeLock?.acquire(12 * 60 * 60 * 1000L) }
        WardriveState.log(getString(R.string.log_session_start, session))
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(1_000)
                maybeAutoPing()
            }
        }
        updateNotification()
    }

    private fun stopWardrive() {
        if (!WardriveState.running.value) return
        tickerJob?.cancel()
        tickerJob = null
        WardriveState.running.value = false
        WardriveState.blocker.value = null
        releaseWakeLock()
        WardriveState.log(getString(R.string.log_session_stop))
        updateNotification()
    }

    /** Deja a la vista por qué no sale un ping; al registro solo va cuando el motivo cambia. */
    private fun blocked(reason: String, manual: Boolean) {
        if (manual || WardriveState.blocker.value != reason) WardriveState.log("⏸ $reason")
        WardriveState.blocker.value = reason
    }

    private suspend fun maybeAutoPing() {
        if (pending != null) return
        if (settings.mode == MODE_LISTEN) {
            WardriveState.blocker.value = getString(R.string.blk_listen)
            return
        }
        if (System.currentTimeMillis() - lastPingAt < settings.intervalS * 1000L) return
        if (settings.chUtilPause) {
            val u = WardriveState.channelUtil.value
            if (u != null && u >= settings.chUtilThreshold) {
                blocked(getString(R.string.blk_chutil, u.roundToInt()), false)
                return
            }
        }
        val loc = currentFix(10_000)
        val last = lastPingLoc
        if (loc != null && last != null) {
            val left = settings.minDistanceM - loc.distanceTo(last)
            if (left > 0) {
                // No es un fallo: se muestra sin llenar el registro
                WardriveState.blocker.value = getString(R.string.wait_move, left.toInt() + 1)
                return
            }
        }
        if (loc != null && settings.smartPing) {
            val n = cellCount(loc)
            if (n >= settings.smartMin) {
                WardriveState.blocker.value = getString(R.string.blk_cell_done, n)
                return
            }
        }
        doPing(manual = false)
    }

    /** Pings ya hechos por celda, para el ping inteligente. */
    private fun loadCellCounts() {
        cellCounts.clear()
        cellCountsSize = settings.cellSizeM
        val size = cellCountsSize.toDouble()
        for (p in db.allPings()) {
            val k = CoverageGrid.keyOf(p.lat, p.lon, size)
            cellCounts[k] = (cellCounts[k] ?: 0) + 1
        }
    }

    private fun cellCount(loc: Location): Int {
        if (cellCountsSize != settings.cellSizeM) loadCellCounts()
        return cellCounts[CoverageGrid.keyOf(loc.latitude, loc.longitude, cellCountsSize.toDouble())] ?: 0
    }

    private suspend fun doPing(manual: Boolean) {
        if (WardriveState.conn.value.conn != Conn.READY) {
            blocked(getString(R.string.log_connect_first), manual)
            return
        }
        if (pending != null) {
            if (manual) WardriveState.log(getString(R.string.log_wait_prev))
            return
        }
        if (settings.mode == MODE_LISTEN) {
            blocked(getString(R.string.blk_listen), manual)
            return
        }
        val loc = currentFix(if (manual) 30_000 else 10_000)
        if (loc == null) {
            blocked(getString(R.string.log_no_gps), manual)
            return
        }
        if (loc.hasAccuracy() && loc.accuracy > settings.maxAccuracyM) {
            blocked(getString(R.string.log_gps_poor, loc.accuracy.toInt()), manual)
            return
        }

        val mode = settings.mode
        val ch = resolveChannel()
        val dest: Int
        if (mode == MODE_DIRECT) {
            dest = settings.targetNode
            if (dest == 0) {
                blocked(getString(R.string.log_pick_target), manual)
                return
            }
        } else {
            dest = BROADCAST
            if (ch == null) {
                blocked(getString(R.string.st_missing_channel, settings.channelName), manual)
                return
            }
        }
        // Directo: canal 0, así el firmware usa la clave del destino (PKI) o la del canal
        // primario, y el destino no necesita tener el canal de wardriving.
        val channelIndex = if (mode == MODE_DIRECT) 0 else (ch?.index ?: 0)

        seq++
        val text = buildString {
            append(settings.pingPrefix).append(" #").append(seq)
            if (settings.includeCoords) {
                append(String.format(Locale.US, " %.4f,%.4f", loc.latitude, loc.longitude))
            }
        }
        val id = Random.nextInt(1, Int.MAX_VALUE)
        val row = PingRow(
            packetId = id,
            session = session,
            ts = System.currentTimeMillis(),
            lat = loc.latitude,
            lon = loc.longitude,
            alt = if (loc.hasAltitude()) loc.altitude else null,
            acc = if (loc.hasAccuracy()) loc.accuracy else null,
            speed = if (loc.hasSpeed()) loc.speed else null,
            mode = mode,
            channel = channelIndex,
            dest = dest,
            hopLimit = settings.hopLimit,
            text = text,
        )
        row.rowId = db.insertPing(row)
        val pp = PendingPing(row)
        pending = pp
        WardriveState.blocker.value = null
        lastPingAt = row.ts
        lastPingLoc = loc
        if (cellCountsSize == settings.cellSizeM) {
            val k = CoverageGrid.keyOf(loc.latitude, loc.longitude, cellCountsSize.toDouble())
            cellCounts[k] = (cellCounts[k] ?: 0) + 1
        }

        val packet = MeshPacket(
            to = dest,
            channel = channelIndex,
            decoded = DataMsg(PortNum.TEXT_MESSAGE, text.toByteArray(Charsets.UTF_8)),
            id = id,
            hopLimit = settings.hopLimit,
            wantAck = true,
        )
        try {
            ble.send(MeshProto.encodeToRadioPacket(packet))
        } catch (e: Exception) {
            row.status = PingStatus.SEND_ERROR
            WardriveState.log(getString(R.string.ps_send_error) + ": ${e.message}")
            finalizePing(pp)
            return
        }
        val target = if (dest == BROADCAST) "${ch?.name} (#$channelIndex)" else WardriveState.nodeLabel(dest)
        WardriveState.log("→ Ping #$seq → $target")
        if (pending === pp) savePing(row)
        WardriveState.stats.value = db.stats()
        WardriveState.bumpData()

        pp.timeoutJob = scope.launch {
            delay(settings.ackTimeoutS * 1000L)
            if (pending === pp) {
                if (pp.row.status == PingStatus.PENDING) pp.row.status = PingStatus.TIMEOUT
                finalizePing(pp)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
    }

    private fun savePing(row: PingRow) {
        db.updatePing(row)
        WardriveState.lastPing.value = row.copy()
    }

    private fun finalizePing(pp: PendingPing) {
        pp.timeoutJob?.cancel()
        if (pending === pp) pending = null
        val row = pp.row
        savePing(row)
        WardriveState.log("← ${describe(row)}")
        WardriveState.stats.value = db.stats()
        WardriveState.bumpData()
        updateNotification()
    }

    private fun describe(r: PingRow): String = buildString {
        append(r.text.split(' ').take(2).joinToString(" ")).append(' ')
        append(getString(r.status.labelRes))
        when (r.status) {
            PingStatus.ACK_DEST -> {
                append(" (").append(WardriveState.nodeLabel(r.ackFrom ?: 0)).append(')')
                r.ackSnr?.let { append(" · SNR %.1f dB".format(it)) }
                r.ackRssi?.let { append(" · RSSI $it") }
                r.ackHops?.let { append(" · ").append(getString(R.string.hops, it)) }
                r.ackRelayNode?.let { append(" · ").append(getString(R.string.last_hop, WardriveState.relayLabel(it))) }
                if (r.relayNode != null) append(" · relay ${WardriveState.relayLabel(r.relayNode)}")
            }
            PingStatus.ACK_RELAY -> {
                r.relayNode?.let { append(" · relay ${WardriveState.relayLabel(it)}") }
                r.relaySnr?.let { append(" · SNR %.1f dB".format(it)) }
                r.relayRssi?.let { append(" · RSSI $it") }
            }
            PingStatus.NAK -> append(" · ${RoutingError.name(r.errorReason ?: -1)}")
            else -> Unit
        }
        r.latencyMs?.let { append(" · %.1f s".format(it / 1000.0)) }
    }

    // ------------------------------------------------------------ GPS

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // La posición por red (wifi/antenas) solo se usa si no hay una de GPS reciente
            if (location.provider != LocationManager.GPS_PROVIDER) {
                val cur = WardriveState.location.value
                if (cur != null && cur.provider == LocationManager.GPS_PROVIDER) {
                    val ageMs = (location.elapsedRealtimeNanos - cur.elapsedRealtimeNanos) / 1_000_000
                    if (ageMs < 10_000) return
                }
            }
            WardriveState.location.value = location
            if (WardriveState.running.value) scope.launch { maybeTrack(location) }
        }

        override fun onProviderEnabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) gpsOffLogged = false
        }

        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER && !gpsOffLogged) {
                gpsOffLogged = true
                WardriveState.log(getString(R.string.log_gps_off))
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (locationActive || !hasLocationPermission()) return
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1_000L, 0f, locationListener, Looper.getMainLooper(),
            )
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                if (WardriveState.location.value == null) WardriveState.location.value = it
            }
            locationActive = true
            // Respaldo para interiores: posición por red, si el móvil la ofrece
            runCatching {
                if (locationManager.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, 5_000L, 0f, locationListener, Looper.getMainLooper(),
                    )
                }
            }
        } catch (e: Exception) {
            WardriveState.log("GPS: ${e.message}")
        }
    }

    private fun stopLocation() {
        if (!locationActive) return
        runCatching { locationManager.removeUpdates(locationListener) }
        locationActive = false
    }

    private fun ageMs(l: Location): Long = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000

    /**
     * Posición a usar. Por defecto la del móvil; con la opción "GPS del nodo", la del nodo
     * cuando la del móvil falta o no alcanza la precisión mínima.
     */
    private fun currentFix(maxAgeMs: Long): Location? {
        val phone = WardriveState.location.value?.takeIf { ageMs(it) <= maxAgeMs }
        if (!settings.nodeGps) return phone
        val node = nodeFix?.takeIf { ageMs(it) <= maxAgeMs } ?: return phone
        val phoneGood = phone != null && (!phone.hasAccuracy() || phone.accuracy <= settings.maxAccuracyM)
        return if (phoneGood) phone else node
    }

    /** Posición que envía nuestro propio nodo, si tiene GPS. */
    private fun onNodePosition(pos: PositionMsg) {
        val lat = pos.lat ?: return
        val lon = pos.lon ?: return
        val nowS = System.currentTimeMillis() / 1000
        // Una posición antigua no sirve para situar el ping de ahora
        if (pos.time != 0 && kotlin.math.abs(nowS - (pos.time.toLong() and 0xFFFFFFFFL)) > 120) return
        val hdop = if (pos.hdop > 0) pos.hdop / 100f else 0f
        val accM = when {
            pos.accuracyMm > 0 && hdop > 0f -> pos.accuracyMm / 1000f * hdop
            pos.accuracyMm > 0 -> pos.accuracyMm / 1000f
            hdop > 0f -> hdop * 5f
            else -> 20f
        }
        val l = Location("meshtastic-node").apply {
            latitude = lat
            longitude = lon
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            accuracy = accM.coerceIn(1f, 10_000f)
        }
        nodeFix = l
        if (settings.nodeGps && WardriveState.running.value && currentFix(10_000) === l) maybeTrack(l)
    }

    /** Guarda la ruta: un punto cada 15 m de avance, o cada minuto si se avanza poco. */
    private fun maybeTrack(loc: Location) {
        if (!WardriveState.running.value) return
        if (loc.hasAccuracy() && loc.accuracy > settings.maxAccuracyM) return
        val last = lastTrackLoc
        val now = System.currentTimeMillis()
        if (last != null) {
            val d = loc.distanceTo(last)
            if (d < 3f) return
            if (d < 15f && now - lastTrackAt < 60_000L) return
        }
        runCatching {
            db.insertTrack(
                TrackPoint(session, now, loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else null)
            )
        }
        lastTrackLoc = loc
        lastTrackAt = now
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------ notificación

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(NOTIF_CHANNEL, "Wardriving", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, getString(R.string.gave_up_title), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_wardrive)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
        if (WardriveState.running.value) {
            // Parar el wardrive sin abrir la app
            val stop = PendingIntent.getService(
                this, 1,
                Intent(this, WardriveService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(android.R.drawable.ic_media_pause, getString(R.string.stop), stop)
        }
        return b.build()
    }

    private fun notifyAlert(title: String, text: String, id: Int = ALERT_ID) {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_wardrive)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(id, n) }
    }

    private fun goForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= 29) {
            var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (hasLocationPermission()) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            t
        } else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), type)
    }

    private fun updateNotification() {
        val c = WardriveState.conn.value
        val s = WardriveState.stats.value
        val text = when (c.conn) {
            Conn.READY -> {
                val state = if (WardriveState.running.value) "Wardriving" else getString(R.string.n_connected)
                "$state · pings ${s.sent} · ✓ ${s.ackRelay + s.ackDest} · ✗ ${s.failed}"
            }
            Conn.DISCONNECTED -> getString(R.string.n_disconnected)
            else -> c.message ?: getString(R.string.n_connecting)
        }
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        }
    }

    private fun newSessionId(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}
