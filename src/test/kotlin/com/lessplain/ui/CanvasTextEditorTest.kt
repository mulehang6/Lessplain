package com.lessplain.ui

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

/** 测试原生文档和快捷键层，不打开 IDE 或弹层窗口。 */
class CanvasTextEditorTest {
    @Test fun `closing and reopening a submitting draft cannot duplicate the committed write`() {
        val draft = CanvasTextDraft("问题")
        var completion: ((Result<Unit>) -> Unit)? = null
        var writes = 0
        var saved = 0
        var dismissed = 0
        val dispatch: com.lessplain.core.BackgroundReadDispatch<Unit> = { read, done ->
            writes++; read(); completion = done
            AutoCloseable { error("关窗不能取消已经提交的保存") }
        }
        lateinit var first: CanvasTextEditor
        lateinit var second: CanvasTextEditor
        SwingUtilities.invokeAndWait {
            first = CanvasTextEditor("追问", draft, true, {}, { saved++ }, {}, {}, saveDispatch = dispatch)
            first.requestSave()
        }
        SwingUtilities.invokeAndWait {
            assertTrue(draft.saving)
            first.close()
            second = CanvasTextEditor("追问", draft, true, {}, { error("不得重复保存") }, { dismissed++ }, {}, saveDispatch = dispatch)
            assertFalse(second.saveButton.isEnabled)
            assertFalse(second.input.isEditable)
            second.requestSave()
            completion!!(Result.success(Unit))
            assertFalse(draft.retain)
            assertEquals(1, saved)
            assertEquals(1, dismissed)
            second.close()
        }
        SwingUtilities.invokeAndWait { assertEquals(1, writes) }
    }

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

    @Test fun `save uses the native root default button while multiline enter still inserts a line break`() {
        SwingUtilities.invokeAndWait {
            val editor = CanvasTextEditor("源码提问", CanvasTextDraft("第一行"), true, { error("普通 Enter 不能保存") }, {}, {}, {})
            val root = javax.swing.JRootPane()
            root.contentPane.add(editor)
            try {
                editor.addNotify()
                assertTrue(editor.saveButton.isDefaultButton)
                assertEquals(editor.saveButton, root.defaultButton)
                assertEquals(com.intellij.util.ui.JBUI.scale(PopupUi.PADDING), editor.insets.left)
                editor.input.caretPosition = editor.input.document.length
                val key = editor.input.inputMap.get(javax.swing.KeyStroke.getKeyStroke("ENTER"))
                editor.input.actionMap[key].actionPerformed(ActionEvent(editor.input, 0, "enter"))
                assertEquals("第一行\n", editor.input.text)
            } finally { editor.removeNotify(); editor.close() }
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
