package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.sqrt

/** Average TDD over a number of days. [days] = how many days had a TDD. */
data class TddAverage(val avg: Double, val basalShare: Double, val days: Int)

/** What the TDD card says about the last weeks. */
enum class TddAdviceKind {
    /** Not enough days to say anything reliable. */
    NOT_ENOUGH_DATA,
    /** Days differ too much from each other, a comparison is not reliable. */
    TOO_NOISY,
    /** TDD did not change much. */
    STABLE,
    /** TDD up and TIR worse. */
    UP_TIR_WORSE,
    /** TDD up, TIR not worse. */
    UP_TIR_OK,
    /** TDD down and TIR not worse. */
    DOWN_TIR_OK,
    /** TDD down and TIR worse. */
    DOWN_TIR_WORSE
}

data class TddAdvice(
    val kind: TddAdviceKind,
    val changePct: Double = 0.0,
    val tirChange: Double = 0.0,
    val recentDays: Int = 0,
    val baseDays: Int = 0
)

/** One block of days for the weekly chart. [start] is the oldest day of the block. */
data class TddWeekBlock(
    val start: LocalDate,
    val tdd: Double,
    /** NaN = not known (too few days with enough BG data). */
    val tirPct: Double,
    val tddDays: Int,
    /** Time above / below range, NaN = not known. */
    val tarPct: Double = Double.NaN,
    val tbrPct: Double = Double.NaN,
    /** Share (percent) of the insulin that FCL commanded itself, NaN = not known. */
    val fclSharePct: Double = Double.NaN
)

/** A dosing setting that is followed per day. */
enum class ParamKind(val isPercent: Boolean) {
    STERKTE(true), TIMING(true), VOLHOUDENDHEID(true), NACHT_FACTOR(true),
    AAPS_MULTIPLIER(true), DISTRIBUTION_STYLE(false), NIGHT_STYLE(false)
}

/** Average results over a window of days. NaN = not known. */
data class PeriodResult(
    val days: Int,
    val tdd: Double,
    val tirPct: Double,
    val tarPct: Double,
    val tbrPct: Double,
    val fclSharePct: Double
)

/**
 * One change of a setting that stayed for the next day. [before] covers the [CHANGE_WINDOW]
 * days before the change day, [after] the days after it. [after] is null while there are fewer
 * than [TddStats.MIN_CHANGE_DAYS] days with data after the change ("still too short").
 */
data class ParamChange(
    val date: LocalDate,
    val kind: ParamKind,
    /** Old and new value as text: a number for percent settings, else the style name. */
    val from: String,
    val to: String,
    val before: PeriodResult?,
    val after: PeriodResult?
)

/** What a weekday looks like compared with the average over all days. */
enum class WeekdayHintKind {
    /** TDD clearly higher and TAR higher: more insulin may be needed on this day. */
    UP_TAR_HIGHER,
    /** TDD clearly higher, TAR not higher: the system handles it. */
    UP_TAR_OK,
    /** TDD clearly lower and TBR higher: less insulin may be needed on this day. */
    DOWN_TBR_HIGHER,
    /** TDD clearly lower, TBR not higher: lower need, no problem. */
    DOWN_TBR_OK
}

/**
 * One weekday. [tdd] is NaN with fewer than 3 days. [hint] is only set for a clear and
 * repeated difference (see [TddStats.weekdayOverview]). [diffPoints] is the TAR (hint UP_*)
 * or TBR (hint DOWN_*) of this weekday minus the average, in percent points.
 */
data class WeekdayStat(
    val dow: DayOfWeek,
    val days: Int,
    val tdd: Double,
    val hint: WeekdayHintKind? = null,
    val tddChangePct: Double = 0.0,
    val diffPoints: Double = 0.0
)

data class WeekdayOverview(
    val stats: List<WeekdayStat>,
    val meanTdd: Double,
    /** False when no weekday has enough days for a hint yet. */
    val enoughDays: Boolean
)

/**
 * Plain calculations on the stored day lines for the TDD card. No Android here.
 *
 * The advice only appears when it can be trusted: enough days with a TDD in both windows,
 * days that are not too different from each other, and a TIR from days with enough BG data.
 * It is a hint to look at the settings. It never changes anything.
 */
object TddStats {

    const val RECENT_DAYS = 14
    const val BASE_DAYS = 28
    const val MIN_RECENT_TDD_DAYS = 10
    const val MIN_BASE_TDD_DAYS = 14
    /** A day counts for TIR only with at least this many hours of BG. */
    const val MIN_TIR_HOURS = 20.0
    /** TDD change (fraction) that counts as a real change. */
    const val CHANGE_LIMIT = 0.15
    /** TIR change (percent points) that counts as better or worse. */
    const val TIR_LIMIT = 3.0
    /** Spread (standard deviation / average) above which days are too different. */
    const val MAX_SPREAD = 0.35

