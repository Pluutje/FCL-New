package app.aaps.plugins.aps.openAPSFCL.vnext.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FCLCycleLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FCLCycleLogEntity)

    @Query("SELECT * FROM fcl_cycle_log ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<FCLCycleLogEntity>

    @Query("SELECT * FROM fcl_cycle_log WHERE timestampMs >= :fromMs ORDER BY timestampMs ASC")
    suspend fun getSince(fromMs: Long): List<FCLCycleLogEntity>

    @Query("SELECT * FROM fcl_cycle_log WHERE timestampMs >= :fromMs AND timestampMs <= :toMs ORDER BY timestampMs ASC")
    suspend fun getInRange(fromMs: Long, toMs: Long): List<FCLCycleLogEntity>

    @Query("SELECT COUNT(*) FROM fcl_cycle_log")
    suspend fun count(): Int

    @Query("DELETE FROM fcl_cycle_log WHERE timestampMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)

    @Query("SELECT * FROM fcl_cycle_log ORDER BY timestampMs ASC")
    suspend fun getAll(): List<FCLCycleLogEntity>

    // 21/09/2026 (de gebruiker) — laatste cyclus met een echte afgifte (deliveredTotal, de
    // TOTALE dosis: basaal-over-cyclus + SMB samen), voor de "laatste dosis"-tekst op
    // FclOverviewScreen.kt. Bewust NIET de laatste rij ongeacht deliveredTotal (getRecent(1)) —
    // de meeste cycli leveren 0 af, dus die zou meestal een lege/oude cyclus teruggeven i.p.v.
    // de laatste echte dosis.
    @Query("SELECT * FROM fcl_cycle_log WHERE deliveredTotal > 0 ORDER BY timestampMs DESC LIMIT 1")
    suspend fun getLastDelivery(): FCLCycleLogEntity?

    // 22/09/2026 (de gebruiker) — zelfde WHERE deliveredTotal > 0-filter als getLastDelivery(),
    // maar dan de laatste N i.p.v. alleen de nieuwste — voor het "laatste doseringen"-lijstje
    // (popup bij een klik op de laatste-dosis-tekst) op FclOverviewScreen.kt.
    @Query("SELECT * FROM fcl_cycle_log WHERE deliveredTotal > 0 ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun getRecentDeliveries(limit: Int): List<FCLCycleLogEntity>

    // Slim rows for the daily dose summary (analyzer/DoseContextHistory). Only the columns it
    // needs, so a 90 day backfill does not load the wide entity.
    @Query(
        "SELECT timestampMs, sterktePct, timingPct, volhoudendheidPct, nachtFactorPct, " +
            "doseDistributionStyle, nightResponseStyle, deliveredTotal, realDeliveredBasalU, " +
            "realDeliveredBolusU, externalBolusU, aapsMultiplier, aigfPct, aigfBPct, " +
            "activityInsulinPct, guardMaxSmbLimited, guardIobLimited, effectiveISF, iob, bolus " +
            "FROM fcl_cycle_log WHERE timestampMs >= :fromMs AND timestampMs < :toMs " +
            "ORDER BY timestampMs ASC"
    )
    suspend fun getDoseRows(fromMs: Long, toMs: Long): List<CycleDoseRow>

    // Slim rows for the measured IOB cover factor (analyzer/CoverIsfHistory).
    @Query(
        "SELECT timestampMs, bg, target, iob, effectiveISF, recentSlope, deliveredTotal, commitDoseFinal, codeVersion " +
            "FROM fcl_cycle_log WHERE timestampMs >= :fromMs AND timestampMs < :toMs " +
            "ORDER BY timestampMs ASC"
    )
    suspend fun getCoverRows(fromMs: Long, toMs: Long): List<CycleCoverRow>

    // Slim rows to measure the timing of the insulin against the BG peak (analyzer/InsulinTimingHistory).
    @Query(
        "SELECT timestampMs, bg, realDeliveredBolusU, realDeliveredBasalU, profileBasalUH, mealState, " +
            "doseAccess, consistency, effectiveCommitAllowed, commitDoseRaw, accel, recentSlope, recentDelta5m, " +
            "target " +
            "FROM fcl_cycle_log WHERE timestampMs >= :fromMs AND timestampMs < :toMs " +
            "ORDER BY timestampMs ASC"
    )
    suspend fun getTimingRows(fromMs: Long, toMs: Long): List<CycleTimingRow>
}

/** Columns of [FCLCycleLogEntity] used to measure the insulin timing. Names match the columns. */
data class CycleTimingRow(
    val timestampMs: Long,
    val bg: Double,
    val realDeliveredBolusU: Double,
    val realDeliveredBasalU: Double,
    val profileBasalUH: Double,
    val mealState: String,
    val doseAccess: String,
    val consistency: Double,
    val effectiveCommitAllowed: Boolean,
    val commitDoseRaw: Double,
    val accel: Double,
    val recentSlope: Double,
    val recentDelta5m: Double,
    val target: Double
)

/** Columns of [FCLCycleLogEntity] used to measure the IOB cover factor. Names match the columns. */
data class CycleCoverRow(
    val timestampMs: Long,
    val bg: Double,
    val target: Double,
    val iob: Double,
    val effectiveISF: Double,
    val recentSlope: Double,
    val deliveredTotal: Double,
    val commitDoseFinal: Double,
    val codeVersion: String
)

/** Columns of [FCLCycleLogEntity] used for the daily dose summary. Names match the columns. */
data class CycleDoseRow(
    val timestampMs: Long,
    val sterktePct: Int,
    val timingPct: Int,
    val volhoudendheidPct: Int,
    val nachtFactorPct: Int,
    val doseDistributionStyle: String,
    val nightResponseStyle: String,
    val deliveredTotal: Double,
    val realDeliveredBasalU: Double,
    val realDeliveredBolusU: Double,
    val externalBolusU: Double,
    val aapsMultiplier: Double,
    val aigfPct: Double,
    val aigfBPct: Double,
    val activityInsulinPct: Double,
    val guardMaxSmbLimited: Boolean,
    val guardIobLimited: Boolean,
    val effectiveISF: Double,
    val iob: Double,
    val bolus: Double
)
