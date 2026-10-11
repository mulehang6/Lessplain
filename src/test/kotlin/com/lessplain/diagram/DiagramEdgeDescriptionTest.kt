package com.lessplain.diagram

import com.lessplain.core.StaticExplorer
import com.lessplain.model.*
import org.junit.jupiter.api.Test
import java.awt.Font
import java.awt.Point
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JToolTip
import javax.swing.SwingUtilities
import kotlin.test.*

class DiagramEdgeDescriptionTest {
    private val graph = UnderstandingGraph(listOf(GraphNode("a", "入口"), GraphNode("b", "模型")),
        listOf(GraphEdge("ab", "a", "b", "call", coverage = "完整源码依据")))

    @Test fun `bounded tooltip escapes special text and retains complete evidence separately`() {
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            for (size in listOf(12, 20, 32)) {
                val font = Font(Font.DIALOG, Font.PLAIN, size)
                val metrics = graphics.getFontMetrics(font)
                for (text in listOf("中文说明".repeat(200), "A long explanation and identifier".repeat(200),
                    "<tag>& \"quoted\" 😀".repeat(200))) {
                    val lines = boundedTooltipLines(text, 340, metrics)
                    assertEquals(3, lines.size)
                    assertTrue(lines.last().endsWith("…"))
                    assertTrue(lines.all { metrics.stringWidth(it) <= 340 })
                    assertFalse(lines.joinToString("").contains("\uFFFD"))
                    val edge = graph.edges.single().copy(summary = text, inferred = true,
                        condition = "x < 3 && ready")
                    val html = edgeTooltip(edge, graph, metrics)
                    assertFalse(html.contains("完整源码依据"))
                    assertTrue(html.contains("推断"))
                    assertTrue(html.contains("条件："), "长短句也必须给条件留出空间")
                    val tip = JToolTip().apply { this.font = font; tipText = html }
                    assertTrue(tip.preferredSize.width <= 360, "实际 Swing 提示不能被长文字撑大")
                    assertTrue(edgeEvidenceText(edge, graph).contains(text))
                }
            }
            val edge = graph.edges.single().copy(summary = "<tag>& \"quoted\"", condition = "x < 3")
            val html = edgeTooltip(edge, graph, graphics.getFontMetrics(Font(Font.DIALOG, 0, 12)))
            assertTrue(html.contains("&lt;tag&gt;&amp;"))
            assertTrue(html.contains("&quot;quoted&quot;"))
            assertTrue(html.contains("条件：x &lt; 3"))
            for (relation in listOf("call", "control_flow", "data_flow", "structure", "dependency")) {
                assertFalse(edgeRelationship(edge.copy(relation = relation), graph).contains(relation))
            }
        } finally { graphics.dispose() }
    }

    @Test fun `details include unabridged coverage conditions and all referenced evidence`() {
        val anchor = SourceAnchor("src/A.kt", "run", "fun run() {\n    save()\n}", "v")
        val edge = graph.edges.single().copy(coverage = "完整依据".repeat(2000), condition = "ready",
            evidenceIds = listOf("proof"), inferred = true)
        val data = graph.copy(evidence = listOf(Evidence("proof", anchor, "说明", listOf(anchor.copy(path = "src/B.kt")))))
        val full = edgeEvidenceText(edge, data)
        assertTrue(full.contains(edge.coverage!!))
        assertTrue(full.contains("条件：ready"))
        assertTrue(full.contains(anchor.text))
        assertTrue(full.contains("src/B.kt"))
        assertTrue(full.contains("说明"))
    }

    @Test fun `right edge menu does not select a path and right drag still pans`() {
        SwingUtilities.invokeAndWait {
            val record = UnderstandingRecord("root", 1, null, null, "测试", "", null, graph, 0)
            val diagram = createUnderstandingDiagram(record)
            val explorer = StaticExplorer(graph)
            val navigation = DiagramRouteNavigation(diagram, explorer, {})
            try {
                diagram.setSize(800, 600)
                diagram.layout = layoutUnderstandingGraph(graph)
                val segment = diagram.layout.edges.single().segments.maxBy { kotlin.math.hypot(it.x2 - it.x1, it.y2 - it.y1) }
                val point = Point((400 + ((segment.x1 + segment.x2) / 2 - diagram.center.x) * diagram.zoom).toInt(),
                    (300 + ((segment.y1 + segment.y2) / 2 - diagram.center.y) * diagram.zoom).toInt())
                var opened = 0
                val input = DiagramMouseInteraction(diagram, { _, _ -> fail("不应打开节点菜单") },
                    { edge, _ -> assertEquals("ab", edge.id); opened++ })
                fun event(id: Int, p: Point) = MouseEvent(diagram, id, 0, 0, p.x, p.y, 1, false, MouseEvent.BUTTON3)
                val path = explorer.path
                val node = diagram.currentNodeId
                input.mousePressed(event(MouseEvent.MOUSE_PRESSED, point))
                input.mouseReleased(event(MouseEvent.MOUSE_RELEASED, point))
                assertEquals(1, opened)
                assertEquals(path, explorer.path)
                assertEquals(node, diagram.currentNodeId)
                val center = diagram.center
                input.mousePressed(event(MouseEvent.MOUSE_PRESSED, point))
                val end = Point(point.x + 20, point.y + 10)
                input.mouseDragged(event(MouseEvent.MOUSE_DRAGGED, end))
                input.mouseReleased(event(MouseEvent.MOUSE_RELEASED, end))
                assertEquals(1, opened)
                assertNotEquals(center, diagram.center)
                val duplicate = diagram.layout.edges.single().copy(edge = graph.edges.single().copy(id = "duplicate"))
                diagram.layout = diagram.layout.copy(edges = diagram.layout.edges + duplicate)
                diagram.centerX = center.x; diagram.centerY = center.y
                assertNull(diagram.edgeAt(point))
            } finally { navigation.close(); diagram.dispose() }
        }
    }
}
