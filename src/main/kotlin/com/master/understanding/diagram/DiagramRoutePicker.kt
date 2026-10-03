package com.master.understanding.diagram

import com.master.understanding.core.CompleteRoute
import com.intellij.ide.setToolTipText
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/** A popup lists complete routes without selecting or recommending a candidate automatically. */
internal class DiagramRoutePicker(
    private val navigation: DiagramCompleteRouteNavigation,
    private val target: String,
    private val dismiss: () -> Unit,
) : AutoCloseable {
    private val graph = navigation.graph
    private val nodes = graph.nodes.associateBy { it.id }
    private val edges = graph.edges.associateBy { it.id }
    private val popup = JPopupMenu()
    private val model = DefaultListModel<CompleteRoute>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 7
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean,
            ): Component = super.getListCellRendererComponent(list, value, index, selected, focused).apply {
                text = (value as? CompleteRoute)?.let(::describe).orEmpty()
                setToolTipText((value as? CompleteRoute)?.let { HtmlChunk.text(detail(it)) })
            }
        }
        accessibleContext.accessibleName = "完整路线候选"
    }
    private val status = JLabel("正在查找路线…")
    private val more = JButton("加载下一批")
    private val confirm = JButton("确认路线").apply { isEnabled = false }
    private val upstream = navigation.routes.edgesTo(target)
    private val entryOptions = listOf(FilterOption(null, "全部起点")) + navigation.routes.entryNodeIds
        .filter { it == target || graph.edges.any { edge -> edge.from == it && edge.id in upstream } }
        .map { FilterOption(it, "起点 · ${nodes[it]?.label ?: it}") }
    private val conditionOptions = listOf(FilterOption(null, "全部条件")) + graph.edges
        .filter { it.id in upstream && !it.condition.isNullOrBlank() }
        .sortedBy { it.id }.map { FilterOption(it.id, "条件 · ${it.condition} · ${nodes[it.from]?.label ?: it.from}") }
    private val entry = ComboBox(entryOptions.toTypedArray())
    private val condition = ComboBox(conditionOptions.toTypedArray())
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "understanding-route-candidates").apply { isDaemon = true }
    }
    private var future: Future<*>? = null
    private var iterator: Iterator<CompleteRoute>? = null
    @Volatile private var generation = 0
    @Volatile private var closed = false
    private var exhausted = false

    init {
        val filters = JPanel(java.awt.GridLayout(2, 1, 0, 4)).apply { add(entry); add(condition) }
        val footer = JPanel(BorderLayout(4, 4)).apply {
            add(status, BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                add(more); add(JButton("取消").apply { addActionListener { dismiss() } }); add(confirm)
            }, BorderLayout.SOUTH)
        }
        popup.add(JPanel(BorderLayout(6, 6)).apply {
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
            add(JLabel("选一条路线 · ${nodes[target]?.label ?: target}"), BorderLayout.NORTH)
            add(JPanel(BorderLayout(4, 6)).apply {
                add(filters, BorderLayout.NORTH)
                add(JBScrollPane(list).apply { preferredSize = Dimension(480, 240) }, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
            add(footer, BorderLayout.SOUTH)
        })
        popup.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) = Unit
            override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) { if (!closed) dismiss() }
            override fun popupMenuCanceled(event: PopupMenuEvent) { if (!closed) dismiss() }
        })
        list.addListSelectionListener {
            confirm.isEnabled = list.selectedIndex >= 0
            if (!it.valueIsAdjusting) navigation.preview(list.selectedValue)
        }
        list.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(event: MouseEvent) {
                val index = list.locationToIndex(event.point)
                val route = if (index >= 0 && list.getCellBounds(index, index)?.contains(event.point) == true) model[index] else null
                navigation.preview(route)
            }
        })
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(event: MouseEvent) { navigation.preview(list.selectedValue) }
            override fun mouseClicked(event: MouseEvent) {
                val index = list.locationToIndex(event.point)
                if (SwingUtilities.isLeftMouseButton(event) && index >= 0 &&
                    list.getCellBounds(index, index)?.contains(event.point) == true) navigation.confirm(model[index], target)
            }
        })
        confirm.addActionListener { list.selectedValue?.let { navigation.confirm(it, target) } }
        more.addActionListener { loadPage(reset = false) }
        entry.addActionListener { loadPage(reset = true) }
        condition.addActionListener { loadPage(reset = true) }
    }

    fun show(anchor: Component) {
        popup.show(anchor, 0, anchor.height.coerceAtMost(48))
        loadPage(reset = true)
    }

    private fun loadPage(reset: Boolean) {
        if (closed || (!reset && exhausted)) return
        if (reset) {
            generation++
            future?.cancel(true)
            model.clear()
            list.clearSelection()
            navigation.preview(null)
            iterator = null
            exhausted = false
        }
        val version = generation
        val entryId = (entry.selectedItem as FilterOption).id
        val edgeId = (condition.selectedItem as FilterOption).id
        more.isEnabled = false
        status.text = "正在查找路线…"
        future = executor.submit {
            try {
                val candidates = iterator ?: navigation.routes.routesTo(target, entryId, edgeId) {
                    closed || generation != version || Thread.currentThread().isInterrupted
                }
                val page = mutableListOf<CompleteRoute>()
                while (page.size < PAGE_SIZE && candidates.hasNext()) page += candidates.next()
                // Do not look ahead after a full page: finding the next route can be expensive.
                val end = page.size < PAGE_SIZE
                SwingUtilities.invokeLater {
                    if (closed || generation != version) return@invokeLater
                    iterator = candidates
                    page.forEach(model::addElement)
                    list.clearSelection()
                    exhausted = end
                    more.isEnabled = !end
                    status.text = when {
                        model.isEmpty -> "当前筛选下没有完整路线"
                        end -> "${model.size()} 条路线 · 悬停预览，点击选择"
                        else -> "已显示 ${model.size()} 条 · 悬停预览，点击选择；可加载或筛选"
                    }
                }
            } catch (_: CancellationException) {
                // A changed filter or closed popup owns the next result.
            } catch (failure: Exception) {
                SwingUtilities.invokeLater {
                    if (!closed && generation == version) {
                        status.text = "路线查询失败：${failure.message ?: failure.javaClass.simpleName}"
                        more.isEnabled = true
                    }
                }
            }
        }
    }

    private fun describe(route: CompleteRoute): String {
        val choices = route.edgeIds.mapNotNull { edges[it]?.condition?.takeIf(String::isNotBlank) }.joinToString(" → ")
        return "起点 · ${nodes[route.entryNodeId]?.label ?: route.entryNodeId} · ${route.edgeIds.size} 步" +
            if (choices.isBlank()) "" else " · $choices"
    }

    private fun detail(route: CompleteRoute): String = buildString {
        append(nodes[route.entryNodeId]?.label ?: route.entryNodeId)
        route.edgeIds.forEach { id -> edges[id]?.let { edge ->
            append(" → ")
            edge.condition?.takeIf(String::isNotBlank)?.let { append("[$it] ") }
            append(nodes[edge.to]?.label ?: edge.to)
        } }
    }

    override fun close() {
        if (closed) return
        closed = true
        generation++
        future?.cancel(true)
        executor.shutdownNow()
        popup.isVisible = false
        navigation.preview(null)
    }

    private data class FilterOption(val id: String?, val label: String) {
        override fun toString(): String = label
    }

    private companion object { const val PAGE_SIZE = 50 }
}
