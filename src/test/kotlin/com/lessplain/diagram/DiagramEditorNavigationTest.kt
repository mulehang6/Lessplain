package com.lessplain.diagram

import com.lessplain.ide.GraphLayerKey
import com.lessplain.ide.GraphViewStateStore
import com.lessplain.ide.GraphLayoutPreference
import com.lessplain.model.*
import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities
import kotlin.test.*

class DiagramEditorNavigationTest {
    @Test fun `root and follow up editors keep local navigation while layout and browse remain shared`() {
        SwingUtilities.invokeAndWait {
            val record = UnderstandingRecord("root", 1, null, null, "测试", "", null,
                UnderstandingGraph(listOf(GraphNode("a", "A")), emptyList()), 0)
            for (key in listOf(GraphLayerKey(record.id), GraphLayerKey(record.id, "a"))) {
                val values = mutableMapOf<String, String>()
                val store = GraphViewStateStore(values::get) { name, value -> values[name] = value }
                val window = createUnderstandingDiagram(record)
                val editor = createUnderstandingDiagram(record)
                val second = createUnderstandingDiagram(record)
                try {
                    window.bindGraphState(store, key)
                    editor.bindGraphState(store, key, NavigationStateScope.EDITOR_LOCAL)
                    second.bindGraphState(store, key, NavigationStateScope.EDITOR_LOCAL)
                    assertFalse(window.navigationPinned)
                    assertTrue(editor.navigationPinned)
                    assertTrue(second.navigationPinned)
                    editor.navigationPinned = false
                    assertFalse(store.get(key).navigationPinned)
                    window.navigationPinned = true
                    assertFalse(editor.navigationPinned)
                    assertTrue(second.navigationPinned)
                    store.update(key) { it.copy(flowEnabled = false, autoFollowNode = false,
                        layoutPreference = GraphLayoutPreference.HORIZONTAL) }
                    assertFalse(editor.flowEnabled)
                    assertFalse(second.autoFollowNode)
                    assertEquals(GraphLayoutPreference.HORIZONTAL, editor.layoutPreference)
                    assertFalse(editor.navigationPinned, "普通共享状态更新不能重置手动开启的跳转")
                    saveGraphViewState(store, key, editor, emptyList(), "a")
                    assertTrue(store.get(key).navigationPinned, "保存位置不能覆盖工具窗口开关")
                    assertEquals("a", store.get(key).browse?.nodeId)
                } finally { window.dispose(); editor.dispose(); second.dispose() }
            }
        }
    }
}
