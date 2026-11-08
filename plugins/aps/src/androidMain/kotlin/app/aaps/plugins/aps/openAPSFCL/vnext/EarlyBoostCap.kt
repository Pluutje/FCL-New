package app.aaps.plugins.aps.openAPSFCL.vnext

import android.content.Context
import kotlin.math.abs

/**
 * Limits the early boost dose (see `computeEarlyDoseDecision`) to what the predicted peak asks for.
 *
 * WHY (2/11/2026): at 19:51 the boost asked for 5.47 U (cut to 3.50 U) and 5 minutes later for 1.42 U more,
 * for a rise that stopped at 8.0 mmol/L. The boost follows the speed of the rise (`pred60` was 12.2), but the
 * peak predictor said 8.5 and that was right. Two parts, both gradual (no step):
 *
 *  1. Peak cap. The boost dose may not be more than [CAP_FACTOR] times what the predicted peak needs:
 *     `CAP_FACTOR * (predicted peak - target) / ISF`. The cap fades out between a predicted peak of
 *     [PEAK_FROM] and [PEAK_TO] mmol/L, so a real big meal is not limited.
 *  2. Cover fade. When the insulin that is already on board will lower the BG by more than the predicted
 *     peak is above target (`futureDrop60 > peak - target`), the boost factor fades to 1 over
 *     [COVER_FADE_MMOL] mmol/L. A later boost dose in the same meal then gets smaller by itself.
 *
 * The calculation is pure and tested. Cycles in which it acted are stored (like `FastMealCap`) and
 * written to the CSV: `early_cap_u`, `early_uncapped_u`, `early_cover_weight`.
 */
object EarlyBoostCap {

    /** How many times the dose that the predicted peak needs is allowed. Ruim, because carbs still come after the peak. */
    const val CAP_FACTOR = 3.0

    /** Predicted peak (mmol/L) where the cap is fully on, and where it is fully off. */
    const val PEAK_FROM = 8.5
    const val PEAK_TO = 10.5

    /** The boost fades to 1 when the cover is this much (mmol/L) more than the peak needs. */
    const val COVER_FADE_MMOL = 2.0

    /** An ISF lower than this is not trusted (the cap would explode). */
    private const val MIN_ISF = 0.5

    private const val KEEP_DAYS = 60L
    private const val MAX_EVENTS = 3000
    private const val PREFS = "fcl_early_boost_cap"
    private const val KEY_EVENTS = "events_v1"
    private const val SEP = "|"

    const val CSV_TOLERANCE_MS = 150_000L

    /** One cycle in which the cap or the cover fade changed the boost target. */
    data class Event(
        val timestampMs: Long,
        /** The boost target before the cap and the fade (U). */
        val uncappedU: Double,
        /** The cap (U), also when the target was already lower. */
        val capU: Double,
        /** 0 to 1: how much the insulin on board covers the peak. */
        val coverWeight: Double
    )

    // ── Pure calculation (tested) ─────────────────────────────────────────

    private fun smooth01(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    /** 1 below a predicted peak of [PEAK_FROM], 0 above [PEAK_TO], smooth in between. */
    fun capWeight(predictedPeak: Double): Double =
        1.0 - smooth01((predictedPeak - PEAK_FROM) / (PEAK_TO - PEAK_FROM))

    /** The dose (U) that [CAP_FACTOR] times the need of the predicted peak gives. */
    fun capU(predictedPeak: Double, target: Double, isf: Double): Double =
        CAP_FACTOR * (predictedPeak - target).coerceAtLeast(0.0) / isf.coerceAtLeast(MIN_ISF)

    /**
     * 0 when the insulin on board does not cover the predicted peak, 1 when it covers it by [COVER_FADE_MMOL]
     * or more. [futureDrop60] is the drop (mmol/L) that the insulin on board still gives in 60 minutes.
     */
    fun coverWeight(predictedPeak: Double, futureDrop60: Double, target: Double): Double =
        smooth01((futureDrop60 - (predictedPeak - target)) / COVER_FADE_MMOL)

    /** Boost factor after the cover fade: [factor] at weight 0, 1.0 at weight 1. */
    fun fadedBoostFactor(factor: Double, coverWeight: Double): Double =
        1.0 + (factor - 1.0) * (1.0 - coverWeight)

    /**
     * The boost target after the cap. At [capWeight] 0 it is [targetU], at 1 it is the lower of [targetU] and
     * [capU]. [floorU] is the smallest early dose, it is never cut below that (or below [targetU] itself).
     */
    fun cappedTarget(targetU: Double, capU: Double, capWeight: Double, floorU: Double): Double {
        val lowered = targetU + (minOf(targetU, capU) - targetU) * capWeight
        return maxOf(lowered, minOf(floorU, targetU))
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
        val text = all.joinToString("\n") { "${it.timestampMs}$SEP${it.uncappedU}$SEP${it.capU}$SEP${it.coverWeight}" }
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
