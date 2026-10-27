package com.roadvision.roadsense.inference

import org.junit.Assert.assertEquals
import org.junit.Test

class CtcDecoderTest {
    @Test
    fun collapsesRepeatsAndBlank() {
        val output = CtcDecoder.decode(
            arrayOf(
                floatArrayOf(0.9f, 0.05f, 0.05f),
                floatArrayOf(0.8f, 0.1f, 0.1f),
                floatArrayOf(0.1f, 0.1f, 0.8f),
                floatArrayOf(0.1f, 0.8f, 0.1f),
            ),
            alphabet = listOf('A', 'B'), blankIndex = 2,
        )
        assertEquals("AB", output?.text)
    }
}
