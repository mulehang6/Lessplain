package com.master.understanding.ide

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanvasContentDraftsTest {
    @Test fun `new and edited note drafts retain their source node without changing saved counts`() {
        val drafts = CanvasContentDrafts()
        val newNote = noteKey("record-a", "node-a", "")
        val editedNote = noteKey("record-a", "node-a", "question-1")

        assertTrue(drafts.retain(newNote, "新注释草稿"))
        assertTrue(drafts.retain(editedNote, "已有注释的草稿"))

        assertEquals(setOf("record-a" to "node-a"), drafts.nodeKeys())
        assertEquals(
            listOf("" to "新注释草稿", "question-1" to "已有注释的草稿"),
            drafts.forNode("record-a", "node-a").map { it.questionId to it.text },
        )
        assertTrue(drafts.forNode("record-b", "node-a").isEmpty())

        assertTrue(drafts.retain(editedNote, "继续编辑"))
        assertEquals("继续编辑", drafts.forNode("record-a", "node-a").last().text)
        assertEquals(setOf("record-a" to "node-a"), drafts.nodeKeys())
    }

    @Test fun `empty note drafts disappear and only stale question drafts are pruned`() {
        val drafts = CanvasContentDrafts()
        val blankNote = noteKey("record-a", "node-a", "")
        val retainedQuestion = noteKey("record-a", "node-b", "question-pending")
        val staleQuestion = noteKey("record-a", "node-c", "question-answered")
        val title = "title:record-a"

        assertFalse(drafts.retain(blankNote, "   "))
        assertTrue(drafts.retain(blankNote, "临时文字"))
        assertTrue(drafts.retain(blankNote, "   "))
        assertTrue(drafts.nodeKeys().isEmpty())
        assertEquals(null, drafts.text(blankNote))
        drafts.retain(retainedQuestion, "保留")
        drafts.retain(staleQuestion, "过期")
        drafts.retain(title, "标题草稿")

        assertTrue(drafts.removeNoteDrafts { it.questionId == "question-answered" })
        assertEquals(setOf("record-a" to "node-b"), drafts.nodeKeys())
        assertEquals("标题草稿", drafts.text(title))
        assertFalse(drafts.removeNoteDrafts { it.questionId == "missing" })

        drafts.clear()
        assertEquals(null, drafts.text(retainedQuestion))
        assertTrue(drafts.nodeKeys().isEmpty())
    }
}
