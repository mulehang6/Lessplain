package com.master.lessplain.ui

import com.intellij.util.ui.JBFont
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.UIManager

/** Native controls use the same UI font as Jewel's IDE bridge, including open-panel settings changes. */
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
