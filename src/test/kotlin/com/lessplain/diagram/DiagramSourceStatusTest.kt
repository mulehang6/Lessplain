package com.lessplain.diagram

import com.lessplain.model.GraphNode
import com.lessplain.model.UnderstandingGraph
import com.lessplain.model.UnderstandingRecord
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagramSourceStatusTest {
    @Test
    fun `failed source navigation exposes its reason and clearing selection removes it`() {
        SwingUtilities.invokeAndWait {
            val record = UnderstandingRecord("root", 1, null, null, "测试", "", null,
                UnderstandingGraph(listOf(GraphNode("a", "A")), emptyList()), 0)
            val diagram = createUnderstandingDiagram(record)
            try {
                diagram.setSize(600, 400)
                val status = diagram.sourceStatus
                val selection = "root" to "a"
                assertFalse(status.isVisible)
                for (message in listOf("源码定位失败：证据文本有多处匹配", "源码定位失败：当前文件中找不到对应证据",
                    "仅定位到所属函数，原片段已变化")) {
                    status.showForSelection(message, selection, selection)
                    diagram.doLayout()
                    assertTrue(status.isVisible)
                    assertEquals(message, status.label.text)
                    assertEquals(message, status.label.accessibleContext.accessibleName)
                    assertTrue(status.label.toolTipText.contains(message))
                    assertTrue(status.y + status.height < diagram.controls.previousButton.y)
                    assertTrue(status.x >= 0 && status.x + status.width <= diagram.width)
                }
                status.showForSelection(null, selection, selection)
                assertFalse(status.isVisible)
                assertEquals("", status.label.text)
                status.showForSelection("旧节点失败", selection, "root" to "b")
                assertFalse(status.isVisible)
                status.showForSelection("其他记录失败", selection, "other" to "a")
                assertFalse(status.isVisible)
                status.showForSelection("无节点失败", null, null to null)
                assertFalse(status.isVisible)
            } finally { diagram.dispose() }
        }
    }
}
