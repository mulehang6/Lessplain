package com.master.understanding.ui

import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import javax.swing.UIManager
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnderstandingControlPainterTest {
    @Test fun `navigation follows live theme colors and disabled hover remains quiet`() {
        withColors {
            for ((foreground, background) in listOf(Color(0x34363C) to Color.WHITE,
                Color(0xDFE1E5) to Color(0x2B2D30))) {
                UIManager.put("Button.foreground", foreground)
                UIManager.put("Panel.background", background)
                UIManager.put("Button.disabledText", Color(0x858A96))
                UIManager.put("ActionButton.hoverBackground", Color(0x548AF7))
                val enabled = navigation(enabled = true, hovered = false)
                val hover = navigation(enabled = true, hovered = true)
                val disabled = navigation(enabled = false, hovered = false)
                val disabledHover = navigation(enabled = false, hovered = true)
                assertTrue(pixels(enabled).any { (it and 0xFFFFFF) == (foreground.rgb and 0xFFFFFF) && (it ushr 24) > 0 })
                assertEquals(Color(0x548AF7).rgb, hover.getRGB(21, 22))
                assertEquals(0, enabled.getRGB(21, 22), "默认行内按钮不铺常驻底色")
                assertTrue(pixels(disabled).contentEquals(pixels(disabledHover)), "禁用按钮不出现悬停反馈")
                assertTrue(pixels(disabled).any { it ushr 24 > 0 })
            }
        }
    }

    @Test fun `navigation ink stays within its hit rectangle at different scales and heights`() {
        withColors {
            UIManager.put("Button.foreground", Color.BLACK)
            for (scale in listOf(0.75, 1.0, 1.5, 2.0)) for (height in listOf(1, 12, 20)) {
                val bounds = Rectangle(16, 12, 20, height)
                val image = BufferedImage(100, 90, BufferedImage.TYPE_INT_ARGB)
                val g = image.createGraphics()
                try {
                    g.scale(scale, scale)
                    UnderstandingControlPainter.paintNavigation(g, bounds, next = false, enabled = true, hovered = true)
                } finally { g.dispose() }
                var ink = 0
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    if (image.getRGB(x, y) ushr 24 == 0) continue
                    ink++
                    assertTrue(x >= bounds.x * scale - 1 && x < bounds.maxX * scale + 1)
                    assertTrue(y >= bounds.y * scale - 1 && y < bounds.maxY * scale + 1)
                }
                if (height > 1) assertTrue(ink > 10)
            }
        }
    }

    private fun navigation(enabled: Boolean, hovered: Boolean): BufferedImage {
        val image = BufferedImage(60, 48, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            UnderstandingControlPainter.paintNavigation(g, Rectangle(16, 12, 20, 20),
                next = true, enabled = enabled, hovered = hovered)
        } finally { g.dispose() }
        return image
    }

    private fun pixels(image: BufferedImage): IntArray =
        image.getRGB(0, 0, image.width, image.height, null, 0, image.width)

    private fun withColors(action: () -> Unit) {
        val keys = listOf("Button.foreground", "Panel.background", "Button.disabledText", "ActionButton.hoverBackground")
        val originals = keys.associateWith { UIManager.get(it) }
        try { action() } finally { originals.forEach { (key, value) -> UIManager.put(key, value) } }
    }
}
