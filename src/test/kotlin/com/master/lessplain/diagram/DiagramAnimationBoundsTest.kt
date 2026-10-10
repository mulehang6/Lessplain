package com.master.lessplain.diagram

import com.master.lessplain.model.GraphEdge
import com.master.lessplain.model.GraphNode
import com.master.lessplain.model.UnderstandingGraph
import com.master.lessplain.model.UnderstandingRecord
import java.awt.Rectangle
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import javax.swing.JComponent
import javax.swing.RepaintManager
import javax.swing.SwingUtilities

class DiagramAnimationBoundsTest {
    @Test
    fun `flow repaints when only arrow enters viewport at different camera transforms`() {
        SwingUtilities.invokeAndWait {
            val record = UnderstandingRecord("animation", 1, null, null, "测试", "", null,
                UnderstandingGraph(listOf(GraphNode("a", "A"), GraphNode("b", "B")),
                    listOf(GraphEdge("flow", "a", "b", "control_flow"))), 0)
            val diagram = UnderstandingDiagram(record, null)
            val previous = RepaintManager.currentManager(diagram)
            val regions = mutableListOf<Rectangle>()
            RepaintManager.setCurrentManager(object : RepaintManager() {
                override fun addDirtyRegion(component: JComponent, x: Int, y: Int, width: Int, height: Int) {
                    if (component === diagram) regions.add(Rectangle(x, y, width, height))
                }
            })
            try {
                diagram.setSize(100, 100)
                for (scale in listOf(0.35, 1.0, 2.0)) {
                    val path = Path2D.Double().apply { moveTo(-80.0, 0.0); lineTo(80.0, 0.0) }
                    val edge = PlacedEdge(GraphEdge("flow", "a", "b", "control_flow"), path,
                        80.0, 0.0, 160.0, 0.0, 0.0, 0.0)
                    diagram.layout = UnderstandingGraphLayout(emptyMap(), listOf(edge), edge.paintBounds)
                    diagram.zoom = scale
                    diagram.centerX = 80.0
                    // The line is just outside the bottom; the upper arrow wing is visible.
                    diagram.centerY = -51.0 / scale
                    for (upstreamOnly in listOf(false, true)) {
                        diagram.showUnderstandingPath(if (upstreamOnly) emptyList() else listOf("flow"), "b", false)
                        assertEquals(setOf("flow"), diagram.highlightedEdgeIds)
                        assertEquals(upstreamOnly, diagram.routeEdgeIds.isEmpty())
                        regions.clear()
                        diagram.advanceFlow()
                        assertTrue(regions.any { it.intersects(Rectangle2D.Double(35.0, 94.0, 30.0, 6.0)) },
                            "仅箭头可见时仍应请求局部重绘: $scale / $upstreamOnly / $regions")
                        assertTrue(regions.all { it.width > 0 && it.height > 0 })
                    }
                }
            } finally {
                diagram.dispose()
                RepaintManager.setCurrentManager(previous)
            }
        }
    }
}
