package com.master.lessplain.diagram

import com.intellij.ui.JBColor
import com.master.lessplain.model.GraphEdge
import com.master.lessplain.model.GraphNode
import com.master.lessplain.model.UnderstandingGraph
import com.master.lessplain.model.branchNodeIds
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import javax.swing.UIManager
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DiagramNodeSemanticsTest {
    @Test fun `success failure unclassified and dual roles keep semantic colors on hover`() {
        val graph = UnderstandingGraph(listOf(GraphNode("normal", "普通"),
            GraphNode("success", "成功", flowRoles = listOf("end"), endOutcome = "success"),
            GraphNode("failure", "失败", flowRoles = listOf("end"), endOutcome = "failure"),
            GraphNode("unknown", "未分类", flowRoles = listOf("end")),
            GraphNode("start", "入口", flowRoles = listOf("start")),
            GraphNode("dual", "独立流程", flowRoles = listOf("start", "end"), endOutcome = "success")), emptyList())
        val layout = layoutUnderstandingGraph(graph)
        val starts = setOf("start", "dual")
        val ends = setOf("success", "failure", "unknown", "dual")
        val darkBefore = JBColor.isBright()
        try {
            for (dark in listOf(false, true)) {
                JBColor.setDark(dark)
                fun image(focus: String? = null): BufferedImage {
                    val image = BufferedImage(layout.bounds.maxX.toInt() + 50, layout.bounds.maxY.toInt() + 50, BufferedImage.TYPE_INT_ARGB)
                    val g = image.createGraphics()
                    try { DiagramNodePainter.paint(g, layout, focus, focus, emptyMap(), emptyMap(), starts, ends) }
                    finally { g.dispose() }
                    return image
                }
                val base = image()
                fun fill(id: String, image: BufferedImage = base) = layout.nodes.getValue(id).bounds.let {
                    image.getRGB(it.centerX.toInt(), it.maxY.toInt() - 8)
                }
                assertEquals(DiagramNodeSemantics.successFill.rgb, fill("success"))
                assertEquals(DiagramNodeSemantics.failureFill.rgb, fill("failure"))
                assertEquals(DiagramNodeSemantics.unclassifiedFill.rgb, fill("unknown"))
                assertNotEquals(fill("normal"), fill("unknown"))
                assertEquals(fill("start"), fill("dual"))
                assertEquals(fill("failure"), fill("failure", image("failure")))
                val dual = layout.nodes.getValue("dual").bounds
                assertEquals(DiagramNodeSemantics.successBorder.rgb, base.getRGB(dual.x.toInt(), dual.centerY.toInt()))
            }
        } finally { JBColor.setDark(!darkBefore) }
    }

    @Test fun `only fork nodes paint the glyph while merge and single successors do not`() {
        val graph = UnderstandingGraph(listOf("left", "right", "merge", "fork", "done").map { GraphNode(it, it) },
            listOf(GraphEdge("left-merge", "left", "merge", "call"),
                GraphEdge("right-merge", "right", "merge", "call"),
                GraphEdge("merge-fork", "merge", "fork", "control_flow"),
                GraphEdge("fork-done", "fork", "done", "call"),
                GraphEdge("fork-left", "fork", "left", "data_flow")))
        val branches = graph.branchNodeIds()
        assertEquals(setOf("fork"), branches)
        val layout = layoutUnderstandingGraph(graph)
        val bright = JBColor.isBright()
        try {
            for (dark in listOf(false, true)) {
                JBColor.setDark(dark)
                for (id in listOf("merge", "fork", "done")) {
                    val bounds = layout.nodes.getValue(id).bounds
                    val image = BufferedImage(300, 100, BufferedImage.TYPE_INT_ARGB)
                    val g = image.createGraphics()
                    try {
                        g.translate(20 - bounds.x, 20 - bounds.y)
                        DiagramNodePainter.paint(g, layout, null, null, emptyMap(), emptyMap(), emptySet(), emptySet(),
                            branchIds = branches)
                    } finally { g.dispose() }
                    val ink = (20 until 60).sumOf { x -> (22 until 53).count { y ->
                        image.getRGB(x, y) == DiagramNodeSemantics.branchColor.rgb
                    } }
                    if (id == "fork") assertTrue(ink > 0) else assertEquals(0, ink)
                }
            }
        } finally { JBColor.setDark(!bright) }
    }

    @Test fun `fork glyph follows successors matches font size and stays bounded`() {
        val fontBefore = UIManager.getFont("Label.font")
        val brightBefore = JBColor.isBright()
        try {
            for (dark in listOf(false, true)) for (size in listOf(12, 18, 28)) {
                JBColor.setDark(dark)
                val font = Font(Font.DIALOG, Font.PLAIN, size)
                UIManager.put("Label.font", font)
                val node = GraphNode("choice", "需要校验访问权限与文章状态，然后选择处理路径")
                val graph = UnderstandingGraph(listOf(node, GraphNode("a", "A"), GraphNode("b", "B")),
                    listOf(GraphEdge("a", "choice", "a", "control_flow", "有权限"),
                        GraphEdge("b", "choice", "b", "control_flow", "无权限")))
                val input = DiagramLayoutInput.prepare(graph, LayoutDirection.VERTICAL)
                assertEquals(setOf("choice"), graph.branchNodeIds())
                assertEquals(DiagramTextMetrics.nodeSize(node.label, question = false, branch = true), input.nodeSizes["choice"])
                val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
                probe.font = font
                val metrics = probe.fontMetrics
                val bounds = DiagramNodeSemantics.iconBounds(Rectangle2D.Double(10.0, 10.0, 260.0, 100.0), font, metrics.ascent, metrics.descent)
                probe.dispose()
                assertEquals(size.toDouble(), bounds.width)
                assertEquals(size.toDouble(), bounds.height)
                assertTrue(bounds.maxX + size / 2.0 <= 22 + DiagramNodeSemantics.textInset(font, true))
                for (scale in listOf(0.75, 1.0, 1.5, 2.0)) {
                    val image = BufferedImage(160, 160, BufferedImage.TYPE_INT_ARGB)
                    val g = image.createGraphics()
                    try {
                        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        g.scale(scale, scale)
                        DiagramNodeSemantics.paintBranch(g, bounds)
                    } finally { g.dispose() }
                    var ink = 0
                    for (y in 0 until image.height) for (x in 0 until image.width) {
                        if (image.getRGB(x, y) ushr 24 == 0) continue
                        ink++
                        assertTrue(x >= bounds.x * scale - 1 && x <= bounds.maxX * scale + 1)
                        assertTrue(y >= bounds.y * scale - 1 && y <= bounds.maxY * scale + 1)
                    }
                    assertTrue(ink >= 15)
                }
                val oneSuccessor = graph.copy(edges = graph.edges.take(1))
                assertEquals(emptySet(), oneSuccessor.branchNodeIds())
                assertEquals(DiagramTextMetrics.nodeSize(node.label, question = false),
                    DiagramLayoutInput.prepare(oneSuccessor, LayoutDirection.VERTICAL).nodeSizes["choice"])
            }
        } finally { UIManager.put("Label.font", fontBefore); JBColor.setDark(!brightBefore) }
    }
}
