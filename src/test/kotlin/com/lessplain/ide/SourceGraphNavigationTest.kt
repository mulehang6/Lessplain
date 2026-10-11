package com.lessplain.ide

import com.lessplain.core.UnderstandingCore
import com.lessplain.model.BrowseState
import com.lessplain.model.GraphEdge
import com.lessplain.model.GraphNode
import com.lessplain.model.RouteNavigationState
import com.lessplain.model.UnderstandingGraph
import com.lessplain.storage.UnderstandingRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
                showGraph = { _, _ -> error("A unique edge must not open a graph") },
            )

            navigation.previous(record.id, "c")
            assertEquals("b", selectedNodeId)
            assertEquals(BrowseState(record.id, nodeId = "b", staticPath = listOf("ab")),
                repository.getBrowseState(record.id)?.copy(navigationState = null))
            assertEquals(RouteNavigationState("a", listOf("ab")), repository.getBrowseState(record.id)?.navigationState)
            assertEquals("b", graphStates.get(GraphLayerKey(record.id)).browse?.nodeId)
            assertEquals(listOf("ab"), graphStates.get(GraphLayerKey(record.id)).browse?.staticPath)

            navigation.next(record.id, "b")
            assertEquals("c", selectedNodeId)
            assertEquals("c", repository.getBrowseState(record.id)?.nodeId)
            assertEquals(listOf("ab", "bc"), repository.getBrowseState(record.id)?.staticPath)
        }
    }

    @Test
    fun `source choices open the graph once and dispatch subsequent directions to its owner`() {
        UnderstandingRepository(root).use { repository ->
            val record = UnderstandingCore(repository).present(3, "canvas", "画布", mergeGraph(), "root")
            val original = BrowseState(record.id, "d", navigationState = RouteNavigationState("d"))
            repository.saveBrowseState(original)
            val values = mutableMapOf<String, String>()
            val states = GraphViewStateStore(values::get) { key, value -> values[key] = value }
            val steps = mutableListOf<Boolean>()
            var opens = 0
            var owner: CanvasNavigationSessions.Registration? = null
            val navigation = SourceGraphNavigation(repository, states,
                isSelected = { id, node -> id == record.id && node == "d" },
                selectNode = { _, _ -> error("Candidate highlighting must not commit a source selection") },
                showGraph = { shown, ready ->
                    assertEquals(record, shown)
                    opens++
                    owner = states.navigationSessions.register(
                        { id, node -> id == record.id && node == "d" }, { true }, {}, {},
                        sourceStep = { steps += it },
                    ).also { it.activate() }
                    ready()
                },
            )
            try {
                navigation.previous(record.id, "d")
                navigation.previous(record.id, "d")
                navigation.next(record.id, "d")
                assertEquals(1, opens)
                assertEquals(listOf(true, true, false), steps)
                assertEquals(original, repository.getBrowseState(record.id))
                owner?.close()
                navigation.previous(record.id, "d")
                assertEquals(2, opens)
                assertEquals(listOf(true, true, false, true), steps)
            } finally { navigation.close(); owner?.close() }
        }
    }

    @Test
    fun `a graph opening completed after cancellation selection or disposal cannot apply a step`() {
        UnderstandingRepository(root).use { repository ->
            val record = UnderstandingCore(repository).present(3, "deferred", "延迟打开", mergeGraph(), "root")
            repository.saveBrowseState(BrowseState(record.id, "d"))
            val values = mutableMapOf<String, String>()
            val states = GraphViewStateStore(values::get) { key, value -> values[key] = value }
            var selected = "d"
            var steps = 0
            val callbacks = mutableListOf<() -> Unit>()
            val navigation = SourceGraphNavigation(repository, states,
                isSelected = { id, node -> id == record.id && node == selected },
                selectNode = { _, _ -> error("No source selection expected") },
                showGraph = { _, ready -> callbacks += ready },
            )
            navigation.previous(record.id, "d")
            navigation.cancelChoices()
            navigation.previous(record.id, "d")
            selected = "b"
            val owner = states.navigationSessions.register({ _, _ -> true }, { false }, { steps++ }, { steps++ })
            try {
                selected = "d"
                callbacks.first()()
                assertEquals(0, steps)
                selected = "b"
                callbacks[1]()
                assertEquals(0, steps)
                selected = "d"
                owner.close()
                navigation.previous(record.id, "d")
                val replacement = states.navigationSessions.register({ _, _ -> true }, { false }, { steps++ }, {})
                navigation.close()
                callbacks.last()()
                assertEquals(0, steps)
                replacement.close()
            } finally { navigation.close(); owner.close() }
        }
    }

    @Test
    fun `a changed route snapshot invalidates a deferred graph opening`() {
        UnderstandingRepository(root).use { repository ->
            val record = UnderstandingCore(repository).present(3, "stale", "过期选择", mergeGraph(), "root")
            repository.saveBrowseState(BrowseState(record.id, "d"))
            val values = mutableMapOf<String, String>()
            val states = GraphViewStateStore(values::get) { key, value -> values[key] = value }
            var ready: (() -> Unit)? = null
            var steps = 0
            val navigation = SourceGraphNavigation(repository, states, { _, node -> node == "d" }, { _, _ -> },
                showGraph = { _, callback -> ready = callback })
            navigation.previous(record.id, "d")
            val direct = BrowseState(record.id, "d", listOf("ab", "bd"),
                navigationState = RouteNavigationState("a", listOf("ab", "bd")))
            states.update(GraphLayerKey(record.id)) { it.copy(browse = direct) }
            repository.saveBrowseState(direct)
            val owner = states.navigationSessions.register({ _, _ -> true }, { false }, { steps++ }, {})
            try {
                assertTrue(ready != null)
                ready()
                assertEquals(0, steps)
                assertEquals(direct, repository.getBrowseState(record.id))
            } finally { owner.close(); navigation.close() }
        }
    }

    @Test
    fun `tool window show completion waits for a later visible canvas before highlighting choices`() {
        UnderstandingRepository(root).use { repository ->
            val record = UnderstandingCore(repository).present(3, "late-canvas", "延迟图表", mergeGraph(), "root")
            val browse = BrowseState(record.id, "d")
            repository.saveBrowseState(browse)
            val values = mutableMapOf<String, String>()
            val states = GraphViewStateStore(values::get) { key, value -> values[key] = value }
            var shows = 0
            var steps = 0
            var visible = false
            val navigation = SourceGraphNavigation(repository, states, { _, n -> n == "d" }, { _, _ -> },
                showGraph = { _, ready -> shows++; ready() })
            navigation.previous(record.id, "d")
            assertEquals(1, shows)
            val owner = states.navigationSessions.register(
                { r, n -> visible && r == record.id && n == "d" }, { false }, { steps++ }, {},
            )
            try {
                assertEquals(0, steps)
                visible = true
                owner.activate()
                assertEquals(1, steps)
                owner.activate()
                assertEquals(1, steps)
                assertEquals(browse, repository.getBrowseState(record.id))
            } finally { owner.close(); navigation.close() }
        }
    }

    @Test
    fun `cancel before the tool window canvas attaches prevents a delayed choice`() {
        UnderstandingRepository(root).use { repository ->
            val record = UnderstandingCore(repository).present(3, "cancelled-canvas", "取消图表", mergeGraph(), "root")
            repository.saveBrowseState(BrowseState(record.id, "d"))
            val values = mutableMapOf<String, String>()
            val states = GraphViewStateStore(values::get) { key, value -> values[key] = value }
            var steps = 0
            val navigation = SourceGraphNavigation(repository, states, { _, n -> n == "d" }, { _, _ -> },
                showGraph = { _, ready -> ready() })
            navigation.previous(record.id, "d")
            navigation.cancelChoices()
            val owner = states.navigationSessions.register({ _, _ -> true }, { false }, { steps++ }, {})
            try {
                owner.activate()
                assertEquals(0, steps)
            } finally { owner.close(); navigation.close() }
        }
    }

    @Test
    fun `repeated source choices reuse an opening graph until its canvas is available`() {
        for (showCompleted in listOf(false, true)) {
            UnderstandingRepository(root.resolve("repeat-$showCompleted")).use { repository ->
                val record = UnderstandingCore(repository).present(3, "repeat", "延迟图表", mergeGraph(), "root")
                repository.saveBrowseState(BrowseState(record.id, "d"))
                val values = mutableMapOf<String, String>()
                val states = GraphViewStateStore(values::get) { key, value -> values[key] = value }
                var opens = 0
                var steps = 0
                var ready: (() -> Unit)? = null
                val navigation = SourceGraphNavigation(repository, states, { _, n -> n == "d" }, { _, _ -> },
                    showGraph = { _, callback -> opens++; ready = callback })
                navigation.use {
                    navigation.previous(record.id, "d")
                    if (showCompleted) ready!!()
                    navigation.previous(record.id, "d")
                    assertEquals(1, opens)
                    val owner = states.navigationSessions.register({ _, _ -> true }, { false }, { steps++ }, {})
                    try {
                        if (!showCompleted) ready!!()
                        assertEquals(1, steps)
                    } finally { owner.close() }
                    navigation.previous(record.id, "d")
                    assertEquals(2, opens)
                }
            }
        }
    }
    @Test
    fun `opposite source direction invalidates an opening choice`() {
        UnderstandingRepository(root).use { repository ->
            val record = UnderstandingCore(repository).present(3, "direction", "切换方向", mergeGraph(), "root")
            repository.saveBrowseState(BrowseState(record.id, "d"))
            val values = mutableMapOf<String, String>()
            val states = GraphViewStateStore(values::get) { key, value -> values[key] = value }
            val callbacks = mutableListOf<() -> Unit>()
            var steps = 0
            SourceGraphNavigation(repository, states, { _, n -> n == "d" }, { _, _ -> },
                showGraph = { _, ready -> callbacks += ready }).use { navigation ->
                navigation.previous(record.id, "d")
                navigation.next(record.id, "d")
                navigation.previous(record.id, "d")
                assertEquals(2, callbacks.size)
                val owner = states.navigationSessions.register({ _, _ -> true }, { false }, { steps++ }, {})
                try {
                    callbacks.first()()
                    assertEquals(0, steps)
                    callbacks.last()()
                    assertEquals(1, steps)
                } finally { owner.close() }
            }
        }
    }

    @Test
    fun `a failed graph opening allows retrying the same source choice`() {
        UnderstandingRepository(root).use { repository ->
            val record = UnderstandingCore(repository).present(3, "retry", "重试图表", mergeGraph(), "root")
            repository.saveBrowseState(BrowseState(record.id, "d"))
            val values = mutableMapOf<String, String>()
            val states = GraphViewStateStore(values::get) { key, value -> values[key] = value }
            var opens = 0
            var steps = 0
            var ready: (() -> Unit)? = null
            SourceGraphNavigation(repository, states, { _, n -> n == "d" }, { _, _ -> },
                showGraph = { _, callback ->
                    opens++
                    if (opens == 1) error("无法显示图表")
                    ready = callback
                }).use { navigation ->
                assertFailsWith<IllegalStateException> { navigation.previous(record.id, "d") }
                navigation.previous(record.id, "d")
                assertEquals(2, opens)
                val owner = states.navigationSessions.register({ _, _ -> true }, { false }, { steps++ }, {})
                try {
                    ready!!()
                    assertEquals(1, steps)
                } finally { owner.close() }
            }
        }
    }
    private fun mergeGraph() = UnderstandingGraph(listOf("a", "b", "c", "d").map { GraphNode(it, it) }, listOf(
        GraphEdge("ab", "a", "b", "control_flow", "走 B"), GraphEdge("ac", "a", "c", "control_flow", "走 C"),
        GraphEdge("bd", "b", "d", "call"), GraphEdge("cd", "c", "d", "data_flow"),
    ))

    @Test
    fun `source steps cross a structural start boundary and a dependency while keeping route state`() {
        UnderstandingRepository(root).use { repository ->
            val graph = UnderstandingGraph(
                listOf(GraphNode("registration", "登记"), GraphNode("tool", "工具", flowRoles = listOf("start")),
                    GraphNode("store", "存储")),
                listOf(GraphEdge("rt", "registration", "tool", "structure"),
                    GraphEdge("ts", "tool", "store", "dependency")),
            )
            val record = UnderstandingCore(repository).present(3, "all-relations", "源码阅读", graph, "root")
            repository.saveBrowseState(BrowseState(record.id, nodeId = "tool"))
            val values = mutableMapOf<String, String>()
            val states = GraphViewStateStore(values::get) { key, value -> values[key] = value }
            var selected = "tool"
            SourceGraphNavigation(repository, states,
                isSelected = { id, node -> id == record.id && node == selected },
                selectNode = { _, node -> selected = node },
                showGraph = { _, _ -> error("Unique links do not require choosing a route") },
            ).use { navigation ->
                assertTrue(navigation.explorer(record, selected).canPrevious)
                navigation.previous(record.id, selected)
                assertEquals("registration", selected)
                navigation.next(record.id, selected)
                assertEquals("tool", selected)
                navigation.next(record.id, selected)
                assertEquals("store", selected)
                assertEquals(listOf("rt", "ts"), repository.getBrowseState(record.id)?.staticPath)
                navigation.previous(record.id, selected)
                assertEquals("tool", selected)
                assertEquals(listOf("rt"), states.get(GraphLayerKey(record.id)).browse?.staticPath)
            }
        }
    }
}
