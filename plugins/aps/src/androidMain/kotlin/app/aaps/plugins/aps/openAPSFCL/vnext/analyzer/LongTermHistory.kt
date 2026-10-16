package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import android.content.Context
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.stats.TddCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** One stored day: all values come from the glucose readings of that calendar day. */
data class DayAgg(
    val date: LocalDate,
    val meanMmol: Double,
    val pgr: Double,
    val gvp: Double,
    val tirPct: Double,
    val hours: Double,
    /** Total daily dose in units for a whole day. NaN = not known (day not filled yet). */
    val tdd: Double = Double.NaN,
    val tddBasal: Double = Double.NaN,
    val tddBolus: Double = Double.NaN,
    /** Time below range (< 4.0 mmol/L), percent of the readings. NaN = not known (old line). */
    val tbrPct: Double = Double.NaN
)

/** One chart point: the average of the stored days in one bucket. */
data class PeriodBucket(
    val meanMmol: Double,
    val pgr: Double,
    val gvp: Double,
    val tirPct: Double,
    /** Average TDD of the days in this bucket that have one. NaN = no day with TDD. */
    val tdd: Double = Double.NaN,
    /** Year view only: position in time (0 = oldest month, 11 = newest). Used to break the line. */
    val order: Int = 0
)

/** Period of the overview charts. [lineWindow] = number of buckets in the average line. */
enum class OverviewPeriod(
    val label: String,
    val lineWindow: Int,
    val legend: String
) {
    D14("14 d", 14, "stip = dag, lijn = 14-daags gemiddelde"),
    D30("30 d", 3, "stip = 2 dagen, lijn = gemiddelde van 3 punten"),
    D90("90 d", 3, "stip = week, lijn = gemiddelde van 3 punten"),
    Y1("1 jaar", 1, "stip = maand, vaste x-as: 1 = januari t/m 12 = december")
}

/**
 * Long-term store with one line per day (kept 400 days), filled from the AAPS glucose
 * database (kept about 186 days). Only BG is needed: mean, PGR, GVP and TIR are all
 * calculated from the glucose readings. The first run fills all days AAPS still has;
 * after that the last 3 days are refreshed every hour.
 */
object LongTermHistory {

    private const val PREFS = "fcl_long_term"
    private const val KEY_DAYS = "days_v1"
    // v2: the day lines now also hold TBR, so the BG lines are calculated once more.
    private const val KEY_BACKFILL = "backfill_v2_done"
    private const val KEY_TDD_BACKFILL = "tdd_backfill_v1_done"
    /** Under this many units a day is treated as missing data. */
    private const val MIN_VALID_TDD = 1.0
    private const val KEEP_DAYS = 400L
    private const val BACKFILL_DAYS = 185L
    private const val REFRESH_DAYS = 3L

    @Volatile private var lastRunHourKey = -1L
    @Volatile private var running = false
    private val scope = CoroutineScope(Dispatchers.IO)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Reading ───────────────────────────────────────────────────────────

