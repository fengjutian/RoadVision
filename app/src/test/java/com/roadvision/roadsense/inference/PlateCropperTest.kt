package com.roadvision.roadsense.inference

import org.junit.Assert.assertEquals
import org.junit.Test

class PlateCropperTest {
    @Test
    fun projectiveMappingPreservesQuadCorners() {
        val cropper = PlateCropper()
        val quad = PlateQuad(
            ImagePoint(0.1f, 0.2f), ImagePoint(0.9f, 0.1f),
            ImagePoint(0.8f, 0.9f), ImagePoint(0.2f, 0.8f),
        )
        assertPoint(quad.topLeft, cropper.project(quad, 0f, 0f))
        assertPoint(quad.topRight, cropper.project(quad, 1f, 0f))
        assertPoint(quad.bottomRight, cropper.project(quad, 1f, 1f))
        assertPoint(quad.bottomLeft, cropper.project(quad, 0f, 1f))
    }

    private fun assertPoint(expected: ImagePoint, actual: ImagePoint) {
        assertEquals(expected.x, actual.x, 0.001f)
        assertEquals(expected.y, actual.y, 0.001f)
    }
}
