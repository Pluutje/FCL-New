package app.aaps.plugins.aps.openAPSFCL.vnext

import android.content.Context
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.GvpCalculator
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.InsulinTimingHistory
import app.aaps.plugins.aps.openAPSFCL.vnext.database.FCLAnalyzerDatabase
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs

/**
 * Limit on the insulin per rise (29/10/2026).
 *
 * While the BG rises, the insulin given since the start of the rise may not grow faster than the rise itself allows.
 * The limit is personal: it is based on the insulin per mmol/L of rise that the user needed in the last 28 days
 * (the median over the meals, see [refreshReference]). It is not based on max SMB or max IOB, so it also helps when
 * one of those settings is not ideal.
 *
 *  - Room (U) = capFactor x reference (U per mmol/L) x rise so far (mmol/L) + margin (U) - insulin given since the start of the rise.
 *  - The dose is multiplied by `smooth01(room / dose)`: nothing changes when there is room for the whole dose, and the dose
 *    fades out smoothly to 0 when the room is used up. There is no hard step.
 *  - The limit fades in with the rise speed ([risingWeight]): it does not act when the BG is flat, so a correction of a
 *    high BG is never held back by it.
 *  - It can only lower a dose, never raise it. All hypo protection stays as it is.
 *  - Levels: [Level.MEASURE] only calculates and logs what [Level.MODERATE] would do. The other levels also act.
 *  - Without a reference (fewer than [MIN_EPISODES] meals in the last [REF_DAYS] days) there is no limit.
 *
 * The peak IOB brake is not changed here. Cycles in which that brake held a dose back while there was still room are
 * only counted (see [Event.brake]), to decide later if it can be relaxed.
 */
object InsulinBudget {

    /** The user setting. [capFactor] is how many times the personal insulin per rise is allowed. */
    enum class Level(val code: Int, val capFactor: Double, val marginU: Double, val acts: Boolean) {
        /** Only calculates and logs what [MODERATE] would do. Doses stay as they are. */
        // 2/11/2026: factors set from a replay of 7 days (own reference 1.46 U per mmol/L): about 10%, 19% and 24% of
        // all insulin is held back (with the cut counted as not given). Below 1.0 the limit would be lower than the
        // user's own median, so Strong stays at 1.0. MEASURE logs what MODERATE does.
        MEASURE(0, 1.2, 0.5, false),
        LIGHT(1, 1.6, 0.5, true),
        MODERATE(2, 1.2, 0.5, true),
        STRONG(3, 1.0, 0.5, true);

        companion object {
            fun fromCode(code: Int): Level = entries.firstOrNull { it.code == code } ?: MEASURE
        }
    }

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

    /** A cut smaller than this (U) is not stored. */
    private const val MIN_CUT_U = 0.05

    /** A cycle with the peak brake counts as "held back with room" from this room (U). */
    private const val BRAKE_ROOM_U = 0.3

    private const val KEEP_DAYS = 60L
    private const val MAX_EVENTS = 3000

    const val PREFS_NAME = "fcl_insulin_budget"
    private const val PREFS = PREFS_NAME
    private const val KEY_LEVEL = "level"
    private const val KEY_REF = "ref_u_per_mmol"
    private const val KEY_REF_N = "ref_episodes"
    private const val KEY_REF_MS = "ref_updated_ms"
    private const val KEY_EVENTS = "events_v1"
    private const val SEP = "|"

    /** One cycle in which the limit cut a dose (or would have cut it, in [Level.MEASURE]), or the peak brake held a dose back with room left. */
    data class Event(
        val timestampMs: Long,
        /** The dose before the limit (U). */
        val wantedU: Double,
        /** The dose after the limit (U). Equal to [wantedU] when the limit did not act. */
        val limitedU: Double,
        /** The room that was left before this dose (U). */
        val roomU: Double,
        val levelCode: Int,
        /** True when the dose was really lowered. False in [Level.MEASURE]. */
        val applied: Boolean,
        /** True when the peak brake or a lockout held this cycle at 0 while the BG was rising. */
        val brake: Boolean
    )

    /**
     * Result of one calculation. [doseU] is the dose to use. [limitedU] is the dose after the limit: it is
     * equal to [doseU] at a level that acts, and lower than the dose to use in [Level.MEASURE].
     */
    data class Decision(
        val doseU: Double,
        val limitedU: Double,
        val factor: Double,
        val roomU: Double
    )

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

