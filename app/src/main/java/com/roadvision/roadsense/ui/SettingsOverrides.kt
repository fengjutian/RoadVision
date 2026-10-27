package com.roadvision.roadsense.ui

import com.roadvision.roadsense.pipeline.PipelineModelConfig

/** Applies user overrides on top of the reviewed model bundle without mutating the manifest. */
object SettingsOverrides {
    fun apply(base: PipelineModelConfig, settings: AppSettings): PipelineModelConfig = base.copy(
        provider = settings.providerOverride ?: base.provider,
        vehicleDecode = settings.vehicleConfidence?.let {
            base.vehicleDecode.copy(confidenceThreshold = it)
        } ?: base.vehicleDecode,
        lane = base.lane?.let { lane ->
            settings.laneThreshold?.let {
                lane.copy(decode = lane.decode.copy(threshold = it))
            } ?: lane
        },
        plateOcr = base.plateOcr?.let { plateOcr ->
            settings.plateConfidence?.let {
                plateOcr.copy(plateDecode = plateOcr.plateDecode.copy(confidenceThreshold = it))
            } ?: plateOcr
        },
    )
}
