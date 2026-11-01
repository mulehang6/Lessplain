package com.lessplain.tutorial

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 普通 Swing 事件不保证持有平台模型锁，教程入口必须自行建立可升级的读域。 */
class TutorialOpeningThreadingTest {
    @Test fun `tutorial opens and edits from a Swing callback without an implicit lock`() {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("LessplainTutorialOpening", true).fixture
        fixture.setUp()
        val app = ApplicationManager.getApplication()
        try {
            val service = fixture.project.getService(TutorialService::class.java)
            service.store.saveContext(TutorialContext("ctx_opening", "测试打开", emptyList()))
            val record = service.create("ctx_opening", "打开教程", "原文", TutorialResourceState())
            var result: Result<Unit>? = null
            SwingUtilities.invokeAndWait {
                result = runCatching {
                    assertFalse(app.isWriteIntentLockAcquired, "必须从未持锁的 Swing 回调复现")
                    service.open(record.id)
                    val manager = FileEditorManager.getInstance(fixture.project)
                    assertTrue(manager.openFiles.filterIsInstance<TutorialVirtualFile>().any { it.tutorialId == record.id })
                    service.edit(record.id, record.revision, listOf(TutorialEdit("原文", "修改后的正文")))
                    assertEquals("修改后的正文", service.document(record.id).text)
                }
            }
            requireNotNull(result).getOrThrow()
        } finally {
            app.invokeAndWait {
                val manager = FileEditorManager.getInstance(fixture.project)
                manager.openFiles.forEach(manager::closeFile)
                fixture.tearDown()
            }
        }
    }
}
