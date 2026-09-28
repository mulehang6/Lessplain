package com.master.understanding.ide

import com.master.understanding.model.BrowseState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphViewStateStoreTest {
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
