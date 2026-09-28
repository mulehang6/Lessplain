package com.master.understanding.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.master.understanding.model.UnderstandingRecord
import com.master.understanding.model.UnderstandingQuestion
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

private val historyTimeFormat = DateTimeFormatter.ofPattern("MM/dd HH:mm")

/** A history entry grows with its text instead of clipping it to button height. */
@Composable
internal fun HistoryRecordRow(
    record: UnderstandingRecord,
    selected: Boolean,
    questionCount: Int,
    onClick: () -> Unit,
) {
    val colors = JewelTheme.globalColors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(8.dp)
    val background = when {
        selected -> colors.outlines.focused.copy(alpha = 0.14f)
        hovered -> colors.text.normal.copy(alpha = 0.06f)
        else -> colors.panelBackground
    }
    val border = if (selected) colors.outlines.focused else colors.borders.normal
    val time = remember(record.createdAt) {
        historyTimeFormat.format(Instant.ofEpochMilli(record.createdAt).atZone(ZoneId.systemDefault()))
    }

    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(background)
            .border(1.dp, border, shape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(record.question, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(time, color = colors.text.info, fontSize = 11.sp)
        }
        if (record.answer.isNotBlank()) {
            Text(
                record.answer.replace('\n', ' '),
                color = colors.text.info,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (questionCount > 0) Text("$questionCount 个追问", color = colors.text.info, fontSize = 11.sp)
    }
}

@Composable
internal fun HistoryQuestionRow(question: UnderstandingQuestion, answers: List<String>) {
    val colors = JewelTheme.globalColors
    val shape = RoundedCornerShape(8.dp)
    val time = remember(question.createdAt) {
        historyTimeFormat.format(Instant.ofEpochMilli(question.createdAt).atZone(ZoneId.systemDefault()))
    }
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(colors.panelBackground)
            .border(1.dp, colors.borders.normal, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(question.text, modifier = Modifier.weight(1f))
            Text(time, color = colors.text.info, fontSize = 11.sp)
        }
        if (answers.isEmpty()) {
            Text("尚无回答", color = colors.text.info, fontSize = 12.sp)
        } else {
            answers.forEach { answer ->
                Text(answer, color = colors.text.normal, fontSize = 12.sp)
            }
        }
    }
}
