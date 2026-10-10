package com.master.lessplain.diagram

import com.master.lessplain.ide.GraphLayoutPreference
import com.master.lessplain.model.GraphEdge
import com.master.lessplain.model.GraphNode
import com.master.lessplain.model.UnderstandingGraph
import com.master.lessplain.model.UnderstandingRecord
import java.awt.Toolkit
import java.awt.event.ComponentEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class DiagramAsyncLayoutTest {
    private val graph = UnderstandingGraph(
        listOf(GraphNode("a", "开始"), GraphNode("b", "结束")),
        listOf(GraphEdge("ab", "a", "b", "control_flow")),
    )
    private val record = UnderstandingRecord("async", 1, null, null, "测试", "", null, graph, 0)

    @Test
    fun `first valid size starts layout without manual arrange in either setup order`() {
        SwingUtilities.invokeAndWait {
            for (preferenceBeforeSize in listOf(false, true)) {
                val diagram = createUnderstandingDiagram(record)
                try {
                    if (preferenceBeforeSize) diagram.setLayoutPreference(GraphLayoutPreference.HORIZONTAL)
                    diagram.setSize(if (preferenceBeforeSize) 700 else 1000, 500)
                    if (!preferenceBeforeSize) diagram.setLayoutPreference(GraphLayoutPreference.AUTO)
                    await { diagram.layoutReady }
                    assertEquals(LayoutDirection.HORIZONTAL, diagram.activeDirection)
                    assertTrue(diagram.initializedViewport)
                    assertEquals(graph.nodes.size, diagram.layout.nodes.size)
                } finally { diagram.dispose() }
            }
        }
    }

    @Test
    fun `arrange preserves automatic direction and explicit direction wins`() {
        SwingUtilities.invokeAndWait {
            val diagram = createUnderstandingDiagram(record)
            try {
                assertFalse(diagram.layoutReady)
                resize(diagram, 700)
                await { diagram.layoutReady && diagram.activeDirection == LayoutDirection.VERTICAL }
                resize(diagram, 1000)
                assertEquals(LayoutDirection.VERTICAL, diagram.activeDirection)
                diagram.arrangeNodes()
                await { !diagram.layoutBusy }
                assertEquals(LayoutDirection.VERTICAL, diagram.activeDirection)
                diagram.setLayoutPreference(GraphLayoutPreference.VERTICAL)
                await { diagram.layoutReady && diagram.activeDirection == LayoutDirection.VERTICAL }
            } finally { diagram.dispose() }
        }
    }

    @Test
    fun `arrange clears only positions in the visible direction and leaves preference unchanged`() {
        SwingUtilities.invokeAndWait {
            for (preference in GraphLayoutPreference.entries) {
                val diagram = createUnderstandingDiagram(record)
                try {
                    diagram.setLayoutPreference(preference)
                    resize(diagram, 1000)
                    await { diagram.layoutReady }
                    val direction = diagram.activeDirection
                    val other = if (direction == LayoutDirection.HORIZONTAL) LayoutDirection.VERTICAL else LayoutDirection.HORIZONTAL
                    val saved = mapOf("a" to com.master.lessplain.ide.GraphNodePosition(900.0, 800.0))
                    diagram.setDirectionState(other, null, saved)
                    diagram.setNodePositions(saved)
                    var cleared = 0
                    diagram.onNodePositionsChanged { if (it.isEmpty()) cleared++ }
                    resize(diagram, 500)
                    diagram.arrangeNodes()
                    await { !diagram.layoutBusy }
                    assertEquals(direction, diagram.activeDirection)
                    assertEquals(preference, diagram.layoutPreference)
                    assertTrue(diagram.nodePositions.isEmpty(), "$preference/$direction 整理后仍保留 ${diagram.nodePositions}")
                    assertEquals(1, cleared)
                    diagram.setLayoutPreference(if (other == LayoutDirection.VERTICAL) GraphLayoutPreference.VERTICAL else GraphLayoutPreference.HORIZONTAL)
                    await { diagram.activeDirection == other }
                    assertEquals(saved, diagram.nodePositions)
                } finally { diagram.dispose() }
            }
        }
    }

    @Test
    fun `stale result is discarded and failure can retry`() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val attempts = AtomicInteger()
        SwingUtilities.invokeAndWait {
            val diagram = UnderstandingDiagram(record, null, layoutComputer = { direction ->
                when (attempts.incrementAndGet()) {
                    1 -> {
                        firstStarted.countDown()
                        releaseFirst.await(5, TimeUnit.SECONDS)
                        layoutUnderstandingGraph(graph, direction)
                    }
                    2 -> error("测试失败")
                    else -> layoutUnderstandingGraph(graph, direction)
                }
            })
            try {
                resize(diagram, 700)
                assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
                diagram.setLayoutPreference(GraphLayoutPreference.HORIZONTAL)
                releaseFirst.countDown()
                await { diagram.layoutFailure != null }
                assertFalse(diagram.layoutReady)
                assertTrue(diagram.retryFailedLayout())
                await { diagram.layoutReady && diagram.activeDirection == LayoutDirection.HORIZONTAL }
                assertEquals(3, attempts.get())
            } finally {
                releaseFirst.countDown()
                diagram.dispose()
            }
        }
    }

    @Test
    fun `layout preference changed at zero size is applied after an initialized canvas resizes`() {
        SwingUtilities.invokeAndWait {
            val diagram = createUnderstandingDiagram(record)
            try {
                resize(diagram, 700)
                await { diagram.layoutReady }
                resize(diagram, 0)
                diagram.setLayoutPreference(GraphLayoutPreference.HORIZONTAL)
                assertTrue(diagram.layoutRequestPending)
                assertEquals(LayoutDirection.VERTICAL, diagram.activeDirection)
                resize(diagram, 700)
                await { !diagram.layoutBusy && diagram.activeDirection == LayoutDirection.HORIZONTAL }
                assertFalse(diagram.layoutRequestPending)
                assertTrue(diagram.initializedViewport)
            } finally { diagram.dispose() }
        }
    }

    @Test
    fun `only the latest preference deferred at zero size is applied`() {
        SwingUtilities.invokeAndWait {
            val diagram = createUnderstandingDiagram(record)
            try {
                resize(diagram, 1000)
                await { diagram.layoutReady }
                resize(diagram, 0)
                diagram.setLayoutPreference(GraphLayoutPreference.VERTICAL)
                diagram.setLayoutPreference(GraphLayoutPreference.HORIZONTAL)
                resize(diagram, 700)
                await { !diagram.layoutBusy && !diagram.layoutRequestPending }
                assertEquals(GraphLayoutPreference.HORIZONTAL, diagram.layoutPreference)
                assertEquals(LayoutDirection.HORIZONTAL, diagram.activeDirection)
            } finally { diagram.dispose() }
        }
    }

    @Test
    fun `disposing coordinator interrupts work and drops its result`() {
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val delivered = AtomicInteger()
        val coordinator = DiagramLayoutCoordinator(
            compute = {
                started.countDown()
                try {
                    CountDownLatch(1).await(5, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    interrupted.countDown()
                }
                layoutUnderstandingGraph(graph, it)
            },
            onResult = { _, _ -> delivered.incrementAndGet() },
        )
        SwingUtilities.invokeAndWait { coordinator.request(LayoutDirection.VERTICAL) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        SwingUtilities.invokeAndWait { coordinator.close() }
        assertTrue(interrupted.await(5, TimeUnit.SECONDS))
        SwingUtilities.invokeAndWait { }
        assertEquals(0, delivered.get())
    }

    private fun resize(diagram: UnderstandingDiagram, width: Int) {
        diagram.setSize(width, 500)
        diagram.dispatchEvent(ComponentEvent(diagram, ComponentEvent.COMPONENT_RESIZED))
    }

    private fun await(done: () -> Boolean) {
        if (done()) return
        val loop = Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
        val started = System.nanoTime()
        var timedOut = false
        val timer = Timer(5) {
            if (done()) loop.exit()
            else if (System.nanoTime() - started > 5_000_000_000L) {
                timedOut = true
                loop.exit()
            }
        }
        timer.start()
        loop.enter()
        timer.stop()
        assertFalse(timedOut, "布局计算超时")
    }
}
