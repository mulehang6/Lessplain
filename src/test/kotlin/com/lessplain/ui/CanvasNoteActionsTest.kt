package com.lessplain.ui

import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities
import kotlin.test.*

/** 原生问题弹层在离屏 Swing 组件中验证，不打开 IDE 或窗口。 */
class CanvasNoteActionsTest {
    @Test fun directDeleteIsSeparateFromCancelAndFailureKeepsInput() {
        SwingUtilities.invokeAndWait {
            var deletes = 0
            var cancelled = 0
            val draft = CanvasTextDraft("原文")
            val editor = CanvasTextEditor("问题", draft, true, {}, {}, {}, { cancelled++ },
                delete = { deletes++; error("删除失败") }, cancelLabel = "取消编辑")
            try {
                editor.input.text = "正在编辑"
                assertTrue(editor.deleteButton.isVisible)
                editor.deleteButton.doClick()
                assertEquals(1, deletes)
                assertEquals(0, cancelled)
                assertTrue(editor.input.isEditable)
                assertEquals("正在编辑", editor.input.text)
                assertEquals("删除失败", editor.error.text)
                editor.cancelButton.doClick()
                assertEquals(1, deletes)
                assertEquals(1, cancelled)
            } finally { editor.close() }
        }
    }

    @Test fun externalAnswerLocksQueuedSaveAndKeepsDraftText() {
        lateinit var editor: CanvasTextEditor
        val draft = CanvasTextDraft("原文")
        var writes = 0
        SwingUtilities.invokeAndWait {
            editor = CanvasTextEditor("问题", draft, true, { writes++ }, {}, {}, {}, delete = { writes++ })
            editor.input.text = "当前草稿"
            editor.requestSave()
            editor.lockEditing()
            editor.requestDelete()
        }
        SwingUtilities.invokeAndWait {
            try {
                assertEquals(0, writes)
                assertEquals("当前草稿", draft.text)
                assertFalse(editor.input.isEditable)
                assertFalse(editor.saveButton.isVisible)
                assertFalse(editor.deleteButton.isVisible)
                assertEquals("关闭", editor.cancelButton.text)
            } finally { editor.close() }
        }
    }

    @Test fun asyncFailureCannotReenableExternallyLockedEditor() {
        SwingUtilities.invokeAndWait {
            var completion: ((Result<Unit>) -> Unit)? = null
            val editor = CanvasTextEditor("问题", CanvasTextDraft("内容"), true, {}, {}, {}, {},
                saveDispatch = { _, complete -> completion = complete; AutoCloseable {} }, delete = {})
            try {
                editor.requestDelete()
                editor.lockEditing()
                completion!!(Result.failure(IllegalStateException("失败")))
                assertFalse(editor.input.isEditable)
                assertFalse(editor.deleteButton.isEnabled)
                assertEquals("内容", editor.input.text)
            } finally { editor.close() }
        }
    }
}
