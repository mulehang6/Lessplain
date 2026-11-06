package com.lessplain.tutorial

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class ReaderPreferencesTest {
    @Test
    fun `empty preferences need no assessment and explicit updates survive reopening`() {
        val values = mutableMapOf<String, String>()
        fun store() = ReaderPreferencesStore(values::get) { key, value -> values[key] = value }
        assertEquals(ReaderPreferences(), store().snapshot())
        store().save(ReaderPreferences("  先解释对象\r\n少翻译判断  ", " 不熟悉会话树 "))
        assertEquals(ReaderPreferences("先解释对象\n少翻译判断", "不熟悉会话树"), store().snapshot())
        store().save(ReaderPreferences())
        assertEquals(ReaderPreferences(), store().snapshot())
    }

    @Test
    fun `only context entry points return current preferences without rewriting frozen source`() {
        TutorialMcpFixture("ReaderPreferences").use { fixture ->
            val store = ReaderPreferencesStore(fixture.project)
            val previous = store.snapshot()
            try {
                fixture.onEdt { store.save(ReaderPreferences("解释项目对象", "不熟悉会话树")) }
                val source = fixture.source()
                assertFalse(source.containsKey("readerPreferences"))
                val captured = fixture.call("lessplain_context_capture", buildJsonObject { put("question", "看这个入口") })
                assertEquals("解释项目对象", captured["readerPreferences"]!!.jsonObject["guidance"]!!.jsonPrimitive.content)
                val id = captured["contextId"]!!.jsonPrimitive.content
                fixture.onEdt { store.save(ReaderPreferences("简单判断不用展开", "")) }
                val read = fixture.call("lessplain_context_read", buildJsonObject { put("contextId", id) })
                assertEquals(captured["context"], read["context"])
                assertEquals("简单判断不用展开", read["readerPreferences"]!!.jsonObject["guidance"]!!.jsonPrimitive.content)
                val sources = assertNotNull(read["sources"])
                assertEquals(captured["sources"], sources)
                val materials = fixture.call("lessplain_materials_list", buildJsonObject { put("kind", "tutorial") })
                assertFalse(materials.containsKey("readerPreferences"))
            } finally { fixture.onEdt { store.save(previous) } }
        }
    }
}
