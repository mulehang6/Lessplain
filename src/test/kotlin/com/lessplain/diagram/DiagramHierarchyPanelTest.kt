package com.lessplain.diagram

import org.junit.jupiter.api.Test
import java.awt.Font
import java.awt.event.ActionEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagramHierarchyPanelTest {
    @Test fun `current marker is separate from title and indentation uses row geometry`() {
        SwingUtilities.invokeAndWait {
            var selected = -1
            val entries = listOf(DiagramHierarchyItem("理解标题", 0, 20, false),
                DiagramHierarchyItem("<html>中文 & 引号", 2, 40, true))
            val panel = DiagramHierarchyPanel(entries) { selected = it }
            try {
                assertEquals(1, panel.list.selectedIndex)
                assertEquals("理解层级", panel.list.accessibleContext.accessibleName)
                val renderer = panel.list.cellRenderer
                val row = renderer.getListCellRendererComponent(panel.list, entries[1], 1, true, true) as JPanel
                val labels = row.components.filterIsInstance<JLabel>()
                assertEquals(entries[1].title, labels.first().text)
                assertEquals("当前", labels.last().text)
                assertTrue(labels.last().isVisible)
                assertEquals(true, labels.first().getClientProperty("html.disable"))
                val childInset = row.insets.left
                renderer.getListCellRendererComponent(panel.list, entries[0], 0, false, false)
                assertTrue(childInset > row.insets.left)
                assertFalse(labels.last().isVisible)
                panel.list.actionMap.get("choose-level").actionPerformed(ActionEvent(panel.list, 0, "ENTER"))
                assertEquals(1, selected)
                panel.list.setSize(400, 200)
                panel.list.doLayout()
                val bounds = panel.list.getCellBounds(1, 1)
                val event = MouseEvent(panel.list, MouseEvent.MOUSE_MOVED, 0, 0, bounds.x + 1, bounds.y + 1, 0, false)
                val tip = requireNotNull(panel.list.getToolTipText(event))
                assertTrue(tip.contains("&lt;html&gt;"))
                assertTrue(tip.contains("&amp;"))
                panel.list.dispatchEvent(MouseEvent(panel.list, MouseEvent.MOUSE_CLICKED, 0, 0,
                    1, 190, 1, false, MouseEvent.BUTTON1))
                assertEquals(1, selected, "空白处不能猜选最后一项")
            } finally { panel.close() }
        }
    }

    @Test fun `question command is independent of hierarchy indices and empty space`() = SwingUtilities.invokeAndWait {
        var questions = 0
        var selected = -1
        val panel = DiagramHierarchyPanel(listOf(DiagramHierarchyItem("根层", 0, 7, true)),
            showQuestions = { questions++ }) { selected = it }
        try {
            requireNotNull(panel.questionButton).doClick()
            assertEquals(1, questions)
            assertEquals(-1, selected)
            panel.list.actionMap["choose-level"].actionPerformed(ActionEvent(panel.list, 0, "ENTER"))
            assertEquals(0, selected, "命令不能占用层级行索引")
        } finally { panel.close() }
    }

    @Test fun `long tree is scrollable width bounded and live fonts release listeners`() {
        val original = UIManager.getFont("Label.font")
        var panel: DiagramHierarchyPanel? = null
        var defaultsBefore = 0
        var managerBefore = 0
        try {
            SwingUtilities.invokeAndWait {
                defaultsBefore = UIManager.getDefaults().propertyChangeListeners.size
                managerBefore = UIManager.getPropertyChangeListeners().size
                panel = DiagramHierarchyPanel((0..20).map {
                    DiagramHierarchyItem("很长的层级标题".repeat(40), it, it, it == 16)
                }) { }
                val current = panel!!
                val scroll = current.components.filterIsInstance<JScrollPane>().single()
                assertTrue(current.preferredSize.width <= com.intellij.util.ui.JBUI.scale(656))
                assertEquals(current.list.fixedCellHeight * 8, scroll.preferredSize.height)
                UIManager.put("Label.font", Font(Font.DIALOG, Font.PLAIN, 23))
            }
            SwingUtilities.invokeAndWait {
                val current = panel!!
                assertEquals(23, current.list.font.size)
                val row = current.list.cellRenderer.getListCellRendererComponent(current.list,
                    current.list.model.getElementAt(16), 16, true, false) as JPanel
                row.setSize(400, current.list.fixedCellHeight)
                row.doLayout()
                val image = BufferedImage(row.width, row.height, BufferedImage.TYPE_INT_ARGB)
                val g = image.createGraphics()
                try { row.paint(g) } finally { g.dispose() }
                assertTrue((0 until row.height).any { y -> (0 until row.width).any { x -> image.getRGB(x, y) != 0 } })
                current.close()
                assertEquals(defaultsBefore, UIManager.getDefaults().propertyChangeListeners.size)
                assertEquals(managerBefore, UIManager.getPropertyChangeListeners().size)
            }
        } finally {
            SwingUtilities.invokeAndWait { panel?.close(); UIManager.put("Label.font", original) }
        }
    }
}
