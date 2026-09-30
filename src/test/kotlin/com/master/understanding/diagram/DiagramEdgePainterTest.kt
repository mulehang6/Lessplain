package com.master.understanding.diagram

import com.master.understanding.model.GraphEdge
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class DiagramEdgePainterTest {
    @Test
    fun `horizontal vertical and bent edges survive clipping before any drag`() {
        val edges = listOf(
            edge(listOf(10.0 to 50.0, 100.0 to 50.0)),
            edge(listOf(50.0 to 10.0, 50.0 to 100.0)),
            edge(listOf(10.0 to 20.0, 60.0 to 20.0, 60.0 to 100.0)),
        )
        for (placed in edges) {
            compareClippedPaint(placed, Rectangle2D.Double(0.0, 0.0, 120.0, 120.0))
            compareClippedPaint(placed, Rectangle2D.Double(40.0, 40.0, 30.0, 30.0))
        }
    }

    @Test
    fun `only arrow or stroke margin can enter viewport`() {
        val horizontal = edge(listOf(10.0 to 50.0, 100.0 to 50.0))
        compareClippedPaint(horizontal, Rectangle2D.Double(86.0, 43.0, 11.0, 5.0))
        compareClippedPaint(horizontal, Rectangle2D.Double(30.0, 49.0, 20.0, 1.0), animated = true)
    }

    @Test
    fun `label alone remains visible including legacy label positions`() {
        val original = edge(listOf(10.0 to 10.0, 50.0 to 10.0))
        val labeled = original.copy(edge = original.edge.copy(condition = "继续"), labelX = 100.0, labelY = 70.0)
        compareClippedPaint(labeled, Rectangle2D.Double(80.0, 60.0, 40.0, 30.0))
        val bounds = requireNotNull(labeled.resolvedLabelBounds)
        compareClippedPaint(labeled.copy(labelBounds = bounds), bounds)
        assertTrue(labeled.paintBounds.contains(bounds))
    }

    @Test
    fun `clipping remains equivalent under zoom translation and flow animation`() {
        val placed = edge(listOf(-80.0 to -30.0, 100.0 to -30.0))
        for (zoom in listOf(0.35, 1.0, 2.4)) {
            compareClippedPaint(placed, Rectangle2D.Double(-20.0, -32.0, 60.0, 6.0), zoom, true)
        }
    }

    private fun edge(points: List<Pair<Double, Double>>): PlacedEdge {
        val path = Path2D.Double().apply {
            moveTo(points.first().first, points.first().second)
            points.drop(1).forEach { (x, y) -> lineTo(x, y) }
        }
        val (x, y) = points.last()
        val (beforeX, beforeY) = points[points.lastIndex - 1]
        return PlacedEdge(GraphEdge("edge", "a", "b", "control_flow"), path, x, y,
            x - beforeX, y - beforeY, 0.0, 0.0)
    }

    private fun compareClippedPaint(edge: PlacedEdge, visible: Rectangle2D, zoom: Double = 1.0,
        animated: Boolean = false) {
        fun render(cull: Boolean): IntArray {
            val image = BufferedImage(300, 300, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            try {
                g.translate(70.0, 90.0)
                g.scale(zoom, zoom)
                g.clip(visible)
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                DiagramEdgePainter.paint(g, listOf(edge), setOf(edge.edge.id), animated, 3f,
                    if (cull) visible else null)
            } finally { g.dispose() }
            return image.getRGB(0, 0, 300, 300, null, 0, 300)
        }
        val reference = render(false)
        assertTrue(reference.any { it != 0 }, "样例必须有可见像素: $visible")
        assertContentEquals(reference, render(true), "可见部分不应被裁剪判定丢弃")
    }
}
