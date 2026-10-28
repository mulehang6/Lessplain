package com.lessplain.tutorial

import org.junit.jupiter.api.Test
import kotlin.test.*

class TutorialMarkdownTest : MarkdownParsingTest() {
    @Test fun `Chinese emoji repeated text emphasis inline code and links map to original UTF16 spans`() {
        val raw = "前😀 **中文** 与 `Flow` 再 **中文** [项目](lessplain://source/src_flow)"
        val mapped = inlineMarkdown(raw, 13)
        assertEquals("前😀 中文 与 Flow 再 中文 项目", mapped.text)
        val first = mapped.text.indexOf("中文")
        val second = mapped.text.lastIndexOf("中文")
        assertEquals(13 + raw.indexOf("中文"), mapped.source(first))
        assertEquals(13 + raw.lastIndexOf("中文"), mapped.source(second))
        assertEquals(13 + raw.indexOf("Flow"), mapped.source(mapped.text.indexOf("Flow")))
        assertEquals("lessplain://source/src_flow", mapped.styles.single { it.kind == "link" }.href)
        assertEquals(raw.length + 13, mapped.source(mapped.text.length))
    }

    @Test fun `platform parser recognizes resources table and untruncated long code with source bounds`() {
        val code = (1..180).joinToString("\n") { "println($it)" }
        val markdown = "# 标题\n\n正文\n\n| 名称 | 意义 |\n| --- | --- |\n| Flow | 陆续交付 |\n\n```kotlin\n$code\n```\n\n![模拟](lessplain://simulation/demo)"
        val blocks = parseMarkdown(markdown)
        assertTrue(blocks.any { it.kind == "table" })
        assertTrue(blocks.any { it.kind == "simulation" && it.resourceId == "demo" })
        val block = blocks.single { it.kind == "code" }
        assertEquals(code, block.text.text)
        assertEquals(markdown.indexOf("println(1)"), block.text.source(0))
        assertEquals(180, block.text.text.lines().size)
        assertTrue(blocks.all { it.start >= 0 && it.end <= markdown.length && it.end > it.start })
    }
}
