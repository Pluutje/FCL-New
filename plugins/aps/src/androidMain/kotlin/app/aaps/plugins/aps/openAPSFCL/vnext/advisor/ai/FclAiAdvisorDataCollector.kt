package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai

import android.os.Environment
import app.aaps.core.keys.DoubleKey
import app.aaps.plugins.aps.openAPSFCL.vnext.FclSettingsStoreBridge
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFLearner
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * ============================================================================
 * FCL AI-Advisor — DataCollector
 * ============================================================================
 *
 * Builds the compact report (FclDailyReportPayload) from:
 *  - the settings the user can change (three sliders, max SMB, max IOB);
 *  - FCLvNext_LearnerLog_v1.csv   (what the learners decided in the period);
 *  - FCLvNext_Log_v<n>.csv        (5-minute cycle log, highest n; only for statistics: time in range,
 *                                   hypos, and which brake cut the dose most).
 *
 * No raw CSV rows go to the model, only the summary.
 */
object FclAiAdvisorDataCollector {

    private const val RELATIVE_PATH = "Documents/AAPS/ANALYSE"
    private const val LEARNER_LOG_FILE = "FCLvNext_LearnerLog_v1.csv"
    // The cycle log is written as FCLvNext_Log_v<schema number>.csv (FCLCycleLogRepository.kt) and the
    // number goes up with every new column. We do not hard-code it: we take the highest number in
    // the folder. (A fixed name was the bug: the advisor read an old v8 file that is not written
    // any more, so it saw no cycles at all.)
    private val CYCLE_LOG_NAME = Regex("""FCLvNext_Log_v(\d+)\.csv""")
    private const val MAX_NOTABLE_EPISODES = 5
    private const val MAX_HYPO_EPISODES = 5

    /** One row of the cycle log, only the columns the advisor needs. */
    private data class CycleRow(
        val ts: Instant, val bg: Double, val iobRatio: Double, val bolus: Double,
        val cutLayer: String, val minutesSinceMeal: Double
    )

    /** Default period. Settings need more evidence than one day. */
    const val DEFAULT_PERIOD_HOURS = 72

    private const val CUT_MAX_IOB = "IOB_HARD_CAP"
    private const val CUT_MAX_SMB = "MAX_SMB_CAP"

    private val isoFmt = DateTimeFormatter.ISO_INSTANT

    private fun analyseDir(): File =
        File(Environment.getExternalStorageDirectory(), RELATIVE_PATH)

    /**
     * @param context     Android context, needed to read the slider levels.
     * @param periodHours how many hours to look back.
     * @param metrics     episode metrics for time to peak, peak BG and drop after the peak.
     */
    fun collect(
        context: android.content.Context? = null,
        periodHours: Int = DEFAULT_PERIOD_HOURS,
        metrics: List<app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.EpisodeMetrics> = emptyList()
    ): FclDailyReportPayload {
        val dir = analyseDir()
        val nowUtc = Instant.now()
        val cutoff = nowUtc.minusSeconds(periodHours * 3600L)

        val settings = readSettings(context)

        val cycle = summariseCycleLog(findCycleLog(dir), cutoff)
        val learnerSummary = summariseLearnerLog(File(dir, LEARNER_LOG_FILE), cutoff)
        val episodeStats = summariseEpisodeMetrics(metrics, cutoff)

        return FclDailyReportPayload(
            dateUtc = isoFmt.format(nowUtc),
            periodHours = periodHours,
            timeInRangePct = cycle.timeInRangePct,
            timeAboveRangePct = cycle.timeAboveRangePct,
            timeBelowRangePct = cycle.timeBelowRangePct,
            hypoCount = cycle.hypoCount,
            hypoMinutesTotal = cycle.hypoMinutes,
            avgTimeToPeakMin = episodeStats.avgTimeToPeakMin,
            avgOvershootAfterPeakMmol = episodeStats.avgPostPeakDropMmol,
            avgPeakBgMmol = episodeStats.avgPeakBgMmol,
            episodeCount = episodeStats.count,
            activeParams = settings,
            learnerEventsSummary = learnerSummary,
            notableEpisodes = cycle.notableEpisodes,
            hypoEpisodes = cycle.hypoEpisodes,
            totalCycles = cycle.totalCycles,
            cyclesCutByMaxIob = cycle.cutByMaxIob,
            cyclesCutByMaxSmb = cycle.cutByMaxSmb,
            cyclesCutByOtherBrake = cycle.cutByOther
        )
    }

