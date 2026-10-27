package com.roadvision.roadsense.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class FrameConverterTest {
    private val pixels = byteArrayOf(
        1, 1, 1, 2, 2, 2,
        3, 3, 3, 4, 4, 4,
        5, 5, 5, 6, 6, 6,
    )

    @Test
    fun rotatesClockwise90() {
        assertArrayEquals(
            byteArrayOf(
                5, 5, 5, 3, 3, 3, 1, 1, 1,
                6, 6, 6, 4, 4, 4, 2, 2, 2,
            ),
            FrameConverter.rotateRgb(pixels, width = 2, height = 3, rotation = 90),
        )
    }

    @Test
    fun rotatesClockwise180() {
        assertArrayEquals(
            byteArrayOf(
                6, 6, 6, 5, 5, 5,
                4, 4, 4, 3, 3, 3,
                2, 2, 2, 1, 1, 1,
            ),
            FrameConverter.rotateRgb(pixels, width = 2, height = 3, rotation = 180),
        )
    }
}
