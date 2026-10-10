package com.master.lessplain.ide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResultInboxTest {
    @Test
    fun `pending results survive recreation and viewed results cannot be re-enqueued by a retry`() {
        var saved: String? = null
        fun inbox() = ResultInbox({ saved }, { saved = it })
        val first = PendingResult("record:one", "one", null, 1)
        val second = PendingResult("answer:two", null, "question-two", 2)

        assertTrue(inbox().register(first))
        assertTrue(inbox().register(second))
        assertEquals(listOf(first, second), inbox().pending())

        inbox().markViewed(second.key)
        assertEquals(listOf(first), inbox().pending())
        assertFalse(inbox().register(second))
        assertEquals(listOf(first), inbox().pending())
    }

    @Test
    fun `notification state is retained while results stay unread`() {
        var saved: String? = null
        val inbox = ResultInbox({ saved }, { saved = it })
        val first = PendingResult("record:one", "one", null, 1)
        val second = PendingResult("record:two", "two", null, 2)
        inbox.register(first)
        inbox.register(second)
        inbox.markNotified(setOf(first.key, second.key))

        val restarted = ResultInbox({ saved }, { saved = it })
        assertTrue(restarted.pending().all { it.notified })
        restarted.markViewed(second.key)
        assertEquals(listOf(first.key), restarted.pending().map { it.key })
    }

    @Test
    fun `deleting a root clears unread descendant targets`() {
        var saved: String? = null
        val inbox = ResultInbox({ saved }, { saved = it })
        inbox.register(PendingResult("record:root", "root", null, 1))
        inbox.register(PendingResult("record:child", "child", "question", 2))
        inbox.register(PendingResult("record:other", "other", null, 3))
        inbox.removeDeleted(setOf("root", "child"), setOf("question"))
        assertEquals(listOf("record:other"), ResultInbox({ saved }, { saved = it }).pending().map { it.key })
    }

    @Test
    fun `open tool window never sends a notification`() {
        assertEquals(ResultDelivery.OPEN, resultDelivery(toolWindowVisible = true, projectWindowActive = true))
        assertEquals(ResultDelivery.WAIT, resultDelivery(toolWindowVisible = true, projectWindowActive = false))
        assertEquals(ResultDelivery.NOTIFY, resultDelivery(toolWindowVisible = false, projectWindowActive = true))
    }

    @Test
    fun `notification title distinguishes new and updated graphs after restart`() {
        var saved: String? = null
        val inbox = ResultInbox({ saved }, { saved = it })
        inbox.register(PendingResult("record:root", "root", null, 1))
        inbox.register(PendingResult("record:child", "child", "question", 2, isUpdate = true))
        val restored = ResultInbox({ saved }, { saved = it }).pending()

        assertEquals("新增理解图", resultNotificationTitle(restored.take(1)))
        assertEquals("理解图已更新", resultNotificationTitle(restored.takeLast(1)))
        assertEquals("理解图有新增和更新", resultNotificationTitle(restored))
        assertEquals("新增 1 · 更新 1", resultUnreadSummary(restored))
    }
}
