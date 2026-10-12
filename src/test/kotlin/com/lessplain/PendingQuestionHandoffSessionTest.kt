package com.lessplain

import com.lessplain.core.PendingQuestionCopy
import com.lessplain.core.PendingQuestionHandoffSession
import com.lessplain.storage.PendingNoteScope
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingQuestionHandoffSessionTest {
    private class Harness {
        var scope = PendingNoteScope("root", 0)
        var reads = mutableListOf<String>()
        var callback: (() -> Unit)? = null
        var work: (() -> PendingQuestionCopy)? = null
        var complete: ((Result<PendingQuestionCopy>) -> Unit)? = null
        var count = -1
        var enabled = true
        var tooltipError: String? = null
        var clipboard = "原剪贴板"
        var scopeFailure = false
        var readFailure = false
        var writeFailure = false
        var readCount = 2
        val notices = mutableListOf<Pair<String, Boolean>>()
        val session = PendingQuestionHandoffSession(
            loadScope = { if (scopeFailure) error("范围不存在"); scope },
            loadCopy = { id -> reads += id; if (readFailure) error("读取失败"); PendingQuestionCopy(id, readCount) },
            scopeDispatch = { read, done -> done(runCatching(read)); AutoCloseable { } },
            subscribe = { listener -> callback = listener; AutoCloseable { callback = null } },
            dispatch = { read, done -> work = read; complete = done; AutoCloseable { } },
            write = { copy -> if (writeFailure) error("剪贴板不可用"); clipboard = copy.text },
            updated = { total, available, error -> count = total; enabled = available; tooltipError = error },
            notify = { message, error -> notices += message to error },
        )
        fun finish() { complete!!(runCatching { work!!() }) }
        fun change(value: PendingNoteScope) { scope = value; callback?.invoke() }
    }

    @Test fun `count refresh uses lightweight reads and click fixes scope throughout background work`() {
        val h = Harness()
        assertEquals(0, h.count)
        assertFalse(h.enabled)
        h.session.copy()
        assertTrue(h.reads.isEmpty())
        h.change(PendingNoteScope("root", 2))
        assertTrue(h.enabled)
        assertTrue(h.reads.isEmpty(), "数量刷新不能读取完整批次")
        h.session.copy()
        assertFalse(h.enabled)
        h.change(PendingNoteScope("another", 7))
        assertFalse(h.enabled)
        h.session.copy()
        h.finish()
        assertEquals(listOf("root"), h.reads)
        assertEquals("root", h.clipboard)
        assertEquals(listOf("已复制 2 条待答注释" to false), h.notices)
        assertEquals(7, h.count)
        assertTrue(h.enabled)
        h.change(PendingNoteScope("another", 0))
        assertFalse(h.enabled)
        h.session.close()
        assertEquals(null, h.callback)
    }

    @Test fun `read write and missing scope failures preserve clipboard and permit retry`() {
        for (writeFailure in listOf(false, true)) {
            val h = Harness()
            h.change(PendingNoteScope("root", 1))
            h.readFailure = !writeFailure
            h.writeFailure = writeFailure
            h.session.copy()
            h.finish()
            assertEquals("原剪贴板", h.clipboard)
            assertTrue(h.notices.single().second)
            assertTrue(h.enabled)
            h.readFailure = false
            h.writeFailure = false
            h.session.copy()
            h.finish()
            assertEquals("root", h.clipboard)
            h.scopeFailure = true
            h.callback!!()
            assertFalse(h.enabled)
            assertEquals("范围不存在", h.tooltipError)
            h.session.close()
        }
    }

    @Test fun `empty latest batch and closed canvas never overwrite clipboard`() {
        val h = Harness()
        h.change(PendingNoteScope("root", 1))
        h.readCount = 0
        h.session.copy()
        h.finish()
        assertEquals("原剪贴板", h.clipboard)
        assertEquals("没有待答注释" to false, h.notices.single())
        h.session.copy()
        h.session.close()
        h.readCount = 2
        h.finish()
        assertEquals("原剪贴板", h.clipboard)
        assertEquals(1, h.notices.size)
        assertFalse(h.enabled)
        assertEquals(null, h.callback)
    }

    @Test fun `scope count loads asynchronously coalesces refresh and never publishes after close`() {
        var changed: (() -> Unit)? = null
        var read: (() -> PendingNoteScope)? = null
        var done: ((Result<PendingNoteScope>) -> Unit)? = null
        var reads = 0
        var count = -1
        var enabled = true
        var current = PendingNoteScope("root", 2)
        var copies = 0
        val session = PendingQuestionHandoffSession(
            loadScope = { reads++; current },
            loadCopy = { copies++; PendingQuestionCopy(it, 1) },
            scopeDispatch = { work, complete -> read = work; done = complete; AutoCloseable { } },
            subscribe = { callback -> changed = callback; AutoCloseable { changed = null } },
            dispatch = { work, complete -> complete(runCatching(work)); AutoCloseable { } },
            write = { },
            updated = { total, available, _ -> count = total; enabled = available },
            notify = { _, _ -> },
        )
        assertEquals(0, reads, "初始化不应同步读库")
        assertEquals(0, count)
        assertFalse(enabled)
        session.copy()
        assertEquals(0, copies)
        changed!!()
        changed!!()
        val stale = runCatching { read!!() }
        done!!(stale)
        assertEquals(0, count, "已被刷新替换的旧结果不能显示")
        current = PendingNoteScope("root", 5)
        done!!(runCatching { read!!() })
        assertEquals(2, reads, "连续刷新应合并为一次后续读取")
        assertEquals(5, count)
        assertTrue(enabled)
        changed!!()
        session.close()
        done!!(runCatching { read!!() })
        assertEquals(0, count)
        assertFalse(enabled)
        assertEquals(null, changed)
    }
}
