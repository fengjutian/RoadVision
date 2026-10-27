package com.roadvision.roadsense.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roadvision.roadsense.data.DetectionEventEntity
import com.roadvision.roadsense.privacy.PrivacyLog
import java.text.DateFormat
import java.util.Date

@Composable
fun PlateEventList(
    events: List<DetectionEventEntity>,
    onDelete: (DetectionEventEntity) -> Unit,
    onDeleteAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (events.isEmpty()) return
    Column(modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("本地事件（${events.size}）")
            Button(onClick = onDeleteAll) { Text("全部删除") }
        }
        events.take(3).forEach { event ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "#${event.trackId} ${PrivacyLog.maskPlate(event.plateText)} · " +
                        DateFormat.getTimeInstance().format(Date(event.observedAtEpochMs)) +
                        if (event.uncertain) " · 不确定" else "",
                )
                Button(onClick = { onDelete(event) }) { Text("删除") }
            }
        }
    }
}
