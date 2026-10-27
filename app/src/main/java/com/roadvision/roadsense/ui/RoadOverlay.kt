package com.roadvision.roadsense.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.roadvision.roadsense.inference.LaneResult
import com.roadvision.roadsense.tracking.TrackedVehicle

@Composable
fun RoadOverlay(vehicles: List<TrackedVehicle>, lanes: LaneResult?, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        lanes?.lanes?.forEach { lane ->
            lane.zipWithNext().forEach { (start, end) ->
                drawLine(
                    color = Color(0xFF39D98A),
                    start = Offset(start.x * size.width, start.y * size.height),
                    end = Offset(end.x * size.width, end.y * size.height),
                    strokeWidth = 5f,
                )
            }
        }
        vehicles.forEach { vehicle ->
            val box = vehicle.detection.box
            val rect = Rect(box.left * size.width, box.top * size.height, box.right * size.width, box.bottom * size.height)
            drawRect(Color(0xFFFFC857), rect.topLeft, rect.size, style = Stroke(width = 4f))
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 34f
                    isAntiAlias = true
                    setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
                }
                canvas.nativeCanvas.drawText(
                    "#${vehicle.trackId} ${vehicle.detection.label} %.0f%%".format(vehicle.detection.confidence * 100),
                    rect.left, (rect.top - 8f).coerceAtLeast(36f), paint,
                )
            }
        }
    }
}
