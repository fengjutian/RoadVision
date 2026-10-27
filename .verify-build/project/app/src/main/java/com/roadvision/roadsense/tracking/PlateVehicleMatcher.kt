package com.roadvision.roadsense.tracking

import com.roadvision.roadsense.inference.NormalizedRect

data class PlateCandidate(val box: NormalizedRect, val confidence: Float, val timestampNs: Long)

data class PlateMatch(
    val plate: PlateCandidate,
    val trackId: Long,
    val score: Float,
    val consecutiveFrames: Int = 1,
    val confirmed: Boolean = false,
)

/**
 * Associates plate detections with vehicle tracks.
 *
 * A single frame's geometry is not enough to claim an association (FR-06). A candidate must stay
 * inside the same vehicle and keep overlapping its previous plate box for
 * [minimumConsecutiveFrames] plate-detection frames before [PlateMatch.confirmed] becomes true.
 */
class PlateVehicleMatcher(
    private val minimumScore: Float = 0.35f,
    private val minimumConsecutiveFrames: Int = 2,
    private val maximumGapNs: Long = 1_500_000_000L,
    private val minimumPlateIou: Float = 0.2f,
) {
    init {
        require(minimumScore in 0f..1f)
        require(minimumConsecutiveFrames >= 1)
        require(maximumGapNs > 0)
        require(minimumPlateIou in 0f..1f)
    }

    private data class Association(
        val box: NormalizedRect,
        val timestampNs: Long,
        val consecutive: Int,
    )

    private val associations = mutableMapOf<Long, Association>()

    @Synchronized
    fun match(plates: List<PlateCandidate>, vehicles: List<TrackedVehicle>): List<PlateMatch> =
        plates.mapNotNull { plate ->
            val best = vehicles
                .map { vehicle -> vehicle.trackId to containmentScore(plate.box, vehicle.detection.box) }
                .maxByOrNull { it.second }
                ?.takeIf { it.second >= minimumScore }
                ?: return@mapNotNull null

            val previous = associations[best.first]
            val consistent = previous != null &&
                plate.timestampNs >= previous.timestampNs &&
                plate.timestampNs - previous.timestampNs <= maximumGapNs &&
                previous.box.iou(plate.box) >= minimumPlateIou
            val consecutive = if (consistent) previous!!.consecutive + 1 else 1
            associations[best.first] = Association(plate.box, plate.timestampNs, consecutive)
            PlateMatch(
                plate = plate,
                trackId = best.first,
                score = best.second,
                consecutiveFrames = consecutive,
                confirmed = consecutive >= minimumConsecutiveFrames,
            )
        }

    @Synchronized
    fun prune(activeTrackIds: Set<Long>) {
        associations.keys.retainAll(activeTrackIds)
    }

    @Synchronized
    fun forget(trackId: Long) {
        associations.remove(trackId)
    }

    private fun containmentScore(plate: NormalizedRect, vehicle: NormalizedRect): Float {
        val centerX = (plate.left + plate.right) / 2f
        val centerY = (plate.top + plate.bottom) / 2f
        if (centerX !in vehicle.left..vehicle.right || centerY !in vehicle.top..vehicle.bottom) return 0f
        val expectedY = vehicle.top + vehicle.height * 0.72f
        val verticalScore = 1f - (kotlin.math.abs(centerY - expectedY) / vehicle.height).coerceIn(0f, 1f)
        val sizeScore = (plate.width / vehicle.width * 4f).coerceIn(0f, 1f)
        return verticalScore * 0.6f + sizeScore * 0.4f
    }
}
