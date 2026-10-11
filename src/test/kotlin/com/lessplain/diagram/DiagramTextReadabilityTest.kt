package com.lessplain.diagram

import com.lessplain.model.GraphNode
import com.lessplain.model.UnderstandingGraph
import java.awt.Font
import java.awt.image.BufferedImage
import javax.swing.UIManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 用实际节点字号验证全文容量和单词边界，避免几何通过但文字仍截断。 */
class DiagramTextReadabilityTest {
    @Test fun `labels preserve ordinary words and allocate every line`() {
        val font = Font(Font.DIALOG, Font.PLAIN, 17)
        val text = "配置 Koog 运行\n保存或中断检查并恢复未完成的工具调用"
        val (width, height) = DiagramTextMetrics.nodeSize(text, false, true, font)
        val inset = DiagramNodeSemantics.textInset(font, true)
        val lines = DiagramTextMetrics.lines(text, width.toInt() - 24 - inset, font)
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.font = font
            assertTrue(lines.any { "Koog" in it })
            assertTrue(lines.all { g.fontMetrics.stringWidth(it) <= width - 24 - inset })
            assertTrue(height >= lines.size * g.fontMetrics.height + 28)
            assertEquals(text.replace("\n", ""), lines.joinToString(""))
        } finally { g.dispose() }
    }

    @Test fun `long label remains complete past three lines`() {
        val font = Font(Font.DIALOG, Font.PLAIN, 17)
        val text = "连接模型与工具节点并记录执行结果，等待用户审批，再恢复检查点并将执行状态回传给调用者".repeat(2)
        val (width, height) = DiagramTextMetrics.nodeSize(text, false, false, font)
        val lines = DiagramTextMetrics.lines(text, width.toInt() - 24, font)
        val g = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
        try {
            g.font = font
            assertTrue(lines.size > 3)
            assertTrue(height >= lines.size * g.fontMetrics.height + 28)
            assertEquals(text, lines.joinToString(""))
        } finally { g.dispose() }
    }

    @Test fun `placed node retains measured font after theme font changes`() {
        val before = UIManager.get("Label.font")
        try {
            val font = Font(Font.DIALOG, Font.PLAIN, 17)
            UIManager.put("Label.font", font)
            val graph = UnderstandingGraph(listOf(GraphNode("n", "配置 Koog 运行")), emptyList())
            val input = DiagramLayoutInput.prepare(graph, LayoutDirection.VERTICAL)
            UIManager.put("Label.font", Font(Font.DIALOG, Font.PLAIN, 11))
            val layout = ElkGraphLayoutAdapter.layout(input)
            assertEquals(font, layout.nodes.getValue("n").font)
        } finally { UIManager.put("Label.font", before) }
    }
}
