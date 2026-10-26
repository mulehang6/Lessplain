package com.lessplain.tutorial

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.lessplain.ide.uiRead
import com.intellij.ui.popup.list.SelectablePanel
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.event.ActionEvent
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JPanel
import kotlin.test.*

class TutorialPopupInteractionTest {
    @Test fun `catalog hover leaves and reenters independently of selection then keyboard takes over`() = fixture { service ->
        repeat(2) { service.create("ctx_popup", "教程 $it", "正文 $it", TutorialResourceState()) }
        val library = TutorialLibrary(service.project)
        try {
            library.list.setSize(300, 450)
            library.list.selectedIndex = 1
            fun move(index: Int) {
                val bounds = library.list.getCellBounds(index, index)
                library.list.dispatchEvent(MouseEvent(library.list, MouseEvent.MOUSE_MOVED, 0, 0, 10, bounds.y + 10, 0, false))
            }
            fun leave() = library.list.dispatchEvent(MouseEvent(library.list, MouseEvent.MOUSE_EXITED, 0, 0, 310, 450, 0, false))
            move(0)
            assertEquals(0, library.activeIndex)
            assertEquals(1, library.list.selectedIndex, "悬停不能修改实际选择")
            val selected = library.list.cellRenderer.getListCellRendererComponent(library.list, library.list.model.getElementAt(0), 0, false, false) as SelectablePanel
            assertNotNull(selected.selectionColor)
            assertTrue(selected.selectionArc > 0)
            leave(); assertEquals(-1, library.activeIndex)
            move(0); assertEquals(0, library.activeIndex)
            library.list.dispatchEvent(MouseEvent(library.list, MouseEvent.MOUSE_MOVED, 0, 0, 10, 400, 0, false))
            assertEquals(-1, library.activeIndex)
            assertEquals(1, library.list.selectedIndex)
            library.search.textEditor.actionMap["catalog.UP"].actionPerformed(ActionEvent(library, 0, "up"))
            assertEquals(0, library.activeIndex)
            val inactive = library.list.cellRenderer.getListCellRendererComponent(library.list, library.list.model.getElementAt(1), 1, true, false) as SelectablePanel
            assertNull(inactive.selectionColor)
            move(1); assertEquals(1, library.activeIndex)
            val second = library.list.getCellBounds(1, 1)
            val secondTip = library.list.getToolTipText(MouseEvent(library.list, MouseEvent.MOUSE_MOVED, 0, 0, 10, second.y + 10, 0, false))
            move(0)
            val firstTip = library.list.getToolTipText(MouseEvent(library.list, MouseEvent.MOUSE_MOVED, 0, 0, 10, 10, 0, false))
            assertNotEquals(secondTip, firstTip, "跨行后提示必须来自当前鼠标行")
            assertFalse(library.list.expandableItemsHandler.isEnabled, "不混用与选区绑定的扩展行提示")
        } finally { library.dispose() }
    }

    @Test fun `catalog single click and enter open while blank area never opens`() = fixture { service ->
        val record = service.create("ctx_popup", "打开教程", "正文", TutorialResourceState())
        val library = TutorialLibrary(service.project)
        var opened = 0
        library.opened = { opened++ }
        try {
            library.list.setSize(300, 400)
            library.list.selectedIndex = 0
            library.list.dispatchEvent(MouseEvent(library.list, MouseEvent.MOUSE_CLICKED, 0, 0, 10, 10, 1, false, MouseEvent.BUTTON1))
            assertEquals(1, opened)
            library.list.dispatchEvent(MouseEvent(library.list, MouseEvent.MOUSE_CLICKED, 0, 0, 10, 300, 1, false, MouseEvent.BUTTON1))
            assertEquals(1, opened, "单击空白不能打开选中条目")
            library.list.actionMap["open"].actionPerformed(ActionEvent(library, 0, "enter"))
            assertEquals(2, opened)
            val manager = FileEditorManager.getInstance(service.project)
            assertTrue(manager.openFiles.filterIsInstance<TutorialVirtualFile>().any { it.tutorialId == record.id })
            manager.openFiles.forEach(manager::closeFile)
        } finally { library.dispose() }
    }

    @Test fun `visible float handles target changes icons menu dismissal and selection lifetime without windows`() = fixture { service ->
        val record = service.create("ctx_popup", "浮条", "中文 Flow", TutorialResourceState())
        service.store.createAnnotation(service.annotationDraft(record.id, 0, 2, "note").copy(text = "笔记"))
        service.store.createAnnotation(service.annotationDraft(record.id, 3, 7, "question").copy(text = "问题"))
        val owner = object : JPanel() { override fun isShowing() = true }
        val windows = TutorialAnnotationWindows(service.project, record.id, service, owner)
        val shown = mutableListOf<JBPopup>()
        val actions = TutorialSelectionActions(service, record.id, windows) { popup, _, _ -> shown += popup }
        try {
            actions.hover(owner, 0, Point())
            assertEquals(1, shown.size)
            val buttons = children(shown.last().content).filterIsInstance<JButton>()
            assertEquals(2, buttons.size)
            assertTrue(buttons.all { it.icon != null && !it.toolTipText.isNullOrBlank() && !it.accessibleContext.accessibleName.isNullOrBlank() })
            assertEquals(setOf("1", ""), buttons.map { it.text.orEmpty() }.toSet())
            assertTrue(buttons.all { it.preferredSize.width <= com.intellij.util.ui.JBUI.scale(44) })
            actions.hover(owner, 1, Point(20, 0))
            assertEquals(1, shown.size, "同一入口移动不应反复重建")
            actions.hover(owner, 4, Point(60, 0))
            assertEquals(2, shown.size)
            assertTrue(shown.first().isDisposed)
            actions.suspendForMenu()
            assertTrue(shown.last().isDisposed)
            actions.beginSelection()
            actions.changed(owner, 0 until 7, Point())
            actions.selectionSettled()
            assertEquals(2, shown.size, "拖选中不弹浮条")
            actions.endSelection(); actions.selectionSettled()
            assertEquals(3, shown.size)
            actions.hover(owner, 1, Point())
            assertEquals(3, shown.size, "主动选区浮条不被悬停替换")
            actions.changed(owner, null, Point())
            assertTrue(shown.last().isDisposed)
        } finally { actions.close(); windows.close() }
    }

    private fun children(component: Component): List<Component> = listOf(component) +
        if (component is Container) component.components.flatMap(::children) else emptyList()

    private fun fixture(test: (TutorialService) -> Unit) {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("LessplainPopupEvents", true).fixture
        fixture.setUp()
        try {
            ApplicationManager.getApplication().invokeAndWait { uiRead {
                val service = fixture.project.getService(TutorialService::class.java)
                service.store.saveContext(TutorialContext("ctx_popup", "测试", emptyList()))
                test(service)
            } }
        } finally { ApplicationManager.getApplication().invokeAndWait { fixture.tearDown() } }
    }
}
