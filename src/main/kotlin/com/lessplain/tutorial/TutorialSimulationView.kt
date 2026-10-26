package com.lessplain.tutorial

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Splitter
import com.intellij.ui.OnePixelSplitter
import com.intellij.util.ui.JBUI
import com.lessplain.ide.SourceSweepAnimation
import com.lessplain.ide.paintSourceSweep
import com.lessplain.ui.NativeMenuItem
import com.lessplain.ui.PopupUi
import com.lessplain.ui.RoundedPanel
import com.lessplain.ui.showNativeMenu
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Point
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.text.View

/** 模拟与原生工具栏共处一个边框内，播放和布局切换只更新本组件。 */
internal class TutorialSimulationView(private val editor: Editor, val playback: SimulationPlayback) : RoundedPanel(), AutoCloseable {
    val code = JTextPane().apply { isEditable = false }
    val terminal = JTextPane().apply { isEditable = false }
    private val codeFrame = textFrame("示例代码", code)
    private val terminalFrame = textFrame("模拟终端", terminal)
    val split = OnePixelSplitter(false, 0.62f).apply { setHonorComponentsMinimumSize(false) }
    private val body = JPanel(BorderLayout(0, JBUI.scale(PopupUi.GAP))).apply { isOpaque = false }
    private val status = JLabel()
    private var resource = playback.resource
    private val stepHighlights = mutableListOf<Any>()
    internal val animation = SourceSweepAnimation { code.repaint() }
    private var shownStep: Pair<String, Int>? = null
    private val visibility = java.awt.event.HierarchyListener { event ->
        if (event.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && !isShowing) animation.finish()
    }
    var stacked = false
        private set
    private var bodyLayout: Pair<Boolean, Boolean>? = null
    val actions: List<AnAction> = listOf(
        control("上一步", AllIcons.Actions.Back, { playback.stepIndex >= 0 }) { playback.previous(); updateStep() },
        control("下一步", AllIcons.Actions.Forward, { playback.stepIndex < playback.scenario.steps.lastIndex }) { playback.next(); updateStep() },
        control("重置", AllIcons.Actions.Refresh, { playback.stepIndex >= 0 }) { playback.reset(); updateStep() },
        control("切换情境", AllIcons.Actions.GroupBy) { event ->
            val anchor = event.inputEvent?.component as? javax.swing.JComponent ?: toolbar.component
            showNativeMenu(anchor, playback.resource.scenarios.mapIndexed { index, scenario ->
                NativeMenuItem(scenario.label) { playback.select(index); updateStep() }
            }, Point(0, anchor.height), project = editor.project)
        },
        control("切换横排／上下排", AllIcons.Actions.SplitHorizontally, { playback.hasTerminal }) { useStackedLayout(!stacked) },
    )
    val toolbar = ActionManager.getInstance().createActionToolbar("Lessplain.TutorialSimulation", DefaultActionGroup(actions), true).apply {
        setTargetComponent(this@TutorialSimulationView)
        setMinimumButtonSize(JBUI.size(PopupUi.ICON_SIZE))
        component.isOpaque = false
        component.border = JBUI.Borders.empty()
    }
    private val header = JPanel(BorderLayout(0, JBUI.scale(PopupUi.GAP))).apply {
        isOpaque = false
        add(JPanel(BorderLayout(JBUI.scale(PopupUi.GAP), 0)).apply {
            isOpaque = false
            add(JLabel("教学模拟").apply { toolTipText = "预设教学步骤，不代表真实运行结果" }, BorderLayout.WEST)
            add(toolbar.component, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(status, BorderLayout.SOUTH)
    }

    init {
        layout = BorderLayout(0, JBUI.scale(PopupUi.GAP))
        border = JBUI.Borders.empty(PopupUi.PADDING)
        add(header, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
        split.addPropertyChangeListener(Splitter.PROP_PROPORTION) { revalidate() }
        addHierarchyListener(visibility)
        refresh(resource)
    }

    fun refresh(value: TutorialSimulation) {
        if (playback.resource != value) playback.update(value)
        if (resource != value || code.text != value.code) {
            resource = value
            shownStep = null
            animation.reset()
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

    fun useStackedLayout(value: Boolean) {
        if (stacked == value) return
        stacked = value
        actions.last().templatePresentation.icon = if (value) AllIcons.Actions.SplitVertically else AllIcons.Actions.SplitHorizontally
        updateStep()
    }

    private fun updateStep() {
        status.text = "${playback.scenario.label} · ${playback.stepIndex + 1}/${playback.scenario.steps.size} · ${playback.step?.caption ?: "点击下一步开始"}"
        status.toolTipText = status.text
        if (terminal.text != playback.output) terminal.text = playback.output
        val nextLayout = stacked to playback.hasTerminal
        if (bodyLayout != nextLayout) {
            body.removeAll()
            split.firstComponent = null
            split.secondComponent = null
            if (stacked || !playback.hasTerminal) {
                body.add(codeFrame, BorderLayout.NORTH)
                if (playback.hasTerminal) body.add(terminalFrame, BorderLayout.CENTER)
            } else {
                split.firstComponent = codeFrame
                split.secondComponent = terminalFrame
                body.add(split, BorderLayout.CENTER)
            }
            bodyLayout = nextLayout
        }
        stepHighlights.forEach(code.highlighter::removeHighlight); stepHighlights.clear()
        var offset = 0
        resource.code.split('\n').forEachIndexed { index, line ->
            if (index + 1 in playback.step?.highlightLines.orEmpty() && line.isNotEmpty()) {
                stepHighlights += code.highlighter.addHighlight(offset, offset + line.length, TutorialRangePainter { g, region -> paintSourceSweep(g, region, animation.progress) })
            }
            offset += line.length + 1
        }
        val nextStep = playback.scenario.id to playback.stepIndex
        if (shownStep != nextStep) {
            shownStep = nextStep
            if (playback.step == null) animation.reset()
            else if (isShowing) animation.start() else animation.finish()
        }
        toolbar.updateActionsAsync()
        revalidate(); repaint()
    }

    override fun getPreferredSize(): Dimension {
        val available = width.coerceAtLeast(JBUI.scale(300))
        val contentWidth = (available - insets.left - insets.right).coerceAtLeast(1)
        val gap = JBUI.scale(PopupUi.GAP)
        val codeWidth = if (playback.hasTerminal && !stacked) ((contentWidth - gap) * split.proportion).toInt() else contentWidth
        val codeHeight = measure(codeFrame, code, codeWidth)
        val terminalHeight = if (playback.hasTerminal) measure(terminalFrame, terminal, if (stacked) contentWidth else contentWidth - codeWidth - gap) else 0
        val bodyHeight = if (stacked && playback.hasTerminal) codeHeight + gap + terminalHeight else maxOf(codeHeight, terminalHeight)
        return Dimension(available, insets.top + insets.bottom + header.preferredSize.height + gap + bodyHeight)
    }

    private fun measure(frame: RoundedPanel, pane: JTextPane, width: Int): Int {
        val w = (width - frame.insets.left - frame.insets.right - pane.insets.left - pane.insets.right).coerceAtLeast(1)
        val root = pane.ui.getRootView(pane)
        root.setSize(w.toFloat(), Float.MAX_VALUE)
        val height = kotlin.math.ceil(root.getPreferredSpan(View.Y_AXIS).toDouble()).toInt() + pane.insets.top + pane.insets.bottom +
            frame.insets.top + frame.insets.bottom + frame.getComponent(0).preferredSize.height + JBUI.scale(PopupUi.GAP)
        frame.preferredSize = Dimension(width.coerceAtLeast(1), height)
        return height
    }

    private fun textFrame(title: String, pane: JTextPane) = RoundedPanel(BorderLayout(0, JBUI.scale(PopupUi.GAP))).apply {
        border = JBUI.Borders.empty(PopupUi.GAP)
        add(JLabel(title), BorderLayout.NORTH)
        add(pane, BorderLayout.CENTER)
    }

    override fun close() {
        removeHierarchyListener(visibility)
        animation.close()
    }

    private fun control(text: String, icon: Icon, enabled: () -> Boolean = { true }, run: (AnActionEvent) -> Unit): AnAction =
        object : DumbAwareAction(text, text, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(event: AnActionEvent) { event.presentation.isEnabled = enabled() }
            override fun actionPerformed(event: AnActionEvent) { if (enabled()) run(event) }
        }
}
