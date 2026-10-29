package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import app.aaps.plugins.aps.openAPSFCL.vnext.CommitGate
import app.aaps.plugins.aps.openAPSFCL.vnext.database.CycleTimingRow
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Timing of the insulin against the BG peak. It only measures and shows. It does not change any dose.
 *
 * Two things are measured per meal:
 *  1. The lag: minutes between the BG peak and the peak of the insulin activity. The activity is
 *     calculated from the insulin that was really given (above the profile basal), with an
 *     assumed insulin model ([ASSUMED_PEAK_MIN] minutes to the peak, [ASSUMED_DIA_MIN] minutes in total).
 *     A positive lag means the insulin peak comes after the BG peak (the insulin is late).
 *  2. The wait before the first commit: the cycles where the BG was already rising but the commit was
 *     not there yet, and which limit was closed first in each of these cycles ([Gate]).
 *
 * Pure calculation on rows of the cycle log, so it can be tested without Android.
 */
object InsulinTimingHistory {

    /** Assumed minutes from the dose to the peak of the insulin activity. */
    const val ASSUMED_PEAK_MIN = 45.0

    /** Assumed total action time of the insulin in minutes. */
    const val ASSUMED_DIA_MIN = 300.0

    /** Length of one step of the time grid in minutes. */
    const val STEP_MIN = 5

    /** A meal needs at least this rise (mmol/L) from the lowest point to the peak. */
    private const val MIN_RISE_MMOL = 2.0

    /** Meals that need more minutes than this to rise are slow rises and are skipped. */
    private const val MAX_RISE_MIN = 110

    /** The BG must fall at least this much (mmol/L) after the peak, so that it is a real peak. */
    private const val MIN_DROP_MMOL = 1.0

    /** Steps (5 min) to look back for the lowest point before a peak. */
    private const val LOOK_BACK_STEPS = 24

    /** Steps on each side in which the peak must be the highest point. */
    private const val PEAK_HALF_WINDOW = 12

    /** Steps after the peak in which the fall is measured. */
    private const val DROP_STEPS = 36

    /** Longest gap in steps that is filled by a straight line between two BG values. */
    private const val MAX_GAP_STEPS = 6

    /** The first steps of the data are skipped: the insulin before them is not known. */
    private const val WARMUP_STEPS = 36

    /** Lag in minutes from which the insulin counts as late. */
    const val LATE_LAG_MIN = 20.0

    /** Lag in minutes (plus or minus) that counts as "in step". */
    const val IN_STEP_LAG_MIN = 10.0

    /** A first commit is a commit of at least this size, with no such commit in the 90 minutes before it. */
    private const val FIRST_COMMIT_MIN_U = 0.5
    private const val FIRST_COMMIT_QUIET_MIN = 90L

    /** The BG counts as rising when the 5 minute rise is above this (mmol/L). */
    private const val RISING_DELTA_5M = 0.04

    /** Most cycles and minutes that are looked back from a first commit. */
    private const val MAX_WAIT_CYCLES = 8
    private const val MAX_WAIT_MIN = 40L

    /** The limit that was closed first in a cycle. */
    enum class Gate { MEAL_NOT_RECOGNIZED, ACCESS_LEVEL, COMMIT_PAUSE, DELTA_TO_TARGET, SLOW_RISE, ACCELERATION, OTHER }

    data class Episode(
        val peakMs: Long,
        val bgPeak: Double,
        val rise: Double,
        val riseMin: Int,
        /** Insulin above the basal, from just before the rise to a bit after the peak (U). */
        val insulinU: Double,
        /** Minutes from the BG peak to the insulin peak. Positive = the insulin peak is later. */
        val lagMin: Int
    )

    data class FirstCommitWait(
        val timeMs: Long,
        val waitCycles: Int,
        val waitMin: Int,
        val gates: List<Gate>
    )

    data class Result(val episodes: List<Episode>, val waits: List<FirstCommitWait>)

