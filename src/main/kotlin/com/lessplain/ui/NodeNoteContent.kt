package com.lessplain.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lessplain.model.UnderstandingQuestion
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.foundation.theme.JewelTheme

internal class NodeNoteContentState {
    var notes by mutableStateOf<List<UnderstandingQuestion>>(emptyList())
    var drafts by mutableStateOf<List<NodeNoteDraft>>(emptyList())
    var error by mutableStateOf<String?>(null)
}

internal data class NodeNoteDraft(val questionId: String, val text: String)

@Composable
internal fun NodeNoteContent(
    state: NodeNoteContentState,
    readOnly: Boolean,
    add: () -> Unit,
    edit: (UnderstandingQuestion) -> Unit,
    continueDraft: (String) -> Unit,
    delete: (UnderstandingQuestion) -> Unit,
) {
    var page by remember { mutableIntStateOf(0) }
    val extraDrafts = state.drafts.filter { it.questionId.isEmpty() }
    val count = state.notes.size + extraDrafts.size
    val currentPage = page.coerceIn(0, (count - 1).coerceAtLeast(0))
    Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.toolwindowBackground)
        .padding(UnderstandingUiMetrics.PAGE_PADDING.dp),
        verticalArrangement = Arrangement.spacedBy(UnderstandingUiMetrics.SECTION_GAP.dp)) {
        UnderstandingSectionTitle("节点注释")
        Row(horizontalArrangement = Arrangement.spacedBy(UnderstandingUiMetrics.GAP.dp)) {
            OutlinedButton(onClick = { page = (currentPage - 1).coerceAtLeast(0) }) { Text("上一页") }
            Text("${if (count == 0) 0 else currentPage + 1}/$count")
            OutlinedButton(onClick = { page = (currentPage + 1).coerceAtMost((count - 1).coerceAtLeast(0)) }) { Text("下一页") }
        }
        key(currentPage) {
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(UnderstandingUiMetrics.SECTION_GAP.dp)) {
            state.notes.getOrNull(currentPage)?.let { note ->
                UnderstandingDetailIsland {
                    SelectionContainer { Text(note.text, style = JewelTheme.defaultTextStyle) }
                    state.drafts.firstOrNull { it.questionId == note.id }?.let { draft ->
                        NoteDraftText("未保存草稿", draft.text)
                        if (!readOnly) {
                            OutlinedButton(onClick = { continueDraft(note.id) }) { Text("继续编辑") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(UnderstandingUiMetrics.GAP.dp)) {
                        if (!readOnly) {
                            OutlinedButton(onClick = { edit(note) }) { Text("查看／编辑") }
                            OutlinedButton(onClick = { delete(note) }) { Text("删除") }
                        }
                    }
                }
            }
            extraDrafts.getOrNull(currentPage - state.notes.size)?.let { draft ->
                UnderstandingDetailIsland {
                    NoteDraftText("未保存注释", draft.text)
                    if (!readOnly) {
                        OutlinedButton(onClick = { continueDraft("") }) { Text("继续编辑") }
                    }
                }
            }
        }
        }
        state.error?.let { Text(it, color = Color(UnderstandingUiStyle.failure.rgb)) }
        if (!readOnly) OutlinedButton(onClick = add) { Text("添加注释") }
    }
}

/** 保留草稿属于其注释，并共用 Islands 表面样式。 */
@Composable
private fun NoteDraftText(title: String, text: String) {
    val shape = RoundedCornerShape(UnderstandingUiMetrics.INNER_RADIUS.dp)
    Column(Modifier.fillMaxWidth().clip(shape)
        .border(1.dp, Color(UnderstandingUiStyle.border.rgb).copy(alpha = 0.35f), shape)
        .padding(UnderstandingUiMetrics.GAP.dp),
        verticalArrangement = Arrangement.spacedBy(UnderstandingUiMetrics.GAP.dp)) {
        Text(title, color = JewelTheme.globalColors.text.info)
        SelectionContainer { Text(text, style = JewelTheme.defaultTextStyle) }
    }
}
