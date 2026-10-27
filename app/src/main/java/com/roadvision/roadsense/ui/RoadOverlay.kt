package com.roadvision.roadsense.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.roadvision.roadsense.inference.LaneResult
import com.roadvision.roadsense.tracking.TrackedVehicle

/**
 * Draws perception results inside the same Android view hierarchy as [androidx.camera.view.PreviewView].
 *
 * A Compose Canvas is hosted above Compose content but below AndroidView children on some devices,
 * so the camera preview can cover it. This View is added to the preview's FrameLayout after
 * PreviewView, which keeps the boxes visible without relying on interop z-order.
 */
class RoadOverlayView(context: Context) : View(context) {
    private var vehicles: List<TrackedVehicle> = emptyList()
    private var lanes: LaneResult? = null
    private var sourceWidth: Int = 0
    private var sourceHeight: Int = 0

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 200, 87)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val lanePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(57, 217, 138)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * resources.displayMetrics.scaledDensity
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }

    fun update(
        vehicles: List<TrackedVehicle>,
        lanes: LaneResult?,
        sourceWidth: Int,
        sourceHeight: Int,
    ) {
        this.vehicles = vehicles
        this.lanes = lanes
        this.sourceWidth = sourceWidth
        this.sourceHeight = sourceHeight
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sourceWidth <= 0 || sourceHeight <= 0 || width == 0 || height == 0) return
        val mapper = PreviewCoordinateMapper(sourceWidth, sourceHeight, width.toFloat(), height.toFloat())

        lanes?.lanes?.forEach { lane ->
            lane.zipWithNext().forEach { (start, end) ->
                val mappedStart = mapper.map(start.x, start.y)
                val mappedEnd = mapper.map(end.x, end.y)
                canvas.drawLine(mappedStart.x, mappedStart.y, mappedEnd.x, mappedEnd.y, lanePaint)
            }
        }

        vehicles.forEach { vehicle ->
            val box = vehicle.detection.box
            val topLeft = mapper.map(box.left, box.top)
            val bottomRight = mapper.map(box.right, box.bottom)
            val rect = RectF(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
            canvas.drawRect(rect, boxPaint)
            canvas.drawText(
                "#${vehicle.trackId} ${vehicle.detection.label} ${"%.0f%%".format(vehicle.detection.confidence * 100)}",
                rect.left,
                (rect.top - 8f).coerceAtLeast(labelPaint.textSize + 4f),
                labelPaint,
            )
        }
    }
}
