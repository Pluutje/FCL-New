package app.aaps.plugins.aps.openAPSFCL.vnext

import android.content.Context
import kotlin.math.abs

/**
 * Small extra dose when the BG stays flat above target.
 *
 * WHY (4/11/2026): on every day of 28/10 to 3/11 the mean BG was about 1.5 mmol/L above target (3/11: 7.9, 96% of
 * the time above 6.5). The BG was flat, so there was no rise to react on, and the insulin on board covered the
 * excess on paper. The loop gave only micro doses and the BG stayed at the wrong level. This is the same effect as
 * a basal rate that is a little too low.
 *
 * The extra dose per cycle is `excess / ISF * 5 / SPAN_MIN`, which means "close the excess in about [SPAN_MIN]
 * minutes", times these weights. All of them are gradual (no step):
 *  - excess weight: 0 at [EXCESS_FROM] mmol/L above target, 1 at [EXCESS_FROM] + [EXCESS_SPAN].
 *  - flat weight: 1 for a flat BG, 0 when the slope is [FLAT_SLOPE_FROM] + [FLAT_SLOPE_SPAN] mmol/L/h or more
 *    (up or down). Rising and falling BG are handled by the normal code.
 *  - IOB weight: 1 below [IOB_FROM] U, 0 above [IOB_FROM] + [IOB_SPAN] U.
 *  - hypo debt weight, hypo projection weight, IOB ratio weight: the dose fades out near any of them.
 * The dose of one cycle is at most [CAP_U]. A replay over 7 days gave 0.7 to 3.2 U per day (largest dose
 * 0.09 U per cycle).
 *
 * The calculation is pure and tested. Cycles in which it acted are stored (like `EarlyBoostCap`) and written to
 * the CSV: `flat_high_u`, `flat_high_excess`, `flat_high_weight`.
 */
object FlatHighCorrection {

    const val SPAN_MIN = 60.0
    const val CAP_U = 0.2
    const val EXCESS_FROM = 0.8
    const val EXCESS_SPAN = 1.2
    const val FLAT_SLOPE_FROM = 0.4
    const val FLAT_SLOPE_SPAN = 0.8
    const val IOB_FROM = 1.5
    const val IOB_SPAN = 1.0
    const val DEBT_FROM = 0.1
    const val DEBT_SPAN = 0.4
    /** The hypo projection (mmol/L): dose is 0 at [PROJ_FROM], full at [PROJ_FROM] + [PROJ_SPAN]. */
    const val PROJ_FROM = 4.5
    const val PROJ_SPAN = 1.0
    /** IOB ratio (IOB / max IOB): full below [RATIO_FROM], 0 at [RATIO_FROM] + [RATIO_SPAN]. */
    const val RATIO_FROM = 0.5
    const val RATIO_SPAN = 0.3
    private const val MIN_ISF = 2.0
    private const val CYCLE_MIN = 5.0

    private const val KEEP_DAYS = 60L
    private const val MAX_EVENTS = 3000
    private const val PREFS = "fcl_flat_high_correction"
    private const val KEY_EVENTS = "events_v1"
    private const val SEP = "|"
    const val CSV_TOLERANCE_MS = 150_000L

    data class Event(
        val timestampMs: Long,
        val doseU: Double,
        /** BG minus target (mmol/L). */
        val excess: Double,
        /** The product of all weights, 0 to 1. */
        val weight: Double
    )

    data class Result(val doseU: Double, val weight: Double)

    private fun smooth01(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    /**
     * [hypoProjectedMin] is the lowest projected BG, or 0 or less when there is no projection.
     * [blocked] is true when another safety part has closed the dose (peak brake, post-hypo brake, lockout).
     */
    fun calculate(
        bg: Double,
        target: Double,
        slope: Double,
        iob: Double,
        isf: Double,
        hypoDebtU: Double,
        hypoProjectedMin: Double,
        iobRatio: Double,
        blocked: Boolean
    ): Result {
        val excess = bg - target
        if (blocked || excess <= 0.0) return Result(0.0, 0.0)
        val excessW = smooth01((excess - EXCESS_FROM) / EXCESS_SPAN)
        val flatW = 1.0 - smooth01((abs(slope) - FLAT_SLOPE_FROM) / FLAT_SLOPE_SPAN)
        val iobW = 1.0 - smooth01((iob - IOB_FROM) / IOB_SPAN)
        val debtW = 1.0 - smooth01((hypoDebtU - DEBT_FROM) / DEBT_SPAN)
        val projW = if (hypoProjectedMin > 0.0) smooth01((hypoProjectedMin - PROJ_FROM) / PROJ_SPAN) else 1.0
        val ratioW = 1.0 - smooth01((iobRatio - RATIO_FROM) / RATIO_SPAN)
        val weight = excessW * flatW * iobW * debtW * projW * ratioW
        val u = (excess / isf.coerceAtLeast(MIN_ISF)) * CYCLE_MIN / SPAN_MIN
        return Result((u * weight).coerceAtMost(CAP_U), weight)
    }

    // ── Events ────────────────────────────────────────────────────────────

    fun loadEvents(context: Context): List<Event> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_EVENTS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val p = line.split(SEP)
            if (p.size != 4) return@mapNotNull null
            try {
                Event(p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble())
            } catch (_: Exception) { null }
        }.sortedBy { it.timestampMs }
    }

    @Synchronized
    fun addEvent(context: Context, event: Event) {
        val first = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        val all = (loadEvents(context) + event).filter { it.timestampMs >= first }.takeLast(MAX_EVENTS)
        val text = all.joinToString("\n") { "${it.timestampMs}$SEP${it.doseU}$SEP${it.excess}$SEP${it.weight}" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_EVENTS, text).apply()
    }

    /** The event closest to [timestampMs] within [toleranceMs], for the CSV export. */
    fun nearest(sorted: List<Event>, timestampMs: Long, toleranceMs: Long): Event? {
        var best: Event? = null
        var bestD = Long.MAX_VALUE
        for (e in sorted) {
            val d = abs(e.timestampMs - timestampMs)
            if (d <= toleranceMs && d < bestD) { best = e; bestD = d }
            if (e.timestampMs - timestampMs > toleranceMs) break
        }
        return best
    }
}
