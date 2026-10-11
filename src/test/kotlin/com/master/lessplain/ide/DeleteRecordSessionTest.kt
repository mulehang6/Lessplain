package com.master.lessplain.ide

import com.master.lessplain.ui.DeleteRecordState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeleteRecordSessionTest {
    private class Popup(val state: DeleteRecordState, val confirm: () -> Unit, val cancel: () -> Unit,
        val dismissed: () -> Unit) : DeleteRecordWindow {
        var releases = 0
        override fun show() = Unit
        override fun close() { releases++; dismissed() }
    }

    @Test fun `escape window close replacement and disposal cancel without accepting stale callbacks`() {
        val handles = mutableListOf<Popup>()
        val deleted = mutableListOf<String>()
        val session = DeleteRecordSession(deleted::add) { state, confirm, cancel, closed ->
            Popup(state, confirm, cancel, closed).also(handles::add)
        }
        session.show("first", "第一张")
        assertEquals("first", handles[0].state.recordId)
        assertEquals("第一张", handles[0].state.title)
        handles[0].cancel() // Esc 和取消按钮使用相同取消规则。
        assertEquals(1, handles[0].releases)
        session.show("second", "第二张")
        handles[1].close() // 窗口关闭按同一生命周期规则报告释放。
        session.show("third", "第三张")
        session.show("fourth", "第四张")
        assertEquals(1, handles[2].releases)
        handles[2].confirm()
        handles[2].dismissed()
        handles[3].confirm()
        handles[3].confirm()
        assertEquals(listOf("fourth"), deleted)
        assertEquals(1, handles[3].releases)
        session.show("fifth", "第五张")
        session.close()
        session.close()
        handles[4].confirm()
        session.show("sixth", "第六张")
        assertEquals(5, handles.size)
        assertEquals(1, handles[4].releases)
        assertEquals(listOf("fourth"), deleted)
    }

    @Test fun `opening failure releases ownership and synchronously closed popup releases returned handle`() {
        var failure = true
        var released = 0
        val session = DeleteRecordSession({ error("未确认不能删除") }) { _, _, _, closed ->
            if (failure) error("窗口已关闭")
            closed()
            object : DeleteRecordWindow {
                override fun show() = Unit
                override fun close() { released++ }
            }
        }
        assertFailsWith<IllegalStateException> { session.show("a", "A") }
        failure = false
        session.show("b", "B")
        assertEquals(1, released)
        session.close()
        assertEquals(1, released)
    }

    @Test fun `modal show owns its window before nested event loop disposes the session`() {
        var releases = 0
        var shown = 0
        lateinit var session: DeleteRecordSession
        session = DeleteRecordSession({ error("销毁不能删除") }) { _, confirm, _, closed ->
            object : DeleteRecordWindow {
                override fun show() {
                    shown++
                    session.close()
                    confirm()
                }
                override fun close() { releases++; closed() }
            }
        }
        session.show("a", "A")
        session.close()
        assertEquals(1, shown)
        assertEquals(1, releases)
    }
}