    // ── Current settings ──────────────────────────────────────────────────

    private fun readSettings(context: android.content.Context?): Map<String, ActiveParamSnapshot> {
        val result = LinkedHashMap<String, ActiveParamSnapshot>()
        if (context != null) {
            val levels = DFLearner.getAggLevels(context)
            fun slider(key: String, value: Int) {
                result[key] = ActiveParamSnapshot(active = value.toDouble(), default = 5.0, src = "setting")
            }
            slider(FclAiAdvisorRanges.KEY_STRENGTH, levels.strength)
            slider(FclAiAdvisorRanges.KEY_TIMING, levels.timing)
            slider(FclAiAdvisorRanges.KEY_PERSISTENCE, levels.persistence)
        }
        val store = FclSettingsStoreBridge.get()
        if (store != null) {
            val maxSmb = DoubleKey.max_bolus_day
            val maxIob = DoubleKey.fcl_vnext_MaxIOB
            result[FclAiAdvisorRanges.KEY_MAX_SMB] = ActiveParamSnapshot(
                active = store.getDouble(maxSmb.key, maxSmb.defaultValue), default = maxSmb.defaultValue, src = "setting"
            )
            result[FclAiAdvisorRanges.KEY_MAX_IOB] = ActiveParamSnapshot(
                active = store.getDouble(maxIob.key, maxIob.defaultValue), default = maxIob.defaultValue, src = "setting"
            )
        }
        return result
    }

    // ── EpisodeMetrics → time to peak / peak BG / drop after the peak ─────

    private data class EpisodeStats(
        val avgTimeToPeakMin: Double?,
        val avgPostPeakDropMmol: Double?,
        val avgPeakBgMmol: Double?,
        val count: Int
    )

    private fun summariseEpisodeMetrics(
        metrics: List<app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.EpisodeMetrics>,
        cutoff: Instant
    ): EpisodeStats {
        val recent = metrics.filter { it.start.isAfter(cutoff) }
        if (recent.isEmpty()) return EpisodeStats(null, null, null, 0)

        val timeToPeak = recent.mapNotNull { it.timeToPeakMinutes?.toDouble() }
        val avgTimeToPeak = if (timeToPeak.isNotEmpty()) timeToPeak.average() else null

        // Drop after the peak inside the post window: a sign of too much insulin around the peak.
        val postPeakDrops = recent.map { it.peakBg - it.minBgInWindow }
        val avgPostPeakDrop = if (postPeakDrops.isNotEmpty()) postPeakDrops.average() else null

        return EpisodeStats(avgTimeToPeak, avgPostPeakDrop, recent.map { it.peakBg }.average(), recent.size)
    }

    // ── FCLvNext_Log_v8.csv → time in range / hypo / brake statistics ──────

    private data class CycleSummary(
        val timeInRangePct: Double,
        val timeAboveRangePct: Double,
        val timeBelowRangePct: Double,
        val hypoCount: Int,
        val hypoMinutes: Int,
        val notableEpisodes: List<String>,
        val hypoEpisodes: List<String>,
        val totalCycles: Int,
        val cutByMaxIob: Int,
        val cutByMaxSmb: Int,
        val cutByOther: Int
    )

    /** The cycle log with the highest schema number in the folder, or null when there is none. */
    private fun findCycleLog(dir: File): File? =
        dir.listFiles()
            ?.mapNotNull { f -> CYCLE_LOG_NAME.matchEntire(f.name)?.let { f to it.groupValues[1].toInt() } }
            ?.maxByOrNull { it.second }
            ?.first

