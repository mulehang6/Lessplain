package com.master.understanding.diagram

import com.master.understanding.model.GraphEdge
import com.master.understanding.model.GraphNode
import com.master.understanding.model.UnderstandingGraph
import com.master.understanding.model.UnderstandingRecord
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class DiagramMouseInteractionTest {
    @Test fun `pointer movement and left blank drag do not move the camera or select a release target`() = withDiagram { d, input ->
        val center = d.center
        val zoom = d.zoom
        val blank = Point(880, 630)
        input.mouseMoved(mouse(d, MouseEvent.MOUSE_MOVED, blank, MouseEvent.NOBUTTON))
        input.mousePressed(mouse(d, MouseEvent.MOUSE_PRESSED, blank, MouseEvent.BUTTON1))
        val node = screenNode(d, "b")
        input.mouseDragged(mouse(d, MouseEvent.MOUSE_DRAGGED, node, MouseEvent.BUTTON1))
        input.mouseReleased(mouse(d, MouseEvent.MOUSE_RELEASED, node, MouseEvent.BUTTON1))
        assertEquals(center, d.center)
        assertEquals(zoom, d.zoom)
        assertTrue(d.nodePositions.isEmpty())
        assertEquals("a", d.currentNodeId)
    }

    @Test fun `right drag on a node moves camera without moving the node or opening a menu`() = withDiagram { d, input ->
        val start = screenNode(d)
        val center = d.center
        input.mousePressed(mouse(d, MouseEvent.MOUSE_PRESSED, start, MouseEvent.BUTTON3))
        input.mouseDragged(mouse(d, MouseEvent.MOUSE_DRAGGED, Point(start.x + 48, start.y + 24), MouseEvent.BUTTON3))
        input.mouseReleased(mouse(d, MouseEvent.MOUSE_RELEASED, Point(start.x + 48, start.y + 24), MouseEvent.BUTTON3))
        assertEquals(center.x - 48 / d.zoom, d.centerX, 0.00001)
        assertEquals(center.y - 24 / d.zoom, d.centerY, 0.00001)
        assertTrue(d.nodePositions.isEmpty())
    }

    @Test fun `left node drag still changes node position without panning camera`() = withDiagram { d, input ->
        val start = screenNode(d)
        val center = d.center
        val bounds = d.layout.nodes.getValue("a").bounds
        val originalX = bounds.x
        input.mousePressed(mouse(d, MouseEvent.MOUSE_PRESSED, start, MouseEvent.BUTTON1))
        input.mouseDragged(mouse(d, MouseEvent.MOUSE_DRAGGED, Point(start.x + 45, start.y), MouseEvent.BUTTON1))
        input.mouseReleased(mouse(d, MouseEvent.MOUSE_RELEASED, Point(start.x + 45, start.y), MouseEvent.BUTTON1))
        assertEquals(center, d.center)
        assertEquals(originalX + 45 / d.zoom, d.nodePositions.getValue("a").x, 0.00001)
    }

    @Test fun `continuous vertical and horizontal scrolling pan at different zooms without changing scale`() = withDiagram { d, input ->
        for (zoom in listOf(0.6, 2.4)) {
            d.zoom = zoom
            d.centerX = 100.0
            d.centerY = 200.0
            val vertical = wheel(d, 0, 0.25)
            input.mouseWheelMoved(vertical)
            val verticalPixels = (d.centerY - 200.0) * zoom
            assertTrue(verticalPixels > 0)
            assertEquals(zoom, d.zoom)
            assertEquals(100.0, d.centerX)
            assertTrue(vertical.isConsumed)
            val horizontal = wheel(d, InputEvent.SHIFT_DOWN_MASK, 0.25)
            input.mouseWheelMoved(horizontal)
            assertEquals(verticalPixels, (d.centerX - 100.0) * zoom, 0.00001)
            assertEquals(zoom, d.zoom)
        }
    }

    @Test fun `control scroll zooms at cursor and consumed wheel cannot apply twice`() = withDiagram { d, input ->
        val event = wheel(d, InputEvent.CTRL_DOWN_MASK, -0.5)
        val world = d.screenToWorld(event.point)
        val before = d.zoom
        input.mouseWheelMoved(event)
        assertTrue(d.zoom > before)
        val after = d.screenToWorld(event.point)
        assertEquals(world.x, after.x, 0.00001)
        assertEquals(world.y, after.y, 0.00001)
        val center = d.center
        val zoom = d.zoom
        input.mouseWheelMoved(event)
        assertEquals(center, d.center)
        assertEquals(zoom, d.zoom)
    }

    private fun withDiagram(action: (UnderstandingDiagram, DiagramMouseInteraction) -> Unit) = SwingUtilities.invokeAndWait {
        val graph = UnderstandingGraph(listOf(GraphNode("a", "来源"), GraphNode("b", "目标")),
            listOf(GraphEdge("ab", "a", "b", "control_flow")))
        val layout = layoutUnderstandingGraph(graph)
        val record = UnderstandingRecord("input", 1, null, null, "操作", "", null, graph, 0)
        val diagram = UnderstandingDiagram(record, null, layoutComputer = { layout })
        try {
            diagram.layout = layout
            diagram.setSize(900, 650)
            diagram.zoom = 1.5
            action(diagram, diagram.mouseListeners.filterIsInstance<DiagramMouseInteraction>().single())
        } finally { diagram.dispose() }
    }

    private fun screenNode(d: UnderstandingDiagram, id: String = "a"): Point {
        val b = d.layout.nodes.getValue(id).bounds
        return Point((d.width / 2.0 + (b.centerX - d.centerX) * d.zoom).toInt(),
            (d.height / 2.0 + (b.centerY - d.centerY) * d.zoom).toInt())
    }
    private fun mouse(d: UnderstandingDiagram, id: Int, point: Point, button: Int) =
        MouseEvent(d, id, 0, 0, point.x, point.y, 1, false, button)
    private fun wheel(d: UnderstandingDiagram, modifiers: Int, precise: Double) =
        MouseWheelEvent(d, MouseEvent.MOUSE_WHEEL, 0, modifiers, 700, 300, 700, 300, 0, false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, if (precise > 0) 1 else -1, precise)
}
