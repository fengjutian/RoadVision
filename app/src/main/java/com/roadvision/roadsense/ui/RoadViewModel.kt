package com.roadvision.roadsense.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roadvision.roadsense.camera.CameraStatus
import com.roadvision.roadsense.camera.ConvertedFrame
import com.roadvision.roadsense.data.AppDatabase
import com.roadvision.roadsense.data.DetectionEventEntity
import com.roadvision.roadsense.data.DetectionRepository
import com.roadvision.roadsense.diagnostics.PerformanceRecorder
import com.roadvision.roadsense.diagnostics.DeviceHealthSampler
import com.roadvision.roadsense.inference.LaneResult
import com.roadvision.roadsense.pipeline.FrameScheduler
import com.roadvision.roadsense.tracking.TrackedVehicle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PerceptionUiState(
    val vehicles: List<TrackedVehicle> = emptyList(),
    val lanes: LaneResult? = null,
    val modelsReady: Boolean = false,
)

class RoadViewModel(application: Application) : AndroidViewModel(application) {
    val recorder = PerformanceRecorder()
    private val database = AppDatabase.create(application)
    private val repository = DetectionRepository(database.events())
    val events = repository.events.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val mutablePerception = MutableStateFlow(PerceptionUiState())
    val perception = mutablePerception.asStateFlow()
    private val mutableCameraStatus = MutableStateFlow<CameraStatus>(CameraStatus.Stopped)
    private val healthSampler = DeviceHealthSampler(application)
    val cameraStatus = mutableCameraStatus.asStateFlow()

    val scheduler = FrameScheduler(
        onDropped = recorder::onDropped,
        processor = ::processFrame,
    )

    fun updateCameraStatus(status: CameraStatus) {
        mutableCameraStatus.value = status
    }

    fun deleteEvent(event: DetectionEventEntity) = viewModelScope.launch { repository.delete(event) }
    fun deleteAllEvents() = viewModelScope.launch { repository.deleteAll() }

    private fun processFrame(frame: ConvertedFrame) {
        // Model execution remains disabled until reviewed ONNX assets and model cards are supplied.
        check(frame.rgb.size == frame.width * frame.height * 3)
        recorder.onProcessed(frame.timestampNs)
        if (frame.frameId % 30L == 0L) healthSampler.sample(recorder)
    }

    override fun onCleared() {
        scheduler.close()
        database.close()
    }
}
