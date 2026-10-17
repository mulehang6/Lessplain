package com.lessplain.experiment

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.lessplain.experiment.ide.resolveViewSources
import com.lessplain.experiment.ide.validateViewSelection
import com.lessplain.experiment.ide.anchor
import com.lessplain.experiment.ide.snapshot
import com.lessplain.experiment.model.ResolvedSource
import com.lessplain.experiment.model.SourceReference
import com.lessplain.model.SourceSelectionRange
import com.lessplain.storage.sha256
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** 使用真实 IDE 文档校验，覆盖未保存编辑，避免伪 resolver 掩盖集成问题。 */
class ViewSourcesPlatformTest {
    private lateinit var fixture: CodeInsightTestFixture
    private lateinit var file: Path
    @BeforeEach fun setUp() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        fixture = factory.createCodeInsightFixture(factory.createFixtureBuilder("ViewSourcesPlatformTest").fixture)
        fixture.setUp()
        val root = Path.of(requireNotNull(fixture.project.basePath))
        Files.createDirectories(root)
        file = root.resolve("source-validation.txt")
        Files.writeString(file, "heading\nreturn value;\n")
    }
    @AfterEach fun tearDown() {
        fixture.tearDown()
    }
    @Test fun uniqueCurrentDocumentAndVersion() {
        val virtual = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file))
        val document = requireNotNull(ApplicationManager.getApplication().runReadAction<com.intellij.openapi.editor.Document?> {
            FileDocumentManager.getInstance().getDocument(virtual)
        })
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction { document.setText("unsaved\nreturn changed;\n") }
        }
        val resolved = resolveViewSources(fixture.project, mapOf("return" to SourceReference(file.fileName.toString(), "return changed;"))).getValue("return")
        assertEquals(2, resolved.line)
        assertEquals(sha256("unsaved\nreturn changed;\n"), resolved.version)
        assertTrue(Files.readString(file).contains("return value;"), "验证未保存文档，不应强制保存")
    }
    @Test fun duplicatesWrongSymbolAndOutsidePath() {
        Files.writeString(file, "same\nsame\n")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)?.refresh(false, false)
        assertFailsWith<IllegalArgumentException> { resolveViewSources(fixture.project, mapOf("dup" to SourceReference(file.fileName.toString(), "same"))) }
        assertFailsWith<IllegalArgumentException> { resolveViewSources(fixture.project, mapOf("symbol" to SourceReference(file.fileName.toString(), "same\nsame", "wrongFunction"))) }
        assertFailsWith<IllegalArgumentException> { resolveViewSources(fixture.project, mapOf("outside" to SourceReference("../outside.txt", "value"))) }
    }

    @Test fun repeatedSelectionUsesFrozenUtf16RangeAndRejectsStaleOrInvalidRanges() {
        val content = "😀 item item\nitem\n"
        Files.writeString(file, content)
        val virtual = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file))
        val document = requireNotNull(ApplicationManager.getApplication().runReadAction<com.intellij.openapi.editor.Document?> {
            FileDocumentManager.getInstance().getDocument(virtual)
        })
        val start = content.indexOf("item", content.indexOf("item") + 1)
        val selection = ResolvedSource(file.fileName.toString(), "item", version = sha256(content), line = 1,
            selectionRange = SourceSelectionRange(start, start + 4))
        assertEquals(8, start, "偏移计入 emoji 的两个 UTF-16 码元")
        assertEquals(selection, validateViewSelection(fixture.project, selection))
        assertEquals(selection, selection.anchor().snapshot(), "问题和回答使用同一可序列化来源")
        for (range in listOf(SourceSelectionRange(-1, 3), SourceSelectionRange(start, start + 3), SourceSelectionRange(100, 104))) {
            assertFailsWith<IllegalStateException> { validateViewSelection(fixture.project, selection.copy(selectionRange = range)) }
        }
        assertFailsWith<IllegalStateException> { validateViewSelection(fixture.project, selection.copy(selectionRange = null)) }
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(fixture.project) { document.insertString(0, "changed ") }
        }
        assertFailsWith<IllegalStateException> { validateViewSelection(fixture.project, selection) }
    }
}
