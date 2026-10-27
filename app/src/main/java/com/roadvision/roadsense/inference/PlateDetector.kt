package com.roadvision.roadsense.inference

import com.roadvision.roadsense.camera.ConvertedFrame

interface PlateDetector {
    val contract: ModelContract
    fun detect(frame: ConvertedFrame): List<Detection>
}

class OnnxPlateDetector(
    override val contract: ModelContract,
    private val assetPath: String,
    private val provider: ExecutionProvider,
    private val runtime: OrtRuntimeManager,
    confidenceThreshold: Float = 0.5f,
    nmsIouThreshold: Float = 0.4f,
) : PlateDetector {
    private val decoder = VehicleOutputDecoder(mapOf(0 to "plate"), confidenceThreshold, nmsIouThreshold)

    override fun detect(frame: ConvertedFrame): List<Detection> {
        val prepared = TensorPreprocessor.prepare(frame, contract)
        val spec = contract.outputs.single()
        val output = runtime.run(
            assetPath, provider, contract.input.name, prepared.shape, prepared.values, setOf(spec.name),
        ).getValue(spec.name)
        OutputValidator.validateShape(output.shape, spec.shape, spec.name)
        require(output.values.size % 6 == 0)
        return decoder.decode(
            Array(output.values.size / 6) { output.values.copyOfRange(it * 6, it * 6 + 6) },
            frame.frameId, frame.timestampNs,
        ).map { it.copy(box = prepared.transform.modelToSource(it.box)) }
    }
}
