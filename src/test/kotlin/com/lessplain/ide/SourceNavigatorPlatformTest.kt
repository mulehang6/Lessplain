package com.lessplain.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.lessplain.experiment.ide.SourceStatus
import com.lessplain.experiment.ide.ViewService
import com.lessplain.experiment.model.ResolvedSource
import com.lessplain.storage.sha256
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** 同文件源码提问导航复用已打开编辑器，并居中解析到的目标。 */
class SourceNavigatorPlatformTest {
    @Test
    fun `same source file open keeps the selected editor and centers the target`() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val fixture: CodeInsightTestFixture = factory.createCodeInsightFixture(
            factory.createFixtureBuilder("SourceNavigatorPlatformTest").fixture,
        )
        fixture.setUp()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var service: ViewService? = null
        try {
            val content = (1..80).joinToString("\n") { "private fun handler$it() = Unit" }
            val sourcePath = Path.of(requireNotNull(fixture.project.basePath)).resolve("src/ChatRunController.kt")
            Files.createDirectories(sourcePath.parent)
            Files.writeString(sourcePath, content)
            val sourceFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sourcePath)
                ?: error("Could not refresh fixture source file: $sourcePath")
            val editor = onEdt {
                fixture.openFileInEditor(sourceFile)
                val editor = fixture.editor
                editor.component.setSize(800, 240)
                editor.contentComponent.setSize(800, 240)
                editor.component.doLayout()
                editor.scrollingModel.scrollVertically(0)
                editor
            }
            val viewService = ViewService(fixture.project, scope).also { service = it }
            val target = "private fun handler65() = Unit"
            val offset = content.indexOf(target)

            val status = runBlocking {
                viewService.sources.open(
                    ResolvedSource(
                        path = "src/ChatRunController.kt",
                        text = target,
                        version = sha256(content),
                        line = 65,
                    ),
                    "handler65",
                )
            }

            assertEquals(SourceStatus("EXACT", "UNCHANGED"), status)
            onEdt {
                assertSame(editor, FileEditorManager.getInstance(fixture.project).selectedTextEditor)
                assertEquals(offset, editor.caretModel.offset)
                assertTrue(editor.scrollingModel.verticalScrollOffset > 0, "目标源码导航应滚动离开文件顶部")
            }
        } finally {
            service?.let(Disposer::dispose)
            scope.cancel()
            fixture.tearDown()
        }
    }

    private fun <T> onEdt(body: () -> T): T {
        var result: T? = null
        ApplicationManager.getApplication().invokeAndWait({ result = body() }, ModalityState.nonModal())
        return requireNotNull(result)
    }
}
