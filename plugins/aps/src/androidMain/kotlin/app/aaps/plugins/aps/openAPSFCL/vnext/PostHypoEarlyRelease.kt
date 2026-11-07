package app.aaps.plugins.aps.openAPSFCL.vnext

import android.content.Context
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.GvpCalculator
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs

/**
 * Experiment: the post-hypo brake lets go earlier.
 *
 * The brake is armed by "hypo debt" (a dose was cut by the hypo guard) when there was no real low BG before. Data of 36 days
 * showed: 59 such brakes, only 2 lows afterwards, but 24 peaks above 10. A big dose often came right after the brake let go.
 *
 * With the experiment on, the brake lets a part of the dose it holds back through while the meal is confirmed, the BG is above
 * 6.5 and rising fast, and the IOB is not high. The part grows smoothly with each of these three (`smooth01`, no hard step) and
 * is never more than the max of the chosen [Level] (low 20%, medium 35%, high 55%). The experiment does nothing:
 *  - when the brake was armed by a real low BG (4.0 or lower in the last 45 minutes),
 *  - in a cycle where the hypo guard cuts the dose (that guard stays in charge).
 *
 * A PIN is needed for every step to a higher level, so nobody turns it on by accident. It is off by default.
 */
object PostHypoEarlyRelease {

    /** Needed to switch the experiment on or to a higher level. Going lower or off needs no PIN. */
    const val PIN = "0000"

    const val PREFS_NAME = "fcl_brake_early_release"
    private const val KEY_LEVEL = "level"
    private const val KEY_EVENTS = "events_v1"
    private const val SEP = "|"
    private const val MAX_EVENTS = 1500
    private const val KEEP_DAYS = 60

    /**
     * How much the brake may let go. [maxWeight] is the biggest part of the dose held back by the brake that is let through.
     * 2/11/2026: scale moved down after the first real use. The old low (35%) is now medium, the old medium (55%) is now high.
     */
    enum class Level(val code: Int, val maxWeight: Double) {
        OFF(0, 0.0),
        LOW(1, 0.20),
        MEDIUM(2, 0.35),
        HIGH(3, 0.55);

        val acts: Boolean get() = this != OFF

        companion object {
            fun fromCode(code: Int): Level = entries.firstOrNull { it.code == code } ?: OFF
        }
    }

    /** Rise speed (mmol/L per hour) where the release starts and where it is full. */
    const val SLOPE_START = 2.0
    const val SLOPE_FULL = 4.0

    /** BG (mmol/L) where the release starts and where it is full. */
    const val BG_START = 6.5
    const val BG_FULL = 8.0

    /** IOB ratio (IOB / max IOB) where the release starts to fade out, and where it is gone. */
    const val IOB_FADE_START = 0.35
    const val IOB_FADE_END = 0.60

    /** An event is stored when the experiment let at least this much extra insulin (U) through. */
    const val MIN_EXTRA_U = 0.02

    /**
     * One cycle in which the experiment let extra insulin through. [standardU] is the dose of the cycle without the extra
     * (what the brake allowed), [extraU] what the experiment added, so the total delivered dose is [totalU].
     * [standardU] is NaN in events stored before 2/11/2026.
     */
    data class Event(
        val timestampMs: Long,
        val weight: Double,
        val extraU: Double,
        val standardU: Double = Double.NaN
    ) {
        /** The dose delivered in the cycle (standard plus extra). NaN without the details. */
        val totalU: Double get() = standardU + extraU
    }

    /** The extra dose of one cycle, kept until the real delivered dose of that cycle is known. */
    data class Pending(val timestampMs: Long, val weight: Double, val extraU: Double)

    /**
     * Makes the event of a cycle from the [pending] extra and the dose that was really delivered ([deliveredU]).
     * The extra can never be more than the delivered dose. Returns null when there is nothing to store.
     */
    fun finish(pending: Pending?, nowMs: Long, deliveredU: Double): Event? {
        if (pending == null || pending.timestampMs != nowMs || deliveredU <= 0.0) return null
        val extra = minOf(pending.extraU, deliveredU)
        if (extra < MIN_EXTRA_U) return null
        return Event(nowMs, pending.weight, extra, deliveredU - extra)
    }

