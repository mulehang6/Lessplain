package com.master.understanding.diagram

import com.master.understanding.model.GraphNode
import com.master.understanding.model.UnderstandingGraph
import com.master.understanding.ui.UnderstandingUiStyle
import java.awt.Color
import java.awt.image.BufferedImage
import javax.swing.UIManager
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DiagramNodeSelectionTest {
    @Test fun `purple selection replaces every role border while keeping role surfaces`() = withColors {
        val nodes = listOf(GraphNode("normal", "普通"), GraphNode("start", "起点"),
            GraphNode("success", "成功", endOutcome = "success"),
            GraphNode("failure", "失败", endOutcome = "failure"), GraphNode("unknown", "未分类"),
            GraphNode("dual", "独立流程", endOutcome = "success"))
        val layout = layoutUnderstandingGraph(UnderstandingGraph(nodes, emptyList()))
        val starts = setOf("start", "dual")
        val ends = setOf("success", "failure", "unknown", "dual")
        for ((surface, purple) in listOf(Color.WHITE to Color(0x8060DB), Color(0x26282C) to Color(0x967BEF))) {
            UIManager.put("Panel.background", surface)
            UIManager.put("MasterUnderstanding.Graph.selection", purple)
            for (node in nodes) {
                val before = render(layout, node.id, starts, ends)
                val selected = render(layout, node.id, starts, ends, focused = node.id)
                val bounds = layout.nodes.getValue(node.id).bounds
                val y = (12 + bounds.height / 2).toInt()
                assertEquals(UnderstandingUiStyle.graphSelection.rgb, selected.getRGB(12, y))
                if (node.id != "normal") {
                    val fillY = (12 + bounds.height - 8).toInt()
                    assertEquals(before.getRGB(20, fillY), selected.getRGB(20, fillY), "${node.id} 保留角色底色")
                }
            }
            assertEquals(UnderstandingUiStyle.accent.rgb,
                render(layout, "start", starts, ends).getRGB(12, (12 + layout.nodes.getValue("start").bounds.height / 2).toInt()))
            assertNotEquals(UnderstandingUiStyle.accent.rgb, UnderstandingUiStyle.graphSelection.rgb)
        }
    }

    @Test fun `preview is dashed and current selection wins when both refer to the same node`() = withColors {
        val layout = layoutUnderstandingGraph(UnderstandingGraph(listOf(GraphNode("node", "节点")), emptyList()))
        val height = layout.nodes.getValue("node").bounds.height.toInt()
        val preview = render(layout, "node", emptySet(), emptySet(), preview = "node")
        val current = render(layout, "node", emptySet(), emptySet(), focused = "node", preview = "node")
        val rows = 25 until 12 + height - 12
        assertTrue(rows.any { preview.getRGB(12, it) == UnderstandingUiStyle.graphSelection.rgb })
        assertTrue(rows.any { preview.getRGB(12, it) != UnderstandingUiStyle.graphSelection.rgb })
        assertTrue(rows.all { current.getRGB(12, it) == UnderstandingUiStyle.graphSelection.rgb })
        val hover = render(layout, "node", emptySet(), emptySet(), hovered = "node")
        assertTrue(rows.none { hover.getRGB(12, it) == UnderstandingUiStyle.graphSelection.rgb }, "普通悬停不冒充选中")
    }

    private fun render(
        layout: UnderstandingGraphLayout,
        id: String,
        starts: Set<String>,
        ends: Set<String>,
        focused: String? = null,
        preview: String? = null,
        hovered: String? = null,
    ): BufferedImage {
        val bounds = layout.nodes.getValue(id).bounds
        val image = BufferedImage(bounds.width.toInt() + 24, bounds.height.toInt() + 24, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.translate(12 - bounds.x, 12 - bounds.y)
            DiagramNodePainter.paint(g, layout, focused, hovered, emptyMap(), emptyMap(), starts, ends,
                focusedChoice = preview)
        } finally { g.dispose() }
        return image
    }

    private fun withColors(action: () -> Unit) {
        val colors = mapOf("Panel.background" to Color(0x26282C), "ActionButton.focusedBorderColor" to Color(0x3871E1),
            "MasterUnderstanding.Graph.selection" to Color(0x967BEF))
        val original = colors.keys.associateWith { UIManager.get(it) }
        try { colors.forEach { (key, value) -> UIManager.put(key, value) }; action() }
        finally { original.forEach { (key, value) -> UIManager.put(key, value) } }
    }
}
