package app.aaps.plugins.aps.openAPSFCL.vnext

import android.content.Context

/**
 * How much of a dose passes the down-trend gate and the two stops SHORT-TERM DIP and LONG-SLOPE BLOCK, 0 to 1.
 *
 * Before, these set the dose to exactly 0 when the BG fell, also when the BG was still clearly above target and the
 * fall was mild. On 9/11/2026 the BG never went below 6.0 and hung at 7 to 9 for hours.
 *
 * The pass weight is the product of three gradual parts, times a maximum that depends on the strength of the
 * experiment (0 to 150 percent, 0 = off = the old hard stop):
 *  - height: 0 at [DELTA_FROM] mmol/L above target or lower, 1 at [DELTA_FROM] + [DELTA_SPAN].
 *  - slope: 1 for a mild fall, 0 when the fast slope is [SLOPE_FROM] + [SLOPE_SPAN] mmol/L/h down or more.
 *  - low IOB: 1 below an IOB ratio of [IOB_FROM], 0 at [IOB_FROM] + [IOB_SPAN].
 * The maximum is [BASE_MAX] at a strength of [STRENGTH_FOR_BASE], grows in a straight line with the strength and is
 * never more than [ABS_MAX]. Pure functions, easy to test.
 */
object DowntrendPass {

    /** The part of the dose that passes at best, at a strength of [STRENGTH_FOR_BASE]. */
    const val BASE_MAX = 0.7

    /** The strength (factor, 0.75 = 75 percent) at which the maximum is [BASE_MAX]. */
    const val STRENGTH_FOR_BASE = 0.75

    /** The gate never opens completely. */
    const val ABS_MAX = 0.95

    const val DELTA_FROM = 1.5
    const val DELTA_SPAN = 2.0
    const val SLOPE_FROM = 0.6
    const val SLOPE_SPAN = 1.4
    const val IOB_FROM = 0.20
    const val IOB_SPAN = 0.25

    /** The most that can pass at this strength factor. 0 at strength 0. */
    fun maxPass(strength: Double): Double =
        (BASE_MAX * strength.coerceAtLeast(0.0) / STRENGTH_FOR_BASE).coerceIn(0.0, ABS_MAX)

    /**
     * @param deltaToTarget BG minus target (mmol/L)
     * @param recentSlope the fast slope in mmol/L/h (negative = falling)
     * @param iobRatio IOB divided by max IOB
     * @param strength strength factor of the experiment, 0.0 to 1.5
     */
    fun weight(deltaToTarget: Double, recentSlope: Double, iobRatio: Double, strength: Double): Double {
        val maxW = maxPass(strength)
        if (maxW <= 0.0) return 0.0
        val heightW = smooth01((deltaToTarget - DELTA_FROM) / DELTA_SPAN)
        val slopeW = 1.0 - smooth01((-recentSlope - SLOPE_FROM) / SLOPE_SPAN)
        val iobW = 1.0 - smooth01((iobRatio - IOB_FROM) / IOB_SPAN)
        return maxW * heightW * slopeW * iobW
    }

    private fun smooth01(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}

/** Cycles in which the pass let a dose through, for the experiment card. Same pattern as `FlatHighCorrection`. */
object DowntrendPassLog {

    private const val KEEP_DAYS = 60L
    private const val MAX_EVENTS = 3000
    private const val PREFS = "fcl_downtrend_pass"
    private const val KEY_EVENTS = "events_v1"
    private const val SEP = "|"

    /** [doseU] is the dose that was let through in that cycle. */
    data class Event(val timestampMs: Long, val doseU: Double)

    fun loadEvents(context: Context): List<Event> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_EVENTS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val p = line.split(SEP)
            if (p.size != 2) return@mapNotNull null
            try {
                Event(p[0].toLong(), p[1].toDouble())
            } catch (_: Exception) { null }
        }.sortedBy { it.timestampMs }
    }

    @Synchronized
    fun addEvent(context: Context, event: Event) {
        val first = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        val all = (loadEvents(context) + event).filter { it.timestampMs >= first }.takeLast(MAX_EVENTS)
        val text = all.joinToString("\n") { "${it.timestampMs}$SEP${it.doseU}" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_EVENTS, text).apply()
    }
}
