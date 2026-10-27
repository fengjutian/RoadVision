package com.roadvision.roadsense.inference

data class PlateRecognition(val text: String, val confidence: Float, val characterConfidences: List<Float>)

interface PlateRecognizer {
    val contract: ModelContract
    fun recognize(rgb: ByteArray, width: Int, height: Int): PlateRecognition?
}

object CtcDecoder {
    fun decode(
        timeSteps: Array<FloatArray>,
        alphabet: List<Char>,
        blankIndex: Int,
    ): PlateRecognition? {
        require(blankIndex in 0..alphabet.size)
        if (timeSteps.isEmpty()) return null
        val text = StringBuilder()
        val confidences = mutableListOf<Float>()
        var previous = -1
        timeSteps.forEach { probabilities ->
            require(probabilities.size == alphabet.size + 1)
            OutputValidator.validateFinite(probabilities, "OCR output")
            require(probabilities.all { it in 0f..1f })
            val index = probabilities.indices.maxBy { probabilities[it] }
            if (index != blankIndex && index != previous) {
                val alphabetIndex = if (index < blankIndex) index else index - 1
                text.append(alphabet[alphabetIndex])
                confidences += probabilities[index]
            }
            previous = index
        }
        if (text.isEmpty()) return null
        return PlateRecognition(text.toString(), confidences.average().toFloat(), confidences)
    }
}
