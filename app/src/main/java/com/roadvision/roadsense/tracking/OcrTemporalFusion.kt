package com.roadvision.roadsense.tracking

data class OcrObservation(
    val trackId: Long,
    val text: String,
    val confidence: Float,
    val timestampNs: Long,
)

data class FusedOcrResult(
    val trackId: Long,
    val text: String?,
    val confidence: Float,
    val uncertain: Boolean,
    val observations: Int,
)

class OcrTemporalFusion(
    private val minimumObservations: Int = 2,
    private val acceptanceConfidence: Float = 0.75f,
    private val retentionNs: Long = 2_000_000_000L,
) {
    private val history = mutableMapOf<Long, MutableList<OcrObservation>>()

    @Synchronized
    fun add(observation: OcrObservation): FusedOcrResult {
        require(observation.text.isNotBlank())
        require(observation.confidence in 0f..1f)
        val observations = history.getOrPut(observation.trackId, ::mutableListOf)
        observations.removeAll { observation.timestampNs - it.timestampNs > retentionNs }
        observations += observation.copy(text = normalize(observation.text))
        val grouped = observations.groupBy(OcrObservation::text)
        val winner = grouped.maxByOrNull { (_, values) -> values.sumOf { it.confidence.toDouble() } }
        val score = winner?.value?.map(OcrObservation::confidence)?.average()?.toFloat() ?: 0f
        val accepted = (winner?.value?.size ?: 0) >= minimumObservations && score >= acceptanceConfidence
        return FusedOcrResult(
            trackId = observation.trackId,
            text = winner?.key,
            confidence = score,
            uncertain = !accepted,
            observations = observations.size,
        )
    }

    private fun normalize(value: String) = value.uppercase().filter(Char::isLetterOrDigit)
}
