package com.lessplain.ui

import org.junit.jupiter.api.Test
import com.lessplain.core.BackgroundTasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundReadTest {
    @Test fun `slow read does not block EDT and result is published on EDT`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val responsive = CountDownLatch(1)
        var background = false
        var publishedOnEdt = false
        var value = 0
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tasks = BackgroundTasks(parent) { SwingUtilities.invokeLater(it) }
        try {
            SwingUtilities.invokeAndWait {
                tasks.dispatch({
                    background = !SwingUtilities.isEventDispatchThread()
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    42
                }) {
                    publishedOnEdt = SwingUtilities.isEventDispatchThread()
                    value = it.getOrThrow()
                    delivered.countDown()
                }
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            SwingUtilities.invokeLater { responsive.countDown() }
            assertTrue(responsive.await(5, TimeUnit.SECONDS), "后台读库未完成时界面线程仍能处理事件")
            assertFalse(delivered.count == 0L)
            release.countDown()
            assertTrue(delivered.await(10, TimeUnit.SECONDS))
            assertTrue(background)
            assertTrue(publishedOnEdt)
            assertEquals(42, value)
        } finally { release.countDown(); tasks.close(); parent.cancel() }
    }
}
