package com.lessplain.core

import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackgroundResourceTest {
    @Test fun `failed initialization can recover without recreating the resource owner`() {
        var attempts = 0
        var closed = 0
        val resource = BackgroundResource {
            check(++attempts > 1) { "temporary failure" }
            AutoCloseable { closed++ }
        }
        assertFailsWith<IllegalStateException> { resource.get() }
        resource.get()
        resource.get()
        assertEquals(2, attempts)
        resource.close()
        assertEquals(1, closed)
    }

    @Test fun `close while initialization is blocked returns and the late resource is disposed once`() {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val closed = AtomicInteger()
        val resource = BackgroundResource {
            started.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
            AutoCloseable { closed.incrementAndGet() }
        }
        Executors.newSingleThreadExecutor().use { executor ->
            val initialization = executor.submit { assertFailsWith<CancellationException> { resource.get() } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            resource.close()
            assertEquals(0, closed.get())
            finish.countDown()
            initialization.get(5, TimeUnit.SECONDS)
        }
        resource.close()
        assertEquals(1, closed.get())
        assertFailsWith<CancellationException> { resource.get() }
    }

    @Test fun `resource is initialized once and closing an unused resource does not initialize it`() {
        var created = 0
        var closed = 0
        val resource = BackgroundResource { created++; AutoCloseable { closed++ } }
        resource.get()
        resource.get()
        assertEquals(1, created)
        resource.close()
        resource.close()
        assertEquals(1, closed)
        BackgroundResource { created++; AutoCloseable {} }.close()
        assertEquals(1, created)
    }
}
