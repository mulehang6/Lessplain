package com.master.lessplain.diagram

import com.master.lessplain.model.GraphNode
import com.master.lessplain.model.UnderstandingGraph
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.UIManager
import org.junit.jupiter.api.Test
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.assertTrue

class DiagramNodeFocusTest {
    @Test fun `start selection and branch preview stay visible when generic focus border matches the surface`() {
        withTheme {
            val layout = layoutUnderstandingGraph(UnderstandingGraph(listOf(GraphNode("node", "节点")), emptyList()))
            val themes = listOf(
                Triple(Color(0x1E1F22), Color(0x2B2D30), Color(0x3574F0)),
                Triple(Color(0x191A1C), Color(0x191A1C), Color(0x3871E1)),
                Triple(Color.WHITE, Color.WHITE, Color(0x3574F0)),
            )
            for ((canvas, surface, focus) in themes) {
                UIManager.put("EditorPane.background", canvas)
                UIManager.put("Panel.background", surface)
                UIManager.put("Component.borderColor", Color(0x4E5157))
                UIManager.put("Component.focusedBorderColor", surface)
                UIManager.put("ActionButton.focusedBorderColor", focus)
                UIManager.put("Component.focusColor", focus)
                for (highlight in Highlight.entries) for (zoom in listOf(0.75, 1.0, 1.5, 2.0)) {
                    val image = render(layout, highlight, zoom, canvas)
                    assertVisibleBorder(image, layout, zoom, canvas, "$highlight, zoom=$zoom, canvas=$canvas",
                        dashed = highlight == Highlight.CHOICE)
                }
            }
        }
    }

    @Test fun `transparent focus defaults fall through to an opaque native focus color`() {
        withTheme {
            val canvas = Color(0x191A1C)
            val layout = layoutUnderstandingGraph(UnderstandingGraph(listOf(GraphNode("node", "节点")), emptyList()))
            UIManager.put("EditorPane.background", canvas)
            UIManager.put("Panel.background", canvas)
            UIManager.put("Component.focusedBorderColor", canvas)
            UIManager.put("ActionButton.focusedBorderColor", Color(0, 0, 0, 0))
            UIManager.put("Component.focusColor", Color(0x548AF7))
            UIManager.put("Link.activeForeground", Color(0x71A1FE))
            for (focus in listOf(Color(0x548AF7), Color(0x548AF7 or (128 shl 24), true))) {
                UIManager.put("Component.focusColor", focus)
                val image = render(layout, Highlight.SELECTED, 1.0, canvas)
                assertVisibleBorder(image, layout, 1.0, canvas, "透明焦点色不能隐藏选中边框")
            }
        }
    }

    private enum class Highlight { START, SELECTED, CHOICE }

    private fun render(
        layout: UnderstandingGraphLayout,
        highlight: Highlight,
        zoom: Double,
        canvas: Color,
    ): BufferedImage {
        val bounds = layout.nodes.getValue("node").bounds
        val image = BufferedImage(ceil((bounds.width + 24) * zoom).toInt(),
            ceil((bounds.height + 24) * zoom).toInt(), BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.color = canvas
            g.fillRect(0, 0, image.width, image.height)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.scale(zoom, zoom)
            g.translate(12 - bounds.x, 12 - bounds.y)
            DiagramNodePainter.paint(g, layout, if (highlight == Highlight.SELECTED) "node" else null,
                null, emptyMap(), emptyMap(), if (highlight == Highlight.START) setOf("node") else emptySet(),
                emptySet(), focusedChoice = if (highlight == Highlight.CHOICE) "node" else null)
        } finally { g.dispose() }
        return image
    }

    private fun assertVisibleBorder(
        image: BufferedImage,
        layout: UnderstandingGraphLayout,
        zoom: Double,
        canvas: Color,
        message: String,
        dashed: Boolean = false,
    ) {
        val height = layout.nodes.getValue("node").bounds.height
        val edgeX = floor(12 * zoom).toInt()
        val firstY = ceil((12 + height * 0.35) * zoom).toInt()
        val lastY = floor((12 + height * 0.65) * zoom).toInt()
        val visibleRows = (firstY..lastY).count { y ->
            (edgeX - 2..edgeX + 2).any { x -> contrast(Color(image.getRGB(x, y), true), canvas) >= 3.0 }
        }
        if (dashed) assertTrue(visibleRows >= (lastY - firstY + 1) / 3, "$message, 虚线应清楚可见")
        else assertTrue(visibleRows == lastY - firstY + 1, "$message, 实线边框应连续可见")
    }

    private fun contrast(first: Color, second: Color): Double {
        fun luminance(color: Color): Double {
            fun channel(value: Int): Double = (value / 255.0).let {
                if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4)
            }
            return channel(color.red) * 0.2126 + channel(color.green) * 0.7152 + channel(color.blue) * 0.0722
        }
        val a = luminance(first)
        val b = luminance(second)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    private fun withTheme(action: () -> Unit) {
        val keys = listOf("EditorPane.background", "Panel.background", "Component.borderColor",
            "Component.focusedBorderColor", "ActionButton.focusedBorderColor", "Component.focusColor", "Link.activeForeground")
        val originals = keys.associateWith { UIManager.get(it) }
        try { action() } finally { originals.forEach { (key, value) -> UIManager.put(key, value) } }
    }
}
