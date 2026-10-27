package com.roadvision.roadsense.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceRecorderTest {
    @Test
    fun reportsRatesAndCounters() {
        var now = 0L
        val recorder = PerformanceRecorder(clockNs = { now }, windowNs = 2_000_000_000L)
        recorder.onCaptured()
        now = 1_000_000_000L
        recorder.onCaptured()
        recorder.onProcessed(900_000_000L)
        recorder.onDropped()
        recorder.onError()

        val state = recorder.state.value
        assertEquals(1.0, state.cameraFps, 0.001)
        assertEquals(2, state.capturedFrames)
        assertEquals(1, state.processedFrames)
        assertEquals(1, state.droppedFrames)
        assertEquals(1, state.errors)
        assertEquals(100.0, state.lastLatencyMs!!, 0.001)
        assertEquals(LATENCY_SOURCE_ANALYZER_INGRESS, state.latencySource)
    }

    @Test
    fun latencyComesFromIngressNotSensorTimestamp() {
        var now = 0L
        val recorder = PerformanceRecorder(clockNs = { now }, windowNs = 2_000_000_000L)
        // A sensor timestamp from another clock base must not be able to zero out or inflate latency.
        now = 2_000_000_000L
        recorder.onProcessed(ingressNs = 1_950_000_000L, sensorNs = 5_000_000L)

        val state = recorder.state.value
        assertEquals(50.0, state.lastLatencyMs!!, 0.001)
        assertEquals(50.0, state.latencyP50Ms!!, 0.001)
        assertTrue(state.sensorClockMismatch)
        assertEquals(-1_945.0, state.sensorClockOffsetMs!!, 0.001)
        assertEquals(0, state.clockAnomalies)
    }

    @Test
    fun negativeLatencyIsCountedInsteadOfBeingHidden() {
        var now = 0L
        val recorder = PerformanceRecorder(clockNs = { now }, windowNs = 2_000_000_000L)
        now = 1_000_000_000L
        recorder.onProcessed(ingressNs = 1_100_000_000L)

        val state = recorder.state.value
        assertEquals(1, state.clockAnomalies)
        assertEquals(0.0, state.lastLatencyMs!!, 0.001)
    }

    @Test
    fun deviceHealthKeepsProcessAndDeviceMemorySeparate() {
        val recorder = PerformanceRecorder(clockNs = { 0L })
        recorder.onDeviceHealth(deviceUsedMemoryMb = 7_656, appPssMb = 512, thermalStatus = 1)

        val state = recorder.state.value
        assertEquals(7_656, state.deviceUsedMemoryMb)
        assertEquals(512, state.appPssMb)
        assertEquals(1, state.thermalStatus)
    }

    @Test
    fun frameGeometryIsReportedOnce() {
        val recorder = PerformanceRecorder(clockNs = { 0L })
        recorder.onFrameGeometry(width = 640, height = 480, rotationDegrees = 90)
        recorder.onAnalysisFormat("YUV_420_888")

        val state = recorder.state.value
        assertEquals(640, state.analysisWidth)
        assertEquals(480, state.analysisHeight)
        assertEquals(90, state.rotationDegrees)
        assertEquals("YUV_420_888", state.analysisFormat)
    }

    @Test
    fun sessionElapsedIsDerivedFromTheMonotonicClock() {
        var now = 0L
        val recorder = PerformanceRecorder(clockNs = { now }, wallClockMs = { 1_500L })
        now = 42_000_000_000L
        recorder.onCaptured()

        val state = recorder.state.value
        assertEquals(1_500L, state.sessionStartedAtEpochMs)
        assertEquals(42.0, state.sessionElapsedSeconds, 0.001)
    }

    @Test
    fun skippedFramesAreNotCountedAsProcessed() {
        var now = 0L
        val recorder = PerformanceRecorder(clockNs = { now }, windowNs = 2_000_000_000L)
        recorder.onCaptured()
        now = 1_000_000_000L
        recorder.onCaptured()
        recorder.onSkipped()
        recorder.onSkipped()

        val state = recorder.state.value
        assertEquals(2, state.capturedFrames)
        assertEquals(2, state.skippedFrames)
        assertEquals(0, state.processedFrames)
        assertEquals(0.0, state.processedFps, 0.001)
        assertEquals(null, state.lastLatencyMs)
    }
}
