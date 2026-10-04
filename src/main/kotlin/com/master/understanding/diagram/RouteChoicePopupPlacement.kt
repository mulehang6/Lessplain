package com.master.understanding.diagram

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import javax.swing.JComponent
import javax.swing.JViewport
import javax.swing.SwingUtilities
import javax.swing.event.ChangeListener

/** Fits beside the source caption, limiting both dimensions to the editor viewport. */
internal fun routeChoicePopupBounds(anchor: Point, viewport: Rectangle, preferred: Dimension): Rectangle {
    val width = preferred.width.coerceIn(1, viewport.width.coerceAtLeast(1))
    val height = preferred.height.coerceIn(1, viewport.height.coerceAtLeast(1))
    return Rectangle(
        anchor.x.coerceIn(viewport.x, viewport.x + viewport.width - width),
        anchor.y.coerceIn(viewport.y, viewport.y + viewport.height - height),
        width, height,
    )
}

/** An editor scroll, resize or release ends the popup instead of leaving an obsolete anchor. */
internal class RouteChoicePopupPlacement(
    private val popup: JBPopup,
    private val component: Component,
    private val anchor: Point,
    private val selection: RouteChoiceSelection,
) : AutoCloseable {
    private var closed = false
    private val viewport = SwingUtilities.getAncestorOfClass(JViewport::class.java, component) as? JViewport
    private val scroll = ChangeListener { cancel() }
    private val resize = object : ComponentAdapter() {
        override fun componentResized(event: ComponentEvent) = cancel()
        override fun componentHidden(event: ComponentEvent) = cancel()
    }
    private val hierarchy = HierarchyListener { event ->
        if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && !component.isShowing) cancel()
    }

    fun show() {
        if (!component.isShowing) {
            cancel()
            return
        }
        val visible = (component as? JComponent)?.visibleRect ?: Rectangle(component.size)
        val preferred = popup.content.preferredSize
        val bounds = routeChoicePopupBounds(Point(anchor.x + JBUI.scale(6), anchor.y), visible, preferred)
        popup.setMinimumSize(Dimension(1, 1))
        popup.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) { detach() }
        })
        popup.show(RelativePoint(component, bounds.location))
        if (popup.isDisposed) return
        popup.size = bounds.size
        // Screen fitting must not move the list onto the graph.
        val screen = Point(bounds.location)
        SwingUtilities.convertPointToScreen(screen, component)
        popup.setLocation(screen)
        viewport?.addChangeListener(scroll)
        component.addComponentListener(resize)
        component.addHierarchyListener(hierarchy)
    }

    private fun cancel() {
        selection.choose(null)
        close()
    }

    private fun detach() {
        viewport?.removeChangeListener(scroll)
        component.removeComponentListener(resize)
        component.removeHierarchyListener(hierarchy)
    }

    override fun close() {
        if (closed) return
        closed = true
        selection.close()
        detach()
        if (!popup.isDisposed) popup.cancel()
    }
}
