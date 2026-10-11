package com.lessplain.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeleteRecordStateTest {
    @Test fun `dismiss never deletes and target remains fixed across selection changes`() {
        val first = DeleteRecordState("first", "原记录")
        val selected = DeleteRecordState("other", "切换后的记录")
        val deleted = mutableListOf<String>()
        assertTrue(first.confirm(deleted::add))
        assertEquals(listOf("first"), deleted)
        assertFalse(first.confirm(deleted::add))
        selected.cancel()
        assertFalse(selected.confirm(deleted::add))
        assertEquals(listOf("first"), deleted)
    }

    @Test fun `failure keeps confirmation retryable and reentrant acceptance cannot delete twice`() {
        val state = DeleteRecordState("id", "图")
        var calls = 0
        assertFalse(state.confirm { calls++; error("只读") })
        assertEquals("只读", state.error)
        assertFalse(state.busy)
        assertTrue(state.confirm {
            calls++
            assertTrue(state.busy)
            assertFalse(state.confirm { error("不能重复删除") })
        })
        assertEquals(2, calls)
        assertNull(state.error)
        assertFalse(state.busy)
        state.cancel()
        state.cancel()
        assertFalse(state.confirm { error("已关闭") })
    }
}
