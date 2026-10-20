package com.lessplain.diagram

import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.lessplain.model.GraphEdge
import java.awt.Dimension
import java.awt.Point
import com.lessplain.ui.NativeMenuItem
import com.lessplain.ui.showNativeMenu

/** 右键查看只读依据，不触发节点选择和阅读路径变更。 */
internal fun UnderstandingDiagram.showEdgeMenu(edge: GraphEdge, point: Point) {
    showNativeMenu(this, listOf(NativeMenuItem("查看依据") { showEdgeEvidence(edge, point) }), point)
}

internal fun UnderstandingDiagram.showEdgeEvidence(edge: GraphEdge, point: Point) {
    val text = JBTextArea(edgeEvidenceText(edge, record.graph)).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        caretPosition = 0
        border = JBUI.Borders.empty(10)
        accessibleContext.accessibleName = "边的完整依据"
    }
    val pane = JBScrollPane(text).apply {
        preferredSize = Dimension(JBUI.scale(520), JBUI.scale(360))
        horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    }
    val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(pane, text)
        .setTitle("查看依据").setResizable(true).setMovable(true)
        .setLocateWithinScreenBounds(true).setRequestFocus(true).createPopup()
    overlays.replaceEdgeDetails(AutoCloseable { if (!popup.isDisposed) { popup.cancel(); popup.dispose() } })
    popup.show(RelativePoint(this, point))
}
