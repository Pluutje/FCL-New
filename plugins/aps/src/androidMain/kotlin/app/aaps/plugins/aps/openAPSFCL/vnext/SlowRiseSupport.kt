package app.aaps.plugins.aps.openAPSFCL.vnext

import android.content.Context
import kotlin.math.abs

/**
 * Gentle steady extra dose when the BG rises slowly but keeps rising (experiment, off by default).
 *
 * WHY (6/11/2026): in a replay of one week of a user with slow meals, the BG peaked after 85 minutes or later in
 * 10 of 25 big rises. In those meals only 0 to 1.8 U was given in the first 30 minutes, the BG stayed above 10 for 65
 * to 190 minutes, and then a late stack of 5 to 11 U came. Part of those late doses was followed by a low.
 * A small steady dose during the slow rise can replace a part of that late stack.
 *
 * The dose of one cycle is `excess / ISF * 5 / SPAN_MIN` ("close the excess in about [SPAN_MIN] minutes"), times the
 * strength and these weights. All of them are gradual (no step):
 *  - excess weight: 0 at [EXCESS_FROM] mmol/L above target, 1 at [EXCESS_FROM] + [EXCESS_SPAN].
 *  - slope weight: rises from [SLOPE_FROM] and is full at [SLOPE_FROM] + [SLOPE_SPAN] mmol/L/h. It falls again from
 *    [FAST_FROM] (a fast rise is the job of the normal meal code) and is 0 at [FAST_FROM] + [FAST_SPAN].
 *  - acceleration weight: 1 for a steady rise, 0 for a rise that speeds up quickly.
 *  - given weight: 1 when little insulin was given in the last 90 minutes, 0 when a lot was given. So it only fills gaps.
 *  - IOB weight, hypo debt weight, hypo projection weight, IOB ratio weight: the dose fades out near any of them.
 * The dose of one cycle is at most [CAP_U] times the strength. Strength 0 = off.
 *
 * The calculation is pure and tested. Cycles in which it acted are stored and written to the CSV:
 * `slow_rise_u`, `slow_rise_weight`.
 */
object SlowRiseSupport {

    const val SPAN_MIN = 45.0
    const val CAP_U = 0.25
    const val EXCESS_FROM = 0.5
    const val EXCESS_SPAN = 1.0
    const val SLOPE_FROM = 0.3
    const val SLOPE_SPAN = 0.5
    const val FAST_FROM = 2.0
    const val FAST_SPAN = 1.0
    const val ACCEL_FROM = 0.10
    const val ACCEL_SPAN = 0.20
    const val GIVEN_FROM = 1.0
    const val GIVEN_SPAN = 2.0
    const val IOB_FROM = 2.5
    const val IOB_SPAN = 1.5
    const val DEBT_FROM = 0.1
    const val DEBT_SPAN = 0.4
    const val PROJ_FROM = 4.5
    const val PROJ_SPAN = 1.0
    const val RATIO_FROM = 0.5
    const val RATIO_SPAN = 0.3
    private const val MIN_ISF = 2.0
    private const val CYCLE_MIN = 5.0

    private const val KEEP_DAYS = 60L
    private const val MAX_EVENTS = 3000
    private const val PREFS = "fcl_slow_rise_support"
    private const val KEY_EVENTS = "events_v1"
    private const val SEP = "|"
    const val CSV_TOLERANCE_MS = 150_000L

    data class Event(
        val timestampMs: Long,
        val doseU: Double,
        /** The product of all weights, 0 to 1. */
        val weight: Double
    )

    data class Result(val doseU: Double, val weight: Double)

    private fun smooth01(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    /**
     * [strength] is 0 (off) to 1.5. [slope] is the recent slope in mmol/L per hour. [acceleration] is the same value
     * as the meal code uses. [givenLast90U] is the insulin given in the last 90 minutes.
     * [hypoProjectedMin] is the lowest projected BG, or 0 or less when there is no projection.
     * [blocked] is true when another safety part has closed the dose (peak brake, post-hypo brake, lockout).
     */
    fun calculate(
        strength: Double,
        bg: Double,
        target: Double,
        slope: Double,
        acceleration: Double,
        iob: Double,
        isf: Double,
        givenLast90U: Double,
        hypoDebtU: Double,
        hypoProjectedMin: Double,
        iobRatio: Double,
        blocked: Boolean
    ): Result {
        val excess = bg - target
        if (strength <= 0.0 || blocked || excess <= 0.0) return Result(0.0, 0.0)
        val excessW = smooth01((excess - EXCESS_FROM) / EXCESS_SPAN)
        val slopeW = smooth01((slope - SLOPE_FROM) / SLOPE_SPAN) * (1.0 - smooth01((slope - FAST_FROM) / FAST_SPAN))
        val accelW = 1.0 - smooth01((acceleration - ACCEL_FROM) / ACCEL_SPAN)
        val givenW = 1.0 - smooth01((givenLast90U - GIVEN_FROM) / GIVEN_SPAN)
        val iobW = 1.0 - smooth01((iob - IOB_FROM) / IOB_SPAN)
        val debtW = 1.0 - smooth01((hypoDebtU - DEBT_FROM) / DEBT_SPAN)
        val projW = if (hypoProjectedMin > 0.0) smooth01((hypoProjectedMin - PROJ_FROM) / PROJ_SPAN) else 1.0
        val ratioW = 1.0 - smooth01((iobRatio - RATIO_FROM) / RATIO_SPAN)
        val weight = excessW * slopeW * accelW * givenW * iobW * debtW * projW * ratioW
        val u = (excess / isf.coerceAtLeast(MIN_ISF)) * CYCLE_MIN / SPAN_MIN * strength
        return Result((u * weight).coerceAtMost(CAP_U * strength), weight)
    }

    // ── Events ────────────────────────────────────────────────────────────

    fun loadEvents(context: Context): List<Event> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_EVENTS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val p = line.split(SEP)
            if (p.size != 3) return@mapNotNull null
            try {
                Event(p[0].toLong(), p[1].toDouble(), p[2].toDouble())
            } catch (_: Exception) { null }
        }.sortedBy { it.timestampMs }
    }

    @Synchronized
    fun addEvent(context: Context, event: Event) {
        val first = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        val all = (loadEvents(context) + event).filter { it.timestampMs >= first }.takeLast(MAX_EVENTS)
        val text = all.joinToString("\n") { "${it.timestampMs}$SEP${it.doseU}$SEP${it.weight}" }
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
