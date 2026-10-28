package com.lessplain.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.lessplain.core.UnderstandingCore
import com.lessplain.model.BrowseState
import com.lessplain.model.GraphNode
import com.lessplain.model.UnderstandingGraph
import com.lessplain.storage.UnderstandingRepository
import com.lessplain.ui.FollowUpLayer
import org.junit.jupiter.api.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PanelRestorePlatformTest {
    @Test fun `startup restores panel route instead of unread result and manual result can return to previous graph`() {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("LessplainRestorePanel", true).fixture
        fixture.setUp()
        try {
            val graph = UnderstandingGraph(listOf(GraphNode("n", "节点")), emptyList())
            val root: com.lessplain.model.UnderstandingRecord
            val other: com.lessplain.model.UnderstandingRecord
            UnderstandingRepository(Path.of(requireNotNull(fixture.project.basePath))).use { repository ->
                val core = UnderstandingCore(repository)
                root = core.present(4, "root", "上次的图", graph, "root")
                other = core.present(4, "other", "未读的图", graph, "root")
            }
            val route = PanelCanvasRoute(root.id, listOf(FollowUpLayer(root.id, "n")))
            val pending = PendingResult("record:${other.id}", other.id, null, 1)
            lateinit var service: UnderstandingService
            edt {
                PanelCanvasSession(fixture.project).remember(route)
                ResultInbox(fixture.project).register(pending)
                GraphViewStateStore(fixture.project).update(GraphLayerKey(root.id, "n")) {
                    it.copy(browse = BrowseState(root.id, "saved-local-node", zoom = 1.4, panX = 25.0, hasPan = true))
                }
                service = fixture.project.getService(UnderstandingService::class.java)
            }
            await { service.panelRecordId == root.id && service.panelLayers == route.layers }
            edt {
                assertEquals(listOf(pending), service.resultInbox.pending())
                assertEquals(1.4, service.graphStates.get(GraphLayerKey(root.id, "n")).browse?.zoom)
                service.showHistory()
                assertEquals(route, service.panelSession.load(), "停在历史页不能清除最后画布")
                service.select(other, navigateSource = false)
                assertEquals(route, service.panelSession.load(), "编辑器的普通选择不能覆盖插件窗口的指针")
                service.showResult(pending)
            }
            await { service.panelRecordId == other.id }
            edt {
                assertTrue(service.resultInbox.pending().isEmpty())
                assertTrue(service.canReturnToPrevious)
                service.returnToPrevious()
                assertEquals(PanelPage.HISTORY, service.panelPage)
                val lastCanvas = service.panelSession.load()
                service.storage.remove(setOf(other.id))
                service.showRoot(other)
                assertEquals(PanelPage.HISTORY, service.panelPage, "删除后的旧列表快照不能重新打开画布")
                assertEquals(lastCanvas, service.panelSession.load(), "过期打开不能覆盖最后画布指针")
            }
        } finally { edt { fixture.tearDown() } }
    }

    @Test fun `explicit history navigation wins over queued startup restore`() {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("LessplainRestoreCancel", true).fixture
        fixture.setUp()
        try {
            lateinit var service: UnderstandingService
            val loaded = CountDownLatch(1)
            edt {
                PanelCanvasSession(fixture.project).remember(PanelCanvasRoute("not-loaded-yet"))
                service = fixture.project.getService(UnderstandingService::class.java)
                service.showHistory()
                service.readBackground({ service.repository }) { loaded.countDown() }
            }
            assertTrue(loaded.await(5, TimeUnit.SECONDS))
            edt {
                assertEquals(PanelPage.HISTORY, service.panelPage)
                assertNull(service.panelRecordId)
                assertEquals("not-loaded-yet", service.panelSession.load()?.rootId)
            }
        } finally { edt { fixture.tearDown() } }
    }

    @Test fun `inactive automatic delivery keeps unread result while explicit opening still works`() {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("LessplainInactiveDelivery", true).fixture
        fixture.setUp()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var service: UnderstandingService? = null
        try {
            val graph = UnderstandingGraph(listOf(GraphNode("n", "节点")), emptyList())
            val record = UnderstandingRepository(Path.of(requireNotNull(fixture.project.basePath))).use { repository ->
                UnderstandingCore(repository).present(4, "delivery", "异步结果", graph, "root")
            }
            val pending = PendingResult("record:${record.id}", record.id, null, 1)
            lateinit var current: UnderstandingService
            fun finishReads() {
                runBlocking {
                    withTimeout(5_000) { scope.coroutineContext.job.children.toList().flatMap { it.children.toList() }.joinAll() }
                }
                edt {} // 后台任务先排入平台队列，再等待该队列交付完毕。
            }
            edt {
                current = UnderstandingService(fixture.project, scope)
                service = current
                current.resultInbox.register(pending)
                current.showResult(pending, automatic = true)
            }
            finishReads()
            edt {
                assertEquals(PanelPage.HISTORY, current.panelPage)
                assertNull(current.panelRecordId)
                assertEquals(listOf(pending), current.resultInbox.pending())
                current.showResult(pending)
            }
            finishReads()
            edt {
                assertEquals(PanelPage.CANVAS, current.panelPage)
                assertEquals(record.id, current.panelRecordId)
                assertTrue(current.resultInbox.pending().isEmpty())
            }
        } finally {
            edt { service?.dispose(); fixture.tearDown() }
            scope.cancel()
        }
    }

    private fun edt(action: () -> Unit) = ApplicationManager.getApplication().invokeAndWait { uiRead(action) }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        var done = false
        while (!done && System.nanoTime() < deadline) { edt { done = condition() }; if (!done) Thread.sleep(10) }
        assertTrue(done)
    }
}
