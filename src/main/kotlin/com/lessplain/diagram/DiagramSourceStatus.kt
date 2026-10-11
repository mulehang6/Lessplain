package com.lessplain.diagram

import com.intellij.ide.setToolTipText
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.util.ui.JBUI
import com.lessplain.ide.UnderstandingService
import com.lessplain.ui.SourceNode
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel

/** 编辑器和工具窗口画布共用安静且可访问的源码状态提示。 */
internal class DiagramSourceStatus : JPanel(BorderLayout()) {
    internal val label = JLabel()

    init {
        isFocusable = false
        border = JBUI.Borders.empty(4, 8)
        add(label, BorderLayout.CENTER)
        isVisible = false
    }

    fun showMessage(message: String?) {
        if (label.text == message.orEmpty() && isVisible == (message != null)) return
        label.text = message.orEmpty()
        if (message != null) label.setToolTipText(HtmlChunk.text(message)) else label.toolTipText = null
        label.accessibleContext.accessibleName = message.orEmpty()
        isVisible = message != null
        parent?.revalidate()
        repaint()
    }

    fun showForSelection(message: String?, source: Pair<String, String>?, selection: Pair<String?, String?>) {
        showMessage(message.takeIf { source != null && source == selection })
    }

    fun arrange(availableWidth: Int, bottom: Int) {
        val gap = JBUI.scale(8)
        val margin = JBUI.scale(12)
        val h = preferredSize.height
        setBounds(margin.coerceAtMost(availableWidth), (bottom - gap - h).coerceAtLeast(0),
            (availableWidth - margin * 2).coerceAtLeast(0), h)
    }
}

/** 定位提示仅属于选中的源码节点，无关画布保持无提示。 */
internal fun UnderstandingDiagram.bindSourceLocationFeedback(
    service: UnderstandingService,
    sourceAt: (String) -> SourceNode?,
): AutoCloseable {
    var closed = false
    fun refresh() {
        if (closed || disposed) return
        val source = currentNodeId?.let(sourceAt)
        sourceStatus.showForSelection(service.sourceLocationMessage,
            source?.let { it.record.id to it.nodeId }, service.activeRecordId to service.activeNodeId)
    }
    val changed = service.onChanged(::refresh)
    val selected = service.onSelectionChanged { _, _ -> refresh() }
    refresh()
    return AutoCloseable {
        closed = true
        changed.close()
        selected.close()
        sourceStatus.showMessage(null)
    }
}