    /** Weekly chart: number of blocks, days per block, and days with a TDD a block needs. */
    const val WEEK_BLOCKS = 8
    const val BLOCK_DAYS = 7
    const val MIN_BLOCK_TDD_DAYS = 4

    /** Weekday view: window, days a weekday needs for a hint, and the limits for a hint. */
    const val WEEKDAY_WINDOW_DAYS = 90
    const val MIN_WEEKDAY_DAYS = 6
    /** A weekday must differ at least this much (fraction) from the average TDD. */
    const val WEEKDAY_CHANGE_LIMIT = 0.10
    /** TAR (percent points) higher than average that counts as worse. */
    const val TAR_LIMIT = 3.0
    /** TBR (percent points) higher than average that counts as worse. */
    const val TBR_LIMIT = 1.0

    /** Before / after view around a setting change. */
    const val CHANGE_WINDOW = 7
    const val CHANGE_LOOKBACK_DAYS = 85
    const val MIN_CHANGE_DAYS = 4
    const val MAX_CHANGES = 6
    /** A dose day needs this many cycles (of about 288) to be used for a change. */
    const val MIN_CYCLES_PER_DAY = 150

    /** Days with a TDD, from [fromBack] to [toBack] days before [today] (1 = yesterday). */
    fun window(all: List<DayAgg>, today: LocalDate, fromBack: Int, toBack: Int): List<DayAgg> {
        val first = today.minusDays(toBack.toLong())
        val last = today.minusDays(fromBack.toLong())
        return all.filter { !it.date.isBefore(first) && !it.date.isAfter(last) && !it.tdd.isNaN() }
    }

    fun average(days: List<DayAgg>): TddAverage? {
        if (days.isEmpty()) return null
        val total = days.sumOf { it.tdd }
        val basal = days.filter { !it.tddBasal.isNaN() }.sumOf { it.tddBasal }
        val basalTotal = days.filter { !it.tddBasal.isNaN() }.sumOf { it.tdd }
        return TddAverage(
            avg = total / days.size,
            basalShare = if (basalTotal > 0.0) basal / basalTotal else Double.NaN,
            days = days.size
        )
    }

    private fun spread(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        if (mean <= 0.0) return 0.0
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return sqrt(variance) / mean
    }

    private fun tirAverage(all: List<DayAgg>, today: LocalDate, fromBack: Int, toBack: Int): Pair<Double, Int>? {
        val first = today.minusDays(toBack.toLong())
        val last = today.minusDays(fromBack.toLong())
        val d = all.filter {
            !it.date.isBefore(first) && !it.date.isAfter(last) && it.hours >= MIN_TIR_HOURS
        }
        if (d.isEmpty()) return null
        return d.map { it.tirPct }.average() to d.size
    }

    /** Compare the last [RECENT_DAYS] days with the [BASE_DAYS] days before them. */
    fun advice(all: List<DayAgg>, today: LocalDate): TddAdvice {
        val recent = window(all, today, 1, RECENT_DAYS)
        val base = window(all, today, RECENT_DAYS + 1, RECENT_DAYS + BASE_DAYS)
        if (recent.size < MIN_RECENT_TDD_DAYS || base.size < MIN_BASE_TDD_DAYS) {
            return TddAdvice(TddAdviceKind.NOT_ENOUGH_DATA, recentDays = recent.size, baseDays = base.size)
        }
        val tirRecent = tirAverage(all, today, 1, RECENT_DAYS)
        val tirBase = tirAverage(all, today, RECENT_DAYS + 1, RECENT_DAYS + BASE_DAYS)
        if (tirRecent == null || tirBase == null ||
            tirRecent.second < MIN_RECENT_TDD_DAYS || tirBase.second < MIN_BASE_TDD_DAYS
        ) {
            return TddAdvice(TddAdviceKind.NOT_ENOUGH_DATA, recentDays = recent.size, baseDays = base.size)
        }
        if (spread(recent.map { it.tdd }) > MAX_SPREAD || spread(base.map { it.tdd }) > MAX_SPREAD) {
            return TddAdvice(TddAdviceKind.TOO_NOISY, recentDays = recent.size, baseDays = base.size)
        }
        val recentAvg = recent.map { it.tdd }.average()
        val baseAvg = base.map { it.tdd }.average()
        val change = recentAvg / baseAvg - 1.0
        val tirChange = tirRecent.first - tirBase.first
        val kind = when {
            abs(change) < CHANGE_LIMIT -> TddAdviceKind.STABLE
            change > 0 && tirChange <= -TIR_LIMIT -> TddAdviceKind.UP_TIR_WORSE
            change > 0 && tirChange > -TIR_LIMIT -> TddAdviceKind.UP_TIR_OK
            change < 0 && tirChange <= -TIR_LIMIT -> TddAdviceKind.DOWN_TIR_WORSE
            else -> TddAdviceKind.DOWN_TIR_OK
        }
        return TddAdvice(kind, change * 100.0, tirChange, recent.size, base.size)
    }

