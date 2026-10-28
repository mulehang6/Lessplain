package com.lessplain.core

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class ConfirmedWritesTest {
    @Test fun `closing drains accepted writes in order and suppresses late UI delivery`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callbacks = CopyOnWriteArrayList<() -> Unit>()
        val committed = CopyOnWriteArrayList<Int>()
        val queue = ConfirmedWrites { callbacks += it }
        var delivered = 0
        queue.dispatch({ started.countDown(); check(release.await(5, TimeUnit.SECONDS)); committed += 1 }) { delivered++ }
        queue.dispatch({ committed += 2 }) { delivered++ }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        Executors.newSingleThreadExecutor().use { closer ->
            val closing = closer.submit { queue.close() }
            try {
                assertFalse(closing.isDone)
                release.countDown()
                closing.get(5, TimeUnit.SECONDS)
                assertEquals(listOf(1, 2), committed.toList())
                callbacks.forEach { it() }; assertEquals(0, delivered)
                assertFailsWith<IllegalStateException> { queue.dispatch({}, {}) }
            } finally { release.countDown() }
        }
    }
}
