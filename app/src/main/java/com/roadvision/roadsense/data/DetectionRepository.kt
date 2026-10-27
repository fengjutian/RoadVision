package com.roadvision.roadsense.data

import kotlinx.coroutines.flow.Flow
import java.time.Duration

class DetectionRepository(private val dao: DetectionEventDao) {
    val events: Flow<List<DetectionEventEntity>> = dao.observeAll()

    suspend fun add(event: DetectionEventEntity) = dao.insert(event)
    suspend fun delete(event: DetectionEventEntity) = dao.delete(event)
    suspend fun deleteAll() = dao.deleteAll()
    suspend fun enforceRetention(retention: Duration = Duration.ofDays(30)): Int =
        dao.deleteOlderThan(System.currentTimeMillis() - retention.toMillis())
}