    /** The calculation without any state. [refUPerMmol] NaN or 0 = no reference = no limit. */
    fun calculate(
        level: Level, refUPerMmol: Double, doseU: Double, riseMmol: Double, givenU: Double, recentSlope: Double
    ): Decision {
        if (refUPerMmol.isNaN() || refUPerMmol <= 0.0) return Decision(doseU, doseU, 1.0, Double.NaN)
        val weight = risingWeight(recentSlope)
        if (weight <= 0.0) return Decision(doseU, doseU, 1.0, Double.NaN)
        val allowed = allowedU(refUPerMmol, level.capFactor, riseMmol, level.marginU)
        val room = allowed - givenU
        val factor = roomFactor(room, doseU)
        val limited = limitedDose(doseU, factor, weight)
        return Decision(if (level.acts) limited else doseU, limited, factor, room)
    }

    // ── Settings and state ────────────────────────────────────────────────

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun level(context: Context): Level = Level.fromCode(prefs(context).getInt(KEY_LEVEL, Level.MEASURE.code))

    fun setLevel(context: Context, level: Level) {
        prefs(context).edit().putInt(KEY_LEVEL, level.code).apply()
    }

    /** Personal insulin per mmol/L of rise (U), NaN without a reference. */
    fun reference(context: Context): Double {
        val v = prefs(context).getFloat(KEY_REF, Float.NaN)
        return if (v.isNaN() || v <= 0f) Double.NaN else v.toDouble()
    }

    fun referenceEpisodes(context: Context): Int = prefs(context).getInt(KEY_REF_N, 0)

    fun referenceUpdatedMs(context: Context): Long = prefs(context).getLong(KEY_REF_MS, 0L)

    // ── Use in the algorithm ──────────────────────────────────────────────

    /**
     * Applies the limit to the dose of this cycle. [history] is (time in ms, BG), [givenSinceStartU] is the insulin given since the
     * start of the rise (see [riseBase]), [brakeActive] is true when the peak brake or a lockout is active. Stores an event
     * when the limit cut the dose, and when the brake held a rising BG at 0 while there was still room.
     */
    fun apply(
        context: Context, nowMs: Long, doseU: Double, bgNow: Double, recentSlope: Double,
        baseBg: Double, givenSinceStartU: Double, brakeActive: Boolean
    ): Decision {
        val level = level(context)
        val d = calculate(level, reference(context), doseU, bgNow - baseBg, givenSinceStartU, recentSlope)
        // In measure mode the dose stays as it is, so the cut is what the limit would do.
        val cut = doseU - d.limitedU
        val brakeWithRoom = brakeActive && doseU <= 0.0 && !d.roomU.isNaN() && d.roomU >= BRAKE_ROOM_U &&
            risingWeight(recentSlope) > 0.5
        if (cut >= MIN_CUT_U || brakeWithRoom) {
            addEvent(context, Event(nowMs, doseU, d.limitedU, d.roomU, level.code, level.acts && cut >= MIN_CUT_U, brakeWithRoom))
        }
        return d
    }

    // ── Events ────────────────────────────────────────────────────────────

    fun loadEvents(context: Context): List<Event> {
        val raw = prefs(context).getString(KEY_EVENTS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val p = line.split(SEP)
            if (p.size != 7) return@mapNotNull null
            try {
                Event(p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toInt(), p[5] == "1", p[6] == "1")
            } catch (_: Exception) { null }
        }.sortedBy { it.timestampMs }
    }

    @Synchronized
    private fun addEvent(context: Context, event: Event) {
        val first = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        val all = (loadEvents(context) + event).filter { it.timestampMs >= first }.takeLast(MAX_EVENTS)
        val text = all.joinToString("\n") {
            "${it.timestampMs}$SEP${it.wantedU}$SEP${it.limitedU}$SEP${it.roomU}$SEP${it.levelCode}$SEP${if (it.applied) 1 else 0}$SEP${if (it.brake) 1 else 0}"
        }
        prefs(context).edit().putString(KEY_EVENTS, text).apply()
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

    // ── Personal reference (once per hour, from CoverIsfHistory) ──────────

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

    // ── Totals for the card ───────────────────────────────────────────────

    data class Summary(
        /** Cycles in which a dose was cut (or would have been cut in measure mode). */
        val cuts: Int,
        /** Total insulin cut (U). */
        val cutU: Double,
        /** Days with at least one cut. */
        val days: Int,
        /** Cycles in which the peak brake held a rising BG at 0 while there was room. */
        val brakeCycles: Int
    )

    fun summary(events: List<Event>, today: LocalDate, windowDays: Int): Summary {
        val first = today.minusDays(windowDays.toLong() - 1)
        val w = events.filter {
            val d = Instant.ofEpochMilli(it.timestampMs).atZone(GvpCalculator.zone).toLocalDate()
            !d.isBefore(first) && !d.isAfter(today)
        }
        val cuts = w.filter { !it.brake }
        val dayCount = cuts.map { Instant.ofEpochMilli(it.timestampMs).atZone(GvpCalculator.zone).toLocalDate() }.distinct().size
        return Summary(cuts.size, cuts.sumOf { it.wantedU - it.limitedU }, dayCount, w.count { it.brake })
    }
}
