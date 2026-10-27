package com.roadvision.roadsense.pipeline

import org.junit.Assert.assertThrows
import org.junit.Test

class DecodeConfigTest {
    @Test
    fun rejectsOutOfRangeConfidence() {
        assertThrows(IllegalArgumentException::class.java) {
            DetectionDecodeConfig(confidenceThreshold = 1.2f)
        }
    }

    @Test
    fun rejectsNonPositiveMaxDetections() {
        assertThrows(IllegalArgumentException::class.java) {
            DetectionDecodeConfig(maxDetections = 0)
        }
    }

    @Test
    fun rejectsOutOfRangeLaneThreshold() {
        assertThrows(IllegalArgumentException::class.java) {
            LaneDecodeConfig(threshold = -0.1f)
        }
    }
}
