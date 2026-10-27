package com.roadvision.roadsense.capture

import com.roadvision.roadsense.camera.CameraController
import com.roadvision.roadsense.diagnostics.PerformanceRecorder
import com.roadvision.roadsense.pipeline.FrameScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CaptureBridgeTest {
    @Test
    fun handsTheControllerToTheCaptureService() {
        val controller = CameraController(
            recorder = PerformanceRecorder(),
            scheduler = FrameScheduler(onDropped = {}, processor = {}),
            onStatus = {},
        )

        CaptureBridge.controller = controller
        assertSame(controller, CaptureBridge.controller)

        CaptureBridge.controller = null
        assertNull(CaptureBridge.controller)
        controller.close()
    }

    @Test
    fun exposesRestartActionForCameraRecovery() {
        assertEquals("com.roadvision.roadsense.action.RESTART_CAPTURE", CaptureService.ACTION_RESTART)
    }
}
