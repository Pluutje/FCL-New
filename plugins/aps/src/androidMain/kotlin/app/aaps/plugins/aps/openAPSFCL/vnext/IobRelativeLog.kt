package app.aaps.plugins.aps.openAPSFCL.vnext

import android.content.Context
import kotlin.math.abs

/**
 * Log only (15/11/2026): what the IOB brake would do if it used the relative IOB cover instead of IOB / max IOB.
 *
 * WHY: the IOB brakes work on `iobRatio` = IOB / max IOB. A user with a low max IOB is braked much earlier than a user
 * with a high one, also when both have the same IOB for the same BG. The relative cover (see [CoverDecay]) is the IOB
 * per mmol/L that still needs insulin, divided by the personal reference. This log only measures. It does NOT change
 * any dose.
 *
 * Per cycle three numbers are stored:
 *  - [Event.ratioRel]: the relative cover times [CALIBRATION]. It is on the same scale as `iobRatio`.
 *  - [Event.factorRel]: the IOB damping factor (the `iob_factor` column) worked out from [Event.ratioRel].
 *  - [Event.stepBrake]: the step decay brake. The layer IOB_STEP_BRAKE in the dose cut trace is the IOB factor times
 *    the strength setting times this brake, so this brake must be known to read that layer.
 *
 * [CALIBRATION] is a first choice: in the logs of Ecko the two measures lie close together above BG 10
 * (0.46 of max IOB against a cover of 0.51), and the brakes work well for him. Check it against more logs before it is
 * used for a dose. Without a personal reference nothing is stored.
 *
 * Cycles with almost no IOB are not stored (the damping factor is 1.0 there for both measures).
 */
object IobRelativeLog {

    /** Relative cover to `iobRatio` scale. See the kdoc of this object. */
    const val CALIBRATION = 0.9

    /** Below this value for both measures the cycle is not stored. */
    const val MIN_RATIO_TO_STORE = 0.10

    const val CSV_TOLERANCE_MS = 150_000L
    private const val KEEP_DAYS = 30L
    private const val MAX_EVENTS = 4000
    private const val PREFS = "fcl_iob_relative_log"
    private const val KEY_EVENTS = "events_v1"
    private const val SEP = "|"

    /**
     * [relaxWeight] is the weight of the experiment [IobRelax] in this cycle (0 = off). [factorOriginal] is the IOB factor
     * before the experiment changed it. Old events (4 parts) read as weight 0 and original NaN.
     */
    data class Event(
        val timestampMs: Long,
        val ratioRel: Double,
        val factorRel: Double,
        val stepBrake: Double,
        val relaxWeight: Double = 0.0,
        val factorOriginal: Double = Double.NaN,
        val stepBrakeOriginal: Double = Double.NaN
    )

    /** The relative cover on `iobRatio` scale. NaN without a personal reference. */
    fun equivalentRatio(
        iob: Double,
        mmolAboveTarget: Double,
        slope: Double,
        recentSlope: Double,
        refUPerMmol: Double
    ): Double {
        if (refUPerMmol.isNaN() || refUPerMmol <= 0.0) return Double.NaN
        return CoverDecay.iobPerNeed(iob, mmolAboveTarget, slope, recentSlope) / refUPerMmol * CALIBRATION
    }

    /** True when this cycle is worth storing. */
    fun worthStoring(iobRatio: Double, ratioRel: Double): Boolean =
        !ratioRel.isNaN() && (iobRatio >= MIN_RATIO_TO_STORE || ratioRel >= MIN_RATIO_TO_STORE)

    fun loadEvents(context: Context): List<Event> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_EVENTS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val p = line.split(SEP)
            if (p.size != 4 && p.size != 6 && p.size != 7) return@mapNotNull null
            try {
                val base = Event(p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble())
                when (p.size) {
                    4 -> base
                    6 -> base.copy(relaxWeight = p[4].toDouble(), factorOriginal = p[5].toDouble())
                    else -> base.copy(relaxWeight = p[4].toDouble(), factorOriginal = p[5].toDouble(), stepBrakeOriginal = p[6].toDouble())
                }
            } catch (_: Exception) { null }
        }.sortedBy { it.timestampMs }
    }

    @Synchronized
    fun addEvent(context: Context, event: Event) {
        val first = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        val all = (loadEvents(context) + event).filter { it.timestampMs >= first }.takeLast(MAX_EVENTS)
        val text = all.joinToString("\n") { "${it.timestampMs}$SEP${it.ratioRel}$SEP${it.factorRel}$SEP${it.stepBrake}$SEP${it.relaxWeight}$SEP${it.factorOriginal}$SEP${it.stepBrakeOriginal}" }
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
