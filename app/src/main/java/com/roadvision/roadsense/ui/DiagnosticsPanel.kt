package com.roadvision.roadsense.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.roadvision.roadsense.camera.CameraStatus
import com.roadvision.roadsense.diagnostics.DiagnosticsState
import java.util.Locale

@Composable
fun DiagnosticsPanel(
    status: CameraStatus,
    diagnostics: DiagnosticsState,
    modelState: ModelRuntimeState,
    vehicleCount: Int,
    modifier: Modifier = Modifier,
) {
    val running = modelState as? ModelRuntimeState.Running
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xD908110E))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(panelTitle(running), color = Color(0xFF39D98A), style = MaterialTheme.typography.titleMedium)
        Text(statusText(status), color = if (status is CameraStatus.Error) Color(0xFFFF6B6B) else Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Metric("采集 FPS", format(diagnostics.cameraFps))
            Metric("分析 FPS", running?.let { format(diagnostics.processedFps) } ?: "—")
            Metric(
                "延迟",
                if (running == null) {
                    "—"
                } else {
                    diagnostics.lastLatencyMs?.let { "${format(it)} ms" } ?: "—"
                },
            )
        }
        val e2e = if (running == null) {
            "—/—"
        } else {
            "${metric(diagnostics.latencyP50Ms)}/${metric(diagnostics.latencyP95Ms)}"
        }
        Text(
            "延迟 P50/P95 $e2e ms" +
                " · 推理 " + inferenceText(running, diagnostics) +
                " · 内存 应用 ${diagnostics.appPssMb} MB / 整机 ${diagnostics.deviceUsedMemoryMb} MB" +
                (diagnostics.thermalStatus?.let { " · 热状态 $it" } ?: ""),
            color = Color(0xFFCAD5D0),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "采集 ${diagnostics.capturedFrames} · 分析 ${diagnostics.processedFrames} · 跳过 ${diagnostics.skippedFrames}",
            color = Color(0xFFCAD5D0),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "分析尺寸 ${diagnostics.analysisWidth}×${diagnostics.analysisHeight} · 旋转 ${diagnostics.rotationDegrees}°" +
                " · 检测 $vehicleCount · 丢弃 ${diagnostics.droppedFrames} · 错误 ${diagnostics.errors}",
            color = Color(0xFFCAD5D0),
            style = MaterialTheme.typography.bodySmall,
        )
        if (running == null) {
            Text(
                "推理未启用：当前只采集，不产生检测结果",
                color = Color(0xFFFFC857),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            val missing = missingOptionalModelRoles(running.enabledModels)
            if (missing.isNotEmpty()) {
                Text(
                    "降级模式：已启用 ${enabledModelsLabel(running.enabledModels)}；未启用 " +
                        missing.joinToString("、") { modelRoleLabel(it) },
                    color = Color(0xFFFFC857),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun panelTitle(running: ModelRuntimeState.Running?): String = when {
    running == null -> "RoadVision · 采集基线"
    missingOptionalModelRoles(running.enabledModels).isEmpty() -> "RoadVision · 实时感知"
    running.enabledModels == listOf("vehicle") -> "RoadVision · 车辆检测"
    else -> "RoadVision · 道路感知"
}

private fun inferenceText(running: ModelRuntimeState.Running?, diagnostics: DiagnosticsState): String =
    if (running == null) {
        "未启用 —/— ms"
    } else {
        "${running.provider} ${metric(diagnostics.inferenceP50Ms)}/${metric(diagnostics.inferenceP95Ms)} ms"
    }

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(value, color = Color.White, style = MaterialTheme.typography.titleSmall)
        Text(label, color = Color(0xFF9EACA6), style = MaterialTheme.typography.labelSmall)
    }
}

private fun statusText(status: CameraStatus): String = when (status) {
    CameraStatus.Starting -> "相机正在启动"
    CameraStatus.Running -> "相机运行中 · 本地处理 · 不保存原始帧"
    CameraStatus.Stopped -> "相机已停止"
    is CameraStatus.Error -> "错误：${status.message}"
}

private fun format(value: Double): String = String.format(Locale.US, "%.1f", value)
private fun metric(value: Double?): String = value?.let(::format) ?: "—"
