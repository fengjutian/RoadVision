package com.roadvision.roadsense.tracking

import com.roadvision.roadsense.inference.NormalizedRect

data class PlateCandidate(val box: NormalizedRect, val confidence: Float, val timestampNs: Long)
data class PlateMatch(val plate: PlateCandidate, val trackId: Long, val score: Float)

class PlateVehicleMatcher(private val minimumScore: Float = 0.35f) {
    fun match(plates: List<PlateCandidate>, vehicles: List<TrackedVehicle>): List<PlateMatch> =
        plates.mapNotNull { plate ->
            vehicles.map { vehicle -> vehicle.trackId to containmentScore(plate.box, vehicle.detection.box) }
                .maxByOrNull { it.second }
                ?.takeIf { it.second >= minimumScore }
                ?.let { PlateMatch(plate, it.first, it.second) }
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
