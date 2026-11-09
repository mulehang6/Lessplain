package com.lessplain.tutorial

import org.junit.jupiter.api.Test
import kotlin.test.*

class TutorialStepsTest {
    @Test fun `explicit steps keep canonical ranges and definition links never choose main anchor`() {
        val text = "# 主题\n\n<!-- lessplain:step window source=src_window -->\n\n## window 从哪里来\n\n[类型](lessplain://source/src_type) 是补充定义。\n\n另一段解释😀。\n\n<!-- lessplain:step failure source=src_failure -->\n\n## 失败返回\n\n找不到就结束。"
        val steps = tutorialSteps(text, strict = true)
        assertEquals(listOf("window", "failure"), steps.map { it.id })
        assertEquals("src_window", steps.first().sourceId)
        val model = TutorialPreviewModel(text, steps.first().range)
        assertContains(model.text, "另一段解释😀")
        assertFalse(model.text.contains("失败返回"))
        assertFalse(model.text.contains("lessplain:step"))
        assertEquals(text.indexOf("另一段"), model.sourceAt(model.text.indexOf("另一段")))
        assertEquals(setOf("src_type", "src_window", "src_failure"), tutorialSourceIds(text).toSet())
        assertFalse(TutorialPreviewModel(text).text.contains("lessplain:step"))
    }

    @Test fun `legacy articles and markers in code remain literal without inferred steps`() {
        assertTrue(tutorialSteps("# 标题\n\n[源码](lessplain://source/src_a)").isEmpty())
        val code = "```html\n<!-- lessplain:step example source=src_x -->\n```"
        assertTrue(tutorialSteps(code).isEmpty())
        assertContains(TutorialPreviewModel(code).text, "lessplain:step")
    }

    @Test fun `duplicate malformed and empty points reject atomic writes but typing remains safe`() {
        val duplicate = "<!-- lessplain:step a -->\n\n一\n\n<!-- lessplain:step a -->\n\n二"
        assertFailsWith<TutorialProblem> { tutorialSteps(duplicate, strict = true) }
        assertFailsWith<TutorialProblem> { tutorialSteps("<!-- lessplain:step bad source=not_a_source -->\n\n说明", strict = true) }
        assertFailsWith<TutorialProblem> { tutorialSteps("<!-- lessplain:step a -->", strict = true) }
        assertTrue(tutorialSteps("<!-- lessplain:step a source= -->").isEmpty())
        val concept = tutorialSteps("<!-- lessplain:step background -->\n\n这里只补背景。", strict = true).single()
        assertNull(concept.sourceId)
    }

    @Test fun `wheel respects content boundary direction and gesture inertia`() {
        val wheel = TutorialStepWheel()
        assertEquals(0, wheel.turn(1.0, false, 1))
        assertEquals(0, wheel.turn(.3, true, 10))
        assertEquals(1, wheel.turn(.8, true, 20))
        assertEquals(0, wheel.turn(2.0, true, 30))
        assertEquals(0, wheel.turn(.8, true, 100))
        assertEquals(-1, wheel.turn(-1.0, true, 110))
        assertEquals(-1, wheel.turn(-1.0, true, 400))
    }

    @Test fun `moving a whole point keeps repeated annotations attached to the right point`() {
        val a = "<!-- lessplain:step a -->\n\n重复解释。\n\n"
        val b = "<!-- lessplain:step b -->\n\n重复解释。\n\n"
        val before = a + b
        val start = a.indexOf("重复")
        val note = TutorialAnnotation("note", "tut", "note", start, start + 4, "重复解释", "手写内容")
        val moved = assertNotNull(movedStepAnnotation(note, before, b + a))
        assertEquals(b.length + start, moved.start)
        assertEquals("手写内容", moved.text)
        assertEquals(note, movedStepAnnotation(moved, b + a, before))
    }
}
