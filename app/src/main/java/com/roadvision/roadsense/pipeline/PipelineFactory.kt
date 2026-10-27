package com.roadvision.roadsense.pipeline

import android.content.Context
import com.roadvision.roadsense.camera.ConvertedFrame
import com.roadvision.roadsense.data.DetectionEventEntity
import com.roadvision.roadsense.diagnostics.PerformanceRecorder
import com.roadvision.roadsense.inference.ExecutionProvider
import com.roadvision.roadsense.inference.ModelAssetVerifier
import com.roadvision.roadsense.inference.ModelContract
import com.roadvision.roadsense.inference.OnnxLaneDetector
import com.roadvision.roadsense.inference.OnnxPlateDetector
import com.roadvision.roadsense.inference.OnnxPlateRecognizer
import com.roadvision.roadsense.inference.OnnxVehicleDetector
import com.roadvision.roadsense.inference.OrtRuntimeManager
import java.io.Closeable

data class ModelAssetConfig(val assetPath: String, val contract: ModelContract)

data class PipelineModelConfig(
    val vehicle: ModelAssetConfig,
    val vehicleLabels: Map<Int, String>,
    val lane: ModelAssetConfig,
    val plate: ModelAssetConfig,
    val ocr: ModelAssetConfig,
    val ocrAlphabet: List<Char>,
    val ocrBlankIndex: Int,
    val provider: ExecutionProvider = ExecutionProvider.CPU,
    val schedule: PipelineSchedule = PipelineSchedule(),
) {
    init {
        require(vehicleLabels.isNotEmpty())
        require(ocrAlphabet.isNotEmpty() && ocrAlphabet.distinct().size == ocrAlphabet.size)
        require(ocrBlankIndex in 0..ocrAlphabet.size)
    }
}

class ActivePipeline internal constructor(
    private val runtime: OrtRuntimeManager,
    private val coordinator: PipelineCoordinator,
) : Closeable {
    fun process(frame: ConvertedFrame) = coordinator.process(frame)
    override fun close() = runtime.close()
}

object PipelineFactory {
    /**
     * Activates inference only after every asset hash matches its reviewed contract.
     * Any failure aborts the whole pipeline; partial model activation is not allowed.
     */
    fun create(
        context: Context,
        config: PipelineModelConfig,
        recorder: PerformanceRecorder,
        onResult: (FusedPerceptionResult) -> Unit,
        onEvent: (DetectionEventEntity) -> Unit,
    ): ActivePipeline {
        listOf(config.vehicle, config.lane, config.plate, config.ocr).forEach {
            ModelAssetVerifier.verify(context, it.assetPath, it.contract.sha256)
        }
        val runtime = OrtRuntimeManager(context.applicationContext)
        return try {
            val coordinator = PipelineCoordinator(
                vehicleDetector = OnnxVehicleDetector(
                    config.vehicle.contract, config.vehicle.assetPath, config.provider, runtime, config.vehicleLabels,
                ),
                laneDetector = OnnxLaneDetector(
                    config.lane.contract, config.lane.assetPath, config.provider, runtime,
                ),
                plateDetector = OnnxPlateDetector(
                    config.plate.contract, config.plate.assetPath, config.provider, runtime,
                ),
                recognizer = OnnxPlateRecognizer(
                    config.ocr.contract, config.ocr.assetPath, config.provider, runtime,
                    config.ocrAlphabet, config.ocrBlankIndex,
                ),
                recorder = recorder,
                providerName = config.provider.name,
                schedule = config.schedule,
                onResult = onResult,
                onEvent = onEvent,
            )
            ActivePipeline(runtime, coordinator)
        } catch (error: Throwable) {
            runtime.close()
            throw error
        }
    }
}
