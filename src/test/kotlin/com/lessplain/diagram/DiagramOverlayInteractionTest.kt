package com.lessplain.diagram

import com.lessplain.model.GraphNode
import com.lessplain.model.GraphEdge
import com.lessplain.model.UnderstandingGraph
import com.lessplain.model.UnderstandingRecord
import org.junit.jupiter.api.Test
import java.awt.Point
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagramOverlayInteractionTest {
    private val graph = UnderstandingGraph(listOf(GraphNode("a", "读取"), GraphNode("b", "处理"), GraphNode("c", "输出")),
        listOf(GraphEdge("ab", "a", "b", "data_flow"), GraphEdge("bc", "b", "c", "data_flow")))
    private val groups = listOf(DiagramCluster("first", "读取与处理", linkedSetOf("a", "b")),
        DiagramCluster("second", "输出", linkedSetOf("c")))

    private fun withDiagram(action: (UnderstandingDiagram) -> Unit) = SwingUtilities.invokeAndWait {
        val layout = layoutUnderstandingClusters(graph, groups)
        val record = UnderstandingRecord("test", 1, null, null, "流程", "", null, graph, 0)
        val diagram = UnderstandingDiagram(record, null, groups, layoutComputer = { layout })
        try { diagram.layout = layout; diagram.setSize(900, 650); action(diagram) } finally { diagram.dispose() }
    }

    private fun screen(diagram: UnderstandingDiagram, x: Double, y: Double): Point = Point(
        (diagram.width / 2.0 + (x - diagram.centerX) * diagram.zoom).toInt(),
        (diagram.height / 2.0 + (y - diagram.centerY) * diagram.zoom).toInt(),
    )

    private fun event(diagram: UnderstandingDiagram, point: Point, type: Int, clicks: Int = 1) =
        MouseEvent(diagram, type, 0, 0, point.x, point.y, clicks, false,
            if (type == MouseEvent.MOUSE_MOVED) MouseEvent.NOBUTTON else MouseEvent.BUTTON1)

    @Test fun `note icon is hit without node hover and empty node adds a note`() = withDiagram { diagram ->
        val input = diagram.mouseListeners.filterIsInstance<DiagramMouseInteraction>().single()
        diagram.setPendingNoteCounts(mapOf("a" to 2))
        var viewed = ""
        val view = diagram.onNoteBadgeClicked { id, _, _ -> viewed = id }
        for (scale in listOf(0.65, 1.0, 1.8)) {
            viewed = ""
            diagram.zoom = scale
            diagram.centerX += 12
            val bounds = DiagramNodePainter.noteBounds(diagram.layout.nodes.getValue("a").bounds)
            val point = screen(diagram, bounds.centerX, bounds.y + 6)
            diagram.hoveredNodeId = null
            assertEquals("a", diagram.noteActionAt(point))
            val popup = diagram.notePopupAnchor(point)
            assertTrue(popup.y > screen(diagram, bounds.x, bounds.maxY).y)
            input.mouseMoved(event(diagram, point, MouseEvent.MOUSE_MOVED))
            assertEquals("", viewed, "hover must not open notes")
            input.mousePressed(event(diagram, point, MouseEvent.MOUSE_PRESSED))
            input.mouseReleased(event(diagram, point, MouseEvent.MOUSE_RELEASED))
            assertEquals("a", viewed)
        }
        view.close()
        diagram.setPendingNoteCounts(emptyMap())
        diagram.hoveredNodeId = null
        val bounds = DiagramNodePainter.noteBounds(diagram.layout.nodes.getValue("a").bounds)
        val point = screen(diagram, bounds.centerX, bounds.centerY)
        assertNull(diagram.noteActionAt(point))
        diagram.hoveredNodeId = "a"
        var added = ""
        diagram.onAddNodeNote { added = it }
        input.mousePressed(event(diagram, point, MouseEvent.MOUSE_PRESSED))
        input.mouseReleased(event(diagram, point, MouseEvent.MOUSE_RELEASED))
        assertEquals("a", added)
    }

    @Test fun `pending choices still allow selecting a different exploration start`() = withDiagram { diagram ->
        diagram.setChoiceTargets(setOf("b"))
        var picked = ""
        diagram.onNodeSelected { picked = it }
        diagram.selectUnderstandingNode("c", notify = true)
        assertEquals("c", picked)
        assertEquals("c", diagram.currentNodeId)
    }

    @Test fun `persistent note paints outside node while absent note leaves that area empty`() {
        val layout = layoutUnderstandingGraph(graph)
        val node = layout.nodes.getValue("a")
        val bounds = DiagramNodePainter.noteBounds(node.bounds)
        fun pixels(counts: Map<String, Int>): Int {
            val image = BufferedImage(600, 600, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            g.translate(80 - bounds.x, 80 - bounds.y)
            DiagramNodePainter.paint(g, layout, null, null, emptyMap(), counts, emptySet(), emptySet())
            g.dispose()
            return (84 until 104).sumOf { x -> (82 until 90).count { y -> image.getRGB(x, y) ushr 24 > 0 } }
        }
        assertEquals(0, pixels(emptyMap()))
        assertTrue(pixels(mapOf("a" to 1)) > 0)
    }

    @Test fun `draft badge is persistent clickable and never increases saved note counts`() = withDiagram { diagram ->
        val input = diagram.mouseListeners.filterIsInstance<DiagramMouseInteraction>().single()
        diagram.setDraftNoteNodes(setOf("a", "missing"))
        var viewed = ""
        diagram.onNoteBadgeClicked { id, _, _ -> viewed = id }
        assertEquals(setOf("a"), diagram.draftNoteNodes)
        assertTrue(diagram.pendingNoteCounts.isEmpty())
        diagram.hoveredNodeId = null
        val bounds = DiagramNodePainter.noteBounds(diagram.layout.nodes.getValue("a").bounds)
        val point = screen(diagram, bounds.centerX, bounds.centerY)
        assertEquals("a", diagram.noteActionAt(point))
        diagram.onAddNodeNote { error("draft must reopen instead of adding a separate note") }
        input.mousePressed(event(diagram, point, MouseEvent.MOUSE_PRESSED))
        input.mouseReleased(event(diagram, point, MouseEvent.MOUSE_RELEASED))
        assertEquals("a", viewed)
        diagram.setPendingNoteCounts(mapOf("a" to 1))
        diagram.setDraftNoteNodes(emptySet())
        assertEquals("a", diagram.noteActionAt(point))
        diagram.setPendingNoteCounts(emptyMap())
        diagram.hoveredNodeId = null
        assertNull(diagram.noteActionAt(point))
        diagram.dispose()
        diagram.setDraftNoteNodes(setOf("a"))
        assertTrue(diagram.draftNoteNodes.isEmpty())
    }

    @Test fun `draft badge paints without hover using the existing note glyph`() {
        val layout = layoutUnderstandingGraph(graph)
        val bounds = DiagramNodePainter.noteBounds(layout.nodes.getValue("a").bounds)
        fun image(saved: Map<String, Int>, drafts: Set<String>): BufferedImage {
            val image = BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            try {
                g.translate(20 - bounds.x, 20 - bounds.y)
                DiagramNodePainter.paint(g, layout, null, null, emptyMap(), saved, emptySet(), emptySet(),
                    draftNoteNodes = drafts)
            } finally { g.dispose() }
            return image
        }
        val saved = image(mapOf("a" to 1), emptySet())
        val draft = image(emptyMap(), setOf("a"))
        assertTrue((20 until 48).any { x -> (20 until 30).any { y -> draft.getRGB(x, y) ushr 24 > 0 } })
        for (x in 0 until 100) for (y in 0 until 100) assertEquals(saved.getRGB(x, y), draft.getRGB(x, y))
    }

    @Test fun `title feedback and edit glyph use shared geometry and double click stays available`() = withDiagram { diagram ->
        val input = diagram.mouseListeners.filterIsInstance<DiagramMouseInteraction>().single()
        val title = DiagramClusterText.titleBounds(diagram.layout.clusters.first())
        val point = screen(diagram, title.centerX, title.centerY)
        var edits = 0
        diagram.onClusterTitleEdit { assertEquals("first", it); edits++ }
        input.mouseMoved(event(diagram, point, MouseEvent.MOUSE_MOVED))
        assertEquals("first", diagram.overlays.titles.hovered)
        input.mousePressed(event(diagram, point, MouseEvent.MOUSE_PRESSED))
        assertEquals("first", diagram.overlays.titles.pressed)
        input.mouseReleased(event(diagram, point, MouseEvent.MOUSE_RELEASED))
        assertEquals("first", diagram.overlays.titles.selected)
        assertNull(diagram.overlays.titles.pressed)
        assertEquals(0, edits)
        input.mouseClicked(event(diagram, point, MouseEvent.MOUSE_CLICKED, 2))
        assertEquals(1, edits)
        val edit = DiagramClusterText.editBounds(diagram.layout.clusters.first())
        val editPoint = screen(diagram, edit.centerX, edit.centerY)
        assertEquals("first", diagram.clusterEditAt(editPoint))
        input.mousePressed(event(diagram, editPoint, MouseEvent.MOUSE_PRESSED))
        input.mouseReleased(event(diagram, editPoint, MouseEvent.MOUSE_RELEASED))
        assertEquals(2, edits)
    }

    @Test fun `cluster navigation remembers target preserves zoom and all graph elements`() = withDiagram { diagram ->
        diagram.zoom = 1.3
        val nodeIds = diagram.layout.nodes.keys.toSet()
        val edgeIds = diagram.layout.edges.map { it.edge.id }
        diagram.selectUnderstandingNode("b")
        diagram.focusCluster("second")
        assertEquals("c", diagram.currentNodeId)
        diagram.focusCluster("first")
        assertEquals("b", diagram.currentNodeId)
        assertEquals(1.3, diagram.zoom)
        assertEquals(nodeIds, diagram.layout.nodes.keys)
        assertEquals(edgeIds, diagram.layout.edges.map { it.edge.id })
        assertEquals(diagram.layout.nodes.getValue("b").bounds.centerX, diagram.centerX)
        var released = 0
        diagram.overlays.own(AutoCloseable { released++ })
        diagram.dispose()
        assertEquals(1, released)
        assertTrue(diagram.overlays.clusterTargets.isEmpty())
    }

    @Test fun `initial cluster focus waits for asynchronous layout and survives resize`() {
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        val layout = layoutUnderstandingClusters(graph, groups)
        lateinit var diagram: UnderstandingDiagram
        SwingUtilities.invokeAndWait {
            val record = UnderstandingRecord("test", 1, null, null, "流程", "", null, graph, 0)
            diagram = UnderstandingDiagram(record, null, groups, layoutComputer = {
                started.countDown()
                check(gate.await(3, TimeUnit.SECONDS))
                layout
            })
            diagram.setSize(900, 650)
            diagram.zoom = 1.2
            diagram.requestFocusCluster("second")
            diagram.requestLayout()
        }
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS))
            SwingUtilities.invokeAndWait {
                diagram.applyPendingClusterFocus()
                assertEquals("second", diagram.pendingFocusClusterId)
                diagram.setSize(920, 650)
            }
            gate.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var ready = false
            while (!ready && System.nanoTime() < deadline) {
                SwingUtilities.invokeAndWait { ready = diagram.layoutReady }
                if (!ready) Thread.sleep(10)
            }
            SwingUtilities.invokeAndWait {
                assertTrue(diagram.layoutReady)
                assertNull(diagram.pendingFocusClusterId)
                assertEquals("c", diagram.currentNodeId)
                assertEquals(layout.nodes.getValue("c").bounds.centerX, diagram.centerX)
            }
        } finally {
            gate.countDown()
            SwingUtilities.invokeAndWait { diagram.dispose() }
        }
    }
}
