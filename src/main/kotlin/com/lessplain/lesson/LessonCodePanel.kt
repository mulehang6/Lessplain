package com.lessplain.lesson

import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** 按软换行后的可见行计算高度；长示例在阅读页最多显示 24 行。 */
internal class LessonCodePanel(
    private val editor: EditorEx,
    private val reflow: (() -> Unit) -> Unit,
) : JPanel(BorderLayout()) {
    private var contentHeight = heightFor(editor.document.lineCount)
    private var queued = false

    init {
        isOpaque = false
        add(editor.component, BorderLayout.CENTER)
        editor.contentComponent.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = measureLater()
        })
    }

    override fun getPreferredSize(): Dimension = Dimension(640, contentHeight)

    override fun doLayout() {
        super.doLayout()
        editor.component.doLayout()
        measureLater()
    }

    private fun measureLater() {
        if (queued || editor.isDisposed) return
        queued = true
        SwingUtilities.invokeLater {
            queued = false
            if (editor.isDisposed) return@invokeLater
            val next = lessonUiRead { heightFor(editor.offsetToVisualPosition(editor.document.textLength).line + 1) }
            if (next != contentHeight) reflow { contentHeight = next; revalidate() }
        }
    }

    private fun heightFor(lines: Int): Int = editor.lineHeight * lines.coerceIn(1, 24) + JBUI.scale(12)
}
