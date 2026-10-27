package com.roadvision.roadsense.inference

import com.roadvision.roadsense.camera.ConvertedFrame

class OnnxPlateRecognizer(
    override val contract: ModelContract,
    private val assetPath: String,
    private val provider: ExecutionProvider,
    private val runtime: OrtRuntimeManager,
    private val alphabet: List<Char>,
    private val blankIndex: Int,
) : PlateRecognizer {
    override fun recognize(rgb: ByteArray, width: Int, height: Int): PlateRecognition? {
        require(rgb.size == width * height * 3)
        val prepared = TensorPreprocessor.prepare(ConvertedFrame(0, 0, width, height, 0, rgb), contract)
        val spec = contract.outputs.single()
        val output = runtime.run(
            assetPath, provider, contract.input.name, prepared.shape, prepared.values, setOf(spec.name),
        ).getValue(spec.name)
        OutputValidator.validateShape(output.shape, spec.shape, spec.name)
        val classes = alphabet.size + 1
        require(output.values.size % classes == 0)
        val steps = Array(output.values.size / classes) {
            output.values.copyOfRange(it * classes, it * classes + classes)
        }
        return CtcDecoder.decode(steps, alphabet, blankIndex)
    }
}
