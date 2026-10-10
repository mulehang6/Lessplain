package com.master.lessplain.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import javax.swing.UIManager
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

/** Typography stays relative to the IDE font; only component geometry is plugin-owned. */
@Composable
internal fun UnderstandingSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = JewelTheme.defaultTextStyle.copy(fontWeight = FontWeight.SemiBold))
}

/** Groups related details using Islands spacing, without changing native control shapes. */
@Composable
internal fun UnderstandingDetailIsland(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(UnderstandingUiMetrics.OUTER_RADIUS.dp))
            .background(JewelTheme.globalColors.panelBackground).padding(UnderstandingUiMetrics.DETAIL_PADDING.dp),
        verticalArrangement = Arrangement.spacedBy(UnderstandingUiMetrics.SECTION_GAP.dp),
        content = content,
    )
}

/** Native list selection keeps the same blue and foreground as IDE chooser rows. */
@Composable
internal fun understandingListSelectionColors(): Pair<Color, Color> {
    // Reading the theme also invalidates these native colors when the bridge changes theme.
    val theme = JewelTheme.globalColors
    val background = UIManager.getColor("List.selectionBackground")?.let { Color(it.rgb) } ?: theme.outlines.focused
    val foreground = UIManager.getColor("List.selectionForeground")?.let { Color(it.rgb) } ?: theme.text.normal
    return background to foreground
}
