package com.master.lessplain.diagram

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.master.lessplain.ide.GraphLayoutPreference
import com.master.lessplain.model.UnderstandingGraph
import com.master.lessplain.model.UnderstandingRecord
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Container
import java.awt.event.HierarchyEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.lang.reflect.Proxy
import javax.swing.AbstractButton
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JRadioButton
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagramBehaviorPopupTest {
    @Test
    fun `all settings apply immediately while the same popup stays open`() = withFixture { fixture ->
        fixture.controller.toggle()
        val popup = fixture.popups.single()
        val buttons = buttons(popup.content)
        buttons.single { it.text == "方向：横向" }.doClick(0)
        assertEquals(GraphLayoutPreference.HORIZONTAL, fixture.diagram.layoutPreference)
        buttons.single { it.text == "方向：纵向" }.doClick(0)
        assertEquals(GraphLayoutPreference.VERTICAL, fixture.diagram.layoutPreference)
        buttons.single { it.text == "方向：自动" }.doClick(0)
        assertEquals(GraphLayoutPreference.AUTO, fixture.diagram.layoutPreference)
        buttons.single { it.text == "路径流动" }.doClick(0)
        buttons.single { it.text == "自动跳转源码" }.doClick(0)
        buttons.single { it.text == "自动跟随节点" }.doClick(0)
        assertFalse(fixture.diagram.flowEnabled)
        assertTrue(fixture.diagram.navigationPinned)
        assertFalse(fixture.diagram.autoFollowNode)
        fixture.diagram.zoom = 1.8
        buttons.single { it.text == "恢复 100% 缩放" }.doClick(0)
        assertEquals(1.0, fixture.diagram.zoom)
        assertTrue(popup.visible)
        assertEquals(0, popup.cancelCount)
        assertEquals(1, fixture.popups.size)
    }

    @Test
    fun `trigger press cannot auto cancel before its action closes once`() = withFixture { fixture ->
        fixture.controller.toggle()
        val popup = fixture.popups.single()
        fixture.event = press(fixture.anchor)
        popup.platformCancel()
        assertTrue(popup.visible)
        fixture.anchor.doClick(0)
        assertFalse(popup.visible)
        assertEquals(1, popup.cancelCount)
        assertTrue(popup.listeners.isEmpty())
        assertEquals(1, fixture.popups.size, "同一点击不能重新创建弹窗")
        fixture.event = null
        fixture.anchor.doClick(0)
        assertEquals(2, fixture.popups.size)
        assertTrue(fixture.popups.last().visible)
    }

    @Test
    fun `outside click and escape close and reopening reads current settings`() = withFixture { fixture ->
        fixture.controller.toggle()
        val first = fixture.popups.single()
        fixture.event = press(JButton("空白处"))
        first.platformCancel()
        assertFalse(first.visible)
        fixture.diagram.flowEnabled = false
        fixture.diagram.navigationPinned = true
        fixture.diagram.setLayoutPreference(GraphLayoutPreference.HORIZONTAL)
        fixture.controller.toggle()
        val second = fixture.popups.last()
        val buttons = buttons(second.content)
        assertFalse(buttons.single { it.text == "路径流动" }.isSelected)
        assertFalse(buttons.single { it.text == "自动跳转源码" }.isSelected)
        assertTrue(buttons.single { it.text == "方向：横向" }.isSelected)
        fixture.event = KeyEvent(second.content, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ESCAPE, '\u001b')
        second.platformCancel()
        assertFalse(second.visible)
        assertTrue(first.listeners.isEmpty() && second.listeners.isEmpty())
    }

    @Test
    fun `leaving canvas closes and dispose releases listeners and blocks later actions`() = withFixture { fixture ->
        val originalListeners = fixture.anchor.hierarchyListeners.size - 1
        fixture.controller.toggle()
        fixture.anchor.hierarchyListeners.forEach {
            it.hierarchyChanged(HierarchyEvent(fixture.anchor, HierarchyEvent.HIERARCHY_CHANGED,
                fixture.anchor, null, HierarchyEvent.SHOWING_CHANGED.toLong()))
        }
        assertFalse(fixture.popups.single().visible)
        fixture.controller.toggle()
        fixture.controller.dispose()
        fixture.controller.dispose()
        assertFalse(fixture.popups.last().visible)
        assertEquals(originalListeners, fixture.anchor.hierarchyListeners.size)
        assertTrue(fixture.popups.all { it.listeners.isEmpty() })
        fixture.anchor.doClick(0)
        assertEquals(2, fixture.popups.size)
    }

    @Test
    fun `explicit close disposes a popup that temporarily refuses cancellation`() = withFixture { fixture ->
        fixture.controller.toggle()
        val popup = fixture.popups.single()
        popup.cancelBlocked = true
        fixture.anchor.doClick(0)
        assertFalse(popup.visible)
        assertTrue(popup.disposed)
        assertEquals(1, popup.disposeCount)
        assertTrue(popup.listeners.isEmpty())
        fixture.controller.toggle()
        assertTrue(fixture.popups.last().visible)
    }

    @Test
    fun `panel uses accessible focusable controls and radio selection is exclusive`() = withFixture { fixture ->
        fixture.controller.toggle()
        val popup = fixture.popups.single()
        val buttons = buttons(popup.content)
        assertTrue(buttons.all { it.isFocusable && it.accessibleContext.accessibleName == it.text })
        assertEquals("恢复 100% 缩放", (popup.focus as AbstractButton).text)
        val radio = buttons.filterIsInstance<JRadioButton>()
        assertEquals(1, radio.count { it.isSelected })
        // 标准空格动作与鼠标输入共用监听器，不关闭面板。
        val flow = buttons.single { it.text == "路径流动" }
        val event = KeyEvent(flow, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_SPACE, ' ')
        val pressKey = flow.getInputMap(JComponent.WHEN_FOCUSED).get(javax.swing.KeyStroke.getKeyStrokeForEvent(event))
        val releaseKey = flow.getInputMap(JComponent.WHEN_FOCUSED).get(javax.swing.KeyStroke.getKeyStroke("released SPACE"))
        flow.actionMap.get(pressKey).actionPerformed(java.awt.event.ActionEvent(flow, 0, "pressed"))
        flow.actionMap.get(releaseKey).actionPerformed(java.awt.event.ActionEvent(flow, 0, "released"))
        assertFalse(fixture.diagram.flowEnabled)
        assertTrue(popup.visible)
        radio.single { it.text == "方向：横向" }.doClick(0)
        assertEquals(1, radio.count { it.isSelected })
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        SwingUtilities.invokeAndWait {
            val fixture = Fixture()
            try { test(fixture) } finally { fixture.controller.dispose(); fixture.diagram.dispose() }
        }
    }

    private fun press(component: Component) =
        MouseEvent(component, MouseEvent.MOUSE_PRESSED, 0, 0, 2, 2, 1, false, MouseEvent.BUTTON1)

    private fun buttons(component: Component): List<AbstractButton> = when (component) {
        is AbstractButton -> listOf(component)
        is Container -> component.components.flatMap(::buttons)
        else -> emptyList()
    }

    private class Fixture {
        val diagram = UnderstandingDiagram(UnderstandingRecord("popup", 1, null, null, "测试", "", null,
            UnderstandingGraph(emptyList(), emptyList()), 0), null)
        val anchor = JButton("画布行为")
        var event: AWTEvent? = null
        val popups = mutableListOf<TestPopup>()
        val controller = DiagramBehaviorPopup(diagram, anchor, { content, focus, mayCancel ->
            TestPopup(content, focus, mayCancel).also { popups += it }.popup
        }, { event })

        init { anchor.addActionListener { controller.toggle() } }
    }

    /** 只模拟公开弹层契约，不打开原生窗口或 IDE。 */
    private class TestPopup(val content: JComponent, val focus: JComponent, private val mayCancel: () -> Boolean) {
        val listeners = mutableListOf<JBPopupListener>()
        var visible = false
        var disposed = false
        var cancelCount = 0
        var cancelBlocked = false
        var disposeCount = 0
        val popup: JBPopup = Proxy.newProxyInstance(JBPopup::class.java.classLoader, arrayOf(JBPopup::class.java)) {
                proxy, method, arguments ->
            when (method.name) {
                "showUnderneathOf" -> { visible = true; null }
                "isVisible" -> visible
                "isDisposed" -> disposed
                "addListener" -> { listeners += arguments!![0] as JBPopupListener; null }
                "removeListener" -> { listeners -= arguments!![0] as JBPopupListener; null }
                "cancel" -> { platformCancel(); null }
                "dispose" -> { disposeCount++; visible = false; disposed = true; null }
                "getContent" -> content
                "equals" -> proxy === arguments?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "TestPopup"
                else -> error("未模拟的公开弹窗方法：${method.name}")
            }
        } as JBPopup

        fun platformCancel() {
            if (disposed || cancelBlocked || !mayCancel()) return
            cancelCount++
            visible = false
            disposed = true
            listeners.toList().forEach { it.onClosed(LightweightWindowEvent(popup)) }
        }
    }
}
