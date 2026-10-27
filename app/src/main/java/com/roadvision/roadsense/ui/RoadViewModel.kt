package com.roadvision.roadsense.ui

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roadvision.roadsense.camera.CameraController
import com.roadvision.roadsense.camera.CameraStatus
import com.roadvision.roadsense.camera.ConvertedFrame
import com.roadvision.roadsense.capture.CaptureBridge
import com.roadvision.roadsense.capture.CaptureService
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
    data class Running(
        val provider: String,
        val fallbackToCpu: Boolean = false,
        val enabledModels: List<String> = listOf("vehicle", "lane", "plate", "ocr"),
    ) : ModelRuntimeState
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
    val cameraController = CameraController(recorder, scheduler, ::updateCameraStatus)

    init {
        CaptureBridge.controller = cameraController
        configureModels()
        viewModelScope.launch {
            repository.enforceRetention(Duration.ofDays(mutableSettings.value.retentionDays.toLong()))
        }
    }

    fun startCaptureService() {
        if (!mutableCaptureEnabled.value) return
        val context = getApplication<Application>()
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, CaptureService::class.java))
        }.onFailure { error ->
            recorder.onError()
            mutableCameraStatus.value = CameraStatus.Error(error.message ?: "前台采集服务启动失败")
        }
    }

    fun stopCaptureService() {
        val context = getApplication<Application>()
        runCatching { context.stopService(Intent(context, CaptureService::class.java)) }
        cameraController.stop()
    }

    fun restartCapture() {
        val context = getApplication<Application>()
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_RESTART),
            )
        }.onFailure { error ->
            recorder.onError()
            mutableCameraStatus.value = CameraStatus.Error(error.message ?: "相机重启失败")
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
                    plateConfidence = config.plateOcr?.plateDecode?.confidenceThreshold ?: 0.5f,
                    laneThreshold = config.lane?.decode?.threshold ?: 0.5f,
                    enabledModels = pipeline.enabledModels,
                )
                mutableModelState.value = ModelRuntimeState.Running(
                    pipeline.provider.name,
                    pipeline.fallbackToCpu,
                    pipeline.enabledModels,
                )
            }.onFailure { error ->
                if (generation != modelGeneration) return@onFailure
                val retired = synchronized(pipelineLock) {
                    activePipeline.also { activePipeline = null }
                }
                retired?.close()
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
            stopCaptureService()
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
            // Detach first so no new frame can enter the retired pipeline, then close it. Closing
            // waits for an in-flight run() to return instead of freeing the session under it.
            val retired = synchronized(pipelineLock) {
                activePipeline.also { activePipeline = null }
            }
            retired?.close()
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

    private fun describeModels(config: PipelineModelConfig): List<ModelDescriptor> = buildList {
        add(ModelDescriptor("vehicle", config.vehicle.contract.id, config.vehicle.contract.version, config.vehicle.contract.sha256))
        config.lane?.asset?.let {
            add(ModelDescriptor("lane", it.contract.id, it.contract.version, it.contract.sha256))
        }
        config.plateOcr?.let {
            add(ModelDescriptor("plate", it.plate.contract.id, it.plate.contract.version, it.plate.contract.sha256))
            add(ModelDescriptor("ocr", it.ocr.contract.id, it.ocr.contract.version, it.ocr.contract.sha256))
        }
    }

    private fun processFrame(frame: ConvertedFrame) {
        check(frame.rgb.size == frame.width * frame.height * 3)
        recorder.onFrameGeometry(frame.width, frame.height, frame.rotationDegrees)
        val pipeline = synchronized(pipelineLock) { activePipeline }
        if (pipeline == null) {
            recorder.onSkipped()
            sampleHealth(frame)
            return
        }
        try {
            pipeline.process(frame)
            recorder.onProcessed(frame.ingressNs, frame.timestampNs)
        } catch (error: Throwable) {
            val failed = synchronized(pipelineLock) {
                if (activePipeline === pipeline) {
                    activePipeline = null
                    pipeline
                } else {
                    null
                }
            }
            // A pipeline that was already retired (reload, capture stop, ViewModel teardown) can
            // throw "session was closed" from the frame thread; that is not a runtime failure.
            if (failed != null) {
                failed.close()
                recorder.onError()
                mutablePerception.value = PerceptionUiState()
                mutableModelState.value = ModelRuntimeState.Failed(error.message ?: "模型推理失败")
            }
        }
        sampleHealth(frame)
    }

    private fun sampleHealth(frame: ConvertedFrame) {
        if (frame.frameId % 30L == 0L) healthSampler.sample(recorder)
    }

    override fun onCleared() {
        stopCaptureService()
        CaptureBridge.controller = null
        cameraController.close()
        // Waits for the worker to leave any in-flight inference before the session is closed.
        scheduler.close()
        val retired = synchronized(pipelineLock) {
            activePipeline.also { activePipeline = null }
        }
        retired?.close()
        database.close()
    }
}
