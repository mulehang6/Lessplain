package com.master.understanding.diagram

import com.master.understanding.model.GraphNode
import com.master.understanding.model.UnderstandingGraph
import com.master.understanding.model.UnderstandingRecord
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import javax.swing.UIManager
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagramAppearanceTest {
    @Test fun `already loaded node painter follows live theme surface and text colors`() {
        val keys = listOf("Panel.background", "Label.foreground", "Component.borderColor")
        val originals = keys.associateWith { UIManager.get(it) }
        val graph = UnderstandingGraph(listOf(GraphNode("node", "节点")), emptyList())
        val layout = layoutUnderstandingGraph(graph)
        val bounds = layout.nodes.getValue("node").bounds
        try {
            for ((surface, foreground) in listOf(Color(0xFFFFFF) to Color(0x1E1F22),
                Color(0x2B2D30) to Color(0xDFE1E5))) {
                UIManager.put("Panel.background", surface)
                UIManager.put("Label.foreground", foreground)
                UIManager.put("Component.borderColor", Color(0x797F8B))
                val image = BufferedImage(layout.bounds.maxX.toInt() + 30, layout.bounds.maxY.toInt() + 30,
                    BufferedImage.TYPE_INT_ARGB)
                val g = image.createGraphics()
                try {
                    DiagramNodePainter.paint(g, layout, null, null, emptyMap(), emptyMap(), emptySet(), emptySet())
                } finally { g.dispose() }
                assertEquals(surface.rgb, image.getRGB(bounds.centerX.toInt(), bounds.maxY.toInt() - 8))
                assertTrue((bounds.y.toInt() + 15 until bounds.maxY.toInt() - 10).any { y ->
                    (bounds.x.toInt() + 12 until bounds.maxX.toInt() - 12).any { x -> image.getRGB(x, y) == foreground.rgb }
                }, "标签使用当前主题的前景色")
            }
        } finally { originals.forEach { (key, value) -> UIManager.put(key, value) } }
    }

    @Test fun `open diagram refreshes UI fonts and releases listeners when disposed`() {
        val original = UIManager.getFont("Label.font")
        var diagram: UnderstandingDiagram? = null
        var defaultsBefore = 0
        var managerBefore = 0
        try {
            SwingUtilities.invokeAndWait {
                UIManager.put("Label.font", Font(Font.DIALOG, Font.PLAIN, 17))
                defaultsBefore = UIManager.getDefaults().propertyChangeListeners.size
                managerBefore = UIManager.getPropertyChangeListeners().size
                val graph = UnderstandingGraph(emptyList(), emptyList())
                val record = UnderstandingRecord("test", 1, null, null, "图表", "", null, graph, 0)
                val created = UnderstandingDiagram(record, null)
                diagram = created
                assertEquals(17, created.header.label.font.size)
                assertTrue(created.header.label.font.isBold)
                UIManager.put("Label.font", Font(Font.SERIF, Font.PLAIN, 23))
            }
            SwingUtilities.invokeAndWait {
                val current = checkNotNull(diagram)
                assertEquals(23, current.header.label.font.size)
                assertEquals(23, current.sourceStatus.label.font.size)
                assertTrue(current.header.label.font.isBold)
                assertTrue(current.layoutRequestPending, "字体变化会重新测量图表")
                current.dispose()
                assertEquals(defaultsBefore, UIManager.getDefaults().propertyChangeListeners.size)
                assertEquals(managerBefore, UIManager.getPropertyChangeListeners().size)
                UIManager.put("Label.font", Font(Font.DIALOG, Font.PLAIN, 25))
            }
            SwingUtilities.invokeAndWait {
                val current = checkNotNull(diagram)
                assertEquals(23, current.header.label.font.size)
                assertFalse(current.layoutBusy)
            }
        } finally {
            SwingUtilities.invokeAndWait { diagram?.dispose(); UIManager.put("Label.font", original) }
        }
    }
}
