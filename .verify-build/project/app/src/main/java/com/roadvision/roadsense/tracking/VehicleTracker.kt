package com.roadvision.roadsense.tracking

import com.roadvision.roadsense.inference.Detection
import com.roadvision.roadsense.inference.NormalizedRect
import kotlin.math.max

data class TrackedVehicle(
    val trackId: Long,
    val detection: Detection,
    val ageFrames: Int,
    val missedFrames: Int,
)

class VehicleTracker(
    private val minimumIou: Float = 0.25f,
    private val maximumMissedFrames: Int = 8,
    private val maximumIdleNs: Long = 1_000_000_000L,
) {
    private data class MutableTrack(
        val id: Long,
        var detection: Detection,
        var age: Int = 1,
        var missed: Int = 0,
    )

    private val tracks = mutableListOf<MutableTrack>()
    private var nextId = 1L
    private var lastTimestampNs = Long.MIN_VALUE

    @Synchronized
    fun update(detections: List<Detection>, timestampNs: Long): List<TrackedVehicle> {
        require(timestampNs >= lastTimestampNs) { "Tracker timestamps must be monotonic" }
        lastTimestampNs = timestampNs
        tracks.removeAll { timestampNs - it.detection.timestampNs > maximumIdleNs }

        val unmatched = detections.toMutableSet()
        tracks.sortedByDescending { it.detection.confidence }.forEach { track ->
            val match = unmatched
                .filter { it.classId == track.detection.classId }
                .maxByOrNull { track.detection.box.iou(it.box) }
                ?.takeIf { track.detection.box.iou(it.box) >= minimumIou }
            if (match == null) {
                track.missed++
            } else {
                track.detection = match
                track.age++
                track.missed = 0
                unmatched.remove(match)
            }
        }
        tracks.removeAll { it.missed > maximumMissedFrames }
        unmatched.forEach { tracks += MutableTrack(nextId++, it) }
        return tracks.map { TrackedVehicle(it.id, it.detection, it.age, it.missed) }
    }

    @Synchronized
    fun reset() {
        tracks.clear()
        lastTimestampNs = Long.MIN_VALUE
    }
}
