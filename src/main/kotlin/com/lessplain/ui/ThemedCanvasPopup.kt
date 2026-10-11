package com.lessplain.ui

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

/** 使用公开平台弹层承载 IDE 内置 Jewel 主题，不显示原生对话框标题栏。 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal class ThemedCanvasPopup(
    owner: Component,
    point: Point,
    size: Dimension,
    requestFocus: Boolean = true,
    title: String? = null,
    private val onClosed: () -> Unit = {},
    mayCancel: () -> Boolean = { true },
    centerInProject: Project? = null,
    preferredSize: (() -> Dimension)? = null,
    content: @Composable () -> Unit,
) : AutoCloseable {
    private lateinit var composePanel: ComposePanel
    private val panel = JewelComposePanel(config = {
        composePanel = this
        this.preferredSize = preferredSize?.invoke() ?: JBUI.size(size.width, size.height)
    }, content = content)
    private var closed = false
    val popup: JBPopup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, panel)
        .apply { if (title != null) setTitle(title) }
        .setFocusable(true)
        .setRequestFocus(requestFocus)
        .setCancelOnClickOutside(true)
        .setCancelKeyEnabled(true)
        .setCancelOnWindowDeactivation(true)
        .setCancelCallback { mayCancel() }
        .createPopup()
    private val fonts = preferredSize?.let { measure ->
        CanvasUiFonts(listOf(panel), onRefreshed = {
            resizeToContent(measure)
        })
    }

    init {
        popup.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) { release() }
        })
        try {
            if (centerInProject == null) popup.show(RelativePoint(owner, point))
            else popup.showCenteredInCurrentWindow(centerInProject)
            preferredSize?.let(::resizeToContent)
        } catch (failure: RuntimeException) {
            close()
            throw failure
        }
    }

    private fun resizeToContent(measure: () -> Dimension) {
        if (closed || popup.isDisposed || !popup.isVisible) return
        val location = nativePopupLocation(popup)
        panel.preferredSize = measure()
        popup.content.preferredSize = null
        popup.pack(true, true)
        val bounds = clampPopupBounds(location, popup.size, usableScreenBoundsAt(location))
        setNativePopupBounds(popup, bounds)
    }

    private fun release() {
        if (closed) return
        closed = true
        fonts?.close()
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
