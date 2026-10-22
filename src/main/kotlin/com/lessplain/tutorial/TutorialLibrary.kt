package com.lessplain.tutorial

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
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
import javax.swing.JButton
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
    val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(library, library.search.textEditor)
        .setTitle("教程目录").setResizable(true).setMovable(true).setRequestFocus(true).createPopup()
    library.opened = { popup.cancel() }
    Disposer.register(popup, library)
    if (anchor != null && anchor.isShowing) popup.showUnderneathOf(anchor) else popup.showCenteredInCurrentWindow(project)
}

internal class TutorialLibrary(project: Project) : JPanel(BorderLayout(0, JBUI.scale(8))), Disposable {
    private val service = project.getService(TutorialService::class.java)
    val search = SearchTextField()
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
    private val subscription = service.onChanged { refresh() }

    init {
        preferredSize = JBUI.size(520, 400)
        border = JBUI.Borders.empty(8)
        search.textEditor.emptyText.text = "搜索教程标题或正文"
        add(search, BorderLayout.NORTH)
        add(JBScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.CENTER)
        add(JButton("打开 Flow 示例").apply { addActionListener { service.attempt { service.open(createFlowExample(service).id); opened() } } }, BorderLayout.SOUTH)
        list.fixedCellHeight = UIUtil.getLabelFont().size * 2 + JBUI.scale(22)
        list.cellRenderer = ListCellRenderer { owner, value, _, selected, _ ->
            JPanel(BorderLayout(0, JBUI.scale(3))).apply {
                background = if (selected) owner.selectionBackground else owner.background
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
            override fun mouseClicked(event: MouseEvent) {
                if (event.button == MouseEvent.BUTTON1 && event.clickCount == 2) cellAt(event.point)?.let { list.selectedIndex = it; open() }
            }
        })
        list.addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(event: MouseEvent) { if (cellAt(event.point) == null) list.clearSelection() }
        })
        list.inputMap.put(KeyStroke.getKeyStroke("ENTER"), "open")
        list.actionMap.put("open", object : AbstractAction() { override fun actionPerformed(event: java.awt.event.ActionEvent) = open() })
        search.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = refresh()
            override fun removeUpdate(event: DocumentEvent) = refresh()
            override fun changedUpdate(event: DocumentEvent) = refresh()
        })
        refresh()
    }
    fun cellAt(point: Point): Int? = list.locationToIndex(point).takeIf { it >= 0 && list.getCellBounds(it, it)?.contains(point) == true }
    private fun paths(record: TutorialRecord) = record.sourceIds.map { service.store.source(it).path }.distinct().joinToString().ifBlank { "独立学习文档" }
    private fun open() { list.selectedValue?.let { service.open(it.id); opened() } }
    private fun refresh() {
        val selected = list.selectedValue?.id
        model.clear()
        service.store.list().filter { it.title.contains(search.text, true) || it.markdown.contains(search.text, true) }.sortedByDescending { it.createdAt }.forEach(model::addElement)
        (0 until model.size).find { model[it].id == selected }?.let { list.selectedIndex = it }
    }
    override fun dispose() { subscription.close(); ToolTipManager.sharedInstance().unregisterComponent(list) }
}

private fun html(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
