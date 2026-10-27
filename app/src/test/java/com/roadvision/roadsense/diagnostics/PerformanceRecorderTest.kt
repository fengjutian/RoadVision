package com.roadvision.roadsense.diagnostics

import org.junit.Assert.assertEquals
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
    }
}
