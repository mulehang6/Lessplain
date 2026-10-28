package com.lessplain.tutorial

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.lessplain.ide.uiRead
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.*

class TutorialComponentTest {
    @Test fun `simulation toolbar is compact and layout changes retain prefix playback`() {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("LessplainSimulationComponents", true).fixture
        fixture.setUp()
        try {
            ApplicationManager.getApplication().invokeAndWait { uiRead {
                val editor = EditorFactory.getInstance().createEditor(EditorFactory.getInstance().createDocument(""), fixture.project)
                try {
                    val simulation = TutorialSimulation("play", "kotlin", "println(1)\n".repeat(160), listOf(
                        SimulationScenario("a", "开始收集：collect=true", listOf(SimulationStep(listOf(1), "打印", "1\n"), SimulationStep(listOf(2), "继续", "2\n"))),
                        SimulationScenario("b", "无输出", listOf(SimulationStep(listOf(1), "不打印"))),
                    ))
                    val player = SimulationPlayback(simulation)
                    val view = TutorialSimulationView(editor, player)
                    view.setSize(1000, view.preferredSize.height)
                    layout(view)
                    fun invoke(index: Int) {
                        val action = view.actions[index]
                        action.actionPerformed(AnActionEvent.createEvent(action, DataContext.EMPTY_CONTEXT, null, "LessplainTest", com.intellij.openapi.actionSystem.ActionUiKind.NONE, null))
                    }
                    fun enabled(index: Int): Boolean {
                        val action = view.actions[index]
                        val event = AnActionEvent.createEvent(action, DataContext.EMPTY_CONTEXT, null, "LessplainTest", com.intellij.openapi.actionSystem.ActionUiKind.NONE, null)
                        action.update(event)
                        return event.presentation.isEnabled
                    }
                    assertEquals(5, view.actions.size)
                    assertTrue(view.actions.all { it.templatePresentation.icon != null })
                    assertTrue(view.toolbar.component.preferredSize.width < view.width / 3, "操作集中在模拟标题旁，不分散铺满整页")
                    assertFalse(enabled(0))
                    assertFalse(enabled(2))
                    invoke(1); assertEquals("1\n", player.output)
                    invoke(1); assertEquals("1\n2\n", player.output)
                    assertFalse(enabled(1))
                    invoke(0); assertEquals("1\n", player.output)
                    val beforeLayout = view.preferredSize.height
                    invoke(4)
                    assertTrue(view.stacked)
                    assertEquals("1\n", player.output)
                    assertEquals(0, player.stepIndex)
                    assertTrue(view.preferredSize.height > beforeLayout)
                    invoke(4); assertFalse(view.stacked)
                    invoke(2); assertEquals("", player.output)
                    view.split.proportion = 0.35f
                    assertEquals(0.35f, view.split.proportion)
                    assertTrue(view.preferredSize.height > editor.lineHeight * 100)
                    view.refresh(simulation.copy(scenarios = listOf(simulation.scenarios[1]), revision = 2))
                    assertNull(view.split.secondComponent)
                    assertFalse(enabled(4), "纯流程情境不提供无效的终端布局切换")
                    view.refresh(simulation.copy(code = "println(\"" + "中文".repeat(180) + "\")", scenarios = listOf(simulation.scenarios[0].copy(steps = listOf(simulation.scenarios[0].steps[0]))), revision = 3))
                    view.split.proportion = 0.7f
                    val wideHeight = view.preferredSize.height
                    view.split.proportion = 0.25f
                    assertTrue(view.preferredSize.height > wideHeight, "拖窄代码区后必须按实际换行高度参与页面滚动")
                    val image = BufferedImage(1000, 500, BufferedImage.TYPE_INT_ARGB)
                    image.createGraphics().let { g -> try { view.paint(g) } finally { g.dispose() } }
                } finally { EditorFactory.getInstance().releaseEditor(editor) }
            } }
        } finally { ApplicationManager.getApplication().invokeAndWait { fixture.tearDown() } }
    }

    @Test fun `library blank area never selects a material and rows fit a narrow viewport`() {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("LessplainLibraryGeometry", true).fixture
        fixture.setUp()
        try {
            ApplicationManager.getApplication().invokeAndWait { uiRead {
                val service = fixture.project.getService(TutorialService::class.java)
                service.store.saveContext(TutorialContext("ctx_library", "检索", emptyList()))
                val record = service.create("ctx_library", "非常长的教程标题".repeat(25), "可以搜索的正文", TutorialResourceState())
                val library = TutorialLibrary(fixture.project)
                try {
                    library.setSize(320, 500); layout(library)
                    library.list.setSize(300, 350)
                    val cell = assertNotNull(library.list.getCellBounds(0, 0))
                    assertEquals(0, library.cellAt(Point(5, cell.y + cell.height / 2)))
                    assertNull(library.cellAt(Point(5, cell.y + cell.height + 40)))
                    assertEquals(-1, library.list.locationToIndex(Point(5, cell.y + cell.height + 40)), "平台 hover 也不能把空白区域夹到最后一行")
                    assertTrue(library.list.scrollableTracksViewportWidth)
                    val renderer = library.list.cellRenderer.getListCellRendererComponent(library.list, record, 0, false, false)
                    assertTrue(renderer.preferredSize.width <= 300)
                    val tooltip = library.list.getToolTipText(java.awt.event.MouseEvent(library.list, java.awt.event.MouseEvent.MOUSE_MOVED, 0, 0, 5, 5, 0, false))
                    assertTrue(tooltip.orEmpty().contains(record.title))
                    library.search.text = "不匹配"
                    assertEquals(0, library.list.model.size)
                } finally { library.dispose() }
            } }
        } finally { ApplicationManager.getApplication().invokeAndWait { fixture.tearDown() } }
    }

    @Test fun `ink shape is deterministic and categories have different colors`() {
        fun paint(kind: String): List<Int> {
            val image = BufferedImage(220, 50, BufferedImage.TYPE_INT_ARGB)
            image.createGraphics().let { g -> try { paintInk(g, Rectangle(10, 10, 180, 22), kind, 8127) } finally { g.dispose() } }
            return image.getRGB(0, 0, 220, 50, null, 0, 220).toList()
        }
        assertEquals(paint("note"), paint("note"))
        assertNotEquals(paint("note"), paint("question"))
        assertNotEquals(paint("both"), paint("question"))
        assertNotEquals(paint("both"), paint("note"))
    }

    private fun layout(component: Component) {
        if (component is Container) { component.doLayout(); component.components.forEach(::layout) }
    }
}
