package com.roadvision.roadsense.ui

import com.roadvision.roadsense.camera.CameraStatus
import com.roadvision.roadsense.diagnostics.DiagnosticsState
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsSummaryTest {
    @Test
    fun runningPipelineShowsAnalysisRateAndLatency() {
        val line = compactDiagnosticsLine(
            DiagnosticsState(cameraFps = 29.97, processedFps = 12.34, lastLatencyMs = 45.67),
            ModelRuntimeState.Running("XNNPACK"),
            3,
        )

        assertEquals("采集 30.0 FPS · 分析 12.3 FPS · 延迟 45.7 ms · 检测 3", line)
    }

    @Test
    fun disabledInferenceShowsDashesInsteadOfZeroRates() {
        val line = compactDiagnosticsLine(
            DiagnosticsState(cameraFps = 7.87),
            ModelRuntimeState.NotConfigured,
            0,
        )

        assertEquals("采集 7.9 FPS · 分析 — · 延迟 — · 检测 0", line)
    }

    @Test
    fun pipelineWithoutLatencySampleStillReportsRates() {
        val line = compactDiagnosticsLine(
            DiagnosticsState(processedFps = 10.0),
            ModelRuntimeState.Running("CPU"),
            2,
        )

        assertEquals("采集 0.0 FPS · 分析 10.0 FPS · 延迟 — · 检测 2", line)
    }

    @Test
    fun cameraStatusLabelsFitTheCompactHeader() {
        assertEquals("相机启动中", cameraStatusLabel(CameraStatus.Starting))
        assertEquals("相机运行中", cameraStatusLabel(CameraStatus.Running))
        assertEquals("相机已停止", cameraStatusLabel(CameraStatus.Stopped))
        assertEquals("相机错误", cameraStatusLabel(CameraStatus.Error("camera in use")))
    }
}
