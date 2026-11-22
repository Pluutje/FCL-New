package app.aaps.plugins.aps.openAPSFCL.vnext

import android.content.Context
import kotlin.math.abs

/**
 * Log only (15/11/2026): what the experiments "Extra early dose" (T1_BOOST) and "Earlier alert" (EARLY_ALERT) did in a cycle.
 *
 * Before this, nothing in the CSV showed their share, so their effect could not be judged.
 * - [Event.t1BonusU]: the extra insulin (U) in the early dose that comes from the T1 bonus.
 * - [Event.alertHit]: 1 when the meal was only watched because of the lower peak threshold of the experiment
 *   (the predicted peak is between the threshold with the experiment and the normal threshold).
 *
 * This does NOT change any dose. Cycles with nothing to say are not stored.
 */
object EarlyExperimentLog {

    const val CSV_TOLERANCE_MS = 150_000L
    private const val KEEP_DAYS = 60L
    private const val MAX_EVENTS = 3000
    private const val PREFS = "fcl_early_experiment_log"
    private const val KEY_EVENTS = "events_v1"
    private const val SEP = "|"

    data class Event(val timestampMs: Long, val t1BonusU: Double, val alertHit: Int)

    /** True when the predicted peak starts the watching only because of the lower threshold of the experiment. */
    fun alertHit(predictedPeak: Double, strength: Double): Boolean =
        predictedPeak >= EarlyExperimentWeights.peakStartThreshold(strength) &&
            predictedPeak < EarlyExperimentWeights.PEAK_START_DEFAULT

    fun loadEvents(context: Context): List<Event> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_EVENTS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val p = line.split(SEP)
            if (p.size != 3) return@mapNotNull null
            try {
                Event(p[0].toLong(), p[1].toDouble(), p[2].toInt())
            } catch (_: Exception) { null }
        }.sortedBy { it.timestampMs }
    }

    @Synchronized
    fun addEvent(context: Context, event: Event) {
        val first = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        val all = (loadEvents(context) + event).filter { it.timestampMs >= first }.takeLast(MAX_EVENTS)
        val text = all.joinToString("\n") { "${it.timestampMs}$SEP${it.t1BonusU}$SEP${it.alertHit}" }
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
