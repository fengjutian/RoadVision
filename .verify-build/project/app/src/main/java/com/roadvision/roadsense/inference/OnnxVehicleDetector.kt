package com.roadvision.roadsense.inference

import com.roadvision.roadsense.camera.ConvertedFrame
import com.roadvision.roadsense.pipeline.DetectionDecodeConfig

class OnnxVehicleDetector(
    override val contract: ModelContract,
    private val assetPath: String,
    private val provider: ExecutionProvider,
    private val runtime: OrtRuntimeManager,
    labels: Map<Int, String>,
    decode: DetectionDecodeConfig = DetectionDecodeConfig(),
) : VehicleDetector {
    private val decoder = VehicleOutputDecoder(
        labels, decode.confidenceThreshold, decode.nmsIouThreshold, decode.maxDetections,
    )

    /** Reused between frames; safe because inference consumes it before the next detect(). */
    private var scratch: FloatArray? = null

    override fun detect(frame: ConvertedFrame): List<Detection> {
        val prepared = TensorPreprocessor.prepare(frame, contract, scratch)
        scratch = prepared.values
        val spec = contract.outputs.single()
        val output = runtime.run(
            assetPath, provider, contract.input.name, prepared.shape, prepared.values, setOf(spec.name),
        ).getValue(spec.name)
        OutputValidator.validateShape(output.shape, spec.shape, spec.name)
        require(output.values.size % 6 == 0)
        val rows = Array(output.values.size / 6) { index -> output.values.copyOfRange(index * 6, index * 6 + 6) }
        return decoder.decode(rows, frame.frameId, frame.timestampNs).map { detection ->
            detection.copy(box = prepared.transform.modelToSource(detection.box))
        }
    }
}
