package app.aaps.plugins.aps.openAPSFCL.vnext

import android.content.Context
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.GvpCalculator
import app.aaps.plugins.aps.openAPSFCL.vnext.database.FCLCycleLogRepository
import java.time.Instant
import java.time.LocalDate

/**
 * Trial (23/10/2026): at the start of a meal the commit cover brake uses the measured IOB cover
 * (see `CoverIsfHistory`) instead of only the fixed curve of [CoverIsf]. Off by default.
 *
 * Why a trial and not a fixed change: the measured value is a net value, and the effect on the BG could
 * not be shown offline (more insulin early gives a higher IOB and so less insulin later). So the
 * correction starts small and gets stronger only when the results allow it:
 *
 *  - Strength `s` (0 to 1): the factor of the brake is `curve + s x (shadow factor - curve)`.
 *  - Start [START_STRENGTH]. Every judged commit without a low BG raises `s` by [STEP] / [BATCH], up to [MAX_STRENGTH].
 *  - A judged commit with a lowest BG below [LOW_MMOL] in the next 3 hours only counts when the trial caused it.
 *    The cause is the share of the low that the extra insulin explains: without the extra insulin the lowest BG
 *    would have been `minBg + CF_ACTION_SHARE x extra U x ISF`. If that is still below [LOW_MMOL] (with a margin),
 *    the low would have come anyway: the event is ignored. The share goes up smoothly from 0 to 1.
 *    A caused low divides `s` by `1 + LOSS_FACTOR x share` and blocks raising for `share x` [HOLD_DAYS] days.
 *  - Caps on the extra insulin: [CAP_PER_COMMIT_U] per commit and [CAP_PER_DAY_U] per day.
 *  - Only at the start of a meal: slope of [ONSET_SLOPE] or more, and at most [ONSET_ABOVE_TARGET] above target.
 *
 * The settings below are fixed values for now. They are shown on the Analyser card, so they can become
 * sliders in a later version.
 */
object CoverTrial {

    // ── Settings (shown on the card) ──────────────────────────────────────
    const val START_STRENGTH = 0.25
    const val STEP = 0.10
    const val MAX_STRENGTH = 0.75
    const val MIN_STRENGTH = 0.05
    const val CAP_PER_COMMIT_U = 0.5
    const val CAP_PER_DAY_U = 2.0
    const val BATCH = 10
    /** Longest block on raising `s` after a low that the trial fully caused. Smaller when only partly caused. */
    const val HOLD_DAYS = 3
    const val LOW_MMOL = 3.9
    /** Share of the extra insulin that works within the 3 hours of the judging window (an estimate). */
    const val CF_ACTION_SHARE = 0.6
    /** Margin (mmol/L) around [LOW_MMOL] over which the cause share goes from 0 to 1. */
    private const val CAUSE_MARGIN_MMOL = 0.3
    private const val LOSS_FACTOR = 0.5
    const val ONSET_SLOPE = 4.0
    const val ONSET_ABOVE_TARGET = 3.0
    private const val MIN_EXTRA_U = 0.05
    private const val JUDGE_AFTER_MIN = 180L
    private const val KEEP_DAYS = 90L
    private const val HIGH_MMOL = 11.0

    private const val PREFS = "fcl_cover_trial"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_STRENGTH = "strength"
    private const val KEY_HOLD_UNTIL = "hold_until_ms"
    private const val KEY_CLEAN = "clean_since_change"
    private const val KEY_EVENTS = "events_v1"
    private const val KEY_RULE = "rule_version"
    private const val RULE_VERSION = 2
    private const val KEY_LAST_CHANGE_MS = "last_change_ms"
    private const val KEY_LAST_CHANGE_TEXT = "last_change_text"
    private const val SEP = "|"

    /** Measured medians as `table[group][class]`. Set by `CoverIsfHistory` each hour. Empty = only the floor. */
    @Volatile
    var measuredTable: Array<DoubleArray> = emptyTable()

    private fun emptyTable() = Array(CoverIsf.GROUP_COUNT) { DoubleArray(CoverIsf.CLASS_COUNT) { Double.NaN } }

    /** One commit that was made bigger by the trial. */
    data class Event(
        val timestampMs: Long,
        val extraU: Double,
        val strength: Double,
        val judged: Boolean,
        val minBg: Double,
        val maxBg: Double,
        /** Extra fall of the BG that the extra insulin can cause (extra U x ISF). NaN = not known (old events). */
        val dropMmol: Double = Double.NaN
    )

