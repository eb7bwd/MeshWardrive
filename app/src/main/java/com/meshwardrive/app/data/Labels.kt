package com.meshwardrive.app.data

import com.meshwardrive.app.R

/** Texto traducible de cada estado de ping (el nombre del enum se usa en las exportaciones). */
val PingStatus.labelRes: Int
    get() = when (this) {
        PingStatus.PENDING -> R.string.ps_pending
        PingStatus.ACK_RELAY -> R.string.ps_relay
        PingStatus.ACK_DEST -> R.string.ps_dest
        PingStatus.NAK -> R.string.ps_nak
        PingStatus.TIMEOUT -> R.string.no_response
        PingStatus.SEND_ERROR -> R.string.ps_send_error
        PingStatus.NOT_SENT -> R.string.ps_not_sent
    }

val Difficulty.labelRes: Int
    get() = when (this) {
        Difficulty.EASY -> R.string.diff_easy
        Difficulty.NORMAL -> R.string.diff_normal
        Difficulty.HARD -> R.string.diff_hard
    }

val Reliability.labelRes: Int
    get() = when (this) {
        Reliability.HIGH -> R.string.rel_high
        Reliability.MEDIUM -> R.string.rel_medium
        Reliability.LOW -> R.string.rel_low
    }

fun placementLabelRes(p: String): Int = when (p) {
    Conditions.PLACE_POCKET -> R.string.place_pocket
    Conditions.PLACE_BAG -> R.string.place_bag
    Conditions.PLACE_HAND -> R.string.place_hand
    Conditions.PLACE_CAR_INSIDE -> R.string.place_car_inside
    Conditions.PLACE_DASHBOARD -> R.string.place_dashboard
    Conditions.PLACE_ROOF -> R.string.place_roof
    else -> R.string.place_mast
}

fun transportLabelRes(t: String): Int = when (t) {
    Conditions.TRANSPORT_BIKE -> R.string.transport_bike
    Conditions.TRANSPORT_CAR -> R.string.transport_car
    else -> R.string.transport_walk
}