    /** Sample standard deviation. */
    private fun sampleSd(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
    }

    /** Time above range of one day: what is left after TIR and TBR. */
    private fun tarOf(d: DayAgg): Double = (100.0 - d.tirPct - d.tbrPct).coerceAtLeast(0.0)

    /**
     * [WEEK_BLOCKS] blocks of [BLOCK_DAYS] days ending yesterday, oldest first. A block with
     * fewer than [MIN_BLOCK_TDD_DAYS] days with a TDD is null.
     */
    fun weekBlocks(all: List<DayAgg>, today: LocalDate, dose: List<DoseDay> = emptyList()): List<TddWeekBlock?> =
        (WEEK_BLOCKS - 1 downTo 0).map { k ->
            val from = 1 + k * BLOCK_DAYS
            val to = (k + 1) * BLOCK_DAYS
            val d = window(all, today, from, to)
            if (d.size < MIN_BLOCK_TDD_DAYS) null else {
                val tir = tirAverage(all, today, from, to)
                val r = periodResult(all, dose, today.minusDays(to.toLong()), today.minusDays(from.toLong()))
                TddWeekBlock(
                    start = today.minusDays(to.toLong()),
                    tdd = d.map { it.tdd }.average(),
                    tirPct = if (tir != null && tir.second >= MIN_BLOCK_TDD_DAYS) tir.first else Double.NaN,
                    tddDays = d.size,
                    tarPct = r?.tarPct ?: Double.NaN,
                    tbrPct = r?.tbrPct ?: Double.NaN,
                    fclSharePct = r?.fclSharePct ?: Double.NaN
                )
            }
        }

    /**
     * TDD per weekday over the last [WEEKDAY_WINDOW_DAYS] days, with a hint for a weekday that
     * is clearly and repeatedly different: at least [MIN_WEEKDAY_DAYS] days, the TDD at least
     * [WEEKDAY_CHANGE_LIMIT] away from the average, and more than two standard errors away
     * (so normal day-to-day wobble does not count). The hint looks at TAR (TDD higher) or TBR
     * (TDD lower) of that weekday against the average of all days with enough BG data.
     */
    fun weekdayOverview(all: List<DayAgg>, today: LocalDate): WeekdayOverview {
        val d = window(all, today, 1, WEEKDAY_WINDOW_DAYS)
        val mean = if (d.isEmpty()) Double.NaN else d.map { it.tdd }.average()
        val quality = d.filter { it.hours >= MIN_TIR_HOURS && !it.tbrPct.isNaN() }
        val meanTar = quality.map { tarOf(it) }.average()
        val meanTbr = quality.map { it.tbrPct }.average()
        val stats = DayOfWeek.values().map { dow ->
            val values = d.filter { it.date.dayOfWeek == dow }.map { it.tdd }
            if (values.size < 3) return@map WeekdayStat(dow, values.size, Double.NaN)
            val avg = values.average()
            val dev = avg - mean
            var hint: WeekdayHintKind? = null
            var diff = 0.0
            if (values.size >= MIN_WEEKDAY_DAYS && mean > 0.0 && !meanTar.isNaN()) {
                val se = sampleSd(values) / sqrt(values.size.toDouble())
                if (abs(dev) / mean >= WEEKDAY_CHANGE_LIMIT && abs(dev) > 2.0 * se) {
                    val q = quality.filter { it.date.dayOfWeek == dow }
                    if (q.size >= 4) {
                        val tarDiff = q.map { tarOf(it) }.average() - meanTar
                        val tbrDiff = q.map { it.tbrPct }.average() - meanTbr
                        if (dev > 0.0) {
                            hint = if (tarDiff >= TAR_LIMIT) WeekdayHintKind.UP_TAR_HIGHER else WeekdayHintKind.UP_TAR_OK
                            diff = tarDiff
                        } else {
                            hint = if (tbrDiff >= TBR_LIMIT) WeekdayHintKind.DOWN_TBR_HIGHER else WeekdayHintKind.DOWN_TBR_OK
                            diff = tbrDiff
                        }
                    }
                }
            }
            WeekdayStat(dow, values.size, avg, hint, if (mean > 0.0) dev / mean * 100.0 else 0.0, diff)
        }
        return WeekdayOverview(stats, mean, stats.any { it.days >= MIN_WEEKDAY_DAYS })
    }

