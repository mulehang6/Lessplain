package com.lessplain.ui

import com.intellij.icons.AllIcons
import com.intellij.util.ui.JBUI
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Dimension
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import kotlin.test.*

class NativePopupTest {
    @Test fun `window mask rounds the whole size and follows resizing`() = SwingUtilities.invokeAndWait {
        listOf(Dimension(520, 400), Dimension(780, 520)).forEach { size ->
            val shape = popupMask.getMask(size)
            assertEquals(size.width.toDouble(), shape.bounds2D.width)
            assertEquals(size.height.toDouble(), shape.bounds2D.height)
            assertFalse(shape.contains(0.0, 0.0))
            assertFalse(shape.contains((size.width - 1).toDouble(), 0.0))
            assertTrue(shape.contains(size.width / 2.0, 1.0))
            assertTrue(shape.contains(size.width / 2.0, size.height - 1.0))
            val image = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
            image.createGraphics().let { g -> try { g.clip = shape; g.color = Color.RED; g.fillRect(0, 0, size.width, size.height) } finally { g.dispose() } }
            assertEquals(0, image.getRGB(0, 0))
            assertNotEquals(0, image.getRGB(size.width / 2, 1))
        }
    }

    @Test fun `shared native shell exposes close control composition guard and escape dispatch`() {
        val fixture = com.intellij.testFramework.fixtures.IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("LessplainNativeChrome", true).fixture
        fixture.setUp()
        try {
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait {
                var composing = true
                val content = javax.swing.JPanel()
                val input = javax.swing.JTextArea()
                content.add(input)
                val popup = nativePopupBuilder(content, input, "笔记", false) { !composing }.createPopup()
                try {
                    fun components(component: java.awt.Component): List<java.awt.Component> = listOf(component) +
                        if (component is java.awt.Container) component.components.flatMap(::components) else emptyList()
                    val close = components(popup.content).filterIsInstance<javax.swing.JComponent>().single { it.toolTipText == "关闭" }
                    // 平台测试使用不绘制的 DummyIcon。仅替换图标画笔，保留真实关闭控件和悬停事件。
                    assertNotSame(popupCloseIcon.regular, popupCloseIcon.hovered)
                    val assets = popupCloseIcon.replaceBy(object : com.intellij.ui.icons.IconReplacer {
                        override fun replaceIcon(original: javax.swing.Icon): javax.swing.Icon = object : javax.swing.Icon {
                            override fun getIconWidth() = 16
                            override fun getIconHeight() = 16
                            override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics, x: Int, y: Int) {
                                g.color = if (original === popupCloseIcon.hovered) Color.BLUE else Color.GRAY
                                g.fillRect(x, y, iconWidth, iconHeight)
                            }
                        }
                    })
                    (close as com.intellij.ui.InplaceButton).apply { setIcons(assets); setPainting(true); setActive(true) }
                    close.setSize(close.preferredSize)
                    fun paintClose(): List<Int> {
                        val image = BufferedImage(close.width, close.height, BufferedImage.TYPE_INT_ARGB)
                        image.createGraphics().let { g -> try { close.paint(g) } finally { g.dispose() } }
                        return image.getRGB(0, 0, image.width, image.height, null, 0, image.width).toList()
                    }
                    val normal = paintClose()
                    close.dispatchEvent(java.awt.event.MouseEvent(close, java.awt.event.MouseEvent.MOUSE_ENTERED, 0, 0, 3, 3, 0, false))
                    assertNotEquals(normal, paintClose(), "所有共用浮窗的关闭 X 都应有主题 hover 反馈")
                    close.dispatchEvent(java.awt.event.MouseEvent(close, java.awt.event.MouseEvent.MOUSE_EXITED, 0, 0, -1, -1, 0, false))
                    assertEquals(normal, paintClose())
                    assertTrue(popup.isPersistent)
                    assertTrue(popup.isCancelKeyEnabled)
                    val escape = java.awt.event.KeyEvent(input, java.awt.event.KeyEvent.KEY_PRESSED, 0, 0, java.awt.event.KeyEvent.VK_ESCAPE, java.awt.event.KeyEvent.CHAR_UNDEFINED)
                    // 未显示窗口的 cancel 会直接释放；输入法保护通过同一个公开关闭条件检查。
                    assertFalse(popup.canClose())
                    composing = false
                    assertTrue(popup.canClose())
                    popup.dispatchKeyEvent(escape)
                    assertTrue(popup.isDisposed)
                } finally { if (!popup.isDisposed) com.intellij.openapi.util.Disposer.dispose(popup) }
                val catalog = nativePopupBuilder(javax.swing.JPanel(), null, "教程目录", true).createPopup()
                try { assertFalse(catalog.isPersistent) } finally { com.intellij.openapi.util.Disposer.dispose(catalog) }
            }
        } finally { com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait { fixture.tearDown() } }
    }

    @Test fun `icon buttons expose counts descriptions disabled feedback and a scaled hit area`() = SwingUtilities.invokeAndWait {
        var clicks = 0
        val button = popupIconButton(AllIcons.General.ContextHelp, "打开2条追问", 2) { clicks++ }
        assertEquals("2", button.text)
        assertEquals("打开2条追问", button.accessibleContext.accessibleName)
        assertTrue(button.preferredSize.width >= JBUI.scale(28))
        assertEquals(JBUI.scale(28), button.preferredSize.height)
        button.isEnabled = false; button.doClick()
        assertEquals(0, clicks)
        button.isEnabled = true; button.doClick()
        assertEquals(1, clicks)
        val empty = popupIconButton(AllIcons.Actions.Edit, "添加笔记") {}
        assertTrue(empty.text.isNullOrEmpty())
        assertEquals(JBUI.scale(28), empty.preferredSize.width)
        assertEquals(empty.preferredSize, empty.minimumSize)
        assertTrue(button.preferredSize.width < JBUI.scale(44), "计数只增加实际文字宽度")
    }
}
