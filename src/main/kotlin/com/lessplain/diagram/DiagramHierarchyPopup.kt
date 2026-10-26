package com.lessplain.diagram

import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.util.ui.JBUI
import com.lessplain.ui.CanvasUiFonts
import com.lessplain.ui.clampPopupBounds
import com.lessplain.ui.nativePopupLocation
import com.lessplain.ui.setNativePopupBounds
import com.lessplain.ui.usableScreenBoundsAt
import com.lessplain.ui.UnderstandingUiMetrics
import com.lessplain.ui.UnderstandingUiStyle
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

/** 层级入口使用原生弹层与共用主题，只有打开时才创建列表和字体监听器。 */
internal class DiagramHierarchyPopup(
    private val anchor: JComponent,
    private val items: () -> List<DiagramHierarchyItem>,
    private val showQuestions: () -> (() -> Unit)? = { null },
    private val navigate: (Int) -> Unit,
) : Disposable {
    private var popup: JBPopup? = null
    private var panel: DiagramHierarchyPanel? = null
    private var disposed = false
    private var closing = false
    private val visibility = HierarchyListener { event ->
        if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && !anchor.isShowing) close()
    }

    init { anchor.addHierarchyListener(visibility) }

    fun toggle() {
        if (disposed) return
        if (popup?.isDisposed == false) { close(); return }
        val snapshot = items().toList()
        if (snapshot.isEmpty()) return
        val content = DiagramHierarchyPanel(snapshot, ::resizeToContent, showQuestions = showQuestions()?.let { action -> { close(); action() } }) { index ->
            close()
            snapshot.getOrNull(index)?.let { navigate(it.targetIndex) }
        }
        panel = content
        try {
            val created = JBPopupFactory.getInstance().createComponentPopupBuilder(content, content.list)
                .setTitle("理解层级")
                .setFocusable(true)
                .setRequestFocus(true)
                .setCancelOnClickOutside(true)
                .setCancelKeyEnabled(true)
                .setCancelOnWindowDeactivation(true)
                .setCancelOnOtherWindowOpen(true)
                .setLocateWithinScreenBounds(true)
                .setCancelCallback { mayCancel() }
                .createPopup()
            popup = created
            created.addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    if (popup === created) { popup = null; panel = null }
                    content.close()
                    created.removeListener(this)
                }
            })
            created.showUnderneathOf(anchor)
            resizeToContent()
            content.list.ensureIndexIsVisible(content.list.selectedIndex)
        } catch (failure: RuntimeException) {
            close()
            content.close()
            throw failure
        }
    }

    private fun resizeToContent() {
        val current = popup?.takeIf { !it.isDisposed && it.isVisible } ?: return
        val location = nativePopupLocation(current)
        current.content.preferredSize = null
        current.pack(true, true)
        setNativePopupBounds(current, clampPopupBounds(location, current.size, usableScreenBoundsAt(location)))
    }

    private fun mayCancel(): Boolean {
        if (closing) return true
        val source = (EventQueue.getCurrentEvent() as? MouseEvent)?.source as? Component ?: return true
        return source !== anchor && !SwingUtilities.isDescendingFrom(source, anchor)
    }

    fun close() {
        val current = popup
        val content = panel
        closing = true
        try {
            if (current?.isDisposed == false) current.cancel()
            if (current?.isDisposed == false) current.dispose()
        } finally {
            content?.close()
            popup = null
            panel = null
            closing = false
        }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        close()
        anchor.removeHierarchyListener(visibility)
    }
}

