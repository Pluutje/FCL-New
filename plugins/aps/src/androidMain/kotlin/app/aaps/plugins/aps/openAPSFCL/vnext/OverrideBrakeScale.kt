package app.aaps.plugins.aps.openAPSFCL.vnext

import android.content.Context
import kotlin.math.abs

/**
 * How much a positive override relaxes the post-hypo brake, as a smooth scale.
 *
 * WHY (5/11/2026): after a low BG (3.6 at 17:21) a positive override ("Moeilijke maaltijd", 150%) switched the
 * post-hypo brake off completely. The meal doses of 18:01 and 18:06 (3.2 U and 4.0 U, with a 3.0 U portion) came
 * with no brake at all. Before, the override was an on/off switch. Now it is a weight.
 *
 *  - Severity (0 to 1) = depth weight x recency weight.
 *    Depth: the lowest BG of the last hour. [DEPTH_FULL_MMOL] or lower gives 1, [DEPTH_NONE_MMOL] or higher gives 0.
 *    Recency: minutes since that lowest BG. Up to [RECENT_FULL_MIN] gives 1, [RECENT_NONE_MIN] or more gives 0.
 *  - With a positive override the brake strength is the severity. Without an override it is always 1.
 *  - The brake result is blended: `dose = dose_no_brake - strength x (dose_no_brake - dose_with_brake)`.
 *  - The override portion (extra insulin of the override) is scaled with `1 - strength`. The part that is not given
 *    stays open and is asked again in the next cycle, when the low is older and the strength is smaller.
 *
 * With no recent low the severity is 0, so the old behaviour (override = no brake) stays exactly the same.
 * The calculation is pure and tested. Cycles in which it acted are stored and written to the CSV:
 * `ovr_brake_severity`, `ovr_brake_portion_cut_u`.
 */
object OverrideBrakeScale {

    const val DEPTH_FULL_MMOL = 3.6
    const val DEPTH_NONE_MMOL = 4.4
    const val RECENT_FULL_MIN = 30.0
    const val RECENT_NONE_MIN = 60.0

    private const val KEEP_DAYS = 60L
    private const val MAX_EVENTS = 3000
    private const val PREFS = "fcl_override_brake_scale"
    private const val KEY_EVENTS = "events_v1"
    private const val SEP = "|"
    const val CSV_TOLERANCE_MS = 150_000L

    data class Event(
        val timestampMs: Long,
        /** 0 to 1: how deep and how recent the low was. */
        val severity: Double,
        /** Override portion (U) that was held back in this cycle. */
        val portionCutU: Double
    )

    private fun smooth01(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    /**
     * 8/11/2026 (de gebruiker): a positive override means "I eat". The BG that comes back up and is above the low range
     * and still rising is the proof that the carbs work. Then the brake must not stay on only because the low was
     * less than 30 minutes ago (14:07-14:42: BG 3.3 to 8.3, override portion of 1.5 U held back until 14:32).
     *
     * The severity is multiplied by (1 - recovery). Recovery is the product of two smooth parts:
     *  - level: 0 at [RECOVER_BG_START_MMOL] and lower, 1 at [RECOVER_BG_START_MMOL] + [RECOVER_BG_SPAN_MMOL] and higher
     *  - rising: 0 at a 5 min delta of 0 or lower, 1 from [RECOVER_DELTA_FULL] per 5 min
     * While the BG is still low or falling the brake stays as before (this is the hypo safety).
     */
    const val RECOVER_BG_START_MMOL = 4.4
    const val RECOVER_BG_SPAN_MMOL = 1.0
    const val RECOVER_DELTA_FULL = 0.2

    /** 0 to 1: how far the BG has come back from the low, see above. */
    fun recovery(bgNow: Double, recentDelta5m: Double): Double =
        smooth01((bgNow - RECOVER_BG_START_MMOL) / RECOVER_BG_SPAN_MMOL) *
            smooth01(recentDelta5m / RECOVER_DELTA_FULL)

    /**
     * 0 to 1. [lowestBgLastHour] in mmol/L, [minutesSinceLowest] in minutes.
     * [bgNow] (mmol/L) and [recentDelta5m] are used for the recovery. If they are not given there is no recovery.
     */
    fun severity(
        lowestBgLastHour: Double,
        minutesSinceLowest: Double,
        bgNow: Double = lowestBgLastHour,
        recentDelta5m: Double = 0.0
    ): Double {
        val depth = smooth01((DEPTH_NONE_MMOL - lowestBgLastHour) / (DEPTH_NONE_MMOL - DEPTH_FULL_MMOL))
        val recent = 1.0 - smooth01((minutesSinceLowest - RECENT_FULL_MIN) / (RECENT_NONE_MIN - RECENT_FULL_MIN))
        return depth * recent * (1.0 - recovery(bgNow, recentDelta5m))
    }

    /** Brake strength: 1 without a positive override, the severity with one. */
    fun brakeStrength(positiveOverride: Boolean, severity: Double): Double =
        if (positiveOverride) severity.coerceIn(0.0, 1.0) else 1.0

    /** Dose after blending the brake result into the dose without the brake. */
    fun blend(doseNoBrake: Double, doseWithBrake: Double, strength: Double): Double =
        doseNoBrake - strength.coerceIn(0.0, 1.0) * (doseNoBrake - doseWithBrake)

    /** Part of a positive override portion that may be given now. */
    fun portionScale(positiveOverride: Boolean, severity: Double): Double =
        if (positiveOverride) 1.0 - severity.coerceIn(0.0, 1.0) else 1.0

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
        val text = all.joinToString("\n") { "${it.timestampMs}$SEP${it.severity}$SEP${it.portionCutU}" }
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
