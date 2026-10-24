package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import android.content.Context
import app.aaps.plugins.aps.openAPSFCL.vnext.CoverIsf
import app.aaps.plugins.aps.openAPSFCL.vnext.CoverTrial
import app.aaps.plugins.aps.openAPSFCL.vnext.database.CycleCoverRow
import app.aaps.plugins.aps.openAPSFCL.vnext.database.FCLCycleLogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.sqrt

/**
 * Measured IOB cover factor per BG class. It only measures and shows. It never changes the curve
 * in [CoverIsf]. The cycle log is kept 90 days, these day lines are kept 400 days (about 150 bytes per
 * day), so the data is not lost while we wait to see what the numbers say.
 *
 * One measurement: a moment with IOB 1.5 U or more and BG above target, and the BG 3 hours later.
 * The measurements are kept in two groups: flat or falling BG, and rising BG (see [CoverIsf.groupOf]),
 * because the carbs that still have to be absorbed are very different in these two cases.
 *
 *     factor = (BG now - BG after 3 h) / (ISF x (IOB now + 0.5 x insulin given in the 3 hours))
 *
 * 1.0 means "the insulin works as the profile ISF says". The insulin given in between counts half,
 * because on average it has done half of its work after the 3 hours. Carbs that are still being
 * absorbed push the BG up, so the measured factor is expected to be lower than the real one.
 */
data class CoverClassDay(
    val n: Int,
    val sum: Double,
    val sumSq: Double,
    /** Median of the measurements of this day in this class. NaN = no measurements. */
    val median: Double
)

/**
 * One stored day. [classes] holds [CoverIsf.GROUP_COUNT] x [CoverIsf.CLASS_COUNT] cells,
 * cell index = group x CLASS_COUNT + BG class.
 */
data class CoverDay(val date: LocalDate, val classes: List<CoverClassDay>)

/** Summary of one BG class in one group over a number of days. */
data class CoverClassSummary(
    val group: Int,
    val cls: Int,
    val n: Int,
    val days: Int,
    val mean: Double,
    val sd: Double,
    /** Median of the daily medians (days with 3 measurements or more). NaN = too few. */
    val median: Double,
    /** Factor of the fixed curve in the middle of this class. */
    val curve: Double
)

object CoverIsfHistory {

    private const val PREFS = "fcl_cover_isf"
    // v2 (19/10/2026): two groups (flat / rising) per BG class. The v1 key is left alone. Nothing is lost:
    // every day is made again from the 90 day cycle log by the first run (the v2 backfill).
    private const val KEY_DAYS = "days_v2"
    // "v3": the first v2 run found no measurements (a bug, fixed) but still marked the backfill as done,
    // so the 90 days must be read again once.
    // "v4": the shadow calculation is new and must also be made for the 90 days in the cycle log once.
    // Reading the measurements again is safe: a stored day is only replaced by one with as many or more.
    private const val KEY_BACKFILL = "backfill_v4_done"
    private const val KEEP_DAYS = 400L
    private const val BACKFILL_DAYS = 90L
    private const val REFRESH_DAYS = 3L
    private const val CHUNK_DAYS = 5L
    private const val SEP = "|"

    // Measurement rules
    private const val MIN_IOB = 1.5
    private const val LOOK_AHEAD_MIN = 180L
    private const val LOOK_AHEAD_TOLERANCE_MIN = 7L
    private const val MAX_GAP_MIN = 12L
    private const val SAMPLE_EVERY_MIN = 15L
    private const val GIVEN_INSULIN_WEIGHT = 0.5
    private const val CLAMP_LOW = -0.5
    private const val CLAMP_HIGH = 1.5
    private const val MIN_DAY_SAMPLES_FOR_MEDIAN = 3

    @Volatile private var lastRunHourKey = -1L
    @Volatile private var running = false
    private val scope = CoroutineScope(Dispatchers.IO)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Reading ───────────────────────────────────────────────────────────

