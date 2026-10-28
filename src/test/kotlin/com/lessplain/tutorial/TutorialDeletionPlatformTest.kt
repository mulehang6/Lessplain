package com.lessplain.tutorial

import com.intellij.openapi.fileEditor.FileEditorManager
import com.lessplain.ide.uiRead
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.*

class TutorialDeletionPlatformTest {
    @Test fun `MCP retries cannot recreate deleted tutorial or replay old edits resources and answers`() {
        TutorialMcpFixture("LessplainTutorialDeletionMcp").use { fixture ->
            val context = fixture.call("lessplain_context_capture", buildJsonObject { put("question", "学习") })["contextId"]!!.jsonPrimitive.content
            val create = buildJsonObject { put("contextId", context); put("title", "删除测试"); put("markdown", "删除测试正文") }
            val result = fixture.call("lessplain_tutorial_create", create)
            val id = result["tutorial"]!!.jsonObject["id"]!!.jsonPrimitive.content
            val edit = buildJsonObject { put("id", id); put("expectedRevision", 1); put("edits", buildJsonArray { add(buildJsonObject { put("find", "正文"); put("replace", "修改") }) }) }
            fixture.call("lessplain_tutorial_edit", edit)
            val simulation = buildJsonObject {
                put("tutorialId", id); put("id", "s"); put("expectedRevision", 0); put("language", "kotlin"); put("code", "println(1)")
                put("scenarios", buildJsonArray { add(buildJsonObject { put("id", "a"); put("label", "场景"); put("steps", buildJsonArray { add(buildJsonObject { put("highlightLines", buildJsonArray { add(1) }); put("caption", "输出") }) }) }) })
            }
            fixture.call("lessplain_simulation_save", simulation)
            val question = fixture.tutorials.annotationDraft(id, 0, 2, "question").copy(text = "为什么", status = "pending", contextId = context)
            fixture.tutorials.store.createAnnotation(question)
            val answer = buildJsonObject { put("questionId", question.id); put("markdown", "删除前回答") }
            fixture.call("lessplain_question_answer", answer)
            fixture.tutorials.store.deleteTutorial(id)
            for ((name, args) in listOf("lessplain_tutorial_create" to create, "lessplain_tutorial_edit" to edit, "lessplain_simulation_save" to simulation, "lessplain_question_answer" to answer)) {
                val retry = fixture.invoke(name, args)
                assertTrue(retry.isError, name)
                assertEquals("DELETED", retry.structuredContent!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.content, name)
            }
            assertTrue(fixture.tutorials.store.list().isEmpty())
            val reveal = fixture.invoke("lessplain_reveal", buildJsonObject { put("id", id) })
            assertTrue(reveal.isError)
            assertFalse(Files.exists(fixture.storeRoot.resolve(id)))
        }
    }

    @Test fun `confirmed deletion closes every editor view before cleanup and releases cached document`() {
        TutorialMcpFixture("LessplainTutorialDeletionViews").use { fixture ->
            val service = fixture.tutorials
            service.store.saveContext(TutorialContext("ctx", "问题", emptyList()))
            val record = service.create("ctx", "删除打开教程", "原始正文", TutorialResourceState())
            lateinit var first: TutorialEditorView
            lateinit var second: TutorialEditorView
            fixture.onEdt { uiRead {
                service.open(record.id)
                first = TutorialEditorView(fixture.project, record.id)
                second = TutorialEditorView(fixture.project, record.id)
                assertSame(first.editor.document, second.editor.document)
                service.deleteConfirmed(record.id)
                assertTrue(first.editor.isDisposed); assertTrue(second.editor.isDisposed)
                assertTrue(FileEditorManager.getInstance(fixture.project).openFiles.filterIsInstance<TutorialVirtualFile>().none { it.tutorialId == record.id })
            } }
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!service.store.isDeleted(record.id) && System.nanoTime() < deadline) Thread.sleep(10)
            service.store.flush()
            assertTrue(service.store.isDeleted(record.id))
            fixture.onEdt { uiRead { assertFailsWith<TutorialProblem> { service.document(record.id) } } }
            assertFalse(Files.exists(fixture.storeRoot.resolve(record.id)))
        }
    }
}
