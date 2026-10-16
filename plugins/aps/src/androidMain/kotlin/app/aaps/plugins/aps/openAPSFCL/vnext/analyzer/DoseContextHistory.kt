package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import android.content.Context
import app.aaps.plugins.aps.openAPSFCL.vnext.database.CycleDoseRow
import app.aaps.plugins.aps.openAPSFCL.vnext.database.FCLCycleLogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One stored day of dosing context, summed from the FCL cycle log. The cycle log is kept only
 * 90 days, this summary is kept 400 days (about 150 bytes per day), so later studies of
 * "settings against results" do not have to wait for new data.
 *
 * Whole-number settings hold the most used value of the day, so a change in the middle of a
 * day still shows as one clear value.
 */
data class DoseDay(
    val date: LocalDate,
    val cycles: Int,
    /** Units FCL commanded itself (sum of deliveredTotal). */
    val fclU: Double,
    /** Units really delivered by the pump, from any source (basal + bolus). */
    val realU: Double,
    /** Units of insulin from outside FCL (manual bolus or AAPS SMB). */
    val externalU: Double,
    val sterktePct: Int,
    val timingPct: Int,
    val volhoudendheidPct: Int,
    val nachtFactorPct: Int,
    /** AAPS multiplier x 100, rounded to steps of 5. */
    val aapsMultiplierPct: Int,
    val distributionStyle: String,
    val nightStyle: String,
    val aigfPct: Double,
    val aigfBPct: Double,
    val activityPct: Double,
    /** Percent of the cycles in which the max SMB limit cut the dose. */
    val maxSmbLimitedPct: Double,
    /** Percent of the cycles in which the IOB limit cut the dose. */
    val iobLimitedPct: Double,
    val avgIsf: Double,
    val avgIob: Double,
    /** Number of SMB / bolus deliveries by FCL and their total units. */
    val smbCount: Int,
    val smbU: Double,
    /**
     * All user settings and learned values as they were when this day was last refreshed,
     * as "name=value;name=value". Empty = not known: days that were filled from the old cycle
     * log (the log does not hold the settings). Kept as text so a new setting needs no change here.
     */
    val settings: String = "",
    /**
     * Profile ISF in mmol/L per unit, the average of the hourly samples of this day. Together
     * with [avgIsf] (what FCL really used) it gives the ISF correction of the day:
     * [avgIsf] / [profileIsf]. NaN = not known.
     */
    val profileIsf: Double = Double.NaN,
    val profileIsfSamples: Int = 0
) {
    /** Share of the delivered insulin that FCL commanded. NaN = not known. */
    val fclShare: Double
        get() = if (realU >= MIN_REAL_U) (fclU / realU).coerceIn(0.0, 1.0) else Double.NaN

    companion object {
        /** Under this many units of real delivery the share is not reliable. */
        const val MIN_REAL_U = 1.0
    }
}

/** Builds, stores and reads the [DoseDay] lines. */
object DoseContextHistory {

    private const val PREFS = "fcl_dose_context"
    private const val KEY_DAYS = "days_v1"
    private const val KEY_BACKFILL = "backfill_v1_done"
    private const val KEEP_DAYS = 400L
    private const val BACKFILL_DAYS = 90L
    private const val REFRESH_DAYS = 3L
    private const val CHUNK_DAYS = 5L
    private const val SEP = "|"

    @Volatile private var lastRunHourKey = -1L
    @Volatile private var running = false
    private val scope = CoroutineScope(Dispatchers.IO)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Reading ───────────────────────────────────────────────────────────

