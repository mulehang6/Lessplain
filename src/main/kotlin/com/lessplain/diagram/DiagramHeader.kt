package com.lessplain.diagram

import com.intellij.icons.AllIcons
import com.intellij.ide.setToolTipText
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.util.ui.JBUI
import com.lessplain.ui.UnderstandingUiStyle
import java.awt.Cursor
import java.awt.Font
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/** 紧凑的视口覆盖层，仅标题和相邻编辑入口接收指针输入。 */
internal class DiagramHeader(title: String) : JPanel(null), AutoCloseable {
    internal val label = JLabel()
    internal val editButton: JButton = DiagramIconButton(AllIcons.Actions.Edit).apply {
        setToolTipText(HtmlChunk.text("编辑标题"))
        accessibleContext.accessibleName = "编辑标题"
        border = JBUI.Borders.empty(2)
        isContentAreaFilled = false
        isBorderPainted = false
        isOpaque = false
        isVisible = false
    }
    private val displayTitle = title.replace(Regex("\\s+"), " ")
    private var edit: (() -> Unit)? = null
    private var hovered = false
    private var focusWithin = false
    private var hasIconSpace = false
    private var closed = false
    private val editAction = java.awt.event.ActionListener { edit?.invoke() }
    private val pointer = object : MouseAdapter() {
        override fun mouseEntered(e: MouseEvent) { hovered = true; refreshEdit() }
        override fun mouseExited(e: MouseEvent) {
            hovered = contains(SwingUtilities.convertPoint(e.component, e.point, this@DiagramHeader))
            refreshEdit()
        }
        override fun mousePressed(e: MouseEvent) {
            if (e.button == MouseEvent.BUTTON1 && e.component != editButton) requestFocusInWindow()
        }
        override fun mouseClicked(e: MouseEvent) {
            if (e.button == MouseEvent.BUTTON1 && e.clickCount == 2 && e.component != editButton) edit?.invoke()
        }
    }
    private val focus = object : FocusAdapter() {
        override fun focusGained(e: FocusEvent) { focusWithin = true; refreshEdit() }
        override fun focusLost(e: FocusEvent) {
            focusWithin = e.oppositeComponent == this@DiagramHeader || e.oppositeComponent == editButton
            refreshEdit()
        }
    }

    init {
        isOpaque = false
        refreshAppearance()
        label.setToolTipText(HtmlChunk.text(title))
        label.accessibleContext.accessibleName = title
        getAccessibleContext().accessibleName = title
        getAccessibleContext().accessibleDescription = "Enter 或 F2 编辑标题"
        add(label)
        add(editButton)
        editButton.addActionListener(editAction)
        listOf(this, label, editButton).forEach { it.addMouseListener(pointer) }
        addFocusListener(focus)
        editButton.addFocusListener(focus)
        for (key in listOf("ENTER", "F2")) getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), "edit")
        actionMap.put("edit", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) { edit?.invoke() }
        })
    }

    fun refreshAppearance() {
        label.font = DiagramTextMetrics.font().deriveFont(Font.BOLD)
        label.foreground = UnderstandingUiStyle.foreground
        revalidate()
        repaint()
    }

    fun bindEdit(action: (() -> Unit)?): AutoCloseable {
        if (closed) return AutoCloseable { }
        edit = action
        isFocusable = action != null
        label.cursor = if (action != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
        refreshEdit()
        parent?.revalidate()
        return AutoCloseable {
            if (edit === action) {
                edit = null
                isFocusable = false
                label.cursor = Cursor.getDefaultCursor()
                refreshEdit()
                parent?.revalidate()
            }
        }
    }

    /** 与首行控件共用空间，并预留编辑入口，悬停时不移动文字。 */
    fun arrange(availableWidth: Int, controls: Rectangle, rowHeight: Int) {
        val gap = JBUI.scale(8)
        val iconSize = JBUI.scale(24)
        val start = (controls.x + controls.width + gap).coerceAtMost(availableWidth)
        val available = (availableWidth - start - JBUI.scale(12)).coerceAtLeast(0)
        val metrics = label.getFontMetrics(label.font)
        hasIconSpace = edit != null && available >= metrics.stringWidth("…") + gap + iconSize
        val reserve = if (hasIconSpace) gap + iconSize else 0
        val shown = elide(displayTitle, (available - reserve).coerceAtLeast(0))
        val textWidth = metrics.stringWidth(shown)
        val contentHeight = maxOf(metrics.height, if (hasIconSpace) iconSize else 0)
        val h = maxOf(rowHeight, contentHeight)
        setBounds(start, controls.y + (rowHeight - h) / 2, textWidth + reserve, h)
        label.text = shown
        label.setBounds(0, (h - metrics.height) / 2, textWidth, metrics.height)
        editButton.setBounds(textWidth + gap, (h - iconSize) / 2, iconSize, iconSize)
        isVisible = shown.isNotEmpty()
        refreshEdit()
    }

    internal val popupAnchor: Point get() = Point(x, y + height + JBUI.scale(6))

    private fun elide(text: String, available: Int): String {
        val metrics = label.getFontMetrics(label.font)
        if (metrics.stringWidth(text) <= available) return text
        if (metrics.stringWidth("…") > available) return ""
        var short = text
        while (short.isNotEmpty() && metrics.stringWidth("$short…") > available) {
            short = short.substring(0, short.offsetByCodePoints(short.length, -1))
        }
        return "$short…"
    }

    private fun refreshEdit() {
        editButton.isVisible = !closed && edit != null && hasIconSpace && (hovered || focusWithin)
        repaint()
    }

    override fun close() {
        if (closed) return
        closed = true
        edit = null
        hovered = false
        focusWithin = false
        isFocusable = false
        refreshEdit()
        listOf(this, label, editButton).forEach { it.removeMouseListener(pointer) }
        removeFocusListener(focus)
        editButton.removeFocusListener(focus)
        editButton.removeActionListener(editAction)
        getInputMap(WHEN_FOCUSED).clear()
        actionMap.clear()
    }
}
