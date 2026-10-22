package com.lessplain.tutorial

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.lessplain.ide.uiRead
import org.junit.jupiter.api.Test
import kotlin.test.*

class TutorialEditorUndoTest {
    @Test fun `custom tutorial participates in the native document undo stack`() {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("LessplainTutorialUndo", true).fixture
        fixture.setUp()
        try {
            ApplicationManager.getApplication().invokeAndWait {
                uiRead {
                    val project = fixture.project
                    val service = project.getService(TutorialService::class.java)
                    service.store.saveContext(TutorialContext("ctx_undo", "测试撤销", emptyList()))
                    val record = service.create("ctx_undo", "撤销", "原文", TutorialResourceState())
                    val file = TutorialVirtualFile(record.id, record.title)
                    val editor = TutorialEditorProvider().createEditor(project, file)
                    try {
                        assertTrue(editor is TextEditor)
                        service.edit(record.id, record.revision, listOf(TutorialEdit("原文", "用户可编辑的新文")))
                        val undo = UndoManager.getInstance(project)
                        assertTrue(undo.isUndoAvailable(editor))
                        undo.undo(editor)
                        assertEquals("原文", service.document(record.id).text)
                        assertTrue(undo.isRedoAvailable(editor))
                        undo.redo(editor)
                        assertEquals("用户可编辑的新文", service.document(record.id).text)
                    } finally { editor.dispose() }
                }
            }
        } finally { ApplicationManager.getApplication().invokeAndWait { fixture.tearDown() } }
    }
}
