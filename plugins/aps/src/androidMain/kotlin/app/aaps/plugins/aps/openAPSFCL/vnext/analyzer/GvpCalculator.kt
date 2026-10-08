package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Result of one GVP calculation (one window or one day).
 *
 * @property gvpPct Glycemic Variability Percentage. 0 = flat line.
 * @property magMmolPerHour Mean Absolute Glucose change, in mmol/L per hour.
 * @property meanMmol Mean glucose in mmol/L.
 * @property tirPct Time in range (4.0 - 10.0 mmol/L), percent of the readings.
 * @property tbrPct Time below range (< 4.0 mmol/L), percent of the readings.
 * @property coveredHours Hours of data that were used (gaps are not counted).
 */
data class GvpStats(
    val gvpPct: Double,
    val magMmolPerHour: Double,
    val meanMmol: Double,
    val tirPct: Double,
    val tbrPct: Double,
    val coveredHours: Double
)

data class DailyGvp(val date: LocalDate, val stats: GvpStats)

/**
 * GVP / MAG calculation on the FCLvNext cycle log (the same data as all other
 * statistics on the Statistics tab).
 *
 * GVP (Hill 2018): length of the glucose curve (time axis included) compared
 * with the length of a flat line. Per step: sqrt(dt^2 + dy^2), dt in minutes,
 * dy in mg/dL. GVP = (sum of lengths / sum of dt - 1) * 100.
 * MAG: sum of |dy| divided by the hours covered.
 * Steps longer than MAX_GAP_MIN minutes are skipped (sensor gap).
 */
object GvpCalculator {

    private const val MAX_GAP_MIN = 15.0
    private const val MMOL_TO_MGDL = 18.0
    private const val LOW_MMOL = 4.0
    private const val HIGH_MMOL = 10.0

    /** Minimum covered time for a window (2 hours) */
    private const val MIN_COVERED_MIN_WINDOW = 120.0

    /** Minimum covered time for one day point (6 hours) */
    private const val MIN_COVERED_MIN_DAY = 360.0

    private val zone: ZoneId = ZoneId.of("Europe/Amsterdam")

    private data class Point(val tsMs: Long, val mmol: Double)

    private fun toPoints(rows: List<LogRow>): List<Point> =
        rows.filter { it.bg > 0.0 }
            .map { Point(it.timestamp.toEpochMilli(), it.bg) }
            .sortedBy { it.tsMs }

    /** Stats over the last [days] calendar days, today included. */
    fun windowStats(rows: List<LogRow>, days: Int, now: Instant = Instant.now()): GvpStats? {
        val today = now.atZone(zone).toLocalDate()
        val startMs = today.minusDays(days.toLong() - 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val points = toPoints(rows).filter { it.tsMs in startMs until endMs }
        return compute(points, MIN_COVERED_MIN_WINDOW)
    }

    /** One stats value per calendar day for the last [days] days (days without enough data are left out). */
    fun dailyStats(rows: List<LogRow>, days: Int, now: Instant = Instant.now()): List<DailyGvp> {
        val today = now.atZone(zone).toLocalDate()
        val firstDay = today.minusDays(days.toLong() - 1)
        val byDay = toPoints(rows).groupBy { Instant.ofEpochMilli(it.tsMs).atZone(zone).toLocalDate() }
        val result = ArrayList<DailyGvp>()
        var day = firstDay
        while (!day.isAfter(today)) {
            val points = byDay[day]
            if (points != null) {
                val stats = compute(points, MIN_COVERED_MIN_DAY)
                if (stats != null) result.add(DailyGvp(day, stats))
            }
            day = day.plusDays(1)
        }
        return result
    }

    /** Rolling average over the last [window] values (parallel to the input list). */
    fun rollingAverage(values: List<Double>, window: Int = 14): List<Double> =
        values.indices.map { i ->
            values.subList(maxOf(0, i - window + 1), i + 1).average()
        }

    private fun compute(points: List<Point>, minCoveredMin: Double): GvpStats? {
        if (points.size < 2) return null
        var lengthSum = 0.0
        var dtSum = 0.0
        var absDySum = 0.0
        for (i in 1 until points.size) {
            val dtMin = (points[i].tsMs - points[i - 1].tsMs) / 60_000.0
            if (dtMin <= 0.0 || dtMin > MAX_GAP_MIN) continue
            val dyMgdl = (points[i].mmol - points[i - 1].mmol) * MMOL_TO_MGDL
            lengthSum += sqrt(dtMin * dtMin + dyMgdl * dyMgdl)
            dtSum += dtMin
            absDySum += abs(points[i].mmol - points[i - 1].mmol)
        }
        if (dtSum < minCoveredMin) return null
        val n = points.size.toDouble()
        return GvpStats(
            gvpPct = (lengthSum / dtSum - 1.0) * 100.0,
            magMmolPerHour = absDySum / (dtSum / 60.0),
            meanMmol = points.sumOf { it.mmol } / n,
            tirPct = points.count { it.mmol in LOW_MMOL..HIGH_MMOL } / n * 100.0,
            tbrPct = points.count { it.mmol < LOW_MMOL } / n * 100.0,
            coveredHours = dtSum / 60.0
        )
    }
}
