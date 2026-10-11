package com.lessplain.diagram

import com.lessplain.ide.GraphLayoutPreference
import com.intellij.util.ui.JBUI
import com.lessplain.ui.CanvasUiFonts
import com.lessplain.ui.UnderstandingUiMetrics
import java.awt.Dimension
import javax.swing.AbstractButton
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JSeparator

/** 即时画布设置使用普通控件，编辑不会确认或关闭弹层。 */
internal class DiagramBehaviorPanel(
    private val diagram: UnderstandingDiagram,
    onMetricsChanged: () -> Unit = {},
) : JPanel(), AutoCloseable {
    val resetZoom = JButton("恢复 100% 缩放").apply {
        addActionListener { diagram.zoom = 1.0 }
    }
    private val directions = GraphLayoutPreference.entries.associateWith { preference ->
        JRadioButton(when (preference) {
            GraphLayoutPreference.AUTO -> "方向：自动"
            GraphLayoutPreference.VERTICAL -> "方向：纵向"
            GraphLayoutPreference.HORIZONTAL -> "方向：横向"
        }).apply { addActionListener { diagram.setLayoutPreference(preference) } }
    }
    private val flow = JCheckBox("路径流动").apply {
        addActionListener { diagram.flowEnabled = isSelected }
    }
    private val navigate = JCheckBox("自动跳转源码").apply {
        addActionListener { diagram.navigationPinned = !isSelected }
    }
    private val follow = JCheckBox("自动跟随节点").apply {
        addActionListener { diagram.autoFollowNode = isSelected }
    }
    private val controls: List<AbstractButton> = buildList {
        add(resetZoom)
        addAll(directions.values)
        addAll(listOf(flow, navigate, follow))
    }
    private val fonts = CanvasUiFonts(controls, onRefreshed = {
        controls.forEach { it.maximumSize = Dimension(Int.MAX_VALUE, it.preferredSize.height) }
        revalidate()
        repaint()
        onMetricsChanged()
    })

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(UnderstandingUiMetrics.PAGE_PADDING)
        addControl(resetZoom)
        addSeparator()
        val group = ButtonGroup()
        directions.values.forEach { group.add(it); addControl(it) }
        addSeparator()
        listOf(flow, navigate, follow).forEach(::addControl)
        refresh()
    }

    /** 程序设置选择时不触发动作，也不再次写入共享画布状态。 */
    fun refresh() {
        directions.forEach { (preference, button) -> button.isSelected = diagram.layoutPreference == preference }
        flow.isSelected = diagram.flowEnabled
        navigate.isSelected = !diagram.navigationPinned
        follow.isSelected = diagram.autoFollowNode
    }

    private fun addControl(button: AbstractButton) {
        button.alignmentX = LEFT_ALIGNMENT
        button.maximumSize = Dimension(Int.MAX_VALUE, button.preferredSize.height)
        button.accessibleContext.accessibleName = button.text
        add(button)
    }

    private fun addSeparator() {
        add(JSeparator().apply {
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(UnderstandingUiMetrics.GAP + 1))
            border = JBUI.Borders.empty(UnderstandingUiMetrics.SMALL_GAP, 0)
        })
    }

    override fun close() = fonts.close()
}
