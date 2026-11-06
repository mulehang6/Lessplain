package com.lessplain.ui

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.lessplain.tutorial.TutorialMcpFixture
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.tree.DefaultMutableTreeNode
import kotlin.test.*

class FocusedSourcePanelTest {
    @Test
    fun `editor selection focuses actual source and disposal removes file events`() {
        TutorialMcpFixture("FocusedSourcePanel").use { fixture ->
            fixture.source()
            val root = Path.of(fixture.project.basePath!!)
            val path = root.resolve("chat/state/State.txt")
            Files.createDirectories(path.parent)
            Files.writeString(path, "state")
            lateinit var panel: FocusedSourcePanel
            fixture.onEdt { panel = FocusedSourcePanel(fixture.project) }
            fun awaitFocus(expected: String) {
                val deadline = System.nanoTime() + 5_000_000_000L
                var focused: String? = null
                while (System.nanoTime() < deadline) {
                    fixture.onEdt {
                        focused = ((panel.tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? DirectoryEntry)?.path
                    }
                    if (focused == expected) return
                    Thread.sleep(10)
                }
                assertEquals(expected, focused)
            }
            try {
                awaitFocus(root.resolve("Flow.txt").toString().replace('\\', '/'))
                fixture.onEdt {
                    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
                    FileEditorManager.getInstance(fixture.project).openFile(file, true)
                }
                awaitFocus(path.toString().replace('\\', '/'))
                fixture.onEdt {
                    Disposer.dispose(panel)
                    val previousModel = panel.tree.model
                    val file = LocalFileSystem.getInstance().findFileByNioFile(root.resolve("Flow.txt"))!!
                    FileEditorManager.getInstance(fixture.project).openFile(file, true)
                    assertSame(previousModel, panel.tree.model)
                }
            } finally { fixture.onEdt { Disposer.dispose(panel) } }
        }
    }
}
