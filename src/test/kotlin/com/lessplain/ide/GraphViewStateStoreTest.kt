package com.lessplain.ide

import com.lessplain.model.BrowseState
import com.lessplain.model.RouteNavigationState
import com.lessplain.diagram.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphViewStateStoreTest {
    @Test fun `closing the last subscription releases its layer and allows subscribing again`() {
        val store = GraphViewStateStore({ null }) { _, _ -> }
        val key = GraphLayerKey("temporary")
        var updates = 0
        val first = store.listen(key) { updates++ }
        val second = store.listen(key) { updates++ }
        first.close()
        store.update(key) { it }
        assertEquals(1, updates)
        second.close()
        val field = GraphViewStateStore::class.java.getDeclaredField("listeners").apply { isAccessible = true }
        assertTrue((field.get(store) as Map<*, *>).isEmpty())
        store.listen(key) { updates++ }.use { store.update(key) { it } }
        assertEquals(2, updates)
        assertTrue((field.get(store) as Map<*, *>).isEmpty())
    }

    @Test
    fun `navigation prefix round trips while missing or corrupt optional fields retain the selected node`() {
        val values = mutableMapOf<String, String>()
        val key = GraphLayerKey("route")
        val store = GraphViewStateStore(values::get) { name, value -> values[name] = value }
        val browse = BrowseState("route", "e", listOf("de"), navigationState = RouteNavigationState("d", listOf("de")))
        store.update(key) { it.copy(browse = browse) }
        assertEquals(browse, store.get(key).browse)
        values[key.propertyName()] = """{"browse":{"recordId":"route","nodeId":"d","future":true}}"""
        assertEquals(BrowseState("route", "d"), store.get(key).browse)
        values[key.propertyName()] = """{"browse":{"recordId":"route","nodeId":"d",
            "staticPath":"invalid","navigationState":"invalid"}}"""
        val corrupt = store.get(key).browse
        assertEquals("d", corrupt?.nodeId)
        assertEquals(emptyList(), corrupt?.staticPath)
        assertEquals(RouteNavigationState(""), corrupt?.navigationState)
    }

    @Test
    fun `legacy coordinates migrate to vertical while each direction keeps its own viewport`() {
        val values = mutableMapOf<String, String>()
        val key = GraphLayerKey("legacy")
        values[key.propertyName()] = """{"browse":{"recordId":"legacy","nodeId":"n",
            "staticPath":["e"],"zoom":1.2,"panX":40.0,"panY":70.0,"hasPan":true},
            "nodePositions":{"n":{"x":20.0,"y":30.0}}}"""
        val store = GraphViewStateStore(values::get) { name, value -> values[name] = value }
        val migrated = store.get(key)
        assertEquals(GraphLayoutPreference.AUTO, migrated.layoutPreference)
        assertEquals(40.0, migrated.viewport(LayoutDirection.VERTICAL)?.panX)
        assertEquals(null, migrated.viewport(LayoutDirection.HORIZONTAL))
        assertEquals(GraphNodePosition(20.0, 30.0), migrated.positions(LayoutDirection.VERTICAL)["n"])
        val horizontal = BrowseState("legacy", "n", listOf("e"), 0.9, 200.0, 300.0, true)
        store.update(key) { it.withViewport(LayoutDirection.HORIZONTAL, horizontal)
            .withPositions(LayoutDirection.HORIZONTAL, mapOf("n" to GraphNodePosition(400.0, 500.0)))
            .copy(layoutPreference = GraphLayoutPreference.HORIZONTAL) }
        val restored = store.get(key)
        assertEquals(40.0, restored.viewport(LayoutDirection.VERTICAL)?.panX)
        assertEquals(200.0, restored.viewport(LayoutDirection.HORIZONTAL)?.panX)
        assertEquals(GraphNodePosition(20.0, 30.0), restored.positions(LayoutDirection.VERTICAL)["n"])
        assertEquals(GraphNodePosition(400.0, 500.0), restored.positions(LayoutDirection.HORIZONTAL)["n"])
    }

    @Test
    fun `each history record and follow up canvas restores its own settings`() {
        val values = mutableMapOf<String, String>()
        fun store() = GraphViewStateStore(values::get) { name, value -> values[name] = value }

        val rootA = GraphLayerKey("history-a")
        val childA = GraphLayerKey("history-a", "node-1")
        val otherChildA = GraphLayerKey("history-a", "node-2")
        val rootB = GraphLayerKey("history-b")
        val childB = GraphLayerKey("history-b", "node-1")
        val first = store()
        val observed = mutableListOf<GraphViewState>()
        val subscription = first.listen(childA, observed::add)
        first.update(rootA) { it.copy(navigationPinned = true, autoFollowNode = false) }
        first.update(childA) {
            it.copy(flowEnabled = false, browse = BrowseState(childA.canvasId, "step-2",
                listOf("edge-1"), 1.5, 120.0, 80.0, true),
                nodePositions = mapOf("step-2" to GraphNodePosition(240.0, 320.0)))
        }
        first.update(childB) { it.copy(autoFollowNode = false) }
        assertEquals(1, observed.size)
        assertFalse(observed.single().flowEnabled)
        subscription.close()
        first.update(childA) { it.copy(navigationPinned = true) }
        assertEquals(1, observed.size)

        val restored = store()
        assertTrue(restored.get(rootA).navigationPinned)
        assertFalse(restored.get(rootA).autoFollowNode)
        assertTrue(restored.get(childA).autoFollowNode)
        assertFalse(restored.get(childA).flowEnabled)
        assertTrue(restored.get(childA).navigationPinned)
        assertEquals("step-2", restored.get(childA).browse?.nodeId)
        assertEquals(listOf("edge-1"), restored.get(childA).browse?.staticPath)
        assertEquals(120.0, restored.get(childA).browse?.panX)
        assertEquals(GraphNodePosition(240.0, 320.0), restored.get(childA).nodePositions["step-2"])
        assertTrue(restored.get(rootA).nodePositions.isEmpty())
        assertEquals(GraphViewState(), restored.get(otherChildA))
        assertEquals(GraphViewState(), restored.get(rootB))
        assertFalse(restored.get(childB).autoFollowNode)
        assertFalse(restored.get(childB).navigationPinned)
    }
}
