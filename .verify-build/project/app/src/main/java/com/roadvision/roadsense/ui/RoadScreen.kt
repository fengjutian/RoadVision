package com.roadvision.roadsense.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.roadvision.roadsense.camera.CameraController
import com.roadvision.roadsense.diagnostics.ReportExporter
import com.roadvision.roadsense.inference.LaneResult
import com.roadvision.roadsense.tracking.TrackedVehicle

@Composable
fun RoadScreen(viewModel: RoadViewModel = viewModel()) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF39D98A))) {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val view = LocalView.current
        var hasPermission by remember {
            mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        }
        var permissionRequested by remember { mutableStateOf(false) }
        var notificationRequested by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        var detailPanelExpanded by rememberSaveable { mutableStateOf(false) }
        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionRequested = true
            hasPermission = granted
        }
        val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationRequested = true
        }
        val status by viewModel.cameraStatus.collectAsState()
        val diagnostics by viewModel.recorder.state.collectAsState()
        val perception by viewModel.perception.collectAsState()
        val events by viewModel.events.collectAsState()
        val modelState by viewModel.modelState.collectAsState()
        val modelInfo by viewModel.modelInfo.collectAsState()
        val settings by viewModel.settings.collectAsState()
        val captureEnabled by viewModel.captureEnabled.collectAsState()

        DisposableEffect(view, settings.keepScreenOn) {
            view.keepScreenOn = settings.keepScreenOn
            onDispose { view.keepScreenOn = false }
        }

        DisposableEffect(lifecycleOwner, context) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        // CameraX samples the display rotation when the analyser is bound, so a rotation after
        // that point has to be pushed explicitly; otherwise the analysed frames (and the overlay
        // built from them) keep the old orientation while PreviewView rotates the preview.
        DisposableEffect(view) {
            val displayManager = context.getSystemService(DisplayManager::class.java)
            val listener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = Unit
                override fun onDisplayRemoved(displayId: Int) = Unit
                override fun onDisplayChanged(displayId: Int) {
                    val display = view.display ?: return
                    if (displayId != display.displayId) return
                    viewModel.cameraController.updateTargetRotation(display.rotation)
                }
            }
            displayManager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
            view.display?.rotation?.let(viewModel.cameraController::updateTargetRotation)
            onDispose { displayManager?.unregisterDisplayListener(listener) }
        }

        LaunchedEffect(Unit) {
            if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        LaunchedEffect(hasPermission, notificationRequested) {
            if (hasPermission && !notificationRequested && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationRequested = true
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        LaunchedEffect(hasPermission, captureEnabled) {
            if (!hasPermission || !captureEnabled) viewModel.stopCaptureService()
        }

        BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF08110E))) {
            val screenLayout = screenLayoutFor(maxWidth.value, maxHeight.value)
            when (screenLayout) {
                ScreenLayout.PORTRAIT -> {
                    PreviewPane(
                        modifier = Modifier.fillMaxSize(),
                        hasPermission = hasPermission,
                        captureEnabled = captureEnabled,
                        permissionRequested = permissionRequested,
                        onRetryPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        onOpenSettings = { openAppSettings(context) },
                        onResume = { viewModel.setCaptureEnabled(true) },
                        controller = viewModel.cameraController,
                        onReady = viewModel::startCaptureService,
                        vehicles = perception.vehicles,
                        lanes = perception.lanes,
                        sourceWidth = perception.sourceWidth,
                        sourceHeight = perception.sourceHeight,
                    )
                    SettingsButton(
                        onClick = { showSettings = true },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End),
                            )
                            .padding(12.dp),
                    )
                    PortraitControlPanel(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        status = status,
                        diagnostics = diagnostics,
                        modelState = modelState,
                        vehicleCount = perception.vehicles.size,
                        modelsReady = perception.modelsReady,
                        events = events,
                        expanded = detailPanelExpanded,
                        maxPanelHeight = maxHeight * PORTRAIT_PANEL_MAX_HEIGHT_FRACTION,
                        onToggleExpanded = { detailPanelExpanded = !detailPanelExpanded },
                        onRestartCapture = viewModel::restartCapture,
                        onDeleteEvent = viewModel::deleteEvent,
                        onDeleteAllEvents = viewModel::deleteAllEvents,
                        onReloadModels = viewModel::configureModels,
                    )
                }
                ScreenLayout.LANDSCAPE -> Row(Modifier.fillMaxSize()) {
                    // The picture keeps the whole window height; diagnostics move to the side
                    // column instead of covering half of the road.
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        PreviewPane(
                            modifier = Modifier.fillMaxSize(),
                            hasPermission = hasPermission,
                            captureEnabled = captureEnabled,
                            permissionRequested = permissionRequested,
                            onRetryPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            onOpenSettings = { openAppSettings(context) },
                            onResume = { viewModel.setCaptureEnabled(true) },
                            controller = viewModel.cameraController,
                            onReady = viewModel::startCaptureService,
                            vehicles = perception.vehicles,
                            lanes = perception.lanes,
                            sourceWidth = perception.sourceWidth,
                            sourceHeight = perception.sourceHeight,
                        )
                        SettingsButton(
                            onClick = { showSettings = true },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                                .padding(12.dp),
                        )
                    }
                    LandscapeSidePanel(
                        modifier = Modifier.width(LANDSCAPE_SIDE_PANEL_WIDTH).fillMaxHeight(),
                        status = status,
                        diagnostics = diagnostics,
                        modelState = modelState,
                        vehicleCount = perception.vehicles.size,
                        modelsReady = perception.modelsReady,
                        events = events,
                        onRestartCapture = viewModel::restartCapture,
                        onDeleteEvent = viewModel::deleteEvent,
                        onDeleteAllEvents = viewModel::deleteAllEvents,
                        onReloadModels = viewModel::configureModels,
                    )
                }
            }
        }

        if (showSettings) {
            SettingsSheet(
                settings = settings,
                captureEnabled = captureEnabled,
                modelInfo = modelInfo,
                modelState = modelState,
                onDismiss = { showSettings = false },
                onCaptureEnabled = viewModel::setCaptureEnabled,
                onRetentionDays = viewModel::updateRetentionDays,
                onProvider = viewModel::updateProvider,
                onVehicleConfidence = viewModel::updateVehicleConfidence,
                onPlateConfidence = viewModel::updatePlateConfidence,
                onLaneThreshold = viewModel::updateLaneThreshold,
                onKeepScreenOn = viewModel::updateKeepScreenOn,
                onExportReport = {
                    runCatching {
                        val uri = ReportExporter.write(context, viewModel.buildReport())
                        context.startActivity(Intent.createChooser(ReportExporter.shareIntent(uri), "导出诊断报告"))
                    }.onFailure {
                        Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_LONG).show()
                    }
                },
            )
        }
    }
}

