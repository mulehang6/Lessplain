package com.lessplain.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Point

/** 节点分页详情复用教程批注的原生外壳，窗口本身可移动和缩放。 */
internal class NodeNotePopup(
    project: Project,
    owner: Component,
    point: Point,
    private val panel: NodeNotePanel,
    onClosed: () -> Unit,
    canClose: () -> Boolean,
) : AutoCloseable {
    private val popup = try { nativePopupBuilder(panel, panel, "节点注释", cancelOnOutside = false, canClose = canClose)
        .setResizable(true).setMinSize(JBUI.size(360, 260))
        .setDimensionServiceKey(project, "Lessplain.NodeAnnotations", false)
        .createPopup()
    } catch (failure: Throwable) { panel.close(); throw failure }

    init {
        popup.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) { panel.close(); onClosed() }
        })
        try { popup.show(RelativePoint(owner, point)) } catch (failure: Throwable) { close(); throw failure }
    }
    fun refresh(state: NodeNoteState) = panel.refresh(state)
    fun requestFocus() {
        javax.swing.SwingUtilities.getWindowAncestor(popup.content)?.toFront()
        panel.requestFocusInWindow()
    }
    override fun close() {
        popup.cancel()
        if (!popup.isDisposed) popup.dispose()
        panel.close()
    }
}
