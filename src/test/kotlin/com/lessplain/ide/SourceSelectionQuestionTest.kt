package com.lessplain.ide

import com.lessplain.core.UnderstandingCore
import com.lessplain.model.*
import com.lessplain.storage.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.test.*

class SourceSelectionQuestionTest {
    @TempDir lateinit var root: Path
    private val graph = UnderstandingGraph(listOf(GraphNode("a", "入口"), GraphNode("b", "目标")),
        listOf(GraphEdge("ab", "a", "b", "call")))
    private val anchor = SourceAnchor("src/A.kt", "run", "    unsaved(中文)", sha256("edited document"), 3,
        sha256("    unsaved(中文)"))

    @Test fun `frozen selection question enters pending batch with its own node anchor and path`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val record = core.present(4, "create", "流程", graph, "root")
            val binding = SourceNodeBinding(record.id, record.revision, "b", "目标", listOf("ab"))
            val selected = captureSourceQuestion(repository, core, binding, anchor)
            selected.draft.text = "为什么这样写？"
            core.captureContext(record.id, "a")
            assertTrue(selected.draft.submit { selected.submit(repository, core, it) })
            val item = repository.pendingNoteBatch(record.id).items.single()
            assertEquals("为什么这样写？", item.question)
            assertEquals("b", item.context.nodeId)
            assertEquals(anchor, item.context.anchor)
            assertEquals(listOf("ab"), item.context.staticPath)
            assertEquals(record.revision, item.context.recordRevision)
            val answer = core.present(4, "answer", item.question, graph, "child", questionId = item.questionId)
            assertEquals(record.id, answer.parentId)
            assertTrue(repository.pendingNoteBatch(record.id).items.isEmpty())
        }
    }

    @Test fun `closing restores question together with its frozen selection and cancelling discards it`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val record = core.present(4, "create", "流程", graph, "root")
            val binding = SourceNodeBinding(record.id, 1, "b", "目标", listOf("ab"))
            val drafts = SourceSelectionDrafts()
            val first = drafts.restoreOrCapture(binding, anchor) {
                captureSourceQuestion(repository, core, binding, anchor)
            }
            first.draft.text = "尚未提交"
            drafts.closed(first)
            val restored = drafts.restoreOrCapture(binding, anchor.copy(symbol = null)) { error("不应重新捕获") }
            assertSame(first, restored)
            assertEquals(anchor, restored.anchor)
            assertEquals("尚未提交", restored.draft.text)
            val other = drafts.restoreOrCapture(binding, anchor.copy(text = "other()")) {
                captureSourceQuestion(repository, core, binding, anchor.copy(text = "other()"))
            }
            assertNotSame(first, other)
            assertEquals("", other.draft.text)
            restored.draft.retain = false
            drafts.closed(restored)
            val fresh = drafts.restoreOrCapture(binding, anchor) {
                captureSourceQuestion(repository, core, binding, anchor)
            }
            assertNotSame(first, fresh)
            assertEquals("", fresh.draft.text)
        }
    }

    @Test fun `closing an empty source question discards its draft but keeps the frozen context`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val record = core.present(4, "empty-create", "流程", graph, "root")
            val binding = SourceNodeBinding(record.id, 1, "b", "目标", listOf("ab"))
            val drafts = SourceSelectionDrafts()
            val empty = drafts.restoreOrCapture(binding, anchor) { captureSourceQuestion(repository, core, binding, anchor) }
            empty.draft.text = " \n "
            drafts.closed(empty)
            assertEquals(empty.context, core.context(empty.context.token))
            val fresh = drafts.restoreOrCapture(binding, anchor) { captureSourceQuestion(repository, core, binding, anchor) }
            assertNotSame(empty, fresh)
            assertEquals("", fresh.draft.text)
        }
    }

    @Test fun `revision conflict and deletion retain the original draft and selection`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val record = core.present(4, "create", "流程", graph, "root")
            val question = captureSourceQuestion(repository, core,
                SourceNodeBinding(record.id, 1, "b", "目标", listOf("ab")), anchor)
            question.draft.text = "保留的问题"
            core.revise(4, "revise", record.question, graph.copy(nodes = graph.nodes.map { it.copy(label = "新标签") }), record.id, 1)
            assertFalse(question.draft.submit { question.submit(repository, core, it) })
            assertTrue(question.draft.error!!.contains("重新选择"))
            assertTrue(question.draft.retain)
            assertEquals("保留的问题", question.draft.text)
            assertEquals(anchor, question.anchor)
            assertTrue(repository.pendingNoteBatch(record.id).items.isEmpty())
            repository.deleteRootRecord(record.id)
            assertFalse(question.draft.submit { question.submit(repository, core, it) })
            assertEquals(anchor, question.anchor)
        }
    }

    @Test fun `disposing an old marker cannot clear a newer editor binding`() {
        SwingUtilities.invokeAndWait {
            val fixture = SourceMarkerTestEditor()
            val old = SourceNodeBinding("root", 1, "a", "旧节点", emptyList())
            val new = old.copy(nodeId = "b", label = "新节点")
            val marker = EditorSourceInsightMarker(fixture.editor, 0, fixture.text.length, "旧节点",
                SourceInlayNavigation(false, false, {}, {}), old)
            fixture.editor.putUserData(SOURCE_NODE_BINDING, new)
            marker.dispose()
            assertSame(new, fixture.editor.getUserData(SOURCE_NODE_BINDING))
            fixture.editor.putUserData(SOURCE_NODE_BINDING, null)
        }
    }

    @Test fun `editor binding and unsaved selection are independent between split editors`() {
        SwingUtilities.invokeAndWait {
            val first = SourceMarkerTestEditor()
            val second = SourceMarkerTestEditor()
            val binding = SourceNodeBinding("root", 1, "a", "目标", emptyList())
            val marker = EditorSourceInsightMarker(first.editor, 0, first.text.length, "目标",
                SourceInlayNavigation(false, false, {}, {}), binding)
            try {
                assertNull(selectedSourceBinding(first.editor), "没有选区应隐藏")
                first.selectionStart = first.text.indexOf("foo")
                first.selectionEnd = first.selectionStart + 5
                assertEquals(binding, selectedSourceBinding(first.editor))
                second.selectionStart = 0; second.selectionEnd = 4
                assertNull(selectedSourceBinding(second.editor), "另一编辑器没有节点绑定")
                first.text = first.text.replace("foo", "baz")
                val snapshot = captureAnchorSnapshot(first.editor, "src/A.kt", "run")!!
                assertEquals("baz()", snapshot.text)
                assertEquals(sha256(first.text), snapshot.version)
                assertEquals(1, snapshot.lineHint)
                assertEquals(sha256("baz()"), snapshot.fingerprint)
                first.selectionStart = 0; first.selectionEnd = 4
                assertNull(selectedSourceBinding(first.editor), "空白选区应隐藏")
                assertEquals("baz()", snapshot.text, "后续选区变化不能改写快照")
            } finally { marker.dispose() }
            assertNull(first.editor.getUserData(SOURCE_NODE_BINDING))
        }
    }
}
