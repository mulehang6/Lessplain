package com.lessplain

import com.lessplain.core.UnderstandingCore
import com.lessplain.core.formatPendingQuestionCopy
import com.lessplain.core.preparePendingQuestionCopy
import com.lessplain.core.replaceQuestionClipboard
import com.lessplain.model.GraphNode
import com.lessplain.model.SourceAnchor
import com.lessplain.model.UnderstandingGraph
import com.lessplain.model.UnderstandingInputException
import com.lessplain.storage.PendingNoteItem
import com.lessplain.storage.UnderstandingRepository
import com.lessplain.storage.cancelPendingQuestion
import com.lessplain.storage.consumeReadFeedback
import com.lessplain.storage.pendingNoteBatch
import com.lessplain.storage.pendingNoteScope
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PendingQuestionHandoffTest {
    @TempDir lateinit var directory: Path
    private val graph = UnderstandingGraph(listOf(GraphNode("n", "相同节点")), emptyList())

    @Test fun `root child and nested entry resolve same scope while other understandings stay isolated`() {
        UnderstandingRepository(directory).use { repository ->
            val core = UnderstandingCore(repository)
            val root = core.present(4, "root", "根理解", graph, "root")
            val other = core.present(4, "other", "其他理解", graph, "root")
            val context = core.captureContext(root.id, "n")
            val answered = core.prepareQuestion("创建追问", context.token).question
            val child = core.present(4, "child", answered.text, graph, "child", questionId = answered.id)
            val nested = core.present(4, "nested", "嵌套图", graph, "child", core.captureContext(child.id, "n").token)
            val a = core.prepareQuestion("普通问题", context.token).question
            val b = core.prepareQuestion("图上的线很乱", core.captureContext(child.id, "n").token).question
            val c = core.prepareQuestion("更深的问题", core.captureContext(nested.id, "n").token).question
            core.prepareQuestion("无节点的问题", core.captureContext(root.id).token)
            core.prepareQuestion("其他理解的问题", core.captureContext(other.id, "n").token)
            for (record in listOf(root, child, nested)) {
                assertEquals(root.id, repository.pendingNoteScope(record.id).recordId)
                assertEquals(3, repository.pendingNoteScope(record.id).count)
            }
            assertEquals(1, repository.pendingNoteScope(other.id).count)
            repository.cancelPendingQuestion(a.id)
            assertEquals(2, repository.pendingNoteScope(child.id).count)
            repository.consumeReadFeedback(repository.pendingNoteBatch(root.id).items.filter { it.questionId == b.id })
            assertEquals(1, repository.pendingNoteScope(nested.id).count)
            core.present(4, "answer", c.text, graph, "child", questionId = c.id)
            assertEquals(0, repository.pendingNoteScope(root.id).count)
            assertFailsWith<UnderstandingInputException> { repository.pendingNoteScope("missing") }
        }
    }

    @Test fun `copy keeps raw text order and graph labels without leaking source snapshots or context tokens`() {
        UnderstandingRepository(directory).use { repository ->
            val core = UnderstandingCore(repository)
            val root = core.present(4, "root", "根标题", graph, "root")
            val context = core.captureContext(root.id, "n")
            val child = core.present(4, "child", "追问标题", graph, "child", context.token)
            val nested = core.present(4, "nested", "多层追问", graph, "child", core.captureContext(child.id, "n").token)
            val raw = "保留中文 & <特殊字符> \"引号\"\n  第二行的缩进\nEnglish?"
            val selected = context.copy(token = "frozen-selection", anchor = SourceAnchor("private/Source.kt",
                "privateFunction", "private source body", "version"))
            repository.putContext(selected)
            core.prepareQuestion(raw, selected.token)
            core.prepareQuestion("追问问题", core.captureContext(child.id, "n").token)
            core.prepareQuestion("更深问题", core.captureContext(nested.id, "n").token)
            val before = repository.pendingNoteBatch(root.id)
            val expected = "请用 Lessplain 处理「根标题」的待答注释：\n\n" +
                "1. 相同节点：$raw\n2. 追问标题 / 相同节点：追问问题\n" +
                "3. 多层追问 / 相同节点：更深问题\n\nLessplain 待答批次：${root.id}"
            val copied = repository.preparePendingQuestionCopy(repository.pendingNoteScope(nested.id).recordId)
            assertEquals(expected, copied.text)
            assertTrue(formatPendingQuestionCopy(root.copy(displayTitle = "自定义理解标题"), before).text
                .startsWith("请用 Lessplain 处理「自定义理解标题」"))
            assertEquals(3, copied.count)
            repeat(2) { assertEquals(copied, repository.preparePendingQuestionCopy(root.id)) }
            assertEquals(before, repository.pendingNoteBatch(root.id))
            listOf("private/Source.kt", "private source body", "frozen-selection", "contextToken", "staticPath",
                "get_understanding_context", directory.toString()).forEach { assertTrue(it !in copied.text) }
            val invalid = before.copy(items = before.items + PendingNoteItem("invalid", "来源已删，问题保留",
                context.copy(nodeId = "deleted")))
            assertTrue("4. 来源节点已失效：来源已删，问题保留" in formatPendingQuestionCopy(root, invalid).text)
            assertTrue("追问标题 / 来源节点已失效" in formatPendingQuestionCopy(root,
                before.copy(items = listOf(before.items[1].copy(context = before.items[1].context.copy(nodeId = "deleted"))))).text)
        }
    }

    @Test fun `agent batch after copy includes later additions and excludes completed notes`() {
        UnderstandingRepository(directory).use { repository ->
            val core = UnderstandingCore(repository)
            val root = core.present(4, "root", "根标题", graph, "root")
            val context = core.captureContext(root.id, "n")
            val first = core.prepareQuestion("复制时的问题", context.token).question
            val copy = repository.preparePendingQuestionCopy(root.id)
            val later = core.prepareQuestion("复制之后新增", context.token).question
            core.present(4, "answer", first.text, graph, "child", questionId = first.id)
            val marker = copy.text.substringAfterLast("Lessplain 待答批次：")
            assertEquals(root.id, marker)
            assertEquals(listOf(later.id), repository.pendingNoteBatch(marker).items.map { it.questionId })
            assertEquals(1, repository.pendingNoteScope(root.id).count)
        }
    }

    @Test fun `broken parent and parent cycle report failure instead of falling back to another root`() {
        UnderstandingRepository(directory).use { repository ->
            val root = UnderstandingCore(repository).present(4, "root", "根标题", graph, "root")
            fun changeParent(parent: String) = DriverManager.getConnection("jdbc:sqlite:${repository.databasePath}").use { db ->
                db.prepareStatement("UPDATE records SET parent_id=? WHERE id=?").use { statement ->
                    statement.setString(1, parent)
                    statement.setString(2, root.id)
                    statement.executeUpdate()
                }
            }
            changeParent("missing")
            assertFailsWith<UnderstandingInputException> { repository.pendingNoteScope(root.id) }
            changeParent(root.id)
            assertFailsWith<UnderstandingInputException> { repository.pendingNoteScope(root.id) }
        }
    }

    @Test fun `clipboard preserves non text contents and does not write when original cannot be read`() {
        val original = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.javaFileListFlavor
            override fun getTransferData(flavor: DataFlavor): Any = listOf(java.io.File("sample.kt"))
        }
        var clipboard: Transferable = original
        var writes = 0
        val copy = com.lessplain.core.PendingQuestionCopy("提问文本", 1)
        assertFailsWith<IllegalStateException> {
            replaceQuestionClipboard(copy, { clipboard }) {
                clipboard = it
                if (++writes == 1) error("写入失败")
            }
        }
        assertSame(original, clipboard)
        assertTrue(clipboard.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
        writes = 0
        assertFailsWith<IllegalStateException> {
            replaceQuestionClipboard(copy, { error("无法读取原剪贴板") }) { writes++ }
        }
        assertEquals(0, writes)
        assertSame(original, clipboard)
    }

    @Test fun `clipboard write failure restores original transferable and leaves notes pending`() {
        UnderstandingRepository(directory).use { repository ->
            val core = UnderstandingCore(repository)
            val root = core.present(4, "root", "根标题", graph, "root")
            core.prepareQuestion("问题", core.captureContext(root.id, "n").token)
            val before = repository.pendingNoteBatch(root.id)
            val original = StringSelection("原来的剪贴板")
            var clipboard: Transferable = original
            var writes = 0
            assertFailsWith<IllegalStateException> {
                replaceQuestionClipboard(repository.preparePendingQuestionCopy(root.id), { clipboard }) {
                    clipboard = it
                    if (++writes == 1) error("写入失败")
                }
            }
            assertSame(original, clipboard)
            assertEquals(before, repository.pendingNoteBatch(root.id))
            replaceQuestionClipboard(repository.preparePendingQuestionCopy(root.id), { clipboard }) { clipboard = it }
            assertTrue((clipboard.getTransferData(DataFlavor.stringFlavor) as String).contains("相同节点：问题"))
            assertEquals(before, repository.pendingNoteBatch(root.id))
        }
    }
}
