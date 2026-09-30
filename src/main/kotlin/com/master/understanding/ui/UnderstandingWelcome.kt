package com.master.understanding.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.master.understanding.ide.UnderstandingService
import com.master.understanding.ide.resultUnreadSummary
import com.master.understanding.model.UnderstandingRecord
import org.jetbrains.jewel.ui.component.Text

/** An empty or reopened tool window starts from a compact, centered home. */
@Composable
internal fun UnderstandingWelcome(
    service: UnderstandingService,
    recentRecords: List<UnderstandingRecord>,
    modifier: Modifier,
    onSelectRecord: (UnderstandingRecord) -> Unit,
) {
    val unread = service.resultInbox.pending()
    Box(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 540.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("欢迎回来", fontSize = 22.sp, textAlign = TextAlign.Center)
            if (unread.isNotEmpty()) Text("未读：${resultUnreadSummary(unread)}", textAlign = TextAlign.Center)
            if (recentRecords.isEmpty()) {
                Text("完成代码理解后，图会出现在这里。", textAlign = TextAlign.Center)
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    recentRecords.take(4).forEach { record ->
                        RecordCandidateRow(record, selected = service.activeRecordId == record.id,
                            onClick = { onSelectRecord(record) })
                    }
                }
            }
        }
    }
}
