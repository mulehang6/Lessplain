package com.lessplain.ui

import com.lessplain.ide.PendingResult
import com.lessplain.ide.PanelPage
import com.lessplain.ide.uiRead
import com.lessplain.model.GraphNode
import com.lessplain.model.UnderstandingGraph
import com.lessplain.tutorial.TutorialMcpFixture
import org.junit.jupiter.api.Test
import java.awt.event.InputMethodEvent
import java.awt.event.ActionEvent
import java.text.AttributedString
import javax.swing.JScrollPane
import kotlin.test.*

class HistoryPanelTest {
    @Test fun `loading error and results preserve search editor document and caret through Chinese composition`() {
        TutorialMcpFixture("LessplainHistoryInput").use { fixture ->
            fixture.onEdt { uiRead {
                val queries = mutableListOf<String>()
                val panel = HistoryPanel(fixture.graphs, queryChanged = queries::add)
                try {
                    val field = panel.search.textEditor
                    val document = field.document
                    panel.search.text = "zhong"
                    field.caretPosition = 5
                    fun composition(value: String, committed: Int) {
                        val event = InputMethodEvent(field, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                            AttributedString(value).iterator, committed, null, null)
                        field.inputMethodListeners.forEach { it.inputMethodTextChanged(event) }
                    }
                    composition("zhong", 0)
                    panel.submitQuery()
                    assertTrue(queries.isEmpty(), "拼音尚未提交时不能发起搜索")
                    panel.update(null, 0, {}, {})
                    panel.update(Result.failure(IllegalStateException("读取失败")), 0, {}, {})
                    assertSame(field, panel.search.textEditor); assertSame(document, field.document)
                    assertEquals("zhong", field.text); assertEquals(5, field.caretPosition)
                    panel.search.text = "中文"
                    composition("中文", 2)
                    panel.submitQuery()
                    assertEquals(listOf("中文"), queries)
                    field.caretPosition = 1
                    panel.update(Result.success(UnderstandingPanelData(null, null, emptyList(), null, emptyList(), null)), 0, {}, {})
                    assertEquals(1, field.caretPosition); assertSame(document, field.document)
                    field.document.insertString(1, "a", null)
                    panel.submitQuery()
                    assertEquals(listOf("中文", "中a文"), queries)
                    val scroll = panel.records.components.single() as JScrollPane
                    assertEquals(0, scroll.border.getBorderInsets(scroll).let { it.top + it.left + it.right + it.bottom })
                } finally { panel.close() }
            } }
        }
    }

    @Test fun `history row opens its unread answer and clearing unread updates the existing row`() {
        TutorialMcpFixture("LessplainHistoryUnread").use { fixture ->
            val code = "fun run(): Flow<Event> = TODO()"
            fixture.source(code)
            val anchor = com.lessplain.model.SourceAnchor("Flow.txt", null, code, com.lessplain.storage.sha256(code), 1)
            val graph = UnderstandingGraph(listOf(GraphNode("n", "节点", anchor = anchor)), emptyList())
            val root = fixture.graphs.core.present(4, "root", "根问题", graph, "root")
            val question = fixture.graphs.core.prepareQuestion("追问", fixture.graphs.core.captureContext(root.id, "n").token).question
            val child = fixture.graphs.core.present(4, "child", question.text, graph, "child", questionId = question.id)
            val pending = PendingResult("question:${question.id}", child.id, question.id, 10)
            val request = UnderstandingPanelRequest(PanelPage.HISTORY, null, null, emptyList(), "")
            val data = readUnderstandingPanelData(fixture.graphs.repository, request, listOf(pending))
            assertEquals(pending, data.historyUnread[root.id])
            lateinit var panel: HistoryPanel
            fixture.onEdt { uiRead {
                fixture.graphs.storage.remember(root)
                fixture.graphs.resultInbox.pending().forEach { fixture.graphs.resultInbox.markViewed(it.key) }
                fixture.graphs.resultInbox.register(pending)
                panel = HistoryPanel(fixture.graphs) {}
                panel.update(Result.success(data), 0, {}, {})
                assertEquals(pending, panel.records.list.selectedValue.pending)
                panel.records.list.actionMap.get("open").actionPerformed(ActionEvent(panel.records.list, 0, "open"))
            } }
            try {
                val deadline = System.nanoTime() + 5_000_000_000L
                var viewed = false
                while (!viewed && System.nanoTime() < deadline) {
                    fixture.onEdt { viewed = fixture.graphs.panelFocusQuestionId == question.id && fixture.graphs.resultInbox.pending().isEmpty() }
                    if (!viewed) Thread.sleep(10)
                }
                assertTrue(viewed, "从历史条目打开时应定位具体追问并清除它的未读")
                val refreshed = readUnderstandingPanelData(fixture.graphs.repository, request, fixture.graphs.resultInbox.pending())
                fixture.onEdt { panel.update(Result.success(refreshed), 0, {}, {}); assertNull(panel.records.list.selectedValue.pending) }
            } finally { fixture.onEdt { panel.close() } }
        }
    }

    @Test fun `history menu only has recent and history and unread uses a dot without counts`() {
        var opened = ""
        val menu = historyMenu(true, { opened = "recent" }, { opened = "history" })
        assertEquals(listOf("最近", "历史"), menu.map { it.label })
        assertNull(menu.first().icon); assertSame(UnreadDotIcon, menu.last().icon)
        assertNull(historyMenu(false, {}, {}).last().icon)
        menu.first().perform(); assertEquals("recent", opened)
        menu.last().perform(); assertEquals("history", opened)
    }
}
