package com.master.understanding.diagram

import com.master.understanding.model.UnderstandingGraph
import com.master.understanding.model.UnderstandingRecord
import com.master.understanding.model.GraphNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import javax.swing.JButton
import javax.swing.SwingUtilities

class DiagramClusterTextTest {
    @Test
    fun `question group title wraps while prose answer is absent from the canvas`() {
        val title = "后台用户名密码登录从请求到会话与 Cookie 是怎么走的？失败会发生什么？".repeat(3)
        val answer = "会话保存登录状态。".repeat(80)
        val record = UnderstandingRecord("root", 1, null, null, title, answer, null,
            UnderstandingGraph(emptyList(), emptyList()), 0)
        val cluster = DiagramCluster("question", record.question, record.graph.nodes.mapTo(linkedSetOf()) { it.id })
        val layout = layoutUnderstandingClusters(record.graph, listOf(cluster))
        val placed = layout.clusters.single()

        assertEquals(title, placed.textLayout.titleLines.joinToString(""))
        assertTrue(placed.textLayout.titleLines.size > 1)
        assertTrue(DiagramClusterText.measure(title, 260.0).titleLines.size > 1)
        assertTrue(placed.textLayout.height <= placed.bounds.height)
    }

    @Test
    fun `graph cluster never exposes a prose answer`() {
        val graph = UnderstandingGraph(listOf(GraphNode("start", "读取参数")), emptyList())
        val record = UnderstandingRecord("root", 1, null, null, "登录流程？",
            "控制器读取参数后查用户。".repeat(50), null, graph, 0)
        val cluster = DiagramCluster("question", record.question, record.graph.nodes.mapTo(linkedSetOf()) { it.id })
        val collapsed = layoutUnderstandingClusters(graph, listOf(cluster)).clusters.single()
        assertEquals(cluster.title, collapsed.textLayout.titleLines.joinToString(""))
    }

    @Test
    fun `cluster navigation appears only when there is another cluster`() {
        SwingUtilities.invokeAndWait {
            val record = UnderstandingRecord("root", 1, null, null, "问题", "回答", null,
                UnderstandingGraph(emptyList(), emptyList()), 0)
            val root = DiagramCluster("question", record.question, record.graph.nodes.mapTo(linkedSetOf()) { it.id })
            fun visibleSideButtons(clusters: List<DiagramCluster>): Int {
                val diagram = createUnderstandingDiagram(record, clusters = clusters)
                return try {
                    diagram.setSize(140, 420)
                    diagram.doLayout()
                    val controls = diagram.components.filterIsInstance<UnderstandingDiagramControls>().single()
                    assertTrue(controls.sideControls.x + controls.sideControls.width <= diagram.width)
                    controls.sideControls.components.filterIsInstance<JButton>().count { it.isVisible }
                } finally {
                    diagram.dispose()
                }
            }
            assertEquals(1, visibleSideButtons(listOf(root)))
            assertEquals(2, visibleSideButtons(listOf(root, DiagramCluster("follow-up", "追问", emptySet()))))
        }
    }
}