    data class Summary(
        val episodes: Int,
        val medianLagMin: Double,
        val p25LagMin: Double,
        val p75LagMin: Double,
        /** Share (0..1) of the meals with a lag above [LATE_LAG_MIN]. NaN without meals. */
        val lateShare: Double,
        val firstCommits: Int,
        val medianWaitMin: Double,
        val waitCycles: Int,
        val gateCounts: Map<Gate, Int>
    )

    data class WeekPoint(val lastDay: LocalDate, val episodes: Int, val medianLagMin: Double)

    // ── Calculation ──────────────────────────────────────────────────────────────

    /** Insulin activity per step (fraction of one unit) for the assumed insulin model. */
    internal fun activityKernel(peakMin: Double = ASSUMED_PEAK_MIN, diaMin: Double = ASSUMED_DIA_MIN): DoubleArray {
        val tau = peakMin * (1.0 - peakMin / diaMin) / (1.0 - 2.0 * peakMin / diaMin)
        val a = 2.0 * tau / diaMin
        val s = 1.0 / (1.0 - a + (1.0 + a) * exp(-diaMin / tau))
        val steps = (diaMin / STEP_MIN).toInt()
        return DoubleArray(steps + 1) { k ->
            val t = k.toDouble() * STEP_MIN
            s / (tau * tau) * t * (1.0 - t / diaMin) * exp(-t / tau) * STEP_MIN
        }
    }