    private fun smooth01(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    /** Part (0 to the level's max) of the dose the brake lets through. */
    fun weight(level: Level, bgNow: Double, recentSlope: Double, iobRatio: Double): Double {
        val slopePart = smooth01((recentSlope - SLOPE_START) / (SLOPE_FULL - SLOPE_START))
        val bgPart = smooth01((bgNow - BG_START) / (BG_FULL - BG_START))
        val iobPart = 1.0 - smooth01((iobRatio - IOB_FADE_START) / (IOB_FADE_END - IOB_FADE_START))
        return level.maxWeight * slopePart * bgPart * iobPart
    }

    fun isPinCorrect(input: String): Boolean = input.trim() == PIN

    /** A PIN is needed for every step to a higher level. Going lower or off needs none. */
    fun needsPin(current: Level, target: Level): Boolean = target.code > current.code

    // ── Settings ──────────────────────────────────────────────────────────

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun level(context: Context): Level = Level.fromCode(prefs(context).getInt(KEY_LEVEL, Level.OFF.code))

    fun setLevel(context: Context, level: Level) {
        prefs(context).edit().putInt(KEY_LEVEL, level.code).apply()
    }

    // ── Events ────────────────────────────────────────────────────────────

    fun loadEvents(context: Context): List<Event> {
        val raw = prefs(context).getString(KEY_EVENTS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val p = line.split(SEP)
            try {
                when (p.size) {
                    3 -> Event(p[0].toLong(), p[1].toDouble(), p[2].toDouble())
                    4 -> Event(p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble())
                    else -> null
                }
            } catch (_: Exception) { null }
        }.sortedBy { it.timestampMs }
    }

    @Synchronized
    fun addEvent(context: Context, event: Event) {
        val first = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        val all = (loadEvents(context) + event).filter { it.timestampMs >= first }.takeLast(MAX_EVENTS)
        val text = all.joinToString("\n") {
            "${it.timestampMs}$SEP${it.weight}$SEP${it.extraU}$SEP${it.standardU}"
        }
        prefs(context).edit().putString(KEY_EVENTS, text).apply()
    }

    /** Tolerance for linking an event to a CSV row. Short, so one event belongs to one row only. */
    const val CSV_TOLERANCE_MS = 30_000L

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

    // ── Totals for the card ───────────────────────────────────────────────

    data class Summary(val meals: Int, val cycles: Int, val days: Int)

    /** More than this time between two moments starts a new meal. */
    const val MEAL_GAP_MS = 90 * 60_000L

    /** The events of the last [windowDays] days (today included), oldest first. */
    fun inWindow(events: List<Event>, today: LocalDate, windowDays: Int): List<Event> {
        val first = today.minusDays(windowDays.toLong() - 1)
        return events.filter {
            val d = Instant.ofEpochMilli(it.timestampMs).atZone(GvpCalculator.zone).toLocalDate()
            !d.isBefore(first) && !d.isAfter(today)
        }.sortedBy { it.timestampMs }
    }

    fun summary(events: List<Event>, today: LocalDate, windowDays: Int): Summary {
        val w = inWindow(events, today, windowDays)
        val dayCount = w.map { Instant.ofEpochMilli(it.timestampMs).atZone(GvpCalculator.zone).toLocalDate() }.distinct().size
        return Summary(meals(w).size, w.size, dayCount)
    }

    /** The moments of one meal, oldest first, and the extra insulin of that meal only. */
    data class Meal(val events: List<Event>) {
        val startMs: Long get() = events.first().timestampMs
        val extraU: Double get() = events.sumOf { it.extraU }
    }

    /** After the last moment of a meal the BG is followed for this long, because the insulin keeps working. */
    const val FOLLOW_AFTER_MS = 3 * 60 * 60_000L

    /** One cycle of the log: time, BG (mmol/L) and the insulin delivered in that cycle (U). */
    data class BgPoint(val timeMs: Long, val bg: Double, val deliveredU: Double)

    /** The first dose of a meal is looked for this long before the first moment of the experiment. */
    const val FIRST_DOSE_LOOK_BACK_MS = 60 * 60_000L

    /** A delivered dose of at least this size (U) counts as a dose of the meal. Smaller ones are only basal. */
    const val MEAL_DOSE_MIN_U = 0.3

    /**
     * The highest and lowest BG of a meal.
     *  - The peak is the highest BG from after the first dose of the meal.
     *  - The lowest is the lowest BG chronologically AFTER the last dose (the last moment of the experiment) of the meal,
     *    because only from then on all the insulin of the meal is on board. It is null when there is no BG after that yet.
     * Both windows end [FOLLOW_AFTER_MS] after the last moment, but never in the next meal. [complete] is false while the
     * window is not over yet.
     */
    data class BgRange(
        val peak: Double, val peakMs: Long,
        val low: Double?, val lowMs: Long?,
        val complete: Boolean,
        /** True while the meal is not over and the BG is still rising: then the lowest value is not known yet. */
        val rising: Boolean = false
    )

    /** The BG must have risen at least this much (mmol/L) in [RISING_LOOK_BACK_MS] to count as still rising. */
    const val RISING_MIN_DELTA = 0.3
    const val RISING_LOOK_BACK_MS = 15 * 60_000L

    /** True when the latest BG of [points] (sorted, up to [nowMs]) is at least [RISING_MIN_DELTA] above the BG 15 minutes before. */
    fun isRising(points: List<BgPoint>, nowMs: Long): Boolean {
        val latest = points.lastOrNull { it.timeMs <= nowMs } ?: return false
        val before = points.lastOrNull { it.timeMs <= latest.timeMs - RISING_LOOK_BACK_MS } ?: return false
        return latest.bg - before.bg >= RISING_MIN_DELTA
    }

    /**
     * The time of the first dose of [meal]: the first cycle with a dose of at least [MEAL_DOSE_MIN_U] in the hour before the first
     * moment of the experiment. Without such a cycle it is the first moment of the experiment itself.
     */
    fun firstDoseMs(meal: Meal, points: List<BgPoint>): Long {
        val first = meal.startMs
        return points.firstOrNull {
            it.timeMs in (first - FIRST_DOSE_LOOK_BACK_MS)..first && it.deliveredU >= MEAL_DOSE_MIN_U
        }?.timeMs ?: first
    }

    /**
     * The BG range of every meal in [meals] (oldest first), in the same order. [points] must be sorted by time.
     * An entry is null when there is no BG in the window.
     */
    fun bgRanges(meals: List<Meal>, points: List<BgPoint>, nowMs: Long): List<BgRange?> {
        val starts = meals.map { firstDoseMs(it, points) }
        return meals.mapIndexed { i, meal ->
            val start = starts[i]
            val plannedEnd = meal.events.last().timestampMs + FOLLOW_AFTER_MS
            val nextStart = starts.getOrNull(i + 1)
            val end = if (nextStart != null) minOf(plannedEnd, nextStart) else plannedEnd
            // Strictly after the first dose: the BG at that moment is not an effect of the dose yet.
            val upTo = minOf(end, nowMs)
            val forPeak = points.filter { it.timeMs > start && it.timeMs <= upTo }
            if (forPeak.isEmpty()) null else {
                val peak = forPeak.maxBy { it.bg }
                // The lowest only after the last dose of the meal.
                val lastDoseMs = meal.events.last().timestampMs
                val low = forPeak.filter { it.timeMs > lastDoseMs }.minByOrNull { it.bg }
                val complete = nowMs >= end
                BgRange(peak.bg, peak.timeMs, low?.bg, low?.timeMs, complete, rising = !complete && isRising(points, nowMs))
            }
        }
    }

    /** Groups the events (oldest first) into meals: a gap of more than [gapMs] starts a new meal. Returned oldest first. */
    fun meals(sortedEvents: List<Event>, gapMs: Long = MEAL_GAP_MS): List<Meal> {
        val result = mutableListOf<Meal>()
        var current = mutableListOf<Event>()
        for (e in sortedEvents) {
            if (current.isNotEmpty() && e.timestampMs - current.last().timestampMs > gapMs) {
                result += Meal(current)
                current = mutableListOf()
            }
            current += e
        }
        if (current.isNotEmpty()) result += Meal(current)
        return result
    }
}
