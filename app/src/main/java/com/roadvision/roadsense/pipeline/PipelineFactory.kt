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
    val vehicleDecode: DetectionDecodeConfig = DetectionDecodeConfig(),
    val plateDecode: DetectionDecodeConfig = DetectionDecodeConfig(
        confidenceThreshold = 0.5f,
        nmsIouThreshold = 0.4f,
        maxDetections = 50,
    ),
    val laneDecode: LaneDecodeConfig = LaneDecodeConfig(),
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
    val provider: ExecutionProvider,
    val fallbackToCpu: Boolean,
) : Closeable {
    fun process(frame: ConvertedFrame) = coordinator.process(frame)
    override fun close() = runtime.close()
}

object PipelineFactory {
    /**
     * Activates inference only after every asset hash matches its reviewed contract and all four
     * sessions can be created. A non-CPU provider is retried on CPU so a missing NNAPI/XNNPACK
     * delegate degrades instead of disabling perception entirely.
     */
    fun create(
        context: Context,
        config: PipelineModelConfig,
        recorder: PerformanceRecorder,
        onResult: (FusedPerceptionResult) -> Unit,
        onEvent: (DetectionEventEntity) -> Unit,
    ): ActivePipeline {
        ModelBundleValidator.validate(config)
        listOf(config.vehicle, config.lane, config.plate, config.ocr).forEach {
            ModelAssetVerifier.verify(context, it.assetPath, it.contract.sha256)
        }

        var lastError: Throwable? = null
        providerCandidates(config.provider).forEach { provider ->
            val runtime = OrtRuntimeManager(context.applicationContext)
            try {
                val effective = config.copy(provider = provider)
                val coordinator = buildCoordinator(effective, runtime, recorder, onResult, onEvent)
                // Open sessions eagerly so provider and model failures surface before capture starts.
                listOf(effective.vehicle, effective.lane, effective.plate, effective.ocr).forEach {
                    runtime.session(it.assetPath, provider)
                }
                return ActivePipeline(
                    runtime = runtime,
                    coordinator = coordinator,
                    provider = provider,
                    fallbackToCpu = provider != config.provider,
                )
            } catch (error: Throwable) {
                runtime.close()
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("No ONNX Runtime execution provider could be initialized")
    }

    private fun buildCoordinator(
        config: PipelineModelConfig,
        runtime: OrtRuntimeManager,
        recorder: PerformanceRecorder,
        onResult: (FusedPerceptionResult) -> Unit,
        onEvent: (DetectionEventEntity) -> Unit,
    ) = PipelineCoordinator(
        vehicleDetector = OnnxVehicleDetector(
            config.vehicle.contract, config.vehicle.assetPath, config.provider, runtime,
            config.vehicleLabels, config.vehicleDecode,
        ),
        laneDetector = OnnxLaneDetector(
            config.lane.contract, config.lane.assetPath, config.provider, runtime,
            config.laneDecode.threshold,
        ),
        plateDetector = OnnxPlateDetector(
            config.plate.contract, config.plate.assetPath, config.provider, runtime,
            config.plateDecode,
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
}

/** CPU is the only provider that cannot fall back further. */
internal fun providerCandidates(requested: ExecutionProvider): List<ExecutionProvider> =
    if (requested == ExecutionProvider.CPU) listOf(ExecutionProvider.CPU)
    else listOf(requested, ExecutionProvider.CPU)
