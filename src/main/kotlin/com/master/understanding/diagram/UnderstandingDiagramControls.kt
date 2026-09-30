package com.master.understanding.diagram

import com.intellij.ide.setToolTipText
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.JBColor
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.AbstractButton
import javax.swing.JButton
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JCheckBoxMenuItem
import javax.swing.ButtonGroup
import javax.swing.JRadioButtonMenuItem
import com.master.understanding.ide.GraphLayoutPreference
import javax.swing.UIManager

/** Compact controls painted in the same Swing layer as the graph. */
internal data class DiagramHierarchyItem(
    val title: String,
    val depth: Int,
    val targetIndex: Int,
    val current: Boolean,
)

internal class UnderstandingDiagramControls(private val diagram: UnderstandingDiagram) : JPanel(null) {
    private val zoomIn = button(GraphControlIcon(IconKind.ZOOM_IN), "放大") { diagram.zoom *= 1.2 }
    private val zoomOut = button(GraphControlIcon(IconKind.ZOOM_OUT), "缩小") { diagram.zoom /= 1.2 }
    private val fit = button(GraphControlIcon(IconKind.FIT), "显示全图") { diagram.fitContent() }
    private val arrangeNodes = button(GraphControlIcon(IconKind.ARRANGE), "一键整理节点") { diagram.arrangeNodes() }
    private val clusterNav = button(GraphControlIcon(IconKind.CLUSTERS), "跳转到追问") { showClusterMenu() }
    private val hierarchy = button(GraphControlIcon(IconKind.HIERARCHY), "查看层级") { showHierarchyMenu() }
    private val behavior = button(GraphControlIcon(IconKind.MORE), "画布行为") { showBehaviorMenu() }
    val sideControls = JPanel(null).apply { isOpaque = false }
    private var hierarchyItems = emptyList<DiagramHierarchyItem>()
    private var onNavigateHierarchy: ((Int) -> Unit)? = null
    val previousButton = button(GraphControlIcon(IconKind.PREVIOUS), "上一步") { diagram.previousStep() }
    val nextButton = button(GraphControlIcon(IconKind.NEXT), "下一步") { diagram.nextStep() }
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
        if (availableWidth >= INSET * 2 + BUTTON_SIZE * 2 + GAP) {
            previousButton.setBounds(INSET, bottom, BUTTON_SIZE, BUTTON_SIZE)
            nextButton.setBounds(INSET + BUTTON_SIZE + GAP, bottom, BUTTON_SIZE, BUTTON_SIZE)
        } else {
            previousButton.setBounds(INSET, (bottom - BUTTON_SIZE - GAP).coerceAtLeast(0), BUTTON_SIZE, BUTTON_SIZE)
            nextButton.setBounds(INSET, bottom, BUTTON_SIZE, BUTTON_SIZE)
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
        JPopupMenu().apply {
            add(JMenuItem("恢复 100% 缩放").apply { addActionListener { diagram.zoom = 1.0 } })
            addSeparator()
            val directionGroup = ButtonGroup()
            listOf(
                GraphLayoutPreference.AUTO to "方向：自动",
                GraphLayoutPreference.VERTICAL to "方向：纵向",
                GraphLayoutPreference.HORIZONTAL to "方向：横向",
            ).forEach { (preference, title) ->
                val item = JRadioButtonMenuItem(title, diagram.layoutPreference == preference).apply {
                    addActionListener { diagram.setLayoutPreference(preference) }
                }
                directionGroup.add(item)
                add(item)
            }
            addSeparator()
            add(JCheckBoxMenuItem("路径流动", diagram.flowEnabled).apply {
                addActionListener { diagram.flowEnabled = isSelected }
            })
            add(JCheckBoxMenuItem("自动跳转源码", !diagram.navigationPinned).apply {
                addActionListener { diagram.navigationPinned = !isSelected }
            })
            add(JCheckBoxMenuItem("自动跟随节点", diagram.autoFollowNode).apply {
                addActionListener { diagram.autoFollowNode = isSelected }
            })
            show(behavior, 0, behavior.height)
        }
    }

    private fun button(icon: Icon, label: String, action: () -> Unit): JButton = JButton(icon).apply {
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

private enum class IconKind { ZOOM_IN, ZOOM_OUT, FIT, ARRANGE, CLUSTERS, HIERARCHY, MORE, PREVIOUS, NEXT }

/** Simple vector glyphs so the controls remain visible in IDE themes and preview rendering. */
private class GraphControlIcon(private val kind: IconKind) : Icon {
    override fun getIconWidth(): Int = 16
    override fun getIconHeight(): Int = 16

    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        try {
            g.translate(x, y)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = UIManager.getColor("Button.foreground") ?: component?.foreground ?: JBColor.foreground()
            g.stroke = BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            when (kind) {
                IconKind.ZOOM_IN, IconKind.ZOOM_OUT -> {
                    g.drawOval(1, 1, 10, 10)
                    g.drawLine(10, 10, 15, 15)
                    g.drawLine(4, 6, 8, 6)
                    if (kind == IconKind.ZOOM_IN) g.drawLine(6, 4, 6, 8)
                }
                IconKind.FIT -> {
                    g.drawLine(1, 5, 1, 1)
                    g.drawLine(1, 1, 5, 1)
                    g.drawLine(11, 1, 15, 1)
                    g.drawLine(15, 1, 15, 5)
                    g.drawLine(1, 11, 1, 15)
                    g.drawLine(1, 15, 5, 15)
                    g.drawLine(11, 15, 15, 15)
                    g.drawLine(15, 11, 15, 15)
                }
                IconKind.CLUSTERS -> {
                    g.drawRoundRect(1, 2, 14, 4, 2, 2)
                    g.drawRoundRect(1, 10, 14, 4, 2, 2)
                }
                IconKind.ARRANGE -> {
                    g.drawRoundRect(1, 2, 5, 4, 1, 1)
                    g.drawRoundRect(10, 2, 5, 4, 1, 1)
                    g.drawRoundRect(5, 10, 6, 4, 1, 1)
                    g.drawLine(8, 6, 8, 10)
                }
                IconKind.HIERARCHY -> {
                    g.drawLine(3, 2, 3, 13)
                    g.drawLine(3, 5, 8, 5)
                    g.drawLine(3, 12, 8, 12)
                    g.fillOval(9, 3, 4, 4)
                    g.fillOval(9, 10, 4, 4)
                }
                IconKind.MORE -> {
                    g.fillOval(2, 7, 3, 3)
                    g.fillOval(7, 7, 3, 3)
                    g.fillOval(12, 7, 3, 3)
                }
                IconKind.PREVIOUS, IconKind.NEXT -> {
                    val direction = if (kind == IconKind.PREVIOUS) -1 else 1
                    val center = 8
                    g.drawLine(center - 5 * direction, 8, center + 5 * direction, 8)
                    g.drawLine(center + 5 * direction, 8, center * 1 + direction, 4)
                    g.drawLine(center + 5 * direction, 8, center * 1 + direction, 12)
                }
            }
        } finally {
            g.dispose()
        }
    }
}