    fun load(context: Context): List<DoseDay> {
        val raw = prefs(context).getString(KEY_DAYS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { parse(it) }.sortedBy { it.date }
    }

    private fun parse(line: String): DoseDay? {
        val p = line.split(SEP)
        if (p.size < 21) return null
        return try {
            DoseDay(
                date = LocalDate.parse(p[0]), cycles = p[1].toInt(),
                fclU = p[2].toDouble(), realU = p[3].toDouble(), externalU = p[4].toDouble(),
                sterktePct = p[5].toInt(), timingPct = p[6].toInt(),
                volhoudendheidPct = p[7].toInt(), nachtFactorPct = p[8].toInt(),
                aapsMultiplierPct = p[9].toInt(),
                distributionStyle = p[10], nightStyle = p[11],
                aigfPct = p[12].toDouble(), aigfBPct = p[13].toDouble(),
                activityPct = p[14].toDouble(),
                maxSmbLimitedPct = p[15].toDouble(), iobLimitedPct = p[16].toDouble(),
                avgIsf = p[17].toDouble(), avgIob = p[18].toDouble(),
                smbCount = p[19].toInt(), smbU = p[20].toDouble(),
                settings = p.getOrNull(21) ?: "",
                profileIsf = p.getOrNull(22)?.toDouble() ?: Double.NaN,
                profileIsfSamples = p.getOrNull(23)?.toInt() ?: 0
            )
        } catch (_: Exception) { null }
    }

    private fun encode(d: DoseDay): String = listOf(
        d.date, d.cycles, d.fclU, d.realU, d.externalU,
        d.sterktePct, d.timingPct, d.volhoudendheidPct, d.nachtFactorPct, d.aapsMultiplierPct,
        d.distributionStyle, d.nightStyle, d.aigfPct, d.aigfBPct, d.activityPct,
        d.maxSmbLimitedPct, d.iobLimitedPct, d.avgIsf, d.avgIob, d.smbCount, d.smbU, d.settings, d.profileIsf, d.profileIsfSamples
    ).joinToString(SEP)

    // ── Calculation (no Android) ──────────────────────────────────────────

    private fun <T> mostUsed(values: List<T>, fallback: T): T =
        values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: fallback

    private fun avg(values: List<Double>): Double {
        val ok = values.filter { !it.isNaN() }
        return if (ok.isEmpty()) Double.NaN else ok.average()
    }

    /** Group cycle rows by calendar day. A day with no rows gives no line. */
    fun daysFromRows(rows: List<CycleDoseRow>, zone: ZoneId): List<DoseDay> =
        rows.groupBy { Instant.ofEpochMilli(it.timestampMs).atZone(zone).toLocalDate() }
            .map { (date, r) ->
                val smb = r.filter { it.bolus > 0.0 }
                DoseDay(
                    date = date,
                    cycles = r.size,
                    fclU = r.sumOf { it.deliveredTotal },
                    realU = r.sumOf { it.realDeliveredBasalU + it.realDeliveredBolusU },
                    externalU = r.sumOf { it.externalBolusU },
                    sterktePct = mostUsed(r.map { it.sterktePct }, 100),
                    timingPct = mostUsed(r.map { it.timingPct }, 100),
                    volhoudendheidPct = mostUsed(r.map { it.volhoudendheidPct }, 100),
                    nachtFactorPct = mostUsed(r.map { it.nachtFactorPct }, 100),
                    aapsMultiplierPct = mostUsed(r.map { Math.round(it.aapsMultiplier * 20.0) * 5 }, 100L).toInt(),
                    distributionStyle = mostUsed(r.map { it.doseDistributionStyle }, ""),
                    nightStyle = mostUsed(r.map { it.nightResponseStyle }, ""),
                    aigfPct = avg(r.map { it.aigfPct }),
                    aigfBPct = avg(r.map { it.aigfBPct }),
                    activityPct = avg(r.map { it.activityInsulinPct }),
                    maxSmbLimitedPct = r.count { it.guardMaxSmbLimited } * 100.0 / r.size,
                    iobLimitedPct = r.count { it.guardIobLimited } * 100.0 / r.size,
                    avgIsf = avg(r.map { it.effectiveISF }.filter { it > 0.0 }),
                    avgIob = avg(r.map { it.iob }),
                    smbCount = smb.size,
                    smbU = smb.sumOf { it.bolus }
                )
            }.sortedBy { it.date }

    // ── Writing ───────────────────────────────────────────────────────────

    @Synchronized
    private fun merge(context: Context, newDays: List<DoseDay>) {
        if (newDays.isEmpty()) return
        val map = load(context).associateBy { it.date }.toMutableMap()
        // A later refresh sees the whole day again, so it replaces the old line. The cycle log
        // keeps 90 days only: an older stored line is never replaced by a smaller one.
        newDays.forEach { n ->
            val old = map[n.date]
            // The cycle log has no user settings: keep the ones already stored for this day.
            if (old == null || n.cycles >= old.cycles) {
                map[n.date] = if (old != null && n.settings.isEmpty()) {
                    n.copy(settings = old.settings, profileIsf = old.profileIsf, profileIsfSamples = old.profileIsfSamples)
                } else n
            }
        }
        val firstKeep = LocalDate.now(GvpCalculator.zone).minusDays(KEEP_DAYS)
        val text = map.values.filter { !it.date.isBefore(firstKeep) }
            .sortedBy { it.date }.joinToString("\n") { encode(it) }
        prefs(context).edit().putString(KEY_DAYS, text).apply()
    }

    /** Write the settings of this moment on today's line (needs the line to exist already). */
    @Synchronized
    private fun stampSettings(context: Context, settings: Map<String, Any>, profileIsfMmol: Double, today: LocalDate) {
        val days = load(context)
        if (days.none { it.date == today }) return
        // No "|" or line break may get into a value: they split the stored line.
        val text = settings.entries.sortedBy { it.key }
            .joinToString(";") { "${it.key}=${it.value.toString().replace(Regex("[|;\\n\\r]"), " ")}" }
        val out = days.map {
            if (it.date != today) it else {
                // Running average of the hourly profile ISF samples (the profile ISF can change by time of day).
                val n = it.profileIsfSamples
                val isf = if (profileIsfMmol.isNaN() || profileIsfMmol <= 0.0) it.profileIsf
                else if (n == 0 || it.profileIsf.isNaN()) profileIsfMmol
                else (it.profileIsf * n + profileIsfMmol) / (n + 1)
                val count = if (profileIsfMmol.isNaN() || profileIsfMmol <= 0.0) n else n + 1
                it.copy(settings = text, profileIsf = isf, profileIsfSamples = count)
            }
        }
            .joinToString("\n") { encode(it) }
        prefs(context).edit().putString(KEY_DAYS, out).apply()
    }

    /** Cheap when nothing is due: runs at most once per hour, on a background thread. */
    fun onCycle(context: Context, repository: FCLCycleLogRepository, settings: Map<String, Any>, profileIsfMmol: Double) {
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
                // In small chunks: a 90 day backfill must not load all rows at once.
                while (to.isAfter(first)) {
                    val from = to.minusDays(CHUNK_DAYS).let { if (it.isBefore(first)) first else it }
                    val rows = repository.doseRows(
                        from.atStartOfDay(zone).toInstant().toEpochMilli(),
                        to.atStartOfDay(zone).toInstant().toEpochMilli()
                    )
                    merge(context, daysFromRows(rows, zone))
                    to = from
                }
                if (!done) prefs(context).edit().putBoolean(KEY_BACKFILL, true).apply()
                stampSettings(context, settings, profileIsfMmol, LocalDate.now(zone))
            } catch (_: Exception) {
                // try again next hour
            } finally {
                running = false
            }
        }
    }
}
