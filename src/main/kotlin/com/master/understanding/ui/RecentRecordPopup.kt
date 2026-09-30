package com.master.understanding.ui

import com.intellij.ui.JBColor
import com.master.understanding.ide.UnderstandingService
import com.master.understanding.model.UnderstandingRecord
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel

/** Recent records use the same compact single-line states as the history list. */
internal fun showRecentRecordPopup(service: UnderstandingService, anchor: Component) {
    val records = service.repository.recentRootRecords("", 6)
    val popup = JPopupMenu()
    if (records.isEmpty()) {
        popup.add(JLabel("暂无记录").apply { border = BorderFactory.createEmptyBorder(8, 14, 8, 14) })
        popup.show(anchor, 0, anchor.height)
        return
    }
    val list = JList(records.toTypedArray()).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = records.size
        fixedCellHeight = 38
        preferredSize = Dimension(340, records.size * fixedCellHeight)
        var hoveredIndex = -1
        cellRenderer = ListCellRenderer<UnderstandingRecord> { _, value, index, selected, _ ->
            val active = service.activeRecordId == value.id || selected
            val fill = when {
                active -> JBColor(Color(0xC9DBFF), Color(0x294477))
                index == hoveredIndex -> JBColor(Color(0xEEF0F3), Color(0x303237))
                else -> JBColor(Color.WHITE, Color(0x1E1F22))
            }
            JPanel(BorderLayout(12, 0)).apply {
                background = fill
                border = BorderFactory.createEmptyBorder(0, 12, 0, 12)
                add(JLabel("✳").apply { foreground = JBColor(Color(0xD77932), Color(0xF39B5E)) }, BorderLayout.WEST)
                add(JLabel(value.displayTitle ?: value.question).apply {
                    foreground = JBColor.foreground()
                }, BorderLayout.CENTER)
                accessibleContext.accessibleName = value.displayTitle ?: value.question
            }
        }
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(event: MouseEvent) {
                val index = locationToIndex(event.point)
                if (hoveredIndex != index) { hoveredIndex = index; repaint() }
            }
        })
        addMouseListener(object : MouseAdapter() {
            override fun mouseExited(event: MouseEvent) { hoveredIndex = -1; repaint() }
            override fun mouseClicked(event: MouseEvent) {
                if (event.button == MouseEvent.BUTTON1) {
                    records.getOrNull(locationToIndex(event.point))?.let { service.showRoot(it) }
                    popup.isVisible = false
                }
            }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                if (event.keyCode == KeyEvent.VK_ENTER) {
                    selectedValue?.let(service::showRoot)
                    popup.isVisible = false
                }
            }
        })
    }
    popup.add(list)
    popup.show(anchor, 0, anchor.height)
}
