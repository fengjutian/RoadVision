package com.roadvision.roadsense.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.roadvision.roadsense.diagnostics.ModelDescriptor
import com.roadvision.roadsense.diagnostics.ModelRuntimeInfo
import com.roadvision.roadsense.inference.ExecutionProvider
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: AppSettings,
    captureEnabled: Boolean,
    modelInfo: ModelRuntimeInfo?,
    modelState: ModelRuntimeState,
    onDismiss: () -> Unit,
    onCaptureEnabled: (Boolean) -> Unit,
    onRetentionDays: (Int) -> Unit,
    onProvider: (ExecutionProvider?) -> Unit,
    onVehicleConfidence: (Float) -> Unit,
    onPlateConfidence: (Float) -> Unit,
    onLaneThreshold: (Float) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onExportReport: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0E1A15),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("设置与模型", style = MaterialTheme.typography.titleLarge, color = Color.White)

            Section("采集") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("相机采集", color = Color.White, modifier = Modifier.weight(1f))
                    Button(onClick = { onCaptureEnabled(!captureEnabled) }) {
                        Text(if (captureEnabled) "停止采集" else "开始采集")
                    }
                }
                SwitchRow("保持屏幕常亮", settings.keepScreenOn, onKeepScreenOn)
                Text(
                    "熄屏后台采集尚未实现：需要把 CameraX 所有权迁到前台服务。当前通过常亮保障 30 分钟测试。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF9EACA6),
                )
            }

            Section("本地数据") {
                Text("事件保留 ${settings.retentionDays} 天", color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7, 30, 90).forEach { days ->
                        FilterChip(
                            selected = settings.retentionDays == days,
                            onClick = { onRetentionDays(days) },
                            label = { Text("$days 天") },
                        )
                    }
                }
            }

            Section("推理") {
                Text(
                    "执行提供器（清单默认 ${modelInfo?.requestedProvider ?: "未加载"}）",
                    color = Color.White,
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProviderChip("跟随清单", null, settings.providerOverride, onProvider)
                    ProviderChip("CPU", ExecutionProvider.CPU, settings.providerOverride, onProvider)
                    ProviderChip("XNNPACK", ExecutionProvider.XNNPACK, settings.providerOverride, onProvider)
                    ProviderChip("NNAPI", ExecutionProvider.NNAPI, settings.providerOverride, onProvider)
                }
                Text(
                    providerStatus(modelInfo, modelState),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFCAD5D0),
                )
            }

            Section("阈值") {
                ThresholdRow(
                    "车辆置信度",
                    settings.vehicleConfidence ?: modelInfo?.vehicleConfidence ?: 0.4f,
                    onVehicleConfidence,
                )
                ThresholdRow(
                    "车牌置信度",
                    settings.plateConfidence ?: modelInfo?.plateConfidence ?: 0.5f,
                    onPlateConfidence,
                )
                ThresholdRow(
                    "车道概率",
                    settings.laneThreshold ?: modelInfo?.laneThreshold ?: 0.5f,
                    onLaneThreshold,
                )
                Text(
                    "修改后会重新加载模型；阈值必须与模型卡一致，默认值来自 model-bundle.json。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF9EACA6),
                )
            }

            Section("模型") {
                if (modelInfo == null) {
                    Text(modelStateText(modelState), color = Color(0xFFFFC857))
                } else {
                    modelInfo.models.forEach { ModelRow(it) }
                }
            }

            Button(onClick = onExportReport, modifier = Modifier.fillMaxWidth()) {
                Text("导出诊断报告")
            }
            Text(
                "报告由你主动导出，包含设备、构建、模型 SHA-256、性能指标和本地事件（含车牌文本）。请按隐私要求保管。",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF9EACA6),
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Color(0xFF39D98A), style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ProviderChip(
    label: String,
    provider: ExecutionProvider?,
    selectedProvider: ExecutionProvider?,
    onProvider: (ExecutionProvider?) -> Unit,
) {
    FilterChip(
        selected = selectedProvider == provider,
        onClick = { onProvider(provider) },
        label = { Text(label) },
    )
}

@Composable
private fun ThresholdRow(label: String, initial: Float, onChangeFinished: (Float) -> Unit) {
    var value by remember(initial) { mutableFloatStateOf(initial) }
    Column {
        Text("$label ${(value * 100).roundToInt()}%", color = Color.White)
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onChangeFinished(value) },
            valueRange = 0.05f..0.95f,
        )
    }
}

@Composable
private fun ModelRow(model: ModelDescriptor) {
    Column(Modifier.fillMaxWidth()) {
        Row {
            Text(roleLabel(model.role), color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            Text("${model.id} · ${model.version}", color = Color(0xFFCAD5D0))
        }
        Text(
            "SHA-256 ${model.sha256.take(16)}…",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF9EACA6),
        )
    }
}

private fun roleLabel(role: String): String = when (role) {
    "vehicle" -> "车辆检测"
    "lane" -> "车道识别"
    "plate" -> "车牌检测"
    "ocr" -> "车牌 OCR"
    else -> role
}

private fun providerStatus(info: ModelRuntimeInfo?, state: ModelRuntimeState): String = when {
    info == null -> modelStateText(state)
    info.fallbackToCpu -> "当前 ${info.actualProvider}（请求 ${info.requestedProvider}，已回退 CPU）"
    else -> "当前 ${info.actualProvider}（请求 ${info.requestedProvider}）"
}

private fun modelStateText(state: ModelRuntimeState): String = when (state) {
    ModelRuntimeState.NotConfigured -> "模型未配置：等待已批准权重与 models/model-bundle.json"
    ModelRuntimeState.Loading -> "正在校验并加载模型…"
    is ModelRuntimeState.Running -> "模型运行中：${state.provider}"
    is ModelRuntimeState.Failed -> "模型不可用：${state.message}"
}
