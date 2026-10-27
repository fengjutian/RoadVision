package com.roadvision.roadsense.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenLayoutTest {
    @Test
    fun portraitWindowUsesPortraitLayout() {
        assertEquals(ScreenLayout.PORTRAIT, screenLayoutFor(1080f, 2400f))
        assertEquals(ScreenLayout.PORTRAIT, screenLayoutFor(1080f, 1081f))
    }

    @Test
    fun landscapeWindowUsesLandscapeLayout() {
        assertEquals(ScreenLayout.LANDSCAPE, screenLayoutFor(2400f, 1080f))
    }

    @Test
    fun squareWindowFallsBackToLandscape() {
        assertEquals(ScreenLayout.LANDSCAPE, screenLayoutFor(1200f, 1200f))
    }

    @Test
    fun expandedPortraitPanelStillLeavesRoomForThePicture() {
        assertTrue(PORTRAIT_PANEL_MAX_HEIGHT_FRACTION > 0f)
        assertTrue(PORTRAIT_PANEL_MAX_HEIGHT_FRACTION <= 0.6f)
    }
}
