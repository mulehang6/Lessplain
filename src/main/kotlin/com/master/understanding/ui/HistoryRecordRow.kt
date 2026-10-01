package com.master.understanding.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.master.understanding.model.UnderstandingRecord
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

/** The same compact record treatment is used on welcome and history pages. */
@Composable
internal fun RecordCandidateRow(
    record: UnderstandingRecord,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val colors = JewelTheme.globalColors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(7.dp)
    val fill = when {
        selected -> colors.outlines.focused.copy(alpha = 0.42f)
        hovered -> colors.text.normal.copy(alpha = 0.08f)
        else -> colors.panelBackground
    }
    Row(
        Modifier.fillMaxWidth().clip(shape).background(fill)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(record.displayTitle ?: record.question, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onDelete != null && hovered) {
            Text("删除", color = colors.text.info,
                modifier = Modifier.clickable(role = Role.Button, onClick = onDelete))
        }
    }
}
