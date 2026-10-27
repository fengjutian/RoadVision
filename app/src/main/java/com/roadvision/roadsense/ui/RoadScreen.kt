package com.roadvision.roadsense.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.roadvision.roadsense.camera.CameraStatus
import com.roadvision.roadsense.diagnostics.ReportExporter

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
        var cameraRestartKey by remember { mutableIntStateOf(0) }
        var showSettings by remember { mutableStateOf(false) }
        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionRequested = true
            hasPermission = granted
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

        LaunchedEffect(Unit) {
            if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        Box(Modifier.fillMaxSize().background(Color(0xFF08110E))) {
            when {
                !hasPermission -> PermissionMessage(
                    permissionRequested = permissionRequested,
                    onRetry = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onOpenSettings = { openAppSettings(context) },
                )
                !captureEnabled -> PausedMessage(onResume = { viewModel.setCaptureEnabled(true) })
                else -> CameraPreview(
                    controller = remember(cameraRestartKey) {
                        CameraController(viewModel.recorder, viewModel.scheduler, viewModel::updateCameraStatus)
                    },
                )
            }
            RoadOverlay(
                perception.vehicles,
                perception.lanes,
                perception.sourceWidth,
                perception.sourceHeight,
            )
            Column(Modifier.align(Alignment.BottomCenter).background(Color(0xD908110E))) {
                PlateEventList(events, viewModel::deleteEvent, viewModel::deleteAllEvents)
                DiagnosticsPanel(status, diagnostics)
                if (status is CameraStatus.Error && hasPermission && captureEnabled) {
                    Button(
                        onClick = { cameraRestartKey++ },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    ) { Text("重新启动相机") }
                }
                if (!perception.modelsReady) {
                    Text(
                        modelStatusText(modelState),
                        color = Color(0xFFFFC857),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    if (modelState is ModelRuntimeState.Failed) {
                        Button(
                            onClick = viewModel::configureModels,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        ) { Text("重新加载模型") }
                    }
                }
            }
            Button(
                onClick = { showSettings = true },
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            ) { Text("设置") }
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

private fun modelStatusText(state: ModelRuntimeState): String = when (state) {
    ModelRuntimeState.NotConfigured -> "模型未配置：等待已批准权重与 models/model-bundle.json"
    ModelRuntimeState.Loading -> "正在校验并加载模型…"
    is ModelRuntimeState.Running -> "模型运行中：${state.provider}" + if (state.fallbackToCpu) "（已回退 CPU）" else ""
    is ModelRuntimeState.Failed -> "模型不可用：${state.message}"
}

@Composable
private fun CameraPreview(controller: CameraController) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
    DisposableEffect(controller, owner, previewView) {
        controller.start(context, owner, previewView)
        onDispose { controller.close() }
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
