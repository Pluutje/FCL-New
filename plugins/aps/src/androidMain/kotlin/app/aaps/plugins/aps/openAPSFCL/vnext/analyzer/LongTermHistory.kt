package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import android.content.Context
import app.aaps.core.interfaces.db.PersistenceLayer
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
    val hours: Double
)

/** One chart point: the average of the stored days in one bucket. */
data class PeriodBucket(
    val meanMmol: Double,
    val pgr: Double,
    val gvp: Double,
    val tirPct: Double,
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
    private const val KEY_BACKFILL = "backfill_v1_done"
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
            DayAgg(LocalDate.parse(p[0]), p[1].toDouble(), p[2].toDouble(),
                   p[3].toDouble(), p[4].toDouble(), p[5].toDouble())
        } catch (_: Exception) { null }
    }

    private fun encodeLine(d: DayAgg): String =
        "${d.date}|${d.meanMmol}|${d.pgr}|${d.gvp}|${d.tirPct}|${d.hours}"

    // ── Writing ───────────────────────────────────────────────────────────

    @Synchronized
    private fun merge(context: Context, newDays: List<DayAgg>) {
        if (newDays.isEmpty()) return
        val map = load(context).associateBy { it.date }.toMutableMap()
        newDays.forEach { map[it.date] = it }
        val firstKeep = LocalDate.now(GvpCalculator.zone).minusDays(KEEP_DAYS)
        val text = map.values.filter { !it.date.isBefore(firstKeep) }
            .sortedBy { it.date }
            .joinToString("\n") { encodeLine(it) }
        prefs(context).edit().putString(KEY_DAYS, text).apply()
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
                   d.stats.tirPct, d.stats.coveredHours)
        }
    }

    // ── Background maintenance (called from the loop cycle) ───────────────

    /** Cheap when nothing is due: runs at most once per hour, on a background thread. */
    fun onCycle(context: Context, persistenceLayer: PersistenceLayer) {
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
                tirPct = g.map { it.tirPct }.average()
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
