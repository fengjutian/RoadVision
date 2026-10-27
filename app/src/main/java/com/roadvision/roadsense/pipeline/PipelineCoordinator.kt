package com.roadvision.roadsense.pipeline

import com.roadvision.roadsense.camera.ConvertedFrame
import com.roadvision.roadsense.data.DetectionEventEntity
import com.roadvision.roadsense.diagnostics.PerformanceRecorder
import com.roadvision.roadsense.inference.ImagePoint
import com.roadvision.roadsense.inference.LaneDetector
import com.roadvision.roadsense.inference.LaneResult
import com.roadvision.roadsense.inference.PlateCropper
import com.roadvision.roadsense.inference.PlateDetector
import com.roadvision.roadsense.inference.PlateQuad
import com.roadvision.roadsense.inference.PlateRecognizer
import com.roadvision.roadsense.inference.VehicleDetector
import com.roadvision.roadsense.tracking.FusedOcrResult
import com.roadvision.roadsense.tracking.OcrObservation
import com.roadvision.roadsense.tracking.OcrTemporalFusion
import com.roadvision.roadsense.tracking.PlateCandidate
import com.roadvision.roadsense.tracking.PlateVehicleMatcher
import com.roadvision.roadsense.tracking.TrackedVehicle
import com.roadvision.roadsense.tracking.VehicleTracker

data class FusedPerceptionResult(
    val frameId: Long,
    val timestampNs: Long,
    val vehicles: List<TrackedVehicle>,
    val lanes: LaneResult?,
    val plates: List<FusedOcrResult>,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

data class PipelineSchedule(
    val laneEveryFrames: Int = 3,
    val plateEveryFrames: Int = 3,
) {
    init { require(laneEveryFrames > 0 && plateEveryFrames > 0) }
}

class PipelineCoordinator(
    private val vehicleDetector: VehicleDetector,
    private val laneDetector: LaneDetector,
    private val plateDetector: PlateDetector,
    private val recognizer: PlateRecognizer,
    private val recorder: PerformanceRecorder,
    private val providerName: String,
    private val schedule: PipelineSchedule = PipelineSchedule(),
    private val tracker: VehicleTracker = VehicleTracker(),
    private val cropper: PlateCropper = PlateCropper(),
    private val ocrScheduler: OcrScheduler = OcrScheduler(),
    private val ocrFusion: OcrTemporalFusion = OcrTemporalFusion(),
    private val matcher: PlateVehicleMatcher = PlateVehicleMatcher(),
    private val onResult: (FusedPerceptionResult) -> Unit,
    private val onEvent: (DetectionEventEntity) -> Unit,
) {
    private var lastLane: LaneResult? = null
    private val persistedTracks = mutableSetOf<Long>()

    fun process(frame: ConvertedFrame) {
        val started = System.nanoTime()
        val vehicles = tracker.update(vehicleDetector.detect(frame), frame.timestampNs)
            .filter { it.missedFrames == 0 }
        if (frame.frameId % schedule.laneEveryFrames == 0L) lastLane = laneDetector.detect(frame)
        val fusedPlates = if (frame.frameId % schedule.plateEveryFrames == 0L) {
            processPlates(frame, vehicles)
        } else emptyList()
        recorder.onInference(System.nanoTime() - started, providerName)
        onResult(
            FusedPerceptionResult(
                frame.frameId, frame.timestampNs, vehicles, lastLane, fusedPlates, frame.width, frame.height,
            ),
        )
    }

    private fun processPlates(frame: ConvertedFrame, vehicles: List<TrackedVehicle>): List<FusedOcrResult> {
        val detections = plateDetector.detect(frame)
        val candidates = detections.map { PlateCandidate(it.box, it.confidence, it.timestampNs) }
        val matches = matcher.match(candidates, vehicles).associateBy { it.plate }
        return candidates.mapNotNull { candidate ->
            val trackId = matches[candidate]?.trackId ?: return@mapNotNull null
            val box = candidate.box
            val crop = cropper.crop(
                frame,
                PlateQuad(
                    ImagePoint(box.left, box.top), ImagePoint(box.right, box.top),
                    ImagePoint(box.right, box.bottom), ImagePoint(box.left, box.bottom),
                ),
            )
            if (!ocrScheduler.shouldRun(trackId, frame.timestampNs, crop.acceptable)) return@mapNotNull null
            val recognition = recognizer.recognize(crop.rgb, crop.width, crop.height) ?: return@mapNotNull null
            val fused = ocrFusion.add(OcrObservation(trackId, recognition.text, recognition.confidence, frame.timestampNs))
            if (!fused.uncertain && persistedTracks.add(trackId)) {
                onEvent(
                    DetectionEventEntity(
                        trackId = trackId,
                        plateText = fused.text,
                        confidence = fused.confidence,
                        uncertain = false,
                        observedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            }
            fused
        }
    }
}
