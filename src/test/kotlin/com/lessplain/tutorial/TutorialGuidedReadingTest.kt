package com.lessplain.tutorial

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.lessplain.ide.uiRead
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class TutorialGuidedReadingTest {
    @Test fun `source following preserves native caret selection and invalidates changed focus`() {
        TutorialMcpFixture("GuidedSource").use { fixture ->
            val id = fixture.source()["sourceId"]!!.jsonPrimitive.content
            fixture.onEdt { uiRead {
                val editor = requireNotNull(FileEditorManager.getInstance(fixture.project).selectedTextEditor)
                editor.caretModel.moveToOffset(3)
                editor.selectionModel.setSelection(0, 3)
                var stale = false
                val follower = TutorialSourceFollower(fixture.tutorials.sources) { stale = true }
                try {
                    follower.show(id)
                    assertEquals(3, editor.caretModel.offset)
                    assertEquals(0, editor.selectionModel.selectionStart)
                    assertEquals(3, editor.selectionModel.selectionEnd)
                    assertEquals("Flow<Event>", editor.document.getText(requireNotNull(follower.highlight).textRange))
                    val start = requireNotNull(follower.highlight).startOffset
                    WriteCommandAction.runWriteCommandAction(fixture.project, Runnable { editor.document.replaceString(start, start + 4, "Other") })
                    assertTrue(stale)
                    assertNull(follower.highlight)
                    assertFailsWith<TutorialProblem> { follower.show(id) }
                } finally { follower.close() }
            } }
        }
    }

    @Test fun `point view shares document and annotations while moving freely and reopening at saved point`() {
        TutorialMcpFixture("GuidedDocument").use { fixture ->
            val source = fixture.source()["sourceId"]!!.jsonPrimitive.content
            val context = fixture.call("lessplain_context_capture", buildJsonObject { put("question", "逐点") })["contextId"]!!.jsonPrimitive.content
            val text = "# 主题\n\n<!-- lessplain:step one source=$source -->\n\n## 第一处\n\n原解释😀。\n\n<!-- lessplain:step two -->\n\n## 第二处\n\n概念背景。"
            val id = fixture.call("lessplain_tutorial_create", buildJsonObject { put("contextId", context); put("title", "逐点阅读"); put("markdown", text) })["tutorial"]!!.jsonObject["id"]!!.jsonPrimitive.content
            fixture.onEdt { uiRead {
                assertIs<ToggleAction>(ActionManager.getInstance().getAction("ProjectView.AutoscrollFromSource"))
                val view = TutorialEditorView(fixture.project, id, panel = true)
                try {
                    assertEquals(TutorialDisplayMode.STEPS, view.mode)
                    assertSame(fixture.tutorials.document(id), view.editor.document)
                    assertContains(view.preview.text, "原解释😀")
                    assertFalse(view.preview.text.contains("概念背景"))
                    val offset = text.indexOf("原解释")
                    fixture.tutorials.store.createAnnotation(TutorialAnnotation("note_guided", id, "note", offset, offset + 3, "原解释", "用户笔记"))
                    view.steps.move(1)
                    assertEquals("two", view.steps.stepId)
                    assertContains(view.preview.text, "概念背景")
                    assertFalse(view.preview.text.contains("原解释"))
                    assertEquals("用户笔记", fixture.tutorials.store.annotation("note_guided").text)
                    view.display(TutorialDisplayMode.EDIT)
                    assertNull(view.preview.sourceRange)
                    view.display(TutorialDisplayMode.STEPS)
                } finally { view.close() }
                val reopened = TutorialEditorView(fixture.project, id, panel = true)
                try { assertEquals("two", reopened.steps.stepId) } finally { reopened.close() }
                assertContains(fixture.tutorials.store.record(id).sourceIds, source)
            } }
        }
    }
}
