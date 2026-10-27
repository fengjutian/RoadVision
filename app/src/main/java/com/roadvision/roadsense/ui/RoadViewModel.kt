package com.roadvision.roadsense.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roadvision.roadsense.camera.CameraStatus
import com.roadvision.roadsense.camera.ConvertedFrame
import com.roadvision.roadsense.data.AppDatabase
import com.roadvision.roadsense.data.DetectionEventEntity
import com.roadvision.roadsense.data.DetectionRepository
import com.roadvision.roadsense.diagnostics.DeviceInfo
import com.roadvision.roadsense.diagnostics.DiagnosticsReportBuilder
import com.roadvision.roadsense.diagnostics.ModelDescriptor
import com.roadvision.roadsense.diagnostics.ModelRuntimeInfo
import com.roadvision.roadsense.diagnostics.PerformanceRecorder
import com.roadvision.roadsense.diagnostics.DeviceHealthSampler
import com.roadvision.roadsense.diagnostics.ReportSnapshot
import com.roadvision.roadsense.inference.ExecutionProvider
import com.roadvision.roadsense.inference.LaneResult
import com.roadvision.roadsense.inference.ModelBundleConfigLoader
import com.roadvision.roadsense.pipeline.FrameScheduler
import com.roadvision.roadsense.pipeline.ActivePipeline
import com.roadvision.roadsense.pipeline.PipelineFactory
import com.roadvision.roadsense.pipeline.PipelineModelConfig
import com.roadvision.roadsense.tracking.TrackedVehicle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration

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
    data class Running(val provider: String, val fallbackToCpu: Boolean = false) : ModelRuntimeState
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
    private val mutableModelInfo = MutableStateFlow<ModelRuntimeInfo?>(null)
    val modelInfo = mutableModelInfo.asStateFlow()
    private val settingsStore = SettingsStore(application)
    private val mutableSettings = MutableStateFlow(settingsStore.load())
    val settings = mutableSettings.asStateFlow()
    private val mutableCaptureEnabled = MutableStateFlow(true)
    val captureEnabled = mutableCaptureEnabled.asStateFlow()
    @Volatile private var activePipeline: ActivePipeline? = null
    private val pipelineLock = Any()
    private var modelGeneration = 0
    private val mutableCameraStatus = MutableStateFlow<CameraStatus>(CameraStatus.Stopped)
    private val healthSampler = DeviceHealthSampler(application)
    val cameraStatus = mutableCameraStatus.asStateFlow()

    val scheduler = FrameScheduler(
        onDropped = recorder::onDropped,
        processor = ::processFrame,
    )

    init {
        configureModels()
        viewModelScope.launch {
            repository.enforceRetention(Duration.ofDays(mutableSettings.value.retentionDays.toLong()))
        }
    }

    fun configureModels() {
        if (activePipeline != null || mutableModelState.value == ModelRuntimeState.Loading) return
        val generation = ++modelGeneration
        val context = getApplication<Application>()
        if (!ModelBundleConfigLoader.exists(context)) {
            mutableModelState.value = ModelRuntimeState.NotConfigured
            return
        }
        mutableModelState.value = ModelRuntimeState.Loading
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                val base = ModelBundleConfigLoader.load(context)
                val config = SettingsOverrides.apply(base, mutableSettings.value)
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
                if (generation != modelGeneration) {
                    pipeline.close()
                    return@launch
                }
                synchronized(pipelineLock) { activePipeline = pipeline }
                mutableModelInfo.value = ModelRuntimeInfo(
                    requestedProvider = config.provider.name,
                    actualProvider = pipeline.provider.name,
                    fallbackToCpu = pipeline.fallbackToCpu,
                    models = describeModels(config),
                    vehicleConfidence = config.vehicleDecode.confidenceThreshold,
                    plateConfidence = config.plateDecode.confidenceThreshold,
                    laneThreshold = config.laneDecode.threshold,
                )
                mutableModelState.value = ModelRuntimeState.Running(pipeline.provider.name, pipeline.fallbackToCpu)
            }.onFailure { error ->
                if (generation != modelGeneration) return@onFailure
                synchronized(pipelineLock) {
                    activePipeline?.close()
                    activePipeline = null
                }
                mutableModelInfo.value = null
                recorder.onError()
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

    fun setCaptureEnabled(enabled: Boolean) {
        mutableCaptureEnabled.value = enabled
        if (!enabled) {
            mutablePerception.value = mutablePerception.value.copy(
                vehicles = emptyList(),
                lanes = null,
                sourceWidth = 0,
                sourceHeight = 0,
            )
        }
    }

    fun updateRetentionDays(days: Int) {
        val next = mutableSettings.value.copy(retentionDays = days.coerceIn(1, 365))
        persist(next)
        viewModelScope.launch { repository.enforceRetention(Duration.ofDays(next.retentionDays.toLong())) }
    }

    fun updateProvider(provider: ExecutionProvider?) {
        persist(mutableSettings.value.copy(providerOverride = provider))
        reloadModels()
    }

    fun updateVehicleConfidence(value: Float) = updateDecode { copy(vehicleConfidence = value.coerceIn(0f, 1f)) }
    fun updatePlateConfidence(value: Float) = updateDecode { copy(plateConfidence = value.coerceIn(0f, 1f)) }
    fun updateLaneThreshold(value: Float) = updateDecode { copy(laneThreshold = value.coerceIn(0f, 1f)) }

    fun updateKeepScreenOn(enabled: Boolean) {
        persist(mutableSettings.value.copy(keepScreenOn = enabled))
    }

    private fun updateDecode(transform: AppSettings.() -> AppSettings) {
        persist(mutableSettings.value.transform())
        if (mutableModelInfo.value != null || mutableModelState.value is ModelRuntimeState.Failed) reloadModels()
    }

    fun reloadModels() {
        modelGeneration++
        viewModelScope.launch(Dispatchers.Default) {
            synchronized(pipelineLock) {
                activePipeline?.close()
                activePipeline = null
            }
            mutableModelInfo.value = null
            mutablePerception.value = PerceptionUiState()
            mutableModelState.value = ModelRuntimeState.NotConfigured
            configureModels()
        }
    }

    private fun persist(next: AppSettings) {
        settingsStore.save(next)
        mutableSettings.value = next
    }

    fun buildReport(): String = DiagnosticsReportBuilder.build(reportSnapshot())

    private fun reportSnapshot(): ReportSnapshot {
        val app = getApplication<Application>()
        val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
        @Suppress("DEPRECATION")
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
        val info = mutableModelInfo.value
        return ReportSnapshot(
            generatedAtEpochMs = System.currentTimeMillis(),
            appVersionName = packageInfo.versionName ?: "unknown",
            appVersionCode = versionCode,
            device = DeviceInfo(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                device = Build.DEVICE,
                sdkInt = Build.VERSION.SDK_INT,
                androidRelease = Build.VERSION.RELEASE,
                buildDisplay = Build.DISPLAY,
                socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null,
            ),
            diagnostics = recorder.state.value,
            providerRequested = info?.requestedProvider ?: "DISABLED",
            providerActual = info?.actualProvider ?: "DISABLED",
            fallbackToCpu = info?.fallbackToCpu ?: false,
            retentionDays = mutableSettings.value.retentionDays,
            models = info?.models ?: emptyList(),
            events = events.value,
        )
    }

    private fun describeModels(config: PipelineModelConfig): List<ModelDescriptor> = listOf(
        ModelDescriptor("vehicle", config.vehicle.contract.id, config.vehicle.contract.version, config.vehicle.contract.sha256),
        ModelDescriptor("lane", config.lane.contract.id, config.lane.contract.version, config.lane.contract.sha256),
        ModelDescriptor("plate", config.plate.contract.id, config.plate.contract.version, config.plate.contract.sha256),
        ModelDescriptor("ocr", config.ocr.contract.id, config.ocr.contract.version, config.ocr.contract.sha256),
    )

    private fun processFrame(frame: ConvertedFrame) {
        check(frame.rgb.size == frame.width * frame.height * 3)
        try {
            synchronized(pipelineLock) { activePipeline?.process(frame) }
        } catch (error: Throwable) {
            val failed = synchronized(pipelineLock) {
                activePipeline.also { activePipeline = null }
            }
            failed?.close()
            recorder.onError()
            mutablePerception.value = PerceptionUiState()
            mutableModelState.value = ModelRuntimeState.Failed(error.message ?: "模型推理失败")
        }
        recorder.onProcessed(frame.timestampNs)
        if (frame.frameId % 30L == 0L) healthSampler.sample(recorder)
    }

    override fun onCleared() {
        scheduler.close()
        synchronized(pipelineLock) {
            activePipeline?.close()
            activePipeline = null
        }
        database.close()
    }
}
