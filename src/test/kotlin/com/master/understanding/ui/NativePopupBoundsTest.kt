package com.master.understanding.ui

import com.intellij.openapi.ui.popup.JBPopup
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.lang.reflect.Proxy
import javax.swing.BorderFactory
import javax.swing.JPanel
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class NativePopupBoundsTest {
    @Test fun `window bounds include native border instead of growing past the requested viewport`() {
        for (scale in listOf(1, 2)) {
            val panel = JPanel().apply {
                border = BorderFactory.createEmptyBorder(6 * scale, 8 * scale, 6 * scale, 8 * scale)
            }
            var nativeSize = Dimension()
            var nativeLocation = Point()
            // Platform setters use content coordinates; getSize reports the whole native window.
            val popup = Proxy.newProxyInstance(JBPopup::class.java.classLoader, arrayOf(JBPopup::class.java)) {
                    _, method, arguments ->
                when (method.name) {
                    "getContent" -> panel
                    "setSize" -> {
                        val content = arguments!![0] as Dimension
                        nativeSize = Dimension(content.width + panel.insets.left + panel.insets.right,
                            content.height + panel.insets.top + panel.insets.bottom)
                        null
                    }
                    "getSize" -> nativeSize
                    "getLocationOnScreen" -> Point(nativeLocation.x + panel.insets.left, nativeLocation.y + panel.insets.top)
                    "setLocation" -> {
                        val content = arguments!![0] as Point
                        nativeLocation = Point(content.x - panel.insets.left, content.y - panel.insets.top)
                        null
                    }
                    else -> error("未模拟的公开方法：${method.name}")
                }
            } as JBPopup
            val screen = Rectangle(-1920, -1080, 1920, 1080)
            for (bounds in listOf(Rectangle(-1580, -300, 240, 160), Rectangle(-1920, -1080, 240, 160))) {
                setNativePopupBounds(popup, bounds)
                assertEquals(bounds, Rectangle(nativeLocation, popup.size))
                assertEquals(bounds.location, nativePopupLocation(popup))
                repeat(3) {
                    val resized = clampPopupBounds(nativePopupLocation(popup), popup.size, screen)
                    setNativePopupBounds(popup, resized)
                    assertEquals(bounds, Rectangle(nativeLocation, popup.size))
                }
            }
        }
    }
}
