package com.master.understanding.diagram

import com.master.understanding.ide.GraphLayoutPreference
import java.awt.Dimension
import javax.swing.AbstractButton
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JSeparator

/** Immediate canvas settings use ordinary controls, so editing never accepts or closes the popup. */
internal class DiagramBehaviorPanel(private val diagram: UnderstandingDiagram) : JPanel() {
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

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        addControl(resetZoom)
        addSeparator()
        val group = ButtonGroup()
        directions.values.forEach { group.add(it); addControl(it) }
        addSeparator()
        listOf(flow, navigate, follow).forEach(::addControl)
        refresh()
    }

    /** Programmatic selection does not emit actions or write the shared canvas state again. */
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
            maximumSize = Dimension(Int.MAX_VALUE, 9)
            border = BorderFactory.createEmptyBorder(4, 0, 4, 0)
        })
    }
}
