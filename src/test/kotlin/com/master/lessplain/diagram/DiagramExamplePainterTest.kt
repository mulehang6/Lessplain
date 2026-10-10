package com.master.lessplain.diagram

import com.master.lessplain.ui.UnderstandingUiStyle
import java.awt.Color
import java.awt.Font
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import javax.swing.UIManager
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class DiagramExamplePainterTest {
    @Test fun `example details grow with the UI font and keep their footer inside measured bounds`() {
        val keys = listOf("Label.font", "Panel.background", "Label.foreground", "Label.infoForeground")
        val original = keys.associateWith { UIManager.get(it) }
        try {
            SwingUtilities.invokeAndWait {
                var previousHeight = 0
                for (fontSize in listOf(13, 23, 32)) {
                    UIManager.put("Label.font", Font(Font.DIALOG, Font.PLAIN, fontSize))
                    UIManager.put("Panel.background", Color(0x26282C))
                    UIManager.put("Label.foreground", Color(0xDFE1E5))
                    UIManager.put("Label.infoForeground", Color(0x9DA0A8))
                    val width = DiagramExamplePainter.PANEL_WIDTH
                    val height = DiagramExamplePainter.PANEL_HEIGHT
                    assertTrue(height > previousHeight)
                    previousHeight = height
                    val image = BufferedImage(width + 20, height + 20, BufferedImage.TYPE_INT_ARGB)
                    val g = image.createGraphics()
                    try {
                        DiagramExamplePainter.paint(g, Rectangle2D.Double(10.0, 10.0, width.toDouble(), height.toDouble()),
                            DiagramExamplePanel("group", "超长参数 😀 ".repeat(30), "值 😀 ".repeat(40), emptySet()), "node")
                    } finally { g.dispose() }
                    assertTrue((height - fontSize - 20 until height).any { y ->
                        (35 until width - 20).any { x ->
                            val pixel = Color(image.getRGB(x, y), true)
                            colorDistance(pixel, UnderstandingUiStyle.muted) < 80 &&
                                colorDistance(pixel, UnderstandingUiStyle.surface) > 80
                        }
                    }, "${fontSize}pt 的底部提示文字仍然可见")
                    assertTrue((0 until image.width).all { x -> image.getRGB(x, image.height - 1) == 0 })
                }
            }
        } finally { SwingUtilities.invokeAndWait { original.forEach { (key, value) -> UIManager.put(key, value) } } }
    }

    private fun colorDistance(first: Color, second: Color): Int =
        kotlin.math.abs(first.red - second.red) + kotlin.math.abs(first.green - second.green) +
            kotlin.math.abs(first.blue - second.blue)
}
