package com.lessplain.core

import com.intellij.openapi.progress.ProcessCanceledException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class BackgroundTasksTest {
    @Test fun `view cancellation interrupts actual work and does not publish`() {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val posted = LinkedBlockingQueue<() -> Unit>()
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val published = AtomicBoolean()
        BackgroundTasks(parent, posted::add).use { tasks ->
            val work = tasks.dispatch({
                assertFalse(SwingUtilities.isEventDispatchThread())
                started.countDown()
                try { CountDownLatch(1).await() } finally { interrupted.countDown() }
            }) { published.set(true) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            work.close()
            assertTrue(interrupted.await(5, TimeUnit.SECONDS))
            posted.forEach { it() }
            assertFalse(published.get())
        }
        parent.cancel()
    }

    @Test fun `project disposal invalidates a completion already queued for the UI`() {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val posted = LinkedBlockingQueue<() -> Unit>()
        val tasks = BackgroundTasks(parent, posted::add)
        var published = false
        tasks.dispatch({ 42 }) { published = true }
        val completion = requireNotNull(posted.poll(5, TimeUnit.SECONDS))
        parent.cancel()
        completion()
        assertFalse(published)
        tasks.close()
    }

    @Test fun `ordinary completion survives worker completion but explicit view close invalidates it`() {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val posted = LinkedBlockingQueue<() -> Unit>()
        BackgroundTasks(parent, posted::add).use { tasks ->
            var value = 0
            tasks.dispatch({ 7 }) { value = it.getOrThrow() }
            requireNotNull(posted.poll(5, TimeUnit.SECONDS))()
            assertTrue(value == 7)
            val task = tasks.dispatch({ 9 }) { value = it.getOrThrow() }
            val completion = requireNotNull(posted.poll(5, TimeUnit.SECONDS))
            task.close()
            completion()
            assertTrue(value == 7)
        }
        parent.cancel()
    }

    @Test fun `platform cancellation escapes error conversion unchanged`() {
        val cancelled = ProcessCanceledException()
        assertSame(cancelled, assertFailsWith<ProcessCanceledException> { cancellationAwareResult<Unit> { throw cancelled } })
    }
}
