package com.lessplain.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoalescingBackgroundWriteTest {
    @Test fun `updates never write on the caller and only latest pending state per record is saved in order`() {
        val reads = mutableListOf<() -> Unit>()
        val completions = mutableListOf<(Result<Unit>) -> Unit>()
        val persisted = mutableListOf<Int>()
        val writer = CoalescingBackgroundWrite<String, Int>(
            { read, done -> reads += read; completions += done; AutoCloseable {} }, persisted::add,
            { throw AssertionError(it) },
        )
        writer.submit("a", 1)
        writer.submit("a", 2)
        writer.submit("a", 3)
        writer.submit("b", 4)
        assertTrue(persisted.isEmpty())
        assertEquals(1, reads.size)
        completions[0](runCatching(reads[0]))
        completions[1](runCatching(reads[1]))
        assertEquals(listOf(1, 3, 4), persisted)
        writer.close()
    }
}
