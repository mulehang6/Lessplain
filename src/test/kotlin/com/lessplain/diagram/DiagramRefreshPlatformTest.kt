package com.lessplain.diagram

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.lessplain.core.UnderstandingCore
import com.lessplain.ide.CanvasDraftHost
import com.lessplain.ide.GraphLayerKey
import com.lessplain.ide.UnderstandingService
import com.lessplain.ide.noteKey
import com.lessplain.ide.uiRead
import com.lessplain.model.GraphNode
import com.lessplain.ide.GraphNodePosition
import com.lessplain.model.UnderstandingGraph
import com.lessplain.storage.renameNodeLabel
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.awt.event.ContainerAdapter
import java.awt.event.ContainerEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DiagramRefreshPlatformTest {
    @Test fun `rename refreshes only dependent canvas and retains editor lineage drafts and immediate viewport`() {
        Class.forName("org.eclipse.emf.common.util.CommonUtil")
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("LessplainDiagramRefresh", true).fixture
        fixture.setUp()
        val editors = mutableListOf<FileEditor>()
        try {
            lateinit var service: UnderstandingService
            onEdt { service = fixture.project.getService(UnderstandingService::class.java) }
            val loaded = CountDownLatch(1)
            service.readBackground({ service.repository }) { loaded.countDown() }
            assertTrue(loaded.await(10, TimeUnit.SECONDS))
            val core = UnderstandingCore(service.repository)
            val graph = UnderstandingGraph(listOf(GraphNode("node", "原标签")), emptyList())
            val unrelated = core.present(4, "unrelated", "无关图", graph, "root")
            val parent = core.present(4, "parent", "父图", graph, "root")
            val child = core.present(4, "child", "子图", graph, "explicit", parentId = parent.id)
            listOf(unrelated, parent, child).forEach(service.storage::remember)
            lateinit var a: LoadingUnderstandingFileEditor
            lateinit var b: LoadingUnderstandingFileEditor
            lateinit var childFile: UnderstandingVirtualFile
            lateinit var parentFile: UnderstandingVirtualFile
            onEdt { uiRead {
                // 平台夹具的 FileEditorManager 固定创建文本编辑器，直接装配真实插件 editor 外壳。
                a = LoadingUnderstandingFileEditor(fixture.project, service.editorFile(unrelated)).also(editors::add)
                parentFile = service.editorFile(parent)
                childFile = service.editorFile(child).apply { this.parentFile = parentFile }
                b = LoadingUnderstandingFileEditor(fixture.project, childFile).also(editors::add)
            } }
            awaitDiagram(a) { it.record.revision == 1 }
            val previous = awaitDiagram(b) { it.record.revision == 1 }
            val originalA = requireNotNull(findDiagram(a.component))
            val childKey = GraphLayerKey(child.id)
            val draftKey = noteKey(child.id, "node", "")
            onEdt { uiRead {
                service.contentActions.draftsFor(CanvasDraftHost(GraphLayerKey(unrelated.id), true)).retain(noteKey(unrelated.id, "node", ""), "无关画布的草稿")
                service.contentActions.draftsFor(CanvasDraftHost(childKey, true)).retain(draftKey, "本画布未保存的输入")
                previous.zoom = 0.63
                previous.centerX = 77.0; previous.centerY = 88.0
                previous.setNodePositions(mapOf("node" to GraphNodePosition(42.0, 37.0)))
                previous.cancelViewportSave()
            } }
            val renamed = service.repository.renameNodeLabel(child.id, "node", "改名后的标签", 1)
            service.storage.remember(renamed)
            onEdt { uiRead {
                a.refreshRecord(child.id)
                b.refreshRecord(child.id)
            } }
            val replacement = awaitDiagram(b) { it.record.revision == 2 }
            onEdt { uiRead {
                assertSame(originalA, findDiagram(a.component), "无关画布不应重建")
                assertSame(childFile, b.file)
                assertNotSame(previous, replacement)
                assertSame(childFile, service.editorFile(renamed))
                assertSame(parentFile, childFile.parentFile, "已关闭父标签仍须保留返回链")
                assertEquals("本画布未保存的输入", service.contentActions.draftsFor(CanvasDraftHost(childKey, true)).text(draftKey))
                assertEquals("无关画布的草稿", service.contentActions.draftsFor(CanvasDraftHost(GraphLayerKey(unrelated.id), true)).text(noteKey(unrelated.id, "node", "")))
                assertNull(service.contentActions.draftsFor(CanvasDraftHost(childKey, false)).text(draftKey), "工具窗口与编辑器输入分开")
                val saved = service.graphStates.get(childKey)
                val browse = assertNotNull(saved.browse)
                assertEquals(0.63, browse.zoom)
                assertEquals(77.0, browse.panX)
                assertEquals(88.0, browse.panY)
                assertEquals(mapOf("node" to GraphNodePosition(42.0, 37.0)), saved.verticalPositions)
                assertEquals("改名后的标签", replacement.record.graph.nodes.single().label)
            } }
        } finally { onEdt { uiRead { editors.forEach(Disposer::dispose) }; fixture.tearDown() } }
    }

    private fun awaitDiagram(editor: FileEditor, ready: (UnderstandingDiagram) -> Boolean): UnderstandingDiagram {
        val loaded = CountDownLatch(1)
        val listener = object : ContainerAdapter() {
            override fun componentAdded(event: ContainerEvent) { findDiagram(editor.component)?.takeIf(ready)?.let { loaded.countDown() } }
        }
        onEdt {
            (editor.component as Container).addContainerListener(listener)
            findDiagram(editor.component)?.takeIf(ready)?.let { loaded.countDown() }
        }
        try { assertTrue(loaded.await(15, TimeUnit.SECONDS), "图应完成后台装配") }
        finally { onEdt { (editor.component as Container).removeContainerListener(listener) } }
        return requireNotNull(findDiagram(editor.component))
    }

    private fun findDiagram(component: Component): UnderstandingDiagram? = when (component) {
        is UnderstandingDiagram -> component
        is Container -> component.components.firstNotNullOfOrNull(::findDiagram)
        else -> null
    }
    private fun onEdt(action: () -> Unit) = ApplicationManager.getApplication().invokeAndWait(action)
}
