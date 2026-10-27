package com.roadvision.roadsense.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.roadvision.roadsense.camera.CameraStatus
import com.roadvision.roadsense.data.DetectionEventEntity
import com.roadvision.roadsense.diagnostics.DiagnosticsState

private val PanelBackground = Color(0xF208110E)
private val WarnColor = Color(0xFFFFC857)
private val ErrorColor = Color(0xFFFF6B6B)
private val AccentColor = Color(0xFF39D98A)
private val MutedColor = Color(0xFFCAD5D0)

/** Width of the landscape diagnostics column; the camera picture keeps the rest of the window. */
val LANDSCAPE_SIDE_PANEL_WIDTH: Dp = 360.dp

/**
 * Landscape gives diagnostics and events a dedicated column so the camera picture keeps the full
 * window height on the left, instead of a bottom block that hides half of the road.
 */
@Composable
fun LandscapeSidePanel(
    status: CameraStatus,
    diagnostics: DiagnosticsState,
    modelState: ModelRuntimeState,
    vehicleCount: Int,
    modelsReady: Boolean,
    events: List<DetectionEventEntity>,
    onRestartCapture: () -> Unit,
    onDeleteEvent: (DetectionEventEntity) -> Unit,
    onDeleteAllEvents: () -> Unit,
    onReloadModels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .background(PanelBackground)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.End + WindowInsetsSides.Bottom,
                ),
            ),
    ) {
        Text(
            text = "诊断与事件",
            color = AccentColor,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 2.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            DiagnosticsPanel(status, diagnostics, modelState, vehicleCount)
            PlateEventList(events, onDeleteEvent, onDeleteAllEvents)
            StatusActions(
                status = status,
                modelsReady = modelsReady,
                modelState = modelState,
                onRestartCapture = onRestartCapture,
                onReloadModels = onReloadModels,
            )
        }
    }
}

/**
 * Portrait cannot afford the full diagnostics block above the picture, so it collapses into two
 * compact lines and expands into a height-capped scrollable panel only when asked. The picture and
 * its overlay therefore stay visible in both states.
 */
@Composable
fun PortraitControlPanel(
    status: CameraStatus,
    diagnostics: DiagnosticsState,
    modelState: ModelRuntimeState,
    vehicleCount: Int,
    modelsReady: Boolean,
    events: List<DetectionEventEntity>,
    expanded: Boolean,
    maxPanelHeight: Dp,
    onToggleExpanded: () -> Unit,
    onRestartCapture: () -> Unit,
    onDeleteEvent: (DetectionEventEntity) -> Unit,
    onDeleteAllEvents: () -> Unit,
    onReloadModels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(PanelBackground)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = cameraStatusLabel(status),
                    color = if (status is CameraStatus.Error) ErrorColor else AccentColor,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = compactDiagnosticsLine(diagnostics, modelState, vehicleCount),
                    color = MutedColor,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (status is CameraStatus.Error) {
                TextButton(onClick = onRestartCapture) { Text("重启相机") }
            }
            TextButton(onClick = onToggleExpanded) { Text(if (expanded) "收起" else "详情") }
        }
        if (!modelsReady) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = modelStatusText(modelState),
                    color = WarnColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (modelState is ModelRuntimeState.Failed) {
                    TextButton(onClick = onReloadModels) { Text("重新加载") }
                }
            }
        }
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxPanelHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                DiagnosticsPanel(status, diagnostics, modelState, vehicleCount)
                PlateEventList(events, onDeleteEvent, onDeleteAllEvents)
            }
        }
    }
}

/** Restart / reload affordances shared by both layouts. */
@Composable
private fun StatusActions(
    status: CameraStatus,
    modelsReady: Boolean,
    modelState: ModelRuntimeState,
    onRestartCapture: () -> Unit,
    onReloadModels: () -> Unit,
) {
    if (status is CameraStatus.Error) {
        Button(onClick = onRestartCapture, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text("重新启动相机")
        }
    }
    if (!modelsReady) {
        Text(
            modelStatusText(modelState),
            color = WarnColor,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        if (modelState is ModelRuntimeState.Failed) {
            Button(onClick = onReloadModels, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text("重新加载模型")
            }
        }
    }
}
