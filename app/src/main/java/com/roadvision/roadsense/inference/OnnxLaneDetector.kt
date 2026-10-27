package com.roadvision.roadsense.inference

import com.roadvision.roadsense.camera.ConvertedFrame

class OnnxLaneDetector(
    private val contract: ModelContract,
    private val assetPath: String,
    private val provider: ExecutionProvider,
    private val runtime: OrtRuntimeManager,
    threshold: Float = 0.5f,
) : LaneDetector {
    private val outputSpec = contract.outputs.single()
    private val height = outputSpec.shape[outputSpec.shape.size - 2].toInt()
    private val width = outputSpec.shape.last().toInt()
    private val decoder = LaneMaskDecoder(width, height, threshold)

    override fun detect(frame: ConvertedFrame): LaneResult {
        val prepared = TensorPreprocessor.prepare(frame, contract)
        val output = runtime.run(
            assetPath, provider, contract.input.name, prepared.shape, prepared.values, setOf(outputSpec.name),
        ).getValue(outputSpec.name)
        OutputValidator.validateShape(output.shape, outputSpec.shape, outputSpec.name)
        val decoded = decoder.decode(output.values, frame.frameId, frame.timestampNs)
        return decoded.copy(lanes = decoded.lanes.map { lane -> lane.map(prepared.transform::modelToSource) })
    }
}
