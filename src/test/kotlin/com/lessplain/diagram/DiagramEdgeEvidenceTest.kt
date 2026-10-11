package com.lessplain.diagram

import com.lessplain.model.GraphEdge
import com.lessplain.model.GraphNode
import com.lessplain.model.UnderstandingGraph
import com.lessplain.model.UnderstandingRecord
import java.awt.Point
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagramEdgeEvidenceTest {
    @Test
    fun `ordinary edges expose coverage at several zooms without becoming route choices`() {
        SwingUtilities.invokeAndWait {
            val graph = UnderstandingGraph(listOf(GraphNode("a", "入口"), GraphNode("b", "保存")),
                listOf(GraphEdge("ab", "a", "b", "call", coverage = "由注册关系推断", inferred = true)))
            val record = UnderstandingRecord("root", 1, null, null, "测试", "", null, graph, 0)
            val diagram = createUnderstandingDiagram(record)
            try {
                diagram.setSize(800, 600)
                diagram.layout = layoutUnderstandingGraph(graph)
                val edge = diagram.layout.edges.single()
                val segment = edge.segments.maxBy { kotlin.math.hypot(it.x2 - it.x1, it.y2 - it.y1) }
                val worldX = (segment.x1 + segment.x2) / 2
                val worldY = (segment.y1 + segment.y2) / 2
                for (zoom in listOf(0.5, 1.0, 2.0)) {
                    diagram.zoom = zoom
                    val point = Point((diagram.width / 2 + (worldX - diagram.center.x) * zoom).toInt(),
                        (diagram.height / 2 + (worldY - diagram.center.y) * zoom).toInt())
                    val tip = diagram.getToolTipText(MouseEvent(diagram, MouseEvent.MOUSE_MOVED, 0, 0,
                        point.x, point.y, 0, false))
                    assertTrue(tip!!.contains("由注册关系推断"))
                    assertEquals(null, diagram.candidateEdgeAt(point))
                }
                val node = diagram.layout.nodes.getValue("a").bounds
                val point = Point((diagram.width / 2 + (node.centerX - diagram.center.x) * diagram.zoom).toInt(),
                    (diagram.height / 2 + (node.centerY - diagram.center.y) * diagram.zoom).toInt())
                assertEquals(null, diagram.edgeTooltipAt(point))
            } finally { diagram.dispose() }
        }
    }

    @Test
    fun `inferred stroke has visible gaps while ordinary and active paths still paint`() {
        fun paint(inferred: Boolean, active: Boolean = false, animate: Boolean = false): BufferedImage {
            val image = BufferedImage(140, 80, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            try {
                val path = Path2D.Double().apply { moveTo(10.0, 40.0); lineTo(130.0, 40.0) }
                val placed = PlacedEdge(GraphEdge("ab", "a", "b", "call", inferred = inferred), path,
                    130.0, 40.0, 120.0, 0.0, 0.0, 0.0, null)
                DiagramEdgePainter.paint(g, listOf(placed), if (active) setOf("ab") else emptySet(), animate, 0f)
            } finally { g.dispose() }
            return image
        }
        fun ink(image: BufferedImage) = (20..110).count { image.getRGB(it, 40).ushr(24) != 0 }
        val solid = ink(paint(false))
        val inferred = ink(paint(true))
        assertTrue(solid > inferred)
        assertTrue(inferred > 0)
        assertTrue(ink(paint(true, active = true, animate = true)) > 0)
        assertFalse(DiagramEdgePainter.edgeStroke(true, 1.2f).dashArray.isEmpty())
    }
}
