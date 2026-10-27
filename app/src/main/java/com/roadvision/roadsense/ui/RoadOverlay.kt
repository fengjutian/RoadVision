package com.roadvision.roadsense.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import com.roadvision.roadsense.inference.LaneResult
import com.roadvision.roadsense.tracking.TrackedVehicle

@Composable
fun RoadOverlay(
    vehicles: List<TrackedVehicle>,
    lanes: LaneResult?,
    sourceWidth: Int,
    sourceHeight: Int,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // Created once per composition instead of once per vehicle per frame.
    val labelPaint = remember(density) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = with(density) { 12.sp.toPx() }
            setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
        }
    }
    Canvas(modifier.fillMaxSize()) {
        if (sourceWidth <= 0 || sourceHeight <= 0) return@Canvas
        val mapper = PreviewCoordinateMapper(sourceWidth, sourceHeight, size.width, size.height)
        lanes?.lanes?.forEach { lane ->
            lane.zipWithNext().forEach { (start, end) ->
                val mappedStart = mapper.map(start.x, start.y)
                val mappedEnd = mapper.map(end.x, end.y)
                drawLine(
                    color = Color(0xFF39D98A),
                    start = Offset(mappedStart.x, mappedStart.y),
                    end = Offset(mappedEnd.x, mappedEnd.y),
                    strokeWidth = 5f,
                )
            }
        }
        vehicles.forEach { vehicle ->
            val box = vehicle.detection.box
            val topLeft = mapper.map(box.left, box.top)
            val bottomRight = mapper.map(box.right, box.bottom)
            val rect = Rect(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
            drawRect(Color(0xFFFFC857), rect.topLeft, rect.size, style = Stroke(width = 4f))
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText(
                    "#${vehicle.trackId} ${vehicle.detection.label} %.0f%%".format(vehicle.detection.confidence * 100),
                    rect.left, (rect.top - 8f).coerceAtLeast(labelPaint.textSize + 4f), labelPaint,
                )
            }
        }
    }
}
