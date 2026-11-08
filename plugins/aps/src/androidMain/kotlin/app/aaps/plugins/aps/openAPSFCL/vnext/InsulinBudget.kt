package app.aaps.plugins.aps.openAPSFCL.vnext

import android.content.Context
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.InsulinTimingHistory
import app.aaps.plugins.aps.openAPSFCL.vnext.database.FCLAnalyzerDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Personal insulin per rise, and the maths for a limit per rise.
 *
 * The limit "per rise" itself (own card, levels) was removed on 8/11/2026: it did not show a benefit. What is left is
 * used by [FastMealCap] (the experiment "Snelle stijging"):
 *
 *  - Room (U) = capFactor x reference (U per mmol/L) x rise so far (mmol/L) + margin (U) - insulin given since the start of the rise.
 *  - The dose is multiplied by `smooth01(room / dose)`: nothing changes when there is room for the whole dose, and the dose
 *    fades out smoothly to 0 when the room is used up. There is no hard step.
 *  - The personal reference is the median insulin per mmol/L of rise over the meals of the last [REF_DAYS] days.
 *    It is refreshed once per hour ([onCycle]). Without enough meals ([MIN_EPISODES]) there is no reference and no limit.
 */
object InsulinBudget {

    /** Days of meals used for the personal reference. */
    const val REF_DAYS = 28

    /** Fewer meals than this: no reference, so no limit. */
    const val MIN_EPISODES = 15

    /** Meals with less rise than this (mmol/L) are not used for the reference. */
    private const val MIN_RISE_MMOL = 2.0

    /** The rise starts at the lowest BG of this many minutes back. */
    const val LOOK_BACK_MIN = 90

    /** The limit starts to act from this recent slope (mmol/L per hour) and is full from [RISE_SLOPE_FULL]. */
    const val RISE_SLOPE_START = 0.5
    const val RISE_SLOPE_FULL = 1.5

    const val PREFS_NAME = "fcl_insulin_budget"
    private const val PREFS = PREFS_NAME
    private const val KEY_REF = "ref_u_per_mmol"
    private const val KEY_REF_N = "ref_episodes"
    private const val KEY_REF_MS = "ref_updated_ms"

    // ── Pure calculation (tested) ─────────────────────────────────────────

    private fun smooth01(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    /** Insulin (U) allowed since the start of a rise of [riseMmol]. */
    fun allowedU(refUPerMmol: Double, capFactor: Double, riseMmol: Double, marginU: Double): Double =
        capFactor * refUPerMmol * riseMmol.coerceAtLeast(0.0) + marginU

    /** Factor (0 to 1) on a dose of [doseU] when [roomU] is left. 1 with room for the whole dose, 0 without room. */
    fun roomFactor(roomU: Double, doseU: Double): Double =
        if (doseU <= 0.0) 1.0 else smooth01(roomU / doseU)

    /** How much the limit counts (0 to 1), from the recent slope. 0 when the BG is flat or falling. */
    fun risingWeight(recentSlope: Double): Double =
        smooth01((recentSlope - RISE_SLOPE_START) / (RISE_SLOPE_FULL - RISE_SLOPE_START))

    /** The dose after the limit: the factor counts as much as the rising weight. */
    fun limitedDose(doseU: Double, factor: Double, weight: Double): Double =
        doseU * (1.0 - weight * (1.0 - factor))

    /**
     * The start of the rise: the last lowest BG in the last [LOOK_BACK_MIN] minutes (including now).
     * [history] is a list of (time in ms, BG). Returns the BG and the time, or null without data.
     */
    fun riseBase(history: List<Pair<Long, Double>>, nowMs: Long, bgNow: Double): Pair<Double, Long>? {
        val from = nowMs - LOOK_BACK_MIN * 60_000L
        var bestBg = bgNow
        var bestMs = nowMs
        for ((t, bg) in history) {
            if (t < from || t > nowMs) continue
            if (bg <= bestBg) { bestBg = bg; bestMs = t }
        }
        return bestBg to bestMs
    }

    // ── Personal reference ────────────────────────────────────────────────

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Personal insulin per mmol/L of rise (U), NaN without a reference. */
    fun reference(context: Context): Double {
        val v = prefs(context).getFloat(KEY_REF, Float.NaN)
        return if (v.isNaN() || v <= 0f) Double.NaN else v.toDouble()
    }

    fun referenceEpisodes(context: Context): Int = prefs(context).getInt(KEY_REF_N, 0)

    fun referenceUpdatedMs(context: Context): Long = prefs(context).getLong(KEY_REF_MS, 0L)

    /** Median insulin per mmol/L of rise of the meals, NaN with fewer than [MIN_EPISODES] meals. */
    fun referenceFromEpisodes(episodes: List<InsulinTimingHistory.Episode>): Double {
        val ratios = episodes.filter { it.rise >= MIN_RISE_MMOL && it.insulinU > 0.0 }.map { it.insulinU / it.rise }
        if (ratios.size < MIN_EPISODES) return Double.NaN
        return InsulinTimingHistory.median(ratios)
    }

    /** Reads the last [REF_DAYS] days, finds the meals and stores the personal reference. */
    suspend fun refreshReference(context: Context) {
        val now = System.currentTimeMillis()
        val from = now - (REF_DAYS + 1) * 86_400_000L
        val rows = FCLAnalyzerDatabase.getInstance(context).cycleLogDao().getTimingRows(from, now + 60_000L)
        val episodes = InsulinTimingHistory.analyze(rows).episodes.filter { it.peakMs >= now - REF_DAYS * 86_400_000L }
        val ref = referenceFromEpisodes(episodes)
        prefs(context).edit()
            .putFloat(KEY_REF, if (ref.isNaN()) Float.NaN else ref.toFloat())
            .putInt(KEY_REF_N, episodes.count { it.rise >= MIN_RISE_MMOL && it.insulinU > 0.0 })
            .putLong(KEY_REF_MS, now)
            .apply()
    }

    // ── Once per hour, on a background thread ─────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var lastRunHourKey = -1L
    @Volatile private var running = false

    /** Refreshes the personal reference at most once per hour. Never blocks the dosing cycle. */
    fun onCycle(context: Context) {
        val hourKey = System.currentTimeMillis() / 3_600_000L
        if (hourKey == lastRunHourKey || running) return
        lastRunHourKey = hourKey
        running = true
        scope.launch {
            try {
                refreshReference(context)
            } catch (_: Exception) {
                // try again next hour
            } finally {
                running = false
            }
        }
    }
}
