package com.lessplain.experiment.ide

import com.lessplain.experiment.model.AppSnapshot
import com.lessplain.experiment.model.ViewNote
import com.lessplain.experiment.model.ViewOrigin
import com.lessplain.ui.CanvasTextDraft

/** 仅在 EDT 使用的原生草稿。新选区草稿与待答编辑分开，删除旧注释不影响新输入。 */
internal class SourceQuestionDrafts {
    private val selections = mutableMapOf<ViewOrigin, CanvasTextDraft>()
    private val edits = mutableMapOf<String, CanvasTextDraft>()

    fun selection(origin: ViewOrigin): CanvasTextDraft = selections.getOrPut(origin) { CanvasTextDraft("") }
    fun edit(note: ViewNote): CanvasTextDraft = edits.getOrPut(note.ref) { CanvasTextDraft(note.text) }
    fun closeSelection(origin: ViewOrigin, draft: CanvasTextDraft) {
        if (!draft.retain) selections.remove(origin, draft)
    }
    fun closeEdit(ref: String, draft: CanvasTextDraft) {
        if (!draft.retain) edits.remove(ref, draft)
    }
    fun retainPending(snapshot: AppSnapshot) {
        val pending = snapshot.notes.filter { it.status == "pending" }.mapTo(mutableSetOf()) { it.ref }
        edits.keys.retainAll(pending)
    }
    fun clear() {
        selections.clear()
        edits.clear()
    }
}
