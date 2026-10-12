package com.lessplain.ui

import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 离屏事件测试覆盖后台保存成功、失败和关闭，不创建弹层窗口。 */
class CanvasTextEditorAsyncTest {
    @Test fun `failed save keeps text and popup open while successful retry clears the draft once`() {
        val draft = CanvasTextDraft("保留的中文")
        lateinit var editor: CanvasTextEditor
        lateinit var complete: (Result<Unit>) -> Unit
        var saves = 0
        var closed = 0
        SwingUtilities.invokeAndWait {
            editor = CanvasTextEditor("注释", draft, true, { saves++ }, { closed++ }, {}, {},
                saveDispatch = { _, done -> complete = done; AutoCloseable {} })
            editor.requestSave()
        }
        SwingUtilities.invokeAndWait {
            assertTrue(draft.retain)
            assertFalse(editor.saveButton.isEnabled)
            assertEquals(0, closed)
            complete(Result.failure(IllegalStateException("数据库暂时不可写")))
            assertTrue(draft.retain)
            assertEquals("保留的中文", editor.input.text)
            assertEquals("数据库暂时不可写", editor.error.text)
            assertTrue(editor.saveButton.isEnabled)
            editor.requestSave()
        }
        SwingUtilities.invokeAndWait {
            complete(Result.success(Unit))
            assertFalse(draft.retain)
            assertEquals(1, closed)
            assertEquals(0, saves, "假调度器尚未执行保存工作，不能在界面线程偷偷执行")
            editor.close()
        }
    }

    @Test fun `closing editor cancels its save and ignores late completion`() {
        val draft = CanvasTextDraft("草稿")
        lateinit var editor: CanvasTextEditor
        lateinit var complete: (Result<Unit>) -> Unit
        var cancelled = 0
        var saved = 0
        SwingUtilities.invokeAndWait {
            editor = CanvasTextEditor("注释", draft, true, {}, { saved++ }, {}, {},
                saveDispatch = { _, done -> complete = done; AutoCloseable { cancelled++ } })
            editor.requestSave()
        }
        SwingUtilities.invokeAndWait {
            editor.close()
            complete(Result.success(Unit))
            assertEquals(1, cancelled)
            assertEquals(0, saved)
            assertTrue(draft.retain)
        }
    }
}
