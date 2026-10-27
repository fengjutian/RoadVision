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
fun DiagnosticsPanel(status: CameraStatus, diagnostics: DiagnosticsState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xD908110E))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("RoadVision · P1 采集基线", color = Color(0xFF39D98A), style = MaterialTheme.typography.titleMedium)
        Text(statusText(status), color = if (status is CameraStatus.Error) Color(0xFFFF6B6B) else Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Metric("采集 FPS", format(diagnostics.cameraFps))
            Metric("处理 FPS", format(diagnostics.processedFps))
            Metric("延迟", diagnostics.lastLatencyMs?.let { "${format(it)} ms" } ?: "—")
        }
        Text(
            "E2E P50/P95 ${metric(diagnostics.latencyP50Ms)}/${metric(diagnostics.latencyP95Ms)} ms" +
                " · 推理 ${diagnostics.executionProvider} ${metric(diagnostics.inferenceP50Ms)}/${metric(diagnostics.inferenceP95Ms)} ms" +
                " · 内存 ${diagnostics.memoryMb} MB" +
                (diagnostics.thermalStatus?.let { " · 热状态 $it" } ?: ""),
            color = Color(0xFFCAD5D0),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "采集 ${diagnostics.capturedFrames}  ·  处理 ${diagnostics.processedFrames}  ·  丢弃 ${diagnostics.droppedFrames}",
            color = Color(0xFFCAD5D0),
            style = MaterialTheme.typography.bodySmall,
        )
    }
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
