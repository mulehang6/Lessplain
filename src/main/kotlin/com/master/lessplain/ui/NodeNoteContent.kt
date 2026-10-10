package com.master.lessplain.ui

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.master.lessplain.model.UnderstandingQuestion
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
    Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.toolwindowBackground)
        .padding(UnderstandingUiMetrics.PAGE_PADDING.dp),
        verticalArrangement = Arrangement.spacedBy(UnderstandingUiMetrics.SECTION_GAP.dp)) {
        UnderstandingSectionTitle("节点注释")
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(UnderstandingUiMetrics.SECTION_GAP.dp)) {
            state.notes.forEach { note ->
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
                            OutlinedButton(onClick = { edit(note) }) { Text("编辑") }
                            OutlinedButton(onClick = { delete(note) }) { Text("删除") }
                        }
                    }
                }
            }
            state.drafts.filter { it.questionId.isEmpty() }.forEach { draft ->
                UnderstandingDetailIsland {
                    NoteDraftText("未保存注释", draft.text)
                    if (!readOnly) {
                        OutlinedButton(onClick = { continueDraft("") }) { Text("继续编辑") }
                    }
                }
            }
        }
        state.error?.let { Text(it, color = Color(UnderstandingUiStyle.failure.rgb)) }
        if (!readOnly) OutlinedButton(onClick = add) { Text("添加注释") }
    }
}

/** A retained draft belongs to its note and shares the island surface. */
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
