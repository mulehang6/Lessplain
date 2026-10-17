package com.lessplain.experiment

import com.lessplain.experiment.ide.SourceQuestionDrafts
import com.lessplain.experiment.model.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class SourceQuestionDraftsTest {
    @Test fun deletionDiscardsOnlyExistingNoteEditAndCancellationNeverDeletes() {
        val drafts = SourceQuestionDrafts()
        val origin = ViewOrigin(viewRef = "view:1")
        val note = ViewNote("note:1@1", "question", "已保存", origin, createdAt = 1)
        val selection = drafts.selection(origin).apply { text = "新选区问题" }
        val edit = drafts.edit(note).apply { text = "编辑旧问题" }
        drafts.closeEdit(note.ref, edit)
        assertSame(edit, drafts.edit(note))
        drafts.retainPending(AppSnapshot(emptyList(), emptyList(), emptyMap()))
        assertEquals("已保存", drafts.edit(note).text)
        assertSame(selection, drafts.selection(origin))
        assertEquals("新选区问题", selection.text)
        val cancelledEdit = drafts.edit(note).apply { retain = false }
        drafts.closeEdit(note.ref, cancelledEdit)
        assertNotSame(cancelledEdit, drafts.edit(note))
    }
}
