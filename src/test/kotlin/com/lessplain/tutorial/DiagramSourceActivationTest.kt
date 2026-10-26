package com.lessplain.tutorial

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.lessplain.core.StaticExplorer
import com.lessplain.diagram.bindDiagramSourceNavigation
import com.lessplain.diagram.selectUnderstandingNode
import com.lessplain.diagram.createUnderstandingDiagram
import com.lessplain.ide.GraphLayerKey
import com.lessplain.ide.uiRead
import com.lessplain.model.*
import com.lessplain.storage.sha256
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** 真实编辑器标签与导航绑定回归；不创建 IDE 窗口。 */
class DiagramSourceActivationTest {
    @Test fun `reclick selected node restores source while pinned clicks and ordinary synchronization do not`() {
        TutorialMcpFixture("LessplainSourceActivation").use { fixture ->
            val text = "fun run(): Flow<Event> = TODO()"
            fixture.source(text)
            val anchor = SourceAnchor("Flow.txt", null, text, sha256(text), 1)
            val record = fixture.graphs.core.present(4, "activation", "问题",
                UnderstandingGraph(listOf(GraphNode("n", "返回消息", anchor = anchor)), emptyList()), "root")
            fixture.graphs.storage.loadRecord(record.id)
            fixture.graphs.storage.rememberBrowse(record.id, BrowseState(record.id, "n"))
            val other = Path.of(requireNotNull(fixture.project.basePath)).resolve("Other.txt")
            Files.writeString(other, "其他标签")
            var diagram: com.lessplain.diagram.UnderstandingDiagram? = null
            var binding: AutoCloseable? = null
            fun selectedPath(): String? {
                var path: String? = null
                fixture.onEdt { path = FileEditorManager.getInstance(fixture.project).selectedTextEditor?.virtualFile?.name }
                return path
            }
            fun openOther() = fixture.onEdt { uiRead {
                val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(other))
                FileEditorManager.getInstance(fixture.project).openTextEditor(OpenFileDescriptor(fixture.project, file, 0), true)
            } }
            try {
                fixture.onEdt { uiRead {
                    diagram = createUnderstandingDiagram(record)
                    binding = bindDiagramSourceNavigation(fixture.graphs, GraphLayerKey(record.id), requireNotNull(diagram), StaticExplorer(record.graph), record)
                    requireNotNull(diagram).selectUnderstandingNode("n", notify = true)
                } }
                await { selectedPath() == "Flow.txt" }
                // 先切到另一个编辑器，再点击仍保持选中的节点。
                openOther()
                fixture.onEdt { uiRead { requireNotNull(diagram).selectUnderstandingNode("n", notify = true) } }
                await { selectedPath() == "Flow.txt" }
                openOther()
                fixture.onEdt { uiRead {
                    fixture.graphs.select(record, "n")
                    requireNotNull(diagram).navigationPinned = true
                    requireNotNull(diagram).selectUnderstandingNode("n", notify = true)
                } }
                assertEquals("Other.txt", selectedPath())
                assertEquals("n", fixture.graphs.activeNodeId)
                assertEquals(emptyList(), fixture.graphs.graphStates.get(GraphLayerKey(record.id)).browse?.staticPath)
                fixture.onEdt { uiRead {
                    requireNotNull(diagram).navigationPinned = false
                    requireNotNull(diagram).selectUnderstandingNode("n", notify = true)
                } }
                await { selectedPath() == "Flow.txt" }
            } finally { fixture.onEdt { uiRead { binding?.close(); diagram?.dispose() } } }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition(), "源码导航未在期限内完成")
    }
}
