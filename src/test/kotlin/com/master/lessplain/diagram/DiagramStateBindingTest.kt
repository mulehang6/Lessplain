package com.master.lessplain.diagram

import com.master.lessplain.ide.GraphLayerKey
import com.master.lessplain.ide.GraphLayoutPreference
import com.master.lessplain.ide.GraphViewStateStore
import com.master.lessplain.model.BrowseState
import com.master.lessplain.model.GraphNode
import com.master.lessplain.model.UnderstandingGraph
import com.master.lessplain.model.UnderstandingRecord
import java.awt.Toolkit
import java.awt.event.ComponentEvent
import javax.swing.SwingUtilities
import javax.swing.Timer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagramStateBindingTest {
    private val record = UnderstandingRecord("shared", 1, null, null, "测试", "", null,
        UnderstandingGraph(listOf(GraphNode("a", "A")), emptyList()), 0)

    @Test
    fun `two live canvases apply zoom and pan in both directions without writing feedback`() {
        SwingUtilities.invokeAndWait {
            val values = mutableMapOf<String, String>()
            var writes = 0
            val store = GraphViewStateStore(values::get) { name, value -> values[name] = value; writes++ }
            val key = GraphLayerKey(record.id)
            store.update(key) { it.copy(autoFollowNode = false, layoutPreference = GraphLayoutPreference.VERTICAL) }
            val first = createUnderstandingDiagram(record)
            val second = createUnderstandingDiagram(record)
            try {
                for (diagram in listOf(first, second)) {
                    diagram.bindGraphState(store, key)
                    diagram.setSize(700, 500)
                    diagram.dispatchEvent(ComponentEvent(diagram, ComponentEvent.COMPONENT_RESIZED))
                }
                await { first.layoutReady && second.layoutReady }
                for (diagram in listOf(first, second)) {
                    diagram.onViewportChanged { saveGraphViewState(store, key, diagram, emptyList(), "a") }
                }
                val before = writes
                first.zoom = 1.4
                first.centerX = 400.0
                first.centerY = 200.0
                await { writes > before }
                assertEquals(first.zoom, second.zoom)
                assertEquals(first.center, second.center)
                drainViewportTimers()
                assertEquals(before + 1, writes)

                second.zoom = 0.8
                second.centerX = 100.0
                second.centerY = 600.0
                await { writes > before + 1 }
                assertEquals(second.zoom, first.zoom)
                assertEquals(second.center, first.center)
                drainViewportTimers()
                assertEquals(before + 2, writes)

                first.cameraMotion.retarget(first.centerX, first.centerY, 900.0, 900.0, System.nanoTime())
                first.cameraTimer.start()
                saveGraphViewState(store, key, first, emptyList(), "a")
                assertTrue(first.cameraTimer.isRunning, "发布自身视口不应停止进行中的节点跟随动画")
                first.cameraTimer.stop()
            } finally { first.dispose(); second.dispose() }
        }
    }

    @Test
    fun `inactive direction viewport is cached and applied only when that direction is laid out`() {
        SwingUtilities.invokeAndWait {
            val diagram = createUnderstandingDiagram(record)
            try {
                diagram.setSize(700, 500)
                diagram.dispatchEvent(ComponentEvent(diagram, ComponentEvent.COMPONENT_RESIZED))
                await { diagram.layoutReady }
                val before = diagram.center
                val horizontal = BrowseState(record.id, "a", zoom = 1.5, panX = 800.0, panY = 500.0, hasPan = true)
                diagram.setDirectionState(LayoutDirection.HORIZONTAL, horizontal, emptyMap())
                assertEquals(before, diagram.center)
                diagram.setLayoutPreference(GraphLayoutPreference.HORIZONTAL)
                await { diagram.activeDirection == LayoutDirection.HORIZONTAL }
                assertEquals(1.5, diagram.zoom)
                assertEquals(800.0, diagram.centerX)
                assertEquals(500.0, diagram.centerY)
            } finally { diagram.dispose() }
        }
    }

    private fun drainViewportTimers() {
        var drained = false
        Timer(350) { drained = true }.apply { isRepeats = false; start() }
        await { drained }
    }

    private fun await(done: () -> Boolean) {
        if (done()) return
        val loop = Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
        val started = System.nanoTime()
        var timedOut = false
        val timer = Timer(5) {
            if (done()) loop.exit()
            else if (System.nanoTime() - started > 5_000_000_000L) { timedOut = true; loop.exit() }
        }
        timer.start()
        try { loop.enter() } finally { timer.stop() }
        assertFalse(timedOut, "视图状态同步超时")
    }
}
