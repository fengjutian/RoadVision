package com.roadvision.roadsense.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

data class DiagnosticsState(
    val cameraFps: Double = 0.0,
    val processedFps: Double = 0.0,
    val capturedFrames: Long = 0,
    val processedFrames: Long = 0,
    val droppedFrames: Long = 0,
    val lastLatencyMs: Double? = null,
)

class PerformanceRecorder(
    private val clockNs: () -> Long = System::nanoTime,
    private val windowNs: Long = 2_000_000_000L,
) {
    private val captured = AtomicLong()
    private val processed = AtomicLong()
    private val dropped = AtomicLong()
    private val captureTimes = ArrayDeque<Long>()
    private val processedTimes = ArrayDeque<Long>()
    private val mutableState = MutableStateFlow(DiagnosticsState())
    val state: StateFlow<DiagnosticsState> = mutableState.asStateFlow()

    @Synchronized
    fun onCaptured() {
        val now = clockNs()
        captured.incrementAndGet()
        captureTimes.addLast(now)
        publish(now, null)
    }

    @Synchronized
    fun onProcessed(frameTimestampNs: Long) {
        val now = clockNs()
        processed.incrementAndGet()
        processedTimes.addLast(now)
        publish(now, (now - frameTimestampNs).coerceAtLeast(0) / 1_000_000.0)
    }

    @Synchronized
    fun onDropped() {
        dropped.incrementAndGet()
        publish(clockNs(), null)
    }

    private fun publish(now: Long, latencyMs: Double?) {
        trim(captureTimes, now)
        trim(processedTimes, now)
        mutableState.value = DiagnosticsState(
            cameraFps = rate(captureTimes),
            processedFps = rate(processedTimes),
            capturedFrames = captured.get(),
            processedFrames = processed.get(),
            droppedFrames = dropped.get(),
            lastLatencyMs = latencyMs ?: mutableState.value.lastLatencyMs,
        )
    }

    private fun trim(times: ArrayDeque<Long>, now: Long) {
        while (times.isNotEmpty() && now - times.first() > windowNs) times.removeFirst()
    }

    private fun rate(times: ArrayDeque<Long>): Double {
        if (times.size < 2) return 0.0
        val elapsedSeconds = (times.last() - times.first()) / 1_000_000_000.0
        return if (elapsedSeconds > 0) (times.size - 1) / elapsedSeconds else 0.0
    }
}
