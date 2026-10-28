package com.lessplain.ui

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.fileEditor.FileEditorManager
import com.lessplain.diagram.UnderstandingVirtualFile
import com.lessplain.ide.PanelPage
import com.lessplain.ide.uiRead
import com.lessplain.model.BrowseState
import com.lessplain.model.GraphNode
import com.lessplain.model.SourceAnchor
import com.lessplain.model.UnderstandingGraph
import com.lessplain.storage.sha256
import com.lessplain.tutorial.TutorialMcpFixture
import org.junit.jupiter.api.Test
import kotlin.test.*

class OpenUnderstandingInEditorActionTest {
    @Test fun `title action uses panel record even with no global selection or another editor selection`() {
        TutorialMcpFixture("LessplainOpenPanelRecord").use { fixture ->
            val text = "fun run(): Flow<Event> = TODO()"
            fixture.source(text)
            val graph = UnderstandingGraph(listOf(GraphNode("n", "节点", anchor = SourceAnchor("Flow.txt", null, text, sha256(text), 1))), emptyList())
            val panelRecord = fixture.graphs.core.present(4, "panel", "面板图", graph, "root")
            val other = fixture.graphs.core.present(4, "other", "其他编辑器图", graph, "root")
            fixture.onEdt { uiRead {
                val service = fixture.graphs
                service.storage.remember(panelRecord)
                service.panelRecordId = panelRecord.id; service.panelPage = PanelPage.CANVAS
                assertNull(service.activeRecordId)
                val action = OpenUnderstandingInEditorAction(fixture.project, service)
                val event = AnActionEvent.createEvent(action, SimpleDataContext.getProjectContext(fixture.project), null, "LessplainTest", com.intellij.openapi.actionSystem.ActionUiKind.NONE, null)
                action.update(event); assertTrue(event.presentation.isEnabled)
                service.storage.rememberBrowse(other.id, BrowseState(other.id))
                service.select(other, navigateSource = false)
                assertEquals(other.id, service.activeRecordId)
                action.actionPerformed(event)
                val files = FileEditorManager.getInstance(fixture.project).openFiles.filterIsInstance<UnderstandingVirtualFile>()
                assertTrue(files.any { it.recordId == panelRecord.id })
                assertFalse(files.any { it.recordId == other.id })
            } }
        }
    }
}