/** 标题和状态分列；缩进用尺寸表达，不用空格或树形字符拼接文本。 */
internal class DiagramHierarchyPanel(
    private val entries: List<DiagramHierarchyItem>,
    private val onMetricsChanged: () -> Unit = {},
    showQuestions: (() -> Unit)? = null,
    private val choose: (Int) -> Unit,
) : JPanel(BorderLayout()), AutoCloseable {
    val list = object : JList<DiagramHierarchyItem>(entries.toTypedArray()) {
        override fun getToolTipText(event: MouseEvent): String? {
            val index = locationToIndex(event.point)
            return entries.getOrNull(index)?.takeIf { getCellBounds(index, index)?.contains(event.point) == true }
                ?.let { "<html>${HtmlChunk.text(it.title)}</html>" }
        }
    }.apply {
        isOpaque = false
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = HierarchyRowRenderer()
        selectedIndex = entries.indexOfFirst { it.current }.takeIf { it >= 0 } ?: entries.indices.firstOrNull() ?: -1
        toolTipText = ""
        accessibleContext.accessibleName = "理解层级"
    }
    private val scroll = JScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER).apply {
        border = BorderFactory.createEmptyBorder()
        isOpaque = false
        viewport.isOpaque = false
    }
    val questionButton = showQuestions?.let { show -> javax.swing.JButton("查看本层提问", com.lessplain.ui.QuestionBubbleIcon).apply {
        horizontalAlignment = javax.swing.SwingConstants.LEFT
        addActionListener { show() }
        accessibleContext.accessibleName = "查看本层提问"
    } }
    private val fonts = CanvasUiFonts(listOfNotNull(list, questionButton)) { measure(); onMetricsChanged() }

    init {
        background = UnderstandingUiStyle.surface
        border = JBUI.Borders.empty(UnderstandingUiMetrics.GAP)
        questionButton?.let { button ->
            add(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                isOpaque = false
                add(button, BorderLayout.NORTH)
                add(javax.swing.JSeparator(), BorderLayout.SOUTH)
            }, BorderLayout.NORTH)
        }
        add(scroll, BorderLayout.CENTER)
        list.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(event: MouseEvent) {
                val index = list.locationToIndex(event.point)
                if (list.getCellBounds(index, index)?.contains(event.point) == true) list.selectedIndex = index
            }
        })
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event)) return
                val index = list.locationToIndex(event.point)
                if (list.getCellBounds(index, index)?.contains(event.point) == true) choose(index)
            }
        })
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "choose-level")
        list.actionMap.put("choose-level", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                list.selectedIndex.takeIf { it in entries.indices }?.let(choose)
            }
        })
        measure()
    }

    private fun measure() {
        val metrics = list.getFontMetrics(list.font)
        list.fixedCellHeight = metrics.height + JBUI.scale(UnderstandingUiMetrics.ROW_VERTICAL_PADDING * 2)
        val contentWidth = entries.maxOfOrNull {
            metrics.stringWidth(it.title) + JBUI.scale(indent(it.depth) +
                UnderstandingUiMetrics.ROW_HORIZONTAL_PADDING * 2 + 64)
        } ?: 0
        val width = contentWidth.coerceIn(JBUI.scale(UnderstandingUiMetrics.POPUP_MIN_WIDTH),
            JBUI.scale(UnderstandingUiMetrics.POPUP_MAX_WIDTH))
        scroll.preferredSize = Dimension(width, list.fixedCellHeight *
            entries.size.coerceIn(1, UnderstandingUiMetrics.POPUP_VISIBLE_ROWS))
        revalidate()
        repaint()
    }

    override fun close() = fonts.close()
}

private fun indent(depth: Int): Int = depth.coerceIn(0, 8) * 16

private class HierarchyRowRenderer : JPanel(BorderLayout(JBUI.scale(UnderstandingUiMetrics.GAP), 0)),
    ListCellRenderer<DiagramHierarchyItem> {
    private val title = JLabel().apply { putClientProperty("html.disable", true) }
    private val current = JLabel("当前")
    private var selected = false

    init {
        isOpaque = false
        add(title, BorderLayout.CENTER)
        add(current, BorderLayout.EAST)
    }

    override fun getListCellRendererComponent(list: JList<out DiagramHierarchyItem>, value: DiagramHierarchyItem,
        index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
        selected = isSelected
        border = JBUI.Borders.empty(UnderstandingUiMetrics.ROW_VERTICAL_PADDING,
            UnderstandingUiMetrics.ROW_HORIZONTAL_PADDING + indent(value.depth),
            UnderstandingUiMetrics.ROW_VERTICAL_PADDING, UnderstandingUiMetrics.ROW_HORIZONTAL_PADDING)
        title.font = list.font
        current.font = list.font
        title.text = value.title
        title.icon = GraphControlIcon(if (value.depth == 0) GraphControlIconKind.HIERARCHY else GraphControlIconKind.QUESTIONS)
        title.iconTextGap = JBUI.scale(UnderstandingUiMetrics.GAP)
        title.foreground = if (selected) UnderstandingUiStyle.listSelectionForeground else UnderstandingUiStyle.foreground
        current.foreground = if (selected) UnderstandingUiStyle.listSelectionForeground else UnderstandingUiStyle.accent
        current.isVisible = value.current
        getAccessibleContext().accessibleName = value.title + if (value.current) "，当前层级" else ""
        return this
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        if (!selected) return
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = UnderstandingUiStyle.listSelection
            g.fillRoundRect(JBUI.scale(2), JBUI.scale(2), (width - JBUI.scale(4)).coerceAtLeast(0),
                (height - JBUI.scale(4)).coerceAtLeast(0), UnderstandingUiStyle.NODE_ARC, UnderstandingUiStyle.NODE_ARC)
        } finally { g.dispose() }
    }
}
