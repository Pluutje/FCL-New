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
}