    private fun summariseCycleLog(file: File?, cutoff: Instant): CycleSummary {
        val empty = CycleSummary(0.0, 0.0, 0.0, 0, 0, emptyList(), emptyList(), 0, 0, 0, 0)
        if (file == null || !file.exists()) return empty

        val rows = mutableListOf<CycleRow>()
        // All columns are found by NAME in the header, so a new column in the log never breaks this.
        // A number can be written with a comma (Dutch phone) or a dot, so both are accepted.
        var header: List<String>? = null
        file.forEachLine { line ->
            val cols = line.split(";")
            if (header == null) {
                header = cols
                return@forEachLine
            }
            val h = header ?: return@forEachLine
            fun col(name: String): String? = h.indexOf(name).takeIf { it in cols.indices }?.let { cols[it].trim() }
            fun num(name: String): Double? = col(name)?.replace(',', '.')?.toDoubleOrNull()
            try {
                val ts = Instant.parse(col("ts_utc") ?: return@forEachLine)
                if (ts.isBefore(cutoff)) return@forEachLine
                rows += CycleRow(
                    ts = ts,
                    bg = num("bg_mmol") ?: return@forEachLine,
                    iobRatio = num("iob_ratio") ?: 0.0,
                    bolus = num("bolus") ?: 0.0,
                    cutLayer = col("top_cut_layer") ?: "",
                    minutesSinceMeal = num("minutes_since_meal_start") ?: -1.0
                )
            } catch (_: Exception) { /* skip a broken or incomplete line */ }
        }
        if (rows.isEmpty()) return empty

        // TIR: 3.9–10.0 mmol, a fixed clinical range (not target relative).
        val inRange = rows.count { it.bg in 3.9..10.0 }
        val tir = 100.0 * inRange / rows.size

        // Hypo episodes: runs of rows with bg < 3.9
        var hypoCount = 0
        var hypoMinutes = 0
        var inHypo = false
        val sorted = rows.sortedBy { it.ts }
        val hypoStarts = mutableListOf<Int>()
        for ((i, r) in sorted.withIndex()) {
            if (r.bg < 3.9) {
                if (!inHypo) { hypoCount++; inHypo = true; hypoStarts += i }
                hypoMinutes += 5 // a cycle is about 5 min; rough, enough for a summary
            } else {
                inHypo = false
            }
        }
        val above = 100.0 * rows.count { it.bg > 10.0 } / rows.size
        val below = 100.0 * rows.count { it.bg < 3.9 } / rows.size
        val hypoLines = hypoStarts.takeLast(MAX_HYPO_EPISODES).reversed().map { describeHypo(sorted, it) }

        // Notable cycles: bolus > 1.0 U while iobRatio was already >= 0.50
        val notable = rows.sortedByDescending { it.ts }
            .filter { it.bolus > 1.0 && it.iobRatio >= 0.50 }
            .take(MAX_NOTABLE_EPISODES)
            .map { "tijd=${it.ts} bg=${"%.1f".format(it.bg)} bolus=${"%.2f".format(it.bolus)}U iobRatio=${"%.2f".format(it.iobRatio)}" }

        val cutByMaxIob = rows.count { it.cutLayer == CUT_MAX_IOB }
        val cutByMaxSmb = rows.count { it.cutLayer == CUT_MAX_SMB }
        val cutByOther = rows.count {
            it.cutLayer.isNotBlank() && it.cutLayer != "NONE" && it.cutLayer != CUT_MAX_IOB && it.cutLayer != CUT_MAX_SMB
        }

        return CycleSummary(
            tir, above, below, hypoCount, hypoMinutes, notable, hypoLines, rows.size, cutByMaxIob, cutByMaxSmb, cutByOther
        )
    }

