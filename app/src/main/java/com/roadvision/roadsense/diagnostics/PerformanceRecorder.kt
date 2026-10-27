package com.roadvision.roadsense.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/** Latency is measured from the analyzer ingress stamp, which shares the process clock. */
const val LATENCY_SOURCE_ANALYZER_INGRESS = "analyzer_ingress"

/**
 * A sensor-timestamp/ingress-timestamp offset larger than this means the camera clock base cannot be
 * compared with [System.nanoTime], so a sensor-derived latency would be meaningless.
 */
const val SENSOR_CLOCK_MISMATCH_MS = 1_000.0

data class DiagnosticsState(
    val cameraFps: Double = 0.0,
    val processedFps: Double = 0.0,
    val capturedFrames: Long = 0,
    val processedFrames: Long = 0,
    val skippedFrames: Long = 0,
    val droppedFrames: Long = 0,
    val errors: Long = 0,
    val lastLatencyMs: Double? = null,
    val latencyP50Ms: Double? = null,
    val latencyP95Ms: Double? = null,
    val latencySource: String = LATENCY_SOURCE_ANALYZER_INGRESS,
    /** Samples whose latency would have been negative; a sign of a clock jump, never hidden. */
    val clockAnomalies: Long = 0,
    val sensorClockOffsetMs: Double? = null,
    val sensorClockMismatch: Boolean = false,
    val inferenceP50Ms: Double? = null,
    val inferenceP95Ms: Double? = null,
    /** Whole-device used RAM (total - available); not this process's footprint. */
    val deviceUsedMemoryMb: Long = 0,
    /** This process's proportional set size. */
    val appPssMb: Long = 0,
    val thermalStatus: Int? = null,
    val analysisWidth: Int = 0,
    val analysisHeight: Int = 0,
    val rotationDegrees: Int = 0,
    val analysisFormat: String = "unknown",
    val sessionStartedAtEpochMs: Long = 0,
    val sessionElapsedSeconds: Double = 0.0,
    val executionProvider: String = "DISABLED",
)

class PerformanceRecorder(
    private val clockNs: () -> Long = System::nanoTime,
    private val windowNs: Long = 2_000_000_000L,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
) {
    private val captured = AtomicLong()
    private val processed = AtomicLong()
    private val skipped = AtomicLong()
    private val dropped = AtomicLong()
    private val errors = AtomicLong()
    private val clockAnomalies = AtomicLong()
    private val captureTimes = ArrayDeque<Long>()
    private val processedTimes = ArrayDeque<Long>()
    private val endToEndLatencies = ArrayDeque<Double>()
    private val inferenceLatencies = ArrayDeque<Double>()
    private val startNs = clockNs()
    private val startedAtEpochMs = wallClockMs()
    private val mutableState = MutableStateFlow(DiagnosticsState(sessionStartedAtEpochMs = startedAtEpochMs))
    val state: StateFlow<DiagnosticsState> = mutableState.asStateFlow()

    @Synchronized
    fun onCaptured() {
        val now = clockNs()
        captured.incrementAndGet()
        captureTimes.addLast(now)
        publish(now, null)
    }

    /**
     * @param ingressNs [ConvertedFrame.ingressNs]: the analyzer's `System.nanoTime()` reading.
     * @param sensorNs the camera sensor timestamp, recorded only to detect clock-base mismatch.
     */
    @Synchronized
    fun onProcessed(ingressNs: Long, sensorNs: Long? = null) {
        val now = clockNs()
        processed.incrementAndGet()
        processedTimes.addLast(now)
        val elapsedNs = now - ingressNs
        if (elapsedNs < 0) clockAnomalies.incrementAndGet()
        val latency = elapsedNs.coerceAtLeast(0) / 1_000_000.0
        addBounded(endToEndLatencies, latency)
        val offsetMs = sensorNs?.let { (it - ingressNs) / 1_000_000.0 }
        publish(now, latency, offsetMs)
    }

    /** A frame reached the scheduler while no pipeline was active; it was not analysed. */
    @Synchronized
    fun onSkipped() {
        skipped.incrementAndGet()
        publish(clockNs(), null)
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
    fun onDeviceHealth(deviceUsedMemoryMb: Long, appPssMb: Long, thermalStatus: Int?) {
        mutableState.value = mutableState.value.copy(
            deviceUsedMemoryMb = deviceUsedMemoryMb,
            appPssMb = appPssMb,
            thermalStatus = thermalStatus,
        )
    }

    @Synchronized
    fun onFrameGeometry(width: Int, height: Int, rotationDegrees: Int) {
        val current = mutableState.value
        if (
            current.analysisWidth == width &&
            current.analysisHeight == height &&
            current.rotationDegrees == rotationDegrees
        ) {
            return
        }
        mutableState.value = current.copy(
            analysisWidth = width,
            analysisHeight = height,
            rotationDegrees = rotationDegrees,
        )
    }

    @Synchronized
    fun onAnalysisFormat(format: String) {
        if (mutableState.value.analysisFormat == format) return
        mutableState.value = mutableState.value.copy(analysisFormat = format)
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

    private fun publish(now: Long, latencyMs: Double?, sensorOffsetMs: Double? = null) {
        trim(captureTimes, now)
        trim(processedTimes, now)
        mutableState.value = mutableState.value.copy(
            cameraFps = rate(captureTimes),
            processedFps = rate(processedTimes),
            capturedFrames = captured.get(),
            processedFrames = processed.get(),
            skippedFrames = skipped.get(),
            droppedFrames = dropped.get(),
            errors = errors.get(),
            lastLatencyMs = latencyMs ?: mutableState.value.lastLatencyMs,
            latencyP50Ms = percentile(endToEndLatencies, 0.50),
            latencyP95Ms = percentile(endToEndLatencies, 0.95),
            clockAnomalies = clockAnomalies.get(),
            sensorClockOffsetMs = sensorOffsetMs ?: mutableState.value.sensorClockOffsetMs,
            sensorClockMismatch = (sensorOffsetMs ?: mutableState.value.sensorClockOffsetMs)
                ?.let { abs(it) > SENSOR_CLOCK_MISMATCH_MS }
                ?: false,
            sessionElapsedSeconds = (now - startNs) / 1_000_000_000.0,
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
