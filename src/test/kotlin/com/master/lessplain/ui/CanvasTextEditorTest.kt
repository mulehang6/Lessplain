package com.master.lessplain.ui

import org.junit.jupiter.api.Test
import java.awt.event.ActionEvent
import java.awt.event.InputMethodEvent
import java.awt.Font
import java.text.AttributedString
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.text.AbstractDocument
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises the native document and shortcut layer without opening an IDE or popup window. */
class CanvasTextEditorTest {
    @Test fun `single line title editor grows with native font and visible error instead of using a fixed height`() {
        val original = UIManager.getFont("Label.font")
        try {
            SwingUtilities.invokeAndWait {
                UIManager.put("Label.font", Font(Font.DIALOG, Font.PLAIN, 32))
                val editor = CanvasTextEditor("标题", CanvasTextDraft("标题"), false, {}, {}, {}, {})
                try {
                    val base = editor.preferredSizeFor(420, Int.MAX_VALUE)
                    assertTrue(base.height >= editor.input.preferredSize.height + editor.saveButton.preferredSize.height +
                        editor.insets.top + editor.insets.bottom)
                    editor.showErrorMessage("内容不能为空，请填写新的标题")
                    assertTrue(editor.preferredSizeFor(420, Int.MAX_VALUE).height > base.height)
                    assertTrue(editor.preferredSizeFor(420, 90).height <= 90)
                } finally { editor.close() }
            }
        } finally { SwingUtilities.invokeAndWait { UIManager.put("Label.font", original) } }
    }

    @Test fun `note editor wraps grows caps visible rows and shrinks when text is deleted`() {
        lateinit var editor: CanvasTextEditor
        SwingUtilities.invokeAndWait {
            editor = CanvasTextEditor("注释", CanvasTextDraft(""), true, {}, {}, {}, {})
            val initial = editor.preferredSizeFor(420, Int.MAX_VALUE)
            assertEquals(420, initial.width)
            assertEquals(1, editor.visibleRowCount)
            assertFalse(editor.scrollRequired)

            editor.input.text = "第一行\n第二行"
            val twoRows = editor.preferredSizeFor(420, Int.MAX_VALUE)
            assertEquals(2, editor.visibleRowCount)
            assertTrue(twoRows.height > initial.height)

            editor.input.text = "中文注释内容".repeat(120)
            val manyRows = editor.preferredSizeFor(420, Int.MAX_VALUE)
            assertEquals(8, editor.visibleRowCount)
            assertTrue(editor.scrollRequired)
            assertEquals(420, manyRows.width)

            val screenLimited = editor.preferredSizeFor(420, 170)
            assertTrue(screenLimited.height <= 170)
            assertTrue(editor.visibleRowCount in 1..7)
            assertTrue(editor.scrollRequired)

            val errorBase = editor.preferredSizeFor(180, Int.MAX_VALUE)
            editor.showErrorMessage("请先确认输入法候选文字，请先确认输入法候选文字")
            val errorVisible = editor.preferredSizeFor(180, Int.MAX_VALUE)
            assertTrue(errorVisible.height > errorBase.height)

            editor.input.text = "短"
            val shrunk = editor.preferredSizeFor(420, Int.MAX_VALUE)
            assertEquals(1, editor.visibleRowCount)
            assertFalse(editor.scrollRequired)
            assertTrue(shrunk.height < manyRows.height)
            assertEquals(420, shrunk.width)
            editor.close()
        }
    }

    @Test fun `preedit never saves or dismisses and final native text saves once`() {
        val draft = CanvasTextDraft("初始")
        val saves = mutableListOf<String>()
        var closed = 0
        lateinit var editor: CanvasTextEditor
        SwingUtilities.invokeAndWait {
            editor = CanvasTextEditor("注释", draft, true, saves::add, { closed++ }, { closed++ }, { draft.retain = false })
            editor.input.text = "中文候选\n第二行"
            val preedit = InputMethodEvent(editor.input, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                AttributedString("候选").iterator, 0, null, null)
            editor.input.inputMethodListeners.forEach { it.inputMethodTextChanged(preedit) }
            assertTrue(editor.composition.active)
            assertFalse(preedit.isConsumed)
            editor.actionMap["save"].actionPerformed(ActionEvent(editor, 0, "save"))
            editor.actionMap["dismiss"].actionPerformed(ActionEvent(editor, 0, "dismiss"))
            assertEquals(0, closed)
            assertTrue(saves.isEmpty())
            val committed = InputMethodEvent(editor.input, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                AttributedString("候选").iterator, 2, null, null)
            editor.input.inputMethodListeners.forEach { it.inputMethodTextChanged(committed) }
            assertFalse(editor.composition.active)
            editor.requestSave()
            editor.requestSave()
        }
        SwingUtilities.invokeAndWait {
            assertEquals(listOf("中文候选\n第二行"), saves)
            assertEquals(1, closed)
            assertFalse(draft.retain)
            val doc = editor.input.document as AbstractDocument
            val before = doc.documentListeners.size
            val methods = editor.input.inputMethodListeners.size
            editor.close()
            editor.close()
            assertEquals(before - 1, doc.documentListeners.size)
            assertEquals(methods - 1, editor.input.inputMethodListeners.size)
            assertEquals(0, editor.actionMap.size())
        }
    }

    @Test fun `dismiss retains native document and disposing prevents queued save`() {
        val draft = CanvasTextDraft("")
        var dismisses = 0
        SwingUtilities.invokeAndWait {
            val editor = CanvasTextEditor("标题", draft, false, { error("disposed editor must not save") }, {}, { dismisses++ }, {})
            editor.input.text = "输入中"
            editor.actionMap["dismiss"].actionPerformed(ActionEvent(editor, 0, "dismiss"))
            assertEquals(1, dismisses)
            assertEquals("输入中", draft.text)
            assertTrue(draft.retain)
            editor.requestSave()
            editor.close()
        }
        SwingUtilities.invokeAndWait { assertTrue(draft.retain) }
    }
}
