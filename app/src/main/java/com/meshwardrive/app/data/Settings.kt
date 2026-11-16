package com.meshwardrive.app.data

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager

class Settings(ctx: Context) {
    private val p = PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext)

    private fun int(key: String, def: Int): Int =
        p.getString(key, null)?.trim()?.toIntOrNull() ?: def

    val channelName: String
        get() = p.getString("channel_name", null)?.trim().orEmpty().ifEmpty { "wardriving" }

    val mode: String get() = p.getString("mode", MODE_CHANNEL) ?: MODE_CHANNEL

    val intervalS: Int get() = int("interval_s", 30).coerceAtLeast(15)
    val minDistanceM: Int get() = int("min_distance_m", 50).coerceAtLeast(0)
    val hopLimit: Int get() = int("hop_limit", 1).coerceIn(1, 7)
    val ackTimeoutS: Int get() = int("ack_timeout_s", 90).coerceIn(10, 600)
    val maxAccuracyM: Int get() = int("max_accuracy_m", 50).coerceAtLeast(5)
    val includeCoords: Boolean get() = p.getBoolean("include_coords", true)
    val cellSizeM: Int get() = int("cell_size_m", 150).coerceIn(25, 5000)

    var targetNode: Int
        get() = p.getLong("target_node", 0L).toInt()
        set(v) = p.edit { putLong("target_node", v.toLong() and 0xFFFFFFFFL) }

    var lastAddress: String?
        get() = p.getString("last_address", null)
        set(v) = p.edit { putString("last_address", v) }

    var lastDeviceName: String?
        get() = p.getString("last_device_name", null)
        set(v) = p.edit { putString("last_device_name", v) }

    var disclaimerAccepted: Boolean
        get() = p.getBoolean("disclaimer_accepted_v1", false)
        set(v) = p.edit { putBoolean("disclaimer_accepted_v1", v) }

    var useOfflineMap: Boolean
        get() = p.getBoolean("use_offline_map", false)
        set(v) = p.edit { putBoolean("use_offline_map", v) }

    /** Wardrives que se muestran; null = todos. */
    var sessionFilter: Set<String>?
        get() = p.getStringSet("session_filter", null)?.toSet()
        set(v) = p.edit { if (v == null) remove("session_filter") else putStringSet("session_filter", v) }

    var showHeat: Boolean
        get() = p.getBoolean("layer_heat", true)
        set(v) = p.edit { putBoolean("layer_heat", v) }

    var showCells: Boolean
        get() = p.getBoolean("layer_cells", false)
        set(v) = p.edit { putBoolean("layer_cells", v) }

    var showPings: Boolean
        get() = p.getBoolean("layer_pings", true)
        set(v) = p.edit { putBoolean("layer_pings", v) }

    var showPassive: Boolean
        get() = p.getBoolean("layer_passive", false)
        set(v) = p.edit { putBoolean("layer_passive", v) }

    var showNodes: Boolean
        get() = p.getBoolean("layer_nodes", true)
        set(v) = p.edit { putBoolean("layer_nodes", v) }

    fun setMode(mode: String) = p.edit { putString("mode", mode) }
}
