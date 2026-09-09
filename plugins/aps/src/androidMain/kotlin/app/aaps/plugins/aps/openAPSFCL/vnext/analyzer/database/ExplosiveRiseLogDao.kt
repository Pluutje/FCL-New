package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Dao voor explosive_rise_log — zelfde eenvoudige patroon als
 * PostHypoBrakeLogDao. Zie kdoc bij ExplosiveRiseLogEntity voor de
 * aanleiding (3-4/9/2026).
 */
@Dao
interface ExplosiveRiseLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: ExplosiveRiseLogEntity): Long

    @Query("SELECT * FROM explosive_rise_log WHERE timestampMs >= :sinceMs ORDER BY timestampMs ASC")
    suspend fun getSince(sinceMs: Long): List<ExplosiveRiseLogEntity>

    @Query("SELECT * FROM explosive_rise_log ORDER BY timestampMs DESC LIMIT 1")
    suspend fun getLatest(): ExplosiveRiseLogEntity?

    @Query("DELETE FROM explosive_rise_log WHERE timestampMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)
}
