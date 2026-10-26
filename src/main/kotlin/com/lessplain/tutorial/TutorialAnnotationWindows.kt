package com.lessplain.tutorial

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.util.ui.JBUI
import com.lessplain.ui.nativePopupBuilder
import com.lessplain.ui.NativeMenuItem
import com.lessplain.ui.showNativeMenu
import java.awt.Component
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import javax.swing.SwingUtilities

/** 每个原范围、类别各一个持久主题浮窗，窗口关闭与批注删除互不混淆。 */
internal class TutorialAnnotationWindows(
    private val project: Project,
    private val tutorialId: String,
    private val service: TutorialService,
    private val owner: Component,
) : AutoCloseable {
    private val windows = mutableListOf<AnnotationWindow>()
    private var active = true
    private val showing = HierarchyListener { event ->
        if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) windows.toList().forEach { it.visibility() }
    }
    init { owner.addHierarchyListener(showing) }

    fun create(start: Int, end: Int, kind: String) = open(service.annotationDraft(tutorialId, start, end, kind))
    fun open(annotation: TutorialAnnotation) {
        windows.firstOrNull { it.panel.matches(annotation) }?.let { it.focus(annotation); return }
        AnnotationWindow(annotation).also { windows += it; it.visibility() }
    }
    fun index(anchor: Component) {
        showNativeMenu(anchor, service.store.annotations(tutorialId).filterNot { it.isEmptyDraft }.map { annotation ->
            NativeMenuItem("${if (annotation.kind == "note") "笔记" else "追问"} · ${if (annotation.orphaned) "原文已删除 · " else ""}${annotation.text.ifBlank { annotation.quote }.take(55)}") { open(annotation) }
        }.ifEmpty { listOf(NativeMenuItem("暂无批注", false) {}) }, project = project)
    }
    fun active(value: Boolean) { active = value; windows.toList().forEach { it.visibility() } }
    override fun close() { owner.removeHierarchyListener(showing); windows.toList().forEach { it.close() } }

    private inner class AnnotationWindow(initial: TutorialAnnotation) : AutoCloseable {
        private var closed = false
        private var shown = false
        val panel = TutorialAnnotationPanel(service, initial, ::open, { close() })
        val popup: JBPopup = nativePopupBuilder(panel, panel.input, if (initial.kind == "note") "笔记" else "追问", cancelOnOutside = false) { !panel.composition.active }
            .setResizable(true)
            .setBelongsToGlobalPopupStack(false).setMayBeParent(true)
            .setMinSize(JBUI.size(380, 260)).setDimensionServiceKey(project, "Lessplain.Annotation.${initial.kind}", false)
            .createPopup()
        private val subscription = service.onChanged { id -> if (id == tutorialId && !closed) panel.refresh(false) }

        init {
            panel.registerKeyboardAction({ popup.cancel() }, javax.swing.KeyStroke.getKeyStroke("ESCAPE"), javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            popup.addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) = close()
            })
        }
        fun focus(item: TutorialAnnotation) {
            panel.focus(item)
            visibility()
            SwingUtilities.getWindowAncestor(popup.content)?.toFront()
            panel.input.requestFocusInWindow()
        }
        fun visibility() {
            if (closed) return
            val visible = active && owner.isShowing
            if (!shown && visible) { popup.showInCenterOf(owner); shown = true }
            else if (shown) popup.setUiVisible(visible)
        }
        override fun close() {
            if (closed) return
            closed = true
            subscription.close(); panel.close()
            popup.cancel()
            if (!popup.isDisposed) com.intellij.openapi.util.Disposer.dispose(popup)
            windows.remove(this)
        }
    }
}
