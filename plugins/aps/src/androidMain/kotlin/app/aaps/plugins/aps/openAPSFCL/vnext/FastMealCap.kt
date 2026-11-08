package app.aaps.plugins.aps.openAPSFCL.vnext

import android.content.Context
import kotlin.math.abs

/**
 * Extra limit per rise for fast meals (experiment, off by default).
 *
 * WHY (6/11/2026): in a replay of one week of a user with fast meals (peak within 60 minutes), 7 of 12 big rises were
 * followed by a low. The user got 3 to 6 U in the first 30 minutes. The insulin per mmol/L of rise was 1.5 U, which is
 * more than the 0.6 U per mmol/L of the slow meals. When the carbs are used up soon after the peak, the insulin that is
 * still on board brings the BG down too far. Insulin that is given during a slow rise has time to work against the rise.
 *
 * This limit uses the maths of [InsulinBudget], with the same personal reference (U per mmol/L of rise) and the same start of
 * the rise. The difference is that its factor goes down with the strength, and that it counts only as much as the rise is
 * fast (weight 0 at [FAST_FROM] mmol/L/h, 1 at [FAST_FROM] + [FAST_SPAN]). A slow rise is not limited by it.
 *  - Factor = [BASE_FACTOR] - [FACTOR_PER_STRENGTH] x strength, but not lower than [MIN_FACTOR].
 *    Strength 1.0 gives 1.1 times the personal reference.
 *  - Room (U) = factor x reference x rise so far + [MARGIN_U] - insulin given since the start of the rise.
 *  - The dose is multiplied by `1 - fastWeight x (1 - smooth01(room / dose))`: nothing changes with room for the
 *    whole dose, and the dose fades out smoothly when the room is used up. There is no hard step.
 *  - It can only lower a dose. All hypo protection stays as it is. Strength 0 = off.
 *
 * The general limit per rise (InsulinBudget) was removed on 8/11/2026; only its maths and the personal reference are left.
 * Cycles in which this experiment acted are stored and written to the CSV: `fast_cap_cut_u`.
 */
object FastMealCap {

    const val BASE_FACTOR = 1.6
    const val FACTOR_PER_STRENGTH = 0.5
    const val MIN_FACTOR = 0.7
    const val MARGIN_U = 0.5
    const val FAST_FROM = 1.5
    const val FAST_SPAN = 2.0

    /** A cut smaller than this (U) is not stored. */
    private const val MIN_CUT_U = 0.05

    private const val KEEP_DAYS = 60L
    private const val MAX_EVENTS = 3000
    private const val PREFS = "fcl_fast_meal_cap"
    private const val KEY_EVENTS = "events_v1"
    private const val SEP = "|"
    const val CSV_TOLERANCE_MS = 150_000L

    data class Event(
        val timestampMs: Long,
        val wantedU: Double,
        val limitedU: Double,
        /** The room that was left before this dose (U). */
        val roomU: Double
    ) {
        val cutU: Double get() = wantedU - limitedU
    }

    data class Result(val limitedU: Double, val roomU: Double)

    private fun smooth01(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    /** How much the extra limit counts (0 to 1), from the recent slope in mmol/L per hour. */
    fun fastWeight(recentSlope: Double): Double = smooth01((recentSlope - FAST_FROM) / FAST_SPAN)

    /** Factor on the personal reference for a [strength] of 0 to 1.5. */
    fun capFactor(strength: Double): Double =
        (BASE_FACTOR - FACTOR_PER_STRENGTH * strength).coerceAtLeast(MIN_FACTOR)

    /** [refUPerMmol] NaN or 0 = no personal reference = no limit. */
    fun calculate(
        strength: Double, refUPerMmol: Double, doseU: Double, riseMmol: Double, givenU: Double, recentSlope: Double
    ): Result {
        if (strength <= 0.0 || doseU <= 0.0 || refUPerMmol.isNaN() || refUPerMmol <= 0.0) return Result(doseU, Double.NaN)
        val weight = fastWeight(recentSlope)
        if (weight <= 0.0) return Result(doseU, Double.NaN)
        val room = InsulinBudget.allowedU(refUPerMmol, capFactor(strength), riseMmol, MARGIN_U) - givenU
        val factor = InsulinBudget.roomFactor(room, doseU)
        return Result(InsulinBudget.limitedDose(doseU, factor, weight), room)
    }

    /** Stores an event when the limit cut the dose. */
    fun record(context: Context, nowMs: Long, wantedU: Double, result: Result) {
        if (wantedU - result.limitedU >= MIN_CUT_U) {
            addEvent(context, Event(nowMs, wantedU, result.limitedU, result.roomU))
        }
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
    private fun addEvent(context: Context, event: Event) {
        val first = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        val all = (loadEvents(context) + event).filter { it.timestampMs >= first }.takeLast(MAX_EVENTS)
        val text = all.joinToString("\n") { "${it.timestampMs}$SEP${it.wantedU}$SEP${it.limitedU}$SEP${it.roomU}" }
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
