package com.roadvision.roadsense.inference

import com.roadvision.roadsense.camera.ConvertedFrame
import com.roadvision.roadsense.pipeline.LaneDecodeConfig
import com.roadvision.roadsense.pipeline.LaneDecodeType

class OnnxLaneDetector(
    private val contract: ModelContract,
    private val assetPath: String,
    private val provider: ExecutionProvider,
    private val runtime: OrtRuntimeManager,
    decode: LaneDecodeConfig = LaneDecodeConfig(),
) : LaneDetector {
    private val outputSpec = contract.outputs.single()
    private val decoder: LaneDecoder = when (decode.type) {
        LaneDecodeType.MASK -> {
            val height = outputSpec.shape[outputSpec.shape.size - 2].toInt()
            val width = outputSpec.shape.last().toInt()
            LaneMaskDecoder(width, height, decode.threshold)
        }

        LaneDecodeType.UFLD -> {
            val shape = outputSpec.shape
            require(shape.size == 4 && shape[1] > 2 && shape[2] >= 3 && shape[3] > 0) {
                "UFLD output must be [1, cells+1, rows, lanes], got ${shape.contentToString()}"
            }
            require(decode.rowAnchors.size == shape[2].toInt()) {
                "Declared ${decode.rowAnchors.size} row anchors but the model has ${shape[2]}"
            }
            UfldLaneDecoder(
                numCells = (shape[1] - 1).toInt(),
                numLanes = shape[3].toInt(),
                rowAnchors = decode.rowAnchors.toFloatArray(),
                threshold = decode.threshold,
                modelWidth = contract.input.shape[3].toInt(),
            )
        }
    }

    /** Reused between frames; safe because inference consumes it before the next detect(). */
    private var scratch: FloatArray? = null

    override fun detect(frame: ConvertedFrame): LaneResult {
        val prepared = TensorPreprocessor.prepare(frame, contract, scratch)
        scratch = prepared.values
        val output = runtime.run(
            assetPath, provider, contract.input.name, prepared.shape, prepared.values, setOf(outputSpec.name),
        ).getValue(outputSpec.name)
        OutputValidator.validateShape(output.shape, outputSpec.shape, outputSpec.name)
        val decoded = decoder.decode(output.values, frame.frameId, frame.timestampNs)
        return decoded.copy(lanes = decoded.lanes.map { lane -> lane.map(prepared.transform::modelToSource) })
    }
}