    private fun smooth01(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    /**
     * Share (0 to 1) of a low BG that the trial caused. 0 = the BG would have been this low without the extra
     * insulin as well. 1 = the BG would have stayed clearly above [LOW_MMOL]. A missing drop counts as fully caused
     * (old events: the old rule).
     */
    fun causeShare(minBg: Double, dropMmol: Double): Double {
        if (dropMmol.isNaN()) return 1.0
        val without = minBg + CF_ACTION_SHARE * dropMmol
        return smooth01((without - (LOW_MMOL - CAUSE_MARGIN_MMOL)) / (2.0 * CAUSE_MARGIN_MMOL))
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── State ─────────────────────────────────────────────────────────────

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /**
     * One time, when the judging rule changed (rule 2): a strength cut and a block that the old rule made without
     * checking the cause are removed. Strength goes back to [START_STRENGTH], the block is cleared.
     */
    private fun migrateRule(context: Context) {
        val p = prefs(context)
        if (p.getInt(KEY_RULE, 1) >= RULE_VERSION) return
        p.edit()
            .putFloat(KEY_STRENGTH, START_STRENGTH.toFloat())
            .putLong(KEY_HOLD_UNTIL, 0L)
            .putInt(KEY_CLEAN, 0)
            .putInt(KEY_RULE, RULE_VERSION)
            .apply()
    }

    fun strength(context: Context): Double {
        migrateRule(context)
        return prefs(context).getFloat(KEY_STRENGTH, START_STRENGTH.toFloat()).toDouble()
    }

    fun holdUntilMs(context: Context): Long {
        migrateRule(context)
        return prefs(context).getLong(KEY_HOLD_UNTIL, 0L)
    }

    fun cleanSinceChange(context: Context): Int = prefs(context).getInt(KEY_CLEAN, 0)

    fun lastChangeMs(context: Context): Long = prefs(context).getLong(KEY_LAST_CHANGE_MS, 0L)

    fun lastChangeText(context: Context): String = prefs(context).getString(KEY_LAST_CHANGE_TEXT, "") ?: ""

    // ── Events ────────────────────────────────────────────────────────────

    fun loadEvents(context: Context): List<Event> {
        val raw = prefs(context).getString(KEY_EVENTS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val p = line.split(SEP)
            if (p.size != 6 && p.size != 7) return@mapNotNull null
            try {
                val drop = if (p.size == 7) p[6].toDouble() else Double.NaN
                Event(p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p[3] == "1", p[4].toDouble(), p[5].toDouble(), drop)
            } catch (_: Exception) { null }
        }.sortedBy { it.timestampMs }
    }

    @Synchronized
    private fun saveEvents(context: Context, events: List<Event>) {
        val first = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        val text = events.filter { it.timestampMs >= first }.sortedBy { it.timestampMs }.joinToString("\n") {
            "${it.timestampMs}$SEP${it.extraU}$SEP${it.strength}$SEP${if (it.judged) 1 else 0}$SEP${it.minBg}$SEP${it.maxBg}$SEP${it.dropMmol}"
        }
        prefs(context).edit().putString(KEY_EVENTS, text).apply()
    }

    /** Extra insulin already added by the trial on the day of [nowMs] (local date). */
    private fun extraToday(events: List<Event>, nowMs: Long): Double {
        val today = Instant.ofEpochMilli(nowMs).atZone(GvpCalculator.zone).toLocalDate()
        return events.filter { Instant.ofEpochMilli(it.timestampMs).atZone(GvpCalculator.zone).toLocalDate() == today }
            .sumOf { it.extraU }
    }

    // ── Use in the algorithm ──────────────────────────────────────────────

    /**
     * The brake to use for a commit. Equal to [brakeCurve] unless the trial is on and this is the start of a
     * meal. Never lower than [brakeCurve]. [blocked] must be true when a hypo or post-hypo protection is active.
     */
    fun brakeForCommit(
        context: Context, nowMs: Long, bg: Double, target: Double, iob: Double, isf: Double,
        recentSlope: Double, committedBefore: Double, brakeCurve: Double, blocked: Boolean
    ): Double {
        if (!isEnabled(context) || blocked || committedBefore <= 0.0 || iob <= 0.0 || isf <= 0.0) return brakeCurve
        val above = bg - target
        if (recentSlope < ONSET_SLOPE || above > ONSET_ABOVE_TARGET) return brakeCurve
        val s = strength(context).coerceIn(0.0, 1.0)
        if (s <= 0.0) return brakeCurve

        val curve = CoverIsf.factorAbove(above)
        val cls = CoverIsf.classOf(above)
        val measured = if (cls >= 0) measuredTable[CoverIsf.groupOf(recentSlope)][cls] else Double.NaN
        val shadow = CoverIsf.shadowFactor(measured, curve)
        val factor = curve + s * (shadow - curve)
        val brakeTrial = CoverIsf.commitBrake(CoverIsf.commitCoverRatio(iob, isf, factor, above))
        var extra = committedBefore * (brakeTrial - brakeCurve)
        if (extra < MIN_EXTRA_U) return brakeCurve

        val events = loadEvents(context)
        extra = minOf(extra, CAP_PER_COMMIT_U, (CAP_PER_DAY_U - extraToday(events, nowMs)).coerceAtLeast(0.0))
        if (extra < MIN_EXTRA_U) return brakeCurve

        saveEvents(context, events + Event(nowMs, extra, s, false, Double.NaN, Double.NaN, extra * isf))
        return brakeCurve + extra / committedBefore
    }

    // ── Judging (once per hour, from CoverIsfHistory) ─────────────────────

    /**
     * Looks at the events that are 3 hours or older. A low BG halves the strength; a batch of clean events
     * raises it. The BG after the commit includes the extra insulin, because it was really given.
     */
    suspend fun judge(context: Context, repository: FCLCycleLogRepository) {
        val events = loadEvents(context)
        val nowMs = System.currentTimeMillis()
        val due = events.filter { !it.judged && it.timestampMs + JUDGE_AFTER_MIN * 60_000L <= nowMs }
        if (due.isEmpty()) return
        val rows = repository.coverRows(due.first().timestampMs, nowMs + 60_000L)
        if (rows.isEmpty()) return

        migrateRule(context)
        var s = strength(context)
        var clean = cleanSinceChange(context)
        var holdUntil = holdUntilMs(context)
        var changeText = lastChangeText(context)
        var changeMs = lastChangeMs(context)
        val judged = HashMap<Long, Event>()
        for (e in due) {
            val window = rows.filter {
                it.timestampMs >= e.timestampMs && it.timestampMs <= e.timestampMs + JUDGE_AFTER_MIN * 60_000L
            }
            // Not enough BG data (gap or restart): leave it for the next hour, but not forever.
            if (window.size < 20 && e.timestampMs + 6 * 3_600_000L > nowMs) continue
            val minBg = window.minOfOrNull { it.bg } ?: Double.NaN
            val maxBg = window.maxOfOrNull { it.bg } ?: Double.NaN
            judged[e.timestampMs] = e.copy(judged = true, minBg = minBg, maxBg = maxBg)
            if (minBg.isNaN()) continue
            val doneAtMs = e.timestampMs + JUDGE_AFTER_MIN * 60_000L
            if (minBg < LOW_MMOL) {
                // A low that would have come without the extra insulin is not the fault of the trial: ignore it.
                val share = causeShare(minBg, e.dropMmol)
                if (share > 0.0) {
                    s = maxOf(s / (1.0 + LOSS_FACTOR * share), MIN_STRENGTH)
                    clean = (clean * (1.0 - share)).toInt()
                    val until = doneAtMs + (share * HOLD_DAYS * 86_400_000L).toLong()
                    if (until > holdUntil) holdUntil = until
                    changeMs = doneAtMs
                    changeText = "LOW:%.1f:%.2f".format(java.util.Locale.US, minBg, s)
                }
            } else {
                // Gradual: every clean event gives a small step, not one big step after a batch.
                clean = (clean + 1).coerceAtMost(BATCH)
                if (doneAtMs >= holdUntil && s < MAX_STRENGTH) {
                    s = minOf(s + STEP / BATCH, MAX_STRENGTH)
                    changeMs = doneAtMs
                    changeText = "UP::%.2f".format(java.util.Locale.US, s)
                }
            }
        }
        if (judged.isEmpty()) return
        saveEvents(context, events.map { judged[it.timestampMs] ?: it })
        prefs(context).edit()
            .putFloat(KEY_STRENGTH, s.toFloat())
            .putInt(KEY_CLEAN, clean)
            .putLong(KEY_HOLD_UNTIL, holdUntil)
            .putLong(KEY_LAST_CHANGE_MS, changeMs)
            .putString(KEY_LAST_CHANGE_TEXT, changeText)
            .apply()
    }

    // ── Totals for the card ───────────────────────────────────────────────

    data class Summary(
        val events: Int,
        val judged: Int,
        val extraU: Double,
        val lows: Int,
        val highs: Int
    )

    fun summary(events: List<Event>, today: LocalDate, windowDays: Int): Summary {
        val first = today.minusDays(windowDays.toLong() - 1)
        val w = events.filter {
            val d = Instant.ofEpochMilli(it.timestampMs).atZone(GvpCalculator.zone).toLocalDate()
            !d.isBefore(first) && !d.isAfter(today)
        }
        val j = w.filter { it.judged && !it.minBg.isNaN() }
        return Summary(w.size, j.size, w.sumOf { it.extraU }, j.count { it.minBg < LOW_MMOL }, j.count { it.maxBg >= HIGH_MMOL })
    }

    /** The event closest to [timestampMs] within [toleranceMs], for the CSV export. */
    fun nearest(sorted: List<Event>, timestampMs: Long, toleranceMs: Long): Event? {
        var best: Event? = null
        var bestD = Long.MAX_VALUE
        for (e in sorted) {
            val d = kotlin.math.abs(e.timestampMs - timestampMs)
            if (d <= toleranceMs && d < bestD) { best = e; bestD = d }
            if (e.timestampMs - timestampMs > toleranceMs) break
        }
        return best
    }
}
