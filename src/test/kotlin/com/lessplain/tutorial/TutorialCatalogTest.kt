package com.lessplain.tutorial

import org.junit.jupiter.api.Test
import kotlin.test.*

class TutorialCatalogTest {
    @Test
    fun `nested folders and shared references persist reorder and move without copying content`() {
        var saved: String? = null
        val catalog = TutorialCatalog({ saved }) { saved = it }
        val course = catalog.createFolder(null, "Agent 完整流程")
        val chapter = catalog.createFolder(course.id, "第一遍")
        val first = catalog.place("tut_one", "普通回复", chapter.id)
        val second = catalog.place("tut_two", "工具调用", chapter.id)
        catalog.reorder(second.id, -1)
        assertEquals(listOf(second.id, first.id), catalog.children(chapter.id).map { it.id })
        catalog.place("tut_one", "普通回复", course.id)
        assertEquals(2, catalog.snapshot().count { it.tutorialId == "tut_one" })
        catalog.move(first.id, course.id)
        catalog.rename(chapter.id, "入门")
        assertEquals(catalog.snapshot(), TutorialCatalog({ saved }) { error("读取不能写入") }.snapshot())
        catalog.removeReference(first.id)
        assertEquals(1, catalog.snapshot().count { it.tutorialId == "tut_one" })
        catalog.dissolve(chapter.id)
        assertEquals(course.id, catalog.snapshot().first { it.id == second.id }.parentId)
    }

    @Test
    fun `invalid moves names and failed persistence leave previous catalog intact`() {
        var saved: String? = null
        var fail = false
        val catalog = TutorialCatalog({ saved }) { if (fail) error("write failed") else saved = it }
        val parent = catalog.createFolder(null, "课程")
        val child = catalog.createFolder(parent.id, "章节")
        val before = catalog.snapshot()
        for (action in listOf<() -> Unit>(
            { catalog.move(parent.id, child.id) }, { catalog.move(child.id, "missing") },
            { catalog.createFolder(null, "课程") }, { catalog.rename(child.id, " ") },
        )) { assertFails { action() }; assertEquals(before, catalog.snapshot()) }
        fail = true
        assertFails { catalog.rename(child.id, "修改") }
        assertEquals(before, catalog.snapshot())
    }

    @Test
    fun `initial grouping is idempotent and retry respects user arranged location`() {
        var saved: String? = null
        val catalog = TutorialCatalog({ saved }) { saved = it }
        catalog.placeNew("tut_one", "第一遍", listOf("Agent 流程", "基础"))
        catalog.placeNew("tut_two", "第二遍", listOf("Agent 流程", "基础"))
        assertEquals(2, catalog.snapshot().count { it.tutorialId == null })
        val first = catalog.snapshot().first { it.tutorialId == "tut_one" }
        catalog.move(first.id, null)
        val before = catalog.snapshot()
        catalog.placeNew("tut_one", "第一遍", listOf("另一个目录"))
        assertEquals(before, catalog.snapshot())
        assertFails { catalog.placeNew("tut_three", "第三遍", listOf("", "错误")) }
        assertEquals(before, catalog.snapshot())
    }
}
