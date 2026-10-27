package com.roadvision.roadsense.ui

import com.roadvision.roadsense.pipeline.PipelineModelConfig

/** Applies user overrides on top of the reviewed model bundle without mutating the manifest. */
object SettingsOverrides {
    fun apply(base: PipelineModelConfig, settings: AppSettings): PipelineModelConfig = base.copy(
        provider = settings.providerOverride ?: base.provider,
        vehicleDecode = settings.vehicleConfidence?.let {
            base.vehicleDecode.copy(confidenceThreshold = it)
        } ?: base.vehicleDecode,
        plateDecode = settings.plateConfidence?.let {
            base.plateDecode.copy(confidenceThreshold = it)
        } ?: base.plateDecode,
        laneDecode = settings.laneThreshold?.let {
            base.laneDecode.copy(threshold = it)
        } ?: base.laneDecode,
    )
}
