package com.lessplain

import com.lessplain.core.UnderstandingCore
import com.lessplain.ide.AnchorSourceSnapshot
import com.lessplain.ide.validateGraphAnchors
import com.lessplain.model.GraphNode
import com.lessplain.model.SourceAnchor
import com.lessplain.model.UnderstandingGraph
import com.lessplain.model.UnderstandingRevisionConflict
import com.lessplain.model.UnderstandingInputException
import com.lessplain.storage.UnderstandingRepository
import com.lessplain.storage.renameNodeLabel
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 人工标签修订不依赖当前源码，仍受图版本约束。 */
class UnderstandingNodeLabelTest {
    @TempDir lateinit var root: Path

    @Test fun `rename preserves frozen anchors notes and history after the source changes`() {
        var source = "fun run() = 1"
        var sourceReads = 0
        lateinit var recordId: String
        lateinit var questionId: String
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository, prepareGraph = { graph ->
                validateGraphAnchors(graph) {
                    sourceReads++
                    AnchorSourceSnapshot(source) { null }
                }
            })
            val graph = UnderstandingGraph(listOf(GraphNode("run", "旧标签", anchor = SourceAnchor("Run.kt", null, source, ""))), emptyList())
            val saved = core.present(4, "root", "入口", graph, "root")
            recordId = saved.id
            val frozen = core.captureContext(saved.id, "run", saved.graph.nodes.single().anchor)
            val question = core.prepareQuestion("这是我的注释", frozen.token).question
            questionId = question.id
            source += "\n// 修改其他位置"
            val renamed = repository.renameNodeLabel(saved.id, "run", "  新标签  ", saved.revision)
            assertEquals(saved.copy(revision = 2, graph = saved.graph.copy(nodes = saved.graph.nodes.map { it.copy(label = "新标签") })), renamed)
            assertEquals(1, sourceReads)
            assertEquals(frozen, repository.getContext(frozen.token))
            assertEquals(question, repository.getQuestion(question.id))
            repository.connect().use { db ->
                db.prepareStatement("SELECT data FROM record_versions WHERE record_id=? AND revision=1").use { statement ->
                    statement.setString(1, saved.id)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(saved, repository.gson.fromJson(rows.getString(1), com.lessplain.model.UnderstandingRecord::class.java))
                    }
                }
            }
            val conflict = assertFailsWith<UnderstandingRevisionConflict> { repository.renameNodeLabel(saved.id, "run", "并发覆盖", 1) }
            assertEquals(renamed.revision, conflict.currentRevision)
            assertFailsWith<UnderstandingInputException> { repository.renameNodeLabel(saved.id, "missing", "不存在", 2) }
            assertFailsWith<UnderstandingInputException> { repository.renameNodeLabel(saved.id, "run", " ", 2) }
            assertEquals(renamed, repository.getRecord(saved.id))
        }
        UnderstandingRepository(root).use { reopened ->
            assertEquals("新标签", reopened.getRecord(recordId).graph.nodes.single().label)
            assertEquals("这是我的注释", reopened.getQuestion(questionId).text)
        }
    }
}
