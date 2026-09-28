package com.master.understanding.ui

import java.awt.KeyboardFocusManager
import java.awt.MouseInfo
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities

/** Native heavyweight popup stays above the embedded Swing graph. */
internal fun showGraphPopup(items: List<Pair<String, () -> Unit>>) {
    if (!SwingUtilities.isEventDispatchThread()) {
        SwingUtilities.invokeLater { showGraphPopup(items) }
        return
    }
    val root = (KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow as? RootPaneContainer)
        ?.rootPane ?: return
    val location = MouseInfo.getPointerInfo()?.location ?: return
    SwingUtilities.convertPointFromScreen(location, root)
    JPopupMenu().apply {
        isLightWeightPopupEnabled = false
        items.forEach { (label, action) ->
            val singleLine = label.replace('\n', ' ')
            val shown = if (singleLine.length > 36) singleLine.take(36) + "…" else singleLine
            add(JMenuItem(shown).apply {
                toolTipText = label
                accessibleContext.accessibleName = label
                addActionListener { action() }
            })
        }
        val x = (location.x - preferredSize.width / 2).coerceIn(0, (root.width - preferredSize.width).coerceAtLeast(0))
        show(root, x, location.y + 10)
    }
}
