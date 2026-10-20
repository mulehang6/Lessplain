package com.lessplain.lesson

import com.lessplain.storage.sha256
import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities
import javax.swing.text.StyleConstants
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LessonRichTextTest {
    @Test fun `styles preserve exact UTF16 selection including repeated words and emoji`() {
        val runs = listOf(LessonTextRun("中文 🙂 Flow "), LessonTextRun("Flow", "code"), LessonTextRun(" 逐个交付", "strong"))
        val block = LessonBlock("intro", "text", richText = runs)
        val expected = "中文 🙂 Flow Flow 逐个交付"
        SwingUtilities.invokeAndWait {
            val view = lessonTextView(block)
            assertEquals(expected, view.document.getText(0, view.document.length))
            val start = expected.lastIndexOf("Flow")
            view.select(start, start + 4)
            assertEquals("Flow", view.selectedText)
            val target = LessonTarget("intro", start = start, end = start + 4, selectedText = "Flow", contentVersion = sha256(expected))
            val record = LessonRecord("lesson", 1, "课", LessonOrigin(com.lessplain.model.SourceAnchor("A.kt", null, "source", "v")), listOf(block), createdAt = 0)
            assertTrue(lessonTargetIsCurrent(record, emptyList(), target))
            assertTrue(StyleConstants.getFontFamily(view.styledDocument.getCharacterElement(start).attributes).isNotBlank())
            assertTrue(StyleConstants.isBold(view.styledDocument.getCharacterElement(expected.length - 1).attributes))
        }
    }

    @Test fun `rich text cannot duplicate plain text or execute arbitrary links`() {
        assertFailsWith<IllegalArgumentException> { validateLessonBlocks(listOf(LessonBlock("text", "text", "duplicate",
            richText = listOf(LessonTextRun("other")))), emptySet()) }
        assertFailsWith<IllegalArgumentException> { validateLessonBlocks(listOf(LessonBlock("text", "text",
            richText = listOf(LessonTextRun("打开", "link", "javascript:alert(1)")))), emptySet()) }
    }

    @Test fun `wide pages constrain reading width and narrow pages reflow`() {
        SwingUtilities.invokeAndWait {
            val page = LessonPagePanel()
            val text = selectableLessonText("Flow 的一段解释。".repeat(80))
            page.add(text)
            page.setSize(1800, 400)
            val wide = text.preferredSize
            assertTrue(wide.width < 1000)
            page.setSize(480, 400)
            val narrow = text.preferredSize
            assertTrue(narrow.width < wide.width)
            assertTrue(narrow.height > wide.height)
        }
    }
}
