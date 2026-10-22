package com.lessplain.tutorial

import com.lessplain.ide.captureSourceFocus
import org.junit.jupiter.api.Test
import kotlin.test.*

class TutorialPreviewModelTest {
    @Test fun `one continuous selection crosses formatted paragraphs code and table without losing offsets`() {
        val markdown = "# 标题\n\n前😀 **中文** 与 `Flow`\n\n再一段中文\n\n```kotlin\nval 中文 = 1\n```\n\n| 名称 | 作用 |\n| --- | --- |\n| **中文😀** | `Flow` |"
        val model = TutorialPreviewModel(markdown)
        assertFalse(model.text.contains("**"))
        assertFalse(model.text.contains("---"))
        val a = model.text.indexOf("中文")
        val b = model.text.lastIndexOf("Flow") + 4
        val range = assertNotNull(model.selection(a, b))
        assertEquals(markdown.indexOf("中文"), range.first)
        assertEquals(markdown.lastIndexOf("Flow") + 4, range.last + 1)
        assertEquals(model.text.lastIndexOf("Flow"), model.visibleAt(markdown.lastIndexOf("Flow")))
        assertEquals(markdown.lastIndexOf("中文"), model.sourceAt(model.text.lastIndexOf("中文")))
        val emoji = model.text.indexOf("😀")
        val emojiRange = assertNotNull(model.selection(emoji + 1, emoji + 2))
        assertEquals("😀", markdown.substring(emojiRange.first, emojiRange.last + 1))
    }

    @Test fun `native source focus expands duplicate context and preserves selected occurrence`() {
        val repeated = "a".repeat(120) + "Flow<Event>" + "a".repeat(120)
        val source = "first " + repeated + " second " + repeated
        val start = source.lastIndexOf("Flow<Event>")
        val captured = captureSourceFocus(source, "Gateway.kt", start, start + 11)
        assertEquals(source.indexOf(captured.anchor.text), source.lastIndexOf(captured.anchor.text))
        assertEquals(start, source.indexOf(captured.anchor.text) + captured.start)
        assertEquals("Flow<Event>", captured.anchor.text.substring(captured.start, captured.end))
    }
}
