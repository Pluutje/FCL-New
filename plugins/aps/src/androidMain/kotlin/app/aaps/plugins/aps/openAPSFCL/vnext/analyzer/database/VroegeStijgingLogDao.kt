package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Dao voor vroege_stijging_log — zelfde eenvoudige patroon als
 * PostHypoBrakeLogDao/ExplosiveRiseLogDao/TempOverrideLogDao. Zie kdoc bij
 * VroegeStijgingLogEntity voor de aanleiding (18/09/2026).
 */
@Dao
interface VroegeStijgingLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: VroegeStijgingLogEntity): Long

    @Query("SELECT * FROM vroege_stijging_log WHERE timestampMs >= :sinceMs ORDER BY timestampMs ASC")
    suspend fun getSince(sinceMs: Long): List<VroegeStijgingLogEntity>

    @Query("DELETE FROM vroege_stijging_log WHERE timestampMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)
}