    /** Median of the values, NaN when there are none. */
    internal fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val v = values.sorted()
        val mid = v.size / 2
        return if (v.size % 2 == 1) v[mid] else (v[mid - 1] + v[mid]) / 2.0
    }

    /** Value at fraction [p] (0..1) of the sorted values, NaN when there are none. */
    internal fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return Double.NaN
        val v = values.sorted()
        val pos = p * (v.size - 1)
        val lo = pos.toInt()
        val hi = minOf(lo + 1, v.size - 1)
        return v[lo] + (v[hi] - v[lo]) * (pos - lo)
    }

    /** Finds the meals and the waits before the first commits in the rows (sorted on time). */
    fun analyze(rows: List<CycleTimingRow>): Result {
        if (rows.size < 2) return Result(emptyList(), emptyList())
        return Result(findEpisodes(rows), findWaits(rows))
    }

    private fun findEpisodes(rows: List<CycleTimingRow>): List<Episode> {
        val stepMs = STEP_MIN * 60_000L
        val t0 = rows.first().timestampMs / stepMs
        val n = ((rows.last().timestampMs / stepMs) - t0).toInt() + 1
        if (n < WARMUP_STEPS + DROP_STEPS) return emptyList()

        val bgSum = DoubleArray(n)
        val bgCount = IntArray(n)
        val extra = DoubleArray(n)
        for (r in rows) {
            val i = ((r.timestampMs / stepMs) - t0).toInt()
            if (i < 0 || i >= n) continue
            bgSum[i] += r.bg
            bgCount[i]++
            val given = r.realDeliveredBolusU + r.realDeliveredBasalU
            val basal = r.profileBasalUH * STEP_MIN / 60.0
            extra[i] += (given - basal).coerceAtLeast(0.0)
        }
        val bg = DoubleArray(n) { if (bgCount[it] > 0) bgSum[it] / bgCount[it] else Double.NaN }
        fillGaps(bg)
        val sm = smooth(bg)

        val kernel = activityKernel()
        val activity = DoubleArray(n)
        for (i in 0 until n) {
            if (extra[i] <= 0.0) continue
            for (k in kernel.indices) {
                if (i + k >= n) break
                activity[i + k] += extra[i] * kernel[k]
            }
        }

        val episodes = ArrayList<Episode>()
        for (i in WARMUP_STEPS until n - PEAK_HALF_WINDOW) {
            val peak = sm[i]
            if (peak.isNaN() || !isLocalMax(sm, i)) continue
            val lo = maxOf(0, i - LOOK_BACK_STEPS)
            var j0 = -1
            // The last lowest point, so a flat start does not make the rise look longer.
            for (k in lo..i) {
                if (!sm[k].isNaN() && (j0 < 0 || sm[k] <= sm[j0])) j0 = k
            }
            if (j0 < 0) continue
            val rise = peak - sm[j0]
            val riseMin = (i - j0) * STEP_MIN
            if (rise < MIN_RISE_MMOL || riseMin >= MAX_RISE_MIN) continue
            if (j0 < WARMUP_STEPS) continue

            var lowest = peak
            var seen = 0
            for (k in i..minOf(n - 1, i + DROP_STEPS)) {
                if (!sm[k].isNaN()) { lowest = minOf(lowest, sm[k]); seen++ }
            }
            if (seen < PEAK_HALF_WINDOW || peak - lowest < MIN_DROP_MMOL) continue

            // The insulin peak is searched up to 1 hour after the BG peak. Later insulin belongs to the next meal.
            val from = maxOf(0, j0 - 6)
            val to = minOf(n - 1, i + PEAK_HALF_WINDOW)
            var best = from
            for (k in from..to) if (activity[k] > activity[best]) best = k
            var insulin = 0.0
            for (k in from..minOf(n - 1, i + PEAK_HALF_WINDOW)) insulin += extra[k]

            episodes.add(
                Episode(
                    peakMs = (t0 + i) * stepMs,
                    bgPeak = peak,
                    rise = rise,
                    riseMin = riseMin,
                    insulinU = insulin,
                    lagMin = (best - i) * STEP_MIN
                )
            )
        }
        return episodes
    }

    /** True when the point is the highest of the window around it (the first one when equal). */
    private fun isLocalMax(sm: DoubleArray, i: Int): Boolean {
        val lo = maxOf(0, i - PEAK_HALF_WINDOW)
        val hi = minOf(sm.size - 1, i + PEAK_HALF_WINDOW)
        for (k in lo until i) if (!sm[k].isNaN() && sm[k] >= sm[i]) return false
        for (k in i + 1..hi) if (!sm[k].isNaN() && sm[k] > sm[i]) return false
        return true
    }

    /** Fills short gaps with a straight line. Longer gaps stay NaN. */
    private fun fillGaps(bg: DoubleArray) {
        var i = 0
        while (i < bg.size) {
            if (!bg[i].isNaN()) { i++; continue }
            var j = i
            while (j < bg.size && bg[j].isNaN()) j++
            val gap = j - i
            if (i > 0 && j < bg.size && gap <= MAX_GAP_STEPS) {
                val a = bg[i - 1]
                val b = bg[j]
                for (k in i until j) bg[k] = a + (b - a) * (k - i + 1) / (gap + 1).toDouble()
            }
            i = j
        }
    }

    /** Mean of the point and its two neighbours, without the missing ones. */
    private fun smooth(bg: DoubleArray): DoubleArray = DoubleArray(bg.size) { i ->
        var sum = 0.0
        var cnt = 0
        for (k in maxOf(0, i - 1)..minOf(bg.size - 1, i + 1)) {
            if (!bg[k].isNaN()) { sum += bg[k]; cnt++ }
        }
        if (cnt == 0 || bg[i].isNaN()) Double.NaN else sum / cnt
    }

    private fun findWaits(rows: List<CycleTimingRow>): List<FirstCommitWait> {
        val result = ArrayList<FirstCommitWait>()
        var lastBigCommitMs = Long.MIN_VALUE
        for (i in rows.indices) {
            val r = rows[i]
            if (r.commitDoseRaw <= FIRST_COMMIT_MIN_U) continue
            val quiet = lastBigCommitMs == Long.MIN_VALUE ||
                r.timestampMs - lastBigCommitMs > FIRST_COMMIT_QUIET_MIN * 60_000L
            lastBigCommitMs = r.timestampMs
            if (!quiet) continue

            // Walk back over the cycles in which the BG was already rising.
            var j = i
            while (j > 0 &&
                i - j < MAX_WAIT_CYCLES &&
                rows[j - 1].recentDelta5m > RISING_DELTA_5M &&
                r.timestampMs - rows[j - 1].timestampMs <= MAX_WAIT_MIN * 60_000L
            ) j--
            val waiting = rows.subList(j, i)
            val waitMin = if (waiting.isEmpty()) 0 else ((r.timestampMs - waiting.first().timestampMs) / 60_000L).toInt()
            result.add(FirstCommitWait(r.timestampMs, waiting.size, waitMin, waiting.map { gateOf(it) }))
        }
        return result
    }

    /** The limit that was closed first in this cycle, in the order the algorithm checks them. */
    internal fun gateOf(r: CycleTimingRow): Gate = when {
        r.mealState == "NONE"                                  -> Gate.MEAL_NOT_RECOGNIZED
        r.doseAccess != "NORMAL"                               -> Gate.ACCESS_LEVEL
        !r.effectiveCommitAllowed                              -> Gate.COMMIT_PAUSE
        r.bg - r.target < CommitGate.MIN_DELTA_TO_TARGET       -> Gate.DELTA_TO_TARGET
        !CommitGate.fastLane(r.recentDelta5m, r.recentSlope)   -> Gate.SLOW_RISE
        !CommitGate.accelOk(r.accel, r.recentDelta5m)          -> Gate.ACCELERATION
        else                                                   -> Gate.OTHER
    }

    // ── Summary ──────────────────────────────────────────────────────────────────

    /** Summary of the meals and waits in the last [days] days before [today]. */
    fun summary(result: Result, today: LocalDate, days: Int, zone: ZoneId): Summary {
        val from = today.minusDays((days - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val episodes = result.episodes.filter { it.peakMs >= from }
        val waits = result.waits.filter { it.timeMs >= from }
        val lags = episodes.map { it.lagMin.toDouble() }
        val counts = HashMap<Gate, Int>()
        waits.forEach { w -> w.gates.forEach { g -> counts[g] = (counts[g] ?: 0) + 1 } }
        return Summary(
            episodes = episodes.size,
            medianLagMin = median(lags),
            p25LagMin = percentile(lags, 0.25),
            p75LagMin = percentile(lags, 0.75),
            lateShare = if (lags.isEmpty()) Double.NaN else lags.count { it > LATE_LAG_MIN }.toDouble() / lags.size,
            firstCommits = waits.size,
            medianWaitMin = median(waits.map { it.waitMin.toDouble() }),
            waitCycles = waits.sumOf { it.waitCycles },
            gateCounts = counts
        )
    }

    /** Median lag per week over the last [weeks] weeks, oldest week first. */
    fun weeklyLag(result: Result, today: LocalDate, weeks: Int, zone: ZoneId): List<WeekPoint> =
        (weeks - 1 downTo 0).map { w ->
            val last = today.minusDays(w * 7L)
            val first = last.minusDays(6)
            val fromMs = first.atStartOfDay(zone).toInstant().toEpochMilli()
            val toMs = last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val lags = result.episodes.filter { it.peakMs in fromMs until toMs }.map { it.lagMin.toDouble() }
            WeekPoint(last, lags.size, median(lags))
        }

    /** The gate that held back the most cycles (without [Gate.OTHER]), and its share of all waiting cycles. */
    fun topGate(s: Summary): Pair<Gate, Int>? {
        val total = s.gateCounts.values.sum()
        if (total <= 0) return null
        val top = s.gateCounts.filterKeys { it != Gate.OTHER }.maxByOrNull { it.value } ?: return null
        return top.key to (100.0 * top.value / total).roundToInt()
    }
}
