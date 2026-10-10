package com.master.understanding.diagram

import com.intellij.ide.setToolTipText
import com.intellij.openapi.util.text.HtmlChunk
import java.awt.Dimension
import javax.swing.Icon
import javax.swing.AbstractButton
import javax.swing.JButton
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu

/** Compact controls painted in the same Swing layer as the graph. */
internal data class DiagramHierarchyItem(
    val title: String,
    val depth: Int,
    val targetIndex: Int,
    val current: Boolean,
)

internal class UnderstandingDiagramControls(private val diagram: UnderstandingDiagram) : JPanel(null) {
    private val zoomIn = button(GraphControlIcon(GraphControlIconKind.ZOOM_IN), "放大") { diagram.zoom *= 1.2 }
    private val zoomOut = button(GraphControlIcon(GraphControlIconKind.ZOOM_OUT), "缩小") { diagram.zoom /= 1.2 }
    private val fit = button(GraphControlIcon(GraphControlIconKind.FIT), "显示全图") { diagram.fitContent() }
    private val arrangeNodes = button(GraphControlIcon(GraphControlIconKind.ARRANGE), "一键整理节点") { diagram.arrangeNodes() }
    private val clusterNav = button(GraphControlIcon(GraphControlIconKind.CLUSTERS), "跳转到分组") { showClusterMenu() }
    private val hierarchy = button(GraphControlIcon(GraphControlIconKind.HIERARCHY), "查看层级") { showHierarchyMenu() }
    private val behavior = button(GraphControlIcon(GraphControlIconKind.MORE), "画布行为") { showBehaviorMenu() }
    private val behaviorPopup = DiagramBehaviorPopup(diagram, behavior)
    val sideControls = JPanel(null).apply { isOpaque = false }
    private var hierarchyItems = emptyList<DiagramHierarchyItem>()
    private var onNavigateHierarchy: ((Int) -> Unit)? = null
    val previousButton = button(GraphControlIcon(GraphControlIconKind.PREVIOUS), "上一步") { diagram.previousStep() }
    val nextButton = button(GraphControlIcon(GraphControlIconKind.NEXT), "下一步") { diagram.nextStep() }
    private var routeAction: (() -> Unit)? = null
    val routeButton = button(GraphControlIcon(GraphControlIconKind.COMPLETE_ROUTE), "补全来路") { routeAction?.invoke() }.apply {
        isVisible = false
    }
    private val buttons: List<AbstractButton> = listOf(zoomIn, zoomOut, fit, arrangeNodes)

    init {
        isOpaque = false
        buttons.forEach(::add)
        sideControls.add(clusterNav)
        sideControls.add(hierarchy)
        sideControls.add(behavior)
        clusterNav.isVisible = diagram.clusterCount > 1
        hierarchy.isVisible = false
        previousButton.isVisible = false
        nextButton.isVisible = false
    }

    fun updateSteps(hasPrevious: Boolean, hasNext: Boolean) {
        previousButton.isVisible = hasPrevious
        nextButton.isVisible = hasNext
        arrangeSteps(diagram.width, diagram.height)
        diagram.repaint()
    }

    fun refreshClusters() {
        clusterNav.isVisible = diagram.clusterCount > 1
        arrangeSide(diagram.width, diagram.height)
    }

    fun updateStepAvailability(hasPrevious: Boolean, hasNext: Boolean) {
        previousButton.isEnabled = previousButton.isVisible && hasPrevious
        nextButton.isEnabled = nextButton.isVisible && hasNext
    }

    fun updateRoute(visible: Boolean, choosing: Boolean, notice: String?, action: (() -> Unit)?) {
        routeAction = action
        val label = if (choosing) "取消选路" else "补全来路"
        routeButton.icon = GraphControlIcon(if (choosing) GraphControlIconKind.CANCEL_ROUTE else GraphControlIconKind.COMPLETE_ROUTE)
        routeButton.accessibleContext.accessibleName = label
        routeButton.setToolTipText(HtmlChunk.text("$label：${notice ?: if (choosing) "点击候选节点选择路线，Esc 取消" else "当前为局部路径，选择来路可补全"}"))
        routeButton.isVisible = visible && action != null
        arrangeSteps(diagram.width, diagram.height)
        diagram.repaint()
    }

