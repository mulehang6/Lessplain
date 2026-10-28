package com.lessplain.tutorial

import org.junit.jupiter.api.Test
import kotlin.test.*

class MarkdownResourcesTest : MarkdownParsingTest() {
    @Test fun `inline resources preserve exact positions in repeated Chinese and emoji text`() {
        val image = "![图](lessplain://diagram/delivery)"
        val simulation = "![模拟](lessplain://simulation/cold_flow)"
        val markdown = "# 中文\n\n重复😀 $image 重复😀 $simulation 结束"
        val blocks = parseMarkdown(markdown)
        val resources = blocks.filter { it.kind in listOf("diagram", "simulation") }
        assertEquals(listOf("delivery", "cold_flow"), resources.map { it.resourceId })
        assertEquals(listOf(image, simulation), resources.map { markdown.substring(it.start, it.end) })
        assertTrue(blocks.any { it.kind == "heading1" })
    }

    @Test fun `escaped references code spans and fenced examples stay literal`() {
        val image = "![图](lessplain://diagram/delivery)"
        listOf("\\$image", "`$image`", "``示例 `$image` ``", "```markdown\n$image\n```", "~~~markdown\n$image\n~~~").forEach { literal ->
            assertTrue(parseMarkdown(literal).none { it.kind == "diagram" }, literal)
        }
        assertEquals("delivery", parseMarkdown("\\\\$image").single { it.kind == "diagram" }.resourceId)
        assertEquals("delivery", parseMarkdown(image).single().resourceId)
    }

    @Test fun `unclosed code delimiters do not swallow a later resource and escaped alt text is supported`() {
        val markdown = "` 未闭合 ![图\\]例](lessplain://diagram/delivery)"
        val reference = resourceReferences(markdown, 8).single()
        assertEquals("delivery", reference.id)
        assertEquals(8 + markdown.indexOf('!'), reference.start)
        assertEquals(8 + markdown.length, reference.end)
    }
    @Test fun `indented code and indented fences never become resource components`() {
        val image = "![图](lessplain://diagram/delivery)"
        listOf("    $image", "  ```kotlin\n  $image\n  ```", "~~~kotlin\n$image\n~~~").forEach { markdown ->
            val code = parseMarkdown(markdown).single()
            assertEquals("code", code.kind, markdown)
            assertEquals(image, code.text.text)
            assertEquals(markdown.indexOf(image), code.text.source(0))
            assertEquals(markdown.indexOf(image) + image.length, code.text.source(image.length))
        }
    }
}
