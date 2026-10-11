package com.lessplain.ui

import com.intellij.util.ui.JBFont
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.UIManager

/** 原生控件与 Jewel 的 IDE 桥使用相同界面字体，包括面板打开时的设置变化。 */
internal class CanvasUiFonts(
    private val body: List<JComponent>,
    private val onRefreshed: () -> Unit = {},
) : AutoCloseable {
    private var closed = false
    private var defaults = UIManager.getDefaults()
    private val defaultsChanged = PropertyChangeListener { event ->
        if (event.propertyName == "Label.font") scheduleRefresh()
    }
    private val lookAndFeelChanged = PropertyChangeListener { event ->
        if (event.propertyName == "lookAndFeel") {
            defaults.removePropertyChangeListener(defaultsChanged)
            defaults = UIManager.getDefaults()
            defaults.addPropertyChangeListener(defaultsChanged)
            scheduleRefresh()
        }
    }

    init {
        defaults.addPropertyChangeListener(defaultsChanged)
        UIManager.addPropertyChangeListener(lookAndFeelChanged)
        applyFonts()
    }

    fun refresh() {
        if (closed) return
        applyFonts()
        onRefreshed()
    }

    private fun applyFonts() {
        body.forEach {
            it.font = JBFont.regular().asUIResource()
            it.revalidate()
            it.repaint()
        }
    }

    private fun scheduleRefresh() { SwingUtilities.invokeLater { refresh() } }

    override fun close() {
        if (closed) return
        closed = true
        defaults.removePropertyChangeListener(defaultsChanged)
        UIManager.removePropertyChangeListener(lookAndFeelChanged)
    }
}