    fun setHierarchy(items: List<DiagramHierarchyItem>, navigate: (Int) -> Unit) {
        hierarchyItems = items
        onNavigateHierarchy = navigate
        hierarchy.isVisible = items.size > 1
        arrangeSide(diagram.width, diagram.height)
    }

    private fun showHierarchyMenu() {
        JPopupMenu().apply {
            hierarchyItems.forEach { item ->
                add(JMenuItem("${"  ".repeat(item.depth)}${if (item.current) "✓" else if (item.depth == 0) "理解图" else "└"} ${item.title}").apply {
                    setToolTipText(HtmlChunk.text(item.title))
                    addActionListener { onNavigateHierarchy?.invoke(item.targetIndex) }
                })
            }
            show(hierarchy, 0, hierarchy.height)
        }
    }

    fun arrange(availableWidth: Int) {
        val columns = if (availableWidth >= 160) 4 else if (availableWidth >= 96) 2 else 1
        val visible = buttons
        val rows = (visible.size + columns - 1) / columns
        setBounds(12, 12, columns * BUTTON_SIZE + (columns - 1) * GAP, rows * BUTTON_SIZE + (rows - 1) * GAP)
        visible.forEachIndexed { index, button ->
            button.setBounds((index % columns) * (BUTTON_SIZE + GAP), (index / columns) * (BUTTON_SIZE + GAP),
                BUTTON_SIZE, BUTTON_SIZE)
        }
    }

    fun arrangeSide(availableWidth: Int, availableHeight: Int) {
        val visible = listOf(clusterNav, hierarchy, behavior).filter { it.isVisible }
        val panelHeight = visible.size * BUTTON_SIZE + (visible.size - 1) * GAP
        val minTop = y + height + GAP
        val maxTop = (availableHeight - INSET - BUTTON_SIZE - GAP - panelHeight).coerceAtLeast(0)
        val top = ((availableHeight - panelHeight) / 2).coerceAtLeast(minTop).coerceAtMost(maxTop)
        sideControls.setBounds((availableWidth - INSET - BUTTON_SIZE).coerceAtLeast(0), top,
            BUTTON_SIZE, panelHeight)
        visible.forEachIndexed { index, button ->
            button.setBounds(0, index * (BUTTON_SIZE + GAP), BUTTON_SIZE, BUTTON_SIZE)
        }
    }

    fun arrangeSteps(availableWidth: Int, availableHeight: Int) {
        val bottom = (availableHeight - INSET - BUTTON_SIZE).coerceAtLeast(0)
        val steps = listOf(previousButton, nextButton) + if (routeButton.isVisible) listOf(routeButton) else emptyList()
        val columns = ((availableWidth - INSET * 2 + GAP) / (BUTTON_SIZE + GAP)).coerceIn(1, steps.size)
        val rows = (steps.size + columns - 1) / columns
        steps.forEachIndexed { index, button ->
            button.setBounds(INSET + (index % columns) * (BUTTON_SIZE + GAP),
                (bottom - (rows - 1 - index / columns) * (BUTTON_SIZE + GAP)).coerceAtLeast(0),
                BUTTON_SIZE, BUTTON_SIZE)
        }
    }

    private fun showClusterMenu() {
        JPopupMenu().apply {
            diagram.clusters.forEachIndexed { index, cluster ->
                val title = cluster.title.replace('\n', ' ')
                val shortTitle = if (title.length > 36) title.take(36) + "…" else title
                add(JMenuItem("${index + 1}. $shortTitle").apply {
                    setToolTipText(HtmlChunk.text(title))
                    addActionListener { diagram.focusCluster(cluster.id) }
                })
            }
            show(clusterNav, 0, clusterNav.height)
        }
    }

    private fun showBehaviorMenu() {
        behaviorPopup.toggle()
    }

    fun refreshBehavior() = behaviorPopup.refresh()

    fun dispose() { routeAction = null; behaviorPopup.dispose() }

    private fun button(icon: Icon, label: String, action: () -> Unit): JButton = DiagramIconButton(icon).apply {
        setToolTipText(HtmlChunk.text(label))
        getAccessibleContext().accessibleName = label
        preferredSize = Dimension(BUTTON_SIZE, BUTTON_SIZE)
        addActionListener { action() }
    }

    companion object {
        private const val BUTTON_SIZE = 32
        private const val GAP = 4
        private const val INSET = 12
    }
}