    fun load(context: Context): List<DayAgg> {
        val raw = prefs(context).getString(KEY_DAYS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { parseLine(it) }.sortedBy { it.date }
    }

    private fun parseLine(line: String): DayAgg? {
        val p = line.split("|")
        if (p.size < 6) return null
        return try {
            // Old lines have 6 fields. Lines with TDD have 3 more (total, basal, bolus).
            // Lines with TBR have 1 more.
            val hasTdd = p.size >= 9
            DayAgg(LocalDate.parse(p[0]), p[1].toDouble(), p[2].toDouble(),
                   p[3].toDouble(), p[4].toDouble(), p[5].toDouble(),
                   if (hasTdd) p[6].toDouble() else Double.NaN,
                   if (hasTdd) p[7].toDouble() else Double.NaN,
                   if (hasTdd) p[8].toDouble() else Double.NaN,
                   if (p.size >= 10) p[9].toDouble() else Double.NaN)
        } catch (_: Exception) { null }
    }

    private fun encodeLine(d: DayAgg): String =
        "${d.date}|${d.meanMmol}|${d.pgr}|${d.gvp}|${d.tirPct}|${d.hours}" +
            "|${d.tdd}|${d.tddBasal}|${d.tddBolus}|${d.tbrPct}"

    // ── Writing ───────────────────────────────────────────────────────────

    @Synchronized
    private fun merge(context: Context, newDays: List<DayAgg>) {
        if (newDays.isEmpty()) return
        val map = load(context).associateBy { it.date }.toMutableMap()
        newDays.forEach { nieuw ->
            // The BG refresh knows nothing about TDD: keep the TDD that is already stored.
            val oud = map[nieuw.date]
            map[nieuw.date] = if (oud != null && nieuw.tdd.isNaN()) {
                nieuw.copy(tdd = oud.tdd, tddBasal = oud.tddBasal, tddBolus = oud.tddBolus)
            } else nieuw
        }
        write(context, map)
    }

    /** Add TDD to days that are already stored (a day needs its BG line first). */
    @Synchronized
    private fun mergeTdd(context: Context, tdds: Map<LocalDate, TddDay>) {
        if (tdds.isEmpty()) return
        val map = load(context).associateBy { it.date }.toMutableMap()
        tdds.forEach { (date, t) ->
            val d = map[date] ?: return@forEach
            map[date] = d.copy(tdd = t.total, tddBasal = t.basal, tddBolus = t.bolus)
        }
        write(context, map)
    }

    private fun write(context: Context, map: Map<LocalDate, DayAgg>) {
        val firstKeep = LocalDate.now(GvpCalculator.zone).minusDays(KEEP_DAYS)
        val text = map.values.filter { !it.date.isBefore(firstKeep) }
            .sortedBy { it.date }
            .joinToString("\n") { encodeLine(it) }
        prefs(context).edit().putString(KEY_DAYS, text).apply()
    }

    /** TDD of one whole day, in units. */
    private data class TddDay(val total: Double, val basal: Double, val bolus: Double)

    /**
     * Read whole-day TDD from AAPS. The first time we go back [BACKFILL_DAYS] days (in blocks
     * of 30 days, so one slow block does not stop the rest); after that only the last
     * [REFRESH_DAYS] days. Today is never included, a day must be complete.
     */
    private suspend fun readTdd(tddCalculator: TddCalculator, backfill: Boolean): Map<LocalDate, TddDay> {
        val result = HashMap<LocalDate, TddDay>()
        val today = LocalDate.now(GvpCalculator.zone)
        val blocks = if (backfill) ((BACKFILL_DAYS + 29) / 30).toInt() else 1
        for (k in 0 until blocks) {
            val days = if (backfill) 30L else REFRESH_DAYS
            val endMs = today.minusDays(k * 30L).atStartOfDay(GvpCalculator.zone)
                .toInstant().toEpochMilli()
            val part = try {
                tddCalculator.calculate(endMs, days, true)
            } catch (_: Exception) { null } ?: continue
            for (i in 0 until part.size()) {
                val t = part.valueAt(i)
                val total = if (t.totalAmount > 0.0) t.totalAmount else t.basalAmount + t.bolusAmount
                // A day with (almost) no insulin means missing data, not a real day.
                if (total < MIN_VALID_TDD) continue
                val date = Instant.ofEpochMilli(part.keyAt(i)).atZone(GvpCalculator.zone).toLocalDate()
                result[date] = TddDay(total, t.basalAmount, t.bolusAmount)
            }
        }
        return result
    }

    /** Calculate the day lines from raw readings (timestampMs, mmol). */
    fun daysFromReadings(readings: List<Pair<Long, Double>>): List<DayAgg> {
        val stats = GvpCalculator.dailyStatsFromReadings(readings)
        if (stats.isEmpty()) return emptyList()
        val bgByDay = readings.filter { it.second > 0.0 }
            .groupBy { Instant.ofEpochMilli(it.first).atZone(GvpCalculator.zone).toLocalDate() }
        return stats.mapNotNull { d ->
            val bg = bgByDay[d.date]?.map { it.second } ?: return@mapNotNull null
            val score = CgpScoreCalculator.calculateFromBg(bg) ?: return@mapNotNull null
            DayAgg(d.date, d.stats.meanMmol, score.pgr, d.stats.gvpPct,
                   d.stats.tirPct, d.stats.coveredHours, tbrPct = d.stats.tbrPct)
        }
    }

    // ── Background maintenance (called from the loop cycle) ───────────────

    /** Cheap when nothing is due: runs at most once per hour, on a background thread. */
    fun onCycle(context: Context, persistenceLayer: PersistenceLayer, tddCalculator: TddCalculator) {
        val hourKey = System.currentTimeMillis() / 3_600_000L
        if (hourKey == lastRunHourKey || running) return
        lastRunHourKey = hourKey
        running = true
        scope.launch {
            try {
                val done = prefs(context).getBoolean(KEY_BACKFILL, false)
                val days = if (done) REFRESH_DAYS else BACKFILL_DAYS
                val today = LocalDate.now(GvpCalculator.zone)
                val startMs = today.minusDays(days).atStartOfDay(GvpCalculator.zone)
                    .toInstant().toEpochMilli()
                val endMs = System.currentTimeMillis()
                val readings = persistenceLayer
                    .getBgReadingsDataFromTimeToTime(startMs, endMs, true)
                    .filter { it.isValid && it.value > 0.0 }
                    .map { it.timestamp to it.value / 18.0 }
                merge(context, daysFromReadings(readings))
                if (!done && readings.isNotEmpty()) {
                    prefs(context).edit().putBoolean(KEY_BACKFILL, true).apply()
                }
                // TDD after the BG lines: a TDD needs its day line to exist.
                val tddDone = prefs(context).getBoolean(KEY_TDD_BACKFILL, false)
                val tdds = readTdd(tddCalculator, backfill = !tddDone)
                mergeTdd(context, tdds)
                if (!tddDone && tdds.isNotEmpty()) {
                    prefs(context).edit().putBoolean(KEY_TDD_BACKFILL, true).apply()
                }
            } catch (_: Exception) {
                // try again next hour
            } finally {
                running = false
            }
        }
    }

    // ── Buckets for the charts ────────────────────────────────────────────

    /** One entry per chart point; null = no data in that bucket. */
    fun buckets(all: List<DayAgg>, period: OverviewPeriod): List<PeriodBucket?> {
        val today = LocalDate.now(GvpCalculator.zone)
        val first: LocalDate
        val n: Int
        val idxOf: (LocalDate) -> Int
        when (period) {
            OverviewPeriod.D14 -> {
                first = today.minusDays(13); n = 14
                idxOf = { ChronoUnit.DAYS.between(first, it).toInt() }
            }
            OverviewPeriod.D30 -> {
                first = today.minusDays(29); n = 15
                idxOf = { (ChronoUnit.DAYS.between(first, it) / 2).toInt() }
            }
            OverviewPeriod.D90 -> {
                first = today.minusDays(90); n = 13
                idxOf = { (ChronoUnit.DAYS.between(first, it) / 7).toInt() }
            }
            OverviewPeriod.Y1 -> {
                first = today.withDayOfMonth(1).minusMonths(11); n = 12
                idxOf = { ChronoUnit.MONTHS.between(first, it.withDayOfMonth(1)).toInt() }
            }
        }
        val groups = Array(n) { ArrayList<DayAgg>() }
        all.filter { !it.date.isBefore(first) && !it.date.isAfter(today) }
            .forEach { d -> idxOf(d.date).let { if (it in 0 until n) groups[it].add(d) } }
        val result = groups.map { g ->
            if (g.isEmpty()) null
            else PeriodBucket(
                meanMmol = g.map { it.meanMmol }.average(),
                pgr = g.map { it.pgr }.average(),
                gvp = g.map { it.gvp }.average(),
                tirPct = g.map { it.tirPct }.average(),
                tdd = g.map { it.tdd }.filter { !it.isNaN() }.let { if (it.isEmpty()) Double.NaN else it.average() }
            )
        }
        if (period != OverviewPeriod.Y1) return result
        // Year view: fixed x axis, slot 0 = January ... slot 11 = December. Each month is placed
        // on its own calendar position, so the seasons can be compared. The last 12 months fill
        // the 12 slots (every month of the year occurs exactly once in 12 months).
        val slots = arrayOfNulls<PeriodBucket>(12)
        result.forEachIndexed { i, b ->
            if (b != null) slots[first.plusMonths(i.toLong()).monthValue - 1] = b.copy(order = i)
        }
        return slots.toList()
    }

    /** Average over the last [window] buckets (nulls skipped); null where the bucket itself is empty. */
    fun averageLine(values: List<Double?>, window: Int): List<Double?> =
        values.indices.map { i ->
            if (values[i] == null) null
            else values.subList(maxOf(0, i - window + 1), i + 1).filterNotNull().average()
        }
}
