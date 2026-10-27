package com.roadvision.roadsense.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "detection_events")
data class DetectionEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val plateText: String?,
    val confidence: Float,
    val uncertain: Boolean,
    val observedAtEpochMs: Long,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)
