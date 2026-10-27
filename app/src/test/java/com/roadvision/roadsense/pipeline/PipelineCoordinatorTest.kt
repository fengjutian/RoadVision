package com.roadvision.roadsense.pipeline

import com.roadvision.roadsense.camera.ConvertedFrame
import com.roadvision.roadsense.data.DetectionEventEntity
import com.roadvision.roadsense.diagnostics.PerformanceRecorder
import com.roadvision.roadsense.inference.ColorOrder
import com.roadvision.roadsense.inference.Detection
import com.roadvision.roadsense.inference.LaneDetector
import com.roadvision.roadsense.inference.LanePoint
import com.roadvision.roadsense.inference.LaneResult
import com.roadvision.roadsense.inference.ModelContract
import com.roadvision.roadsense.inference.NormalizedRect
import com.roadvision.roadsense.inference.PlateCropper
import com.roadvision.roadsense.inference.PlateDetector
import com.roadvision.roadsense.inference.PlateRecognition
import com.roadvision.roadsense.inference.PlateRecognizer
import com.roadvision.roadsense.inference.TensorLayout
import com.roadvision.roadsense.inference.TensorSpec
import com.roadvision.roadsense.inference.VehicleDetector
import org.junit.Assert.assertEquals
import org.junit.Test

class PipelineCoordinatorTest {
    private val contract = ModelContract(
        "fake", "1", "0".repeat(64), TensorSpec("input", longArrayOf(1, 3, 10, 10)),
        listOf(TensorSpec("output", longArrayOf(1, 1, 6))), TensorLayout.NCHW, ColorOrder.RGB,
    )

    @Test
    fun coordinatesDetectionTrackingOcrFusionAndPersistence() {
        val results = mutableListOf<FusedPerceptionResult>()
        val events = mutableListOf<DetectionEventEntity>()
        val coordinator = PipelineCoordinator(
            vehicleDetector = object : VehicleDetector {
                override val contract = this@PipelineCoordinatorTest.contract
                override fun detect(frame: ConvertedFrame) = listOf(
                    Detection(0, "car", 0.9f, NormalizedRect(0f, 0f, 1f, 1f), frame.frameId, frame.timestampNs),
                )
            },
            laneDetector = object : LaneDetector {
                override val contract = this@PipelineCoordinatorTest.contract
                override fun detect(frame: ConvertedFrame) = LaneResult(
                    listOf(listOf(LanePoint(0f, 0f), LanePoint(1f, 1f))), 0.9f, frame.frameId, frame.timestampNs,
                )
            },
            plateDetector = object : PlateDetector {
                override val contract = this@PipelineCoordinatorTest.contract
                override fun detect(frame: ConvertedFrame) = listOf(
                    Detection(0, "plate", 0.9f, NormalizedRect(0.2f, 0.6f, 0.8f, 0.8f), frame.frameId, frame.timestampNs),
                )
            },
            recognizer = object : PlateRecognizer {
                override val contract = this@PipelineCoordinatorTest.contract
                override fun recognize(rgb: ByteArray, width: Int, height: Int) =
                    PlateRecognition("A12345", 0.9f, List(6) { 0.9f })
            },
            recorder = PerformanceRecorder(),
            providerName = "TEST",
            schedule = PipelineSchedule(1, 1),
            cropper = PlateCropper(outputWidth = 20, outputHeight = 8, minimumSourceWidth = 1, minimumSharpness = 0.0),
            onResult = results::add,
            onEvent = events::add,
        )
        coordinator.process(frame(1, 1_000_000_000L))
        coordinator.process(frame(2, 1_500_000_000L))

        assertEquals(2, results.size)
        assertEquals(results[0].vehicles.single().trackId, results[1].vehicles.single().trackId)
        assertEquals(1, events.size)
        assertEquals("A12345", events.single().plateText)
        assertEquals(100, results.last().sourceWidth)
    }

    private fun frame(id: Long, timestamp: Long) = ConvertedFrame(
        id, timestamp, 100, 100, 0, ByteArray(100 * 100 * 3) { (it % 251).toByte() },
    )
}
