package com.lessplain.ui

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

/** 字体始终相对于 IDE 字体，仅组件几何由插件控制。 */
@Composable
internal fun UnderstandingSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = JewelTheme.defaultTextStyle.copy(fontWeight = FontWeight.SemiBold))
}

/** 按 Islands 间距组织相关细节，不改变原生控件形状。 */
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

/** 原生列表选中项使用与 IDE 选择器相同的蓝色和前景色。 */
@Composable
internal fun understandingListSelectionColors(): Pair<Color, Color> {
    // 读取主题也使这些原生颜色在桥接主题变化时更新。
    val theme = JewelTheme.globalColors
    val background = UIManager.getColor("List.selectionBackground")?.let { Color(it.rgb) } ?: theme.outlines.focused
    val foreground = UIManager.getColor("List.selectionForeground")?.let { Color(it.rgb) } ?: theme.text.normal
    return background to foreground
}
