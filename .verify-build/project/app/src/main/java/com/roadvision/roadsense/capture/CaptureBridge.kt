package com.roadvision.roadsense.capture

import com.roadvision.roadsense.camera.CameraController

/**
 * Same-process handoff between the ViewModel (scheduler/pipeline owner) and the capture service
 * (CameraX lifecycle owner). The service must not start without a controller.
 */
object CaptureBridge {
    @Volatile
    var controller: CameraController? = null
}
