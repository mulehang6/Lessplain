package com.lessplain.ui

import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.lessplain.ide.PendingResult
import com.lessplain.ide.UnderstandingService
import com.lessplain.model.UnderstandingRecord
import java.awt.BorderLayout
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

internal data class RecordListItem(val key: String, val title: String, val recordId: String?, val pending: PendingResult? = null)

/** 历史和最近共用准确命中的原生列表；右键菜单是唯一删除入口。 */
internal class RecordListPanel(private val service: UnderstandingService, private val choose: (RecordListItem) -> Unit) : JPanel(BorderLayout()), AutoCloseable {
    private val model = DefaultListModel<RecordListItem>()
    private var hovered = -1
    private var pointerActive = false
    val list = object : JBList<RecordListItem>(model) {
        override fun getScrollableTracksViewportWidth() = true
        override fun locationToIndex(location: Point?): Int {
            if (location == null) return -1
            val index = super.locationToIndex(location)
            return index.takeIf { it >= 0 && getCellBounds(it, it)?.contains(location) == true } ?: -1
        }
        override fun getToolTipText(event: MouseEvent): String? = locationToIndex(event.point).takeIf { it >= 0 }?.let { model.getElementAt(it).title }
    }
    private val fonts = CanvasUiFonts(listOf(list)) { list.fixedCellHeight = UIUtil.getLabelFont().size + JBUI.scale(22); list.revalidate(); list.repaint() }
    init {
        list.emptyText.text = "暂无记录"
        list.setExpandableItemsEnabled(false)
        list.fixedCellHeight = UIUtil.getLabelFont().size + JBUI.scale(22)
        list.cellRenderer = ListCellRenderer { owner, value, index, _, _ ->
            val active = index == if (pointerActive) hovered else list.selectedIndex
            ListSelectionPanel(owner.background, owner.selectionBackground.takeIf { active }, JBUI.insets(2, 2)).apply {
                layout = BorderLayout()
                border = JBUI.Borders.empty(8, 12)
                if (value.pending != null) add(JLabel(UnreadDotIcon).apply { toolTipText = "有未读" }, BorderLayout.EAST)
                add(JLabel(value.title).apply { putClientProperty("html.disable", true); font = UIUtil.getLabelFont(); foreground = if (active) owner.selectionForeground else owner.foreground })
            }
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(event: MouseEvent) { hovered = -1; pointerActive = true; list.repaint() }
            override fun mouseClicked(event: MouseEvent) {
                if (event.button == MouseEvent.BUTTON1 && event.clickCount == 1) list.locationToIndex(event.point).takeIf { it >= 0 }?.let { list.selectedIndex = it; choose(model[it]) }
            }
        })
        list.addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(event: MouseEvent) {
                pointerActive = true
                val next = list.locationToIndex(event.point)
                if (hovered != next) ToolTipManager.sharedInstance().mouseExited(MouseEvent(list, MouseEvent.MOUSE_EXITED, event.`when`, 0, -1, -1, 0, false))
                hovered = next; list.repaint()
            }
        })
        list.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(event: java.awt.event.KeyEvent) { pointerActive = false; list.repaint() }
        })
        list.addListSelectionListener { list.repaint() }
        list.inputMap.put(KeyStroke.getKeyStroke("ENTER"), "open")
        list.actionMap.put("open", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent) { list.selectedValue?.let(choose) }
        })
        installListDeletionMenu(list, "删除", { !service.readOnly }) { it.recordId?.let(service::deleteRoot) }
        ToolTipManager.sharedInstance().registerComponent(list)
        add(JBScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER).apply { border = JBUI.Borders.empty(); viewportBorder = JBUI.Borders.empty() })
    }
    fun records(values: List<UnderstandingRecord>, unread: Map<String, PendingResult> = emptyMap()) = items(values.map { RecordListItem(it.id, it.displayTitle ?: it.question, it.id, unread[it.id]) })
    fun items(values: List<RecordListItem>) {
        val selected = list.selectedValue?.key
        val index = list.selectedIndex
        if ((0 until model.size).map(model::get) == values) return
        model.clear(); values.forEach(model::addElement)
        hovered = -1; pointerActive = false
        list.selectedIndex = values.indexOfFirst { it.key == selected }.takeIf { it >= 0 }
            ?: values.indexOfFirst { selected == null && it.recordId == service.panelRecordId }.takeIf { it >= 0 }
            ?: index.coerceIn(0, (values.size - 1).coerceAtLeast(0)).takeIf { values.isNotEmpty() } ?: -1
    }
    override fun close() { fonts.close(); ToolTipManager.sharedInstance().unregisterComponent(list) }
}
