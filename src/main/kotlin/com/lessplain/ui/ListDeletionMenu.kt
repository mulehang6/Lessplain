package com.lessplain.ui

import java.awt.Component
import java.awt.EventQueue
import java.awt.Point
import java.awt.event.KeyEvent
import javax.swing.JList
import javax.swing.JPopupMenu

/** 在显示菜单的那一刻冻结条目；鼠标空白与键盘当前项分别命中，不触发打开。 */
internal fun <T> installListDeletionMenu(list: JList<T>, label: String, enabled: () -> Boolean, present: (Component, List<NativeMenuItem>, Point) -> Unit = { owner, items, point -> showNativeMenu(owner, items, point) },
    delete: (T) -> Unit) {
    list.componentPopupMenu = object : JPopupMenu() {
        override fun show(invoker: Component, x: Int, y: Int) {
            val index = if (EventQueue.getCurrentEvent() is KeyEvent) list.selectedIndex else {
                list.locationToIndex(Point(x, y)).takeIf { it >= 0 && list.getCellBounds(it, it)?.contains(x, y) == true } ?: -1
            }
            if (index !in 0 until list.model.size) return
            list.selectedIndex = index
            val target = list.model.getElementAt(index)
            present(list, listOf(NativeMenuItem(label, enabled()) { delete(target) }), Point(x, y))
        }
    }
}
