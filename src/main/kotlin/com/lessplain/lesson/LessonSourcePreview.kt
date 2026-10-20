package com.lessplain.lesson

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane

/** 预览只读取保存的解释，明确点击后才离开源码并定位教材。 */
internal fun lessonSourcePreview(service: LessonService, entries: List<LessonSourceEntry>, entered: () -> Unit, exited: () -> Unit): JBPopup {
    val body = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(8, 12)
    }
    val display = entries.distinctBy { listOf(it.lessonId, it.blockId, it.owner, it.focus) }
    display.forEach { entry ->
        body.add(JLabel(entry.title).apply { border = JBUI.Borders.empty(4, 0) })
        val notes = entry.annotations.distinctBy { it.id }
        val count = "${entry.label} · 教材" +
            if (notes.isEmpty()) "" else " · 笔记 ${notes.count { it.note.isNotBlank() }} · 追问 ${notes.count { it.question.isNotBlank() }}"
        body.add(selectableLessonText(count))
        if (entry.excerpt.isNotBlank()) body.add(selectableLessonText(entry.excerpt))
        notes.take(3).forEach { note ->
            body.add(selectableLessonText((note.note.ifBlank { note.question }).take(180)))
            body.add(JButton(if (note.question.isNotBlank()) "打开这条追问" else "打开这条笔记").apply {
                addActionListener { service.openLesson(entry.lessonId,
                    LessonDestination(entry.blockId, entry.owner, note.id)) }
            })
        }
        body.add(JButton("打开教材位置").apply {
            addActionListener { service.openLesson(entry.lessonId, LessonDestination(entry.blockId, entry.owner)) }
        })
    }
    val pane = JScrollPane(body).apply {
        border = JBUI.Borders.empty()
        preferredSize = Dimension(JBUI.scale(430), body.preferredSize.height.coerceAtMost(JBUI.scale(460)))
    }
    val listener = object : MouseAdapter() {
        override fun mouseEntered(event: MouseEvent) = entered()
        override fun mouseExited(event: MouseEvent) = exited()
    }
    // Swing 子组件之间移动也会触发 exited，下一次 entered 会取消延迟关闭。
    fun install(component: java.awt.Component) {
        component.addMouseListener(listener)
        if (component is java.awt.Container) component.components.forEach(::install)
    }
    install(pane)
    return JBPopupFactory.getInstance().createComponentPopupBuilder(pane, null)
        .setRequestFocus(false).setFocusable(true).setCancelOnClickOutside(true)
        .setCancelOnOtherWindowOpen(true).setLocateWithinScreenBounds(true).createPopup()
}
