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
    val errors: Long = 0,
    val lastLatencyMs: Double? = null,
    val latencyP50Ms: Double? = null,
    val latencyP95Ms: Double? = null,
    val inferenceP50Ms: Double? = null,
    val inferenceP95Ms: Double? = null,
    val memoryMb: Long = 0,
    val thermalStatus: Int? = null,
    val executionProvider: String = "DISABLED",
)

class PerformanceRecorder(
    private val clockNs: () -> Long = System::nanoTime,
    private val windowNs: Long = 2_000_000_000L,
) {
    private val captured = AtomicLong()
    private val processed = AtomicLong()
    private val dropped = AtomicLong()
    private val errors = AtomicLong()
    private val captureTimes = ArrayDeque<Long>()
    private val processedTimes = ArrayDeque<Long>()
    private val endToEndLatencies = ArrayDeque<Double>()
    private val inferenceLatencies = ArrayDeque<Double>()
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
        val latency = (now - frameTimestampNs).coerceAtLeast(0) / 1_000_000.0
        addBounded(endToEndLatencies, latency)
        publish(now, latency)
    }

    @Synchronized
    fun onInference(durationNs: Long, provider: String) {
        addBounded(inferenceLatencies, durationNs.coerceAtLeast(0) / 1_000_000.0)
        mutableState.value = mutableState.value.copy(
            inferenceP50Ms = percentile(inferenceLatencies, 0.50),
            inferenceP95Ms = percentile(inferenceLatencies, 0.95),
            executionProvider = provider,
        )
    }

    @Synchronized
    fun onDeviceHealth(memoryMb: Long, thermalStatus: Int?) {
        mutableState.value = mutableState.value.copy(memoryMb = memoryMb, thermalStatus = thermalStatus)
    }

    @Synchronized
    fun onDropped() {
        dropped.incrementAndGet()
        publish(clockNs(), null)
    }

    @Synchronized
    fun onError() {
        errors.incrementAndGet()
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
            errors = errors.get(),
            lastLatencyMs = latencyMs ?: mutableState.value.lastLatencyMs,
            latencyP50Ms = percentile(endToEndLatencies, 0.50),
            latencyP95Ms = percentile(endToEndLatencies, 0.95),
            inferenceP50Ms = mutableState.value.inferenceP50Ms,
            inferenceP95Ms = mutableState.value.inferenceP95Ms,
            memoryMb = mutableState.value.memoryMb,
            thermalStatus = mutableState.value.thermalStatus,
            executionProvider = mutableState.value.executionProvider,
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

    private fun addBounded(values: ArrayDeque<Double>, value: Double) {
        values.addLast(value)
        while (values.size > 600) values.removeFirst()
    }

    private fun percentile(values: ArrayDeque<Double>, quantile: Double): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * quantile).toInt().coerceIn(sorted.indices)]
    }
}
