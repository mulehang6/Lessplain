package com.lessplain.tutorial

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.lessplain.ide.uiRead
import org.junit.jupiter.api.Test
import java.awt.Container
import java.awt.event.HierarchyEvent
import javax.swing.text.StyleConstants
import kotlin.test.*

class TutorialResourceLifecycleTest {
    @Test fun `repeated resources have independent components and refresh every occurrence`() {
        TutorialMcpFixture("LessplainRepeatedResources").use { fixture ->
            fixture.onEdt { uiRead {
                val service = fixture.tutorials
                service.store.saveContext(TutorialContext("ctx_resources", "资源", emptyList()))
                val simulation = TutorialSimulation("s", "kotlin", "println(1)\nprintln(2)", listOf(
                    SimulationScenario("a", "输出", listOf(SimulationStep(listOf(1), "第一步", "1\n"), SimulationStep(listOf(2), "第二步", "2\n"))),
                ))
                val diagram = TutorialDiagram("d", TutorialGraph(listOf(TutorialNode("n", "开始")), emptyList()))
                val markdown = "![图](lessplain://diagram/d)\n\n![模拟](lessplain://simulation/s)\n\n![图](lessplain://diagram/d)\n\n![模拟](lessplain://simulation/s)"
                val record = service.create("ctx_resources", "重复资源", markdown, TutorialResourceState(listOf(diagram), listOf(simulation)))
                val view = TutorialEditorView(fixture.project, record.id)
                try {
                    fun resources(kind: String): List<java.awt.Component> = view.preview.model.parts.filter { it.block.kind == kind }.map {
                        (StyleConstants.getComponent(view.preview.styledDocument.getCharacterElement(it.from).attributes) as Container).getComponent(0)
                    }
                    val diagrams = resources("diagram")
                    val simulations = resources("simulation").map { it as TutorialSimulationView }
                    assertEquals(2, diagrams.size); assertNotSame(diagrams[0], diagrams[1])
                    assertEquals(2, simulations.size); assertNotSame(simulations[0], simulations[1])
                    val first = simulations[0]
                    fun invoke(index: Int) {
                        val action = first.actions[index]
                        action.actionPerformed(AnActionEvent.createEvent(action, DataContext.EMPTY_CONTEXT, null, "LessplainTest", com.intellij.openapi.actionSystem.ActionUiKind.NONE, null))
                    }
                    invoke(1); invoke(1)
                    assertEquals(1, first.playback.stepIndex)
                    assertEquals(-1, simulations[1].playback.stepIndex)
                    assertEquals(1, first.code.highlighter.highlights.size)
                    first.animation.start()
                    invoke(2)
                    assertEquals(0f, first.animation.progress); assertFalse(first.animation.isRunning)
                    invoke(1); first.animation.start()
                    val hidden = HierarchyEvent(first, HierarchyEvent.HIERARCHY_CHANGED, first, first.parent, HierarchyEvent.SHOWING_CHANGED.toLong())
                    first.hierarchyListeners.forEach { it.hierarchyChanged(hidden) }
                    assertFalse(first.animation.isRunning)
                    assertEquals(1f, first.animation.progress)
                    service.store.saveSimulation(record.id, simulation.copy(code = "println(3)", scenarios = listOf(SimulationScenario("b", "更新", listOf(SimulationStep(listOf(1), "第三步", "3\n"))))), 1)
                    view.preview.refresh()
                    simulations.forEach { assertEquals("println(3)", it.code.text); assertEquals(-1, it.playback.stepIndex) }
                    first.animation.start()
                    view.close()
                    assertFalse(first.animation.isRunning)
                } finally { view.close() }
            } }
        }
    }

    @Test fun `resources saved after markdown references appear without replacing existing playback`() {
        TutorialMcpFixture("LessplainLateResources").use { fixture ->
            fixture.onEdt { uiRead {
                val service = fixture.tutorials
                service.store.saveContext(TutorialContext("ctx_late_resources", "资源", emptyList()))
                val simulation = TutorialSimulation("existing", "kotlin", "println(1)", listOf(
                    SimulationScenario("a", "输出", listOf(SimulationStep(listOf(1), "第一步", "1\n"))),
                ))
                val record = service.create("ctx_late_resources", "稍后保存资源", "![已有模拟](lessplain://simulation/existing)",
                    TutorialResourceState(simulations = listOf(simulation)))
                val view = TutorialEditorView(fixture.project, record.id)
                try {
                    fun component(id: String): java.awt.Component? {
                        val part = view.preview.model.parts.single { it.block.resourceId == id }
                        return (StyleConstants.getComponent(view.preview.styledDocument.getCharacterElement(part.from).attributes) as? Container)?.getComponent(0)
                    }
                    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(fixture.project) {
                        view.editor.document.insertString(view.editor.document.textLength,
                            "\n\n![稍后插图](lessplain://diagram/later)\n\n![稍后模拟](lessplain://simulation/later)")
                    }
                    view.refresh()
                    val existing = component("existing") as TutorialSimulationView
                    existing.actions[1].let { action ->
                        action.actionPerformed(AnActionEvent.createEvent(action, DataContext.EMPTY_CONTEXT, null, "LessplainTest", com.intellij.openapi.actionSystem.ActionUiKind.NONE, null))
                    }
                    assertEquals(0, existing.playback.stepIndex)
                    val missing = view.preview.model.parts.filter { it.block.resourceId == "later" }
                    missing.forEach { assertNull(StyleConstants.getComponent(view.preview.styledDocument.getCharacterElement(it.from).attributes)) }
                    service.store.saveDiagram(record.id, TutorialDiagram("later", TutorialGraph(listOf(TutorialNode("n", "开始")), emptyList())), 0)
                    service.store.saveSimulation(record.id, simulation.copy(id = "later"), 0)
                    view.refresh()
                    missing.forEach { part ->
                        val slot = StyleConstants.getComponent(view.preview.styledDocument.getCharacterElement(part.from).attributes) as? Container
                        assertNotNull(slot, "资源落盘后应创建 ${part.block.kind} 组件")
                        assertEquals(1, slot.componentCount)
                    }
                    assertSame(existing, component("existing"))
                    assertEquals(0, existing.playback.stepIndex, "新增其他资源不能重置已播放的模拟")
                } finally { view.close() }
            } }
        }
    }

    @Test fun `submitted questions remain answered on resubmission and notes cannot enter pending queue`() {
        TutorialMcpFixture("LessplainQuestionSubmission").use { fixture ->
            val service = fixture.tutorials
            service.store.saveContext(TutorialContext("ctx_submission", "来源", emptyList()))
            val record = service.create("ctx_submission", "问题状态", "原文", TutorialResourceState())
            val question = service.annotationDraft(record.id, 0, 2, "question").copy(text = "为什么", status = "pending", contextId = "ctx_submission")
            service.store.createAnnotation(question)
            val answered = service.answer(question.id, "因为如此", null)
            assertEquals(answered, service.submitQuestion(question.id))
            val note = service.annotationDraft(record.id, 0, 2, "note").copy(text = "个人笔记")
            service.store.createAnnotation(note)
            assertEquals("INVALID_KIND", assertFailsWith<TutorialProblem> { service.submitQuestion(note.id) }.code)
            assertEquals("个人笔记", service.store.annotation(note.id).text)
        }
    }
}
