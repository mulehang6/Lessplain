package com.lessplain.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LatestBackgroundReadTest {
    private class Queue<T> {
        val work = mutableListOf<() -> T>()
        val finish = mutableListOf<(Result<T>) -> Unit>()
        val published = mutableListOf<Result<T>>()
        val reader = LatestBackgroundRead<T>({ read, done -> work += read; finish += done }, published::add)
        fun complete(index: Int) = finish[index](runCatching(work[index]))
    }

    @Test fun `read is deferred coalesced and stale result is never published`() {
        val q = Queue<String>()
        var reads = 0
        q.reader.request { reads++; "第一张图" }
        q.reader.request { reads++; "第二张图" }
        q.reader.request { reads++; "第三张图" }
        assertEquals(0, reads)
        assertEquals(1, q.work.size)
        q.complete(0)
        assertTrue(q.published.isEmpty())
        assertEquals(2, q.work.size)
        q.complete(1)
        assertEquals(2, reads)
        assertEquals(listOf("第三张图"), q.published.map { it.getOrThrow() })
        q.reader.close()
    }

    @Test fun `close discards in flight and queued work`() {
        val q = Queue<Int>()
        q.reader.request { 1 }
        q.reader.request { 2 }
        q.reader.close()
        q.complete(0)
        q.reader.request { 3 }
        assertEquals(1, q.work.size)
        assertTrue(q.published.isEmpty())
    }

    @Test fun `latest failure is published and later refresh recovers`() {
        val q = Queue<Int>()
        q.reader.request { error("数据库暂时不可读") }
        q.complete(0)
        assertEquals("数据库暂时不可读", q.published.single().exceptionOrNull()?.message)
        q.reader.request { 7 }
        q.complete(1)
        assertEquals(7, q.published.last().getOrThrow())
        q.reader.close()
    }
}
