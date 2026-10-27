package com.roadvision.roadsense.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    var revealedEventId by remember { mutableStateOf<Long?>(null) }
    Column(modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("本地事件（${events.size}）")
            Button(onClick = onDeleteAll) { Text("全部删除") }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
            items(events, key = DetectionEventEntity::id) { event ->
                val revealed = revealedEventId == event.id
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        "Track #${event.trackId} · " +
                            DateFormat.getDateTimeInstance().format(Date(event.observedAtEpochMs)),
                    )
                    Text(
                        (if (revealed) event.plateText ?: "<无结果>" else PrivacyLog.maskPlate(event.plateText)) +
                            " · 置信度 %.1f%%".format(event.confidence * 100) +
                            if (event.uncertain) " · 不确定" else "",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { revealedEventId = if (revealed) null else event.id }) {
                            Text(if (revealed) "隐藏车牌" else "查看详情")
                        }
                        Button(onClick = {
                            if (revealedEventId == event.id) revealedEventId = null
                            onDelete(event)
                        }) { Text("删除") }
                    }
                }
            }
        }
    }
}
