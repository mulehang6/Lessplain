package com.lessplain.diagram

import com.intellij.util.ui.JBUI
import com.lessplain.model.GraphNode
import com.lessplain.model.UnderstandingGraph
import com.lessplain.model.UnderstandingRecord
import java.awt.Component
import java.awt.Point
import java.awt.event.ActionEvent
import java.awt.event.FocusEvent
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class DiagramHeaderTest {
    private fun withDiagram(title: String, action: (UnderstandingDiagram) -> Unit) = SwingUtilities.invokeAndWait {
        val record = UnderstandingRecord("root", 1, null, null, "原问题", "不显示的文字", null,
            UnderstandingGraph(listOf(GraphNode("n", "步骤")), emptyList()), 0, title)
        val diagram = createUnderstandingDiagram(record)
        try { diagram.setSize(600, 400); diagram.doLayout(); action(diagram) } finally { diagram.dispose() }
    }

    private fun mouse(component: Component, type: Int, x: Int = 2, y: Int = 2, clicks: Int = 1, button: Int = MouseEvent.BUTTON1) =
        MouseEvent(component, type, 0, 0, x, y, clicks, false, button)

    @Test fun `title overlays full height canvas beside controls and stays fixed through camera changes`() = withDiagram("付费文章") { diagram ->
        val header = diagram.header
        assertSame(diagram, diagram.jComponent)
        assertSame(diagram, header.parent)
        assertEquals(400, diagram.jComponent.height)
        assertTrue(diagram.clusterSpecs.isEmpty())
        assertTrue(diagram.layout.clusters.isEmpty())
        assertFalse(header.isOpaque)
        val controls = diagram.components.filterIsInstance<UnderstandingDiagramControls>().single()
        assertEquals(controls.x + controls.width + JBUI.scale(8), header.x)
        assertEquals(controls.y + controls.components.first().height / 2, header.y + header.height / 2)
        val original = header.bounds
        diagram.zoom = 2.0
        diagram.centerX += 200
        diagram.centerY -= 150
        diagram.doLayout()
        assertEquals(original, header.bounds)
    }

    @Test fun `hover follows compact title to pencil without shifting text and edit bindings release`() = withDiagram("短标题") { diagram ->
        val header = diagram.header
        var edits = 0
        val binding = header.bindEdit { edits++ }
        diagram.doLayout()
        assertFalse(header.editButton.isVisible)
        assertFalse(header.editButton.isBorderPainted)
        val original = header.bounds
        val label = header.label
        label.dispatchEvent(mouse(label, MouseEvent.MOUSE_ENTERED))
        assertTrue(header.editButton.isVisible)
        assertEquals(label.width + JBUI.scale(8), header.editButton.x)
        assertEquals(original, header.bounds)
        label.dispatchEvent(mouse(label, MouseEvent.MOUSE_EXITED, label.width + 1, label.height / 2))
        assertTrue(header.editButton.isVisible)
        header.editButton.dispatchEvent(mouse(header.editButton, MouseEvent.MOUSE_ENTERED))
        header.editButton.doClick()
        label.dispatchEvent(mouse(label, MouseEvent.MOUSE_CLICKED, clicks = 2))
        assertEquals(2, edits)
        header.editButton.dispatchEvent(mouse(header.editButton, MouseEvent.MOUSE_EXITED,
            header.editButton.width + 2, header.editButton.height / 2))
        assertFalse(header.editButton.isVisible)
        binding.close()
        label.dispatchEvent(mouse(label, MouseEvent.MOUSE_ENTERED))
        label.dispatchEvent(mouse(label, MouseEvent.MOUSE_CLICKED, clicks = 2))
        assertFalse(header.editButton.isVisible)
        assertEquals(2, edits)
    }

    @Test fun `pencil moves with actual title length and narrow title preserves full tooltip and surrogate pairs`() {
        val positions = mutableListOf<Int>()
        for (title in listOf("短标题", "付费文章购买回调与阅读")) withDiagram(title) { diagram ->
            diagram.header.bindEdit { }
            diagram.doLayout()
            val header = diagram.header
            assertEquals(title, header.label.text)
            positions += header.editButton.x
        }
        assertTrue(positions[1] > positions[0])
        val long = "😀付费文章购买与阅读解锁".repeat(12)
        withDiagram(long) { diagram ->
            val header = diagram.header
            header.bindEdit { }
            for (width in listOf(600, 240, 120, 60)) {
                diagram.setSize(width, 400)
                diagram.doLayout()
                assertEquals(400, diagram.height)
                assertTrue(header.x >= 0 && header.x + header.width <= width)
                assertTrue(header.label.toolTipText.contains(long))
                val shown = header.label.text
                assertTrue(shown.isEmpty() || shown.endsWith("…"))
                val prefix = shown.removeSuffix("…")
                assertFalse(prefix.lastOrNull()?.isHighSurrogate() ?: false)
                if (header.isVisible) assertFalse(header.bounds.intersects(
                    diagram.components.filterIsInstance<UnderstandingDiagramControls>().single().bounds))
            }
        }
    }

    @Test fun `title area does not intercept blank top row and panning keeps overlay fixed`() = withDiagram("标题") { diagram ->
        val header = diagram.header
        header.bindEdit { }
        diagram.doLayout()
        val point = Point(header.x + header.width + 20, header.y + header.height / 2)
        assertSame(diagram, SwingUtilities.getDeepestComponentAt(diagram, point.x, point.y))
        val before = diagram.center
        val titleBounds = header.bounds
        diagram.dispatchEvent(mouse(diagram, MouseEvent.MOUSE_PRESSED, point.x, point.y, button = MouseEvent.BUTTON3))
        diagram.dispatchEvent(mouse(diagram, MouseEvent.MOUSE_DRAGGED, point.x + 18, point.y + 10, button = MouseEvent.BUTTON3))
        diagram.dispatchEvent(mouse(diagram, MouseEvent.MOUSE_RELEASED, point.x + 18, point.y + 10, button = MouseEvent.BUTTON3))
        assertEquals(before.x - 18 / diagram.zoom, diagram.centerX)
        assertEquals(before.y - 10 / diagram.zoom, diagram.centerY)
        assertEquals(titleBounds, header.bounds)
        assertEquals(Point(header.x, header.y + header.height + JBUI.scale(6)), header.popupAnchor)
    }

    @Test fun `keyboard reveals editing focus transfers to pencil and dispose removes listeners and queued actions`() = withDiagram("键盘编辑") { diagram ->
        val header = diagram.header
        var edits = 0
        header.bindEdit { edits++ }
        diagram.doLayout()
        header.focusListeners.forEach { it.focusGained(FocusEvent(header, FocusEvent.FOCUS_GAINED)) }
        assertTrue(header.editButton.isVisible)
        header.focusListeners.forEach { it.focusLost(FocusEvent(header, FocusEvent.FOCUS_LOST, false, header.editButton)) }
        assertTrue(header.editButton.isVisible)
        val action = header.actionMap["edit"]
        action.actionPerformed(ActionEvent(header, 0, "edit"))
        assertEquals(1, edits)
        header.editButton.focusListeners.forEach {
            it.focusLost(FocusEvent(header.editButton, FocusEvent.FOCUS_LOST, false, diagram))
        }
        assertFalse(header.editButton.isVisible)
        val pointers = header.label.mouseListeners.size
        val focusListeners = header.focusListeners.size
        diagram.dispose()
        assertEquals(pointers - 1, header.label.mouseListeners.size)
        assertEquals(focusListeners - 1, header.focusListeners.size)
        assertEquals(0, header.actionMap.size())
        action.actionPerformed(ActionEvent(header, 0, "edit"))
        header.editButton.doClick()
        assertEquals(1, edits)
        assertFalse(header.editButton.isVisible)
    }
}
