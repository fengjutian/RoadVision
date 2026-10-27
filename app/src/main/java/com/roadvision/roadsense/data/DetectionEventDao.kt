package com.roadvision.roadsense.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DetectionEventDao {
    @Query("SELECT * FROM detection_events ORDER BY observedAtEpochMs DESC")
    fun observeAll(): Flow<List<DetectionEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: DetectionEventEntity): Long

    @Delete
    suspend fun delete(event: DetectionEventEntity)

    @Query("DELETE FROM detection_events")
    suspend fun deleteAll()

    @Query("DELETE FROM detection_events WHERE createdAtEpochMs < :cutoffEpochMs")
    suspend fun deleteOlderThan(cutoffEpochMs: Long): Int
}
