package com.lessplain

import com.lessplain.core.UnderstandingCore
import com.lessplain.model.*
import com.lessplain.storage.UnderstandingRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class UnderstandingSchemaTest {
    @TempDir lateinit var root: Path
    @Test fun `inferred edges require coverage and summaries persist with complete evidence`() {
        val graph = UnderstandingGraph(listOf(GraphNode("a", "A"), GraphNode("b", "B")),
            listOf(GraphEdge("ab", "a", "b", "call", coverage = "完整依据", inferred = true, summary = "传递结果")))
        graph.validate()
        assertFailsWith<UnderstandingInputException> { graph.copy(edges = graph.edges.map { it.copy(coverage = null) }).validate() }
        UnderstandingRepository(root).use { repository ->
            val record = UnderstandingCore(repository).present(4, "summary", "关系", graph, "root")
            assertEquals(graph, repository.getRecord(record.id).graph)
        }
    }
}
