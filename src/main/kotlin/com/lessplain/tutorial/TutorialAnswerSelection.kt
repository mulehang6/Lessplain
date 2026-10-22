package com.lessplain.tutorial

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.lessplain.ui.NativeMenuItem
import com.lessplain.ui.installNativeMenu
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.Timer

/** 回答的局部追问只在有选区时出现，不在笔记或空回答底部留下按钮。 */
internal class TutorialAnswerSelection(private val answer: JTextArea, private val followUp: (String) -> Unit) : AutoCloseable {
    private var popup: JBPopup? = null
    private var dragging = false
    private val timer = Timer(140) { show() }.apply { isRepeats = false }
    private val caret = javax.swing.event.CaretListener { if (!dragging) timer.restart() }
    private val mouse = object : MouseAdapter() {
        override fun mousePressed(event: MouseEvent) { dismiss(); dragging = !SwingUtilities.isRightMouseButton(event) }
        override fun mouseReleased(event: MouseEvent) { dragging = false; if (!SwingUtilities.isRightMouseButton(event)) timer.restart() }
    }
    init {
        answer.addCaretListener(caret); answer.addMouseListener(mouse)
        installNativeMenu(answer) {
            dismiss()
            listOf(NativeMenuItem("复制", !answer.selectedText.isNullOrEmpty()) { answer.copy() },
                NativeMenuItem("全选") { answer.selectAll() },
                NativeMenuItem("追问所选内容", !answer.selectedText.isNullOrEmpty()) { answer.selectedText?.let(followUp) })
        }
    }
    private fun show() {
        dismiss()
        val selected = answer.selectedText?.takeIf { it.isNotEmpty() } ?: return
        if (!answer.isShowing || dragging) return
        val rect = answer.modelToView2D(answer.selectionEnd)?.bounds ?: return
        val button = JButton("继续追问").apply { addActionListener { dismiss(); followUp(selected) } }
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(button, null).setFocusable(false).setRequestFocus(false)
            .createPopup().also { it.show(RelativePoint(answer, Point(rect.x, rect.y + rect.height))) }
    }
    fun dismiss() { timer.stop(); popup?.cancel(); popup = null }
    override fun close() { dismiss(); answer.removeCaretListener(caret); answer.removeMouseListener(mouse); answer.componentPopupMenu = null }
}
