package com.lessplain.ui

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
import com.lessplain.ide.UnderstandingService
import com.lessplain.ide.resultUnreadSummary
import com.lessplain.model.UnderstandingRecord
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

/** 空的或重新打开的工具窗口从紧凑居中的首页开始。 */
@Composable
internal fun UnderstandingWelcome(
    service: UnderstandingService,
    recentRecords: List<UnderstandingRecord>,
    modifier: Modifier,
    onSelectRecord: (UnderstandingRecord) -> Unit,
) {
    val unread = service.resultInbox.pending()
    Box(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(UnderstandingUiMetrics.DETAIL_PADDING.dp),
        contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 540.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(UnderstandingUiMetrics.SECTION_GAP.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            UnderstandingSectionTitle("欢迎回来")
            if (unread.isNotEmpty()) Text("未读：${resultUnreadSummary(unread)}", textAlign = TextAlign.Center)
            if (recentRecords.isEmpty()) {
                Text("完成代码理解后，图会出现在这里。", color = JewelTheme.globalColors.text.info,
                    textAlign = TextAlign.Center)
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(UnderstandingUiMetrics.SMALL_GAP.dp)) {
                    recentRecords.take(4).forEach { record ->
                        RecordCandidateRow(record, selected = service.activeRecordId == record.id,
                            onClick = { onSelectRecord(record) })
                    }
                }
            }
        }
    }
}
