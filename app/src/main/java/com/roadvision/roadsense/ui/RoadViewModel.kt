package com.roadvision.roadsense.ui

import androidx.lifecycle.ViewModel
import com.roadvision.roadsense.camera.CameraStatus
import com.roadvision.roadsense.camera.ConvertedFrame
import com.roadvision.roadsense.diagnostics.PerformanceRecorder
import com.roadvision.roadsense.pipeline.FrameScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class RoadViewModel : ViewModel() {
    val recorder = PerformanceRecorder()
    private val mutableCameraStatus = MutableStateFlow<CameraStatus>(CameraStatus.Stopped)
    val cameraStatus = mutableCameraStatus.asStateFlow()

    val scheduler = FrameScheduler(
        onDropped = recorder::onDropped,
        processor = ::processFrame,
    )

    fun updateCameraStatus(status: CameraStatus) {
        mutableCameraStatus.value = status
    }

    private fun processFrame(frame: ConvertedFrame) {
        // P1 pipeline boundary. Licensed model adapters are attached here in P2.
        check(frame.rgb.size == frame.width * frame.height * 3)
        recorder.onProcessed(frame.timestampNs)
    }

    override fun onCleared() {
        scheduler.close()
    }
}
