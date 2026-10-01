package com.master.understanding.diagram

import java.awt.Color
import java.awt.image.BufferedImage
import javax.swing.JButton
import javax.swing.UIManager
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class GraphControlIconTest {
    @Test
    fun `fit and arrange remain distinct visible and bounded in themes and scales`() {
        val button = JButton()
        val old = UIManager.getColor("Button.foreground")
        try {
            for (color in listOf(Color(0x252525), Color(0xDFE1E5))) {
                UIManager.put("Button.foreground", color)
                for (scale in listOf(1.0, 1.25, 1.5, 2.0)) {
                    val pixels = listOf(GraphControlIconKind.FIT, GraphControlIconKind.ARRANGE).map { kind ->
                        val image = BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB)
                        val g = image.createGraphics()
                        try {
                            g.scale(scale, scale)
                            GraphControlIcon(kind).paintIcon(button, g, 3, 3)
                        } finally { g.dispose() }
                        val painted = mutableSetOf<Pair<Int, Int>>()
                        val ink = mutableListOf<Color>()
                        for (y in 0 until image.height) for (x in 0 until image.width) {
                            val rgb = image.getRGB(x, y)
                            if (rgb ushr 24 == 0) continue
                            painted += x to y
                            if (rgb ushr 24 >= 192) ink += Color(rgb, true)
                            assertTrue(x >= 2 * scale && y >= 2 * scale && x < 20 * scale && y < 20 * scale,
                                "图标不应越出按钮图形区域: $kind / $scale")
                        }
                        assertTrue(painted.size >= 45, "主题与缩放下图标必须可见")
                        assertTrue(ink.isNotEmpty())
                        // Repeated AA compositing rounds fringe colors; dominant ink still follows the theme.
                        val middle = ink.size / 2
                        assertTrue(kotlin.math.abs(ink.map { it.red }.sorted()[middle] - color.red) <= 2 &&
                            kotlin.math.abs(ink.map { it.green }.sorted()[middle] - color.green) <= 2 &&
                            kotlin.math.abs(ink.map { it.blue }.sorted()[middle] - color.blue) <= 2)
                        painted
                    }
                    assertTrue(pixels[0] != pixels[1], "显示全图和整理节点必须可辨认")
                }
            }
        } finally {
            if (old == null) UIManager.getDefaults().remove("Button.foreground")
            else UIManager.put("Button.foreground", old)
        }
    }
}
