package com.roadvision.roadsense.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewCoordinateMapperTest {
    @Test
    fun mapsLandscapeSourceIntoPortraitFillCenterCrop() {
        val mapper = PreviewCoordinateMapper(1920, 1080, 1080f, 1920f)
        val center = mapper.map(0.5f, 0.5f)
        assertEquals(540f, center.x, 0.01f)
        assertEquals(960f, center.y, 0.01f)
        assertEquals(-1166.67f, mapper.map(0f, 0f).x, 0.1f)
    }

    @Test
    fun mapsMatchingAspectRatioWithoutOffset() {
        val mapper = PreviewCoordinateMapper(100, 50, 200f, 100f)
        val point = mapper.map(0.25f, 0.5f)
        assertEquals(50f, point.x, 0.01f)
        assertEquals(50f, point.y, 0.01f)
    }
}
