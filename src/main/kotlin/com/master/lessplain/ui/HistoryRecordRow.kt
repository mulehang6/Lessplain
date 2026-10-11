package com.master.lessplain.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.border
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
import com.master.lessplain.model.UnderstandingRecord
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

/** 欢迎页和历史页使用同样紧凑的记录外观。 */
@Composable
internal fun RecordCandidateRow(
    record: UnderstandingRecord,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = JewelTheme.globalColors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(UnderstandingUiMetrics.INNER_RADIUS.dp)
    val (selectedBackground, selectedForeground) = understandingListSelectionColors()
    val fill = when {
        selected -> selectedBackground
        hovered || focused -> colors.text.normal.copy(alpha = 0.08f)
        else -> colors.panelBackground
    }
    Row(
        modifier.fillMaxWidth().clip(shape).background(fill)
            .border(1.dp, if (focused) colors.outlines.focused else androidx.compose.ui.graphics.Color.Transparent, shape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = UnderstandingUiMetrics.ROW_HORIZONTAL_PADDING.dp,
                vertical = UnderstandingUiMetrics.ROW_VERTICAL_PADDING.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(UnderstandingUiMetrics.SECTION_GAP.dp),
    ) {
        Text(record.displayTitle ?: record.question, color = if (selected) selectedForeground else colors.text.normal,
            modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onDelete != null && hovered) {
            Text("删除", color = if (selected) selectedForeground else colors.text.info,
                modifier = Modifier.clickable(role = Role.Button, onClick = onDelete))
        }
    }
}
