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

/**
 * Lane detection is optional. When absent the coordinator keeps publishing vehicle-only results;
 * it never reports a stale or synthetic lane.
 */
data class LaneModelConfig(
    val asset: ModelAssetConfig,
    val decode: LaneDecodeConfig = LaneDecodeConfig(),
)

/**
 * Plate detection and OCR are one capability: a plate box without a recognizer produces no useful
 * event, so the bundle must provide both or neither.
 */
data class PlateOcrModelConfig(
    val plate: ModelAssetConfig,
    val ocr: ModelAssetConfig,
    val alphabet: List<Char>,
    val blankIndex: Int,
    val plateDecode: DetectionDecodeConfig = DetectionDecodeConfig(
        confidenceThreshold = 0.5f,
        nmsIouThreshold = 0.4f,
        maxDetections = 50,
    ),
) {
    init {
        require(alphabet.isNotEmpty()) { "OCR alphabet must not be empty" }
        require(alphabet.distinct().size == alphabet.size) { "OCR alphabet must not contain duplicates" }
        require(blankIndex in 0..alphabet.size) { "OCR blank index must be in 0..${alphabet.size}" }
    }
}

data class PipelineModelConfig(
    val vehicle: ModelAssetConfig,
    val vehicleLabels: Map<Int, String>,
    val lane: LaneModelConfig? = null,
    val plateOcr: PlateOcrModelConfig? = null,
    val provider: ExecutionProvider = ExecutionProvider.CPU,
    val schedule: PipelineSchedule = PipelineSchedule(),
    val vehicleDecode: DetectionDecodeConfig = DetectionDecodeConfig(),
) {
    init {
        require(vehicleLabels.isNotEmpty()) { "Vehicle labels must not be empty" }
    }
}

class ActivePipeline internal constructor(
    private val runtime: OrtRuntimeManager,
    private val coordinator: PipelineCoordinator,
    val provider: ExecutionProvider,
    val fallbackToCpu: Boolean,
    val enabledModels: List<String>,
) : Closeable {
    fun process(frame: ConvertedFrame) = coordinator.process(frame)
    override fun close() = runtime.close()
}

object PipelineFactory {
    /**
     * Activates inference for every model declared in the bundle. Vehicle detection is always
     * required; lane and plate+OCR are optional so the detection path can be validated before
     * those assets clear license review. Declared assets are still all verified before any
     * session is created, so a typo or stale hash cannot silently downgrade the pipeline.
     */
    fun create(
        context: Context,
        config: PipelineModelConfig,
        recorder: PerformanceRecorder,
        onResult: (FusedPerceptionResult) -> Unit,
        onEvent: (DetectionEventEntity) -> Unit,
    ): ActivePipeline {
        ModelBundleValidator.validate(config)
        val assets = config.assets()
        assets.forEach {
            ModelAssetVerifier.verify(context, it.assetPath, it.contract.sha256)
        }

        var lastError: Throwable? = null
        providerCandidates(config.provider).forEach { provider ->
            val runtime = OrtRuntimeManager(context.applicationContext)
            try {
                val effective = config.copy(provider = provider)
                val coordinator = buildCoordinator(effective, runtime, recorder, onResult, onEvent)
                // Open sessions eagerly so provider and model failures surface before capture starts.
                assets.forEach { runtime.session(it.assetPath, provider) }
                return ActivePipeline(
                    runtime = runtime,
                    coordinator = coordinator,
                    provider = provider,
                    fallbackToCpu = provider != config.provider,
                    enabledModels = enabledModels(config),
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
        laneDetector = config.lane?.let { lane ->
            OnnxLaneDetector(
                lane.asset.contract, lane.asset.assetPath, config.provider, runtime,
                lane.decode.threshold,
            )
        },
        plateDetector = config.plateOcr?.let { plateOcr ->
            OnnxPlateDetector(
                plateOcr.plate.contract, plateOcr.plate.assetPath, config.provider, runtime,
                plateOcr.plateDecode,
            )
        },
        recognizer = config.plateOcr?.let { plateOcr ->
            OnnxPlateRecognizer(
                plateOcr.ocr.contract, plateOcr.ocr.assetPath, config.provider, runtime,
                plateOcr.alphabet, plateOcr.blankIndex,
            )
        },
        recorder = recorder,
        providerName = config.provider.name,
        schedule = config.schedule,
        onResult = onResult,
        onEvent = onEvent,
    )
}

/** Model roles that will actually run for [config], in a stable display order. */
internal fun enabledModels(config: PipelineModelConfig): List<String> = buildList {
    add("vehicle")
    if (config.lane != null) add("lane")
    if (config.plateOcr != null) {
        add("plate")
        add("ocr")
    }
}

private fun PipelineModelConfig.assets(): List<ModelAssetConfig> = buildList {
    add(vehicle)
    lane?.let { add(it.asset) }
    plateOcr?.let {
        add(it.plate)
        add(it.ocr)
    }
}

/** CPU is the only provider that cannot fall back further. */
internal fun providerCandidates(requested: ExecutionProvider): List<ExecutionProvider> =
    if (requested == ExecutionProvider.CPU) listOf(ExecutionProvider.CPU)
    else listOf(requested, ExecutionProvider.CPU)
