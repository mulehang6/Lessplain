package com.lessplain.lesson

import com.lessplain.diagram.layoutUnderstandingGraph
import com.lessplain.model.GraphEdge
import com.lessplain.model.GraphNode
import com.lessplain.model.UnderstandingGraph
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LessonRenderingTest {
    @Test fun `long table explanations wrap when the tutorial narrows`() {
        SwingUtilities.invokeAndWait {
            val table = createLessonTable(LessonBlock("members", "table", columns = listOf("作用"),
                rows = listOf(listOf("Continuation 保存执行位置和必要数据，使挂起的计算稍后能够继续。".repeat(5)))))
            table.setSize(640, 600); table.doLayout()
            val wide = table.getRowHeight(0)
            table.setSize(200, 600); table.doLayout()
            assertTrue(table.getRowHeight(0) > wide)
            assertTrue(!table.isCellEditable(0, 0))
            val scroll = contentScroll(table)
            assertEquals(javax.swing.JScrollPane.VERTICAL_SCROLLBAR_NEVER, scroll.verticalScrollBarPolicy)
            assertTrue(scroll.preferredSize.height >= table.preferredSize.height)
        }
    }

    @Test fun `a growing diagram updates its scroll container height without creating a huge page`() {
        SwingUtilities.invokeAndWait {
            val figure = JPanel().apply { preferredSize = Dimension(600, 150) }
            val scroll = contentScroll(figure, 600)
            val initial = scroll.preferredSize.height
            figure.preferredSize = Dimension(600, 450)
            assertTrue(scroll.preferredSize.height > initial)
            assertEquals(scroll.preferredSize.height, scroll.maximumSize.height)
            figure.preferredSize = Dimension(600, 2_000)
            assertTrue(scroll.preferredSize.height < figure.preferredSize.height)
        }
    }

    @Test fun `Chinese and long identifiers paint within the diagram node supplied by layout`() {
        val label = "Flow<AgentStreamEvent>\n向收集者逐个提供项目事件"
        val graph = UnderstandingGraph(
            listOf(GraphNode("flow", label), GraphNode("collector", "处理收到的事件")),
            listOf(GraphEdge("events", "flow", "collector", "data_flow")),
        )
        val layout = layoutUnderstandingGraph(graph)
        val node = layout.nodes.getValue("flow")
        val image = BufferedImage(node.bounds.width.toInt() + 40, node.bounds.height.toInt() + 40, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.color = Color.BLACK
            g.font = node.font ?: Font(Font.DIALOG, Font.PLAIN, 13)
            drawLessonLabel(g, label, Rectangle2D.Double(20.0, 20.0, node.bounds.width, node.bounds.height))
        } finally { g.dispose() }
        val pixels = (0 until image.height).flatMap { y -> (0 until image.width).mapNotNull { x ->
            if ((image.getRGB(x, y) ushr 24) != 0) x to y else null
        } }
        assertTrue(pixels.isNotEmpty())
        assertTrue(pixels.all { (x, y) -> x >= 20 && x < 20 + node.bounds.width && y >= 20 && y < 20 + node.bounds.height })
    }
}
