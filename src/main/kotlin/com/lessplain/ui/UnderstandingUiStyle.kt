package com.lessplain.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import javax.swing.UIManager
import kotlin.math.roundToInt

/** 从 Jewel 的 IDE 桥所用的同一 Swing 主题中读取图与导航颜色。 */
internal object UnderstandingUiStyle {
    val NODE_ARC: Int get() = JBUI.scale(UnderstandingUiMetrics.INNER_RADIUS * 2)
    val GROUP_ARC: Int get() = JBUI.scale(UnderstandingUiMetrics.OUTER_RADIUS * 2)
    val CONTROL_ARC: Int get() = JBUI.scale(UIManager.getInt("Component.arc").takeIf { it > 0 } ?: 12)

    val canvas: Color get() = color(0xF7F8FA, 0x1E1F22, "EditorPane.background", "Panel.background")
    val surface: Color get() = color(0xFFFFFF, 0x2B2D30, "Panel.background")
    val listSelection: Color get() = color(0xD4E2FF, 0x2E436E, "List.selectionBackground")
    val listSelectionForeground: Color get() = color(0x1E1F22, 0xDFE1E5, "List.selectionForeground", "Label.foreground")
    val foreground: Color get() = color(0x1E1F22, 0xDFE1E5, "Label.foreground")
    val muted: Color get() = color(0x6C707E, 0x9DA0A8, "Label.infoForeground", "Label.disabledForeground")
    val border: Color get() = color(0xD3D5DB, 0x4E5157, "Component.borderColor", "Separator.foreground")
    // 通用组件焦点边框在 Dark 和 Islands 主题下可能与背景色相同。
    val accent: Color get() = listOf("ActionButton.focusedBorderColor", "Component.focusColor", "Link.activeForeground")
        .firstNotNullOfOrNull { key -> colorOr(key)?.takeIf { it.alpha == 255 } }
        ?: JBColor(Color(0x3574F0), Color(0x548AF7))
    val graphSelection: Color get() = JBColor.namedColor("Lessplain.Graph.selection", 0x8060DB, 0x967BEF)
    val warning: Color get() = color(0xA86400, 0xD6AE58, "Component.warningFocusColor")
    val success: Color get() = color(0x278047, 0x5FAD65, "Lessplain.Graph.successForeground")
    val failure: Color get() = color(0xC02D3E, 0xDB5860, "Component.errorFocusColor")

    val group: Color get() = blend(canvas, surface, 0.55)
    val groupBorder: Color get() = blend(canvas, border, 0.8)
    val edge: Color get() = blend(canvas, foreground, 0.6)
    val focusHalo: Color get() = alpha(accent, 36)
    val focusSurface: Color get() = blend(surface, accent, 0.09)
    val graphSelectionHalo: Color get() = alpha(graphSelection, 36)
    val graphSelectionSurface: Color get() = blend(surface, graphSelection, 0.09)
    val nodeHoverBorder: Color get() = blend(surface, foreground, 0.55)
    val successSurface: Color get() = blend(surface, success, 0.09)
    val failureSurface: Color get() = blend(surface, failure, 0.09)
    val neutralSurface: Color get() = blend(surface, muted, 0.08)
    val flow: Color get() = blend(accent, Color.WHITE, 0.78)

    val controlForeground: Color get() = color(0x43454A, 0xDFE1E5, "Button.foreground", "Label.foreground")
    val disabledForeground: Color get() = color(0xA8ADBD, 0x6F737A,
        "Button.disabledText", "Label.disabledForeground")
    val controlHover: Color get() = colorOr("ActionButton.hoverBackground") ?: blend(surface, accent, 0.1)
    val controlPressed: Color get() = colorOr("ActionButton.pressedBackground") ?: blend(surface, accent, 0.18)
    val tooltipBackground: Color get() = colorOr("ToolTip.background") ?: surface
    val tooltipForeground: Color get() = colorOr("ToolTip.foreground") ?: foreground

    fun alpha(color: Color, opacity: Int): Color = Color(color.red, color.green, color.blue, opacity)

    private fun color(light: Int, dark: Int, vararg keys: String): Color =
        keys.firstNotNullOfOrNull(::colorOr) ?: JBColor(Color(light), Color(dark))

    private fun colorOr(key: String): Color? = UIManager.getColor(key)

    private fun blend(background: Color, foreground: Color, amount: Double): Color {
        fun channel(from: Int, to: Int): Int = (from + (to - from) * amount).roundToInt()
        return Color(channel(background.red, foreground.red), channel(background.green, foreground.green),
            channel(background.blue, foreground.blue))
    }
}
