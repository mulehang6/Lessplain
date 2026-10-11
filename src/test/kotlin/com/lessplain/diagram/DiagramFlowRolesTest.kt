package com.lessplain.diagram

import com.lessplain.model.GraphEdge
import com.lessplain.model.GraphNode
import com.lessplain.model.UnderstandingGraph
import com.lessplain.model.UnderstandingRecord
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DiagramFlowRolesTest {
    @Test fun `multiple explicit starts ends and dual roles use separate colors without changing navigation`() {
        SwingUtilities.invokeAndWait {
            val graph = UnderstandingGraph(listOf(GraphNode("a", "开始", flowRoles = listOf("start")),
                GraphNode("b", "普通叶节点"), GraphNode("c", "结束", flowRoles = listOf("end")),
                GraphNode("d", "独立链路", flowRoles = listOf("start", "end")),
                GraphNode("e", "另一入口", flowRoles = listOf("start"))),
                listOf(GraphEdge("ac", "a", "c", "call")))
            val diagram = createUnderstandingDiagram(UnderstandingRecord("root", 1, null, null, "流程", "", null, graph, 0))
            try {
                assertEquals(setOf("a", "d", "e"), diagram.startIds)
                assertEquals(setOf("c", "d"), diagram.endIds)
                val layout = layoutUnderstandingGraph(graph)
                fun image(hovered: String? = null): BufferedImage {
                    val image = BufferedImage(layout.bounds.maxX.toInt() + 100, layout.bounds.maxY.toInt() + 100, BufferedImage.TYPE_INT_ARGB)
                    val g = image.createGraphics()
                    DiagramNodePainter.paint(g, layout, null, hovered, emptyMap(), emptyMap(), diagram.startIds, diagram.endIds)
                    g.dispose()
                    return image
                }
                val normal = image()
                fun fill(id: String) = layout.nodes.getValue(id).bounds.let { normal.getRGB(it.centerX.toInt(), it.maxY.toInt() - 8) }
                assertEquals(fill("a"), fill("d"))
                assertEquals(fill("a"), fill("e"))
                assertNotEquals(fill("a"), fill("c"))
                assertNotEquals(fill("b"), fill("c"))
                val hover = image("c")
                val b = layout.nodes.getValue("c").bounds
                assertEquals(normal.getRGB(b.centerX.toInt(), b.maxY.toInt() - 8), hover.getRGB(b.centerX.toInt(), b.maxY.toInt() - 8))
                assertTrue((b.x.toInt() - 4..b.x.toInt() + 4).any { x ->
                    normal.getRGB(x, b.centerY.toInt()) != hover.getRGB(x, b.centerY.toInt()) })
            } finally { diagram.dispose() }
        }
    }
}
