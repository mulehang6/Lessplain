package com.master.understanding.ui

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import javax.swing.SwingUtilities

/** Native editor popup permits temporary IME-window activation and owns all input listeners. */
internal class CanvasEditorPopup(
    owner: Component,
    point: Point,
    size: Dimension,
    title: String,
    draft: CanvasTextDraft,
    multiline: Boolean,
    save: (String) -> Unit,
    private val onClosed: () -> Unit,
) : AutoCloseable {
    private val width = JBUI.scale(size.width).coerceAtLeast(1)
    private var closed = false
    private var resizeQueued = false
    private val editor = CanvasTextEditor(
        title = title,
        draft = draft,
        multiline = multiline,
        save = save,
        saved = ::close,
        dismiss = ::close,
        cancel = { draft.retain = false; close() },
        onMetricsChanged = ::scheduleResize,
    )
    private val popup: JBPopup = JBPopupFactory.getInstance().createComponentPopupBuilder(editor, editor.input)
        .setTitle(title)
        .setMovable(true)
        .setResizable(false)
        .setLocateWithinScreenBounds(true)
        .setFocusable(true)
        .setRequestFocus(true)
        .setCancelOnClickOutside(true)
        .setCancelKeyEnabled(false)
        .setCancelOnWindowDeactivation(false)
        .setCancelCallback { !editor.composition.active }
        .createPopup()

    init {
        editor.preferredSize = editor.preferredSizeFor(width, Int.MAX_VALUE)
        popup.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                release()
            }
        })
        try {
            popup.show(RelativePoint(owner, point))
            resizeToContent()
            SwingUtilities.invokeLater {
                if (!closed && !popup.isDisposed) editor.input.requestFocusInWindow()
            }
        } catch (failure: RuntimeException) {
            close()
            throw failure
        }
    }

    override fun close() {
        if (!popup.isDisposed) popup.cancel()
        if (!popup.isDisposed) popup.dispose()
        release()
    }

    private fun scheduleResize() {
        if (closed || popup.isDisposed || !popup.isVisible || resizeQueued) return
        resizeQueued = true
        SwingUtilities.invokeLater {
            resizeQueued = false
            if (!closed && !popup.isDisposed && popup.isVisible) resizeToContent()
        }
    }

    private fun resizeToContent() {
        if (closed || popup.isDisposed || !popup.isVisible) return
        val location = nativePopupLocation(popup)
        val screen = usableScreenBoundsAt(location)
        val currentSize = popup.size
        val contentSize = editor.size.takeIf { it.width > 0 && it.height > 0 } ?: editor.preferredSize
        val popupChromeWidth = (currentSize.width - contentSize.width).coerceAtLeast(0)
        val popupChromeHeight = (currentSize.height - contentSize.height).coerceAtLeast(0)
        val screenMargin = JBUI.scale(8)
        val contentWidth = minOf(width, (screen.width - popupChromeWidth - screenMargin * 2).coerceAtLeast(1))
        val availableContentHeight = (screen.height - popupChromeHeight - screenMargin * 2).coerceAtLeast(1)

        editor.preferredSize = editor.preferredSizeFor(contentWidth, availableContentHeight)
        editor.revalidate()
        popup.content.preferredSize = null
        popup.pack(true, true)

        val bounds = clampPopupBounds(location, popup.size, screen)
        setNativePopupBounds(popup, bounds)
    }

    private fun release() {
        if (closed) return
        closed = true
        editor.close()
        onClosed()
    }
}

/** Keeps the current top-left point while resizing, including on monitors left of the primary display. */
internal fun clampPopupBounds(location: Point, size: Dimension, screen: Rectangle): Rectangle {
    val width = size.width.coerceAtLeast(1).coerceAtMost(screen.width.coerceAtLeast(1))
    val height = size.height.coerceAtLeast(1).coerceAtMost(screen.height.coerceAtLeast(1))
    val maxX = screen.x + screen.width - width
    val maxY = screen.y + screen.height - height
    val x = location.x.coerceIn(screen.x, maxX.coerceAtLeast(screen.x))
    val y = location.y.coerceIn(screen.y, maxY.coerceAtLeast(screen.y))
    return Rectangle(x, y, width, height)
}

/** Public AWT monitor bounds shared by native popup hosts, excluding taskbars. */
internal fun usableScreenBoundsAt(point: Point): Rectangle {
    val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val configuration = environment.screenDevices
        .asSequence()
        .flatMap { it.configurations.asSequence() }
        .firstOrNull { it.bounds.contains(point) }
        ?: environment.defaultScreenDevice.defaultConfiguration
    return configuration.usableBounds()
}

private fun GraphicsConfiguration.usableBounds(): Rectangle {
    val bounds = Rectangle(bounds)
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(this)
    bounds.x += insets.left
    bounds.y += insets.top
    bounds.width = (bounds.width - insets.left - insets.right).coerceAtLeast(1)
    bounds.height = (bounds.height - insets.top - insets.bottom).coerceAtLeast(1)
    return bounds
}
