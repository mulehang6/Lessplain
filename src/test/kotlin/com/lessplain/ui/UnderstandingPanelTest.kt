package com.lessplain.ui

import com.intellij.openapi.util.Disposer
import com.lessplain.ide.uiRead
import com.lessplain.model.GraphNode
import com.lessplain.model.UnderstandingGraph
import com.lessplain.tutorial.TutorialMcpFixture
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.*

class UnderstandingPanelTest {
    @Test fun `native panel keeps the search document through actual asynchronous refreshes`() {
        TutorialMcpFixture("LessplainNativePanelSearch").use { fixture ->
            fixture.graphs.core.present(4, "history", "中文问题", sourceGraph(fixture), "root")
            lateinit var panel: UnderstandingPanel
            fixture.onEdt { panel = UnderstandingPanel(fixture.graphs) }
            try {
                await(fixture) { panel.history.records.list.model.size == 1 }
                fixture.onEdt {
                    val field = panel.history.search.textEditor
                    val document = field.document
                    field.text = "中文"
                    field.caretPosition = 1
                    panel.history.submitQuery()
                    fixture.graphs.notifyChanged()
                    assertSame(document, field.document)
                }
                await(fixture) { panel.history.records.list.model.size == 1 }
                fixture.onEdt {
                    assertEquals("中文", panel.history.search.textEditor.text)
                    assertEquals(1, panel.history.search.textEditor.caretPosition)
                }
            } finally { fixture.onEdt { Disposer.dispose(panel) } }
        }
    }

    @Test fun `selection and service refresh preserve canvas identity and routing remains panel local`() {
        TutorialMcpFixture("LessplainNativePanelCanvas").use { fixture ->
            val graph = sourceGraph(fixture)
            val root = fixture.graphs.core.present(4, "root", "工具窗口", graph, "root")
            val other = fixture.graphs.core.present(4, "other", "其他标签", graph, "root")
            lateinit var panel: UnderstandingPanel
            fixture.onEdt { uiRead { fixture.graphs.showRoot(root); panel = UnderstandingPanel(fixture.graphs) } }
            try {
                await(fixture) { panel.canvas != null }
                lateinit var original: PanelCanvasView
                var diagramDisposed = false
                fixture.onEdt { uiRead {
                    original = assertNotNull(panel.canvas)
                    Disposer.register(original.diagram) { diagramDisposed = true }
                    fixture.graphs.select(other, "n", navigateSource = false)
                    fixture.graphs.notifyChanged()
                } }
                await(fixture) { panel.canvas === original }
                fixture.onEdt {
                    assertEquals(root.id, fixture.graphs.panelRecordId)
                    assertSame(original, panel.canvas)
                    fixture.graphs.showHistory()
                }
                await(fixture) { panel.canvas == null }
                fixture.onEdt { assertTrue(diagramDisposed); uiRead { fixture.graphs.showRoot(root) } }
                await(fixture) { panel.canvas != null }
                fixture.onEdt { assertNotSame(original, panel.canvas) }
            } finally { fixture.onEdt { Disposer.dispose(panel) } }
        }
    }

    @Test fun `disposing a panel rejects pending reads and releases its canvas`() {
        TutorialMcpFixture("LessplainNativePanelClose").use { fixture ->
            val root = fixture.graphs.core.present(4, "root", "关闭", sourceGraph(fixture), "root")
            lateinit var panel: UnderstandingPanel
            fixture.onEdt { uiRead {
                fixture.graphs.showRoot(root)
                panel = UnderstandingPanel(fixture.graphs)
                Disposer.dispose(panel)
                fixture.graphs.notifyChanged()
            } }
            // 队列屏障交付后，已经关闭的面板不能被迟到结果重新填充。
            val done = java.util.concurrent.CountDownLatch(1)
            fixture.graphs.readBackground({ Unit }) { done.countDown() }
            assertTrue(done.await(10, TimeUnit.SECONDS))
            fixture.onEdt { assertEquals(0, panel.componentCount); assertNull(panel.canvas) }
        }
    }

    private fun sourceGraph(fixture: TutorialMcpFixture): UnderstandingGraph {
        val code = "fun run(): Flow<Event> = TODO()"
        fixture.source(code)
        val anchor = com.lessplain.model.SourceAnchor("Flow.txt", null, code, com.lessplain.storage.sha256(code), 1)
        return UnderstandingGraph(listOf(GraphNode("n", "节点", anchor = anchor)), emptyList())
    }

    private fun await(fixture: TutorialMcpFixture, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            var done = false
            fixture.onEdt { done = condition() }
            if (done) return
            Thread.sleep(10)
        }
        fail("工具窗口后台更新未完成")
    }
}
