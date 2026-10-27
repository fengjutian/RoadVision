package com.roadvision.roadsense.diagnostics

import com.roadvision.roadsense.data.DetectionEventEntity
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsReportBuilderTest {
    @Test
    fun includesDeviceModelProviderAndEvents() {
        val json = DiagnosticsReportBuilder.build(
            ReportSnapshot(
                generatedAtEpochMs = 123,
                appVersionName = "0.1.0",
                appVersionCode = 1,
                device = DeviceInfo(
                    manufacturer = "HONOR",
                    model = "Magic",
                    device = "honor",
                    sdkInt = 35,
                    androidRelease = "15",
                    buildDisplay = "BUILD-1",
                    socModel = "SoC-X",
                ),
                diagnostics = DiagnosticsState(cameraFps = 14.5, errors = 2, memoryMb = 512),
                providerRequested = "NNAPI",
                providerActual = "CPU",
                fallbackToCpu = true,
                retentionDays = 30,
                models = listOf(ModelDescriptor("vehicle", "yolo", "1.0", "a".repeat(64))),
                events = listOf(
                    DetectionEventEntity(
                        id = 7, trackId = 3, plateText = "A\"1\n2", confidence = 0.9f,
                        uncertain = false, observedAtEpochMs = 456,
                    ),
                ),
            ),
        )

        assertTrue(json.contains("\"manufacturer\": \"HONOR\""))
        assertTrue(json.contains("\"socModel\": \"SoC-X\""))
        assertTrue(json.contains("\"requested\": \"NNAPI\""))
        assertTrue(json.contains("\"fallbackToCpu\": true"))
        assertTrue(json.contains("\"role\": \"vehicle\""))
        assertTrue(json.contains("\"plateText\": \"A\\\"1\\n2\""))
        assertTrue(json.contains("\"errors\": 2"))
    }

    @Test
    fun keepsEmptyCollectionsValid() {
        val json = DiagnosticsReportBuilder.build(
            ReportSnapshot(
                generatedAtEpochMs = 1,
                appVersionName = "0.1.0",
                appVersionCode = 1,
                device = DeviceInfo("A", "B", "C", 26, "8.0", "D", null),
                diagnostics = DiagnosticsState(),
                providerRequested = "DISABLED",
                providerActual = "DISABLED",
                fallbackToCpu = false,
                retentionDays = 30,
                models = emptyList(),
                events = emptyList(),
            ),
        )
        assertTrue(json.contains("\"socModel\": null"))
        assertTrue(json.contains("\"models\": []"))
        assertTrue(json.contains("\"events\": []"))
    }
}
