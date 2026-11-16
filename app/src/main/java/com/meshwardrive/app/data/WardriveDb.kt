package com.meshwardrive.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class WardriveDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx, "wardrive.db", null, 1) {

    companion object {
        @Volatile private var instance: WardriveDb? = null
        fun get(ctx: Context): WardriveDb =
            instance ?: synchronized(this) {
                instance ?: WardriveDb(ctx.applicationContext).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE pings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                packet_id INTEGER, session TEXT, ts INTEGER,
                lat REAL, lon REAL, alt REAL, acc REAL, speed REAL,
                mode TEXT, channel INTEGER, dest INTEGER, hop_limit INTEGER, text TEXT,
                status TEXT, error_reason INTEGER, ack_ts INTEGER,
                relay_node INTEGER, relay_snr REAL, relay_rssi INTEGER,
                ack_from INTEGER, ack_snr REAL, ack_rssi INTEGER, ack_hops INTEGER, ack_relay_node INTEGER
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE rx (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session TEXT, ts INTEGER, lat REAL, lon REAL, acc REAL,
                from_node INTEGER, to_node INTEGER, channel INTEGER, portnum INTEGER,
                snr REAL, rssi INTEGER, hop_start INTEGER, hop_limit INTEGER,
                relay_node INTEGER, packet_id INTEGER
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE nodes (
                num INTEGER PRIMARY KEY, user_id TEXT, long_name TEXT, short_name TEXT,
                lat REAL, lon REAL, last_heard INTEGER
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_pings_ts ON pings(ts)")
        db.execSQL("CREATE INDEX idx_rx_ts ON rx(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Versión 1: nada que migrar todavía.
    }

    // ---------------- Pings ----------------

    private fun pingValues(r: PingRow) = ContentValues().apply {
        put("packet_id", r.packetId)
        put("session", r.session)
        put("ts", r.ts)
        put("lat", r.lat)
        put("lon", r.lon)
        put("alt", r.alt)
        put("acc", r.acc)
        put("speed", r.speed)
        put("mode", r.mode)
        put("channel", r.channel)
        put("dest", r.dest)
        put("hop_limit", r.hopLimit)
        put("text", r.text)
        put("status", r.status.name)
        put("error_reason", r.errorReason)
        put("ack_ts", r.ackTs)
        put("relay_node", r.relayNode)
        put("relay_snr", r.relaySnr)
        put("relay_rssi", r.relayRssi)
        put("ack_from", r.ackFrom)
        put("ack_snr", r.ackSnr)
        put("ack_rssi", r.ackRssi)
        put("ack_hops", r.ackHops)
        put("ack_relay_node", r.ackRelayNode)
    }

    fun insertPing(r: PingRow): Long = writableDatabase.insert("pings", null, pingValues(r))

    fun updatePing(r: PingRow) {
        writableDatabase.update("pings", pingValues(r), "id = ?", arrayOf(r.rowId.toString()))
    }

    fun allPings(): List<PingRow> =
        readableDatabase.rawQuery("SELECT * FROM pings ORDER BY ts", null).use { c ->
            val out = ArrayList<PingRow>(c.count)
            while (c.moveToNext()) out.add(c.toPing())
            out
        }

    private fun Cursor.toPing() = PingRow(
        rowId = long("id")!!,
        packetId = int("packet_id") ?: 0,
        session = str("session").orEmpty(),
        ts = long("ts") ?: 0,
        lat = dbl("lat") ?: 0.0,
        lon = dbl("lon") ?: 0.0,
        alt = dbl("alt"),
        acc = flt("acc"),
        speed = flt("speed"),
        mode = str("mode") ?: MODE_CHANNEL,
        channel = int("channel") ?: 0,
        dest = int("dest") ?: 0,
        hopLimit = int("hop_limit") ?: 0,
        text = str("text").orEmpty(),
        status = runCatching { PingStatus.valueOf(str("status")!!) }.getOrDefault(PingStatus.TIMEOUT),
        errorReason = int("error_reason"),
        ackTs = long("ack_ts"),
        relayNode = int("relay_node"),
        relaySnr = flt("relay_snr"),
        relayRssi = int("relay_rssi"),
        ackFrom = int("ack_from"),
        ackSnr = flt("ack_snr"),
        ackRssi = int("ack_rssi"),
        ackHops = int("ack_hops"),
        ackRelayNode = int("ack_relay_node"),
    )

    /** Pings que quedaron a medias (app cerrada); se marcan como sin respuesta. */
    fun closeStalePings() {
        writableDatabase.execSQL("UPDATE pings SET status = 'TIMEOUT' WHERE status = 'PENDING'")
    }

    // ---------------- Recepción pasiva ----------------

    fun insertRx(r: RxRow): Long = writableDatabase.insert("rx", null, ContentValues().apply {
        put("session", r.session)
        put("ts", r.ts)
        put("lat", r.lat)
        put("lon", r.lon)
        put("acc", r.acc)
        put("from_node", r.from)
        put("to_node", r.to)
        put("channel", r.channel)
        put("portnum", r.portnum)
        put("snr", r.snr)
        put("rssi", r.rssi)
        put("hop_start", r.hopStart)
        put("hop_limit", r.hopLimit)
        put("relay_node", r.relayNode)
        put("packet_id", r.packetId)
    })

    fun allRx(onlyWithFix: Boolean): List<RxRow> {
        val where = if (onlyWithFix) "WHERE lat IS NOT NULL AND lon IS NOT NULL" else ""
        return readableDatabase.rawQuery("SELECT * FROM rx $where ORDER BY ts", null).use { c ->
            val out = ArrayList<RxRow>(c.count)
            while (c.moveToNext()) {
                out.add(
                    RxRow(
                        rowId = c.long("id")!!,
                        session = c.str("session").orEmpty(),
                        ts = c.long("ts") ?: 0,
                        lat = c.dbl("lat"),
                        lon = c.dbl("lon"),
                        acc = c.flt("acc"),
                        from = c.int("from_node") ?: 0,
                        to = c.int("to_node") ?: 0,
                        channel = c.int("channel") ?: 0,
                        portnum = c.int("portnum") ?: -1,
                        snr = c.flt("snr"),
                        rssi = c.int("rssi"),
                        hopStart = c.int("hop_start") ?: 0,
                        hopLimit = c.int("hop_limit") ?: 0,
                        relayNode = c.int("relay_node"),
                        packetId = c.int("packet_id") ?: 0,
                    )
                )
            }
            out
        }
    }

    // ---------------- Nodos ----------------

    fun upsertNode(n: NodeEntry) {
        writableDatabase.insertWithOnConflict("nodes", null, ContentValues().apply {
            put("num", n.num)
            put("user_id", n.userId)
            put("long_name", n.longName)
            put("short_name", n.shortName)
            put("lat", n.lat)
            put("lon", n.lon)
            put("last_heard", n.lastHeard)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun allNodes(): List<NodeEntry> =
        readableDatabase.rawQuery("SELECT * FROM nodes", null).use { c ->
            val out = ArrayList<NodeEntry>(c.count)
            while (c.moveToNext()) {
                out.add(
                    NodeEntry(
                        num = c.int("num") ?: 0,
                        userId = c.str("user_id"),
                        longName = c.str("long_name"),
                        shortName = c.str("short_name"),
                        lat = c.dbl("lat"),
                        lon = c.dbl("lon"),
                        lastHeard = c.long("last_heard") ?: 0,
                    )
                )
            }
            out
        }

    // ---------------- Varios ----------------

    fun stats(): Stats {
        var s = Stats()
        readableDatabase.rawQuery("SELECT status, COUNT(*) FROM pings GROUP BY status", null).use { c ->
            while (c.moveToNext()) {
                val n = c.getInt(1)
                s = when (runCatching { PingStatus.valueOf(c.getString(0)) }.getOrNull()) {
                    PingStatus.ACK_RELAY -> s.copy(ackRelay = n)
                    PingStatus.ACK_DEST -> s.copy(ackDest = n)
                    PingStatus.PENDING -> s.copy(pending = n)
                    null -> s
                    else -> s.copy(failed = s.failed + n)
                }
            }
        }
        val sent = s.ackRelay + s.ackDest + s.failed + s.pending
        val rx = readableDatabase.rawQuery("SELECT COUNT(*) FROM rx", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
        return s.copy(sent = sent, rx = rx)
    }

    /** Borra pings y recepciones (los nodos conocidos se conservan). */
    fun clearMeasurements() {
        writableDatabase.execSQL("DELETE FROM pings")
        writableDatabase.execSQL("DELETE FROM rx")
    }
}

// Lectores de columnas que respetan NULL
private fun Cursor.idx(name: String) = getColumnIndexOrThrow(name)
private fun Cursor.int(name: String): Int? = idx(name).let { if (isNull(it)) null else getInt(it) }
private fun Cursor.long(name: String): Long? = idx(name).let { if (isNull(it)) null else getLong(it) }
private fun Cursor.dbl(name: String): Double? = idx(name).let { if (isNull(it)) null else getDouble(it) }
private fun Cursor.flt(name: String): Float? = idx(name).let { if (isNull(it)) null else getFloat(it) }
private fun Cursor.str(name: String): String? = idx(name).let { if (isNull(it)) null else getString(it) }
