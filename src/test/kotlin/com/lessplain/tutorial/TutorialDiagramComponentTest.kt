package com.lessplain.tutorial

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.lessplain.diagram.DiagramMouseInteraction
import com.lessplain.diagram.actionAvailability
import com.lessplain.ide.GraphNodePosition
import com.lessplain.ide.uiRead
import org.junit.jupiter.api.Test
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JPanel
import javax.swing.text.StyleConstants
import kotlin.test.*

class TutorialDiagramComponentTest {
    @Test fun `embedded diagram is the main canvas with shared gestures persistent positions and viewport`() {
        Class.forName("org.eclipse.emf.common.util.CommonUtil")
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("LessplainEmbeddedCanvas", true).fixture
        fixture.setUp()
        lateinit var view: TutorialEditorView
        var created = false
        lateinit var diagram: TutorialDiagramView
        try {
            onEdt { uiRead {
                val service = fixture.project.getService(TutorialService::class.java)
                service.store.saveContext(TutorialContext("ctx_canvas", "图", emptyList()))
                val graph = TutorialGraph(listOf(TutorialNode("one", "入口"), TutorialNode("two", "结束")), listOf(TutorialEdge("edge", "one", "two")))
                val record = service.create("ctx_canvas", "图组件", "开始\n\n![图](lessplain://diagram/main)\n\n结束", TutorialResourceState(diagrams = listOf(TutorialDiagram("main", graph))))
                view = TutorialEditorView(fixture.project, record.id)
                created = true
                view.preview.setSize(900, 1000)
                val part = view.preview.model.parts.single { it.block.kind == "diagram" }
                val slot = view.preview.styledDocument.getCharacterElement(part.from).attributes.getAttribute(StyleConstants.ComponentAttribute) as JPanel
                diagram = slot.components.single() as TutorialDiagramView
                assertEquals(360, diagram.preferredSize.height)
                slot.setSize(850, slot.preferredSize.height); slot.doLayout(); diagram.doLayout()
                assertTrue(diagram.x >= 48)
                assertTrue(slot.width - diagram.x - diagram.width >= 48)
                assertFalse(diagram.contains(0, 0), "圆角以外不应命中画布")
                assertTrue(diagram.contains(diagram.width / 2, diagram.height / 2))
                assertTrue(diagram.canvas.mouseWheelListeners.any { it is DiagramMouseInteraction })
                assertFalse(diagram.canvas.actionAvailability().copy)
                assertFalse(diagram.canvas.actionAvailability().addNote)
                diagram.canvas.setSize(800, 360)
                diagram.canvas.dispatchEvent(ComponentEvent(diagram.canvas, ComponentEvent.COMPONENT_RESIZED))
                diagram.canvas.requestLayout()
            } }
            val deadline = System.nanoTime() + 5_000_000_000L
            var ready = false
            while (!ready && System.nanoTime() < deadline) {
                onEdt { ready = diagram.canvas.layoutReady }
                if (!ready) Thread.sleep(10)
            }
            assertTrue(ready, "共享画布必须完成真实布局")
            onEdt { uiRead {
                val canvas = diagram.canvas
                val interaction = canvas.mouseWheelListeners.filterIsInstance<DiagramMouseInteraction>().single()
                val oldCenter = canvas.centerY
                val pageScroll = view.previewScroll.verticalScrollBar.value
                val ordinary = MouseWheelEvent(canvas, MouseWheelEvent.MOUSE_WHEEL, 0, 0, 120, 90, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1)
                canvas.dispatchEvent(ordinary)
                assertTrue(ordinary.isConsumed)
                assertNotEquals(oldCenter, canvas.centerY, "图内普通滚轮继续平移共享画布")
                assertEquals(pageScroll, view.previewScroll.verticalScrollBar.value)
                val oldZoom = canvas.zoom
                val wheel = MouseWheelEvent(canvas, MouseWheelEvent.MOUSE_WHEEL, 0, InputEvent.CTRL_DOWN_MASK, 120, 90, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -1)
                interaction.mouseWheelMoved(wheel)
                assertTrue(wheel.isConsumed)
                assertTrue(canvas.zoom > oldZoom)
                canvas.setNodePositions(mapOf("one" to GraphNodePosition(123.0, 234.0)))
                canvas.commitNodePositions()
                val service = view.service
                assertEquals(TutorialPosition(123, 234), service.store.resource(view.id).positions.getValue("main")["one"])
                diagram.saveViewport()
                assertEquals(canvas.zoom, service.store.reading(view.id).diagrams.getValue("main").zoom)
                val old = service.store.resource(view.id).diagrams.single()
                service.store.saveDiagram(view.id, old.copy(graph = old.graph.copy(nodes = old.graph.nodes.map { it.copy(label = "新${it.label}") })), old.revision)
                view.refresh()
                assertNotSame(canvas, diagram.canvas)
                assertEquals(service.store.reading(view.id).diagrams.getValue("main").zoom, diagram.canvas.initialState?.zoom)
            } }
        } finally { onEdt { uiRead { if (created) view.close() }; fixture.tearDown() } }
    }
    private fun onEdt(action: () -> Unit) = ApplicationManager.getApplication().invokeAndWait(action)
}
