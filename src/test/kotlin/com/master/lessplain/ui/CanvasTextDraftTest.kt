package com.master.lessplain.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanvasTextDraftTest {
    @Test fun `dismiss retains a draft while successful save and explicit cancel discard it`() {
        val draft = CanvasTextDraft("初始内容")
        draft.text += "\n第二行"
        assertTrue(draft.retain)
        assertEquals("初始内容\n第二行", draft.text)
        var saved = ""
        assertTrue(draft.submit { saved = it })
        assertEquals("初始内容\n第二行", saved)
        assertFalse(draft.retain)
        val cancelled = CanvasTextDraft("丢弃")
        cancelled.retain = false
        assertFalse(cancelled.retain)
    }

    @Test fun `blank text and revision conflict keep the editor contents available`() {
        val blank = CanvasTextDraft(" \n ")
        assertFalse(blank.submit { error("不能调用保存") })
        assertTrue(blank.retain)
        val conflict = CanvasTextDraft("正在编辑")
        assertFalse(conflict.submit { throw IllegalArgumentException("记录版本已变化") })
        assertEquals("记录版本已变化", conflict.error)
        assertEquals("正在编辑", conflict.text)
        assertTrue(conflict.retain)
    }

    @Test fun `preedit is active until all characters are committed or composition is cancelled`() {
        val state = EditorComposition()
        state.changed(3, 0)
        assertTrue(state.active)
        state.changed(3, 1)
        assertTrue(state.active)
        state.changed(2, 2)
        assertFalse(state.active)
        state.changed(4, 0)
        state.changed(0, 0)
        assertFalse(state.active)
    }
}
