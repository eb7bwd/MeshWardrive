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
    }
