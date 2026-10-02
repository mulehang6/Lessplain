package com.master.understanding.ide

import com.master.understanding.core.UnderstandingCore
import com.master.understanding.model.BrowseState
import com.master.understanding.model.GraphEdge
import com.master.understanding.model.GraphNode
import com.master.understanding.model.UnderstandingGraph
import com.master.understanding.storage.UnderstandingRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Point
import java.nio.file.Path
import javax.swing.JPanel
import kotlin.test.assertEquals

class SourceGraphNavigationTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `source steps persist the selected node with its actual edge path`() {
        UnderstandingRepository(root).use { repository ->
            val graph = UnderstandingGraph(
                nodes = listOf(GraphNode("a", "A"), GraphNode("b", "B"), GraphNode("c", "C")),
                edges = listOf(
                    GraphEdge("ab", "a", "b", "call"),
                    GraphEdge("bc", "b", "c", "data_flow"),
                ),
            )
            val record = UnderstandingCore(repository).present(3, "source-route", "源码路径", graph, "root")
            repository.saveBrowseState(BrowseState(record.id, nodeId = "c", staticPath = listOf("ab", "bc")))
            val values = mutableMapOf<String, String>()
            val graphStates = GraphViewStateStore(
                read = values::get,
                write = { key, value -> values[key] = value },
            )
            var selectedNodeId = "c"
            val navigation = SourceGraphNavigation(
                repository = repository,
                graphStates = graphStates,
                isSelected = { recordId, nodeId -> recordId == record.id && nodeId == selectedNodeId },
                selectNode = { _, nodeId -> selectedNodeId = nodeId },
            )

            navigation.previous(record.id, "c")
            assertEquals("b", selectedNodeId)
            assertEquals(BrowseState(record.id, nodeId = "b", staticPath = listOf("ab")),
                repository.getBrowseState(record.id))
            assertEquals("b", graphStates.get(GraphLayerKey(record.id)).browse?.nodeId)
            assertEquals(listOf("ab"), graphStates.get(GraphLayerKey(record.id)).browse?.staticPath)

            navigation.next(record.id, "b", JPanel(), Point())
            assertEquals("c", selectedNodeId)
            assertEquals("c", repository.getBrowseState(record.id)?.nodeId)
            assertEquals(listOf("ab", "bc"), repository.getBrowseState(record.id)?.staticPath)
        }
    }
}
