package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Dao voor temp_override_log — zelfde eenvoudige patroon als
 * PostHypoBrakeLogDao/ExplosiveRiseLogDao. Zie kdoc bij TempOverrideLogEntity
 * voor de aanleiding (11/09/2026).
 */
@Dao
interface TempOverrideLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: TempOverrideLogEntity): Long

    @Query("SELECT * FROM temp_override_log WHERE timestampMs >= :sinceMs ORDER BY timestampMs ASC")
    suspend fun getSince(sinceMs: Long): List<TempOverrideLogEntity>

    @Query("SELECT * FROM temp_override_log ORDER BY timestampMs DESC LIMIT 1")
    suspend fun getLatest(): TempOverrideLogEntity?

    @Query("DELETE FROM temp_override_log WHERE timestampMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)
}
