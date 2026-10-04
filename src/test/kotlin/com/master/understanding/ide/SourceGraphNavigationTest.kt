package com.master.understanding.ide

import com.master.understanding.core.UnderstandingCore
import com.master.understanding.core.StepDirection
import com.master.understanding.diagram.RouteChoicePresenter
import com.master.understanding.model.BrowseState
import com.master.understanding.model.GraphEdge
import com.master.understanding.model.GraphNode
import com.master.understanding.model.RouteNavigationState
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
                repository.getBrowseState(record.id)?.copy(navigationState = null))
            assertEquals(RouteNavigationState("a", listOf("ab")), repository.getBrowseState(record.id)?.navigationState)
            assertEquals("b", graphStates.get(GraphLayerKey(record.id)).browse?.nodeId)
            assertEquals(listOf("ab"), graphStates.get(GraphLayerKey(record.id)).browse?.staticPath)

            navigation.next(record.id, "b", JPanel(), Point())
            assertEquals("c", selectedNodeId)
            assertEquals("c", repository.getBrowseState(record.id)?.nodeId)
            assertEquals(listOf("ab", "bc"), repository.getBrowseState(record.id)?.staticPath)
        }
    }

    @Test
    fun `source previous after direct selection asks for all incoming edges and next preserves chosen prefix`() {
        UnderstandingRepository(root).use { repository ->
            val record = UnderstandingCore(repository).present(3, "merge", "合流", mergeGraph(), "root")
            repository.saveBrowseState(BrowseState(record.id, "d"))
            val values = mutableMapOf<String, String>()
            val states = GraphViewStateStore(values::get) { key, value -> values[key] = value }
            var selected = "d"
            var finished: ((String?) -> Unit)? = null
            var requests = 0
            val presenter = RouteChoicePresenter { _, direction, edges, _, point, _, callback ->
                assertEquals(StepDirection.PREVIOUS, direction)
                assertEquals(listOf("bd", "cd"), edges.map { it.id })
                assertEquals(Point(7, 9), point)
                requests++
                finished = callback
                AutoCloseable { }
            }
            val navigation = SourceGraphNavigation(repository, states,
                isSelected = { id, node -> id == record.id && node == selected },
                selectNode = { _, node -> selected = node }, choices = presenter)
            navigation.previous(record.id, "d", JPanel(), Point(7, 9))
            assertEquals("d", selected)
            finished?.invoke(null)
            assertEquals("d", repository.getBrowseState(record.id)?.nodeId)
            navigation.previous(record.id, "d", JPanel(), Point(7, 9))
            finished?.invoke("cd")
            assertEquals("c", selected)
            assertEquals(RouteNavigationState("c"), repository.getBrowseState(record.id)?.navigationState)
            navigation.next(record.id, "c", JPanel(), Point())
            assertEquals(setOf("ac", "cd"), navigation.explorer(record, "d").highlightedEdgeIds)
            navigation.previous(record.id, "d", JPanel(), Point(7, 9))
            assertEquals(3, requests)
            navigation.close()
            finished?.invoke("bd")
            assertEquals("d", selected)
        }
    }

    @Test
    fun `a choice opened before a new route snapshot cannot overwrite that snapshot`() {
        UnderstandingRepository(root).use { repository ->
            val record = UnderstandingCore(repository).present(3, "stale", "过期选择", mergeGraph(), "root")
            repository.saveBrowseState(BrowseState(record.id, "d", listOf("ab", "bd")))
            val values = mutableMapOf<String, String>()
            val states = GraphViewStateStore(values::get) { key, value -> values[key] = value }
            var finished: ((String?) -> Unit)? = null
            var commits = 0
            val navigation = SourceGraphNavigation(repository, states, { _, node -> node == "d" },
                { _, _ -> commits++ }, RouteChoicePresenter { _, _, _, _, _, _, callback ->
                    finished = callback
                    AutoCloseable { }
                })
            navigation.previous(record.id, "d", JPanel())
            val direct = BrowseState(record.id, "d", navigationState = RouteNavigationState("d"))
            repository.saveBrowseState(direct)
            states.update(GraphLayerKey(record.id)) { it.copy(browse = direct) }
            finished?.invoke("bd")
            assertEquals(0, commits)
            assertEquals(direct, repository.getBrowseState(record.id))
            navigation.close()
        }
    }

    @Test
    fun `source steps use the active canvas and resume local choices after it closes`() {
        UnderstandingRepository(root).use { repository ->
            val record = UnderstandingCore(repository).present(3, "canvas-owner", "画布导航", mergeGraph(), "root")
            val original = BrowseState(record.id, "d", navigationState = RouteNavigationState("d"))
            repository.saveBrowseState(original)
            val values = mutableMapOf<String, String>()
            val states = GraphViewStateStore(values::get) { key, value -> values[key] = value }
            var previous = 0
            var next = 0
            var busy = false
            val owner = states.navigationSessions.register(
                matches = { id, node -> id == record.id && node == "d" },
                busy = { busy },
                previous = { previous++ },
                next = { next++ },
            )
            owner.activate()
            var requests = 0
            var selected = "d"
            var finished: ((String?) -> Unit)? = null
            val navigation = SourceGraphNavigation(repository, states,
                isSelected = { id, node -> id == record.id && node == selected },
                selectNode = { _, node -> selected = node },
                choices = RouteChoicePresenter { _, direction, edges, _, _, _, callback ->
                    assertEquals(StepDirection.PREVIOUS, direction)
                    assertEquals(listOf("bd", "cd"), edges.map { it.id })
                    requests++
                    finished = callback
                    AutoCloseable { }
                })
            try {
                navigation.previous(record.id, "d", JPanel())
                navigation.next(record.id, "d", JPanel(), Point())
                assertEquals(1, previous)
                assertEquals(1, next)
                busy = true
                navigation.previous(record.id, "d", JPanel())
                assertEquals(1, previous)
                assertEquals(0, requests)
                assertEquals(original, repository.getBrowseState(record.id))

                owner.close()
                navigation.previous(record.id, "d", JPanel())
                assertEquals(1, requests)
                finished?.invoke("cd")
                assertEquals("c", selected)
                assertEquals(RouteNavigationState("c"), repository.getBrowseState(record.id)?.navigationState)
            } finally {
                navigation.close()
                owner.close()
            }
        }
    }

    private fun mergeGraph() = UnderstandingGraph(listOf("a", "b", "c", "d").map { GraphNode(it, it) }, listOf(
        GraphEdge("ab", "a", "b", "control_flow", "走 B"), GraphEdge("ac", "a", "c", "control_flow", "走 C"),
        GraphEdge("bd", "b", "d", "call"), GraphEdge("cd", "c", "d", "data_flow"),
    ))
}
