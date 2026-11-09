package com.lessplain.ui

import com.intellij.icons.AllIcons
import com.intellij.util.ui.JBUI
import org.junit.jupiter.api.Test
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.UIManager
import kotlin.test.*

class CompactIconButtonTest {
    @Test fun `real mouse gestures paint hover and pressed states and activate once on release`() = onEdt {
        var clicks = 0
        val button = compactIconButton(AllIcons.Actions.Back, "返回主图") { clicks++ }
        button.setSize(button.preferredSize)
        val normal = pixels(button)
        mouse(button, MouseEvent.MOUSE_ENTERED)
        val hovered = pixels(button)
        assertNotEquals(normal, hovered, "悬停必须有可见反馈")
        mouse(button, MouseEvent.MOUSE_PRESSED)
        assertNotEquals(hovered, pixels(button), "按下必须有区别于悬停的反馈")
        assertEquals(0, clicks)
        mouse(button, MouseEvent.MOUSE_RELEASED)
        assertEquals(1, clicks, "快速移入并点击也必须生效，且只执行一次")
        mouse(button, MouseEvent.MOUSE_EXITED)
        assertEquals(normal, pixels(button))
        mouse(button, MouseEvent.MOUSE_ENTERED)
        mouse(button, MouseEvent.MOUSE_PRESSED)
        mouse(button, MouseEvent.MOUSE_EXITED)
        mouse(button, MouseEvent.MOUSE_RELEASED)
        assertEquals(1, clicks, "按下后移出再释放应取消点击")
        button.isEnabled = false
        val disabled = pixels(button)
        mouse(button, MouseEvent.MOUSE_ENTERED)
        mouse(button, MouseEvent.MOUSE_PRESSED)
        mouse(button, MouseEvent.MOUSE_RELEASED)
        assertEquals(1, clicks)
        assertEquals(disabled, pixels(button), "禁用时不绘制可点击状态")
    }

    @Test fun `native minimum button width cannot inflate icon actions and keyboard activation remains available`() = onEdt {
        val old = UIManager.get("Button.minimumWidth")
        UIManager.put("Button.minimumWidth", 140)
        try {
            var clicked = 0
            val button = compactIconButton(AllIcons.Actions.Back, "返回主图") { clicked++ }
            assertEquals(JBUI.scale(24), button.preferredSize.width)
            assertEquals(JBUI.scale(24), button.preferredSize.height)
            assertEquals(button.preferredSize, button.minimumSize)
            assertEquals("返回主图", button.accessibleContext.accessibleName)
            button.actionMap.get("activate").actionPerformed(ActionEvent(button, 0, ""))
            assertEquals(1, clicked)
            assertEquals("activate", button.inputMap.get(KeyStroke.getKeyStroke("released ENTER")))
            val space = KeyEvent(button, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, KeyEvent.VK_SPACE, ' ')
            button.keyListeners.forEach { it.keyReleased(space) }
            assertEquals(2, clicked, "Space 只执行一次")
            button.isEnabled = false
            button.actionMap.get("activate").actionPerformed(ActionEvent(button, 0, ""))
            button.keyListeners.forEach { it.keyReleased(space) }
            assertEquals(2, clicked)
        } finally { if (old == null) UIManager.getDefaults().remove("Button.minimumWidth") else UIManager.put("Button.minimumWidth", old) }
    }

    private fun onEdt(block: () -> Unit) {
        val fixture = com.intellij.testFramework.fixtures.IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("CompactActions", true).fixture
        fixture.setUp()
        try { com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait(block) }
        finally { com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait { fixture.tearDown() } }
    }

    private fun mouse(button: JComponent, id: Int) {
        val click = id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_RELEASED
        button.dispatchEvent(MouseEvent(button, id, System.currentTimeMillis(),
            if (id == MouseEvent.MOUSE_PRESSED) InputEvent.BUTTON1_DOWN_MASK else 0,
            button.width / 2, button.height / 2, if (click) 1 else 0, false,
            if (click) MouseEvent.BUTTON1 else MouseEvent.NOBUTTON))
    }

    private fun pixels(button: JComponent): List<Int> {
        val image = BufferedImage(button.width, button.height, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().let { graphics -> try { button.paint(graphics) } finally { graphics.dispose() } }
        return image.getRGB(0, 0, image.width, image.height, null, 0, image.width).toList()
    }
}