    fun load(context: Context): List<CoverDay> {
        val raw = prefs(context).getString(KEY_DAYS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { parse(it) }.sortedBy { it.date }
    }

    private fun parse(line: String): CoverDay? {
        val p = line.split(SEP)
        val cells = CoverIsf.GROUP_COUNT * CoverIsf.CLASS_COUNT
        if (p.size != 1 + cells) return null
        return try {
            CoverDay(
                date = LocalDate.parse(p[0]),
                classes = (1..cells).map { i ->
                    val f = p[i].split(":")
                    CoverClassDay(
                        n = f[0].toInt(), sum = f[1].toDouble(),
                        sumSq = f[2].toDouble(), median = f[3].toDouble()
                    )
                }
            )
        } catch (_: Exception) { null }
    }

    private fun encode(d: CoverDay): String =
        (listOf(d.date.toString()) + d.classes.map { "${it.n}:${it.sum}:${it.sumSq}:${it.median}" })
            .joinToString(SEP)

    /** Summary per class of one group over the last [windowDays] days up to and including [today]. */
    fun summary(days: List<CoverDay>, today: LocalDate, group: Int, windowDays: Int = 28): List<CoverClassSummary> {
        val first = today.minusDays(windowDays.toLong() - 1)
        val inWindow = days.filter { !it.date.isBefore(first) && !it.date.isAfter(today) }
        return (0 until CoverIsf.CLASS_COUNT).map { c ->
            val parts = inWindow.map { it.classes[group * CoverIsf.CLASS_COUNT + c] }.filter { it.n > 0 }
            val n = parts.sumOf { it.n }
            val mean = if (n > 0) parts.sumOf { it.sum } / n else Double.NaN
            val variance = if (n > 1) (parts.sumOf { it.sumSq } / n - mean * mean).coerceAtLeast(0.0) else Double.NaN
            val dailyMedians = parts.filter { it.n >= MIN_DAY_SAMPLES_FOR_MEDIAN && !it.median.isNaN() }
                .map { it.median }.sorted()
            val median = when {
                dailyMedians.isEmpty() -> Double.NaN
                dailyMedians.size % 2 == 1 -> dailyMedians[dailyMedians.size / 2]
                else -> (dailyMedians[dailyMedians.size / 2 - 1] + dailyMedians[dailyMedians.size / 2]) / 2.0
            }
            CoverClassSummary(
                group = group, cls = c, n = n, days = parts.size, mean = mean,
                sd = if (variance.isNaN()) Double.NaN else sqrt(variance),
                median = median, curve = CoverIsf.curveAtClassMiddle(c)
            )
        }
    }

    /** Mean factor of one group over one week, for the trend line. */
    data class CoverWeekPoint(
        /** Last day of this week. */
        val lastDay: LocalDate,
        val n: Int,
        /** Mean over all measurements of the week. NaN = no measurements. */
        val mean: Double
    )

    /**
     * Weekly mean of one group over the last [weeks] weeks (oldest first, the last week ends at [today]).
     * Only BG classes from [minClass] up are used: there the insulin has most to do, and mixing
     * all classes would make the line follow the BG spread of the week instead of the factor.
     */
    fun weeklyTrend(
        days: List<CoverDay>, today: LocalDate, group: Int,
        weeks: Int = 8, minClass: Int = 2
    ): List<CoverWeekPoint> =
        (weeks - 1 downTo 0).map { w ->
            val last = today.minusDays(7L * w)
            val first = last.minusDays(6)
            val parts = days.filter { !it.date.isBefore(first) && !it.date.isAfter(last) }
                .flatMap { d -> (minClass until CoverIsf.CLASS_COUNT).map { c -> d.classes[group * CoverIsf.CLASS_COUNT + c] } }
                .filter { it.n > 0 }
            val n = parts.sumOf { it.n }
            CoverWeekPoint(last, n, if (n > 0) parts.sumOf { it.sum } / n else Double.NaN)
        }

    // ── Shadow calculation ────────────────────────────────────────────────
    //
    // "What if the commit cover brake had used the measured factor?" Nothing is dosed with it. For every
    // commit of a version that has the cover brake, the brake of the curve is replaced by the brake of
    // the shadow factor ([CoverIsf.shadowFactor]) and the extra insulin is added up per day. The BG of the
    // next 3 hours (after the dose that was really given) tells if the extra insulin would have helped
    // or would have been risky.

    /** Result of the shadow calculation for one day. */
    data class ShadowDay(
        val date: LocalDate,
        /** Commits of the day (all rows with a commit dose), also the ones without any change. */
        val commits: Int,
        /** Commits that would have been bigger. */
        val rows: Int,
        /** Insulin that would have been added (U). */
        val extraU: Double,
        /** Commits where the lowest BG of the next 3 hours, minus the effect of the extra insulin, is below [SHADOW_LOW_MMOL]. */
        val lowRisk: Int,
        /** Commits followed by a peak of [SHADOW_HIGH_MMOL] or more that the extra insulin would have lowered below it. */
        val highHelped: Int
    )

    private const val KEY_SHADOW = "shadow_v1"
    /** First version with the commit cover brake. Older rows have no brake, so there is nothing to compare. */
    private const val SHADOW_FIRST_VERSION = "v146-2026-10-19-2200"
    private const val SHADOW_MIN_EXTRA_U = 0.05
    /** Part of the profile ISF effect that is assumed to work within 3 hours. */
    private const val SHADOW_ISF_EFFECT_3H = 0.6
    /** Part of that effect that is assumed to work on the peak (which is earlier than the lowest point). */
    private const val SHADOW_PEAK_EFFECT = 0.8
    private const val SHADOW_LOW_MMOL = 4.0
    private const val SHADOW_HIGH_MMOL = 11.0
    private const val SHADOW_LOOK_AHEAD_MIN = 180L
    /** A commit is judged only when the log reaches this far after it. */
    private const val SHADOW_MIN_LOOK_AHEAD_MIN = 170L
    private const val SHADOW_MIN_SAMPLES = 10

    /** True if the rows of this version had the commit cover brake. Compares the version text. */
    private fun hasCoverBrake(codeVersion: String): Boolean =
        codeVersion.length >= SHADOW_FIRST_VERSION.length && codeVersion >= SHADOW_FIRST_VERSION

    /** Measured medians as `table[group][class]`, NaN where there are fewer than [SHADOW_MIN_SAMPLES] measurements. */
    internal fun shadowTable(days: List<CoverDay>, today: LocalDate): Array<DoubleArray> =
        Array(CoverIsf.GROUP_COUNT) { g ->
            val s = summary(days, today, g, 28)
            DoubleArray(CoverIsf.CLASS_COUNT) { c ->
                if (s[c].n >= SHADOW_MIN_SAMPLES && !s[c].median.isNaN()) s[c].median else Double.NaN
            }
        }

    /** Shadow result of one commit: the factor, the brake of the curve, the brake of the shadow factor and the extra insulin. */
    data class ShadowRow(val factor: Double, val brakeNow: Double, val brakeShadow: Double, val extraU: Double)

    /**
     * Shadow calculation of one cycle. Null when it does not apply: no commit, or a version without the
     * commit cover brake. [table] comes from [shadowTable]; use an all-NaN table to use only the floor.
     */
    fun shadowForRow(
        bg: Double, target: Double, iob: Double, isf: Double, recentSlope: Double,
        commitDoseFinal: Double, codeVersion: String, table: Array<DoubleArray>
    ): ShadowRow? {
        if (commitDoseFinal <= 0.0 || iob <= 0.0 || isf <= 0.0 || !hasCoverBrake(codeVersion)) return null
        val above = bg - target
        val curve = CoverIsf.factorAbove(above)
        val brakeNow = CoverIsf.commitBrake(CoverIsf.commitCoverRatio(iob, isf, curve, above))
        val cls = CoverIsf.classOf(above)
        val measured = if (cls >= 0) table[CoverIsf.groupOf(recentSlope)][cls] else Double.NaN
        val shadow = CoverIsf.shadowFactor(measured, curve)
        val brakeShadow = CoverIsf.commitBrake(CoverIsf.commitCoverRatio(iob, isf, shadow, above))
        val extra = if (brakeNow >= 0.999) 0.0 else commitDoseFinal / brakeNow * brakeShadow - commitDoseFinal
        return ShadowRow(shadow, brakeNow, brakeShadow, extra)
    }

    /** A table with no measured values: the shadow factor is then the floor (or the curve if that is lower). */
    fun emptyShadowTable(): Array<DoubleArray> =
        Array(CoverIsf.GROUP_COUNT) { DoubleArray(CoverIsf.CLASS_COUNT) { Double.NaN } }

    internal fun shadowFromRows(rows: List<CycleCoverRow>, zone: ZoneId, table: Array<DoubleArray>): List<ShadowDay> {
        if (rows.isEmpty()) return emptyList()
        val lastMs = rows.last().timestampMs
        class Acc(var commits: Int = 0, var rows: Int = 0, var extra: Double = 0.0, var low: Int = 0, var high: Int = 0)
        val byDay = HashMap<LocalDate, Acc>()
        for (i in rows.indices) {
            val r = rows[i]
            if (r.commitDoseFinal <= 0.0 || r.iob <= 0.0 || r.effectiveISF <= 0.0) continue
            if (!hasCoverBrake(r.codeVersion)) continue
            if (lastMs < r.timestampMs + SHADOW_MIN_LOOK_AHEAD_MIN * 60_000L) continue
            val acc = byDay.getOrPut(Instant.ofEpochMilli(r.timestampMs).atZone(zone).toLocalDate()) { Acc() }
            acc.commits++

            val extra = shadowForRow(
                r.bg, r.target, r.iob, r.effectiveISF, r.recentSlope, r.commitDoseFinal, r.codeVersion, table
            )?.extraU ?: continue
            if (extra < SHADOW_MIN_EXTRA_U) continue

            var lowest = r.bg
            var highest = r.bg
            var k = i
            while (k < rows.size && rows[k].timestampMs <= r.timestampMs + SHADOW_LOOK_AHEAD_MIN * 60_000L) {
                lowest = minOf(lowest, rows[k].bg)
                highest = maxOf(highest, rows[k].bg)
                k++
            }
            val effect = extra * r.effectiveISF * SHADOW_ISF_EFFECT_3H
            acc.rows++
            acc.extra += extra
            if (lowest - effect < SHADOW_LOW_MMOL) acc.low++
            if (highest >= SHADOW_HIGH_MMOL && highest - effect * SHADOW_PEAK_EFFECT < SHADOW_HIGH_MMOL) acc.high++
        }
        return byDay.map { (d, a) -> ShadowDay(d, a.commits, a.rows, round4(a.extra), a.low, a.high) }.sortedBy { it.date }
    }

    fun loadShadow(context: Context): List<ShadowDay> {
        val raw = prefs(context).getString(KEY_SHADOW, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val p = line.split(SEP)
            if (p.size != 6) return@mapNotNull null
            try {
                ShadowDay(LocalDate.parse(p[0]), p[1].toInt(), p[2].toInt(), p[3].toDouble(), p[4].toInt(), p[5].toInt())
            } catch (_: Exception) { null }
        }.sortedBy { it.date }
    }

    @Synchronized
    private fun mergeShadow(context: Context, newDays: List<ShadowDay>) {
        if (newDays.isEmpty()) return
        val map = loadShadow(context).associateBy { it.date }.toMutableMap()
        // A day is made again from the whole day, so the new line replaces the old one,
        // unless the cycle log has already lost part of that day (fewer commits).
        newDays.forEach { n ->
            val old = map[n.date]
            if (old == null || n.commits >= old.commits) map[n.date] = n
        }
        val firstKeep = LocalDate.now(GvpCalculator.zone).minusDays(KEEP_DAYS)
        val text = map.values.filter { !it.date.isBefore(firstKeep) }.sortedBy { it.date }
            .joinToString("\n") { "${it.date}$SEP${it.commits}$SEP${it.rows}$SEP${it.extraU}$SEP${it.lowRisk}$SEP${it.highHelped}" }
        prefs(context).edit().putString(KEY_SHADOW, text).apply()
    }

    /** Totals of the shadow calculation over the last [windowDays] days up to and including [today]. */
    data class ShadowSummary(
        val days: Int,
        val commits: Int,
        val rows: Int,
        val extraU: Double,
        val lowRisk: Int,
        val highHelped: Int
    )

    fun shadowSummary(days: List<ShadowDay>, today: LocalDate, windowDays: Int = 7): ShadowSummary {
        val first = today.minusDays(windowDays.toLong() - 1)
        val w = days.filter { !it.date.isBefore(first) && !it.date.isAfter(today) }
        return ShadowSummary(
            w.size, w.sumOf { it.commits }, w.sumOf { it.rows }, w.sumOf { it.extraU },
            w.sumOf { it.lowRisk }, w.sumOf { it.highHelped }
        )
    }

    // ── Calculation (no Android) ──────────────────────────────────────────

    private class Sample(val date: LocalDate, val group: Int, val cls: Int, val factor: Double)

    /**
     * All measurements that can be made from [rows] (sorted by time). A moment is used only when the
     * rows from the moment to 3 hours later have no gap longer than [MAX_GAP_MIN] minutes.
     */
    private fun samplesFromRows(rows: List<CycleCoverRow>, zone: ZoneId): List<Sample> {
        val out = ArrayList<Sample>()
        if (rows.size < 2) return out
        // Not Long.MIN_VALUE: "timestamp - MIN_VALUE" overflows and then every moment is skipped.
        var lastSampleMs = 0L
        var j = 0
        for (i in rows.indices) {
            val r = rows[i]
            if (r.timestampMs - lastSampleMs < SAMPLE_EVERY_MIN * 60_000L) continue
            val above = r.bg - r.target
            val cls = CoverIsf.classOf(above)
            if (cls < 0 || r.iob < MIN_IOB || r.effectiveISF <= 0.0) continue

            val targetMs = r.timestampMs + LOOK_AHEAD_MIN * 60_000L
            if (j < i) j = i
            while (j < rows.size && rows[j].timestampMs < targetMs - LOOK_AHEAD_TOLERANCE_MIN * 60_000L) j++
            if (j >= rows.size) break
            val end = rows[j]
            if (end.timestampMs > targetMs + LOOK_AHEAD_TOLERANCE_MIN * 60_000L) continue

            var gapOk = true
            var given = 0.0
            for (k in i + 1..j) {
                if (rows[k].timestampMs - rows[k - 1].timestampMs > MAX_GAP_MIN * 60_000L) { gapOk = false; break }
                given += rows[k].deliveredTotal
            }
            if (!gapOk) continue

            val denominator = r.effectiveISF * (r.iob + GIVEN_INSULIN_WEIGHT * given)
            if (denominator <= 0.0) continue
            val factor = ((r.bg - end.bg) / denominator).coerceIn(CLAMP_LOW, CLAMP_HIGH)
            out.add(
                Sample(
                    Instant.ofEpochMilli(r.timestampMs).atZone(zone).toLocalDate(),
                    CoverIsf.groupOf(r.recentSlope), cls, factor
                )
            )
            lastSampleMs = r.timestampMs
        }
        return out
    }

    private fun median(sorted: List<Double>): Double = when {
        sorted.isEmpty() -> Double.NaN
        sorted.size % 2 == 1 -> sorted[sorted.size / 2]
        else -> (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
    }

    /** Group the samples by day of the moment of the measurement. A day with no samples gives no line. */
    internal fun daysFromRows(rows: List<CycleCoverRow>, zone: ZoneId): List<CoverDay> =
        samplesFromRows(rows, zone).groupBy { it.date }.map { (date, list) ->
            CoverDay(
                date = date,
                classes = (0 until CoverIsf.GROUP_COUNT * CoverIsf.CLASS_COUNT).map { cell ->
                    val g = cell / CoverIsf.CLASS_COUNT
                    val c = cell % CoverIsf.CLASS_COUNT
                    val v = list.filter { it.group == g && it.cls == c }.map { it.factor }
                    CoverClassDay(
                        n = v.size,
                        sum = round4(v.sum()),
                        sumSq = round4(v.sumOf { it * it }),
                        median = if (v.isEmpty()) Double.NaN else round4(median(v.sorted()))
                    )
                }
            )
        }.sortedBy { it.date }

    private fun round4(v: Double) = Math.round(v * 10_000.0) / 10_000.0

    // ── Writing ───────────────────────────────────────────────────────────

    @Synchronized
    private fun merge(context: Context, newDays: List<CoverDay>) {
        if (newDays.isEmpty()) return
        val map = load(context).associateBy { it.date }.toMutableMap()
        // The cycle log keeps 90 days only: an older stored line is never replaced by one with less data.
        newDays.forEach { n ->
            val old = map[n.date]
            if (old == null || n.classes.sumOf { it.n } >= old.classes.sumOf { it.n }) map[n.date] = n
        }
        val firstKeep = LocalDate.now(GvpCalculator.zone).minusDays(KEEP_DAYS)
        val text = map.values.filter { !it.date.isBefore(firstKeep) }
            .sortedBy { it.date }.joinToString("\n") { encode(it) }
        prefs(context).edit().putString(KEY_DAYS, text).apply()
    }

    /** Cheap when nothing is due: runs at most once per hour, on a background thread. */
    fun onCycle(context: Context, repository: FCLCycleLogRepository) {
        val hourKey = System.currentTimeMillis() / 3_600_000L
        if (hourKey == lastRunHourKey || running) return
        lastRunHourKey = hourKey
        running = true
        scope.launch {
            try {
                val done = prefs(context).getBoolean(KEY_BACKFILL, false)
                val days = if (done) REFRESH_DAYS else BACKFILL_DAYS
                val zone = GvpCalculator.zone
                val tomorrow = LocalDate.now(zone).plusDays(1)
                var to = tomorrow
                val first = tomorrow.minusDays(days + 1)
                val lookAheadMs = (LOOK_AHEAD_MIN + LOOK_AHEAD_TOLERANCE_MIN) * 60_000L
                // Measured medians of the days stored so far, for the shadow calculation.
                val shadowMeasured = shadowTable(load(context), LocalDate.now(zone))
                // In small chunks: a 90 day backfill must not load all rows at once. Each chunk also
                // reads the 3 hours after it, so the last moments of a chunk can be measured.
                while (to.isAfter(first)) {
                    val from = to.minusDays(CHUNK_DAYS).let { if (it.isBefore(first)) first else it }
                    val rows = repository.coverRows(
                        from.atStartOfDay(zone).toInstant().toEpochMilli(),
                        to.atStartOfDay(zone).toInstant().toEpochMilli() + lookAheadMs
                    )
                    val fromDate = from
                    // Keep only the days that start inside this chunk: a day is complete here.
                    merge(context, daysFromRows(rows, zone).filter { !it.date.isBefore(fromDate) && it.date.isBefore(to) })
                    mergeShadow(
                        context,
                        shadowFromRows(rows, zone, shadowMeasured).filter { !it.date.isBefore(fromDate) && it.date.isBefore(to) }
                    )
                    to = from
                }
                if (!done) prefs(context).edit().putBoolean(KEY_BACKFILL, true).apply()
                // The trial (CoverTrial) reads the measured medians from here and is judged once per hour.
                CoverTrial.measuredTable = shadowTable(load(context), LocalDate.now(zone))
                CoverTrial.judge(context, repository)
            } catch (_: Exception) {
                // try again next hour
            } finally {
                running = false
            }
        }
    }
}
