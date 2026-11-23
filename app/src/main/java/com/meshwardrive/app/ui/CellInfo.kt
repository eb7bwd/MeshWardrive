package com.meshwardrive.app.ui

import android.content.Context
import com.meshwardrive.app.R
import com.meshwardrive.app.data.CoverageGrid
import com.meshwardrive.app.data.Prediction
import com.meshwardrive.app.data.Reliability
import com.meshwardrive.app.data.WardriveState
import com.meshwardrive.app.data.labelRes
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Ficha de una celda: lo medido, lo estimado, si los datos son recientes y quién te oyó allí. */
object CellInfo {

    const val RECENT_DAYS = 30
    const val OLD_DAYS = 180

    private fun snr(v: Float) = String.format(Locale.US, "%.1f", v)

    /**
     * @param c celda medida, o null si no hay mediciones.
     * @param est estimación, si la celda no está medida.
     * @param heard nodos oídos directamente en la celda (recepción pasiva).
     * @param groundM elevación del terreno según el relieve descargado.
     */
    fun describe(
        ctx: Context,
        c: CoverageGrid.Cell?,
        est: Prediction.Est?,
        heard: List<String>?,
        groundM: Double? = null,
    ): String = buildString {
        fun line(s: String) {
            if (isNotEmpty()) append('\n')
            append(s)
        }
        if (c != null && c.measured) {
            line(
                ctx.getString(
                    when (c.state) {
                        CoverageGrid.State.COVERED -> R.string.ci_state_covered
                        CoverageGrid.State.NO_COVERAGE -> R.string.ci_state_none
                        CoverageGrid.State.UNCONFIRMED -> R.string.ci_state_unconfirmed
                        CoverageGrid.State.PENDING -> R.string.ci_state_pending
                    }
                )
            )
            line(ctx.getString(R.string.cell_snippet, c.count, c.ok, c.fail, c.successPct ?: 0))
            if (c.weakFail > 0) line(ctx.getString(R.string.ci_weak_fails, c.weakFail))
            if (c.notSent > 0) line(ctx.getString(R.string.ci_not_sent, c.notSent))
            c.bestSnr?.let { b ->
                val w = c.worstSnr
                line(
                    if (w != null && w != b) ctx.getString(R.string.ci_snr_range, snr(b), snr(w))
                    else ctx.getString(R.string.best_snr, snr(b))
                )
            }
            when (c.trend) {
                CoverageGrid.Trend.NEW -> line("▲ " + ctx.getString(R.string.trend_new_long))
                CoverageGrid.Trend.LOST -> line("▼ " + ctx.getString(R.string.trend_lost_long))
                else -> Unit
            }
            if (c.firstTs != Long.MAX_VALUE && c.lastTs > 0) {
                val df = DateFormat.getDateInstance(DateFormat.SHORT)
                line(ctx.getString(R.string.ci_dates, df.format(Date(c.firstTs)), df.format(Date(c.lastTs))))
                val days = ((System.currentTimeMillis() - c.lastTs) / 86_400_000L).toInt()
                line(
                    when {
                        days <= RECENT_DAYS -> ctx.getString(R.string.ci_recent, days)
                        days >= OLD_DAYS -> ctx.getString(R.string.ci_old, days)
                        else -> ctx.getString(R.string.ci_age, days)
                    }
                )
            }
            val rel = c.reliabilities
            if (rel.isNotEmpty()) {
                line(
                    ctx.getString(R.string.ci_reliability) + " " + Reliability.values()
                        .filter { (rel[it] ?: 0) > 0 }
                        .joinToString(" · ") { ctx.getString(it.labelRes) + " " + rel[it] }
                )
            }
            val who = c.relays.map { WardriveState.relayLabel(it) } + c.ackFrom.map { WardriveState.nodeLabel(it) }
            if (who.isNotEmpty()) line(ctx.getString(R.string.ci_heard_you, who.distinct().take(8).joinToString(", ")))
            c.avgAlt?.let { line(ctx.getString(R.string.ci_alt, it.toInt())) }
        } else if (est != null) {
            line(
                ctx.getString(
                    when (est.level) {
                        Prediction.Level.LIKELY -> R.string.ci_est_likely
                        Prediction.Level.UNCERTAIN -> R.string.ci_est_uncertain
                        Prediction.Level.UNLIKELY -> R.string.ci_est_unlikely
                    }
                )
            )
            line(ctx.getString(R.string.ci_est_detail, (est.score * 100).toInt(), (est.confidence * 100).toInt()))
            line(ctx.getString(R.string.ci_est_basis, est.neighbours, est.nearestM.toInt()))
            line(
                ctx.getString(
                    when (est.terrain) {
                        Prediction.Terrain.CLEAR -> R.string.ci_terrain_clear
                        Prediction.Terrain.BLOCKED -> R.string.ci_terrain_blocked
                        Prediction.Terrain.MIXED -> R.string.ci_terrain_mixed
                        Prediction.Terrain.UNKNOWN -> R.string.ci_terrain_unknown
                    }
                )
            )
        } else {
            line(ctx.getString(R.string.ci_no_data))
        }
        if (!heard.isNullOrEmpty()) line(ctx.getString(R.string.ci_you_heard, heard.take(8).joinToString(", ")))
        groundM?.let { line(ctx.getString(R.string.ci_ground, it.toInt())) }
    }
}
