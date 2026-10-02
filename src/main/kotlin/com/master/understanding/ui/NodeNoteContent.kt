package com.master.understanding.ui

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.master.understanding.model.UnderstandingQuestion
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
    Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("节点注释", style = JewelTheme.defaultTextStyle.copy(fontWeight = FontWeight.SemiBold))
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.notes.forEach { note ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelectionContainer { Text(note.text, style = JewelTheme.defaultTextStyle) }
                    state.drafts.firstOrNull { it.questionId == note.id }?.let { draft ->
                        Text("未保存草稿", style = JewelTheme.defaultTextStyle.copy(fontWeight = FontWeight.SemiBold))
                        SelectionContainer { Text(draft.text, style = JewelTheme.defaultTextStyle) }
                        if (!readOnly) {
                            OutlinedButton(onClick = { continueDraft(note.id) }) { Text("继续编辑") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (!readOnly) {
                            OutlinedButton(onClick = { edit(note) }) { Text("编辑") }
                            OutlinedButton(onClick = { delete(note) }) { Text("删除") }
                        }
                    }
                }
            }
            state.drafts.filter { it.questionId.isEmpty() }.forEach { draft ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("未保存注释", style = JewelTheme.defaultTextStyle.copy(fontWeight = FontWeight.SemiBold))
                    SelectionContainer { Text(draft.text, style = JewelTheme.defaultTextStyle) }
                    if (!readOnly) {
                        OutlinedButton(onClick = { continueDraft("") }) { Text("继续编辑") }
                    }
                }
            }
        }
        state.error?.let { Text(it) }
        if (!readOnly) OutlinedButton(onClick = add) { Text("添加注释") }
    }
}