/**
 * Camera picture, overlay and the permission/paused states, sized by whichever layout slot hosts
 * it: the whole window in portrait, the area left of the diagnostics column in landscape.
 */
@Composable
private fun PreviewPane(
    hasPermission: Boolean,
    captureEnabled: Boolean,
    permissionRequested: Boolean,
    onRetryPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onResume: () -> Unit,
    controller: CameraController,
    onReady: () -> Unit,
    vehicles: List<TrackedVehicle>,
    lanes: LaneResult?,
    sourceWidth: Int,
    sourceHeight: Int,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        when {
            !hasPermission -> PermissionMessage(
                permissionRequested = permissionRequested,
                onRetry = onRetryPermission,
                onOpenSettings = onOpenSettings,
            )
            !captureEnabled -> PausedMessage(onResume = onResume)
            else -> CameraPreview(controller, onReady, vehicles, lanes, sourceWidth, sourceHeight)
        }
    }
}

@Composable
private fun SettingsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier) { Text("设置") }
}

@Composable
private fun CameraPreview(
    controller: CameraController,
    onReady: () -> Unit,
    vehicles: List<TrackedVehicle>,
    lanes: LaneResult?,
    sourceWidth: Int,
    sourceHeight: Int,
) {
    val context = LocalContext.current
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val overlayView = remember { RoadOverlayView(context) }
    val container = remember {
        FrameLayout(context).apply {
            addView(
                previewView,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
            addView(
                overlayView,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
        }
    }
    AndroidView(
        factory = { container },
        modifier = Modifier.fillMaxSize(),
        update = { overlayView.update(vehicles, lanes, sourceWidth, sourceHeight) },
    )
    DisposableEffect(controller, previewView) {
        controller.setSurfaceProvider(previewView.surfaceProvider)
        onReady()
        onDispose { controller.setSurfaceProvider(null) }
    }
}

@Composable
private fun PermissionMessage(
    permissionRequested: Boolean,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (permissionRequested) "相机权限被拒绝。请授权后继续；若不再弹出系统对话框，请在应用设置中开启相机权限。"
            else "RoadVision 需要相机权限来执行设备端道路画面分析。原始视频帧默认不会保存或上传。",
            color = Color.White,
        )
        Row(
            Modifier.padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onRetry) { Text("重新请求权限") }
            Button(onClick = onOpenSettings) { Text("打开应用设置") }
        }
    }
}

@Composable
private fun PausedMessage(onResume: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("采集已暂停；本地事件仍保留，可随时继续。", color = Color.White)
        Button(onClick = onResume, modifier = Modifier.padding(top = 16.dp)) { Text("继续采集") }
    }
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ),
        )
    }
}
