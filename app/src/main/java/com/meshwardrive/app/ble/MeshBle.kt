package com.meshwardrive.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.meshwardrive.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/**
 * Cliente BLE para la API de radio de Meshtastic:
 *  - Escribir ToRadio (protobuf) en la característica TORADIO.
 *  - Cuando FROMNUM notifica, leer FROMRADIO repetidamente hasta recibir 0 bytes.
 *  - Al conectar se envía want_config_id y el nodo vuelca su configuración y NodeDB.
 */
@SuppressLint("MissingPermission")
class MeshBle(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onBleConnecting()
        /** Paso actual de la conexión, para mostrarlo al usuario y dejarlo en el registro. */
        fun onBleStep(msg: String)
        fun onBleReady()
        fun onBleDisconnected(reason: String?)
        fun onFromRadio(bytes: ByteArray)
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
        val TORADIO_UUID: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
        val FROMRADIO_UUID: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
        val FROMNUM_UUID: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Tipo de operación GATT en curso: cada callback solo completa la suya
        private const val OP_MTU = 1
        private const val OP_DISCOVER = 2
        private const val OP_READ = 3
        private const val OP_WRITE = 4
        private const val OP_DESC = 5
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val opMutex = Mutex()
    private val lock = Any()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var toRadio: BluetoothGattCharacteristic? = null
    @Volatile private var fromRadio: BluetoothGattCharacteristic? = null
    @Volatile private var pending: CompletableDeferred<Any?>? = null
    @Volatile private var pendingKind = 0
    @Volatile private var connectDeferred: CompletableDeferred<Unit>? = null
    @Volatile private var drainSignal = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var sessionJob: Job? = null
    @Volatile private var activeSession = 0
    private var sessionCounter = 0

    @Volatile var isReady = false
        private set

    /** true mientras hay una sesión en curso (conectando o conectada). */
    val isActive: Boolean get() = activeSession != 0

    fun connect(address: String) {
        closeInternal()
        val sid: Int
        synchronized(lock) {
            sid = ++sessionCounter
            activeSession = sid
        }
        listener.onBleConnecting()
        sessionJob = scope.launch {
            try {
                runSession(sid, address)
            } catch (e: CancellationException) {
                // cierre manual o reconexión
            } catch (e: Exception) {
                failSession(sid, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Desconexión pedida por el usuario: no avisa al listener. */
    fun disconnect() = closeInternal()

    fun shutdown() {
        closeInternal()
        scope.cancel()
    }

    /** Envía un ToRadio ya codificado. Lanza excepción si no se pudo escribir. */
    suspend fun send(bytes: ByteArray) {
        val g = gatt ?: throw IOException("not connected")
        val ch = toRadio ?: throw IOException("not connected")
        if (!isReady) throw IOException("node not ready")
        op<Any?>(OP_WRITE) { writeChar(g, ch, bytes) }
        drainSignal.trySend(Unit)
    }

    // ------------------------------------------------------------------

    private suspend fun runSession(sid: Int, address: String) {
        val manager = ctx.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter ?: throw IOException(ctx.getString(R.string.err_bt_off))
        if (!adapter.isEnabled) throw IOException(ctx.getString(R.string.err_bt_off))
        val device = adapter.getRemoteDevice(address)

        delay(400) // deja que termine el escaneo antes de conectar

        // Hasta 3 intentos: en Android el primero falla a menudo (estado 133)
        drainSignal = Channel(Channel.CONFLATED)
        var opened: BluetoothGatt? = null
        var attempt = 0
        while (opened == null) {
            attempt++
            listener.onBleStep(
                if (attempt == 1) ctx.getString(R.string.ble_connecting) else ctx.getString(R.string.ble_retry, attempt)
            )
            val connected = CompletableDeferred<Unit>()
            connectDeferred = connected
            val cand = withContext(Dispatchers.Main) {
                device.connectGatt(ctx, false, SessionCallback(sid), BluetoothDevice.TRANSPORT_LE)
            } ?: throw IOException("connectGatt returned null")
            gatt = cand
            val error: IOException? = try {
                // Ojo: withTimeout lanzaría una CancellationException y el fallo pasaría desapercibido
                withTimeoutOrNull(20_000) { connected.await() }
                    ?: throw IOException(ctx.getString(R.string.err_no_answer))
                null
            } catch (e: IOException) {
                e
            }
            if (error == null) {
                opened = cand
            } else {
                gatt = null
                runCatching { cand.disconnect() }
                runCatching { cand.close() }
                if (attempt >= 3) throw error
                delay(1_500)
            }
        }
        val g = opened ?: throw IOException("not connected")
        delay(600)

        // Emparejar con el enlace BLE ya abierto: así Android empareja por LE y no por Bluetooth clásico
        bondIfNeeded(device)

        listener.onBleStep(ctx.getString(R.string.ble_reading))
        // MTU grande para que los FromRadio quepan en una sola lectura
        runCatching { op<Any?>(OP_MTU, 5_000) { g.requestMtu(512) } }

        op<Any?>(OP_DISCOVER, 15_000) { g.discoverServices() }
        var svc = g.getService(SERVICE_UUID)
        if (svc == null) {
            // Puede ser la caché GATT de Android: se vacía y se vuelve a preguntar
            runCatching { g.javaClass.getMethod("refresh").invoke(g) }
            delay(800)
            op<Any?>(OP_DISCOVER, 15_000) { g.discoverServices() }
            svc = g.getService(SERVICE_UUID)
        }
        val service = svc
            ?: throw IOException(ctx.getString(R.string.err_not_mesh))
        val have = service.characteristics.joinToString { it.uuid.toString().take(8) }
        val tr = service.getCharacteristic(TORADIO_UUID)
            ?: throw IOException("missing ToRadio characteristic; found: $have")
        val fr = service.getCharacteristic(FROMRADIO_UUID)
            ?: throw IOException("missing FromRadio characteristic; found: $have")
        val fn = service.getCharacteristic(FROMNUM_UUID)
            ?: throw IOException("missing FromNum characteristic; found: $have")
        toRadio = tr
        fromRadio = fr

        enableNotify(g, fn)

        isReady = true
        listener.onBleReady()

        coroutineScope {
            launch {
                for (s in drainSignal) drain(g, fr)
            }
            launch {
                // Por si se pierde alguna notificación
                while (true) {
                    delay(10_000)
                    drainSignal.trySend(Unit)
                }
            }
        }
    }

    private suspend fun drain(g: BluetoothGatt, fr: BluetoothGattCharacteristic) {
        while (true) {
            val v = op<ByteArray>(OP_READ, 10_000) { g.readCharacteristic(fr) }
            if (v.isEmpty()) return
            try {
                listener.onFromRadio(v)
            } catch (e: Exception) {
                // nunca tumbar la sesión por un paquete raro
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        if (!g.setCharacteristicNotification(ch, true)) throw IOException("cannot enable notifications")
        val desc = ch.getDescriptor(CCCD_UUID) ?: return
        val value = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        op<Any?>(OP_DESC) {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(desc, value) == BluetoothStatusCodes.SUCCESS
            } else {
                desc.value = value
                g.writeDescriptor(desc)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun writeChar(g: BluetoothGatt, ch: BluetoothGattCharacteristic, bytes: ByteArray): Boolean {
        val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, bytes, type) == BluetoothStatusCodes.SUCCESS
        } else {
            ch.writeType = type
            ch.value = bytes
            g.writeCharacteristic(ch)
        }
    }

    /** Ejecuta una operación GATT y espera su callback. Android exige hacerlas de una en una. */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> op(kind: Int, timeoutMs: Long = 10_000, start: () -> Boolean): T =
        opMutex.withLock {
            val d = CompletableDeferred<Any?>()
            pendingKind = kind
            pending = d
            try {
                if (!start()) throw IOException("GATT operation could not start")
                // withTimeoutOrNull + IOException: un timeout debe tumbar la sesión y reconectar
                val result = withTimeoutOrNull(timeoutMs) { d.await() }
                    ?: throw IOException("GATT operation timed out")
                result as T
            } finally {
                pending = null
                pendingKind = 0
            }
        }

    private suspend fun bondIfNeeded(device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return
        val done = CompletableDeferred<Int>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d: BluetoothDevice? =
                    IntentCompat.getParcelableExtra(i, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (d?.address != device.address) return
                val st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                if (st == BluetoothDevice.BOND_BONDED || st == BluetoothDevice.BOND_NONE) done.complete(st)
            }
        }
        ContextCompat.registerReceiver(
            ctx, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        try {
            val started = device.bondState == BluetoothDevice.BOND_BONDING || device.createBond()
            if (!started) return // seguimos sin emparejar; si el nodo lo exige fallará la lectura
            listener.onBleStep(ctx.getString(R.string.ble_pairing))
            val st = withTimeoutOrNull(60_000) { done.await() }
            if (st != BluetoothDevice.BOND_BONDED && device.bondState != BluetoothDevice.BOND_BONDED) {
                throw IOException(ctx.getString(R.string.err_pair_failed))
            }
            delay(1_500)
        } finally {
            runCatching { ctx.unregisterReceiver(receiver) }
        }
    }

    private fun failSession(sid: Int, reason: String) {
        synchronized(lock) {
            if (sid != activeSession) return
            activeSession = 0
        }
        sessionJob?.cancel()
        sessionJob = null
        cleanup()
        listener.onBleDisconnected(reason)
    }

    private fun closeInternal() {
        synchronized(lock) { activeSession = 0 }
        sessionJob?.cancel()
        sessionJob = null
        cleanup()
    }

    private fun cleanup() {
        isReady = false
        pending?.completeExceptionally(IOException("disconnected"))
        connectDeferred?.completeExceptionally(IOException("disconnected"))
        val g = gatt
        gatt = null
        toRadio = null
        fromRadio = null
        if (g != null) {
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        drainSignal.close()
    }

    private fun finish(kind: Int, status: Int, value: Any?) {
        if (pendingKind != kind) return
        val p = pending ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) p.complete(value)
        else p.completeExceptionally(
            IOException(
                when (status) {
                    5, 8, 15, 137 -> ctx.getString(R.string.err_pairing_gatt, status)
                    else -> "GATT error $status"
                }
            )
        )
    }

    private inner class SessionCallback(private val sid: Int) : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (sid != activeSession) return
            val cd = connectDeferred
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                cd?.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                if (cd != null && !cd.isCompleted) {
                    // Aún conectando: runSession decide si reintenta
                    cd.completeExceptionally(IOException(ctx.getString(R.string.err_connect, status)))
                } else {
                    failSession(sid, ctx.getString(R.string.err_lost, status))
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = finish(OP_MTU, status, mtu)

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = finish(OP_DISCOVER, status, Unit)

        override fun onCharacteristicRead(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) = finish(OP_READ, status, value)

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) =
            finish(OP_READ, status, ch.value ?: ByteArray(0))

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) =
            finish(OP_WRITE, status, status)

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) =
            finish(OP_DESC, status, status)

        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            if (ch.uuid == FROMNUM_UUID) drainSignal.trySend(Unit)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid == FROMNUM_UUID) drainSignal.trySend(Unit)
        }
    }
}
