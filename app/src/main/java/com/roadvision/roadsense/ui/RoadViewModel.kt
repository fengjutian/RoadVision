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
import com.roadvision.roadsense.inference.ModelBundleConfigLoader
import com.roadvision.roadsense.pipeline.FrameScheduler
import com.roadvision.roadsense.pipeline.ActivePipeline
import com.roadvision.roadsense.pipeline.PipelineFactory
import com.roadvision.roadsense.tracking.TrackedVehicle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class PerceptionUiState(
    val vehicles: List<TrackedVehicle> = emptyList(),
    val lanes: LaneResult? = null,
    val modelsReady: Boolean = false,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
)

sealed interface ModelRuntimeState {
    data object NotConfigured : ModelRuntimeState
    data object Loading : ModelRuntimeState
    data class Running(val provider: String) : ModelRuntimeState
    data class Failed(val message: String) : ModelRuntimeState
}

class RoadViewModel(application: Application) : AndroidViewModel(application) {
    val recorder = PerformanceRecorder()
    private val database = AppDatabase.create(application)
    private val repository = DetectionRepository(database.events())
    val events = repository.events.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val mutablePerception = MutableStateFlow(PerceptionUiState())
    val perception = mutablePerception.asStateFlow()
    private val mutableModelState = MutableStateFlow<ModelRuntimeState>(ModelRuntimeState.NotConfigured)
    val modelState = mutableModelState.asStateFlow()
    @Volatile private var activePipeline: ActivePipeline? = null
    private val mutableCameraStatus = MutableStateFlow<CameraStatus>(CameraStatus.Stopped)
    private val healthSampler = DeviceHealthSampler(application)
    val cameraStatus = mutableCameraStatus.asStateFlow()

    val scheduler = FrameScheduler(
        onDropped = recorder::onDropped,
        processor = ::processFrame,
    )

    init {
        configureModels()
        viewModelScope.launch { repository.enforceRetention() }
    }

    fun configureModels() {
        if (activePipeline != null || mutableModelState.value == ModelRuntimeState.Loading) return
        val context = getApplication<Application>()
        if (!ModelBundleConfigLoader.exists(context)) {
            mutableModelState.value = ModelRuntimeState.NotConfigured
            return
        }
        mutableModelState.value = ModelRuntimeState.Loading
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                val config = ModelBundleConfigLoader.load(context)
                val pipeline = PipelineFactory.create(
                    context = context,
                    config = config,
                    recorder = recorder,
                    onResult = { result ->
                        mutablePerception.value = PerceptionUiState(
                            result.vehicles, result.lanes, true, result.sourceWidth, result.sourceHeight,
                        )
                    },
                    onEvent = { event -> viewModelScope.launch { repository.add(event) } },
                )
                activePipeline = pipeline
                mutableModelState.value = ModelRuntimeState.Running(config.provider.name)
            }.onFailure { error ->
                activePipeline?.close()
                activePipeline = null
                mutablePerception.value = PerceptionUiState()
                mutableModelState.value = ModelRuntimeState.Failed(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    fun updateCameraStatus(status: CameraStatus) {
        mutableCameraStatus.value = status
    }

    fun deleteEvent(event: DetectionEventEntity) = viewModelScope.launch { repository.delete(event) }
    fun deleteAllEvents() = viewModelScope.launch { repository.deleteAll() }

    private fun processFrame(frame: ConvertedFrame) {
        check(frame.rgb.size == frame.width * frame.height * 3)
        try {
            activePipeline?.process(frame)
        } catch (error: Throwable) {
            activePipeline?.close()
            activePipeline = null
            mutablePerception.value = PerceptionUiState()
            mutableModelState.value = ModelRuntimeState.Failed(error.message ?: "模型推理失败")
        }
        recorder.onProcessed(frame.timestampNs)
        if (frame.frameId % 30L == 0L) healthSampler.sample(recorder)
    }

    override fun onCleared() {
        scheduler.close()
        activePipeline?.close()
        activePipeline = null
        database.close()
    }
}
