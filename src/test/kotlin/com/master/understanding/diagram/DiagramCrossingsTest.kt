package com.master.understanding.diagram

import com.master.understanding.model.GraphEdge
import java.awt.Color
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagramCrossingsTest {
    @Test fun `interior crossing has a visible bridge and identical rendering in a clipped viewport`() {
        val edges = listOf(edge("horizontal", "a", "b", 10.0, 50.0, 90.0, 50.0),
            edge("vertical", "c", "d", 50.0, 10.0, 50.0, 90.0))
        val crossings = diagramCrossings(edges)
        assertEquals(1, crossings.size)
        val full = render(edges, crossings)
        val joined = render(edges, emptyList())
        assertEquals(Color(0xFAFBFC).rgb, full.getRGB(50, 50))
        assertTrue(joined.getRGB(50, 50) != full.getRGB(50, 50))
        val viewport = Rectangle2D.Double(44.0, 44.0, 20.0, 20.0)
        val clipped = render(edges, crossings, viewport)
        for (x in 44..63) for (y in 44..63) assertEquals(full.getRGB(x, y), clipped.getRGB(x, y), "$x,$y")
    }

    @Test fun `shared node endpoints remain junctions without a bridge`() {
        assertTrue(diagramCrossings(listOf(edge("first", "a", "merge", 10.0, 50.0, 50.0, 50.0),
            edge("second", "b", "merge", 50.0, 10.0, 50.0, 50.0))).isEmpty())
    }

    private fun render(edges: List<PlacedEdge>, crossings: List<DiagramCrossing>, viewport: Rectangle2D? = null): BufferedImage {
        val image = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color(0xFAFBFC)
            graphics.fillRect(0, 0, 100, 100)
            viewport?.let { graphics.clip = it }
            DiagramEdgePainter.paint(graphics, edges, emptySet(), false, 0f, viewport, crossings = crossings)
        } finally { graphics.dispose() }
        return image
    }

    private fun edge(id: String, from: String, to: String, x1: Double, y1: Double, x2: Double, y2: Double): PlacedEdge =
        PlacedEdge(GraphEdge(id, from, to, "call"), Path2D.Double().apply { moveTo(x1, y1); lineTo(x2, y2) },
            x2, y2, x2 - x1, y2 - y1, 0.0, 0.0)
}
