package com.lessplain.mcp

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.nio.file.Files
import java.nio.file.Path
import com.lessplain.lesson.validateLessonBlocks
import com.lessplain.lesson.lessonBlockText
import com.lessplain.lesson.focusText

class LessonPayloadParserTest {
    @Test fun `new Flow example retains exact concept bindings and canonical rich text`() {
        val parsed = parseLessonPayload(Files.readString(Path.of("docs/examples/flow-native-lesson.json")))
        validateLessonBlocks(parsed.blocks, parsed.examples.map { it.id }.toSet())
        val bindings = parsed.blocks.flatMap { it.sourceBindings.orEmpty() }
        assertEquals(setOf("Flow", "AgentStreamEvent"), bindings.map { focusText(it.focus) }.toSet())
        parsed.blocks.filter { it.richText != null }.forEach { block ->
            assertEquals(block.richText!!.joinToString("") { it.text }, lessonBlockText(block))
        }
        assertEquals("flow-events.main.kts", parsed.examples.single().fileName)
    }
    @Test fun `mixed native content parses without inventing graph nodes for the lesson`() {
        val parsed = parseLessonPayload("""{
          "title":"Flow 是什么", "blocks":[
            {"id":"intro","kind":"text","text":"认识 Flow","level":1},
            {"id":"code","kind":"code","exampleId":"numbers"},
            {"id":"members","kind":"table","columns":["成员","作用"],"rows":[["collect","收集值"]]},
            {"id":"flow","kind":"diagram","graph":{"nodes":[{"id":"produce","label":"产生值"}],"edges":[]}}
          ], "examples":[{"id":"numbers","fileName":"numbers.main.kts","code":"println(1)","dependencies":[]}]
        }""")
        assertEquals(4, parsed.blocks.size)
        assertEquals("step", parsed.blocks.last().graph!!.nodes.single().kind)
        assertEquals("numbers.main.kts", parsed.examples.single().fileName)
    }

    @Test fun `diagram blocks preserve the existing no coordinates boundary`() {
        assertFailsWith<IllegalArgumentException> {
            parseLessonPayload("""{"blocks":[{"id":"diagram","kind":"diagram","graph":{"nodes":[{"id":"a","label":"A","x":1}],"edges":[]}}]}""")
        }
    }

    @Test fun `malformed content fails explicitly`() {
        assertFailsWith<IllegalArgumentException> { parseLessonPayload("""{"blocks":"text"}""") }
        assertFailsWith<IllegalArgumentException> { parseLessonPayload("""{"blocks":[{"id":"a","kind":1}]}""") }
    }

    @Test fun `source reference retains the unique project excerpt`() {
        val source = parseLessonPayload("""{"title":"事件","blocks":[{"id":"definition","kind":"code","text":"sealed interface AgentStreamEvent",
            "source":{"path":"src/Events.kt","symbol":"AgentStreamEvent","text":"sealed interface AgentStreamEvent"}}]}""").blocks.single().source!!
        assertEquals("src/Events.kt", source.path)
        assertEquals("sealed interface AgentStreamEvent", source.text)
    }
}
