package app.aaps.plugins.aps.openAPSFCL.vnext

import android.content.Context
import kotlin.math.abs

/**
 * Log only (11/11/2026): which layer of the algorithm cut the dose the most in a cycle.
 *
 * WHY: the dose goes through many layers (brakes, caps, holds). When a dose is small or zero, it takes hours to find
 * out which layer did it. With this trace, the CSV tells it for every cycle in `top_cut_layer`.
 *
 * HOW: the algorithm calls [mark] after a layer with the dose it has at that moment. A drop in the dose since the
 * last mark is counted for that layer. A rise is not counted (it only moves the starting point).
 *
 * This does NOT change any dose. It only reads the dose.
 *
 * Cycles with a cut of at least [MIN_STORE_U], or with a hard stop, are stored and written to the CSV:
 * `top_cut_layer`, `top_cut_u`, `total_cut_u`.
 */
class DoseCutTrace {

    private var last = Double.NaN
    private val cuts = LinkedHashMap<String, Double>()

    /** The layer that stopped everything before any dose was worked out. Empty when there was none. */
    var hardStopLayer: String = ""
        private set

    fun reset() {
        last = Double.NaN
        cuts.clear()
        hardStopLayer = ""
    }

    /** [dose] is the dose after [layer]. The first call only sets the starting point. */
    fun mark(layer: String, dose: Double) {
        if (dose.isNaN()) return
        if (!last.isNaN() && dose < last) {
            cuts[layer] = (cuts[layer] ?: 0.0) + (last - dose)
        }
        last = dose
    }

    /**
     * Forgets the cuts of all layers whose name starts with [prefix] and sets the starting point to [dose].
     * Used when a block was worked out but its dose was not used (for example a commit below the minimum).
     */
    fun forget(prefix: String, dose: Double) {
        cuts.keys.removeAll { it.startsWith(prefix) }
        if (!dose.isNaN()) last = dose
    }

    /** A layer that ended the cycle before a dose was worked out (no dose to compare with). */
    fun markHardStop(layer: String) {
        hardStopLayer = layer
    }

    fun totalCutU(): Double = cuts.values.sum()

    /** The layer with the biggest cut, or the hard stop, or empty. */
    fun topLayer(): String =
        if (hardStopLayer.isNotEmpty()) hardStopLayer else cuts.maxByOrNull { it.value }?.key ?: ""

    fun topCutU(): Double = if (hardStopLayer.isNotEmpty()) 0.0 else cuts.values.maxOrNull() ?: 0.0

    /** True when this cycle is worth storing. */
    fun worthStoring(): Boolean = hardStopLayer.isNotEmpty() || totalCutU() >= MIN_STORE_U

    companion object {
        /** Smaller cuts are not stored. */
        const val MIN_STORE_U = 0.02
    }
}

object DoseCutLog {

    const val CSV_TOLERANCE_MS = 150_000L
    private const val KEEP_DAYS = 60L
    private const val MAX_EVENTS = 6000
    private const val PREFS = "fcl_dose_cut_log"
    private const val KEY_EVENTS = "events_v1"
    private const val SEP = "|"

    data class Event(
        val timestampMs: Long,
        val layer: String,
        val topCutU: Double,
        val totalCutU: Double
    )

    fun record(context: Context, nowMs: Long, trace: DoseCutTrace) {
        if (!trace.worthStoring()) return
        addEvent(context, Event(nowMs, trace.topLayer(), trace.topCutU(), trace.totalCutU()))
    }

    fun loadEvents(context: Context): List<Event> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_EVENTS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val p = line.split(SEP)
            if (p.size != 4) return@mapNotNull null
            try {
                Event(p[0].toLong(), p[1], p[2].toDouble(), p[3].toDouble())
            } catch (_: Exception) { null }
        }.sortedBy { it.timestampMs }
    }

    @Synchronized
    private fun addEvent(context: Context, event: Event) {
        val first = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        val all = (loadEvents(context) + event).filter { it.timestampMs >= first }.takeLast(MAX_EVENTS)
        val text = all.joinToString("\n") {
            "${it.timestampMs}$SEP${it.layer}$SEP${it.topCutU}$SEP${it.totalCutU}"
        }
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
