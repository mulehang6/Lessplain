package com.lessplain.lesson

import com.lessplain.model.SourceAnchor
import com.lessplain.storage.sha256
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LessonSourceReferencesTest {
    private val source = "fun first(): Flow<AgentStreamEvent> = TODO()\nfun second(): Flow<AgentStreamEvent> = TODO()"
    private val focus = captureLessonFocus(source, "src/Gateway.kt", source.indexOf("Flow"), source.indexOf("Flow") + 4)

    @Test fun `two identical words retain different locations across reopening and unrelated edits`() {
        val second = source.lastIndexOf("Flow")
        val other = captureLessonFocus(source, "src/Gateway.kt", second, second + 4)
        assertEquals(source.indexOf("Flow"), resolveLessonFocus(source, focus)!!.first)
        assertEquals(second, resolveLessonFocus(source, other)!!.first)
        val padded = "// new header\n" + source
        assertEquals(second + 14, resolveLessonFocus(padded, other)!!.first)
        assertNull(resolveLessonFocus(source.replace("first", "renamed"), focus))
        assertNull(resolveLessonFocus(source + "\n" + source, focus))
    }

    @Test fun `selection in a multi concept paragraph associates its note with only that concept`() {
        val eventStart = source.indexOf("AgentStreamEvent")
        val eventFocus = captureLessonFocus(source, "src/Gateway.kt", eventStart, eventStart + "AgentStreamEvent".length)
        val text = "Flow 提供事件，AgentStreamEvent 定义事件。"
        val block = LessonBlock("purpose", "text", text, sourceBindings = listOf(
            LessonSourceBinding("flow", "Flow", focus, 0, 4),
            LessonSourceBinding("event", "AgentStreamEvent", eventFocus, text.indexOf("AgentStreamEvent"), text.indexOf("AgentStreamEvent") + 16),
        ))
        val origin = LessonOrigin(SourceAnchor("src/Gateway.kt", null, "Flow", "v"))
        val record = LessonRecord("lesson", 1, "概念", origin, listOf(block), createdAt = 0)
        val target = LessonTarget("purpose", start = 0, end = 4, selectedText = "Flow", contentVersion = sha256(text))
        val note = LessonAnnotation("note", record.id, target, note = "我的理解", createdAt = 0)
        val answer = LessonBlock("cold", "text", "冷流")
        val parent = note.copy(answer = listOf(answer), status = "answered")
        val nested = LessonAnnotation("nested", record.id, LessonTarget("cold", "note", 0, 2, "冷流", sha256("冷流")),
            question = "解释冷流", createdAt = 1)
        val entries = lessonSourceEntries(LessonState(lessons = listOf(record), annotations = listOf(parent, nested)))
        assertEquals(setOf("note", "nested"), entries.first { it.label == "Flow" }.annotations.map { it.id }.toSet())
        assertTrue(entries.first { it.label == "AgentStreamEvent" }.annotations.isEmpty())
    }

    @Test fun `overlap prefers narrower ranges and keeps every related lesson`() {
        val entries = listOf((10 until 30) to "whole", (10 until 14) to "flow-a", (10 until 14) to "flow-b")
        assertEquals(listOf("flow-a", "flow-b", "whole"), lessonEntriesAt(entries, 12))
        assertTrue(lessonEntriesAt(entries, 30).isEmpty())
    }

    @Test fun `legacy broad origins do not become misleading precise marks`() {
        val record = LessonRecord("old", 1, "旧课", LessonOrigin(SourceAnchor("Gateway.kt", null, source, "v")),
            listOf(LessonBlock("text", "text", "解释")), createdAt = 0)
        assertTrue(lessonSourceEntries(LessonState(lessons = listOf(record))).isEmpty())
    }

    @Test fun `invalid source and rendered ranges fail before save`() {
        assertFailsWith<IllegalArgumentException> { validateLessonFocus(focus.copy(end = focus.anchor.text.length + 1)) }
        assertFailsWith<IllegalArgumentException> { validateLessonFocus(focus.copy(anchor = focus.anchor.copy(path = "../escape.kt"))) }
        assertFailsWith<IllegalArgumentException> { validateLessonBlocks(listOf(LessonBlock("text", "text", "Flow",
            sourceBindings = listOf(LessonSourceBinding("flow", "Flow", focus, 0, 8)))), emptySet()) }
    }
}