    /**
     * One line about the hypo that starts at [startIdx] in [sorted] (rows in time order): the lowest BG, the highest BG
     * in the 3 hours before, the BG change in the half hour before, the insulin of the 2 hours before and which brake
     * was active most in those 2 hours. This is what the advisor needs to tell "too much insulin" from "too late".
     */
    private fun describeHypo(sorted: List<CycleRow>, startIdx: Int): String {
        val start = sorted[startIdx]
        var lowest = start.bg
        var i = startIdx
        while (i < sorted.size && sorted[i].bg < 3.9) { lowest = minOf(lowest, sorted[i].bg); i++ }
        val lowMinutes = (i - startIdx) * 5
        val before2h = sorted.filter { it.ts.isBefore(start.ts) && it.ts.isAfter(start.ts.minusSeconds(2 * 3600L)) }
        val before3h = sorted.filter { it.ts.isBefore(start.ts) && it.ts.isAfter(start.ts.minusSeconds(3 * 3600L)) }
        val peak3h = before3h.maxOfOrNull { it.bg }
        val bgHalfHourAgo = sorted.lastOrNull { !it.ts.isAfter(start.ts.minusSeconds(30 * 60L)) }?.bg
        val change30 = bgHalfHourAgo?.let { start.bg - it }
        val bolus2h = before2h.sumOf { it.bolus }
        val maxIobRatio = before2h.maxOfOrNull { it.iobRatio }
        val topBrake = before2h.map { it.cutLayer }.filter { it.isNotBlank() && it != "NONE" }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val mealMin = before2h.maxOfOrNull { it.minutesSinceMeal }?.takeIf { it >= 0.0 }
        return "tijd=${start.ts} laagste=${"%.1f".format(lowest)} minutenOnder3.9=$lowMinutes" +
            " piekBg3uVoor=${peak3h?.let { "%.1f".format(it) } ?: "?"}" +
            " bgVerandering30minVoor=${change30?.let { "%+.1f".format(it) } ?: "?"}" +
            " bolus2uVoor=${"%.2f".format(bolus2h)}U" +
            " hoogsteIobRatio2uVoor=${maxIobRatio?.let { "%.2f".format(it) } ?: "?"}" +
            " maaltijdMinutenGeleden=${mealMin?.toInt() ?: "geen"}" +
            " vaakstRem2uVoor=${topBrake ?: "geen"}"
    }

    // ── FCLvNext_LearnerLog_v1.csv → short summary lines ───────────────────

    private fun summariseLearnerLog(file: File, cutoff: Instant): List<String> {
        if (!file.exists()) return emptyList()
        val lines = mutableListOf<String>()
        var header: List<String>? = null
        file.forEachLine { line ->
            val cols = line.split(";")
            if (header == null) { header = cols; return@forEachLine }
            val h = header ?: return@forEachLine
            val tsIdx = h.indexOf("ts_eval_utc")
            val typeIdx = h.indexOf("type")
            if (tsIdx < 0 || typeIdx < 0 || cols.size <= maxOf(tsIdx, typeIdx)) return@forEachLine
            val ts = try { Instant.parse(cols[tsIdx]) } catch (_: Exception) { return@forEachLine }
            if (ts.isBefore(cutoff)) return@forEachLine

            val blokIdx = h.indexOf("aanpassing_geblokt")
            val blocked = blokIdx in cols.indices && cols[blokIdx].isNotBlank()
            if (blocked) return@forEachLine // only real changes, no noise

            val summary = when (cols[typeIdx]) {
                "EPISODE" -> summariseField(h, cols, "old_d", "new_d")?.let { "D-factor (sterkte): $it" }
                "V" -> summariseField(h, cols, "v_old_extra", "v_new_extra")?.let { "V-extra (volhoudendheid): $it" }
                else -> null
            }
            if (summary != null) lines += "$ts: $summary"
        }
        return lines
    }

    private fun summariseField(header: List<String>, cols: List<String>, oldKey: String, newKey: String): String? {
        val oldIdx = header.indexOf(oldKey)
        val newIdx = header.indexOf(newKey)
        if (oldIdx < 0 || newIdx < 0 || cols.size <= maxOf(oldIdx, newIdx)) return null
        val oldVal = cols[oldIdx].toDoubleOrNull() ?: return null
        val newVal = cols[newIdx].toDoubleOrNull() ?: return null
        if (oldVal == newVal) return null
        return "${"%.3f".format(oldVal)} → ${"%.3f".format(newVal)}"
    }

    private fun String.toDoubleOrNull(): Double? = try { this.toDouble() } catch (_: Exception) { null }
}
