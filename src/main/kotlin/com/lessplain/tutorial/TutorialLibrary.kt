package com.lessplain.tutorial

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.lessplain.ui.PopupUi
import com.lessplain.ui.nativePopupBuilder
import com.intellij.ui.popup.list.SelectablePanel
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.DefaultListModel
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.ScrollPaneConstants
import javax.swing.ToolTipManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** 目录独立弹出，工具窗口保留理解图的内容和导航。 */
internal fun showTutorialLibrary(project: Project, anchor: Component?) {
    val library = TutorialLibrary(project)
    val popup = nativePopupBuilder(library, library.search.textEditor, "教程目录", cancelOnOutside = true) { !library.composition.active }
        .setResizable(true).setDimensionServiceKey(project, "Lessplain.TutorialLibrary", false).createPopup()
    library.opened = { popup.cancel() }
    Disposer.register(popup, library)
    if (anchor != null && anchor.isShowing) popup.showUnderneathOf(anchor) else popup.showCenteredInCurrentWindow(project)
}

internal class TutorialLibrary(project: Project) : JPanel(BorderLayout(0, JBUI.scale(8))), Disposable {
    private val service = project.getService(TutorialService::class.java)
    val search = SearchTextField()
    val composition = com.lessplain.ui.EditorComposition()
    private val model = DefaultListModel<TutorialRecord>()
    val list = object : JBList<TutorialRecord>(model) {
        override fun getScrollableTracksViewportWidth() = true
        override fun locationToIndex(location: Point?): Int {
            if (location == null) return -1
            val index = super.locationToIndex(location)
            return if (index >= 0 && getCellBounds(index, index)?.contains(location) == true) index else -1
        }
        override fun getToolTipText(event: MouseEvent): String? = cellAt(event.point)?.let { index ->
            val record = this@TutorialLibrary.model[index]
            "<html>${html(record.title)}<br>${html(paths(record))}</html>"
        }
    }
    var opened: () -> Unit = {}
    private var hoveredIndex = -1
    private var mouseActive = false
    internal val activeIndex: Int get() = if (mouseActive) hoveredIndex else list.selectedIndex
    private val subscription = service.onChanged { refresh() }

    init {
        preferredSize = JBUI.size(520, 400)
        border = JBUI.Borders.empty(PopupUi.PADDING)
        search.textEditor.emptyText.text = "搜索教程标题或正文"
        search.textEditor.addInputMethodListener(object : java.awt.event.InputMethodListener {
            override fun inputMethodTextChanged(event: java.awt.event.InputMethodEvent) {
                val text = event.text
                composition.changed(if (text == null) 0 else text.endIndex - text.beginIndex, event.committedCharacterCount)
            }
            override fun caretPositionChanged(event: java.awt.event.InputMethodEvent) = Unit
        })
        add(search, BorderLayout.NORTH)
        add(JBScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.CENTER)
        // 只使用按当前鼠标位置取值的普通提示，避免平台扩展行提示继续绑定旧选择。
        list.setExpandableItemsEnabled(false)
        list.fixedCellHeight = UIUtil.getLabelFont().size * 2 + JBUI.scale(22)
        list.cellRenderer = ListCellRenderer { owner, value, index, _, _ ->
            val selected = index == activeIndex
            SelectablePanel().apply {
                layout = BorderLayout(0, JBUI.scale(3))
                background = owner.background
                selectionArc = JBUI.CurrentTheme.Popup.Selection.ARC.get()
                selectionInsets = JBUI.insets(2, 4)
                selectionColor = if (selected) owner.selectionBackground else null
                border = JBUI.Borders.empty(7, 10)
                preferredSize = Dimension(1, list.fixedCellHeight)
                add(JLabel(value.title).apply {
                    font = UIUtil.getLabelFont().deriveFont(java.awt.Font.BOLD)
                    foreground = if (selected) owner.selectionForeground else owner.foreground
                }, BorderLayout.NORTH)
                add(JLabel(paths(value)).apply {
                    font = UIUtil.getLabelFont()
                    foreground = if (selected) owner.selectionForeground else UIUtil.getLabelDisabledForeground()
                }, BorderLayout.CENTER)
            }
        }
        ToolTipManager.sharedInstance().registerComponent(list)
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(event: MouseEvent) { hoverAt(null) }
            override fun mouseClicked(event: MouseEvent) {
                if (event.button == MouseEvent.BUTTON1 && event.clickCount == 1) cellAt(event.point)?.let { list.selectedIndex = it; open() }
            }
        })
        list.addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(event: MouseEvent) { hoverAt(event.point) }
        })
        list.addListSelectionListener { list.repaint() }
        list.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(event: java.awt.event.KeyEvent) { mouseActive = false; list.repaint() }
        })
        list.inputMap.put(KeyStroke.getKeyStroke("ENTER"), "open")
        list.actionMap.put("open", object : AbstractAction() { override fun actionPerformed(event: java.awt.event.ActionEvent) = open() })
        search.textEditor.addActionListener { mouseActive = false; open() }
        listOf("DOWN" to 1, "UP" to -1).forEach { (key, delta) ->
            search.textEditor.inputMap.put(KeyStroke.getKeyStroke(key), "catalog.$key")
            search.textEditor.actionMap.put("catalog.$key", object : AbstractAction() {
                override fun actionPerformed(event: java.awt.event.ActionEvent) {
                    mouseActive = false
                    if (!model.isEmpty) {
                        list.selectedIndex = (list.selectedIndex + delta).coerceIn(0, model.size - 1)
                        list.ensureIndexIsVisible(list.selectedIndex)
                    }
                    list.repaint()
                }
            })
        }
        search.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = refresh()
            override fun removeUpdate(event: DocumentEvent) = refresh()
            override fun changedUpdate(event: DocumentEvent) = refresh()
        })
        refresh()
    }
    fun cellAt(point: Point): Int? = list.locationToIndex(point).takeIf { it >= 0 && list.getCellBounds(it, it)?.contains(point) == true }
    private fun hoverAt(point: Point?) {
        mouseActive = true
        val next = point?.let(::cellAt) ?: -1
        if (hoveredIndex != next) {
            // 跨行先结束旧提示，再交给当前行重建。
            ToolTipManager.sharedInstance().mouseExited(MouseEvent(list, MouseEvent.MOUSE_EXITED, System.currentTimeMillis(), 0, -1, -1, 0, false))
            hoveredIndex = next
            if (point != null && next >= 0) ToolTipManager.sharedInstance().mouseMoved(MouseEvent(list, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, point.x, point.y, 0, false))
        }
        list.repaint()
    }
    private fun paths(record: TutorialRecord) = record.sourceIds.map { service.store.source(it).path }.distinct().joinToString().ifBlank { "独立学习文档" }
    private fun open() { list.selectedValue?.let { service.open(it.id); opened() } }
    private fun refresh() {
        val selected = list.selectedValue?.id
        hoveredIndex = -1
        model.clear()
        service.store.list().filter { it.title.contains(search.text, true) || it.markdown.contains(search.text, true) }.sortedByDescending { it.createdAt }.forEach(model::addElement)
        (0 until model.size).find { model[it].id == selected }?.let { list.selectedIndex = it }
    }
    override fun dispose() { subscription.close(); ToolTipManager.sharedInstance().unregisterComponent(list) }
}

private fun html(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
