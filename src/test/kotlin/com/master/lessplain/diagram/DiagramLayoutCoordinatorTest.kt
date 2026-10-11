package com.master.lessplain.diagram

import java.awt.geom.Rectangle2D
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 字体重新测量与后台布局交错时，过期结果不能覆盖新请求或污染其缓存。 */
class DiagramLayoutCoordinatorTest {
    @Test fun `measurement change discards a running layout and its late cache entry`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val computations = AtomicInteger()
        val observed = AtomicReference<Result<UnderstandingGraphLayout>>()
        val coordinator = DiagramLayoutCoordinator(
            compute = {
                val number = computations.incrementAndGet()
                if (number == 1) {
                    started.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "等待重测请求超时" }
                }
                UnderstandingGraphLayout(emptyMap(), emptyList(), Rectangle2D.Double(0.0, 0.0, number.toDouble(), 1.0))
            },
            onResult = { _, result ->
                observed.set(result)
                completed.countDown()
            },
        )
        try {
            SwingUtilities.invokeAndWait { coordinator.request(LayoutDirection.VERTICAL) }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            SwingUtilities.invokeAndWait {
                coordinator.invalidateMeasurements()
                coordinator.request(LayoutDirection.VERTICAL)
            }
            release.countDown()
            assertTrue(completed.await(10, TimeUnit.SECONDS))
            assertEquals(2, computations.get())
            assertEquals(2.0, observed.get().getOrThrow().bounds.width)
        } finally {
            release.countDown()
            SwingUtilities.invokeAndWait { coordinator.close() }
        }
    }
}
