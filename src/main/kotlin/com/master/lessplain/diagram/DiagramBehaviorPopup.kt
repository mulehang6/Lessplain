package com.master.lessplain.diagram

import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.master.lessplain.ui.clampPopupBounds
import com.master.lessplain.ui.nativePopupLocation
import com.master.lessplain.ui.setNativePopupBounds
import com.master.lessplain.ui.usableScreenBoundsAt
import java.awt.AWTEvent
import java.awt.Component
import java.awt.EventQueue
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.SwingUtilities

/** Owns one settings popup and keeps its outside-click cancellation from reopening on its trigger. */
internal class DiagramBehaviorPopup(
    diagram: UnderstandingDiagram,
    private val anchor: JComponent,
    private val popupFactory: (JComponent, JComponent, () -> Boolean) -> JBPopup = ::createBehaviorPopup,
    private val currentEvent: () -> AWTEvent? = EventQueue::getCurrentEvent,
) : Disposable {
    private val panel = DiagramBehaviorPanel(diagram, ::resizeToContent)
    private var popup: JBPopup? = null
    private var popupListener: JBPopupListener? = null
    private var closing = false
    private var disposed = false
    private val visibilityListener = HierarchyListener { event ->
        if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && !anchor.isShowing) close()
    }

    init {
        anchor.addHierarchyListener(visibilityListener)
    }

    fun toggle() {
        if (disposed) return
        if (popup?.isDisposed == false) {
            close()
            return
        }
        panel.refresh()
        val created = popupFactory(panel, panel.resetZoom, ::mayCancel)
        popup = created
        val listener = object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                if (popup === created) {
                    popup = null
                    popupListener = null
                }
                created.removeListener(this)
            }
        }
        popupListener = listener
        created.addListener(listener)
        try {
            created.showUnderneathOf(anchor)
        } catch (failure: RuntimeException) {
            close()
            throw failure
        }
    }

    fun refresh() = panel.refresh()

    private fun resizeToContent() {
        val current = popup?.takeIf { !it.isDisposed && it.isVisible } ?: return
        val location = nativePopupLocation(current)
        current.content.preferredSize = null
        current.pack(true, true)
        setNativePopupBounds(current, clampPopupBounds(location, current.size, usableScreenBoundsAt(location)))
    }

    private fun mayCancel(): Boolean {
        if (closing) return true
        val event = currentEvent() as? MouseEvent ?: return true
        val source = event.source as? Component ?: return true
        // The trigger action closes it once; the platform's earlier outside press must not close it first.
        return source !== anchor && !SwingUtilities.isDescendingFrom(source, anchor)
    }

    fun close() {
        val current = popup ?: return
        val listener = popupListener
        closing = true
        try {
            if (!current.isDisposed) current.cancel()
            // Native popups can temporarily veto cancellation just after opening or under a modal window.
            if (!current.isDisposed) current.dispose()
        } finally {
            listener?.let(current::removeListener)
            if (popup === current) {
                popup = null
                popupListener = null
            }
            closing = false
        }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        close()
        panel.close()
        anchor.removeHierarchyListener(visibilityListener)
    }
}

private fun createBehaviorPopup(content: JComponent, focus: JComponent, mayCancel: () -> Boolean): JBPopup =
    JBPopupFactory.getInstance().createComponentPopupBuilder(content, focus)
        .setFocusable(true)
        .setRequestFocus(true)
        .setCancelOnClickOutside(true)
        .setCancelKeyEnabled(true)
        .setCancelOnWindowDeactivation(true)
        .setCancelOnOtherWindowOpen(true)
        .setCancelCallback { mayCancel() }
        .createPopup()
