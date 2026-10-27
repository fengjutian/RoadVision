package com.roadvision.roadsense.camera

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayRotationTest {
    @Test
    fun acceptsEveryRotationCameraXSupports() {
        assertEquals(Surface.ROTATION_0, normalizeSurfaceRotation(Surface.ROTATION_0))
        assertEquals(Surface.ROTATION_90, normalizeSurfaceRotation(Surface.ROTATION_90))
        assertEquals(Surface.ROTATION_180, normalizeSurfaceRotation(Surface.ROTATION_180))
        assertEquals(Surface.ROTATION_270, normalizeSurfaceRotation(Surface.ROTATION_270))
    }

    @Test
    fun rejectsUnknownRotationsInsteadOfForwardingThemToCameraX() {
        assertNull(normalizeSurfaceRotation(-1))
        assertNull(normalizeSurfaceRotation(4))
        assertNull(normalizeSurfaceRotation(Int.MAX_VALUE))
    }
}
