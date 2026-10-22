package com.lessplain.tutorial

import com.lessplain.core.UnderstandingCore
import com.lessplain.model.GraphNode
import com.lessplain.model.SourceAnchor
import com.lessplain.model.UnderstandingGraph
import com.lessplain.model.UnderstandingQuestion
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class TutorialMcpPaginationTest {
    @Test fun `graph list reaches records beyond recent ui limit with stable page boundaries`() {
        TutorialMcpFixture("LessplainGraphPages").use { test ->
            val repository = test.graphs.repository
            val template = UnderstandingCore(repository).present(4, "paging-template", "模板", UnderstandingGraph(listOf(GraphNode("one", "节点")), emptyList()), "root")
            repository.write { db -> repeat(501) { index ->
                repository.insertRecord(db, template.copy(id = "page-$index", question = "paging sample $index", createdAt = index.toLong()))
            } }
            val ids = mutableListOf<String>()
            var cursor = ""
            do {
                val page = test.call("lessplain_materials_list", buildJsonObject { put("kind", "graph"); put("query", "paging sample"); put("limit", 100); put("cursor", cursor) })
                ids += page["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
                cursor = page["nextCursor"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content.orEmpty()
            } while (cursor.isNotEmpty())
            assertEquals(501, ids.size)
            assertEquals(501, ids.distinct().size)
            assertEquals("page-500", ids.first())
            assertEquals("page-0", ids.last())
            val invalid = test.invoke("lessplain_materials_list", buildJsonObject { put("kind", "graph"); put("cursor", "99999") })
            assertEquals("INVALID_CURSOR", invalid.structuredContent!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            val badLimit = test.invoke("lessplain_materials_list", buildJsonObject { put("kind", "graph"); put("limit", 0) })
            assertEquals("INVALID_LIMIT", badLimit.structuredContent!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            val malformed = test.invoke("lessplain_annotations_list", buildJsonObject { put("cursor", "invalid") })
            assertEquals("INVALID_CURSOR", malformed.structuredContent!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `question filters precede paging and tutorial graph boundary includes only page sources`() {
        TutorialMcpFixture("LessplainAnnotationPages").use { test ->
            val repository = test.graphs.repository
            val core = UnderstandingCore(repository)
            val graph = UnderstandingGraph(listOf(GraphNode("one", "节点")), emptyList())
            val owner = core.present(4, "owner", "目标", graph, "root")
            val other = core.present(4, "other", "其他", graph, "root")
            val context = core.captureContext(owner.id, "one", SourceAnchor("A.kt", null, "旧的问题来源", "a".repeat(64)))
            val wanted = core.prepareQuestion("很早的待答问题", context.token).question
            val otherContext = core.captureContext(other.id, "one")
            val plain = core.captureContext()
            repository.write { db -> repeat(510) { index ->
                repository.insertQuestion(db, UnderstandingQuestion("newer-$index", "其他已答$index", otherContext.token, other.id, "answered", index.toLong()))
            } }
            val plainQuestion = core.prepareQuestion("独立源码问题", plain.token).question
            val filtered = test.call("lessplain_annotations_list", buildJsonObject { put("materialId", owner.id); put("kind", "question"); put("status", "pending") })
            assertEquals(wanted.id, filtered["items"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
            val feedback = test.call("lessplain_annotations_list", buildJsonObject { put("kind", "feedback"); put("status", "pending") })
            assertEquals(wanted.id, feedback["items"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
            assertFalse(feedback["items"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == plainQuestion.id })

            val tutorialSource = test.tutorials.store.saveSource(TutorialSource("src_tutorial_page", "B.kt", "教程来源", "教程来源", 0, 4, "b".repeat(64)))
            val tutorialContext = test.tutorials.store.saveContext(TutorialContext("ctx_page", "教程", listOf(tutorialSource.id)))
            val tutorial = test.tutorials.create(tutorialContext.id, "跨类型分页", "正文", TutorialResourceState())
            test.tutorials.store.createAnnotation(TutorialAnnotation("q_page", tutorial.id, "question", 0, 2, "正文", "教程追问", "pending", contextId = tutorialContext.id))
            val first = test.call("lessplain_annotations_list", buildJsonObject { put("status", "pending"); put("limit", 1) })
            assertEquals("q_page", first["items"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
            assertEquals(listOf(tutorialSource.id), first["sources"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
            val second = test.call("lessplain_annotations_list", buildJsonObject { put("status", "pending"); put("limit", 1); put("cursor", first["nextCursor"]!!.jsonPrimitive.content) })
            assertEquals(plainQuestion.id, second["items"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
            assertTrue(second["sources"]!!.jsonArray.isEmpty())
            val third = test.call("lessplain_annotations_list", buildJsonObject { put("status", "pending"); put("limit", 1); put("cursor", second["nextCursor"]!!.jsonPrimitive.content) })
            assertEquals(wanted.id, third["items"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
            assertEquals("A.kt", third["sources"]!!.jsonArray.single().jsonObject["path"]!!.jsonPrimitive.content)
            assertNull(third["nextCursor"]?.takeUnless { it is JsonNull })
            // 不加过滤也能越过 500 条；第一页教程问题之后是全部图问题。
            val tail = test.call("lessplain_annotations_list", buildJsonObject { put("limit", 100); put("cursor", "500") })
            assertEquals(13, tail["items"]!!.jsonArray.size)
            assertTrue(tail["items"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == wanted.id })
        }
    }
}