    /** Days (from the first date to the last, both included) with enough data for the results. */
    fun periodResult(all: List<DayAgg>, dose: List<DoseDay>, first: LocalDate, last: LocalDate): PeriodResult? {
        val inRange = all.filter { !it.date.isBefore(first) && !it.date.isAfter(last) }
        val tddDays = inRange.filter { !it.tdd.isNaN() }
        if (tddDays.isEmpty()) return null
        val q = inRange.filter { it.hours >= MIN_TIR_HOURS && !it.tbrPct.isNaN() }
        val shares = dose.filter { !it.date.isBefore(first) && !it.date.isAfter(last) }
            .map { it.fclShare }.filter { !it.isNaN() }
        return PeriodResult(
            days = tddDays.size,
            tdd = tddDays.map { it.tdd }.average(),
            tirPct = if (q.isEmpty()) Double.NaN else q.map { it.tirPct }.average(),
            tarPct = if (q.isEmpty()) Double.NaN else q.map { tarOf(it) }.average(),
            tbrPct = if (q.isEmpty()) Double.NaN else q.map { it.tbrPct }.average(),
            fclSharePct = if (shares.isEmpty()) Double.NaN else shares.average() * 100.0
        )
    }

    private fun paramValue(d: DoseDay, kind: ParamKind): String = when (kind) {
        ParamKind.STERKTE -> d.sterktePct.toString()
        ParamKind.TIMING -> d.timingPct.toString()
        ParamKind.VOLHOUDENDHEID -> d.volhoudendheidPct.toString()
        ParamKind.NACHT_FACTOR -> d.nachtFactorPct.toString()
        ParamKind.AAPS_MULTIPLIER -> d.aapsMultiplierPct.toString()
        ParamKind.DISTRIBUTION_STYLE -> d.distributionStyle
        ParamKind.NIGHT_STYLE -> d.nightStyle
    }

    /**
     * Changes of the dosing settings in the last [CHANGE_LOOKBACK_DAYS] days, newest first, at
     * most [MAX_CHANGES]. A change counts when the value of a day differs from the day before,
     * the day before that was the same as the day before, and the next day keeps the new
     * value (so a one-day flicker is not a change). Today is never used, it is not complete.
     * This shows what happened around a change. It does not prove that the change caused it.
     */
    fun changeEffects(all: List<DayAgg>, dose: List<DoseDay>, today: LocalDate): List<ParamChange> {
        val byDate = dose.filter { it.cycles >= MIN_CYCLES_PER_DAY && it.date.isBefore(today) }
            .associateBy { it.date }
        val result = ArrayList<ParamChange>()
        val firstDay = today.minusDays(CHANGE_LOOKBACK_DAYS.toLong())
        for (kind in ParamKind.values()) {
            var d = firstDay
            while (d.isBefore(today)) {
                val cur = byDate[d]
                val prev = byDate[d.minusDays(1)]
                val prev2 = byDate[d.minusDays(2)]
                val next = byDate[d.plusDays(1)]
                if (cur != null && prev != null) {
                    val v = paramValue(cur, kind)
                    val pv = paramValue(prev, kind)
                    val steady = prev2 == null || paramValue(prev2, kind) == pv
                    val keeps = next == null || paramValue(next, kind) == v
                    if (v != pv && v.isNotEmpty() && pv.isNotEmpty() && steady && keeps) {
                        val before = periodResult(all, dose, d.minusDays(CHANGE_WINDOW.toLong()), d.minusDays(1))
                        val afterLast = d.plusDays(CHANGE_WINDOW.toLong()).let { if (it.isAfter(today.minusDays(1))) today.minusDays(1) else it }
                        val after = periodResult(all, dose, d.plusDays(1), afterLast)
                            ?.takeIf { it.days >= MIN_CHANGE_DAYS }
                        result.add(ParamChange(d, kind, pv, v,
                                               before?.takeIf { it.days >= MIN_CHANGE_DAYS }, after))
                    }
                }
                d = d.plusDays(1)
            }
        }
        return result.sortedByDescending { it.date }.take(MAX_CHANGES)
    }
}
