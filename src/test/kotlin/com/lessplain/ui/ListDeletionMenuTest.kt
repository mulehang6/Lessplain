package com.lessplain.ui

import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Point
import javax.swing.JList
import javax.swing.SwingUtilities
import kotlin.test.*

class ListDeletionMenuTest {
    @Test fun `menu freezes clicked row never targets blank area and exposes readonly state`() = SwingUtilities.invokeAndWait {
        val list = JList(arrayOf("教程一", "教程二")).apply { fixedCellHeight = 30; setSize(300, 180) }
        var menu = emptyList<NativeMenuItem>()
        var deleted = ""
        var canDelete = true
        installListDeletionMenu(list, "删除", { canDelete }, present = { _: Component, values: List<NativeMenuItem>, _: Point -> menu = values }) { deleted = it }
        list.componentPopupMenu.show(list, 10, 40)
        assertEquals(1, list.selectedIndex)
        val target = menu.single()
        list.selectedIndex = 0
        target.perform()
        assertEquals("教程二", deleted, "选择改变不能更改菜单已冻结的目标")
        menu = emptyList()
        list.componentPopupMenu.show(list, 10, 140)
        assertTrue(menu.isEmpty())
        canDelete = false
        list.componentPopupMenu.show(list, 10, 10)
        assertFalse(menu.single().enabled)
    }

    @Test fun `keyboard popup targets selected item regardless of popup coordinates`() {
        val list = JList(arrayOf("第一项", "第二项"))
        var menu = emptyList<NativeMenuItem>()
        var deleted = ""
        SwingUtilities.invokeAndWait {
            list.fixedCellHeight = 30; list.setSize(300, 180); list.selectedIndex = 1
            installListDeletionMenu(list, "删除", { true }, present = { _, values, _ -> menu = values }) { deleted = it }
        }
        val delivered = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val event = object : java.awt.event.KeyEvent(list, java.awt.event.KeyEvent.KEY_PRESSED, 0,
            java.awt.event.InputEvent.SHIFT_DOWN_MASK, java.awt.event.KeyEvent.VK_F10, java.awt.event.KeyEvent.CHAR_UNDEFINED), java.awt.ActiveEvent {
            override fun dispatch() {
                try {
                    list.componentPopupMenu.show(list, 10, 140)
                    assertEquals(1, list.selectedIndex)
                    menu.single().perform()
                    assertEquals("第二项", deleted)
                } catch (problem: Throwable) { failure.set(problem) } finally { delivered.countDown() }
            }
        }
        java.awt.Toolkit.getDefaultToolkit().systemEventQueue.postEvent(event)
        assertTrue(delivered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        failure.get()?.let { throw it }
    }

    @Test fun `paging is centered compact and actions honor enabled boundaries`() = SwingUtilities.invokeAndWait {
        val panel = HistoryPagingPanel()
        var page = 0
        panel.update(0, true, { page-- }, { page++ })
        panel.setSize(500, 32); panel.doLayout()
        assertNotNull(panel.previousButton.icon); assertNotNull(panel.nextButton.icon)
        assertFalse(panel.previousButton.isEnabled)
        assertTrue(panel.previousButton.x > 100)
        panel.previousButton.doClick(); assertEquals(0, page)
        panel.nextButton.doClick(); assertEquals(1, page)
        panel.update(1, false, { page-- }, { page++ })
        panel.nextButton.doClick(); assertEquals(1, page)
    }
}
