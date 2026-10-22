package com.lessplain.tutorial

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.util.ui.JBUI
import com.intellij.ui.OnePixelSplitter
import com.intellij.openapi.ui.Splitter
import com.lessplain.ide.paintSourceSweep
import com.lessplain.ui.NativeMenuItem
import com.lessplain.ui.showNativeMenu
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextPane

/** 真实按钮与分隔条承载预设教学步骤，更新播放状态不重建教程。 */
internal class TutorialSimulationView(private val editor: Editor, val playback: SimulationPlayback) : JPanel(BorderLayout(0, JBUI.scale(8))) {
    val buttons = listOf(JButton("上一步"), JButton("下一步"), JButton("重置"), JButton("情境 ▾"))
    val code = JTextPane().apply { isEditable = false }
    val terminal = JTextPane().apply { isEditable = false }
    val split = OnePixelSplitter(false, 0.62f).apply {
        setHonorComponentsMinimumSize(false)
        firstComponent = code
        secondComponent = terminal
    }
    private val status = JLabel()
    private var resource = playback.resource
    private val stepHighlights = mutableListOf<Any>()

    init {
        val header = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            add(JLabel("教学模拟 · 预设情境，不代表真实运行结果"), BorderLayout.NORTH)
            add(JPanel(GridLayout(1, 4, JBUI.scale(8), 0)).apply {
                buttons.forEach { button -> add(JPanel(FlowLayout(FlowLayout.CENTER, 0, 0)).apply { add(button) }) }
            }, BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
        }
        add(header, BorderLayout.NORTH)
        add(split, BorderLayout.CENTER)
        buttons[0].addActionListener { playback.previous(); updateStep() }
        buttons[1].addActionListener { playback.next(); updateStep() }
        buttons[2].addActionListener { playback.reset(); updateStep() }
        buttons[3].addActionListener {
            showNativeMenu(buttons[3], playback.resource.scenarios.mapIndexed { index, scenario ->
                NativeMenuItem(scenario.label) { playback.select(index); updateStep() }
            }, project = editor.project)
        }
        split.addPropertyChangeListener(Splitter.PROP_PROPORTION) { revalidate() }
        refresh(resource)
    }

    fun refresh(value: TutorialSimulation) {
        if (playback.resource != value) playback.update(value)
        if (resource != value || code.text != value.code) {
            resource = value
            code.text = value.code
        }
        val font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        listOf(code, terminal).forEach { pane ->
            pane.font = font
            pane.foreground = editor.colorsScheme.defaultForeground
            pane.background = editor.colorsScheme.defaultBackground
        }
        styleTutorialCode(code.styledDocument, 0, value.code, value.language, editor)
        updateStep()
    }

    private fun updateStep() {
        buttons[0].isEnabled = playback.stepIndex >= 0
        buttons[1].isEnabled = playback.stepIndex < playback.scenario.steps.lastIndex
        status.text = "${playback.scenario.label} · ${playback.stepIndex + 1}/${playback.scenario.steps.size} · ${playback.step?.caption ?: "点击下一步开始"}"
        status.toolTipText = status.text
        if (terminal.text != playback.output) terminal.text = playback.output
        terminal.toolTipText = "模拟终端"
        if (playback.hasTerminal && split.secondComponent == null) split.secondComponent = terminal
        else if (!playback.hasTerminal && split.secondComponent != null) split.secondComponent = null
        stepHighlights.forEach(code.highlighter::removeHighlight); stepHighlights.clear()
        val lines = resource.code.split('\n')
        var offset = 0
        lines.forEachIndexed { index, line ->
            if (index + 1 in playback.step?.highlightLines.orEmpty() && line.isNotEmpty()) {
                stepHighlights += code.highlighter.addHighlight(offset, offset + line.length, TutorialRangePainter { g, region -> paintSourceSweep(g, region) })
            }
            offset += line.length + 1
        }
        revalidate(); repaint()
    }

    override fun getPreferredSize(): Dimension {
        val available = width.coerceAtLeast(JBUI.scale(300))
        val codeWidth = if (playback.hasTerminal) (available * split.proportion).toInt() else available
        fun height(pane: JTextPane, w: Int): Int {
            val insets = pane.insets
            val root = pane.ui.getRootView(pane)
            root.setSize((w - insets.left - insets.right).coerceAtLeast(1).toFloat(), Float.MAX_VALUE)
            return kotlin.math.ceil(root.getPreferredSpan(javax.swing.text.View.Y_AXIS).toDouble()).toInt() + insets.top + insets.bottom
        }
        val textHeight = maxOf(height(code, codeWidth), if (playback.hasTerminal) height(terminal, available - codeWidth) else 0)
        return Dimension(available, textHeight + getComponent(0).preferredSize.height + JBUI.scale(12))
    }
}
