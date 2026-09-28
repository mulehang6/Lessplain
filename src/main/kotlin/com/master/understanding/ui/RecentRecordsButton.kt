package com.master.understanding.ui

import androidx.compose.runtime.Composable
import com.master.understanding.model.UnderstandingRecord
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/** Keeps the recent-record trigger in Compose, so it shares the tool window's theme and window lifecycle. */
@Composable
internal fun RecentRecordsButton(
    records: List<UnderstandingRecord>,
    onSelect: (UnderstandingRecord) -> Unit,
) {
    OutlinedButton(onClick = {
        showGraphPopup(records.map { record -> record.question to { onSelect(record) } })
    }, enabled = records.isNotEmpty()) { Text("最近 ▾") }
}
