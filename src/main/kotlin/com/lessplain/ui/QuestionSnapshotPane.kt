package com.lessplain.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.lessplain.model.SourceAnchor
import java.awt.BorderLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JPanel
import javax.swing.JTextArea

/** 节点注释与提问回看共用完整只读正文、代码视口和字体生命周期。 */
internal class QuestionSnapshotPane(private val project: Project) : JPanel(BorderLayout()), AutoCloseable {
    val text = JTextArea().apply {
        isEditable = false; lineWrap = true; wrapStyleWord = true
        border = JBUI.Borders.empty()
        accessibleContext.accessibleName = "问题或注释全文"
    }
    private val body = JPanel(BorderLayout(0, JBUI.scale(PopupUi.GAP))).apply { isOpaque = false }
    private var anchor: SourceAnchor? = null
    var codePreview: SelectedCodePreviewPanel? = null
        private set
    private var closed = false
    private val fonts = CanvasUiFonts(listOf(text), ::sizePreview)

    init {
        isOpaque = false
        body.add(text, BorderLayout.NORTH)
        add(JBScrollPane(body).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = sizePreview()
        })
    }

    fun showSnapshot(value: String, source: SourceAnchor?) {
        if (closed) return
        if (text.text != value) { text.text = value; text.caretPosition = 0 }
        if (anchor != source) {
            codePreview?.let { body.remove(it); it.close() }
            anchor = source
            codePreview = source?.let { SelectedCodePreviewPanel(SelectedCodePreview(project, it), expandable = false, changed = ::sizePreview) }
            codePreview?.let { body.add(it, BorderLayout.CENTER) }
        }
        sizePreview()
        repaint()
    }

    private fun sizePreview() {
        if (closed) return
        codePreview?.heightFor((width - JBUI.scale(16)).coerceAtLeast(JBUI.scale(260)), JBUI.scale(320))
        revalidate()
    }

    override fun close() {
        if (closed) return
        closed = true
        fonts.close(); codePreview?.close(); codePreview = null
    }
}
