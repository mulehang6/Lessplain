package com.lessplain.tutorial

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath
import kotlin.test.*

class TutorialCatalogTreeTest {
    @Test
    fun `nested catalog renders canonical references and keyboard opens the selected material`() {
        TutorialMcpFixture("TutorialCatalogTree").use { fixture ->
            fixture.source()
            val context = fixture.call("lessplain_context_capture", buildJsonObject { put("question", "三遍阅读") })["contextId"]!!.jsonPrimitive.content
            val id = fixture.call("lessplain_tutorial_create", buildJsonObject {
                put("contextId", context); put("title", "第一遍"); put("markdown", "同一份正文")
            })["tutorial"]!!.jsonObject["id"]!!.jsonPrimitive.content
            fixture.onEdt {
                val catalog = fixture.tutorials.catalog
                catalog.placeNew(id, "第一遍", listOf("Agent 流程", "基础"))
                val alternate = catalog.createFolder(null, "另一条阅读路线")
                catalog.place(id, "第一遍", alternate.id)
                var opened = 0
                val panel = TutorialCatalogTree(fixture.tutorials) { opened++ }
                panel.refresh(fixture.tutorials.store.list())
                val root = panel.tree.model.root as DefaultMutableTreeNode
                assertEquals(2, root.childCount)
                val course = root.getChildAt(0) as DefaultMutableTreeNode
                val path = TreePath(course.path)
                panel.tree.selectionPath = path
                panel.openSelection()
                assertTrue(panel.tree.isExpanded(path))
                val chapter = course.getChildAt(0) as DefaultMutableTreeNode
                val first = chapter.getChildAt(0) as DefaultMutableTreeNode
                assertEquals("第一遍", first.userObject.toString())
                panel.tree.selectionPath = TreePath(first.path)
                panel.openSelection()
                assertEquals(1, opened)
                assertEquals(1, fixture.tutorials.store.list().size)
                catalog.rename((catalog.snapshot().first { it.title == "基础" }).id, "入门")
                panel.refresh(fixture.tutorials.store.list())
                assertEquals("第一遍", (panel.tree.selectionPath!!.lastPathComponent as DefaultMutableTreeNode).userObject.toString())
                assertEquals("同一份正文", fixture.tutorials.document(id).text)
            }
        }
    }
}
