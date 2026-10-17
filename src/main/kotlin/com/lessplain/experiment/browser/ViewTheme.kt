package com.lessplain.experiment.browser

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.util.ui.UIUtil
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.Color
import com.lessplain.ui.UnderstandingUiStyle
import kotlin.math.pow
import kotlin.math.roundToInt

/** IDE 主题令牌映射到前端 CSS；不向组件暴露平台对象。 */
internal fun theme() = buildJsonObject {
    val scheme = EditorColorsManager.getInstance().globalScheme
    val background = UIUtil.getPanelBackground()
    val dark = background.red + background.green + background.blue < 384
    fun color(key: String, value: Color) { put(key, cssColor(value)) }
    themePalette(
        background, UnderstandingUiStyle.foreground, UnderstandingUiStyle.muted,
        UnderstandingUiStyle.disabledForeground, UnderstandingUiStyle.border, UnderstandingUiStyle.accent,
        UnderstandingUiStyle.controlForeground, UnderstandingUiStyle.controlHover, UnderstandingUiStyle.controlPressed,
    ).forEach { (key, value) -> color(key, value) }
    color("--diagram-purple", UnderstandingUiStyle.graphSelection)
    color("--diagram-warning", UnderstandingUiStyle.warning)
    color("--diagram-success", UnderstandingUiStyle.success)
    color("--diagram-failure", UnderstandingUiStyle.failure)
    put("--ide-code-font", scheme.editorFontName)
    put("--ide-font", UIUtil.getLabelFont().family)
    put("--ide-font-size", UIUtil.getLabelFont().size.toString() + "px")
    put("--ide-code-font-size", scheme.editorFontSize.toString() + "px")
    put("--ide-color-scheme", if (dark) "dark" else "light")
}

/** 区分文本语义与控件状态；半透明原色留给控件，菜单使用在页面背景上合成的颜色。 */
internal fun themePalette(
    background: Color,
    foreground: Color,
    muted: Color,
    disabled: Color,
    border: Color,
    primary: Color,
    control: Color,
    hover: Color,
    pressed: Color,
): Map<String, Color> {
    val text = readableForeground(background, foreground)
    val composite = blend(background, hover, hover.alpha / 255.0)
    val accent = if (listOf(composite.red - background.red, composite.green - background.green,
            composite.blue - background.blue).maxOf { kotlin.math.abs(it) } >= 12) composite
        else blend(background, text, 0.12)
    return mapOf(
        "--background" to background,
        "--card" to background,
        "--foreground" to text,
        "--muted" to blend(background, text, 0.07),
        "--muted-foreground" to readableForeground(background, muted),
        "--disabled-foreground" to disabled,
        "--border" to border,
        "--primary" to primary,
        "--primary-foreground" to readableForeground(primary, Color.WHITE),
        "--accent" to accent,
        "--accent-foreground" to readableForeground(accent, control),
        "--control-foreground" to readableForeground(background, control),
        "--control-hover" to hover,
        "--control-pressed" to pressed,
    )
}

private fun blend(background: Color, foreground: Color, amount: Double): Color {
    fun channel(from: Int, to: Int) = (from + (to - from) * amount).roundToInt()
    return Color(channel(background.red, foreground.red), channel(background.green, foreground.green),
        channel(background.blue, foreground.blue))
}

/** 自定义主题给出不可读前景色时，回退到该背景上对比更高的黑或白。 */
internal fun readableForeground(background: Color, preferred: Color): Color {
    fun luminance(color: Color): Double {
        fun linear(value: Int): Double = (value / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }
    fun contrast(foreground: Color): Double {
        val a = luminance(background)
        val b = luminance(blend(background, foreground, foreground.alpha / 255.0))
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
    return if (contrast(preferred) >= 4.5) preferred else listOf(Color.BLACK, Color.WHITE).maxBy(::contrast)
}

/** Swing 的半透明悬停色必须原样交给 CSS 合成，不能丢掉 alpha 变成纯白。 */
internal fun cssColor(value: Color): String = if (value.alpha == 255) {
    "#%02x%02x%02x".format(value.red, value.green, value.blue)
} else {
    "rgba(${value.red},${value.green},${value.blue},${value.alpha / 255.0})"
}
