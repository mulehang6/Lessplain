package com.master.lessplain.ui

import com.master.lessplain.model.GraphNode
import com.master.lessplain.model.UnderstandingGraph
import com.master.lessplain.model.UnderstandingQuestion
import com.master.lessplain.model.UnderstandingRecord
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class QuestionClusterMappingTest {
    @Test fun `each question gets one complete visual group and its actual source record`() {
        val graph = UnderstandingGraph(listOf(GraphNode("n", "步骤"), GraphNode("m", "下一步")), emptyList())
        fun child(id: String) = UnderstandingRecord(id, 1, "root", id, "问题$id", "", null, graph, 0)
        fun question(id: String) = UnderstandingQuestion(id, "问题$id", "context", "root", "answered", 0)
        val first = questionClusterMapping(question("one"), child("one").copy(displayTitle = "自定义标题"), "qone_").single()
        val second = questionClusterMapping(question("two"), child("two"), "qtwo_").single()
        assertNotEquals(first.first.id, second.first.id)
        assertEquals("one", first.first.id)
        assertEquals("自定义标题", first.first.title)
        assertEquals("问题two", second.first.title)
        assertEquals(setOf("qone_n", "qone_m"), first.first.nodeIds)
        assertEquals("one", first.second.record.id)
        assertEquals(listOf("n", "m"), first.second.record.graph.nodes.map { it.id })
    }
}
