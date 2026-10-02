package com.master.understanding.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.awt.ComposePanel
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.project.Project
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
import java.awt.Point
import org.jetbrains.jewel.bridge.JewelComposePanel

/** Public platform popup hosting the IDE's bundled Jewel theme, without native dialog chrome. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal class ThemedCanvasPopup(
    owner: Component,
    point: Point,
    size: Dimension,
    requestFocus: Boolean = true,
    private val onClosed: () -> Unit = {},
    mayCancel: () -> Boolean = { true },
    centerInProject: Project? = null,
    content: @Composable () -> Unit,
) : AutoCloseable {
    private lateinit var composePanel: ComposePanel
    private val panel = JewelComposePanel(config = {
        composePanel = this
        preferredSize = JBUI.size(size.width, size.height)
    }, content = content)
    private var closed = false
    val popup: JBPopup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, panel)
        .setFocusable(true)
        .setRequestFocus(requestFocus)
        .setCancelOnClickOutside(true)
        .setCancelKeyEnabled(true)
        .setCancelOnWindowDeactivation(true)
        .setCancelCallback { mayCancel() }
        .createPopup()

    init {
        popup.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) { release() }
        })
        try {
            if (centerInProject == null) popup.show(RelativePoint(owner, point))
            else popup.showCenteredInCurrentWindow(centerInProject)
        } catch (failure: RuntimeException) {
            close()
            throw failure
        }
    }

    private fun release() {
        if (closed) return
        closed = true
        panel.removeAll()
        composePanel.dispose()
        onClosed()
    }

    override fun close() {
        if (!popup.isDisposed) popup.cancel()
        if (!popup.isDisposed) popup.dispose()
        release()
    }

    fun requestFocus() { composePanel.requestFocusInWindow() }
}
