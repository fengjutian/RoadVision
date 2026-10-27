package com.roadvision.roadsense.inference

object OutputValidator {
    fun validateShape(actual: LongArray, expected: LongArray, name: String) {
        require(actual.size == expected.size) { "$name rank ${actual.size}, expected ${expected.size}" }
        actual.zip(expected).forEachIndexed { index, (value, contract) ->
            require(contract == -1L || value == contract) {
                "$name dimension $index is $value, expected $contract"
            }
        }
    }

    fun validateFinite(values: FloatArray, name: String) {
        require(values.all(Float::isFinite)) { "$name contains NaN or Infinity" }
    }

    fun confidence(value: Float): Float {
        require(value.isFinite() && value in 0f..1f) { "Confidence is outside [0,1]: $value" }
        return value
    }

    fun normalizedBox(left: Float, top: Float, right: Float, bottom: Float): NormalizedRect {
        val rect = NormalizedRect(left, top, right, bottom)
        require(rect.left >= -0.01f && rect.top >= -0.01f && rect.right <= 1.01f && rect.bottom <= 1.01f) {
            "Box is outside normalized coordinate range: $rect"
        }
        return rect.clamp()
    }
}
