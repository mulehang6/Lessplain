package com.master.understanding

import com.master.understanding.core.UnderstandingCore
import com.master.understanding.model.Evidence
import com.master.understanding.model.GraphNode
import com.master.understanding.model.SourceAnchor
import com.master.understanding.model.UnderstandingGraph
import com.master.understanding.model.UnderstandingInputException
import com.master.understanding.model.validate
import com.master.understanding.storage.UnderstandingRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SourceAnchorValidationTest {
    @TempDir lateinit var root: Path
    private val anchor = SourceAnchor("src/A.kt", "A.run", "run()", "fixed-version", 1)
    private val invalidPaths = listOf("\\outside\\A.kt", "\\\\server\\share\\A.kt", "/outside/A.kt",
        "C:\\outside\\A.kt", "C:outside\\A.kt", "src/../A.kt", "src\\..\\A.kt")

    @Test
    fun `project relative paths accept either separator and reject rooted paths`() {
        for (path in listOf("src/A.kt", "src\\A.kt")) anchor.copy(path = path).validate()
        for (path in invalidPaths) {
            assertFailsWith<UnderstandingInputException>(path) { anchor.copy(path = path).validate() }
        }
    }

    @Test
    fun `invalid node evidence and dependency paths cannot be committed or consume a request id`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            for (path in invalidPaths) {
                val invalid = anchor.copy(path = path)
                val graphs = listOf(
                    UnderstandingGraph(listOf(GraphNode("a", "A", anchor = invalid)), emptyList()),
                    UnderstandingGraph(listOf(GraphNode("a", "A", evidenceIds = listOf("e"))), emptyList(),
                        listOf(Evidence("e", invalid))),
                    UnderstandingGraph(listOf(GraphNode("a", "A", anchor = anchor)), emptyList(),
                        listOf(Evidence("e", anchor, dependencies = listOf(invalid)))),
                )
                for (graph in graphs) {
                    assertFailsWith<UnderstandingInputException>(path) {
                        core.present(4, "invalid-path", "测试", graph, "root")
                    }
                    assertTrue(repository.recentRecords().isEmpty())
                }
            }
            val valid = UnderstandingGraph(listOf(GraphNode("a", "A", anchor = anchor)), emptyList())
            val record = core.present(4, "invalid-path", "测试", valid, "root")
            assertEquals(listOf(record.id), repository.recentRecords().map { it.id })
        }
    }
}
